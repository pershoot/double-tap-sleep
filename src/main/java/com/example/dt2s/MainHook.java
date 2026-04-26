package com.example.dt2s;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Vibrator;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "DT2S-A16";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    
    // Unique session ID for this module load to prevent stale state after reinstall
    private final String SESSION_ID = Integer.toHexString(this.hashCode());

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(SYSTEMUI_PACKAGE)) return;

        // 1. ACTIVE SESSION GUARD
        // Ensures only the latest reinstalled version of the module handles events
        XposedHelpers.setAdditionalInstanceField(lpparam.classLoader, "active_session", SESSION_ID);

        // 2. SURGICAL MUZZLE: Silences ghost haptics using uptime-frozen timer
        XC_MethodHook muzzleHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // Version check: only run if we are the active module load
                if (!SESSION_ID.equals(XposedHelpers.getAdditionalInstanceField(lpparam.classLoader, "active_session"))) return;

                Long silenceUntil = (Long) XposedHelpers.getAdditionalInstanceField(lpparam.classLoader, "muzzle");
                if (silenceUntil != null && SystemClock.uptimeMillis() < silenceUntil) {
                    Object[] args = param.args;
                    // Protect Biometrics: USAGE_TOUCH is 18.
                    if (args.length > 1 && args[1] != null && args[1].getClass().getName().contains("VibrationAttributes")) {
                        int usage = (int) XposedHelpers.callMethod(args[1], "getUsage");
                        if (usage != 18) return; 
                    }
                    param.setResult(true); // Kill the vibration
                }
            }
        };

        try {
            // Hook all signatures of haptic feedback
            XposedHelpers.findAndHookMethod("android.view.View", lpparam.classLoader, "performHapticFeedback", int.class, muzzleHook);
            XposedHelpers.findAndHookMethod("android.view.View", lpparam.classLoader, "performHapticFeedback", int.class, int.class, muzzleHook);
            XposedHelpers.findAndHookMethod("android.os.Vibrator", lpparam.classLoader, "vibrate", 
                "android.os.VibrationEffect", "android.os.VibrationAttributes", muzzleHook);
        } catch (Throwable ignored) {}

        XC_MethodHook touchHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                if (!SESSION_ID.equals(XposedHelpers.getAdditionalInstanceField(lpparam.classLoader, "active_session"))) return;
                
                // Recursion guard for CANCEL injection
                if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(param.thisObject, "internal"))) return;

                final View view = (View) param.thisObject;
                MotionEvent event = (MotionEvent) param.args[0];
                int action = event.getActionMasked();

                // Sequence Hijack: Wipe the second tap sequence
                Boolean isSwallowing = (Boolean) XposedHelpers.getAdditionalInstanceField(view, "swallow");
                if (isSwallowing != null && isSwallowing) {
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        XposedHelpers.setAdditionalInstanceField(view, "swallow", false);
                    }
                    param.setResult(true);
                    return;
                }

                // Physical De-duplication
                Long lastT = (Long) XposedHelpers.getAdditionalInstanceField(view, "last_t");
                if (lastT != null && event.getEventTime() == lastT && action == MotionEvent.ACTION_DOWN) return;
                XposedHelpers.setAdditionalInstanceField(view, "last_t", event.getEventTime());

                GestureDetector detector = (GestureDetector) XposedHelpers.getAdditionalInstanceField(view, "detector");
                if (detector == null) {
                    detector = new GestureDetector(view.getContext().getApplicationContext(), new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            if (!isShadeInTransit(view)) {
                                // 3. THE ATOMIC KILL: Stop the event, stop the motor, stop the ghost
                                XposedHelpers.setAdditionalInstanceField(view, "swallow", true);
                                
                                // Kill hardware motor queue
                                try {
                                    Vibrator v = (Vibrator) view.getContext().getSystemService("vibrator");
                                    if (v != null) v.cancel();
                                } catch (Throwable ignored) {}
                                
                                // Arm 1s wake-up muzzle (frozen during sleep)
                                XposedHelpers.setAdditionalInstanceField(lpparam.classLoader, "muzzle", SystemClock.uptimeMillis() + 1000);

                                performSleep(view.getContext());
                                return true;
                            }
                            return false;
                        }
                    }, new Handler(Looper.getMainLooper()));
                    XposedHelpers.setAdditionalInstanceField(view, "detector", detector);
                }

                if (detector.onTouchEvent(event)) {
                    param.setResult(true);
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.phone.PhoneStatusBarView", lpparam.classLoader, "dispatchTouchEvent", MotionEvent.class, touchHook);
            XposedHelpers.findAndHookMethod("com.android.systemui.shade.NotificationShadeWindowView", lpparam.classLoader, "dispatchTouchEvent", MotionEvent.class, touchHook);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Hooking failed: " + t.getMessage());
        }
    }

    private boolean isShadeInTransit(View view) {
        try {
            Object ctrl = null;
            String[] fields = {"mShadeViewController", "mPanelViewController", "mNotificationPanelViewController"};
            for (String f : fields) {
                try { ctrl = XposedHelpers.getObjectField(view, f); if (ctrl != null) break; } catch (Throwable t) {}
            }
            if (ctrl == null) ctrl = XposedHelpers.getAdditionalInstanceField(view, "controller");
            if (ctrl != null) {
                boolean isTracking = (boolean) XposedHelpers.callMethod(ctrl, "isTracking");
                float expansion = (float) XposedHelpers.callMethod(ctrl, "getExpansionFraction");
                return isTracking || (expansion > 0.01f && expansion < 0.99f);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void performSleep(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isInteractive()) {
            try { XposedHelpers.callMethod(pm, "goToSleep", SystemClock.uptimeMillis(), 4, 0); } catch (Throwable ignored) {}
        }
    }
}
