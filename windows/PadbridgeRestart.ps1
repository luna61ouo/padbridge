# 自動更新後的小幫手：等舊的接收程式結束，再把新版叫起來（下次登入照常由排程啟動）。
param([int]$WaitPid, [string]$Dir)
Wait-Process -Id $WaitPid -Timeout 30 -ErrorAction SilentlyContinue
Start-Process powershell.exe -WindowStyle Hidden -ArgumentList "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$(Join-Path $Dir 'PadbridgeReceiver.ps1')`""
