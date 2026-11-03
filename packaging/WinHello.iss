#ifndef AppVersion
  #error AppVersion must be passed to ISCC
#endif
#ifndef PackageDir
  #error PackageDir must be passed to ISCC
#endif
#ifndef OutputDir
  #error OutputDir must be passed to ISCC
#endif
#ifndef IconPath
  #error IconPath must be passed to ISCC
#endif

[Setup]
AppId={{C07D22BA-935A-4E5B-A099-D6547D0D8E2A}
AppName=WinHello
AppVersion={#AppVersion}
AppPublisher=WinHello
DefaultDirName={autopf}\WinHello
DefaultGroupName=WinHello
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
ChangesEnvironment=yes
Compression=lzma2
SolidCompression=yes
SetupIconFile={#IconPath}
UninstallDisplayIcon={app}\winhello.ico
OutputDir={#OutputDir}
OutputBaseFilename=WinHello-ver{#AppVersion}-windows-x64-setup
DisableProgramGroupPage=yes
WizardStyle=modern

[Files]
Source: "{#PackageDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion
Source: "{#PackageDir}\scripts\update-installed-service.ps1"; Flags: dontcopy
Source: "{#PackageDir}\bin\agent\*"; DestDir: "{autopf}\WinHelloLanDemo"; Flags: recursesubdirs createallsubdirs ignoreversion restartreplace; Check: IsWinHelloInstalled
Source: "{#PackageDir}\bin\credential\WinHelloCredentialProvider.dll"; DestDir: "{autopf}\WinHelloLanDemo"; Flags: ignoreversion restartreplace; Check: IsWinHelloInstalled

[Icons]
Name: "{group}\WinHello Help"; Filename: "{cmd}"; Parameters: "/k ""{app}\winhello.cmd"" h"; WorkingDir: "{app}"; IconFilename: "{app}\winhello.ico"

[Code]
const
  EnvironmentKey = 'SYSTEM\CurrentControlSet\Control\Session Manager\Environment';
  ServiceKey = 'SYSTEM\CurrentControlSet\Services\WinHelloLanAgent';

function IsWinHelloInstalled: Boolean;
begin
  Result := RegKeyExists(HKLM, ServiceKey);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ExitCode, ProcessResult: Integer;
  StateFile, Arguments: string;
begin
  Result := '';
  if not IsWinHelloInstalled then Exit;

  ExtractTemporaryFile('update-installed-service.ps1');
  StateFile := ExpandConstant('{tmp}\winhello-service-state.txt');
  Arguments := '-NoProfile -ExecutionPolicy Bypass -File "' + ExpandConstant('{tmp}\update-installed-service.ps1') +
    '" -Action Stop -StateFile "' + StateFile + '"';
  if not Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'), Arguments, '', SW_HIDE,
      ewWaitUntilTerminated, ProcessResult) or (ProcessResult <> 0) then
    Result := 'WinHello could not stop its service safely. Close apps using WinHello and retry.';
end;

function HasPathEntry(const CurrentPath, Entry: string): Boolean;
begin
  Result := Pos(';' + Lowercase(Entry) + ';', ';' + Lowercase(CurrentPath) + ';') > 0;
end;

procedure AddWinHelloPath;
var
  CurrentPath, AppPath: string;
begin
  AppPath := ExpandConstant('{app}');
  if RegQueryStringValue(HKLM, EnvironmentKey, 'Path', CurrentPath) then
    if not HasPathEntry(CurrentPath, AppPath) then
      RegWriteExpandStringValue(HKLM, EnvironmentKey, 'Path', CurrentPath + ';' + AppPath);
end;

procedure RemoveLegacyUserPath;
var
  CurrentPath, LegacyPath: string;
begin
  LegacyPath := ExpandConstant('{localappdata}\Programs\WinHello');
  if RegQueryStringValue(HKCU, 'Environment', 'Path', CurrentPath) then
    if HasPathEntry(CurrentPath, LegacyPath) then
    begin
      StringChangeEx(CurrentPath, ';' + LegacyPath, '', True);
      if CompareText(CurrentPath, LegacyPath) = 0 then CurrentPath := '';
      RegWriteExpandStringValue(HKCU, 'Environment', 'Path', CurrentPath);
    end;
end;

procedure RemoveWinHelloPath;
var
  CurrentPath, Entry, AppPath: string;
begin
  AppPath := ExpandConstant('{app}');
  if RegQueryStringValue(HKLM, EnvironmentKey, 'Path', CurrentPath) then
  begin
    Entry := ';' + AppPath;
    StringChangeEx(CurrentPath, Entry, '', True);
    if CompareText(CurrentPath, AppPath) = 0 then CurrentPath := '';
    RegWriteExpandStringValue(HKLM, EnvironmentKey, 'Path', CurrentPath);
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  ExitCode: Integer;
  StateFile, Arguments: string;
begin
  if CurStep = ssPostInstall then
  begin
    AddWinHelloPath;
    RemoveLegacyUserPath;
    if IsWinHelloInstalled then
    begin
      StateFile := ExpandConstant('{tmp}\winhello-service-state.txt');
      Arguments := '-NoProfile -ExecutionPolicy Bypass -File "' + ExpandConstant('{tmp}\update-installed-service.ps1') +
        '" -Action Start -StateFile "' + StateFile + '"';
      if not Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'), Arguments, '', SW_HIDE,
          ewWaitUntilTerminated, ExitCode) or (ExitCode <> 0) then
        MsgBox('The update files were installed, but the WinHello service did not restart. Restart Windows or start WinHelloLanAgent from Services.', mbError, MB_OK);
    end;
  end;
end;

function InitializeUninstall(): Boolean;
var
  ExitCode: Integer;
  ScriptPath, Arguments: string;
begin
  ScriptPath := ExpandConstant('{app}\scripts\uninstall-winhello.ps1');
  Arguments := '-NoProfile -ExecutionPolicy Bypass -File "' + ScriptPath + '"';
  Result := Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
    Arguments, '', SW_SHOW, ewWaitUntilTerminated, ExitCode) and (ExitCode = 0);
  if not Result then
    MsgBox('Could not remove the WinHello sign-in service. The CLI files were kept. Run winhello uninstall from an elevated CMD and retry.', mbError, MB_OK);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then RemoveWinHelloPath;
end;
