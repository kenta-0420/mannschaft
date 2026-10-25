# CMP-261021-2213 L1-F 最終検証

[実装PR #4045](https://github.com/kenta-0420/mannschaft/pull/4045)は2026-10-24T10:41:53ZにMERGED。検証対象headは `1487f99fbba85b10b6c1098a7e8f20109b13c296`、squash mergeは `23daf720490cdda597b4596cd03f7fd3f5ac9b81`。PR4104の修正merge後main run40148629302（exact `d4f8b6ece263fb0ea7f6e7d367e6fefb364cea12`）の全6shard・Arch・workflow最終successを確認し、L1-Fの完了保留を解除する。実装headのpremerge CI成功だけを完了根拠にせず、統合後検証を別途確認した。親戦役や他担当の完了を意味しない。

## 変更と承認の範囲

D1/D3/D5の依存descriptionとoccurrenceを安定して記録し、全7storeを上限方式で検査する。上限以内の削減はchip-awayとして許容し、新たな増加・重複・並び/形式不正・stored.rulesと物理storeの不一致を拒否する。Self17case・Dependency4caseと、既存の専用Integrity2case経路を分離して維持した。

- 初回承認178件は既存群内の未記録依存（D1+119/D3+33/D5+26）の補正。初回移行上限2156/1325/1876の履歴、削減0・新class-pair0を保持する。
- 後発main PR4070のgetSlug追加1件は初回178とは別の明示承認。D1旧2156行の生bytesを保持し、main106行からoccurrence1を導出して自然順436行へ追加した。該当prefix6→7、最終D1上限2157。他7store/他6上限に追加緩和はない。
- main PR4023の同ドメインconstructor引数追加にD3/D5各1行を同件数で追従した。追加引数部分を除けば旧行と完全一致、各prefix3件・occurrence・ソート・上限は維持。
- mainのD3T返済3行（追加0/削除3）を引き継ぎ、上限7325→7322を採用。旧履歴原文とR1履歴は保全した。
- R3不整合は承認されたSelfTestの重み1行だけ削除。archunitタグ/判定/guardを維持し、重み全量再生成なし。
- 開発規約分類は `backend/.claudecode.md` 厳密1pathのみtoolingへ追加。近似path・本番・buildのapp分類は維持した。

最終上限はAuthz0/D1 2157/D3 1325/D3T7322/D2b569/D5 1876/ServiceAPI600。F固有の本番コード・build・CI・時間制限変更は0。formatter/freeze除外/番人判定の追加緩和はない。詳細な初回移行根拠は[記録移行証拠](CMP-261021-2213-l1-f-recording-migration.md)と[返済履歴](../architecture/archunit_freeze_history.md)に残す。

## AC・実装・green試験

正本は返済計画§7.4/§9.1。既存AC1〜10と後発SCOPE/R3/GETSLUGを区別する。試験のcase名・実装pathは以下の通り。静的照合にはJUnit XMLが無いことを明示する。

| AC | 条件 | 実装・試験名 | 確認方法 |
|---|---|---|---|
| AC-1 | 全7storeでN/N-1/0はpass、N+1はfail。上限0も検証する | `backend/src/test/java/com/mannschaft/app/common/architecture/ArchUnitFreezeStoreIntegritySelfTest.java` — 全七ストアは上限ちょうどと一件未満と空で通り一件超過を拒否する | 選択green XML |
| AC-2 | 重複・逆順・空白/空行・別形式・旧形式・suffix欠落を拒否する | `backend/src/test/java/com/mannschaft/app/common/architecture/ArchUnitFreezeStoreIntegritySelfTest.java` — 全七形式の重複と逆順と空白行と空行と別形式は拒否する／三依存ストアの旧形式と識別suffix欠落は拒否する | 選択green XML |
| AC-3 | stored.rulesの閉集合・UUID対応/一意・重複keyと物理store欠落/余剰を検査する | `backend/src/test/java/com/mannschaft/app/common/architecture/ArchUnitFreezeStoreIntegritySelfTest.java` — ServiceAPI登録がstoredRulesから欠落したら拒否する／未知の八番目ルールを拒否する／UUIDの取り違えを拒否する／別ルールに同じUUIDを期待しても拒否する／storedRulesの同じキー二重登録は同じ値でも拒否する／ServiceAPI実ストアの欠損を拒否する／余分な実ファイルを拒否する | 選択green XML |
| AC-4 | D1/D3/D5の検出・除外・Dependency件数を維持し全行を一意に記録する | `backend/src/test/java/com/mannschaft/app/common/architecture/DependencyFreezeRecordingTest.java` — D3の注釈入口と各番人の自ドメイン除外を維持する／三番人はfieldとconstructorとsignatureと同一行二callを個別記録する | 選択green XML |
| AC-5 | field・constructor・method signature・call・同一行同call二回を実javac検体で保持する | `backend/src/test/java/com/mannschaft/app/common/architecture/DependencyFreezeRecordingTest.java` — 三番人はfieldとconstructorとsignatureと同一行二callを個別記録する | 選択green XML |
| AC-6 | 再importとクラス走査順変更でも記録multisetが安定する | `backend/src/test/java/com/mannschaft/app/common/architecture/DependencyFreezeRecordingTest.java` — 再importとクラス走査順が変わっても記録multisetは変わらない | 選択green XML |
| AC-7 | 行移動・同一行/別行整形はpass、追加はfail、一件減少だけchip-awayする | `backend/src/test/java/com/mannschaft/app/common/architecture/DependencyFreezeRecordingTest.java` — 既定freezeは行移動と別行同一行の整形を許し増加を拒否し減少を縮小する | 選択green XML |
| AC-8 | 初回旧prefix頻度は承認178だけを除き一致し削減0/新class-pair0。後発getSlug承認1件はAC-GETSLUGへ分離 | `docs/evidence/CMP-261021-2213-l1-f-recording-migration.md` — 固定before/候補原本の全prefix頻度MapとL0-C照合、最新main/F原本8blob照合 | 生bytes・全prefix頻度Map・履歴の静的照合 |
| AC-9 | 移設した旧履歴原文を保全しmainのR1返済履歴を欠落なく引き継ぐ | `docs/architecture/archunit_freeze_history.md` — 旧履歴prefix生bytes完全一致とR1履歴原文照合 | 生bytes・全prefix頻度Map・履歴の静的照合 |
| AC-10 | 専用guard既存2case経路とbuild設定を維持し規約同期。head1487の選択原本・各runner after8SHA不変をCI成功と独立照合で確認 | `backend/src/test/java/com/mannschaft/app/common/architecture/ArchUnitFreezeStoreIntegrityTest.java` — ストアUUID対応の裏取り／凍結ストアの行数が期待値と一致する（6runner各2case=12成功） | 選択green XML |
| AC-SCOPE | backend/.claudecode.md厳密一致のみtooling、テスト混在test-only。類似path・本番・buildはapp | `scripts/completion-evidence.test.mjs` — 開発規約backend/.claudecode.mdだけはtooling、テストとの混在はtest-only、類似pathや本番変更はapp | Harness分類回帰green |
| AC-R3 | archTestタグを維持しSelfTestの通常shard重み1行だけ削除。他weights bytes・guard・8store・上限不変、全量再生成なし | `backend/src/test/java/com/mannschaft/app/common/architecture/ArchUnitTestTagGuardTest.java` — ArchUnitTestTagGuardTest.テストツリー全体に違反が無い | shard4のR3 XML 9case green |
| AC-GETSLUG | 初回178とは別のユーザー承認1件。D1旧2156行の生bytes保持＋getSlug1行、最終上限2157/prefix6→7。他7store/他6cap/判定/guard/tag/weights/timeouts不変 | `docs/evidence/CMP-261021-2213-l1-f-recording-migration.md` — getSlug実106行からoccurrence1を導出し自然順436行へ追加。旧2156行完全保持・頻度Map承認1以外差0の生blob照合<br>`backend/src/test/java/com/mannschaft/app/common/architecture/CrossDomainEntityImportArchTest.java` — no_cross_domain_entity_dependency<br>`backend/src/test/java/com/mannschaft/app/common/architecture/ArchUnitFreezeStoreIntegrityTest.java` — 凍結ストアの行数が期待値と一致する | 承認1件の静的照合＋D1/専用Integrity green、after8SHA一致 |
| AC-MAIN-CI | PR4104のcompile分離後、修正headのmanual全量CIと修正merge SHAを含むmain統合後CIの全6shard・Arch・workflow最終successを確認する。BuildDeployの条件skipと実デプロイを区別し、通常85分改善を先取りしない | [manual40141590669](https://github.com/kenta-0420/mannschaft/actions/runs/40141590669) / [main40148629302](https://github.com/kenta-0420/mannschaft/actions/runs/40148629302) | 実API照合：全9job success、6shardのGradle最終成功・専用guard計12件成功 |

## CIと原本の確認

[Backend40073246043](https://github.com/kenta-0420/mannschaft/actions/runs/40073246043)はworkflow最新attempt2 completed/success、全12job success。失敗jobだけを同SHAで一度再実行したため、採用したshard3はattempt2、他5shardとarchTestはattempt1の成功jobである。名前やlatestだけでは選ばず、uploadログのartifact ID/name、jobのrun/head/attempt/success、artifact作成日時を照合した。

| 対象 | 選択原本の結果 |
|---|---|
| archTest | 347case、Self17/Dependency4を含む |
| engine | 18suite/37case、従来suite集合と一致 |
| 専用Integrity | 各6runner2case、計12case |
| 通常R3 | shard4の9case、全testcase@classnameがArchUnitTestTagGuardTest完全修飾名 |
| ファイル/ケース | 元118files（70XML/396cases＋48store）、同じ7ZIPから別選択したR3 1XML/9case、計119files/405case |
| 成否 | 選択対象はfailures0/errors0/skipped0。normal全量のskip0を意味しない |
| after freeze原本 | 各6runner×8=48store全てhead1487の生blob SHA-256に一致 |

同じ取得bytesからR3をRAM選択し追加ZIPは取得していない。ZIP256MiB/entry16MiB制限、path・symlink・暗号化・重複entry検査を維持し、選択対象のXMLと48storeを実体再読で照合した。48storeは各採用成功runnerのafter一致であり、全6runnerが別個に再実行された証明ではない。

| artifact | attempt | 成功job（uploadログ） | artifact ID | created_at UTC | ZIP SHA-256 |
|---|---|---|---|---|---|
| shard0 | 1 | [120811127444](https://github.com/kenta-0420/mannschaft/actions/runs/40073246043/job/120811127444) | 12553922104 | 2026-10-24T08:21:55Z | 7fccc6fca1b5ad3c0df1cbde3de16ba8c49db361a23b83dcb3c54d42b3384cb6 |
| shard1 | 1 | [120811127489](https://github.com/kenta-0420/mannschaft/actions/runs/40073246043/job/120811127489) | 12554191823 | 2026-10-24T08:31:13Z | 016e1947bd1951ad764a2830c15349e17a2f355d5c5c36fc137aaa5088c5cf7a |
| shard2 | 1 | [120811127459](https://github.com/kenta-0420/mannschaft/actions/runs/40073246043/job/120811127459) | 12554275433 | 2026-10-24T08:20:56Z | c8285be3181249ffc078732ceb38488a550ee5ddd29f2ee0c4a69ed7225f65b2 |
| shard3 | 2 | [120831564863](https://github.com/kenta-0420/mannschaft/actions/runs/40073246043/job/120831564863) | 12556992357 | 2026-10-24T10:21:49Z | bb5d7b7b5ddcb5071360a4b47bfe0beef7f8fb3cd382441fb8bf812b56969073 |
| shard4 | 1 | [120811127476](https://github.com/kenta-0420/mannschaft/actions/runs/40073246043/job/120811127476) | 12553880713 | 2026-10-24T08:07:37Z | 4705a2afb1306ac893f868b2de7fbd7863d0cbb3bd6494c82a009e7a03edad86 |
| shard5 | 1 | [120811127553](https://github.com/kenta-0420/mannschaft/actions/runs/40073246043/job/120811127553) | 12554850538 | 2026-10-24T08:39:31Z | c1c38fefb68badf72b2f9b06b518dc1e3ae38c34a5900d495b9c3296ccb815ac |
| archTest | 1 | [120811127329](https://github.com/kenta-0420/mannschaft/actions/runs/40073246043/job/120811127329) | 12552117820 | 2026-10-24T07:16:40Z | 9a710a1e8e9fbc62396b564f9d16c1caef38fd1d077b41a53edeec5c03a93111 |

原本照合のmanifest SHA-256は `86988dd497a9c60a2b37387d62ea0120a58bded3589146b7ef4b8e794b005565`、artifact/job provenanceは `96b6d3b4bdc508097f8aa6c1ef4748c83340fae711e9bea2c3033cd0156eb981`、R3 XMLは `e183ce1ab7a7769621c4c9f3f352ff32ee9ec9006100ccfd2296ebb8431a6392`、R3別照合記録は `d39c9015853c5abc0280277090bd1f462b4d162d73b4af580a2fba1bb57899ff`。上記の公開job uploadログとartifact IDで出所を追跡できる。期限付きartifactが失効した場合、hashだけから原本を再取得することはできない。

| storeファイル | 物理行数 | head1487/全6runner after共通SHA-256 |
|---|---|---|
| `296295dd-06cf-4f7b-bf82-315ba12ff501` | 7322 | d709ed2f49d2b5187820feca2056f087ba5f2cbed9523a2c8fa702264afa1c66 |
| `2c0ba995-682e-4f80-a5a5-f68c835b720d` | 569 | e18798e22df8611e3d7cbd45c28a823b13e1b935a1ab596352a308d6a7bd7ee8 |
| `427c445d-37ce-4d6e-b095-a1733efe209f` | 1876 | 0088b3863b88bb33e4dbe5254ec9723e0fcb499f5d1ef33f1b9420087a8e3b89 |
| `584c3a46-b9c1-4cc2-bf74-e0a18eab1bef` | 2157 | 28845447b0077a31c8d1716e4d0016d91bfb3bfc92c43f61e0aa7b27800e84d7 |
| `93124b52-f328-4f09-8c4f-6d022519fae2` | 600 | 19022a1dbb002081981235151f3e732937d9a3f4473225f9ffca9b5dbc6760d7 |
| `9ed4737d-c74f-4374-923e-4663d3c9e256` | 0 | e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855 |
| `f14374b1-655e-4df2-8e82-2d79c8df9174` | 1325 | 9cf3d2f79c77d645e45ca4f2068264348c7cc28368bdc5697e323cb027d8ef01 |
| `stored.rules` | 9 | af7cef906e9abf2bb093f875872c05083898fb81243ee9f3397e1ef3c7ddb4c9 |

[Harness40073246008](https://github.com/kenta-0420/mannschaft/actions/runs/40073246008/job/120811035589)は71 tests/pass71/fail0/skip0。追加分類回帰はPASS（case0.315692ms）。AC8テストのcase wall durationは27.287733ms、内部分類100ms未満assertは成功した。case wallを内部infer計測値そのものと混同しない。公開jobログ原本は24246 bytes、SHA-256 `4421b326e6f0ec0c8891fb88c170287850f6ab65eee7d3a36f7d9b32dd4577e6`。

## 回帰のredとgreen

- 初回試練先行commit `11b6789fffe18e56f20dc42ccd4bc4aa342d973b`、本実装 `fe425e71a9`。[公開red報告](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6329255852)はarchTest347・想定失敗9/error0・専用Integrity2成功。[前報告](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6329199570)はunique expected6/actual1。今回当時raw XMLを再取得したという意味ではない。上限運用・同一行description/occurrenceの多重度保全は最新BackendのSelf/Dependency greenで確認。
- [Backend39964737374](https://github.com/kenta-0420/mannschaft/actions/runs/39964737374)のD3/D5 constructor description各1件failureは、同件数descriptor置換後、最新Backend実解析/after原本でgreen。
- [Backend39967912936](https://github.com/kenta-0420/mannschaft/actions/runs/39967912936)のR3 failureはSelfTest重み1行削除後、`ArchUnitTestTagGuardTest.テストツリー全体に違反が無い` を含む最新shard4の9case greenで確認。同runのshard3 timeoutをR3修正の効果とはしない。

scope分類の実装/試験pathは `scripts/completion-evidence.mjs` と `scripts/completion-evidence.test.mjs:313:1`。保存したローカル原本の必要部分を以下に転記する。

```text
red（修正前、対象回帰単独）
test: scripts/completion-evidence.test.mjs:313:1
AssertionError: 'app' !== 'tooling'
tests 1 / pass 0 / fail 1 / exit=1

green-attempt（厳密backend/.claudecode.md分類修正後）
開発規約backend/.claudecode.mdだけはtooling、テストとの混在はtest-only、類似pathや本番変更はapp: PASS
tests 21 / pass 20 / fail 1 / exit=1
残存失敗: AC-8 assert 111.1648ms > 100ms
```

ローカルgreen-attemptはscope回帰がgreenという意味であり全suite成功ではない。AC8表示case wall261.4188msとassert対象111.1648msを区別する。変更前も101.7022/170.776msで失敗し、100ms閾値は変更していない。最新Harnessは同じ閾値で全71件green。Windowsローカル遅延の原因が解決したとは主張しない。

## 攻め口5類型と検証範囲

| 類型 | 対応と確認 | 限界 |
|---|---|---|
| 空・0・null | AC1/2/3の空store・cap0・空行/空白・欠損をSelf green XMLで検査 | 業務null応答の試験ではない |
| 境界 | AC1 N/N-1/0/N+1、AC7同件数/1件増減をgreen確認 | 専用検体の境界 |
| 途中失敗 | AC3登録/ファイル欠損拒否、AC7増加拒否と減少、AC6再importをgreen確認 | 一時store/記録処理に限定。永続業務更新はなく業務rollback/補償は対象外 |
| 認可 | F固有app/auth/UI/API挙動変更なし | 対象本番操作/導線がなくAPI実機E2E・ロール横断・自由探索は未実施、test-onlyでnot-applicable。CIを実機代替とは呼ばない |
| 性能・大入力 | 自然順ソート・同一行多重度・実全量storeとHarness分類3000path/100ms条件 | Harnessの分類性能はF記録処理のSLAではない。専用benchmarkは追加せず、normal timeout原因は未解決 |

## timeout・未読証拠の限界

最新Backend attempt1はshard3だけ85分timeout、他5shard成功。normal afterSuiteは5097 tests/5095pass/0fail/2skip。診断ではtop-level499・nested込み1351クラス全て終了SUCCESSで、旧82ca成功run39992224568と集合が完全一致。並列累積クラス時間は7312.174→9118.270秒（1.247倍）、旧1秒以上197クラスの177が遅延した。旧最終classからtask完了61.164秒に対し、新最終classからtimeout40.155秒、afterSuite後30.489秒しか残らなかった。単一hangの証拠はなく全般slowdown/終端予算不足が候補だが、host・DB・GC・report I/Oの原因切分けはできない。

attempt1の `TEST-com.mannschaft.app.activity.ActivityAutomaticCreationPathsIntegrationTest.xml` は133,962,141 bytesで16MiB安全上限を超え、内容を読んでいない。旧成功の同XML個別サイズは未取得。XML ZIP全体は旧38,335,257→新38,576,390 bytes（約0.63%差）だが、圧縮率と個別内訳が不明なため新たな巨大化やI/O因果を証明しない。選択XML405caseのgreenをnormal全量XML検証済みへ広げない。診断result.jsonのexit0よりGitHubのtimeout/failureを優先した。

同SHAのfailedjobsを一度だけ再実行したattempt2はsuccessとなった。code/weights/build/時間制限を変えておらず、再実行成功を性能原因の解決や性能改善の証明には使わない。旧Backend39967912936にもtimeoutがあり、旧82ca成功は旧headの証拠として保持する。

## 検分・merge・check世代

head1487の静的差分、ACと試験、選択XML/48store原本を独立照合しpassed、finding0。scope分類/許可baselineとcap差分/他不変条件を確認した。実装PRのマージ前最新checkは23 unique ID/name、22success＋週次check skip1。本文更新後の[Task-List PR Gate40086713399/job120851037946](https://github.com/kenta-0420/mannschaft/actions/runs/40086713399/job/120851037946)がsuccessであり、旧run40073311091を代用していない。更新後check世代照合cacheのSHA-256は `fd54aae768b837e2369b7dc5113470545d21afb7a9778b1ffb35bfd4ed7e98f8`。

ユーザーの明示的な`--admin`指示に基づきsquash/admin mergeした。複数回main追従済み、実装headのPR CI全成功、MERGEABLEで、残る交差は`docs/task-list.md`の別CMP行のみ。同一行・code/store重複はなかった。merge実体とhead1487のF19pathsの比較でも、本人台帳行は一致、残18pathsの生blob差0、台帳の他CMP行のみ差がある。新mainとの統合CIを事前に実行済みという意味ではない。

マージ後mainの[Backend Build/Test/Deploy40086938407](https://github.com/kenta-0420/mannschaft/actions/runs/40086938407)（exact SHA `23daf720490cdda597b4596cd03f7fd3f5ac9b81`）はfailure。このfailure確認時点では、全shardとworkflow最終successの確認までL1-Fを完了保留とした。後述のPR4104修正後main成功により、この保留を解除した。[gitleaks40086938366](https://github.com/kenta-0420/mannschaft/actions/runs/40086938366)はsuccessだが、この条件の代わりにはならない。既存の完了証拠JSONのci[]・stageと本報告の405選択case/48storeは実装headのpremerge検証証拠として保全する。未成功かつheadの異なるmain runをJSONのci[]へ追加しない。

完了証拠CLIは台帳・JSON・参照の構造照合であり、GitHub APIのPR merge/check世代/CI成否照合とは別である。後者は公開PR/各CIとマージ時点のAPI情報で確認する。ローカルheavy/Gradle/WSL/server/container起動は行っていない。

## main統合後shard3 timeoutの是正

[main run40086938407/job120851787348](https://github.com/kenta-0420/mannschaft/actions/runs/40086938407/job/120851787348)（exact SHA `23daf720490cdda597b4596cd03f7fd3f5ac9b81`）はshard3が85分timeoutでfailure。他5shardとArchはsuccess、Build/Deployはskipであり、この時点ではL1-Fの完了保留を維持した。失敗job全文ログ原本は142288 bytes、SHA-256 `3467bd6c5cd498b3b73cbc0439d26e819d08e05bd4908b0d3db663f50bc36f87`。

| 観測値 | main失敗 | 実装head1487のPR attempt2成功 |
|---|---|---|
| test command開始→testClasses | 172.709秒 | 15.739秒 |
| test command開始→normal test開始 | 214.312秒 | 56.946秒 |
| normal開始→afterSuite | 最終markerなし・未確定 | 4854.999秒 |
| afterSuite→Jacoco/BUILD最終 | 未確定 | 82.095秒 |
| normal＋終端/Jacoco | 未確定 | 4937.094秒（82分17.094秒） |
| normal開始→実timeoutの残り枠 | 4898.210秒（81分38.210秒） | 成功command全体4994.040秒、85分まで105.960秒の余白 |

mainはnormal開始前にPR成功時より157.366秒余計に消費した。PR成功時と同じnormal＋終端/Jacoco所要を仮定しても、mainの実timeoutまでの残り枠には38.884秒収まらない。ただしこれは反実仮想の比較であり、個別テスト遅延や単独原因の証明ではない。mainログにはci-class markerとnormal最終test-countがなく、normal最終件数/失敗数・class別所要は未確定。旧PR attempt1のtimeout後にある別post処理のBUILD SUCCESSFULは、失敗test commandの成功や終端所要として扱わない。

是正は `.github/workflows/backend-deploy.yml` のtest jobで、PR CIと同じ `./gradlew compileJava compileTestJava --no-daemon --build-cache` を独立stepとして先行させる。test commandにもPR同様の`--build-cache`を明記するが、`org.gradle.caching=true`は元から有効であり、cacheを新たに有効化したという意味ではない。test stepの通常85分/migration込み105分、job全体100/120分、依存・番人・store・weights・context cache5・fork設定は変更しない。

この変更後の通常85分完走はまだ実証していない。後述のmanual/mainはmigration込みfull CIの成功であり、通常85分条件の改善証明とは区別する。compile分離は時間枠内の作業配分を変えるもので、総job時間の短縮や遅延原因の解消を証明していない。実装headの既存premerge CI/405選択case/48store証拠は保持し、未成功のmain runを既存JSONのci[]へ追加しない。

## PR4104修正後のmain統合後検証と完了確認

[PR4104](https://github.com/kenta-0420/mannschaft/pull/4104)は2026-10-25T00:56:08Zにmerge SHA `d4f8b6ece263fb0ea7f6e7d367e6fefb364cea12` として着地した。ユーザーの明示admin指示に基づき、先行mainとの差は他docsのみ・対象2ファイルの重複0を確認してmergeした。修正headの[manual40141590669](https://github.com/kenta-0420/mannschaft/actions/runs/40141590669)はexact `0b24f38a4fe9160c99841ad930051fba0a25af32`、全12job successをAPIで照合済み。

[main40148629302](https://github.com/kenta-0420/mannschaft/actions/runs/40148629302)はattempt1、exact headが同じ修正merge SHA `d4f8b6ece263fb0ea7f6e7d367e6fefb364cea12`、status=completed/conclusion=success。全9jobがsuccessであり、6shardのCompile/Run tests各step、ArchのGradle最終成功を確認した。以下は実API metadataと公開jobログに基づくUTC時刻である。

| shard / job | job開始→終了 UTC | Compile開始→終了 UTC | Run tests開始→終了 UTC | Gradle所要 | 専用Integrity |
|---|---|---|---|---|---|
| [0 / 121035329602](https://github.com/kenta-0420/mannschaft/actions/runs/40148629302/job/121035329602) | 2026-10-25T00:56:37Z → 2026-10-25T02:22:38Z | 2026-10-25T00:56:59Z → 2026-10-25T01:00:04Z | 2026-10-25T01:00:04Z → 2026-10-25T02:22:22Z | 1h 22m 17s | 2/2 success |
| [1 / 121035329587](https://github.com/kenta-0420/mannschaft/actions/runs/40148629302/job/121035329587) | 2026-10-25T00:56:36Z → 2026-10-25T02:25:54Z | 2026-10-25T00:57:00Z → 2026-10-25T00:59:59Z | 2026-10-25T00:59:59Z → 2026-10-25T02:25:32Z | 1h 25m 32s | 2/2 success |
| [2 / 121035329612](https://github.com/kenta-0420/mannschaft/actions/runs/40148629302/job/121035329612) | 2026-10-25T00:56:37Z → 2026-10-25T02:31:55Z | 2026-10-25T00:56:59Z → 2026-10-25T00:59:59Z | 2026-10-25T00:59:59Z → 2026-10-25T02:31:29Z | 1h 31m 29s | 2/2 success |
| [3 / 121035329637](https://github.com/kenta-0420/mannschaft/actions/runs/40148629302/job/121035329637) | 2026-10-25T00:56:36Z → 2026-10-25T02:33:31Z | 2026-10-25T00:56:53Z → 2026-10-25T00:59:42Z | 2026-10-25T00:59:42Z → 2026-10-25T02:33:18Z | 1h 33m 35s | 2/2 success |
| [4 / 121035329574](https://github.com/kenta-0420/mannschaft/actions/runs/40148629302/job/121035329574) | 2026-10-25T00:56:36Z → 2026-10-25T02:30:03Z | 2026-10-25T00:56:53Z → 2026-10-25T00:59:59Z | 2026-10-25T00:59:59Z → 2026-10-25T02:29:55Z | 1h 29m 55s | 2/2 success |
| [5 / 121035329596](https://github.com/kenta-0420/mannschaft/actions/runs/40148629302/job/121035329596) | 2026-10-25T00:56:36Z → 2026-10-25T02:13:32Z | 2026-10-25T00:56:57Z → 2026-10-25T00:59:04Z | 2026-10-25T00:59:04Z → 2026-10-25T02:13:23Z | 1h 14m 18s | 2/2 success |

| job | 開始 UTC | 終了 UTC | 結果 |
|---|---|---|---|
| [ArchUnit tests (separate JVM)](https://github.com/kenta-0420/mannschaft/actions/runs/40148629302/job/121035260865) | 2026-10-25T00:56:13Z | 2026-10-25T00:59:25Z | success |
| [Build & Deploy](https://github.com/kenta-0420/mannschaft/actions/runs/40148629302/job/121054071170) | 2026-10-25T02:33:34Z | 2026-10-25T02:33:39Z | success |

全6shardのRun testsでGradle最終`BUILD SUCCESSFUL`、専用Integrityは各2件実行/2成功・failure0/skip0、計12件を公開ログで確認した。これはmainのguard実行証拠であり、mainの48store raw原本を新規に取得・照合したという意味ではない。405選択case・48store/8SHAは実装head1487のpremerge証拠として上記のまま保持する。

mainのfullMigration=true、今回はmigration込み105分test/120分job側で成功した。通常85分条件の完走・compile分離による総時間短縮・過去timeoutの性能原因解明は未実証のまま。修正で番人・freeze上限/store・weights・fork/context cache・時間制限を緩めていない。

Build & Deploy jobはsuccessだが、`Check AWS credentials`成功後、checkout/JAR build/AWS認証/ECR/ECS等の実ビルド・デプロイstepは全て条件skip。これは正常な条件分岐の完了であり、実デプロイ済みとは扱わない。同SHAのchecksは14件（13success/週次1skip/fail0）、gitleaksのPR差分スキャンはsuccess、週次全履歴はskip。

独立API照合terminal metadataのSHA-256は `f67791ce44295bc4f45ef742660e33d2ee8dd5c355b0cf66215328bdbf3c1646`。native run metadataは `7b71a3d32fa09b95f29dd93f7180cc4c76685f988abf51d1b8b21ed062cce16c`、native jobsは `5b73cd6a3392ce0bbd80812b5facd3c94434fadc37ee0aee083b834e96f29942`、6jobログ/guard/最終checksを照合したsummaryは `019ec03416e71c5c8535825af52d73cc2834eff81cd2cfdeee190bed8fdf71f0`。公開run/job URLとhashで出所を追跡する。headの異なるmanual/mainrunは既存JSONのci[]へ追加せず、追加AC-MAIN-CIの証拠として記録する。

### 誤完了報告と訂正の履歴

実装PR4045のマージとpremerge CI成功だけで一度L1-F完了と報告したが、main統合後CIが未完了であるとのユーザー指摘を受け撤回した。親Issueコメントを「実装/マージ済み・統合後検証中」に訂正し、共有資産解放依頼を取り下げ、PR4093で台帳を実装中・完了保留へ戻した。その後、元main23dafのshard3 failureを確認しPR4104でcompileを独立stepへ分離した。今回の修正後main実成功を確認して初めて保留を解除する。旧失敗、巨大normal XML未読、Windows AC8赤、通常85分未実証という限界は消さない。親CMP・他担当の状態や調整台帳のhold解放を、この完了記録だけで変更したとは扱わない。
