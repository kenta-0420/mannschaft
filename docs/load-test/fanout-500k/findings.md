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

**本 findings は「run 待ち」のプレースホルダである。** 実測値欄（§3）は 50万本走（推定約40分・この出陣の
範囲外）を実行してから埋める。埋めた値は **一度確定したら freeze**（後続 fix の before 基準として固定し、
数値を書き換えず「改善後の再測定」を別セクションとして追記する）。

- 出陣時点で実行できたのは以下のみ:
  1. コンパイル確認（`./gradlew compileTestJava`）
  2. クラッシュ再開 IT（20,000件・40チャンク）の実 RUN
  3. 500k IT の**縮小 smoke**（配線・PERF_MEASURE 出力・アサートの動作確認のみ。件数は縮小値）
- **50万本走はマスター指示で本出陣の範囲外**。実施時は本ファイルの §3 を実測値で埋め、JUnit XML の
  `tests/skipped/failures` を裏取りして record する。

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

## 3. 実測値（50万本走・run 待ち）

| 指標 | SLO | 実測値 |
|---|---|---|
| enqueue 応答レイテンシ | < 300 ms | *（run 待ち）* |
| 完走壁時計 | <= 120 s | *（run 待ち）* |
| 生成 notifications 件数 | = 500,000 | *（run 待ち）* |
| job.insertedCount | = 500,000 | *（run 待ち）* |
| 最終 status | != DEAD_LETTER | *（run 待ち）* |
| seed 投入時間（JDBC バッチ） | 参考記録 | *（run 待ち）* |

生値は `PERF_MEASURE key=value` 行（`System.out` 直接出力・logback 非経由）を JUnit XML の
`<system-out>` から回収する（`docs/load-test/fanout-10k/findings.md` §1 と同じ作法）。

---

## 4. クラッシュ再開実測（20,000件・40チャンク）

`NotificationFanoutOrgCrashResumeIT` は 20 チャンク目（10,000件付近）の INSERT 確定直後・カーソル未前進の
状態で例外を発生させ、再開させる。破断点の根拠は同クラスの javadoc（呼び出し順 `insertAndDispatchChunk`
→ `job.setCursorSubjectId`（in-memory）→ `jobService.advanceCursor`（DB 独立コミット）の間で例外を投げる）
を参照。

| 指標 | 実測値 |
|---|---|
| クラッシュ直後の inserted 件数 | *（本出陣の実走結果は最終報告を参照。母集団 20,000 未満）* |
| 再開後の総 notifications 件数 | *（母集団以上・母集団+500 以下）* |
| DISTINCT user_id | *（= 20,000）* |
| 最終 status | *（DONE）* |

---

## 5. 対象スコープの選定に関する割り切り

`Fanout500kSeeder` は 1 組織への**直属**メンバー（`user_roles.organization_id`）のみで50万件を構成し、
`team_org_memberships` 経由のチーム所属メンバーは対象に含めない。実クエリ
`UserRoleRepository#findDistributionUserIdsForOrganizationRecursiveKeyset` は
`organization_id IN org_tree` OR `team_id IN (配下ACTIVEチーム)` の和集合だが、投入コストを支配しない
ため直属経路のみで測定する（詳細は `Fanout500kSeeder` javadoc）。チーム経由の再帰展開自体の正しさは
`OrgFanoutRecipientSourceRedIT`（AC-2/AC-14/AC-15）が別途カバー済み。

---

## 6. 参考

- ワーカー: `backend/src/main/java/com/mannschaft/app/notification/fanout/NotificationFanoutWorker.java`
- ジョブサービス: `backend/src/main/java/com/mannschaft/app/notification/fanout/NotificationFanoutJobService.java`
- 受信者ソース: `backend/src/main/java/com/mannschaft/app/role/fanout/OrgFanoutRecipientSource.java`
- 1万人規模の先行実測（村 fan-out）: `docs/load-test/fanout-10k/findings.md`
