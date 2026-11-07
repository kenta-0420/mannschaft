# L1-R 初回ロット: DuplicateNameNormalizer の検証記録

- 子CMP: `CMP-261028-2304`。scope: `app`。L1-R全体・親CMPは実装中を維持する。
- 実装基準: `977c3df5fd2ab5af79fa34db66c4121294634eff`。記録対象は[実装PR #4352](https://github.com/kenta-0420/mannschaft/pull/4352) のhead `b360c098c7320200b86c485f4ef5fd503265e1ac`。記録時点でDraft・未マージであり、実装内容がmainへ反映されたことを示さない。
- 対象は名称正規化1型だけ。実体は `organization.service.DuplicateNameNormalizer`、公開窓口は `organization.api.DuplicateNameNormalizerApi`。旧common型は既存static契約を保つ委譲だけとした。
- 旧型の `TransitionalBridge` は[PR #4214](https://github.com/kenta-0420/mannschaft/pull/4214)でmain提供済みの `common.annotation` 契約（必須reason/removalLane、TYPE、RUNTIME）を使用。既存caller維持を理由とし、呼出側はL2で移行、橋渡しはL5で撤去する。

## 記録時点の担当・登録履歴

- 本記録は2026-11-07の進捗更新。L1-Rの対象全164型・親戦役は未完了であり、状態はP4実装中を維持する。名称正規化1型の局所進捗をレーン全体の完了へ読み替えない。
- 登録は[PR #4211](https://github.com/kenta-0420/mannschaft/pull/4211)でmain反映済み。登録時の正規claim `cc2c5a146211d4b97f29760ffb324e01ae03c23d` は履歴として保持する。現在のcommon-core担当引継ぎと初回ロットの着手根拠は[親の引継ぎ・担当宣言](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6695483881)を参照する。
- REQ-L1R-BRIDGE-01のTransitionalBridge待ちは解消済みであり、L1-M全体の完了待ちではない。

## 先行試練と単体検証

2026-11-07、同一の `DuplicateNameNormalizerApiTest` を実装前後で実行した。新APIをimportせず `Class.forName` を存在assertionで包むため、型未配置でも試験をコンパイルできる。

| 段階 | 対象 | 結果 |
|---|---|---|
| 実装前の特性固定 | 旧入口のDN-1〜5、明示期待値19ケース | 19成功、失敗/skip/abort 0 |
| 実装前のred | 全試験40件 | 19成功・21失敗。新API不存在によるAssertionFailedError 20件、旧型のDeprecated未付与1件。コンパイルは成功 |
| 実装後の同一試験 | 新旧入口19ケースずつ＋公開契約2件 | 40成功、失敗/skip/abort 0 |

DN-1はnull・空・1個/複数のU+0020、DN-2は両端/内部U+0020・大小文字、DN-3はタブ/改行/NBSP/全角空白/NULを区別し、DN-4は長さ1・片端/両端・サロゲートペア、DN-5は各端100000個のU+0020を検証した。時間閾値による性能合否は設けていない。

実行はWindows上のJava 21.0.10と既存GradleキャッシュのJUnit Jupiter 5.12.2 / Platform 1.12.2を使用。`javac -encoding UTF-8 --release 21` で本番対象・既存TransitionalBridge・新UT・一時Launcher runnerだけを専用classesへコンパイルし、JUnit Launcherで当該クラスを実行した。旧特性だけの実行は同クラスの `旧入口_正規化の契約を保つ(java.lang.String,java.lang.String,java.lang.String)` を選択した。runnerは発見0件・失敗・skip・abort・container失敗を非0とする。依存追加・Gradle/build設定変更は行っていない。

通常sandboxではjavacが既存キャッシュjarのclose時にAccessDeniedExceptionを出したため、その出力はred証拠に採用しなかった。既存依存の読み取りと専用worktree内の同試験だけをサンドボックス外で再実行し、例外なしのコンパイルと上記red/greenを確認した。一時runner/classesは製品差分に含めない。

### 初回採用結果の出典と同一性

初回の採用stdoutは実行担当のツール出力にだけ残り、原本XML・log・summaryファイルは保存していない。後から作成した再現summaryやこの文書を初回実行の原本として扱わない。旧19件成功、全40件の19成功・21 assertion失敗、同じ全40件成功は当該ツール出力で確認した結果である。

- 同一UTソースのSHA-256: `9DD02F51D978F451161749BE1D0BA23215BD3F2CD4C2C935D5EAE4078814559B`。
- red/greenのUT class双方のSHA-256: `F86CD1F513BD33D288231075EA68AFA8FF384FFEB4A354B69FEF16C940FB830A`。

## ACの到達点と未完ゲート

| 条件 | 現段階の証拠・状態 |
|---|---|
| DN-1〜5 | 上記新旧入口の38ケースgreen |
| DN-6 | 公開final/private無引数constructor/public static String trimSpaces(String)1本を新旧双方で検査、旧型のDeprecatedと非空のbridge属性もgreen。差分上でループ・SPACE定数は所有者内部のみ、旧型→新API→内部実体の委譲を確認 |
| DN-7 | 本番3callerと既存DuplicateNameCollationGuardRedITは差分0。実MySQLの既存6ケースは未完、条件全体は未合格 |
| DN-8 | 全登録store・stored.rules・番人・EXPECTED_LINES・weights・build/CIのロット由来変更0。旧headのarchTest 447件は成功・skip0だが、別engine archUnitTestと本番7storeのIntegrityは各shard未完。条件全体は未合格（下記） |
| DN-9 | Controller/HTTP DTO/認可/DDL変更0。同一経路の変更前後の実生成・Drift成功と生成結果bytes一致を根拠に親が合格判定（下記） |
| DN-10 | 実機の組織/チーム名称導線・ロール横断・URL直打ちは未実施、未合格 |
| DN-11 | 実機後の独立探索は未実施、未合格 |

単体greenやOpenAPI同一性は出荷判断の代替としない。実DB・残る全量Arch・実機・探索および最終CIを親担当が継続する。

## DN-8の部分証拠と残件

旧head `0e03b740fcece253065c9843423206ede2dd9ad7` の[archTest job](https://github.com/kenta-0420/mannschaft/actions/runs/42300076106/job/128178930328)は、Jupiterタグ `archunit` の試験447件が成功し、失敗・skipは0だった。実コマンドは `./gradlew archTest --no-daemon --continue --build-cache`（部分filterなし）。XMLは58 files/58 suites/447件で、うち `ArchUnitFreezeStoreIntegritySelfTest` は自己試験17件である。この17件だけから本番7storeの解析・Integrity成功を推定しない。

別engineの `archUnitTest` と本番7storeを検査する `ArchUnitFreezeStoreIntegrityTest` は各shardでの実走・元XML・store不変性確認が未完であり、DN-8全体は未合格。同じ旧run全体はmain追従によりcancelledであり、job単独の成功をrun全体や最終CIの成功へ読み替えない。

## DN-9の変更前後の実生成とbytes一致

- before: head `f0ff3ef527ff06e487279adb244d1e7c4b2de84f`、[run 42251377093 / job 128007072417](https://github.com/kenta-0420/mannschaft/actions/runs/42251377093/job/128007072417)。Generate・Driftとも成功。
- after: head `0e03b740fcece253065c9843423206ede2dd9ad7`、[run 42300590894 / job 128177058114](https://github.com/kenta-0420/mannschaft/actions/runs/42300590894/job/128177058114)。`forkedSpringBootRun` → `generateOpenApiDocs` を実行し、3分28秒で成功。Generate・Driftとも成功し、UP-TO-DATEによる生成省略を証拠にしていない。
- beforeと本ロットの基準 `977c3df5fd2ab5af79fa34db66c4121294634eff` のbackend treeは `b11471040e12bf5fe37314b00d6d8f7c9d51407d` で同一。beforeとafterのbackend treeは同一ではなく、afterには本ロットの本番3ファイルの差分がある。
- before/afterのworkflow blobは `a260ca45762046aa42cfd415bd3623f704a0d35c`、生成設定・コマンドも同一。追跡済みOpenAPI blobは双方 `93e48023afc2abda738406d570e961c99d4ee0ad`。

同じ生成経路で両headの実生成とDrift checkが成功し、比較先の追跡済みOpenAPI bytesも同一であることから、生成結果bytesの一致が推移的に成立すると親が判定した。追跡済みファイルの単純diffだけを実生成の代用にはしていない。

記録対象head `b360c098c7320200b86c485f4ef5fd503265e1ac` は、after `0e03` とbackend tree `f075ddbef28f3725d6ed19e3d7de62942cf60e18`・frontend tree `a69d1cde5bb67c2f1cdda3a18dbffc0d2a08f79a` が同一で、workflow・生成設定・追跡済みOpenAPIにも差分0。この同一性からDN-9証拠を引き継ぎ、重複生成は起動していない。archTestの部分証拠も同じJupiter試験集合の証拠として引き継げるが、未完の別engineや本番Integrityを合格にはしない。

## 記録時点のCI・実機・探索

- 親確認時点の最新Backend CIはhead `b360` の[run 42309432309](https://github.com/kenta-0420/mannschaft/actions/runs/42309432309)。Detect・coverageは成功、他の実走jobはqueued。最終CI greenではない。
- 旧run `42300076106` は上記のとおりcancelled。最新runの成功証拠へ転用しない。
- DN-10/11は未実施・未合格。専用環境の起動設定を調整して再準備中で、health・seed・FE・fixtureがまだ成立していない。UIソース調査で挙がった候補を、未再現のまま確定不具合・完了としない。
- 実装PR #4352は記録時点でDraft・未マージ。全164型のL1-R・親戦役は未完了を維持する。
