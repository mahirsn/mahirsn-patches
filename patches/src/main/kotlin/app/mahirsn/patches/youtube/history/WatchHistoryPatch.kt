package app.mahirsn.patches.youtube.history

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.AccessFlags

private const val EXTENSION_CLASS = "Lapp/mahirsn/extension/youtube/history/WatchHistory;"
private const val ADD_ON_MANAGER_CLASS = "Lapp/morphe/extension/youtube/addon/AddOnManager;"

/**
 * Methods of Morphe Patches the extension calls at runtime. Checked while patching, so an
 * incompatible Morphe Patches version fails here with a clear message instead of on the device.
 */
private val REQUIRED_HOST_METHODS = mapOf(
    "Lapp/morphe/extension/youtube/addon/AddOnApi;" to setOf(
        "addVideoIdListener(Ljava/util/function/Consumer;)V",
        "addVideoTimeListener(Ljava/util/function/LongConsumer;)V",
        "addVideoStateListener(Ljava/util/function/Consumer;)V",
    ),
    "Lapp/morphe/extension/youtube/patches/VideoInformation;" to setOf(
        "getVideoTitle()Ljava/lang/String;",
        "getChannelName()Ljava/lang/String;",
        "getVideoLength()J",
        "lastVideoIdIsShort()Z",
        "seekTo(J)Z",
    ),
)

/** Supported versions are whatever the Morphe Patches bundle used alongside supports. */
private val COMPATIBILITY_YOUTUBE = Compatibility(
    packageName = "com.google.android.youtube",
    name = "YouTube",
    apkFileType = ApkFileType.APK_REQUIRED,
    appIconColor = 0xFF0033,
)

private fun BytecodePatchContext.checkHost() {
    val missing = REQUIRED_HOST_METHODS.flatMap { (type, methods) ->
        val host = classDefByOrNull(type) ?: return@flatMap listOf(type)
        methods.filter { wanted ->
            host.methods.none {
                it.name + "(" + it.parameters.joinToString("") + ")" + it.returnType == wanted &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
            }
        }
    }
    if (missing.isNotEmpty()) throw PatchException(
        "This version of Morphe Patches is not compatible with Watch history. Missing: " +
            missing.joinToString()
    )
}

private fun String.smali() = replace("\\", "\\\\").replace("\"", "\\\"")

@Suppress("unused")
val watchHistoryPatch = bytecodePatch(
    name = "Watch history on your server",
    description = "Keeps the watch history and resume positions on your own server instead of Google's, " +
        "so YouTube's history can stay off. Requires Morphe official patches and a yt-history server.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    extendWith("extensions/extension.mpe")

    val serverUrl by stringOption(
        key = "serverUrl",
        default = "",
        title = "Server URL",
        description = "Base URL of the history API, for example https://example.com/yt/api",
        required = true,
    )
    val token by stringOption(
        key = "token",
        default = "",
        title = "Token",
        description = "Sent to the server in the X-Token header.",
        required = true,
    )

    execute {
        val url = serverUrl!!.trim().trimEnd('/')
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw PatchException("Server URL must start with https:// or http://")
        }
        val extension = mutableClassDefBy(EXTENSION_CLASS)
        mapOf("serverUrl" to url, "token" to token!!.trim()).forEach { (name, value) ->
            extension.methods.first { it.name == name }.addInstructions(
                0,
                """
                    const-string v0, "${value.smali()}"
                    return-object v0
                """,
            )
        }
    }

    finalize {
        // The extension of Morphe Patches is merged while its patches execute, so it is
        // only certain to be there now.
        val manager = mutableClassDefByOrNull(ADD_ON_MANAGER_CLASS) ?: throw PatchException(
            "Watch history needs Morphe official patches. Patch together with the Morphe bundle."
        )
        checkHost()
        manager.methods.first {
            it.name == "registerAddOns" && it.parameters.isEmpty() && it.returnType == "V"
        }.addInstruction(0, "invoke-static { }, $EXTENSION_CLASS->register()V")
    }
}
