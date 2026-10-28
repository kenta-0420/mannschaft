# CMP-261027-1646 L1-B 実装・検証報告

実装PR: [#4169](https://github.com/kenta-0420/mannschaft/pull/4169)。移設検証HEAD `a8b7d8b999973d2c138ffecce2fbe2233f6bb09a`。main統合HEAD `f2817eb94326b413122c69f4b0c409288c8982a6` の[CI40648568860](https://github.com/kenta-0420/mannschaft/actions/runs/40648568860)は6fresh・失敗0で成功し、[採取結果](CMP-261027-1646/integration-ci.md)を別記した。その後docsのみを通常追従した実装HEADは `e67a77172f67ddbacfe4c8d8f9504eae8f54b004`。[e67 CI40662266445](https://github.com/kenta-0420/mannschaft/actions/runs/40662266445)は6cache・観測異常0で成功し、PR4169は通常squash merge済み。

最終e67の同HEAD CI成功・実装mergeを照合した[完了JSON](CMP-261027-1646.json)に対応する検証報告である。[証拠入口](CMP-261027-1646/README.md)にbuild・実機/探索・検分をまとめる。[source bridge](CMP-261027-1646/build/integration-261028-source-bridge.json)で元の移設変更11非Markdownファイルのblob一致を確認したが、upstreamの全製品/テスト/画面のbytes同一性や、旧a8b実証の統合HEADでの再実施を意味しない。

本報告は実装担当の構成・割当・境界・負試験・重み再生成・OpenAPI実生成の結果である。最終CI・検分・実機・探索とAC全体の出荷判定は、別の完了証拠と合わせて判定する。

## 実装と限定裁可

単一appを維持し、included build `backend/build-logic` に既存の依存宣言・共通Test・ShardAssignment/coverage・JaCoCo設定を移した。rootには外部plugin版、app固有タスク、Boot/OpenAPI/OWASP設定を残した。Java/JaCoCoは従来順序でBootより先に適用し、conventionの型accessorにも冪等適用する。アプリ依存をbuild-logic自身の依存へ追加していない。

親Issue4022の[承認6436425040](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6436425040)に従い、StripeJavaVersionGuardTestは候補2pathだけを新依存定義へ変更した。28固定・正規表現・29以上拒否・欠損failは不変、旧buildへのfallbackはない。規約3文書と検知path3箇所を同期した。

## 固定条件と原本

- 移設前source: `5990c19a173b6815b08ed2e76ea9d354310ffa74`。旧重みでの移設後: `6f6c1eccaf298db643d5772b2fd988151b9fda89`。新重みでの移設後: `a8b7d8b999973d2c138ffecce2fbe2233f6bb09a`。
- WSL Linux / JDK21.0.12.1 / Gradle8.14.4。同じproperty・scriptと同じ重みbytesを両側へ適用。全heavyは既存gradle-turnstile経由、既存サーバー停止なし。
- clean sourceと固定HEADを既存scripts/verification-run.mjsで確認し、専用Linuxコピーで実行。native Git archiveに限定した`-c core.autocrlf=false`は、Git blobとshell原本bytes一致の検証を付して適用。共有Git設定は変更していない。
- raw stdoutは除外・整形・正規化なしでbytes比較。候補TSVは実Gradle Test.candidateClassFilesを決定的順序で出力し、engine/tag/heap/fork設定を別TSVへ採取。割当を別言語で再実装していない。
- source原本・stdout/stderr/exit/XML/TSV/receiptの採取時位置: `/home/kenta/verification-evidence/` の下記run。支援script・分類manifestの採取時位置は `.claude/evidence/CMP-261027-1646-l1b-support/`。原本pathは恒久保持や現在の実在を保証しない。小さな証拠JSON/XML要約・画像はGitへ保存し、全量TSV/archive等の重複rawはdurable記録確定後に自隊cleanupで撤去する。[cleanup/保持記録](CMP-261027-1646/cleanup.md)で1root撤去・残14承認待ち保持を記録し、全量raw cleanup未完了を維持する。本報告に原本の件数とSHAを残す。

| run | 用途・結果 |
|---|---|
| before-4 | 移設前・旧重み。全手順成功、cleanup=removed |
| after-oldweights-1 | 移設後・旧重み。76比較一致とStripe負試験成功。後述の補助init設定エラーで残試験前に停止、cleanup=removed |
| before-finalweights-1 / after-finalweights-1 | 同一新重みでの前後比較。全手順成功、cleanup=removed |
| after-independent-1 | archTest0件拒否を正しい理由で確認。freeze欠損はonlyIf根原因が短縮stderrに隠れたため補助grepで停止、cleanup=removed |
| after-freeze-openapi-1 / after-arch-continue-1 | a8bのstacktrace付きfreeze欠損・実OpenAPIと、CI同等continue条件のfinalizer試験。全手順成功、cleanup=removed |

## B-1 構成・依存・タスク一覧

旧重み・新重みの両方で下記9本がexit0、前後stdout bytes完全一致。タスク追加行等を消す比較は行っていない。

| raw stdout | bytes | SHA256（前後同一） |
|---|---:|---|
| tasks | 5028 | `4e8ab3df4d7e1177c7131c4a79c4d6e64af6f466dbdbeb916307d65991b81ad5` |
| dependencies-compileClasspath | 69250 | `e9d656f8152ccf68638186b7ffedf4d37ace5443f0f0d4e3d013b9b9d1cedb63` |
| dependencies-runtimeClasspath | 76617 | `b982e20586fd8fbe328e4166724adf85050a12f30615ec853e24f93636a53166` |
| dependencies-annotationProcessor | 503 | `a59d316d1aa67354b9c387e51a1059ca44cb63adfa4e1004534c7c2c328b30b1` |
| dependencies-testCompileClasspath | 74389 | `ec5d641a14807087d9ee88f31e250323ea3cd80b1e6fb0496f5cb20c36f8f566` |
| dependencies-testRuntimeClasspath | 82554 | `4a9cb14d0f0e6410736e8eaca29ad3363fcf2bd13c684410c1e702d496b56d20` |
| graph-test | 244 | `ad99034b14e569dfe89afee1dbf254ed119015a2acdbec6403d718b8baeaa790` |
| graph-check | 277 | `1ab486d9a4890ab05a999bc78f9953ac867024f2aaa8b904f7a5dc9915feef66` |
| graph-archUnitTest | 204 | `80d8b7cb21b98cd411094fc6e0c492126269d5e9a8e2155bd3790418fa5d299e` |

## B-2/B-3/B-4 実候補・空・境界

旧重みと新重みそれぞれ、unsharded + 重み有無×migration除外真偽×6indexの24profile + total1/2/6/非数値×index0/末端/非数値の12profile、計37profileの候補と設定が全行bytes一致。各回class原一覧とhelper境界を含め76比較一致。コンパイル済みraw classは9418、top-levelは3421で空採取ではない。unshardedの5task候補合計47087行（test9415、他4task各9418）。これはJUnit実行件数ではなく、freezeの名前filterは別に働く。

両側verifyShardCoverageは4profileすべてで取りこぼし0・重複0・migration漏れ0・ネスト分断0。index-1/6は正しいshard.index原因で拒否。実helper呼出しで負hash/Int.MIN_VALUE、unsigned、total1/2/6、同重み・同累計の先頭優先、重み欠損/空/不正値/重複key、directory/非class、未計測fallback、nested、migration AND条件を検証した。

| 重み | 採取 | 候補原本SHA256（37profile） |
|---|---|---|
| 旧 | candidates-unsharded.tsv / 47087行 | `77c29798fda0603db975e438ffff0cb9d3061cdb3dbe1363a76a2e714db6ad97` |
| 旧 | candidates-present-false-0.tsv / 23363行 | `dc8217f3d58e5e0a8dc31f3a4589f535e7a8219031479ae43fe3adbb4cb79ef6` |
| 旧 | candidates-present-false-1.tsv / 23855行 | `428ab5dfbe4c6a1e2bb7884a1068dbe7b4532b2e83a7e64ecdf4daef497db24e` |
| 旧 | candidates-present-false-2.tsv / 23276行 | `864672304a1b365fd1704fd2f2fdecf712641461663155ef025df17af425384c` |
| 旧 | candidates-present-false-3.tsv / 23480行 | `390361d98efd8152f88f7fc75de2b28028aa7452dfb183b532d470fb9541f0f7` |
| 旧 | candidates-present-false-4.tsv / 23681行 | `234cbcb5342b48f0a78daefe4bd1b54a5734d20c4f9808e79fffbbcefb7369ae` |
| 旧 | candidates-present-false-5.tsv / 23612行 | `3dccb5eeb3f4cd04a2bc7d84cb60bcdadc94bb79f32c5c5ed5196e027331394a` |
| 旧 | candidates-present-true-0.tsv / 23099行 | `ae67d589b116530bbd53a416c0dff1b2fae6822708393beeacd401d823675552` |
| 旧 | candidates-present-true-1.tsv / 23504行 | `a1892aa22281471f6a0bf1c1cd1a2918ae1f8a80d6f382e52922a52b63927cb7` |
| 旧 | candidates-present-true-2.tsv / 23192行 | `0324dc4f721e5f53cdde8f75c7d154359134eb22057bc4ed3e66b579a2513df6` |
| 旧 | candidates-present-true-3.tsv / 23423行 | `9acd141020490b2e0dab1da6d051b6a6a8e5aca9f8228df10bbc9a9d5af52fe7` |
| 旧 | candidates-present-true-4.tsv / 23111行 | `72ec9db772265553b8794b2e1e75b0c032ba850e1be432639828f82e36658859` |
| 旧 | candidates-present-true-5.tsv / 23633行 | `c36e7687ab9a7795a94f2154db7230efa283f444357707c642973222eac27f8f` |
| 旧 | candidates-missing-false-0.tsv / 23597行 | `5c8d564a35f263d7cd77a35d8ca8fe5187d63d14c0f3b9626e268e10efde72a0` |
| 旧 | candidates-missing-false-1.tsv / 23129行 | `c4d769579331f92ddbc65fed6585c148845b744e99ced1a92e04a564484b9e27` |
| 旧 | candidates-missing-false-2.tsv / 23870行 | `1e949d6c47dda5ed86f2c9d82411943f26514111f5b51cff27cfb6eb59c6b5a3` |
| 旧 | candidates-missing-false-3.tsv / 23630行 | `b8f88134f3818138b96e9b8ec739ebcdf49f51b297a572eaccf4168c01456ece` |
| 旧 | candidates-missing-false-4.tsv / 23978行 | `95b8a5064ce10ad84788fc41da30c3b7fed03db2af152cf872b5a951e58a22b6` |
| 旧 | candidates-missing-false-5.tsv / 23063行 | `c34a2c903b3e9a6a802fa70b7efb2cc57f5e464eed8f193739280899418985a8` |
| 旧 | candidates-missing-true-0.tsv / 23351行 | `c63651ee49151c95a5be7e82bef11eecc23ccc9b8614e6c4e8c44a4ccaa4402a` |
| 旧 | candidates-missing-true-1.tsv / 22910行 | `a2490a23a04c3cf59a3be795a5235e70f4c1b1eeb518849b15b76ad9952642f6` |
| 旧 | candidates-missing-true-2.tsv / 23660行 | `54e476851ca62386fc5552551ca0998e33f745891febcb0cf0f316b64ecb6ddb` |
| 旧 | candidates-missing-true-3.tsv / 23414行 | `4cee85bcf7987932aef275661f532d8c9228ddbdf486a98a0808a399525f7394` |
| 旧 | candidates-missing-true-4.tsv / 23765行 | `495929ae502a572887b8d262069941a195472d529f63b0e65e6262fa94a9a0e4` |
| 旧 | candidates-missing-true-5.tsv / 22862行 | `b403410d09a2a0f2a38c52c76a081bf7e8eadf88ce6814f70ecd4e9821de207a` |
| 旧 | candidates-boundary-1-0.tsv / 47087行 | `a666f03501a7f398baa38dafd4fcbe334d666f0f54b0461447c68a5ffbf05951` |
| 旧 | candidates-boundary-1-last.tsv / 47087行 | `1360a20f86920e8b327b5458cade4f37b36758d54249f0a7454be6a25ff06181` |
| 旧 | candidates-boundary-1-invalid.tsv / 47087行 | `b628c6ad1e078a57fdc76a3ba5303c25119e388484adf5379eb961bf9d12a9f8` |
| 旧 | candidates-boundary-2-0.tsv / 33164行 | `95bd712b849fbea50ee4a6b02e31a52670e967f8ea48495497cc763ef884fc75` |
| 旧 | candidates-boundary-2-last.tsv / 32759行 | `85eac22054f66f286f003ffeab2d469969a1601888e5ca7aae42c7e0a0d82167` |
| 旧 | candidates-boundary-2-invalid.tsv / 47087行 | `34812f9293e59d74c4ddfdeaa5c1962c080fcac92b967175aa50e687e13a1fee` |
| 旧 | candidates-boundary-6-0.tsv / 23363行 | `bb2480b86a69e882f5752190bcbb7e0ff0571613e76b496e82b2adf780035c48` |
| 旧 | candidates-boundary-6-last.tsv / 23612行 | `f79b88fcd28b847a0d259b347b059b958c154b65fb60c15462a3f9dd063d26c8` |
| 旧 | candidates-boundary-6-invalid.tsv / 47087行 | `da97813b1322f106771b2af7ac06940cada7ef5dd21b5021908763db0412d3ec` |
| 旧 | candidates-boundary-invalid-0.tsv / 47087行 | `f436c311dc9ec98beedaaa9b4e440e1b45270bc7b72671762f11d4ec026a777e` |
| 旧 | candidates-boundary-invalid-last.tsv / 47087行 | `9a8b6055e02bdef148f589f0456fec0f7aca9ba40737f5e5f32ce0899b8f4104` |
| 旧 | candidates-boundary-invalid-invalid.tsv / 47087行 | `896c16feba1320b323dddbcc92da9b1bd8b0d6483457b9b2df1a804a662a3afe` |
| 新 | candidates-unsharded.tsv / 47087行 | `77c29798fda0603db975e438ffff0cb9d3061cdb3dbe1363a76a2e714db6ad97` |
| 新 | candidates-present-false-0.tsv / 23369行 | `a06110eaca40fbd5739891f0cf31987dde2cdaf3aea29a7ba4ac5bcb1f8fc616` |
| 新 | candidates-present-false-1.tsv / 23411行 | `cda02acc2875728e31a1a216c85ed5102d85b45a55cd4b872d35c2bd5a5545c5` |
| 新 | candidates-present-false-2.tsv / 23513行 | `86c7601ce86c981364ec37626a410c3009ad063e5c974ec7d5a3bd860456247d` |
| 新 | candidates-present-false-3.tsv / 23507行 | `24c0fbbedd7175895cf3c646606a3a88196cf6d09ad19757c1405aa64ccce94c` |
| 新 | candidates-present-false-4.tsv / 23561行 | `7f0f5b7f2072e92a0f146db746b4876201dea1f1199b3214f5a7d0727e15559a` |
| 新 | candidates-present-false-5.tsv / 23906行 | `99ad7ad312a10f65cd37d5c61bae3320079886d8d9627ab9a02721d02c386ca1` |
| 新 | candidates-present-true-0.tsv / 23156行 | `c1e55d2e0dde7ea6fc0b522bb87f7f633aedc4cfa9d7b5c8da8c6a385887c636` |
| 新 | candidates-present-true-1.tsv / 23504行 | `be501f314f7c6ef4ef1391e290fe54cc90558f8c9b665ba213b5a3f48ba829c3` |
| 新 | candidates-present-true-2.tsv / 23345行 | `4ad924deb39d1b9f6140179d3168e670b2fd1f2cdccb3fc7cf19d2f9395dd1f7` |
| 新 | candidates-present-true-3.tsv / 23147行 | `d780ec9c57436b54961ec6febae0776c803fd80a464627d237a55524463511f2` |
| 新 | candidates-present-true-4.tsv / 23222行 | `7cff553520f432f0fedb5d893c73fa6c63fe47cb13734556e9add4cb925392e1` |
| 新 | candidates-present-true-5.tsv / 23588行 | `aefa35da0949dfcc58029bcdc0994e97191a67921228b122b847a5e5cb512550` |
| 新 | candidates-missing-false-0.tsv / 23597行 | `5c8d564a35f263d7cd77a35d8ca8fe5187d63d14c0f3b9626e268e10efde72a0` |
| 新 | candidates-missing-false-1.tsv / 23129行 | `c4d769579331f92ddbc65fed6585c148845b744e99ced1a92e04a564484b9e27` |
| 新 | candidates-missing-false-2.tsv / 23870行 | `1e949d6c47dda5ed86f2c9d82411943f26514111f5b51cff27cfb6eb59c6b5a3` |
| 新 | candidates-missing-false-3.tsv / 23630行 | `b8f88134f3818138b96e9b8ec739ebcdf49f51b297a572eaccf4168c01456ece` |
| 新 | candidates-missing-false-4.tsv / 23978行 | `95b8a5064ce10ad84788fc41da30c3b7fed03db2af152cf872b5a951e58a22b6` |
| 新 | candidates-missing-false-5.tsv / 23063行 | `c34a2c903b3e9a6a802fa70b7efb2cc57f5e464eed8f193739280899418985a8` |
| 新 | candidates-missing-true-0.tsv / 23351行 | `c63651ee49151c95a5be7e82bef11eecc23ccc9b8614e6c4e8c44a4ccaa4402a` |
| 新 | candidates-missing-true-1.tsv / 22910行 | `a2490a23a04c3cf59a3be795a5235e70f4c1b1eeb518849b15b76ad9952642f6` |
| 新 | candidates-missing-true-2.tsv / 23660行 | `54e476851ca62386fc5552551ca0998e33f745891febcb0cf0f316b64ecb6ddb` |
| 新 | candidates-missing-true-3.tsv / 23414行 | `4cee85bcf7987932aef275661f532d8c9228ddbdf486a98a0808a399525f7394` |
| 新 | candidates-missing-true-4.tsv / 23765行 | `495929ae502a572887b8d262069941a195472d529f63b0e65e6262fa94a9a0e4` |
| 新 | candidates-missing-true-5.tsv / 22862行 | `b403410d09a2a0f2a38c52c76a081bf7e8eadf88ce6814f70ecd4e9821de207a` |
| 新 | candidates-boundary-1-0.tsv / 47087行 | `a666f03501a7f398baa38dafd4fcbe334d666f0f54b0461447c68a5ffbf05951` |
| 新 | candidates-boundary-1-last.tsv / 47087行 | `1360a20f86920e8b327b5458cade4f37b36758d54249f0a7454be6a25ff06181` |
| 新 | candidates-boundary-1-invalid.tsv / 47087行 | `b628c6ad1e078a57fdc76a3ba5303c25119e388484adf5379eb961bf9d12a9f8` |
| 新 | candidates-boundary-2-0.tsv / 33062行 | `ab9b08b07d3a99a625a7cf848fcfa579b445b8bf46806e561416d07ce8cf8d7f` |
| 新 | candidates-boundary-2-last.tsv / 32861行 | `3e45d2db3265c4618bd5efaa658d8aafca5a546f270ca6ebd8587798ae9dd8ca` |
| 新 | candidates-boundary-2-invalid.tsv / 47087行 | `34812f9293e59d74c4ddfdeaa5c1962c080fcac92b967175aa50e687e13a1fee` |
| 新 | candidates-boundary-6-0.tsv / 23369行 | `272ef1caca6ab6748d4f75ad9ac674827e8ce64e064af5608724f64835246c10` |
| 新 | candidates-boundary-6-last.tsv / 23906行 | `92e5f847836ab37c7f1d92ab4fb5e4ae0de8a587d540a690cd9ca773b21356b2` |
| 新 | candidates-boundary-6-invalid.tsv / 47087行 | `da97813b1322f106771b2af7ac06940cada7ef5dd21b5021908763db0412d3ec` |
| 新 | candidates-boundary-invalid-0.tsv / 47087行 | `f436c311dc9ec98beedaaa9b4e440e1b45270bc7b72671762f11d4ec026a777e` |
| 新 | candidates-boundary-invalid-last.tsv / 47087行 | `9a8b6055e02bdef148f589f0456fec0f7aca9ba40737f5e5f32ce0899b8f4104` |
| 新 | candidates-boundary-invalid-invalid.tsv / 47087行 | `896c16feba1320b323dddbcc92da9b1bd8b0d6483457b9b2df1a804a662a3afe` |

## 重み再生成と別差分

`bash scripts/regen-shard-weights.sh 40413233675 nightly-full` を既存scriptで実行。nightly head `ef1e9e2ad2de973efba29f5ee380d03a8b60196e` / attempt1 / success / created2026-10-26T22:32:35Z。全6fresh runtimeとdigest等をscriptが検証し、XML fallbackなしで3111classを出力した。既存selftest6件成功（不正入力23種、ZIP/member/digest、置換失敗時の旧表bytes保持を含む）。

- 旧表LF原本SHA256: `dc4bb0fbaf3e7a37ccd4fe08d547f2b74f16e0f2b1764d979e8b550996c8857e`。旧適用3083。
- 新表226358bytes / SHA256 `0975a7419fee19d57fbd48a45d5d76584f6e68f391295f61f9c8e644caf2ce0b`。Git blobと生成原本も独立確認で一致。新適用3111、旧のみ0、新規掲載28。
- 計測3111 = 現在normal source3109 + 実class Disabled2。削除/arch/perfは各0。Disabled2はNotificationDispatchVisibilityGuardTest / MembershipBasisIntegrationTestで、過去skip suiteの実測を維持する。
- 現在source3366file中の非掲載255file = perf9 / arch60 / 専用freeze1 / annotation3 / abstract29 / helper-or-fixture145 / fixture7 / ProductionClasses helper1。compiled top-level非掲載310 = 上記255 + 副top-level fixture54/helper1。名前全件と適用判断はweights-classification.jsonへ記録。
- 例: perfはAdAudienceResolverScaledMeasurementIT、archはArchUnitFreezeMechanismSelfTest、専用番人はArchUnitFreezeStoreIntegrityTest。ArchUnitTestTagとProductionClassesは通常未計測テストではない。非掲載classが分割候補へ現れる場合は既存hash fallbackを維持する。
- sourceファイル名対応とannotation regexによる静的分類はmeta annotation/条件付き有効化/discovery全体を証明しない。test-like未計測検出0を、実行された全JUnit件数の保証には使わない。実候補/coverage/CIを別証拠とする。

| migration除外 | task | 旧/新候補path | shard移動path | 移動top-level |
|---|---|---:|---:|---:|
| False | test | 9415/9415 | 7306 | 2595 |
| False | archUnitTest | 9418/9418 | 7306 | 2595 |
| False | perfTest | 9418/9418 | 7306 | 2595 |
| True | test | 9328/9328 | 7404 | 2599 |
| True | archUnitTest | 9331/9331 | 7404 | 2599 |
| True | perfTest | 9331/9331 | 7404 | 2599 |

上表は実Gradle候補のindex集合を旧→新重みで比較した別差分。移設による不一致は0で、重み変更による割当移動と混ぜない。

## B-3/B-5 番人と途中失敗

- Stripe28は実テストgreen。移設前に新参照へ先行変更すると実テストredとなり、存在しない新配置が原因であることを確認して元へ復元。
- 移設後の所有コピーでtest.doFirst時に参照sourceだけ29へ、または宣言欠損へ変更。依存解決/コンパイルは正しい28のまま実parserを試験し、XML各tests1/failures1、29検出/宣言欠損の固有理由を検証。原本を毎回復元した。
- a8bの実archUnitTest.doFirstへ制御故障を注入し、CIと同じ--continue条件で期待した理由の失敗後もfreeze finalizerが実走しXML tests2/failures0/errors0/skipped0、JVM1/heap128MB/fork0を確認。continue無しの最初の試行はfreeze未走であり、無条件のfinalizer契約とは主張しない。archTestの実行0とfreeze必須class欠損は固有guard理由で拒否した。
**B-5の公開診断artifact欠損gate強化は裁可により別件へ分離した。** [2026-10-28裁可](https://github.com/kenta-0420/mannschaft/issues/4022#issuecomment-6462685947)と[条件同期PR #4201](https://github.com/kenta-0420/mannschaft/pull/4201)に従い、[CMP-261028-1554 / Issue #4200](https://github.com/kenta-0420/mannschaft/issues/4200)を未着手で登録。backend-ci.ymlのuploadはif-no-files-found: warn、gateはjob resultのみという既存挙動を維持し、不足を解決済み・負試験greenとは扱わない。再生成拒否やjobresult欠損拒否は公開artifact検査の代替証拠にしない。ローカルruntimeはGradle0でも有効なTest終端が無ければexit125となる別契約で、固定統合HEADの実wrapperを変更せず実行した[終端契約fixture7件](CMP-261027-1646/terminal-contract/README.md)で確認した。実Gradle/公開artifactの欠損再現ではない。class-progress.log全欠損はここでは検証せず、--finalizeはresult書込み成功ならexit0。B-5の他条件・B-1〜B-8の残条件、所有・予約・入口は維持する。

- backend-ci gateは変更せず元shellを抽出してneeds入力27ケースを検証（全成功/docs成功、各必須resultのfailure/cancelled/skipped/空、timeoutがfailure集約される入力）。診断artifact欠損をgateが直接検査するとは扱わない。診断欠損等は再生成拒否selftestの別証拠。実GitHub matrix/timeout発生自体の再現ではない。

gate-input-evidence.json SHA256 `10f979c4ba797f6540c512b9f3cc74d7959783d65a2ada43ce97c75cb7f7fb08`。verify-gate.mjs SHA256 `6d94324adad387db5fb035765c8fa477325843635685d4eb8d95bfb7f3e09223`。common-freeze-graphの7fixture成功はアプリconventionの負試験とは区別する。

## B-6 OpenAPI実生成

両側で同一18072を直列使用しgenerateOpenApiDocsを実行、exit0。実生成JSONは前後bytes完全一致: 6378658bytes / SHA256 `03ebfc14e16829cb1866e4c8d17aa3140e9ad1bd3dab047f45a6b069db9a34a0`。JSON解析でpaths2810/schemas4423を確認。コミット済みJSONとの比較だけで代用していない。新API・認可・DB変更はない。実機の許可/拒否/他テナントは別担当報告で確認する。

## 障害の切り分けと限界

- 初回Linux Git archiveのD/p9 I/O待ち、実行権欠損、native Git既定CRLFを順に診断し、正式原本はLF条件を揃えたbefore-4以降のみとした。CRLF以前の出力を合格証拠へ混ぜていない。shell bytes/先頭行/実行権をpreflightで確認。
- 初回CIはJava適用順序でspringdocのbootRun解決に失敗。root java/jacocoを元順序へ戻す最小修正後、6fのOpenAPI/graph/coverage/arch/nonJST/lint成功を確認。6f Backend全体は次pushでcancelledのため全6成功とは扱わない。
- after-oldweightsのarch-failure試行は補助initがincluded buildで存在しないtaskを検索した設定エラー。実arch故障/finalizerの成功証拠にはせず、app rootだけに限定したinitへ修正。次のcontinue無し試行は制御故障を検出したがfreeze task/XMLなしで未達。advisor監査後、CIと同じ--continueだけ追加の1変数試験へ分離し、独立2負試験/OpenAPIも別runとした。arch0は固有理由で拒否を確認後、freeze欠損のonlyIf根原因が通常stderrに隠れて補助grepが停止したため、さらにstacktrace付きfreeze欠損とOpenAPIだけへ絞った。復元classのSHA前後一致も確認した。
- 初回保持run-ZXyomrはrootがPID460831/464504停止・uid1000全生存process参照0・所有marker/dev/inode/専用絶対pathを確認し、2026-10-27T10:06:21Zに空tree/marker/固定dirだけを非再帰削除した。保持残存は解消。元失敗receiptは変更せずbefore-1/post-retention-cleanup.jsonへ追記証拠を保存。
- [a8b移設検証HEAD Backend CI](https://github.com/kenta-0420/mannschaft/actions/runs/40481639328)・最新検分・実機/探索・出荷JSONはrootの統合証拠を参照。CIのcache hitをfresh runtimeと扱わず、L0-M免除を性能改善の証拠にしない。B-7の実runtime JVM/heap/壁時計とB-8の出荷は別途判定する。

## 証拠index SHA256

| 原本index/manifest | SHA256 |
|---|---|
| CMP-261027-1646-after-oldweights-1/raw-comparison-partial.json | `43581d52097eb1271dc7cfe9ec5ce49f0b7c4951b77581088b7a62bd88fcf9eb` |
| CMP-261027-1646-after-oldweights-1/candidate-comparison.json | `cbeaf468375e591023244178b52a537db720c161eb24b058d561a22418a2ae64` |
| CMP-261027-1646-after-finalweights-1/candidate-comparison.json | `a4315901a5adca9497a29e630e152dd8869418dfed851494fab0c0aec6abc4cc` |
| CMP-261027-1646-after-finalweights-1/raw-comparison-partial.json | `43581d52097eb1271dc7cfe9ec5ce49f0b7c4951b77581088b7a62bd88fcf9eb` |
| CMP-261027-1646-after-finalweights-1/weight-assignment-change.json | `609b9ce1a9d657a093f82b0adb484dbe942633473631987797354caaddecd249` |
| weights-classification.json | `3fcf9a973ace663d9414f572b01ce838ff3effb7e3467afada9003e686eb4a1a` |
| nightly-40413233675-artifact-metadata.json | `67dc200e37028f199a07274bda8c2c609c593425c3a8db638682bb6ed8d504d0` |
| shell-entry-original-bytes.json | `12351153031574006c9ea6424e395846bd277a8eb72cc288029cc96bba1b06b7` |
| archive-original-bytes-check.txt | `e56273ef72982f1f12f42337e50ff30d0bb76c3f379f4b25ccc1e654a9a4dba7` |
| CMP-261027-1646-after-arch-continue-1/arch-failure-freeze-finalizer.xml | `d18eb66918a1ea2e4263110e84ac3441e820cfb28fb431536dc18d905865e776` |
| CMP-261027-1646-after-independent-1/arch-zero-negative.stdout | `ad0d0f0c4f4f6dd36bc0ce96bde36e7cae29c2ba3a1b7780f0b51f53748337a8` |
| CMP-261027-1646-after-independent-1/arch-zero-negative.stderr | `d604f9bf1071901e3a498816a4c30012bbdad07c4491953bbec2a90b3fb91942` |
| CMP-261027-1646-after-freeze-openapi-1/freeze-missing-negative.stderr | `35c36206937154d3a3d53d72ec9089c46952c19de72f7ca81bb8b12117ee434c` |
| CMP-261027-1646-after-freeze-openapi-1/required-freeze.before.sha256 | `fc108dce433a5c38034c7d338a5a9d555a635abff97e8fdba033a50afcbce24f` |
| CMP-261027-1646-after-freeze-openapi-1/required-freeze.after.sha256 | `fc108dce433a5c38034c7d338a5a9d555a635abff97e8fdba033a50afcbce24f` |
| CMP-261027-1646-after-oldweights-1/stripe-29-negative.xml | `8ad0e825ab1d7fdfe26f318e2fac07e71733ac2042cf66ca13279e85046572e0` |
| CMP-261027-1646-after-oldweights-1/stripe-missing-negative.xml | `fba71890f501301af0bd5a096ef6fe7c6b066292061b7df29d72e68d50064124` |
| CMP-261027-1646-before-1/post-retention-cleanup.json | `7c70f06fd13b1c1a891d8969a24a6eafcd994ce26a732e2852ca1bc669e7bdc2` |
