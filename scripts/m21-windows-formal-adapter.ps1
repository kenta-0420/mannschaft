# M21固定adapterの関数定義だけ。dot-source時に型初期化/子起動を行わない。
function Convert-M21FormalTicksToEpochMs([object]$Ticks) {
 # 呼出し元はACK後。PowerShellの / によるdouble丸めを使わない。
 try{Add-Type -AssemblyName System.Numerics}
 catch{Set-Reason 'formal-ticks-assembly-unavailable';throw}
 if($Ticks -isnot [string] -and $Ticks -isnot [long] -and $Ticks -isnot [Numerics.BigInteger]){
  Set-Reason 'formal-ticks-invalid-type';throw ([IO.InvalidDataException]::new())
 }
 $text=$Ticks
 if($Ticks -isnot [string]){$text=$Ticks.ToString([Globalization.CultureInfo]::InvariantCulture)}
 if($text -cnotmatch '^[0-9]{1,19}$'){
  Set-Reason 'formal-ticks-invalid-value';throw ([IO.InvalidDataException]::new())
 }
 try{$integer=[Numerics.BigInteger]::Parse($text,[Globalization.CultureInfo]::InvariantCulture)}
 catch{Set-Reason 'formal-ticks-invalid-value';throw}
 $epoch=[Numerics.BigInteger]::Parse('621355968000000000')
 if($integer -lt $epoch -or $integer -gt [Numerics.BigInteger]::Parse('3155378975999999999')){
  Set-Reason 'formal-ticks-out-of-range';throw ([IO.InvalidDataException]::new())
 }
 return [Numerics.BigInteger]::Divide([Numerics.BigInteger]::Subtract($integer,$epoch),[Numerics.BigInteger]::Parse('10000')).ToString([Globalization.CultureInfo]::InvariantCulture)
}

function Confirm-M21FormalJavaGeneration([Diagnostics.Process]$Process,[object]$JavaIso) {
 # JavaはUTCの厳密なミリ秒表現だけ。曖昧なtimezone/精度/空値は採用しない。
 if($JavaIso -isnot [string] -or $JavaIso -cnotmatch '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$'){
  Set-Reason 'formal-java-invalid-ms';throw ([IO.InvalidDataException]::new())
 }
 $parsed=[DateTimeOffset]::MinValue
 $styles=[Globalization.DateTimeStyles]::AssumeUniversal -bor [Globalization.DateTimeStyles]::AdjustToUniversal
 if(![DateTimeOffset]::TryParseExact($JavaIso,"yyyy-MM-dd'T'HH:mm:ss.fff'Z'",[Globalization.CultureInfo]::InvariantCulture,$styles,[ref]$parsed)){
  Set-Reason 'formal-java-invalid-ms';throw ([IO.InvalidDataException]::new())
 }
 if($null -eq $Process){Set-Reason 'formal-java-process-missing';throw ([IO.InvalidDataException]::new())}
 try{
  # 呼出し元の保持Processだけを使う。PID再検索/Dispose/終了への読み替えはしない。
  $handle=$Process.Handle
  $exitedBefore=$Process.WaitForExit(0)
  $ticks=$Process.StartTime.ToUniversalTime().Ticks.ToString([Globalization.CultureInfo]::InvariantCulture)
  $exitedAfter=$Process.WaitForExit(0)
 }catch{Set-Reason 'formal-java-generation-unavailable';throw}
 if($handle -eq [IntPtr]::Zero){Set-Reason 'formal-java-handle-unavailable';throw ([InvalidOperationException]::new())}
 if($exitedBefore -or $exitedAfter){Set-Reason 'formal-java-process-exited';throw ([InvalidOperationException]::new())}
 $epochMs=Convert-M21FormalTicksToEpochMs $ticks
 $javaMs=$parsed.ToUnixTimeMilliseconds().ToString([Globalization.CultureInfo]::InvariantCulture)
 $matched=($epochMs -ceq $javaMs)
 $reason='formal-java-ms-mismatch';if($matched){$reason=$null}
 return [ordered]@{matched=$matched;reason=$reason;creationTicks=$ticks;epochMs=$epochMs;javaIso=$JavaIso;retainedHandle=$true}
}
