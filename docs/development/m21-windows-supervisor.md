# M21 Windows Job監督 prototype

現在の固定境界は、Job外Parentの非継承Job/ACK、保持Processの世代・actual exit・両EOFとwriter publication、Java epoch-msの整数投影とUTC秒Z/3桁msZの厳密照合、固定FEの絶対CLI/CWD/環境分離、fixture-markerだけのowned-cleanup admissionである。FEは127.0.0.1:3003とlocalhost public/API baseを固定し、8GiB NODE_OPTIONSはFEだけ。cleanupはOS2値と所有runのTEMP/TMPだけを再構成し、親秘密・任意command/envを転送しない。実Docker cleanupは未実装、未知kindは拒否する。

`--formal-contract-fixture` はms→fake FE→W後work拒否/実owned marker cleanup/G後拒否の3件。G420秒/R18秒/W402秒を一度だけ設定しcase間resetなし、最大同時5process/Job1、最初の不合格で停止する。局所W/Gは短縮だけで、実OS subms=0は有効、整数vector/表記対照を実OS世代証明にしない。fbcf603947a7b0299909479236775e8221417a09固定で3件＋readiness4＋通常10は合格した。asset準備関数は今回追加の未実行コードであり、この17件の資格をasset生成へ転用しない。期待値・予算は変更していない。

Java登録/envの3境界試練は9cのCIで通常scan実1/期待0とformal孫の実leaked=true/期待falseのREDを確認した。Fixturesをannotationなしのplain4@Beanにし、formal環境をOS2/元のowned TEMP・TMP/PLAYWRIGHT_BROWSERS_PATHの退避→clear→必要actors/run/BASE_URL/API_BASE_URL再投入へ限定修正したが、変更後の実測はまだない。既存3testsの期待値は不変更、Java innerRun/outerTempの不一致も有効なままである。

full productionCP/明示3source JAR、実App/FE、Chrome nested Job/Sky GUI、実Docker cleanupは未実測である。productionCP前に固定HEADのbackend main/resources/build-logic/Gradle設定/依存入力を全materializeしてGit manifestと照合し、focused test前はtest source/resourcesも揃える。test CPをproductionと呼ばず、既存prototype/Java/helperの資格を正式実機へ転用しない。

過去runの経緯・原本・不足証拠は自隊private result packetに保持する。固定Node22/入力full HEADを記録し、保存dirを事前作成、stdout/stderr/actual exitをpacket整形前にprivate rawへ永続化する。原本はroot解放まで保持し、新tracked runnerは作らない。

## Windows production assets（生成未実行）

CP initの固定ownerは現正式runの f6efb6d3-51e1-4408-a760-92f7d3642122 に一致させる。Supervisor/probeの既定ownerと有限fixtureの固定ownerも同じ新実行主体へ揃え、CP initでは旧owner・別UUID・nullを許可しない。nonce/purpose/sourceCommit/path/CREATE_NEW/main CP境界は変更しない。この補正は静的契約の整合だけで、実Gradle/CP/JAR生成やowner/creator実検証の合格ではない。実検証は既存前提が満たされた正式Runの別GO後に行う。

`New-M21ProductionAssetContext` はACK後のSupervisor/自隊Jobだけで呼ぶ。新規owned `run/assets` とownerを作り、固定Git実体によるHEAD・全backend/Gradle入力・launcher/initのtracked manifest、実在/reparse/raw SHA256、同HEADとのGit diffを照合する。main/resources/build-logic/wrapperとtest source/resourcesの欠落や未追跡sourceを拒否する。CRLF checkoutのraw SHAとGit blobは別記する。Gitの暗黙取得/認証promptは無効化し、既存M21Child/Remaining/Captureで自分のGit子だけを有限回収する。JDK21/Node22は既存固定path/SHAを検証し、追加downloadしない。

rootの共通heavy順番取得前はturnstile待機wrapperも起動しない。取得後の固定CP生成は、backend CWD・`JAVA_HOME=固定JDK21`・`M21_BACKEND_DIR=context.backend`・`M21_CP_OUTPUT=context.cpFile`で、実体 `C:/Program Files/Git/usr/bin/bash.exe --noprofile --norc ./scripts/gradle-turnstile.sh ./gradlew --no-daemon --offline -Dorg.gradle.java.home=固定JDK21 -Dorg.gradle.java.installations.auto-download=false -I context.cpInit m21ProductionClasspath`。新initは同一canonical projectとowner/nonce、新規出力、Windows/JDK21を確認し、`classes`依存の`sourceSets.main.runtimeClasspath`だけをCREATE_NEW/UTF-8/BOM・改行なしで出力する。test/別WT出力・欠落entryは拒否する。旧CP/cacheへのfallbackはしない。

actual0/両EOF/publicationを確認した保持CP processだけを`Get-M21FixtureCompilePlan`へ渡す。main classes/resourcesと既存Gradle cache JARのbytesを記録し、空output/別WT/test CP・source/tool変化を拒否する。返るjavac引数は`-J-Xmx512m --release 21 -encoding UTF-8 -parameters -implicit:none --source-path 空directory -cp productionCP -d 新規classes`とApplication/Fixtures/Cleanupの明示3sourceだけ。javac actual0の後だけ固定JDK jarの`--create --file 新規JAR -C 新規classes .`へ進む。保持javac/jar両actual0とpublication後、`Confirm-M21FixtureJar`は本体/内部class3系統＋META-INF/MANIFEST.MFだけを検査し、CP/JAR/entry SHAと正確な`productionCP + ';' + fixtureJAR`を保存する。JUnit/helper/test frameworkをJARへ混ぜない。

大きい証拠は所有assets内の固定3 manifest名だけにCREATE_NEW保存する（上限16MiB）。16KiB protocol JSON境界は変更しない。Git子3本は`--no-optional-locks -c core.fsmonitor=false`を固定し、source/toolsの各再hash前にもRemainingを確認する。CP/JARの構造確認と資格は分離する。同じbash/CWD/actual0だけでは固定argv・own登録child・実taskの接続証拠が足りないため、後続callerで紐付くまで`productionCPVerified=false`/`fixtureJARVerified=false`を保持する。Gradle task outcomeは保持rawを別途確認し、offline解決の実証前は`offlineResolutionVerified=false`のまま。今回sliceは関数/固定initだけで、Gradle・javac・jarの起動実装/実行、正式55分Parent/Dockerは未接続である。Java3境界の実RED待ちをCPへtest依存追加で隠さない。

## 所有と失敗時の境界

親は非継承・無名のJob handleを単独保持し、KILL_ON_JOB_CLOSEだけを設定する。直接起動した待機supervisorをJobへ割り当て、保持handleでIsProcessInJobを確認した後にだけ、owner/nonce/pid/creationを含むackを発行する。supervisorはack前にdummyを起動しない。Job handleは子へ継承・複製せず、breakawayも許可しない。

Supervisorのack前は標準Process/JSONでhelloを保存しackを有限待機するだけで、Add-Typeも実行しない。親がexact Job所属を確認した後だけownedJobVerified付きackを発行し、Supervisorは同run/世代のackを照合してから型をcompileする。custom型を参照する関数定義と呼出しもこのgateの後に配置する。Parent/Observeの型初期化とは位置を分ける。

根拠は[.NET Framework reference source CodeCompiler.cs](https://raw.githubusercontent.com/microsoft/referencesource/main/System/compmod/system/codedom/compiler/CodeCompiler.cs)のCompileがcompilerExeをExecutor.ExecWaitWithCaptureへ渡す点。PS5.1の全呼出しchainや実compileを測定した証拠ではなく、Add-Typeが子0で完結するという仮定を避けるための配置である。型名解決の実動作もruntime合格とはせず、静的ASTでgate前のcustom型参照0と、型を使う関数定義がAdd-Type後にあることを確認する。

直接Processの保持handleと開始時刻を使う。P4ではsupervisorが子/孫のcandidate metadataを出し、Job外親が自身のJob handleで各既知世代のIsProcessInJobを確認してからcertified-tree.jsonを発行する。observerは直接保持した親のpid/creation・owner/nonceと一致する認定済み3件だけを採用し、各世代のhandleを保持して実exitを観測する。「何らかのJobに所属」だけでは親所有Jobの証拠にしない。Job handleをobserver/子へ渡さない。未知PID停止APIやCIM列挙は提供しない。取得不能・Job照合失敗を終了済みに読み替えない。

stdout/stderrは別のprivate fileへCopyToAsyncし、実exit/exit codeと各EOFを有限待機する。起動後例外でもStartはprimaryを固定し、直接保持Processのkill・実exit最大1秒・開始済みstdout/stderr各最大0.5秒を別々に試す。保持objectをDisposeせず呼出元のheldへ返し、finallyで再確認する。startupRecoveryにはpid/creation/logs・各事実・retainedAtStartupを残す。最終回収不明はresourceRecovery.retainedへ残してnonGreenとする。

Captureが例外なしで実exitを確認できなかった場合、初回exited=false/timeout/未公開を保存し、初回publication未試行の同じ保持childだけを保留する。既存last-handle段階のWaitForExit(0)と両pump完了後、残Gが正なら各writerを既存PublishCapturedOutputで一度だけ閉じて公開する。各側の公開後と最終証拠確定時にも残Gが正であることを要求する。EOF・writerclose・publicationを別欄にしたresourceRecovery.lateExitProofsを残し、Job確認と既存回収判定も成立した場合だけ保留を解消する。既試行publication失敗は再試行しない。Wait例外/EOF失敗/P6/inner/Job/外部回収などでfalseになった判定をtrueへ戻さず、未解消は最終falseとする。lateObservationの既存値は変更しない。

assign-fail回帰の初回1500ms終了待ち未達は実際の一次観測であり、後続の保持handle実exit/各EOFで上書きしない。OS側で1500ms以内にsignalされなかった原因はtimings未採取のため未確定である。期限・sleep・poll・初回capを追加せず、pure境界matrixと最終固定sourceの実回帰で判定する。

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

### 正式入口の中間実装と固定asset caller

`Scenario=formal-m21` は Parent/Supervisor、TestFixture=none だけに限定する。G55分(3300000ms)/W50分(3000000ms)/R5分(300000ms)を上限とし、渡される残W/Gの差にもRを確保する。既存dummyのG420秒/R18秒を拡大しない。親の同じ時計から子へ残量を渡し、各子の開始時に55分へ戻さない。

Parentの固定初期化hookと、同じhello→exact Job assign→ACK後のSupervisor hookを追加した。後者は引数なしの `Invoke-M21ProductionAssets` へ明示的に接続する。dummy・fixture・環境変数の有無からは呼ばない。Supervisor起動環境はOS2値・owned TEMP/TMP・固定入力HEADだけを選び、全親環境を継承しない。

正式hookは、固定WSL bridgeのready→init→限定3preflight→descriptor bind、専用MySQL/Valkey作成、ACK後asset生成、FE/Java/helper/3browserの固定起動へ接続する。必要source/receipt/環境が不足すれば先へ進めない。`formalEntryEvidence` は接続/asset確認/正式run資格を分離し、`formalRunQualified=false` を維持する。正式入口の存在や有限fixtureのgreenを正式実行合格にしない。実asset生成・実App・実Docker作成はrootの別GOまで未実行である。

回収の固定計画は90+30+10+16+12+61+14+14+20=267秒、余裕33秒でR300秒。App Job保持最大8、Job外Parent relay1を別fieldで記録する。Docker cleanup CLIは最大14、同時1を維持する。Job空待ちは正式のみ10秒、Captureは実exit最大2秒・各stdout/stderr EOF最大1秒を残Gへclampする。relay終端の実exit2秒＋各EOF0.5秒は最後の20秒枠に含め、ledger/FSへ残17秒を割り当てる。固定表と実装を実回収の実証へ読み替えない。

Parentは専用Temp直下の `m21-windows-*` とexact `{owner,nonce}` のowner.jsonを使う。新規bridge/owner.jsonには別run UUIDと固定purposeを追加する。Windows→Linuxは既存M21Childの専用stdin frame（4byte BE＋UTF8 JSON、最大16KiB）だけ、Linux→Windowsは新規owned receipt fileだけを使う。stdout/stderrは既存CopyToAsyncのprivate rawとして別採用し、応答parserへ転用しない。OS2値とowned TEMP/TMPだけで固定wsl.exeを1本起動し、WSLENV/proxy/全親envを転送しない。

Linux readyのt0とParent受信時の単一時計を固定し、initはG/Wの絶対offset、requestは送信準備時のelapsed＋局所capを元のG/W offsetへclampして渡す。受領時にnow＋remainingへ戻さない。descriptorは既存daemonと既存local image3件の固定preflightだけで採用し、pull/任意config/追加WSLを起動しない。各work資源はvolume-create→volume-inspect→container-create→container-inspect→container-start→container-inspectの固定6件、2roleで12件である。専用loopback portをstart後にfreezeし、共有Valkey6379を拒否する。

MySQLは新規DB `m21_<bridge-runhex>`、固定user m21、別々の生成root/app passwordだけをprivate frame→Linuxメモリ→Docker子の固定envへ渡す。値をargv/receiptへ出さない。container名は `m21-<runhex>-<role>`、volume名はその `-data` suffix。cleanupは同daemon確認→所有inspect4→stop2→rm4→roleごとの両資源不存在candidate2→最後の同daemon確認の計14件である。各要求は当該保持CLI/watchdogの終了・独立した両EOF/reapを確認後だけ次へ進み、未回収時は追加spawnを拒否する。不存在candidateだけでは資格を立てず、最後の同daemon receiptの両role/exact container/volume名と合わせる。

FEは同WTの絶対CLI/CWD、3003 loopback、必要OS/Temp/URLとFE専用8GiBだけで起動する。Javaはproduction CP＋fixture JAR、8081 loopback、専用DB/Valkey、自己test鍵、メールsimulate、SQS無効、自己BEにpinしたR2/SES/OTLPと既存AWS env providerを固定する。必要キーを新規辞書へ作り、親env/.env/資格情報fileをコピーしない。helperのJava msとOS 100ns世代は既存整数変換を使い、Parentが同じ保持Processのexact Jobを認定する。追加listener workerが必要ならApp保持8の内数にする。

rootはformal-ready.jsonの固定3titleを正規Skyで選ぶ。UI操作をscriptへ追加しない。探索終了時はouter runのformal-browser-close-requested.jsonへexact `{owner,nonce,runId,actorCodes:[system,admin-de,member],requested:true}` を新規atomic発行する。Supervisorが全保持browser/helper生存を検証し、formal-browser-close-admitted.jsonのphase=browser-close-admittedを公開したことをrootが確認してから、Skyで3windowを通常closeする。各actual0/両EOF後のformal-browsers-closed.jsonを確認して初めて既存inner controls/exploration-finished.jsonを発行する。既存Java制御3種を変えない。

Javaが探索完了markerを先に読みhelper cleanupへ進んだ場合、Supervisor/Parentはfresh markerとserved3/failed=falseのhelper state、全browser回収を照合してHOST_CLEANUPへ移る。未完探索のW到達は即nonGreen、既存完了を裏付けたcleanupだけhost90秒＋Supervisor終端30秒を観測する。host-resultとJava actual0/各EOFを確認後、認定済みFE世代を停止し、後続Job回収と外部Docker回収を別に記録する。早期exit/不一致を成功へ置換しない。

有限契約fixture（正式App/Gradle/Docker/Chrome起動0）:

    node scripts/m21-windows-supervisor.probe.cjs --formal-entry-contract --owner-id f6efb6d3-51e1-4408-a760-92f7d3642122

| 境界 | fixtureで実assertする範囲 | 未実証 |
|---|---|---|
| 予算 | G/W/R、267+33、dummy上限、formalのmode/fixture/残量拒否 | 実55分run |
| hook順序 | 固定Supervisor関数を呼び、外部asset境界の置換でassets→work、CP失敗後work0、期限後assets0 | ACK前spawn0/ACK後実asset生成のOS観測 |
| RequiredAlive | 現在の保持Processを採用、空集合を拒否 | FE/host/helper/browser早期終了 |
| 環境 | Supervisor用辞書にsentinel/PATHなし、必要HEAD/TEMPあり | 各実子/孫への伝播 |
| 途中失敗 | asset例外の後続停止 | 実Job/EOF/Dockerの独立回収 |

通常sandboxのPowerShell policyで拒否された場合は未実行と記録し、policyを変更しない。今回のred/greenは通常ユーザーの既存policyで行い、最初は未定義関数によるred、実装後greenを確認した。既存17件とChromiumの回帰は統合HEADを固定した別GOで1回実施する。

関数診断の旧15秒launcher上限はworkerが置いた任意の診断値であり、採用計画のACではない。その試行は停止・失敗のまま保持し、phase/SHA未採取のため原因を確定していない。追加した60秒診断はchild entryからのphase時刻、Windows launcherのactual exit/両EOF、source前後SHAを `.claude/m21-entry-contract-<nonce>.json` に記録する。60秒診断のgreenを15秒green、正式55/5/50、dummy420/18、B4の保証へ読み替えない。正常時は所有確認後に小さい診断Tempを撤去し、失敗時はexact pathを保持する。

### B4 固定relay喪失fixture

StartBridgeだけは固定prefix `-d Ubuntu-24.04 --exec /usr/bin/python3 -I` をliteralにし、後続source/metadataには既存Quoteを使う。通常M21Childのserializerは変えない。最初のc650入力B4では全argvをQuoteした起動が `/bin/bash: line 1: -d: command not found` / relay actual127で終わり、意図Killへ進めなかった。quoted optionの解釈自体はdocsだけで断定せず、この一次失敗とLinux証拠未取得を保持する。修正後は非秘密の実rendered argumentsもreceiptへ保存する。

212a入力ではbridge ready後terminal/relay actual1となり、Linux子proofを採取できなかった。既定stdinのBOMが原因だったとは未確定である。一方、Frameworkのbuffered BaseStreamにWriteAsyncだけを行いFlushしない不足は独立して補完する。固定bridgeのProcess.Start周囲だけ、既知65001のpreambleがある場合にUTF8NoBOMへ保存・finally復元し、未知preambleは起動前拒否する。Start直後に保持Startedを記録し、起動と復元の二重失敗は起動をprimary・復元をsecondaryへ残す。通常Start/OS設定ファイルは変更しない。各frameは同じ単一cap内でwriteとflushのRanToCompletionを確認し、失敗後の再送を拒否する。B4には固定phaseとcodepage/preamble長/write-flush完了状態だけを追加し、原因未確定の旧失敗を成功へ置換しない。

private Node22で `node scripts/m21-windows-supervisor.probe.cjs --wsl-relay-loss-fixture --owner-id f6efb6d3-51e1-4408-a760-92f7d3642122` を実行する。`M21_INPUT_COMMIT` にclean統合HEADを指定する。既存readiness runnerとM21Childを再利用する独立1caseであり、通常17件を呼ばない。Windows側はG60秒/W40秒/R20秒、Linux initは残量へclampしたG4秒/W3秒と固定request cap1秒を使う。

固定test CLIはproductionのguard/bridge loopを使い、外部Docker command/preflight境界だけを固定Python dummyへ置換する。Linuxのfork/setsid ACK/pidfd/subreaper/独立watchdog/reap/両EOFは実物である。実Docker/Java/FE/Chromeは起動しない。request-started.jsonのowner/nonce/run/source/request/世代とsession ACKを確認してから、保持した同じwsl.exeだけをKillする。Windows actual exit/各EOFと、その後の独立Linux receiptのCLI子集合reap/各EOFを別に検査する。

fixture passedは意図故障の検査成功だけで、primaryRelayQualified/formalRunQualifiedはfalseである。Windows relayのexit/EOFをLinux CLIやbridgeのactual exitに読み替えない。Linux bridge自身のactual exitは本fixtureでは未観測のfalse、watchdog自身はobserved receiptが得られた場合だけ別flagで記録する。一次Linux失敗を後続reap/EOFでgreenへ更新しない。source SHA前後・exact owner/nonce・保持世代とrawを残し、rootの最小証拠確定と所有/書き手終了確認後に自己Tempだけを撤去する。

Windows→WSLの同一性ゲートは、既存work/valkey最終container-inspectでLinux側owned loopbackから読んだ単一40桁run_idと、Java起動直前にWindows側同portから読んだ値を照合する。固定INFO serverだけを使い、CLI/子/要求を増やさない。Windows側はWと局所3秒をclampし、全frame16KiB、完全RESP bulk、単一run_id、取得済み/直後queuedな追加bytesの拒否、finallyでのcloseを要求する。未来に遅れて届く追加bytesの不在は主張しない。呼出し前に前回proofをnullへ戻し、未生成socketのclosedもnullとする。失敗時はJavaを起動しない。

関数診断の固定loopback9応答は正常、不一致、run_id不足、重複、oversize、不正RESP、不完全RESP、追加bytes、timeoutを実assertする。これは固定parser/gateの有限試験であり、実WSL転送先・実Valkey/daemon・正式Appの資格は未取得のままである。既存Cookie契約は変更していない。旧backend規約のlocalStorage記述との不一致はrootへ報告し、この変更で規約を黙って修正していない。

### 統合後17件の固定引渡し

B4のWindows phaseは同じStopwatchのelapsed ticksとfrequencyで最大10点だけを記録する。descriptor検証、bind送信開始、write/flush完了、bound待ちを分け、native guard前とwrapper guard前の状態をfreshに戻す。bind専用finallyのstateを採取し、initのtrueをbind成功へ読み替えない。Linuxの絶対時計値との直接比較はしない。hostには専用生成 `JOB_QR_SIGNING_SECRET` を一項目だけ追加し、値を掲載せず非空/UTF8 32byte以上を検査する。既存kid/active設定と他validatorを変更しない。

通常kentaの既存ExecutionPolicy、固定Node `C:/Claude/mannschaft/.claude/runtime-assets/node22-d7249e88-6cfac601/node-v22.23.2-win-x64/node.exe`（SHA256 `0d0f5e39f9f3d9587bc19f73eab3c2c9c4903fd02d6dbf9c853dd81b3d95fad4`）で、clean統合HEADの40桁を `M21_INPUT_COMMIT` に設定し、同じownerを明示する。rootの別GO後、順に各1回だけ実行する。

    node scripts/m21-windows-supervisor.probe.cjs --formal-contract-fixture --owner-id f6efb6d3-51e1-4408-a760-92f7d3642122
    node scripts/m21-windows-supervisor.probe.cjs --readiness-fixture --owner-id f6efb6d3-51e1-4408-a760-92f7d3642122
    node scripts/m21-windows-supervisor.probe.cjs --owner-id f6efb6d3-51e1-4408-a760-92f7d3642122

順に正式契約3、readiness4、通常10。`--formal-entry-contract` は別の60秒関数診断、B4/Chromiumも別suiteであり17件へ混ぜない。stdout/stderrとexit codeを新nonceのprivate採取先へ保存し、packet内retained/exact runのrawを保全する。過去の失敗Temp、15秒timeout、policy拒否、source別の中間greenを上書き・削除しない。成功時のrunner自身による所有照合済みTemp回収だけを既存動作として認める。

callerは既存Job/Remaining/M21Child/Register-Child/Captureを用いて、source検査後に固定Git Bashの既存turnstile経由offline production CP、固定JDK21のjavac、jarの順で進む。正規WindowsユーザーのKnownFolderと `C:/Users/kenta/AppData/Local` の一致を確認し、既存machine gateを使う。Gradle cacheは `C:/Users/kenta/.gradle`、子環境は必要OS値・private TEMP/HOME・固定tool/cache/path・CP入出力だけを明示する。全親環境は継承しない。

各buildはcreatorが保持する同一child参照、held登録、owner/nonce/input HEAD、固定exe/CWD/argv、実exit0、両EOFとpublicationを要求する。CPはCREATE_NEW出力とsource/tool再hash、同child rawのcompileJava/processResources/classes/m21ProductionClasspath outcomeを照合し、UP-TO-DATE/FROM-CACHEを実再compileへ読み替えない。未確認なら後続を起動しない。CP/JAR manifestへ固定生成元と各outcomeを保存する。fixture JARの構造・CP bytes・source/tool不変を確認した場合だけasset資格を立て、正式run資格はfalseのままとする。完了済みCLIは既存Capture証拠を保持してDispose/held解除し、未確認childは既存finally回収へ残す。

`-OwnerId` は既存owner/nonce/runガードに渡すUUID一つだけの選択で、ParentからSupervisorへ伝達する。probeも末尾 `--owner-id <UUID>` を受け、全dummy子孫とfixtureの期待ownerへ同じ値を渡す。旧既定値は過去dummy互換用であり、今回のrunには必ず新ownerを明示する。引継済みNode22の固定ツールパス内の過去作成者名は変更せず、SHA確認して読取再利用する。

今回の所有者を使うprobeの形（実行には別GOが必要）:

    node scripts/m21-windows-supervisor.probe.cjs --owner-id f6efb6d3-51e1-4408-a760-92f7d3642122

asset生成・変更後dummy回帰・実Java/Gradle・正式環境・実UIは未実行。CP task outcomeの実ログ形式、実machine gate、途中失敗時の停止/EOF/publication/held解除は実測合格としていない。

PS Parser.ParseFile、Node --check、diffの静的確認後、rootの限定GOごとに固定入力のsuiteを1回実行する。過去のprobeでAdd-Type実コンパイル・Win32 Job・host非0伝播まで観測したが、P6では孫の早期exit0によりpipe保持fixtureが不成立だった。P6限定修正版の全10caseは同一固定HEADの実測packetで判定する。正式環境・実UI・Chromium互換は未実施で、dummy成功を正式M21合格へ流用しない。過去のsharing違反やPS admission遅延の原因解消とも扱わない。

### 固定Chromium fixture（実起動は別GO）

`node scripts/m21-windows-supervisor.probe.cjs --chromium-fixture --owner-id f6efb6d3-51e1-4408-a760-92f7d3642122` は通常10caseを呼ばず、固定2caseだけを同じG420000/R18000内で実行する。`M21_INPUT_COMMIT` はrootの確定40桁HEADを必須とし、PS/probe/adapter/readinessの固定4file SHAを前・case間・後で照合する。既存private Node22とbundled chromium-1223/chrome-win64/chrome.exeの固定SHAを起動前に検査する。新engine・任意exe/path・UI操作は追加しない。**有限Chromium異常caseだけ**は、既存M21Jobを使うouter witnessを一つ追加する例外とする。正式入口の単一Job、通常dummy/P4、通常Chromium closeの機構・予算は変えない。

Supervisorのhello→Parent exact Job assign→ACKが済んだ後だけ、既存M21Child.Startで3rootを起動する。0budgetは既存Require-Workで停止し、ACK不成立時のChrome spawnは0。新owned run/profiles直下のsystem/admin-de/memberだけを新規作成し、既存path・reparse・範囲外を拒否する。引数はuser-data-dir/no-first-run/no-default-browser-check/new-windowと非秘密nonce+role titleのdata-pageだけ。sandbox/security/breakawayの変更はない。

Parentが固定3候補のowner/nonce/出生/実exe/live/exact自己Jobを保持handleで確認してから、`chromium-ready.json` を一度だけ公開する。probe stdoutの`chromium-sky-ready`が返すexact readyFileをrootだけが限定readし、正規Skyの返却windowをtitleで一意選択する。通常caseではSkyで各自己windowをcloseし、browser各actual0/両EOFをSupervisor、Supervisor actual0/両EOFとJob Active0をParentが記録する。window closeと後続Job.Stopの全tree回収は別事実である。

異常caseはObserve自身をwitness外に置く。直接保持Parentはnative compile前に固定 `chromium-witness-hello.json` を公開して待ち、Observeが同じowner/nonce/PID/出生と生存を確認してwitnessへ所属させた後だけ、厳密5keyの `chromium-witness-ack.json` をatomic発行する。ParentはACKのfield/type/出生/nonce/所属trueと残Wを検証した後で初めてnative compile・inner Job・Supervisor生成へ進む。ACK前のinner Job/子生成は0。新待機capや予算延長を加えず、既存Wと25ms pollを使う。

Parentは既存certified-treeの固定supervisor/system/admin-de/memberの4集合に加えて、実QueryInformationJobObjectのinner LimitFlagsが `0x2000`（kill-on-closeだけ）、Parent自身がinner外、4rootがinner内である事実を記録する。Observeはそのcertifierを直接保持Parentの同出生に照合し、保持Parentと4root全てを同じwitness handleでIsProcessInJob照会する。rootはSky観測後だけ、返却observedFileへ厳密3key `{owner,nonce,observed:true}` をatomic発行する。Observeは4root/Parentの生存とwitnessのkill前Active>0を確認し、直接Parentだけをkillする。保持4rootの実exit、Parentの実exit/採用両EOFを既存Captureで採取した後、既存3000ms capと残Gの短縮側でwitness Active0を照会する。Nodeのlauncher両EOFは外側で別途採取する。既存dummyの3集合期待値は変えない。

成功はouter Stop/Dispose/close前に観測した上記事実だけで判定し、`chromium-witness.json` をatomic保存してからhandleをcloseする。ゼロ照会の前後と資格証拠確定時には残Gが正であることを要求し、proofの `remainingRecoveryMs` はstrict整数の正数だけを純粋判定で認める。初回CREATE_NEW公開後にも残Gを確認し、公開が期限を跨げばqualifiedを立てず非greenとする。最初の公開fileは上書きせず、残予算と失敗を最終receiptへ別記する。証拠公開失敗、先行failure、outer cleanupの利用、取得不能、残数不明も非greenである。故障時はwitness Stop→有限残数照会→closeを独立attemptするが、その回収でprimaryや資格を成功へ戻さない。probeは最終receiptのpassed/recovered/qualified、fallbackStopUsed=false、公開後残Gの正数、保存proofとの完全一致を全て要求する。

**証拠の意味:** [Nested Jobs](https://learn.microsoft.com/en-us/windows/win32/procthread/nested-jobs)の祖先accountingと[AssignProcessToJobObject](https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-assignprocesstojobobject)の所属継承に従い、保持witnessの[ActiveProcesses](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_basic_accounting_information)残数0を `witnessContainmentRemainingZero` として記録する。これはinner Active0、各rendererの保持handleによるactual exit、死亡Supervisorのbrowser pumpのEOFという別の観測を代替しない。`resourceRecovery.jobEmpty` はObserveではnull、`innerActiveZeroObserved` / `browserEofObserved` / `fullRendererActualObserved` とpacketの従来3unknown fieldはfalseを維持する。全renderer handle取得を追加ACにせず、未知PID探索/killは行わない。inner Job handleは従来の無名CreateJob/null security attributes、引数/metadata非伝達、複製・継承を追加しないsourceに基づく証拠を記録する。runtime handle総数の照会・実証を主張しない。

| AC | 実装・証拠 | 今回の検証資格 |
|---|---|---|
| owner/nonce/原子的出生ACK、ACK前inner Job/子0 | 固定hello/5key ACK、native前gate、既存Require-Work | 純粋ACK正常1/拒否7、gate→native/inner Job/子のAST順序。OS所属は未実測 |
| flagsと境界の実Query | inner flags=0x2000、Parent inner外/4root inner内、Observer witness外/保持5世代 witness内 | 実装済、純粋proof反例で拒否。実JobのQueryは未実測 |
| Parentだけの異常停止と限定owned回収 | 保持4root actual exit、Parent Capture actual/各EOF、kill前witnessActive>0→後0 | 実装済、Chrome/Sky/Job起動0のため実測資格なし |
| cleanup/pub失敗・残G消尽を成功へ合成しない | pure qualification、照会前後/確定時/初回公開後の残G、保存proof＋最終receipt、fallback独立attempt | 純粋proof正常1/拒否24（残Gの0/負/null/未知型/欠落を含む）。cleanup/期限跨ぎの実故障は未実測 |
| inner/full-renderer/browserEOFへ昇格しない | rootActualExits、採用EOF、witnessContainmentRemainingZeroとunknown3fieldを分離 | 純粋反例とprobe固定assert、正式App資格false |
| 既存pending回収を維持 | lateExitProofs/唯一PURE marker/実Job条件をそのまま使用 | 既存純粋14caseを同markerから抽出して合格。17回帰は今回未実行 |

`--chromium-witness-contract` はこの有限fixtureの純粋schema/AST診断専用で、Job/Chrome/GUIを起動しない。生成PSはWindows PowerShell5向けUTF8 BOMを明示する。ACK8/proof25/既存pending14caseを検査し、同じnative C# literalを静的compileする。既存System.Numerics assembly読込を別native engineと数えず、実 `Add-Type -TypeDefinition $nativeCode` だけをASTで選択する。診断のactual exit/各EOF、owner/nonce、4source SHA/mtime前後を自隊 `.claude/campaigns/chromium-witness-contract-<nonce>/` に保存する。純粋診断の合格をChromium qualificationや正式Runの実測へ流用しない。

成功時もprofile/rawはrootの証拠確定とexact所有確認まで保持し、retainedにexact baseを返す。途中失敗では後続caseを起動せず、回収不明を記録する。Chrome/Job拒否や取得不能を成功へ読み替えず、未知PID採用・killは行わない。このsliceの静的parser/check成功は実Chromium/Sky互換の証明ではない。

根拠: [Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)、[AssignProcessToJobObject](https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-assignprocesstojobobject)。last handle閉鎖がkill条件であり、CreateProcess系の子は既定でJob継承する。WMI生成やbreakawayは利用しない。
