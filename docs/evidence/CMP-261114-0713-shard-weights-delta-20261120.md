# CMP-261114-0713 未登録regularテスト7クラスの個別重み追加

適用元は `b6c8e6aa3089922a891872bc8bac4d3309547b3e`。同headの [Backend CI / run 44701729811](https://github.com/kenta-0420/mannschaft/actions/runs/44701729811) の `verifyShardCoverage` が、実行対象3334クラス・未掲載101クラスで上限100を超過した。full / migration除外の予測max/avg比はいずれも1.0000で、上限1.10以内だった。件数だけのログから全101クラスの一覧を推測していない。

採択根拠は [run 43949745232 / attempt 1](https://github.com/kenta-0420/mannschaft/actions/runs/43949745232/attempts/1)、source `978f25276681ce71f78fe79d70cf60d94f564089`、workflow `backend-ci.yml`（pull_request、全run success）の保存済み成果物である。7クラスのJavaテストblobは適用元と同一。各クラスは通常 `:test` の開始・終了が厳密に各1件、`SUCCESS`、実行stepの全exit 0、completed=true、cacheHit=falseで、対応する `build/test-results/test/` のXML計71ケースもfailure/error/skipped 0を確認した。

## 採択値

一次値は通常 `:test` のclass-suite実時間 `elapsedMs` を1000で割った秒数。JUnit XMLの `testsuite.time` は成功裏付けの別情報であり、重みに使っていない。

| FQCN | shard | elapsedMs | 秒 | XMLケース | 同一テストblob |
|---|---:|---:|---:|---:|---|
| com.mannschaft.app.cspreport.CspReportPrivacyHttpIT | 2 | 82244 | 82.244 | 1 | `381bc238e8b9a9460000765e43d303a6a6e83ac4` |
| com.mannschaft.app.cspreport.CspReportPrivacyTest | 1 | 315 | 0.315 | 18 | `cfb1d260947782eefb3a21aea6b59298f5865aac` |
| com.mannschaft.app.errorreport.service.DiagnosticUrlTest | 0 | 20 | 0.020 | 7 | `8cf2c7d53fe4ad974c1687b635a26a92d828254c` |
| com.mannschaft.app.errorreport.service.ErrorReportDiagnosticUrlContractIT | 5 | 76529 | 76.529 | 31 | `fd1857b9db4ed05084994a08b5c0263466e41a4f` |
| com.mannschaft.app.match.service.MatchStartLifecycleTest | 4 | 240 | 0.240 | 9 | `5eae57561331ce06c63c3bd9f3a47d278f0f39a6` |
| com.mannschaft.app.match.service.MatchStartTransactionIT | 5 | 19871 | 19.871 | 4 | `17ac79b7101777f09375d1b555292e6c71cd7333` |
| com.mannschaft.app.signage.service.SignageTokenRequestSchemaTest | 3 | 82 | 0.082 | 1 | `74fa8939d9ef1285f78e82e40e383d14d645cf75` |

## 原本の同定

下記ZIP SHA-256はGitHub artifact APIのdigestとも一致。runtime memberは `class-progress.log`、XML memberは `build/test-results/test/TEST-<FQCN>.xml`。同一ZIP・memberの重複掲載は省く。

| 種別 | artifact ID | ZIP名 | ZIP SHA-256 |
|---|---:|---|---|
| runtime | 14285038959 | `backend-runtime-diag-pr-4529-shard-2-attempt-1.zip` | `e88d1d7ba72dae728d49f4e50fdd23b3af79f30504af231b6948d43e58dca708` |
| XML | 14285726272 | `test-results-xml-pr-4529-shard-2.zip` | `40918205f1e8867fb388176311ba07ade2c75aa70a806c9a1f7cbf1089eadc5b` |
| runtime | 14285736131 | `backend-runtime-diag-pr-4529-shard-1-attempt-1.zip` | `8880d1bf3a5649d4786750d9f865c9f4ce3ed26962f04fe865c6356a0bfade7a` |
| XML | 14284604387 | `test-results-xml-pr-4529-shard-1.zip` | `dd57ca846229d13cedb444c1f4ebb6161fbc5d621788eb5fa5c6af0e7070cd83` |
| runtime | 14281164778 | `backend-runtime-diag-pr-4529-shard-0-attempt-1.zip` | `2232171a8f3b15415b5f31db3a354a980ef4d12679f2f5847c5cfe0485818649` |
| XML | 14283240800 | `test-results-xml-pr-4529-shard-0.zip` | `c4ac12ba6976685e243742234b958642507e93ccc4624e752842b4036f29dba4` |
| runtime | 14285625837 | `backend-runtime-diag-pr-4529-shard-5-attempt-1.zip` | `44580df341e94e6c3fca1e7ced2ff043f39894fa4eb7eecbb8024cd20f89eefd` |
| XML | 14285491235 | `test-results-xml-pr-4529-shard-5.zip` | `515f3d89d776831adc0008199cab46135b0d8ba54d8e9a7f4c58c21f8f5d2f4b` |
| runtime | 14283459008 | `backend-runtime-diag-pr-4529-shard-4-attempt-1.zip` | `0619d2aac9b529c11fb4773dfa81f394589c914bc540284b81644c28a7e745fe` |
| XML | 14283309907 | `test-results-xml-pr-4529-shard-4.zip` | `c8c2eaa08decc7931c00ed9f83f2ae9280ea4b5012d7de508e271d348a62c7cf` |
| runtime | 14284312843 | `backend-runtime-diag-pr-4529-shard-3-attempt-1.zip` | `738d81591138326afbf6986a997bcbd270935be4a766bd687cab19bb1b06aa8a` |
| XML | 14284322730 | `test-results-xml-pr-4529-shard-3.zip` | `ec9a2f10d684c8f3150d644006468a9f6f58f3f3d51e158b35902e10a2d0ba00` |

| FQCN | runtime member SHA-256 | XML member SHA-256 |
|---|---|---|
| com.mannschaft.app.cspreport.CspReportPrivacyHttpIT | `3470f059e558f018585b994008aac3b38d17561531f37ad6353994797b952a26` | `e40de2826188a006108991c619294cef725d2ac8390f6526ba34ac44cacd845a` |
| com.mannschaft.app.cspreport.CspReportPrivacyTest | `a7743eaf155283dfa52b64b2479b58899fcc64b24aa1c0f1bbb2217a58c7f501` | `8b8b1cf97e92c4560c12c99ac68efade29e2d640d7e5e9eedabcf3727a2e7d17` |
| com.mannschaft.app.errorreport.service.DiagnosticUrlTest | `055bcc777c21d12a2459a29c36b2430efabf401283fd152dc1bef208faae2aa0` | `b6fc6d899025a99f12d3f75de18784953c4db6a97e500aab55157d84ba4743ac` |
| com.mannschaft.app.errorreport.service.ErrorReportDiagnosticUrlContractIT | `f249569d43aa9ee812ac7dc1c9dc54e88a619fbe905d651527e131e24d7735da` | `861a9fac8a0b2275558b97850d8de51f0d43c26a23c7e91fb012b22cab541eab` |
| com.mannschaft.app.match.service.MatchStartLifecycleTest | `738e4ce30327a1f0a3869c037ac1f43d2ad44de4b782a88afffd5e8e69f9f49d` | `5baab4af7ea95dbf8a5df3b997dadfc42d8f53edbe17479baff6bfc9fce4ee24` |
| com.mannschaft.app.match.service.MatchStartTransactionIT | `f249569d43aa9ee812ac7dc1c9dc54e88a619fbe905d651527e131e24d7735da` | `b449c6f4ae9c2c3e6988fa57cc254cf94591b82e1fa190e12826cb9e11fb6f68` |
| com.mannschaft.app.signage.service.SignageTokenRequestSchemaTest | `8aa6171e2fc493269d7cc8fd693388f54e88023ca6a19b1e6d623db8dfec7f5e` | `d4a37ac06fb0a45cd7ad3adaf42f63ddb71a974e10335eca7546a79cac04d3fe` |

## 適用範囲と保全

- 未登録だった自チームのregularテスト7クラスを全て追記し、既存main3234クラスと先行採択4クラスの計3238 key/valueは全て保持する。追記後3245キー、重複0。
- `SignageSocketIntegrationFixture` はテスト実行結果を持たないfixtureであり、重みに加えない。専用 `archUnitFreezeStoreIntegrityTest` やArchUnitのXML/runtimeも通常テスト重みへ転用しない。
- `ShardAssignment.runnableTopLevelClasses` は `FREEZE_INTEGRITY_CLASS` を除去し、実行側・coverageguard側とも同関数を使う。専用Integrityは未掲載101件に含まれない。分類・閾値・除外・guard・テスト・runtime production inputs・SQL・FE・設定・build-logicは変更しない。
- 旧runの成功実測を今回の新headのfresh実測とは扱わない。successful nightlyによる全表再生成ではなく、rootが7クラスの一次資料を個別採択したdeltaである。全shardのfresh成功nightlyを要求するupdater契約は変更しない。
- 未掲載数101→94は同一実行対象集合を前提とする推計であり、正規 `verifyShardCoverage -Pshard.total=8` と新headのCIで実測する。既存上限100、偏り上限1.10、8shard、各runnerのArchUnit全解析・Integrity・実行inventory・必須集約gateは維持する。
- この変更は重み表と本書だけに限定する。実稼働検証sourceと新headの関係はruntime inputsのGit blob同値で資格化し、Git tree全体の同一性や新CI成功を主張しない。
