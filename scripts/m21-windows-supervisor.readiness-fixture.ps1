# 固定readiness試練専用。mainのReadinessFixtureから定数pathでだけ呼ぶ。
# 実Windows sharing違反を観測し、通常Read-Json/Wait-Fileの帰還は置換しない。
function Record-FixtureReadFailure([Management.Automation.ErrorRecord]$record) {
 $exception=$record.Exception
 for($i=0;$null -ne $exception -and $i -lt 8;$i++){
  if($exception -is [IO.IOException] -and $exception.HResult -eq -2147024864){
   $script:fixtureEvidence.realRead32++
   $script:fixtureEvidence.lastHResult=$exception.HResult
   $script:fixtureEvidence.events.Add(@{event='actual-read32';elapsedMs=$localClock.ElapsedMilliseconds})
   if($TestFixture -eq 'lock-release' -and $null -ne $script:fixtureLock){
    try{
     $script:fixtureLock.Dispose();$script:fixtureLock=$null
     $script:fixtureEvidence.lockReleased=$true
     $script:fixtureEvidence.events.Add(@{event='lock-released';elapsedMs=$localClock.ElapsedMilliseconds})
    }catch{$script:fixtureEvidence.cleanupFailure=@(Safe-Error $_.Exception);$script:recovered=$false}
   }
   return
  }
  $exception=$exception.InnerException
 }
}
if($Mode -ne 'ReadinessFixture' -or $TestFixture -notin @('lock-release','lock-held','invalid-json')){throw '固定readiness fixture以外は拒否'}
$fixtureIdentity=Identity ([Diagnostics.Process]::GetCurrentProcess())
$fixtureEvidence=@{fixture=$TestFixture;expectedIdentity=$fixtureIdentity;acceptedIdentity=$null;accepted=0;realRead32=0;lastHResult=$null;lockAcquired=$false;lockReleased=$false;cleanupFailure=@();budgetExhausted=$false;workDeadlineMs=$WorkRemainingMs;startMs=$null;endMs=$null;events=(New-Object 'Collections.Generic.List[object]')}
$target=Join-Path $run 'child-candidate.json'
if($TestFixture -eq 'invalid-json'){
 # 同期write/close→moveのpublish完了後に、読取可能な固定不正JSONを渡す。
 $stream=New-Object IO.FileStream(($target+'.next'),[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
 try{$bytes=$utf8.GetBytes('{fixed-invalid-json');$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
 [IO.File]::Move(($target+'.next'),$target)
}else{
 Write-Json 'child-candidate.json' $fixtureIdentity
 $fixtureLock=New-Object IO.FileStream($target,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)
 $fixtureEvidence.lockAcquired=$true
 $fixtureEvidence.events.Add(@{event='lock-acquired';elapsedMs=$localClock.ElapsedMilliseconds})
}
if($TestFixture -eq 'lock-held'){
 # fixtureの期限を短縮するだけ。既存絶対W/原点/G/Rを延長・resetしない。
 $WorkRemainingMs=[int][Math]::Min($WorkRemainingMs,$localClock.ElapsedMilliseconds+1000)
 $fixtureEvidence.workDeadlineMs=$WorkRemainingMs
}
$fixtureEvidence.startMs=$localClock.ElapsedMilliseconds
try{
 Require-Work
 $accepted=Wait-File 'child-candidate.json'
 Assert-P6Identity $accepted
 if($accepted.pid -ne $fixtureIdentity.pid -or $accepted.creation -cne $fixtureIdentity.creation){throw 'fixture identity不一致'}
 $fixtureEvidence.accepted++
 $fixtureEvidence.acceptedIdentity=$accepted
 $fixtureEvidence.events.Add(@{event='accepted';elapsedMs=$localClock.ElapsedMilliseconds})
}finally{
 $fixtureEvidence.endMs=$localClock.ElapsedMilliseconds
 $fixtureEvidence.budgetExhausted=((Remaining) -eq 0)
 if($null -ne $fixtureLock){try{$fixtureLock.Dispose();$fixtureLock=$null;$fixtureEvidence.lockReleased=$true}catch{$fixtureEvidence.cleanupFailure=@(Safe-Error $_.Exception);$script:recovered=$false}}
}
