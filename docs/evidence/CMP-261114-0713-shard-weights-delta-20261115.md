# CMP-261114-0713 未登録4クラスの個別重み追加

[観測run 43703897863 / attempt 1](https://github.com/kenta-0420/mannschaft/actions/runs/43703897863/attempts/1)、source `71787467893ea39684b6ce0d30cd9c09ab453ec2` の通常PR CIを根拠に、殿が未登録4クラスの `class-suite` 終端 `SUCCESS` 値だけを個別採択した。全表再生成ではなく、既存3218行とそのsuccessful nightly provenanceを保持する。既存値の変更・削除は0。

## 採択値（UTC、秒）

| FQCN | shard | 開始 | 終了 | elapsedMs | 秒 |
|---|---|---|---|---:|---:|
| com.mannschaft.app.signage.websocket.SignageSocketGuardTest | 0 | 11:17:22.167352236 | 11:17:22.375634364 | 209 | 0.209 |
| com.mannschaft.app.signage.SignageWebSocketAuthorizationIT | 4 | 11:39:40.071784350 | 11:41:24.380538972 | 104500 | 104.500 |
| com.mannschaft.app.signage.websocket.SignageSocketAccessReaderTest | 4 | 11:41:24.571513357 | 11:41:24.755556579 | 184 | 0.184 |
| com.mannschaft.app.signage.SignageSocketDatabaseIT | 4 | 11:44:22.991784056 | 11:45:49.985420464 | 86996 | 86.996 |

開始/終了日は全件2026-11-15。一次入力は `class-progress.log` の通常 `:test` class開始/終了であり、JUnit XMLの `testsuite.time` ではない。

- shard0 class-progress SHA-256: `9c25e8027d26da654baf04388a20b8ec347dafb4e1d8fe911e26f551ce8c4ff1`
- shard4 class-progress SHA-256: `3e90ddb1e32dd5274e0e4f9aab0833c50790e273d3ba433501fde2e09c6a45c2`
- shard4診断artifact: ID `14160264607`、SHA-256 `46332b333f9234b1bf146335d96c42ef0ad08d49dcd50fada956242bd6d2a3d4`。

## 欠損・失敗と適用範囲

全runはfailureである。shard4は `completed=false`、command/test/shell exitはnull、MultiNode AC7はfixture slug長超過で失敗し、最後のFailOpenクラスは85分制限で未終端だった。この2クラスの値は採択・更新しない。4クラスの個別SUCCESSを全runの成功やfresh全量採取完了とは呼ばない。

`backend/.claudecode.md` §31および `scripts/regen-shard-weights.sh` の全6fresh・正常終端・成功nightlyというCLI全表再生成条件は変更しない。本追加はそのCLIへfailed backend-ciを通した結果ではなく、殿が個別クラスSUCCESSの一次資料に限って裁可したdeltaである。既allocator、migration filter、hash fallback、全6 shard、cap、必須gate、85分test制限は変更しない。

## 配分のprojectionと検証

同runの終端SUCCESSクラス実時間を既LPTに当てた診断projectionでは、最大クラス負荷は9335.662→7957.321秒（約14.8%減）、shard4は9335.662→7671.056秒（約17.8%減）となる。これはクラス実時間の再配置試算であり、実際の壁時計短縮・新CI成功の保証ではない。未終端/失敗クラス、fork/コンテキスト/GC等のクラス間費用を含む完走の裏付けは、新HEADの全CIで確認する。

既存generatorの純回帰検証と、正規 `verifyShardCoverage -Pshard.total=6` による重み有無×migration除外有無の4profile検証を行う。検証結果はPRの実結果と対応付け、未実行を成功扱いしない。
