@file:Suppress(
    "EXPERIMENTAL_API_USAGE",
    "DEPRECATION_ERROR",
    "OverridingDeprecatedMember",
    "INVISIBLE_REFERENCE",
    "INVISIBLE_MEMBER"
)
@file:OptIn(ConsoleFrontEndImplementation::class, ConsoleExperimentalApi::class, MiraiInternalApi::class)

package io.github.mzdluo123.mirai.android.miraiconsole

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import net.mamoe.mirai.console.ConsoleFrontEndImplementation
import net.mamoe.mirai.console.MiraiConsole
import net.mamoe.mirai.console.data.PluginDataStorage
import net.mamoe.mirai.console.internal.util.PluginServiceHelper.findServices
import net.mamoe.mirai.console.internal.util.PluginServiceHelper.loadAllServices
import net.mamoe.mirai.console.plugin.jvm.*
import net.mamoe.mirai.console.internal.plugin.*
import net.mamoe.mirai.console.plugin.loader.AbstractFilePluginLoader
import net.mamoe.mirai.console.plugin.loader.PluginLoadException
import net.mamoe.mirai.console.plugin.name
import net.mamoe.mirai.utils.MiraiInternalApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import net.mamoe.mirai.console.internal.data.MultiFilePluginDataStorageImpl
import net.mamoe.mirai.console.plugin.PluginManager
import net.mamoe.mirai.console.plugin.dependencies
import net.mamoe.mirai.console.util.ConsoleExperimentalApi
import net.mamoe.mirai.utils.childScope
import net.mamoe.mirai.utils.debug
import net.mamoe.mirai.utils.verbose
import net.mamoe.yamlkt.Yaml
import java.nio.file.Path
import net.mamoe.mirai.console.plugin.id
import net.mamoe.mirai.utils.*

internal class DexPluginLoader(
    val odexPath: String,
    private val workingDir: Path,
) : AbstractFilePluginLoader<JvmPlugin, JvmPluginDescription>(".jar"),
    CoroutineScope by MiraiConsole.childScope(
        "DexPluginLoader",
        CoroutineExceptionHandler { _, throwable ->
            MiraiAndroidLogger.error(
                "Unhandled Jar plugin exception: ${throwable.message}",
                throwable
            )
        }),
    JvmPluginLoader {

    private fun pluginsFilesSequence(
        files: Sequence<File> = PluginManager.pluginsFolder.listFiles().orEmpty().asSequence()
    ): Sequence<File> {
        val raw = files
            .filter { it.isFile && it.name.endsWith(fileSuffix, ignoreCase = true) }
            .toMutableList()

        val mirai2List = raw.filter { it.name.endsWith(".mirai2.jar", ignoreCase = true) }
        for (mirai2Plugin in mirai2List) {
            val name = mirai2Plugin.name.substringBeforeLast('.').substringBeforeLast('.') // without ext.
            raw.removeAll {
                it !== mirai2Plugin && it.name.substringBeforeLast('.').substringBeforeLast('.') == name
            } // remove those with .mirai.jar
        }

        return raw.asSequence()
    }

    override fun listPlugins(): List<JvmPlugin> {
        return pluginsFilesSequence().extractPlugins()
    }
    
    override val configStorage: PluginDataStorage
        get() = MultiFilePluginDataStorageImpl(workingDir.resolve("config"))

    override val dataStorage: PluginDataStorage
        get() = MultiFilePluginDataStorageImpl(workingDir.resolve("data"))


    private val dexPluginLoadingCtx: DexPluginsLoadingCtx by lazy {
        val legacyCompatibilityLayerClassLoader = LegacyCompatibilityLayerClassLoader.newInstance(
            DexPluginLoader::class.java.classLoader,
        )

        val classLoader = DynLibClassLoader.newInstance(
            legacyCompatibilityLayerClassLoader, "GlobalShared"
        )
        val ctx = DexPluginsLoadingCtx(
            legacyCompatibilityLayerClassLoader,
            classLoader,
            mutableListOf(),
            DexPluginDependencyDownloader(MiraiAndroidLogger),
        )
        MiraiAndroidLogger.debug("Downloading legacy compatibility modules.....")
        ctx.downloader.resolveDependencies(
            sequenceOf(
                "client-core",
                "client-core-jvm",
                "client-okhttp",
                "utils",
                "utils-jvm",
            ).map { "io.ktor:ktor-$it:1.6.8" }.asIterable()
        ).let { rsp ->
            rsp.artifactResults.forEach {
                legacyCompatibilityLayerClassLoader.addLib(it.artifact.file)
            }
            if (MiraiAndroidLogger.isVerboseEnabled) {
                MiraiAndroidLogger.verbose("Legacy compatibility modules:")
                rsp.artifactResults.forEach { art ->
                    MiraiAndroidLogger.verbose(" `- ${art.artifact}  -> ${art.artifact.file}")
                }
            }
        }

        MiraiAndroidLogger.verbose("Plugin shared libraries: " + PluginManager.pluginSharedLibrariesFolder)
        PluginManager.pluginSharedLibrariesFolder.listFiles()?.asSequence().orEmpty()
            .onEach { MiraiAndroidLogger.debug("Peek $it in shared libraries") }
            .filter { file ->
                if (file.isDirectory) {
                    return@filter true
                }
                if (!file.exists()) {
                    MiraiAndroidLogger.debug("Skipped $file because file not exists")
                    return@filter false
                }
                if (file.isFile) {
                    if (file.extension == "jar") {
                        return@filter true
                    }
                    MiraiAndroidLogger.debug("Skipped $file because extension <${file.extension}> != jar")
                    return@filter false
                }
                MiraiAndroidLogger.debug("Skipped $file because unknown error")
                return@filter false
            }
            .filter { it.isDirectory || (it.isFile && it.extension == "jar") }
            .forEach { pt ->
                classLoader.addLib(pt)
                MiraiAndroidLogger.debug("Linked static shared library: $pt")
            }
        val libraries = PluginManager.pluginSharedLibrariesFolder.resolve("libraries.txt")
        if (libraries.isFile) {
            MiraiAndroidLogger.verbose("Linking static shared libraries....")
            val libs = libraries.useLines { lines ->
                lines.filter { it.isNotBlank() }
                    .filterNot { it.startsWith("#") }
                    .onEach { MiraiAndroidLogger.verbose("static lib queued: $it") }
                    .toMutableList()
            }
            val staticLibs = ctx.downloader.resolveDependencies(libs)
            staticLibs.artifactResults.forEach { artifactResult ->
                if (artifactResult.isResolved) {
                    // TODO: 使用 d8 转 dex
                    ctx.sharedLibrariesLoader.addLib(artifactResult.artifact.file)
                    ctx.sharedLibrariesDependencies.add(artifactResult.artifact.depId())
                    MiraiAndroidLogger.debug("Linked static shared library: ${artifactResult.artifact}")
                    MiraiAndroidLogger.verbose("Linked static shared library: ${artifactResult.artifact.file}")
                }
            }
        } else {
            libraries.createNewFile()
        }
        ctx
    }

    override val classLoaders: MutableList<DexPluginClassLoaderN> get() = dexPluginLoadingCtx.pluginClassLoaders

    override fun findLoadedClass(name: String): Class<*>? {
        return classLoaders.firstNotNullOfOrNull { it.loadedClass(name) }
    }


    override fun getPluginDescription(plugin: JvmPlugin): JvmPluginDescription = plugin.description

    private val pluginFileToInstanceMap: MutableMap<File, JvmPlugin> = ConcurrentHashMap()

    override fun Sequence<File>.extractPlugins(): List<JvmPlugin> {
        ensureActive()

        fun Sequence<Map.Entry<File, DexPluginClassLoaderN>>.initialize(): Sequence<Map.Entry<File, DexPluginClassLoaderN>> {
            return onEach { (_, pluginClassLoader) ->
                val exportManagers = pluginClassLoader.findServices(
                    ExportManager::class
                ).loadAllServices()
                if (exportManagers.isEmpty()) {
                    val rules = pluginClassLoader.getResourceAsStream("export-rules.txt")
                    if (rules == null)
                        pluginClassLoader.declaredFilter = StandardExportManagers.AllExported
                    else rules.bufferedReader(Charsets.UTF_8).useLines {
                        pluginClassLoader.declaredFilter = ExportManagerImpl.parse(it.iterator())
                    }
                } else {
                    pluginClassLoader.declaredFilter = exportManagers[0]
                }
            }
        }

        fun Sequence<Map.Entry<File, DexPluginClassLoaderN>>.findAllInstances(): Sequence<Map.Entry<File, JvmPlugin>> {
            return map { (f, pluginClassLoader) ->
                f to pluginClassLoader.findServices(
                    JvmPlugin::class,
                    KotlinPlugin::class,
                    JavaPlugin::class
                ).loadAllServices().also { plugins ->
                    plugins.firstOrNull()?.logger?.let { pluginClassLoader.linkedLogger = it }
                }
            }.flatMap { (f, list) ->

                list.associateBy { f }.asSequence()
            }
        }

        fun Map.Entry<File, DexPluginClassLoaderN>.loadWithoutPluginDescription(): Sequence<Pair<File, JvmPlugin>> {
            return sequenceOf(this).initialize().findAllInstances().map { (k, v) -> k to v }
        }

        fun Map.Entry<File, DexPluginClassLoaderN>.loadWithPluginDescription(description: JvmPluginDescription): Sequence<Pair<File, JvmPlugin>> {
            val pluginClassLoader = this.value
            val pluginFile = this.key
            pluginClassLoader.pluginDescriptionFromPluginResource = description

            val pendingPlugin = object : NotYetLoadedDexPlugin(
                description = description,
                classLoaderN = pluginClassLoader,
            ) {
                private val plugin by lazy {
                    val services = pluginClassLoader.findServices(
                        JvmPlugin::class,
                        KotlinPlugin::class,
                        JavaPlugin::class
                    ).loadAllServices()
                    if (services.isEmpty()) {
                        error("No plugin instance found in $pluginFile")
                    }
                    if (services.size > 1) {
                        error(
                            "Only one plugin can exist at the same time when using plugin.yml:\n\nPlugins found:\n" + services.joinToString(
                                separator = "\n"
                            ) { it.javaClass.name + " (from " + it.javaClass.classLoader + ")" }
                        )
                    }

                    return@lazy services[0]
                }

                override fun resolve(): JvmPlugin = plugin
            }
            pluginClassLoader.linkedLogger = pendingPlugin.logger


            return sequenceOf(pluginFile to pendingPlugin)
        }

        val filePlugins = this.filterNot {
            pluginFileToInstanceMap.containsKey(it)
        }.associateWith {
            DexPluginClassLoaderN.newLoader(it, odexPath, dexPluginLoadingCtx)
        }.onEach { (_, classLoader) ->
            classLoaders.add(classLoader)
        }.asSequence().flatMap { entry ->
            val (file, pluginClassLoader) = entry

            val pluginDescriptionDefine = pluginClassLoader.getResourceAsStream("plugin.yml")
            if (pluginDescriptionDefine == null) {
                entry.loadWithoutPluginDescription()
            } else {
                val desc = kotlin.runCatching {
                    pluginDescriptionDefine.bufferedReader().use { resource ->
                        Yaml.decodeFromString(
                            SimpleJvmPluginDescription.SerialData.serializer(),
                            resource.readText()
                        ).toJvmPluginDescription()
                    }
                }.onFailure { err ->
                    throw PluginLoadException("Invalid plugin.yml in " + file.absolutePath, err)
                }.getOrThrow()

                entry.loadWithPluginDescription(desc)
            }
        }.onEach {
            MiraiAndroidLogger.verbose("Successfully initialized JvmPlugin ${it.second}.")
        }.onEach { (file, plugin) ->
            pluginFileToInstanceMap[file] = plugin
        }

        return filePlugins.toSet().map { it.second }
    }

    private val loadedPlugins = ConcurrentHashMap<String, JvmPlugin>()

    private fun Path.moveNameFolder(plugin: JvmPlugin) {
        val nameFolder = this.resolve(plugin.description.name).toFile()
        if (plugin.description.name != plugin.description.id && nameFolder.exists()) {
            // need move
            val idFolder = this.resolve(plugin.description.id).toFile()
            val moveDescription =
                "移动 ${plugin.description.smartToString()} 的数据文件目录(${nameFolder.path})到 ${idFolder.path}"
            if (idFolder.exists()) {
                if (idFolder.listFiles()?.size != 0) {
                    MiraiAndroidLogger.error("$moveDescription 失败, 原因:数据文件目录(${idFolder.path})被占用")
                    MiraiAndroidLogger.error("Mirai Console 将自动关闭, 请删除或移动该目录后再启动")
                    MiraiConsole.job.cancel()
                } else
                    idFolder.delete()
            }
            kotlin.runCatching {
                MiraiAndroidLogger.info(moveDescription)
                if (!nameFolder.renameTo(idFolder)) {
                    MiraiAndroidLogger.error("$moveDescription 失败")
                    MiraiAndroidLogger.error("Mirai Console 将自动关闭, 请手动移动该文件夹后再启动")
                    MiraiConsole.job.cancel()
                }
            }.onFailure {
                MiraiAndroidLogger.error("$moveDescription 失败, 原因:\n", it)
                MiraiAndroidLogger.error("Mirai Console 将自动关闭, 请解决该错误后再启动")
                MiraiConsole.job.cancel()
            }
            MiraiAndroidLogger.info("$moveDescription 完成")
        }
    }

    @Throws(PluginLoadException::class)
    override fun load(plugin: JvmPlugin) {
        ensureActive()

        if (loadedPlugins.put(plugin.id, plugin) != null) {
            error("Plugin '${plugin.id}' is already loaded and cannot be reloaded.")
        }
        MiraiAndroidLogger.verbose("Loading plugin ${plugin.description.smartToString()}")
        runCatching {
            // move nameFolder in config and data to idFolder
            PluginManager.pluginsDataPath.moveNameFolder(plugin)
            PluginManager.pluginsConfigPath.moveNameFolder(plugin)

            check(plugin is JvmPluginInternal || plugin is NotYetLoadedJvmPlugin) {
                "A JvmPlugin must extend AbstractJvmPlugin to be loaded by JvmPluginLoader.BuiltIn"
            }


            // region Link dependencies
            when (plugin) {
                is NotYetLoadedJvmPlugin -> plugin.classLoaderN
                else -> plugin.javaClass.classLoader
            }.safeCast<DexPluginClassLoaderN>()?.let { jvmPluginClassLoaderN ->
                // Link plugin dependencies
                plugin.description.dependencies.asSequence().mapNotNull { dependency ->
                    plugin.logger.verbose { "Linking dependency: ${dependency.id}" }
                    PluginManager.plugins.firstOrNull { it.id == dependency.id }
                }.mapNotNull { it.javaClass.classLoader.safeCast<DexPluginClassLoaderN>() }.forEach { dependency ->
                    plugin.logger.debug { "Linked  dependency: $dependency" }
                    jvmPluginClassLoaderN.dependencies.add(dependency)
                    jvmPluginClassLoaderN.pluginSharedCL.dependencies.cast<MutableList<DynLibClassLoader>>().add(
                        dependency.pluginSharedCL
                    )
                }
                jvmPluginClassLoaderN.linkPluginLibraries(plugin.logger)
            }

            val realPlugin = when (plugin) {
                is NotYetLoadedJvmPlugin -> plugin.resolve().also { realPlugin ->
                    check(plugin.description === realPlugin.description) {
                        "A JvmPlugin loaded by plugin.yml must has same description reference"
                    }
                }
                else -> plugin
            }

            check(realPlugin is JvmPluginInternal) { "A JvmPlugin must extend AbstractJvmPlugin to be loaded by JvmPluginLoader.BuiltIn" }
            // endregion
            realPlugin.internalOnLoad()
        }.getOrElse {
            throw PluginLoadException("Exception while loading ${plugin.description.smartToString()}", it)
        }
    }

    override fun enable(plugin: JvmPlugin) {
        if (plugin.isEnabled) error("Plugin '${plugin.name}' is already enabled and cannot be re-enabled.")
        ensureActive()
        runCatching {
            MiraiAndroidLogger.verbose { "Enabling plugin ${plugin.description.smartToString()}" }

            val loadedPlugins = PluginManager.plugins
            val failedDependencies = plugin.dependencies.asSequence().mapNotNull { dep ->
                loadedPlugins.firstOrNull { it.id == dep.id }
            }.filterNot { it.isEnabled }.toList()
            if (failedDependencies.isNotEmpty()) {
                MiraiAndroidLogger.error("Failed to enable '${plugin.name}' because dependencies not enabled: " + failedDependencies.joinToString { "'${it.name}'" })
                return
            }

            if (plugin is JvmPluginInternal) {
                plugin.internalOnEnable()
            } else plugin.onEnable()

            // Extra space for logging align
            MiraiAndroidLogger.verbose { "Enabled  plugin ${plugin.description.smartToString()}" }
        }.getOrElse {
            throw PluginLoadException("Exception while enabling ${plugin.description.name}", it)
        }
    }

    override fun disable(plugin: JvmPlugin) {
        if (!plugin.isEnabled) error("Plugin '${plugin.name}' is not already disabled and cannot be re-disabled.")

        if (MiraiConsole.isActive)
            ensureActive()

        if (plugin is JvmPluginInternal) {
            plugin.internalOnDisable()
        } else plugin.onDisable()
    }
}