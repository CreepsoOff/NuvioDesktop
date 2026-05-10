param(
    [Parameter(Mandatory=$true)]
    [string]$AppDir,

    [Parameter(Mandatory=$true)]
    [string]$OutputDir,

    [Parameter(Mandatory=$true)]
    [string]$AppVersion,

    [Parameter(Mandatory=$true)]
    [string]$AppBuild,

    [Parameter(Mandatory=$true)]
    [string]$SetupIcon,

    [Parameter(Mandatory=$true)]
    [string]$AppIcon,

    [Parameter(Mandatory=$true)]
    [string]$SidebarPng
)

$ErrorActionPreference = "Stop"

# Resolve paths
$appDirResolved = (Resolve-Path $AppDir).Path
$outputDirResolved = (Resolve-Path $OutputDir).Path
$setupIconResolved = (Resolve-Path $SetupIcon).Path
$appIconResolved = (Resolve-Path $AppIcon).Path
$sidebarPngResolved = (Resolve-Path $SidebarPng).Path

# Generate .iss file
$issPath = Join-Path $outputDirResolved "Nuvio-$AppVersion-$AppBuild-x64.iss"

$issContent = @"
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
OutputDir=$($outputDirResolved.Replace('\', '\\'))
OutputBaseFilename=Nuvio-$AppVersion-$AppBuild-x64
Compression=lzma
SolidCompression=yes
WizardStyle=modern
SetupIconFile=$($setupIconResolved.Replace('\', '\\'))
WizardImageFile=$($sidebarPngResolved.Replace('\', '\\'))
WizardSmallImageFile=$($sidebarPngResolved.Replace('\', '\\'))
UninstallDisplayIcon={app}\Nuvio.exe

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "$($appDirResolved.Replace('\', '\\'))\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

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

Write-Host "Writing ISS to $issPath"
New-Item -ItemType Directory -Force -Path $outputDirResolved | Out-Null
Set-Content -LiteralPath $issPath -Value $issContent -Encoding UTF8

# Locate Inno Setup compiler
$isccPaths = @(
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles(x86)}\Inno Setup 5\ISCC.exe",
    "${env:ProgramFiles}\Inno Setup 6\ISCC.exe"
)

$iscc = $null
foreach ($p in $isccPaths) {
    if (Test-Path $p) {
        $iscc = $p
        break
    }
}

if (-not $iscc) {
    # Try registry lookup
    $regPath = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1"
    if (Test-Path $regPath) {
        $installLocation = (Get-ItemProperty $regPath).InstallLocation
        if ($installLocation) {
            $candidate = Join-Path $installLocation "ISCC.exe"
            if (Test-Path $candidate) { $iscc = $candidate }
        }
    }
}

if (-not $iscc) {
    Write-Error "Inno Setup compiler (ISCC.exe) not found. Install Inno Setup 6 from https://jrsoftware.org/isinfo.php"
    exit 1
}

Write-Host "Compiling installer with $iscc"
& $iscc $issPath

if ($LASTEXITCODE -ne 0) {
    Write-Error "ISCC failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

Write-Host "Installer created successfully in $outputDirResolved"
