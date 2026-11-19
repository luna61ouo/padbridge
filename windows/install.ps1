# Padbridge 電腦端安裝（Wi-Fi 接收＋藍牙最後一哩＋右下角小圖示）。重跑＝更新。
# 裝好之後在右下角小圖示選「加一支手機（掃 QR）」，用手機 App 掃就配好了。
# 做三件事：
#   1. 防火牆只開 UDP 47801，而且只收同一個區網來的封包
#   2. 建一個「登入時自動啟動」的排程，用你的身分、一般權限、在背景跑接收程式
#   3. 立刻啟動，確認有在聽
# 移除：powershell -ExecutionPolicy Bypass -File install.ps1 -Uninstall
param([switch]$Uninstall, [string]$Dir, [string]$User)
$ErrorActionPreference = 'Stop'
$dir = $(if ($Dir) { $Dir } else { Join-Path $env:LOCALAPPDATA 'Padbridge' })
# 提升權限後可能變成另一個管理員帳號：資料夾與排程的使用者一律用「原本那個人」的（由呼叫端傳進來）
if (-not $User) { $User = "$env:USERDOMAIN\$env:USERNAME" }
$rule = 'Padbridge UDP'
$task = 'Padbridge'

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host '需要管理員權限，跳出確認視窗時請按「是」…'
    $args2 = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Dir `"$dir`" -User `"$User`""
    if ($Uninstall) { $args2 += ' -Uninstall' }
    Start-Process powershell.exe -Verb RunAs -ArgumentList $args2 -Wait
    exit
}

if ($Uninstall) {
    Stop-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $task -Confirm:$false -ErrorAction SilentlyContinue
    Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -like '*PadbridgeReceiver.ps1*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Write-Host '已移除 Padbridge 接收端。'
    Read-Host '按 Enter 關閉'
    exit
}


if (-not (Test-Path (Join-Path $dir 'PadbridgeReceiver.ps1')) -or -not (Test-Path (Join-Path $dir 'PadbridgeBleKick.ps1')) -or -not (Test-Path (Join-Path $dir 'PadbridgeBlePair.ps1')) -or -not (Test-Path (Join-Path $dir 'PadbridgeQr.cs'))) {
    Write-Host "找不到 $dir 裡的程式檔，請重新下載安裝包。" -ForegroundColor Red
    Read-Host '按 Enter 關閉'; exit 1
}

Get-NetFirewallRule -DisplayName $rule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $rule -Direction Inbound -Protocol UDP -LocalPort 47801 `
    -RemoteAddress LocalSubnet -Action Allow -Profile Any | Out-Null
Write-Host '✓ 防火牆：UDP 47801，只收家裡網路（區網）'

$user = $User
$a = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ("-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"" + (Join-Path $dir 'PadbridgeReceiver.ps1') + "`"")
$t = New-ScheduledTaskTrigger -AtLogOn -User $user
$p = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$s = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
# 重裝時先停掉舊的接收程式
Stop-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -like '*PadbridgeReceiver.ps1*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Register-ScheduledTask -TaskName $task -Action $a -Trigger $t -Principal $p -Settings $s -Force | Out-Null
Start-ScheduledTask -TaskName $task
Start-Sleep 4

$ep = Get-NetUDPEndpoint -LocalPort 47801 -ErrorAction SilentlyContinue
if ($ep) { Write-Host '✓ 接收程式已啟動，正在聽 UDP 47801，右下角有月亮小圖示（之後每次登入會自己啟動）' -ForegroundColor Green }
else { Write-Host '✗ 接收程式沒起來，請把這個視窗截圖回報問題' -ForegroundColor Red }
Read-Host '按 Enter 關閉'
