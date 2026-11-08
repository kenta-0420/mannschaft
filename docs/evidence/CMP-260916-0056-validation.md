# CMP-260916-0056 検証記録

実装は[PR #4403](https://github.com/kenta-0420/mannschaft/pull/4403)、最終headは `4c07f4250917a979862fc54299d809e10a2d9647`。テスト3ファイル・README・開発文書の計5ファイル（303行追加、12行削除）を変更した。製品UI/API/認可/schema/利用者データは不変であり、実機・探索はtest-onlyのため対象外。

## 受け入れ条件と試験

- AC-1: `realRegexReturnsControlAtTimeout` 2件と `interruptedRegexWorkerActuallyStops` 2件で、実regex中の制御復帰と割込みworker終了を別々に確認する。worker回収の根拠は有限finally release/joinのassertionであり、remote全PIDを実測したという意味ではない。
- AC-2: `eachMatcherRejectsInterruptedScan` 11件と `interruptedEmptyDirectoryDoesNotReturnPartialSuccess` 2件で、実matcherとファイル境界の割込み応答、interrupt flag保持、キャンセルを空・部分成功へ変えないことを確認する。
- AC-3: `normalScanRetainsDetection` 2件、`ioFailureIsNotReturnedAsEmptyResult` 2件と既存番人・走査ロジック試験で、正常検出とIO失敗伝播を維持する。既存Flywayの `scanWithinTimeout` と割込み可能入力を再用し、pattern・freeze・30秒上限を維持した。

## CIと検分

[正式RED](https://github.com/kenta-0420/mannschaft/actions/runs/42461648479)（`e7be535d18efc8ea488cfe449cd90dcdfed4459d`）は意図したassertion 17件が失敗、対照4件が成功し、error/skipは0。21件のclassname/nameと本文・期待予算をGREENと照合した。

[component GREEN](https://github.com/kenta-0420/mannschaft/actions/runs/42476347006)（`67818acef465eabe434a306a730ad085c95ba07d`）は7論理family／10物理XML、99件が成功（21 + Raw番人2 + Rawロジック10 + Date番人3 + Dateロジック19 + Flyway番人3 + Flywayロジック41）。failure/error/skipは0。原XML、公式6ZIPのdigest、対応する6jobの原ログは独立照合済み。既存フル走査の測定はRawが検出87件・1533ms、Dateが4340件・2810msで、これはcomponentの測定であり、最終headの再計測ではない。

[最終headのBackend CI](https://github.com/kenta-0420/mannschaft/actions/runs/42496576449)はattempt 1で全12ジョブ成功。番人3ファイルは上記componentと同一blob。実装PRのマージ直前は最新23 checkが22 success／1 skipped、commit statusは0件。PR本文に記録された独立検分は指摘0件。

完了証拠JSONのCI欄は、実装PR最終headと一致する最終Backend CIだけを参照する。ローカルの重複Gradle実行はCIで同等検証済みのため省略した。構造・参照検査CLIと、GitHub上の実装PR／CI照合は別々に確認する。
