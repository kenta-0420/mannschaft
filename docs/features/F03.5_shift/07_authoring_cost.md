# F03.5 シフト管理 — §12 人件費（概算）・目安・過去実績

> **ステータス**: 🟡 設計案（マスター裁可済みの方針を反映）・実装未着手
> **初版**: 2026-09-09（軍議。手動シフト作成支援への方針転換に伴う金銭側の設計）
> **姉妹文書**: [`06_manual_authoring.md`](06_manual_authoring.md)（自動割当の停止・枠作成・割当支援）。**本文書は §11 戦役 D に対応する**
> **関連**: F08.7（シフト-予算-TODO 連携）、[`01_db_design.md`](01_db_design.md) §3、[`04_security_operations.md`](04_security_operations.md) §6
> **実測基準**: `origin/main`（2026-09-09 時点）。本文の file:line はすべて本文書作成時に実物で確認した値

---

## 12.0 呼称の規約（マスター裁可・**変更禁止**）

**金額を「給与」と称してはならない。** 画面・API・ドキュメントのいずれにおいても **「人件費（概算）」** と呼ぶ。

さらに、金額を表示するすべての画面に**次の注記を必須表示**する:

> 実際の支給額とは異なる場合があります（割増・手当・控除を含みません）。

本人向けの画面は「**勤務実績と人件費の目安**」、時間のみを示す箇所は「**勤務実績（時間）**」と呼ぶ。

---

## 12.1 既存資産の再利用（新規に作らない）

### 12.1.1 実測

| 資産 | 実測結果 | 出典 |
|---|---|---|
| 時給テーブル | `shift_hourly_rates`（`user_id` × `team_id` × `hourly_rate DECIMAL(10,2)` × `effective_from DATE`、UNIQUE `uq_shr_user_team_from`） | `backend/src/main/resources/db/migration/V3.076__create_shift_hourly_rates_table.sql` |
| 有効時給の取得 | `ShiftHourlyRateRepository#findEffectiveRate(userId, teamId, date)` | `backend/src/main/java/com/mannschaft/app/shift/repository/ShiftHourlyRateRepository.java:27` |
| 認可の金型 | `ShiftHourlyRateService#checkHourlyRateAccess`（`listHourlyRates:45` / `getEffectiveRate:61` / `createHourlyRate:77` が使用） | `backend/src/main/java/com/mannschaft/app/shift/service/ShiftHourlyRateService.java:122` |
| 時間計算 | `ShiftBudgetConsumptionService.calculateHours(start, end)`（**public static**、日跨ぎ +24h 済み） | `backend/src/main/java/com/mannschaft/app/shiftbudget/service/ShiftBudgetConsumptionService.java:164` |
| 確定後の集計 | `shift_budget_consumptions`（`hourly_rate_snapshot` / `hours` / `amount` / `currency` / `status`） | `backend/src/main/resources/db/migration/V11.031__create_shift_budget_consumptions.sql` |
| 消費記録の生成 | `ShiftBudgetConsumptionRecordListener` が `ShiftPublishedEvent` を AFTER_COMMIT ＋ `@Async` で購読して INSERT | `backend/src/main/java/com/mannschaft/app/shiftbudget/listener/ShiftBudgetConsumptionRecordListener.java:119,127` |

### 12.1.2 方針

上記をすべて**再利用する**。特に時間計算は `calculateHours` を呼ぶだけとし、**別実装を作らない**（日跨ぎ +24h の扱いが二重定義になると、金額が経路によって食い違う）。

---

## 12.2 作成中の見積り（`authoring-view` の `cost`）

### 12.2.1 なぜ新規が必要か

消費記録（`shift_budget_consumptions`）は **公開後にしか存在しない**（`ShiftBudgetConsumptionRecordListener` が `ShiftPublishedEvent` を購読するため）。したがって「いま組んでいる最中のシフトがいくらになるか」を既存資産だけでは出せない。

### 12.2.2 設計

`GET /api/v1/shifts/schedules/{scheduleId}/authoring-view` のレスポンスの `cost` として、**読み取り専用のオンザフライ計算**を行う。

> **永続化しない。`ShiftBudgetConsumptionEntity` を `PLANNED` で先行 INSERT してはならない。** 公開時 hook と**二重計上**になる。

**内訳**:

| 項目 | 内容 |
|---|---|
| 日別合計時間 | その日の全枠の `hours` 合計 |
| 日別人件費 | その日の全枠の `hours × 有効時給` 合計 |
| 人別（日 / 期間 / 月）時間 | `counters` と同じ 3 粒度 |
| 人別 月額 | 暦月の人件費 |
| チーム 月合計 | 暦月のチーム全体人件費 |

### 12.2.3 見積り・見込み・確定の区別（**3 状態**）【Codex 検分 P1-7・方針変更】

当初案は「`estimate`（未公開）と `confirmed`（公開済み）の 2 状態」としていた。**これは誤りである。**

**実測**: `shift_budget_consumptions` は**シフト公開時に `PLANNED` で INSERT され、月次締めで初めて `CONFIRMED` へ昇格する**（`backend/src/main/resources/db/migration/V11.031__create_shift_budget_consumptions.sql` 冒頭コメント ＋ `chk_sbc_status CHECK (status IN ('PLANNED','CONFIRMED','CANCELLED'))`、`confirmed_at` 列）。

したがって「公開済み ＝ 確定」と表示すると、**締め前でまだ変更可能な見込み額を確定額として誤表示する**。マスターは「確定と見積りを混同させるな」と明確に要求している。

**契約（3 状態を別フィールドで並べる。同一カラムに混ぜない）**:

| フィールド | 対象 | 由来 | 画面表記 | FE の描き分け |
|---|---|---|---|---|
| `estimate` | **未公開**のシフト | オンザフライ計算（永続化なし） | **見積り** | 破線枠 |
| `planned` | **公開済み・月次締め前** | `shift_budget_consumptions` の `status = PLANNED` | **見込み** | 実線・細字 |
| `confirmed` | **月次締め済み** | `shift_budget_consumptions` の `status = CONFIRMED` | **確定** | 実線・太字 |

- `CANCELLED` はいずれにも計上しない。
- 3 つは**排他**であり、同一の (slot, user) が 2 つのフィールドに現れてはならない。
- 「合計」を表示する場合、**3 つを足した単一の数字を主役にしない**（性質の違う金額の合算は誤読の元）。内訳を必ず併記する。

### 12.2.4 時給未設定者

既存の公開時 hook は**黙って 0 円**にしている（`ShiftBudgetConsumptionRecordListener.java:127` の `orElse(BigDecimal.ZERO)`）。

**見積り側では 0 円にしない。** `unratedUserIds[]` を返し、画面に「**時給未設定 N 名（金額に未反映）**」と明示する。

> 公開時 hook 側の 0 円黙認は**射程外**とし、[`06_manual_authoring.md`](06_manual_authoring.md) §11.8-2 として起票する。

### 12.2.5 丸めと通貨

- 計算は **scale 2**、表示は**円未満切り捨て**。
- 通貨は **JPY**。

### 12.2.6 認可

- `cost` はチーム **ADMIN / DEPUTY_ADMIN**（＋ `SYSTEM_ADMIN`）のみ。
- 一般 MEMBER は `authoring-view` 自体に到達不能（403）であり、**他人の時給が構造的に見えない**。
- SUPPORTER は参照系も不可。
- フラグ OFF 時は `cost` を **`null`** で返す（**503 にしない**。作成支援そのものは動くべきであるため）。

---

## 12.3 人件費の目安（新規テーブル `shift_labor_cost_targets`）

### 12.3.1 なぜ既存 `shift_budget_allocations` を使わないか（マスター裁可済み）

| # | 理由 |
|---|---|
| 1 | `fiscal_year_id` / `budget_category_id` が **NOT NULL** であり、作成者に会計側の入力を強いる |
| 2 | 権限が**組織スコープ**の `BUDGET_VIEW` / `BUDGET_ADMIN` であり、**シフト作成者が持つとは限らない** |
| 3 | `consumed_amount` をアトミックに増減する**会計の正本**であり、閾値アラート・月次締め・`budget_transactions` の仕訳に直結する。**作成者のメモ的な目安で会計台帳に行を作るのは「正本を 1 つに保つ」原則に反する** |

（実測: `V11.030__create_shift_budget_allocations.sql` / `V11.033__create_budget_threshold_alerts.sql`）

### 12.3.2 スキーマ

**Entity は `UuidV7Entity` を継承する（必須）。**【Codex 検分 P1-6】

> `docs/architecture/domain_db_design_principles.md` §6「新規テーブルの主キーは `UuidV7Entity` を継承する（2026-05-11〜）」の対象である。**既存のシフト系テーブル（`shift_slots` / `shift_hourly_rates` / `shift_budget_consumptions` 等）が `BIGINT AUTO_INCREMENT` なのは、それらが規約制定前に作られたからであり、倣ってはならない。** 同原則は「既存テーブルの BIGINT ID は変更しない」とも言っており、新旧の混在は想定されている。

```java
public class ShiftLaborCostTargetEntity extends UuidV7Entity {
    // id は UuidV7Entity が持つ（UUID 型・自動生成）
}
```

| カラム | 型・制約 |
|---|---|
| `id` | **`BINARY(16) NOT NULL`**（UUIDv7）。`PRIMARY KEY (id)` |
| `team_id` | `BIGINT UNSIGNED NOT NULL`。ID 参照（**クロスドメイン FK は張らずインデックスのみ**） |
| `period_type` | `DAY` / `MONTH` |
| `target_key` | 日付（`YYYY-MM-DD`）または年月（`YYYY-MM`） |
| `amount` | `DECIMAL(12,0)` |
| `currency` | 既定 `JPY` |
| `note` | 任意メモ |
| `created_by` | 作成者ユーザー ID |
| `version` | 楽観ロック |
| `deleted_at` / `deleted_at_uq` | 論理削除 |

**UNIQUE**: `(team_id, period_type, target_key, deleted_at_uq)`

### 12.3.3 正本の定義

- **日ごとが正本。月合計は独立した目安**である。
- **日次合計が月合計を超えてもエラーにしない（警告のみ）。**
- 月合計だけを設定した場合、日次は**未設定として扱う（日割りしない）**。

### 12.3.4 入力

**単票入力させない。**

```
POST /api/v1/shifts/teams/{teamId}/labor-cost-targets/bulk
```

「期間 × 曜日パターン × 金額」を受け、**BE で日付へ展開**する。**コピー作成（[`06_manual_authoring.md`](06_manual_authoring.md) §11.4）と入力モデルを揃える**: 確定前に件数のプレビューを出し、期間外は `rejected[]` に列挙する。

#### 12.3.4.1 再入力・更新・削除の契約【Codex 検分 P2-3】

当初案は `POST .../bulk` だけを定義していた。`(team_id, period_type, target_key, deleted_at_uq)` に UNIQUE を張るため、**2 度目の入力が制約違反になり、一度設定した目安を修正できない**。`version` 列を持たせながら更新経路が無いのも矛盾している。

**設計**:

| 操作 | 契約 |
|---|---|
| `POST .../labor-cost-targets/bulk` | **upsert（冪等）**。既存の `(team_id, period_type, target_key)` があれば `amount` / `note` を**更新**し、無ければ作成する。同じリクエストを 2 回投げても結果は同じで、2 回目も 200 |
| 結果の列挙 | `created[]` / `updated[]` / `rejected[]` に**排他的**に分類して全件返す（[`06_manual_authoring.md`](06_manual_authoring.md) §11.4.4.1 と同じ考え方）。`created + updated + rejected` が展開後の件数と一致する |
| 楽観ロック | リクエストに任意で `expectedVersion` を含められる。省略時は**上書き**（作成者本人が繰り返し塗り直す運用が主であるため、既定は緩く）。指定して食い違えば **409** とし、現在値を応答に含める（**再取得のために画面を往復させない**） |
| `DELETE .../labor-cost-targets/{id}` | **論理削除**（`deleted_at` を打つ）。削除後は `target: null` となり、§12.3.6 の「未設定」と同じ扱いになる |
| `DELETE .../labor-cost-targets/bulk` | 期間 × 曜日パターンで**まとめて論理削除**（入力モデルを `POST bulk` と揃える） |

**「0 円を設定する」と「未設定にする」は別物である。** 前者は `amount = 0` の行（超過判定が働き、1 円でも使えば超過になる）、後者は行の不在（`target: null`・超過判定なし）。UI でも別の操作として提示する。

### 12.3.5 超過表示

`authoring-view` の `cost` に **`target`** と **`overBy`** を含める。

> **既存 `BudgetThresholdAlertEntity`（80 / 100 / 120% 固定・通知とワークフローを伴う）は流用しない。**
> **閾値アラートは会計の正本用、目安の超過表示は作成支援用であり、意図的に別系統である。**

### 12.3.6 未設定時

`target: null` を返す。**0 円扱いにしない**（0 円扱いにすると、未設定のチームが常に「超過」と表示される）。

### 12.3.7 認可とフラグ

- 認可: チーム **ADMIN / DEPUTY_ADMIN** 以上。
- フラグは **`FEATURE_SHIFT_ENABLED` 配下**とし、**`feature.shift-budget.enabled` には依存させない**。予算機構は prod 既定 OFF であり、縛ると目安機能ごと死ぬため。

### 12.3.8 導線

**シフト作成画面の中**に置く。運営コンソール `/admin/shift-budget/*` には置かない。**読者が違う**（会計担当と現場の作成者）ためである。

> **将来 CMP-260909-0545 が是正されても統合しない。意図的な分離である。**

---

## 12.4 過去実績

### 12.4.1 呼称

- 時間: **「勤務実績（時間）」**
- 金額: **「人件費（概算）」**
- 本人向け画面: **「勤務実績と人件費の目安」**
- **§12.0 の注記を必須表示**（マスター裁可済み）

### 12.4.2 本人向け

```
GET /api/v1/shifts/my/work-summary?month=YYYY-MM
```

- `@SelfScopedEndpoint` を付与し、**自分の分のみ**を返す。他人の `userId` を指定する経路を作らない。
- `shift_budget_consumptions` の自分の行（`PLANNED` / `CONFIRMED`、`deleted_at IS NULL`）を月で集計し、`hours` / `amount` / status 内訳を返す。

#### 12.4.2.1 「月」は勤務月であって計上月ではない【Codex 検分 P1-8】

**実測**: `shift_budget_consumptions` に**勤務日を表す列は無い**。直接使える日時列は `recorded_at`（既定 `CURRENT_TIMESTAMP` ＝ **シフト公開時刻**）・`confirmed_at`・`cancelled_at` のみで、既存インデックスも `idx_sbc_user_recorded (user_id, recorded_at)` である（`V11.031__create_shift_budget_consumptions.sql`）。

したがって `recorded_at` で月を絞ると、**1 月勤務のシフト表を 12 月に公開した場合、1 月分の勤務が 12 月の実績に入る**。「前月いくらだったか」が狂い、年末調整・扶養の確認という §12.4.4 の実需をそのまま裏切る。

**設計**:

1. 月の絞り込みは **`shift_slots` と JOIN し `shift_slots.slot_date` で行う**（`shift_budget_consumptions.slot_id` → `shift_slots.id`）。`recorded_at` は使わない。
2. **日跨ぎ枠は開始日（`slot_date`）の月に数える**（`06_manual_authoring.md` §11.3.3 と同一規則）。
3. レスポンスに `month`（勤務月）とは別に **`publishedInOtherMonthCount`**（公開月が勤務月と異なる行の件数）を含め、画面に「うち N 件は別の月に公開されたシフトです」と注記する（**利用者から見た不一致を黙って均さない**）。
4. 性能: JOIN を伴うため、`shift_slots(slot_date)` 側のインデックスで絞れることを確認する。必要なら `idx` を追加する（**`shift_budget_consumptions` に `slot_date` を非正規化コピーしない**。正本を 2 つにしないため）。

### 12.4.3 管理者向け

**teamId スコープの新規 API を立てる**（マスター裁可済み）。

> 既存 `getConsumptionSummary`（`backend/src/main/java/com/mannschaft/app/shiftbudget/service/ShiftBudgetSummaryService.java:77`、`ShiftBudgetSummaryController.java:67`）は **`organizationId` ＋ `allocationId`** を引数に取る**組織スコープ**の API であり、`BUDGET_ADMIN` 前提のため使わない。

認可は `checkHourlyRateAccess` と**同型**とする（ADMIN / DEPUTY_ADMIN、かつ**対象ユーザーの当該チーム所属も検証**＝対象側 BOLA の封鎖）。

### 12.4.4 対象月

**直近 13 ヶ月を選択可**とする。前月固定にしない（年末調整・扶養の確認で**前年同月**を見る実需があるため）。

### 12.4.5 データ無し

`{"hasData": false}` を返す。**0 円と区別する。**

### 12.4.6 締め後の取消（射程外）

`CANCELLED` は集計から除外されるが、`budget_transactions` の仕訳は動かない。**この不整合の解消は射程外**とする。表示側は「**確定額は月次締め時点の集計です**」と注記し、[`06_manual_authoring.md`](06_manual_authoring.md) §11.8-4 として**起票**する。

### 12.4.7 時給改定耐性

`shift_budget_consumptions.hourly_rate_snapshot` により、過去分の金額は時給改定後も変わらない（**実測済み。欠陥ではない**）。

### 12.4.8 フラグ OFF 時

`hasData: false` ＋「**人件費の記録機能が無効です**」と表示する。**空の 0 円を返さない。**

---

## 12.5 失敗の可視化（[`06_manual_authoring.md`](06_manual_authoring.md) §11.6 の適用）

金銭画面には特に次を課す。

- **未設定・データ無し・取得失敗の 3 状態を区別**する。
- **API 失敗時に金額欄へ 0 円を表示してはならない**（`—` または「取得できませんでした」）。
- 状態は `loading` / `error` / `empty` / `loaded` の 4 状態を持ち、`error` → `empty` へフォールバックしない。

---

## 12.6 PR 分割（戦役 D）

| PR | 内容 | 依存 |
|---|---|---|
| D1 | 時給設定 UI | なし（運営管理 21 枚の到達性は §12.8-9 の起票に依存） |
| D2 | 見積り `cost`（`authoring-view` へ合流） | C3 |
| D3 | 目安（`shift_labor_cost_targets` DDL ＋ 一括入力 ＋ 超過表示） | D2 |
| D4 | 過去実績（本人向け ＋ 管理者向け） | なし（**C と並行可**） |

**Flyway 採番**: D3 のみ DDL を伴う。本文書作成時点の `origin/main` の最大 major は **204**（実測）だが、**着手直前に採り直すこと**。

---

## 12.7 設計原則との整合

- `docs/architecture/domain_db_design_principles.md`: `shift_labor_cost_targets` の `team_id` は**クロスドメイン FK を張らず ID 参照 ＋ インデックスのみ**。`@Transactional` は shift ドメイン内に閉じる。会計の正本（`shift_budget_allocations` / `budget_transactions`）へは**一切書かない**（正本を 1 つに保つ）。
- `docs/security/README.md`: 金額を返すすべての経路を**入口で認可**し、対象ユーザーの所属チームまで検証する（対象側 BOLA）。エラー応答から内部識別子を漏らさない。

---

## 12.8 射程外として起票する項目（金銭側）

[`06_manual_authoring.md`](06_manual_authoring.md) §11.8 の一覧のうち、本文書に関係するもの:

| # | 項目 | 参照 |
|---|---|---|
| 2 | 時給未設定者が黙って 0 円で計上される（公開時 hook） | §12.2.4 |
| 4 | 月次締め後の `CANCELLED` と `budget_transactions` のズレ | §12.4.6 |
| 6 | `ShiftHourlyRateService#deleteHourlyRate` に認可が無い（`ShiftHourlyRateService.java:96`。呼び出し元ゼロ・API 未公開のため現時点の悪用経路は無いが、**エンドポイントを生やした瞬間に無認可で他人の時給を消せる**） | §12.1.1 |
| 9 | 運営管理 21 枚が到達不能（時給設定 UI がここに含まれる可能性。CMP-260909-1141） | §12.6 D1 |

---

## 12.9 受け入れ条件（AC）

> AC-1〜8群・AC-11〜13群は [`06_manual_authoring.md`](06_manual_authoring.md) §11.9 を参照。本節は**金銭側の AC-9群・AC-10群**のみを扱う。

### AC-9群 人件費の見積り（9件・PR D2）

| ID | 種別 | 受け入れ条件 |
|---|---|---|
| AC-9-01 | [IT] | 時給 1200 円のメンバーが 3 時間の枠に入っているとき、日別人件費が **3600** になる |
| AC-9-02 | [IT] | 時給未設定のメンバーは**金額に 0 円として加算されず**、`unratedUserIds[]` にその `userId` が列挙される |
| AC-9-03 | [IT] | `effective_from` を跨ぐ期間では、**各日の有効時給**（`findEffectiveRate(userId, teamId, date)`）が使われる |
| AC-9-04 | [IT] | 見積り計算を実行しても `shift_budget_consumptions` に **1 行も INSERT されない** |
| AC-9-05 | [IT] | **未公開シフトが `estimate`**、**公開済み・締め前（`PLANNED`）が `planned`**、**月次締め済み（`CONFIRMED`）が `confirmed`** の**3 つの別フィールド**に返り、同一の (slot, user) が 2 つのフィールドに現れない（§12.2.3） |
| AC-9-09 | [IT] | 公開しただけ（`status = PLANNED`）のシフトが **`confirmed` に一切計上されない**（締め前の見込み額を確定額として返さない）。`CANCELLED` はいずれのフィールドにも計上されない |
| AC-9-06 | [IT] | 機能フラグ OFF のとき `cost` が **`null`** で返り（503 にならず）、`slots` / `candidates` / `timeline` は正常に返る |
| AC-9-07 | [IT] | 通貨が `JPY` で返り、表示値が**円未満切り捨て**（計算は scale 2） |
| AC-9-08 | [IT] | 一般 MEMBER の経路からは `cost` に**到達できない**（`authoring-view` 自体が 403） |

### AC-10群 目安と過去実績（7件・PR D3/D4）

| ID | 種別 | 受け入れ条件 |
|---|---|---|
| AC-10-01 | [IT] | `labor-cost-targets/bulk` に「期間 × 曜日パターン × 金額」を投入すると、**作成件数がプレビュー件数と一致**し、期間外の日付は `rejected[]` に列挙され、**月合計は `period_type = MONTH` の別行**として保存される |
| AC-10-02 | [IT] | 目安が未設定のとき `target: null` が返り（0 円扱いにならず）、**超過判定が行われない**（`overBy` が返らない） |
| AC-10-03 | [IT] | 時給を改定しても**前月分の `amount` が変わらず**（`hourly_rate_snapshot`）、`CANCELLED` の行は集計から除外され、対象月に行が 1 件も無ければ `hasData: false` が返る（0 円と区別される） |
| AC-10-04 | [IT] | **同じ `bulk` リクエストを 2 回投げても 2 回目が 200** になり（制約違反にならず）、2 回目は `updated[]` に列挙され、**行数が増えない**（冪等性。§12.3.4.1） |
| AC-10-05 | [IT] | `expectedVersion` が現在値と食い違うと **409** になり、応答に現在値が含まれる。`expectedVersion` 省略時は上書きされて 200 |
| AC-10-06 | [IT] | `DELETE .../labor-cost-targets/{id}` 後に当該日の `target` が **`null`** になり（0 円ではない）、超過判定が行われない。**`amount = 0` の行が存在する場合とは区別**され、後者では 1 円の消化でも `overBy` が返る |
| AC-10-07 | [IT] | **1 月勤務のシフト表を 12 月に公開**したとき、`work-summary?month=<1月>` に当該勤務が計上され、`month=<12月>` には計上されない（`shift_slots.slot_date` で絞る。§12.4.2.1）。かつ 1 月の応答の `publishedInOtherMonthCount` が **1 以上**になる |
