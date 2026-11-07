# M21 WSL Docker 固定bridge

既存 Ubuntu-24.04 /usr/bin/python3（3.12）と/usr/bin/dockerだけを使用する。新CLI取得、engine/service追加、任意remoteexec、任意argv/env/path辞書を提供しない。正式runの資格はLinux bridge単独の試験から導かない。

## 固定入口とwire

必須引数は --run-directory、--owner（canonical UUID）、--run（canonical UUID）、--nonce（32hex）、--input-commit（40hex）。run-directoryは既知CドライブTEMPの /mnt/c/Users/kenta/AppData/Local/Temp/m21-windows-*/bridge に限る。全componentをdirfd＋O_NOFOLLOWで開き、親owner.json exact {owner,nonce}、子owner.json exact {owner,nonce,run,purpose:m21-docker-bridge}、子directoryのfreshnessを照合する。Parentがowned子directoryとowner.jsonを新規作成する。既存親schemaは変えない。

stdinのみ4byte big-endian長＋UTF-8 JSON（1〜16384byte）。重複JSON key、未知key、未知operation、再送request、null/0/bool境界を拒否する。stdoutにはresponseを出さず、唯一のresponseはowned directoryのCREATE_NEW pending→非上書きhardlink公開→directory fsync→exact read対象file。秘密はreceipt/argv/exceptionへ書かない。

bootstrapはready.json（queryなしLinux t0/metadata）→init（type/owner/run/global_offset_ms/work_offset_ms）→W内固定preflight3件→initialized.json→bind（type/owner/run/descriptor）→bound.json。preflightはdaemon ID、mysql:8.0 image ID、valkey/valkey:8-alpine image IDのread-only各1件/3秒、retry/pullなし。descriptorはdaemon_id（有界非空実ID）とmysql_image_id/valkey_image_id（sha256 ID）。Parentが同descriptorをpinしてbindする。

全file共通metadataはversion=1、owner/run/nonce、inputCommit、bridgeSourceSha256、pythonSha256、dockerSha256、t0_ns、boot_id、pid_namespace（文字列）、socket_dev/socket_ino。inputCommitとbridgeSourceSha256を混同しない。Parentはmanifestのexact値と照合し、古いreceiptを採用しない。

request keysはtype/owner/run/request_id/phase/operation/resource/deadline_offset_ms。phaseはwork/cleanup、roleはdaemon/mysql/valkey、request_idは1から単調。container-create/mysqlのみsecrets exact {mysql_root_password,mysql_password}を追加する。秘密はframed stdin→Linuxメモリ→clearしたDocker子envのMYSQL_ROOT_PASSWORD/MYSQL_PASSWORDだけへ渡す。argvは --env KEY だけ。MYSQL_DATABASE=m21_<runhex>とMYSQL_USER=m21は固定導出する。親env/credentials/context/HOME/proxy/DOCKER_HOSTを読まない。

operationはdaemon-info、volume-create/inspect/remove/absent、container-create/inspect/start/stop/remove/absentだけ。owned名はm21-<runhex>-<role>。Dockerは固定host unix:///var/run/docker.sock、owned新規空config directoryを使用。create実行はrootの別GOまでParent側から送信しない。既存daemon/imageのread-only資格とmutation資格は別である。

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
