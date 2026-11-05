# M21 Windows Job監督 prototype

462単発はms PASS後、FE dummy actual0/両EOF成功の後に自己stdout読取IO32で不合格となった。producerのwriter保持が原因で、現在は同じM21Childが実exit＋両CopyToAsync成功後に両writerを独立attemptでsealする修正を追加した静的段階（変更後実行は次GO待ち）。stdoutPublished/stderrPublishedをEOFと別記録し、Process handle/出生/exitは保持する。P6一次EOF失敗ではsealしない。再観測Eofはseal済みwriterを再Flushせず、finally Disposeも再度安全に行う。reader共有条件・metadata retry・期待値・予算は変更していない。原本packetは `foundation-m21-formal-fe-stdout-sharing-red-result-d7249e88.json` に単一renameし、3UGiPY rawは不変更保持する。

06単発でms契約のcanonical/zero/wrongを直接保存してGREEN、次FE未実装入口の実REDを確認した。現在は固定FE specとfrontend admissionだけをgreen実装した静的段階で、実行は次GO待ち。絶対Nuxt CLI/frontend CWD/127.0.0.1:3003、localhost public/API base、FEだけ8GiB NODE_OPTIONSを固定し、SystemRoot/WINDIRと所有Supervisor runのTEMP/TMP以外の親値を転送しない。Java innerRun/outerTempの契約は変更しない。既存M21Child.Startの同一本体へ明示env overloadを加え、FEではClear後固定envのみ、旧6引数dummyは継承挙動のまま。W/G後frontendはspawn前null拒否、owned-cleanupはG内でもformal-owned-cleanup-unimplementedで明示失敗する未実装状態。実Nuxt/FE/Java/Gradleは起動していない。

Java epoch-ms由来Instantの正規表記はUTCの秒のみZまたは3桁msZとなるため、formal-msへ秒のみ候補1件をtest先行追加した。保持Processの実javaIsoのms部分を000へ丸めた同じ秒を使い、ms同一の場合だけmatched=true、それ以外はparse成功後formal-java-ms-mismatchを要求する。世代許容幅0、表記対照を実OS世代証明にしない。ab4単発でformal-java-invalid-msの実REDを観測したが、呼出別地点が未保存だった制限は残す。現在は正規2形式だけのstrict parserとcanonicalProof/zero/wrong-msの地点・結果保存を追加した段階で、変更後実行は別GO待ち。6/9桁・offset・曖昧parseは許可しない。既存JDK21 src.zipのInstant346-349/1354-1355、DateTimeFormatter1199-1201、DateTimeFormatterBuilder895-897/3861-3873で0/3桁出力の一次sourceを確認済み（JDK実行なし、Gradle/App起動なし）。

正式adapterのPS契約試練は既存probeの固定 `--formal-contract-fixture` で選ぶ。e340の単発で(1)の未実装関数REDへ到達した。現在はmsの2関数だけgreen実装を追加した段階で、変更後実行は別GO待ち。順番は (1) 保持Supervisor実出生のfull ticks文字列とJava epoch-ms整数商・別ms拒否、(2) 所有Temp内のfake Nuxt CLIによる絶対CLI/CWD/FE-role環境、(3) 同じadmission入口でW後work拒否→実owned cleanup dummy終了→G後cleanup拒否。実出生subms=0は有効とし、固定BigInteger vectorを実OS世代証明へ格上げしない。Java登録/envの3境界試練は別未実測のままで、このPS試練で代替しない。

既存readiness4/通常10のcase・期待値・予算は変更しない。新3件は全体G420秒/R18秒/W402秒を一度だけ設定しcase間でresetしない。最大同時5process（harness Node、Parent、Supervisor、固定dummy、過渡compiler）、同時Job1。実サービス・ネットワーク・Chrome・Gradleは起動しない。最初の不合格で後続を止め、actual exit/両EOF/Job回収と失敗を分け、失敗Tempを保持する。case3の局所W/Gを短縮するだけで延長せず、markerと実owned childを検査するが、本番cleanup実行済みとは呼ばない。

固定後の実行案（rootの次GOまで未実行）: private Node22の `node.exe scripts/m21-windows-supervisor.probe.cjs --formal-contract-fixture`。`M21_INPUT_COMMIT` は当該固定full HEAD、通常user/TTY/login:false、外側観測420秒、再試行0。次はms合格とFE未実装REDを分けて採取する予定で、bootstrap/環境失敗を指定RED成立とはしない。新adapterは関数定義のみをdot-sourceし、BigInteger assembly loadもACK後の関数呼出し内。full ticksはstring/Int64/BigIntegerの整数として保持し、Java UTCミリ秒の厳密構文と一致した世代だけ採用する。違うmsはmatched=false、不正値/取得失敗/実終了は固定reasonでfail closed、PID再検索はしない。

e340の原本DNGZitは保持する。Supervisor actual1/両EOF/Jobemptyは記録されたが、wrapper保存先欠落によりharness actual/Parent launcher両EOFは未保存で全run資格を主張しない。次回は.claude保存先を先に作成し、Node stdout/stderrとactual exitをpacket整形前にprivate rawへ永続化する（tracked runner追加なし）。

正式adapterの先行試練は `ModuleEventRealE2eBootstrapBoundaryTest`。DB/full Appをrefreshせず、通常component走査のfixture候補0と明示sourceの既存4BeanDefinitionを対照にする。host formal子の既存env putだけをprivate methodへ挙動不変抽出し、固定JDK-only子へ公開sentinelを渡して不要key非到達/必要actor・run・Temp・URL到達を検査する。現段階はtest先行であり、TestConfiguration除去・env clearのgreen修正と実試験は未実施。production main CPだけの3source javacはrootのheavy gate/別GO後に一度行い、test CPをproductionと呼ばない。

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

診断採取版は既存stdoutへSupervisorの固定ASCII phaseを最大12件/合計4KiBだけ出す。entry→Guard前後→hello保存前/公開後→ack検証→native型前後をpid/creation・同一OSのUTC ticks・ローカルStopwatch elapsedで区別する。親子elapsedは原点が異なるため直接減算しない。Parentの開始前後/hello期限/kill前状態と実施有無は既存resultの最大16件の配列へ残す。CPU pollは追加しない。

entryはparam直後へ置き、New-Object等cmdlet・各種List/UTF8初期化・custom型初期化より前にnative .NETだけで出す。履歴（91fad953、GO Aでは撤去済）として、normalのhello5秒失敗を固定後に最大3秒の非解禁観測を置いていた。今回の単一予算ではこの追加観測を実行せず、二重消費しない。初期phase0ならengine/param前を含む未観測として返し、原因は推測しない。

旧分岐の15.5/21.5秒と独立launcher30秒は履歴値であり、製品ACではない。GO AはNodeのperformance.nowで自己Temp準備・最初のspawnより前に一度だけG=start+420000ms、R=18000ms、W=G-Rを設定し、caseごとにresetしない。Nodeが最終停止正本。PS/.NETやdummyのローカルclockと原点が同じとは仮定しない。

| wait/境界 | GO Aでの扱い |
|---|---|
| Node launcher/admission、PS hello/通常ACK/孫metadata/dummy終了/Supervisor終了/P4候補認定、dummy孫exit | 同じremainingWへclamp。独立30/5/3/4/8/9秒打切りは撤去 |
| spawn/Add-Type/Job assign/ACK/子解禁 | 各側のremainingW>0が必要。Node W到達でchannel収集より先に直接保持launcher停止を開始し、新case spawnを行わない |
| pre-ack故障注入 | 2500msとremainingWの小さい方。work予算切れをack-timeoutの成功にしない |
| P6 EOF観測・dummy hold | 既存500msの短期EOF、800/15000msのfixture holdを維持し、残予算で短くclamp。保持時間を延長しない |
| exit/各EOF/finish-close/回収、C# Start失敗回収 | remainingGへclampし、既存局所capを維持。最後のWaitForExit(0)は追加待機なし |

Node clockでcase/spawn前後/entry・hello・ACKの観測/actual exit/cleanupを記録する。entry等の時刻はファイルをNodeが観測した時刻であり、PS phase自体のUTC/elapsedとは区別する。固定case・数値残予算の引数だけを渡し、新metadata群やclock同期protocolは作らない。PS入場前の時間はPS側だけでは減算できないため、Node W/Gを越えた回収不明をgreenへ変換しない。

metadata-timeoutには固定理由・filename・elapsed/limitを付け、JSON IOはget-item/read/decode、guard/open/write/close/moveを区別する。ScriptStackTraceの英語形式が得られなければtrusted script一致のInvocationInfo.ScriptLineNumberへfallbackする。最後の保持handleでWaitForExit(0)とpump完了を再観測し、一次失敗は消さない。追加待機はない。

negativeはcase固有reasonに加え、wrong ack実体/親Job認定/一致hello/実dummy exit17等の指定故障点をassertする。別bootstrap失敗をnegativeの成功にしない。normalはdummy/directの非空2role集合・異なるpid/creation・実exit0/各EOF・最後のhandle観測・8phase順序を必須にし、every(empty)を許さない。これは診断採取であり、一度の成功を過去sharing違反/timeoutの根治とは呼ばない。

test-firstのhost-fail/cleanup-fail fixtureは、孫exit listenerをspawn直後に登録し、子が保持した孫のactual exit0後にだけ既存grandchild.jsonを公開してexit17する。孫自身はこの2caseで同fileを公開しない。孫の保持時間・既存timeoutは増やさず、旧PSの生存前提lookupを終了後へ通すRED試験である。PSのgreen修正はこの試験と別GOで行う。

GO BのPS修正はこの2caseだけを対象とする。自己run/owner/nonce、grandchild.jsonの厳密な5fieldと型・正整数pid・creatorPid一致・直接保持childのStart時RecordedPid/RecordedCreation・actual exited=true/exitCode0を検証する。受理した証拠はcreator-observed-exitとし、終了済み孫のPID/Handle/Jobを後追い取得しない。OS live/exact owned Job照会済みとは記録しない。子の実exit17/両EOFを保持Processで記録する。P4のlive世代・親exact Job認定・3handleの実exit経路、他caseのlive経路、予算/fixture/hold/faultは変更しない。

成功時に既存自己Tempが撤去されても、既存receiptからprimary/secondary理由・process世代・actual exit/両EOF・Jobempty・grandchild証拠種別・P4のexact Job認定と保持3handleのactual exitを最小summaryへ抽出してstdout packetに残す。入力3file SHA256と、採取側が指定した任意のM21_INPUT_COMMIT（40桁hexのみ）もpacketへ残す。新metadataファイルや削除方式は追加しない。

P6診断では既存live lookupで得た同じgrand Process/handleをCapture完了まで保持し、直接child exit後・各EOF前後にWaitForExit(0)/HasExited/取得可能なExitCodeを記録する。PID再検索や追加待機はしない。採用CopyToAsync前後・body/Capture exit待機前後・Eof前後はP6だけ最大16件の固定timingにUTC/local elapsed/実cap/TaskStatusを記録する。local elapsedは保持child object原点（PS観測はparam後原点）を区別する。15秒hold・500ms EOF・G/R・業務挙動は変えず、診断値も成功summaryへ抽出する。

P6の孫spawnだけはdetached=trueとし、Node自身の非detached用Jobへの登録を避ける。outer Jobのbreakaway許可・起動flag・security設定は変更しない。子は既存grandchild.jsonを見た直後に終了せず、固定p6-release.jsonをremainingW内で待つ。ParentはSupervisor終了待機より前にchild/grandchild候補の厳密なfield/type・owner/nonce・出生を検証し、自身Job handleでexact所属と保持handleの生存を認定した後だけACKを公開する。子もACKの厳密なfield/typeと両candidate identityを照合してからactual exit0する。

親のp6Certificationはinnerのany-job観測で上書きしない。保持孫はchild exit後から両EOF判定後までliveであること、一次stdout/stderr EOFが共にfalseでprimaryがstdout-eof-timeoutであることを要求する。その後ParentのJob.Stop・Jobempty・認定時から保持した同じ孫handleのactual exitを別々に保存する。15秒以内に認定できなかった場合や孫が先に自然終了した場合は不成立として停止し、hold/500ms/G/Rを増やさない。後続dummy pumpのEOFを再観測した証拠としては扱わない。

## 有限probe（実行には別GOが必要）

固定readiness試練は `node scripts/m21-windows-supervisor.probe.cjs --readiness-fixture` だけを入口とし、通常10case suiteを呼ばない。PSのTestFixtureは固定列挙のみ、ReadinessFixture本体は同じscripts内の定数pathに限定する。da699ddb/private Node22の同fixtureでA/B/Cのgenuine RED、不正JSON対照の即失敗を実測した後、以下の限定green処理を追加した。実測結果は固定commit/SHAと区別して判定する。

| fixture | 実境界と要求 |
|---|---|
| parent-before-certificate | Parentをcert前の固定故障で実exit1、作業子0。Observeのtest前処理は直接保持Parentのactual exit/codeだけをfixture-parent-exit.jsonへ記録し、Capture/streams/Capturedには触れない。旧cert待ちが継続すれば実exit記録後の既存R18000と残W/GのMinまで観測し、自隊Parent実終了とreceiptの作業子0を照合した後だけNodeが自分のObserveを停止。green判定には通常Observe自身のactual1・親Captureのactual1/両EOF・認定0が必要 |
| lock-release | publish完了済みの固定child-candidate.jsonを同PSの実FileStream/FileShare.Noneで保持し、未変更Wait-File→Read-Jsonで実HRESULT0x80070020を観測。test hookはcount/時刻だけを記録し、Bだけhandleを解放、元例外をそのままrethrow。受理1回・同owner/nonce/identityを要求し、旧readerの即失敗をREDとして残す |
| lock-held | 同じ実lockを保持し、専用WorkRemainingMsをMin(既存絶対W,現在elapsed+1000)へ短縮するだけ。実32count>0・元code・枯渇・受理0を要求。旧readerの即32は枯渇とは扱わない。finallyで自分のlockを閉じる |
| invalid-json | 同期write/close→move後の読取可能な固定不正JSON。decode段階の即失敗、実32count0、受理0、予算未枯渇を対照として要求 |

共有modeを緩めない。実32観測hookはReadinessFixtureの固定file/read段階だけで動き、hook自身は例外を握りつぶさず元例外をrethrowする。同processの別handleにもWindows共有制約は適用される。lockの時系列はlock-acquired→actual-read32→Bだけlock-released→受理の固定記録を残す。Cのfinally解放を、readiness回復による受理とは扱わない。

Wait-Fileのread段階では、同じRead-Json例外の有限InnerException chain中に実IOExceptionと完全HRESULT0x80070020/21を確認した場合だけ、同じ絶対Wと既存25msの外pollへ戻す。毎回Get-Itemのsize/reparse境界・read・decodeをやり直す。decode、owner/nonce/世代、reparse、サイズ、access denied、公開後消失、その他IOはretryしない。既存読取share modeは変えない。32/33別count・first/last elapsed・累積poll待機・元HRESULT・recovered/exhaustedを有限集計して結果へ残し、枯渇時は元例外chainとmetadata-readiness-exhaustedを保存する。回復待ちとして消費したRemember-Failure contextは直ちにclearし、後の無関係な失敗へ流用しない。

Observeのcert待ちだけは各外pollの先頭と認定前・親Kill直前で、直接保持ParentのWaitForExit(0)を確認する。早期終了ならparent-exited-before-certificateをprimaryとし、保持出生・actual code・認定0をparentExitEvidenceへ保存する。Parent receiptの読取・owner/nonce/出生照合は後続の独立attemptにして、失敗してもprimaryとactual exitを維持する。通常finallyのCaptureで親のactual exit/各EOFを採取し、ParentのJobemptyとP4成功を同一視しない。Observe自身のbootstrap/final結果は固定observe-result.jsonへ保存し、Parentのresult.json、3handle exit用observer.jsonと衝突させない。

P4はParentを意図的にKillするため、そのfinally結果ではなく既存certified-tree.jsonへ認定時点のmetadataReadinessをsnapshotし、同じcertifier identityに対応するP4 summaryへ残す。

試練全体も開始前に単一G420000/R18000を設定し、caseごとにresetしない。Aの18秒観測は既存絶対W内、回収の各exit/EOF/finish-closeは残Gへclampする。意図したREDでも独立した4fixtureの対照は採取するが、直接launcher未終了やAのParent安全条件が未確認なら後続へ進まずexact retainedへ残す。通常10case/正式環境は起動しない。test専用PSを含む4file SHA・入力commit・実exit/code・各channel・原本pathをpacketへ保存する。

Windows標準PowerShellと既存Nodeだけを使う。新依存/SDK/ダウンロードなし。通常のPowerShell policyを適用し、ExecutionPolicy Bypass/Unblock-File/ACL変更を行わない。policyや強い既存Jobで拒否されたら、非0・保持理由を報告する。sandbox無効化やbreakawayで逃がさない。

自隊worktreeのルートで、rootから有限probe実行GOを得た後だけ実行する:

    node scripts/m21-windows-supervisor.probe.cjs

10 case逐次で総W/Gを共有する。実exitとChildProcess closeを同一視しない。stdout EOF・書込finish/close・stderr EOF・書込finish/closeは各最大2秒、失敗後の直接own child exit最大5秒、各stream回収各最大2秒をremainingGでclampする。無期限finished待機はない。

回収reserveは **4×(2000+25)+(5000+25)+2×(2000+25)=17175ms < R=18000ms**。workは最大402000ms、総Gは420000msで、各待機はその残りを上限とする。W到達時は先に停止を開始するため、回収前にEOF待機へreserveを浪費しない。最初の失敗/予算切れで残caseへ進まず未実施を返す。同期filesystem/OS fatal/event-loop pauseまで420秒以内に完了する保証ではなく、未回収はexact retainedへ残す。

P4で外側がObserveを停止しただけでは、Observeが所有するParent/Job treeを回収済みとしない。総期限途中停止はunverifiedOwnedTreeとexact runを保持し、未知PIDへ接続して停止しない。GO Aがhost REDで停止した場合はP4未実施であり、この制限を試験済みとはしない。

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

PS Parser.ParseFile、Node --check、diffの静的確認後、rootの限定GOごとに固定入力のsuiteを1回実行する。過去のprobeでAdd-Type実コンパイル・Win32 Job・host非0伝播まで観測したが、P6では孫の早期exit0によりpipe保持fixtureが不成立だった。P6限定修正版の全10caseは同一固定HEADの実測packetで判定する。正式環境・実UI・Chromium互換は未実施で、dummy成功を正式M21合格へ流用しない。過去のsharing違反やPS admission遅延の原因解消とも扱わない。

根拠: [Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)、[AssignProcessToJobObject](https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-assignprocesstojobobject)。last handle閉鎖がkill条件であり、CreateProcess系の子は既定でJob継承する。WMI生成やbreakawayは利用しない。
