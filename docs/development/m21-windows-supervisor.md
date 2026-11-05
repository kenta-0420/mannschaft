# M21 Windows Job監督 prototype

この入口はM21の固定dummy用で、正式FE/Java/Docker/browserを起動しない。実サービスへの結線、production CP生成、UI操作APIは未実装。Java host/helperのqualified結果やこのprototypeの結果を、Chromium互換・正式実機・探索の合格に流用しない。

## 所有と失敗時の境界

親は非継承・無名のJob handleを単独保持し、KILL_ON_JOB_CLOSEだけを設定する。直接起動した待機supervisorをJobへ割り当て、保持handleでIsProcessInJobを確認した後にだけ、owner/nonce/pid/creationを含むackを発行する。supervisorはack前にdummyを起動しない。Job handleは子へ継承・複製せず、breakawayも許可しない。

Supervisorのack前は標準Process/JSONでhelloを保存しackを有限待機するだけで、Add-Typeも実行しない。親がexact Job所属を確認した後だけownedJobVerified付きackを発行し、Supervisorは同run/世代のackを照合してから型をcompileする。custom型を参照する関数定義と呼出しもこのgateの後に配置する。Parent/Observeの型初期化とは位置を分ける。

根拠は[.NET Framework reference source CodeCompiler.cs](https://raw.githubusercontent.com/microsoft/referencesource/main/System/compmod/system/codedom/compiler/CodeCompiler.cs)のCompileがcompilerExeをExecutor.ExecWaitWithCaptureへ渡す点。PS5.1の全呼出しchainや実compileを測定した証拠ではなく、Add-Typeが子0で完結するという仮定を避けるための配置である。型名解決の実動作もruntime合格とはせず、静的ASTでgate前のcustom型参照0と、型を使う関数定義がAdd-Type後にあることを確認する。

直接Processの保持handleと開始時刻を使う。P4ではsupervisorが子/孫のcandidate metadataを出し、Job外親が自身のJob handleで各既知世代のIsProcessInJobを確認してからcertified-tree.jsonを発行する。observerは直接保持した親のpid/creation・owner/nonceと一致する認定済み3件だけを採用し、各世代のhandleを保持して実exitを観測する。「何らかのJobに所属」だけでは親所有Jobの証拠にしない。Job handleをobserver/子へ渡さない。未知PID停止APIやCIM列挙は提供しない。取得不能・Job照合失敗を終了済みに読み替えない。

stdout/stderrは別のprivate fileへCopyToAsyncし、実exit/exit codeと各EOFを有限待機する。起動後例外でもStartはprimaryを固定し、直接保持Processのkill・実exit最大1秒・開始済みstdout/stderr各最大0.5秒を別々に試す。保持objectをDisposeせず呼出元のheldへ返し、finallyで再確認する。startupRecoveryにはpid/creation/logs・各事実・retainedAtStartupを残す。最終回収不明はresourceRecovery.retainedへ残してnonGreenとする。

Supervisor bodyがCapture前にthrowした通常childも、未観測のままconfirmed=trueにしない。Capture到達flagがなければconfirmed=false/retainedとし、親Jobの回収へ委ねる。正常完了caseの二重Captureは行わない。

親はJob停止後のActiveProcesses=0も確認する。streamsの一次EOF失敗・primaryは消さず、後続resourceRecovery.jobEmpty/confirmedとは別の事実として記録する。たとえばstdio-eofでは一次EOF失敗でrunがnonGreenのまま、後続Job回収だけをconfirmed=trueと記録できる。primary failureを保持し、停止・各stream・Job close・後続cleanup・observer metadata読取・結果保存を独立に試す。破損observer metadataもAttempt内で最初ならprimary、既存failureがあればsecondaryへ追記し、結果保存を飛ばさない。外部cleanupはJob外の親に位置するが、prototypeでは専用dummyしか扱わず、Docker清掃は未結線。

failure診断はexception type・InnerException type・HResultと、Win32ExceptionのNativeErrorCodeだけを最大8段記録する。任意例外本文/stack/資格情報は追加しない。Add-Typeの実compile/policy拒否はnative-bootstrapとして独立に捕捉し、子起動へ進まず同じ安全な形式で結果保存を試す。保存不能時も安全な型/数値だけをstderrへ出して非0とする。

再現用診断はParentのcreateJob/startSupervisor/readHello/identity/assign/publishOwned/publishAck/waitと、JSON read/write/move・固定metadata filenameをメモリlabelに残す。失敗時だけ自隊scriptの関数/行、例外ごとに最大6件の型名.method名を記録する。引数・任意本文・stackの生文字列は出さない。Parent pid/creationは既存result、actual exitCodeは既存suite packetへ追加し、成功パスの新ファイルIO・待機上限・機能処理は変えない。

## 有限probe（実行には別GOが必要）

Windows標準PowerShellと既存Nodeだけを使う。新依存/SDK/ダウンロードなし。通常のPowerShell policyを適用し、ExecutionPolicy Bypass/Unblock-File/ACL変更を行わない。policyや強い既存Jobで拒否されたら、非0・保持理由を報告する。sandbox無効化やbreakawayで逃がさない。

自隊worktreeのルートで、rootから有限probe実行GOを得た後だけ実行する:

    node scripts/m21-windows-supervisor.probe.cjs

10 case逐次。launcherの実exitを最大30秒、stdout EOF・stdout書込finish/close・stderr EOF・stderr書込finish/closeをそれぞれ最大2秒で別々に待つ。実exitとChildProcess closeを同一視しない。失敗後も直接own childのexitを最大5秒、各streamのclose/書込回収をそれぞれ最大2秒待ち、失敗はstream別の保持情報へ残す。無期限finished待機はない。

pollは25msなので、待機式の保守的上限は **10 × (30000 + 25 + 4 × (2000 + 25)) + (5000 + 25) + 2 × (2000 + 25) = 390325ms**。最初の失敗で次caseへ進まず、末尾の回収項は最大1回。C#起動catchの有限回収もlauncher30秒枠内で観測する。外側観測の予算案は420秒。これは制御された待機の予算であり、同期filesystemやOS fatalの完了を保証するものではない。timeout/未回収はnonGreenと正確なretained対象で返し、上限超過を成功に読み替えない。

成功時だけ新規自己Tempを所有marker照合後撤去し、その結果も確認してからpassedを出す。failure/回収不明時は正確なretained pathと直接launcher pid・exit/close・stream別状態をJSONに残し、成功・清掃済みとしない。launcher.out/err、dummy.out/err等は固定dummyだけ。資格情報を入力しない。

| AC | case・境界 |
|---|---|
| P1 | normal: assign/ack後spawn、実exit0、両EOF、結果保存 |
| P2 | pre-ack / wrong-ack: ackなし/異nonceを拒否、子作業0 |
| P3 | assign-fail: 割当直前の故障注入から回収継続（実OS拒否の再現とは区別） |
| P4 | kill-close: 親自身Jobによる認定済み集合だけをobserverが採用、直接親をkill、保持したsupervisor/子/孫3handleの実exit |
| P5 | host-fail: dummy child非0、親primaryへ伝播、Job全体回収 |
| P6 | stdio-eof: child終了後に孫が両pipeを保持、EOF期限失敗、親Job停止で回収 |
| P7 | cleanup-fail / save-fail: primary維持・後続attempt・secondary保存、保存段階の故障注入をnonGreen |
| P8 | owner-mismatch: helloのowner不一致を拒否、子作業0・未知kill0 |

assign-fail/cleanup-fail/save-failは固定fault点への例外注入であり、実Windows割当拒否・実Docker失敗・ディスク故障の実証ではない。owner-mismatchは実metadata不一致、wrong-ackは実ack不一致を入力する。CIM AccessDenied、canonical path変更、pid/creation不一致各分岐や一方だけのEOF遅延は、現prototypeのruntime caseに含めていない。コード上でreject/個別観測し、未試験をpassedにしない。Chromium sandbox/nestedJob互換は別GOの実browser準備で確認する。

## 現段階の検証

PS Parser.ParseFile、Node --check、diffの静的確認だけを行う段階。Add-TypeのC#実コンパイル、P/Invoke呼出し、dummy probe、正式環境、実UIは未実行。正式監督に進む前にこの固定dummy probeと独立レビューを通し、同一固定HEADの証拠で判断する。

根拠: [Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)、[AssignProcessToJobObject](https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-assignprocesstojobobject)。last handle閉鎖がkill条件であり、CreateProcess系の子は既定でJob継承する。WMI生成やbreakawayは利用しない。
