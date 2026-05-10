$iss = @"
#define MyAppName "Nuvio"
#define MyAppVersion "$AppVersion"
#define MyAppPublisher "Creepso"

[Setup]
AppId={{7E14C1D3-BFA0-45B4-BD5E-0B3D8D6D3C11}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
OutputDir=$($OutputDir.Replace('\', '\\'))
OutputBaseFilename=Nuvio-$($AppVersion)_$($AppBuild)-x64
Compression=lzma
SolidCompression=yes
WizardStyle=modern
SetupIconFile=$($SetupIcon.Replace('\', '\\'))
WizardImageFile=$($wizardImagePath.Replace('\', '\\'))
WizardSmallImageFile=$($wizardSmallImagePath.Replace('\', '\\'))
UninstallDisplayIcon={app}\Nuvio.exe

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "$($AppDir.Replace('\', '\\'))\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\Nuvio"; Filename: "{app}\Nuvio.exe"; IconFilename: "{app}\Nuvio.exe"
Name: "{autodesktop}\Nuvio"; Filename: "{app}\Nuvio.exe"; IconFilename: "{app}\Nuvio.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Classes\nuvio"; ValueType: string; ValueData: "URL:Nuvio Protocol"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\nuvio"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""; Flags: uninsdeletevalue
Root: HKCU; Subkey: "Software\Classes\nuvio\DefaultIcon"; ValueType: string; ValueData: """{app}\Nuvio.exe"",0"
Root: HKCU; Subkey: "Software\Classes\nuvio\shell\open\command"; ValueType: string; ValueData: """{app}\Nuvio.exe"" ""%1"""

[Run]
Filename: "{app}\Nuvio.exe"; Description: "{cm:LaunchProgram,Nuvio}"; Flags: nowait postinstall skipifsilent

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var
  InstalledFile: string;
begin
  if CurStep = ssPostInstall then
  begin
    InstalledFile := ExpandConstant('{app}\.installed');
    SaveStringToFile(InstalledFile, 'installed-by-inno-setup', False);
  end;
end;
"@

Set-Content -LiteralPath $issFile -Value $iss -Encoding UTF8
