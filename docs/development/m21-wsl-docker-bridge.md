# M21 WSL Docker 固定bridge

既存 Ubuntu-24.04 /usr/bin/python3（3.12）と/usr/bin/dockerだけを使用する。新CLI取得、engine/service追加、任意remoteexec、任意argv/env/path辞書を提供しない。正式runの資格はLinux bridge単独の試験から導かない。

## 固定入口とwire

必須引数は --run-directory、--owner（canonical UUID）、--run（canonical UUID）、--nonce（32hex）、--input-commit（40hex）。run-directoryは既知CドライブTEMPの /mnt/c/Users/kenta/AppData/Local/Temp/m21-windows-*/bridge に限る。全componentをdirfd＋O_NOFOLLOWで開き、親owner.json exact {owner,nonce}、子owner.json exact {owner,nonce,run,purpose:m21-docker-bridge}、子directoryのfreshnessを照合する。Parentがowned子directoryとowner.jsonを新規作成する。既存親schemaは変えない。

stdinのみ4byte big-endian長＋UTF-8 JSON（1〜16384byte）。重複JSON key、未知key、未知operation、再送request、null/0/bool境界を拒否する。stdoutにはresponseを出さず、唯一のresponseはowned directoryのCREATE_NEW pending→非上書きhardlink公開→directory fsync→exact read対象file。秘密はreceipt/argv/exceptionへ書かない。

bootstrapはready.json（queryなしLinux t0/metadata）→init（type/owner/run/global_offset_ms/work_offset_ms）→W内固定preflight3件→initialized.json→bind（type/owner/run/descriptor）→bound.json。preflightはdaemon ID、mysql:8.0 image ID、valkey/valkey:8-alpine image IDのread-only各1件/3秒、retry/pullなし。descriptorはdaemon_id（有界非空実ID）とmysql_image_id/valkey_image_id（sha256 ID）。Parentが同descriptorをpinしてbindする。

全file共通metadataはversion=1、owner/run/nonce、inputCommit、bridgeSourceSha256、pythonSha256、dockerSha256、t0_ns、boot_id、pid_namespace（文字列）、socket_dev/socket_ino。inputCommitとbridgeSourceSha256を混同しない。Parentはmanifestのexact値と照合し、古いreceiptを採用しない。

request keysはtype/owner/run/request_id/phase/operation/resource/deadline_offset_ms。phaseはwork/cleanup、roleはdaemon/mysql/valkey、request_idは1から単調。container-create/mysqlのみsecrets exact {mysql_root_password,mysql_password}を追加する。秘密はframed stdin→Linuxメモリ→clearしたDocker子envのMYSQL_ROOT_PASSWORD/MYSQL_PASSWORDだけへ渡す。argvは --env KEY だけ。MYSQL_DATABASE=m21_<runhex>とMYSQL_USER=m21は固定導出する。親env/credentials/context/HOME/proxy/DOCKER_HOSTを読まない。

operationはdaemon-info、volume-create/inspect/remove、container-create/inspect/start/stop/remove、resources-absentだけ。owned名はm21-<runhex>-<role>。Dockerは固定host unix:///var/run/docker.sock、owned新規空config directoryを使用。create実行はrootの別GOまでParent側から送信しない。既存daemon/imageのread-only資格とmutation資格は別である。

## deadlineと回収

Parent ready受領QPC p0を固定し、送信準備時elapsed＋局所capをG/Wへclamp、整数ms短縮丸めしたt0からの絶対offsetを渡す。受領時now＋remainingへresetしない。到着期限切れ/G0/W0はspawn0。W後work0、cleanup最大14、同時CLI1。G55分/R5分/W50分、R267＋余裕33秒は維持する。preflight3件はW内でcleanup14件とは別。

要求専用watchdogはbridgeと別session、SIGCHLD既定、subreaper設定・確認後にCLIをforkする。CLI専用session/group成立ack、pidfd、pid/startticks/boot/nsを保持する。leaderはWNOWAITで観測して全signal判断完了まで未reap anchor。Popen.poll/wait/destructor、PID再検索、unknown PID停止を使わない。

leaderの一次子集合exitと一次各EOFを回収前に固定する。孫が両pipeを閉じて生存していても一次greenにしない。必要時owned groupへ固定SIGKILL最大1回、その後PGIDへのsignalを禁止し、自分の子/養子をwaitpid(-1,WNOHANG)→ECHILDまで回収する。actual exit/reap1秒、stdout/stderr各EOF0.5秒をglobal deadlineへclampする。一次失敗は後続回収でgreenへ変更しない。clock discontinuityはnonGreenへ倒す。

ParentEOF/partialframe/初期handshake期限/G満了は新spawnを止める。active control閉鎖後もwatchdogは自分の回収を継続する。watchdogはWindows stdioを継承保持せず、非秘密request receiptを独立公開する。bridgeはwatchdog自身の実waitを別observed receiptへ記録する。

request-00001.jsonのlinuxはleader_identity/cli_exit/children_reaped/reaped_pids、各EOF、write_closed、force_count、reason、primary_children_exited/primary_stdout_eof/primary_stderr_eof、green。request-00001-observed.jsonはwatchdog_exited/exit、採用result、greenを別に持つ。preflight-1〜3は同じ分離方式。receiptだけでwatchdog自身/Windows wsl.exeの実exitやEOFを証明しない。

## daemon資源と判定

socket/boot/ns変更を拒否し、daemon-infoはpin済み実IDとexact比較する。container/volumeのowner/run/purpose3label、image ID、exact owned volume mount、単一127.0.0.1専用portを確認する。portはdaemon自動採番（3306/6379のloopback mapping）をinspectで1回freezeし、変更を拒否する。stop/rm/startは認定済みimmutable container ID、volume rmは認定済みowned名に限る。CLI exit0と資源不存在は別結果。非0、daemon変更、label不一致、残存、未確認はnonGreen/retainedである。

bridge-exit.jsonはLinux側の有限終端結果であり、Windows実exit/各EOFはfalseのままParentが別観測する。bridge終了2秒＋各EOF0.5秒は既存ledger/FS20秒phaseへ含める。余裕33秒をretryや追加queryへ転用しない。

## 試練と残存資格

標準実行は python3 scripts/test_m21_wsl_docker_bridge.py。Linux実子試練は即exit/hang/孫pipe保持/孫両pipe閉じ生存/controlEOF、G0fork0、常駐idle/partial/activeParentEOF、init期限。Docker外部境界は固定stub契約でsecret非argv/envclear、daemon変更/nonzero不存在拒否を検査する。WindowsではLinux試験のskipを明示し、greenに数えない。WSL NTFS専用fixtureはproduction同guard/CREATE_NEW/atomic公開/fsync/readを自己所有小directoryで1回測る。

旧checkpoint18866の孫生存greenは欠陥と判定し、greenfalseを要求する実子red2件を先に確認して修正した。現sourceのgreenは新SHAを固定した有限suite結果だけで判断する。Windows relay強制終了、実Docker操作、正式App/Java/FE/Chromium、55分formalrun、既存14回帰は別資格であり、本書だけで取得済みとしない。

最終不存在はR14を維持するため、volume名をcontainer名＋-dataと分離する。resources-absentはroleごと1CLI `docker inspect --format M21_PRESENT <containerName> <volumeName>` に固定し、存在時のJSONを出さない。stdoutはexact LF 1byte（hex0a）、exit1、distinct2名それぞれのexact no-such-objectだけのstderrを暫定candidateとする。最後の同daemon-infoが成功して初めてconfirmed_absent_resourcesへ昇格する。candidateのabsentはfalseのまま、unknown errorはretained。

[Docker inspect公式仕様](https://docs.docker.com/reference/cli/docker/inspect/)は複数object/任意type照合を規定する。[Docker CLI一次source](https://raw.githubusercontent.com/docker/cli/master/cli/command/system/inspect.go)では各object typeをprobeして非skiperrorを返す。別名のどのtypeも不存在なら対象container/volumeも不存在、という判定はこれらからの推論であり、29系実binaryのexact stderr形と3秒capは別の限定read-only実測で確認する。他namespaceの同名存在時は保守的nonGreen。

resources-absentだけは想定CLI exit1のためlinux.greenはfalse。linux.physical_completed（一次子集合/各EOF＋回収済み）とexact absence candidateをobservedで採用する。他operationはexit0/greenを要求する。Windows保持relay終了試験は既存M21Child側担当が実施する。testfileの固定 --relay-fixture はproduction同guard/bridge-loopを使い、Docker境界だけ固定dummyへ置き換える。Linux process/EOFをmockに置換しない。


## 受け入れ条件と証拠の対応

| 条件 | 固定試練と実装 | 資格の限界 |
|---|---|---|
| B1 固定境界 | BoundaryTests、RemainingBoundaryTestsのframe/重複/上限とProtocolのexact keys/owner/run/単調ID | 任意argv/env/path設定APIなし |
| B2 子集合 | LinuxFiniteTests、LossFiniteTests、PublicReceiptFiniteTestsで実子/孫/EOF/reapを検証 | escapeはnonGreen retained。fixture所有者が自己通知済みの既知childだけを回収 |
| B3 世代 | setsid ACK/pidfd/WNOWAIT anchorを保持し、全signal判断後にwaitpid/ECHILDを観測 | PID再検索と他PID停止なし |
| B4 喪失と寿命 | ResidentFiniteTestsのidle/partial/activeEOF/init期限とLossFiniteTestsの実bridge SIGKILL | Windows held wslのactual/各EOFはFormal担当が別検証 |
| B5 期限 | ready-offset/expired/G0fork0/W後work拒否/clock異常spawn0、全waitのG clamp | OS fatal/suspendの数学的な期限保証はしない |
| B6 予算 | cleanup14上限/同時1、body→force1→reap1秒→各EOF0.5秒 | 既存daemon正常infoの3秒資格は未取得。予算延長と症状隠しretryなし |
| B7 秘密 | FixedDockerTests、実Linux env sentinel、公開16KiB/private key非掲載/secret非到達 | secret envは固定2key。既存env/credential/context読取0 |
| B8 daemon | InspectContractTestsの作成前後/loopback freezeとdaemon変更/非0/不存在candidate拒否、固定readonly syntax | 同daemon前後falseはnonGreenを保持。mutation/実資源不存在は未取得 |

公開receiptはbody elapsed/primary reason、CLI actual exit、子集合reap、各EOF時刻と状態を別fieldへ保存する。Linux private IPC64KiBと公開frame/receipt16KiBは別上限である。私有fixtureの1read=EOF誤仮定はbounded read-to-EOFへ修正し、公開上限は維持した。

限定read-only診断run2067aa47-9f31-43c8-9392-50d6e4406cc7、nonce a49bdc631aa54abcadad848faa239528では、CLI SHA256 2fc3b56f978ff6951845be1e4ce352256c0ce126eeda8b84d5713009da3771ca、stdout hex0a、exit1、exact lowercase error2行、inspect834ms、子集合reap/両EOFを確認した。[v29.4.1 TemplateInspector.Flush](https://raw.githubusercontent.com/docker/cli/v29.4.1/cli/command/inspect/inspector.go)の0件時改行出力と一致する。stdout.stripによる任意空白への一般化はしない。

同診断のdaemon-infoは前3029ms/後2578ms、same_daemon_before_after=falseで全体nonGreen。各infoの個別exit/physical/IDhashを保存しなかったため原因を断定しない。次の正規packetでは各CLIの結果を独立保存する。追加再測定/cap緩和/pull/mutationは行わない。自己所有NTFS receiptのO_NOFOLLOW/CREATE_NEW/hardlink公開/directory fsync/exact read成功はdaemonやWindows資格へ流用しない。

診断時のC空き約4GB/他TestExecutor稼働環境では正式runへGOしないというroot判断を保持する。bridge単体greenから55分formalrun、App/Java/FE/Chromium、実Docker mutation、既存14回帰の資格は導かない。

私有IPC送信とParent異常時watchdog waitもmin(G、request本体deadline＋既存exit/EOF2秒、当該局所上限)へclampする。既存枠の消費後に送信0.5秒/終了wait2秒を加算しない。

CLI metadata引数は固定5flag/value組だけを受ける。未知flag、重複、略記は静的exit78で拒否し、渡された値をstderrへ反映しない。in-memory argv境界sentinelで非掲載とrun認定未到達を検査する。


## 正規否認とfork後fault

valid schema/owner/単調request-id認定後はslot/idを先に消費する。期限/phase admission、未認定所有、daemon未確認のspawn0は固定code admission-denied/ownership-unverified/daemon-unconfirmed、not_spawned=true/green=false/childproof=null/linux=null/watchdog exitとresult=nullを返し、他roleのRを継続する。actual exit0/EOFtrueは捏造しない。frame/schema/owner/未知operation/replayとG満了はterminalを維持する。

info開始時はdaemon-confirmedと古い認定cacheを、inspect開始時は該当role認定をinvalidateする。失敗したfresh queryの後にstartupの古cacheをwriteへ使わない。readonly inspectと最終infoは固定枠内で継続する。container createは[公式CLI仕様](https://docs.docker.com/reference/cli/docker/container/create/)の既定pull=missingを使わず、固定 --pull=never としてpin後のimage消失でも取得しない。

fork後のpidfd/identity/started receipt/観測失敗はprimary_failure_stageへ固定stageを保存し、finallyで自分の未reap子の停止→waitpid/ECHILD→各EOFを別attemptする。ACK不明時に推定PGIDへsignalしない。pidfd不在でも自分のfork子anchorがある場合だけそのPIDを停止する。anchor喪失はgroup停止せずretained/nonGreen。brokerのwatchdog pidfd取得不能時はcontrol EOFを渡し、独立watchdogの回収と親のown child waitを既存枠内でattemptする。watchdogだけを先にkillしてCLIを放棄しない。

Wのbootstrap3read CLI、Wの固定12 create/inspect/start request、Rの14 requestslotは別枠である。通常wireのrequest-idは全runで1〜26へ単調に進み、G/W起点を変えない。R denyも14slotを消費する。cleanup_cli_spawn_upper_boundはwatchdog1本につきCLI最大1本という上界で、実CLI起動証拠は各started/childproofに分ける。WorkRecoveryBudgetTestsはW12→R14→R15拒否を固定する。

.github/workflows/harness-ci.ymlの既存jobへ追加するstepは無引数 python3 scripts/test_m21_wsl_docker_bridge.py を実行する。real-readonly専用opt-in引数は渡さず、実daemon試験はskip、Linux dummy suiteのみとする。新workflow/job/servicesは追加しない。


## Valkey endpointの同一性gate

既存work/valkey/container-inspectでfresh owned CID/owner/run/purpose/image/running=trueと単一127.0.0.1 published portを認定し、同request絶対body deadlineの残量で固定INFO serverを1回付ける。新operation/request/CLI/childは0。W12 CLI/R14/G55分R5分W50分/3秒capを維持し、CLI後にnow+3秒へresetしない。prestart/cleanup/MySQL inspectのsocket処理は0。

bridgeは認定済みportの固定IPv4 loopbackへnonblocking接続し、固定RESP INFO serverだけを送る。入力endpoint/command/envの設定口はない。全RESP frame16KiB以下、bulk length/CRLFを完全照合し、単一lowercase40hex run_idだけを抽出する。取得済み超過/直後queued extraは拒否しfinallyでcloseする。将来の任意遅延extra不存在は主張しない。INFO本文/OS情報/秘密は公開receiptへ出さない。

成功時のobserved.resultは既存ownership/identity/loopback_portへrun_idとsocket_proofを加える。socket_proofはtransport=owned-loopback-info、response_complete/socket_closed/within_deadline/green/reason/response_bytes/response_completed_ns/observed_ns/deadline_ns、およびcli_spawned=false/childproof=nullを持つ。既存inspect CLIのLinux proofは別保持。socket未作成のclosedはnull。socket失敗はobserved.green=false/run_id欠落としてRを継続し、古run_id/socketproofを再利用しない。

within_deadlineは同じ絶対期限内の完全受信を確認できた証明である。timeoutでは未証明のためfalse、reason=timeoutと実観測時刻/absolute期限を別記する。観測時計がdeadline前でも、短縮側timeoutをdeadline到達実測へ書き換えない。closeまでの全処理が枠外ならgreenにしない。

Windows側はresources-readyへexpectedRunIdとLinux側socket_proofを保持し、CP/FE準備後のJava spawn直前に同advertised portへ固定INFOを1回実行してrun_idを完全一致させる。誤port/別run_id/不足ではAppを起動しない。この比較はFormal側の責務である。

期限境界の完了/cancel raceはactiveなし・直前の実完了exact ID・初回だけ別request-N-late-cancel.jsonへ固定late-cancel-ackを返す。元receiptは不変、green=false/late_cancel=true/no_signal=true/signal_count=0/cli_spawned=false/childproof=null/deadline_reset=falseを記録しRを継続する。未知/古ID/新request/replay/2回目/別activeへの古cancelはterminal。追加signal/CLI/retryと期限resetは0。

先行red5件は新しい必須gateの未実装関数を検出した試練である。parserとowned loopback dummy socketで不足/重複/16KiB境界/partial/extra/timeout/closeを検査する。既存continuationへCLI green/Socket非Green、完了前後cancel、古cachewrite拒否と他role R継続を実子で結合する。実App/実daemon試験/mutation/サービス導入は0。9d checkpointのB4資格は新sourceへ流用しない。

最終inspectはrunning=trueだけでなく、同じowned Valkey CIDへの自隊container-start成功後のwork inspectに限る。prestartで予期せずrunningでもINFOを追加せず、daemon再確認/新create時はこの認定を失効する。run_idはcacheしない。

bridge-exitの固定診断はterminal_phase/terminal_code/phase_started_ns/phase_deadline_ns/terminal_nsを持つ。bind-read→bind-validation→bound-publishを区別し、schemaとdescriptor-bindは固定codeで分離する。readのdeadlineと記録値は同一変数、全時刻はLinux CLOCK_MONOTONICであり、期限を延長しない。失敗code/時刻を回収前に固定し、後続回収で上書きしない。Rejectedの未知値はother-rejected、OS/値/Unicode例外は固定typeのみで、例外本文・受信値・秘密を公開しない。既存reason=bridge-terminalは維持する。

## readonly診断fixtureの有限観測

専用入口は --real-readonly-absence-once --input-commit <40hex>。input-commitは供給値の形式確認と記録だけを行い、実HEAD一致は実行前のroot照合で保証する。実行GO前に非起動のwsl --list --runningでUbuntu-24.04の既稼働を確認し、停止中なら起動しない。Docker CLIはinfo→resources-absent→infoの最大3件直列、固定Unix socketと新規所有空config、PATH/LANGだけの子envを維持する。

各onceは同じbeganからbody3秒/G5秒を固定し、送信は既存send_private、受信はnonblocking/selectの64KiB上限で行う。完全JSON・result EOF・supervisor actual exit/reap・CLI子集合reapを別記録し、いずれか未確認なら次CLIを起動しない。body期限でcontrol EOFを送り、既存superviseによる回収を同じG内で観測する。期限後のblocking read/waitや成功への上書きは行わない。G後も不明な子はunknownとして所有Tempを保持し、supervisorだけを停止してCLI回収済みとは扱わない。全runのhard15秒保証は主張しない。

packetはowner/run/nonce、供給inputcommit、物理source/test SHA、実argv、configpath/socket、各CLIのreason/actualexit/EOF/reapと出力len/hashを保存し、raw本文を公開しない。dummy試練はpartial JSONと完全JSON後のpipe保持/未終了を実forkで確認する。後者の試験自身による既知dummyの別有限cleanupは診断G資格と分離し、cleanup成功で診断nonGreenを変更しない。実daemon診断は専用GOまで実行しない。

readonly診断のfork子ではcontrol read FDの所有をsuperviseへ移管し、supervise自身のfinallyがcloseする。呼出側は二重closeしない。結果FDはsend_privateが閉じないため子がcloseするが、その失敗も非0へ保持する。child branchは外側finallyのos._exitで必ず終端し、例外・cleanup失敗・関数returnがunittestや親packet公開finallyへ戻ることを禁止する。packet作成は親だけが行い、transport非Greenなら所有Tempを保持する。実child branchの試練はcontrolを閉じるfake superviseと結果FDclose faultを使い、actual0／非0・完全JSON・EOF・reapとfallthrough sentinel不在を個別に確認する。旧実診断で子が公開したCLI数0のpacketは親全体の証拠に採用しない。
