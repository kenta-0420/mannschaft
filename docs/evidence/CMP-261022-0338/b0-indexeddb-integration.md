# CMP-261022-0338 気づき箱IndexedDB取込確認

## 実行条件

新しいPlaywright Chromium非永続contextから、専有loopback origin `http://127.0.0.1:55380` で生成済み `docs/prototypes/beta-inventory-board.html` を開いた。対象のIndexedDB `mannschaft-beta-inventory-board` はboardを開く前には存在せず、ユーザー既存profileや本陣のブラウザDBには接続していない。overlay runIdは `october-first-wave-20261026`、sourceHeadは `03be10c6d87ceeec29d4b09f0bac4db0d391c514`。

初回表示後に5件・ID一意を確認し、同じcontextでページをreloadして再度読み取った。2回目も5件・ID一意で、ID集合は1回目と一致した。DBの読取とUI操作以外に手入力保存はしていない。server/context/browserは終了済み。

## UI表示

「気づき箱」でB0を選ぶと `organization-manage` と `auth` の2件、B2を選ぶと `moderation-incident` の3件を確認。両stageの合計5件はIndexedDBからの表示と一致した。

- [B0の気づき箱](b0-insights-stage-b0.png)
- [B2の気づき箱](b0-insights-stage-b2.png)
- 機械可読な行・二度目の件数・cleanup記録: [b0-indexeddb-integration.json](b0-indexeddb-integration.json)

画像は `view_image` で目視確認済み。カードには登録元、persona、journeyId、タイトル、実行証拠path、screenshotPathが表示される。feature stageに応じたfilterのため、B0とB2の2画面へ分かれる。

## 標準importの変換と保持範囲

既存 `beta-inventory-board.html` の `syncAlicizationInsights` は `ALI-${sanitize(runId)}-${sanitize(source insight id)}` を安定IDとして使い、既存IDにない行だけIndexedDBへupsertする。このrunでは1件ずつの行IDが5件作られ、再度開いても増えなかった。

source overlay由来の `personaId` / `personaArchetype` / `journeyId` / `runId` / `evidencePath` / `screenshotPath` は各rowで一致した。標準表示向けの `status` は `inbox`、`registrationSource` は `alicization` に変換される。`trusted:false` と `status:observed-unverified`、`sourceHead`、`originalRunId` はoverlayに残るが、既存import後のIndexedDB rowには個別fieldとして保持されない。`screenshotPath` は文字列として保持され、PNG自体をBlobとして自動取込しない。これらは既存UIの変換仕様であり、この作業ではHTML/validatorを変更していない。

既存のmain overlay 4件は生成前に [existing-overlay-archive.json](existing-overlay-archive.json) へmain commit/blob/SHA付きで保存済み。新5件は標準generator用 `.b0-local/run-CMP-261022-0338-residents-20261026.json` から生成したdraftで、PRに含める場合は標準閲覧時の通常importにより同じIndexedDB箱へ同期される。旧overlayの原本退避と新runの入力は別ファイルで保つ。

この操作はB0 resident runnerやcoverage検証ではない。IndexedDB取込receiptは同じ5件overlayを使った先行draft確認であり、task completion後のgenerator再実行は [overlay-validation.md](overlay-validation.md) に記録した。Tの最新実機4件とCIはhead e202で成功したが、住民探索sourceは旧head 03beのまま。CSP PR #4127の完了を主張せず、CSPのtask row/完了JSONも変更していない。
