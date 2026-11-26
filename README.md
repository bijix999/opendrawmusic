# Open Draw Music

Open Draw Music is an Android app for drawing notes on a musical grid, arranging them
across frames, playing the sequence with FluidSynth, and exporting it as WAV or MIDI.

> **Repository URL:** https://github.com/bijix999/opendrawmusic
>
> ```text
> https://github.com/bijix999/opendrawmusic
> ```

## Features

- 24-step by 36-pitch-line note grid.
- Instrument, key, and musical-scale selection.
- Free-draw and block brush modes, plus an eraser.
- Tempo control and looped playback.
- Multiple frames that play in sequence.
- Per-frame undo and redo.
- Starter projects and a private on-device project library.
- WAV and MIDI export for the complete frame sequence.
- Bundled GeneralUser GS SoundFont and support for importing additional `.sf2` files.

## Requirements

### Android device

- Android 7.0 (API 24) or newer.
- The app's native libraries are built for `arm64-v8a` and `x86_64`.
- For USB installation, enable Developer options and USB debugging, connect the device,
  and approve the computer's debugging prompt.

### Build computer

- Git.
- Android Studio with Android SDK Platform 36, Android NDK, and CMake 3.22.1.
- JDK 17 or newer. JDK 21 is recommended; the project has previously been built with
  Microsoft OpenJDK 21.0.13.102.
- The checked-in Gradle wrapper downloads Gradle 8.12 as needed.

The project uses Android Gradle Plugin 8.9.1, Kotlin 2.1.20, compile SDK 36, and Java /
Kotlin JVM target 17. The native FluidSynth library is built from `third_party/fluidsynth`
using CMake.

## Get the source from GitHub

Clone the app repository:

```powershell
git clone https://github.com/bijix999/opendrawmusic.git OpenDrawMusic
Set-Location OpenDrawMusic
```

FluidSynth is tracked as a Git submodule at `third_party/fluidsynth`; it also uses
submodules for some of its dependencies. Initialize it and its nested dependencies:

```powershell
git submodule update --init --recursive
```

Do not copy a machine-specific `local.properties` file from another computer. Android
Studio creates or updates it with the local Android SDK path. Do not commit that file.

## Configure Android Studio

1. Install Android Studio and open the cloned project root (the directory containing
   `settings.gradle.kts`).
2. In **Tools > SDK Manager**, install:
   - Android SDK Platform 36.
   - Android SDK Build-Tools offered for that platform.
   - Android NDK (Side by side).
   - CMake 3.22.1.
   - Android SDK Platform-Tools (includes `adb`).
3. In **Settings > Build, Execution, Deployment > Build Tools > Gradle**, choose a
   Gradle JDK version 17 or newer. JDK 21 is recommended.
4. Allow Android Studio to sync the project and download the declared dependencies.
5. Connect an Android device or start an emulator using `arm64-v8a` or `x86_64`.

The app's minimum SDK is API 24. It targets API 36. A device with another CPU ABI may
not be able to install this build.

## Build without Android Studio

Android Studio is optional. The build only needs the SDK *components* (Platform 36,
NDK, CMake 3.22.1, Platform-Tools) and a JDK — the IDE itself is never invoked by
Gradle. This section documents the command-line-only path; it has been verified on
this machine (build completed with `BUILD SUCCESSFUL`, native FluidSynth configured
and compiled for both ABIs, with no Android Studio running).

### Prerequisites

- JDK 17 or newer (JDK 21 recommended; verified with Microsoft OpenJDK 21.0.13.102).
- Android SDK directory containing:
  - `platforms/android-36`
  - an NDK (Side by side) install — no `ndkVersion` is pinned in
    `app/build.gradle.kts`, so AGP resolves the installed default
    (verified: NDK `27.0.12077973`)
  - `cmake/3.22.1`
  - `build-tools/` for compile SDK 36 (verified: `35.0.0`)
  - `platform-tools/` (provides `adb`)
- The Gradle wrapper (`gradlew` / `gradlew.bat`) downloads Gradle 8.12 on first run.

If the components are missing, install them without the IDE using the bundled
command-line tools:

```powershell
C:\Android\cmdline-tools\latest\bin\sdkmanager.bat "platforms;android-36" `
  "ndk;27.0.12077973" "cmake;3.22.1" "build-tools;35.0.0" "platform-tools"
```

Adjust the SDK root to your install location. The verified environment on this
machine is:

| Item | Value |
|---|---|
| `ANDROID_HOME` | `C:\Android` (also set in `local.properties` as `sdk.dir`) |
| JDK (`JAVA_HOME`) | `C:\Program Files\Microsoft\jdk-21.0.13.102-hotspot` |
| NDK | `C:\Android\ndk\27.0.12077973` |
| CMake | `C:\Android\cmake\3.22.1` |
| `adb` | `C:\Android\platform-tools\adb.exe` |

Gradle finds the SDK through either `ANDROID_HOME` / `ANDROID_SDK_ROOT` or
`local.properties` (`sdk.dir=`). Keep `local.properties` machine-specific and
uncommitted, as described above.

### Build, install, and run from the command line

Windows (PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.13.102-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

C:\Android\platform-tools\adb.exe devices -l
C:\Android\platform-tools\adb.exe install -r ".\app\build\outputs\apk\debug\app-debug.apk"
C:\Android\platform-tools\adb.exe shell am start -n com.opendrawmusic/.MainActivity
```

macOS or Linux:

```bash
export JAVA_HOME="/path/to/jdk-21"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="/path/to/android-sdk"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

adb devices -l
adb install -r ./app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.opendrawmusic/.MainActivity
```

Notes:

- The first build compiles FluidSynth from `third_party/fluidsynth` for both ABIs and
  can take several minutes; later builds are incremental (a no-op rebuild of this
  project completed in about 9 seconds).
- The unit test, assemble, and lint tasks can be run individually; see the next
  section.
- Android Studio remains useful as an IDE and as a GUI for managing SDK components,
  but it is not required to build, install, or test the app.

## Build, test, and lint

Run these commands from the project root.

### Windows PowerShell

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

### macOS or Linux

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

To run each Gradle task separately, use `:app:testDebugUnitTest` for unit tests,
`:app:assembleDebug` to build the APK, or `:app:lintDebug` for Android lint.

## Install with ADB

1. Connect and unlock the device. Enable **USB debugging** and accept the authorization
   prompt.
2. From the project root, check the device serial:

   ```powershell
   adb devices -l
   ```

3. Install or update the debug APK. Replace the serial with the one reported above:

   ```powershell
   adb -s DEVICE_SERIAL install -r ".\app\build\outputs\apk\debug\app-debug.apk"
   ```

   `-r` reinstalls or updates the app while preserving its existing app data when the
   package signature is compatible.

   `".\app\build\outputs\apk\debug\app-debug.apk"` are the APK directory. You may download the APK from [Releases](https://github.com/bijix999/opendrawmusic/releases) and use the newly downloaded APK directory replacing `".\app\build\outputs\apk\debug\app-debug.apk"`

5. Launch the app:

   ```powershell
   adb -s DEVICE_SERIAL shell am start -n com.opendrawmusic/.MainActivity
   ```

For a single connected device, `-s DEVICE_SERIAL` may be omitted. If an update fails
because the installed APK was signed with a different key, uninstalling first may
resolve the signature conflict, but uninstalling removes the app's private projects
and imported SoundFonts.

The same ADB commands work in macOS/Linux terminals; use the corresponding APK path
separator for your shell.

## Use the app

### Make a pattern

1. Open **Library** and select a **Starter project** to explore an example, or begin
   with the current project.
2. Draw or drag on the grid to place notes. The grid has 24 time steps and 36 pitch
   lines; the line indicator reports the pitch under the current selection.
3. Tap the instrument control to browse instrument families and presets. Tap **C** to
   choose a key, or **Musical Scale** to choose a scale. These settings determine the
   pitches represented by the grid lines.
4. Use the pencil/eraser control to switch between drawing and erasing.
5. Tap **Clear** to clear the current project.

The initial project uses 120 BPM, key C, and a pentatonic scale. In the default mapping,
line 5 corresponds to C6. Use **Settings > Info** in the app for the grid and mapping
summary.

### Tempo, brush, and volume

- The **−** and **+** buttons beside BPM change tempo by 5 BPM, between 40 and 240 BPM.
- Long-press the BPM value to open the tempo slider (60–200 BPM).
- Open **Settings** to choose **Free draw** or **Block** brush behavior.
- Adjust **Volume** in Settings. Volume and brush mode are app-session settings; they
  are not saved as part of a project.

### Frames and playback

- Tap **+** beside the frame strip to add a frame.
- Tap a frame thumbnail to select it. Each frame has its own note grid.
- Long-press a thumbnail to open frame actions, including adding a frame before/after,
  copying, pasting, and deleting. Deleting a frame requires confirmation; the final
  remaining frame cannot be deleted.
- **Undo** and **Redo** apply to the currently selected frame.
- Tap **Play** to play frames in order. Tap it again to stop.
- Turn on **Loop** to repeat the whole frame sequence.

### Save and export

- Tap **Save to library** to store the project in the app's private on-device library.
- Tap **Library**, then **Saved project**, to reopen a saved project.
- Tap **Export**, choose **Export to WAV** or **Export to MIDI**, then choose a
  destination in Android's document picker. Exports include all frames in sequence.

Saved projects and imported SoundFonts are held in private app storage. They are not
automatically backed up or exported with the APK. Uninstalling the app or clearing its
storage deletes them.

## Configure SoundFonts

### Bundled SoundFont

The app includes `app/src/main/assets/core.sf2`, identified in the app as GeneralUser GS
v2.0.3. On startup, the app copies it into its private storage and loads it as the core
SoundFont. It is the default instrument source and cannot be removed from the app.

The upstream GeneralUser GS project and its usage/redistribution information are at
[mrbumpy409/GeneralUser-GS](https://github.com/mrbumpy409/GeneralUser-GS). The upstream
project describes GeneralUser GS as a General MIDI SoundFont. Follow the terms and
attribution requirements published by its author when redistributing the SoundFont;
public availability does not by itself mean public-domain status.

FluidSynth's license text is included at
[`app/src/main/assets/FluidSynth-LGPL-2.1.txt`](app/src/main/assets/FluidSynth-LGPL-2.1.txt).
This file covers FluidSynth, not necessarily every other project asset.

### Import another SoundFont

1. Obtain a valid `.sf2` file that you are allowed to use.
2. In the app, tap **Settings** (gear) > **Import SoundFont**.
3. Choose the `.sf2` file in Android's file picker and wait for the load status.
4. Open **Instrument** and select a preset from the imported SoundFont.
5. To remove an imported SoundFont, go to **Settings > Soundfont Configuration** and
   tap its delete control. The bundled GeneralUser GS entry is protected. If you delete
   the currently selected imported SoundFont, the app switches back to the bundled
   SoundFont.

Imported files are copied into the app's private `files/soundfonts` directory and
registered in private app data. They are not stored in a public shared folder. Import
the original file again after reinstalling the app or clearing its data.

The importer is intended for valid SoundFont 2 (`.sf2`) files that FluidSynth can load
and whose presets can be read by the app. SoundFont 3 (`.sf3`) is not supported by the
app's preset reader. Large SoundFonts may take time and use substantial memory and
storage; if a file does not load, check that it is a valid, uncorrupted `.sf2` and try
removing the failed entry from **Soundfont Configuration** before importing again.

In the current UI, **FluidR3 GM** and **Arachno SoundFont** may appear as sample-library
names. They are not bundled `.sf2` files in this source tree; import an actual file to
use a SoundFont other than the bundled GeneralUser GS.

## Troubleshooting

### Gradle cannot find the Android SDK

- Open the project in Android Studio and let it configure `local.properties`, or set
  `ANDROID_HOME` to your installed Android SDK directory.
- Confirm SDK Platform 36, NDK, and CMake 3.22.1 are installed.
- Do not use another machine's `local.properties`.

### Native build fails

- Confirm `third_party/fluidsynth` exists and contains its CMake project files.
- Confirm Android NDK and CMake 3.22.1 are installed in Android Studio's SDK Manager.
- Use JDK 17 or newer and rerun Gradle sync.

### ADB does not list the phone

- Check the USB cable and select a data-transfer USB mode if required by the device.
- Enable USB debugging, unlock the phone, and accept its authorization dialog.
- Run `adb devices -l` again. The device should be listed as `device`, not `unauthorized`.

### No sound or imported preset is missing

- Confirm the app has finished loading the core SoundFont.
- Select an instrument from **Instrument** and draw notes before playback.
- For an imported SoundFont, confirm the file is a valid `.sf2` with readable presets,
  then import it again if necessary.
- Check the device media volume and the app's **Settings > Volume**.

## Credits and licenses

- **Bundled SoundFont:** GeneralUser GS v2.0.3, by S. Christian Collins
  ([mrbumpy409/GeneralUser-GS](https://github.com/mrbumpy409/GeneralUser-GS)). The
  SoundFont is bundled as `app/src/main/assets/core.sf2` and is credited here to its
  original project. Consult the upstream repository for its current license and
  attribution/redistribution terms; retain this credit when redistributing it.
- **Synthesizer:** FluidSynth. Its included license text is
  [`app/src/main/assets/FluidSynth-LGPL-2.1.txt`](app/src/main/assets/FluidSynth-LGPL-2.1.txt).
- **User-imported SoundFonts:** Users are responsible for having permission to use
  imported files and for following each file's license and attribution requirements.
- Review the license and attribution notices for every dependency and asset before
  redistributing a build.

## Current build scope

This repository is configured for Android debug builds and personal testing. The
instructions above build and install a debug APK; they do not configure Play Store
publishing, signing keys, ads, billing, or premium features.
