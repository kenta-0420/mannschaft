# CMP-261021-0717 回帰 red/green 記録

## red6

source `9f467cbd573f3e98134e21372a2799b579f18ad6`。`ShiftBudgetConsumptionCancelRaceIT` の3 test（`整合バッチと取消リスナーが競合しても二重減算しない()`、`逐次重複取消と取消対象外は無作用()`、`減算途中失敗時は消化更新も全件巻き戻る()`）を実行。並行取消の業務assertionで期待30,000円・実際8,400円、errors0/skips0、原exit1。別シフト残額の期待を保てない回帰を捕捉。rootがXML/receiptを独立確認。

red6 raw evidence: `/home/kenta/verification-evidence/CMP-261021-0717-red6-20261105`。

## green2

修正source `dc977b6724958111c149140ab83f0094b433bb78`。日時guard3件、新競合IT3件、手動archive IT11件、整合batch IT3件、service UT7件、計27件成功。errors/failures/skips 0、原exit0、cleanup removed。no-arg `LocalDateTime.now()`を正準wallClockへ修正し、時間guard・baselineを緩和していない。rootがXML/receiptを独立確認。

green2 raw evidence: `/home/kenta/verification-evidence/CMP-261021-0717-green2-20261105`。

dc977 CI run `41888567126` は6 shardを含みcompleted/success。別途d9e21db `41958521115` もcompleted/success。最終実装head `89be0ad9292c04b63c8d17a44e520b0464f500eb` のCI `41979500331` はcompleted/success、6 shard+aggregate成功。旧日時guard失敗CI `41868260974` も履歴として保持する。全runをheadごとに区別し、別head結果を付け替えない。
