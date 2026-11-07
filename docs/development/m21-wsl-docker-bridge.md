# M21 WSL Docker bridge（基盤slice）

本sliceは固定境界とLinux要求専用watchdogの基盤である。正式M21入口、実Docker、55/5/50正式runの資格は取得していない。`m21-wsl-docker-bridge.py` を直接起動すると固定診断 `M21_BRIDGE_NOT_ADMITTED` とexit78で終わり、CLIを起動しない。

## 接続前に保持する契約

Windowsは既存M21Childのstdout/stderr採用・実exit・各EOFを使用し、W中にbridgeを1本だけ保持する。stdinのみ4byte big-endian長＋UTF-8 JSON（最大16384byte）を使う。Linuxからの唯一の応答はowned run直下のatomic receipt fileとし、stdoutへ重複responseを出さない。

readyのLinux monotonic t0とParent受領QPC p0を固定する。initはt0からのglobal/work offset、requestは送信準備時elapsed＋局所capをglobal上限へclampしたdeadline_offset_msを渡す。整数msへ短縮丸めし、Linuxで到着時刻＋remainingへresetしない。到着時期限切れはspawn0。G55分/R5分/W50分、R267＋余裕33秒は変更しない。

requestのexact keysはtype/owner/run/request_id/operation/resource/deadline_offset_ms。ownerはこの戦役root UUID、runはcanonical UUID、request_idは1から単調。任意argv/env/path/JSON設定辞書、未知key、重複JSON keyを拒否する。許可operationはdaemon-info、container-inspect/stop/remove/absent、volume-inspect/remove/absent。roleはdaemon/mysql/valkeyのみ、所有名はm21-<run UUIDのhyphen除去>-<role>から計算する。cleanup最大14、同時要求1は接続側の追加責務である。create/秘密transportは未接続で許可しない。

既存ParentのRunDirectoryはWindows TEMP直下m21-*をowner.json/nonceで認定する。管理WT固定pathを正式契約に使わない。canonical /mnt/c対応、owner/nonce、no-reparse、source SHA、fresh receipt identityを同じ正規入口へ接続するまではmainをfailclosedに保つ。

## Linux回収

superviseはwireから呼べない内部機構であり、fork済み要求専用watchdogだけが呼ぶ。watchdogは別session、SIGCHLD既定、PR_SET_CHILD_SUBREAPER設定・確認をCLI forkより先に行う。CLIは専用session/group、pidfdと/proc startticks/boot-id/pid-namespaceを保持する。CLIのsetsid成立ackがない場合に古いPGIDへsignalしない。

leaderはwaitid WNOWAITで観測し、全group signal判断完了まで未reap anchorに保つ。Popen.poll/wait/destructorは使わない。groupへの固定SIGKILLは最大1回、以後そのPGIDへsignalしない。自分の子と養子をwaitpid(-1,WNOHANG)で回収し、ECHILDで子集合回収を確定する。戻り0を完了としない。actual exit/reap1秒、stdout/stderr各EOF0.5秒を独立に観測し、未確認はgreenへ変換しない。control EOFでも既に固定した回収を放棄しない。

receiptはCLI exit、子集合reap、各EOF、書込close、force_count、reasonを分離する。watchdog receipt自体はwatchdog自身の実exit、Windows wsl.exeの実exit/EOF、daemon資源不存在を証明しない。group外escapeの一般的回収は提供しない。

## 検証と未取得資格

境界試験は実装不存在redを先に確認してから4件green。Linux有限fixtureは即exit、hang、pipeを閉じる孫、pipe保持孫、bridge制御EOFを各1回実行する設計。Windows上のskipはLinux成功に数えない。fixtureは既知の自隊子以外を停止しない。

未接続項目は常駐bridgeのidle/partial frame/full output/G終端、owned run認定とready/receipt公開、watchdog専用fd閉鎖・bridgeからの実wait、固定Docker argv/env/config/socket/daemon同一性、exact label/image/loopback照合、sentinel秘密transport、既存14回帰、Windows relay喪失実測である。B1〜B8全体はnonGreen。新download/install/cachecopy/engine/service、安全無効化、既存credential/env/context読取、Docker mutationは行わない。

bridge終了2秒＋各EOF0.5秒は既存ledger/FS20秒phase内へ含める必要がある。これは設計値であり、この基盤sliceで実測証明していない。余裕33秒をretryや追加queryへ転用しない。
