package com.example.hook

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.view.Surface
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

class XposedCameraHook : IXposedHookLoadPackage {

    private val tag = "VCamProHook"
    private var activeMediaPlayer: MediaPlayer? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Skip hooking system frameworks to avoid system crash or instability
        if (lpparam.packageName == "android" || lpparam.packageName == "com.android.systemui") {
            return
        }

        // We target typical applications like TikTok, Shopee, Instagram, etc.
        // We log the hook injection event
        XposedBridge.log("[$tag] Injecting camera hook into: ${lpparam.packageName}")

        if (lpparam.packageName == "com.aistudio.vcampro.hkxjdn") {
            try {
                val mainActivityClass = XposedHelpers.findClass("com.example.MainActivity", lpparam.classLoader)
                XposedHelpers.findAndHookMethod(mainActivityClass, "isModuleActive", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                })
                XposedBridge.log("[$tag] Self-hook activated: isModuleActive will return true.")
            } catch (e: Exception) {
                XposedBridge.log("[$tag] Failed to apply self-hook: ${e.message}")
            }
            return
        }

        hookCamera2API(lpparam)
    }

    private fun hookCamera2API(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook CameraDeviceImpl's session creation methods
            // These are the core methods that receive the target surfaces from the app.
            val cameraDeviceImplClass = XposedHelpers.findClass(
                "android.hardware.camera2.impl.CameraDeviceImpl",
                lpparam.classLoader
            )

            XposedBridge.log("[$tag] Found CameraDeviceImpl class. Attempting method hook.")

            // Overload 1: Typical Camera2 session creation
            XposedHelpers.findAndHookMethod(
                cameraDeviceImplClass,
                "createCaptureSession",
                List::class.java,
                CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val outputs = param.args[0] as? List<*> ?: return
                        XposedBridge.log("[$tag] Intercepted createCaptureSession! Surface Count: ${outputs.size}")

                        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                        if (context == null) {
                            XposedBridge.log("[$tag] Context not available in CameraDeviceImpl.")
                            return
                        }

                        // Play virtual video onto captured surfaces
                        injectVideoIntoSurfaces(context, outputs)
                    }
                }
            )

            // Support for newer Android versions (Android 10 - 14) which use createCaptureSessionByConfigurations
            XposedHelpers.findAndHookMethod(
                cameraDeviceImplClass,
                "createCaptureSessionByConfigurations",
                List::class.java,
                CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val configs = param.args[0] as? List<*> ?: return
                        XposedBridge.log("[$tag] Intercepted createCaptureSessionByConfigurations! Configs: ${configs.size}")

                        val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context ?: return
                        val surfaces = ArrayList<Surface>()
                        
                        for (config in configs) {
                            val surfaceList = XposedHelpers.callMethod(config, "getSurfaces") as? List<*>
                            if (surfaceList != null) {
                                for (s in surfaceList) {
                                    if (s is Surface) {
                                        surfaces.add(s)
                                    }
                                }
                            }
                        }

                        injectVideoIntoSurfaces(context, surfaces)
                    }
                }
            )

        } catch (e: Exception) {
            XposedBridge.log("[$tag] Error hooking Camera2 API: ${e.message}")
        }
    }

    private fun injectVideoIntoSurfaces(context: Context, surfaces: List<*>) {
        if (surfaces.isEmpty()) return

        // Retrieve config from our app's public workspace
        val configData = readConfig(context)
        val videoPath = configData.first
        val isLoop = configData.second

        if (videoPath == null || !File(videoPath).exists()) {
            XposedBridge.log("[$tag] Active virtual video file not configured or does not exist: $videoPath")
            return
        }

        XposedBridge.log("[$tag] Success: Injecting video file: $videoPath onto output surfaces.")

        // Release old media player
        activeMediaPlayer?.stop()
        activeMediaPlayer?.release()
        activeMediaPlayer = null

        // Set up MediaPlayer to render onto intercepted surfaces in a background thread
        try {
            val mediaPlayer = MediaPlayer().apply {
                setDataSource(videoPath)
                isLooping = isLoop
                setVolume(0f, 0f) // Keep muted for camera hook feed to avoid echo
            }

            // Bind first target surface
            val firstSurface = surfaces[0] as? Surface
            if (firstSurface != null && firstSurface.isValid) {
                mediaPlayer.setSurface(firstSurface)
                mediaPlayer.prepareAsync()
                mediaPlayer.setOnPreparedListener { mp ->
                    mp.start()
                    XposedBridge.log("[$tag] MediaPlayer started streaming virtual camera feed.")
                }
                activeMediaPlayer = mediaPlayer
            }
        } catch (e: Exception) {
            XposedBridge.log("[$tag] Failed to inject MediaPlayer streaming: ${e.message}")
        }
    }

    /**
     * Reads virtual camera parameters from the shared app configuration file.
     * Returns a Pair of (VideoPath, IsLooping)
     */
    private fun readConfig(context: Context): Pair<String?, Boolean> {
        try {
            // Locate VCam config file in public workspace directory
            // This is external storage standard accessible under Xposed hooks
            val targetDir = File("/sdcard/Android/data/com.aistudio.vcampro.hkxjdn/files")
            val configFile = File(targetDir, "vcam_config.txt")
            
            if (configFile.exists()) {
                val lines = configFile.readLines()
                if (lines.isNotEmpty()) {
                    val path = lines[0].trim()
                    val loop = lines.getOrNull(1)?.trim()?.toBoolean() ?: true
                    return Pair(path, loop)
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("[$tag] Failed to read config file: ${e.message}")
        }
        return Pair(null, true)
    }
}
