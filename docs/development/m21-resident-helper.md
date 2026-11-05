# M21固定住民helper

有限契約試験は38件すべて成功した。正式M21 Run・GUI・3独立profile探索の合格ではない。旧WIPではstate renameのEPERMを実測したが、reader共有方式やAVなどOS拒否の具体的由来までは確定していない。

`scripts/m21-resident-helper.cjs` はWindowsの同じ信頼済み自己runに限る固定IPC補助である。ランダムpipe名と自称residentIdはOS本人認証ではない。任意コード、module、shell、URL、ファイル取得を受け付けない。製品サービスや汎用brokerではない。

Java hostがprivate環境変数 `M21_RUN_DIRECTORY`（自己Temp直下の `m21-UUID`）と `M21_ACTORS_JSON`（system/admin-de/memberのActor 3件だけ）を渡す。hostの `resident-helper-owner.json` を最大10秒待ち、pid/runId/executableを自己値と照合し、creationを変更せずreadyへ載せる。helper自身は親processの世代や住民の本人を検証済みと主張しない。

pipeはUTF8 NDJSON。各frameは改行を除き最大16,384バイト、接続の受渡し期限は固定10秒。要求のキーは `op,runId,actorCode,requestId,residentId` のみ。requestId/residentIdは小文字UUIDで、claim-actorの応答 `op=actor` だけにActorの資格情報を含める。識別子が一致するack-deliveredを完全受信し、atomic保存と期限再確認が成功した時点をcommitとする。commit前にservedCodesを確定しない。通常full-duplex住民は `op=delivered` を受信してからcloseする。

WindowsのrenameだけをEPERM/EACCES/EBUSYの場合に限って短く再試行する。.nextのwriteは1回、同じ.nextのrenameだけを使い、targetのunlink/in-place/copy fallbackはしない。retry開始から固定250msと、全active接続の最早10秒絶対期限の小さい方をmonotonic clockで監視する。標準Atomics.waitで短く待ち、retry前/待機前後/ACK成功前に期限を再確認する。回復時も定型の回数/経過時間を記録する。write/硬いerror/永続拒否/期限枯渇で失敗保存もできなければ、実nonzero exitへ倒す。OS syscallや待機の実時間をJSから強制中断できるとは主張しない。

ACK commit後の正常peer closeと、その終端返信が届かないことを区別する。既知の閉鎖由来errorだけを終端返信の文脈で分離し、未知error・未完了ACK・追加garbage・別接続の失敗は引き続きsticky failedとする。Node 24.15.0同梱libuv 1.51.0の[Windows実装](https://github.com/nodejs/node/blob/v24.15.0/deps/uv/src/win/pipe.c#L2129-L2229)はshutdown後の読取に50ms EOF timerを持つ。client.end直後の返信をTCP同様に保証せず、commit済served/failed=falseと実closeを検査し、返信の観測有無は別記する。旧失敗の原因が50ms timerだけだったとは断定しない。

| 旧期待・AC | 現契約と試験 |
|---|---|
| R4: ACK前にservedを増やさない | 完全一致ACK＋atomic commit＋期限確認。正常3actorのdelivered返信は厳格に検査 |
| ACK直後endでもdelivered必着 | commit/failed=false/実closeを必須とし、返信到達は別観測。通常接続の返信必須は維持 |
| R5: Windows renameが直ちに失敗 | 対象3codeだけ同.nextを短い固定窓で再試行。決定的unitで回復/永続/硬error/非Windows/最早期限を検査 |
| timeout・保存不能 | 期限後の成功を拒否し、failed保存不能なら実exit1を検査。旧false metadataを生存成功へ読み替えない |
| snapshot原子性・failed sticky | 実reader並行の全snapshot、同run/pid/完全JSON、次接続/close後のsticky、実子終了/両EOF/自己Temp不存在を検査 |

各code/request/residentは一度限り。再接続再配布、未知code/run、null/欠落/余分キー、不正UTF8/JSON、上限超過、trailing garbage、途中切断、期限超過はsticky failedで全体nonGreenにする。秘密はstdout/argv/永続JSONへ出さず、例外ログは定型コードだけ。ready/stateは最大16KiBの `.next` からatomic replaceする。helper配布済みは探索完了の証明ではない。

有限契約試験: `node --test scripts/m21-resident-helper.test.mjs`。新規自己Temp、自己spawn子、named pipeだけを使い、子の実終了・両stream EOF・秘密非露出・Temp撤去まで確認する。既存Harness CIは試験ファイルの明示列挙であり、この試験は現在自動実行対象に入っていない。DB/GUI/正式実機/3独立profile探索はこの試験の範囲外。
