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

    private static final String TAG = "DT2S-A16";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    
    private GestureDetector mGestureDetector;
    private PowerManager mPowerManager;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(SYSTEMUI_PACKAGE)) return;

        // 1. PhoneStatusBarView Hook (Status Bar)
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.statusbar.phone.PhoneStatusBarView", 
                lpparam.classLoader,
                "onTouchEvent", 
                MotionEvent.class, 
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        MotionEvent event = (MotionEvent) param.args[0];
                        View view = (View) param.thisObject;
                        processTouchEvent(view.getContext(), event, param);
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " PhoneStatusBarView hook failed: " + t.getMessage());
        }

        // 2. NotificationShadeWindowView Hook (Lockscreen)
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.systemui.shade.NotificationShadeWindowView", 
                lpparam.classLoader,
                "dispatchTouchEvent", 
                MotionEvent.class, 
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        MotionEvent event = (MotionEvent) param.args[0];
                        View view = (View) param.thisObject;

                        // Bulletproof: Skip if Bouncer (PIN entry) or AOD is active
                        try {
                            Object surfaces = XposedHelpers.getObjectField(view, "mCentralSurfaces");
                            if (surfaces != null) {
                                if ((boolean) XposedHelpers.callMethod(surfaces, "isBouncerShowing")) return;
                                if ((boolean) XposedHelpers.callMethod(surfaces, "isDozing")) return;
                            }
                        } catch (Throwable ignored) {}

                        processTouchEvent(view.getContext(), event, param);
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " NotificationShadeWindowView hook failed: " + t.getMessage());
        }
    }

    private void processTouchEvent(Context context, MotionEvent event, XC_MethodHook.MethodHookParam param) {
        if (context == null) return;
        ensureGestureDetector(context.getApplicationContext());
        
        if (mGestureDetector != null && mGestureDetector.onTouchEvent(event)) {
            param.setResult(true);
        }
    }

    private void ensureGestureDetector(Context context) {
        if (mGestureDetector == null && context != null) {
            mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (mPowerManager != null && mPowerManager.isInteractive()) {
                        try {
                            XposedHelpers.callMethod(mPowerManager, "goToSleep", SystemClock.uptimeMillis());
                            return true;
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " goToSleep failed: " + t.getMessage());
                        }
                    }
                    return false;
                }
            }, new Handler(Looper.getMainLooper()));
        }
    }
}
