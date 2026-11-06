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
 if($Role -eq 'owned-cleanup'){
  # この分岐は所有fixture marker専用。実Docker等のcleanupではない。
  if($Mode -cne 'Supervisor' -or $TestFixture -cne 'formal-admission' -or $Scenario -cne 'normal' -or ![M21Job]::InJob($self,$null)){Set-Reason 'formal-cleanup-job-unverified';throw ([InvalidOperationException]::new())}
  Guard-Run
  $resource=$Spec.OwnedResource
  if($resource -isnot [Collections.IDictionary] -or $resource.Count -ne 3 -or @($resource.Keys|Where-Object {$_ -cnotin @('owner','nonce','kind')}).Count -ne 0 -or $resource.owner -isnot [string] -or $resource.owner -cne $owner -or $resource.nonce -isnot [string] -or $resource.nonce -cne $Nonce -or $resource.kind -isnot [string] -or $resource.kind -cne 'fixture-marker'){
   Set-Reason 'formal-cleanup-owner-kind-invalid';throw ([IO.InvalidDataException]::new())
  }
  $probe=Join-Path $PSScriptRoot 'm21-windows-supervisor.probe.cjs'
  $prefix=Join-Path $run 'formal-cleanup'
  $arguments=@($Spec.ArgumentList)
  $fixedHeadArguments=@($probe,'--dummy','hold-stream',$run,$Nonce,'normal')
  if($Spec.FilePath -cne $NodePath -or $Spec.WorkingDirectory -cne $run -or $Spec.LogPrefix -cne $prefix -or $arguments.Count -ne 8){Set-Reason 'formal-cleanup-spec-invalid';throw ([IO.InvalidDataException]::new())}
  foreach($argument in $arguments){if($argument -isnot [string]){Set-Reason 'formal-cleanup-spec-invalid';throw ([IO.InvalidDataException]::new())}}
  for($index=0;$index -lt $fixedHeadArguments.Count;$index++){if($arguments[$index] -cne $fixedHeadArguments[$index]){Set-Reason 'formal-cleanup-spec-invalid';throw ([IO.InvalidDataException]::new())}}
  if($arguments[6] -cne '2000' -or $arguments[7] -cnotmatch '^[0-9]{1,6}$'){Set-Reason 'formal-cleanup-budget-invalid';throw ([IO.InvalidDataException]::new())}
  $inputTotal=[int]::Parse($arguments[7],[Globalization.CultureInfo]::InvariantCulture)
  if($inputTotal -gt 420000){Set-Reason 'formal-cleanup-budget-invalid';throw ([IO.InvalidDataException]::new())}
  $environment=New-Object 'Collections.Generic.Dictionary[string,string]' ([StringComparer]::OrdinalIgnoreCase)
  foreach($key in @('SystemRoot','WINDIR')){$value=[Environment]::GetEnvironmentVariable($key);if($null -ne $value){$environment.Add($key,$value)}}
  $environment.Add('TEMP',$run);$environment.Add('TMP',$run)
  # 古いremaining値との完全一致は要求せず、元上限と直前Gの小さい方へ縮める。
  $launchTotal=[int][Math]::Min($inputTotal,(Remaining -Recovery))
  if($launchTotal -le 0){return $null}
  $launchArguments=[string[]]@($probe,'--dummy','hold-stream',$run,$Nonce,'normal',[string]([Math]::Min(2000,$launchTotal)),[string]$launchTotal,'--owner-id',$owner)
  return [M21Child]::Start($NodePath,$launchArguments,$run,$prefix,$launchTotal,$false,$environment)
 }
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

function Get-M21AssetFileRecord([string]$Path) {
 Require-Work
 $file=New-Object IO.FileInfo([IO.Path]::GetFullPath($Path))
 if(!$file.Exists -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)){Set-Reason 'asset-file-missing-or-reparse';throw ([IO.InvalidDataException]::new())}
 $directory=$file.Directory
 while($null -ne $directory -and $script:m21AssetCheckedDirectories.Add($directory.FullName)){
  if($directory.Attributes -band [IO.FileAttributes]::ReparsePoint){Set-Reason 'asset-parent-reparse';throw ([IO.InvalidDataException]::new())}
  $directory=$directory.Parent
 }
 return [ordered]@{path=$file.FullName;bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash}
}

function Assert-M21AssetCreator([ValidateSet('cp','javac','jar')][string]$Role,[M21Child]$Child) {
 $context=$script:m21ProductionAssets;$proof=$script:m21AssetCreators[$Role]
 if($null -eq $proof -or ![object]::ReferenceEquals($proof.child,$Child) -or @($script:held|Where-Object {[object]::ReferenceEquals($_,$Child)}).Count -ne 1 -or $proof.owner -cne $owner -or $proof.nonce -cne $Nonce -or $proof.sourceCommit -cne $context.sourceCommit -or !$Child.Started -or !$Child.Captured -or !$Child.Process.WaitForExit(0) -or $Child.Process.ExitCode -ne 0 -or !$Child.StdoutPublished -or !$Child.StderrPublished){Set-Reason 'asset-creator-unverified';throw ([IO.InvalidDataException]::new())}
 if($Child.Process.StartInfo.FileName -cne $proof.exe -or $Child.Process.StartInfo.WorkingDirectory -cne $proof.cwd){Set-Reason 'asset-creator-changed';throw ([IO.InvalidDataException]::new())}
}

function Get-M21AssetBuildProof([ValidateSet('cp','javac','jar')][string]$Role) {
 $proof=$script:m21AssetCreators[$Role];$child=$proof.child
 Assert-M21AssetCreator $Role $child
 return @{owner=$proof.owner;nonce=$proof.nonce;sourceCommit=$proof.sourceCommit;exe=$proof.exe;cwd=$proof.cwd;argv=$proof.argv.Clone();pid=$child.RecordedPid;creation=$child.RecordedCreation;exitCode=0;stdoutPublished=$true;stderrPublished=$true;logs=$child.LogPrefix}
}

function Invoke-M21ProductionAssets {
 # 未接続・引数なしの固定caller。dummyや環境変数の有無からは呼ばない。
 $context=New-M21ProductionAssetContext
 $script:m21AssetCreators=@{}
 $local='C:/Users/kenta/AppData/Local';$cache='C:/Users/kenta/.gradle'
 $known=[Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
 if([IO.Path]::GetFullPath($known).TrimEnd('\') -ine [IO.Path]::GetFullPath($local).TrimEnd('\')){Set-Reason 'asset-machine-gate-mismatch';throw ([IO.InvalidDataException]::new())}
 foreach($path in @($local,$cache)){
  $directory=New-Object IO.DirectoryInfo($path)
  if(!$directory.Exists){Set-Reason 'asset-build-directory-missing';throw ([IO.InvalidDataException]::new())}
  while($null -ne $directory){if($directory.Attributes -band [IO.FileAttributes]::ReparsePoint){Set-Reason 'asset-build-directory-reparse';throw ([IO.InvalidDataException]::new())};$directory=$directory.Parent}
 }
 $environment=New-Object 'Collections.Generic.Dictionary[string,string]' ([StringComparer]::OrdinalIgnoreCase)
 foreach($key in @('SystemRoot','WINDIR')){$value=[Environment]::GetEnvironmentVariable($key);if($null -ne $value){$environment.Add($key,$value)}}
 $environment.Add('TEMP',$run);$environment.Add('TMP',$run);$environment.Add('HOME',$run);$environment.Add('USERPROFILE',$run)
 $environment.Add('LOCALAPPDATA',$local);$environment.Add('JAVA_HOME',$context.jdk);$environment.Add('GRADLE_USER_HOME',$cache)
 $environment.Add('PATH',($context.jdk+'/bin;C:/Program Files/Git/usr/bin;C:/Program Files/Git/cmd;C:/Windows/System32;C:/Windows'))
 $environment.Add('M21_BACKEND_DIR',$context.backend);$environment.Add('M21_CP_OUTPUT',$context.cpFile)
 $plan=$null
 foreach($role in @('cp','javac','jar')){
  Guard-Run;Require-Work
  if((Remaining -Recovery) -le 0){Set-Reason 'asset-total-budget-exhausted';throw ([TimeoutException]::new())}
  if($role -eq 'cp'){
   if(Test-Path -LiteralPath $context.cpFile){Set-Reason 'asset-cp-output-exists';throw ([IO.IOException]::new())}
   $exe=$context.bash
   $argv=[string[]]@('--noprofile','--norc','./scripts/gradle-turnstile.sh','./gradlew','--no-daemon','--offline','--console=plain',('-Dorg.gradle.java.home='+$context.jdk),'-Dorg.gradle.java.installations.auto-download=false','-I',$context.cpInit,'m21ProductionClasspath')
  }elseif($role -eq 'javac'){$exe=$plan.javac;$argv=[string[]]$plan.javacArguments.Clone()}
  else{$exe=$plan.jar;$argv=[string[]]$plan.jarArguments.Clone();if(Test-Path -LiteralPath $context.fixtureJar){Set-Reason 'asset-jar-output-exists';throw ([IO.IOException]::new())}}
  Require-Work
  $child=[M21Child]::Start($exe,$argv,$context.backend,(Join-Path $context.assets $role),(Remaining -Recovery),$false,$environment)
  $script:m21AssetCreators[$role]=@{child=$child;owner=$owner;nonce=$Nonce;sourceCommit=$context.sourceCommit;exe=$exe;cwd=$context.backend;argv=$argv.Clone()}
  Register-Child $child ('asset-'+$role)
  if(!$child.Process.WaitForExit((Remaining))){
   Set-Reason 'asset-work-timeout'
   # W到達ではEOF待ちより先に直接保持childの停止を始める。
   Attempt ('asset-'+$role+'-stop') {if(!$child.Process.HasExited){$child.Process.Kill()}}
   Capture $child ('asset-'+$role) 1000
   throw ([TimeoutException]::new())
  }
  Capture $child ('asset-'+$role) 1000
  Assert-M21AssetCreator $role $child
  if($role -eq 'cp'){
   $plan=Get-M21FixtureCompilePlan $child
   Release-M21AssetChild $child
  }elseif($role -eq 'jar'){
   $result=Confirm-M21FixtureJar $script:m21AssetCreators.javac.child $child
   Release-M21AssetChild $script:m21AssetCreators.javac.child
   Release-M21AssetChild $child
  }
 }
 return $result
}

function Get-M21AssetDirectoryFiles([string]$Path) {
 $cursor=New-Object IO.DirectoryInfo([IO.Path]::GetFullPath($Path))
 while($null -ne $cursor){if($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint){Set-Reason 'asset-parent-reparse';throw ([IO.InvalidDataException]::new())};$cursor=$cursor.Parent}
 $pending=New-Object 'Collections.Generic.Stack[IO.DirectoryInfo]'
 $pending.Push((New-Object IO.DirectoryInfo([IO.Path]::GetFullPath($Path))))
 while($pending.Count){
  Require-Work;$directory=$pending.Pop()
  if(!$directory.Exists -or ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint)){Set-Reason 'asset-directory-missing-or-reparse';throw ([IO.InvalidDataException]::new())}
  foreach($entry in $directory.GetFileSystemInfos()){
   Require-Work
   if($entry.Attributes -band [IO.FileAttributes]::ReparsePoint){Set-Reason 'asset-entry-reparse';throw ([IO.InvalidDataException]::new())}
   if($entry -is [IO.DirectoryInfo]){$pending.Push($entry)}else{$entry}
  }
 }
}

function Save-M21AssetManifest([ValidateSet('source-manifest.json','production-cp-manifest.json','fixture-manifest.json')][string]$Name,[object]$Value) {
 # 大きいread-only証拠だけ。16KiBのprotocol reader/writerは変更しない。
 Guard-Run;Require-Work
 $assetDirectory=New-Object IO.DirectoryInfo((Join-Path $run 'assets'))
 if($assetDirectory.Attributes -band [IO.FileAttributes]::ReparsePoint){Set-Reason 'asset-directory-reparse';throw ([IO.InvalidDataException]::new())}
 $assetOwner=Read-Json 'assets/owner.json'
 if($assetOwner.owner -cne $owner -or $assetOwner.nonce -cne $Nonce -or $assetOwner.purpose -cne 'm21-production-assets'){Set-Reason 'asset-owner-mismatch';throw ([IO.InvalidDataException]::new())}
 $bytes=$utf8.GetBytes(($Value|ConvertTo-Json -Depth 12 -Compress))
 if($bytes.Length -gt 16777216){Set-Reason 'asset-manifest-too-large';throw ([IO.InvalidDataException]::new())}
 $stream=New-Object IO.FileStream((Join-Path $assetDirectory.FullName $Name),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
 try{$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
}

function New-M21ProductionAssetContext {
 # 呼出しはACK後の固定Supervisorだけ。source/tool検査でありbuild起動ではない。
 if($Mode -cne 'Supervisor' -or ![M21Job]::InJob($self,$null)){Set-Reason 'asset-job-unverified';throw ([InvalidOperationException]::new())}
 Guard-Run;Require-Work;Set-Operation 'assets.source-preflight'
 $commit=[Environment]::GetEnvironmentVariable('M21_INPUT_COMMIT')
 if($commit -cnotmatch '^[a-f0-9]{40}$'){Set-Reason 'asset-source-commit-invalid';throw ([IO.InvalidDataException]::new())}
 $root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')
 $backend=Join-Path $root 'backend';$assets=Join-Path $run 'assets'
 if(Test-Path -LiteralPath $assets){Set-Reason 'asset-directory-already-exists';throw ([IO.IOException]::new())}
 New-Item -ItemType Directory -Path $assets -ErrorAction Stop > $null
 Write-Json 'assets/owner.json' @{owner=$owner;nonce=$Nonce;purpose='m21-production-assets';sourceCommit=$commit}
 $script:m21AssetCheckedDirectories=New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
 $jdk='C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot'
 $node='C:/Claude/mannschaft/.claude/runtime-assets/node22-d7249e88-6cfac601/node-v22.23.2-win-x64/node.exe'
 if($NodePath -cne $node){Set-Reason 'asset-node-path-mismatch';throw ([IO.InvalidDataException]::new())}
 $tools=@(Get-M21AssetFileRecord (Join-Path $jdk 'bin/java.exe');Get-M21AssetFileRecord (Join-Path $jdk 'bin/javac.exe');Get-M21AssetFileRecord (Join-Path $jdk 'bin/jar.exe');Get-M21AssetFileRecord $node)
 $expected=@('BD3D87DFB44651A1C61C995E08CA4FCADB458437A373D46941381F9AEFF9D6A0','5B36DA4D2778BBD20AFBC9D4B686B11488D7DD4A55A4BB003E6A97FD4B45BDA7','7727686E0A75726587042F8D046D6A0B48427F752AEB581C70B100085F6C3EBE','0D0F5E39F9F3D9587BC19F73EAB3C2C9C4903FD02D6DBF9C853DD81B3D95FAD4')
 for($index=0;$index -lt $tools.Count;$index++){if($tools[$index].sha256 -cne $expected[$index]){Set-Reason 'asset-tool-bytes-changed';throw ([IO.InvalidDataException]::new())}}
 $environment=New-Object 'Collections.Generic.Dictionary[string,string]' ([StringComparer]::OrdinalIgnoreCase)
 foreach($key in @('SystemRoot','WINDIR')){$value=[Environment]::GetEnvironmentVariable($key);if($null -ne $value){$environment.Add($key,$value)}}
 $environment.Add('TEMP',$run);$environment.Add('TMP',$run)
 $environment.Add('GIT_NO_LAZY_FETCH','1');$environment.Add('GIT_TERMINAL_PROMPT','0')
 $git='C:/Program Files/Git/cmd/git.exe'
 $auxiliaryTools=@(Get-M21AssetFileRecord $git;Get-M21AssetFileRecord 'C:/Program Files/Git/usr/bin/bash.exe')
 Require-Work
 $head=[M21Child]::Start($git,[string[]]@('--no-optional-locks','-c','core.fsmonitor=false','-C',$root,'rev-parse','HEAD'),$root,(Join-Path $assets 'git-head'),(Remaining -Recovery),$false,$environment);Register-Child $head 'asset-git-head'
 if(!$head.Process.WaitForExit((Remaining))){Set-Reason 'asset-git-timeout';Attempt 'asset-git-head-stop' {if(!$head.Process.HasExited){$head.Process.Kill()}};Capture $head 'asset-git-head' 1000;throw ([TimeoutException]::new())};Capture $head 'asset-git-head' 1000
 if($head.Process.ExitCode -ne 0 -or !$head.StdoutPublished -or !$head.StderrPublished -or [IO.File]::ReadAllText((Join-Path $assets 'git-head.out')).Trim() -cne $commit){Set-Reason 'asset-source-head-mismatch';throw ([IO.InvalidDataException]::new())}
 Release-M21AssetChild $head
 Require-Work
 $tree=[M21Child]::Start($git,[string[]]@('--no-optional-locks','-c','core.fsmonitor=false','-C',$root,'ls-tree','-r','-z','--long',$commit,'--','backend','.gitattributes','scripts/m21-production-cp.init.gradle','scripts/m21-windows-formal-adapter.ps1','scripts/m21-windows-supervisor.ps1'),$root,(Join-Path $assets 'git-tree'),(Remaining -Recovery),$false,$environment);Register-Child $tree 'asset-git-tree'
 if(!$tree.Process.WaitForExit((Remaining))){Set-Reason 'asset-git-timeout';Attempt 'asset-git-tree-stop' {if(!$tree.Process.HasExited){$tree.Process.Kill()}};Capture $tree 'asset-git-tree' 1000;throw ([TimeoutException]::new())};Capture $tree 'asset-git-tree' 1000
 if($tree.Process.ExitCode -ne 0 -or !$tree.StdoutPublished -or !$tree.StderrPublished){Set-Reason 'asset-source-tree-failed';throw ([IO.InvalidDataException]::new())}
 Release-M21AssetChild $tree
 $records=New-Object 'Collections.Generic.List[object]';$tracked=New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
 foreach($line in [IO.File]::ReadAllText((Join-Path $assets 'git-tree.out'),[Text.Encoding]::UTF8).Split([char]0)){
  if(!$line){continue};Require-Work
  if($line -cnotmatch '^(100644|100755) blob ([a-f0-9]{40}) +([0-9]+)\t(.+)$'){Set-Reason 'asset-git-entry-invalid';throw ([IO.InvalidDataException]::new())}
  $mode=$Matches[1];$blob=$Matches[2];$blobBytes=[long]::Parse($Matches[3]);$relative=$Matches[4]
  $path=[IO.Path]::GetFullPath((Join-Path $root $relative))
  if(!$path.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)){Set-Reason 'asset-source-path-escape';throw ([IO.InvalidDataException]::new())}
  if(!$tracked.Add($path)){Set-Reason 'asset-source-case-duplicate';throw ([IO.InvalidDataException]::new())}
  $record=Get-M21AssetFileRecord $path;$record.relative=$relative;$record.gitMode=$mode;$record.gitBlob=$blob;$record.gitBlobBytes=$blobBytes;$records.Add($record)
 }
 # mainだけを部分checkoutしてclassesへ進めない。focused testの入力も同じmanifestに含める。
 foreach($required in @('backend/build.gradle.kts','backend/settings.gradle.kts','backend/build-logic/build.gradle.kts','backend/gradle/wrapper/gradle-wrapper.jar','backend/src/test/resources/shard-weights.properties')){if(!$tracked.Contains([IO.Path]::GetFullPath((Join-Path $root $required)))){Set-Reason 'asset-source-manifest-incomplete';throw ([IO.InvalidDataException]::new())}}
 foreach($scope in @('main','test')){foreach($sourceFile in Get-M21AssetDirectoryFiles (Join-Path $backend ('src/'+$scope))){if(!$tracked.Contains($sourceFile.FullName)){Set-Reason 'asset-untracked-source';throw ([IO.InvalidDataException]::new())}}}
 Require-Work
 $diff=[M21Child]::Start($git,[string[]]@('--no-optional-locks','-c','core.fsmonitor=false','-C',$root,'diff','--quiet','--no-ext-diff','--no-textconv',$commit,'--','backend','.gitattributes','scripts/m21-production-cp.init.gradle','scripts/m21-windows-formal-adapter.ps1','scripts/m21-windows-supervisor.ps1'),$root,(Join-Path $assets 'git-diff'),(Remaining -Recovery),$false,$environment);Register-Child $diff 'asset-git-diff'
 if(!$diff.Process.WaitForExit((Remaining))){Set-Reason 'asset-git-timeout';Attempt 'asset-git-diff-stop' {if(!$diff.Process.HasExited){$diff.Process.Kill()}};Capture $diff 'asset-git-diff' 1000;throw ([TimeoutException]::new())};Capture $diff 'asset-git-diff' 1000
 if($diff.Process.ExitCode -ne 0 -or !$diff.StdoutPublished -or !$diff.StderrPublished){Set-Reason 'asset-source-diff';throw ([IO.InvalidDataException]::new())}
 Release-M21AssetChild $diff
 $fixtureSources=[string[]]@('Application','Fixtures','Cleanup'|ForEach-Object {Join-Path $backend ('src/test/java/com/mannschaft/app/moduleevent/e2e/ModuleEventRealE2e'+$_+'.java')})
 foreach($directory in @('fixture-classes','empty-sourcepath')){New-Item -ItemType Directory -Path (Join-Path $assets $directory) -ErrorAction Stop > $null}
 $context=[ordered]@{owner=$owner;nonce=$Nonce;sourceCommit=$commit;root=$root;backend=$backend;assets=$assets;jdk=$jdk;node=$node;tools=$tools;auxiliaryTools=$auxiliaryTools;sourceManifest=@($records.ToArray());sourceVerified=$true;offlineResolutionVerified=$false;productionCPVerified=$false;productionCPStructureVerified=$false;fixtureJARVerified=$false;fixtureJARStructureVerified=$false;cpFile=(Join-Path $assets 'production-cp.txt');classes=(Join-Path $assets 'fixture-classes');emptySourcePath=(Join-Path $assets 'empty-sourcepath');fixtureJar=(Join-Path $assets 'm21-fixtures.jar');fixtureSources=$fixtureSources;cpInit=(Join-Path $PSScriptRoot 'm21-production-cp.init.gradle');bash='C:/Program Files/Git/usr/bin/bash.exe'}
 Save-M21AssetManifest 'source-manifest.json' $context
 $script:m21ProductionAssets=$context
 return $script:m21ProductionAssets
}

function Get-M21FixtureCompilePlan([M21Child]$ProductionCpProcess) {
 Guard-Run;Require-Work;Set-Operation 'assets.production-cp'
 $context=$script:m21ProductionAssets
 Assert-M21AssetCreator 'cp' $ProductionCpProcess
 if($context.owner -cne $owner -or $context.nonce -cne $Nonce -or !$context.sourceVerified){Set-Reason 'asset-context-unverified';throw ([IO.InvalidDataException]::new())}
 if($null -eq $ProductionCpProcess -or !$ProductionCpProcess.Started -or !$ProductionCpProcess.Process.WaitForExit(0) -or $ProductionCpProcess.Process.ExitCode -ne 0 -or !$ProductionCpProcess.StdoutPublished -or !$ProductionCpProcess.StderrPublished -or $ProductionCpProcess.Process.StartInfo.FileName -cne $context.bash -or $ProductionCpProcess.Process.StartInfo.WorkingDirectory -ine $context.backend){Set-Reason 'asset-cp-process-unverified';throw ([IO.InvalidDataException]::new())}
 foreach($record in $context.sourceManifest){if((Get-M21AssetFileRecord $record.path).sha256 -cne $record.sha256){Set-Reason 'asset-source-changed';throw ([IO.InvalidDataException]::new())}}
 foreach($record in @($context.tools)+@($context.auxiliaryTools)){if((Get-M21AssetFileRecord $record.path).sha256 -cne $record.sha256){Set-Reason 'asset-tool-bytes-changed';throw ([IO.InvalidDataException]::new())}}
 $cpRecord=Get-M21AssetFileRecord $context.cpFile
 if($cpRecord.bytes -gt 1048576){Set-Reason 'asset-cp-too-large';throw ([IO.InvalidDataException]::new())}
 $cpBytes=[IO.File]::ReadAllBytes($context.cpFile)
 if($cpBytes.Length -ge 3 -and $cpBytes[0] -eq 239 -and $cpBytes[1] -eq 187 -and $cpBytes[2] -eq 191){Set-Reason 'asset-cp-bom';throw ([IO.InvalidDataException]::new())}
 $strictUtf8=New-Object Text.UTF8Encoding($false,$true);$cp=$strictUtf8.GetString($cpBytes)
 if(!$cp -or $cp[0] -eq [char]0xFEFF -or $cp.Contains("`r") -or $cp.Contains("`n")){Set-Reason 'asset-cp-format-invalid';throw ([IO.InvalidDataException]::new())}
 $entries=New-Object 'Collections.Generic.List[object]'
 $allowedDirectories=@([IO.Path]::GetFullPath((Join-Path $context.backend 'build/classes/java/main')),[IO.Path]::GetFullPath((Join-Path $context.backend 'build/resources/main')))
 foreach($entry in $cp.Split(';')){
  Require-Work
  if(![IO.Path]::IsPathRooted($entry)){Set-Reason 'asset-cp-relative';throw ([IO.InvalidDataException]::new())}
  $path=[IO.Path]::GetFullPath($entry)
  if(Test-Path -LiteralPath $path -PathType Container){
   if($path -notin $allowedDirectories -or ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)){Set-Reason 'asset-cp-foreign-directory';throw ([IO.InvalidDataException]::new())}
   $files=@(Get-M21AssetDirectoryFiles $path|Sort-Object FullName|ForEach-Object {Get-M21AssetFileRecord $_.FullName})
   if(!$files.Count){Set-Reason 'asset-cp-empty-main-output';throw ([IO.InvalidDataException]::new())};$entries.Add(@{path=$path;kind='main-output';files=$files})
  }else{
   if([IO.Path]::GetExtension($path) -cne '.jar' -or (!$path.StartsWith($context.backend+'\',[StringComparison]::OrdinalIgnoreCase) -and !$path.StartsWith('C:\Users\kenta\.gradle\caches\',[StringComparison]::OrdinalIgnoreCase))){Set-Reason 'asset-cp-foreign-jar';throw ([IO.InvalidDataException]::new())}
   $entries.Add((Get-M21AssetFileRecord $path))
  }
 }
 foreach($directory in $allowedDirectories){if($directory -notin @($entries|ForEach-Object {$_.path})){Set-Reason 'asset-cp-main-output-missing';throw ([IO.InvalidDataException]::new())}}
 if(@(Get-ChildItem -LiteralPath $context.emptySourcePath -Force).Count -or @(Get-ChildItem -LiteralPath $context.classes -Force).Count -or (Test-Path -LiteralPath $context.fixtureJar)){Set-Reason 'asset-fixture-output-not-new';throw ([IO.InvalidDataException]::new())}
 $outcomes=[ordered]@{}
 foreach($line in [IO.File]::ReadLines($ProductionCpProcess.LogPrefix+'.out')){
  Require-Work
  if($line -cmatch '^> Task :(compileJava|processResources|classes|m21ProductionClasspath)(?: (UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED|FAILED))?$'){
   $task=$Matches[1];$outcome='EXECUTED';if($Matches.ContainsKey(2)){$outcome=$Matches[2]}
   if($outcomes.Contains($task) -or $outcome -in @('NO-SOURCE','SKIPPED','FAILED')){Set-Reason 'asset-cp-task-unverified';throw ([IO.InvalidDataException]::new())}
   $outcomes[$task]=$outcome
  }
 }
 foreach($task in @('compileJava','processResources','classes','m21ProductionClasspath')){if(!$outcomes.Contains($task)){Set-Reason 'asset-cp-task-missing';throw ([IO.InvalidDataException]::new())}}
 if($outcomes.m21ProductionClasspath -cne 'EXECUTED'){Set-Reason 'asset-cp-task-not-executed';throw ([IO.InvalidDataException]::new())}
 Save-M21AssetManifest 'production-cp-manifest.json' @{owner=$owner;nonce=$Nonce;sourceCommit=$context.sourceCommit;cp=$cpRecord;entries=@($entries.ToArray());offlineResolutionVerified=$true;heldBuild=(Get-M21AssetBuildProof 'cp');taskOutcomes=$outcomes}
 $context.productionCPVerified=$true;$context.offlineResolutionVerified=$true
 $context.productionCPStructureVerified=$true;$context.productionCP=$cp;$context.cpRecord=$cpRecord;$context.cpEntries=@($entries.ToArray())
 return [ordered]@{javac=(Join-Path $context.jdk 'bin/javac.exe');javacArguments=[string[]](@('-J-Xmx512m','--release','21','-encoding','UTF-8','-parameters','-implicit:none','--source-path',$context.emptySourcePath,'-cp',$cp,'-d',$context.classes)+$context.fixtureSources);jar=(Join-Path $context.jdk 'bin/jar.exe');jarArguments=[string[]]@('--create','--file',$context.fixtureJar,'-C',$context.classes,'.')}
}

function Confirm-M21FixtureJar([M21Child]$JavacProcess,[M21Child]$JarProcess) {
 Guard-Run;Require-Work;Set-Operation 'assets.fixture-jar'
 $context=$script:m21ProductionAssets
 Assert-M21AssetCreator 'javac' $JavacProcess
 Assert-M21AssetCreator 'jar' $JarProcess
 if(!$context.productionCPVerified -or !$context.productionCPStructureVerified -or $context.owner -cne $owner -or $context.nonce -cne $Nonce){Set-Reason 'asset-cp-unverified';throw ([IO.InvalidDataException]::new())}
 if($null -eq $JavacProcess -or $null -eq $JarProcess){Set-Reason 'asset-fixture-process-missing';throw ([IO.InvalidDataException]::new())}
 foreach($build in @($JavacProcess,$JarProcess)){if(!$build.Started -or !$build.Process.WaitForExit(0) -or $build.Process.ExitCode -ne 0 -or !$build.StdoutPublished -or !$build.StderrPublished){Set-Reason 'asset-fixture-process-unverified';throw ([IO.InvalidDataException]::new())}}
 if($JavacProcess.Process.StartInfo.FileName -cne (Join-Path $context.jdk 'bin/javac.exe') -or $JarProcess.Process.StartInfo.FileName -cne (Join-Path $context.jdk 'bin/jar.exe')){Set-Reason 'asset-fixture-tool-mismatch';throw ([IO.InvalidDataException]::new())}
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $jar=Get-M21AssetFileRecord $context.fixtureJar;$zip=[IO.Compression.ZipFile]::OpenRead($context.fixtureJar)
 try{
  $names=@($zip.Entries|ForEach-Object {$_.FullName});$prefix='com/mannschaft/app/moduleevent/e2e/'
  foreach($name in $names){Require-Work;if($name.EndsWith('/')){if(!$prefix.StartsWith($name,[StringComparison]::Ordinal) -and $name -cne 'META-INF/'){Set-Reason 'asset-jar-entry-invalid';throw ([IO.InvalidDataException]::new())}}elseif($name -cne 'META-INF/MANIFEST.MF' -and $name -cnotmatch '^com/mannschaft/app/moduleevent/e2e/ModuleEventRealE2e(Application|Fixtures|Cleanup)(\$[^/]+)?\.class$'){Set-Reason 'asset-jar-entry-invalid';throw ([IO.InvalidDataException]::new())}}
  foreach($base in @('Application','Fixtures','Cleanup')){if(($prefix+'ModuleEventRealE2e'+$base+'.class') -cnotin $names){Set-Reason 'asset-jar-class-missing';throw ([IO.InvalidDataException]::new())}}
 }finally{$zip.Dispose()}
 if((Get-M21AssetFileRecord $context.cpFile).sha256 -cne $context.cpRecord.sha256){Set-Reason 'asset-cp-changed';throw ([IO.InvalidDataException]::new())}
 foreach($entry in $context.cpEntries){$files=@($entry);if($entry.Contains('files')){$files=$entry.files};foreach($file in $files){if((Get-M21AssetFileRecord $file.path).sha256 -cne $file.sha256){Set-Reason 'asset-cp-entry-changed';throw ([IO.InvalidDataException]::new())}}}
 foreach($record in @($context.sourceManifest)+@($context.tools)+@($context.auxiliaryTools)){if((Get-M21AssetFileRecord $record.path).sha256 -cne $record.sha256){Set-Reason 'asset-input-changed';throw ([IO.InvalidDataException]::new())}}
 Save-M21AssetManifest 'fixture-manifest.json' @{owner=$owner;nonce=$Nonce;sourceCommit=$context.sourceCommit;jar=$jar;entries=$names;productionCP=$context.cpRecord;fixtureSources=$context.fixtureSources;runtimeCP=($context.productionCP+';'+$context.fixtureJar);heldBuilds=@((Get-M21AssetBuildProof 'javac');(Get-M21AssetBuildProof 'jar'))}
 $context.fixtureJARStructureVerified=$true
 $context.fixtureJARVerified=$true
 return [ordered]@{jar=$jar;runtimeCP=($context.productionCP+';'+$context.fixtureJar);sourceCommit=$context.sourceCommit;formalRunQualified=$false}
}
