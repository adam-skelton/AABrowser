# Android Auto/Automotive - Motion Restrictions Documentation

This document explains the current implementation and available options to enable the application to run while the vehicle is in motion.

## Current Configuration (v1.6+)

The application has been optimized to bypass typical "greyed out" or "blocked" states in Android Auto and Android Automotive.

### 1. Manifest Optimization
- **App Category**: Restored to `game`. Android Auto currently only supports games as a parked-app category on the projected car screen, so `productivity` caused the app to be filtered from the launcher entirely. The host still applies parked/motion restrictions for games.
- **Distraction Optimized Flags**: All user-facing activities are now tagged with `<meta-data android:name="distractionOptimized" android:value="true" />`.
- **Hardware Acceleration**: Enabled for smooth rendering of web content.

### 2. Automotive Features (`automotive_app_desc.xml`)
- Uses the `media` capability. Note: The app renders web content directly via WebView/Surface and does not use the templated `androidx.car.app` library, so the `template` capability is not required.

## Available Testing Options

A new **Experimental / Testing** section has been added to the application's internal Settings menu.

- **Bypass motion restrictions (Experimental)**: A persistent toggle to signal the app to remain active and functional even if it detects motion. This works at the application level by bypassing the `CarUxRestrictionsManager` listener.
    - *Note: This toggle only bypasses application-level logic (like disabling the address bar). System-level blocks from Android Auto or the car manufacturer may still apply.*
- **Motion Status Capability**: A dynamic indicator that queries the `CarUxRestrictionsManager` at runtime to show whether the application is currently "Active", "Bypassed", or "Restricted" by the system's driving state.

## Runtime Behavior & Safety

When the vehicle is in motion and UX Restrictions are active (and the "Bypass" toggle is **OFF**):
1. **Address Bar**: The address/URL bar is disabled to prevent typing while driving.
2. **Menu Overlays**: Any open menu overlays are automatically hidden to minimize distraction.
3. **Motion Status**: The status in Settings will turn red and show "Restricted (Motion)".

When the "Bypass" toggle is **ON**, these restrictions are ignored by the app, and the Motion Status will show "Active (Bypassed)".

## Troubleshooting "Greyed Out" Icon in Android Auto

If the application icon is still greyed out or won't start in Android Auto:

1. **Check Developer Mode**: In the Android Auto settings on your phone, ensure "Developer mode" is enabled and "Unknown sources" is checked.
2. **App Category Enforcement**: Android Auto currently only lists parked apps with `appCategory="game"`. If the app is missing from **Settings → Android Auto → Customize launcher**, confirm the manifest uses `game` rather than `productivity`.
3. **Manifest Conflicts**: Ensure that no other component in the app is missing the `distractionOptimized` flag.
4. **Phone-Side Restrictions**: Some phone manufacturers (e.g., Samsung, Huawei) have their own motion-sensing restrictions that might override the Android Auto settings.

## Future Research & Development

- **Dynamic Capability Reporting**: Investigating if we can report different capabilities based on the car's current `CarUxRestrictions`.
- **Car Library Integration**: Deeper integration with `androidx.car.app` could provide more robust ways to handle driving state changes.
