# Double Tap to Sleep

Standalone LSPosed module for Double Tap to Sleep on:
- Status Bar
- Lockscreen

## Build Instructions
1. Open in Android Studio.
2. Build APK.
3. Install and enable in LSPosed manager.
4. Reboot SystemUI or device.

## Hooks
- `com.android.systemui.statusbar.phone.PhoneStatusBarView#onTouchEvent`
- `com.android.systemui.shade.NotificationShadeWindowView#dispatchTouchEvent`

## Reference
- https://github.com/siavash79/PixelXpert
