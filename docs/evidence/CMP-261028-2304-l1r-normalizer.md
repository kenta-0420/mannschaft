# L1-R 初回ロット: DuplicateNameNormalizer の検証記録

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

## ACの到達点と未完ゲート

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
