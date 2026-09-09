# F03.5 シフト管理 — §11 手動シフト作成支援（自動割当の停止と「線を引く」作成体験）

> **ステータス**: 🟡 設計案（マスター裁可済みの方針を反映）・実装未着手
> **初版**: 2026-09-09（軍議。方針転換に伴い旧 `06_request_time_window.md`（CMP-260909-1142）を差し替え）
> **前提課題**: CMP-260909-1143「同じ日に枠が2つあると2件目のシフト希望が出せない」（**本設計の射程に含める**。§11.5.1 参照）
> **姉妹文書**: [`07_authoring_cost.md`](07_authoring_cost.md)（人件費（概算）・目安・過去実績）
> **関連**: [`01_db_design.md`](01_db_design.md) §3、[`02_api_design.md`](02_api_design.md) §4、[`03_business_logic.md`](03_business_logic.md) §5、[`04_security_operations.md`](04_security_operations.md) §6、[`05_unpublished_visibility.md`](05_unpublished_visibility.md) §10
> **実測基準**: `origin/main`（2026-09-09 時点）。本文の file:line はすべて本文書作成時に実物で確認した値

---

## 11.0 方針転換（マスター裁可・2026-09-09）

**自動割当はいったん停止する。** シフトは**手動で作る**前提とし、作成者が「線を引き」、その線へ勤務者を入れていく体験に作り替える。勤務日数・勤務時間をカウントし、事前の勤務申請から「その日に入れる人・入れる時間」が作成時に見えるようにして、**シフト作成者が組みやすくする**ことを本設計の目的とする。

### 11.0.1 前提（確定・変更禁止）

| # | 前提 |
|---|---|
| 1 | **1本＝1人の勤務**。12:00-20:00 の営業で 12:00-14:00 が4人なら **12-14 の枠を4本**、13:00-18:00 に1人なら **13-18 を1本** |
| 2 | **同じ人が同じ日に複数本へ入れる**（8時間勤務の1時間休憩 → 12:00-15:00 と 16:00-20:00 の2本） |
| 3 | **時間が重なる2本に同じ人は入れない**。ただし機械的に禁止せず**警告に留め保存は許す**（§11.3.5） |
| 4 | 勤務日数は「同じ日に何本入っても1日」 |

### 11.0.2 マスター裁可済みの決定（変更禁止）

| # | 決定 |
|---|---|
| 1 | 金額の呼称は「給与」としてはならない。**「人件費（概算）」**とし、注記を必須表示（→ [`07_authoring_cost.md`](07_authoring_cost.md)） |
| 2 | 日次の人件費目安は既存の予算台帳を使わず、**作成支援専用の新規テーブル `shift_labor_cost_targets`** を新設（→ [`07_authoring_cost.md`](07_authoring_cost.md)） |
| 3 | 割当の重なりは**警告に留め保存は許す**。**完全一致（同一時刻・同一人物の2本）のみ 409 で拒否** |
| 4 | 枠時刻の刻みは **15分グリッド**。予約ドメインは30分だが、シフトは「17:45上がり」が現実に存在するため非対称を許容する |
| 5 | 旧 `06_request_time_window.md`（CMP-260909-1142）は縮小。**時刻カラム追加（永続化）の章は破棄**し、表示・突合の仕様は本設計の作成画面へ吸収する |
| 6 | 過去実績の管理者向け参照は、既存 `getConsumptionSummary`（組織スコープの予算権限が前提）を使わず、**teamId スコープの新規 API** を立てる（→ [`07_authoring_cost.md`](07_authoring_cost.md)） |

---

## 11.1 自動割当の停止

### 11.1.1 実測

| 観測点 | 実測結果 | 出典 |
|---|---|---|
| 自動割当のエンドポイント | **6本**（`auto-assign` POST / `auto-assign/confirm` POST / `auto-assign` DELETE / `assignment-runs` GET / `assignment-runs/{runId}` GET / `assignment-runs/{runId}/confirm-visual-review` POST） | `backend/src/main/java/com/mannschaft/app/shift/controller/ShiftAutoAssignController.java:44,58,71,84,97,110` |
| 同コントローラの `@RequireFeature` | **無い**（クラス・メソッドとも。`RequireFeature` の import すら無い） | 同上 |
| フラグ機構 | `feature_flags` テーブル ＋ `@RequireFeature` ＋ FE `GATE_ROUTE_MAP` | `backend/src/main/resources/db/migration/V10.003__create_feature_flags_table.sql` / `frontend/app/constants/featureGates.ts:39` |
| 既存のシフト系フラグ | `FEATURE_SHIFT_ENABLED`（`listSchedules` にのみ付与） | `backend/src/main/java/com/mannschaft/app/shift/controller/ShiftScheduleController.java:4,53` |

### 11.1.2 設計

1. 新フラグ `FEATURE_SHIFT_AUTO_ASSIGN_ENABLED` を `feature_flags` へ **`is_enabled = FALSE`** で seed する。書式は既存の単票 seed（`V13.012__insert_feature_flag_equipment_ranking.sql`）に倣う。

   ```sql
   INSERT INTO feature_flags (flag_key, is_enabled, description)
   VALUES ('FEATURE_SHIFT_AUTO_ASSIGN_ENABLED', FALSE, 'シフト自動割当（2026-09-09 方針転換により既定 OFF）')
   ON DUPLICATE KEY UPDATE description = VALUES(description);
   ```

2. BE: `ShiftAutoAssignController` に**クラスレベルで `@RequireFeature("FEATURE_SHIFT_AUTO_ASSIGN_ENABLED")`** を付ける。**実装クラスに付けること**（インターフェースへの付与は Aspect が拾わず、番人 `RequireFeatureInterfaceGuardTest`（`backend/src/test/java/com/mannschaft/app/common/architecture/RequireFeatureInterfaceGuardTest.java`）が禁止している）。**6 エンドポイントすべて**が `FEATURE_GATE_001` で拒否される。

   > **メソッド単位の付け外しは採らない。** ゲートは**全経路を一括で塞ぐ方が穴が少ない**。メソッドごとに付けると、将来エンドポイントが増えたときに付け忘れる。実際に同ドメインの `ShiftScheduleController` は **9 本中 8 本で `@RequireFeature` が抜けている**（§11.8-3）。ここでは単純さが安全につながる。

3. **入口で塞ぐ。FE だけ外すのは不可。** API を直叩きされると「時刻を見ない割当」が入り、`shift_slots.assigned_user_ids` が汚れるため。

4. FE: `useFeatureFlag()` で `board.vue` の自動割当ボタン・実行履歴タブを**非表示ではなく「停止中」表示**にする（disabled ＋ 理由のツールチップ）。**黙って消さない**。

5. `GATE_ROUTE_MAP` には**足さない**。足すと `/teams/*/shifts` 全体が巻き添えで遮断されるため、コンポーネント層で出し分ける。

6. **テストは消さない。** 既存の自動割当テストはフラグ ON で実行する（将来の再開に備えた資産）。

7. `docs/inventory/feature-inventory.yaml` に停止理由を記載する:「時刻を見ない割当が二重割当を生むため。方針転換 2026-09-09」。

### 11.1.3 未確認 run を持つシフト表は公開できなくなる（既知の制約）【Codex 検分 P1-1・申し送り】

**実測（`origin/main`）**: `PUBLISHED` への遷移時に `autoAssignService.assertNoUnreviewedRuns(id)` が呼ばれ（`backend/src/main/java/com/mannschaft/app/shift/service/ShiftScheduleService.java:226`）、当該 schedule に **`SUCCEEDED` の run が 1 件でも存在すれば** `VISUAL_REVIEW_REQUIRED` を投げる（`ShiftAutoAssignService.java:320-326`）。これを解除する唯一の手段は `confirmVisualReview` が run の status を `SUCCEEDED` → `CONFIRMED` へ遷移させることだが（`ShiftAutoAssignService.java:294-306` / `ShiftAssignmentRunEntity.java:117-123`）、**その経路もクラスレベルのゲートで塞がれる**。したがってフラグを OFF にすると、未確認の `SUCCEEDED` run を持つシフト表は公開できなくなる。**これは本設計における既知の制約であり、仕様である。** 本プロジェクトは**本番稼働前でありローカル環境のデータしか存在しないため、該当行は破棄してよい**（移行設計もデータ移行マイグレーションも不要）。

> ⚠️ **将来への申し送り（消してはならない）**
>
> **本番データが存在する状態で改めてこのフラグを OFF にする場合、この経路は障害になる。** 未確認 `SUCCEEDED` run を抱えたシフト表が**永久に公開できなくなり、利用者のデータが人質になる**。その時点では、次のいずれかの移行手順が必須である。
>
> - 停止前に未確認 run を洗い出し、目視確認または破棄を利用者に促す（停止を段階的に行う）
> - あるいは後始末の 2 経路（`confirm-visual-review` / `DELETE .../auto-assign`）をゲートの例外として開ける
>
> **上記が不要なのは「守るべき実データが無い」という現在の前提に依存している。前提が変わったら、この節を読み直すこと。**

---

## 11.2 枠作成（線を引く）

### 11.2.1 実測

| 観測点 | 実測結果 | 出典 |
|---|---|---|
| 枠作成の時刻バリデーション | **無い**。`start < end` すら検証せず、リクエスト値をそのまま Entity へ詰めている | `backend/src/main/java/com/mannschaft/app/shift/service/ShiftSlotService.java` の `createSlot` / `bulkCreateSlots` |
| 一括作成の入力形 | `List<CreateShiftSlotRequest>` を受ける。上限 `@Size(max = 200)` | `backend/src/main/java/com/mannschaft/app/shift/dto/BulkCreateShiftSlotRequest.java:19` |
| `required_count` | Entity 既定 1、リクエストで上書き可 | `backend/src/main/java/com/mannschaft/app/shift/entity/ShiftSlotEntity.java:43` / `backend/src/main/java/com/mannschaft/app/shift/dto/CreateShiftSlotRequest.java:29` |
| 予約側の時刻検証 | `SlotTimeValidator` が存在するが **package-private かつ final**（`com.mannschaft.app.reservation.service`）。30分グリッド・`endsNextDay` 対応済み | `backend/src/main/java/com/mannschaft/app/reservation/service/SlotTimeValidator.java` |

### 11.2.2 `copies`（同一時間帯を N 本引く）

`CreateShiftSlotRequest` に **`copies`（1..50、既定1）** を追加し、**サーバ側で N 行へ展開**する。

- **`required_count` を増やす方式は採らない**（前提①「1本＝1人」を壊すため）。
- `copies` 未指定は 1 行（後方互換）。
- `copies = 0` / `copies = 51` は 400。

### 11.2.3 複数時間帯の同時投入

**行う。** 既存 `bulkCreateSlots` が `List` を受けるため **BE の実装量はほぼゼロ**で、コストは FE ダイアログに閉じる。

### 11.2.4 期間 × 曜日との組み合わせ

「時間帯パターン（時間帯 × 本数 × ポジション）の配列」×「期間 × 曜日」の**直積**で展開する。

- **生成件数のプレビューを必ず出してから確定**させる。
- 上限は既存の `@Size(max = 200)` を踏襲し、**展開後の件数**で判定する（パターン数ではない）。
- 1件でも不正なら**全件ロールバック**。

### 11.2.5 `ShiftSlotTimeValidator` の新設

`com.mannschaft.app.shift.service` に **`ShiftSlotTimeValidator` を新設**する。

> 予約側 `SlotTimeValidator` は **package-private かつ final** で物理的に共有できない（実測）。したがって shift 側に**同型の単一検証点**を新設し、クラス Javadoc に「予約側 `SlotTimeValidator` と同型。刻みのみ 15 分で非対称。**将来 `common` へ統合する候補**」と明記する。

**規則**:

| # | 規則 | 違反時 |
|---|---|---|
| 1 | `start < end`（`endsNextDay = false` のとき） | 400 |
| 2 | 分は **15 の倍数**、秒・ナノは 0 | 400 |
| 3 | 最小 **15 分** | 400 |
| 4 | **24 時間未満**（24 時間ちょうどは不可） | 400 |
| 5 | **日跨ぎは `endsNextDay` を明示**する（`end < start` かつ `endsNextDay = false` は矛盾として拒否、`end > start` かつ `endsNextDay = true` も拒否） | 400 |

**既存データ互換**: **書き込み時のみ検証**する。既存の不正行の一括修正はしない（別途スキャンして起票）。時刻を触らない更新（`note` のみの更新など）は、既存の不正時刻を理由に拒否してはならない。片側だけを更新する場合は、**もう片方の現値と合わせて**検証する。

### 11.2.6 `required_count` の非推奨化

- カラムは**残す**（既存データがあるため）。
- **新規作成は常に 1 を書く**。
- 既存の 2 以上の行は「N人枠」として引き続き表示する。
- FE から 2 以上を作る導線を外す。
- **`required_count` は非推奨（deprecated）。新規は常に 1。** 本節がその宣言である。

---

## 11.3 割当支援 API（本設計の中心）

### 11.3.1 エンドポイント

```
GET /api/v1/shifts/schedules/{scheduleId}/authoring-view?date=YYYY-MM-DD
```

- 権限: チーム **ADMIN / DEPUTY_ADMIN** 限定（＋ `SYSTEM_ADMIN`）。**一般 MEMBER は 403**。SUPPORTER は参照系も不可。
- スコープ: `scheduleId` → `teamId` を**エンティティ経由で解決**して認可する（クライアント指定の `teamId` を信じない）。

### 11.3.2 レスポンス構造

| フィールド | 内容 |
|---|---|
| `slots[]` | 枠 ＋ 割当者 ＋ 時間位置 |
| `candidates[]` | その日のメンバーごとの候補情報（下記） |
| `timeline[]` | 30 分セルへの**読み取り専用**投影（§11.3.4） |
| `cost` | 人件費（概算）。→ [`07_authoring_cost.md`](07_authoring_cost.md) |

**`candidates[]` の各要素**:

| フィールド | 内容 |
|---|---|
| `slotPreferences[]` | **枠ごとの希望**（下記）。「1本＝1人」かつ「同一日に枠ごとの希望を出せる」ため、**単一値では表現できない**【Codex 検分 P1-2】 |
| `dayPreference` | 日単位の希望（`slot_id IS NULL` の行）。無ければ `null` |
| `defaultAvailability` | 曜日既定の `start` / `end`（`member_availability_defaults`） |
| `conflicts[]` | 選択中の枠と重なる既存割当の `slotId` |
| `counters` | 下記 |

**`slotPreferences[]` の各要素**（`slotId` をキーとする配列。空配列は「その日の希望が 1 件も無い」）:

| フィールド | 内容 |
|---|---|
| `slotId` | 対象の枠 |
| `preference` | 5 段階の希望強度 |
| `note` | 希望のフリーテキスト |

**`dayPreference`**（`preference` ＋ `note`）は日単位の希望であり、`slotPreferences[]` と**併存しうる**。同一枠に枠指定と日指定の希望が両方ある場合、**枠指定を優先**して表示する（日指定は補助情報として併記する。**どちらかを黙って捨てない**）。

> **なぜ単一値にしないか**: 同一日に枠 A へ `PREFERRED`・枠 B へ `STRONG_REST` を出せる設計（§11.5.1）にした以上、候補ごとに 1 つの `preference` しか返さない契約は**構造的に情報を落とす**。これは停止した `GreedyShiftAssignmentStrategy#buildPreferenceMap` が日付キーで希望を潰していたのと同じ欠陥であり、それを表示側で再演することになる。

**`counters`**:

| フィールド | 定義 |
|---|---|
| `daysInPeriod` | シフト表期間内の勤務日数。**同日に何本入っても 1 日**（`DISTINCT slot_date`） |
| `hoursInPeriod` | シフト表期間内の勤務時間合計 |
| `daysInMonth` | 暦月の勤務日数 |
| `hoursInMonth` | 暦月の勤務時間合計 |

**集計単位はシフト表期間を主・暦月を従とし、両方を別フィールドで返す。** 理由: 作業の単位は期間だが、労務判断（月上限・扶養）の単位は暦月であり、どちらか一方では足りない。同じクエリの group-by 違いで済むため追加コストは小さい。

### 11.3.3 時間計算

勤務時間は `ShiftBudgetConsumptionService.calculateHours(start, end)`（**public static**、日跨ぎ +24h 済み。`backend/src/main/java/com/mannschaft/app/shiftbudget/service/ShiftBudgetConsumptionService.java:164`）を**再利用する。別実装を作らない。**

日跨ぎ枠は **開始日に数える**（`slot_date` が正）。

### 11.3.4 時間軸の充足表示（`timeline[]`）

**セルの粒度は 15 分とする。**【Codex 検分 P1-5 により 30 分から変更・マスター再裁可が必要】

> **判断理由**: 枠は 15 分グリッド（§11.2.5・マスター裁可済み）である。投影の粒度が枠の粒度より粗いと、`17:30`-`17:45` と `17:45`-`18:00` の**別々の 2 本が同一セルに落ちる**。その結果、17:30 台のセルが `assignedCount = 2` となり「17:30〜18:00 は常時 2 人いる」と読めてしまう（実際は各 15 分に 1 人ずつ）。あるいは実装が片方を落とせば、引いたはずの線が消える。いずれも**「どの時間帯が埋まっていて、どこが穴か」を見るという充足表示の中心目的を壊す**。
>
> **投影の粒度は、必ず永続化の粒度以上に細かくなければならない。** 1 日は 96 セル（15 分 × 96）であり、`authoring-view` は 1 日分を返す契約（`?date=YYYY-MM-DD`）なので、応答量の増加は 2 倍に留まる。

- **15 分セルへの読み取り専用の投影**。予約側 `ReservationGridService`（`backend/src/main/java/com/mannschaft/app/reservation/service/ReservationGridService.java`）の考え方を借用する（予約は 30 分グリッドのため粒度のみ非対称）。
- 各セルは `assignedCount`（人が入っている本数）と `slotCount`（引かれている枠の本数）を持つ。**両者を区別する**ことで「枠は引いたが人が居ない」と「枠自体が無い（＝穴）」を見分けられる。
- FE は視認性のために複数セルを束ねて描いてよいが、**束ねた結果として `assignedCount` を合算してはならない**（束ねた区間の代表値は**最小値**とし、区間内で値が割れている場合は分割して描く）。
- **永続化は絶対にセル単位へ変えない。** 「1枠＝1勤務」の前提はマイシフト・PDF（team / personal 両レイアウト）・予算集計・TODO 連携のすべてに及ぶ。`timeline` はあくまで表示のための投影である。

### 11.3.5 重なり判定

- **半開区間 `[start, end)`**。したがって **15:00 終了と 15:00 開始は重ならない**（隣接は衝突ではない）。
- 日跨ぎは `endsNextDay` を展開して `LocalDateTime` 区間で比較する。
- `patchSlotAssignments` のレスポンスに警告を返す:

  ```json
  { "warnings": [ { "code": "ASSIGNMENT_OVERLAP", "conflictingSlotIds": [123, 456] } ] }
  ```

- FE は**確認ダイアログ**を出し、保存後も**恒久的な警告バッジ**を該当枠に表示する（一度きりのトーストにしない）。
- **完全一致（同一日・同一開始・同一終了・同一人物の 2 本）のみ 409** で拒否する。

### 11.3.6 射程外: 労働基準法の休憩

**労基法の休憩（6 時間超 45 分 / 8 時間超 60 分）の検証は本設計の射程外とする。**

理由: 休憩の永続化モデル（休憩自体を枠として引くのか、勤務枠の属性として持つのか）の判断が要り、その判断は `hours` の計算＝**人件費に直結**する。金銭の正確性に波及する変更を、作成支援と同時には入れない。**起票候補**として §11.8 に記す。

なお「12:00-15:00 と 16:00-20:00 の 2 本」という運用は、休憩を**枠の隙間**として表現しており、モデルとしては整合している。

---

## 11.4 コピー作成

### 11.4.1 エンドポイント

```
POST /api/v1/shifts/schedules/{scheduleId}/slots/replicate
```

### 11.4.2 入力

| フィールド | 内容 |
|---|---|
| `sourceSlotIds[]` | 複製元の**既存**枠。未保存オブジェクトの複製は原子性の議論が二重になるため、**「まず作ってから複製」に統一**する |
| `targetDates[]` | 複製先の日付配列。**曜日・期間の展開は FE で日付配列へ落としてから送る**（BE の入力形式を 1 つに保ち、テストの組合せ爆発を防ぐ） |
| `includeAssignments` | 既定 `true`（割当者ごと複製） |
| `onConflict` | `"ABORT"`（既定）/ `"SKIP"` |

### 11.4.3 衝突の扱い（**黙って落とさない**）

| # | 衝突 | コード | 扱い |
|---|---|---|---|
| 1 | 同一時間帯・同一ポジションの枠が既存 | `DUPLICATE_SLOT` | **警告扱いで作成する**が、結果に必ず列挙する |
| 2 | 同じ人の重なる勤務が既存 | `ASSIGNMENT_OVERLAP` | `ABORT` → 409 で**全件ロールバック**。`SKIP` → **枠は作り、割当だけ空にして** `skipped[]` に理由付きで返す（**線は残して人だけ外す**） |
| 3 | schedule 期間外 | `OUT_OF_PERIOD` | **常に拒否**（幽霊行になるため。`SKIP` でも作らない） |
| 4 | schedule が `PUBLISHED` / `ARCHIVED` | — | 409 |

### 11.4.4 原子性

**既定は全件成功か全件失敗。** 理由:「A さんの 9-12 を月〜木に」は **4 件で 1 つの意図**であり、3 件だけ入った状態は気付かれずに出荷される。

`SKIP` 指定時のみ部分成功を許し、そのとき結果を全件列挙する。

#### 11.4.4.1 結果配列の排他的な定義【Codex 検分 P2-1】

当初案の `created` / `skipped` / `rejected` の 3 本立ては破綻していた。`ASSIGNMENT_OVERLAP` の `SKIP` は「**枠は作るが割当は空**」であり、その組を `created` に入れると `skipped` と二重計上され、入れなければ `created` が実際の作成行数を表さなくなる。

**枠の結果と割当の結果を別の軸に分ける。**

**軸1: 枠（(sourceSlotId, targetDate) の組ごとに必ずどれか 1 つ）**

| 配列 | 意味 |
|---|---|
| `createdSlots[]` | 枠が作られた（`{sourceSlotId, targetDate, newSlotId}`） |
| `rejectedSlots[]` | 枠が作られなかった（`{sourceSlotId, targetDate, code}`。`code` は `OUT_OF_PERIOD` 等） |

**不変条件**: `createdSlots.size() + rejectedSlots.size() == sourceSlotIds.size() × targetDates.size()`（**この 2 本だけで合計検査を行う**）。

**軸2: 割当（`createdSlots` の部分集合にのみ付随する注記）**

| 配列 | 意味 |
|---|---|
| `skippedAssignments[]` | 枠は作られたが**割当を空にした**（`{newSlotId, userId, code: "ASSIGNMENT_OVERLAP"}`） |
| `warnings[]` | 作られたが注意が要る（`{newSlotId, code: "DUPLICATE_SLOT"}`） |

**軸2 は合計検査に加算しない**（軸1 の要素に対する注記であるため）。`DUPLICATE_SLOT` は枠を作るので `createdSlots` に入り、同時に `warnings[]` にも現れる。`ASSIGNMENT_OVERLAP` の `SKIP` は枠を作るので `createdSlots` に入り、同時に `skippedAssignments[]` にも現れる。**各要素がどちらの軸に属するかは排他的に決まる。**

### 11.4.5 認可

`sourceSlotIds` が**全件同一 schedule 配下であること**を検証する。他チームの `slotId` を混ぜられると BOLA になるため、1 件でも外部の ID が混じれば 403 とし、**1 件も作成しない**。

---

## 11.5 希望を出す側（作成者から見た情報源）

### 11.5.1 CMP-260909-1143 の是正（射程内・必須）

シフト希望の重複判定を `(scheduleId, userId, slotDate)` → **`(scheduleId, userId, slotId)`** へ変える。

- `slotId` が `NULL` の日単位希望は、**従来どおり日で判定**する（同一日 1 件）。

**これは「1本＝1人」方式の成立条件そのものである。** 同じ日に枠が 2 本あるのに希望を 1 件しか出せない現状のままでは、作成者が読むべき情報が構造的に欠ける。

#### 11.5.1.1 `slotId` の実体整合検証【Codex 検分 P1-3】

**実測**: `ShiftRequestService#submitRequest`（`backend/src/main/java/com/mannschaft/app/shift/service/ShiftRequestService.java:98-118`）は、schedule の所属チーム（`:100`）と収集ステータス・締切（`:101-102`）は検証するが、**`req.getSlotId()` を一切検証せずそのまま Entity へ詰めている**（`:113`）。したがって現状は **他チームの `slotId` を自チームの `scheduleId` に紐付けられる**。

`slotId` 単位の判定へ移行すると `slotId` が一意性の鍵になるため、この穴は**放置できない**。次を必須とする:

1. `slotId` が非 `null` のとき、**枠を実体で引き**、`slot.scheduleId == req.scheduleId` を検証する。不一致は **403**（存在オラクルを与えないため 404 と畳まず、`04_security_operations.md` の既定に従う）。
2. さらに `slot.slotDate == req.slotDate` を検証する。不一致は **400**（クライアントの自己矛盾であり越境ではない）。
3. 枠が存在しない `slotId` は **403**（他チームの枠と同じ応答にし、ID の存否を漏らさない）。

> **同型の指摘が旧 CMP-260909-1142 の設計でも P1-6 として出ている。** 同じ穴を二度作らないため、本設計では戦役 A（PR A3）の必須要件とする。`docs/security/README.md` の BOLA 対策に必要。

#### 11.5.1.2 一意性は DB で担保する【Codex 検分 P1-4・方針変更】

当初案は「`slot_id` に NULL が混在するため UNIQUE を足さず、アプリ層判定 ＋ 既存 INDEX で足りる」としていた。**これは誤りである。** アプリ層の存在チェック → INSERT は原子的ではなく、同一ユーザーからの 2 リクエストが同時に届けば**両方ともチェックを通過して重複 INSERT できる**。

「NULL 混在」は**単純な UNIQUE を諦める理由にはなるが、一意性そのものを諦める理由にはならない。** 同一リポジトリに前例がある: `shift_budget_consumptions` は `deleted_at_uq DATETIME AS (COALESCE(deleted_at, '9999-12-31 00:00:00')) STORED NOT NULL` という生成列を作り、それを含めて UNIQUE を張っている（`backend/src/main/resources/db/migration/V11.031__create_shift_budget_consumptions.sql:27,40`）。

**設計**: `shift_requests` に正規化した生成列を足し、UNIQUE を張る。

```sql
ALTER TABLE shift_requests
  ADD COLUMN slot_id_uq BIGINT UNSIGNED
      AS (COALESCE(slot_id, 0)) STORED NOT NULL,
  ADD CONSTRAINT uq_sr_schedule_user_slot
      UNIQUE (schedule_id, user_id, slot_id_uq, slot_date);
```

- `slot_id` が非 `null` のときは `(schedule_id, user_id, slot_id)` の一意性になる（`slot_date` は §11.5.1.1-2 の検証により枠と一致することが保証されるため、キーに含めても等価）。
- `slot_id` が `null`（→ `0`）のときは `(schedule_id, user_id, slot_date)` の**日単位の一意性**になり、従来の規則がそのまま保たれる。
- アプリ層の事前チェックは**残す**（親切なエラーメッセージのため）。**制約違反は握りつぶさず** `REQUEST_ALREADY_EXISTS`（409）へ写像する。
- **DDL 適用前に既存の重複行を掃除する**マイグレーションを同一バージョンに含める（最新の 1 件を残し、他を論理削除する。件数をログに残す）。

> この節は当初の「DB に UNIQUE は足さない」という記載を**撤回するものである**。

### 11.5.2 `GreedyShiftAssignmentStrategy` は触らない

`GreedyShiftAssignmentStrategy#buildPreferenceMap` の「日付キーで希望が潰れる」問題は**触らない**（停止中の経路であり、いま直しても検証手段が無い）。**フラグを戻す際の前提条件**として §11.8 に記す。

### 11.5.3 希望に時刻カラムは追加しない（旧設計からの方針転換）

理由:

1. 自動割当を止めた以上、時間帯は**機械の制約ではなく人が読む情報**である。
2. 曜日既定 `member_availability_defaults` が `start_time` / `end_time` を **NOT NULL** で既に持っている。
3. 「1本＝1人」になったことで、**どの枠に希望を出したか自体が時間帯の表明**になる。

3 つ目の正本を作ることは `docs/architecture/domain_db_design_principles.md`（正本を 1 つに保つ）に反する。旧 `06_request_time_window.md` の DDL 章（`shift_requests` への時刻 2 列追加）は**破棄**する。

### 11.5.4 曜日既定のハードコード解除（射程内）

**実測**: `frontend/app/pages/my/shift-availability.vue:85-86` が `startTime: '00:00'` / `endTime: '23:59'` を**ハードコードして送っている**（曜日ごとの希望強度しか UI で入力できない）。

これを実入力へ置き換える。**1 ファイルの修正で `candidates[].defaultAvailability` の質が跳ね上がり、費用対効果が最大**である。

---

## 11.6 FE の状態設計（失敗の可視化）

既存の CMP-260909-0543 / 0544 と同型の欠陥を作らないため、本設計で新設するすべての画面に次を課す。

1. 取得失敗時に**空状態を表示してはならない**。エラー状態（再試行付き）と空状態は**別コンポーネント / 別 `data-testid`** で描き分ける。
2. **未設定・データ無し・取得失敗の 3 状態を区別**する（`labor-cost-targets` 一覧、`my/work-summary` も同様）。
3. 状態管理は `loading` / `error` / `empty` / `loaded` の **4 状態**を持ち、**`error` から `empty` へフォールバックする分岐を持たない**。
4. 人件費・時給の画面で API 失敗時に金額欄へ **0 円を表示してはならない**（`—` または「取得できませんでした」）。

---

## 11.7 戦役分割・PR 分割

| 戦役 | 内容 | 依存 | 並行 |
|---|---|---|---|
| **A 土台** | 枠時刻バリデータ／重なり判定ユーティリティ／CMP-260909-1143 是正／曜日既定のハードコード解除 | なし | 単独先行 |
| **B 自動割当の停止** | フラグ seed ＋ `@RequireFeature` ＋ FE「停止中」表示 | なし | A と並行可 |
| **C 作成支援** | `copies` ＋ 複数時間帯／`replicate`／`authoring-view` | A に全面依存 | C 内 3PR |
| **D 金銭** | 時給設定 UI／見積り `cost`／目安テーブル ＋ 一括入力／過去実績 | C の `authoring-view` に部分依存 | 過去実績のみ C と並行可 |

**PR 分割**:

| PR | 内容 |
|---|---|
| A1 | `ShiftSlotTimeValidator` |
| A2 | 重なり判定ユーティリティ ＋ `warnings` |
| A3 | 希望の `slotId` 化（CMP-260909-1143）＋ **`slotId` の実体整合検証**（§11.5.1.1）＋ **生成列 ＋ UNIQUE と重複掃除の DDL**（§11.5.1.2） |
| A4 | 曜日既定のハードコード解除 |
| B1 | フラグ seed ＋ BE ゲート |
| B2 | FE「停止中」表示 |
| C1 | `copies` ＋ 複数時間帯 |
| C2 | `replicate` |
| C3 | `authoring-view` |
| D1 | 時給設定 UI |
| D2 | 見積り `cost` |
| D3 | 目安（`shift_labor_cost_targets`） |
| D4 | 過去実績 |

**推奨着手順**: 戦役 A（特に **A1 と A3**）→ B → C → D。

理由:
- **A1** は現在 `start > end` の枠すら作れる**出荷済みの穴**であり、放置すると `hours` が負になり人件費が壊れる。
- **A3** は「1本＝1人」方式の成立条件そのもの。
- **B** は依存ゼロ・工数最小で、方針転換を利用者に見せられる。
- **A4** は 1 ファイル修正で情報量が跳ね上がり、費用対効果が最大。

**Flyway 採番**: DDL を伴うのは **A3**（`shift_requests` への生成列 ＋ UNIQUE ＋ 重複掃除）、**B1**（フラグ seed）、**D3**（`shift_labor_cost_targets`）の 3 本。本文書作成時点の `origin/main` の最大 major は **204**（実測）だが、**着手直前に採り直すこと**。A3 と D3 は別 PR のため major を分ける。

---

## 11.8 射程外として起票すべき既存欠陥

いずれも本戦役では**直さない**。実測で見つかったため記録する。

| # | 欠陥 | 実測の出典 |
|---|---|---|
| 1 | **割当の正本が二重**。`shift_slots.assigned_user_ids` と `shift_assignments` テーブルが併存し、`getScheduleSummary` は後者・予算 hook は前者を読む。**同じ割当が別集計になる**（重大・要独立戦役） | `ShiftScheduleService` / `ShiftBudgetConsumptionRecordListener` |
| 2 | 時給未設定者が**黙って 0 円**で計上される | `backend/src/main/java/com/mannschaft/app/shiftbudget/listener/ShiftBudgetConsumptionRecordListener.java:127`（`orElse(BigDecimal.ZERO)`） |
| 3 | `ShiftScheduleController` の **8 エンドポイントに `@RequireFeature` が無い**（マッピングは 9 本あり、`:53` の `listSchedules` にのみ付与。`:71,83,96,110,122,144,168,182` は無し） | `backend/src/main/java/com/mannschaft/app/shift/controller/ShiftScheduleController.java` |
| 4 | 月次締め後に `CANCELLED` になった消費が `budget_transactions` の仕訳とズレる | `ShiftBudgetConsumptionService` / `budget_transactions` |
| 5 | `GreedyShiftAssignmentStrategy` の日付重複計上と時刻無視（**自動割当を再開する場合の前提条件**） | `GreedyShiftAssignmentStrategy#buildPreferenceMap` |
| 6 | **`ShiftHourlyRateService#deleteHourlyRate(rateId)` に認可が無い**。他 3 メソッド（`listHourlyRates`=`:45` / `getEffectiveRate`=`:61` / `createHourlyRate`=`:77`）は `checkHourlyRateAccess` を通すが、**削除だけ素通り**。実測で確認: 呼び出し元ゼロ・API 未公開のため現時点の悪用経路は無いが、**エンドポイントを生やした瞬間に無認可で他人の時給を消せる** | `backend/src/main/java/com/mannschaft/app/shift/service/ShiftHourlyRateService.java:96` |
| 7 | 枠の物理削除で希望も CASCADE 消滅する | `fk_sr_slot ON DELETE CASCADE`（`V3.073__create_shift_requests_table.sql`） |
| 8 | 労基法の休憩（6h 超 45 分 / 8h 超 60 分）の検証が無い（§11.3.6） | — |
| 9 | 運営管理 21 枚が到達不能（時給設定 UI がここに含まれる可能性。CMP-260909-1141） | — |

---

## 11.9 受け入れ条件（AC）

**全群を「失敗するテストとして先に書ける」粒度で記す。** `[UT]` / `[IT]` / `[E2E]` の別を付す。

> **記載場所**: AC-1〜8群・AC-11〜13群は本節に、**AC-9群（人件費の見積り）と AC-10群（目安と過去実績）は [`07_authoring_cost.md`](07_authoring_cost.md) §12.9** に置く（読者が違うため）。

### AC-1群 枠時刻バリデーション（12件・PR A1）

| ID | 種別 | 受け入れ条件 |
|---|---|---|
| AC-1-01 | [UT] | `start == end` の枠作成が 400 になる |
| AC-1-02 | [UT] | `end < start` かつ `endsNextDay = false` の枠作成が 400 になる（時刻の逆転） |
| AC-1-03 | [UT] | `end > start` かつ `endsNextDay = true` の枠作成が 400 になる（`endsNextDay` の矛盾） |
| AC-1-04 | [UT] | 分が 15 の倍数でない時刻（例 `09:10`）の枠作成が 400 になる |
| AC-1-05 | [UT] | 秒・ナノが 0 でない時刻の枠作成が 400 になる |
| AC-1-06 | [UT] | 枠長が 15 分未満になる指定の作成が 400 になる |
| AC-1-07 | [UT] | 枠長が **24 時間ちょうど**の作成が 400 になる |
| AC-1-08 | [UT] | `09:15`-`09:30` の枠作成が**成功**する（最小 15 分の境界） |
| AC-1-09 | [IT] | `22:00`-`02:00` ＋ `endsNextDay = true` の枠作成が**成功**し、`calculateHours` が **4.00** を返す |
| AC-1-10 | [IT] | 不正な時刻で作成を試みたとき、`shift_slots` の行数が **1 行も増えない** |
| AC-1-11 | [IT] | `start` のみを更新する部分更新でも、**保存済みの `end` と合わせて**検証され、結果が不正なら 400 になる |
| AC-1-12 | [IT] | 既存の不正時刻の行に対し、**時刻を触らない更新**（`note` のみ）は拒否されない（既存データ互換） |

### AC-2群 複数本・複数時間帯の一括作成（8件・PR C1）

| ID | 種別 | 受け入れ条件 |
|---|---|---|
| AC-2-01 | [IT] | `copies = 4` で **4 行**が作成され、**全行の `required_count` が 1** である |
| AC-2-02 | [IT] | 2 つの時間帯パターン（本数 2 ＋ 本数 3）を同時投入すると **5 行**が作成される |
| AC-2-03 | [UT] | `copies = 0` と `copies = 51` がいずれも 400 になる |
| AC-2-04 | [IT] | `copies` 未指定で **1 行**が作成される（後方互換） |
| AC-2-05 | [IT] | **展開後**の件数が 200 を超えると 400 になる（パターン数ではなく展開後で判定） |
| AC-2-06 | [IT] | 1 件でも不正な時間帯が含まれると**全件ロールバック**され、`shift_slots` の行数が増えない |
| AC-2-07 | [IT] | 「期間 × 曜日 × 時間帯パターン」の展開件数が、確定前プレビューの件数と**一致**する |
| AC-2-08 | [IT] | 作成された枠の `assigned_user_ids` が **null**（空の線として作られる） |

### AC-3群 重なり判定（9件・PR A2）

| ID | 種別 | 受け入れ条件 |
|---|---|---|
| AC-3-01 | [UT] | 完全一致（`09:00`-`12:00` と `09:00`-`12:00`）が重なりと判定される |
| AC-3-02 | [UT] | 部分重なり（`09:00`-`12:00` と `11:00`-`14:00`）が重なりと判定される |
| AC-3-03 | [UT] | **隣接（`09:00`-`12:00` と `12:00`-`15:00`）は重ならない**と判定される（半開区間） |
| AC-3-04 | [UT] | 包含（`09:00`-`18:00` と `12:00`-`13:00`）が重なりと判定される |
| AC-3-05 | [UT] | 別日の同一時刻は重ならないと判定される |
| AC-3-06 | [UT] | 日跨ぎ枠（`22:00`-`02:00` ＋ `endsNextDay`）と翌日 `01:00`-`05:00` が重なりと判定される |
| AC-3-07 | [IT] | 重なる割当の保存が **200 ＋ `warnings[].code = "ASSIGNMENT_OVERLAP"`** で成功し、`conflictingSlotIds` に相手の `slotId` が入る |
| AC-3-08 | [IT] | **完全一致**の割当は **409** で拒否される |
| AC-3-09 | [IT] | 同日の重ならない 2 本（`12:00`-`15:00` と `16:00`-`20:00`）への同一人物の割当は、**警告が 1 件も出ない** |

### AC-4群 認可・テナント越境（12件・PR C2/C3/D3/D4）

| ID | 種別 | 受け入れ条件 |
|---|---|---|
| AC-4-01 | [IT] | チーム非メンバーの `authoring-view` 取得が **403** |
| AC-4-02 | [IT] | 他チームの `scheduleId` を指定した `authoring-view` 取得が **403** |
| AC-4-03 | [IT] | `replicate` の `sourceSlotIds` に他チームの `slotId` を 1 件混ぜると **403** となり、**1 件も作成されない** |
| AC-4-04 | [IT] | SUPPORTER の `authoring-view` 取得が **403** |
| AC-4-05 | [IT] | SUPPORTER の `replicate` 実行が **403** |
| AC-4-06 | [IT] | 一般 MEMBER の `authoring-view` 取得が **403**（＝他人の時給へ構造的に到達不能） |
| AC-4-07 | [IT] | DEPUTY_ADMIN（`MANAGE_SHIFTS`）の `authoring-view` 取得が **200** |
| AC-4-08 | [IT] | SYSTEM_ADMIN が全チームの `authoring-view` を **200** で取得できる |
| AC-4-09 | [IT] | `GET /shifts/my/work-summary` が**自分の分のみ**を返し、**他人の `userId` を指定する経路が存在しない** |
| AC-4-10 | [IT] | チーム ADMIN が**他チーム**の月次実績を取得しようとすると **403** |
| AC-4-11 | [IT] | 一般 MEMBER が `labor-cost-targets` の作成を試みると **403** となり、**DB の行が増えない** |
| AC-4-12 | [IT] | シフト希望の提出時に**他チームの `slotId`** を自チームの `scheduleId` と組み合わせて送ると **403** となり、`shift_requests` の行が増えない（§11.5.1.1） |

### AC-5群 勤務日数・時間の集計（7件・PR C3）

| ID | 種別 | 受け入れ条件 |
|---|---|---|
| AC-5-01 | [IT] | 同日 2 本（`12:00`-`15:00` ＋ `16:00`-`20:00`）で `daysInPeriod = 1`・`hoursInPeriod = 7.00` |
| AC-5-02 | [IT] | 3 日に 5 本入っている人の `daysInPeriod = 3` |
| AC-5-03 | [IT] | 期間集計（`*InPeriod`）と暦月集計（`*InMonth`）が**別フィールド**で同時に返る |
| AC-5-04 | [IT] | 日跨ぎ枠は**開始日**に数えられ、`hours` は +24h 補正された値になる |
| AC-5-05 | [IT] | 割当者が居ない空枠は `counters` に一切寄与しない |
| AC-5-06 | [IT] | 退会（論理削除）したユーザーの過去の勤務が**黙って消えない**（`candidates` から外れても集計値は保持される） |
| AC-5-07 | [IT] | 枠を削除すると、その人の `counters` から**即座に**当該分が消える |

### AC-6群 時間軸の充足表示（5件・PR C3）

| ID | 種別 | 受け入れ条件 |
|---|---|---|
| AC-6-01 | [IT] | `12:00`-`14:00` に 4 本・全て割当済みのとき、12:00 台と 13:00 台の 30 分セル 4 つがいずれも `assignedCount = 4` |
| AC-6-02 | [IT] | 枠 3 本のうち 1 本のみ割当済みのセルで `slotCount = 3`・`assignedCount = 1` と**区別**して返る |
| AC-6-03 | [IT] | 枠が 1 本も無い時間帯のセルが `slotCount = 0` で返り、**穴として見える** |
| AC-6-04 | [IT] | `timeline` を返しても `shift_slots` の**行数・形状が一切変わらない**（投影であって永続化ではない） |
| AC-6-05 | [IT] | `17:30`-`17:45` と `17:45`-`18:00` の 2 本（各 1 名割当）があるとき、**17:30 セルと 17:45 セルがそれぞれ `assignedCount = 1`** で返る（1 つのセルに潰れて `2` にならず、片方が落ちもしない）【15分境界】 |

### AC-7群 コピー作成（9件・PR C2）

| ID | 種別 | 受け入れ条件 |
|---|---|---|
| AC-7-01 | [IT] | 1 枠を 4 日へ複製すると 4 行が作成され、`includeAssignments = true` で割当者も複製される |
| AC-7-02 | [IT] | `includeAssignments = false` で**枠のみ**作成され、`assigned_user_ids` が null |
| AC-7-03 | [IT] | `onConflict = "ABORT"` で `ASSIGNMENT_OVERLAP` が起きると **409** となり、**1 件も作成されない** |
| AC-7-04 | [IT] | `onConflict = "SKIP"` で `ASSIGNMENT_OVERLAP` が起きると、**枠は作られ割当が空**になり、`skipped[]` に理由付きで列挙される |
| AC-7-05 | [IT] | schedule 期間外の `targetDate` は `SKIP` 指定でも**作成されない**（`rejected[]` に `OUT_OF_PERIOD`） |
| AC-7-06 | [IT] | 同一時間帯・同一ポジションの枠が既存でも**作成される**が、結果の `warnings` に `DUPLICATE_SLOT` が列挙される |
| AC-7-07 | [UT] | `targetDates` が空配列だと 400 |
| AC-7-08 | [IT] | **`createdSlots` ＋ `rejectedSlots` の合計**が `sourceSlotIds.size() × targetDates.size()` と**厳密に一致**する（§11.4.4.1 の軸1。`skippedAssignments` / `warnings` は加算されない） |
| AC-7-09 | [IT] | `ASSIGNMENT_OVERLAP` を `SKIP` した組は `createdSlots` に **1 回だけ**現れ、同時に `skippedAssignments` にも現れる（**二重計上されない**） |

### AC-8群 希望（8件・PR A3/C3）

| ID | 種別 | 受け入れ条件 |
|---|---|---|
| AC-8-01 | [IT] | 同一日・**異なる `slotId`** への希望が 2 件とも成功する（CMP-260909-1143 の是正） |
| AC-8-02 | [IT] | **同一 `slotId`** への 2 件目の希望が **409** になる |
| AC-8-03 | [IT] | `slotId = null`（日単位）の希望は同一日に **1 件まで**で、2 件目が 409 になる |
| AC-8-04 | [IT] | 同一日に枠 A（`PREFERRED`）・枠 B（`STRONG_REST`）の希望があるとき、`authoring-view` の `candidates[].slotPreferences[]` が **2 要素**になり、各 `slotId` に対応する `preference` が正しく返る（日付キーで潰れない） |
| AC-8-05 | [IT] | 希望提出で**他 schedule 配下の `slotId`** を指定すると **403** になり、行が増えない（§11.5.1.1-1） |
| AC-8-06 | [IT] | 希望提出で `slotId` と `slotDate` が食い違うと **400** になる（§11.5.1.1-2）。存在しない `slotId` は **403**（§11.5.1.1-3） |
| AC-8-07 | [IT] | 同一 `(scheduleId, userId, slotId)` への希望を**同時に 2 リクエスト**投げると、**必ず一方だけが成功し他方が 409** になる（DB の UNIQUE 制約由来。§11.5.1.2。制約違反を握りつぶして 500 にしない） |
| AC-8-08 | [IT] | 生成列 ＋ UNIQUE を追加するマイグレーション適用後、`slot_id IS NULL` の日単位希望が**同一日に 2 件目を作れない**（従来規則が保たれる）／既存の重複行は掃除され、掃除件数がログに残る |

### AC-9群・AC-10群

→ [`07_authoring_cost.md`](07_authoring_cost.md) §12.9 を参照。

### AC-11群 自動割当の停止 ＋ 非回帰（11件・PR B1/B2）

| ID | 種別 | 受け入れ条件 |
|---|---|---|
| AC-11-01 | [IT] | `FEATURE_SHIFT_AUTO_ASSIGN_ENABLED = FALSE` のとき、`ShiftAutoAssignController` の **6 経路すべて**（実行 / 割当確定 / 破棄 / 履歴一覧 / 履歴詳細 / **目視確認**）が `FEATURE_GATE_001` で拒否される |
| AC-11-02 | [IT] | 上記の拒否時に `shift_assignment_runs` の**行が 1 件も増えない** |
| AC-11-03 | [IT] | フラグ ON で既存の自動割当テストが**全 green**（テストを削除していないことの担保） |
| AC-11-04 | [E2E] | `board.vue` の自動割当ボタンが**「停止中」表示で存在**し、DOM から消えていない（disabled ＋ 理由のツールチップ） |
| AC-11-05 | [IT] | 非回帰: `shift_slots.assigned_user_ids` の **JSON 形状が不変** |
| AC-11-06 | [IT] | 非回帰: マイシフト API のレスポンス形状が不変 |
| AC-11-07 | [IT] | 非回帰: シフト表 PDF が **team / personal の両レイアウト**で従前どおり生成される |
| AC-11-08 | [IT] | 非回帰: シフト交代依頼（3 パターン）が従前どおり動作する |
| AC-11-09 | [IT] | 非回帰: `ShiftPublishedEvent` 発火後の**消化記録件数が従前と一致**する |
| AC-11-10 | [IT] | 非回帰: シフト-TODO 連携（F08.7）が従前どおり動作する |
| AC-11-11 | [IT] | **既知の制約の固定**: フラグ OFF のとき、未確認 `SUCCEEDED` run を持つシフト表の `PUBLISHED` 遷移が `assertNoUnreviewedRuns` により **`VISUAL_REVIEW_REQUIRED` で拒否される**。**これは欠陥ではなく仕様である**旨を、テストメソッド名（例: `フラグOFF時_未確認runを持つシフト表は公開できない_既知の制約`）とコメントに明示し、§11.1.3 の申し送りを参照させる |

### AC-12群 失敗の可視化と情報漏洩の防止（6件・全 PR 横断）

既存の CMP-260909-0543 / 0544 と同型の欠陥を作らないための群。

| ID | 種別 | 受け入れ条件 |
|---|---|---|
| AC-12-01 | [E2E] | `authoring-view` の取得が失敗したとき、**空状態を表示してはならない**。エラー状態（再試行ボタン付き）と空状態が**別コンポーネント / 別 `data-testid`** で描き分けられている |
| AC-12-02 | [E2E] | `labor-cost-targets` 一覧と `my/work-summary` でも同様に、**取得失敗が「目安未設定」「データ無し」に化けない**（未設定・データ無し・取得失敗の **3 状態を区別**して表示する） |
| AC-12-03 | [UT] | 状態管理が `loading` / `error` / `empty` / `loaded` の **4 状態**を持ち、**`error` から `empty` へフォールバックする分岐が存在しない** |
| AC-12-04 | [IT] | 新設 API（`authoring-view` / `replicate` / `labor-cost-targets` / `work-summary`）のエラー応答**全文**に、SQL 文字列・Java 例外クラス名（`org.hibernate.*` / `java.lang.*` / `*Exception`）・スタックトレース・テーブル名・列名が**一切含まれない**ことを正規表現でアサートする |
| AC-12-05 | [IT] | 楽観ロック競合 409・認可失敗 403・フラグ拒否のいずれの応答も、**内部識別子を漏らさない** |
| AC-12-06 | [E2E] | 人件費・時給の画面で API が失敗したとき、金額欄に **0 円を表示しない**（`—` または「取得できませんでした」と表示する） |

### AC-13群 性能（2件・PR C3）【Codex 検分 P2-2】

`authoring-view` は 1 リクエストで「全枠 ＋ 枠別希望 ＋ 曜日既定 ＋ 期間集計 ＋ 月集計 ＋ 有効時給」を返す。**候補数・期間長に対する計算量の受け入れ条件が無いと、`findEffectiveRate` の N+1 や候補ごとの月次集計クエリがあっても機能 AC を全て通過してしまう。**

**代表最大件数**（本 AC の基準）: メンバー 100 名 / シフト表期間 31 日 / 枠 1,000 本。

| ID | 種別 | 受け入れ条件 |
|---|---|---|
| AC-13-01 | [IT] | 代表最大件数の `authoring-view` 1 回で発行される **SQL クエリ数が候補数・枠数に比例せず、定数本（20 本以下）**であること（`hibernate.generate_statistics` またはクエリカウンタでアサート）。**特に `findEffectiveRate` を候補ごとに呼ぶ N+1 が無いこと**（有効時給は 1 クエリで一括取得する） |
| AC-13-02 | [IT] | 候補を 10 名 → 100 名へ 10 倍にしても、発行クエリ数が**増えない**（応答時間ではなくクエリ数で判定し、CI のマシン差で flaky にしない） |

---

## 11.10 設計原則との整合

- `docs/architecture/domain_db_design_principles.md`: 新規テーブルはドメイン内に閉じ、クロスドメイン FK を張らない（`shift_labor_cost_targets` の `team_id` は ID 参照 ＋ インデックスのみ。→ [`07_authoring_cost.md`](07_authoring_cost.md)）。「正本を 1 つに保つ」原則に従い、希望への時刻カラム追加を退けた（§11.5.3）。
- `docs/security/README.md`: 新設エンドポイントはすべて**入口（public エントリ）で認可**し、スコープは**サーバ側で `scheduleId` → `teamId` を解決**する。エラー応答から内部情報を漏らさない（AC-12群）。
