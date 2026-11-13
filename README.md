# DarQ

DarQ 2.3 is an Android 17 compatibility update of [Kieron Quinn’s DarQ](https://github.com/KieronQuinn/DarQ).

## Android 17 setup

- Start Shizuku and authorize DarQ, or grant root access. Enable the system dark theme, then select apps in DarQ.
- On Android 17 ROMs with **Expanded** dark theme, enable it in Android settings and turn on **Apply Expanded Dark to selected apps** in DarQ’s advanced settings. DarQ adds its selection to the system’s existing overrides. Android still applies the rest of its Expanded theme to other apps. Turning off this option removes the entries DarQ added.
- If an app ignores the standard Force Dark path, enable DarQ in LSPosed and include DarQ and the target app in the module’s scope. Enable **Aggressive Force Dark** in DarQ and reopen the target app after force-stopping it. The module hooks Android’s View renderer. Pages drawn by a custom engine, such as Flutter, may need the app’s own theme.

The new application ID is `io.github.ltt2077.darq`, so this version can be installed alongside the original. Transfer your selection with DarQ’s **Backup & Restore**. Disable the original DarQ service and its LSPosed module when testing this version, since both can manage the same system Force Dark property.

The project builds with JDK 17, Android SDK Platform 37.0 and Build Tools 37.0.0.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

## Original project

![DarQ](https://i.imgur.com/PFfaGMrl.png)

DarQ provides a per-app selectable force dark option for Android 10 and above. 

It uses a root or [Shizuku](https://shizuku.rikka.app/) (ADB) service to apply the theme seamlessly and quickly, without needing an accessibility service.

DarQ also provides an option to apply the system dark theme (as well as selectable force dark) only after sunset and before sunrise, protecting your eyes when it's most needed. 

Please read the Frequently Asked Questions sections in the app or [here](https://github.com/KieronQuinn/DarQ/blob/master/app/src/main/assets/faq.md) for more information and some answers to questions

[Original releases](https://github.com/KieronQuinn/DarQ/releases)

[XDA Thread](https://forum.xda-developers.com/t/app-root-adb-10-11-oct-darq-per-app-selectable-force-dark-option-for-android-10.3944356/)
