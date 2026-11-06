# L1-R 初回ロット: DuplicateNameNormalizer の検証記録

- 子CMP: `CMP-261028-2304`。scope: `app`。L1-R全体・親CMPは実装中を維持する。
- 基準: `977c3df5fd`。実装branch: `feature/4022-l1r-normalizer-7a9502a2`。
- 対象は名称正規化1型だけ。実体は `organization.service.DuplicateNameNormalizer`、公開窓口は `organization.api.DuplicateNameNormalizerApi`。旧common型は既存static契約を保つ委譲だけとした。
- 旧型の `TransitionalBridge` は既提供の `common.annotation` 契約（必須reason/removalLane、TYPE、RUNTIME）を使用。既存caller維持を理由とし、呼出側はL2で移行、橋渡しはL5で撤去する。

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

## ACの到達点と未完ゲート

| 条件 | 現段階の証拠・状態 |
|---|---|
| DN-1〜5 | 上記新旧入口の38ケースgreen |
| DN-6 | 公開final/private無引数constructor/public static String trimSpaces(String)1本を新旧双方で検査、旧型のDeprecatedと非空のbridge属性もgreen。差分上でループ・SPACE定数は所有者内部のみ、旧型→新API→内部実体の委譲を確認 |
| DN-7 | 本番3callerと既存DuplicateNameCollationGuardRedITは差分0。実MySQL試験は未実施であり、条件全体は未合格 |
| DN-8 | 番人・全凍結store・stored.rules・EXPECTED_LINES・weights・build/CIは変更なし。全量archUnitTest/archTestは未実施、未合格 |
| DN-9 | Controller/HTTP DTO/認可/DDL変更0。同一生成手順のOpenAPI前後bytes比較は未実施、未合格 |
| DN-10 | 実機の組織/チーム名称導線・ロール横断・URL直打ちは未実施、未合格 |
| DN-11 | 実機後の独立探索は未実施、未合格 |

単体greenは出荷判断の代替としない。実DB・全量Arch・生成OpenAPI前後比較・実機・探索および最終CIを親担当が継続する。
