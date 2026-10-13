> **⚠️ 参考保存（破棄された旧案）**: 2026-09-09 の方針転換（README v2.5）で、本案の「シフト希望への時刻カラム追加」は破棄され、`../06_manual_authoring.md` に差し替えられた（§11.0.2 #5・§11.5.3）。本ファイルは CMP-261006-0421 で孤児コミットから回収し、CMP-260909-1142 の検討材料として残すものであり、正本ではない。実装の根拠にしないこと。

# F03.5 シフト管理 — §11 シフト希望の「入れる時間帯」（CMP-260909-1142）

> **ステータス**: 🟡 設計案（マスター裁可済みの方針を反映）・**Codex 検分 P1×6 / P2×5 を反映済（v1.1）。方針変更 2 件はマスター再裁可が必要**・実装未着手
> **初版**: 2026-09-09（軍議） / **v1.1**: 2026-09-09 Codex 検分 11 件を反映
> **正本課題**: CMP-260909-1142「シフト希望に『入れる時間帯』を持たせる（マスター必須機能）」
> **前提課題**: CMP-260909-1143「同じ日に枠が2つあると2件目のシフト希望が出せない」（**本設計の射程に含める**。§2.6 参照）
> **関連**: 本ディレクトリ `01_db_design.md` §3（`shift_requests` / `shift_slots` / `member_availability_defaults`）、`02_api_design.md` §4、`03_business_logic.md` §5（自動割当アルゴリズム）、`04_security_operations.md` §6（認可・IDOR）、`05_unpublished_visibility.md` §10

---

## 0. この文書が扱う問題

メンバーが「その日はこの時間帯なら入れる」を申請できない。

**実測（2026-09-09、`origin/main` = `05f21e7a1b`）**:

| 観測点 | 実測結果 | 出典 |
|---|---|---|
| `shift_requests` の時刻カラム | **存在しない**。`schedule_id / user_id / slot_id / slot_date / preference / note / submitted_at / updated_at` のみ | `backend/src/main/resources/db/migration/V3.073__create_shift_requests_table.sql` |
| 時間帯の保持場所 | 枠側 `shift_slots.start_time` / `end_time`（ともに `TIME NOT NULL`）の固定属性 | `V3.072__create_shift_slots_table.sql` |
| 希望の値 | 5 段階（`PREFERRED` / `AVAILABLE` / `WEAK_REST` / `STRONG_REST` / `ABSOLUTE_REST`） | `backend/src/main/java/com/mannschaft/app/shift/ShiftPreference.java` |
| メンバーの提出 UI | 枠ごとに「5 段階ラジオ」＋「note」の 2 項目のみ。時刻は表示専用 | `frontend/app/pages/my/shift-request.vue:375-424` |
| 管理者の希望一覧 UI | 行=ユーザー・列=日付のマトリクス。1 セルは `ShiftPreferenceIcon` のアイコン 1 個のみ（note すら出ない） | `frontend/app/pages/shift/[id]/requests.vue:226-267` |

したがって現状は「入れる／入れない」の強弱しか申請できず、**時間の情報は `note` のフリーテキストに書くしかない**。`note` は自動割当が一切読まないため、管理者が目視で拾わない限り事実上失われる。

---

## 1. 目的

1. メンバーが希望提出時に「入れる時間帯」（開始・終了）を任意で添えられるようにする。
2. その時間帯が枠を**完全に被覆しない**申請を、自動割当の候補から機械的に外す。
3. **外した事実と申請内容を、管理者に必ず届ける**（握りつぶさない）。
4. 「時間帯を毎回入れ直す」手間を、既存の曜日別既定希望（`member_availability_defaults`）からのコピーで解消する。

---

## 2. 方針（マスター裁可済み 2026-09-09・変更禁止）

### 2.1 方針①: 部分被覆の申請は自動割当の候補から外す

枠 18:00-22:00 に対し「20:00 から可」（20:00-22:00）の申請は、**自動割当の候補に入れない**。

### 2.2 方針②: ただし申請は握りつぶさず、管理者に必ず可視化する

候補から外れた申請も `shift_requests` に保存され、以下の 2 経路で管理者に届く。

- **希望一覧（マトリクス）**: セルに時間帯バッジを出す（§6.1）
- **自動割当の申し送り警告**: 新コード `PARTIAL_TIME_COVERAGE` を `warnings` に積む（§5.3）

### 2.3 方針③: 部分時間での自動割当は採らない

枠 18:00-22:00 を 20:00-22:00 だけ埋める割当は**実装しない**。理由（裁可済み）:

1. **18:00-20:00 の穴が誰にも気づかれず残る。** 現行の未充足警告は必要人数単位（実装は `UNASSIGNED_SLOT`、`GreedyShiftAssignmentStrategy:150-158`）であり、「人数は足りたが時間に穴がある」を表現できない。部分割当を許すと、警告が出ないまま穴が開く。
2. **`shift_slots.assigned_user_ids` を割当の正本と確定した直後（CMP-260908-2117）に、同じ列の意味を変えることになる。** マイシフト表示（`ShiftMyService:106-107`）・PDF・予算集計への波及リスクが便益に釣り合わない。
3. **部分時間が要るなら、管理者が枠を 18-20 と 20-22 に分割する運用で実現できる。**

> ⚠️ **v1.1 での重要な訂正（Codex 検分 P1-1）**: 上記 3 の「**既存機能のまま今日実現できる**」は**誤りだった**。§2.3.1 で是正する。**この節は方針そのものの変更を含むため、マスターの再裁可を要する。**

### 2.3.1 方針③の前提の是正: 枠の分割は現状「希望を黙って消す」【v1.1 新規・要再裁可】

**実測（自分で裏を取った）**:

| 観測点 | 実測結果 | 出典 |
|---|---|---|
| 枠の削除 | **物理削除**。`slotRepository.delete(entity)` を無条件に呼ぶ（希望の存在確認なし） | `ShiftSlotService#deleteSlot`（244-249 行） |
| 希望 → 枠の FK | `CONSTRAINT fk_sr_slot FOREIGN KEY (slot_id) REFERENCES shift_slots (id) **ON DELETE CASCADE**` | `V3.073__create_shift_requests_table.sql` |
| その FK は後に外されたか | **外されていない**。`V113.001__phase4d_drop_physdel_setnull_fk.sql:72` が「`shift_requests` → `shift_slots`（`fk_sr_slot` CASCADE・**同一ドメイン**）」と明記して**存置**を宣言している（外されたのは `fk_sr_user` / `fk_shift_requests_proxy` のみ） |

したがって現状、**枠 18:00-22:00 を削除して 18-20 / 20-22 の 2 枠を作り直すと、その枠に紐づく希望が CASCADE で全件物理削除される**。

**すなわち、部分時間の希望を救うために推奨した操作が、まさにその希望を黙って消す。** 方針③の第 3 の論拠は、この是正を伴わない限り成立しない。

#### 採る対策と、その理由

**2 つを組み合わせる。**

**(A) 希望がぶら下がった枠の裸の削除を、既定で拒否する。**

`DELETE /api/v1/shifts/slots/{slotId}` は、当該枠を参照する `shift_requests` が 1 件以上ある場合 **409 `SLOT_HAS_REQUESTS`（`SHIFT_039`）** で拒否する。管理者が中身を承知のうえで捨てたい場合のみ `?force=true` を明示する（そのときは件数を監査ログへ残す）。

**(B) 「枠の分割」を専用操作として新設し、希望を移送する。**

```
POST /api/v1/shifts/slots/{slotId}/split
{ "splitAt": "20:00" }
```

- 元枠 18:00-22:00 を 18:00-20:00 と 20:00-22:00 の 2 枠に置き換える（`requiredCount` / `positionId` / `note` は両方へ複製）。
- **元枠に付いていた希望を、両方の新枠へ複製して移送する。** 移送規則:
  - 時間帯 `NULL`（＝枠を丸ごと可）の希望 → 両新枠へ `NULL` のまま複製。
  - 時間帯付きの希望 → **申請された絶対時刻をそのまま両新枠へ複製**する（20:00-22:00 の申請は分割後も 20:00-22:00 のまま）。被覆判定は自動割当が新しい枠に対して改めて行うため、前半枠では非被覆・後半枠では被覆となり、**申請者が意図したとおりの結果**になる。
  - `preference` / `note` / `isProxyInput` / `proxyInputRecordId` はそのまま引き継ぐ。
- 元枠は削除する（希望は上記で移送済みのため失われない）。
- レスポンスに新枠 2 件と移送した希望件数を返す。

#### なぜこの組み合わせを選んだか（利用者にとっての正しさ）

- **「自動移送だけ」では足りない。** 管理者は `split` を使わず `DELETE` + `POST` で分割することもできる。その経路が黙って消し続ける限り、事故は残る。だから (A) の門を必ず立てる。
- **「削除阻止だけ」でも足りない。** 拒否されるだけでは管理者は分割できず、方針③の推奨運用が成立しない。だから (B) の正しい道を用意する。
- **移送で `preference` を書き換えたり、時間帯を新枠に合わせて丸めたりはしない。** それは利用者が申請していない意思の捏造になる。**申請された絶対時刻を保存し、解釈（被覆判定）は割当時に行う**のが唯一、利用者の申請内容を歪めない設計である（同じ理由で §4.4 の既定コピーも絶対時刻を保持する。P1-4 / §2.3.2）。

### 2.3.2 方針⑥: 時間帯は常に「絶対時刻」として保存し、枠に合わせて正規化しない【v1.1 新規】

`available_start_time` / `available_end_time` は**申請された絶対時刻**であり、「枠の何割を覆うか」といった枠相対の情報へ変換して保存することはしない。`NULL` は「時間帯を申請していない」という**別の意味**であり、「たまたま枠と一致した」ことの表現には使わない。

**理由（Codex 検分 P1-4）**: 枠の時刻は `ShiftSlotService#updateSlot`（158-182 行、`applyUpdate` で `startTime` / `endTime` を書き換え可能）でいつでも変更できる。既定 18:00-22:00 が枠 18:00-22:00 と一致したからといって `NULL`（＝枠全体可）へ正規化すると、**管理者が後から枠を 17:00-23:00 へ広げた瞬間、利用者が一度も申請していない 17:00-18:00 と 22:00-23:00 に対して「入れる」と解釈される**。絶対時刻で保存しておけば、枠が広がった時点で自動的に非被覆となり候補から外れる（＝安全側に倒れる）。

### 2.4 方針④: 使い方ガイドに「枠を分割すれば部分時間に対応できる」旨を明示する（マスター指示）

§7 に記載事項を定める。

### 2.5 方針⑤: 「入れる時間帯を覚えさせる」は既存の既定希望を活かす

`member_availability_defaults` は既に `start_time` / `end_time` を `TIME NOT NULL` で持つ（`V3.075` 実測）。**DDL 追加は不要**。

実測された欠損は 2 点:

- `frontend/app/pages/my/shift-availability.vue:85-86` が全曜日を `startTime: '00:00'` / `endTime: '23:59'` で**固定送信**しており、時間帯を絞る UI が存在しない。
- 同ページの `initDefaults()`（47-56 行）はサーバから返る `startTime` / `endTime` を**読み捨てる**ため、保存するたび 00:00-23:59 に上書きされる（往復欠損）。

よって必要なのはハードコード解除・往復の修復と、既定 → 今回分へのコピー導線（§4.4）である。

### 2.6 射程判断: CMP-260909-1143 を本戦役に含める

**含める（PR1 を先行させる）。** 理由:

- 実測で欠陥を再確認した。`ShiftRequestService:105-108` の重複判定は `findByScheduleIdAndUserIdAndSlotDate(scheduleId, userId, slotDate)` と**日付のみ**で引くため、同日 2 枠でも 2 件目は `REQUEST_ALREADY_EXISTS`（`SHIFT_015`）で 409。加えて `GreedyShiftAssignmentStrategy:279-289` の `buildPreferenceMap` が `userId → (LocalDate → ShiftPreference)` と**キーを日付に潰しており**、同日 2 件があれば後勝ちで片方が消える（`getCandidateUserIds` は `slotId` を見ているのに、スコア計算側が日付で潰すという非対称がある）。
- 時間帯を導入すると、この制限は「1 日 1 時間帯しか申請できない」という**利用者に見える不合理**として顕在化する。時間帯の AC（同日 2 枠に別々の時間帯）は 1143 を直さない限り書けない。
- ただし**別 PR に切る**（§8 PR1）。DDL を伴わない純粋な是正であり、時間帯の DDL と混ぜるとレビュー単位が肥大するため。

---

## 3. DB 設計

### 3.1 `shift_requests` へのカラム追加【v2.5 新規】

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `available_start_time` | `TIME` | YES | `NULL` | **【v2.5 新規】** メンバーが申請した「入れる時間帯」の開始。`NULL` = 時間帯の指定なし |
| `available_end_time` | `TIME` | YES | `NULL` | **【v2.5 新規】** 同終了。`NULL` = 時間帯の指定なし |

**インデックス**: 追加しない（時間帯だけで検索する経路が無く、自動割当はスケジュール単位で全件をメモリへ読むため。`AssignmentContext#requests`）。

**制約・備考**:

- **両方 NULL か、両方 NOT NULL か**のいずれかに限る。片側のみは DB の CHECK 制約と Service 層バリデーションの二重で拒否する。
- **後方互換**: 既存行はすべて `NULL` / `NULL` となり、これは「**枠の時間を丸ごと入れる**」＝現行と完全に同一の意味として扱う。**データ移行スクリプトは不要**。§5.2 の被覆判定も、時刻 `NULL` の希望は無条件に被覆とみなす。
- **日跨ぎは表現しない**（§3.3）。`available_start_time < available_end_time` を制約とする。
- クロスドメイン FK は張らない（`docs/architecture/domain_db_design_principles.md` 原則 1）。今回の追加列は FK を伴わない。
- 主キーは既存の `BIGINT UNSIGNED AUTO_INCREMENT` のまま。原則 6（`UuidV7Entity`）は明文で「**新規に作成するテーブルの Entity**」に限定され「既存テーブルの BIGINT ID は変更しない」と定めているため、UuidV7 化はしない（前例: `V3.084__add_open_call_columns_to_shift_swap_requests.sql` 等）。
- 原則 8（照合順序の明示宣言）は `CREATE TABLE` が対象。本 DDL は `ALTER TABLE ... ADD COLUMN` であり、かつ追加するのは `TIME` 型で文字列型ではないため、列単位 `COLLATE` は書かない（列単位の `COLLATE` 上書きは同ドキュメントで明確に禁止されている）。
- 原則 7（`AbstractTenantAwareRepository`）は該当しない。`shift_requests` に `organization_id` は無く、テナント境界は `schedule_id → shift_schedules.team_id` 経由で解決される。越境防止は `*ScopeContractIT` で担保する（AC-1142-33）。

### 3.2 Flyway マイグレーション

**採番（実測）**: `origin/main` の `backend/src/main/resources/db/migration/` は 1135 ファイル、major の最大値は **204**。したがって新規 major は **205**。minor はタイムスタンプ（`date -u '+%Y%m%d%H%M%S'`）。

ファイル名（例。実装時にタイムスタンプを取り直すこと）:

```
V205.<yyyyMMddHHmmss>__add_time_window_to_shift_requests.sql
```

```sql
ALTER TABLE shift_requests
    ADD COLUMN available_start_time TIME NULL COMMENT '入れる時間帯の開始（NULL=指定なし＝枠を丸ごと可）' AFTER slot_date,
    ADD COLUMN available_end_time   TIME NULL COMMENT '入れる時間帯の終了（NULL=指定なし＝枠を丸ごと可）' AFTER available_start_time,
    ADD CONSTRAINT chk_sr_time_window CHECK (
        (available_start_time IS NULL AND available_end_time IS NULL)
        OR (available_start_time IS NOT NULL
            AND available_end_time IS NOT NULL
            AND available_start_time <  available_end_time
            AND available_start_time >= '00:00:00'
            AND available_end_time   <= '23:59:59')
    );

-- 【v1.1 / Codex 検分 P2-3】同一ユーザーの同一枠への希望重複を DB で一意化する。
-- slot_id は NULL 可（日付単位の希望）で、MySQL の UNIQUE は NULL 同士を重複と見なさないため、
-- NULL を 0 へ畳んだ生成列を一意キーに使う（0 は AUTO_INCREMENT 由来の slot_id と衝突しない）。
ALTER TABLE shift_requests
    ADD COLUMN slot_ref BIGINT UNSIGNED
        AS (IFNULL(slot_id, 0)) STORED
        COMMENT '一意制約用: slot_id の NULL を 0 へ畳んだ生成列（日付単位の希望を表す）',
    ADD CONSTRAINT uq_sr_schedule_user_slot
        UNIQUE (schedule_id, user_id, slot_ref, slot_date);
```

CHECK 制約方式は本リポジトリの流儀（`01_db_design.md` の `preference` が `VARCHAR + CHECK`）に揃えた。**CHECK は最終防波堤であり、利用者に見えるエラーは Service 層で 400 として返す**（§4.2）。

**時刻の範囲制限について（Codex 検分 P2-4）**: MySQL の `TIME` は `-838:59:59`〜`838:59:59` を受け付けるため、CHECK が物理性と大小関係しか見ていないと `-01:00` や `25:00` の行が成立してしまう。その行は `java.time.LocalTime` へマッピングできず、**一覧・サマリー・自動割当をまとめて 500 にする**（読んだ瞬間に落ちるため、書いた本人以外にも波及する）。API 経由では `LocalTime` バインドが先に弾くので通常は到達しないが、生 SQL・データ修復スクリプト・他システムからの流し込みが到達経路として残る。よって `00:00:00`〜`23:59:59` の範囲も CHECK に含める。

**一意制約について（Codex 検分 P2-3）**: 初版はアプリ層の事前確認だけで重複を防ぐ設計だった。しかし**同一枠への同時投入**（`my/shift-request.vue` は枠数ぶんの POST を `Promise.all` で並列発行する）と **`apply-defaults` の二重実行**は、どちらも「まだ無い」を観測してから INSERT するため、事前確認をすり抜けて重複行を作れる。PR1 で「同一枠の重複は 409」を契約にする以上、DB 側の一意キーが無ければその契約は守れない。生成列方式により、日付単位の希望（`slot_id IS NULL`）も `slot_ref = 0` として同じ一意キーに載る。重複 INSERT は `DataIntegrityViolationException` を捕捉して `REQUEST_ALREADY_EXISTS`（409）へ翻訳する。

**適用前の重複掃除**: 本 UNIQUE を張る前に、既存データに重複が無いことを確認する必要がある。現行はアプリ層が「1 ユーザー 1 日 1 希望」を強制してきたため理論上は重複しないが、`slot_id` 違いの同日 2 件が過去に入っている可能性は排除できない。マイグレーションは **(1) 重複検出クエリ → (2) 重複があれば `submitted_at` が新しい行を残して削除 → (3) UNIQUE 追加** の順で書き、削除件数をログへ出す。

### 3.3 日跨ぎ枠の実測と、日跨ぎを扱わない判断

**実測結果**:

| 観測点 | 実測結果 |
|---|---|
| `shift_slots.start_time` / `end_time` の型 | `TIME NOT NULL`（`V3.072`）。MySQL の `TIME` は `-838:59:59`〜`838:59:59` を格納できるが、**24 時間超の値を使っている箇所は無い** |
| Entity のマッピング | `ShiftSlotEntity#startTime` / `endTime` はともに `java.time.LocalTime`。LocalTime は 00:00-23:59:59.999999999 しか表現できず、**`24:00` 以上は構造的に載らない** |
| 日跨ぎフラグ | `shift_slots` に `ends_next_day` 相当の列は**存在しない** |
| 枠の時刻バリデーション | **存在しない**。`ShiftSlotService` は `req.getStartTime()` / `getEndTime()` をそのまま builder に流すだけで（104-105 行・131-132 行）、`start < end` すら検証していない |
| 枠の時刻を使った時間演算 | shift ドメインには**無い**。`ShiftMyService:106-107` は表示のため詰め替えるだけ、`ShiftToTaskService:56` は文字列連結、`ShiftToTaskService:62` は `startTime` を `dueTime` へ流すだけ。**Duration 計算・大小比較は 1 箇所も無い** |
| 他ドメインの前例 | 予約ドメインは `SlotTimeValidator#validateTimeRange(start, end, endsNextDay)` を持ち、`endsNextDay=true` のとき `end < start` を要求する明示フラグ方式（`backend/src/main/java/com/mannschaft/app/reservation/service/SlotTimeValidator.java`） |

**結論**: シフト枠は**日跨ぎを定義された意味で表現できない**。22:00-02:00 のような行は DB に入りうる（バリデーションが無いため）が、それが「翌日 2 時まで」なのか単なる入力ミスなのかを判別する情報がスキーマ上どこにも無い。

**判断**:

1. **希望の時間帯は日跨ぎを許さない。** `available_start_time < available_end_time` を必須とし、逆転・同値は 400（§4.2）。日跨ぎ対応は `shift_slots` 側に `ends_next_day` を入れる別戦役の射程とし、本設計では扱わない。
2. **枠側が `end_time <= start_time` の行（日跨ぎ疑い／不正データ）は、自動割当の対象から丸ごと外す（fail-closed）。**

   > **v1.1 での方針変更（Codex 検分 P1-2）・要マスター再裁可**
   >
   > 初版はここを「時間帯フィルタを適用せず素通しする（fail-open）」としていた。**これは誤りである。** 素通しにすると、枠 22:00-02:00（判定不能）に対して **09:00-12:00 しか入れないと申請した利用者が実際に割り当てられる**。警告を積んでも誤割当そのものは起きてしまい、「利用者が明示した時間を破らない」という本機能の存在意義が壊れる。
   >
   > 改めた方針: 当該枠は**誰も自動割当しない**（時刻付きの希望も、時刻 `NULL` の希望も候補にしない）。枠の時間が意味不明である以上、「枠を丸ごと入れる」の「丸ごと」自体が定義できないためである。警告 `SLOT_TIME_RANGE_INVALID` を積んで管理者に枠の修正を促す。管理者が枠を直せば次回の実行から通常どおり割り当たる。手動割当（D&D）は従来どおり可能なので、管理者の逃げ道は塞がない。
3. `shift_slots` に `start < end` のバリデーションが無いこと自体は本件の射程外の既存欠陥である。**別課題として台帳に起票する**（§9-1）。

---

## 4. API 契約

### 4.1 変更するエンドポイント

| メソッド | パス | 変更内容 |
|---|---|---|
| `POST` | `/api/v1/shifts/requests` | リクエストに `availableStartTime` / `availableEndTime` を追加（任意） |
| `PATCH` | `/api/v1/shifts/requests/{requestId}` | 同上。**明示的なクリアを表現できること**（§4.3） |
| `GET` | `/api/v1/shifts/requests?scheduleId=` | レスポンスに 2 項目を追加 |
| `GET` | `/api/v1/shifts/my/requests` | 同上 |
| `POST` | `/api/v1/shifts/requests/apply-defaults` | **新規**（§4.4） |

### 4.2 リクエスト / バリデーション

`CreateShiftRequestRequest` / `UpdateShiftRequestRequest` に追加:

```json
{
  "scheduleId": 12,
  "slotId": 101,
  "slotDate": "2026-10-03",
  "preference": "AVAILABLE",
  "availableStartTime": "20:00",
  "availableEndTime": "22:00",
  "note": "20時までは学校です"
}
```

- `availableStartTime` / `availableEndTime`: 任意。`HH:mm`（`LocalTime`）。
- **両方指定か、両方省略（`null`）か**のいずれか。片側のみは 400。
- `availableStartTime < availableEndTime` 必須。同値・逆転は 400。日跨ぎ（`end < start`）も同じ経路で 400。
- **枠の時間範囲との整合は 400 にしない。** 枠外・部分被覆・無交差のいずれも保存し、可視化する（方針②）。理由は 2 つ:
  - `slotId` が `null`（日付単位の希望）の場合、照合すべき枠が一意に定まらない。
  - 「枠外の時間帯を申請した」こと自体が管理者にとって意味のある情報（枠の切り方が実態に合っていない、という申し送り）であり、400 で握りつぶすと方針②に反する。
- 新しいエラーコード（`ShiftErrorCode`。実測で `SHIFT_036` まで使用済みのため次番）:

| 定数 | コード | メッセージ | HTTP |
|---|---|---|---|
| `INVALID_REQUEST_TIME_WINDOW` | `SHIFT_037` | 入れる時間帯は開始・終了の両方を指定し、開始が終了より前である必要があります | 400 |
| `SLOT_DATE_MISMATCH` | `SHIFT_038` | 指定されたシフト枠の日付と希望の日付が一致しません | 400 |
| `SLOT_HAS_REQUESTS` | `SHIFT_039` | このシフト枠にはメンバーの希望が提出されています。削除すると希望も失われます | 409 |

### 4.3 時間帯のクリア（PATCH のセマンティクス）【v1.1 改訂・Codex 検分 P1-5】

> **初版の誤り**: 初版は「省略＝クリア」（既存の全項目上書きセマンティクスの踏襲）としていた。**これは後方互換を壊す。** 新項目を知らない既存クライアント（`ShiftRequestForm.vue` 経由の旧画面や、外部の API 利用者）は従来どおり `preference` と `note` だけを PATCH する。省略＝クリアだと、**利用者が別途登録した時間帯が、ごく普通の希望更新だけで黙って消える**。「レスポンス項目の追加は非破壊」という §4.5 の記述とも矛盾していた。

**改めた契約**: 時間帯については**省略＝据え置き**とし、クリアは**明示操作**で表す。

`UpdateShiftRequestRequest` に 3 項目を持たせる:

| フィールド | 型 | 省略時 | 意味 |
|---|---|---|---|
| `availableStartTime` | `LocalTime` / `null` | 据え置き | 指定時は開始時刻を更新 |
| `availableEndTime` | `LocalTime` / `null` | 据え置き | 指定時は終了時刻を更新 |
| `clearAvailableTimeWindow` | `Boolean` | `false` | `true` のとき時間帯を `NULL` / `NULL` へクリアする |

- **旧クライアント**（3 項目とも送らない）→ 時間帯は**変化しない**。後方互換が守られる。
- 時間帯を変更する → `availableStartTime` と `availableEndTime` を**両方**指定する（片側のみは 400 `SHIFT_037`。据え置きと部分指定を区別できないため）。
- 時間帯を消す → `clearAvailableTimeWindow: true` を送る。このとき `availableStartTime` / `availableEndTime` を同時に指定するのは矛盾なので 400 `SHIFT_037`。

**`JsonNullable` を使わない理由**: 「キーが無い」と「キーがあって `null`」を Jackson で区別するには `jackson-databind-nullable` の導入か `Optional` ラッパーの持ち込みが要る。本リポジトリに前例が無く、1 項目のために依存とマッピング規約を増やす価値が無い。明示フラグ方式は追加依存ゼロで、OpenAPI 上も意味が読み取れる。

`preference` / `note` は**既存の全項目上書きセマンティクスのまま変更しない**（本件で触らない）。

### 4.3.1 `slotId` の実体整合検証【v1.1 新規・Codex 検分 P1-6】

**実測（自分で裏を取った）**:

- `V3.073` の FK は `fk_sr_schedule`（→ `shift_schedules`）と `fk_sr_slot`（→ `shift_slots`）が**独立に**張られているだけで、「その枠がそのスケジュールに属する」ことを表す制約は**無い**。
- `ShiftRequestService#submitRequest`（95-125 行）は `req.getSlotId()` を**一度も引かず**、そのまま Entity へ詰めている。枠の実在すら確認していない。
- `slotDate` も同様にリクエスト値をそのまま保存しており、枠の `slot_date` との一致は検証されない。

**結果**: チーム A の正当なメンバーが、**チーム B の `slotId`** をチーム A の `scheduleId` と組み合わせて送ると、その組み合わせのまま保存できる。これは 2 つの問題を同時に起こす。

1. **認可の問題**: 他テナントの枠 ID を自分の希望行に埋め込める（ID の存在・非存在をオラクルとして観測できる余地も生じる）。
2. **静かなデータ消失**: PR1 で自動割当を枠単位の検索に変えると、その希望に対応する枠が当該スケジュール内に存在しないため、**希望が候補にも警告にも現れず黙って消える**。方針②（必ず可視化する）が破れる。

**対策**: `submitRequest` / `updateRequest` は、`slotId` が非 `null` のとき保存前に必ず枠を実体解決し、以下を検証する。

| 検証 | 不成立時 |
|---|---|
| 枠が存在する | 404 `SHIFT_SLOT_NOT_FOUND`（`SHIFT_002`） |
| `slot.scheduleId == req.scheduleId` | 404 `SHIFT_SLOT_NOT_FOUND`（**403 ではなく 404**。他テナントの枠 ID の存在を推測させないため、不在と越境を同一応答へ畳む） |
| `slot.slotDate == req.slotDate` | 400 `SLOT_DATE_MISMATCH`（`SHIFT_038`） |

`scheduleId` 側のチーム認可は既存の `checkTeamMemberAccess` が担うため、上記により **scope はすべて実体由来**（スケジュール → チーム、枠 → スケジュール）で解決される。パス・ボディの値を信用する経路は残らない。

なお `slotDate` はそもそも枠から導出できる冗長な入力だが、`slotId` が `null` の日付単位希望では必須であるため、フィールド自体は残し**一致検証で守る**（既存 API 契約を壊さない選択）。

### 4.4 新規エンドポイント: 既定希望からのコピー

```
POST /api/v1/shifts/requests/apply-defaults
```

「曜日ごとの既定希望（`member_availability_defaults`）を、指定スケジュールの各枠へ写す」導線。

**リクエスト**:

```json
{ "scheduleId": 12 }
```

**挙動**:

1. `scheduleId` からスケジュールを解決し、その `teamId` を得る（パス／ボディの scope 値は信用しない）。
2. 呼び出し元が当該チームのメンバーであること、かつ SUPPORTER でないことを検証（既存 `checkTeamMemberAccess` と同一の判定）。**対象ユーザーは常に `SecurityUtils.getCurrentUserId()` 自身に固定**し、リクエストで他者を指定させない（自己スコープ）。
3. スケジュールが `COLLECTING` であり、提出期限を過ぎていないことを検証（既存 `validateCollectingStatus` / `validateRequestDeadline` を再利用）。
4. 当該スケジュールの全枠を取得し、各枠の `slot_date` の曜日に一致する既定希望を引く。
5. **既に希望が存在する枠はスキップする**（上書きしない）。
6. **既定の `start_time` / `end_time` をそのまま `available_start_time` / `available_end_time` へ写す。枠と一致しても `NULL` へ正規化しない**（§2.3.2 / Codex 検分 P1-4）。
7. 作成した希望の一覧と、スキップした枠の一覧（理由付き）を返す（201 Created）。

#### 4.4.1 不正・日跨ぎの既定希望の扱い【v1.1 新規・Codex 検分 P2-1】

**実測**: `member_availability_defaults` の DDL（`V3.075`）に `start_time < end_time` の制約は**無く**、`AvailabilityDefaultRequest` にも `@NotNull` しか付いていない（`dayOfWeek` / `startTime` / `endTime` / `preference`）。すなわち **`22:00`–`02:00`（日跨ぎ）や `10:00`–`10:00`（同値）の既定行が正当に保存されうる**。

これを §3.1 の CHECK 制約が効く `shift_requests` へそのまま写すと、**1 行の不正既定によって `apply-defaults` 全体が DB エラー（500）で落ちる**。

**契約**:

- **不正な既定行（`start >= end`、または `00:00:00`〜`23:59:59` の範囲外）はスキップし、業務エラーにはしない。** 1 行の不整合で操作全体を失敗させると、正常な既定まで反映されず利用者が回復できないため。
- スキップした行はレスポンスの `skipped` に `reason: "INVALID_DEFAULT_TIME_RANGE"` として**必ず返し**、FE は「この曜日の既定は時間帯が不正なため反映できませんでした。既定を修正してください」と表示する（握りつぶさない）。
- **原子性**: 本操作は単一の `@Transactional` 内で実行する。途中で予期せぬ例外が起きた場合、**作成済みの希望も含めて全件ロールバックされる**（「途中まで作られた中途半端な状態」を残さない）。スキップは例外ではないためロールバックの対象外。
- 既定側の `start < end` バリデーション欠落そのものの是正は本件の射程外（§9-8 に起票）。

#### 4.4.2 同一曜日に既定が複数ある場合の決定規則【v1.1 新規・Codex 検分 P2-2】

**実測**: `member_availability_defaults` の UNIQUE は `uq_mad_user_team_dow_time (user_id, team_id, day_of_week, start_time, end_time)` であり、**同じ曜日に時間帯違いの複数行が合法**である（例: 月曜の 09:00-12:00 と 18:00-22:00）。初版は「曜日に一致する既定希望」を単数として扱っており、どれを写すか未定義だった（取得順で結果が変わる／重複希望が作られる）。

**決定規則**（1 枠につき必ず 0 件または 1 件の希望を作る）:

1. 当該曜日の既定行を `start_time` 昇順 → `end_time` 昇順で安定ソートする（`findByUserIdAndTeamIdOrderByDayOfWeekAscStartTimeAsc` が既にこの順で返す）。
2. §4.4.1 の不正行を除外する。
3. **その枠を完全に被覆する行**（`default.start <= slot.start && slot.end <= default.end`）のうち**最初の 1 行**を採る。
4. 被覆する行が無ければ、**枠と重なりが最大の行**を採る。重なりが同点なら開始時刻が早い行（ソート順で先の行）を採る。
5. 枠と全く重ならない行しか無ければ、**その枠には希望を作らない**（スキップし、`reason: "NO_MATCHING_DEFAULT"` を返す）。利用者が「入れない」と言っている時間帯しか登録していないのに希望を捏造しないため。

規則 3・4 は決定論的であり、**同じ入力に対して常に同じ結果**を返す（取得順に依存しない）。

**認可マーカー**: 自己スコープだが**チーム所属の検証を Service 内で行う**ため、`@SelfScopedEndpoint` ではなく既存 `submitRequest` と同じ Service 内認可とする。`AuthzControllerGuardArchTest` の委譲探索が深さ 2 までである制約に合わせ、認可ヘルパーから `accessControlService` を 1 ホップで直接呼ぶ既存の書き方を踏襲する。

**参考（実測・本件の射程外）**: 既存の `ShiftAvailabilityController` の `/shifts/availability` 3 本は `@SelfScopedEndpoint` で自己スコープを主張しているが、`ShiftAvailabilityService` には**チーム所属の検証が一切ない**（`teamId` は絞り込みにしか使われない）。すなわち非メンバーが任意の `teamId` で自分の既定希望行を作れる。データは自分のものしか読めないため情報漏洩ではないが、`apply-defaults` を同じ作りにすると「非メンバーが希望を作れる」になるため、本 EP では必ずチーム所属を検証する。

### 4.5 レスポンス

`ShiftRequestResponse` に 2 項目を追加。

```json
{
  "id": 501,
  "scheduleId": 12,
  "userId": 34,
  "slotId": 101,
  "slotDate": "2026-10-03",
  "preference": "AVAILABLE",
  "availableStartTime": "20:00",
  "availableEndTime": "22:00",
  "note": "20時までは学校です",
  "submittedAt": "2026-09-20T10:00:00"
}
```

時間帯なしの行は両方 `null`。**既存クライアントは追加項目を無視すれば従来どおり動く**（破壊的変更なし）。

`docs/openapi.json` を再生成し、`cd frontend && npm run generate:types` で生成型を更新すること。

---

## 5. 自動割当の変更点

対象: `backend/src/main/java/com/mannschaft/app/shift/assignment/GreedyShiftAssignmentStrategy.java`

### 5.1 前提（PR1 / CMP-260909-1143）

`buildPreferenceMap` のキーを `LocalDate` から**枠の同一性**へ変える。同日 2 枠が後勝ちで消える現状を是正しない限り、時間帯の判定は成立しない。

- 枠指定の希望（`slotId != null`）: `userId → (slotId → request)` で引けるようにする。
- 日付指定の希望（`slotId == null`）: `userId → (slotDate → request)` のフォールバックとして残す。
- 同一枠に対して枠指定と日付指定の両方がある場合は、**枠指定を優先**する（より具体的な申請が勝つ）。
- あわせて、スコア計算が `ShiftPreference` だけでなく**希望エンティティそのもの**（時間帯を含む）を引けるようにする。時間帯判定に必要なため。

`getCandidateUserIds` は既に `slotId` 一致／日付一致（`slotId == null`）を両方拾っており（281-289 行）、この方針と整合している。潰しているのはスコア側だけである。

### 5.2 被覆判定（本件の中核）

候補の絞り込みに「**希望の時間帯が枠を完全に被覆するか**」を加える。

**まず枠そのものを検査する（fail-closed。§3.3-2 / Codex 検分 P1-2）**:

```
if (slot.endTime <= slot.startTime) {
    // 枠の時間が判定不能（日跨ぎ疑い・不正データ）
    // → この枠は誰にも自動割当しない。警告 SLOT_TIME_RANGE_INVALID を積んで次の枠へ。
    continue;
}
```

**枠が正常な場合の被覆判定**:

```
covers(request, slot) :=
    request.availableStartTime == null                    // 時刻なし = 従来どおり
 || (request.availableStartTime <= slot.startTime
     && slot.endTime <= request.availableEndTime)
```

- 完全一致（20:00-22:00 の希望 × 20:00-22:00 の枠）→ **被覆する**（`<=` を使うため）。
- 包含（17:00-23:00 の希望 × 18:00-22:00 の枠）→ **被覆する**。
- 部分被覆（20:00-22:00 の希望 × 18:00-22:00 の枠）→ **被覆しない → 候補から除外**。
- 無交差（09:00-12:00 の希望 × 18:00-22:00 の枠）→ **被覆しない → 候補から除外**。
- 時刻 `NULL` → **被覆する**（後方互換）。

**評価順序**: `ABSOLUTE_REST` の除外を被覆判定より**先**に行う。理由は §5.3 の警告を「絶対休みなのに部分被覆の申し送りが出る」というノイズにしないため。既存実装の `pref == null || !pref.isAssignable()` の判定直後に被覆判定を挿入する。

### 5.3 申し送り警告（方針②の担保）

`AssignmentWarningDto`（`code` / `message` / `slotId` / `userId`）に新コードを積む。**DTO の形は変えない**。

| コード | 発火条件 | `slotId` | `userId` | メッセージ例 |
|---|---|---|---|---|
| `PARTIAL_TIME_COVERAGE` | 割当可能な希望（`isAssignable()` が真）が、時間帯の非被覆を理由に候補から外れた | 対象枠 | 対象ユーザー | `ユーザー {userId} は枠 {slotId}（18:00-22:00）に対し 20:00-22:00 のみ可のため候補から除外しました` |
| `SLOT_TIME_RANGE_INVALID` | 枠の `endTime <= startTime`（日跨ぎ疑い・不正データ）のため、**当該枠を自動割当の対象から外した** | 対象枠 | `null` | `枠 {slotId} の時間 {start}-{end} は開始が終了以降となっており判定できないため、自動割当の対象から除外しました。枠の時間を修正してください` |

- **`PARTIAL_TIME_COVERAGE` は枠が充足したかどうかに関わらず必ず積む。** 「人は足りたが、時間を絞って申請した人がいた」という事実こそ管理者が知るべき情報であるため。
- 既存の `UNASSIGNED_SLOT`（未充足）とは独立。部分被覆で人が足りなくなった枠には**両方**が積まれる。
- **設計書と実装の既知の乖離**: `03_business_logic.md` と `02_api_design.md` は未充足の警告種別を `VACANCY` と記しているが、実装は `UNASSIGNED_SLOT` を積んでいる（`GreedyShiftAssignmentStrategy:150`）。本設計は**実装側の表記に揃える**（新コードも `UPPER_SNAKE` の同形式）。この乖離の是正自体は別課題（§9-2）。

### 5.4 スコアリングへの影響

**変更しない。** 被覆した希望は従来どおり `preference` ベースのスコアで並ぶ。「ちょうど被覆する人より、余裕をもって被覆する人を優先する」といった重み付けは、方針③（部分時間割当を採らない）と同様に今回は導入しない（複雑さに便益が釣り合わない）。

---

## 6. FE の変更点

### 6.1 管理者: 希望一覧（`frontend/app/pages/shift/[id]/requests.vue`）【v1.1 大幅改訂・Codex 検分 P1-3】

> **初版の誤り**: 初版は「セルに時間バッジを足す」だけで済むと書いていた。**足りない。**

**実測（自分で裏を取った）**:

- 列は**日付単位**である。`dateList`（89-93 行）が `requests` の `slotDate` を `Set` で畳んで列を作る。
- セルの値取りは `getPreference(userId, date)`（96-101 行）で、実装は **`arr.find((r) => r.slotDate === date)`** ＝ **その日の最初の 1 件しか選ばない**。
- 描画（252-262 行）もその 1 件を `ShiftPreferenceIcon` で出すだけ。

したがって PR1 で同日複数枠の希望を許可すると、**2 枠目の希望はこの画面に一切現れない**。時間バッジを足しても、そもそも表示対象に選ばれない希望は見えない。これは本設計の柱である方針②（必ず可視化する）を正面から破る。

**改める内容**: **列を「日付」から「枠」へ変える。**

- 列の定義を、当該スケジュールの**枠一覧**（`GET /shifts/schedules/{id}/slots`）から作る。同日 2 枠なら 2 列になる。
- 列ヘッダは 2 段: 上段に日付（同日の枠は `colspan` でまとめる）、下段に `18:00-22:00` のような枠の時間帯（＋ポジション名があれば併記）。
- セルの値取りを `getRequest(userId, slotId)` へ変える（`slotId` 一致で引く）。
- `slotId` が `null` の**日付単位の希望**は、その日のすべての枠列に**同じ値を薄く（`opacity` を落として）表示**し、「枠を特定しない申請である」ことを凡例で説明する。枠指定の希望がある枠では枠指定を優先して表示する（§5.1 の割当ロジックと同じ優先順位）。
- 時間帯が指定されている希望には、アイコンに加えて時間バッジ（例: `20:00-22:00`）を出す。**枠を完全被覆しない（＝自動割当から外れる）希望はバッジを警告色にする**。管理者が「この申請は自動割当されない」と一目で判別できることが要件。
- 凡例（271-282 行）に、時間バッジ・警告色・薄表示（日付単位の希望）の 3 つを追加する。
- 希望が 1 件も無い枠の列も**表示する**（現行は `requests` から列を作るため、誰も希望を出していない日は列ごと消える。枠単位に変えることで「全員未提出の枠」が可視になる副次効果がある）。

なお `UID:{{ userId }}` 表示（248-250 行）と曜日配列の日本語ハードコード（129 行）は既存の別欠陥であり、本件では触らない（§9-6 に起票）。

### 6.2 メンバー: 希望提出（`frontend/app/pages/my/shift-request.vue`）

- 枠ごとのフォーム（375-424 行）に、5 段階ラジオの下へ「入れる時間帯（任意）」の開始・終了ピッカーを追加する。
- 既定は未指定（＝枠を丸ごと可）。既存の一括設定ダイアログ（`ShiftPreferenceBulkSetDialog`）は時間帯を扱わない（一括で時間帯を入れる需要は既定希望コピー §6.3 で満たす）。
- 送信時、時間帯は**常に現在値を送る**（§4.3 のクリア・セマンティクス）。

### 6.3 メンバー: 既定希望（`frontend/app/pages/my/shift-availability.vue`）

- **`'00:00'` / `'23:59'` のハードコード（85-86 行）を解除**し、曜日ごとに時間帯を入力できるようにする。
- `initDefaults()`（47-56 行）がサーバの `startTime` / `endTime` を読み捨てている往復欠損を修復する。
- 「この既定を今回のシフト希望に反映する」ボタンから §4.4 の `apply-defaults` を呼ぶ。
- 本ページは**FE 全体からリンク 0 件の孤立ページ**である（CMP-260909-1141 で判明）。導線の付与は 1141 の射程だが、本戦役で時間帯の入力口となる以上、**`my/shift-request.vue` からの導線は本戦役で付ける**（重複作業を避けるため 1141 側の担当と調整すること）。

### 6.4 i18n

追加文言はすべて `frontend/app/locales/{ja,en,zh,ko,es,de}/shift.json` に登録する。直書き禁止。

---

## 7. 使い方ガイドへの記載事項（マスター指示）

既存の金型に沿って記載する。ガイド本文の実体は i18n（`frontend/app/locales/ja/shift.json` の `shift_guide` / `my_shift_guide`）であり、モーダルは `ShiftGuideModal.vue` + `ShiftGuideContent.vue`（管理側）/ `MyShiftGuideModal.vue` + `MyShiftGuideContent.vue`（メンバー側）。

### 7.1 メンバー向け（希望提出ガイド）

1. 「入れる時間帯」は**任意**。空欄なら「枠の時間を丸ごと入れる」の意味になる。
2. 枠の一部だけを申請した場合（例: 18:00-22:00 の枠に 20:00-22:00）、**自動割当の対象からは外れる**。ただし申請は管理者に必ず届く。
3. 毎回同じ時間帯なら、`マイページ > 既定のシフト希望` に登録しておけば今回分へまとめて反映できる。

### 7.2 管理者向け（希望一覧・自動割当ガイド）【マスター指示の中核】

1. 時間帯付きの希望はマトリクスに時間バッジで表示される。
2. **枠を部分的にしか埋められない希望は自動割当されない。** その理由（時間の穴が警告に出ないため）を 1 文で添える。
3. **部分時間で人を入れたいときは、枠そのものを分割する。** 例:「18:00-22:00 の枠を 18:00-20:00 と 20:00-22:00 の 2 枠に分ければ、20 時から入れる人を後半の枠へ自動割当できる」。枠の分割は `シフト表 > 編集` タブから既存機能で今すぐ行える。
4. 自動割当の実行結果に `PARTIAL_TIME_COVERAGE` の申し送りが出たら、その枠は分割を検討する合図である。

### 7.3 ガイドモーダルの新設

実測により、`shift/[id]/requests.vue` / `my/shift-request.vue` / `my/shift-availability.vue` の 3 画面には **`PageHeader` の `help` フラグもガイドモーダルも無い**（`requests.vue` は `PageHeader` すら使わず生 `<h1>`）。`PageHeader.vue` は 13-14 行で `help` / `helpLabel` prop、55-61 行で `data-testid="page-header-help"` の `@help` emit を用意済みのため、既存の金型に沿ってモーダルを新設する。

---

## 8. PR 分割案

| PR | 内容 | 依存 | 並行可否 |
|---|---|---|---|
| **PR1** | **CMP-260909-1143 の是正**（`ShiftRequestService` の重複判定を枠単位へ／`buildPreferenceMap` のキーを枠単位へ）＋ **`slotId` の実体整合検証**（§4.3.1・`SHIFT_038`）。DDL なし。BE のみ | なし | **最初に単独で着地させる** |
| **PR2** | DDL（`V205.*`: 時刻 2 列 ＋ CHECK ＋ 重複掃除 ＋ 生成列 `slot_ref` ＋ UNIQUE）＋ Entity / DTO / Mapper ＋ 保存・取得・バリデーション・`SHIFT_037` ＋ PATCH 契約（`clearAvailableTimeWindow`）。BE のみ。試練先行 | PR1 | PR3 と並行不可（同一クラス） |
| **PR3** | 自動割当: 枠の fail-closed 除外 ＋ 被覆判定 ＋ 警告 2 種（`PARTIAL_TIME_COVERAGE` / `SLOT_TIME_RANGE_INVALID`）。BE のみ | PR2 | PR4・PR9 と並行可 |
| **PR4** | `POST /shifts/requests/apply-defaults`（既定コピー・不正既定スキップ・同曜日複数の決定規則・原子性）＋ 認可 IT | PR2 | PR3 と並行可 |
| **PR9** | **【v1.1 新規】枠の削除・分割の安全化**: `DELETE /shifts/slots/{id}` の希望存在時 409 `SHIFT_039`（`force=true` で明示削除・監査ログ）＋ `POST /shifts/slots/{id}/split`（希望の移送）。BE のみ | PR2 | PR3・PR4 と並行可 |
| **PR5** | FE: メンバーの時間帯入力（`my/shift-request.vue`）＋ 生成型再生成 | PR2 | PR6 と並行可 |
| **PR6** | FE: **希望一覧の列を日付から枠へ作り替える**（§6.1）＋ 時間バッジ・警告色・凡例 | PR2 | PR5 と並行可 |
| **PR10** | **【v1.1 新規】FE: 枠の分割 UI**（枠編集から `split` を呼ぶ導線・削除時の 409 ハンドリングと確認ダイアログ） | PR9, PR6 | — |
| **PR7** | FE: 既定希望ページのハードコード解除・往復修復・コピー導線（`my/shift-availability.vue`） | PR4, PR5 | — |
| **PR8** | 使い方ガイド 3 画面（§7）＋ i18n 6 言語 | PR5, PR6, PR7, PR10 | 最後 |

**クリティカルパス**: PR1 → PR2 →（PR9 → PR10）→ PR8。PR3・PR4・PR5・PR6 は PR2 直後から並行できる。

**v1.1 で PR が 8 → 10 本に増えた理由**: Codex 検分 P1-1（枠削除で希望が CASCADE 消失）と P1-3（希望一覧が同日 2 枠目を表示できない）が、いずれも「バッジを足す」程度では済まない構造の変更を要求したため。PR9 / PR10 を落とすと**方針③の推奨運用そのものが成立しない**（枠を分割した瞬間に希望が消える）ため、本戦役の必須構成要素とする。

**注意**: PR2 が DDL を含むため、Flyway の major は着手直前に `origin/main` で採り直すこと（本設計時点の実測は 204、したがって 205）。

---

## 9. 本設計の射程外として起票すべき既存欠陥

いずれも本戦役では**直さない**。実測で見つかったため記録する。

1. **`ShiftSlotService` に枠時刻のバリデーションが無い**（`start < end` すら未検証。104-105 行 / 131-132 行）。予約ドメインには `SlotTimeValidator` という前例がある。
2. **設計書と実装の警告コード乖離**: 設計書は `VACANCY`、実装は `UNASSIGNED_SLOT`。
3. **設計書に存在する UNIQUE 制約が実 DDL に無い**: `01_db_design.md` は `uq_shift_requests_user_slot (schedule_id, user_id, slot_id, slot_date)` を記すが、`V3.073` に UNIQUE KEY は存在しない（重複防止はアプリ層のみ）。
4. **`shift_slots` に `ends_next_day` が無く、日跨ぎ枠を定義された意味で表現できない**（§3.3）。
5. **旧世代フォームの選択肢が v1 のまま**: `components/shift/ShiftRequestForm.vue:26-30` が `WANT` / `DONT_WANT` / `NEUTRAL` で、現行 5 段階と不整合（`pages/teams/[slug]/shifts.vue:138` が使用）。
6. **`requests.vue` の表示名が `UID:{userId}` のまま**（247-250 行に「userStore 統合時に置換」の実装注）、および曜日配列の日本語ハードコード（129 行・i18n 未対応）。
7. **`/shifts/availability` 3 本にチーム所属の検証が無い**（§4.4 の参考節。非メンバーが任意 `teamId` で自分の既定希望行を作れる）。
8. **既定希望に `start < end` のバリデーションが無い**（`V3.075` に CHECK なし・`AvailabilityDefaultRequest` は `@NotNull` のみ）。`22:00`-`02:00` や同値の行が保存できる（§4.4.1）。本件では `apply-defaults` 側でスキップして凌ぐが、入口を塞ぐのが根治である。

---

## 10. 受け入れ条件（AC）

BE / API はすべて**失敗するテストとして先に書ける粒度**で記す。ID は `AC-1142-xx`。

### 10.1 前提の是正（CMP-260909-1143 / PR1）

| ID | 受け入れ条件 |
|---|---|
| AC-1142-01 | 同一スケジュール・同一日に枠 A・枠 B がある時、同一ユーザーが枠 A と枠 B の**両方に希望を提出でき**、2 件目が `REQUEST_ALREADY_EXISTS`（409）にならない |
| AC-1142-02 | 同一ユーザーが**同一枠**へ 2 度目の提出をすると `REQUEST_ALREADY_EXISTS`（409）になる |
| AC-1142-03 | `slotId` が `null`（日付単位）の希望を同一日に 2 件出すと `REQUEST_ALREADY_EXISTS`（409）になる（日付単位の重複は従来どおり禁止） |
| AC-1142-04 | 同一日に枠 A（`PREFERRED`）・枠 B（`STRONG_REST`）の希望がある時、自動割当のスコア計算で**枠 A に `PREFERRED`、枠 B に `STRONG_REST` が使われる**（日付キーで後勝ちに潰れない） |
| AC-1142-05 | 同一枠に対して枠指定の希望と日付指定の希望が併存する時、**枠指定が優先**される |

### 10.2 保存・取得・後方互換（PR2）

| ID | 受け入れ条件 |
|---|---|
| AC-1142-06 | `POST /shifts/requests` に `availableStartTime` / `availableEndTime` を付けて提出すると 201 で保存され、レスポンスに同じ値が返る |
| AC-1142-07 | 時間帯を省略して提出すると 201 で保存され、レスポンスの両項目が `null` になる |
| AC-1142-08 | `GET /shifts/requests?scheduleId=` / `GET /shifts/my/requests` のレスポンスに両項目が含まれる |
| AC-1142-09 | **時刻が `NULL` の既存行**を含むスケジュールで一覧・サマリー・自動割当を実行しても 500 にならず、時刻なしの行は両項目 `null` で返る（後方互換） |
| AC-1142-10 | `PATCH /shifts/requests/{id}` で `availableStartTime` / `availableEndTime` を両方指定すると更新される |
| AC-1142-11 | **【v1.1 改訂】** `PATCH /shifts/requests/{id}` で時間帯 3 項目をすべて省略すると、**既存の時間帯が据え置かれる**（旧クライアント互換。§4.3） |
| AC-1142-11a | **【v1.1 新規】** `clearAvailableTimeWindow: true` を送ると時間帯が `NULL` / `NULL` へクリアされる |
| AC-1142-11b | **【v1.1 新規】** `clearAvailableTimeWindow: true` と `availableStartTime` / `availableEndTime` を同時に送ると 400 `SHIFT_037`（矛盾指定） |
| AC-1142-11c | **【v1.1 新規】** 時間帯付きの希望に対し、`preference` と `note` のみを送る**旧クライアント形式の PATCH** を行っても、時間帯が消えない（後方互換の中核） |
| AC-1142-12 | マイグレーション `V205.*` 適用後、既存の `shift_requests` 行が 1 件も欠落・改変されない（データ移行不要の担保） |

### 10.3 バリデーション境界値（PR2）

| ID | 受け入れ条件 |
|---|---|
| AC-1142-13 | `availableStartTime` のみ指定 → 400 `SHIFT_037` |
| AC-1142-14 | `availableEndTime` のみ指定 → 400 `SHIFT_037` |
| AC-1142-15 | `start == end`（例 20:00 / 20:00）→ 400 `SHIFT_037` |
| AC-1142-16 | `start > end`（逆転。例 22:00 / 20:00）→ 400 `SHIFT_037` |
| AC-1142-17 | 日跨ぎのつもりの入力（例 22:00 / 02:00）→ **`start > end` として 400 `SHIFT_037`**（日跨ぎは本設計では扱わない） |
| AC-1142-18 | 枠の時間範囲**外**の時間帯（例 09:00-12:00 の希望 × 18:00-22:00 の枠）→ **400 にならず 201 で保存される**（方針②） |
| AC-1142-19 | `slotId` が `null` の日付単位希望に時間帯を付けても **400 にならず保存される**（照合すべき枠が一意でないため） |
| AC-1142-20 | 上記 400 系は `PATCH` でも同じコード・同じステータスで拒否される |
| AC-1142-21 | Service 層を迂回して DB へ片側のみ／逆転の値を書こうとすると、CHECK 制約 `chk_sr_time_window` により失敗する |
| AC-1142-21a | **【v1.1 新規・P2-4】** 生 SQL で `available_start_time = '-01:00'` または `available_end_time = '25:00'` を書こうとすると CHECK 制約により失敗する（`LocalTime` へマップできない行を作らせない） |
| AC-1142-21b | **【v1.1 新規・P2-4】** 境界値 `00:00:00` 開始・`23:59:59` 終了の希望は**保存できる**（範囲制限が正当な値を巻き添えにしない） |

### 10.4 認可・テナント越境（PR2 / PR4）

| ID | 受け入れ条件 |
|---|---|
| AC-1142-22 | 当該チームの**非メンバー**が時間帯付きで提出 → 403 `COMMON_002` |
| AC-1142-23 | 当該チームの **SUPPORTER** が時間帯付きで提出 → 403 `COMMON_002`（既存の SUPPORTER 提出不可を時間帯付きでも維持） |
| AC-1142-24 | **他チームのユーザー**が他チームの希望を `PATCH` で更新 → 403（越境で成功しない） |
| AC-1142-25 | 一般 MEMBER が**他人の希望**の時間帯を `PATCH` → 403 |
| AC-1142-26 | 希望の**提出者本人**は自分の希望の時間帯を `PATCH` できる（200） |
| AC-1142-27 | 当該チームの **ADMIN / DEPUTY_ADMIN** は他メンバーの希望の時間帯を `PATCH` できる（200。既存 `checkOwnerOrTeamAdmin` の踏襲） |
| AC-1142-28 | `SYSTEM_ADMIN` は短絡的に許可される（既存踏襲） |
| AC-1142-29 | 一覧 `GET /shifts/requests?scheduleId=` は非 ADMIN では 403 のまま（時間帯の追加で緩まない） |
| AC-1142-30 | `POST /shifts/requests/apply-defaults` は、リクエストで他ユーザーを指定する経路を持たず、**常に呼び出し元自身の希望のみ**を作成する |
| AC-1142-31 | `POST /shifts/requests/apply-defaults` を当該チームの非メンバー／SUPPORTER が叩く → 403 |
| AC-1142-32 | `POST /shifts/requests/apply-defaults` に**他チームの** `scheduleId` を渡す → 403 / 404（越境で作成されない） |
| AC-1142-33 | 上記認可は実 MySQL の `*ScopeContractIT`（非メンバー 403・越境・正当成功）で検証され、新規 EP は ArchUnit 認可番人（`AuthzControllerGuardArchTest`）を通る |
| AC-1142-33a | **【v1.1 新規・P1-6】** チーム A の正当なメンバーが、**チーム B の `slotId`** をチーム A の `scheduleId` と組み合わせて提出 → **404 `SHIFT_SLOT_NOT_FOUND`** で拒否され、行が作られない（越境保存の封鎖。403 ではなく 404 で、他テナントの枠 ID の存在を推測させない） |
| AC-1142-33b | **【v1.1 新規・P1-6】** 実在しない `slotId` を指定 → **AC-1142-33a と同一の応答**（404・同一メッセージ）になり、実在／非実在を応答差から判別できない |
| AC-1142-33c | **【v1.1 新規・P1-6】** `slotId` が指すシフト枠の `slot_date` と、リクエストの `slotDate` が食い違う → 400 `SLOT_DATE_MISMATCH`（`SHIFT_038`） |
| AC-1142-33d | **【v1.1 新規・P1-6】** 上記 3 件は `PATCH` 経路でも同様に拒否される |

### 10.5 締切・ステータス（PR2 / PR4）

| ID | 受け入れ条件 |
|---|---|
| AC-1142-34 | スケジュールが `COLLECTING` 以外の時、時間帯付きの提出は `INVALID_SCHEDULE_STATUS`（`SHIFT_012`）で拒否される |
| AC-1142-35 | `requestDeadline` を過ぎている時、時間帯付きの提出は `REQUEST_DEADLINE_PASSED`（`SHIFT_011`）で拒否される |
| AC-1142-36 | 同じく時間帯付きの `PATCH` も上記 2 条件で拒否される |
| AC-1142-37 | `apply-defaults` も上記 2 条件で拒否される |

### 10.6 自動割当（PR3）

すべて枠 18:00-22:00・必要人数 1 を基準ケースとする。

| ID | 受け入れ条件 |
|---|---|
| AC-1142-38 | 希望 18:00-22:00（**完全一致**）→ 候補に**入り**、割当される |
| AC-1142-39 | 希望 20:00-22:00（**部分被覆**）→ 候補から**外れ**、割当されない |
| AC-1142-40 | 希望 17:00-23:00（**包含**）→ 候補に**入り**、割当される |
| AC-1142-41 | 希望 09:00-12:00（**無交差**）→ 候補から**外れる** |
| AC-1142-42 | 時刻 `NULL` の希望 → **従来どおり**候補に入る |
| AC-1142-43 | `ABSOLUTE_REST` かつ時間帯が枠を完全被覆する希望 → **割当されない**（`ABSOLUTE_REST` の除外が被覆判定より優先） |
| AC-1142-44 | `ABSOLUTE_REST` かつ部分被覆の希望 → 割当されず、かつ `PARTIAL_TIME_COVERAGE` 警告は**積まれない**（ノイズ抑止） |
| AC-1142-45 | 部分被覆の希望者しかいない枠 → 誰も割当されず、`UNASSIGNED_SLOT` と `PARTIAL_TIME_COVERAGE` の**両方**が警告に積まれる |
| AC-1142-46 | 完全被覆の希望者で必要人数が充足した枠でも、部分被覆の申請者がいれば `PARTIAL_TIME_COVERAGE` が**必ず積まれる**（充足の有無に関わらず管理者へ届く） |
| AC-1142-47 | `PARTIAL_TIME_COVERAGE` の警告は `slotId` と `userId` の両方を持ち、メッセージに枠の時間と申請時間帯の双方が含まれる |
| AC-1142-48 | **【v1.1 改訂・P1-2】** 枠が `endTime <= startTime`（例 22:00-02:00）の場合、**その枠には誰も自動割当されない**（fail-closed）。とくに 09:00-12:00 しか申請していない利用者が**割り当てられないこと**を明示的に検証する |
| AC-1142-48a | **【v1.1 新規・P1-2】** 同じ状況で、時刻 `NULL` の希望者も当該枠へ割り当てられない（「丸ごと」が定義できないため） |
| AC-1142-48b | **【v1.1 新規・P1-2】** 同じ状況で警告 `SLOT_TIME_RANGE_INVALID` が `slotId` 付きで積まれ、管理者に枠の修正を促すメッセージを含む |
| AC-1142-48c | **【v1.1 新規・P1-2】** 不正な枠が 1 件あっても、**同一スケジュール内の正常な枠の割当は通常どおり実行される**（1 枠の異常が全体を止めない） |
| AC-1142-49 | 同一日に枠 A（希望 18:00-22:00・完全被覆）と枠 B（希望 20:00-22:00・部分被覆）がある時、**枠 A のみ割当され枠 B は外れる**（AC-1142-04 と時間帯判定の合成） |
| AC-1142-49a | **【v1.1 新規・P1-4】** 希望 18:00-22:00 が保存された後、管理者が枠を 17:00-23:00 へ広げると、その希望は**非被覆となり候補から外れる**（利用者が申請していない範囲へ割り当てない。絶対時刻保存の担保） |

### 10.7 既定希望からのコピー（PR4 / PR7）

| ID | 受け入れ条件 |
|---|---|
| AC-1142-50 | `apply-defaults` は、枠の `slot_date` の**曜日に一致する**既定希望のみを写す |
| AC-1142-51 | 既に希望が存在する枠は**上書きされない**（スキップされ、既存の `preference` / 時間帯 / `note` が変わらない） |
| AC-1142-52 | **【v1.1 改訂・P1-4】** 既定の時間帯が枠と**完全に一致する**場合でも、作成される希望には**その絶対時刻がそのまま入る**（`NULL` へ正規化しない） |
| AC-1142-53 | 既定の時間帯が枠を部分的にしか覆わない場合、その時間帯がそのまま `available_start_time` / `available_end_time` に写る |
| AC-1142-54 | 既定希望が 1 件も無い状態で `apply-defaults` を叩いても 500 にならず、作成 0 件で正常終了する |
| AC-1142-55 | `my/shift-availability.vue` で時間帯を設定して保存し、再読込しても **00:00-23:59 に戻らない**（往復欠損の修復） |
| AC-1142-55a | **【v1.1 新規・P2-1】** 不正な既定行（`22:00`-`02:00` や同値）が混ざっていても `apply-defaults` は **500 にならず**、その行だけをスキップし、正常な既定は反映される |
| AC-1142-55b | **【v1.1 新規・P2-1】** スキップした行はレスポンスの `skipped` に `reason: "INVALID_DEFAULT_TIME_RANGE"` として返り、握りつぶされない |
| AC-1142-55c | **【v1.1 新規・P2-1】** 処理途中で例外が発生した場合、**作成済みの希望を含め全件ロールバックされる**（中途半端な状態を残さない） |
| AC-1142-55d | **【v1.1 新規・P2-2】** 同一曜日に既定が複数行ある場合、**枠を完全被覆する行**が優先して採用される |
| AC-1142-55e | **【v1.1 新規・P2-2】** 被覆する行が無い場合、**枠と重なりが最大の行**が採用される。重なりが同点なら開始時刻が早い行が採られ、**取得順を入れ替えても結果が変わらない**（決定論性） |
| AC-1142-55f | **【v1.1 新規・P2-2】** 同一曜日に複数行あっても、**1 枠につき作成される希望は必ず 1 件以下**（重複希望を作らない） |
| AC-1142-55g | **【v1.1 新規・P2-2】** 枠と全く重ならない既定行しか無い枠には希望が**作られず**、`reason: "NO_MATCHING_DEFAULT"` が返る |
| AC-1142-55h | **【v1.1 新規・P2-3】** `apply-defaults` を**2 回連続で実行**しても、同一枠に重複した希望が作られない（2 回目は全件スキップ） |
| AC-1142-55i | **【v1.1 新規・P2-3】** 同一枠への希望 POST を**並行に 2 本**投げると、片方のみ成功しもう片方は `REQUEST_ALREADY_EXISTS`（409）になる（UNIQUE 制約違反を 409 へ翻訳） |

### 10.9 枠の削除・分割の安全化（PR9 / PR10）【v1.1 新規・P1-1】

| ID | 受け入れ条件 |
|---|---|
| AC-1142-64 | 希望が 1 件以上ぶら下がった枠に対する `DELETE /shifts/slots/{id}` は **409 `SLOT_HAS_REQUESTS`（`SHIFT_039`）** で拒否され、**希望も枠も削除されない** |
| AC-1142-65 | 希望が 0 件の枠の `DELETE` は従来どおり成功する（既存挙動の非回帰） |
| AC-1142-66 | `DELETE /shifts/slots/{id}?force=true` は希望があっても削除でき、**失われた希望の件数が監査ログに記録される** |
| AC-1142-67 | `POST /shifts/slots/{id}/split` は元枠 18:00-22:00 を 18:00-20:00 と 20:00-22:00 の 2 枠に置き換え、`requiredCount` / `positionId` / `note` を両方へ複製する |
| AC-1142-68 | `split` 実行後、**元枠に付いていた希望が 1 件も失われず**、両方の新枠へ複製されている（件数が `元の希望数 × 2` になる） |
| AC-1142-69 | `split` で移送された希望は、**時間帯 `NULL` は `NULL` のまま**、**時間帯付きは申請された絶対時刻のまま**である（新枠に合わせて丸められない） |
| AC-1142-70 | `split` 後に自動割当を実行すると、20:00-22:00 を申請した利用者は**後半の枠にのみ**割り当てられ、前半の枠には割り当てられない（P1-1 と本機能が噛み合っていることの検証） |
| AC-1142-71 | `split` は `preference` / `note` / `isProxyInput` / `proxyInputRecordId` を書き換えない（利用者の申請内容の非改竄） |
| AC-1142-72 | `splitAt` が枠の範囲外（`<= startTime` または `>= endTime`）の場合 400 で拒否される |
| AC-1142-73 | `split` は当該チームの ADMIN 以上のみ実行でき、非メンバー／SUPPORTER／一般 MEMBER は 403 |
| AC-1142-74 | `split` は単一トランザクションで、途中失敗時に**元枠も希望も元のまま残る**（部分適用されない） |

### 10.8 非回帰（全 PR）

| ID | 受け入れ条件 |
|---|---|
| AC-1142-56 | `shift_slots.assigned_user_ids` の **JSON 形状が変わらない**（自動割当の確定後も従来と同一のシリアライズ） |
| AC-1142-57 | シフト表 PDF（`layout=team` / `layout=personal`）の出力内容が本変更で変わらない |
| AC-1142-58 | シフト交代依頼（`shift_swap_requests`）・変更依頼（`shift_change_requests`）の挙動が変わらない |
| AC-1142-59 | マイシフト（`ShiftMyService`）の確定枠表示が変わらない |
| AC-1142-60 | 希望サマリー（`GET /shifts/requests/summary`）の 5 段階カウントが、時間帯の有無に関わらず従来と同一の値を返す |
| AC-1142-61 | `docs/openapi.json` を再生成し `frontend/app/types/generated/index.ts` を更新した上で、FE の `npm run lint` / typecheck が緑 |
| AC-1142-62 | UI 追加文言はすべて `locales/{ja,en,zh,ko,es,de}/shift.json` に登録され、直書きが 0 件 |
| AC-1142-63 | 使い方ガイド（管理者側）に「**枠を分割すれば部分時間に対応できる**」旨が明記されている（§7.2-3。マスター指示の達成確認） |
| AC-1142-63a | **【v1.1 新規・P2-5】** 同一の割当内容に対して、**時間帯機能の導入前後で人件費予算の消費行（時間・金額）が同一**である。`01_db_design.md:346` の定義どおり予算は `assigned_user_ids` の読取経路で実行されるため、JSON 形状の一致（AC-1142-56）だけでは足りず、**公開イベント後に生成される消費行そのもの**を突き合わせて検証する |
| AC-1142-63b | **【v1.1 新規・P2-5】** 部分被覆で候補から外れた利用者は予算の消費行に現れない（割当されていない者に費用が立たない） |

### 10.10 希望一覧の可視化（PR6）【v1.1 新規・P1-3】

| ID | 受け入れ条件 |
|---|---|
| AC-1142-75 | 同一日に枠 A・枠 B がある時、希望一覧の列は**枠ごとに 2 列**になり、A と B の**異なる `preference` が同時に表示される**（`slotDate` で 1 件に畳まれない） |
| AC-1142-76 | 同一日の 2 枠に**異なる時間帯**を申請した場合、それぞれの時間バッジが該当する列に表示される |
| AC-1142-77 | 枠を完全被覆しない（＝自動割当から外れる）希望の時間バッジは**警告色**で表示され、被覆する希望と視覚的に区別できる |
| AC-1142-78 | `slotId` が `null` の日付単位の希望は、その日の**全枠列に薄く表示**される。同じ枠に枠指定の希望もある場合は**枠指定が優先して表示**される |
| AC-1142-79 | 誰も希望を出していない枠も**列として表示される**（列が消えて未提出が見えなくなることがない） |
| AC-1142-80 | 凡例に「時間バッジ」「警告色（自動割当対象外）」「薄表示（枠を特定しない申請）」の 3 つの説明が含まれる |

**AC 総数: 104**（初版 63 → v1.1 で 41 件追加）

内訳（v1.1 追加分）: PATCH 後方互換 3 / 時刻範囲 2 / `slotId` 実体整合・越境 4 / fail-closed 枠 3 / 絶対時刻保持 1 / 既定コピーの不正・複数・競合 9 / 枠の削除と分割 11 / 予算非回帰 2 / 希望一覧の枠単位表示 6

---

## 11. 変更履歴

- **v1.1 (2026-09-09)**: Codex 独立検分の P1×6 / P2×5 を全件反映。**方針変更 2 件（要マスター再裁可）**: ①枠削除が希望を CASCADE 消失させるため、削除の既定拒否＋分割 API（希望の移送）を新設し方針③の前提を成立させた（§2.3.1・PR9/PR10）②判定不能な枠を fail-open から **fail-closed** へ改めた（§3.3-2・誤割当の根絶）。ほかに時間帯を絶対時刻で保持し枠に合わせて正規化しない方針を明文化（§2.3.2）、PATCH を「省略＝据え置き／明示フラグでクリア」へ改訂（§4.3）、`slotId` の実体整合検証を追加（§4.3.1）、DDL に一意制約と時刻範囲を追加（§3.2）、既定コピーの不正行スキップ・同曜日複数の決定規則・原子性を定義（§4.4.1/§4.4.2）、希望一覧の列を日付から枠へ作り替え（§6.1）。AC 63 → **104 件**、PR 8 → **10 本**
- **v1.0 (2026-09-09)**: 初版（軍議）。CMP-260909-1142 の設計と AC 63 件。CMP-260909-1143 を PR1 として射程に含める判断を記載
