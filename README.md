[![Build](https://github.com/E7G/BetterYAMF/actions/workflows/release.yml/badge.svg)](https://github.com/E7G/BetterYAMF/actions/workflows/release.yml)
[![Latest release](https://img.shields.io/github/v/release/E7G/BetterYAMF?display_name=tag)](https://github.com/E7G/BetterYAMF/releases)

# BetterYAMF

A performance-focused fork of [reYAMF](https://github.com/JuanArton/reYAMF), with HyperOS-style gesture and floating-window improvements.

## Preview
![Preview](Preview/preview.gif)

## Requirements
- Android 13+ (>= api 33)
- LSPosed

## Features
- Quick launch use gesture.
- HyperOS-style bottom-up gesture: pause for task view, continue toward the upper-right zone for a small window.
- Direct-manipulation animation that follows the real touch point, including off-centre gestures and landscape mode.
- Edge-aware handoff: the window changes pivot only after touching the top/right display boundary, then settles into the compact corner zone.
- No Overview flash during small-window commit or rotation; stale Quickstep state is cleared automatically.
- Automatic cleanup when the hosted task is killed, with reliable floating-ball icon and touch interaction recovery.
- All reYAMF features are supported.

## My Changes
- **Crash Fix**: Resolved `IllegalArgumentException` (View not attached to window manager) caused by accidental triggers of `ITaskStackListener`.
- Use gesture to open float windows like HyperOS.
- Operate float windows like HyperOS.
- **Performance Optimization**: The listener now uses a dynamic registration mechanism, active only when there are open windows to minimize system impact.
- **Stability Enhancement**: Added `isAttachedToWindow` safety checks during the destruction process to ensure reliable view removal.
- **Gesture handoff**: Removed the centre-to-corner jump and anchored the leash to the actual finger position.
- **Rotation fix**: Prevented Launcher Overview from reappearing behind a floating window after rotation.
- **Commit polish**: Hide `RecentsView` until Quickstep returns to `NORMAL`, eliminating the one-frame task-view flash.

## Launch by gesture like hyperOS
- Select Launcher3 or pixel launcher in LSP module working area.

## How to install
- Grab the latest APK from the [releases section](https://github.com/E7G/BetterYAMF/releases)
- Install it
- Enable module in LSPosed
- Go to Accessibility Settings and enable BetterYAMF accessibility service
- Reboot

## Build locally
```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```

The installable debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub Actions release
Push a version tag to build, test, and publish an APK automatically:
```bash
git tag v1.2.0
git push origin v1.2.0
```

The workflow also uploads the APK as a workflow artifact. Running it manually from
the **Actions** tab performs the same build and test steps without creating a release.

## "API" 
- Broadcast `com.buildsession.betterYAMF.action.CURRENT_TO_WINDOW` to float the currently visible app
- Maybe more to come

## Issues
- The system will crash if the module is different from the injected version, its an xposed thing
- Some apps can't seem to launch in small windows
- Some apps scale abnormally at certain sizes
- Some app restart while being resized. (Will fix by adding lock DPI option)

## TODO
- Improve compatibility with vendor-specific Quickstep forks.
- Add signed production builds for stable release channels.

## Contributors List
<!-- readme: contributors -start -->
<table>
	<tbody>
		<tr>
            <td align="center">
                <a href="https://github.com/C70246247">
                    <img src="https://avatars.githubusercontent.com/u/59191002?v=4" width="100;" alt="C70246247"/>
                    <br />
                    <sub><b>BuildSession</b></sub>
                </a>
            </td>
            <td align="center">
                <a href="https://github.com/JuanArton">
                    <img src="https://avatars.githubusercontent.com/u/69680526?v=4" width="100;" alt="JuanArton"/>
                    <br />
                    <sub><b>Juan Arton</b></sub>
                </a>
            </td>
            <td align="center">
                <a href="https://github.com/duzhaokun123">
                    <img src="https://avatars.githubusercontent.com/u/39830683?v=4" width="100;" alt="duzhaokun123"/>
                    <br />
                    <sub><b>o0kam1</b></sub>
                </a>
            </td>
            <td align="center">
                <a href="https://github.com/No-22-Github">
                    <img src="https://avatars.githubusercontent.com/u/132265925?v=4" width="100;" alt="No-22-Github"/>
                    <br />
                    <sub><b>No.22</b></sub>
                </a>
            </td>
		</tr>
	<tbody>
</table>
<!-- readme: contributors -end -->

## Special Thanks
- MASSIVE thanks to [duzhaokun123](https://github.com/duzhaokun123) and [kaii-lb](https://github.com/kaii-lb/YAMFsquared)

- [reYAMF](https://github.com/JuanArton/reYAMF)
- [AOSP](https://source.android.com/)
- [EzXHelper](https://github.com/KyuubiRan/EzXHelper)
- [FlexboxLayout](https://github.com/google/flexbox-layout)
- [Hide-My-Applist](https://github.com/Dr-TSNG/Hide-My-Applist)
- [LSPosed](https://github.com/LSPosed/LSPosed)
- [Material](https://material.io/)
- [Mi-FreeForm](https://github.com/sunshine0523/Mi-FreeForm)
- [QAuxiliary](https://github.com/cinit/QAuxiliary)
- [ViewBindingUtil](https://github.com/matsudamper/ViewBindingUtil)
- [gson](https://github.com/google/gson)
- [xposed](https://forum.xda-developers.com/xposed)
