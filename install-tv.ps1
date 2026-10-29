# Installs TV Dash on the TV as soon as it's on and reachable.
# Run from the HomeIPTV folder in Android Studio's Terminal (or PowerShell):
#   .\install-tv.ps1                 (uses the address below)
#   .\install-tv.ps1 192.168.40.17   (or give the TV's address)
# Leave it running; it tries every 30 seconds until the TV answers, then installs and stops.

param([string]$Tv = "192.168.40.17")

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$apk = "app\build\outputs\apk\debug\app-debug.apk"
$target = "${Tv}:5555"

if (-not (Test-Path $adb)) { Write-Host "adb not found at $adb. Install Android SDK Platform-Tools in Android Studio (Tools > SDK Manager > SDK Tools)."; exit 1 }
if (-not (Test-Path $apk)) { Write-Host "No APK yet. In Android Studio choose Build > Build App Bundle(s) / APK(s) > Build APK(s) first."; exit 1 }

Write-Host "Waiting for the TV at $target ... (Ctrl+C to stop)"
while ($true) {
    & $adb connect $target | Out-Null
    $state = (& $adb -s $target get-state 2>$null)
    if ($state -eq "device") { break }
    if ((& $adb devices) -match [regex]::Escape($target) + "\s+unauthorized") {
        Write-Host "$(Get-Date -Format t)  TV is on but waiting for you to press Allow on the 'Allow USB debugging?' prompt."
    } else {
        Write-Host "$(Get-Date -Format t)  TV not reachable yet, trying again in 30 seconds."
    }
    Start-Sleep -Seconds 30
}

Write-Host "Connected. Installing TV Dash..."
& $adb -s $target install -r $apk
& $adb -s $target shell appops set com.matt.tvdash SYSTEM_ALERT_WINDOW allow
Write-Host "Done. Open TV Dash on the TV: Apps > See all > TV Dash."
