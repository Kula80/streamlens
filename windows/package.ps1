# Builds StreamLens-Setup.exe and assembles the pen-drive kit in dist\StreamLens-Windows (also zipped).
# Run build.cmd first; the phone app is taken from the Android debug build.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$iscc = if ($env:ISCC) { $env:ISCC } else { 'C:\Projects\tools\innosetup\ISCC.exe' }
$kit = Join-Path $root 'dist\StreamLens-Windows'

Push-Location (Join-Path $PSScriptRoot 'installer')
try {
    & $iscc /Q StreamLens.iss
    if ($LASTEXITCODE -ne 0) { throw "Inno Setup failed ($LASTEXITCODE)" }
} finally { Pop-Location }

if (Test-Path $kit) { Remove-Item -Recurse -Force $kit }
New-Item -ItemType Directory -Force "$kit\Phone app" | Out-Null
Copy-Item "$PSScriptRoot\kit\*" $kit
Copy-Item (Join-Path $root 'dist\StreamLens-Setup.exe') $kit
Copy-Item "$root\android\app\build\outputs\apk\debug\app-debug.apk" "$kit\Phone app\StreamLens.apk"

$zip = "$kit.zip"
Compress-Archive -Path "$kit\*" -DestinationPath $zip -Force
Get-ChildItem -Recurse $kit -File | ForEach-Object { $_.FullName.Substring($kit.Length + 1) }
'{0}: {1:N0} MB' -f $zip, ((Get-Item $zip).Length / 1MB)
