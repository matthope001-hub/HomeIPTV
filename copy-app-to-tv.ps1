# Copies an app you already have on your Android phone onto the TV, then it shows in TV Dash.
# Default: Cogeco EPICO. Run from the HomeIPTV folder in Android Studio's Terminal (or PowerShell):
#   .\copy-app-to-tv.ps1
#   .\copy-app-to-tv.ps1 -Tv 192.168.40.17 -Package ca.cogeco.itv.android
# Before running: phone plugged in by USB with USB debugging on, TV on with USB debugging on.

param(
    [string]$Package = "ca.cogeco.itv.android",
    [string]$Tv = "192.168.40.17"
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$target = "${Tv}:5555"
if (-not (Test-Path $adb)) { Write-Host "adb not found at $adb. Install Android SDK Platform-Tools in Android Studio (Tools > SDK Manager > SDK Tools)."; exit 1 }

# The phone is the device connected by USB (its id has no ':' in it)
$phone = & $adb devices | Select-String "`tdevice$" | ForEach-Object { $_.ToString().Split("`t")[0] } | Where-Object { $_ -notmatch ":" } | Select-Object -First 1
if (-not $phone) { Write-Host "No phone found. Plug it in by USB, turn on USB debugging, and press Allow on the phone."; exit 1 }

$paths = & $adb -s $phone shell pm path $Package | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ }
if (-not $paths) { Write-Host "$Package isn't installed on the phone."; exit 1 }

$dir = Join-Path $env:TEMP "tvdash-$Package"
New-Item -ItemType Directory -Force $dir | Out-Null
Remove-Item "$dir\*.apk" -ErrorAction SilentlyContinue
Write-Host "Copying $($paths.Count) file(s) from the phone..."
foreach ($p in $paths) { & $adb -s $phone pull $p $dir | Out-Null }

Write-Host "Connecting to the TV at $target..."
& $adb connect $target | Out-Null
if ((& $adb -s $target get-state 2>$null) -ne "device") { Write-Host "Couldn't reach the TV. Is it on, with USB debugging on? Press Allow on the TV if it asks."; exit 1 }
$sdk = (& $adb -s $target shell getprop ro.build.version.sdk).Trim()
Write-Host "TV runs Android API $sdk."

$files = Get-ChildItem $dir -Filter *.apk | ForEach-Object { $_.FullName }
$out = & $adb -s $target install-multiple -r @files 2>&1 | Out-String
Write-Host $out
if ($out -match "Success") { Write-Host "Done. Open TV Dash: the app is in the Apps row." }
elseif ($out -match "OLDER_SDK") { Write-Host "The app needs a newer Android version than the TV has (TV is API $sdk). It can't run on this TV." }
elseif ($out -match "NO_MATCHING_ABIS") { Write-Host "The phone's copy is built for a different processor than the TV's, so it can't be installed." }
else { Write-Host "Install didn't finish. Send the message above to Claude." }
