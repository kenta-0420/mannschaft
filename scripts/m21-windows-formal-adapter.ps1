# M21固定adapterの関数定義だけ。dot-source時に型初期化/子起動を行わない。
function Test-M21PendingExitRecovery([object]$ExpectedChild,[object]$ObservedChild,[Collections.IDictionary]$Proof,[bool]$RecoveryAllowed,[bool]$JobSatisfied,[int]$RemainingMs) {
 # last-handle専用。PID/名前の一致で保持参照を代用しない。
 if($null -eq $ExpectedChild -or ![object]::ReferenceEquals($ExpectedChild,$ObservedChild) -or !$RecoveryAllowed -or !$JobSatisfied -or $RemainingMs -le 0 -or $null -eq $Proof){return $false}
 foreach($key in @('initialExitObserved','initialPublicationAttempted')){if(!$Proof.Contains($key) -or $Proof[$key] -isnot [bool] -or $Proof[$key]){return $false}}
 foreach($key in @('exited','stdoutEof','stderrEof','stdoutWriterClosed','stderrWriterClosed','stdoutPublished','stderrPublished')){if(!$Proof.Contains($key) -or $Proof[$key] -isnot [bool] -or !$Proof[$key]){return $false}}
 return $true
}

function Get-M21FormalBudget {
 # 回収capの順は採用計画の9段。33秒はretry枠に転用しない。
 return @{totalMs=3300000;workMs=3000000;reserveMs=300000;recoveryCapsMs=@(90000,30000,10000,16000,12000,61000,14000,14000,20000);marginMs=33000;maxAppHeld=8;maxParentRelay=1;maxCleanupCli=14}
}

function Assert-M21ScenarioBudget([string]$ScenarioValue,[string]$ModeValue,[string]$FixtureValue,[int]$WorkMs,[int]$TotalMs) {
 if($WorkMs -lt 0 -or $TotalMs -lt 0 -or $WorkMs -gt $TotalMs){Set-Reason 'invalid-budget';throw ([ArgumentOutOfRangeException]::new())}
 if($ScenarioValue -ceq 'formal-m21'){
  $budget=Get-M21FormalBudget
  if($ModeValue -cnotin @('Parent','Supervisor') -or $FixtureValue -cne 'none' -or $WorkMs -gt $budget.workMs -or $TotalMs -gt $budget.totalMs -or $TotalMs-$WorkMs -lt $budget.reserveMs){Set-Reason 'formal-budget-or-mode-invalid';throw ([ArgumentOutOfRangeException]::new())}
 }elseif($TotalMs -gt 420000){Set-Reason 'dummy-budget-invalid';throw ([ArgumentOutOfRangeException]::new())}
}

function New-M21FormalSupervisorEnvironment {
 # ParentからSupervisorへの境界でも全親envを渡さない。
 $commit=[Environment]::GetEnvironmentVariable('M21_INPUT_COMMIT')
 if($commit -cnotmatch '^[a-f0-9]{40}$'){Set-Reason 'asset-source-commit-invalid';throw ([IO.InvalidDataException]::new())}
 $environment=New-Object 'Collections.Generic.Dictionary[string,string]' ([StringComparer]::OrdinalIgnoreCase)
 foreach($key in @('SystemRoot','WINDIR')){$value=[Environment]::GetEnvironmentVariable($key);if($null -ne $value){$environment.Add($key,$value)}}
 $environment.Add('TEMP',$run);$environment.Add('TMP',$run);$environment.Add('M21_INPUT_COMMIT',$commit)
 if($null -ne (Get-Variable m21FormalConnection -Scope Script -ErrorAction SilentlyContinue)){
  $connection=$script:m21FormalConnection
  $environment.Add('M21_DATABASE',$connection.database)
  $environment.Add('SPRING_DATASOURCE_URL',('jdbc:mysql://127.0.0.1:'+$connection.mysqlPort+'/'+$connection.database+'?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'))
  $environment.Add('SPRING_DATASOURCE_USERNAME','m21');$environment.Add('SPRING_DATASOURCE_PASSWORD',$connection.password)
  $environment.Add('SPRING_DATA_REDIS_HOST','127.0.0.1');$environment.Add('SPRING_DATA_REDIS_PORT',[string]$connection.valkeyPort)
 }
 return ,$environment
}

function New-M21FormalSecret {
 $bytes=New-Object byte[] 32;$random=[Security.Cryptography.RandomNumberGenerator]::Create()
 try{$random.GetBytes($bytes);return [Convert]::ToBase64String($bytes)}finally{$random.Dispose();[Array]::Clear($bytes,0,$bytes.Length)}
}

function Send-M21FormalBridgeFrame([Collections.IDictionary]$Frame,[int]$CapMs=1000) {
 # wrapper拒否でも前回frameのtrueを現在の試行へ混ぜない。
 if($null -ne $script:m21Bridge){$script:m21Bridge.BridgeWriteCompleted=$false;$script:m21Bridge.BridgeFlushCompleted=$false;$script:m21Bridge.BridgeInputStage='wrapper-guard'}
 if($null -eq $script:m21Bridge -or (Remaining -Recovery) -le 0){throw 'bridge送信期限/実体不在'}
 $bytes=$utf8.GetBytes(($Frame|ConvertTo-Json -Depth 8 -Compress))
 if($bytes.Length -lt 1 -or $bytes.Length -gt 16384){throw 'bridge frame上限'}
 $packet=New-Object byte[] ($bytes.Length+4)
 $packet[0]=[byte](($bytes.Length -shr 24)-band 255);$packet[1]=[byte](($bytes.Length -shr 16)-band 255);$packet[2]=[byte](($bytes.Length -shr 8)-band 255);$packet[3]=[byte]($bytes.Length-band 255)
 [Array]::Copy($bytes,0,$packet,4,$bytes.Length)
 try{$script:m21Bridge.SendBridgeFrame($packet,(Remaining $CapMs -Recovery))}
 finally{[Array]::Clear($bytes,0,$bytes.Length);[Array]::Clear($packet,0,$packet.Length)}
}

function Assert-M21FormalBridgeDocument([object]$Document,[string]$Type) {
 if($Document.version -isnot [int] -or $Document.version -ne 1 -or $Document.type -cne $Type -or $Document.owner -cne $owner -or $Document.nonce -cne $Nonce -or $Document.run -cne $script:m21BridgeRun -or $Document.inputCommit -cne [Environment]::GetEnvironmentVariable('M21_INPUT_COMMIT') -or $Document.bridgeSourceSha256 -cne $script:m21BridgeSourceSha -or $Document.pythonSha256 -cnotmatch '^[a-f0-9]{64}$' -or $Document.dockerSha256 -cnotmatch '^[a-f0-9]{64}$' -or ($Document.t0_ns -isnot [long] -and $Document.t0_ns -isnot [int]) -or $Document.t0_ns -le 0 -or $Document.boot_id -cnotmatch '^[a-f0-9-]{36}$' -or $Document.pid_namespace -cnotmatch '^pid:\[[0-9]+\]$'){Set-Reason 'formal-bridge-document-invalid';throw ([IO.InvalidDataException]::new())}
 if($null -ne $script:m21BridgeReady){
  foreach($key in @('t0_ns','boot_id','pid_namespace','socket_dev','socket_ino','pythonSha256','dockerSha256')){if($Document.$key -cne $script:m21BridgeReady.$key){throw 'bridge namespace/tool世代変更'}}
 }
}

function Wait-M21FormalBridgeFile([string]$Name,[int]$CapMs,[switch]$Recovery) {
 if($Name -cnotmatch '^(ready|initialized|bound|bridge-exit|request-[0-9]{5}(-observed|-started)?|preflight-[123](-observed)?)\.json$'){throw 'bridge固定receipt名不一致'}
 $end=$localClock.ElapsedMilliseconds+(Remaining $CapMs -Recovery:$Recovery)
 while($localClock.ElapsedMilliseconds -lt $end -and (Remaining -Recovery:$Recovery) -gt 0){
  if(Test-Path -LiteralPath (Join-Path $run ('bridge/'+$Name))){return (Read-Json ('bridge/'+$Name))}
  if($script:m21Bridge.Process.WaitForExit(0)){throw 'bridge早期終了'}
  Start-Sleep -Milliseconds ([Math]::Min(25,[Math]::Max(0,$end-$localClock.ElapsedMilliseconds)))
 }
 Set-Reason 'formal-bridge-receipt-timeout';throw ([TimeoutException]::new())
}

function Assert-M21FormalDescriptor([object]$Descriptor) {
 $fields=@($Descriptor.PSObject.Properties.Name)
 if($fields.Count -ne 3 -or @($fields|Where-Object {$_ -cnotin @('daemon_id','mysql_image_id','valkey_image_id')}).Count -or $Descriptor.daemon_id -isnot [string] -or $Descriptor.daemon_id.Length -lt 1 -or $Descriptor.daemon_id.Length -gt 256 -or $Descriptor.daemon_id -cmatch '[\x00-\x20\x7f]' -or $Descriptor.mysql_image_id -cnotmatch '^sha256:[a-f0-9]{64}$' -or $Descriptor.valkey_image_id -cnotmatch '^sha256:[a-f0-9]{64}$'){throw 'bridge descriptor不正'}
}

function Start-M21FormalBridge {
 Require-Work;Guard-Run
 $temporary=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
 if($temporary -ine 'C:\Users\kenta\AppData\Local\Temp' -or [IO.Path]::GetDirectoryName($run) -ine $temporary -or [IO.Path]::GetFileName($run) -cnotmatch '^m21-windows-[A-Za-z0-9-]+$'){throw 'formal bridge専用Temp配置不一致'}
 $commit=[Environment]::GetEnvironmentVariable('M21_INPUT_COMMIT')
 if($commit -cnotmatch '^[a-f0-9]{40}$'){throw 'formal input HEAD不足'}
 $bridgePath=Join-Path $PSScriptRoot 'm21-wsl-docker-bridge.py'
 if(!(Test-Path -LiteralPath $bridgePath -PathType Leaf)){Set-Reason 'formal-runtime-not-connected';throw ([IO.FileNotFoundException]::new())}
 $script:m21BridgeSourceSha=(Get-FileHash -LiteralPath $bridgePath -Algorithm SHA256).Hash.ToLowerInvariant()
 $wslSha=(Get-FileHash -LiteralPath 'C:/Windows/System32/wsl.exe' -Algorithm SHA256).Hash
 $script:m21BridgeRun=[Guid]::NewGuid().ToString('D')
 $script:m21BridgeReady=$null;$script:m21BridgeActiveRequest=$null;$script:m21BridgeRequestId=0;$script:m21FormalPendingMutation=$null
 $script:m21BridgeReceipts=New-Object 'Collections.Generic.List[string]'
 $bridgeDirectory=Join-Path $run 'bridge'
 if(Test-Path -LiteralPath $bridgeDirectory){throw 'bridge directory既存'}
 [IO.Directory]::CreateDirectory($bridgeDirectory)>$null
 Write-Json 'bridge/owner.json' @{owner=$owner;nonce=$Nonce;run=$script:m21BridgeRun;purpose='m21-docker-bridge'}
 $environment=New-Object 'Collections.Generic.Dictionary[string,string]' ([StringComparer]::OrdinalIgnoreCase)
 foreach($key in @('SystemRoot','WINDIR')){$value=[Environment]::GetEnvironmentVariable($key);if($null -ne $value){$environment.Add($key,$value)}}
 $environment.Add('TEMP',$run);$environment.Add('TMP',$run)
 $linuxSource='/mnt/c/'+$bridgePath.Substring(3).Replace('\','/')
 $linuxRun='/mnt/c/'+$bridgeDirectory.Substring(3).Replace('\','/')
 Require-Work
 $script:m21Bridge=[M21Child]::StartBridge([string[]]@('-d','Ubuntu-24.04','--exec','/usr/bin/python3','-I',$linuxSource,'--run-directory',$linuxRun,'--owner',$owner,'--run',$script:m21BridgeRun,'--nonce',$Nonce,'--input-commit',$commit),$PSScriptRoot,(Join-Path $run 'bridge-relay'),(Remaining -Recovery),$environment)
 Register-Child $script:m21Bridge 'formal-bridge'
 if([IO.Path]::GetFullPath($script:m21Bridge.Process.MainModule.FileName) -ine 'C:\Windows\System32\wsl.exe'){throw '保持bridge実exe不一致'}
 Write-Json 'formal-bridge-launcher.json' @{owner=$owner;nonce=$Nonce;run=$script:m21BridgeRun;identity=(Identity $script:m21Bridge.Process);exe=$script:m21Bridge.Process.MainModule.FileName;exeSha256=$wslSha;arguments=$script:m21Bridge.Process.StartInfo.Arguments;bridgeSourceSha256=$script:m21BridgeSourceSha;inputCommit=$commit}
 $ready=Wait-M21FormalBridgeFile 'ready.json' 60000
 Assert-M21FormalBridgeDocument $ready 'ready'
 if($ready.ready -ne $true){throw 'bridge ready未確認'}
 $script:m21BridgeReady=$ready
 # Linux t0はready受信より前。単一Parent時計からの残量だけで絶対offsetを固定する。
 $script:m21BridgeOriginTicks=$localClock.ElapsedTicks
 $script:m21BridgeGlobalOffsetMs=Remaining -Recovery;$script:m21BridgeWorkOffsetMs=Remaining
 Send-M21FormalBridgeFrame @{type='init';owner=$owner;run=$script:m21BridgeRun;global_offset_ms=$script:m21BridgeGlobalOffsetMs;work_offset_ms=$script:m21BridgeWorkOffsetMs}
 $initialized=Wait-M21FormalBridgeFile 'initialized.json' 15000
 Assert-M21FormalBridgeDocument $initialized 'initialized'
 if($initialized.green -ne $true -or $initialized.preflight.count -ne 3 -or $initialized.preflight.green -ne $true -or (($initialized.preflight.ids)-join ',') -cne '1,2,3'){throw 'bridge preflight未確認'}
 Assert-M21FormalDescriptor $initialized.descriptor
 foreach($number in @(1,2,3)){
  $observed=Read-Json ('bridge/preflight-'+$number+'-observed.json')
  Assert-M21FormalBridgeDocument $observed 'preflight-observed'
  if($observed.preflight_id -ne $number -or $observed.green -ne $true -or $observed.watchdog_exited -ne $true -or $observed.watchdog_exit -ne 0){throw 'bridge preflight実終了不明'}
 }
 $script:m21BridgeDescriptor=$initialized.descriptor
 Send-M21FormalBridgeFrame @{type='bind';owner=$owner;run=$script:m21BridgeRun;descriptor=$script:m21BridgeDescriptor}
 $bound=Wait-M21FormalBridgeFile 'bound.json' 2000
 Assert-M21FormalBridgeDocument $bound 'bound'
 if($bound.green -ne $true){throw 'bridge bind未確認'}
 foreach($key in @('daemon_id','mysql_image_id','valkey_image_id')){if($bound.descriptor.$key -cne $script:m21BridgeDescriptor.$key){throw 'bridge bind pin不一致'}}
}

function Get-M21FormalBridgeDeadlineOffset([int]$CapMs,[switch]$Recovery) {
 $ticks=[long]$localClock.ElapsedTicks-[long]$script:m21BridgeOriginTicks
 if($ticks -lt 0 -or $ticks -gt 9223372036854775){throw 'bridge QPC discontinuity'}
 $remainder=[long]0
 # 二つのms値を別々にfloorしない。QPC差分を一度だけ整数除算して短縮側へ丸める。
 $elapsed=[Math]::DivRem([long]($ticks*1000),[long][Diagnostics.Stopwatch]::Frequency,[ref]$remainder)
 $bound=[long]$script:m21BridgeWorkOffsetMs;if($Recovery){$bound=[long]$script:m21BridgeGlobalOffsetMs}
 $remaining=Remaining $CapMs -Recovery:$Recovery
 if($elapsed -lt 0 -or $remaining -le 0 -or $bound -le $elapsed){throw 'bridge deadline exhausted'}
 return [long][Math]::Min($bound,$elapsed+$remaining)
}

function Assert-M21WslRelayStarted([object]$Started,[long]$UpperDeadlineNs) {
 if($Started.owner -cne $owner -or $Started.nonce -cne $Nonce -or $Started.run -cne $script:m21BridgeRun -or $Started.request_id -ne 1 -or $Started.operation -cne 'daemon-info' -or $Started.resource -cne 'daemon' -or $Started.session_acknowledged -isnot [bool] -or !$Started.session_acknowledged -or $Started.deadline_ns -le 0 -or $Started.deadline_ns -gt $UpperDeadlineNs){throw 'relay fixture開始receipt不一致'}
 foreach($identity in @($Started.leader_identity,$Started.watchdog_identity)){
  if(($identity.pid -isnot [int] -and $identity.pid -isnot [long]) -or $identity.pid -le 0 -or ($identity.pgid -isnot [int] -and $identity.pgid -isnot [long]) -or $identity.pgid -le 0 -or $identity.startticks -isnot [string] -or $identity.startticks -cnotmatch '^[1-9][0-9]*$' -or $identity.boot_id -cne $script:m21BridgeReady.boot_id -or $identity.pid_namespace -cne $script:m21BridgeReady.pid_namespace){throw 'relay fixture Linux世代不一致'}
 }
}

function Set-M21WslRelayFixturePhase([ValidateSet('ready-adopted','init-write-flush-completed','initialized-adopted','descriptor-validated','bind-send-started','bind-write-flush-completed','bound-wait','bound','request-started','kill-issued')][string]$Phase) {
 if($script:fixtureEvidence.phaseObservations.Count -ge 10){throw 'B4固定phase上限'}
 $script:fixtureEvidence.phase=$Phase
 $script:fixtureEvidence.phaseObservations.Add(@{phase=$Phase;elapsedTicks=[long]$localClock.ElapsedTicks})
 Set-Operation ('B4.'+$Phase)
}

function Invoke-M21WslRelayLossFixture {
 # fixed test CLIだけ。外部Docker境界をdummyに置換しLinux fork/pidfd/reap/EOFは実物。
 Guard-Run;Require-Work
 $commit=[Environment]::GetEnvironmentVariable('M21_INPUT_COMMIT')
 if($commit -cnotmatch '^[a-f0-9]{40}$' -or [IO.Path]::GetDirectoryName($run) -ine 'C:\Users\kenta\AppData\Local\Temp' -or [IO.Path]::GetFileName($run) -cnotmatch '^m21-windows-[A-Za-z0-9_-]+$'){throw 'relay fixture固定入力不一致'}
 $source=Join-Path $PSScriptRoot 'm21-wsl-docker-bridge.py';$test=Join-Path $PSScriptRoot 'test_m21_wsl_docker_bridge.py'
 $script:m21BridgeSourceSha=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
 $testSha=(Get-FileHash -LiteralPath $test -Algorithm SHA256).Hash.ToLowerInvariant()
 $wslSha=(Get-FileHash -LiteralPath 'C:/Windows/System32/wsl.exe' -Algorithm SHA256).Hash
 $script:m21BridgeRun=[Guid]::NewGuid().ToString('D');$script:m21BridgeReady=$null
 $directory=Join-Path $run 'bridge'
 if(Test-Path -LiteralPath $directory){throw 'fixture directory既存'}
 [IO.Directory]::CreateDirectory($directory)>$null
 Write-Json 'bridge/owner.json' @{owner=$owner;nonce=$Nonce;run=$script:m21BridgeRun;purpose='m21-docker-bridge'}
 $environment=New-Object 'Collections.Generic.Dictionary[string,string]' ([StringComparer]::OrdinalIgnoreCase)
 foreach($key in @('SystemRoot','WINDIR')){$value=[Environment]::GetEnvironmentVariable($key);if($null -ne $value){$environment.Add($key,$value)}}
 $environment.Add('TEMP',$run);$environment.Add('TMP',$run)
 $linuxTest='/mnt/c/'+$test.Substring(3).Replace('\','/');$linuxDirectory='/mnt/c/'+$directory.Substring(3).Replace('\','/')
 Require-Work
 $relay=[M21Child]::StartBridge([string[]]@('-d','Ubuntu-24.04','--exec','/usr/bin/python3','-I',$linuxTest,'--relay-fixture','--run-directory',$linuxDirectory,'--owner',$owner,'--run',$script:m21BridgeRun,'--nonce',$Nonce,'--input-commit',$commit),$PSScriptRoot,(Join-Path $run 'relay'),(Remaining -Recovery),$environment)
 $script:m21Bridge=$relay
 Register-Child $relay 'wsl-relay-loss-fixture'
 $script:fixtureEvidence=@{fixture='wsl-relay-loss';passed=$false;primaryRelayQualified=$false;primaryReason='intentional-relay-loss';formalRunQualified=$false;realDockerInvoked=$false;inputCommit=$commit;bridgeSourceSha256=$script:m21BridgeSourceSha;fixtureSourceSha256=$testSha;arguments=$relay.Process.StartInfo.Arguments;windows=$null;linux=$null;linuxBridgeActualExitObserved=$false;linuxWatchdogActualExitObserved=$false;retained=$run}
 $script:fixtureEvidence.phase=$null
 $script:fixtureEvidence.phaseObservations=New-Object 'Collections.Generic.List[object]'
 $script:fixtureEvidence.stopwatchFrequency=[long][Diagnostics.Stopwatch]::Frequency
 $script:fixtureEvidence.stdin=@{codePage=$relay.BridgeInputCodePage;preambleLength=$relay.BridgeInputPreambleLength;initWriteCompleted=$false;initFlushCompleted=$false}
 if([IO.Path]::GetFullPath($relay.Process.MainModule.FileName) -ine 'C:\Windows\System32\wsl.exe'){throw 'fixture保持exe不一致'}
 $ready=Wait-M21FormalBridgeFile 'ready.json' 15000
 Assert-M21FormalBridgeDocument $ready 'ready';$script:m21BridgeReady=$ready
 Set-M21WslRelayFixturePhase 'ready-adopted'
 $script:m21BridgeOriginTicks=$localClock.ElapsedTicks
 $script:m21BridgeGlobalOffsetMs=Remaining 4000 -Recovery;$script:m21BridgeWorkOffsetMs=Remaining 3000
 try{Send-M21FormalBridgeFrame @{type='init';owner=$owner;run=$script:m21BridgeRun;global_offset_ms=$script:m21BridgeGlobalOffsetMs;work_offset_ms=$script:m21BridgeWorkOffsetMs}}
 finally{$script:fixtureEvidence.stdin.initWriteCompleted=$relay.BridgeWriteCompleted;$script:fixtureEvidence.stdin.initFlushCompleted=$relay.BridgeFlushCompleted;$script:fixtureEvidence.stdin.initStage=$relay.BridgeInputStage}
 Set-M21WslRelayFixturePhase 'init-write-flush-completed'
 $initialized=Wait-M21FormalBridgeFile 'initialized.json' 2000
 Assert-M21FormalBridgeDocument $initialized 'initialized'
 Set-M21WslRelayFixturePhase 'initialized-adopted'
 $descriptor=$initialized.descriptor
 if($descriptor.daemon_id -cne 'fixture-daemon' -or $descriptor.mysql_image_id -cne ('sha256:'+('a'*64)) -or $descriptor.valkey_image_id -cne ('sha256:'+('b'*64))){throw 'fixture descriptor不一致'}
 Set-M21WslRelayFixturePhase 'descriptor-validated'
 $script:fixtureEvidence.bindSend=@{stage='not-attempted';writeCompleted=$false;flushCompleted=$false}
 Set-M21WslRelayFixturePhase 'bind-send-started'
 try{Send-M21FormalBridgeFrame @{type='bind';owner=$owner;run=$script:m21BridgeRun;descriptor=$descriptor}}
 finally{$script:fixtureEvidence.bindSend.stage=$relay.BridgeInputStage;$script:fixtureEvidence.bindSend.writeCompleted=$relay.BridgeWriteCompleted;$script:fixtureEvidence.bindSend.flushCompleted=$relay.BridgeFlushCompleted}
 Set-M21WslRelayFixturePhase 'bind-write-flush-completed'
 Set-M21WslRelayFixturePhase 'bound-wait'
 $bound=Wait-M21FormalBridgeFile 'bound.json' 1000
 Assert-M21FormalBridgeDocument $bound 'bound'
 Set-M21WslRelayFixturePhase 'bound'
 $offset=Get-M21FormalBridgeDeadlineOffset 1000 -Recovery
 Send-M21FormalBridgeFrame @{type='request';owner=$owner;run=$script:m21BridgeRun;request_id=1;phase='cleanup';operation='daemon-info';resource='daemon';deadline_offset_ms=$offset}
 $started=Wait-M21FormalBridgeFile 'request-00001-started.json' 1000 -Recovery
 Assert-M21FormalBridgeDocument $started 'request-started'
 Assert-M21WslRelayStarted $started ([long]$ready.t0_ns+$offset*1000000)
 Set-M21WslRelayFixturePhase 'request-started'
 Assert-M21FormalRequiredAlive @($relay.Process)
 $identity=Identity $relay.Process
 $script:fixtureEvidence.windows=@{identity=$identity;exe=$relay.Process.MainModule.FileName;exeSha256=$wslSha;killIssued=$false;actualExited=$false;exitCode=$null;stdoutEof=$false;stderrEof=$false}
 if(Test-Path -LiteralPath (Join-Path $directory 'request-00001.json')){throw 'relay kill前にCLI回収完了済み'}
 if(([long]$localClock.ElapsedTicks-[long]$script:m21BridgeOriginTicks)*1000 -ge $offset*[long][Diagnostics.Stopwatch]::Frequency){throw 'relay kill前に固定期限到達'}
 # 保存済みの同じProcessだけ。Windows relay終了をLinux CLI終了へ読み替えない。
 $relay.Process.Kill()
 $script:fixtureEvidence.windows.killIssued=$true
 Set-M21WslRelayFixturePhase 'kill-issued'
 if(!$relay.Process.WaitForExit((Remaining 2000 -Recovery))){throw 'held relay actual exit不明'}
 Capture $relay 'wsl-relay-loss-primary' 500
 $aliveRejected=$false;try{Assert-M21FormalRequiredAlive @($relay.Process)}catch{$aliveRejected=$true}
 if(!$aliveRejected){throw '終了済み保持世代をRequiredAliveに採用'}
 $captured=$streams[$streams.Count-1]
 $script:fixtureEvidence.windows.actualExited=$captured.exited;$script:fixtureEvidence.windows.exitCode=$captured.exitCode
 $script:fixtureEvidence.windows.stdoutEof=$captured.stdoutEof;$script:fixtureEvidence.windows.stderrEof=$captured.stderrEof
 $receiptEnd=$localClock.ElapsedMilliseconds+(Remaining 5000 -Recovery)
 while(!(Test-Path -LiteralPath (Join-Path $directory 'request-00001.json')) -and $localClock.ElapsedMilliseconds -lt $receiptEnd){Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining -Recovery)))}
 $physical=Read-Json 'bridge/request-00001.json';Assert-M21FormalBridgeDocument $physical 'request'
 if($physical.request_id -ne 1 -or $physical.operation -cne 'daemon-info' -or $physical.resource -cne 'daemon' -or $physical.deadline_ns -ne $started.deadline_ns){throw 'independent Linux receipt不一致'}
 $linux=$physical.linux
 $script:fixtureEvidence.linux=@{started=$started;primaryGreen=$linux.green;primaryChildrenExited=$linux.primary_children_exited;primaryStdoutEof=$linux.primary_stdout_eof;primaryStderrEof=$linux.primary_stderr_eof;cliExit=$linux.cli_exit;childrenReaped=$linux.children_reaped;stdoutEof=$linux.stdout_eof;stderrEof=$linux.stderr_eof;writeClosed=$linux.write_closed;reason=$linux.reason;forceCount=$linux.force_count;reapedPids=$linux.reaped_pids;leaderIdentity=$linux.leader_identity}
 if(Test-Path -LiteralPath (Join-Path $directory 'request-00001-observed.json')){
  $observed=Read-Json 'bridge/request-00001-observed.json'
  Assert-M21FormalBridgeDocument $observed 'observed'
  $script:fixtureEvidence.linuxWatchdogActualExitObserved=($observed.watchdog_exited -eq $true -and $observed.watchdog_exit -eq 0)
 }
 if(!$captured.exited -or !$captured.stdoutEof -or !$captured.stderrEof -or $linux.green -ne $false -or $linux.children_reaped -ne $true -or $linux.stdout_eof -ne $true -or $linux.stderr_eof -ne $true -or $linux.write_closed -ne $true){throw 'relay loss独立回収未確認'}
 if((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -cne $script:m21BridgeSourceSha -or (Get-FileHash -LiteralPath $test -Algorithm SHA256).Hash.ToLowerInvariant() -cne $testSha){throw 'fixture実行中source変更'}
 $script:fixtureEvidence.passed=$true
 Write-Json 'wsl-relay-loss-evidence.json' $script:fixtureEvidence
}

function Invoke-M21FormalDockerRequest([ValidateSet('daemon-info','volume-create','volume-inspect','container-create','container-inspect','container-start','container-stop','container-remove','volume-remove','resources-absent')][string]$Operation,[ValidateSet('mysql','valkey','daemon')][string]$Resource,[ValidateSet('work','cleanup')][string]$Phase,[int]$CapMs=3000) {
 Guard-Run
 if(($Operation -ceq 'daemon-info') -ne ($Resource -ceq 'daemon')){throw '固定operation/resource不一致'}
 if($null -ne $script:m21BridgeActiveRequest){throw '前CLI未回収。追加spawn拒否'}
 if($Phase -ceq 'work'){Require-Work}
 if((Remaining -Recovery) -le 0){throw 'G0 bridge request拒否'}
 $offset=Get-M21FormalBridgeDeadlineOffset $CapMs -Recovery:($Phase -ceq 'cleanup')
 $script:m21BridgeRequestId++
 $id=$script:m21BridgeRequestId;$script:m21BridgeActiveRequest=$id
 $mutating=($Phase -ceq 'work' -and $Operation -cin @('volume-create','container-create','container-start'))
 if($mutating){$script:m21FormalPendingMutation=@{requestId=$id;operation=$Operation;resource=$Resource}}
 $frame=@{type='request';owner=$owner;run=$script:m21BridgeRun;request_id=$id;phase=$Phase;operation=$Operation;resource=$Resource;deadline_offset_ms=$offset}
 if($Operation -ceq 'container-create' -and $Resource -ceq 'mysql'){$frame.secrets=@{mysql_root_password=$script:m21FormalRootPassword;mysql_password=$script:m21FormalAppPassword}}
 $bodyEnd=$localClock.ElapsedMilliseconds+$CapMs
 Send-M21FormalBridgeFrame $frame
 $name='request-'+$id.ToString('00000');$observedName=$name+'-observed.json'
 $cancelled=$false
 while(!(Test-Path -LiteralPath (Join-Path $run ('bridge/'+$observedName))) -and $localClock.ElapsedMilliseconds -lt $bodyEnd+2000 -and (Remaining -Recovery) -gt 0){
  if($localClock.ElapsedMilliseconds -ge $bodyEnd -and !$cancelled){
   $cancelled=$true
   Send-M21FormalBridgeFrame @{type='cancel';owner=$owner;run=$script:m21BridgeRun;request_id=$id} 100
  }
  if($script:m21Bridge.Process.WaitForExit(0)){throw 'bridge要求中relay終了'}
  Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining -Recovery)))
 }
 $physical=Read-Json ('bridge/'+$name+'.json');$observed=Read-Json ('bridge/'+$observedName)
 Assert-M21FormalBridgeDocument $physical 'request';Assert-M21FormalBridgeDocument $observed 'observed'
 foreach($document in @($physical,$observed)){
  if($document.request_id -ne $id -or $document.operation -cne $Operation -or $document.resource -cne $Resource -or $document.deadline_ns -gt ([long]$script:m21BridgeReady.t0_ns+$offset*1000000)){throw 'bridge要求receipt不一致'}
 }
 if($physical.phase -cne $Phase){throw 'bridge要求phase不一致'}
 if(($physical.PSObject.Properties.Name -ccontains 'not_spawned') -and $physical.not_spawned -eq $true){
  if($observed.not_spawned -ne $true -or $physical.green -ne $false -or $observed.green -ne $false -or $physical.code -cnotin @('admission-denied','ownership-unverified','daemon-unconfirmed') -or $observed.code -cne $physical.code -or $null -ne $physical.linux -or $null -ne $physical.childproof -or $null -ne $observed.watchdog_exited -or $null -ne $observed.watchdog_exit -or $null -ne $observed.result){throw 'typed deny receipt不一致'}
  $script:m21BridgeActiveRequest=$null
  if($mutating){$script:m21FormalPendingMutation=$null}
  $script:m21BridgeReceipts.Add('bridge/'+$observedName)
  Set-Reason ('formal-bridge-'+$physical.code)
  # 未生成の子にactual exit/EOFを作らず、他roleの回収要求は継続できる。
  throw ([InvalidOperationException]::new())
 }
 $linux=$physical.linux
 if($observed.watchdog_exited -eq $true -and $observed.watchdog_exit -eq 0 -and $linux.children_reaped -eq $true -and $linux.stdout_eof -eq $true -and $linux.stderr_eof -eq $true -and $linux.write_closed -eq $true){$script:m21BridgeActiveRequest=$null}
 $script:m21BridgeReceipts.Add('bridge/'+$observedName)
 if($null -ne $script:m21BridgeActiveRequest -or $observed.green -ne $true){throw 'bridge CLI/回収未確認'}
 if($Operation -ceq 'resources-absent'){
  if($linux.physical_completed -ne $true -or $linux.cli_exit -ne 1 -or $observed.result.absence_candidate -ne $true -or $observed.result.absent -ne $false -or $observed.result.requires_same_daemon_after -ne $true){throw 'typed不存在candidate不成立'}
 }elseif($linux.green -ne $true -or $linux.cli_exit -ne 0){throw 'bridge CLI非0/実子/各EOF不成立'}
 if($mutating){$script:m21FormalPendingMutation=$null}
 return $observed
}

function New-M21FormalResources {
 $script:m21FormalRootPassword=New-M21FormalSecret;$script:m21FormalAppPassword=New-M21FormalSecret
 $ports=@{}
 foreach($role in @('mysql','valkey')){
  $null=Invoke-M21FormalDockerRequest 'volume-create' $role 'work'
  $null=Invoke-M21FormalDockerRequest 'volume-inspect' $role 'work'
  $null=Invoke-M21FormalDockerRequest 'container-create' $role 'work'
  $null=Invoke-M21FormalDockerRequest 'container-inspect' $role 'work'
  $null=Invoke-M21FormalDockerRequest 'container-start' $role 'work'
  $inspection=Invoke-M21FormalDockerRequest 'container-inspect' $role 'work'
  $mapping=$inspection.result.loopback_port
  if($null -eq $mapping -or @($mapping.PSObject.Properties.Name).Count -ne 2 -or $mapping.HostIp -cne '127.0.0.1' -or $mapping.HostPort -isnot [string] -or $mapping.HostPort -cnotmatch '^[0-9]{1,5}$'){throw '専用loopback mapping不正'}
  $port=[int]$mapping.HostPort
  if($port -lt 1 -or $port -gt 65535 -or ($role -ceq 'valkey' -and $port -eq 6379)){throw '専用loopback port不正'}
  $ports[$role]=[int]$port
  if($role -ceq 'valkey'){
   $socket=$inspection.result.socket_proof
   if($inspection.result.run_id -isnot [string] -or $inspection.result.run_id -cnotmatch '^[a-f0-9]{40}$' -or $socket.transport -cne 'owned-loopback-info' -or $socket.green -ne $true -or $socket.response_complete -ne $true -or $socket.socket_closed -ne $true -or $socket.within_deadline -ne $true -or $socket.cli_spawned -ne $false -or $null -ne $socket.childproof){throw 'Linux Valkey同一性未確認'}
   $valkeyRunId=$inspection.result.run_id;$valkeySocketProof=$socket
  }
 }
 $script:m21FormalConnection=@{database=('m21_'+$script:m21BridgeRun.Replace('-',''));mysqlPort=$ports.mysql;valkeyPort=$ports.valkey;password=$script:m21FormalAppPassword}
 Write-Json 'formal-resources-ready.json' @{owner=$owner;nonce=$Nonce;run=$script:m21BridgeRun;descriptor=$script:m21BridgeDescriptor;database=$script:m21FormalConnection.database;mysqlPort=$ports.mysql;valkeyPort=$ports.valkey;valkeyRunId=$valkeyRunId;valkeySocketProof=$valkeySocketProof;secretsPublished=$false}
}

function Confirm-M21FormalValkeyEndpoint([int]$Port,[string]$ExpectedRunId) {
 Require-Work
 $script:m21LastValkeyProbe=$null
 $script:m21LastValkeyProbe=[M21Child]::ProbeValkeyEndpoint($Port,$ExpectedRunId,$localClock,$WorkRemainingMs)
 if($script:m21LastValkeyProbe.socket_proof.green -ne $true){Set-Reason 'formal-valkey-endpoint-unverified';throw ([IO.InvalidDataException]::new())}
 return $script:m21LastValkeyProbe
}

function Stop-M21FormalExternalResources {
 $confirmed=$true
 if($null -eq $script:m21Bridge){return}
 # R14: 同daemon2、所有inspect4、stop2、rm4、不存在candidate2。各要求を回収後だけ次へ進む。
 $operations=@(@('daemon-info','daemon',3000),@('container-inspect','mysql',3000),@('container-inspect','valkey',3000),@('volume-inspect','mysql',3000),@('volume-inspect','valkey',3000),@('container-stop','mysql',15000),@('container-stop','valkey',10000),@('container-remove','mysql',3000),@('container-remove','valkey',3000),@('volume-remove','mysql',3000),@('volume-remove','valkey',3000),@('resources-absent','mysql',3000),@('resources-absent','valkey',3000),@('daemon-info','daemon',3000))
 $last=$null
 foreach($item in $operations){
  try{$last=Invoke-M21FormalDockerRequest $item[0] $item[1] 'cleanup' $item[2]}
  catch{$confirmed=$false;Fail ('formal-docker-'+$item[0]+'-'+$item[1]) $_.Exception $_}
 }
 try{
  if($null -eq $last -or $last.operation -cne 'daemon-info' -or $last.result.daemon_confirmed -ne $true -or @($last.result.confirmed_absent_resources).Count -ne 2){throw '最終同daemon/両資源不存在未確認'}
  $roles=@($last.result.confirmed_absent_resources|ForEach-Object {$_.role}|Sort-Object)
  if(($roles-join ',') -cne 'mysql,valkey'){throw '最終不存在role不一致'}
  foreach($entry in $last.result.confirmed_absent_resources){$name='m21-'+$script:m21BridgeRun.Replace('-','')+'-'+$entry.role;if($entry.container -cne $name -or $entry.volume -cne ($name+'-data')){throw '最終不存在name不一致'}}
 }catch{$confirmed=$false;Fail 'formal-docker-final-absence' $_.Exception $_}
 if($null -ne $script:m21FormalPendingMutation){$confirmed=$false;Fail 'formal-docker-work-result-unconfirmed' ([InvalidOperationException]::new())}
 try{if((Remaining -Recovery) -gt 0 -and !$script:m21Bridge.Process.WaitForExit(0)){Send-M21FormalBridgeFrame @{type='shutdown';owner=$owner;run=$script:m21BridgeRun} 100}}
 catch{$confirmed=$false;Fail 'formal-bridge-shutdown' $_.Exception $_}
 try{
  $script:m21Bridge.CloseBridgeInput()
  if(!$script:m21Bridge.Process.WaitForExit((Remaining 2000 -Recovery))){throw 'bridge actual exit期限'}
  Capture $script:m21Bridge 'formal-bridge' 500
  $exit=Read-Json 'bridge/bridge-exit.json';Assert-M21FormalBridgeDocument $exit 'bridge-exit'
  if($exit.green -isnot [bool] -or $exit.reason -cne 'shutdown' -or $exit.linux_watchdog_retained -isnot [bool] -or $exit.linux_watchdog_retained){throw 'Linux bridge終端未確認'}
  $expectedExit=0;if(!$exit.green){$expectedExit=1}
  if($script:m21Bridge.Process.ExitCode -ne $expectedExit){throw 'bridge relay通常終端code不一致'}
  # 先行workの失敗と、後続14件の資源回収の事実を混同しない。
  $script:formalEntryEvidence.bridgeOperationalGreen=$exit.green
 }catch{$confirmed=$false;Fail 'formal-bridge-exit-and-streams' $_.Exception $_}
 $script:formalEntryEvidence.externalRecovery=@{confirmed=$confirmed;receipts=@($script:m21BridgeReceipts.ToArray());bridgeRun=$script:m21BridgeRun;unconfirmedMutation=$script:m21FormalPendingMutation;retained=$run;formalRunQualified=$false}
 $script:m21FormalRootPassword=$null;$script:m21FormalAppPassword=$null
 if(!$confirmed){$script:recovered=$false;throw '正式外部資源回収未確認'}
}

function New-M21FormalHostEnvironment {
 $connection=$script:m21FormalConnection
 if($connection.database -cnotmatch '^m21_[a-f0-9]{32}$' -or $connection.mysqlPort -isnot [int] -or $connection.valkeyPort -isnot [int] -or $connection.mysqlPort -lt 1 -or $connection.mysqlPort -gt 65535 -or $connection.valkeyPort -lt 1 -or $connection.valkeyPort -gt 65535 -or $connection.valkeyPort -eq 6379 -or $connection.password -isnot [string] -or $connection.password.Length -lt 16){Set-Reason 'formal-private-connection-invalid';throw ([IO.InvalidDataException]::new())}
 $environment=New-Object 'Collections.Generic.Dictionary[string,string]' ([StringComparer]::OrdinalIgnoreCase)
 foreach($key in @('SystemRoot','WINDIR')){$value=[Environment]::GetEnvironmentVariable($key);if($null -ne $value){$environment.Add($key,$value)}}
 $fixed=@{
  TEMP=$run;TMP=$run;SPRING_PROFILES_ACTIVE='local';SERVER_ADDRESS='127.0.0.1';SERVER_PORT='8081'
  SPRING_DATASOURCE_URL=('jdbc:mysql://127.0.0.1:'+$connection.mysqlPort+'/'+$connection.database+'?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC')
  SPRING_DATASOURCE_USERNAME='m21';SPRING_DATASOURCE_PASSWORD=$connection.password
  SPRING_DATA_REDIS_HOST='127.0.0.1';SPRING_DATA_REDIS_PORT=[string]$connection.valkeyPort
  MANNSCHAFT_ALLOWED_ORIGINS='http://localhost:3003,http://localhost:8081'
  M21_RUN_DIR=$run;M21_FRONTEND_DIR=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../frontend'));M21_NODE_EXE=$NodePath
  M21_DATABASE=$connection.database;M21_PRODUCTION_CP=$script:m21ProductionAssets.productionCP;M21_FIXTURE_JAR=$script:m21ProductionAssets.fixtureJar
  M21_RESIDENT_HELPER=$script:m21FormalHelper;M21_RESIDENT_HELPER_SHA256=$script:m21FormalHelperSha
  PLAYWRIGHT_BROWSERS_PATH='C:/Users/kenta/AppData/Local/ms-playwright';BASE_URL='http://localhost:3003'
  MANNSCHAFT_EMAIL_SIMULATE='true';SES_NOTIFICATION_QUEUE_NAME='';MANNSCHAFT_SQS_ENABLED='false'
  MANNSCHAFT_R2_ENDPOINT='http://127.0.0.1:8081';MANNSCHAFT_R2_ACCESS_KEY='m21-owned-test';MANNSCHAFT_R2_SECRET_KEY=(New-M21FormalSecret)
  MANNSCHAFT_SES_ENDPOINT='http://127.0.0.1:8081';M21_OWN_LOOPBACK_URLS='http://127.0.0.1:8081'
  AWS_ACCESS_KEY_ID='m21-owned-test';AWS_SECRET_ACCESS_KEY=(New-M21FormalSecret);AWS_EC2_METADATA_DISABLED='true';AWS_REGION='ap-northeast-1'
  MANNSCHAFT_ERROR_REPORT_SLACK_WEBHOOK_URL='';MANAGEMENT_TRACING_ENABLED='false';OTEL_SDK_DISABLED='true';OTEL_EXPORTER_OTLP_ENDPOINT='http://127.0.0.1:8081/v1/traces'
  MANNSCHAFT_JWT_SECRET=(New-M21FormalSecret);MANNSCHAFT_ENCRYPTION_KEY=(New-M21FormalSecret);MANNSCHAFT_HMAC_KEY=(New-M21FormalSecret);MANNSCHAFT_INTERNAL_SIGNING_KEY=(New-M21FormalSecret)
  JOB_QR_SIGNING_SECRET=(New-M21FormalSecret)
 }
 if([string]::IsNullOrWhiteSpace($fixed.JOB_QR_SIGNING_SECRET) -or [Text.Encoding]::UTF8.GetByteCount([string]$fixed.JOB_QR_SIGNING_SECRET) -lt 32){Set-Reason 'formal-qr-signing-secret-invalid';throw ([IO.InvalidDataException]::new())}
 foreach($key in $fixed.Keys){$environment.Add($key,[string]$fixed[$key])}
 return ,$environment
}

function Assert-M21FormalRequiredAlive([Diagnostics.Process[]]$Processes) {
 if($null -eq $Processes -or $Processes.Count -lt 1 -or $Processes.Count -gt 8){Set-Reason 'formal-required-alive-set-invalid';throw ([IO.InvalidDataException]::new())}
 foreach($process in $Processes){
  # 呼出し元の保持handleのみ。取得不能を終了扱いせずPIDで取り直さない。
  if($null -eq $process -or $process.Handle -eq [IntPtr]::Zero -or $process.WaitForExit(0)){Set-Reason 'formal-required-alive-exited';throw ([InvalidOperationException]::new())}
 }
}

function Initialize-M21FormalParent {
 Guard-Run;Require-Work
 $script:formalEntryEvidence=@{budget=(Get-M21FormalBudget);assetConnected=$true;productionAssetsVerified=$false;bridgeConnected=$false;applicationConnected=$false;formalRunQualified=$false;retainedReason='bridge-create-and-application-not-connected'}
 $script:m21FormalCertified=@{}
 $script:m21FormalRunId=$null
 $script:m21FormalCloseAdmitted=$false
 $script:m21FormalCleanupStartedMs=$null
 Assert-M21FormalPortsFree
 Start-M21FormalBridge
 New-M21FormalResources
 $script:formalEntryEvidence.bridgeConnected=$true
}

function Add-M21FormalCertifiedMember([object]$Candidate) {
 $role=$Candidate.role
 if($role -cnotin @('frontend','frontend-listener','host','helper','system','admin-de','member') -or $script:m21FormalCertified.ContainsKey($role) -or $script:m21FormalCertified.Count -ge 7){throw '正式保持role集合不一致'}
 Assert-P6Identity $Candidate.identity
 $exe=$NodePath
 if($role -ceq 'host'){$exe='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin\java.exe'}
 if($role -cin @('system','admin-de','member')){$exe=$chromiumExe}
 if([IO.Path]::GetFullPath($Candidate.exe) -ine [IO.Path]::GetFullPath($exe)){throw '正式candidate実体不一致'}
 $process=Open-Known $Candidate.identity $job
 try{
  Assert-M21FormalRequiredAlive @($process)
  if([IO.Path]::GetFullPath($process.MainModule.FileName) -ine [IO.Path]::GetFullPath($exe)){throw '正式保持実体不一致'}
  if(@($script:m21FormalCertified.Values|Where-Object {$_.identity.pid -eq $Candidate.identity.pid}).Count){throw '正式保持PID重複'}
  if($role -cin @('system','admin-de','member')){
   if($Candidate.profile -cne (Join-Path (Join-Path $run 'profiles') $role) -or $Candidate.title -cne ('M21-'+$Nonce+'-'+$role)){throw '正式browser profile/title不一致'}
   if((Get-Item -LiteralPath $Candidate.profile).Attributes -band [IO.FileAttributes]::ReparsePoint){throw '正式browser profile reparse'}
  }
  $certified.Add($process)
  $script:m21FormalCertified[$role]=@{process=$process;identity=$Candidate.identity;candidate=$Candidate}
 }catch{$process.Dispose();throw}
}

function Update-M21FormalParent {
 if(!$script:m21FormalCertified.ContainsKey('host') -and (Test-Path -LiteralPath (Join-Path $run 'formal-app-candidates.json'))){
  $candidate=Read-Json 'formal-app-candidates.json'
  if($candidate.owner -cne $owner -or $candidate.nonce -cne $Nonce -or @($candidate.members).Count -notin @(2,3) -or (($candidate.members|ForEach-Object {$_.role})-join ',') -cnotin @('frontend,host','frontend,host,frontend-listener')){throw '正式App candidate集合不一致'}
  foreach($member in $candidate.members){Add-M21FormalCertifiedMember $member}
  Write-Json 'formal-app-certified.json' @{owner=$owner;nonce=$Nonce;ownedJobVerified=$true}
 }
 if(!$script:m21FormalCertified.ContainsKey('helper') -and (Test-Path -LiteralPath (Join-Path $run 'formal-helper-candidate.json'))){
  $candidate=Read-Json 'formal-helper-candidate.json'
  if($candidate.owner -cne $owner -or $candidate.nonce -cne $Nonce -or $candidate.runId -cnotmatch '^m21-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$' -or !$script:m21FormalCertified.ContainsKey('host')){throw '正式helper candidate不一致'}
  $candidate|Add-Member -NotePropertyName role -NotePropertyValue 'helper'
  Add-M21FormalCertifiedMember $candidate
  $proof=Confirm-M21FormalJavaGeneration $script:m21FormalCertified.helper.process $candidate.javaIso
  if(!$proof.matched -or $candidate.ready.pid -ne $candidate.identity.pid -or $candidate.ready.runId -cne $candidate.runId){throw '正式helper Java世代不一致'}
  $script:m21FormalRunId=$candidate.runId
  $script:m21FormalHelperReady=$candidate.ready
  Write-Json 'formal-helper-certified.json' @{owner=$owner;nonce=$Nonce;runId=$candidate.runId;ownedJobVerified=$true;generation=$proof}
 }
 if(!$script:m21FormalCertified.ContainsKey('system') -and (Test-Path -LiteralPath (Join-Path $run 'formal-browser-candidates.json'))){
  $candidate=Read-Json 'formal-browser-candidates.json'
  if($candidate.owner -cne $owner -or $candidate.nonce -cne $Nonce -or $candidate.runId -cne $script:m21FormalRunId -or @($candidate.members).Count -ne 3 -or (($candidate.members|ForEach-Object {$_.role})-join ',') -cne 'system,admin-de,member' -or !$script:m21FormalCertified.ContainsKey('helper')){throw '正式browser candidate不一致'}
  foreach($member in $candidate.members){Add-M21FormalCertifiedMember $member}
  $members=@($script:m21FormalCertified.Values|ForEach-Object {$_.candidate})
  Write-Json 'formal-ready.json' @{owner=$owner;nonce=$Nonce;runId=$script:m21FormalRunId;phase='exploration-ready';certifier=(Identity ([Diagnostics.Process]::GetCurrentProcess()));members=$members;helper=$script:m21FormalHelperReady;formalRunQualified=$false}
 }
 if(!$script:m21FormalCloseAdmitted -and (Test-Path -LiteralPath (Join-Path $run 'formal-browser-close-admitted.json'))){
  $admitted=Read-Json 'formal-browser-close-admitted.json'
  if($admitted.owner -cne $owner -or $admitted.nonce -cne $Nonce -or $admitted.runId -cne $script:m21FormalRunId -or $admitted.phase -cne 'browser-close-admitted'){throw 'browser close admission不一致'}
  Assert-M21FormalBrowserCloseRequest (Read-Json 'formal-browser-close-requested.json') $script:m21FormalRunId
  $script:m21FormalCloseAdmitted=$true
 }
 $finalMarkerPresent=($null -ne $script:m21FormalRunId -and (Test-Path -LiteralPath (Join-Path $run ($script:m21FormalRunId+'/controls/exploration-finished.json'))))
 if($null -eq $script:m21FormalCleanupStartedMs -and ($finalMarkerPresent -or (Test-Path -LiteralPath (Join-Path $run 'formal-host-cleanup-started.json')))){
  $closed=Read-Json 'formal-browsers-closed.json'
  if(!$script:m21FormalCloseAdmitted -or $closed.owner -cne $owner -or $closed.nonce -cne $Nonce -or $closed.runId -cne $script:m21FormalRunId -or $closed.actualExitAndStreamsConfirmed -ne $true){throw 'host cleanup admission不一致'}
  if(Test-Path -LiteralPath (Join-Path $run 'formal-host-cleanup-started.json')){
   $cleanup=Read-Json 'formal-host-cleanup-started.json'
   if($cleanup.owner -cne $owner -or $cleanup.nonce -cne $Nonce -or $cleanup.runId -cne $script:m21FormalRunId -or $cleanup.phase -cne 'HOST_CLEANUP'){throw 'host cleanup phase不一致'}
  }
  foreach($role in @('system','admin-de','member')){if(!$script:m21FormalCertified[$role].process.WaitForExit(0) -or $script:m21FormalCertified[$role].process.ExitCode -ne 0){throw 'browser actual0未完了'}}
  $inner=$script:m21FormalRunId
  Assert-M21FormalExplorationFinished (Read-Json ($inner+'/controls/exploration-finished.json')) $inner (Read-Json ($inner+'/exploration-helper-state.json')) $script:m21FormalCertified.helper.process.Id
  $script:m21FormalCleanupStartedMs=$localClock.ElapsedMilliseconds
 }
 foreach($role in $script:m21FormalCertified.Keys){
  if($role -cin @('system','admin-de','member') -and $script:m21FormalCloseAdmitted){continue}
  if($null -ne $script:m21FormalCleanupStartedMs){
   if($role -cin @('host','helper')){continue}
   $hostProcess=$script:m21FormalCertified.host.process
   if($hostProcess.WaitForExit(0) -and $hostProcess.ExitCode -eq 0 -and (Test-Path -LiteralPath (Join-Path $run ($script:m21FormalRunId+'/host-result.json')))){
    $hostResult=Read-Json ($script:m21FormalRunId+'/host-result.json')
    if($hostResult.passed -eq $true -and $hostResult.contextClosed -eq $true){continue}
   }
  }
  Assert-M21FormalRequiredAlive @($script:m21FormalCertified[$role].process)
 }
}

function Wait-M21FormalSupervisor([M21Child]$Supervisor) {
 while(!$Supervisor.Process.WaitForExit((Remaining 25 -Recovery))){
  Update-M21FormalParent
  Assert-M21FormalRequiredAlive @($script:m21Bridge.Process)
  if($localClock.ElapsedMilliseconds -ge 600000 -and !(Test-Path -LiteralPath (Join-Path $run 'formal-host-admitted.json'))){Set-Reason 'formal-admission-timeout';throw ([TimeoutException]::new())}
  if($null -eq $script:m21FormalCleanupStartedMs){Require-Work}
  elseif($localClock.ElapsedMilliseconds-$script:m21FormalCleanupStartedMs -ge 120000){Set-Reason 'formal-cleanup-grace-exhausted';throw ([TimeoutException]::new())}
  if((Remaining -Recovery) -le 0){Set-Reason 'formal-total-budget-exhausted';throw ([TimeoutException]::new())}
 }
}

function Invoke-M21FormalWork {
 Guard-Run;Require-Work
 Set-Operation 'Supervisor.formal-connection'
 $database=[Environment]::GetEnvironmentVariable('M21_DATABASE')
 $dsn=[Environment]::GetEnvironmentVariable('SPRING_DATASOURCE_URL')
 if($database -cnotmatch '^m21_[a-f0-9]{32}$' -or $dsn -cnotmatch ('^jdbc:mysql://127\.0\.0\.1:([0-9]{1,5})/'+$database+'\?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC$') -or [Environment]::GetEnvironmentVariable('SPRING_DATASOURCE_USERNAME') -cne 'm21' -or [Environment]::GetEnvironmentVariable('SPRING_DATA_REDIS_HOST') -cne '127.0.0.1'){Set-Reason 'formal-runtime-not-connected';throw ([IO.InvalidDataException]::new())}
 $mysqlPort=[int]$Matches[1]
 $redis=[Environment]::GetEnvironmentVariable('SPRING_DATA_REDIS_PORT')
 if($redis -cnotmatch '^[0-9]{1,5}$'){Set-Reason 'formal-private-connection-invalid';throw ([IO.InvalidDataException]::new())}
 $script:m21FormalConnection=@{database=$database;mysqlPort=$mysqlPort;valkeyPort=[int]$redis;password=[Environment]::GetEnvironmentVariable('SPRING_DATASOURCE_PASSWORD')}
 Assert-M21FormalPortsFree
 $helperSource=Get-M21AssetFileRecord (Join-Path $PSScriptRoot 'm21-resident-helper.cjs')
 if($helperSource.bytes -gt 65536){throw 'helper上限超過'}
 $script:m21FormalHelper=Join-Path $run 'm21-resident-helper.cjs'
 [IO.File]::Copy($helperSource.path,$script:m21FormalHelper,$false)
 $script:m21FormalHelperSha=(Get-FileHash -LiteralPath $script:m21FormalHelper -Algorithm SHA256).Hash
 if($script:m21FormalHelperSha -cne $helperSource.sha256){throw 'helper copy不一致'}
 $environment=New-M21FormalHostEnvironment
 $feEnvironment=@{TEMP=$run;TMP=$run}
 foreach($key in @('SystemRoot','WINDIR')){if($environment.ContainsKey($key)){$feEnvironment[$key]=$environment[$key]}}
 $frontend=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../frontend'))
 $spec=New-M21FormalFrontendLaunchSpec $frontend $NodePath $feEnvironment
 Confirm-M21FormalFrontendAssets $frontend
 Set-Operation 'Supervisor.formal-frontend'
 $fe=Start-M21FormalAdmittedChild 'frontend' $spec;Register-Child $fe 'formal-fe'
 $listener=Wait-M21FormalListener 3003 $fe.Process $NodePath
 $script:m21FormalAuxiliary=New-Object 'Collections.Generic.List[Diagnostics.Process]'
 if(![object]::ReferenceEquals($listener,$fe.Process)){$script:m21FormalAuxiliary.Add($listener)}
 $script:m21FormalAdmissionRequired=@($fe.Process,$listener)
 $response=$null
 while($null -eq $response){
  Require-Work;Assert-M21FormalRequiredAlive @($fe.Process,$listener)
  $http=[Net.HttpWebRequest]::Create('http://127.0.0.1:3003/')
  $http.Timeout=Remaining 1000;$http.AllowAutoRedirect=$false
  try{$response=$http.GetResponse();$script:formalEntryEvidence.frontendHttpStatus=[int]$response.StatusCode}
  catch [Net.WebException]{
   if($null -ne $_.Exception.Response){$response=$_.Exception.Response;$script:formalEntryEvidence.frontendHttpStatus=[int]$response.StatusCode}
   elseif($_.Exception.Status -ne [Net.WebExceptionStatus]::Timeout){throw}
   # 自己Nuxtの初回compile待ちだけ。Parentの10分admission/Wを延長しない。
  }
  finally{if($null -ne $response){$response.Dispose()};$http.Abort()}
 }
 Set-Operation 'Supervisor.formal-java'
 $resources=Read-Json 'formal-resources-ready.json'
 if($resources.owner -cne $owner -or $resources.nonce -cne $Nonce -or $resources.database -cne $database -or $resources.valkeyPort -ne [int]$redis -or $resources.valkeyRunId -cnotmatch '^[a-f0-9]{40}$'){throw 'Valkey endpoint正本不一致'}
 $script:formalEntryEvidence.valkeyEndpoint=Confirm-M21FormalValkeyEndpoint ([int]$redis) $resources.valkeyRunId
 $java=Join-Path $script:m21ProductionAssets.jdk 'bin/java.exe'
 Require-Work
 Assert-M21FormalRequiredAlive @($fe.Process,$listener)
 $javaChild=[M21Child]::Start($java,[string[]]@('-Xmx1g','-XX:+UseG1GC','-Dcom.mysql.cj.disableAbandonedConnectionCleanup=true',('-Djava.io.tmpdir='+$run),'-cp',($script:m21ProductionAssets.productionCP+';'+$script:m21ProductionAssets.fixtureJar),'com.mannschaft.app.moduleevent.e2e.ModuleEventRealE2eApplication'),$script:m21ProductionAssets.backend,(Join-Path $run 'formal-host'),(Remaining -Recovery),$false,$environment)
 Register-Child $javaChild 'formal-host'
 $be=Wait-M21FormalListener 8081 $javaChild.Process $java
 if(![object]::ReferenceEquals($be,$javaChild.Process)){$be.Dispose();throw 'Java listener別PID'}
 $members=@(@{role='frontend';identity=(Identity $fe.Process);exe=$NodePath},@{role='host';identity=(Identity $javaChild.Process);exe=$java})
 if(![object]::ReferenceEquals($listener,$fe.Process)){$members+=@{role='frontend-listener';identity=(Identity $listener);exe=$NodePath}}
 Write-Json 'formal-app-candidates.json' @{owner=$owner;nonce=$Nonce;members=$members}
 $null=Wait-M21FormalCertificate 'formal-app-certified.json'
 $inner=$null
 while($null -eq $inner){
  Require-Work;Assert-M21FormalRequiredAlive @($fe.Process,$javaChild.Process,$listener)
  $candidates=@(Get-ChildItem -LiteralPath $run -Directory |Where-Object {$_.Name -cmatch '^m21-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'})
  if($candidates.Count -gt 1){throw 'inner run複数'}
  if($candidates.Count -eq 1){
   if($candidates[0].Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'inner run reparse'}
   $inner=$candidates[0].FullName
  }else{Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining)))}
 }
 $innerName=[IO.Path]::GetFileName($inner)
 Write-Json 'formal-host-admitted.json' @{owner=$owner;nonce=$Nonce;runId=$innerName;host=(Identity $javaChild.Process)}
 while(!(Test-Path -LiteralPath (Join-Path $inner 'exploration-ready.json'))){
  Require-Work;Assert-M21FormalRequiredAlive @($fe.Process,$javaChild.Process,$listener)
  Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining)))
 }
 Set-Operation 'Supervisor.formal-helper'
 $ui=Read-Json ($innerName+'/ui-result.json')
 if($ui.passed -isnot [bool] -or !$ui.passed -or !(Test-Path -LiteralPath (Join-Path $inner 'final-state.json'))){throw 'formal UI完了不明'}
 $helperOwner=Read-Json ($innerName+'/resident-helper-owner.json');$ready=Read-Json ($innerName+'/exploration-ready.json')
 if($helperOwner.runId -cne $innerName -or [IO.Path]::GetFullPath($helperOwner.executable) -ine [IO.Path]::GetFullPath($NodePath) -or $ready.runId -cne $innerName -or $ready.pid -ne $helperOwner.pid -or $ready.creation -cne $helperOwner.creation -or $ready.pipe -cnotmatch '^\\\\\.\\pipe\\m21-' -or (($ready.actorCodes|Sort-Object)-join ',') -cne 'admin-de,member,system'){throw 'helper metadata不一致'}
 $helper=[Diagnostics.Process]::GetProcessById([int]$helperOwner.pid)
 $script:m21FormalAuxiliary.Add($helper)
 $generation=Confirm-M21FormalJavaGeneration $helper $helperOwner.creation
 if(!$generation.matched -or [IO.Path]::GetFullPath($helper.MainModule.FileName) -ine [IO.Path]::GetFullPath($NodePath) -or ![M21Job]::InJob($helper,$null)){throw 'helper世代/実体/Job不一致'}
 Write-Json 'formal-helper-candidate.json' @{owner=$owner;nonce=$Nonce;runId=$innerName;identity=(Identity $helper);javaIso=$helperOwner.creation;exe=$NodePath;ready=$ready}
 $null=Wait-M21FormalCertificate 'formal-helper-certified.json'
 Set-Operation 'Supervisor.formal-browsers'
 $browsers=Start-M21FormalBrowsers
 $script:formalEntryEvidence.applicationConnected=$true
 $script:formalEntryEvidence.bridgeConnected=$true
 $script:formalEntryEvidence.runId=$innerName
 $script:formalEntryEvidence.retainedReason='root-evidence-and-owned-raw-review-required'
 $members=@($browsers|ForEach-Object {$_.candidate})
 Write-Json 'formal-browser-candidates.json' @{owner=$owner;nonce=$Nonce;runId=$innerName;members=$members}
 $null=Wait-M21FormalCertificate 'formal-ready.json'
 Set-Operation 'Supervisor.formal-exploration'
 $required=@($fe.Process,$javaChild.Process,$listener,$helper)+@($browsers|ForEach-Object {$_.child.Process})
 while(!(Test-Path -LiteralPath (Join-Path $run 'formal-browser-close-requested.json'))){
  Require-Work;Assert-M21FormalRequiredAlive $required
  if(Test-Path -LiteralPath (Join-Path $inner 'controls/exploration-finished.json')){throw 'browser回収前の探索完了marker'}
  Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining)))
 }
 Set-Operation 'Supervisor.formal-browser-close'
 $request=Read-Json 'formal-browser-close-requested.json'
 Assert-M21FormalBrowserCloseRequest $request $innerName
 # rootはこのreceiptを観測後だけcloseする。markercheck/RequiredAliveのraceを閉じる。
 Assert-M21FormalRequiredAlive $required
 Write-Json 'formal-browser-close-admitted.json' @{owner=$owner;nonce=$Nonce;runId=$innerName;phase='browser-close-admitted';actorCodes=@('system','admin-de','member')}
 foreach($browser in $browsers){
  while(!$browser.child.Process.WaitForExit((Remaining 25))){Require-Work;Assert-M21FormalRequiredAlive @($fe.Process,$javaChild.Process,$listener,$helper)}
  if($browser.child.Process.ExitCode -ne 0){throw 'browser通常exit非0'}
  Capture $browser.child ('formal-browser-'+$browser.candidate.role) 1000
 }
 Write-Json 'formal-browsers-closed.json' @{owner=$owner;nonce=$Nonce;runId=$innerName;actualExitAndStreamsConfirmed=$true}
 $finishedName=$innerName+'/controls/exploration-finished.json'
 while(!(Test-Path -LiteralPath (Join-Path $run $finishedName))){
  Require-Work
  if($helper.WaitForExit(0) -or $javaChild.Process.WaitForExit(0)){
   # Javaが先にmarkerを読みhelper cleanupへ進んだ場合だけfresh readへ戻る。
   if(!(Test-Path -LiteralPath (Join-Path $run $finishedName))){throw '探索完了前のhelper/host終了'}
   break
  }
  Assert-M21FormalRequiredAlive @($fe.Process,$listener)
  Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining)))
 }
 $finished=Read-Json $finishedName;$state=Read-Json ($innerName+'/exploration-helper-state.json')
 Assert-M21FormalExplorationFinished $finished $innerName $state $helper.Id
 Set-Operation 'Supervisor.formal-host-cleanup'
 Write-Json 'formal-host-cleanup-started.json' @{owner=$owner;nonce=$Nonce;runId=$innerName;phase='HOST_CLEANUP'}
 if(!$javaChild.Process.WaitForExit((Remaining 90000 -Recovery))){Set-Reason 'host-cleanup-unconfirmed';throw ([TimeoutException]::new())}
 Capture $javaChild 'formal-host' 1000
 $hostResult=Read-Json ($innerName+'/host-result.json')
 if($javaChild.Process.ExitCode -ne 0 -or $hostResult.passed -ne $true -or $hostResult.formalPassed -ne $true -or $hostResult.explorationCompleted -ne $true -or $hostResult.contextClosed -ne $true){throw 'host cleanup完了不明'}
 Set-Operation 'Supervisor.formal-frontend-cleanup'
 if(!$helper.WaitForExit((Remaining 2000 -Recovery))){throw 'helper actual exit不明'}
 # Parentがexact Jobを認定した保持FE世代だけ。未知PID/rendererへ接続しない。
 if(![object]::ReferenceEquals($listener,$fe.Process) -and !$listener.WaitForExit(0)){$listener.Kill()}
 if(!$fe.Process.WaitForExit(0)){$fe.Process.Kill()}
 if(!$listener.WaitForExit((Remaining 2000 -Recovery))){throw 'FE listener actual exit不明'}
 Capture $fe 'formal-fe' 1000
 $script:formalEntryEvidence.applicationCompleted=$true
 $script:formalEntryEvidence.hostResult=$hostResult
}

function Assert-M21FormalBrowserCloseRequest([object]$Request,[string]$RunId) {
 $fields=@($Request.PSObject.Properties.Name)
 if($fields.Count -ne 5 -or @($fields|Where-Object {$_ -cnotin @('owner','nonce','runId','actorCodes','requested')}).Count -or $Request.owner -cne $owner -or $Request.nonce -cne $Nonce -or $Request.runId -cne $RunId -or $Request.requested -isnot [bool] -or !$Request.requested -or @($Request.actorCodes).Count -ne 3 -or (($Request.actorCodes|Sort-Object)-join ',') -cne 'admin-de,member,system'){Set-Reason 'formal-close-request-invalid';throw ([IO.InvalidDataException]::new())}
}

function Wait-M21FormalCertificate([ValidateSet('formal-app-certified.json','formal-helper-certified.json','formal-ready.json')][string]$Name) {
 $certificate=Wait-File $Name
 if($certificate.owner -cne $owner -or $certificate.nonce -cne $Nonce){throw '正式認定receipt owner/nonce不一致'}
 return $certificate
}

function Assert-M21FormalExplorationFinished([object]$Marker,[string]$RunId,[object]$State,[int]$HelperPid) {
 $fields=@($Marker.PSObject.Properties.Name)
 if($fields.Count -ne 3 -or @($fields|Where-Object {$_ -cnotin @('runId','actorCodes','passed')}).Count -or $Marker.runId -cne $RunId -or $Marker.passed -isnot [bool] -or !$Marker.passed -or @($Marker.actorCodes).Count -ne 3 -or (($Marker.actorCodes|Sort-Object)-join ',') -cne 'admin-de,member,system' -or $State.runId -cne $RunId -or $State.pid -ne $HelperPid -or $State.failed -isnot [bool] -or $State.failed -or @($State.servedCodes).Count -ne 3 -or (($State.servedCodes|Sort-Object)-join ',') -cne 'admin-de,member,system'){Set-Reason 'formal-exploration-finished-invalid';throw ([IO.InvalidDataException]::new())}
}

function Assert-M21FormalPortsFree {
 foreach($port in @(8081,3003)){if(@(Get-NetTCPConnection -State Listen -ErrorAction Stop|Where-Object {$_.LocalPort -eq $port}).Count){Set-Reason 'formal-port-occupied';throw ([InvalidOperationException]::new())}}
}

function Wait-M21FormalListener([int]$Port,[Diagnostics.Process]$Root,[string]$Exe) {
 while((Remaining) -gt 0){
  Assert-M21FormalRequiredAlive @($Root)
  if($Port -eq 8081){Assert-M21FormalRequiredAlive $script:m21FormalAdmissionRequired}
  $listeners=@(Get-NetTCPConnection -State Listen -ErrorAction Stop|Where-Object {$_.LocalPort -eq $Port})
  if($listeners.Count){
   if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -cne '127.0.0.1'){throw 'listener bind不一致'}
   if($listeners[0].OwningProcess -eq $Root.Id){return $Root}
   if($Port -ne 3003){throw 'Java listener所有不一致'}
   $candidate=[Diagnostics.Process]::GetProcessById([int]$listeners[0].OwningProcess)
   try{Assert-M21FormalRequiredAlive @($candidate);if([IO.Path]::GetFullPath($candidate.MainModule.FileName) -ine [IO.Path]::GetFullPath($Exe) -or ![M21Job]::InJob($candidate,$null)){throw 'FE listener候補不一致'};return $candidate}catch{$candidate.Dispose();throw}
  }
  Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining)))
 }
 Set-Reason 'formal-listener-timeout';throw ([TimeoutException]::new())
}

function Confirm-M21FormalFrontendAssets([string]$Frontend) {
 Require-Work
 foreach($name in @('.env','.env.test','.env.local','.env.development','.env.development.local')){if(Test-Path -LiteralPath (Join-Path $Frontend $name)){throw 'private envを新規WTへ持ち込まない'}}
 $lock=Get-Content -Raw -Encoding UTF8 (Join-Path $Frontend 'package-lock.json')|ConvertFrom-Json
 foreach($name in @('nuxt','@playwright/test')){
  $package=Get-Content -Raw -Encoding UTF8 (Join-Path $Frontend ('node_modules/'+$name+'/package.json'))|ConvertFrom-Json
  if($package.version -cne $lock.packages.('node_modules/'+$name).version){throw 'frontend依存と固定lock不一致'}
 }
 $modules=Get-Item -LiteralPath (Join-Path $Frontend 'node_modules')
 if($modules.Attributes -band [IO.FileAttributes]::ReparsePoint){
  $targets=@($modules.Target)
  if($targets.Count -ne 1 -or [IO.Path]::GetFullPath($targets[0]).TrimEnd('\') -ine 'C:\Claude\mannschaft\frontend\node_modules'){throw 'frontend共有target不一致'}
 }
}

function Start-M21FormalBrowsers {
 if((Get-FileHash -LiteralPath $chromiumExe -Algorithm SHA256).Hash.ToLowerInvariant() -cne '290fa7018fda22c52ada5eddb0113baf3ebc41fd0fde6085eddb19793606c635'){throw 'Chromium SHA不一致'}
 $profiles=Join-Path $run 'profiles'
 if(Test-Path -LiteralPath $profiles){throw 'profile既存'}
 [IO.Directory]::CreateDirectory($profiles)>$null
 $environment=New-Object 'Collections.Generic.Dictionary[string,string]' ([StringComparer]::OrdinalIgnoreCase)
 foreach($key in @('SystemRoot','WINDIR')){$value=[Environment]::GetEnvironmentVariable($key);if($null -ne $value){$environment.Add($key,$value)}}
 $environment.Add('TEMP',$run);$environment.Add('TMP',$run)
 $browsers=New-Object 'Collections.Generic.List[object]'
 foreach($role in @('system','admin-de','member')){
  Guard-Run;Require-Work
  $profile=Join-Path $profiles $role;[IO.Directory]::CreateDirectory($profile)>$null
  $title='M21-'+$Nonce+'-'+$role
  $page='data:text/html,'+[Uri]::EscapeDataString('<!doctype html><title>'+$title+'</title><h1>'+$title+'</h1>')
  $child=[M21Child]::Start($chromiumExe,[string[]]@(('--user-data-dir='+$profile),'--no-first-run','--no-default-browser-check','--host-resolver-rules=MAP localhost 127.0.0.1','--new-window',$page),$run,(Join-Path $run ('formal-browser-'+$role)),(Remaining -Recovery),$false,$environment)
  Register-Child $child ('formal-browser-'+$role)
  Assert-M21FormalRequiredAlive @($child.Process)
  if($child.Process.MainModule.FileName -ine $chromiumExe){throw 'Chromium実体不一致'}
  $browsers.Add(@{child=$child;candidate=@{role=$role;identity=(Identity $child.Process);exe=$chromiumExe;profile=$profile;title=$title}})
 }
 return ,$browsers.ToArray()
}

function Invoke-M21FormalSupervisor {
 Guard-Run;Require-Work
 Assert-M21FormalRequiredAlive @($self)
 Set-Operation 'Supervisor.formal-assets'
 $script:formalEntryEvidence=@{budget=(Get-M21FormalBudget);assetConnected=$true;productionAssetsVerified=$false;bridgeConnected=$false;applicationConnected=$false;formalRunQualified=$false;retainedReason='bridge-create-and-application-not-connected'}
 # CP→javac→jarの固定callerをACK後にだけ接続。途中失敗なら後続へ進まない。
 $assets=Invoke-M21ProductionAssets
 $script:formalEntryEvidence.productionAssetsVerified=$true
 Require-Work;Assert-M21FormalRequiredAlive @($self)
 Invoke-M21FormalWork
}

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
 # formal-m21のACK後だけが呼ぶ固定caller。dummy/env有無では呼ばない。
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
 $tree=[M21Child]::Start($git,[string[]]@('--no-optional-locks','-c','core.fsmonitor=false','-C',$root,'ls-tree','-r','-z','--long',$commit,'--','backend','.gitattributes','scripts/m21-production-cp.init.gradle','scripts/m21-windows-formal-adapter.ps1','scripts/m21-windows-supervisor.ps1','scripts/m21-resident-helper.cjs','scripts/m21-wsl-docker-bridge.py','frontend/package.json','frontend/package-lock.json'),$root,(Join-Path $assets 'git-tree'),(Remaining -Recovery),$false,$environment);Register-Child $tree 'asset-git-tree'
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
 $diff=[M21Child]::Start($git,[string[]]@('--no-optional-locks','-c','core.fsmonitor=false','-C',$root,'diff','--quiet','--no-ext-diff','--no-textconv',$commit,'--','backend','.gitattributes','scripts/m21-production-cp.init.gradle','scripts/m21-windows-formal-adapter.ps1','scripts/m21-windows-supervisor.ps1','scripts/m21-resident-helper.cjs','scripts/m21-wsl-docker-bridge.py','frontend/package.json','frontend/package-lock.json'),$root,(Join-Path $assets 'git-diff'),(Remaining -Recovery),$false,$environment);Register-Child $diff 'asset-git-diff'
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
