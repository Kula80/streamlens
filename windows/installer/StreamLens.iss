; StreamLens-Setup.exe: installs StreamLens for Windows. Build with package.ps1 (or ISCC.exe StreamLens.iss)
; after build.cmd.
;
; It installs into Program Files, registers StreamLens Camera, lets the phone through Windows Firewall on
; Private/Domain networks, adds shortcuts, and appears in Apps & features for uninstalling. It also replaces
; an installation made with the older pen-drive kit (same folder, shortcut and firewall rule names).

#define AppVersion "0.2.0"
#ifndef FFmpegDir
  #define FFmpegDir "C:\Projects\tools\ffmpeg\ffmpeg-n9.0.2-48-gd4070a14ce-win64-lgpl-shared-9.0"
#endif
#define Build "..\build"

[Setup]
AppId={{9D3B2F6A-6C1E-4E57-9B0D-5A1C7E2F4B81}
AppName=StreamLens
AppVersion={#AppVersion}
AppVerName=StreamLens {#AppVersion}
AppPublisher=StreamLens
DefaultDirName={autopf}\StreamLens
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=..\..\dist
OutputBaseFilename=StreamLens-Setup
SetupIconFile=..\app\streamlens.ico
UninstallDisplayIcon={app}\StreamLens.exe
UninstallDisplayName=StreamLens
WizardStyle=modern
Compression=lzma2/max
SolidCompression=yes
; Close a running StreamLens before updating it (it holds StreamLens Camera).
AppMutex=StreamLensAppMutex
CloseApplications=yes
RestartApplications=no

[Tasks]
Name: "desktopicon"; Description: "Put a StreamLens shortcut on the desktop"
Name: "autostart"; Description: "Start StreamLens when Windows starts (for quick access)"; Flags: unchecked

[Files]
Source: "{#Build}\StreamLens.exe"; DestDir: "{app}"; Flags: ignoreversion
; OBS may have the camera open while updating: then the new one is put in place at the next restart.
Source: "{#Build}\StreamLensCamera.dll"; DestDir: "{app}"; Flags: ignoreversion regserver restartreplace uninsrestartdelete
Source: "{#Build}\avcodec-63.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Build}\avformat-63.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Build}\avutil-61.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Build}\swscale-10.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Build}\swresample-7.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Build}\ui\*"; DestDir: "{app}\ui"; Flags: ignoreversion recursesubdirs
; The OBS-script way stays available for people who prefer it.
Source: "..\..\obs\streamlens.lua"; DestDir: "{app}\OBS script"; Flags: ignoreversion
Source: "..\..\obs\slate\*"; DestDir: "{app}\OBS script\slate"; Flags: ignoreversion
Source: "{#FFmpegDir}\LICENSE.txt"; DestDir: "{app}\Licences"; DestName: "FFmpeg (LGPL).txt"; Flags: ignoreversion
Source: "..\camera\LICENSE-softcam"; DestDir: "{app}\Licences"; DestName: "softcam (MIT).txt"; Flags: ignoreversion

[InstallDelete]
; Left over from the pen-drive kit's installer.
Type: files; Name: "{app}\Uninstall StreamLens.cmd"

[Icons]
Name: "{autoprograms}\StreamLens"; Filename: "{app}\StreamLens.exe"
Name: "{autodesktop}\StreamLens"; Filename: "{app}\StreamLens.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "StreamLens"; \
  ValueData: """{app}\StreamLens.exe"" --minimized"; Tasks: autostart; Flags: uninsdeletevalue

[Run]
; Let the phone reach StreamLens on home and work networks (Windows calls them Private/Domain).
; StreamLens's window explains what to do if Windows calls the network Public.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""StreamLens"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; \
  Parameters: "advfirewall firewall add rule name=""StreamLens"" dir=in action=allow program=""{app}\StreamLens.exe"" enable=yes profile=private,domain"; \
  Flags: runhidden; StatusMsg: "Letting the phone through Windows Firewall..."
Filename: "{app}\StreamLens.exe"; Description: "Open StreamLens now"; Flags: postinstall nowait skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/f /im StreamLens.exe"; Flags: runhidden; RunOnceId: "StopStreamLens"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""StreamLens"""; Flags: runhidden; RunOnceId: "RemoveFirewallRule"

[Code]
const
  WebView2Client = '{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';

function HasWebView2: Boolean;
var
  Version: String;
begin
  Result :=
    (RegQueryStringValue(HKLM, 'SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\' + WebView2Client, 'pv', Version) or
     RegQueryStringValue(HKCU, 'Software\Microsoft\EdgeUpdate\Clients\' + WebView2Client, 'pv', Version)) and
    (Version <> '') and (Version <> '0.0.0.0');
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and not HasWebView2 then
    MsgBox('StreamLens is installed and StreamLens Camera works.' + #13#10#13#10 +
      'To show its window, StreamLens needs Microsoft Edge WebView2 (free, most computers already have it). ' +
      'Get it from: https://go.microsoft.com/fwlink/p/?LinkId=2124703', mbInformation, MB_OK);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  // "Start with Windows" can also be switched on from StreamLens's own window.
  if CurUninstallStep = usPostUninstall then
    RegDeleteValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Run', 'StreamLens');
end;
