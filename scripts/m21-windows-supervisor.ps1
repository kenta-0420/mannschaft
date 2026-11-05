# M21固定dummy専用Windows Job監督。実サービス/任意commandの起動口は持たない。
[CmdletBinding()]
param(
 [ValidateSet('Parent','Supervisor','Observe')][string]$Mode='Parent',
 [Parameter(Mandatory=$true)][ValidateSet('normal','pre-ack','wrong-ack','assign-fail','host-fail','stdio-eof','cleanup-fail','save-fail','owner-mismatch','kill-close')][string]$Scenario,
 [Parameter(Mandatory=$true)][string]$RunDirectory,
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{32}$')][string]$Nonce,
 [Parameter(Mandatory=$true)][string]$NodePath,
 [Parameter(Mandatory=$true)][ValidateRange(0,420000)][int]$WorkRemainingMs,
 [Parameter(Mandatory=$true)][ValidateRange(0,420000)][int]$TotalRemainingMs
)
$localClock=[Diagnostics.Stopwatch]::StartNew()
$phaseCount=0
$phaseBytes=0
function Emit-Phase([ValidateSet('entry','guard-before','guard-after','hello-before','hello-published','ack-validated','native-before','native-after')][string]$phase) {
 $current=[Diagnostics.Process]::GetCurrentProcess()
 $line='M21_PHASE|'+$phase+'|pid='+$current.Id+'|creation='+$current.StartTime.ToUniversalTime().Ticks+'|utcTicks='+[DateTime]::UtcNow.Ticks+'|elapsedMs='+$localClock.ElapsedMilliseconds
 $bytes=[Text.Encoding]::ASCII.GetByteCount($line)+2
 if($script:phaseCount -ge 12 -or $script:phaseBytes+$bytes -gt 4096){throw 'phase上限'}
 $script:phaseCount++;$script:phaseBytes+=$bytes
 [Console]::Out.WriteLine($line)
}
if($Mode -eq 'Supervisor'){Emit-Phase 'entry'}
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$owner='d7249e88-1678-4e0c-ae21-a550989098a6'
$run=[IO.Path]::GetFullPath($RunDirectory).TrimEnd('\')
$utf8=New-Object Text.UTF8Encoding($false)
$primary=$null
$secondary=New-Object 'Collections.Generic.List[object]'
$attempts=New-Object 'Collections.Generic.List[string]'
$streams=New-Object 'Collections.Generic.List[object]'
$held=New-Object 'Collections.Generic.List[object]'
$startupRecovery=New-Object 'Collections.Generic.List[object]'
$certified=New-Object 'Collections.Generic.List[object]'
$recovered=$true
$jobEmpty=$null
$job=$null
$operation='bootstrap'
$ioOperation=$null
$metadataFile=$null
$failureContext=$null
$reason=$null
$reasonFile=$null
$reasonElapsed=$null
$reasonLimit=$null
$parentEvents=New-Object 'Collections.Generic.List[object]'
$lastHandles=New-Object 'Collections.Generic.List[object]'
$lateObservation=$null
$assigned=$false
$ackPublished=$false
$grandchildEvidence=$null
$knownExitEvidence=@()
$p6Grand=$null
$p6GrandIdentity=$null
$p6ParentChild=$null
$p6ParentGrand=$null
$p6Certification=$null
$p6Observations=New-Object 'Collections.Generic.List[object]'
function Remaining([int]$cap=[int]::MaxValue,[switch]$Recovery) {
 $budget=$WorkRemainingMs;if($Recovery){$budget=$TotalRemainingMs}
 return [int][Math]::Max(0,[Math]::Min($cap,[Math]::Floor($budget-$localClock.Elapsed.TotalMilliseconds)))
}
function Require-Work {
 if((Remaining) -le 0){Set-Reason 'work-budget-exhausted';throw 'work budget exhausted'}
}
# Job handleは親のSafeHandleだけで保持する。名前/継承/複製を提供しない。
$nativeCode=@'
using System;
using System.IO;
using System.Text;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Collections.Generic;
using Microsoft.Win32.SafeHandles;
public sealed class M21Job : SafeHandleZeroOrMinusOneIsInvalid {
 private M21Job() : base(true) {}
 protected override bool ReleaseHandle() { return CloseHandle(handle); }
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool CloseHandle(IntPtr h);
 [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern M21Job CreateJobObjectW(IntPtr a,string n);
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetInformationJobObject(M21Job j,int c,ref Extended x,uint n);
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool AssignProcessToJobObject(M21Job j,IntPtr p);
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool IsProcessInJob(IntPtr p,IntPtr j,out bool yes);
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateJobObject(M21Job j,uint code);
 [StructLayout(LayoutKind.Sequential)] struct Basic {
  public long ProcessTime,JobTime;public uint Flags;public UIntPtr Min,Max;
  public uint Active;public UIntPtr Affinity;public uint Priority,Scheduling;
 }
 [StructLayout(LayoutKind.Sequential)] struct Io { public ulong ROp,WOp,OOp,RBytes,WBytes,OBytes; }
 [StructLayout(LayoutKind.Sequential)] struct Extended { public Basic Basic;public Io Io;public UIntPtr ProcessMemory,JobMemory,PeakProcess,PeakJob; }
 static void Check(bool yes) { if(!yes)throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error()); }
 public static M21Job Create() {
  M21Job j=CreateJobObjectW(IntPtr.Zero,null);
  if(j.IsInvalid){int e=Marshal.GetLastWin32Error();j.Dispose();throw new System.ComponentModel.Win32Exception(e);}
  Extended x=new Extended();x.Basic.Flags=0x2000;
  try{Check(SetInformationJobObject(j,9,ref x,(uint)Marshal.SizeOf(typeof(Extended))));return j;}catch{j.Dispose();throw;}
 }
 public void Assign(Process p) { Check(AssignProcessToJobObject(this,p.Handle));if(!InJob(p,this))throw new IOException("Job所属不一致"); }
 public static bool InJob(Process p,M21Job job) {
  bool yes;Check(IsProcessInJob(p.Handle,job==null?IntPtr.Zero:job.DangerousGetHandle(),out yes));return yes;
 }
 [StructLayout(LayoutKind.Sequential)] struct Accounting { public long User,Kernel,PeriodUser,PeriodKernel;public uint Faults,Total,Active,Terminated; }
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool QueryInformationJobObject(M21Job j,int c,out Accounting a,uint n,IntPtr length);
 public uint Active() { Accounting a;Check(QueryInformationJobObject(this,1,out a,(uint)Marshal.SizeOf(typeof(Accounting)),IntPtr.Zero));return a.Active; }
 public void Stop() { Check(TerminateJobObject(this,71)); }
}
public sealed class M21Child : IDisposable {
 public Process Process;public Task OutTask,ErrTask;FileStream output,error;
 public bool Started,StartupExited,StartupStdoutEof,StartupStderrEof,Captured;
 public int RecordedPid;public string RecordedCreation,LogPrefix;public Exception StartupFailure;
 public List<string> StartupCleanupFailures=new List<string>();
 public List<Exception> StartupCleanupExceptions=new List<Exception>();
 public bool ObserveTimings,TimingOverflow;
 public List<Dictionary<string,object>> Timings=new List<Dictionary<string,object>>();
 Stopwatch timingClock=Stopwatch.StartNew();
 public void Mark(string stage,int cap) {
  if(!ObserveTimings)return;
  if(Timings.Count>=16){TimingOverflow=true;return;}
  Timings.Add(new Dictionary<string,object>{{"stage",stage},{"utcTicks",DateTime.UtcNow.Ticks.ToString()},{"localMs",timingClock.ElapsedMilliseconds},{"capMs",cap},{"stdoutTask",OutTask==null?"not-started":OutTask.Status.ToString()},{"stderrTask",ErrTask==null?"not-started":ErrTask.Status.ToString()}});
 }
 void CleanupFailure(string stage,Exception e) { StartupCleanupFailures.Add(stage+":"+e.GetType().Name);StartupCleanupExceptions.Add(e); }
 static string Quote(string s) {
  StringBuilder b=new StringBuilder("\"");int slash=0;
  foreach(char c in s){if(c=='\\'){slash++;continue;}if(c=='"'){b.Append('\\',slash*2+1);b.Append(c);}else{b.Append('\\',slash);b.Append(c);}slash=0;}
  b.Append('\\',slash*2);b.Append('"');return b.ToString();
 }
 public static M21Child Start(string exe,string[] args,string cwd,string prefix,int recoveryMs,bool observeTimings) {
  Stopwatch recoveryClock=Stopwatch.StartNew();
  Func<int,int> remaining=cap=>Math.Max(0,Math.Min(cap,recoveryMs-(int)recoveryClock.ElapsedMilliseconds));
  M21Child c=new M21Child();c.LogPrefix=prefix;c.ObserveTimings=observeTimings;
  try {
   c.output=new FileStream(prefix+".out",FileMode.CreateNew,FileAccess.Write,FileShare.Read);
   c.error=new FileStream(prefix+".err",FileMode.CreateNew,FileAccess.Write,FileShare.Read);
   ProcessStartInfo s=new ProcessStartInfo(exe);s.UseShellExecute=false;s.CreateNoWindow=true;s.WorkingDirectory=cwd;
   s.RedirectStandardOutput=true;s.RedirectStandardError=true;
   StringBuilder a=new StringBuilder();foreach(string x in args){if(a.Length>0)a.Append(' ');a.Append(Quote(x));}s.Arguments=a.ToString();
   c.Process=new Process();c.Process.StartInfo=s;if(!c.Process.Start())throw new IOException("起動失敗");c.Started=true;
   c.RecordedPid=c.Process.Id;
   IntPtr retained=c.Process.Handle;
   c.RecordedCreation=c.Process.StartTime.ToUniversalTime().Ticks.ToString(System.Globalization.CultureInfo.InvariantCulture);
   c.Mark("stdout-copy-before",-1);
   c.OutTask=c.Process.StandardOutput.BaseStream.CopyToAsync(c.output);
   c.Mark("stdout-copy-after",-1);
   c.Mark("stderr-copy-before",-1);
   c.ErrTask=c.Process.StandardError.BaseStream.CopyToAsync(c.error);
   c.Mark("stderr-copy-after",-1);return c;
  } catch(Exception original) {
   // 先にprimaryを固定し、直接保持したProcessと開始済みpumpを個別に有限回収する。
   // このcatchではDispose/throwで保持情報を捨てず、呼出元のheldへ必ず返す。
   c.StartupFailure=original;
   if(c.Started){
    try{if(!c.Process.HasExited)c.Process.Kill();}catch(Exception e){c.CleanupFailure("kill",e);}
    try{c.StartupExited=c.Process.WaitForExit(remaining(1000));if(!c.StartupExited)c.CleanupFailure("exit",new TimeoutException());}catch(Exception e){c.CleanupFailure("exit",e);}
   }else{c.StartupExited=true;}
   try{c.StartupStdoutEof=!c.Started||c.Eof(true,remaining(500));if(!c.StartupStdoutEof)c.CleanupFailure("stdout",new TimeoutException());}catch(Exception e){c.CleanupFailure("stdout",e);}
   try{c.StartupStderrEof=!c.Started||c.Eof(false,remaining(500));if(!c.StartupStderrEof)c.CleanupFailure("stderr",new TimeoutException());}catch(Exception e){c.CleanupFailure("stderr",e);}
   return c;
  }
 }
 public bool Eof(bool stdout,int ms) {
  string role=stdout?"stdout":"stderr";Mark(role+"-eof-before",ms);
  try{
   Task t=stdout?OutTask:ErrTask;if(t==null)return false;
   bool done=t.Wait(ms);if(done){if(stdout)output.Flush();else error.Flush();}return done;
  }finally{Mark(role+"-eof-after",ms);}
 }
 public void Dispose() {
  List<Exception> errors=new List<Exception>();
  try{if(output!=null)output.Dispose();}catch(Exception e){errors.Add(e);}
  try{if(error!=null)error.Dispose();}catch(Exception e){errors.Add(e);}
  try{if(Process!=null)Process.Dispose();}catch(Exception e){errors.Add(e);}
  if(errors.Count>0)throw new AggregateException(errors);
 }
}
'@
function Safe-Error([Exception]$exception) {
 $details=New-Object 'Collections.Generic.List[object]'
 $current=$exception
 for($i=0;$null -ne $current -and $i -lt 8;$i++){
  $native=$null;$innerType=$null
  if($current -is [ComponentModel.Win32Exception]){$native=$current.NativeErrorCode}
  if($null -ne $current.InnerException){$innerType=$current.InnerException.GetType().FullName}
  $methods=New-Object 'Collections.Generic.List[string]'
  $trace=New-Object Diagnostics.StackTrace($current,$false)
  foreach($frame in @($trace.GetFrames()) | Select-Object -First 6){
   if($null -eq $frame){continue}
   $method=$frame.GetMethod()
   if($null -ne $method -and $null -ne $method.DeclaringType){$methods.Add($method.DeclaringType.FullName+'.'+$method.Name)}
  }
  $details.Add(@{type=$current.GetType().FullName;innerType=$innerType;hResult=$current.HResult;nativeErrorCode=$native;methods=@($methods.ToArray())})
  $current=$current.InnerException
 }
 return $details.ToArray()
}
function Safe-ScriptTrace([Management.Automation.ErrorRecord]$record) {
 if($null -eq $record){return @()}
 $frames=New-Object 'Collections.Generic.List[object]'
 foreach($line in @($record.ScriptStackTrace -split '\r?\n')){
  if($line -match '^\s*at ([A-Za-z0-9_-]+|<ScriptBlock>),\s+(.+): line ([0-9]+)\s*$'){
   if([string]::Equals($Matches[2],$PSCommandPath,[StringComparison]::OrdinalIgnoreCase)){$frames.Add(@{function=$Matches[1];line=[int]$Matches[3]})}
  }
  if($frames.Count -ge 8){break}
 }
 if($frames.Count -eq 0 -and $null -ne $record.InvocationInfo){
  $info=$record.InvocationInfo
  if([string]::Equals($info.ScriptName,$PSCommandPath,[StringComparison]::OrdinalIgnoreCase) -and $info.ScriptLineNumber -gt 0){$frames.Add(@{function='InvocationInfo';line=$info.ScriptLineNumber})}
 }
 return $frames.ToArray()
}
function Set-Operation([string]$name) {
 $script:operation=$name;$script:ioOperation=$null;$script:metadataFile=$null
 $script:reason=$null;$script:reasonFile=$null;$script:reasonElapsed=$null;$script:reasonLimit=$null
}
function Set-Reason([string]$code,[string]$file=$null,[object]$elapsed=$null,[object]$limit=$null) {$script:reason=$code;$script:reasonFile=$file;$script:reasonElapsed=$elapsed;$script:reasonLimit=$limit}
function Parent-Event([string]$name,[object]$child=$null) {
 if($parentEvents.Count -ge 16){throw 'parent event上限'}
 $item=@{event=$name;utcTicks=[DateTime]::UtcNow.Ticks.ToString();elapsedMs=$localClock.ElapsedMilliseconds;pid=$null;creation=$null;hasExited=$null}
 if($null -ne $child){$item.pid=$child.RecordedPid;$item.creation=$child.RecordedCreation;if($child.Started){$item.hasExited=$child.Process.HasExited}}
 $script:parentEvents.Add($item)
}
function Remember-Failure([Management.Automation.ErrorRecord]$record) {
 $current=$record.Exception
 for($i=0;$null -ne $current -and $i -lt 8;$i++){
  if($null -ne $script:failureContext -and [object]::ReferenceEquals($script:failureContext.exception,$current)){return}
  $current=$current.InnerException
 }
 $script:failureContext=@{exception=$record.Exception;operation=$script:operation;ioOperation=$script:ioOperation;metadataFile=$script:metadataFile;reason=$script:reason;reasonFile=$script:reasonFile;elapsedMs=$script:reasonElapsed;limitMs=$script:reasonLimit;scriptTrace=@(Safe-ScriptTrace $record)}
}
function Fail([string]$stage,[object]$errorValue,[Management.Automation.ErrorRecord]$record=$null) {
 $context=@{operation=$script:operation;ioOperation=$script:ioOperation;metadataFile=$script:metadataFile;reason=$script:reason;reasonFile=$script:reasonFile;elapsedMs=$script:reasonElapsed;limitMs=$script:reasonLimit;scriptTrace=@(Safe-ScriptTrace $record)}
 $current=$errorValue
 for($i=0;$null -ne $current -and $i -lt 8;$i++){
  if($null -ne $script:failureContext -and [object]::ReferenceEquals($script:failureContext.exception,$current)){$context=$script:failureContext;break}
  $current=$current.InnerException
 }
 $failure=[ordered]@{stage=$stage;kind=$errorValue.GetType().Name;reason=$context.reason;reasonFile=$context.reasonFile;elapsedMs=$context.elapsedMs;limitMs=$context.limitMs;operation=$context.operation;ioOperation=$context.ioOperation;metadataFile=$context.metadataFile;scriptTrace=@($context.scriptTrace);exceptions=@(Safe-Error $errorValue)}
 if($null -eq $script:primary){$script:primary=$failure}else{$script:secondary.Add($failure)}
}
function Attempt([string]$stage,[scriptblock]$action) {
 $script:attempts.Add($stage)
 try{& $action}catch{if($stage -in @('job-stop','direct-stop','exit-and-streams','job-empty','job-close')){$script:recovered=$false};Fail $stage $_.Exception $_}
}
function Read-Json([string]$name) {
 $priorIo=$script:ioOperation;$priorFile=$script:metadataFile
 $script:ioOperation='get-item';$script:metadataFile=$name
 try{
  $file=Join-Path $run $name
  $info=Get-Item -LiteralPath $file
  if($info.Length -gt 16384 -or ($info.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'JSON境界違反'}
  $script:ioOperation='read'
  $text=[IO.File]::ReadAllText($file)
  $script:ioOperation='decode'
  return ($text|ConvertFrom-Json)
 }catch{Remember-Failure $_;throw}
 finally{$script:ioOperation=$priorIo;$script:metadataFile=$priorFile}
}
function Guard-Run {
 if(!(Test-Path -LiteralPath $run -PathType Container)){throw '自己run不在'}
 $cursor=Get-Item -LiteralPath $run
 while($null -ne $cursor){
  if(($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'run経路がreparse point'}
  $cursor=$cursor.Parent
 }
 $o=Read-Json 'owner.json'
 if($o.owner -cne $owner -or $o.nonce -cne $Nonce){throw 'owner/nonce不一致'}
}
function Write-Json([string]$name,[object]$value) {
 $priorIo=$script:ioOperation;$priorFile=$script:metadataFile
 $script:ioOperation='guard';$script:metadataFile=$name
 try{
 Guard-Run
 $file=Join-Path $run $name;$next=$file+'.next'
 $bytes=$utf8.GetBytes(($value|ConvertTo-Json -Depth 12 -Compress))
 if($bytes.Length -gt 16384){throw 'JSON上限'}
 $script:ioOperation='open'
 $s=New-Object IO.FileStream($next,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
 try{$script:ioOperation='write';$s.Write($bytes,0,$bytes.Length)}catch{Remember-Failure $_;throw}finally{$script:ioOperation='close';$s.Dispose()}
 # 各metadataは一度だけ。既存fileを上書きして別世代へ接続しない。
 $script:ioOperation='move'
 [IO.File]::Move($next,$file)
 }catch{Remember-Failure $_;throw}
 finally{$script:ioOperation=$priorIo;$script:metadataFile=$priorFile}
}
$bootstrapStage='native-bootstrap'
try {
 if($WorkRemainingMs -gt $TotalRemainingMs){Set-Reason 'invalid-budget';throw 'work/total budget不一致'}
 # Supervisorは型compileも子を生み得るものとして、標準Process/JSONのgateだけを先行する。
 # この区間にはcustom型参照/外部command/Process.Startを置かない。
 if($Mode -eq 'Supervisor'){
  $bootstrapStage='supervisor-bootstrap'
  Emit-Phase 'guard-before'
  Guard-Run
  Emit-Phase 'guard-after'
  $self=[Diagnostics.Process]::GetCurrentProcess()
  $identity=[ordered]@{owner=$owner;pid=$self.Id;creation=$self.StartTime.ToUniversalTime().Ticks.ToString();nonce=$Nonce}
  if($Scenario -eq 'owner-mismatch'){$identity.owner='wrong-owner'}
  Emit-Phase 'hello-before'
  Write-Json 'hello.json' $identity
  Emit-Phase 'hello-published'
  $clock=[Diagnostics.Stopwatch]::StartNew()
  $ackLimit=Remaining;if($Scenario -eq 'pre-ack'){$ackLimit=[Math]::Min(2500,$ackLimit)}
  while(!(Test-Path -LiteralPath (Join-Path $run 'ack.json')) -and $clock.ElapsedMilliseconds -lt $ackLimit -and (Remaining) -gt 0){Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining)))}
  if(!(Test-Path -LiteralPath (Join-Path $run 'ack.json'))){
   if($Scenario -eq 'pre-ack' -and $clock.ElapsedMilliseconds -ge 2500 -and (Remaining) -gt 0){Set-Reason 'ack-timeout' 'ack.json' $clock.ElapsedMilliseconds 2500}else{Set-Reason 'work-budget-exhausted' 'ack.json' $clock.ElapsedMilliseconds $ackLimit}
   throw 'ack待機期限超過'
  }
  $ack=Read-Json 'ack.json'
  if($ack.owner -cne $owner -or $ack.nonce -cne $Nonce -or $ack.pid -ne $self.Id -or [string]$ack.creation -cne $identity.creation -or $ack.ownedJobVerified -ne $true){Set-Reason 'ack-mismatch' 'ack.json';throw 'ack/親Job認定不一致'}
  Emit-Phase 'ack-validated'
  Guard-Run
 }
 $bootstrapStage='native-bootstrap'
 Require-Work
 if($Mode -eq 'Supervisor'){Emit-Phase 'native-before'}
 Add-Type -TypeDefinition $nativeCode
 if($Mode -eq 'Supervisor'){Emit-Phase 'native-after'}
}
catch {
 # custom型の関数定義/起動へ進まず、安全な型・数値だけを保存する。
 Fail $bootstrapStage $_.Exception $_
 $bootstrapSaved=$false
 $processIdentity=$null
 Attempt 'bootstrap-process-identity' {$current=[Diagnostics.Process]::GetCurrentProcess();$script:processIdentity=@{pid=$current.Id;creation=$current.StartTime.ToUniversalTime().Ticks.ToString()}}
 Attempt 'bootstrap-result-save' {
  $name='result.json';if($Mode -eq 'Supervisor'){$name='supervisor-result.json'}
  Write-Json $name @{owner=$owner;nonce=$Nonce;process=$processIdentity;passed=$false;primary=$primary;secondary=@($secondary.ToArray());grandchildEvidence=$grandchildEvidence;p6Observations=@();parentEvents=@($parentEvents.ToArray());attempts=@($attempts.ToArray());streams=@();lastHandles=@();startupRecovery=@();resourceRecovery=@{confirmed=$true;jobEmpty=$null;retained=@()};recovered=$true;parentKilled=$false;knownExited=0}
  $script:bootstrapSaved=$true
 }
 if(!$bootstrapSaved){[Console]::Error.WriteLine((@{passed=$false;primary=$primary;secondary=@($secondary.ToArray())}|ConvertTo-Json -Depth 12 -Compress))}
 exit 1
}
function Identity([Diagnostics.Process]$process) {
 return [ordered]@{owner=$owner;pid=$process.Id;creation=$process.StartTime.ToUniversalTime().Ticks.ToString();nonce=$Nonce}
}
function Wait-File([string]$name) {
 $clock=[Diagnostics.Stopwatch]::StartNew()
 $milliseconds=Remaining
 while((Remaining) -gt 0){
  if(Test-Path -LiteralPath (Join-Path $run $name)){return (Read-Json $name)}
  Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining)))
 }
 Set-Reason 'work-budget-exhausted' $name $clock.ElapsedMilliseconds $milliseconds
 if($operation -eq 'Parent.readHello'){Parent-Event 'hello-deadline'}
 throw 'metadata待機期限超過'
}
function New-Supervisor([string]$childMode,[string]$prefix) {
 Require-Work
 $ps=[Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
 $childArguments=@('-NoProfile','-NonInteractive','-File',$PSCommandPath,'-Mode',$childMode,'-Scenario',$Scenario,'-RunDirectory',$run,'-Nonce',$Nonce,'-NodePath',$NodePath,'-WorkRemainingMs',[string](Remaining),'-TotalRemainingMs',[string](Remaining -Recovery))
 Require-Work
 return [M21Child]::Start($ps,$childArguments,$PSScriptRoot,(Join-Path $run $prefix),(Remaining -Recovery),($Scenario -eq 'stdio-eof'))
}
function Register-Child([M21Child]$child,[string]$role) {
 $script:held.Add($child)
 if($null -ne $child.StartupFailure){
  $script:startupRecovery.Add([ordered]@{role=$role;pid=$child.RecordedPid;creation=$child.RecordedCreation;logs=$child.LogPrefix;exited=$child.StartupExited;stdoutAdopted=($null -ne $child.OutTask);stderrAdopted=($null -ne $child.ErrTask);stdoutEof=$child.StartupStdoutEof;stderrEof=$child.StartupStderrEof;retainedAtStartup=(!$child.StartupExited -or !$child.StartupStdoutEof -or !$child.StartupStderrEof);failures=@($child.StartupCleanupFailures.ToArray())})
  Fail ($role+'-start') $child.StartupFailure
  for($i=0;$i -lt $child.StartupCleanupExceptions.Count;$i++){Fail ($role+'-start-cleanup-'+$child.StartupCleanupFailures[$i].Split(':')[0]) $child.StartupCleanupExceptions[$i]}
  throw '起動境界失敗。保持Processはfinallyで再確認する'
 }
}
function P6-State([string]$stage) {
 if($Mode -ne 'Supervisor' -or $Scenario -ne 'stdio-eof' -or $null -eq $script:p6Grand){return}
 $snapshot=@{stage=$stage;utcTicks=[DateTime]::UtcNow.Ticks.ToString();localMs=$localClock.ElapsedMilliseconds;pid=$script:p6GrandIdentity.pid;creation=$script:p6GrandIdentity.creation;waitExit0=$script:p6Grand.WaitForExit(0);hasExited=$script:p6Grand.HasExited;exitCode=$null}
 if($snapshot.waitExit0){$snapshot.exitCode=$script:p6Grand.ExitCode}
 $script:p6Observations.Add($snapshot)
}
function Capture([M21Child]$child,[string]$role,[int]$milliseconds) {
 if(!$child.Started){$script:streams.Add(@{role=$role;notStarted=$true;exited=$true;exitCode=$null;stdoutEof=$true;stderrEof=$true});$child.Captured=$true;return}
 $item=[ordered]@{role=$role;pid=$child.RecordedPid;creation=$child.RecordedCreation;logs=$child.LogPrefix;exited=$false;exitCode=$null;stdoutEof=$false;stderrEof=$false;timingOrigin='held-child-instance';timings=@();timingOverflow=$false}
 $exitCap=Remaining $milliseconds -Recovery
 $child.Mark('capture-exit-before',$exitCap)
 try{$item.exited=$child.Process.WaitForExit($exitCap);if($item.exited){$item.exitCode=$child.Process.ExitCode}}catch{Fail ($role+'-exit') $_.Exception}finally{$child.Mark('capture-exit-after',$exitCap)}
 Attempt 'p6-after-child-exit' {P6-State 'after-child-exit'}
 if(!$item.exited){$script:recovered=$false;Fail ($role+'-exit-timeout') ([TimeoutException]::new())}
 Attempt 'p6-stdout-before' {P6-State 'stdout-before'}
 try{$item.stdoutEof=$child.Eof($true,(Remaining $milliseconds -Recovery));if(!$item.stdoutEof){Set-Reason 'stdout-eof-timeout';throw 'stdout EOF期限超過'}}catch{$script:recovered=$false;Fail ($role+'-stdout') $_.Exception $_}finally{Attempt 'p6-stdout-after' {P6-State 'stdout-after'}}
 Attempt 'p6-stderr-before' {P6-State 'stderr-before'}
 try{$item.stderrEof=$child.Eof($false,(Remaining $milliseconds -Recovery));if(!$item.stderrEof){Set-Reason 'stderr-eof-timeout';throw 'stderr EOF期限超過'}}catch{$script:recovered=$false;Fail ($role+'-stderr') $_.Exception $_}finally{Attempt 'p6-stderr-after' {P6-State 'stderr-after'}}
 $item.timings=@($child.Timings.ToArray());$item.timingOverflow=$child.TimingOverflow
 $script:streams.Add($item)
 $child.Captured=$true
}
function Open-Known([object]$metadata,[M21Job]$expectedJob) {
 if($metadata.owner -cne $owner -or $metadata.nonce -cne $Nonce){throw '子owner/nonce不一致'}
 $p=[Diagnostics.Process]::GetProcessById([int]$metadata.pid)
 try{
  $handle=$p.Handle
  if($p.StartTime.ToUniversalTime().Ticks.ToString() -cne [string]$metadata.creation){throw '子世代不一致'}
  if($null -ne $expectedJob -and ![M21Job]::InJob($p,$expectedJob)){throw '親所有Job不一致'}
  return $p
 }catch{$p.Dispose();throw}
}
function Assert-P6Identity([object]$metadata) {
 $fields=@($metadata.PSObject.Properties.Name)
 if($fields.Count -ne 4){throw 'P6 identity field集合不一致'}
 foreach($field in $fields){if(@('owner','nonce','pid','creation') -cnotcontains $field){throw 'P6 identity未知field'}}
 if($metadata.owner -isnot [string] -or $metadata.owner -cne $owner -or $metadata.nonce -isnot [string] -or $metadata.nonce -cne $Nonce){throw 'P6 identity owner/nonce不一致'}
 if(($metadata.pid -isnot [int] -and $metadata.pid -isnot [long]) -or $metadata.pid -le 0 -or $metadata.pid -gt [int]::MaxValue -or $metadata.creation -isnot [string] -or $metadata.creation -cnotmatch '^[1-9][0-9]*$'){throw 'P6 identity出生不正'}
}
try {
 Guard-Run
 if(![IO.Path]::IsPathRooted($NodePath) -or [IO.Path]::GetFileName($NodePath) -ine 'node.exe' -or !(Test-Path -LiteralPath $NodePath -PathType Leaf)){throw 'Node exe不一致'}
 $dummy=Join-Path $PSScriptRoot 'm21-windows-supervisor.probe.cjs'
 if($Mode -eq 'Supervisor'){
  # 親のexact Job認定ack後に初期化した型で補助観測する。
  if(![M21Job]::InJob($self,$null)){throw 'ack後Job不在'}
  Guard-Run
  Require-Work
  $child=[M21Child]::Start($NodePath,@($dummy,'--dummy','child',$run,$Nonce,$Scenario,[string](Remaining),[string](Remaining -Recovery)),$run,(Join-Path $run 'dummy'),(Remaining -Recovery),($Scenario -eq 'stdio-eof'))
  Register-Child $child 'dummy'
  $creatorIdentity=@{owner=$owner;nonce=$Nonce;pid=$child.RecordedPid;creation=$child.RecordedCreation}
  Write-Json 'child-candidate.json' $creatorIdentity
  $g=Wait-File 'grandchild.json'
  if($Scenario -in @('host-fail','cleanup-fail')){
   # 固定creatorの完了観測を受け取り、終了済みPIDへOS handleを取り直さない。
   Set-Reason 'creator-exit-invalid' 'grandchild.json'
   Guard-Run
   $fields=@($g.PSObject.Properties.Name)
   $allowed=@('pid','nonce','creatorPid','exited','exitCode')
   if($fields.Count -ne $allowed.Count){throw '完了孫field集合不一致'}
   foreach($field in $fields){if($allowed -cnotcontains $field){throw '完了孫の未知field'}}
   if($g.nonce -isnot [string] -or $g.nonce -cne $Nonce){throw '完了孫nonce不一致'}
   if(($g.pid -isnot [int] -and $g.pid -isnot [long]) -or $g.pid -le 0 -or $g.pid -gt [int]::MaxValue -or $g.pid -eq $child.RecordedPid){throw '完了孫pid不正'}
   if(($g.creatorPid -isnot [int] -and $g.creatorPid -isnot [long]) -or $g.creatorPid -ne $child.RecordedPid -or $child.RecordedPid -le 0 -or $child.RecordedCreation -notmatch '^[1-9][0-9]*$'){throw '直接保持creator出生不一致'}
   if($g.exited -isnot [bool] -or $g.exited -ne $true -or ($g.exitCode -isnot [int] -and $g.exitCode -isnot [long]) -or $g.exitCode -ne 0){throw 'creator実exit0観測不一致'}
   $grandchildEvidence=@{kind='creator-observed-exit';owner=$owner;nonce=$Nonce;pid=$g.pid;creator=$creatorIdentity;exited=$true;exitCode=0;osLiveLookup=$false;osExactJobVerified=$false;jobEvidence='creator-chain-inheritance'}
  }else{
   # P4等のlive経路は従来どおり。exact owned Job認定は親が別途行う。
   if($g.nonce -cne $Nonce){throw '孫nonce不一致'}
   $grand=[Diagnostics.Process]::GetProcessById([int]$g.pid)
   try{
    $gh=$grand.Handle
    if(![M21Job]::InJob($grand,$null)){throw '孫Job不一致'}
    $liveIdentity=Identity $grand
    Write-Json 'grandchild-candidate.json' $liveIdentity
    $grandchildEvidence=@{kind='os-live-observation';identity=$liveIdentity;osLiveLookup=$true;osExactJobVerified=$false;jobEvidence='any-job-observation'}
    if($Scenario -eq 'stdio-eof'){$script:p6Grand=$grand;$script:p6GrandIdentity=$liveIdentity}
   }finally{if($Scenario -ne 'stdio-eof' -or $null -eq $script:p6Grand){$grand.Dispose()}}
  }
  if($Scenario -eq 'kill-close'){Start-Sleep -Milliseconds (Remaining);Set-Reason 'work-budget-exhausted';throw 'observer未動作'}
  $workExitCap=Remaining
  $child.Mark('body-exit-before',$workExitCap)
  try{if(!$child.Process.WaitForExit($workExitCap)){Set-Reason 'work-budget-exhausted';throw 'dummy exit期限超過'}}finally{$child.Mark('body-exit-after',$workExitCap)}
  Attempt 'p6-after-body-exit' {P6-State 'after-body-child-exit'}
  if($child.Process.ExitCode -ne 0){Set-Reason 'dummy-nonzero';Fail 'dummy-nonzero' ([InvalidOperationException]::new())}
  Capture $child 'dummy' 500
 }elseif($Mode -eq 'Observe'){
  # observerはJob外に残る。親は直接保持Processだけを停止する。
  $parent=New-Supervisor 'Parent' 'parent';Register-Child $parent 'parent'
  $known=New-Object 'Collections.Generic.List[object]'
  try{
   # 自分が直接保持した親世代が、自身Jobで認定した集合だけを採用する。
   $certificate=Wait-File 'certified-tree.json'
   $parentIdentity=Identity $parent.Process
   if($certificate.owner -cne $owner -or $certificate.nonce -cne $Nonce -or $certificate.certifier.owner -cne $owner -or $certificate.certifier.nonce -cne $Nonce -or $certificate.certifier.pid -ne $parentIdentity.pid -or [string]$certificate.certifier.creation -cne $parentIdentity.creation -or $certificate.members.Count -ne 3){throw '親認定metadata不一致'}
   $roles=@($certificate.members | ForEach-Object {$_.role})
   if(($roles -join ',') -cne 'supervisor,child,grandchild'){throw '親認定集合不一致'}
   foreach($member in $certificate.members){if($member.ownedJobVerified -ne $true){throw '親所有Job未認定'};$known.Add((Open-Known $member.identity $null))}
   foreach($p in $known){if($p.HasExited){throw 'RequiredAlive早期終了'}}
   $parent.Process.Kill()
   $exitEvidence=New-Object 'Collections.Generic.List[object]'
   for($i=0;$i -lt $known.Count;$i++){
    $p=$known[$i]
    if(!$p.WaitForExit((Remaining 5000 -Recovery))){throw 'kill-on-close子残留'}
    $exitEvidence.Add(@{role=$certificate.members[$i].role;identity=$certificate.members[$i].identity;exited=$true;exitCode=$p.ExitCode;evidence='observer-held-handle-exit'})
   }
   Write-Json 'observer.json' @{owner=$owner;nonce=$Nonce;parentKilled=$true;knownExited=$known.Count;exits=@($exitEvidence.ToArray())}
  }finally{foreach($p in $known){Attempt 'observer-handle-close' {$p.Dispose()}}}
 }else{
  Set-Operation 'Parent.createJob'
  $job=[M21Job]::Create()
  Set-Operation 'Parent.startSupervisor'
  Parent-Event 'start-before'
  $supervisor=New-Supervisor 'Supervisor' 'supervisor';Register-Child $supervisor 'supervisor'
  Parent-Event 'start-after' $supervisor
  Set-Operation 'Parent.readHello'
  Parent-Event 'hello-wait-before' $supervisor
  $hello=Wait-File 'hello.json'
  Set-Operation 'Parent.identity'
  $identity=Identity $supervisor.Process
  if($hello.owner -cne $owner -or $hello.nonce -cne $Nonce -or $hello.pid -ne $identity.pid -or [string]$hello.creation -cne $identity.creation -or $supervisor.Process.HasExited){Set-Reason 'hello-identity-mismatch' 'hello.json';throw 'hello世代不一致'}
  Set-Operation 'Parent.assign'
  Require-Work
  if($Scenario -eq 'assign-fail'){Set-Reason 'assign-fault' 'hello.json';throw '割当失敗の故障注入'}
  $job.Assign($supervisor.Process)
  $assigned=$true
  Set-Operation 'Parent.publishOwned'
  Write-Json 'supervisor-owned.json' $identity
  if($Scenario -ne 'pre-ack'){
   $ackNonce=$Nonce;if($Scenario -eq 'wrong-ack'){$ackNonce='00000000000000000000000000000000'}
   Set-Operation 'Parent.publishAck'
   Require-Work
   Write-Json 'ack.json' @{owner=$owner;nonce=$ackNonce;pid=$identity.pid;creation=$identity.creation;ownedJobVerified=$true}
   $ackPublished=$true
  }
  if($Scenario -eq 'kill-close'){
   $members=New-Object 'Collections.Generic.List[object]'
   foreach($entry in @(@{role='supervisor';identity=$identity},@{role='child';identity=(Wait-File 'child-candidate.json')},@{role='grandchild';identity=(Wait-File 'grandchild-candidate.json')})){
    $verified=Open-Known $entry.identity $job
    $certified.Add($verified)
    if($verified.HasExited){throw '認定前RequiredAlive終了'}
    $members.Add(@{role=$entry.role;identity=$entry.identity;ownedJobVerified=$true})
   }
   Write-Json 'certified-tree.json' @{owner=$owner;nonce=$Nonce;certifier=(Identity ([Diagnostics.Process]::GetCurrentProcess()));members=@($members.ToArray())}
   Start-Sleep -Milliseconds (Remaining);Set-Reason 'work-budget-exhausted';throw 'observer待機期限超過'
  }
  if($Scenario -eq 'stdio-eof'){
   Set-Operation 'Parent.p6-certify'
   $childIdentity=Wait-File 'child-candidate.json';Assert-P6Identity $childIdentity
   $grandIdentity=Wait-File 'grandchild-candidate.json';Assert-P6Identity $grandIdentity
   if($childIdentity.pid -eq $grandIdentity.pid){throw 'P6同一pid'}
   $p6ParentChild=Open-Known $childIdentity $job;$certified.Add($p6ParentChild)
   $p6ParentGrand=Open-Known $grandIdentity $job;$certified.Add($p6ParentGrand)
   $p6Certification=@{kind='parent-exact-owned-job';child=$childIdentity;grandchild=$grandIdentity;ownedJobVerified=$true;liveBeforeRelease=$false;releasePublished=$false;jobStopped=$false;childExited=$false;childExitCode=$null;grandchildExited=$false;grandchildExitCode=$null}
   if($p6ParentChild.WaitForExit(0) -or $p6ParentGrand.WaitForExit(0)){throw 'P6認定前終了'}
   $p6Certification.liveBeforeRelease=$true
   Require-Work
   Write-Json 'p6-release.json' @{owner=$owner;nonce=$Nonce;child=$childIdentity;grandchild=$grandIdentity;ownedJobVerified=$true}
   $p6Certification.releasePublished=$true
  }
  Set-Operation 'Parent.wait'
  if(!$supervisor.Process.WaitForExit((Remaining))){Set-Reason 'work-budget-exhausted';throw 'supervisor exit期限超過'}
  if(Test-Path -LiteralPath (Join-Path $run 'supervisor-result.json')){
   $inner=Read-Json 'supervisor-result.json'
   if($null -ne $inner.primary){$primary=$inner.primary}
   foreach($failure in $inner.secondary){$secondary.Add($failure)}
   foreach($item in $inner.streams){$streams.Add($item)}
   foreach($item in $inner.lastHandles){$lastHandles.Add($item)}
   foreach($item in $inner.startupRecovery){$startupRecovery.Add($item)}
   foreach($item in $inner.p6Observations){$p6Observations.Add($item)}
   $grandchildEvidence=$inner.grandchildEvidence
   if($Scenario -in @('host-fail','cleanup-fail') -and !$inner.recovered){$recovered=$false}
  }
  if($supervisor.Process.ExitCode -ne 0 -and $null -eq $primary){Fail 'dummy-nonzero' ([InvalidOperationException]::new())}
  if($Scenario -eq 'normal'){
   $inner=Read-Json 'supervisor-result.json'
   if(!$inner.passed -or !$inner.recovered){throw 'inner失敗'}
  }
 }
}catch{
 Fail 'execution' $_.Exception $_
}
finally {
 if($Mode -ne 'Supervisor'){
  Attempt 'job-stop' {if($null -ne $job){$job.Stop();if($null -ne $p6Certification){$p6Certification.jobStopped=$true}}}
  foreach($child in $held){
   Attempt 'direct-stop' {Parent-Event 'kill-before' $child;if($child.Started -and !$child.Process.HasExited){Parent-Event 'kill-issued' $child;$child.Process.Kill();Parent-Event 'kill-returned' $child}else{Parent-Event 'kill-skipped' $child}}
   Attempt 'exit-and-streams' {Capture $child 'direct' 1500}
  }
  Attempt 'job-empty' {
   if($null -ne $job){
    $clock=[Diagnostics.Stopwatch]::StartNew()
    $cleanupLimit=Remaining 3000 -Recovery
    while($job.Active() -gt 0 -and $clock.ElapsedMilliseconds -lt $cleanupLimit -and (Remaining -Recovery) -gt 0){Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining -Recovery)))}
    $script:jobEmpty=($job.Active() -eq 0)
    if(!$script:jobEmpty){$script:recovered=$false;throw 'Job残留'}
   }
  }
  Attempt 'p6-certified-exit' {
   if($null -ne $p6Certification){
    Attempt 'p6-child-exit' {try{$p6Certification.childExited=$p6ParentChild.WaitForExit(0);if($p6Certification.childExited){$p6Certification.childExitCode=$p6ParentChild.ExitCode}}catch{$script:recovered=$false;throw}}
    Attempt 'p6-grand-exit' {try{$p6Certification.grandchildExited=$p6ParentGrand.WaitForExit(0);if($p6Certification.grandchildExited){$p6Certification.grandchildExitCode=$p6ParentGrand.ExitCode}}catch{$script:recovered=$false;throw}}
    if(!$p6Certification.childExited -or !$p6Certification.grandchildExited){$script:recovered=$false;throw 'P6認定handle回収未確認'}
   }
  }
  Attempt 'job-close' {if($null -ne $job){$job.Dispose();$job=$null}}
  # 将来のDocker清掃/ledgerの位置。prototypeは自己dummyだけを扱う。
  Attempt 'external-cleanup' {Guard-Run;if($Scenario -eq 'cleanup-fail'){Set-Reason 'cleanup-fault';throw 'cleanup故障注入'}}
 }
 # 起動catchで回収不明だった対象も、保持情報を失う前に再度独立に確認する。
 if($Mode -eq 'Supervisor'){
  foreach($child in $held){if($null -ne $child.StartupFailure){
   Attempt 'direct-stop' {if($child.Started -and !$child.Process.HasExited){$child.Process.Kill()}}
   Attempt 'exit-and-streams' {Capture $child 'startup-recheck' 1000}
  }elseif(!$child.Captured){
   # bodyの早期throwでCapture未到達なら、親Job回収へ委ねる未確認として残す。
   $script:recovered=$false
   Fail 'supervisor-unobserved-child' ([InvalidOperationException]::new())
  }}
 }
 foreach($p in $certified){Attempt 'certified-handle-close' {$p.Dispose()}}
 Attempt 'p6-grand-handle-close' {if($null -ne $script:p6Grand){$script:p6Grand.Dispose();$script:p6Grand=$null}}
 foreach($child in $held){
  Attempt 'last-handle' {
   $last=@{pid=$child.RecordedPid;creation=$child.RecordedCreation;exited=(!$child.Started -or $child.Process.WaitForExit(0));stdoutEof=(!$child.Started -or ($null -ne $child.OutTask -and $child.OutTask.Status -eq [Threading.Tasks.TaskStatus]::RanToCompletion));stderrEof=(!$child.Started -or ($null -ne $child.ErrTask -and $child.ErrTask.Status -eq [Threading.Tasks.TaskStatus]::RanToCompletion))}
   $script:lastHandles.Add($last)
   if(!$last.exited -or !$last.stdoutEof -or !$last.stderrEof){$script:recovered=$false}
  }
  Attempt 'dispose' {$child.Dispose()}
 }
 $observer=$null
 Attempt 'observer-metadata' {
  if($Mode -eq 'Observe' -and (Test-Path -LiteralPath (Join-Path $run 'observer.json'))){
   $candidate=Read-Json 'observer.json'
   if($candidate.owner -cne $owner -or $candidate.nonce -cne $Nonce -or $candidate.parentKilled -ne $true -or $candidate.knownExited -ne 3){throw 'observer metadata不一致'}
   if($candidate.exits.Count -ne 3 -or @($candidate.exits | Where-Object {$_.exited -ne $true}).Count -ne 0){throw 'observer actual exit集合不一致'}
   $script:knownExitEvidence=@($candidate.exits)
   $script:observer=$candidate
  }
 }
 Attempt 'result-save' {Guard-Run;if($Scenario -eq 'save-fail'){Set-Reason 'save-fault';throw '初回保存失敗の故障注入'}}
 $processIdentity=$null
 Attempt 'process-identity' {$script:processIdentity=Identity ([Diagnostics.Process]::GetCurrentProcess())}
 # streamsは一次観測、resourceRecoveryは後続Job/直接handle回収の別事実。
 $retained=New-Object 'Collections.Generic.List[object]'
 if(!$recovered){foreach($child in $held){$retained.Add(@{pid=$child.RecordedPid;creation=$child.RecordedCreation;logs=$child.LogPrefix;reason='direct-exit/stream/Job recovery unconfirmed'})}}
 if($null -ne $p6Certification){
  if(!$p6Certification.childExited){$retained.Add(@{identity=$p6Certification.child;reason='P6 certified child exit unconfirmed'})}
  if(!$p6Certification.grandchildExited){$retained.Add(@{identity=$p6Certification.grandchild;reason='P6 certified grandchild exit unconfirmed'})}
 }
 $result=[ordered]@{owner=$owner;nonce=$Nonce;process=$processIdentity;mode=$Mode;scenario=$Scenario;passed=($null -eq $primary);primary=$primary;secondary=@($secondary.ToArray());grandchildEvidence=$grandchildEvidence;p6Observations=@($p6Observations.ToArray());p6Certification=$p6Certification;lateObservation=$lateObservation;parentEvents=@($parentEvents.ToArray());attempts=@($attempts.ToArray());streams=@($streams.ToArray());lastHandles=@($lastHandles.ToArray());knownExitEvidence=$knownExitEvidence;startupRecovery=@($startupRecovery.ToArray());resourceRecovery=@{jobEmpty=$jobEmpty;confirmed=$recovered;retained=@($retained.ToArray())};recovered=$recovered;parentKilled=($null -ne $observer);knownExited=0}
 if($null -ne $observer){$result.knownExited=$observer.knownExited}
 try{
  $name='result.json';if($Mode -eq 'Supervisor'){$name='supervisor-result.json'}
  Write-Json $name $result
 }catch{
  Fail 'result-save-final' $_.Exception $_
  [Console]::Error.WriteLine('M21_RESULT_SAVE_FAILED')
 }
}
if($null -ne $primary){exit 1}
exit 0
