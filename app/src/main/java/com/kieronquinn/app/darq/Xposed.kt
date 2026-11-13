package com.kieronquinn.app.darq

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.graphics.RenderNode
import android.os.Build
import android.os.Process
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowInsetsController
import com.kieronquinn.app.darq.components.settings.XposedSharedPreferences
import com.kieronquinn.app.darq.model.xposed.ForceDarkRendering
import com.kieronquinn.app.darq.model.xposed.XposedSettings
import com.kieronquinn.app.darq.utils.extensions.isDarkTheme
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class Xposed : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "DarQXposed"
        private const val SHARED_PREFS_FILENAME = "${BuildConfig.APPLICATION_ID}_prefs"
    }

    private val isDarkMode: Boolean
        get() = Resources.getSystem().configuration.isDarkTheme

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName == BuildConfig.APPLICATION_ID) {
            installSelfHooks(param.classLoader)
            return
        }
        if (!param.isFirstApplication || !Process.isApplicationUid(Process.myUid())) return

        val settings = readSettings(param.packageName) ?: return
        installViewHook()
        if (settings.aggressiveDark) {
            installRenderingHook()
        }
        if (settings.invertStatus) {
            installStatusBarHook()
        }
    }

    private fun installViewHook() {
        // View inflation can call RenderNode directly for android:forceDarkAllowed.
        hookIfAvailable(RenderNode::class.java.name, "setForceDarkAllowed", Boolean::class.javaPrimitiveType!!,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = true
                }
            }
        )
    }

    private fun installRenderingHook() {
        val windowHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable() || !isDarkMode) return
                val current = param.result as? Int ?: return
                if (current != ForceDarkRendering.NONE) return
                val context = try {
                    XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                } catch (_: NoSuchFieldError) {
                    null
                } ?: return
                val value = TypedValue()
                val isLightTheme = !context.theme.resolveAttribute(android.R.attr.isLightTheme, value, true) ||
                    value.data != 0
                param.result = ForceDarkRendering.choose(current, isDarkMode = true, isLightTheme)
            }
        }
        if (hookIfAvailable("android.view.ViewRootImpl", "determineForceDarkType", windowHook)) return

        // Older frameworks and some ROMs use a renderer method instead of a window decision.
        val rendererClass = "android.graphics.HardwareRenderer"
        val intHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val current = param.args[0] as Int
                if (current == ForceDarkRendering.NONE && isDarkMode) {
                    param.args[0] = ForceDarkRendering.FORCE_DARK
                }
            }
        }
        if (hookIfAvailable(rendererClass, "setForceDark", Int::class.javaPrimitiveType!!, intHook)) return

        hookIfAvailable(rendererClass, "setForceDark", Boolean::class.javaPrimitiveType!!,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isDarkMode) param.args[0] = true
                }
            }
        )
    }

    private fun installStatusBarHook() {
        hookIfAvailable(Activity::class.java.name, "onPostResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isDarkMode) return
                val activity = param.thisObject as Activity
                val window = activity.window ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.setSystemBarsAppearance(
                        0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    )
                } else {
                    @Suppress("DEPRECATION")
                    window.decorView.run {
                        systemUiVisibility = systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    }
                }
            }
        })
    }

    private fun hookIfAvailable(className: String, methodName: String, vararg signature: Any): Boolean {
        val type = XposedHelpers.findClassIfExists(className, null) ?: return false
        return try {
            XposedHelpers.findAndHookMethod(type, methodName, *signature)
            true
        } catch (_: NoSuchMethodError) {
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not install $className.$methodName", e)
            false
        }
    }

    private fun installSelfHooks(classLoader: ClassLoader) {
        val hooks = "com.kieronquinn.app.darq.model.xposed.XposedSelfHooks"
        XposedHelpers.findAndHookMethod(hooks, classLoader, "isXposedModuleEnabled",
            XC_MethodReplacement.returnConstant(true)
        )
        XposedHelpers.findAndHookMethod(hooks, classLoader, "getXSharedPrefsPath",
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any =
                    XSharedPreferences(BuildConfig.APPLICATION_ID, SHARED_PREFS_FILENAME).file.absolutePath
            }
        )
    }

    private fun readSettings(packageName: String): XposedSettings? {
        return try {
            val prefs = XposedSharedPreferences(SHARED_PREFS_FILENAME)
            if (!prefs.enabled || (!prefs.alwaysForceDark && packageName !in prefs.enabledApps)) {
                return null
            }
            XposedSettings(prefs.xposedAggressiveDark, prefs.xposedInvertStatus)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load DarQ preferences for $packageName", e)
            null
        }
    }
}
