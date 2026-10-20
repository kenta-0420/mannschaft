# 本人chat・監査の非同期収集失敗と安全な公開文言

## 先行red

統合head `01055e993a6782c8db826fa63993e065e191d406` の[公式BE run39026031404](https://github.com/kenta-0420/mannschaft/actions/runs/39026031404) shard4で、ChatAuditExportFailureITの4件が失敗した。JUnitは4件・failure4・error0・skip0、SHA256 `c06517c18061ea9ee598b2ce42c2e43d31696d90d843a89658acad1268d974e0`。FAILEDとS3 key nullは通過した後、旧テストのカテゴリ名containsが固定文言「エクスポート処理に失敗しました」に一致しなかった。後続upload neverは未到達であり、失敗runの合格と扱わない。

ZIP統合で例外本文を公開DBへ保存しない設計へ変更した一方、旧ITの期待が未追従だった。[元PR3933](https://github.com/kenta-0420/mannschaft/pull/3933)の契約は取得欠落・JSON変換失敗を空の成功ZIPにせずFAILEDにすることで、例外本文を利用者へ公開する要求ではない。製品の固定文言・TX境界は維持する。

## 経路を特定する番人

実PersonalDataCollectorの対象OWNER・単一category・同一prefetched引数だけをspyで観測する。callRealMethodで実収集を実行し、実際に投げられた同じ例外を記録して再throwする。内部wrapperの固定カテゴリ文言を完全一致で検査し、欠落はcause無し、直列化失敗はJackson causeを確認する。別スレッド・実TX内の実行も確認し、別箇所の失敗を成功と誤認しない。収集結果やrepo/outboxのstubは加えない。

公開DBerrorMessageの完全一致、元FAILED・S3 key null・storage upload neverを保持する。実非同期job-pool、実Spring/実MySQL、待機上限20秒、既存Docker可否skip条件を変更しない。

## 検証

Java21・offline・既存turnstile・1 worker/1 forkで、動的専用MySQLを使う実Spring試験4件が成功し、failure/error/skipはすべて0。JUnit SHA256は `a78c80d20e2db40fcebee6ba80a09d427866b8bac5fc3cad321a0e904d1ac264`。全12934 sourceを実行前後で検証し、外部R2境界以外は実処理。専用Testcontainers残存0、既存runtimeへのDML・stop・flushは0。壁時計1040.3秒にはturnstile待機を含み、Gradle実行は14分10秒。

実行したWindows作業候補はCRLFで、Git保存のLF形式とは改行のみが異なる。Git clean正規化一致を確認した。LF形式で再compileTestJavaを実行し、全main/test classの同一性を照合中。

この変更の製品src/main・resources・build設定差分は0。比較元01055のcanonical Jarと元source証拠は不変で保存する。後続の最新main取り込みは別の製品変更を含むため、最終runtimeの同一性をこの結果だけで代替しない。新テストの正式CIは未実施。
