# M21固定住民helper

**WIP・受入条件未達。** Windowsでstateのatomic renameがEPERMになることを実測し、sticky failed保存前にhelperがexit1となる。全29件の直近試験は21pass/8fail。診断版の必須欠落/UUID不正2件もrename EPERMで失敗している。OS拒否の具体的な由来は未確定で、retryや期限延長は追加していない。正式Runで採用可能な完成版ではない。

`scripts/m21-resident-helper.cjs` はWindowsの同じ信頼済み自己runに限る固定IPC補助である。ランダムpipe名と自称residentIdはOS本人認証ではない。任意コード、module、shell、URL、ファイル取得を受け付けない。製品サービスや汎用brokerではない。

Java hostがprivate環境変数 `M21_RUN_DIRECTORY`（自己Temp直下の `m21-UUID`）と `M21_ACTORS_JSON`（system/admin-de/memberのActor 3件だけ）を渡す。hostの `resident-helper-owner.json` を最大10秒待ち、pid/runId/executableを自己値と照合し、creationを変更せずreadyへ載せる。helper自身は親processの世代や住民の本人を検証済みと主張しない。

pipeはUTF8 NDJSON。各frameは改行を除き最大16,384バイト、接続の受渡し期限は固定10秒。要求のキーは `op,runId,actorCode,requestId,residentId` のみ。requestId/residentIdは小文字UUIDで、claim-actorの応答 `op=actor` だけにActorの資格情報を含める。同じ接続で同じ識別子のack-deliveredを完全受信した後にservedCodesをatomic保存し、`op=delivered` を返す。住民はこの応答を待って正常closeできる。ack前切断は失敗になる。ack直後half-closeではserved1/failed=falseでもdelivered応答を観測できない試験失敗があり、期待値は保留している。Node 24.15.0同梱libuv 1.51.0の[Windows実装](https://github.com/nodejs/node/blob/v24.15.0/deps/uv/src/win/pipe.c#L2129-L2229)はshutdown後の読取に50ms EOF timerを持つため、TCPと同じhalf-close応答保証を仮定しない。

各code/request/residentは一度限り。再接続再配布、未知code/run、null/欠落/余分キー、不正UTF8/JSON、上限超過、trailing garbage、途中切断、期限超過はsticky failedで全体nonGreenにする。秘密はstdout/argv/永続JSONへ出さず、例外ログは定型コードだけ。ready/stateは最大16KiBの `.next` からatomic replaceする。helper配布済みは探索完了の証明ではない。

有限契約試験: `node --test scripts/m21-resident-helper.test.mjs`。新規自己Temp、自己spawn子、named pipeだけを使い、子の実終了・両stream EOF・秘密非露出・Temp撤去まで確認する。既存Harness CIは試験ファイルの明示列挙であり、この試験は現在自動実行対象に入っていない。DB/GUI/正式実機/3独立profile探索はこの試験の範囲外。
