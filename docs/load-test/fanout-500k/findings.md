# 通知 fan-out 50万人規模 負荷試験 findings（CMP-001）

- **戦役台帳**: `docs/task-list.md` CMP-001（通知 fan-out 抜本改修 50万目標）
- **射程**: **測定＋文書化のみ**。production コードの挙動は本出陣で一切変更していない。
- **対象**: ORGANIZATION スコープ耐久 fan-out（`OrgFanoutRecipientSource` → `NotificationFanoutWorker`）を
  1 組織直属の50万 ACTIVE メンバーで実測する。
- **測定 IT**: `backend/src/test/java/com/mannschaft/app/notification/fanout/perf/NotificationFanoutOrg500kMeasurementIT.java`
- **クラッシュ再開 IT**: `backend/src/test/java/com/mannschaft/app/notification/fanout/perf/NotificationFanoutOrgCrashResumeIT.java`（母集団 20,000 に縮小・40チャンク）
- **seed ヘルパー**: `backend/src/test/java/com/mannschaft/app/support/perf/Fanout500kSeeder.java`（JDBC バッチ INSERT。`Fanout10kSeeder` の Hibernate persist 方式では50万件が約32分かかるため不採用）

---

## 1. record-then-freeze 方針

**本 findings は 50万本走の実測値で確定・freeze 済み**（2026-08-06）。§3 の数値は実行済みの本走
（JUnit XML 裏取り済み）から record したものであり、以後書き換えない（改善後の再測定は別セクションとして
追記する）。

- 実行済み:
  1. コンパイル確認（`./gradlew compileTestJava`）
  2. クラッシュ再開 IT（20,000件・40チャンク）の実 RUN（AC-8）
  3. 500k IT の縮小 smoke（配線・PERF_MEASURE 出力・アサートの動作確認）
  4. **50万本走**（マスター裁可・本戦役の仕上げとして実施。実測は §3/§7 参照）
- 初回の500k本走は seeder の `USER_ID_BASE` 固定値衝突により異常終了。原因を `Fanout500kSeeder` の
  `NEXT_USER_ID_BASE`（`AtomicLong` 単調採番）へ根治修正した後に完走した（詳細 §8）。

---

## 2. 実行方法（CI smoke から分離）

```bash
cd backend
# 事前に WSL2 Docker + 2375 TCP プロキシが生きていること（Testcontainers 用）
export DOCKER_HOST=tcp://127.0.0.1:2375
export TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1
./gradlew perfTest -Pmax.parallel.forks=1 --tests "*Fanout500k*" --tests "*CrashResume*"
```

- `perfTest` は `@Tag("perf")` のみを実行する専用タスク（通常の `test`/CI smoke からは除外）。
- **SKIP 偽緑に注意**: Docker 不通だと `@EnabledIf(isDockerAvailable)` で静かに SKIP し `BUILD SUCCESSFUL` になる。
  実 RUN の裏取りは JUnit XML の `tests skipped="0"` と Docker 疎通（`curl http://127.0.0.1:2375/_ping`）。
- 50万本走は壁時計が長い（推定約40分）ため、`--tests` で対象クラスを絞って単独実行すること。

---

## 3. 実測値（50万本走・確定・freeze）

| 指標 | SLO | 実測値 | 判定 |
|---|---|---|---|
| enqueue 応答レイテンシ | < 300 ms | 231 ms | ✅ |
| 完走壁時計 | <= 120 s | 2634.891 s（≈43.9分） | ❌（約22倍超過） |
| 生成 notifications 件数 | = 500,000 | 500,000 | ✅ |
| job.insertedCount | = 500,000 | 500,000 | ✅ |
| 最終 status | != DEAD_LETTER | DONE | ✅ |
| seed 投入時間（JDBC バッチ） | 参考記録 | 69,554 ms（≈69.5秒） | 参考 |
| スループット（推定） | 参考記録 | ≈190 件/秒（500,000 ÷ 2634.891s） | 参考 |

生値は `PERF_MEASURE key=value` 行（`System.out` 直接出力・logback 非経由）を JUnit XML の
`<system-out>` から回収した（`docs/load-test/fanout-10k/findings.md` §1 と同じ作法）。

### SLO判定表（サマリ）

| 項目 | 結果 |
|---|---|
| enqueue O(1)（<300ms） | ✅ 231ms |
| 取りこぼし0（generated=母集団） | ✅ generated=500,000 |
| 最終ステータス（≠DEAD_LETTER） | ✅ DONE |
| 完走壁時計（≤120秒） | ❌ 43.9分（22倍超過） |

### 結論

耐久土台は正しい（50万完走・欠落0・enqueue O(1)・クラッシュ再開可）。ボトルネックは単一ワーカーの
スループット（≈190件/秒）であり、≤120秒 SLO を達成するにはワーカー並列化（ジョブ/カーソルのシャーディング
並列消化）が必要——これは**次Phase**として切り出す（マスター裁可 2026-08-06）。本戦役（CMP-001 ④50万負荷
試験）は「単一ワーカー限界の実証」として完了とする。

---

## 4. クラッシュ再開実測（20,000件・40チャンク）

`NotificationFanoutOrgCrashResumeIT` は 20 チャンク目（10,000件付近）の INSERT 確定直後・カーソル未前進の
状態で例外を発生させ、再開させる。破断点の根拠は同クラスの javadoc（呼び出し順 `insertAndDispatchChunk`
→ `job.setCursorSubjectId`（in-memory）→ `jobService.advanceCursor`（DB 独立コミット）の間で例外を投げる）
を参照。

| 指標 | 実測値 |
|---|---|
| クラッシュ直後の inserted 件数 | 10,000（crash_at_chunk=20） |
| 再開後の総 notifications 件数 | 20,500（母集団20,000+重複500） |
| DISTINCT user_id | 20,000 |
| 重複行数 | 500（最大1チャンク分） |
| 最終 status | DONE |

at-least-once（欠落0・重複は最大1チャンク分）を実証。

---

## 5. 対象スコープの選定に関する割り切り

`Fanout500kSeeder` は 1 組織への**直属**メンバー（`user_roles.organization_id`）のみで50万件を構成し、
`team_org_memberships` 経由のチーム所属メンバーは対象に含めない。実クエリ
`UserRoleRepository#findDistributionUserIdsForOrganizationRecursiveKeyset` は
`organization_id IN org_tree` OR `team_id IN (配下ACTIVEチーム)` の和集合だが、投入コストを支配しない
ため直属経路のみで測定する（詳細は `Fanout500kSeeder` javadoc）。チーム経由の再帰展開自体の正しさは
`OrgFanoutRecipientSourceRedIT`（AC-2/AC-14/AC-15）が別途カバー済み。

---

## 6.5 その他の AC 実測

| AC | 内容 | 実測値 |
|---|---|---|
| AC-6 | 母集団0件のジョブは即 DONE | status=DONE, generated=0 |
| AC-7 | CHUNK(500)/1000 境界のページング | generated=1001, distinct=1001, status=DONE |
| AC-9 | 二重 enqueue の冪等性 | job件数=1（重複ジョブ作成なし） |
| AC-10 | FROZEN/削除ユーザー除外 | delivered=10, population=10（凍結・削除メンバーは配信対象から除外） |

---

## 7. 1回目異常終了の真因と根治

初回の500k本走は `Fanout500kSeeder` の固定値 `USER_ID_BASE` を複数 IT クラスが共有していたことによる
user_id 衝突（一意制約違反）で異常終了した。根治として `NEXT_USER_ID_BASE`（`AtomicLong` による単調採番）
へ変更し、同一 JVM 内で複数 IT（500k本走・crash-resume・AC-6/7 の別 seed 呼び出し）が衝突なく共存できる
ようにした。以後の再走で完走を確認済み。

---

## 8. 実行環境

- `perfTest -Pmax.parallel.forks=1`（並列実行なし。Testcontainers 競合回避）
- CI 非搭載（`@Tag("perf")` は通常の `test`/CI smoke から除外）
- `DOCKER_HOST=tcp://127.0.0.1:2375`（WSL2 Docker TCP プロキシ経由）

---

## 9. 参考

- ワーカー: `backend/src/main/java/com/mannschaft/app/notification/fanout/NotificationFanoutWorker.java`
- ジョブサービス: `backend/src/main/java/com/mannschaft/app/notification/fanout/NotificationFanoutJobService.java`
- 受信者ソース: `backend/src/main/java/com/mannschaft/app/role/fanout/OrgFanoutRecipientSource.java`
- 1万人規模の先行実測（村 fan-out）: `docs/load-test/fanout-10k/findings.md`
