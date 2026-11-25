# 自動更新後的小幫手：等舊的接收程式結束，再把新版叫起來（下次登入照常由排程啟動）。
param([int]$WaitPid, [string]$Dir)
Wait-Process -Id $WaitPid -Timeout 30 -ErrorAction SilentlyContinue
$recv = Join-Path $Dir 'PadbridgeReceiver.ps1'
# 用 conhost --headless 叫，不然 Windows 11 的 Windows Terminal 會把它開成看得見的黑視窗（見 install.ps1）
if ([Environment]::OSVersion.Version.Build -ge 17763) {
    Start-Process (Join-Path $env:WINDIR 'System32\conhost.exe') -ArgumentList "--headless powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$recv`""
} else {
    Start-Process powershell.exe -WindowStyle Hidden -ArgumentList "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$recv`""
}
