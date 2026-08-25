#define MyAppName "MicYou"
#ifndef MyAppVersion
  #define MyAppVersion "2.0.0"
#endif
#define MyAppPublisher "LanRhyme"
#define MyAppURL "https://github.com/LanRhyme/MicYou"
#define MyAppExeName "MicYou.exe"

[Setup]
AppId={{C8E6D8A6-3A1B-4E38-B76B-C9DB2A0058C0}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppName}
DisableDirPage=no
DisableProgramGroupPage=yes
UsePreviousAppDir=yes
PrivilegesRequired=admin
; "commandline" enables /CURRENTUSER so CI and sandbox checks can install without elevation
PrivilegesRequiredOverridesAllowed=dialog commandline
; closing the running app is handled explicitly in PrepareToInstall below
CloseApplications=no
OutputBaseFilename={#MyAppName}_{#MyAppVersion}_x64-setup
OutputDir=target\release\bundle\inno
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
SetupIconFile=icons\icon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Default.isl,SimpChinese.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
WebView2Missing=MicYou requires the Microsoft WebView2 Runtime, which was not detected on this system. The official download page has been opened in your browser - install it, then run this setup again.
chinesesimplified.WebView2Missing=MicYou 需要微软 WebView2 运行时，但未在本系统检测到。已在浏览器打开官方下载页面，安装完成后请重新运行本安装程序。
AppRunning=MicYou is still running. It will be closed automatically to continue the installation. Continue?
chinesesimplified.AppRunning=MicYou 正在运行，将继续安装并自动关闭它。是否继续？

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "target\release\MicYou.exe"; DestDir: "{app}"; Flags: ignoreversion
; ONNX models (aec7_ep0185/purevox6), licenses and the PipeWire config are loaded
; at runtime from <exe_dir>\resources (see src-tauri/src/commands/system.rs)
Source: "resources\*"; DestDir: "{app}\resources"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; AppUserModelID: "com.lanrhyme.micyou"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon; AppUserModelID: "com.lanrhyme.micyou"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[Code]
const
  WebView2KeyWow = 'SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';
  WebView2Key64 = 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';
  WebView2KeyUser = 'Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';

function WebView2PvPresent(Root: Integer; SubKey: String): Boolean;
var
  Pv: String;
begin
  Result := RegQueryStringValue(Root, SubKey, 'pv', Pv) and (Pv <> '') and (Pv <> '0.0.0.0');
end;

function WebView2Installed(): Boolean;
begin
  Result :=
    WebView2PvPresent(HKLM, WebView2KeyWow) or
    WebView2PvPresent(HKLM, WebView2Key64) or
    WebView2PvPresent(HKCU, WebView2KeyUser);
end;

function MicYouRunning(): Boolean;
var
  CmdCode: Integer;
  ListFile: String;
  Content: String;
  Lines: TArrayOfString;
  I: Integer;
begin
  Result := False;
  ListFile := ExpandConstant('{tmp}\micyou_tasklist.txt');
  DeleteFile(ListFile);
  if not Exec(ExpandConstant('{cmd}'),
       '/C tasklist /FI "IMAGENAME eq ' + ExpandConstant('{#MyAppExeName}') + '" /NH > "' + ListFile + '"',
       '', SW_HIDE, ewWaitUntilTerminated, CmdCode) then
    Exit;
  if not LoadStringsFromFile(ListFile, Lines) then
    Exit;
  Content := '';
  for I := 0 to GetArrayLength(Lines) - 1 do
    Content := Content + Lines[I];
  Result := Pos(ExpandConstant('{#MyAppExeName}'), Content) > 0;
  DeleteFile(ListFile);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ErrorCode: Integer;
begin
  Result := '';
  if not WebView2Installed() then begin
    if not WizardSilent() then
      ShellExec('open', 'https://go.microsoft.com/fwlink/p/?LinkId=2124703', '', '', SW_SHOWNORMAL, ewNoWait, ErrorCode);
    Result := CustomMessage('WebView2Missing');
    Exit;
  end;
  if MicYouRunning() then begin
    if WizardSilent() or (SuppressibleMsgBox(CustomMessage('AppRunning'), mbConfirmation, MB_OKCANCEL, IDOK) = IDOK) then
      Exec(ExpandConstant('{cmd}'),
        '/C taskkill /IM ' + ExpandConstant('{#MyAppExeName}') + ' /T /F',
        '', SW_HIDE, ewWaitUntilTerminated, ErrorCode);
  end;
end;
