# Padbridge：叫 Windows 跟手機配對藍牙（電腦端圖示「用藍牙連這支手機」另開行程跑這支）。
# 先掃到手機的固定位址在廣播，再用自訂配對自己按同意；手機那邊由小幫手自動同意。結果寫到 pair-result.txt。
# PowerShell 不能直接訂 WinRT 事件，所以第一次用時把一小段 C# 編成 blepair.exe（放在同一個資料夾，之後直接用）。
param([Parameter(Mandatory)][ValidatePattern('^[0-9A-Fa-f]{12}$')][string]$Address)
$dir = Join-Path $env:LOCALAPPDATA 'Padbridge'
$out = Join-Path $dir 'pair-result.txt'
$exe = Join-Path $dir 'blepair.exe'
try {
    if (-not (Test-Path $exe)) {
        $src = Join-Path $dir 'blepair.cs'
        @'
using System; using System.Threading;
using Windows.Devices.Bluetooth; using Windows.Devices.Enumeration;
using System.Runtime.InteropServices.WindowsRuntime;
public static class P {
  static T W<T>(Windows.Foundation.IAsyncOperation<T> op, int ms) { var t0 = DateTime.Now; while (op.Status == Windows.Foundation.AsyncStatus.Started && (DateTime.Now - t0).TotalMilliseconds < ms) Thread.Sleep(50); if (op.Status != Windows.Foundation.AsyncStatus.Completed) return default(T); return op.GetResults(); }
  public static int Main(string[] args) {
    ulong want = Convert.ToUInt64(args[0], 16); bool seen = false; var ftype = BluetoothAddressType.Public;
    var w = new Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher();
    w.ScanningMode = Windows.Devices.Bluetooth.Advertisement.BluetoothLEScanningMode.Active;
    var ah = new Windows.Foundation.TypedEventHandler<Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher, Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementReceivedEventArgs>((s, a) => { if (a.BluetoothAddress == want) { seen = true; ftype = a.BluetoothAddressType; } });
    var wev = typeof(Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher).GetEvent("Received");
    WindowsRuntimeMarshal.AddEventHandler<Windows.Foundation.TypedEventHandler<Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher, Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementReceivedEventArgs>>(
      x => (EventRegistrationToken)wev.AddMethod.Invoke(w, new object[] { x }), t => wev.RemoveMethod.Invoke(w, new object[] { t }), ah);
    w.Start(); for (int i = 0; i < 200 && !seen; i++) Thread.Sleep(100); w.Stop();
    if (!seen) { Console.WriteLine("NOTFOUND"); return 2; }
    BluetoothLEDevice dev = W(BluetoothLEDevice.FromBluetoothAddressAsync(want, ftype), 10000);
    if (dev == null) { Console.WriteLine("NODEVICE"); return 3; }
    var di = dev.DeviceInformation;
    if (di.Pairing.IsPaired) { Console.WriteLine("ALREADY"); return 0; }
    var cp = di.Pairing.Custom;
    var h = new Windows.Foundation.TypedEventHandler<DeviceInformationCustomPairing, DevicePairingRequestedEventArgs>((s, a) => a.Accept());
    var ev = typeof(DeviceInformationCustomPairing).GetEvent("PairingRequested");
    WindowsRuntimeMarshal.AddEventHandler<Windows.Foundation.TypedEventHandler<DeviceInformationCustomPairing, DevicePairingRequestedEventArgs>>(
      x => (EventRegistrationToken)ev.AddMethod.Invoke(cp, new object[] { x }), t => ev.RemoveMethod.Invoke(cp, new object[] { t }), h);
    var r = W(cp.PairAsync(DevicePairingKinds.ConfirmOnly | DevicePairingKinds.ConfirmPinMatch | DevicePairingKinds.DisplayPin, DevicePairingProtectionLevel.Encryption), 90000);
    Console.WriteLine(r == null ? "TIMEOUT" : r.Status.ToString());
    return (r != null && (r.Status == DevicePairingResultStatus.Paired || r.Status == DevicePairingResultStatus.AlreadyPaired)) ? 0 : 1;
  }
}
'@ | Set-Content -Encoding ASCII $src
        $wm = 'C:\Windows\System32\WinMetadata'
        $fw = [Runtime.InteropServices.RuntimeEnvironment]::GetRuntimeDirectory()
        $r = "/r:$wm\Windows.Devices.winmd /r:$wm\Windows.Foundation.winmd /r:$fw\System.Runtime.WindowsRuntime.dll /r:$fw\System.Runtime.dll /r:$fw\System.Threading.Tasks.dll /r:$fw\System.Runtime.InteropServices.WindowsRuntime.dll"
        $co = & cmd /c "`"$fw\csc.exe`" /nologo /out:`"$exe`" $r `"$src`" 2>&1"
        Remove-Item $src -ErrorAction SilentlyContinue
        if (-not (Test-Path $exe)) { throw ('csc failed: ' + ($co -join ' | ')) }
    }
    $res = (& $exe $Address 2>&1 | Select-Object -Last 1)
    [IO.File]::WriteAllText($out, "$res")
} catch { [IO.File]::WriteAllText($out, 'ERROR ' + $_.Exception.Message) }
