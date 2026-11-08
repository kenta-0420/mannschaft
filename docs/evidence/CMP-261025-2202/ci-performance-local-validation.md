# 既存MFA性能試験の局所確認

- 対象は既存 `com.mannschaft.app.auth.mfa.MfaBackupCodeLatencyIT` の4件。MFA本体・テスト・閾値・Gradle/CI設定は変更していない。
- 固定HEADは `371fcc7150ccdae8c76a2fb4886762649e6222af`。専用clean Linux sourceからverification-runで隔離し、共有Gradle turnstile経由、1forkで対象classだけを実行した。
- 実行は `gradlew test --tests com.mannschaft.app.auth.mfa.MfaBackupCodeLatencyIT --no-daemon -x archUnitTest -x jacocoTestReport -Pfork.every=0 -Pmax.parallel.forks=1`。測定対象は既存テストのService呼出区間で、既存のwarm-upも維持している。

| 経路 | 実測ms | 既存上限ms | 結果 |
|---|---:|---:|---|
| Argon2id・8件全不一致 | 247 | 1500 | 成功 |
| Argon2id・8件目一致 | 259 | 1500 | 成功 |
| 旧BCrypt・8件全不一致 | 1954 | 3000 | 成功 |
| 旧BCrypt・8件目一致 | 2247 | 3000 | 成功 |

- XMLの測定出力4行を既存上限と照合した。**4実行・4成功・失敗0・error0・skip0**。`BUILD SUCCESSFUL in 3m 18s`、exit0。前提のarchUnitFreezeStoreIntegrityTestも2件成功した。
- receipt/XML: `/home/kenta/verification-evidence/CMP-261025-2202-mfa-latency-local/`。receiptのsourceHeadは上記固定SHA、cleanupはremoved。測定JVM PIDは86788。
- この結果は単独1forkのローカルではCI失敗が再現しなかったことを示す。CIと実行機材・負荷条件が異なるため、CPU競合が原因であるとの断定や、CI全体の合格判定には使わない。CIジョブ完了後の再実行判断は親担当。
- 一時実行/集計helperは担当が作成した正確な3pathのみ削除済み。管理scratch残存なしを確認した。既存FE/BEやログインは操作していない。raw XML/receiptは親のcanonical記録への転記用に保持する。
