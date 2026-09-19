package com.example.dt2s;

import android.content.Context;
import android.app.KeyguardManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibratorManager;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.lang.reflect.Method;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "DT2S";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    // volatile ensures absolute visibility across UI and Vibration threads
    private static volatile long mMuzzleUntil = 0;

    // Performance: Cache the successful field name to eliminate reflection loops
    private static String mCachedControllerField = null;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(SYSTEMUI_PACKAGE)) return;

        // Cache usage method to eliminate reflection overhead during vibration bursts (fixes wakeup freeze)
        final Method getUsageMethod = XposedHelpers.findMethodBestMatch(
            XposedHelpers.findClass("android.os.VibrationAttributes", lpparam.classLoader), "getUsage"
        );

        // 1. SURGICAL MUZZLE: Target USAGE_TOUCH only to protect Biometrics
        XC_MethodHook muzzleHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (mMuzzleUntil > 0 && SystemClock.elapsedRealtime() < mMuzzleUntil) {
                    Object[] args = param.args;
                    for (Object arg : args) {
                        if (arg != null && arg.getClass().getName().contains("VibrationAttributes")) {
                            int usage = (int) getUsageMethod.invoke(arg);
                            if (usage != 18) return; // whitelist biometrics/notifications
                        }
                    }
                    param.setResult(null);
                }
            }
        };

        try {
            Class<?> vClass = XposedHelpers.findClass("android.os.Vibrator", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(vClass, "vibrate", "android.os.VibrationEffect", muzzleHook);
            XposedHelpers.findAndHookMethod(vClass, "vibrate", "android.os.VibrationEffect", "android.os.VibrationAttributes", muzzleHook);
            XposedHelpers.findAndHookMethod(vClass, "vibrate", "android.os.CombinedVibration", muzzleHook);
            XposedHelpers.findAndHookMethod(vClass, "vibrate", "android.os.CombinedVibration", "android.os.VibrationAttributes", muzzleHook);

            Class<?> vmClass = XposedHelpers.findClass("android.os.VibratorManager", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(vmClass, "vibrate", "android.os.CombinedVibration", muzzleHook);
            XposedHelpers.findAndHookMethod(vmClass, "vibrate", "android.os.CombinedVibration", "android.os.VibrationAttributes", muzzleHook);

            XposedHelpers.findAndHookMethod("android.view.View", lpparam.classLoader, "performHapticFeedback", int.class, muzzleHook);
            XposedHelpers.findAndHookMethod("android.view.View", lpparam.classLoader, "performHapticFeedback", int.class, int.class, muzzleHook);
        } catch (Throwable ignored) {}

        // 2. PASSIVE TOUCH OBSERVER
        XC_MethodHook touchHook = new XC_MethodHook() {
            private GestureDetector mGestureDetector;
            private PowerManager mPowerManager;
            private KeyguardManager mKeyguardManager;
            private int mStatusBarHeight = -1;
            private long mLastEventTime = 0;
            private int mLastEventAction = -1;

            @Override
            protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                final View view = (View) param.thisObject;
                MotionEvent event = (MotionEvent) param.args[0];
                int action = event.getActionMasked();

                // De-duplication: Ensure one physical touch event = one detector update
                // Fixes GestureDetector state machine corruption when multiple hooked views receive the same event
                if (event.getEventTime() == mLastEventTime && action == mLastEventAction) return;
                mLastEventTime = event.getEventTime();
                mLastEventAction = action;

                // Dynamic Release & Pre-Caching
                if (action == MotionEvent.ACTION_DOWN) {
                    mMuzzleUntil = 0;
                    // Pre-fetch resource height on first touch to ensure zero latency during double-tap
                    if (mStatusBarHeight <= 0) mStatusBarHeight = getStatusBarHeight(view.getContext());
                }

                if (mGestureDetector == null) {
                    Context context = view.getContext().getApplicationContext();
                    mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    mKeyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);

                    mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            // Robust Lockscreen Detection: Use AOSP KeyguardManager
                            boolean isLockscreen = mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();

                            if (isLockscreen) {
                                if (isBouncerShowing(getPanelController(view))) return false;
                            } else {
                                // Desktop Mode: Strictly Status Bar area (Using pre-cached height)
                                if (e.getRawY() > mStatusBarHeight) return false;
                            }

                            // Arm muzzle for transition window without cancelling intentional haptics
                            mMuzzleUntil = SystemClock.elapsedRealtime() + 1200;
                            performSleep();
                            return true;
                        }
                    }, new Handler(Looper.getMainLooper()));
                }

                // Passive observation: Never consume events to ensure Tap-to-Wake works
                mGestureDetector.onTouchEvent(event);
            }

            private void performSleep() {
                // isInteractive gate provides a necessary natural delay for hardware sensor re-arming
                if (mPowerManager != null && mPowerManager.isInteractive()) {
                    try {
                        // Priority 4 (Power Button) to ensure display/sound sync
                        XposedHelpers.callMethod(mPowerManager, "goToSleep", SystemClock.uptimeMillis(), 4, 0);
                    } catch (Throwable t) {
                        try { XposedHelpers.callMethod(mPowerManager, "goToSleep", SystemClock.uptimeMillis()); } catch (Throwable ignored) {}
                    }
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.phone.PhoneStatusBarView", lpparam.classLoader, "dispatchTouchEvent", MotionEvent.class, touchHook);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Status bar hooking failed: " + t.getMessage());
        }

        boolean shadeHooked = false;
        String[] shadeClasses = {
            // Android 17 (Flexiglass) Root Views
            "com.android.systemui.scene.ui.view.WindowRootView",
            "com.android.systemui.scene.ui.view.SceneWindowRootView",
            "com.android.systemui.keyguard.ui.view.KeyguardRootView",
            // Android 14-16 Root Views
            "com.android.systemui.shade.NotificationShadeWindowView",
            "com.android.systemui.window.NotificationShadeWindowView",
            // Legacy Root Views
            "com.android.systemui.statusbar.window.NotificationShadeWindowView",
            "com.android.systemui.shade.ShadeWindowView",
            "com.android.systemui.statusbar.phone.NotificationShadeWindowView"
        };
        for (String shadeClass : shadeClasses) {
            try {
                XposedHelpers.findAndHookMethod(shadeClass, lpparam.classLoader, "dispatchTouchEvent", MotionEvent.class, touchHook);
                shadeHooked = true;
                XposedBridge.log(TAG + " Successfully hooked lockscreen target: " + shadeClass);
                break; // Essential: Prevents heavy redundant Xposed callbacks on UI thread
            } catch (Throwable ignored) {}
        }

        if (!shadeHooked) {
            XposedBridge.log(TAG + " CRITICAL: All lockscreen hooks failed. SystemUI structure has changed.");
        }
    }

    private boolean isBouncerShowing(Object ctrl) {
        if (ctrl == null) return false;
        try { return (boolean) XposedHelpers.callMethod(ctrl, "isBouncerShowing"); } catch (Throwable ignored) {}
        return false;
    }

    private Object getPanelController(View view) {
        // High-frequency optimization: Bypass loop if we've already found the correct field
        if (mCachedControllerField != null) {
            try { return XposedHelpers.getObjectField(view, mCachedControllerField); } catch (Throwable ignored) {}
        }

        String[] fields = {"mShadeViewController", "mPanelViewController", "mNotificationPanelViewController", "mService", "mController"};
        for (String f : fields) {
            try {
                Object ctrl = XposedHelpers.getObjectField(view, f);
                if (ctrl != null) {
                    mCachedControllerField = f; // Cache for next interaction
                    return ctrl;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private int getStatusBarHeight(Context context) {
        int resourceId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resourceId > 0 ? context.getResources().getDimensionPixelSize(resourceId) : 100;
    }
}
