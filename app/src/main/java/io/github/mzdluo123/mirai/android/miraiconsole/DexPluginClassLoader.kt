/*
 * Copyright 2019-2023 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/dev/LICENSE
 */
@file:Suppress("MemberVisibilityCanBePrivate", "INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:OptIn(ConsoleExperimentalApi::class)

package io.github.mzdluo123.mirai.android.miraiconsole


import android.os.Build
import dalvik.system.DexClassLoader
import net.mamoe.mirai.console.plugin.jvm.ExportManager
import net.mamoe.mirai.console.plugin.jvm.JvmPluginClasspath
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.util.ConsoleExperimentalApi
import net.mamoe.mirai.utils.*
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.graph.DependencyFilter
import java.io.File
import java.io.InputStream
import java.net.URL
import java.net.URLClassLoader
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/*
Class resolving:

|
`- Resolve standard classes: hard linked by console (@see AllDependenciesClassesHolder)
`- Resolve classes in shared libraries (Shared in all plugins)
|
|-===== SANDBOX =====
|
`- Resolve classes in plugin dependency shared libraries (Shared by depend-ed plugins)
`- Resolve classes in independent libraries (Can only be loaded by current plugin)
`- Resolve classes in current jar.
`- Resolve classes from other plugin jar
`- Resolve by AppClassLoader

 */

internal class DexPluginsLoadingCtx(
    val consoleClassLoader: ClassLoader, // plugin system -> mirai-console classloader WRAPPER
    val sharedLibrariesLoader: DynLibClassLoader,
    val pluginClassLoaders: MutableList<DexPluginClassLoaderN>,
    val downloader: DexPluginDependencyDownloader,
) {
    val sharedLibrariesDependencies = HashSet<String>()
    val sharedLibrariesFilter: DependencyFilter = DependencyFilter { node, _ ->
        return@DependencyFilter node.artifact.depId() !in sharedLibrariesDependencies
    }
}

internal open class DynamicClasspathClassLoader internal constructor(
    urls: Array<URL>, parent: ClassLoader?
) : URLClassLoader(urls, parent) {

    internal fun addLib(url: URL) {

        addURL(url)
    }

    internal fun addLib(file: File) {
        addURL(file.toURI().toURL())
    }

    companion object {
        init {
            ClassLoader.registerAsParallelCapable()
        }
    }
}

internal class LegacyCompatibilityLayerClassLoader private constructor(
    parent: ClassLoader?
) : DynamicClasspathClassLoader(arrayOf(), parent) {

    override fun toString(): String {
        return "LegacyCompatibilityLayerClassLoader@" + hashCode()
    }

    companion object {
        init {
            ClassLoader.registerAsParallelCapable()
        }

        fun newInstance(parent: ClassLoader?): LegacyCompatibilityLayerClassLoader {
            return LegacyCompatibilityLayerClassLoader(parent)
        }
    }
}


internal class DynLibClassLoader private constructor(
    parent: ClassLoader?,
    private val clName: String?
) : DynamicClasspathClassLoader(arrayOf(), parent) {

    internal var dependencies: List<DynLibClassLoader> = emptyList()

    companion object {
        fun newInstance(parent: ClassLoader?, clName: String?): DynLibClassLoader {
            return DynLibClassLoader(parent, clName)

        }

        fun tryFastOrStrictResolve(name: String): Class<*>? {
            if (name.startsWith("java.")) return Class.forName(name, false, JavaSystemPlatformClassLoader)

            // All mirai-core hard-linked should use same version to avoid errors (ClassCastException).
            if (name in net.mamoe.mirai.console.internal.plugin.AllDependenciesClassesHolder.allclasses) {
                return net.mamoe.mirai.console.internal.plugin.AllDependenciesClassesHolder.appClassLoader.loadClass(name)
            }
            if (
                name.startsWith("net.mamoe.mirai.")
                || name.startsWith("kotlin.")
                || name.startsWith("kotlinx.")
                || name.startsWith("org.slf4j.")
            ) { // Avoid plugin classing cheating
                try {
                    return net.mamoe.mirai.console.internal.plugin.AllDependenciesClassesHolder.appClassLoader.loadClass(name)
                } catch (ignored: ClassNotFoundException) {
                }
            }
            try {
                return Class.forName(name, false, JavaSystemPlatformClassLoader)
            } catch (ignored: ClassNotFoundException) {
            }
            return null
        }

        fun tryFastOrStrictResolveResources(name: String): Enumeration<URL> {
            if (name.startsWith("java/")) return JavaSystemPlatformClassLoader.getResources(name)

            // All mirai-core hard-linked should use same version to avoid errors (ClassCastException).
            val fromDependencies = net.mamoe.mirai.console.internal.plugin.AllDependenciesClassesHolder.appClassLoader.getResources(name)

            return if (
                name.startsWith("net/mamoe/mirai/")
                || name.startsWith("kotlin/")
                || name.startsWith("kotlinx/")
                || name.startsWith("org/slf4j/")
            ) { // Avoid plugin classing cheating
                fromDependencies
            } else {
                LinkedHashSet<URL>().apply {
                    addAll(fromDependencies)
                    addAll(JavaSystemPlatformClassLoader.getResources(name))
                }.let {
                    Collections.enumeration(it)
                }
            }
        }
    }

    internal fun loadClassInThisClassLoader(name: String): Class<*>? {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            try {
                return findClass(name)
            } catch (ignored: ClassNotFoundException) {
            }
        }
        return null
    }

    override fun toString(): String {
        clName?.let { return "DynLibClassLoader{$it}" }
        return "DynLibClassLoader@" + hashCode()
    }

    override fun getResource(name: String?): URL? {
        if (name == null) return null
        findResource(name)?.let { return it }
        if (parent is DynLibClassLoader) {
            return parent.getResource(name)
        }
        return null
    }

    override fun getResources(name: String?): Enumeration<URL> {
        if (name == null) return Collections.emptyEnumeration()
        val res = findResources(name)
        return if (parent is DynLibClassLoader) {
            res + parent.getResources(name)
        } else {
            res
        }
    }

    internal fun findButNoSystem(name: String): Class<*>? = findButNoSystem(name, mutableListOf())
    private fun findButNoSystem(name: String, track: MutableList<DynLibClassLoader>): Class<*>? {
        if (name.startsWith("java.")) return null

        // Skip duplicated searching, for faster speed.
        if (this in track) return null
        track.add(this)

        val pt = this.parent
        if (pt is DynLibClassLoader) {
            pt.findButNoSystem(name, track)?.let { return it }
        }
        dependencies.forEach { dep ->
            dep.findButNoSystem(name, track)?.let { return it }
        }

        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            try {
                findClass(name)?.let { return it }
            } catch (ignored: ClassNotFoundException) {
            }
        }
        return null
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        tryFastOrStrictResolve(name)?.let { return it }

        findButNoSystem(name)?.let { return it }

        val topParent = generateSequence<ClassLoader>(this) { it.parent }.firstOrNull { it !is DynLibClassLoader }
        return Class.forName(name, false, topParent)
    }
}

internal class DexPluginClassLoaderN @Suppress("UNUSED_PARAMETER") private constructor(
    val file: File,
    odexPath: String,
    val ctx: DexPluginsLoadingCtx,
    val sharedLibrariesLogger: DynLibClassLoader,
    val pluginSharedCL: DynLibClassLoader,
    val pluginIndependentCL: DynLibClassLoader,
    val pluginMainPackages: MutableSet<String>,
    unused: Unit
) : DexClassLoader(
    file.path, odexPath, file.path, ctx.sharedLibrariesLoader
) {
    var pluginDescriptionFromPluginResource: JvmPluginDescription? = null

    val openaccess: JvmPluginClasspath = OpenAccess()

    val dependencies: MutableCollection<DexPluginClassLoaderN> = hashSetOf()

    @Suppress("PrivatePropertyName")
    private val file_: File
        get() = file

    var linkedLogger by lateinitMutableProperty {
        MiraiLogger.Factory.create(
            DexPluginClassLoaderN::class,
            "JvmPlugin[" + file_.name + "]"
        )
    }
    val undefinedDependencies = mutableSetOf<String>()

    internal var declaredFilter: ExportManager? = null

    val sharedClLoadedDependencies = mutableSetOf<String>()
    val privateClLoadedDependencies = mutableSetOf<String>()
    internal fun containsSharedDependency(
        dependency: String
    ): Boolean {
        if (dependency in sharedClLoadedDependencies) return true
        return dependencies.any { it.containsSharedDependency(dependency) }
    }

    internal fun linkPluginSharedLibraries(logger: MiraiLogger, dependencies: Collection<String>) {

        linkLibraries(logger, dependencies, true)
    }

    internal fun linkPluginPrivateLibraries(logger: MiraiLogger, dependencies: Collection<String>) {
        linkLibraries(logger, dependencies, false)
    }

    private val isPluginLibrariesLinked = AtomicBoolean(false)

    fun linkPluginLibraries(logger: MiraiLogger) {
        if (!isPluginLibrariesLinked.compareAndSet(false, true)) return

        // Link jar dependencies
        fun InputStream?.readDependencies(): Collection<String> {
            if (this == null) return emptyList()
            return bufferedReader().useLines { lines ->
                lines.filterNot { it.isBlank() }
                    .filterNot { it.startsWith('#') }
                    .map { it.trim() }
                    .toMutableList()
            }
        }
        linkPluginSharedLibraries(
            logger,
            getResourceAsStream("META-INF/mirai-console-plugin/dependencies-shared.txt").readDependencies()
        )
        linkPluginPrivateLibraries(
            logger,
            getResourceAsStream("META-INF/mirai-console-plugin/dependencies-private.txt").readDependencies()
        )
    }

    private fun linkLibraries(logger: MiraiLogger, dependencies: Collection<String>, shared: Boolean) {
        if (dependencies.isEmpty()) return
        val results = ctx.downloader.resolveDependencies(
            dependencies, ctx.sharedLibrariesFilter,
            DependencyFilter filter@{ node, _ ->
                val depid = node.artifact.depId()
                if (containsSharedDependency(depid)) return@filter false
                if (depid in privateClLoadedDependencies) return@filter false
                return@filter true
            })
        val files = results.artifactResults.mapNotNull { result ->
            result.artifact?.let { it to it.file }
        }
        val linkType = if (shared) "(shared)" else "(private)"
        files.forEach { (artifact, lib) ->
            logger.verbose { "Linking $lib $linkType" }
            if (shared) {
                pluginSharedCL.addLib(lib)
                sharedClLoadedDependencies.add(artifact.depId())
            } else {
                pluginIndependentCL.addLib(lib)
                privateClLoadedDependencies.add(artifact.depId())
            }
            logger.debug { "Linked $artifact $linkType <${if (shared) pluginSharedCL else pluginIndependentCL}>" }
        }
    }

    companion object {
        init {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ClassLoader.registerAsParallelCapable()
            }
        }

        fun newLoader(file: File, odexPath: String, ctx: DexPluginsLoadingCtx): DexPluginClassLoaderN {
            try {
                val pluginMainPackages: MutableSet<String> = HashSet()

                var shouldResolveConsoleSystemResource = false
                var shouldBeResolvableToIndependent = true
                var shouldResolveIndependent = true
                ZipFile(file).use { zipFile ->
                    zipFile.entries().asSequence()
                        .filter { it.name.endsWith(".class") }
                        .map { it.name.substringBeforeLast('.') }
                        .map { it.removePrefix("/").replace('/', '.') }
                        .map { it.substringBeforeLast('.') }
                        .forEach { pkg ->
                            pluginMainPackages.add(pkg)
                        }

                    zipFile.getEntry("META-INF/mirai-console-plugin/options.properties")?.let { optionsEntry ->
                        runCatching {
                            val options = Properties()
                            zipFile.getInputStream(optionsEntry).bufferedReader().use { reader ->
                                options.load(reader)
                            }

                            shouldBeResolvableToIndependent = options.prop(
                                "class.loading.be-resolvable-to-independent", "true"
                            ) { it.toBooleanStrict() }

                            shouldResolveIndependent = options.prop(
                                "class.loading.resolve-independent", "true"
                            ) { it.toBooleanStrict() }

                            shouldResolveConsoleSystemResource = options.prop(
                                "resources.resolve-console-system-resources", "false"
                            ) { it.toBooleanStrict() }

                        }.onFailure { err ->
                            throw IllegalStateException(
                                "Exception while reading META-INF/mirai-console-plugin/options.properties",
                                err
                            )
                        }
                    }
                }
                val pluginSharedCL = DynLibClassLoader.newInstance(
                    ctx.sharedLibrariesLoader, "SharedCL{${file.name}}"
                )
                val pluginIndependentCL = DynLibClassLoader.newInstance(
                    pluginSharedCL, "IndependentCL{${file.name}}"
                )
                pluginSharedCL.dependencies = mutableListOf()

                return DexPluginClassLoaderN(file, odexPath, ctx, ctx.sharedLibrariesLoader,pluginSharedCL, pluginIndependentCL, pluginMainPackages, Unit).apply {
                    openaccess.also {
                        it.shouldResolveIndependent = shouldResolveIndependent
                        it.shouldBeResolvableToIndependent = shouldBeResolvableToIndependent
                        it.shouldResolveConsoleSystemResource = shouldResolveConsoleSystemResource
                    }
                }
            } catch (e: Throwable) {
                e.addSuppressed(RuntimeException("Failed to initialize new JvmPluginClassLoader, file=$file"))
                throw e
            }

        }
    }

    internal fun resolvePluginSharedLibAndPluginClass(name: String): Class<*>? {
        return try {
            pluginSharedCL.findButNoSystem(name)
        } catch (e: ClassNotFoundException) {
            null
        } ?: resolvePluginPublicClass(name)
    }

    internal fun resolvePluginPublicClass(name: String): Class<*>? {
        if (pluginMainPackages.contains(name.pkgName())) {
            if (declaredFilter?.isExported(name) == false) return null
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                try {
                    return super.findClass(name)
                } catch (ignored: ClassNotFoundException) {
                }
            }
        }
        return null
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> = loadClass(name)

    override fun loadClass(name: String): Class<*> {
        DynLibClassLoader.tryFastOrStrictResolve(name)?.let { return it }

        sharedLibrariesLogger.loadClassInThisClassLoader(name)?.let { return it }

        // Search dependencies first
        dependencies.forEach { dependency ->
            dependency.resolvePluginSharedLibAndPluginClass(name)?.let { return it }
        }
        // Search in independent class loader
        // @context: pluginIndependentCL.parent = pluinSharedCL
        try {
            pluginIndependentCL.findButNoSystem(name)?.let { return it }
        } catch (ignored: ClassNotFoundException) {
        }

        try {
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                return super.findClass(name)
            }
        } catch (error: ClassNotFoundException) {
            if (!openaccess.shouldResolveIndependent) {
                return ctx.consoleClassLoader.loadClass(name)
            }

            // Finally, try search from other plugins and console system
            ctx.pluginClassLoaders.forEach { other ->
                if (other !== this && other !in dependencies) {

                    if (!other.openaccess.shouldBeResolvableToIndependent)
                        return@forEach

                    other.resolvePluginPublicClass(name)?.let {
                        if (undefinedDependencies.add(other.file.name)) {
                            linkedLogger.warning { "Linked class $name in ${other.file.name} but plugin not depend on it." }
                            linkedLogger.warning { "Class loading logic may change in feature." }
                        }
                        return it
                    }
                }
            }
            return ctx.consoleClassLoader.loadClass(name)
        }
    }

    internal fun loadedClass(name: String): Class<*>? = super.findLoadedClass(name)

    private fun getRes(name: String, shared: Boolean): Enumeration<URL> {
        val src = mutableListOf<Enumeration<URL>>(
            findResources(name),
        )
        if (dependencies.isEmpty()) {
            if (shared) {
                src.add(sharedLibrariesLogger.getResources(name))
            }
        } else {
            dependencies.forEach { dep ->
                src.add(dep.getRes(name, false))
            }
        }
        src.add(pluginIndependentCL.getResources(name))

        val resolved = LinkedHashSet<URL>()

        if (openaccess.shouldResolveConsoleSystemResource) {
            DynLibClassLoader.tryFastOrStrictResolveResources(name).let { resolved.addAll(it) }
        }
        src.forEach { nested -> resolved.addAll(nested) }

        return Collections.enumeration(resolved)
    }

    override fun getResources(name: String?): Enumeration<URL> {
        name ?: return Collections.emptyEnumeration()

        if (name.startsWith("META-INF/mirai-console-plugin/"))
            return findResources(name)
        // Avoid loading duplicated mirai-console plugins
        if (name.startsWith("META-INF/services/net.mamoe.mirai.console.plugin."))
            return findResources(name)

        return getRes(name, true)
    }

    override fun getResource(name: String?): URL? {
        name ?: return null
        if (name.startsWith("META-INF/mirai-console-plugin/"))
            return findResource(name)
        // Avoid loading duplicated mirai-console plugins
        if (name.startsWith("META-INF/services/net.mamoe.mirai.console.plugin."))
            return findResource(name)

        if (openaccess.shouldResolveConsoleSystemResource) {
            DynLibClassLoader.tryFastOrStrictResolveResources(name)
                .takeIf { it.hasMoreElements() }
                ?.let { return it.nextElement() }
        }

        findResource(name)?.let { return it }
        // parent: ctx.sharedLibrariesLoader
        sharedLibrariesLogger.getResource(name)?.let { return it }
        dependencies.forEach { dep ->
            dep.getResource(name)?.let { return it }
        }
        return pluginIndependentCL.getResource(name)
    }

    override fun toString(): String {
        return "JvmPluginClassLoader{${file.name}}"
    }

    inner class OpenAccess : JvmPluginClasspath {
        override val pluginFile: File
            get() = this@DexPluginClassLoaderN.file

        override val pluginClassLoader: ClassLoader
            get() = this@DexPluginClassLoaderN

        override val pluginSharedLibrariesClassLoader: ClassLoader
            get() = pluginSharedCL
        override val pluginIndependentLibrariesClassLoader: ClassLoader
            get() = pluginIndependentCL

        override var shouldResolveConsoleSystemResource: Boolean = false
        override var shouldBeResolvableToIndependent: Boolean = true
        override var shouldResolveIndependent: Boolean = true

        private val permitted by lazy {
            arrayOf(
                this@DexPluginClassLoaderN,
                pluginSharedCL,
                pluginIndependentCL,
            )
        }

        override fun addToPath(classLoader: ClassLoader, file: File) {
            if (classLoader !in permitted) {
                throw IllegalArgumentException("Unsupported classloader or cross plugin accessing: $classLoader")
            }
            if (classLoader == this@DexPluginClassLoaderN) {
                //this@DexPluginClassLoaderN.addURL(file.toURI().toURL())
                ctx.sharedLibrariesLoader.addLib(file)
                return
            }
            classLoader as DynLibClassLoader
            classLoader.addLib(file)
        }

        override fun downloadAndAddToPath(classLoader: ClassLoader, dependencies: Collection<String>) {
            if (classLoader !in permitted) {
                throw IllegalArgumentException("Unsupported classloader or cross plugin accessing: $classLoader")
            }
            if (classLoader === this@DexPluginClassLoaderN) {
                throw IllegalArgumentException("Only support download dependencies to `plugin[Shared/Independent]LibrariesClassLoader`")
            }
            this@DexPluginClassLoaderN.linkLibraries(
                linkedLogger, dependencies, classLoader === pluginSharedCL
            )
        }
    }

}

private val loadingLock = java.util.concurrent.ConcurrentHashMap<String, Any>()

private fun getClassLoadingLock(name: String): Any {
    val lock = Any()
    return loadingLock.putIfAbsent(name, lock) ?: lock
}

private val JavaSystemPlatformClassLoader: ClassLoader by lazy {
    kotlin.runCatching {
        ClassLoader::class.java.methods.asSequence().filter {
            it.name == "getPlatformClassLoader"
        }.filter {
            java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.firstOrNull()?.invoke(null) as ClassLoader?
    }.getOrNull() ?: ClassLoader.getSystemClassLoader().parent
}

private fun String.pkgName(): String = substringBeforeLast('.', "")
internal fun Artifact.depId(): String = "$groupId:$artifactId"

private operator fun <E> Enumeration<E>.plus(next: Enumeration<E>): Enumeration<E> {
    return compoundEnumerations(listOf(this, next).iterator())
}

private fun <E> compoundEnumerations(iter: Iterator<Enumeration<E>>): Enumeration<E> {
    return object : Enumeration<E> {
        private lateinit var crt: Enumeration<E>

        private var hasMore: Boolean = false
        private var fetched: Boolean = false

        override tailrec fun hasMoreElements(): Boolean {
            if (fetched) return hasMore
            if (::crt.isInitialized) {
                hasMore = crt.hasMoreElements()
                if (hasMore) {
                    fetched = true
                    return true
                }
            }
            if (!iter.hasNext()) {
                fetched = true
                hasMore = false
                return false
            }
            crt = iter.next()
            return hasMoreElements()
        }

        override fun nextElement(): E {
            if (hasMoreElements()) {
                return crt.nextElement().also {
                    fetched = false
                }
            }
            throw NoSuchElementException()
        }
    }
}

private fun Properties.prop(key: String, def: String): String {
    try {
        return getProperty(key, def)
    } catch (err: Throwable) {
        throw IllegalStateException("Exception while reading `$key`", err)
    }
}

private inline fun <T> Properties.prop(key: String, def: String, dec: (String) -> T): T {
    try {
        return getProperty(key, def).let(dec)
    } catch (err: Throwable) {
        throw IllegalStateException("Exception while reading `$key`", err)
    }
}