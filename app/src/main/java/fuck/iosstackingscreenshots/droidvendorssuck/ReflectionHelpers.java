package fuck.iosstackingscreenshots.droidvendorssuck;

import de.robv.android.xposed.XposedHelpers;

final class ReflectionHelpers {
    private ReflectionHelpers() {
    }

    static Object callMethodIfExists(Object target, String methodName, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            return XposedHelpers.callMethod(target, methodName, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Object getObjectFieldIfExists(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            return XposedHelpers.getObjectField(target, fieldName);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
