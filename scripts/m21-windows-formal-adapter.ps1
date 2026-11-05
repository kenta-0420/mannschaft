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
 # Java epoch-ms由来の正規なUTC秒Z/3桁msZだけ。許容幅は増やさない。
 if($JavaIso -isnot [string] -or $JavaIso -cnotmatch '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{3})?Z$'){
  Set-Reason 'formal-java-invalid-ms';throw ([IO.InvalidDataException]::new())
 }
 $parsed=[DateTimeOffset]::MinValue
 $styles=[Globalization.DateTimeStyles]::AssumeUniversal -bor [Globalization.DateTimeStyles]::AdjustToUniversal
 $formats=[string[]]@("yyyy-MM-dd'T'HH:mm:ss'Z'","yyyy-MM-dd'T'HH:mm:ss.fff'Z'")
 if(![DateTimeOffset]::TryParseExact($JavaIso,$formats,[Globalization.CultureInfo]::InvariantCulture,$styles,[ref]$parsed)){
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

function New-M21FormalFrontendLaunchSpec([string]$Frontend,[string]$NodeExe,[Collections.IDictionary]$SourceEnvironment) {
 # 固定M21の自隊frontend/所有fixtureだけ。任意command/envの設定口にはしない。
 $frontendPath=[IO.Path]::GetFullPath($Frontend).TrimEnd('\')
 $fixtureFrontend=[IO.Path]::GetFullPath((Join-Path $run 'frontend')).TrimEnd('\')
 $worktreeFrontend=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../frontend')).TrimEnd('\')
 if(![IO.Path]::IsPathRooted($Frontend) -or ($frontendPath -ine $fixtureFrontend -and $frontendPath -ine $worktreeFrontend) -or !(Test-Path -LiteralPath $frontendPath -PathType Container) -or $NodeExe -cne $NodePath){
  Set-Reason 'formal-frontend-path-invalid';throw ([IO.InvalidDataException]::new())
 }
 $cli=[IO.Path]::GetFullPath((Join-Path $frontendPath 'node_modules/nuxt/bin/nuxt.mjs'))
 if(!(Test-Path -LiteralPath $cli -PathType Leaf)){Set-Reason 'formal-frontend-cli-missing';throw ([IO.FileNotFoundException]::new())}
 $environment=New-Object 'Collections.Generic.Dictionary[string,string]' ([StringComparer]::OrdinalIgnoreCase)
 foreach($key in @('SystemRoot','WINDIR')){
  $osValue=[Environment]::GetEnvironmentVariable($key)
  if($null -ne $osValue){
   if($null -eq $SourceEnvironment -or !($SourceEnvironment.Keys -contains $key) -or $SourceEnvironment[$key] -cne $osValue){Set-Reason 'formal-frontend-os-env-invalid';throw ([IO.InvalidDataException]::new())}
   $environment.Add($key,$osValue)
  }
 }
 foreach($key in @('TEMP','TMP')){
  if($null -eq $SourceEnvironment -or !($SourceEnvironment.Keys -contains $key) -or $SourceEnvironment[$key] -isnot [string] -or ![IO.Path]::IsPathRooted($SourceEnvironment[$key]) -or [IO.Path]::GetFullPath($SourceEnvironment[$key]).TrimEnd('\') -ine $run){Set-Reason 'formal-frontend-temp-invalid';throw ([IO.InvalidDataException]::new())}
  $environment.Add($key,$run)
 }
 # NODE_OPTIONSはFEだけ。他roleや親envへ書き戻さない。
 $environment.Add('NODE_OPTIONS','--max-old-space-size=8192')
 $environment.Add('PORT','3003')
 $environment.Add('BASE_URL','http://localhost:3003')
 $environment.Add('NUXT_PUBLIC_BASE_URL','http://localhost:3003')
 $environment.Add('NUXT_PUBLIC_API_BASE','http://localhost:8081')
 $environment.Add('NUXT_INTERNAL_API_BASE','http://localhost:8081')
 return [ordered]@{FilePath=$NodePath;ArgumentList=[string[]]@($cli,'dev','--host','127.0.0.1','--port','3003');WorkingDirectory=$frontendPath;Environment=$environment;LogPrefix=(Join-Path $run 'formal-fe')}
}

function Start-M21FormalAdmittedChild([ValidateSet('frontend','owned-cleanup')][string]$Role,[object]$Spec) {
 if((Remaining -Recovery) -le 0){return $null}
 if($Role -eq 'owned-cleanup'){Set-Reason 'formal-owned-cleanup-unimplemented';throw ([NotImplementedException]::new())}
 if((Remaining) -le 0){return $null}
 if($Mode -cne 'Supervisor' -or ![M21Job]::InJob($self,$null)){Set-Reason 'formal-frontend-job-unverified';throw ([InvalidOperationException]::new())}
 # 呼出しspecの任意argv/envを転送せず、固定FE値を再構成する。
 $fixed=New-M21FormalFrontendLaunchSpec $Spec.WorkingDirectory $Spec.FilePath $Spec.Environment
 if($Spec.LogPrefix -cne $fixed.LogPrefix -or @($Spec.ArgumentList).Count -ne $fixed.ArgumentList.Count){Set-Reason 'formal-frontend-spec-invalid';throw ([IO.InvalidDataException]::new())}
 for($index=0;$index -lt $fixed.ArgumentList.Count;$index++){
  if($Spec.ArgumentList[$index] -cne $fixed.ArgumentList[$index]){Set-Reason 'formal-frontend-spec-invalid';throw ([IO.InvalidDataException]::new())}
 }
 if((Remaining) -le 0 -or (Remaining -Recovery) -le 0){return $null}
 return [M21Child]::Start($fixed.FilePath,$fixed.ArgumentList,$fixed.WorkingDirectory,$fixed.LogPrefix,(Remaining -Recovery),$false,$fixed.Environment)
}
