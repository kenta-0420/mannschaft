# F08.7.1 / 05: 試合メンバー表（自チーム作成＋エントリーテンプレ流用＋主催者締切管理）

> **ステータス**: 🟢 設計完了 ／ 🟢 バックエンド実装完了（隊5・test-first）
> **最終更新**: 2026-06-01

> **実装メモ（2026-06-01・隊5）**: バックエンドを設計書どおり実装。
> - Flyway `V9.20260601160000`〜`160500`（roster_deadline / registration_number / uniform_set_id 追加 ＋
>   新規 `team_uniform_set` / `match_roster_staff` / `tournament_entry_template_staff`）。
> - **型方針＝案A 確定**: 着手時 DDL 確認で `tournament_entry_templates.id` の実体物理型は **`CHAR(36)`**
>   （`V9.123`/`V9.124`）。よって `tournament_entry_template_staff.template_id` を **`CHAR(36)`** とし FK CASCADE を成立させた
>   （既存 `tournament_entry_template_members.template_id` も `CHAR(36)` で FK 成立済を踏襲）。
> - API: `GET/PUT /api/v1/tournaments/{tId}/matches/{matchId}/rosters/me`、
>   `POST .../rosters/me/apply-template`、`GET .../rosters`（主催者）、`PATCH .../matches/{matchId}`（締切）。
> - 認可: 提出/適用=自チーム ADMIN/DEPUTY のみ・主催者=閲覧/締切・締切後 409・全 read 認可・提出監査
>   （`TOURNAMENT_ROSTER_SUBMITTED` / `TOURNAMENT_ROSTER_DEADLINE_UPDATED`）。
> - test-first: `MatchRosterServiceTest`（21 ケース）。FK 実体型成立は `FlywayFromScratchMigrationTest`（Docker 必須・CI で検証）。
> - 残: FE 未着手。エントリーテンプレ書込 API への登録番号/staff 入力欄追加は別スコープ（テーブル列は本実装で用意済）。
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・トレーサビリティ
> - [F08.7_tournament_league.md](../F08.7_tournament_league.md) — 既存 `tournament_match_rosters` / `tournament_entry_templates` / `tournament_matches` / `tournament_participants`
> - [docs/security/03_role_authority_model.md](../../security/03_role_authority_model.md) §15 — トーナメント系スコープの認可方針

本書は確定要件 ⑩（**試合メンバー表**＝自チームから作成（エントリーテンプレ流用）＋主催者は締切・閲覧管理）を具体化する。サッカー対応の**項目拡充**（選手登録番号・ユニフォーム色指定・ベンチ入り役員欄）とメンバー表テンプレの一括保存／1 タップ適用は §8 に詳述する。

---

## 1. 中核思想 — 既存テーブルを活用、自チーム作成・提出

| 既存資産 | 流用方法 |
|---------|---------|
| `tournament_match_rosters`（`match_id`/`participant_id`/`user_id`/`is_starter`/`jersey_number`/`position`、`UNIQUE(match_id, user_id)`） | 試合ごとの出場メンバー表の実体。**そのまま使用** |
| `tournament_entry_templates` / `tournament_entry_template_members`（team_id スコープ・背番号/ポジション保持） | エントリーテンプレを **1 タップ適用**（テンプレ → roster へ複製）する元データ |
| `tournament_participants`（チーム → ディビジョン参加） | 呼び出しチーム → participant 解決の源泉 |

- メンバー表は**自チームから作成・提出**する。チーム代表(ADMIN/DEPUTY)が自チーム分のみ編集できる。
- 主催者（組織 ADMIN）は**締切設定**と**全チームの提出状況・内容の閲覧**を行う（既定は代理入力なし）。
- 既存 `tournament_match_rosters` の CRUD API/UI が未整備なら新設、実装済みなら自チーム提出フロー/テンプレ適用/締切のみ追加する（§7 の注記参照）。

---

## 2. DDL — 締切カラム追加（唯一の DB 変更）

提出締切を `tournament_matches` に 1 列追加する。

```sql
ALTER TABLE tournament_matches
  ADD COLUMN roster_deadline DATETIME NULL;   -- 提出締切（NULL = 締切なし）
```

- `roster_deadline DATETIME NULL`（NULL = 締切なし＝いつでも提出可）。
- 既存テーブルへのカラム追加のみ。新規テーブルなし。`tournament_matches` は BIGINT ID の既存テーブルゆえ ID 方式は変更しない（原則 6 は新規テーブルのみ対象）。
- 締切は試合単位で設定する設計。節（`tournament_matchdays`）一括で設定したい運用は、主催者 UI で「節内の全試合に同じ締切を一括 PATCH」する操作として吸収する（DB は試合単位の `roster_deadline` を正本とする）。

---

## 3. フロー（自チーム作成＋テンプレ流用）

ADHD 配慮（入力摩擦最小・必須項目最小）を最優先する。

1. チーム代表(ADMIN/DEPUTY)が自チームのマイページ/試合詳細から、対象 match の**自チーム分メンバー表**を開く。
2. 保存済み **エントリーテンプレを 1 タップ適用**（`tournament_entry_templates` → `tournament_match_rosters` へ複製）。
3. 先発/控え（`is_starter`）・背番号（`jersey_number`）・ポジション（`position`）を調整。
4. **提出**（保存）。
5. 主催者（組織 ADMIN）は **提出締切**（`roster_deadline`）を設定し、各チームの**提出状況・内容を閲覧**。締切後は編集ロック。

- テンプレ未作成でも手動で 1 人ずつ追加できる（テンプレは任意・必須ではない）。
- 提出 = `tournament_match_rosters` への UPSERT（`UNIQUE(match_id, user_id)` で冪等）。提出済みの自チーム行を差し替え可能（締切前に限る）。

---

## 4. API（新設・既存未整備分）

| メソッド | パス | 認可 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/tournaments/{tId}/matches/{matchId}/rosters/me` | 自チーム MEMBER 以上 | 自チーム分の現在の roster を取得（participant は呼び出しチームから解決） |
| PUT | `/api/v1/tournaments/{tId}/matches/{matchId}/rosters/me` | **自チーム ADMIN/DEPUTY** | 自チーム分 roster を提出（UPSERT）。締切後は 409 でロック |
| POST | `/api/v1/tournaments/{tId}/matches/{matchId}/rosters/me/apply-template` | **自チーム ADMIN/DEPUTY** | エントリーテンプレを適用（テンプレ → roster 複製）。body: `{ templateId, overwriteExisting? }` |
| GET | `/api/v1/tournaments/{tId}/matches/{matchId}/rosters` | **主催組織 ADMIN**（＋参加チーム閲覧はオプション） | 全チーム分の提出状況・内容を閲覧（主催者ビュー） |
| PATCH | `/api/v1/tournaments/{tId}/matches/{matchId}` | **主催組織 ADMIN** | `roster_deadline` 設定（試合メタ更新の一部） |

- `rosters/me` の participant 解決: 呼び出しユーザーの所属チーム → 当該 matchId が属する大会の `tournament_participants` から、ホーム/アウェイいずれかの participant を特定（自チームが対戦当事者でなければ 403）。
- **IDOR 検証チェーン**（Service 層必須）: `matchId → matchday → division → tId` 帰属、`participant → division` 帰属、`team → participant` 帰属。
- 主催者ビュー `GET .../rosters` での**相手チーム内容の非公開**はオプション（マスター裁可で「主催者は閲覧可」を既定とし、参加チーム同士で相手 roster を締切前に見せない設定はトグルで提供）。

---

## 5. 認可・セキュリティ

| 操作 | 許可ロール |
|------|-----------|
| 自チーム roster の取得（`rosters/me` GET） | 当該チーム MEMBER 以上（対戦当事者チームのみ） |
| 自チーム roster の提出/テンプレ適用（PUT / apply-template） | **当該チームの ADMIN/DEPUTY のみ** |
| 全チーム roster 閲覧（`rosters` GET） | 主催組織 ADMIN / SYSTEM_ADMIN |
| 締切設定（`roster_deadline` PATCH） | 主催組織 ADMIN / SYSTEM_ADMIN |

- **他チームの roster は編集不可**（自チーム ADMIN/DEPUTY は自チーム participant の行のみ操作可。他 participant 行への INSERT/UPDATE は 403）。
- **既定は代理入力なし**（主催者は閲覧・締切管理のみ。マスター選択肢①）。将来代理入力が必要なら別途要件化。
- **締切後ロック**: `roster_deadline` を過ぎた match への提出（PUT/apply-template）は **409 Conflict**（締切超過）で拒否。締切 NULL ならロックなし。
- **提出監査**: 誰がいつ提出したかを記録する。`tournament_match_rosters.created_at` に加え、提出操作を監査ログ（`AuditEventType` に `TOURNAMENT_ROSTER_SUBMITTED` 等を追加）に残す。
- 存在しない match / roster は **404**（IDOR 統一）。team_id・participant_id・user_id は ID 参照のみ（クロスドメイン FK なし／原則 1）。

---

## 6. ユーザビリティ（ADHD 配慮）

- **エントリーテンプレ 1 タップ適用**で初期入力をゼロ手間に。
- 必須項目は最小（user_id のみ。背番号・ポジション・先発フラグは任意・後で調整可）。
- 締切が近い未提出 match を自チームの試合一覧でハイライト（FE）。
- モバイルでの編集を前提に、先発/控えの並べ替え・チェックボックス操作を主体とする。

---

## 7. 実装時の確認事項（症状を隠さない）

- 既存 `tournament_match_rosters` の **CRUD API/UI の実装有無を実装時に確認**する。
  - 未実装なら新設（本書の API をフル実装）。
  - 実装済み（管理者一括登録のみ等）なら、**自チーム提出フロー（`rosters/me`）・テンプレ適用・締切ロック**を追加する形で統合する。
- `tournament_entry_templates` の適用ロジック（F08.7 の `POST load-from-team` 相当）が roster へ複製する経路を持つか確認し、無ければ `apply-template` で新設する。
- いずれも「未実装を握りつぶさず、未実装は新設して根治」する（CLAUDE.md 障害対応の原則）。

---

## 8. 項目拡充（サッカー対応・テンプレ化）

要件⑩の「項目拡充」を具体化する。**選手登録番号・ユニフォーム色指定・ベンチ入り役員欄**の 3 点をメンバー表に追加し、いずれも「メンバー表テンプレ」として一括保存・1 タップ適用できるようにする（入力摩擦最小／ADHD 配慮）。

### 8.1 選手登録番号（協会登録番号・背番号とは別）

サッカー協会の選手登録番号は、背番号（`jersey_number`）・チーム内識別とは別の恒久的な番号である。これを保持するため、roster とテンプレ両方に列を追加する。

```sql
-- 試合メンバー表（既存 BIGINT PK テーブル・列追加のみ）
ALTER TABLE tournament_match_rosters
  ADD COLUMN registration_number VARCHAR(32) NULL;   -- 協会選手登録番号（背番号 jersey_number とは別。NULL 可）

-- エントリーテンプレのメンバー（既存 UUID PK テーブル・列追加のみ）
ALTER TABLE tournament_entry_template_members
  ADD COLUMN registration_number VARCHAR(32) NULL;   -- 同上。テンプレ適用時に roster へ複製される
```

- `registration_number VARCHAR(32) NULL`（協会未登録選手・登録番号未取得の段階を許容するため NULL 可）。
- テンプレ適用（`apply-template`）時に `tournament_entry_template_members.registration_number` → `tournament_match_rosters.registration_number` へ複製する。
- **PK 型の実態（検分1周目で確認）**: `tournament_match_rosters` は **BIGINT PK**（`TournamentMatchRosterEntity.java:28-30` ＝ `@Id @GeneratedValue(IDENTITY) Long id`、DDL `V8.046:3` ＝ `BIGINT UNSIGNED AUTO_INCREMENT`）。一方 `tournament_entry_templates` / `tournament_entry_template_members` は **UUIDv7 テーブル**（`TournamentEntryTemplateEntity` / `TournamentEntryTemplateMemberEntity` がともに `UuidV7Entity` を継承。`TournamentEntryTemplateMemberEntity.java:33` の `templateId` も `@Column(columnDefinition = "BINARY(16)") UUID`）。
- 列追加（`registration_number`）自体は PK 型に関係なく可能なので、上記 ALTER はそのまま成立する。**「entry_template 系も BIGINT テーブル」という記述は誤り**（B-2 訂正）。entry_template 系は新規 FK・新規子テーブルを足す際に **UUID（BINARY(16)）に整合**させる必要がある（§8.4 参照）。

> **実装時注意（DDL/Entity 型の不一致）**: entry_template 系の作成移行（`V9.123` / `V9.124`）は PK を `CHAR(36)` で宣言しているが、Entity 側は `UuidV7Entity`＝`BINARY(16)` 前提・`templateId` も `columnDefinition="BINARY(16)"`。実 DB の物理型（`CHAR(36)` か `BINARY(16)` か）を実装時に必ず確認し、本章で新設する子テーブルの FK 列の型を **参照先 PK の実体型に一致**させること（不一致は FK 作成エラー／暗黙キャストの原因）。本設計は UuidV7 規約に従い **`BINARY(16)`** を正とし、既存 DDL が `CHAR(36)` のままなら併せて整合移行する想定で記述する。

### 8.2 ユニフォーム色指定（新規テーブル `team_uniform_set`）

メンバー表提出時に「どのユニフォームセットを着用するか」を指定する。相手チームとのカラー衝突回避のため、**試合ごとに使用セットを上書き**できる。セット自体は team_id スコープでテンプレ保存・再利用する。

```sql
-- ユニフォームセット（チーム単位の色テンプレ。新規テーブル → UUIDv7 / 原則 6）
CREATE TABLE team_uniform_set (
    id BINARY(16) NOT NULL,                  -- UUIDv7（UuidV7Entity 継承）
    team_id BIGINT NOT NULL,                 -- team ドメインへの ID 参照（クロスドメイン FK なし／原則 1）
    kind ENUM('FP', 'GK_PRIMARY', 'GK_SECONDARY') NOT NULL,  -- フィールドプレイヤー用 / GK 正 / GK 副
    label VARCHAR(64) NULL,                  -- 表示名（例「ホーム白」）
    shirt_color VARCHAR(32) NOT NULL,        -- シャツ色（色名 or HEX を文字列で保持）
    shorts_color VARCHAR(32) NOT NULL,       -- パンツ色
    socks_color VARCHAR(32) NOT NULL,        -- ソックス色
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    deleted_at DATETIME NULL,                -- soft delete（再利用テンプレの履歴保持）
    PRIMARY KEY (id),
    -- 同一チーム・同一 kind は複数セット保持可（ホーム/アウェイ等）。一意制約は設けず label で識別
    INDEX idx_team_uniform_set_team (team_id, kind)
);
```

- `kind ENUM('FP', 'GK_PRIMARY', 'GK_SECONDARY')`：フィールドプレイヤー用・GK 正・GK 副の 3 種を各シャツ/パンツ/ソックス色で保持。
- 色は色名（"白"）または HEX（"#FFFFFF"）を許容する `VARCHAR(32)`（バリデーションはアプリ層）。
- `team_id` は team ドメインへの **ID 参照のみ**（クロスドメイン FK なし／原則 1）。`deleted_at` で soft delete（テンプレ削除しても過去試合の参照を壊さない）。
- **試合ごとの使用セット参照**：roster 提出時に「FP=セットA、GK=セットB」を指定する。具体的には `tournament_match_rosters` へ着用セットを保持する列を追加して試合ごとに上書きできるようにする。

```sql
-- 試合メンバー表に着用ユニフォームセットの参照を追加（試合ごと上書き＝カラー衝突回避）
ALTER TABLE tournament_match_rosters
  ADD COLUMN uniform_set_id BINARY(16) NULL;   -- 着用 team_uniform_set への ID 参照（同一 team ドメイン・NULL 可）
```

> `tournament_match_rosters.uniform_set_id` は **team ドメイン内**（roster は tournament ドメインだが uniform_set は team ドメイン）への参照になるため、クロスドメイン FK は張らず ID 参照のみとする（原則 1）。値の整合（指定セットが自チームのものか）はアプリ層（Service）で検証する。

- ユニフォームセットは「メンバー表テンプレ」の一部として保存し、試合ごとに 1 タップで適用・必要時のみ上書きする（衝突時のみ手間が発生）。

### 8.3 ベンチ入り役員欄（新規テーブル `match_roster_staff`）

監督・コーチ・トレーナー等、**選手以外のベンチ入り役員**を記載する欄。アプリ未登録者も記載できるよう `user_id` は NULL 可とする。

```sql
-- ベンチ入り役員（試合単位×参加チーム単位。新規テーブル → UUIDv7 / 原則 6）
CREATE TABLE match_roster_staff (
    id BINARY(16) NOT NULL,                  -- UUIDv7（UuidV7Entity 継承）
    match_id BIGINT NOT NULL,                -- tournament_matches への ID 参照（同一 tournament ドメイン）
    participant_id BIGINT NOT NULL,          -- tournament_participants への ID 参照（自チーム分の roster と同じ単位）
    role VARCHAR(32) NOT NULL,               -- 役職（監督/コーチ/トレーナー/帯同審判 等。アプリ層で許容値検証）
    name VARCHAR(128) NOT NULL,              -- 氏名（アプリ未登録者も記載可のため文字列で保持）
    user_id BIGINT NULL,                     -- 紐付くユーザー（アプリ登録済みなら設定・NULL 可）
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_match_roster_staff_match (match_id, participant_id)
);
```

- `user_id BIGINT NULL`：アプリ未登録の外部スタッフ（例：協会から派遣の帯同審判）も `name`/`role` だけで記載できる。
- `match_id`/`participant_id` は同一 tournament ドメイン内テーブルへの ID 参照。`user_id` は user ドメインへの ID 参照（いずれもクロスドメイン FK なし／原則 1）。
- 編集権限は選手 roster と同一（自チーム ADMIN/DEPUTY のみ。§5 と同じ）。締切（`roster_deadline`）後ロックも roster と同様に適用する。
- ベンチ役員もテンプレに保持できるよう、テンプレ側にも staff を保存する（§8.4）。

### 8.4 メンバー表テンプレ（一括保存・1 タップ適用）

選手・登録番号・ユニフォームセット・ベンチ役員をまとめて「メンバー表テンプレ」として保存し、試合ごとに 1 タップで適用する（ADHD 配慮＝入力摩擦最小）。

- 選手・登録番号は既存 `tournament_entry_templates` / `tournament_entry_template_members`（§8.1 で `registration_number` を追加）を流用。
- ベンチ役員のテンプレ保持：`tournament_entry_templates` 配下に staff 用の子テーブル `tournament_entry_template_staff`（テンプレ ID・role・name・user_id NULL 可）を追加する。構造は `match_roster_staff` と対応させ、適用時に複製する。

```sql
-- エントリーテンプレのベンチ役員（テンプレ ID 配下。同一 tournament ドメイン → CASCADE 可／原則 2）
-- 親 tournament_entry_templates は UUIDv7 テーブル（UuidV7Entity）ゆえ template_id は BINARY(16)。
CREATE TABLE tournament_entry_template_staff (
    id BINARY(16) NOT NULL,                  -- UUIDv7（UuidV7Entity 継承）
    template_id BINARY(16) NOT NULL,         -- tournament_entry_templates(id) への参照（同一ドメイン・UUID）
    role VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    user_id BIGINT NULL,                     -- user ドメインへの ID 参照（クロスドメイン FK なし／原則 1）
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_template_staff_template (template_id),
    -- 同一 tournament ドメイン内（template の子）なので CASCADE 可（原則 2）
    -- FK 列型は参照先 PK の実体型に一致させること（§8.1 実装時注意：CHAR(36) のままなら CHAR(36) で合わせる）
    CONSTRAINT fk_template_staff_template FOREIGN KEY (template_id)
      REFERENCES tournament_entry_templates (id) ON DELETE CASCADE
);
```

> **B-2 訂正点**: 当初 `template_id BIGINT` としていたが、`tournament_entry_templates` は **UUIDv7 テーブル**（`TournamentEntryTemplateEntity extends UuidV7Entity`）。FK の `template_id` を BIGINT にすると参照先 PK と型が合わず FK 作成に失敗する。よって `BINARY(16)` とし、`TournamentEntryTemplateMemberEntity.templateId`（既存・`BINARY(16)`）と同じ規約に揃える。CASCADE は同一 tournament ドメイン内なので許可（原則 2 の範囲内）。

- ユニフォームセットはチーム単位の独立テンプレ（`team_uniform_set`）として既に再利用可能なため、エントリーテンプレ側には「既定セット ID」を任意で保持する程度に留める（必須ではない）。
- 適用フロー（`apply-template`）の複製対象を拡張：選手（既存）＋ `registration_number`（§8.1）＋ ベンチ役員（`tournament_entry_template_staff` → `match_roster_staff`）。ユニフォームは roster の `uniform_set_id` に既定セットをセット（衝突時のみ手動上書き）。

### 8.5 認可・締切（拡充項目への適用）

- 登録番号・ユニフォームセット・ベンチ役員の編集も **自チーム ADMIN/DEPUTY のみ**（選手 roster と同一。§5）。
- `team_uniform_set` の CRUD は当該チーム ADMIN/DEPUTY のみ（team スコープ）。他チームのセット参照（`uniform_set_id`）は 403。
- 締切（`roster_deadline`）後は選手 roster・ベンチ役員・ユニフォーム指定とも編集ロック（409）。
- 提出監査は §5 の `TOURNAMENT_ROSTER_SUBMITTED` に集約（拡充項目の変更も同一提出操作の一部として記録）。

### 8.6 DDL まとめ（本章分）

| 種別 | 対象 | 内容 | 原則 |
|------|------|------|------|
| 列追加 | `tournament_match_rosters` | `roster_deadline`（§2 は `tournament_matches`）・`registration_number VARCHAR(32) NULL`・`uniform_set_id BINARY(16) NULL` | 既存 **BIGINT PK** テーブル（`TournamentMatchRosterEntity` ＝ IDENTITY Long）ゆえ ID 方式不変 |
| 列追加 | `tournament_entry_template_members` | `registration_number VARCHAR(32) NULL` | 既存 **UUIDv7 PK** テーブル（`TournamentEntryTemplateMemberEntity extends UuidV7Entity`）。列追加自体は PK 型不問で可 |
| 新規テーブル | `team_uniform_set` | FP/GK 正・副 × シャツ/パンツ/ソックス色（team スコープ） | UUIDv7（原則 6）・クロスドメイン FK なし（原則 1） |
| 新規テーブル | `match_roster_staff` | ベンチ入り役員（match×participant 単位・user_id NULL 可） | UUIDv7（原則 6）。`match_id`/`participant_id` は **BIGINT**（参照先が BIGINT PK の `tournament_matches`/`tournament_participants`）・クロスドメイン FK なし（原則 1） |
| 新規テーブル | `tournament_entry_template_staff` | テンプレのベンチ役員（template 配下・CASCADE 可） | UUIDv7（原則 6）。`template_id` は **BINARY(16)**（参照先 `tournament_entry_templates` が UUIDv7 PK）・同一ドメイン CASCADE のみ（原則 2） |

---

## 9. 精査ログ

### 9.1 1 回目
- **不備**: 自チーム作成（PUT rosters/me）・テンプレ適用（apply-template）・主催者閲覧（GET rosters）・締切設定（PATCH roster_deadline）を網羅。項目拡充（登録番号・ユニフォーム色・ベンチ役員）とテンプレ一括保存／1 タップ適用も §8 で網羅。
- **セキュリティ**: 編集は自チーム ADMIN/DEPUTY のみ・他チーム roster／uniform_set 操作は 403・締切後ロック（409・拡充項目も対象）・提出監査・404 統一・クロスドメイン FK なし（`team_uniform_set.team_id`・`uniform_set_id`・`match_roster_staff.user_id` は ID 参照）。代理入力なし（既定）で権限境界を明確化。
- **ユーザビリティ**: テンプレ 1 タップ（選手＋登録番号＋ベンチ役員＋既定ユニフォーム）・必須最小（拡充項目は全て NULL 可）・カラー衝突時のみ手動上書き・締切ハイライト・モバイル前提（§6・ADHD 配慮）。
- **見落とし**: 既存 roster CRUD の実装有無確認（§7）、`AuditEventType` 追加、participant 解決の対戦当事者チェック、`uniform_set_id` のクロスドメイン参照を FK にせずアプリ層検証とする点を明記。
- **保守性**: 選手・登録番号は既存テーブルへ列追加で済ませ、ユニフォーム/ベンチ役員のみ新規テーブル（いずれも UUIDv7）。テンプレ子テーブルは同一ドメイン CASCADE（原則 2）。実装時の統合方針（未実装なら新設・実装済みなら差分追加）を明記。

### 9.2 2 回目（検分1周目の指摘反映＝B-2 根治）
- **PK 型の実態確認（実コード）**: `tournament_match_rosters`＝BIGINT PK（`TournamentMatchRosterEntity.java:28-30`／`V8.046:3`）、`tournament_entry_templates`/`_members`＝UUIDv7 PK（両 Entity が `UuidV7Entity` 継承／`V9.123`・`V9.124`）。
- **訂正**: ①§8.1 の「entry_template 系も BIGINT」記述を「UUIDv7 テーブル」へ是正。②§8.4 `tournament_entry_template_staff.template_id` を `BIGINT` → **`BINARY(16)`**（参照先 UUID PK と整合・同一ドメイン CASCADE）。③`registration_number` 列追加は PK 型に依存せず可能である点を明記。④`match_roster_staff` の `match_id`/`participant_id` は BIGINT PK 参照ゆえ BIGINT のまま。
- **DDL/Entity 不一致の正直化**: entry_template 系は DDL が `CHAR(36)`、Entity が `BINARY(16)` 想定という既存の物理型不一致を §8.1 に明記し、実装時に実体型へ FK を一致させる注記を追加。
- **退会（O-4）**: `match_roster_staff.user_id` / `tournament_entry_template_staff.user_id` / roster の `user_id` は履歴・証跡として保持＝**強匿名化対象外**（NULL 化しない）。表示名のみ既存匿名化に追従（CLAUDE.md 退会二段モデルと整合）。`name` 列はアプリ未登録者の手入力値ゆえ匿名化対象外（個人特定リスクは運用上の入力であり、当該本人の退会とは独立）。

### 9.3 未解決事項

**現時点でなし。**
