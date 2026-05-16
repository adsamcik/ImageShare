# Screenshots Capture Guide — ImageShare v1.0

Do not run these commands while another agent is using the emulator. This guide is for the final manual capture pass.

## Play Store requirements

- Format: JPEG or PNG.
- Phone screenshots: 2–8 required.
- Tablet screenshots: optional but recommended, 1–8 for 7-inch and 1–8 for 10-inch listings.
- Minimum dimension: 320 px.
- Maximum dimension: 3840 px.
- Maximum aspect ratio: 2:1 or 1:2.
- Avoid transparent backgrounds.
- Recommended phone target: 1080×1920 portrait.
- Recommended tablet target: 1920×1200 or 2560×1800 landscape.

`<TODO: pick final screenshot set and ensure all captures match the store copy.>`

## Setup

Reference phone emulator:

```text
Medium_Phone(AVD) - API 36
```

Common variables:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$package = "com.imageshare.app"
$out = ".\docs\screenshots\phone"
New-Item -ItemType Directory -Force $out | Out-Null
```

Install or refresh the app before capture:

```powershell
.\gradlew.bat :app:installDebug
& $adb shell pm clear $package
& $adb shell am start -n "$package/.MainActivity"
Start-Sleep -Seconds 3
```

`<TODO: choose sample images that you own or have rights to use in Play Store screenshots.>`

## Phone screenshots

### Screenshot 1: Empty home state

Clean install, no source loaded.

```powershell
& $adb shell pm clear $package
& $adb shell am start -n "$package/.MainActivity"
Start-Sleep -Seconds 3
& $adb exec-out screencap -p > "$out\01-empty-home.png"
```

### Screenshot 2: Source loaded with presets visible

Push or select a sample image, then open the document picker from the app.

```powershell
& $adb push ".\docs\screenshots\fixtures\<TODO: sample-image-file>" "/sdcard/Download/imageshare-sample.jpg"
& $adb shell am start -n "$package/.MainActivity"
Start-Sleep -Seconds 2
& $adb shell input tap 540 1500
Start-Sleep -Seconds 2
& $adb shell input tap 540 420
Start-Sleep -Seconds 2
& $adb exec-out screencap -p > "$out\02-source-presets.png"
```

Adjust tap coordinates if the document picker layout differs on the capture AVD.

### Screenshot 3: Batch in progress

Use multiple owned sample images and start a batch so foreground progress is visible.

```powershell
& $adb push ".\docs\screenshots\fixtures\<TODO: sample-batch-folder>" "/sdcard/Download/imageshare-batch"
& $adb shell am start -n "$package/.MainActivity"
Start-Sleep -Seconds 2
& $adb shell input tap 540 1500
Start-Sleep -Seconds 2
& $adb shell input tap 980 180
Start-Sleep -Seconds 1
& $adb shell input tap 540 520
Start-Sleep -Seconds 1
& $adb shell input tap 540 620
Start-Sleep -Seconds 1
& $adb shell input tap 850 1840
Start-Sleep -Seconds 2
& $adb exec-out screencap -p > "$out\03-batch-progress.png"
```

### Screenshot 4: Before/after card

Capture after processing so the reduction percentage is visible.

```powershell
& $adb shell am start -n "$package/.MainActivity"
Start-Sleep -Seconds 2
& $adb shell input tap 540 1500
Start-Sleep -Seconds 2
& $adb shell input tap 540 420
Start-Sleep -Seconds 2
& $adb shell input tap 540 1180
Start-Sleep -Seconds 4
& $adb exec-out screencap -p > "$out\04-before-after.png"
```

### Screenshot 5: Custom dimensions

Show the custom dimensions card with aspect-lock toggle.

```powershell
& $adb shell am start -n "$package/.MainActivity"
Start-Sleep -Seconds 2
& $adb shell input swipe 540 1600 540 700 500
Start-Sleep -Seconds 1
& $adb shell input tap 540 1320
Start-Sleep -Seconds 1
& $adb shell input tap 420 1450
& $adb shell input text 1080
& $adb shell input tap 680 1450
& $adb shell input text 1080
Start-Sleep -Seconds 1
& $adb exec-out screencap -p > "$out\05-custom-dimensions.png"
```

### Screenshot 6 (optional): Dark mode

Same primary screen in dark theme.

```powershell
& $adb shell cmd uimode night yes
& $adb shell am start -n "$package/.MainActivity"
Start-Sleep -Seconds 3
& $adb exec-out screencap -p > "$out\06-dark-mode.png"
& $adb shell cmd uimode night no
```

## Tablet screenshots

Recommended tablet AVD: Pixel Tablet, API 36. If it does not exist, create one with Android SDK tools:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
& "$sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd `
  -n "Pixel_Tablet_API_36" `
  -k "system-images;android-36;google_apis;x86_64" `
  -d "pixel_tablet"
```

Set tablet output:

```powershell
$out = ".\docs\screenshots\tablet"
New-Item -ItemType Directory -Force $out | Out-Null
```

Capture the four essentials on the tablet AVD:

```powershell
# 1. Empty home
& $adb shell pm clear $package
& $adb shell am start -n "$package/.MainActivity"
Start-Sleep -Seconds 3
& $adb exec-out screencap -p > "$out\01-tablet-empty-home.png"

# 2. Source loaded with presets
& $adb shell input tap 960 1000
Start-Sleep -Seconds 2
& $adb shell input tap 960 360
Start-Sleep -Seconds 2
& $adb exec-out screencap -p > "$out\02-tablet-source-presets.png"

# 3. Batch progress
& $adb shell input tap 960 1000
Start-Sleep -Seconds 2
& $adb shell input tap 1720 160
Start-Sleep -Seconds 1
& $adb shell input tap 960 500
Start-Sleep -Seconds 2
& $adb exec-out screencap -p > "$out\03-tablet-batch-progress.png"

# 4. Before/after result
& $adb shell input tap 960 980
Start-Sleep -Seconds 4
& $adb exec-out screencap -p > "$out\04-tablet-before-after.png"
```

`<TODO: verify tablet coordinates on the final tablet AVD before capture day.>`

## Post-process

Recommended checks:

1. Open each PNG and confirm no debug overlays, emulator controls, or unrelated notifications are visible.
2. Crop status and navigation bars only if the resulting image still meets Play Store aspect-ratio rules.
3. Keep phone screenshots portrait and tablet screenshots landscape unless the final UI story requires otherwise.
4. Scale only if needed. Do not upscale blurry screenshots.

Example ImageMagick commands if ImageMagick is already installed:

```powershell
magick ".\docs\screenshots\phone\01-empty-home.png" -gravity North -chop 0x80 ".\docs\screenshots\phone\01-empty-home-cropped.png"
magick ".\docs\screenshots\phone\01-empty-home-cropped.png" -resize 1080x1920^ ".\docs\screenshots\phone\01-empty-home-store.png"
```

Do not add ImageMagick just for this task; manual cropping in an image editor is acceptable.

Final checklist:

- [ ] 4–6 phone screenshots captured.
- [ ] 4–6 tablet screenshots captured if tablet listing is used.
- [ ] All images are PNG or JPEG.
- [ ] No copyrighted or private sample content appears.
- [ ] Store copy matches visible UI.
- [ ] `<TODO: archive final screenshots used for v1.0 submission.>`
