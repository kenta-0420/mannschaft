# L1-R 初回ロット: DuplicateNameNormalizer の検証記録

最新資格は末尾の「2026-11-14 R15標的探索と最終Core統合準備」に集約する。各日付節のhead・「現在」・未完項目は当時の履歴であり、最新状態へ読み替えない。

- 子CMP: `CMP-261028-2304`。scope: `app`。L1-R全体・親CMPは実装中を維持する。
- 実装基準: `977c3df5fd2ab5af79fa34db66c4121294634eff`。記録対象は[実装PR #4352](https://github.com/kenta-0420/mannschaft/pull/4352) のhead `7a3c076a69754044209e86747097e622ae9b1ab9`。記録時点でDraft・未マージであり、実装内容がmainへ反映されたことを示さない。
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

## 2026-11-12までのAC到達点と未完ゲート（履歴）

以下は旧source39a3・旧住民login0時点の表である。最新R14の到達点は末尾「2026-11-14 R14の採用結果と残務」を参照する。

| 条件 | 現段階の証拠・状態 |
|---|---|
| DN-1〜5 | 上記新旧入口の38ケースgreen |
| DN-6 | 公開final/private無引数constructor/public static String trimSpaces(String)1本を新旧双方で検査、旧型のDeprecatedと非空のbridge属性もgreen。差分上でループ・SPACE定数は所有者内部のみ、旧型→新API→内部実体の委譲を確認 |
| DN-7 | 本番3callerと既存DuplicateNameCollationGuardRedITは差分0。head 7a3cの実MySQL既存6ケースは成功、失敗/error/skip 0。実物採取・照合完了 |
| DN-8 | head 7a3cの全6shard・aggregate成功。archTest 58suite/447件、別engine 18suite/37件、本番Integrity 6runner/12件が失敗/error/skip 0。期待18suiteの欠落・重複0。7store＋stored.rules（計8ファイル）・番人・build/CI・weightsのロット差分0（下記） |
| DN-9 | Controller/HTTP DTO/認可/DDL変更0。同一経路の変更前後の実生成・Drift成功と生成結果bytes一致を根拠に親が合格判定（下記） |
| DN-10 | 旧R8の失敗履歴は保持。固定実機source 39a3のformal14は13pass/1failで、重複Org updateの誤期待409を契約200へ修正した別実行o1（native exit 0）との13+1分割証拠を親が採用。create409は維持。実preview・保存・逆復元等の局所結果と残務は下記 |
| DN-11 | 旧3住民の目的達成2/3・未実証観測は履歴として保持。新3住民は別actor・forkなしで並列起動後、全login0・全員の認証後の目的達成未確認で全停止。公開mobileは部分観測として保持し、具体人確認・画像/viewport補完と最終判定は未完 |

単体greenやOpenAPI同一性は出荷判断の代替としない。実DB・全量Archの実物照合と下記の局所実機結果を分離し、独立探索・teardown・最新main追従後の最終CIと出荷判定を親担当が継続する。

## DN-8の初期部分証拠と最新実物照合

旧head `0e03b740fcece253065c9843423206ede2dd9ad7` の[archTest job](https://github.com/kenta-0420/mannschaft/actions/runs/42300076106/job/128178930328)は、Jupiterタグ `archunit` の試験447件が成功し、失敗・skipは0だった。実コマンドは `./gradlew archTest --no-daemon --continue --build-cache`（部分filterなし）。XMLは58 files/58 suites/447件で、うち `ArchUnitFreezeStoreIntegritySelfTest` は自己試験17件である。この17件だけから本番7storeの解析・Integrity成功を推定しない。

この旧時点では別engineと本番Integrityが未完だった。同じ旧run全体はmain追従によりcancelledであり、job単独の成功をrun全体や最終CIの成功へ読み替えない。

最新head `7a3c076a69754044209e86747097e622ae9b1ab9` の[Backend run 42320061301](https://github.com/kenta-0420/mannschaft/actions/runs/42320061301) は全6shardとaggregateが成功。UT40件・実MySQL IT6件・archTest 58suite/447件・別engine 18suite/37件・本番freeze Integrity 6runner/12件は全て失敗/error/skip 0。別engineの期待18suiteにmissing/unexpected/duplicate 0を元XMLから照合した。本番Integrityと自己試験17件は区別する。

実checkoutは `71c75988fc9465f563f8d6527a2287998aae14c0`、tree `35b03439d1d892964857dce63c5a6be079259dce`。remote checkout treeとlocal head 7a3cのtree一致を確認した。private正本は管理worktree `.claude/l1r-ci/pr4352-7a3c-evidence/` の `actual-checkout-proof.json`、`archunit-engine-union-root.json`、`shard5-584-store-diff-root.json`。

追跡済み7store＋stored.rules（計8ファイル）・番人・EXPECTED_LINES・weights・build/CIの本ロット差分は0。runtime storeはchip-awayのみ（D3 14減、D3T 43減、D5 39減、entity/D6 35減、追加0）。削減行のorigin source blobは基準977cとheadで同一と照合済みであり、runtime storeの全bytes不変は主張しない。

## DN-9の変更前後の実生成とbytes一致

- before: head `f0ff3ef527ff06e487279adb244d1e7c4b2de84f`、[run 42251377093 / job 128007072417](https://github.com/kenta-0420/mannschaft/actions/runs/42251377093/job/128007072417)。Generate・Driftとも成功。
- after: head `0e03b740fcece253065c9843423206ede2dd9ad7`、[run 42300590894 / job 128177058114](https://github.com/kenta-0420/mannschaft/actions/runs/42300590894/job/128177058114)。`forkedSpringBootRun` → `generateOpenApiDocs` を実行し、3分28秒で成功。Generate・Driftとも成功し、UP-TO-DATEによる生成省略を証拠にしていない。
- beforeと本ロットの基準 `977c3df5fd2ab5af79fa34db66c4121294634eff` のbackend treeは `b11471040e12bf5fe37314b00d6d8f7c9d51407d` で同一。beforeとafterのbackend treeは同一ではなく、afterには本ロットの本番3ファイルの差分がある。
- before/afterのworkflow blobは `a260ca45762046aa42cfd415bd3623f704a0d35c`、生成設定・コマンドも同一。追跡済みOpenAPI blobは双方 `93e48023afc2abda738406d570e961c99d4ee0ad`。

同じ生成経路で両headの実生成とDrift checkが成功し、比較先の追跡済みOpenAPI bytesも同一であることから、生成結果bytesの一致が推移的に成立すると親が判定した。追跡済みファイルの単純diffだけを実生成の代用にはしていない。

当時の記録対象head `b360c098c7320200b86c485f4ef5fd503265e1ac` は、after `0e03` とbackend tree `f075ddbef28f3725d6ed19e3d7de62942cf60e18`・frontend tree `a69d1cde5bb67c2f1cdda3a18dbffc0d2a08f79a` が同一で、workflow・生成設定・追跡済みOpenAPIにも差分0だった。現head `7a3c` のbackend treeもafterと同じ `f075ddbef28f3725d6ed19e3d7de62942cf60e18` と親が直接照合済み。生成workflow blob・生成設定も同一であり、private正本 `.claude/l1r-ci/dn9-openapi-evidence.md` を根拠にDN-9判定を継承する。実生成headは0e03であり、7a3cで新たに実走したとは主張しない。標準CIで生成をskipした結果を代替証拠にはしていない。

## 記録時点のCI・実機・探索

- 最新Backend CIは上記head 7a3cのrun 42320061301で成功。旧cancelled runとは区別する。
- source 7a3cの `formal-20261107-r8-01` は9ケース中6pass/3fail。組織改名→再GET→元名復旧、member/outsider各2scopeと匿名制限はpass。作成2件は409→中止→409→確認201の後、GET本文読取helperが旧文書を新文書への遷移後に読んで `browser-body-unavailable` となった。製品の保存失敗と断定しない。
- freshPageで読取競合を根治後、`formal-create-followup-20261107-r8-03` のチーム作成は同名409→中止→確認201→GET200・保存名・画面一致がpass。組織はslug利用可text待機でPOST前failとなり原因未断定の原本を保持した。その後の `formal-org-followup-20261107-r8-04` は同じAC・timeoutで、slug利用可1.4秒、同名409→中止→409→fingerprint確認201→freshPage GET200/json-success・保存名のU+0020保持・画面一致がpass。通し単一run9greenとは記録しない。
- チーム名称更新R8はdashboard loadingでPATCH未到達。R7のversion欠落による400とは別結果。別修正[Draft PR #4360](https://github.com/kenta-0420/mannschaft/pull/4360) / CMP-261107-1243は未採用で、依存PR #3644の未採用も維持する。DN10全体は未合格。
- 別修正の前HEAD a36d1df48fcf3f195db72bfba2ea9f3182703e57 の[Frontend run 42344037580 / job 128317780961](https://github.com/kenta-0420/mannschaft/actions/runs/42344037580/job/128317780961)は、正規Nuxt70件（新56＋旧8＋統合6）skip0、全759files/7633pass＋既存skip5、ranch27passを親がproofで直接確認済み。ただし[Smoke run 42344035535 / job 128317774443](https://github.com/kenta-0420/mannschaft/actions/runs/42344035535/job/128317774443)はNAV-GEOMETRYの360pxで1px許容をさらに約0.571px下回る計測値（実gap約-1.571px）により失敗した。現leaf d4284adは同期測定修正後の全CIが成功し、NAV6ケースは初回実行で成功した。ただし実gap等の実値添付はなく、反復実行の再現性まで実証したものではない。
- 独立3住民の選択観測は [resident-observations-20261107.json](CMP-261028-2304/resident-observations-20261107.json)。全員1 context/1 login・各close 1・native exit 0、source 7a3c、mock/API目的代替0。代表画像は親担当が直接実見した。探索3/3実施と目的達成2/3を分離し、管理住民の未達を残す。同名3行は実在fixtureの3件であり行重複の不具合とは認定しない。識別の迷い・403等は未実証観測で、新規確定不具合やTaskList追加へ昇格0。DN11は名称操作の導線・失敗表示・同名確認まで含む条件であり、実施・閉鎖だけから合格にしない。DN10未充足のため最終判定保留。
- 気づき箱は累積正本 `CMP-261015-1510/resident-overlay.json` の旧55 persona/55 journey/111 insight全フィールドをdeep保持し、3/3/3のみ追加して58/58/114。top environment/runIdと旧run/sourceを保持した。新3runはrunEnvironmentsへ別登録し、全てtrusted=false/observed-unverified、coverage不昇格。原本にないADMIN/OUTSIDERのjourneyIdは登録時付与と明記。画像実体は公開しない。
- R8 runtimeは正本receiptでcleanup removed/exit0、所有BE/FE 2process・専用2container・knownvolume1と自scratch不存在を親担当が直接照合済み。全体check exit1は他owner entries5に由来し、全体cleanとはしない。他資産操作0。fixtureは履歴のみでDB撤去済み・再利用不可。名称修正の次回実機には新source・新run・新DB/fixtureが必要。
- 実装PR #4352は記録時点でDraft・未マージ。子CMPと全164型のL1-R・親戦役はP4実装中を維持する。

## 2026-11-12時点の局所結果と残務

旧履歴へ初回1型の採用結果を追記した。実機の固定sourceはローカル統合commit `39a3e96fdc78bd8c2058d3eb9c4b24c4a7a66d98`、Core CIは下記57b7への確認で、source別の局所結果として記録する。最新main追従・最終CIと出荷判定は未完。

- formal14のf2は13pass/1fail。唯一の失敗は重複Org updateを409とした誤期待で、正規契約は200。期待を修正したo1を別実行しnative exit 0を確認した13+1分割証拠を採用する。createの重複409契約は維持する。
- Cardの1440/768/390px、90文字の長名2種×3幅、normal2の実preview・PATCH204・GET逆復元、same-IDのOrg/Team権限差を採用済み。unavailableはheld-pointerによるclip/autoscroll・実preview・PATCH・GET・UI削除を確認した。復元A→視覚A→empty UIの3件削除→復元B→positive Bの連鎖も採用済みで、名前とPUBLICは復元済み。
- normal2・unavailable等のPATCH204後の `ERR_ABORTED` は、機能成功とtransport警告を分けて保持する。loopback204対照では再現0、原因未確定。
- 親担当が追加したseed3再作成条件は撤回し、追加seed3再作成は実施0。復元B3を継続利用し、作成前不存在へ戻す最終owned専用runtime teardownは未完。before5と復元B3は別スナップショットとして記録する。
- Core Backend CIは6normalとaggregate・Arch・coverage・NonJST・Lintが成功。actual direct checkoutは `57b7e7b354c69b1abd8c7f8fad1e78a1a3304440`、treeは `4a220306b6347fe0c6e174e1661ff35ee59a47b9`。UT40件（19 parameterizedケースを各2入口＋通常2件）・MySQL collation6件は全pass。6normalは実行・cache0、auxは42 FROM-CACHE / 96 UP-TO-DATEの資格で区別する。Frontend・E2Eもgreen。最新main追従後の最終CIと出荷判定は未完。[Backend run 42992882425](https://github.com/kenta-0420/mannschaft/actions/runs/42992882425)、[Frontend run 42992882548](https://github.com/kenta-0420/mannschaft/actions/runs/42992882548)、[E2E run 42992882378](https://github.com/kenta-0420/mannschaft/actions/runs/42992882378)。
- 新3住民は別actor・forkなしの並列起動後、全login0・全員の認証後の目的達成未確認で全停止した。source生成の自動審査による拒否と技術的な初期起動失敗は履歴として記録する。公開mobileは部分観測として保持し、DN-11の最終判定は未完。現在は通常ログインの具体人確認待ちで、画像・viewport不足の補完も未実施。
- 支援WS5機能はChat・通知だけ採用済みで、残りは準備中。全AC35・全支援PR・main採用・全残務は未完。L1-R全164型・親戦役はP4実装中を維持する。

## 今回の登録検証と制約

既存生成器を使用し、正本・専用WTのignored copy・生成b0RunOverlayの構造一致と、旧55/55/111の全フィールドdeep保持を確認した。生成器が出した日付・sources・集計・検算・campaignsの無関係差分は採用せず、生成boardのb0RunOverlay以外の全top keyはbaseと構造一致を確認した。通常validatorと--self-test-overlay全体はCMP正本行数と参照抽出数の不一致で不合格。初期base1255と、#4365採用後のmain d7717711619ae3724a395d5670d8f723cc9a7395の双方で、各baseの元boardと同じTaskListを使用したA/B検証は同じ診断で停止した。最新比較ではTaskListと旧55/55/111正本をmain d771と照合し、Git改行変換を正規化して一致を確認した。独立したoverlay限定検査では既存validatorのvalidateRunOverlayと自己検証ブロックをそのまま実行し、正規・空insight・複数head/runを受理、不正10種を拒否した。これは通常validator全体やボードUIの合格を意味しない。

実B0ボードの初回確認では111→114件・旧111件の全record hash不変・reload後114件・3カード表示を確認したが、新3件のIndexedDB rowで出所metadataが欠落したため検証全体は失敗した。原因は既存HTML importerの固定projection。新規import rowに限りindividual runId、originalRunId、sourceHead、trusted、sourceStatus、sourceEvidencePathを保持する局所修正を追加した。人間用triageのstatusはinboxのまま、原観測のobserved-unverifiedはsourceStatusに保存する。既存import ID、表示、重複排除、既存111件や人間編集の保存は変更せず、既存legacy recordのmetadata欠落を自動backfillしない。固定commit f2ace7cc02c6225ec088887e2ed89f6a4871c009のgreen-assertion-03はPASS・native exit0。旧1255の実111→114件、旧111件の全record hash不変、3カード表示、元9propsのdeepEqual、初期inbox、新ADMINへの実triage1回後planned・出典保持・他113件不変、triage後reload114件をすべて確認した。green-assertion-01はrealm配列helper、02は文字化けした????期待値helperの失敗として原本を保持した。元redは同じ正規化後も赤であり、期待値を変更してgreenへ合わせていない。親がgreen03の全12checks=true/exit0、verification-chainの元red ERR_ASSERTION・期待値SHA同一・全5PID不在・全port非LISTENを直接照合し、3カードとtriage後PNGも実見済み。

実B0 UI証拠は固定f2aceの公開Git blobを配信した結果であり、後続文書HEADではHTML/data blob不変を条件に継承する。HTML blobは591816a9d3cdbf673c00df9aa9ee15c6ad04c582（SHA-256:7708e0a5ab9466df5fb174f1d0f280333470f913c5db64dc0678bf8f63eea8a6）、data blobはe1afaa7c3b6496539305309657ad616139aeab1a（SHA-256:85e8355e861a2d44829f0c8577558f9d541c09b48e716a58fe86ed8e5bef644a）。Git checkoutの改行変換によるraw bytes差は実配信blobの差と混同しない。


## 2026-11-13の資格（履歴：追加9枠の直接許可時点・初回1型は未完）

上記の7a3c・A9 source39a3・Core57b7と11月7日/12日の結果は歴史として保持する。本節は固定R14 source `8afd06e6e06dddcd65f769be5e796baf0e83e28f` と公開Core head `a309666653de96b8b173e5ea2c8961078f822b3b` の採用済み資格を分けて記録する。文書準備中のlocal `b040e7e06eca0380e87d2796a0a461294f2122ee` はmain8aaの通常mergeのみで、a309とbackend tree `04e46b9dad39a0b82f8c4acf8b36c6b31b0f3e06` が同一。local mergeを公開済みCI headやmain採用と扱わない。支援PRの最終採用・残実機・新独立3住民は未完で、全164型のL1-Rと親戦役は完了していない。

### Core CI v3：直接XMLと推移資格の区別

[Backend run 43348593781](https://github.com/kenta-0420/mannschaft/actions/runs/43348593781) のlatest attempt2は全12job成功。採用rawはa1のshard0/2/4/5とa2のshard1/3であり、全jobをa2で新規実行したとは主張しない。actual checkout `4d4f2a0d413262529a38904387b50410c3438ff6` は公開a309とtree `df99b1548c53c57405da981c56d74c0dd5bbd4ee` が一致。旧a1失敗・取得失敗・回収の形式差を履歴から削除しない。

親採用のprivate正本 `core-ac-source-raw-qualification-v3.json`（2026-11-13T08:18:04Z、SHA256 `931bfb92940c339e6727172175d58ea4d030e8d712a520821376f6d4ad0e0791`）は次を区別する。

- 六shardのtest合計33356、成功33339、失敗0、skip17。各採用rawの`:test`・`:archUnitTest`・`:archUnitFreezeStoreIntegrityTest`は実行状態で、UP-TO-DATE/cacheによる省略ではない。この資格を補助task全体のcache不使用へ広げない。
- DN-7の`DuplicateNameCollationGuardRedIT`はartifact14007351712の対象JUnit XMLを直接照合し、6 testcase成功・failure/error/skip0。XML SHA256 `fd427906b100e0d0005270a06507bf2b2759c74dba11dbc71e58b6ab401705fe`、対象receipt SHA256 `5b165433c3c4276bc9aedd46a405a7185b7a1d248d0cc20a1d16c90f498034ed`。集計skipからDocker条件有効を推測した旧説明を現在の受入根拠にはしない。
- 正規化APIの40 invocationは、不変sourceの19引数×2入口＋通常2と対象classのraw SUCCESSによる推移資格。**直接per-class XML40件を採取したとは主張しない。**
- DN-8のArchUnit engineはraw37成功/18 class、別`archTest` jobは465成功・失敗/skip0。本番Integrityは同じ2件が六runnerで各2/2成功（計12実行）であり、XML内のself-testや12種類の異なる試験と混同しない。`verifyShardCoverage`成功はprofile期待を束縛するsource guardで、raw中に4profile名を列挙した証拠ではない。

### DN-9の対象限定の継承

旧paired generationの実生成・Drift成功と生成bytes一致は保持する。現在のnormalizer対象3型・既存caller・生成設定のblob同等性を根拠に、対象移設によるHTTP/schema契約不変の判定を親が継承した。現在のglobal OpenAPIはmain側の別変更で旧生成時と異なるため、whole backend tree/全API bytes不変や、source8afd全体を新たに実生成・Drift検証したとは主張しない。これはDN-9対象の推移資格であり、他機能の新API変更の資格ではない。

### R14 formal・postformal・支援実機の分離

- formalはf1の作成2件とf2の残12件を別原summaryから選ぶ**2+12の分割資格**を親が採用。f2 summary SHA256 `e05255000c77c8c0b9b2e79ec0012bbae426394c02d66fa99fd625356a6b6082`、split qualification receipt SHA256 `23a3fecdfb5dc4da59a9ef40b8b440c0940c457fe1e670f9eb02a35b0a5e2abb`。f1の初回案内maskによる停止は履歴として保持し、単一実行14greenや元f2の`dn10Complete=false`書換えによる合格とはしない。
- postformalのresponsive/long・same-IDは親が対象範囲を採用。normal2は実pointer順序変更/逆復元・各reorder204とfresh GET一致を機能採用したが、204 no-content後のERR_ABORTEDは原因未確定のtransport警告として保持し、`transportClean=false`/`overallCleanPass=false`を維持する。unavailable u1は内部DIV scroll未達・release no-op PATCHの失敗。ownerによる元名/PUBLIC/version2/元UUID順の復旧確認と、原summaryの保守的復元flagは別に保持する。残Card復元/empty等は未完。
- 緊急休業EC e2はphase passを親が採用（summary SHA256 `89c13471f65f0c8b2b5afd7a2bc6cc74a82c90f92350ed6aed13e8f5574a39df`）。実UI作成・member確認・管理画面件数/実配送・制御断後再接続を対象範囲で確認した。非adminのGUI拒否/RO403を実STOMP拒否frameの代用にはしない。予約cancel/slot/line削除は成功、closure履歴は最終専用DB撤去待ちで`fullyCleaned=false`/`ac35Passed=false`。
- Signage s1（summary SHA256 `8fd16acbf4d1557978838ac3755a673679bc34ebdec86c50c5970dea397fa8c0`）とMatch m1（同 `fd0759837f52a4f2a5bc179b034e415ed3c34921890a4dd5050d65304e77c8f8`）は失敗として保持。元例外情報不足のため原因未確定で、診断candidateを製品修正成功とは呼ばない。SGはtrigger intent0・既知token失効/screen削除、Matchはevent intent0・既知試合DELETE204→404、各3context閉鎖/unknown=false。新試行の結果は本節では未採用。
- Chat/通知はA9 source39a3の採用済みsubsetを歴史証拠として保持する。対象protocol/auth/notification blob同等性に基づく継承であり、R14で5機能をfresh実施したとは表記しない。AC35全体・AC36独立探索・最終採用は未完。

追加操作の具体案SHA256 `020da9e4e62dbcd372e2654e50b735c8f0be3044271e1bf470ebab9412a28e72` に対し、ユーザーはu2追加3PATCH・SG追加4・Match追加2の最大9 domain writesを直接許可した。承認記録 `r14-additional9-direct-approval-20261113.json` SHA256 `7cdcfa40320cd27b00d5c43c552fae65957e43a76c5d4fd5befce400b379ef83`。各工程1回・未知再送禁止、新DB/runtime/seed0。既承認の未消費emergency2/events2・残Card・新独立3住民・所有cleanupは別の枠として継続する。これは全network requestが9件という意味でも、追加実機・探索・cleanupが成功済みという意味でもない。新独立3住民と最終実機/採用結果はpendingで、旧login0の住民試行は旧履歴のまま残す。

## 2026-11-14 R14の採用結果と残務

固定実機sourceは `8afd06e6e06dddcd65f769be5e796baf0e83e28f`。初回1型に必要な正式実機・Card後段・新独立3住民探索・専用runtime撤去の採用結果を以下に記録する。Core公開head a309のCI v3と、旧sourceのChat/通知の継承資格は11月13日節のままであり、全機能を一つのrunでfresh実施したとはしない。初回1型の最終CI・支援PR採用・main反映は未完で、全164型のL1-R・親戦役はP4実装中を維持する。

| 条件 | R14での到達点・資格 |
|---|---|
| DN-1〜6 | 既存red→green・40 invocationを維持。Core CI v3のAPI40はsourceとclass成功の推移資格で、直接per-class XML40件ではない |
| DN-7〜8 | v3の直接MySQL XML6件、ArchUnit engine37件/18class、別archTest465件、本番Integrity同じ2件×6runnerを採用。六shardのrawはa1/a2混在で33356件/33339成功/17skip。新main統合後の最終CIは未完 |
| DN-9 | 対象Normalizer/caller/生成設定のblob同等性による限定継承。global API/whole backend tree不変やR14新生成とはしない |
| DN-10 | formalはf1作成2＋f2残12の分割資格。responsive/long・same-ID・normal2・unavailable u2・復元A/視覚A・empty・復元B/positive Bを機能採用。204後のERR_ABORTEDは原因不明のtransport警告として原clean falseを保持 |
| DN-11・独立探索 | 正式実機後に新たな独立3住民が通常profile・systemRole nullで探索し、全員native0/CLOSED。下記の部分達成・未実証観測を含む結果として採用。旧login0試行は履歴保持 |
| 支援WS | Chat/通知は旧sourceから対象blob同等性で継承。EC・Match・SGは下記の分割/限定資格。Signage購読認可全体は不合格で別修復へ登録 |
| 後始末・最終採用 | R14所有runtimeと専用DB履歴は撤去済み。Core新CI・支援PRの最終採用・main反映は未完。CI成功や実機完了だけで戦役を閉じない |

### Card・EC・Matchの追加採用

- unavailable u2は実pointerで内部scroll・preview・release・PATCH204→fresh GET一致とUI削除を採用。ledger SHA256 `fec96a5805a10deab09b41ec0fc19707f399211aae4cda17a4c00810af5c8e2b`。復元A receipt `65c5e5813ed0d4bce6b3a027c506ef92b74eb0117bf404adaedea06b55453b8a`、視覚A ledger `024a6adc383cbd2a078e9a45e628cbf97f368efe14fe13d1170cc8941f58e26b`、empty ledger `f37e426258755d11aa6b985010dad3b93f6ce1ebbeb0e0df2d3b0b0d202be9d5`、復元B receipt `e3cdb7d37ebcfd35ea3f16a116afe24fa116dea3e7e113b56321bcf704c102e1`、positive B ledger `6bca5d69cb7f471f74a3ea14ebb66bb07859cf1214d0a3034b20c4ce3a0255fc`を親が原本照合・選別画像実見して採用。emptyは通常UIで3件削除→fresh GET 0、主空表示を実見。補足文全体の可視性まで主張しない。normal3のidentity・順序・元名/PUBLICのsemantic復元であり、初期seed全5件の完全再作成ではない。
- EC e2の機能採用は11月13日節のphase資格を維持し、残った履歴は最終専用DB撤去で閉鎖した。原summaryの `fullyCleaned=false` / `ac35Passed=false` は変更せず、後段の撤去証拠で補う。
- Matchはm2作成とremaining-only resumeを分離して採用。resume summary SHA256 `3346fb63e8b5b2419ca6e8f185fb583f81b0f0916e01dc6e8022fb5ac36579d7` はnative0/passed true。同じ既知試合を使用し新規作成0、未送信だった2イベントのPOST201・各実message1・member表示・再接続2→3、非許可利用者の拒否後message0、DELETE204→404を親が照合した。m2の原unknownは変更しない。m2とresumeは別3contextずつで、単一試行ではない。相関はSOLE_LAST_SUBSCRIBEでserver receipt/destinationなし。resumeの新PNGはなく、元m2画像とresumeのGUI assertion/WS/HTTP/journalを分離する。

### Signage搬送の限定採用と別修復

s4 summary SHA256 `0f02f6ba68d15cd670790f8279b8864f520c64abebaca32e49294452346a25aa` とprivate正本 `r14-signage-s4-root-qualification-20261114.json` を親が照合。管理者・memberへの異なる2marker配送、同contextの明示的補助接続reopen、自然不許可Originの拒否、HTTP token失効を範囲採用する。Origin403はブラウザーの固定診断による別資格で、typed HTTP statusは未観測。製品playerの自動復旧・失効後の既存WS停止は実証していない。s1/s2/s3の失敗とs4原summary passed falseを保持し、履歴DB撤去は後段証拠で閉鎖した。

管理RESTで拒否された他scopeの認証済み利用者が、端末token/capabilityを使わず試験markerを2回受信した。変更前後のsource比較では既存購読guardの欠落で、今回の認可方針変更は0。匿名受信・実利用データの漏洩・失効後WS停止は未実証。Signage認可全体の合格や本番リスク受容とはせず、正本残課題 `CMP-261114-0713` として許可契約の判断・別修復を分離する。既存の組織管理GET認可課題 `CMP-260917-1350` とは異なるWS購読境界である。

### 新独立3住民と気づき箱への引渡し

Name/favoriteの導線を対象にした独立探索は3/3終了し、DN-11の部分的な目的達成を採用する。一方、WS修復計画のAC36（対象機能を標的にした通知・拒否後/再接続の探索）は部分充足であり、標的補完は未完。原3報告にはChat・EC・通知の拒否後を目的にした探索がなく、Match空一覧/reloginも部分証拠に限る。正式実機AC35で代用せず、最終採用・引継ぎ削除はHOLDとする。本節記録時点では新しいWS標的補完3役はsource-only案の準備段階だった。その後の承認済み回復・実探索の結果は末尾のR15節を参照する。

管理者desktop・一般memberの390px touch・別所属利用者desktopが、互いに手順/結果を共有しない独立contextで探索した。通常profileとsystemRole nullを確認し、全員native0/CLOSED。管理者はfavoriteからcalendar/組織membersへの到達、memberは所属/favorite/記録空一覧への到達、別所属利用者は自身/他公開teamの差・所属/試合空一覧・reload/relogin/切戻しを確認した。記録保存や実記録詳細は未確認。別所属利用者の自作favoriteは201→204で復元し残存0、他公開teamのmodules403の原因は未断定。初回private ACL停止・空dirによる再起動停止は履歴保持し、domain操作の再送とはしない。

原report SHA256は順に `5bbbbcb5cc43f278b4d62633cb0f5b1fe6ad755ef7912a458fd658db70dc44e6`、`5aa559431f95b113e0781345ae9813692c3add2d91f96d446cce29a3086dc618`、`0eb6f8e5d9f1e059d85d71c83775d60007b78185b3497c704603a46cdf79d005`。親が原本と選別PNGを実見。識別の迷い1件と、一般memberのテンプレ案内から空管理一覧への到達1件を未実証の気づきとして採択し、第三住民のinsightは0。確定不具合や正本残課題へ昇格していない。第一住民のrunId、第三住民のrunId/sourceHeadは原reportに欠け、固定driver/binding/出力namespaceから親が補足した登録情報である。

B0担当への引渡しは累積67persona/67journey/122insightと旧64/64/120の全field・top environment/runId保持を対象とする。B0側の登録・実IDB検証とその依存commitは別成果物で、本節作成時点ではCoreへ未統合。公開画像本体・資格情報を含めない。

### 所有runtime撤去と残る出荷ゲート

private正本 `r14-final-cleanup-20261114-01/teardown-final-qualification.json` SHA256 `5be8c43ebdfba5d019073b55fbcde7ae614c10f8ac083e3a2f7b0f29184c7401` を親が採用。runner receiptはexit0/signal null/cleanup removed/error null。所有BE/FE・barrier2・container2・専用volume1の計7対象、scratch/cache、監督/child・writerの閉鎖、Windows/Linuxの要求4port非LISTENを確認した。EC/SG履歴も専用DB破棄で閉鎖。共通 `--check` の全体exit1は他の旧3行によるものでR14行0、全体cleanとはしない。元scenario summaryは保持する。raw lifecycleは必要なstartup/shutdown範囲の原bytes・hash・lineageを選別保全してから全量と外部Jarを撤去する別作業が進行中で、撤去完了を本節では先取りしない。引継ぎは最終残務後に削除予定。

新main `32e4777aec54d5de324f22948099e8a4789fc1a4` はRanchのBE/FE・生成型・Nuxt PWA設定等が進み、whole BE/FE同値ではない。支援6PRの通常main追従では対象source51pathを保持したが、新headのCIと最終採用は進行中で、旧CIを新main統合資格へ読み替えない。CoreはB0成果・支援最新headの統合後に最終CIが必要。初回1型の実機/探索/撤去完了と、初回1型の出荷・親/all164の完了を明確に分け、他支援CMPの完了状態・完了JSONを先取り更新しない。

## 2026-11-14 R15標的探索と最終Core統合準備

R14正式実機後のWS標的補完を、同じ固定実機source `8afd06e6e06dddcd65f769be5e796baf0e83e28f` の承認済み回復環境で実施した。親担当はAC-36、新独立3担当の原報告・mutation journal・受信WS metadata・選別10画像を照合し、独立目的探索と裏取りの実施条件としてAC-36を完了と判定した。各担当は通常GUIで1回ログインし、別profileのsystemRole null、desktop2名・mobile touch1名で探索した。認証前contextの閉鎖とidentity確認前の拒否履歴を保持し、全試行を計3contextとは表記しない。旧login0試行も成功へ変更しない。

- 管理者は緊急休業をGUIから201作成し、memberの確認POST200と管理者confirmation topicのMESSAGEを照合、対象1件・確認済み1/1を確認した。人工offline→online直後はオフライン表示が続いた。通常GETと戻り後に新CONNECTEDとリアルタイム表示を確認したが、自動再接続単独の成功とはしない。
- memberは本人のChatへ2投稿し、他private channelへの正規query URLでは不可視・未選択、本人Chatへ戻って投稿継続を確認した。このUI結果をSTOMP拒否とは呼ばない。本人向け通知queueで同じ試験markerの緊急休業通知を受信し、GUI確認200→その通知だけのread200を確認した。無関係通知の一括既読は行っていない。
- 別所属利用者は他scopeの観戦拒否UIとWS ERROR→CLOSEを観測した。ERROR本文・購読相関がないため特定購読のserver receiptとはしない。自身の試合では開始イベントを201作成し、戻り・再読込後もイベント履歴が残る一方、状態表示は開始前00:00へ戻った。比較中に他人向け試験markerの混入は観測しなかったが、無受信を全通知の非漏洩証明とはしない。

探索中の自己操作は計10件（create4/update6/delete0）、全担当pending0・unknown false。新しい気づきは人工offline切替後の確認状況表示継続と、試合再入場後のtimer/period初期化の2候補である。実回線断との同等性・永続要件・既存課題重複は未確定で、確定不備や製品修復へ昇格せずB0の未実証観測として登録する。製品全動作green、5機能×3役の全網羅、認可全合格、自動再接続保証は主張しない。既存Signage購読認可欠落は別P1 `CMP-261114-0713` のままで、今回の修復・本番リスク受容は行っていない。

### 支援CIと最終統合の資格

[WS PR #4459](https://github.com/kenta-0420/mannschaft/pull/4459) head `870bd9829e8fd96e5ea33ad1c2881a9ef0275d65` の[Backend run 43543300356](https://github.com/kenta-0420/mannschaft/actions/runs/43543300356)は全12job成功。6shardのtest taskはskipped/upToDate/noSource/failedが全てfalse、test taskのFROM-CACHE観測0で、通常テスト6本の直接実行成功を採用した。依存・compile cacheの使用はありcold-cacheではない。JUnit artifact全量・case総数は今回未回収。compiled coverageの4profile guard成功も公式ログで確認した。旧85分timeout run 43521977171は失敗履歴を維持し、新weights・新head成功を旧障害原因の証明やflake認定へ拡張しない。

最終Coreは従来の8PR内容を保持し、最新main `1f0ac7b1054651c053cc47a22aa6ba607c4412f7` と[closure PR #4410](https://github.com/kenta-0420/mannschaft/pull/4410)最新head `bcd80bb0f9278f9608084bf2829a423dca5de502` を通常mergeした。main増分は招待受諾の並行性試験3ファイルと証拠文書1ファイルで、文書のみの前進とは扱わない。最終B0成果の通常merge後、最終Coreの実headで全CIとstrict/current-main資格を確認する。旧Core・各支援のCI成功を最終Coreの成功へ読み替えない。force/squash/rebase/admin/bypassは使用しない。

### 現時点の残務

R14のformal f1作成2＋f2残12の分割資格、元f2 false、normal2等のERR_ABORTEDとtransportClean false、Signageの限定採用と認可残課題は上記履歴のまま保持する。R15の全10cleanup ACK（cancel200が1、DELETE204が9）と各原FINALを親担当が直接照合し、実探索3contextのCLOSED/native0を採用した。既ACKの再送は0。閉鎖前summaryのopen記載とdriver既定のac36Passed falseは原値のまま保持し、後続の閉鎖証拠・親担当のAC-36判定と区別する。closure/通知/加入履歴は専用DB最終破棄まで保持する。回復runtime・専用DB・scratch/Jarの撤去は未完で、完了を先取りしない。

最終B0登録・通常同期/generator/全validator/実IDB確認、外closure担当の最終引渡し、最終Core差分の採択・実head CI・ready・main採用は進行中。8PRのmain採用後に支援子CMPの完了証拠と正本状態を通常docs PRで確定する。親戦役・L1-R全164型はP4実装中を維持し、支援CMPの完了・引継ぎ削除・全残務完了を先取りしない。公開文書へprivate操作識別子・資格情報・ローカル能力URL・原画像本体は追加しない。
