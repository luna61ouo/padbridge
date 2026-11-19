# Padbridge BLE 最後一哩：叫 Windows 主動連回已配對的 Padbridge（手機藍牙關開後 Windows 不會自己回來）。
# 由 PadbridgeReceiver.ps1 收到手機的 k 封包時，以隱藏視窗另開一個行程跑（不卡住滑鼠封包）。
# 只在裝置目前是 Disconnected 時才要一次「不用快取」的服務清單——這會讓 Windows 建立連線。
# 不開關藍牙卡、不重啟藍牙服務。
param([Parameter(Mandatory)][ValidatePattern('^[0-9A-Fa-f]{12}$')][string]$Address)
$log = Join-Path (Join-Path $env:LOCALAPPDATA 'Padbridge') 'receiver.log'
function L($s) { "$(Get-Date -Format s) kick $Address $s" | Out-File -Append -Encoding utf8 $log }
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null = [Windows.Devices.Bluetooth.BluetoothLEDevice, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
    $null = [Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceServicesResult, Windows.Devices.Bluetooth, ContentType = WindowsRuntime]
    $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
    function Await($op, [Type]$t) { $task = $asTask.MakeGenericMethod($t).Invoke($null, @($op)); $task.Wait(15000) | Out-Null; $task.Result }
    $dev = Await ([Windows.Devices.Bluetooth.BluetoothLEDevice]::FromBluetoothAddressAsync([Convert]::ToUInt64($Address, 16))) ([Windows.Devices.Bluetooth.BluetoothLEDevice])
    if (-not $dev) { L 'not paired on this PC'; exit 1 }
    if (-not $dev.DeviceInformation.Pairing.IsPaired) { L 'not paired (skip)'; exit 0 }   # 沒配對時連上也沒用（滑鼠服務要加密），別白連
    if ($dev.ConnectionStatus -eq 'Connected') { L 'already connected'; exit 0 }
    $r = Await ($dev.GetGattServicesAsync([Windows.Devices.Bluetooth.BluetoothCacheMode]::Uncached)) ([Windows.Devices.Bluetooth.GenericAttributeProfile.GattDeviceServicesResult])
    Start-Sleep 2
    L ("gatt=" + $r.Status + " now=" + $dev.ConnectionStatus)
    $dev.Dispose()
} catch { L ("error " + $_.Exception.Message) }
