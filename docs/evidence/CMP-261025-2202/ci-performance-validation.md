# 既存MFA性能テストのCI失敗と切り分け

## 最初の失敗

実装head `371fcc7150ccdae8c76a2fb4886762649e6222af` の [Backend CI attempt 1 / shard2](https://github.com/kenta-0420/mannschaft/actions/runs/40232982194/job/121288571632) は5,008件中2件失敗、既存skip3件。失敗は既存 `MfaBackupCodeLatencyIT` のArgon2id 2経路の単発時間assertionであり、表示設定のテストではない。

| 経路 | 上限ms | 前head c643成功時ms | 今回ms |
|---|---:|---:|---:|
| Argon2id・全不一致 | 1500 | 574 | 1541（失敗） |
| Argon2id・最後一致 | 1500 | 584 | 1508（失敗） |
| 旧BCrypt・全不一致 | 3000 | 1782 | 2477 |
| 旧BCrypt・最後一致 | 3000 | 1899 | 2777 |

親が両jobの原本ログを読み、実測値を照合した。比較元は[前head成功job](https://github.com/kenta-0420/mannschaft/actions/runs/40224215370/job/121262279647)。計測対象のMFA本体、当該テスト、GradleとCI設定は本機能で変更していない。auth配下の差分は表示設定専用イベントをcommit後に記録するaudit listener追加であり、MFA測定中にはそのイベントを発行しない。

## 並行実行の確認と限界

終了済みjobの `backend-runtime-diag-pr-4116-shard-2-attempt-1` artifactを取得し、`mfa-intervals.log`・`class-progress.log`・`result.json`も独立照合した。cacheHit=false、test/command/shell exitはいずれも1、テストは完走した。環境起動失敗やcacheの成功偽装として扱わない。

Argon2計測はUTC21:12:34.532–36.073と21:12:40.177–41.685。別forkの `BillingPortalSessionApiIT` は21:12:08.218–21:13:16.349で、両計測区間と重なる。並行実行の存在は確認できるが、artifactには計測区間のCPU使用率がなく、これだけで競合が唯一の原因とは断定しない。

上限を緩和せず、コードと閾値を固定した1forkの局所4件を実行し、他の全量jobの結果も回収する。局所成功だけでCI失敗を合格扱いにせず、同headの正式再検証を確認してから出荷判断する。

## 同headの局所再測定

`371fcc`を専用clean sourceとverification-run/turnstileで固定し、既存4件を1forkで実行した。4件成功、失敗/error/skip0。Argon2全不一致247ms・最後一致259ms、BCrypt全不一致1954ms・最後一致2247msで、既存閾値をすべて満たした。`BUILD SUCCESSFUL in 3m 18s`。親もXMLとreceiptのhead/exit0/cleanup removedを照合し、[独立集計JSON](mfa-local-results.json)に保存した。[担当の局所実行記録](ci-performance-local-validation.md)も参照。

同じコードの単独ローカルでは再現しないことを確認したが、環境差があるためCIの競合原因とは断定しない。正式CIの失敗jobを同head・同条件で再実行し、その成功を確認する。コード・閾値・テスト選別・CI設定の緩和は行わない。

## 正式CIの再実行

初回の全量結果はshard0/1/3/4/5がすべて成功し、shard2だけ上記2件で失敗した。修正対象のAPI・日時・台帳番人も正式全量で成功した。全job終了を確認後、`gh run rerun 40232982194 --failed` で失敗分割と集約jobを同じhead・同じworkflow条件のまま再実行した。再実行の終了結果を確認するまで完了扱いにしない。

同head attempt2の[shard2 job](https://github.com/kenta-0420/mannschaft/actions/runs/40232982194/job/121313400674)は、MFA4経路がすべて上限内となり、テスト本体は `BUILD SUCCESSFUL in 1h 9m 45s` で完走した。原本ログとAPIのRun tests step completed/successを親が照合した。Argon2全不一致1101ms・最後一致1333ms、BCrypt全不一致2105ms・最後一致2272ms。コード・閾値・並行条件は変えていない。

その完了処理中に、先行main `15366b4200`（#4122、交代承認修正）へ追従した新head `609ee90bf65af64b17d95e9ee31d27953c5abdaa` をpushしたため、旧job/run全体の最終状態はcancelledである。旧headのテストstep成功を、run全体成功や新headのCI成功とは扱わない。最新の[Backend run 40246540422](https://github.com/kenta-0420/mannschaft/actions/runs/40246540422)で全量を確認する。

## 最新統合headの正式成功

最新全量のMFA4経路もSUCCESS。Argon2id全不一致915ms・最後一致852ms（上限1500）、BCrypt全不一致2589ms・最後一致2743ms（上限3000）を原本ログで親も照合した。単発の実時間計測の揺らぎは残存リスクとして保持する。

実装head `609ee90bf65af64b17d95e9ee31d27953c5abdaa` の [Backend CI 40246540422](https://github.com/kenta-0420/mannschaft/actions/runs/40246540422) は全6shardと集約gateがsuccess。親がjob APIと6分割の原本ログを照合した。[最新対象クラス・性能値](backend-final-ci-results.json)を参照。Contract/Commit/Service、API宣言・日時・AC145メタの6クラスはいずれもSUCCESS。旧失敗・途中cancelledは履歴として保持し、最新成功と区別する。
