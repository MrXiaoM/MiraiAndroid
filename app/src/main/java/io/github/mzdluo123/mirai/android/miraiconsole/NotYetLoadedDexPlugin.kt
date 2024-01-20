@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package io.github.mzdluo123.mirai.android.miraiconsole

import net.mamoe.mirai.console.data.runCatchingLog
import net.mamoe.mirai.console.permission.Permission
import net.mamoe.mirai.console.permission.PermissionId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.plugin.NotYetLoadedPlugin
import net.mamoe.mirai.console.plugin.jvm.JvmPlugin
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.util.ConsoleExperimentalApi
import net.mamoe.mirai.utils.MiraiLogger
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext

internal abstract class NotYetLoadedDexPlugin(
    override val description: JvmPluginDescription,
    val classLoaderN: DexPluginClassLoaderN,
) : JvmPlugin, NotYetLoadedPlugin<JvmPlugin> {
    abstract override fun resolve(): JvmPlugin

    override val logger: MiraiLogger by lazy {
        MiraiAndroidLogger.runCatchingLog {
            MiraiLogger.Factory.create(NotYetLoadedDexPlugin::class, this.description.name)
        }.getOrThrow()
    }

    override val isEnabled: Boolean get() = false
    override val parentPermission: Permission
        get() = error("Not yet loaded")

    @OptIn(ConsoleExperimentalApi::class)
    override fun permissionId(name: String): PermissionId {
        return PermissionService.INSTANCE.allocatePermissionIdForPlugin(this, name)
    }

    override val coroutineContext: CoroutineContext
        get() = error("Not yet loaded")
    override val dataFolderPath: Path
        get() = error("Not yet loaded")
    override val dataFolder: File
        get() = error("Not yet loaded")
    override val configFolderPath: Path
        get() = error("Not yet loaded")
    override val configFolder: File
        get() = error("Not yet loaded")

    override fun getResourceAsStream(path: String): InputStream? {
        return classLoaderN.getResourceAsStream(path)
    }
}