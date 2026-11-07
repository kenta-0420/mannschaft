# M18 remote CI実測（HEAD 30744）

HEAD `30744c88a7a0a7621e34335e9ed656e9c38131ca` の [Backend run 40992429514 / shard 0](https://github.com/kenta-0420/mannschaft/actions/runs/40992429514/job/123857879696) で、M18の実1件が成功した。入力窓は300.000075512秒、通常3000件は全件完了し、300個の各秒bucketはすべて10件だった。frame/physical拒否は0、全母集団のp99は **131.767637ms**（基準5秒以下）。

判定は [JSON](m18-remote-ci-30744.json)、計測原本は [全M18_RESULT JSONL](m18-result-30744.jsonl) に保存した。normal3000件・sample3002件・tick5回を省略していない。最新HEAD全体のgreen、local L0-M計測、C/F業務購読者の性能は未実証である。期限切れlocal候補窓のactual0は変わらない。

## 実結果と判定

| 条件 | 判定 | 実証 |
|---|---|---|
| remote_case | 達成 | M18は実1件成功・skip/error/failureなし、304.696秒。 |
| input_window | 達成 | 実投入窓300.000075512秒。 |
| backlog_1000 | 達成 | 実DBに1000件投入後のremaining==1000をassert。 |
| normal_3000_slots | 達成 | 全3000件・UUID重複0・100ms刻みの予定時刻に対して全件が自身の100ms slot内に到着。 |
| each_second_10 | 達成 | 300個の実到着bucketすべて10件、計3000件。 |
| full_population | 達成 | 未供給/未完了/failureは0。全3000件を保持し、未供給/未完了はLong.MAX_VALUEとして評価する。 |
| no_rejection | 達成 | frame/physical拒否とも0。 |
| p99_lte_5_seconds | 達成 | 全3000件nearest-rank p99=131767637ns（5秒以下）。全件finished>=committed>=arrived。 |
| real_scheduler_lock | 達成 | 親schedulerなし、子の実Scheduled1件/60秒固定delay。ShedLock LockAssert経由の実tick5回・全完了・lockRows1。 |
| replay_progress | 達成 | claim/admit/accepted meterは500で一致。backlog500件は後始末直前まで保持。 |
| pool_bounds_cleanup | 達成 | core/max4・queue容量200。100ms sample3002件は全範囲内、queue最大93/admission最大100、停止後capacity200。 |
| latest_head_global_green | 未実証 | この記録はtested head30744のshard0/M18のみ。後続commitを含む最新HEAD全体のgreenを示さない。 |
| local_l0m | 未実証 | remote CIによるM18であり、local L0-M計測ではない。期限切れlocal候補窓のactual0は不変。 |
| business_subscriber_performance | 未実証 | 実購読fixtureは固定正常return。C/F業務購読者の処理性能を示さない。 |

normalの予定時刻は100ms刻みで、実到着は全件が自身の100ms slot内だった。到着遅延は0.703452〜18.279214ms、実到着間隔は83.727729〜116.281507msである。backlogは実DBで初期1000件をassertし、5回のtickで500件を再生、500件は後始末直前まで保持した。全backlogをdrainした結果ではない。

## percentileと計測範囲

全3000件を昇順に並べ、`ceil(N × q) - 1` 番目を取るnearest-rankを使う。p50=14.538675ms、p95=32.943165ms、p99=131.767637ms、最大=1.078402348秒で、rawと独立再計算は一致した。

latencyは`finishedNs - arrivedNs`。未供給・未完了は母集団から落とさず`Long.MAX_VALUE`で評価する。今回は未供給・未完了・failureがすべて0で、全件のlistener呼出し・finish呼出し・publicationは各1件だった。finishはpublisherのafterCommit後、finish TXのcommitと別接続によるpublication/attempt消去確認を経た時刻である。全件で`finished >= committed >= arrived`を確認した。実commit→finishのp99は122.253299msで、arrival起点の判定より小さい。

子contextは親ApplicationContextを持たずBeanFactoryだけを参照し、実`@Scheduled` 1件・固定delay60秒と実ShedLockを起動する。tick記録前に`LockAssert.assertLocked()`を実行し、5回すべて終了、DB lock rowは1件だった。親contextのschedulerは起動しない。

`expectedConfigurationFromFrozenSource`（normalReserve50 / claimLimit100 / tick60秒 / cutoff300秒 / lease300秒）は固定sourceの期待値mapであり、独立したruntime field introspectionの結果ではない。core/max4、queue容量200、claimLimit1..100とScheduled固定delay60000msは実source assertionsの通過を示す。

## 原本と再計算の結び付け

- XML artifact: [12954326002](https://github.com/kenta-0420/mannschaft/actions/runs/40992429514/artifacts/12954326002)、88,611,155 bytes、SHA-256 `a60d4f3de976e169883b79c96ac334c10d7dd2f673911bcff746d4e50c7fa74b`。
- exact XML: `build/test-results/test/TEST-com.mannschaft.app.moduleevent.ModuleEventLoadIT.xml`、925,761 bytes、SHA-256 `f156b4dea45117985f5d49f8382c6cf9cb3b614ac9e2a844474d4e8d3f5d93c2`。M18実1件、case時間304.696秒。class全体は初期化込み449.029秒。
- [diagnostics artifact 12954057400](https://github.com/kenta-0420/mannschaft/actions/runs/40992429514/artifacts/12954057400)、52,773 bytes、SHA-256 `25d4f22b973c68771f9fef34ab91879c1ead2c291da6ac643ad16d2f33eefc15`。test step / command / shellの実exitは全0、completed=true、cacheHit=false。
- raw JSONL: SHA-256 `d6a6d6882b0e492af9e2177923f86a4262f972a0e604e49e761ffe665539b919`。末尾LFを含む。root独立判定のXML内JSON文字列SHA-256 `4e4a1f6c03f11cb969d8c34a9f3cb9c7ad378b3340b83631abd9ee9ac1206ff5` は末尾LFを含まないため、値が異なる。
- root独立再計算判定JSON: PASS、SHA-256 `f9b406a495355d3e953113773bb5c05e9886d459aba08cb11bbcc9cc34a29e31`。tested head・exact XML・全3000件・bucket・percentile・拒否・tick・sample・source SHA・実exitを照合した。
- [ModuleEventLoadIT.java](https://github.com/kenta-0420/mannschaft/blob/30744c88a7a0a7621e34335e9ed656e9c38131ca/backend/src/test/java/com/mannschaft/app/moduleevent/ModuleEventLoadIT.java): SHA-256 `809a340652ec696b0f8d1834a2e16eed40f80be20b6d2da3ecc3be06a2602e6e`。
- [ModuleEventLoadTestFixture.java](https://github.com/kenta-0420/mannschaft/blob/30744c88a7a0a7621e34335e9ed656e9c38131ca/backend/src/test/java/com/mannschaft/app/moduleevent/ModuleEventLoadTestFixture.java): SHA-256 `4a8e7b129d15d82d620ddf1f3d0a9dc435e4bd7589a5e2dc40db0352df847446`。

shard 0の通常テストは5234件（成功5233 / failure0 / error0 / skip1）、別Archは8件成功。M18のskipは0。このshardの成功を他shardや後続commit全体のgreenへ読み替えない。購読fixtureは固定正常returnで、C/F業務性能やlocal L0-Mの代替測定は主張しない。

rawのstring値はfixture生成UUIDv4と計測開始/終了時刻だけであり、業務payload・個人情報・認証情報を含めていない。文書作成ではJVM・DB・Gradle・WSL・CI再実行を行っていない。
