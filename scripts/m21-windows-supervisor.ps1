# M21固定fixture用Windows Job監督。実サービス/任意commandの起動口は持たない。
[CmdletBinding()]
param(
 [ValidateSet('Parent','Supervisor','Observe','ReadinessFixture')][string]$Mode='Parent',
 [ValidateSet('none','parent-before-certificate','lock-release','lock-held','invalid-json','formal-ms','formal-fe','formal-admission','chromium-normal','chromium-parent-close','wsl-relay-loss')][string]$TestFixture='none',
 [Parameter(Mandatory=$true)][ValidateSet('normal','pre-ack','wrong-ack','assign-fail','host-fail','stdio-eof','cleanup-fail','save-fail','owner-mismatch','kill-close','formal-m21')][string]$Scenario,
 [Parameter(Mandatory=$true)][string]$RunDirectory,
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{32}$')][string]$Nonce,
 [ValidatePattern('^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$')][string]$OwnerId='f6efb6d3-51e1-4408-a760-92f7d3642122',
 [Parameter(Mandatory=$true)][string]$NodePath,
 [Parameter(Mandatory=$true)][ValidateRange(0,3000000)][int]$WorkRemainingMs,
 [Parameter(Mandatory=$true)][ValidateRange(0,3300000)][int]$TotalRemainingMs
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
$owner=$OwnerId
$run=[IO.Path]::GetFullPath($RunDirectory).TrimEnd('\')
$utf8=New-Object Text.UTF8Encoding($false)
$primary=$null
$secondary=New-Object 'Collections.Generic.List[object]'
$attempts=New-Object 'Collections.Generic.List[string]'
$streams=New-Object 'Collections.Generic.List[object]'
$held=New-Object 'Collections.Generic.List[object]'
$pendingCaptureExits=New-Object 'Collections.Generic.List[object]'
$lateExitProofs=New-Object 'Collections.Generic.List[object]'
$startupRecovery=New-Object 'Collections.Generic.List[object]'
$certified=New-Object 'Collections.Generic.List[object]'
$recovered=$true
$jobEmpty=$null
$job=$null
$jobCreated=$false
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
$fixtureEvidence=$null
$fixtureLock=$null
$metadataReadiness=New-Object 'Collections.Generic.List[object]'
$parentExitEvidence=$null
$formalContractEvidence=$null
$formalEntryEvidence=$null
$m21FormalAuxiliary=$null
$m21Bridge=$null
$chromiumFixture=$TestFixture -in @('chromium-normal','chromium-parent-close')
$chromiumWitnessCase=$TestFixture -ceq 'chromium-parent-close'
$chromiumWitness=$null
$chromiumWitnessQualified=$false
$chromiumWitnessProof=$null
$chromiumWitnessRecovery=@{fallbackStopUsed=$false;activeAfterFallback=$null;closed=$false;remainingAfterPublicationMs=$null}
$chromiumExe=[IO.Path]::GetFullPath('C:/Users/kenta/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe')
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
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool QueryInformationJobObject(M21Job j,int c,out Extended a,uint n,IntPtr length);
 public uint LimitFlags() { Extended a;Check(QueryInformationJobObject(this,9,out a,(uint)Marshal.SizeOf(typeof(Extended)),IntPtr.Zero));return a.Basic.Flags; }
 public void Stop() { Check(TerminateJobObject(this,71)); }
}
public sealed class M21Child : IDisposable {
 // 固定INFOだけを読む。CLI/子生成なし。Wと局所3秒の双方を短縮側で守る。
 public static Dictionary<string,object> ProbeValkeyEndpoint(int port,string expected,Stopwatch clock,int workDeadlineMs) {
  var proof=new Dictionary<string,object>{{"transport","windows-loopback-info"},{"response_complete",false},{"socket_closed",null},{"within_deadline",false},{"cli_spawned",false},{"childproof",null},{"green",false},{"reason","endpoint-invalid"},{"response_bytes",0}};
  var result=new Dictionary<string,object>{{"run_id",null},{"socket_proof",proof}};
  System.Net.Sockets.TcpClient client=null;System.Net.Sockets.NetworkStream stream=null;System.Threading.WaitHandle wait=null;
  long start=clock.ElapsedTicks, cap=start+Stopwatch.Frequency*3;
  Func<int> remaining=()=>{long ticks=Math.Min(cap-clock.ElapsedTicks,(long)workDeadlineMs*Stopwatch.Frequency/1000-clock.ElapsedTicks);return ticks<=0?0:(int)Math.Min(3000,ticks*1000/Stopwatch.Frequency);};
  int count=0;bool valid=false;
  try {
   if(port<1||port>65535||!System.Text.RegularExpressions.Regex.IsMatch(expected??"","\\A[a-f0-9]{40}\\z"))throw new InvalidDataException();
   if(remaining()<=0){proof["reason"]="deadline";throw new TimeoutException();}
   client=new System.Net.Sockets.TcpClient(System.Net.Sockets.AddressFamily.InterNetwork);
   var connect=client.BeginConnect(System.Net.IPAddress.Loopback,port,null,null);wait=connect.AsyncWaitHandle;
   if(!wait.WaitOne(remaining())){proof["reason"]="deadline";throw new TimeoutException();}client.EndConnect(connect);
   stream=client.GetStream();
   byte[] command=System.Text.Encoding.ASCII.GetBytes("*2\r\n$4\r\nINFO\r\n$6\r\nserver\r\n");
   int ms=remaining();if(ms<=0)throw new TimeoutException();stream.WriteTimeout=ms;stream.Write(command,0,command.Length);
   Func<int> read=()=>{int left=remaining();if(left<=0)throw new TimeoutException();stream.ReadTimeout=left;int b=stream.ReadByte();if(b<0)throw new EndOfStreamException();count++;proof["response_bytes"]=count;if(count>16384)throw new InvalidDataException();return b;};
   proof["reason"]="resp-invalid";var header=new List<byte>();
   for(;;){int b=read();header.Add((byte)b);if(header.Count>32)throw new InvalidDataException();if(b==10)break;}
   string line=System.Text.Encoding.ASCII.GetString(header.ToArray());
   if(!System.Text.RegularExpressions.Regex.IsMatch(line,"\\A\\$[1-9][0-9]{0,4}\r\n\\z"))throw new InvalidDataException();
   int size=int.Parse(line.Substring(1,line.Length-3),System.Globalization.CultureInfo.InvariantCulture);
   if(size>16384-header.Count-2)throw new InvalidDataException();
   byte[] body=new byte[size];for(int i=0;i<size;i++)body[i]=(byte)read();
   if(read()!=13||read()!=10)throw new InvalidDataException();proof["response_complete"]=true;
   // 取得済みframeは厳密長。直後にqueuedな追加bytesも拒否し、未来の不在は主張しない。
   if(client.Available!=0)throw new InvalidDataException();
   string info=new System.Text.UTF8Encoding(false,true).GetString(body);
   if(!info.StartsWith("# Server\r\n",StringComparison.Ordinal)||!info.EndsWith("\r\n",StringComparison.Ordinal))throw new InvalidDataException();
   string found=null;int matches=0;
   foreach(string item in info.Split(new[]{"\r\n"},StringSplitOptions.None))if(item.StartsWith("run_id:",StringComparison.Ordinal)){matches++;found=item.Substring(7);}
   proof["reason"]="run-id-mismatch";
   if(matches!=1||!System.Text.RegularExpressions.Regex.IsMatch(found??"","\\A[a-f0-9]{40}\\z")||found!=expected)throw new InvalidDataException();
   if(remaining()<=0)throw new TimeoutException();proof["within_deadline"]=true;result["run_id"]=found;valid=true;
  } catch(TimeoutException){proof["reason"]="deadline";} catch(System.Net.Sockets.SocketException){proof["reason"]="socket-failed";} catch(InvalidDataException){} catch(IOException){if(remaining()<=0)proof["reason"]="deadline";} catch(ArgumentException){proof["reason"]="endpoint-invalid";}
  finally {
   bool closed=true;try{if(stream!=null)stream.Dispose();}catch{closed=false;}try{if(client!=null)client.Close();}catch{closed=false;}try{if(wait!=null)wait.Dispose();}catch{closed=false;}
   proof["socket_closed"]=client==null?null:(object)closed;proof["green"]=valid&&closed&&remaining()>0;
   if((bool)proof["green"])proof["reason"]="matched";else result["run_id"]=null;
  }
  return result;
 }
 public Process Process;public Task OutTask,ErrTask;FileStream output,error;
 public bool Started,StartupExited,StartupStdoutEof,StartupStderrEof,Captured,StdoutPublished,StderrPublished;
 public bool StdoutPublicationAttempted,StderrPublicationAttempted;
 bool bridgeInput,bridgeInputClosed,bridgeInputBroken;
 public int? BridgeInputCodePage,BridgeInputPreambleLength;
 public bool BridgeWriteCompleted,BridgeFlushCompleted;
 public string BridgeInputStage;
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
  return Start(exe,args,cwd,prefix,recoveryMs,observeTimings,null);
 }
 public static M21Child Start(string exe,string[] args,string cwd,string prefix,int recoveryMs,bool observeTimings,Dictionary<string,string> environment) {
  return StartCore(exe,args,cwd,prefix,recoveryMs,observeTimings,environment,false);
 }
 public static M21Child StartBridge(string[] args,string cwd,string prefix,int recoveryMs,Dictionary<string,string> environment) {
  return StartCore("C:/Windows/System32/wsl.exe",args,cwd,prefix,recoveryMs,false,environment,true);
 }
 static string BridgeArguments(string[] args) {
  if(args==null||(args.Length!=16&&args.Length!=17)||args[0]!="-d"||args[1]!="Ubuntu-24.04"||args[2]!="--exec"||args[3]!="/usr/bin/python3"||args[4]!="-I")throw new InvalidDataException("固定WSL prefix不一致");
  bool fixture=args.Length==17;int offset=fixture?7:6;
  if((fixture&&args[6]!="--relay-fixture")||!args[5].EndsWith(fixture?"/test_m21_wsl_docker_bridge.py":"/m21-wsl-docker-bridge.py",StringComparison.Ordinal)||args[offset]!="--run-directory"||args[offset+2]!="--owner"||args[offset+4]!="--run"||args[offset+6]!="--nonce"||args[offset+8]!="--input-commit")throw new InvalidDataException("固定WSL metadata引数不一致");
  // WSLの先頭optionはliteral。--exec後のsource/metadata値には既存Quoteを使う。
  StringBuilder a=new StringBuilder("-d Ubuntu-24.04 --exec /usr/bin/python3 -I");
  for(int i=5;i<args.Length;i++){a.Append(' ');a.Append(Quote(args[i]));}return a.ToString();
 }
 static M21Child StartCore(string exe,string[] args,string cwd,string prefix,int recoveryMs,bool observeTimings,Dictionary<string,string> environment,bool bridgeInput) {
  Stopwatch recoveryClock=Stopwatch.StartNew();
  Func<int,int> remaining=cap=>Math.Max(0,Math.Min(cap,recoveryMs-(int)recoveryClock.ElapsedMilliseconds));
  M21Child c=new M21Child();c.LogPrefix=prefix;c.ObserveTimings=observeTimings;c.bridgeInput=bridgeInput;
  try {
   c.output=new FileStream(prefix+".out",FileMode.CreateNew,FileAccess.Write,FileShare.Read);
   c.error=new FileStream(prefix+".err",FileMode.CreateNew,FileAccess.Write,FileShare.Read);
   ProcessStartInfo s=new ProcessStartInfo(exe);s.UseShellExecute=false;s.CreateNoWindow=true;s.WorkingDirectory=cwd;
   s.RedirectStandardOutput=true;s.RedirectStandardError=true;
   s.RedirectStandardInput=bridgeInput;
   if(bridgeInput){s.Arguments=BridgeArguments(args);}
   else{StringBuilder a=new StringBuilder();foreach(string x in args){if(a.Length>0)a.Append(' ');a.Append(Quote(x));}s.Arguments=a.ToString();}
   if(environment!=null){s.EnvironmentVariables.Clear();foreach(KeyValuePair<string,string> item in environment)s.EnvironmentVariables.Add(item.Key,item.Value);}
   System.Text.Encoding originalInput=null;bool inputChanged=false;Exception launchFailure=null;
   try {
    if(bridgeInput){
     originalInput=Console.InputEncoding;byte[] preamble=originalInput.GetPreamble();
     if(preamble.Length>0){
      if(originalInput.CodePage!=65001||preamble.Length!=3||preamble[0]!=239||preamble[1]!=187||preamble[2]!=191)throw new InvalidDataException("未知stdin preamble拒否");
      inputChanged=true;Console.InputEncoding=new System.Text.UTF8Encoding(false);
     }
     c.BridgeInputCodePage=Console.InputEncoding.CodePage;c.BridgeInputPreambleLength=Console.InputEncoding.GetPreamble().Length;
     if(c.BridgeInputPreambleLength!=0)throw new InvalidDataException("stdin preamble不成立");
    }
    c.Process=new Process();c.Process.StartInfo=s;if(!c.Process.Start())throw new IOException("起動失敗");c.Started=true;
    c.RecordedPid=c.Process.Id;
    IntPtr retained=c.Process.Handle;
    c.RecordedCreation=c.Process.StartTime.ToUniversalTime().Ticks.ToString(System.Globalization.CultureInfo.InvariantCulture);
   }catch(Exception e){launchFailure=e;throw;}
   finally {
    if(inputChanged)try{Console.InputEncoding=originalInput;}catch(Exception restore){if(launchFailure==null)throw;c.CleanupFailure("input-encoding-restore",restore);}
   }
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
 public void SendBridgeFrame(byte[] frame,int milliseconds) {
  BridgeWriteCompleted=false;BridgeFlushCompleted=false;BridgeInputStage="guard";
  if(!bridgeInput||bridgeInputClosed||bridgeInputBroken||!Started||frame==null||frame.Length<5||frame.Length>16388||milliseconds<=0||Process.WaitForExit(0)){BridgeInputStage="guard-rejected";throw new InvalidOperationException("bridge frame拒否");}
  int length=(frame[0]<<24)|(frame[1]<<16)|(frame[2]<<8)|frame[3];
  if(length!=frame.Length-4){BridgeInputStage="guard-rejected";throw new InvalidOperationException("bridge frame長不一致");}
  byte[] payload=(byte[])frame.Clone();Task write=null;
  Stopwatch ioClock=Stopwatch.StartNew();Func<int> left=()=>Math.Max(0,milliseconds-(int)ioClock.ElapsedMilliseconds);
  try{
   BridgeInputStage="write-started";
   write=Process.StandardInput.BaseStream.WriteAsync(payload,0,payload.Length);
   write.ContinueWith(t=>Array.Clear(payload,0,payload.Length),TaskScheduler.Default);
   if(!write.Wait(left())||write.Status!=TaskStatus.RanToCompletion||left()<=0)throw new TimeoutException("bridge frame書込期限");
   BridgeWriteCompleted=true;
   BridgeInputStage="flush-started";
   Task flush=Process.StandardInput.BaseStream.FlushAsync();
   if(!flush.Wait(left())||flush.Status!=TaskStatus.RanToCompletion||left()<=0)throw new TimeoutException("bridge frameflush期限");
   BridgeFlushCompleted=true;
   BridgeInputStage="write-flush-completed";
  }
  catch{bridgeInputBroken=true;throw;}
  finally{if(write==null)Array.Clear(payload,0,payload.Length);}
 }
 public void CloseBridgeInput() { if(bridgeInput&&Started&&!bridgeInputClosed){bridgeInputClosed=true;Process.StandardInput.Close();} }
 public bool Eof(bool stdout,int ms) {
  string role=stdout?"stdout":"stderr";Mark(role+"-eof-before",ms);
  try{
   Task t=stdout?OutTask:ErrTask;if(t==null)return false;
   bool done=t.Wait(ms);if(done){if(stdout){if(!StdoutPublished)output.Flush();}else{if(!StderrPublished)error.Flush();}}return done;
  }finally{Mark(role+"-eof-after",ms);}
 }
 public void PublishCapturedOutput(bool stdout) {
  if(stdout&&!StdoutPublished){if(StdoutPublicationAttempted)throw new InvalidOperationException("stdout publication再試行拒否");StdoutPublicationAttempted=true;}
  if(!stdout&&!StderrPublished){if(StderrPublicationAttempted)throw new InvalidOperationException("stderr publication再試行拒否");StderrPublicationAttempted=true;}
  // 両pump完了と保持Processの実exit後だけ。Process handleは保持し続ける。
  if(!Started||!Process.WaitForExit(0)||OutTask==null||ErrTask==null||OutTask.Status!=TaskStatus.RanToCompletion||ErrTask.Status!=TaskStatus.RanToCompletion)throw new InvalidOperationException("未完了stdioのpublication拒否");
  if(stdout){if(StdoutPublished)return;output.Dispose();StdoutPublished=true;}
  else{if(StderrPublished)return;error.Dispose();StderrPublished=true;}
 }
 public void Dispose() {
  List<Exception> errors=new List<Exception>();
  try{CloseBridgeInput();}catch(Exception e){errors.Add(e);}
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
 }catch{
  Remember-Failure $_
  # 固定試練だけの観測点。実read失敗を解放後も元例外のまま再throwする。
  if($Mode -eq 'ReadinessFixture' -and $name -ceq 'child-candidate.json' -and $script:ioOperation -ceq 'read' -and $null -ne $fixtureEvidence){Record-FixtureReadFailure $_}
  throw
 }
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
function Assert-ChromiumWitnessAck([object]$ack,[object]$expected) {
 $fields=@($ack.PSObject.Properties.Name)
 if($fields.Count -ne 5){throw 'witness ACK field集合不一致'}
 foreach($field in $fields){if(@('owner','nonce','pid','creation','ownedJobVerified') -cnotcontains $field){throw 'witness ACK未知field'}}
 if($ack.owner -isnot [string] -or $ack.owner -cne $expected.owner -or $ack.nonce -isnot [string] -or $ack.nonce -cne $expected.nonce -or ($ack.pid -isnot [int] -and $ack.pid -isnot [long]) -or $ack.pid -ne $expected.pid -or $ack.creation -isnot [string] -or $ack.creation -cne $expected.creation -or $ack.ownedJobVerified -isnot [bool] -or !$ack.ownedJobVerified){throw 'witness ACK出生/所属不一致'}
}
function Wait-ChromiumWitnessAck {
 # native型/子生成前。既存Remaining Wと原子的metadataだけを使う固定Parent gate。
 Guard-Run;Require-Work
 $current=[Diagnostics.Process]::GetCurrentProcess()
 $expected=@{owner=$owner;nonce=$Nonce;pid=$current.Id;creation=$current.StartTime.ToUniversalTime().Ticks.ToString()}
 Write-Json 'chromium-witness-hello.json' $expected
 while(!(Test-Path -LiteralPath (Join-Path $run 'chromium-witness-ack.json')) -and (Remaining) -gt 0){Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining)))}
 Require-Work
 $ack=Read-Json 'chromium-witness-ack.json'
 Assert-ChromiumWitnessAck $ack ([pscustomobject]$expected)
 Guard-Run;Require-Work
}
function Test-ChromiumWitnessQualification([object]$proof) {
 foreach($key in @('parentOutsideInner','rootsInsideInner','observerOutsideWitness','parentInsideWitness','rootsInsideWitness','parentExited','rootsExited','parentStdoutEof','parentStderrEof')){if($proof[$key] -isnot [bool] -or !$proof[$key]){return $false}}
 foreach($key in @('outerStopUsed','outerClosedBeforeProof','priorFailure','innerActiveZeroObserved','browserEofObserved','fullRendererActualObserved')){if($proof[$key] -isnot [bool] -or $proof[$key]){return $false}}
 foreach($key in @('innerFlags','activeBefore','activeAfter')){if($proof[$key] -isnot [int] -and $proof[$key] -isnot [long] -and $proof[$key] -isnot [uint32]){return $false}}
 if(($proof['remainingRecoveryMs'] -isnot [int] -and $proof['remainingRecoveryMs'] -isnot [long] -and $proof['remainingRecoveryMs'] -isnot [uint32]) -or $proof['remainingRecoveryMs'] -le 0){return $false}
 return ($proof.innerFlags -eq 8192 -and $proof.activeBefore -gt 0 -and $proof.activeAfter -eq 0)
}
$bootstrapStage='native-bootstrap'
try {
 if(($TestFixture -eq 'none' -and $Mode -eq 'ReadinessFixture') -or ($TestFixture -eq 'parent-before-certificate' -and ($Mode -notin @('Parent','Observe') -or $Scenario -ne 'kill-close')) -or ($TestFixture -in @('lock-release','lock-held','invalid-json') -and ($Mode -ne 'ReadinessFixture' -or $Scenario -ne 'normal'))){throw '固定fixture組合せ不一致'}
 if($TestFixture -in @('formal-ms','formal-fe','formal-admission') -and ($Mode -notin @('Parent','Supervisor') -or $Scenario -ne 'normal')){throw '固定formal契約fixture組合せ不一致'}
 if($TestFixture -eq 'wsl-relay-loss' -and ($Mode -cne 'Parent' -or $Scenario -cne 'normal')){throw '固定relay fixture組合せ不一致'}
 if($chromiumFixture -and (($TestFixture -eq 'chromium-normal' -and ($Scenario -ne 'normal' -or $Mode -notin @('Parent','Supervisor'))) -or ($TestFixture -eq 'chromium-parent-close' -and $Scenario -ne 'kill-close'))){throw '固定Chromium組合せ不一致'}
 # 定義だけを読む。ACK前に型compile/実子/asset準備を行わない。
 . (Join-Path $PSScriptRoot 'm21-windows-formal-adapter.ps1')
 Assert-M21ScenarioBudget $Scenario $Mode $TestFixture $WorkRemainingMs $TotalRemainingMs
 if($Scenario -ceq 'formal-m21' -and !$PSBoundParameters.ContainsKey('OwnerId')){throw '正式入口は現ownerの明示が必要'}
 if($chromiumWitnessCase -and $Mode -ceq 'Parent'){Wait-ChromiumWitnessAck}
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
  $name='result.json';if($Mode -eq 'Supervisor'){$name='supervisor-result.json'}elseif($Mode -eq 'Observe'){$name='observe-result.json'}
  Write-Json $name @{owner=$owner;nonce=$Nonce;process=$processIdentity;passed=$false;primary=$primary;secondary=@($secondary.ToArray());grandchildEvidence=$grandchildEvidence;p6Observations=@();metadataReadiness=@($metadataReadiness.ToArray());parentExitEvidence=$parentExitEvidence;formalContractEvidence=$formalContractEvidence;formalEntryEvidence=$formalEntryEvidence;parentEvents=@($parentEvents.ToArray());attempts=@($attempts.ToArray());streams=@();lastHandles=@();startupRecovery=@();resourceRecovery=@{confirmed=$true;jobEmpty=$null;retained=@()};recovered=$true;parentKilled=$false;knownExited=0}
  $script:bootstrapSaved=$true
 }
 if(!$bootstrapSaved){[Console]::Error.WriteLine((@{passed=$false;primary=$primary;secondary=@($secondary.ToArray())}|ConvertTo-Json -Depth 12 -Compress))}
 exit 1
}
function Identity([Diagnostics.Process]$process) {
 return [ordered]@{owner=$owner;pid=$process.Id;creation=$process.StartTime.ToUniversalTime().Ticks.ToString();nonce=$Nonce}
}
function Read-LockCode([Management.Automation.ErrorRecord]$record,[string]$name) {
 # read段階の同じ例外だけを対象にする。任意例外のlow wordで判定しない。
 $context=$script:failureContext
 if($null -eq $context -or $context.ioOperation -cne 'read' -or $context.metadataFile -cne $name){return 0}
 $current=$record.Exception;$matchesContext=$false;$code=0
 for($i=0;$null -ne $current -and $i -lt 8;$i++){
  if([object]::ReferenceEquals($context.exception,$current)){$matchesContext=$true}
  if($current -is [IO.IOException]){if($current.HResult -eq -2147024864){$code=32}elseif($current.HResult -eq -2147024863){$code=33}}
  $current=$current.InnerException
 }
 if($matchesContext){return $code};return 0
}
function Assert-ObserverParentAlive {
 if($parent.Process.WaitForExit(0)){
  $script:parentExitEvidence=@{identity=@{owner=$owner;nonce=$Nonce;pid=$parent.RecordedPid;creation=$parent.RecordedCreation};exited=$true;exitCode=$parent.Process.ExitCode;certificateAdopted=$false;receiptPresent=$false;receipt=$null}
  Set-Operation 'Observe.parent-exit'
  Set-Reason 'parent-exited-before-certificate' 'certified-tree.json'
  throw ([InvalidOperationException]::new())
 }
}
function Wait-File([string]$name,[switch]$ParentCertificate) {
 if($ParentCertificate -and ($Mode -ne 'Observe' -or $name -cne 'certified-tree.json')){throw '固定cert待ち以外のParent監視は拒否'}
 $clock=[Diagnostics.Stopwatch]::StartNew()
 $milliseconds=Remaining
 $stats=@{file=$name;count32=0;count33=0;firstElapsedMs=$null;lastElapsedMs=$null;cumulativeWaitMs=0;lastHResult=$null;recovered=$false;exhausted=$false}
 $script:metadataReadiness.Add($stats)
 $published=$false;$lastLockRecord=$null;$lastLockContext=$null
 while((Remaining) -gt 0){
  if($ParentCertificate){Assert-ObserverParentAlive}
  $retryWaiting=$false
  if(Test-Path -LiteralPath (Join-Path $run $name)){
   $published=$true
   try{
    # 毎回Get-Itemのsize/reparse境界、read、decodeをやり直す。
    if($null -ne $lastLockRecord){Guard-Run} # retry時のowner/経路異常自体はretryしない。
    $value=Read-Json $name
    $stats.recovered=($stats.count32+$stats.count33 -gt 0)
    return $value
   }catch{
    $code=Read-LockCode $_ $name
    if($code -eq 0){throw}
    $lastLockRecord=$_;$lastLockContext=$script:failureContext
    $script:failureContext=$null # 回復待ちの旧例外を次の無関係なfailureへ流用しない。
    if($code -eq 32){$stats.count32++}else{$stats.count33++}
    if($null -eq $stats.firstElapsedMs){$stats.firstElapsedMs=$clock.ElapsedMilliseconds}
    $stats.lastElapsedMs=$clock.ElapsedMilliseconds
    $stats.lastHResult=([int]-2147024896+$code)
    $retryWaiting=$true
   }
  }elseif($published){Set-Reason 'metadata-disappeared' $name;throw ([IO.FileNotFoundException]::new())}
  $beforeSleep=$clock.ElapsedMilliseconds
  Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining)))
  if($retryWaiting){$stats.cumulativeWaitMs+=($clock.ElapsedMilliseconds-$beforeSleep)}
 }
 if($null -ne $lastLockRecord){
  $stats.exhausted=$true
  Set-Reason 'metadata-readiness-exhausted' $name $clock.ElapsedMilliseconds $milliseconds
  $script:failureContext=$lastLockContext
  $script:failureContext.reason='metadata-readiness-exhausted';$script:failureContext.reasonFile=$name
  $script:failureContext.elapsedMs=$clock.ElapsedMilliseconds;$script:failureContext.limitMs=$milliseconds
  throw $lastLockRecord
 }
 Set-Reason 'work-budget-exhausted' $name $clock.ElapsedMilliseconds $milliseconds
 if($operation -eq 'Parent.readHello'){Parent-Event 'hello-deadline'}
 throw 'metadata待機期限超過'
}
function New-Supervisor([string]$childMode,[string]$prefix) {
 Require-Work
 $ps=[Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
 $childArguments=@('-NoProfile','-NonInteractive','-File',$PSCommandPath,'-Mode',$childMode,'-Scenario',$Scenario,'-RunDirectory',$run,'-Nonce',$Nonce,'-OwnerId',$owner,'-NodePath',$NodePath,'-WorkRemainingMs',[string](Remaining),'-TotalRemainingMs',[string](Remaining -Recovery))
 if($TestFixture -in @('parent-before-certificate','formal-ms','formal-fe','formal-admission') -or $chromiumFixture){$childArguments+=@('-TestFixture',$TestFixture)}
 Require-Work
 if($Scenario -ceq 'formal-m21'){
  $environment=New-M21FormalSupervisorEnvironment
  Require-Work
  # 2回の時計読取の丸めでRを1ms削らない。保存済みGを基準にWを短縮する。
  $formalTotal=Remaining -Recovery
  $formalWork=[Math]::Min((Remaining),[Math]::Max(0,$formalTotal-300000))
  $childArguments[[Array]::IndexOf($childArguments,'-WorkRemainingMs')+1]=[string]$formalWork
  $childArguments[[Array]::IndexOf($childArguments,'-TotalRemainingMs')+1]=[string]$formalTotal
  return [M21Child]::Start($ps,$childArguments,$PSScriptRoot,(Join-Path $run $prefix),(Remaining -Recovery),$false,$environment)
 }
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
function Release-M21AssetChild([M21Child]$child) {
 # 証拠保存後のasset CLIだけ。同じ保持参照の完了を確認してからhandleを解放する。
 $matches=@($script:held|Where-Object {[object]::ReferenceEquals($_,$child)})
 if($matches.Count -ne 1 -or !$child.Captured -or !$child.Started -or !$child.Process.WaitForExit(0) -or !$child.StdoutPublished -or !$child.StderrPublished){Set-Reason 'asset-release-unverified';throw ([InvalidOperationException]::new())}
 $script:lastHandles.Add(@{pid=$child.RecordedPid;creation=$child.RecordedCreation;exited=$true;stdoutEof=$true;stderrEof=$true})
 $child.Dispose()
 $script:held.Remove($child) > $null
}
function P6-State([string]$stage) {
 if($Mode -ne 'Supervisor' -or $Scenario -ne 'stdio-eof' -or $null -eq $script:p6Grand){return}
 $snapshot=@{stage=$stage;utcTicks=[DateTime]::UtcNow.Ticks.ToString();localMs=$localClock.ElapsedMilliseconds;pid=$script:p6GrandIdentity.pid;creation=$script:p6GrandIdentity.creation;waitExit0=$script:p6Grand.WaitForExit(0);hasExited=$script:p6Grand.HasExited;exitCode=$null}
 if($snapshot.waitExit0){$snapshot.exitCode=$script:p6Grand.ExitCode}
 $script:p6Observations.Add($snapshot)
}
function Capture([M21Child]$child,[string]$role,[int]$milliseconds) {
 if(!$child.Started){$script:streams.Add(@{role=$role;notStarted=$true;exited=$true;exitCode=$null;stdoutEof=$true;stderrEof=$true});$child.Captured=$true;return}
 $item=[ordered]@{role=$role;pid=$child.RecordedPid;creation=$child.RecordedCreation;logs=$child.LogPrefix;exited=$false;exitCode=$null;stdoutEof=$false;stderrEof=$false;stdoutPublished=$false;stderrPublished=$false;timingOrigin='held-child-instance';timings=@();timingOverflow=$false}
 $exitMilliseconds=$milliseconds;$streamMilliseconds=$milliseconds
 if($Scenario -ceq 'formal-m21'){$exitMilliseconds=[Math]::Min(2000,$milliseconds);$streamMilliseconds=[Math]::Min(1000,$milliseconds)}
 $exitCap=Remaining $exitMilliseconds -Recovery
 $exitWaitFaulted=$false
 $child.Mark('capture-exit-before',$exitCap)
 try{$item.exited=$child.Process.WaitForExit($exitCap);if($item.exited){$item.exitCode=$child.Process.ExitCode}}catch{$exitWaitFaulted=$true;$script:recovered=$false;Fail ($role+'-exit') $_.Exception}finally{$child.Mark('capture-exit-after',$exitCap)}
 Attempt 'p6-after-child-exit' {P6-State 'after-child-exit'}
 if(!$item.exited){
  Fail ($role+'-exit-timeout') ([TimeoutException]::new())
  if(!$exitWaitFaulted -and !$child.StdoutPublicationAttempted -and !$child.StderrPublicationAttempted){
   $existing=@($script:pendingCaptureExits|Where-Object {[object]::ReferenceEquals($_.child,$child)})
   if($existing.Count -eq 0){$script:pendingCaptureExits.Add(@{child=$child;role=$role;initialExitCapMs=$exitCap;initialExitObserved=$false;initialPublicationAttempted=$false})}
  }else{$script:recovered=$false}
 }
 Attempt 'p6-stdout-before' {P6-State 'stdout-before'}
 try{$item.stdoutEof=$child.Eof($true,(Remaining $streamMilliseconds -Recovery));if(!$item.stdoutEof){Set-Reason 'stdout-eof-timeout';throw 'stdout EOF期限超過'}}catch{$script:recovered=$false;Fail ($role+'-stdout') $_.Exception $_}finally{Attempt 'p6-stdout-after' {P6-State 'stdout-after'}}
 Attempt 'p6-stderr-before' {P6-State 'stderr-before'}
 try{$item.stderrEof=$child.Eof($false,(Remaining $streamMilliseconds -Recovery));if(!$item.stderrEof){Set-Reason 'stderr-eof-timeout';throw 'stderr EOF期限超過'}}catch{$script:recovered=$false;Fail ($role+'-stderr') $_.Exception $_}finally{Attempt 'p6-stderr-after' {P6-State 'stderr-after'}}
 if($item.exited -and $item.stdoutEof -and $item.stderrEof){
  Attempt ($role+'-stdout-publication') {try{$child.PublishCapturedOutput($true)}catch{$script:recovered=$false;Set-Reason 'stdout-publication-failed';throw}}
  Attempt ($role+'-stderr-publication') {try{$child.PublishCapturedOutput($false)}catch{$script:recovered=$false;Set-Reason 'stderr-publication-failed';throw}}
 }
 $item.stdoutPublished=$child.StdoutPublished;$item.stderrPublished=$child.StderrPublished
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
function Invoke-ChromiumFixture {
 # ACK後だけ。固定bundled実体と自己runの新規3profile以外を受け付けない。
 Guard-Run;Require-Work
 if((Get-FileHash -LiteralPath $chromiumExe -Algorithm SHA256).Hash.ToLowerInvariant() -cne '290fa7018fda22c52ada5eddb0113baf3ebc41fd0fde6085eddb19793606c635'){throw 'Chromium SHA不一致'}
 if((Get-FileHash -LiteralPath $NodePath -Algorithm SHA256).Hash.ToLowerInvariant() -cne '0d0f5e39f9f3d9587bc19f73eab3c2c9c4903fd02d6dbf9c853dd81b3d95fad4'){throw 'Node22 SHA不一致'}
 $profiles=Join-Path $run 'profiles'
 if(Test-Path -LiteralPath $profiles){throw 'profile既存'}
 [IO.Directory]::CreateDirectory($profiles)>$null
 $environment=New-Object 'Collections.Generic.Dictionary[string,string]' ([StringComparer]::OrdinalIgnoreCase)
 foreach($key in @('SystemRoot','WINDIR')){$value=[Environment]::GetEnvironmentVariable($key);if($null -ne $value){$environment.Add($key,$value)}}
 $environment.Add('TEMP',$run);$environment.Add('TMP',$run)
 $members=New-Object 'Collections.Generic.List[object]'
 foreach($role in @('system','admin-de','member')){
  Guard-Run;Require-Work
  $profile=Join-Path $profiles $role
  if(Test-Path -LiteralPath $profile){throw 'profile既存'}
  [IO.Directory]::CreateDirectory($profile)>$null
  $cursor=Get-Item -LiteralPath $profile
  while($null -ne $cursor){if($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'profile reparse'};$cursor=$cursor.Parent}
  if(![IO.Path]::GetFullPath($profile).StartsWith($run+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'profile範囲外'}
  $title='M21-'+$Nonce+'-'+$role
  $page='data:text/html,'+[Uri]::EscapeDataString('<!doctype html><meta charset="utf-8"><title>'+$title+'</title><h1>'+$title+'</h1>')
  $child=[M21Child]::Start($chromiumExe,[string[]]@(('--user-data-dir='+$profile),'--no-first-run','--no-default-browser-check','--new-window',$page),$run,(Join-Path $run ('chromium-'+$role)),(Remaining -Recovery),$false,$environment)
  Register-Child $child ('chromium-'+$role)
  if($child.Process.WaitForExit(0) -or ![string]::Equals($child.Process.MainModule.FileName,$chromiumExe,[StringComparison]::OrdinalIgnoreCase)){throw 'Chromium実体/生存不一致'}
  $members.Add(@{role=$role;identity=(Identity $child.Process);profile=$profile;title=$title;exe=$chromiumExe})
 }
 Write-Json 'chromium-candidates.json' @{owner=$owner;nonce=$Nonce;members=@($members.ToArray())}
 if($TestFixture -eq 'chromium-parent-close'){Start-Sleep -Milliseconds (Remaining);throw 'Chromium observer期限超過'}
 foreach($child in $held){
  if(!$child.Process.WaitForExit((Remaining))){Set-Reason 'chromium-normal-close-timeout';throw 'Sky close期限超過'}
  if($child.Process.ExitCode -ne 0){Set-Reason 'chromium-normal-nonzero';throw 'Chrome通常exit非0'}
  Capture $child 'chromium-normal-close' 500
 }
}
function Certify-Chromium {
 $candidate=Wait-File 'chromium-candidates.json'
 if($candidate.owner -cne $owner -or $candidate.nonce -cne $Nonce -or $candidate.members.Count -ne 3){throw 'Chromium候補不一致'}
 $roles=@($candidate.members|ForEach-Object {$_.role})
 if(($roles -join ',') -cne 'system,admin-de,member'){throw 'Chromium role集合不一致'}
 $members=New-Object 'Collections.Generic.List[object]'
 $members.Add(@{role='supervisor';identity=$identity;ownedJobVerified=$true})
 foreach($entry in $candidate.members){
  Assert-P6Identity $entry.identity
  if($entry.profile -cne (Join-Path (Join-Path $run 'profiles') $entry.role) -or $entry.title -cne ('M21-'+$Nonce+'-'+$entry.role) -or $entry.exe -cne $chromiumExe){throw 'Chromium固定metadata不一致'}
  $cursor=Get-Item -LiteralPath $entry.profile
  while($null -ne $cursor){if($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint){throw '認定profile reparse'};$cursor=$cursor.Parent}
  $verified=Open-Known $entry.identity $job;$certified.Add($verified)
  if($verified.WaitForExit(0) -or ![string]::Equals($verified.MainModule.FileName,$chromiumExe,[StringComparison]::OrdinalIgnoreCase)){throw 'Chromium認定時実体/生存不一致'}
  $members.Add(@{role=$entry.role;identity=$entry.identity;ownedJobVerified=$true;profile=$entry.profile;title=$entry.title;exe=$chromiumExe})
 }
 if(@($members|ForEach-Object {$_.identity.pid}|Select-Object -Unique).Count -ne 4){throw 'Chromium PID重複'}
 Write-Json 'chromium-ready.json' @{owner=$owner;nonce=$Nonce;fixture=$TestFixture;certifier=(Identity ([Diagnostics.Process]::GetCurrentProcess()));members=@($members.ToArray())}
 return ,$members
}
function Invoke-FormalContractFixture {
 # 固定test driverだけ。将来の正式adapter関数は本commitでは実装しない。
 $script:formalContractEvidence=@{fixture=$TestFixture;passed=$false;identity=(Identity $self);actualSubMs=$null;vectors=@();child=$null;observations=$null}
 Set-Operation ('Supervisor.'+$TestFixture)
 Set-Reason $TestFixture
 $adapter=Join-Path $PSScriptRoot 'm21-windows-formal-adapter.ps1'
 if(Test-Path -LiteralPath $adapter){. $adapter} # 固定sourceの関数定義だけを読む。
 if($TestFixture -eq 'formal-ms'){
  Add-Type -AssemblyName System.Numerics
  $ticks=$self.StartTime.ToUniversalTime().Ticks.ToString()
  $epoch=[Numerics.BigInteger]::Parse('621355968000000000')
  $divisor=[Numerics.BigInteger]::Parse('10000')
  $delta=[Numerics.BigInteger]::Subtract([Numerics.BigInteger]::Parse($ticks),$epoch)
  $ms=[Numerics.BigInteger]::Divide($delta,$divisor).ToString()
  $sub=[Numerics.BigInteger]::Remainder($delta,$divisor).ToString()
  $javaIso=[DateTimeOffset]::FromUnixTimeMilliseconds([long]::Parse($ms)).UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'",[Globalization.CultureInfo]::InvariantCulture)
  $vectors=@(@{ticks='638000000000009999';epochMs='1664403200000';subMs='9999'},@{ticks='638000000000010000';epochMs='1664403200001';subMs='0'})
  $script:formalContractEvidence.actualSubMs=@{creationTicks=$ticks;epochMs=$ms;subMs=$sub;javaIso=$javaIso;hasSubMs=($sub -cne '0');actualHandleHeld=$true}
  $script:formalContractEvidence.vectors=$vectors
  # Java Instantの正規な秒のみZ表記。数値比較の許容幅は増やさない。
  $zeroIso=[DateTimeOffset]::FromUnixTimeMilliseconds([long]::Parse($ms)).UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'",[Globalization.CultureInfo]::InvariantCulture)
  $zeroExpected=[Numerics.BigInteger]::Remainder([Numerics.BigInteger]::Parse($ms),[Numerics.BigInteger]::Parse('1000')).IsZero
  $script:formalContractEvidence.zeroFormat=@{javaIso=$zeroIso;expectedMatched=$zeroExpected;proof=$null;kind='format-counterexample-not-os-generation'}
  Write-Json 'formal-contract-input.json' @{owner=$owner;nonce=$Nonce;evidence=$formalContractEvidence}
  # 未実装の固定関数入口によるRED。vectorを実OS世代証明にしない。
  foreach($v in $vectors){if([string](Convert-M21FormalTicksToEpochMs $v.ticks) -cne $v.epochMs){throw 'formal ms整数vector不一致'}}
  if([string](Convert-M21FormalTicksToEpochMs $ticks) -cne $ms){throw 'formal実出生のms整数商不一致'}
  Set-Operation 'Supervisor.formal-ms.canonical'
  $proof=Confirm-M21FormalJavaGeneration $self $javaIso
  if($proof.matched -ne $true -or [string]$proof.creationTicks -cne $ticks -or [string]$proof.epochMs -cne $ms){throw 'formal実出生の採用不一致'}
  $script:formalContractEvidence.canonicalProof=$proof
  Set-Operation 'Supervisor.formal-ms.zero-format'
  $zeroProof=Confirm-M21FormalJavaGeneration $self $zeroIso
  $script:formalContractEvidence.zeroFormat.proof=$zeroProof
  if($zeroProof.matched -ne $zeroExpected -or (!$zeroExpected -and $zeroProof.reason -cne 'formal-java-ms-mismatch')){throw 'formal Java秒表記対照不一致'}
  $wrong=[DateTimeOffset]::FromUnixTimeMilliseconds([long]::Parse($ms)+1).UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'",[Globalization.CultureInfo]::InvariantCulture)
  Set-Operation 'Supervisor.formal-ms.wrong-ms'
  $wrongProof=Confirm-M21FormalJavaGeneration $self $wrong
  $script:formalContractEvidence.wrongMs=@{javaIso=$wrong;proof=$wrongProof}
  if($wrongProof.matched -ne $false){throw '異なるmsを拒否しなかった'}
 }else{
  $frontend=Join-Path $run 'frontend'
  $cli=[IO.Path]::GetFullPath((Join-Path $frontend 'node_modules/nuxt/bin/nuxt.mjs'))
  $environment=@{TEMP=$run;TMP=$run;M21_HOST_ONLY_SENTINEL='public-test-sentinel';SPRING_DATASOURCE_PASSWORD='public-test-sentinel';MANNSCHAFT_JWT_SECRET='public-test-sentinel'}
  foreach($key in @('SystemRoot','WINDIR')){$value=[Environment]::GetEnvironmentVariable($key);if($null -ne $value){$environment[$key]=$value}}
  Write-Json 'formal-contract-input.json' @{owner=$owner;nonce=$Nonce;fixture=$TestFixture;frontend=$frontend;cli=$cli;sourceSentinelKeys=@('M21_HOST_ONLY_SENTINEL','SPRING_DATASOURCE_PASSWORD','MANNSCHAFT_JWT_SECRET')}
  if($TestFixture -eq 'formal-fe'){
   $spec=New-M21FormalFrontendLaunchSpec $frontend $NodePath $environment
   if($spec.FilePath -cne $NodePath -or ![IO.Path]::IsPathRooted([string]$spec.ArgumentList[0]) -or [string]$spec.ArgumentList[0] -cne $cli -or $spec.WorkingDirectory -cne $frontend){throw 'formal FE絶対CLI/CWD不一致'}
   $spec.LogPrefix=Join-Path $run 'formal-fe' # 固定fixtureのprivate stdioだけ。
   $child=Start-M21FormalAdmittedChild 'frontend' $spec
   if($null -eq $child){throw 'formal FE work admission不成立'}
   Register-Child $child 'formal-fe-dummy'
   if(!$child.Process.WaitForExit((Remaining 5000 -Recovery))){throw 'formal FE dummy期限'}
   Capture $child 'formal-fe-dummy' 1000
   $output=Read-Json 'formal-fe.out'
   if($child.Process.ExitCode -ne 0 -or $output.owner -cne $owner -or $output.nonce -cne $Nonce -or $output.pid -ne $child.RecordedPid -or $output.cwd -cne $frontend -or $output.cli -cne $cli -or $output.forbiddenPresent -ne $false -or $output.nodeOptions -cne '--max-old-space-size=8192'){throw 'formal FE実dummy契約不一致'}
   $script:formalContractEvidence.child=@{pid=$child.RecordedPid;creation=$child.RecordedCreation;exited=$true;exitCode=$child.Process.ExitCode;stdoutEof=$streams[$streams.Count-1].stdoutEof;stderrEof=$streams[$streams.Count-1].stderrEof}
   $script:formalContractEvidence.observations=$output
  }else{
   # 同じ実admission入口を使い、時計関数のmockだけで完了扱いしない。
   $workSpec=@{FilePath=$NodePath;ArgumentList=@($cli,'dev','--host','127.0.0.1','--port','3003');WorkingDirectory=$frontend;Environment=$environment;LogPrefix=(Join-Path $run 'formal-work')}
   $script:WorkRemainingMs=[int][Math]::Min($script:WorkRemainingMs,$localClock.ElapsedMilliseconds)
   $denied=Start-M21FormalAdmittedChild 'frontend' $workSpec
   if($null -ne $denied){Register-Child $denied 'unexpected-formal-work';throw 'W後work spawnを拒否しなかった'}
   if(Test-Path -LiteralPath (Join-Path $run 'formal-fe-entered.json')){throw 'W後work marker生成'}
   $safe=@{};foreach($key in @('SystemRoot','WINDIR','TEMP','TMP')){if($environment.ContainsKey($key)){$safe[$key]=$environment[$key]}}
   $cleanupSpec=@{FilePath=$NodePath;ArgumentList=@((Join-Path $PSScriptRoot 'm21-windows-supervisor.probe.cjs'),'--dummy','hold-stream',$run,$Nonce,'normal','2000',[string](Remaining -Recovery));WorkingDirectory=$run;Environment=$safe;LogPrefix=(Join-Path $run 'formal-cleanup');OwnedResource=@{owner=$owner;nonce=$Nonce;kind='fixture-marker'}}
   $child=Start-M21FormalAdmittedChild 'owned-cleanup' $cleanupSpec
   if($null -eq $child){throw 'W後のowned cleanup admission不成立'}
   Register-Child $child 'formal-cleanup-dummy'
   if(!$child.Process.WaitForExit((Remaining 5000 -Recovery))){throw 'formal cleanup dummy期限'}
   Capture $child 'formal-cleanup-dummy' 1000
   $marker=Read-Json 'hold-stream.json'
   if($child.Process.ExitCode -ne 0 -or $marker.pid -ne $child.RecordedPid -or $marker.nonce -cne $Nonce){throw 'formal cleanup実marker不一致'}
   $script:TotalRemainingMs=[int][Math]::Min($script:TotalRemainingMs,$localClock.ElapsedMilliseconds)
   $cleanupSpec.ArgumentList[2]='grandchild'
   $denied=Start-M21FormalAdmittedChild 'owned-cleanup' $cleanupSpec
   if($null -ne $denied){Register-Child $denied 'unexpected-formal-cleanup';throw 'G後cleanup spawnを拒否しなかった'}
   if(Test-Path -LiteralPath (Join-Path $run 'grandchild.json')){throw 'G後cleanup marker生成'}
   $script:formalContractEvidence.child=@{pid=$child.RecordedPid;creation=$child.RecordedCreation;exited=$true;exitCode=$child.Process.ExitCode;stdoutEof=$streams[$streams.Count-1].stdoutEof;stderrEof=$streams[$streams.Count-1].stderrEof}
   $script:formalContractEvidence.observations=@{workMarkerAbsent=$true;cleanupMarkerPresent=$true;afterGMarkerAbsent=$true;workRemainingMs=(Remaining);totalRemainingMs=(Remaining -Recovery)}
  }
 }
 $script:formalContractEvidence.passed=$true
}
try {
 Guard-Run
 if(![IO.Path]::IsPathRooted($NodePath) -or [IO.Path]::GetFileName($NodePath) -ine 'node.exe' -or !(Test-Path -LiteralPath $NodePath -PathType Leaf)){throw 'Node exe不一致'}
 $dummy=Join-Path $PSScriptRoot 'm21-windows-supervisor.probe.cjs'
 if($Mode -eq 'ReadinessFixture'){
  # テスト本体は固定pathだけ。通常reader/Observeを置換しない。
  . (Join-Path $PSScriptRoot 'm21-windows-supervisor.readiness-fixture.ps1')
 }elseif($Mode -eq 'Parent' -and $TestFixture -ceq 'wsl-relay-loss'){
  Invoke-M21WslRelayLossFixture
 }elseif($Mode -eq 'Supervisor' -and $chromiumFixture){
  if(![M21Job]::InJob($self,$null)){throw 'Chromium ACK後Job不在'}
  Invoke-ChromiumFixture
 }elseif($Mode -eq 'Supervisor' -and $TestFixture -in @('formal-ms','formal-fe','formal-admission')){
  if(![M21Job]::InJob($self,$null)){throw 'formal fixture ACK後Job不在'}
  Invoke-FormalContractFixture
 }elseif($Mode -eq 'Supervisor' -and $Scenario -ceq 'formal-m21'){
  if(![M21Job]::InJob($self,$null)){throw 'formal ACK後Job不在'}
  Invoke-M21FormalSupervisor
 }elseif($Mode -eq 'Supervisor'){
  # 親のexact Job認定ack後に初期化した型で補助観測する。
  if(![M21Job]::InJob($self,$null)){throw 'ack後Job不在'}
  Guard-Run
  Require-Work
  $child=[M21Child]::Start($NodePath,@($dummy,'--dummy','child',$run,$Nonce,$Scenario,[string](Remaining),[string](Remaining -Recovery),'--owner-id',$owner),$run,(Join-Path $run 'dummy'),(Remaining -Recovery),($Scenario -eq 'stdio-eof'))
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
  if($chromiumWitnessCase){
   Guard-Run;Require-Work
   $chromiumWitness=[M21Job]::Create()
   if([M21Job]::InJob([Diagnostics.Process]::GetCurrentProcess(),$chromiumWitness) -or $chromiumWitness.LimitFlags() -ne 8192){throw 'witness設定/Observer所属不一致'}
  }
  $parent=New-Supervisor 'Parent' 'parent';Register-Child $parent 'parent'
  $known=New-Object 'Collections.Generic.List[object]'
  try{
   if($chromiumWitnessCase){
    $witnessHello=Wait-File 'chromium-witness-hello.json';Assert-P6Identity $witnessHello
    $parentIdentity=Identity $parent.Process
    if($witnessHello.pid -ne $parentIdentity.pid -or $witnessHello.creation -cne $parentIdentity.creation -or $parent.Process.WaitForExit(0)){throw 'witness Parent出生不一致'}
    Require-Work
    $chromiumWitness.Assign($parent.Process)
    Write-Json 'chromium-witness-ack.json' @{owner=$owner;nonce=$Nonce;pid=$parentIdentity.pid;creation=$parentIdentity.creation;ownedJobVerified=$true}
   }
   if($TestFixture -eq 'parent-before-certificate'){
    # Fixture自身の独立実exit証拠だけ。通常Capture/streams/Capturedへは触れない。
    if(!$parent.Process.WaitForExit((Remaining))){throw 'fixture Parent実終了未確認'}
    Write-Json 'fixture-parent-exit.json' @{owner=$owner;nonce=$Nonce;pid=$parent.RecordedPid;creation=$parent.RecordedCreation;exited=$true;exitCode=$parent.Process.ExitCode}
   }
   # 自分が直接保持した親世代が、自身Jobで認定した集合だけを採用する。
   $certificate=Wait-File 'certified-tree.json' -ParentCertificate
   Assert-ObserverParentAlive
   $parentIdentity=Identity $parent.Process
   $expectedCount=3;if($chromiumFixture){$expectedCount=4}
   if($certificate.owner -cne $owner -or $certificate.nonce -cne $Nonce -or $certificate.certifier.owner -cne $owner -or $certificate.certifier.nonce -cne $Nonce -or $certificate.certifier.pid -ne $parentIdentity.pid -or [string]$certificate.certifier.creation -cne $parentIdentity.creation -or $certificate.members.Count -ne $expectedCount){throw '親認定metadata不一致'}
   $roles=@($certificate.members | ForEach-Object {$_.role})
   $expectedRoles='supervisor,child,grandchild';if($chromiumFixture){$expectedRoles='supervisor,system,admin-de,member'}
   if(($roles -join ',') -cne $expectedRoles){throw '親認定集合不一致'}
   foreach($member in $certificate.members){if($member.ownedJobVerified -ne $true){throw '親所有Job未認定'};$known.Add((Open-Known $member.identity $null))}
   foreach($p in $known){if($p.HasExited){throw 'RequiredAlive早期終了'}}
   if($chromiumWitnessCase){
    $inner=$certificate.chromiumInner
    if($inner.flags -ne 8192 -or $inner.parentOutsideInner -isnot [bool] -or !$inner.parentOutsideInner -or $inner.rootsInsideInner -isnot [bool] -or !$inner.rootsInsideInner){throw 'inner Job実照会不一致'}
    $containment=New-Object 'Collections.Generic.List[object]'
    $parentInside=[M21Job]::InJob($parent.Process,$chromiumWitness)
    if(!$parentInside -or [M21Job]::InJob([Diagnostics.Process]::GetCurrentProcess(),$chromiumWitness)){throw 'witness Parent/Observer所属不一致'}
    $containment.Add(@{role='parent';identity=$parentIdentity;insideWitness=$parentInside})
    for($i=0;$i -lt $known.Count;$i++){
     $same=Identity $known[$i];$expected=$certificate.members[$i].identity
     if($same.pid -ne $expected.pid -or $same.creation -cne $expected.creation -or ![M21Job]::InJob($known[$i],$chromiumWitness)){throw 'witness保持root出生/所属不一致'}
     $containment.Add(@{role=$certificate.members[$i].role;identity=$same;insideWitness=$true})
    }
    $chromiumWitnessProof=@{owner=$owner;nonce=$Nonce;parent=$parentIdentity;containment=@($containment.ToArray());innerFlags=$inner.flags;parentOutsideInner=$inner.parentOutsideInner;rootsInsideInner=$inner.rootsInsideInner;observerOutsideWitness=$true;parentInsideWitness=$true;rootsInsideWitness=$true;activeBefore=$null;parentExited=$false;parentExitCode=$null;rootsExited=$false;rootActualExits=@();parentStdoutEof=$false;parentStderrEof=$false;activeAfter=$null;outerStopUsed=$false;outerClosedBeforeProof=$false;priorFailure=$false;innerActiveZeroObserved=$false;browserEofObserved=$false;fullRendererActualObserved=$false;witnessContainmentRemainingZero=$false;innerHandleTransferEvidence='unnamed-CreateJob-null-security-no-duplicate-or-inherit-source';passed=$false}
   }
   if($chromiumFixture){
    $observed=Wait-File 'chromium-observed.json'
    if(@($observed.PSObject.Properties.Name).Count -ne 3 -or $observed.owner -cne $owner -or $observed.nonce -cne $Nonce -or $observed.observed -isnot [bool] -or !$observed.observed){throw 'root Sky観測marker不一致'}
    foreach($p in $known){if($p.WaitForExit(0)){throw 'Sky観測後RequiredAlive早期終了'}}
   }
   Assert-ObserverParentAlive
   if($chromiumWitnessCase){
    Require-Work
    $chromiumWitnessProof.activeBefore=$chromiumWitness.Active()
    if($chromiumWitnessProof.activeBefore -le 0){throw 'kill前witness残数不正'}
   }
   $parent.Process.Kill()
   $exitEvidence=New-Object 'Collections.Generic.List[object]'
   for($i=0;$i -lt $known.Count;$i++){
    $p=$known[$i]
    if(!$p.WaitForExit((Remaining 5000 -Recovery))){throw 'kill-on-close子残留'}
    $exitEvidence.Add(@{role=$certificate.members[$i].role;identity=$certificate.members[$i].identity;exited=$true;exitCode=$p.ExitCode;evidence='observer-held-handle-exit'})
   }
   if($chromiumWitnessCase){
    # 一次Captureの実exit/採用EOFを先に確定する。後続outer cleanupで成功へ合成しない。
    Capture $parent 'direct' 1500
    $captured=@($streams|Where-Object {$_.pid -eq $parent.RecordedPid -and $_.creation -ceq $parent.RecordedCreation})
    if($captured.Count -ne 1){throw 'witness Parent Capture集合不一致'}
    $chromiumWitnessProof.parentExited=$captured[0].exited;$chromiumWitnessProof.parentExitCode=$captured[0].exitCode
    $chromiumWitnessProof.parentStdoutEof=$captured[0].stdoutEof;$chromiumWitnessProof.parentStderrEof=$captured[0].stderrEof
    $chromiumWitnessProof.rootActualExits=@($exitEvidence.ToArray());$chromiumWitnessProof.rootsExited=($exitEvidence.Count -eq 4 -and @($exitEvidence|Where-Object {!$_.exited}).Count -eq 0)
    $clock=[Diagnostics.Stopwatch]::StartNew();$zeroCap=Remaining 3000 -Recovery
    do{
     $chromiumWitnessProof.zeroQueryRemainingBeforeMs=Remaining -Recovery
     if($chromiumWitnessProof.zeroQueryRemainingBeforeMs -le 0){Set-Reason 'chromium-witness-total-budget-exhausted';throw ([TimeoutException]::new())}
     $chromiumWitnessProof.activeAfter=$chromiumWitness.Active()
     $chromiumWitnessProof.zeroQueryRemainingAfterMs=Remaining -Recovery
     if($chromiumWitnessProof.zeroQueryRemainingAfterMs -le 0){Set-Reason 'chromium-witness-total-budget-exhausted';throw ([TimeoutException]::new())}
     if($chromiumWitnessProof.activeAfter -eq 0){break}
     if($clock.ElapsedMilliseconds -lt $zeroCap -and (Remaining -Recovery) -gt 0){Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining -Recovery)))}
    }while($clock.ElapsedMilliseconds -lt $zeroCap -and (Remaining -Recovery) -gt 0)
    $chromiumWitnessProof.priorFailure=($null -ne $primary -or !$recovered)
    $chromiumWitnessProof.remainingRecoveryMs=Remaining -Recovery
    if(!(Test-ChromiumWitnessQualification $chromiumWitnessProof)){Set-Reason 'chromium-witness-not-qualified';throw 'witness限定資格不成立'}
    $chromiumWitnessProof.witnessContainmentRemainingZero=$true;$chromiumWitnessProof.passed=$true
    if((Remaining -Recovery) -le 0){Set-Reason 'chromium-witness-total-budget-exhausted';throw ([TimeoutException]::new())}
    Write-Json 'chromium-witness.json' $chromiumWitnessProof
    $chromiumWitnessRecovery.remainingAfterPublicationMs=Remaining -Recovery
    if($chromiumWitnessRecovery.remainingAfterPublicationMs -le 0){Set-Reason 'chromium-witness-publication-crossed-deadline';throw ([TimeoutException]::new())}
    $chromiumWitnessQualified=$true
   }
   Write-Json 'observer.json' @{owner=$owner;nonce=$Nonce;parentKilled=$true;knownExited=$known.Count;exits=@($exitEvidence.ToArray())}
  }finally{foreach($p in $known){Attempt 'observer-handle-close' {$p.Dispose()}}}
 }else{
  if($TestFixture -eq 'parent-before-certificate'){Set-Reason 'fixture-parent-exit';throw '固定fixture: cert前Parent終了'}
  if($Scenario -ceq 'formal-m21'){Initialize-M21FormalParent}
  Set-Operation 'Parent.createJob'
  $job=[M21Job]::Create()
  $jobCreated=$true
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
   Set-Operation 'Parent.certify-tree'
   $members=New-Object 'Collections.Generic.List[object]'
   if($chromiumFixture){$members=Certify-Chromium}else{
   foreach($entry in @(@{role='supervisor';identity=$identity},@{role='child';identity=(Wait-File 'child-candidate.json')},@{role='grandchild';identity=(Wait-File 'grandchild-candidate.json')})){
    $verified=Open-Known $entry.identity $job
    $certified.Add($verified)
    if($verified.HasExited){throw '認定前RequiredAlive終了'}
    $members.Add(@{role=$entry.role;identity=$entry.identity;ownedJobVerified=$true})
   }
   }
   $certificateValue=@{owner=$owner;nonce=$Nonce;certifier=(Identity ([Diagnostics.Process]::GetCurrentProcess()));members=@($members.ToArray());metadataReadiness=@($metadataReadiness.ToArray())}
   if($chromiumWitnessCase){
    $rootsInside=[M21Job]::InJob($supervisor.Process,$job)
    foreach($p in $certified){if(![M21Job]::InJob($p,$job)){$rootsInside=$false}}
    $parentOutside=![M21Job]::InJob([Diagnostics.Process]::GetCurrentProcess(),$job);$flags=$job.LimitFlags()
    if(!$rootsInside -or !$parentOutside -or $flags -ne 8192){throw 'Chromium inner所属/flags不一致'}
    $certificateValue.chromiumInner=@{flags=$flags;parentOutsideInner=$parentOutside;rootsInsideInner=$rootsInside}
   }
   Write-Json 'certified-tree.json' $certificateValue
   Start-Sleep -Milliseconds (Remaining);Set-Reason 'work-budget-exhausted';throw 'observer待機期限超過'
  }
  if($chromiumFixture -and $Scenario -eq 'normal'){$null=Certify-Chromium}
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
  if($Scenario -ceq 'formal-m21'){Wait-M21FormalSupervisor $supervisor}
  elseif(!$supervisor.Process.WaitForExit((Remaining))){Set-Reason 'work-budget-exhausted';throw 'supervisor exit期限超過'}
  if(Test-Path -LiteralPath (Join-Path $run 'supervisor-result.json')){
   $inner=Read-Json 'supervisor-result.json'
   if($null -ne $inner.primary){$primary=$inner.primary}
   foreach($failure in $inner.secondary){$secondary.Add($failure)}
   foreach($item in $inner.streams){$streams.Add($item)}
   foreach($item in $inner.lastHandles){$lastHandles.Add($item)}
   foreach($item in $inner.startupRecovery){$startupRecovery.Add($item)}
   foreach($item in $inner.p6Observations){$p6Observations.Add($item)}
   foreach($item in $inner.metadataReadiness){$metadataReadiness.Add($item)}
   if($TestFixture -in @('formal-ms','formal-fe','formal-admission')){$formalContractEvidence=$inner.formalContractEvidence}
   if($Scenario -ceq 'formal-m21'){$formalEntryEvidence=$inner.formalEntryEvidence}
   $grandchildEvidence=$inner.grandchildEvidence
   if($Scenario -in @('host-fail','cleanup-fail','formal-m21') -and !$inner.recovered){$recovered=$false}
  }
  if($supervisor.Process.ExitCode -ne 0 -and $null -eq $primary){Fail 'dummy-nonzero' ([InvalidOperationException]::new())}
  if($Scenario -in @('normal','formal-m21')){
   $inner=Read-Json 'supervisor-result.json'
   if(!$inner.passed -or !$inner.recovered){throw 'inner失敗'}
  }
 }
}catch{
 Fail 'execution' $_.Exception $_
}
finally {
 if($null -ne $chromiumWitness){
  # 成功はStop/close前の証拠だけ。故障時のouter回収は成功判定へ戻さない。
  if(!$chromiumWitnessQualified -or $null -ne $primary){
   $script:recovered=$false
   if($null -eq $primary){Set-Reason 'chromium-witness-unqualified';Fail 'chromium-witness' ([InvalidOperationException]::new())}
   Attempt 'chromium-witness-fallback-stop' {try{$chromiumWitnessRecovery.fallbackStopUsed=$true;$chromiumWitness.Stop()}catch{$script:recovered=$false;throw}}
   Attempt 'chromium-witness-fallback-empty' {
    try{
     $clock=[Diagnostics.Stopwatch]::StartNew();$cleanupLimit=Remaining 3000 -Recovery
     do{$chromiumWitnessRecovery.activeAfterFallback=$chromiumWitness.Active();if($chromiumWitnessRecovery.activeAfterFallback -eq 0){break};if($clock.ElapsedMilliseconds -lt $cleanupLimit -and (Remaining -Recovery) -gt 0){Start-Sleep -Milliseconds ([Math]::Min(25,(Remaining -Recovery)))}}while($clock.ElapsedMilliseconds -lt $cleanupLimit -and (Remaining -Recovery) -gt 0)
     if($chromiumWitnessRecovery.activeAfterFallback -ne 0){throw 'witness fallback残留'}
    }catch{$script:recovered=$false;throw}
   }
  }
  Attempt 'chromium-witness-close' {try{$chromiumWitness.Dispose();$chromiumWitnessRecovery.closed=$true}catch{$script:recovered=$false;throw}}
 }
 if($Mode -ne 'Supervisor'){
  Attempt 'job-stop' {if($null -ne $job){$job.Stop();if($null -ne $p6Certification){$p6Certification.jobStopped=$true}}}
  foreach($child in $held){
   if($chromiumWitnessCase -and $Mode -ceq 'Observe' -and $child.Captured){continue}
   if($Scenario -ceq 'formal-m21' -and $null -ne $m21Bridge -and [object]::ReferenceEquals($child,$m21Bridge)){continue}
   Attempt 'direct-stop' {Parent-Event 'kill-before' $child;if($child.Started -and !$child.Process.HasExited){Parent-Event 'kill-issued' $child;$child.Process.Kill();Parent-Event 'kill-returned' $child}else{Parent-Event 'kill-skipped' $child}}
   Attempt 'exit-and-streams' {Capture $child 'direct' 1500}
  }
  Attempt 'job-empty' {
   if($null -ne $job){
    $clock=[Diagnostics.Stopwatch]::StartNew()
    $jobCap=3000;if($Scenario -ceq 'formal-m21'){$jobCap=10000}
    $cleanupLimit=Remaining $jobCap -Recovery
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
  if($Scenario -ceq 'formal-m21' -and $null -ne (Get-Variable m21FormalCertified -Scope Script -ErrorAction SilentlyContinue)){
   foreach($role in $script:m21FormalCertified.Keys){
    Attempt ('formal-held-exit-'+$role) {
     $entry=$script:m21FormalCertified[$role]
     $exited=$entry.process.WaitForExit((Remaining 2000 -Recovery))
     $code=$null;if($exited){$code=$entry.process.ExitCode}
     $script:knownExitEvidence+=@{role=$role;identity=$entry.identity;exited=$exited;exitCode=$code;evidence='parent-exact-held-handle'}
     if(!$exited){$script:recovered=$false;throw '正式保持世代の実exit不明'}
    }
   }
  }
  Attempt 'job-close' {if($null -ne $job){$job.Dispose();$job=$null}}
  # DockerはJob外。自己ledger/同daemonを独立に回収する。
  Attempt 'external-cleanup' {Guard-Run;if($Scenario -ceq 'formal-m21'){Stop-M21FormalExternalResources};if($Scenario -eq 'cleanup-fail'){Set-Reason 'cleanup-fault';throw 'cleanup故障注入'}}
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
 if($null -ne $m21FormalAuxiliary){foreach($p in $m21FormalAuxiliary){Attempt 'formal-auxiliary-handle-close' {$p.Dispose()}}}
 Attempt 'p6-grand-handle-close' {if($null -ne $script:p6Grand){$script:p6Grand.Dispose();$script:p6Grand=$null}}
 foreach($child in $held){
  Attempt 'last-handle' {
   $last=@{pid=$child.RecordedPid;creation=$child.RecordedCreation;exited=$false;exitCode=$null;stdoutEof=$false;stderrEof=$false;stdoutPublished=$child.StdoutPublished;stderrPublished=$child.StderrPublished}
   try{
    $last.exited=(!$child.Started -or $child.Process.WaitForExit(0))
    if($child.Started -and $last.exited){$last.exitCode=$child.Process.ExitCode}
    $last.stdoutEof=(!$child.Started -or ($null -ne $child.OutTask -and $child.OutTask.Status -eq [Threading.Tasks.TaskStatus]::RanToCompletion))
    $last.stderrEof=(!$child.Started -or ($null -ne $child.ErrTask -and $child.ErrTask.Status -eq [Threading.Tasks.TaskStatus]::RanToCompletion))
    $pending=@($script:pendingCaptureExits|Where-Object {[object]::ReferenceEquals($_.child,$child)})
    if($pending.Count -eq 1){
     if($child.Started -and $last.exited -and $last.stdoutEof -and $last.stderrEof -and (Remaining -Recovery) -gt 0){
      Guard-Run
      Attempt 'late-exit-stdout-publication' {try{if(!$child.StdoutPublished){if($child.StdoutPublicationAttempted){throw 'stdout publication既試行'};if((Remaining -Recovery) -le 0){throw ([TimeoutException]::new())};$child.PublishCapturedOutput($true);if((Remaining -Recovery) -le 0){throw ([TimeoutException]::new())}}}catch{$script:recovered=$false;Set-Reason 'stdout-publication-failed';throw}}
      Attempt 'late-exit-stderr-publication' {try{if(!$child.StderrPublished){if($child.StderrPublicationAttempted){throw 'stderr publication既試行'};if((Remaining -Recovery) -le 0){throw ([TimeoutException]::new())};$child.PublishCapturedOutput($false);if((Remaining -Recovery) -le 0){throw ([TimeoutException]::new())}}}catch{$script:recovered=$false;Set-Reason 'stderr-publication-failed';throw}}
     }
     $last.stdoutPublished=$child.StdoutPublished;$last.stderrPublished=$child.StderrPublished
     $proof=@{initialExitObserved=$pending[0].initialExitObserved;initialPublicationAttempted=$pending[0].initialPublicationAttempted;exited=$last.exited;stdoutEof=$last.stdoutEof;stderrEof=$last.stderrEof;stdoutWriterClosed=$child.StdoutPublished;stderrWriterClosed=$child.StderrPublished;stdoutPublished=$child.StdoutPublished;stderrPublished=$child.StderrPublished}
     $allowed=$script:recovered;$jobSatisfied=($jobCreated -and $jobEmpty -eq $true);$remainingMs=Remaining -Recovery
     $adopted=Test-M21PendingExitRecovery $pending[0].child $child $proof $allowed $jobSatisfied $remainingMs
     $script:lateExitProofs.Add(@{owner=$owner;nonce=$Nonce;pid=$child.RecordedPid;creation=$child.RecordedCreation;role=$pending[0].role;sameHeldChild=[object]::ReferenceEquals($pending[0].child,$child);initialExitCapMs=$pending[0].initialExitCapMs;final=$proof;finalExitCode=$last.exitCode;recoveryAllowed=$allowed;jobSatisfied=$jobSatisfied;remainingMs=$remainingMs;adopted=$adopted;writerCloseEvidence='M21Child-publication-flags'})
     if($adopted){$script:pendingCaptureExits.Remove($pending[0])>$null}else{$script:recovered=$false}
    }
    if(!$last.exited -or !$last.stdoutEof -or !$last.stderrEof){$script:recovered=$false}
   }catch{$script:recovered=$false;throw}finally{$script:lastHandles.Add($last)}
  }
  Attempt 'dispose' {$child.Dispose()}
 }
 if($pendingCaptureExits.Count -gt 0){$script:recovered=$false}
 $observer=$null
 Attempt 'parent-receipt' {
  if($Mode -eq 'Observe' -and $null -ne $parentExitEvidence -and (Test-Path -LiteralPath (Join-Path $run 'result.json'))){
   Set-Operation 'Observe.parent-receipt'
   $candidate=Read-Json 'result.json'
   $identity=$parentExitEvidence.identity
   if($candidate.owner -cne $owner -or $candidate.nonce -cne $Nonce -or $candidate.mode -cne 'Parent' -or $candidate.process.owner -cne $owner -or $candidate.process.nonce -cne $Nonce -or $candidate.process.pid -ne $identity.pid -or [string]$candidate.process.creation -cne $identity.creation){throw 'Parent receipt出生/owner不一致'}
   $script:parentExitEvidence.receiptPresent=$true;$script:parentExitEvidence.receipt=$candidate
  }
 }
 Attempt 'observer-metadata' {
  if($Mode -eq 'Observe' -and (Test-Path -LiteralPath (Join-Path $run 'observer.json'))){
   $candidate=Read-Json 'observer.json'
   $expectedCount=3;if($chromiumFixture){$expectedCount=4}
   if($candidate.owner -cne $owner -or $candidate.nonce -cne $Nonce -or $candidate.parentKilled -ne $true -or $candidate.knownExited -ne $expectedCount){throw 'observer metadata不一致'}
   if($candidate.exits.Count -ne $expectedCount -or @($candidate.exits | Where-Object {$_.exited -ne $true}).Count -ne 0){throw 'observer actual exit集合不一致'}
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
 $result=[ordered]@{owner=$owner;nonce=$Nonce;process=$processIdentity;mode=$Mode;scenario=$Scenario;passed=($null -eq $primary);primary=$primary;secondary=@($secondary.ToArray());grandchildEvidence=$grandchildEvidence;p6Observations=@($p6Observations.ToArray());p6Certification=$p6Certification;fixtureEvidence=$fixtureEvidence;metadataReadiness=@($metadataReadiness.ToArray());parentExitEvidence=$parentExitEvidence;formalContractEvidence=$formalContractEvidence;formalEntryEvidence=$formalEntryEvidence;lateObservation=$lateObservation;parentEvents=@($parentEvents.ToArray());attempts=@($attempts.ToArray());streams=@($streams.ToArray());lastHandles=@($lastHandles.ToArray());knownExitEvidence=$knownExitEvidence;startupRecovery=@($startupRecovery.ToArray());resourceRecovery=@{jobEmpty=$jobEmpty;confirmed=$recovered;retained=@($retained.ToArray());lateExitProofs=@($lateExitProofs.ToArray())};recovered=$recovered;parentKilled=($null -ne $observer);knownExited=0}
 if($chromiumWitnessCase -and $Mode -ceq 'Observe'){$result.chromiumWitness=@{qualified=($chromiumWitnessQualified -and $null -eq $primary -and $recovered);proof=$chromiumWitnessProof;recovery=$chromiumWitnessRecovery}}
 if($null -ne $observer){$result.knownExited=$observer.knownExited}
 try{
  $name='result.json';if($Mode -eq 'Supervisor'){$name='supervisor-result.json'}elseif($Mode -eq 'Observe'){$name='observe-result.json'}
  Write-Json $name $result
 }catch{
  Fail 'result-save-final' $_.Exception $_
  [Console]::Error.WriteLine('M21_RESULT_SAVE_FAILED')
 }
}
if($null -ne $primary){exit 1}
exit 0
