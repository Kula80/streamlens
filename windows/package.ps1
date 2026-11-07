# Assembles the portable StreamLens kit (for a pen drive or download) in dist\StreamLens-Windows and zips it.
# Run build.cmd first; the phone app is taken from the Android debug build.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$build = Join-Path $PSScriptRoot 'build'
$ffmpeg = if ($env:FFMPEG_DIR) { $env:FFMPEG_DIR } else { 'C:\Projects\tools\ffmpeg\ffmpeg-n9.0.2-48-gd4070a14ce-win64-lgpl-shared-9.0' }
$kit = Join-Path $root 'dist\StreamLens-Windows'

if (Test-Path $kit) { Remove-Item -Recurse -Force $kit }
New-Item -ItemType Directory -Force "$kit\StreamLens", "$kit\OBS script", "$kit\Phone app", "$kit\Licences" | Out-Null

Copy-Item "$PSScriptRoot\kit\*" $kit
foreach ($f in 'StreamLens.exe', 'StreamLensCamera.dll', 'avcodec-63.dll', 'avformat-63.dll', 'avutil-61.dll', 'swscale-10.dll', 'swresample-7.dll') {
    Copy-Item (Join-Path $build $f) "$kit\StreamLens"
}
Copy-Item "$root\obs\streamlens.lua" "$kit\OBS script"
Copy-Item -Recurse "$root\obs\slate" "$kit\OBS script\slate"
Copy-Item "$root\android\app\build\outputs\apk\debug\app-debug.apk" "$kit\Phone app\StreamLens.apk"
Copy-Item "$ffmpeg\LICENSE.txt" "$kit\Licences\FFmpeg (LGPL).txt"
Copy-Item "$PSScriptRoot\camera\LICENSE-softcam" "$kit\Licences\softcam (MIT).txt"

$zip = "$kit.zip"
Compress-Archive -Path "$kit\*" -DestinationPath $zip -Force
Get-ChildItem -Recurse $kit -File | ForEach-Object { $_.FullName.Substring($kit.Length + 1) }
'{0}: {1:N0} MB' -f $zip, ((Get-Item $zip).Length / 1MB)
