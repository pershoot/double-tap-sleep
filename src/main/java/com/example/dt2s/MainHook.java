package com.example.dt2s;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "DT2S";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(SYSTEMUI_PACKAGE)) return;

        XC_MethodHook touchHook = new XC_MethodHook() {
            private GestureDetector mGestureDetector;
            private PowerManager mPowerManager;
            private long mLastEventTime = 0;

            @Override
            protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                final View view = (View) param.thisObject;
                MotionEvent event = (MotionEvent) param.args[0];

                // DE-DUPLICATION: Ensures each physical touch is processed exactly once.
                if (event.getEventTime() == mLastEventTime && event.getActionMasked() == MotionEvent.ACTION_DOWN) return;
                mLastEventTime = event.getEventTime();

                if (mGestureDetector == null) {
                    Context context = view.getContext().getApplicationContext();
                    mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

                    mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            if (mPowerManager != null && mPowerManager.isInteractive()) {
                                try {
                                    XposedHelpers.callMethod(mPowerManager, "goToSleep", SystemClock.uptimeMillis());
                                    return true;
                                } catch (Throwable ignored) {}
                            }
                            return false;
                        }
                    }, new Handler(Looper.getMainLooper()));
                }

                mGestureDetector.onTouchEvent(event);
            }
        };

        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.phone.PhoneStatusBarView", lpparam.classLoader, "dispatchTouchEvent", MotionEvent.class, touchHook);
            XposedHelpers.findAndHookMethod("com.android.systemui.shade.NotificationShadeWindowView", lpparam.classLoader, "dispatchTouchEvent", MotionEvent.class, touchHook);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Hooking failed: " + t.getMessage());
        }
    }
}
