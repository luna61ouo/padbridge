@echo off
rem Padbridge Windows setup: double-click this file (inside the extracted zip folder).
rem Copies the receiver to %LOCALAPPDATA%\Padbridge, then runs install.ps1 (firewall rule + start at logon).
rem After setup: right-click the Padbridge tray icon -> "加一支手機（掃 QR）" (add a phone via QR) and scan it with the Padbridge app.
setlocal
set "PB_SRC=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$d = Join-Path $env:LOCALAPPDATA 'Padbridge'; New-Item -ItemType Directory -Force $d | Out-Null; Get-ChildItem -LiteralPath $env:PB_SRC -File | Where-Object { $_.Extension -in '.ps1', '.cs', '.txt' } | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $d -Force; Unblock-File -LiteralPath (Join-Path $d $_.Name) }; & (Join-Path $d 'install.ps1') -Dir $d -User ($env:USERDOMAIN + '\' + $env:USERNAME)"
echo.
pause
