# 村 fan-out 1万人規模 実測 findings（β4 前・測定専用）

- **戦役台帳**: `.claude/campaigns/2026-07-29-fanout-10k-measurement.md`
- **射程**: **測定＋文書化のみ**。production コードの挙動は一切変更していない。ここで摘出した欠陥は
  後続 fix 戦役の **before 基準**（record-then-freeze）である。
- **対象規模**: β4（ペスカドーラ町田・1万人規模）の受け皿＝村機能。村行事作成時の通知 fan-out。
- **測定 IT**: `backend/src/test/java/com/mannschaft/app/village/perf/VillageFanout10kMeasurementIT.java`
- **seed ヘルパー**: `backend/src/test/java/com/mannschaft/app/support/perf/Fanout10kSeeder.java`

---

## 1. 実行方法（CI smoke から分離）

重量級の実測 IT は JUnit タグ `@Tag("perf")` を付け、通常の `test`（CI smoke）から
`build.gradle.kts` の `excludeTags("perf")` で除外する。実行は専用タスク:

```bash
cd backend
# 事前に WSL2 Docker + 2375 TCP プロキシが生きていること（Testcontainers 用）
export DOCKER_HOST=tcp://127.0.0.1:2375
export TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1
./gradlew perfTest -Pmax.parallel.forks=1
```

- `perfTest` は `includeTags("perf")` で perf のみを実行する（タスク名分岐で決定論的に適用）。
- 通常の `./gradlew test` / `build` は perf を除外する（壁時計を食わないため CI に載せて安全）。
- **SKIP 偽緑に注意**: Docker 不通だと `@EnabledIf(isDockerAvailable)` で静かに SKIP し `BUILD SUCCESSFUL` になる。
  実 RUN の裏取りは JUnit XML の `tests="7" skipped="0"` と 2375 到達（`curl http://127.0.0.1:2375/_ping` が `OK`）。

### 1.1 運用注記（この測定 IT は CI smoke 不適・手動/専用実行）

- **Windows での `@SpringBootTest` cold-start が非常に重い**。実測で **1 回目ビルド総時間 36 分 43 秒**、
  うち **テスト本体（7 メソッド）は 106 秒**（2 回目は 85 秒）。差分の大半は **フル app コンテキストの初回起動**
  （AOP pointcut matching＋Windows のクラスパス `File.exists` スキャン。単一スレッドで数十分）。
  Windows Defender のリアルタイム保護は無効環境で、Defender 起因ではないことを確認済み。
- ゆえに本 IT は **CI smoke には不適**。`@Tag("perf")` で分離し、**手動・専用タスク `perfTest`** で回す運用とする。
- テスト本体（seed＋7 メソッド）自体は 85〜106 秒で完走するため、測定コスト自体は軽い。ボトルネックは起動のみ。

---

## 2. 測定対象のホットスポット（file:line）

| # | 箇所 | 症状 |
|---|---|---|
| 1 | `notification/service/NotificationHelper.java` `notifyAllPreAuthorized` | 受信者を1人ずつ逐次ループ・1人=1 INSERT（バルク無し） |
| 2 | `village/event/VillageEventRefluxEventListener.java` | `@TransactionalEventListener(AFTER_COMMIT)+REQUIRES_NEW` だが **`@Async` 無し**＝リクエストスレッドで同期実行 |
| 3 | `config/AsyncConfig.java` `event-pool` | core2/max5/**queue100**・明示 rejection handler 無し＝**既定 AbortPolicy** |
| 4 | `notification/service/NotificationDispatchService.java` `dispatch` から `sendViaPush` | 配信ごと `isNotificationEnabled`＋`isTypeEnabled`＋`listSubscriptions` = 受信者ごと 3 クエリ（N+1） |
| 5 | `timeline/service/TimelinePostService.java` `getFeed` | 先頭ページ固定・`created_at` filesort・深いページング不可 |
| 6 | `village/service/VillageMeetupService.java` `listAttendances` | **健全**（GROUP BY/バッチ集計・N+1なし）。回帰ガード対象 |

---

## 3. 実測値（perfTest 実 RUN）

- **Docker 疎通**: 到達（`http://127.0.0.1:2375/_ping` が `OK`）。Testcontainers MySQL 8.0（tmpfs）が起動。
- **RUN 裏取り**: JUnit XML `tests="7" skipped="0" failures="0" errors="0"` ＝ **7/7 実 RUN・全 PASS**（SKIP ではない）。
- **実行環境**: Windows JVM（gradle test）+ WSL2 Ubuntu-24.04 Docker 29.4.1 + 2375 Python TCP プロキシ + Testcontainers MySQL 8.0。
- **seed**: active=10,000 / left=30 / banned=30 / attendances=10,000 / villagePosts=10,000（投入 **38,511 ms**）。
- 生値は測定 IT が `System.out` へ ASCII で吐く `PERF_MEASURE key=value` 行（logback 抑止・文字化けを回避するため）を JUnit XML の `<system-out>` から回収した。

### 3.1 不変条件（hard assert・現挙動の特性化）

| AC | 内容 | 結果（実測） |
|---|---|---|
| AC-1 | `findActiveUserSubjectIdsByVillageId` がちょうど1万件 | **PASS**：`AC1_recipients=10000` |
| AC-3 | 退村(left_at)/BAN(banned_at) は受信者に含まれない（対象境界） | **PASS**：`AC3_left_banned_excluded=true`／村行事通知の境界レンジ `AC3_boundary_notifs=0` |
| AC-2 | 村行事1件作成 → 現役1万人ぶんの notifications がちょうど1万件 | **PASS**：`AC2_generated=10000` |
| AC-4 | `listAttendances` のクエリ数はページサイズ・総行数に非依存（N+1なし） | **PASS**：repo `q20=2 / q100=2`・service `q20=7 / q100=7`（総行数1万・ページサイズ非依存） |
| AC-5 | `findFeedByVillageId` は総投稿1万でも 1 クエリ・先頭ページ | **PASS**：`AC5_feed_queries=1`（feed_size=20・total_posts=10000） |
| AC-11 | fan-out 途中で1件 INSERT 失敗でも残りは継続（best-effort） | **PASS**：null 受信者1件を混ぜても残り50件が作成される（`Column user_id cannot be null` を捕捉し継続をログで確認） |

### 3.2 測定・記録（record＋寛容上限）

| AC | 指標 | 実測値 |
|---|---|---|
| AC-6 | 村行事作成 同期 fan-out（1万 INSERT）の壁時計＝ユーザー体感ブロック時間 | **42,562 ms**（`AC6_fanout_wall_ms=42562`） |
| AC-7 | event-pool 1万バースト → 受理 / 棄却（AbortPolicy） | **受理 105 / 棄却 9,895**（`queueCapacity=100 maxPool=5 handler=AbortPolicy accepted=105 rejected=9895`） |
| AC-7参考 | 村行事作成 end-to-end の event-pool 完了タスク数（投入ペース依存） | **6,471 完了**（`AC7_e2e_dispatch_completed=6471` → 約 3,529 件が end-to-end でも棄却。最初の棄却は受信者 #105＝`userId=900000105`） |
| AC-8 | 配信1件あたりの購読/設定クエリ数（N+1）／1万配信換算 | **3 クエリ ×1万 = 30,000**（`AC8_per_dispatch_queries=3 projected_10k=30000`） |
| AC-9 | 村フィード read（`getFeed`）レイテンシ（総投稿1万・先頭ページ） | **69 ms**（`AC9_getfeed_latency_ms=69`） |

> **AC-6 の壁時計に関する割り切り**: 42.6 秒は **WSL2 の 2375 TCP プロキシ越し**（Windows JVM から proxy 経由で WSL の MySQL）の
> INSERT レイテンシを含む。本番の直結 DB ではこれより速いが、**「リクエストスレッド上で同期・受信者数に線形」**
> という欠陥の性質と桁（1万件で数十秒オーダー）は環境非依存で成立する。この桁自体が β4 で効いてくる所見である。

---

## 4. 確定した欠陥（β4 前に効いてくる順）

### 4.1 同期 1 万 INSERT がリクエストスレッドをブロックする（AC-6：実測 42,562 ms）
`VillageEventRefluxEventListener` は `@Async` 無しで `AFTER_COMMIT` 実行されるため、村行事作成 API の
応答は 1 万件の逐次 INSERT が終わるまで返らない。実測ブロック時間 **42,562 ms（約 42.6 秒）**。
1 万人村での行事作成が API タイムアウト・スレッド枯渇の火種になる。**還流の非同期化**（`@Async` 化＋
専用プール、あるいは外部キュー化）と **バルク INSERT** が是正の方向。

### 4.2 dispatch の AbortPolicy 棄却＝通知が静かに消える（AC-7）**【β4前 是正推奨】**
`event-pool` は `queueCapacity=100 / maxPoolSize=5` で **明示 rejection handler が無く既定 AbortPolicy**。
1 万件を同時投入すると queue+pool 上限（**実測ちょうど 105**）を超えた分が `RejectedExecutionException` で
棄却され、呼び出し側（`notifyAllPreAuthorized` の best-effort catch）が **握り潰す**。

- **決定的バースト実測**: 受理 **105** / 棄却 **9,895**（1万投入）。
- **end-to-end 実測**: 村行事作成経由でも完了は **6,471**（約 **3,529 件が棄却**）。最初の棄却は
  受信者 **#105**（`userId=900000105`）から始まり、以降ログに `ExecutorService in active state did not accept task`
  が多数記録される。

これは性能ではなく **正しさ寄りの欠陥（通知が届かない）**であり、`page-view-pool` が採る
「DiscardPolicy＋可視化カウンタ」に倣った是正、あるいは配信の**非同期キュー（Valkey/SQS）化**を
**β4 前に検討すべき**。同経路の監査ログ（`AuditLogService.record` も `@Async` で同じ `event-pool`）も
飽和時に落ちうる点に注意。

### 4.3 配信あたりの購読/設定クエリ N+1（AC-8：実測 3 クエリ/配信）
`dispatch` は受信者ごとに `isNotificationEnabled` + `isTypeEnabled` + `listSubscriptions` の
**3 クエリ** を発行する。1 万配信で **30,000 クエリ**。バッチ先読み（user_id 集合で
設定・購読をまとめて引く）で N+1 を解消できる。

### 4.4 健全箇所（回帰ガード）
`listAttendances`（AC-4：repo 2 クエリ固定・service 7 クエリ固定でページサイズ非依存）と
`findFeedByVillageId`（AC-5：**1 クエリ**）は総行数に依存しない一定クエリ数で、現時点で N+1 は無い。
**後続 fix でここを N+1 化させないための回帰ガード**として本 IT を残す。村フィード read レイテンシは **69 ms**。

---

## 5. 割り切り（御裁可済み）

- **Valkey モック**: 基底 `AbstractMySqlIntegrationTest` は `StringRedisTemplate` をモック化するため、
  **WebSocket/Valkey のリアルタイム配信の実測は範囲外**。本 IT が実測するのは「DB INSERT の fan-out」
  「dispatch プールの飽和棄却」「購読/設定クエリの N+1」。実配信の往復レイテンシは別途 WS 実測が要る。
- **AFTER_COMMIT 測定**: 測定 IT は `@Transactional` を付けない（付けると AFTER_COMMIT が発火しない）。
  実コミットさせてから測る。`@Async` dispatch の完了観測は awaitility でプール idle を待つ。
- **AC-7 の end-to-end drop は非決定的**: end-to-end 経路では dispatch 投入が producer の INSERT
  レイテンシにペーシングされ棄却数がタイミング依存（実測 e2e 棄却 約 3,529）。よって AC-7 の **決定的実証**は
  production の `event-pool` Bean へ 1 万を一斉投入して棄却 9,895 件を数える形で行った（end-to-end は参考記録）。
- **AC-6 壁時計**: §3.2 の注記どおり 2375 プロキシのレイテンシを含む（本番直結 DB では速いが桁は成立）。

---

## 6. record-then-freeze 提案基準値

`docs/architecture/db_scalability.md` に村 fan-out の目標値は未定義。初回実測でキャリブレーションした
**基準値（β4 前 fix の合否ライン案）** を以下に提案する（マスター御裁可を経て正式採用）:

| 指標 | 初回実測 | 提案基準（β4 前 fix 後の目標） |
|---|---|---|
| 村行事作成 API ブロック時間（1万人村） | 42,562 ms | **同期処理から切り離し（fan-out を非同期化）→ API は < 300 ms** |
| 1万配信の通知欠落（AbortPolicy 棄却） | バースト 9,895 件 / end-to-end 約 3,529 件 | **0 件（DiscardPolicy 可視化 or 外部キュー化で棄却ゼロ）** |
| 配信あたりクエリ数 | 3（1万で 30,000） | **バッチ先読みで受信者数に依存しない O(1) 群クエリへ** |
| 村フィード read（先頭ページ） | 69 ms・1 クエリ | 現状維持（N+1 化させない回帰ガード） |

---

## 7. 参考

- 通知 fan-out 中核: `NotificationHelper.notifyAllPreAuthorized` から `notifyPreAuthorized` から `NotificationService.createNotificationPreAuthorized`（`@Transactional` INSERT）＋ `NotificationDispatchService.dispatch`（`@Async` event-pool）
- 駆動: `VillageMeetupService.createMeetup` から `VillageEventOccurredEvent` から `VillageEventRefluxEventListener`（AFTER_COMMIT/REQUIRES_NEW/@Async無し）から `VillageEventFeedRefluxService.publish`
- 受信者解決: `VillageMembershipRepository.findActiveUserSubjectIdsByVillageId`（active=leftAt/bannedAt 双方 NULL）
- 生実測ログ（PERF_MEASURE・JUnit XML system-out から回収）:
  - `SEED_ms=38511 active=10000 left=30 banned=30 attendances=10000 posts=10000`
  - `AC1_recipients=10000 AC3_left_banned_excluded=true`
  - `AC2_generated=10000 AC3_boundary_notifs=0 AC6_fanout_wall_ms=42562 AC7_e2e_dispatch_completed=6471`
  - `AC7_burst=10000 queueCapacity=100 maxPool=5 handler=AbortPolicy accepted=105 rejected=9895`
  - `AC8_per_dispatch_queries=3 projected_10k=30000`
  - `AC5_feed_queries=1 feed_size=20 total_posts=10000 AC9_getfeed_latency_ms=69`
  - `AC4_repo_q20=2 repo_q100=2 svc_q20=7 svc_q100=7 total_rows=10000`
