# 一般機能表示設定BEの検証記録

対象はCMP-261025-2202。受け入れ条件は[F01.3.1](../../features/F01.3.1_scope_feature_display_policy.md)を正本とする。
実機・CI・OpenAPI最終同期の合格はこの記録だけで代替しない。

## 固定HEADと試練

| 実行 | HEAD | 結果 | 分類 |
|---|---|---|---|
| red再開初回 | `c7f7a733a7` | 25実行・25失敗・skip 0 | `users.is_searchable`必須値のfixture漏れ。ACアサーション未到達のためred証拠にしない |
| AC-2 red | `e336cdb663` | TEAM/ORGの2実行・2失敗・skip 0 | `ScopeFeatureDisplayPolicyContractIT:104`で期待200に対し未実装404。実MySQL・Security経由のACアサーションへ到達 |
| green初回 | `d0a3593955` | 38実行・37成功・1失敗・skip 0 | ContractIT 30件、ServiceTest 5件、CommitITのrollback/初回並行保存2件は成功。残る成功commit試験はGETで403となり未合格 |
| CommitIT再検証 | `2029fa3197` | 実行中・結果未確定 | 独立fixtureへ読取閾値の`SUPPORTER`ロールを追加。既存Gradleのturnstileを尊重して待機後、compileJavaへ到達 |

初回greenの403は、本試験だけの空DBにADMINしかseedしておらず、`AccessControlService.hasRoleOrAbove`が閾値ロールを解決できなかったため。
実DBではFlywayが基準ロールを用意する。補正はfixtureにpriority 5のSUPPORTERを用意するだけで、本番の認可を変更していない。
実DB障害注入のTRIGGERは所有TEAM/scope IDに限定し、MySQLのbinary log権限に合わせて作成・撤去DDLだけをTestcontainerのroot接続で実施する。

実行receipt/結果XMLは`/home/kenta/verification-evidence/`配下の`CMP-261025-2202-be-red-assert`、`CMP-261025-2202-be-green1`、`CMP-261025-2202-be-green2`へ保存する。
終了済みred/green1の寿命管理runnerは`cleanup=removed`。redのメソッドwildcardは候補全classの走査で空JVMを増やしたため、greenでは完全修飾class指定、`fork.every=0`、`max.parallel.forks=1`へ是正した。

## 実装と検証の対応

- AC-1/2/3/6/7: ContractITでTEAM/ORGの独立4bool、既定true、入力/件数境界、実ロール、運営例外、削除/不存在scope、親子組織の独立性を検証。
- AC-8: CommitITで2件目の実DB例外による全rollback、commit前の監査0、commit後の監査を観測。全メソッドgreenは再検証結果で確定する。
- AC-9: 再表示でもpolicyのID/createdAtを維持する。Writerの書込先はpolicy表だけで、元業務データや個人widget表へアクセスしない。個人設定/順序の復元はFE/実機の証跡と合わせる。
- AC-11: ContractITのHibernate統計でscope一括Repository読取1クエリ、ServiceTestで全カタログ合成時のRepository呼出1回を固定。
- AC-4/5/10/12/13/14: 主にFE/実機の範囲。表示設定は業務APIの認可条件へ追加していない。BEの全量回帰・アーキテクチャ番人はCIと親検分で照合する。

## 実機用配布物と起動

`d0a3593955`の`bootJar`は2分32秒で成功。jar SHA-256は`0541d562c6d764cb70aaf308ce2c83678defb897b3141f707517e8a92bcc3340`。
この後のDTO/Controller変更はOpenAPI注釈のみで実HTTPのvalidation/認可/保存動作を変更しない。最終OpenAPIはCI生成artifactを親/FE担当が同期する。

Windows配布物は`C:/Claude/mannschaft/.claude/runtime/cmp261025-feature-be/mannschaft.jar`。
WSLがWindows上のnested jarを細粒度読取していたため、thread dumpで`ZipCentralDirectory`→`FileChannel.pread0`待ちを確認し、
生成jar単体を`/var/tmp/cmp261025-feature-be-runtime-resume/`へコピーしてSHA一致を確認した。全量sourceの追加コピーはしていない。

専用MySQL `cmp261025-feature-mysql`の13316、Valkey `cmp261025-feature-valkey`の16389だけを使い、DBは`cmp261025_feature`。
Flywayは1226 migrationを全件成功し、`253.20261025131621`まで到達した。既存利用者DB/volumeの削除・初期化はしていない。
`e2e` profileで自動schedulerを無効化し、CORSはlocalhost:3001/8081。seedの既知fixture暗号鍵に合わせる。
QR署名鍵の必須設定漏れによる一度の起動停止を確認し、runtime環境だけに署名鍵を注入して再起動した。秘密値は記録しない。

起動receiptは`C:/Claude/mannschaft/.claude/runtime/cmp261025-feature-be/startup-receipt.json`。
PID世代・SHA・用途・port・終了手順・runtime鍵の設定名だけを記載し、実機中は親が監視を引き継ぐ。
Java PID18562/session20794でWindowsの`http://localhost:8081/actuator/health`にHTTP200を確認し、FE担当へ専用DBのseed開始を依頼済み。
seedと4actorログイン成功の確認はFEの証跡へ記録する。
