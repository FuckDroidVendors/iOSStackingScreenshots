package dev.duda.screenshotdroid;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }
        if (!"com.android.systemui:screenshot".equals(lpparam.processName)) {
            return;
        }
        ScreenshotHooks.install(lpparam.classLoader);
    }
}
