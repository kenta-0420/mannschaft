# F08.7.1 / 06: 大会ごとの書類提出受付（F05.6 workflow＋forms 再利用）

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-05-31
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・トレーサビリティ
> - [F05.6_workflow_approval.md](../F05.6_workflow_approval.md) — **母体**。汎用ワークフロー・承認エンジン（`workflow_templates` / `workflow_requests` / 承認ステップ / `workflow_request_attachments`）。本書はこれを大会スコープで再利用する
> - [F05.5_file_sharing.md](../F05.5_file_sharing.md) — 添付ファイルの実体（R2 presigned URL / `StorageQuotaService`）
> - [04_file_storage.md](./04_file_storage.md) — リーグ単位ファイル置き場（**役割が別**。④＝共有ライブラリ、本書⑥＝提出インボックス）
> - [07_tournament_payment.md](./07_tournament_payment.md) — 大会費用支払い（提出受理を「支払い済み」条件にゲートする連携先）
> - [01_communication.md](./01_communication.md) — 連絡スペース（参加チーム解決・認可規則を共有）

本書は確定要件 ⑪（**大会ごとの書類提出受付**＝既存 F05.6 ワークフロー＋forms を大会スコープで再利用。提出枠・締切・受理状況・承認）を具体化する。

---

## 1. 中核思想 — 汎用基盤は新規構築せず、F05.6 を大会スコープで再利用

| 既存資産（F05.6） | 再利用方法 |
|------------------|-----------|
| `form_templates`（必要書類・フィールド・添付要否の定義） | 主催者が定義する「提出枠」のフォーム定義として使用 |
| `form_submissions`（フォーム回答の実体） | 各チームが提出した書類の回答実体として使用 |
| `workflow_requests`（申請＋承認ステップのルーティング） | 提出の受理／差戻しの承認フローとして使用 |
| `workflow_request_attachments`（添付の登録） | 提出書類の添付（PDF 等）として使用。実ストレージは F05.5 / R2 |
| 承認ステップ（並列／順次・差し戻し再提出） | 受理・差戻しのワークフローとしてそのまま使用 |

- **汎用の提出／承認エンジンは新規に作らない**。大会への薄い連結テーブル `tournament_submission_requirement`（提出枠と tournament/division を結ぶ）だけを新設する。
- ファイル置き場（④ / 04_file_storage.md）とは**役割が別**：④は「主催者・参加チームが資料を共有するライブラリ」、本書⑥は「主催者が提出枠を定義し、各チームが期限までに提出、主催者が受理／差戻しする**提出インボックス**」。両者は同じ R2 ストレージ基盤（F05.5）を使うが、用途・一覧の出方・締切管理が異なる（§6 で役割差を明記）。

---

## 2. データモデル — 薄い連結テーブル `tournament_submission_requirement`

提出枠（どの form_template を、どの大会／ディビジョンの、誰が、いつまでに提出するか）を表す薄い連結テーブルのみを新設する。

```sql
-- 大会の提出枠（form_template と tournament/division を結ぶ薄い連結。新規テーブル → UUIDv7 / 原則 6）
CREATE TABLE tournament_submission_requirement (
    id BINARY(16) NOT NULL,                  -- UUIDv7（UuidV7Entity 継承）
    tournament_id BIGINT NOT NULL,           -- 対象大会（tournament ドメインへの ID 参照）
    division_id BIGINT NULL,                 -- 対象ディビジョン（NULL = 大会全体）。tournament ドメインへの ID 参照
    form_template_id BIGINT NOT NULL,        -- forms/workflow ドメインの form_template への ID 参照（クロスドメイン FK なし／原則 1）
    title VARCHAR(255) NOT NULL,             -- 提出枠の表示名（例「参加申込書」「選手登録一覧」）
    description TEXT NULL,                    -- 補足説明
    deadline DATETIME NULL,                  -- 提出締切（NULL = 締切なし）
    target_scope ENUM('ALL_TEAMS', 'SPECIFIC_TEAMS') NOT NULL DEFAULT 'ALL_TEAMS',  -- 対象＝全参加チーム / 特定チーム
    requires_payment BOOLEAN NOT NULL DEFAULT FALSE,  -- 受理条件に「大会参加費の支払い済み」を課すか（領域⑦連携）
    organization_id BIGINT NOT NULL,         -- 主催組織（テナント絞り込み・クォータ帰属）
    created_by BIGINT NOT NULL,              -- 作成した主催組織 ADMIN の user_id
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    deleted_at DATETIME NULL,                -- soft delete（履歴保持・クロスドメイン CASCADE なし／原則 2）
    PRIMARY KEY (id),
    INDEX idx_submission_req_tournament (tournament_id, division_id),
    INDEX idx_submission_req_org (organization_id)
);

-- 特定チームを対象にする場合の対象チーム明細（target_scope = SPECIFIC_TEAMS のとき。同一ドメインの子 → CASCADE 可／原則 2）
CREATE TABLE tournament_submission_requirement_target (
    id BINARY(16) NOT NULL,                  -- UUIDv7
    requirement_id BINARY(16) NOT NULL,      -- 親 requirement（同一ドメイン）
    team_id BIGINT NOT NULL,                 -- 対象チーム（team ドメインへの ID 参照／クロスドメイン FK なし）
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_submission_target_req (requirement_id),
    CONSTRAINT fk_submission_target_req FOREIGN KEY (requirement_id)
      REFERENCES tournament_submission_requirement (id) ON DELETE CASCADE
);
```

- `form_template_id` / `team_id` / `tournament_id` / `division_id` は他ドメインへの **ID 参照のみ**（クロスドメイン FK なし／原則 1）。
- `organization_id` で主催組織に絞り込めるため、Repository は `AbstractTenantAwareRepository`（原則 7）の適用候補。
- 提出の実体・承認フローは F05.6 の `form_submissions` / `workflow_requests` をそのまま使い、**本書では新規の提出／承認テーブルを作らない**。

### 2.1 提出と requirement の連結（🔴 B-3 根治・実コード型確認済み）

> **当初設計の誤り**: 「`workflow_requests.source_type='TOURNAMENT_SUBMISSION'` ＋ `source_id`（requirement_id をポリモーフィック参照）で結ぶ」としていたが、これは **型不整合で成立しない**。
>
> **実コード確認結果**:
> - `workflow_requests.source_id` ＝ **`BIGINT UNSIGNED`**（`V5.031__create_workflow_requests_table.sql:15`）、`source_type` ＝ `VARCHAR(30)`（同 :14）。
> - `tournament_submission_requirement.id` ＝ **`BINARY(16)`（UUIDv7）**（§2 で新設）。
> - `form_submissions.id` ＝ `BIGINT UNSIGNED`、`workflow_request_id BIGINT UNSIGNED`、`template_id BIGINT UNSIGNED`、`scope_type VARCHAR(20)` / `scope_id BIGINT UNSIGNED`（`V5.038__create_form_submissions_table.sql:3,9,4,5,6`）。
> - `form_templates.id` ＝ `BIGINT UNSIGNED`（`V5.036:3`）。
>
> **UUID（BINARY(16)）の requirement_id を BIGINT 列 `workflow_requests.source_id` に格納することはできない**（数値型に 16 バイトの UUID は入らない）。
>
> **採用案（理由付き）**: workflow の native な source 連結（`source_type`/`source_id`）は **BIGINT id 同士でのみ**使う（例：workflow_request ↔ form_submission の BIGINT 連結＝F05.6 既存方式そのまま）。大会 requirement との対応は、**`form_submissions` 側に `tournament_submission_requirement_id BINARY(16) NULL`（ID 参照・FK なし／原則 1）列を追加**して持たせる。理由は、(a) UUID と BIGINT の型衝突を完全に回避できる、(b) F05.6 の workflow↔form_submission 連結を一切変更しないため母体への副作用がゼロ、(c) submission から requirement への逆引きが 1 カラムで済みインデックスも素直、の 3 点。
>
> ```sql
> -- 提出と大会提出枠の連結（form_submissions へ列追加。BIGINT PK の既存テーブルゆえ ID 方式不変）
> ALTER TABLE form_submissions
>   ADD COLUMN tournament_submission_requirement_id BINARY(16) NULL,   -- 大会提出枠への ID 参照（FK なし／原則 1）
>   ADD INDEX idx_form_submissions_tournament_req (tournament_submission_requirement_id);
> ```
>
> - `source_type='TOURNAMENT_SUBMISSION'`（21 字）は `VARCHAR(30)` に収まる。workflow_request 側に「この申請は大会提出由来」という種別ラベルを付けたい場合のみ `source_type` を使い、`source_id` には **form_submission の BIGINT id**（または 0/NULL）を入れる。requirement の UUID は `source_id` には決して入れない。
> - requirement → 提出群の集計は `form_submissions.tournament_submission_requirement_id = ?` で引く。提出状況ダッシュボード（§5）はこの列＋`form_submissions.status` で構成する。

---

## 3. 提出枠の定義（主催者）

1. 主催者（主催組織 ADMIN）が大会に **提出枠** を作成：使用する form_template（必要書類・フィールド・添付要否）＋締切＋対象（全チーム / 特定チーム）を指定。
2. form_template が未作成なら F05.6 のテンプレ作成 UI で作成し、その template_id を `tournament_submission_requirement.form_template_id` に紐付ける。
3. 締切（`deadline`）・対象（`target_scope`）・支払い条件（`requires_payment`、領域⑦連携）を設定。

---

## 4. 提出（各チーム代表）

1. 各チームの代表（チーム ADMIN/DEPUTY）が提出枠の form を開き、`form_submissions` に回答を記入。このとき `form_submissions.tournament_submission_requirement_id` に対象 requirement の UUID をセットする（§2.1）。
2. 添付（PDF 等）は F05.6 の `POST /workflow-requests/{id}/upload-url` → R2 presigned URL → `attachments` 登録の既存フロー（F05.5 ストレージ基盤）を流用。
3. 提出すると F05.6 既存方式どおり `workflow_requests` が起票され（workflow ↔ form_submission の連結は **BIGINT id 同士**でそのまま。大会由来を示すなら `source_type='TOURNAMENT_SUBMISSION'`＝VARCHAR(30) に収まる）、主催者の承認ステップへルーティングされる。**requirement との対応は `form_submissions.tournament_submission_requirement_id`（BINARY(16)）が担い、`workflow_requests.source_id`（BIGINT）には UUID を入れない**（§2.1 の B-3 根治）。
4. 差戻し時は F05.6 の差し戻し再提出フローをそのまま使う。

> **form_submission ↔ workflow_request の対応（Y-3 補強）**: F05.6 では 1 件の `form_submissions` に対し承認が要る場合に `form_submissions.workflow_request_id`（BIGINT・既存列）で 1:1 に紐付く。本機能の提出も同方式に乗り、`form_submissions` が (a) requirement（`tournament_submission_requirement_id`／UUID）と (b) 承認申請（`workflow_request_id`／BIGINT）の双方を**それぞれ型整合した別カラム**で参照するハブとなる。これにより UUID ドメイン（大会連結）と BIGINT ドメイン（workflow/forms 母体）が混線しない。

---

## 5. 受理・差戻し・状況ダッシュボード（主催者）

- 主催者は **提出状況ダッシュボード**（未提出 / 提出済 / 受理 / 差戻し）を閲覧。締切超過チームを可視化。
- 受理／差戻しは F05.6 の承認ステップ（並列／順次）で処理。受理＝承認完了、差戻し＝REJECT で再提出を促す。
- `requires_payment=TRUE` の提出枠は、**受理操作の前提条件**として領域⑦の支払い済み（`member_payments` PAID）を Service 層でチェックする（未払いなら受理をブロックし、その旨を提示。症状を隠さず根治／CLAUDE.md 障害対応の原則）。

### 5.1 API（新設・薄い連結のみ）

| メソッド | パス | 認可 | 説明 |
|---------|-----|------|------|
| POST | `/api/v1/tournaments/{tId}/submission-requirements` | **主催組織 ADMIN** | 提出枠を定義（form_template_id・締切・対象・requires_payment） |
| GET | `/api/v1/tournaments/{tId}/submission-requirements` | 参加チーム ADMIN/DEPUTY ＋ 主催組織 ADMIN | 提出枠一覧（自チームが対象の枠のみ／主催者は全件） |
| PATCH | `/api/v1/tournaments/{tId}/submission-requirements/{reqId}` | **主催組織 ADMIN** | 締切・対象・支払い条件の更新 |
| DELETE | `/api/v1/tournaments/{tId}/submission-requirements/{reqId}` | **主催組織 ADMIN** | 提出枠の soft delete |
| GET | `/api/v1/tournaments/{tId}/submission-requirements/{reqId}/status` | **主催組織 ADMIN** | 提出状況ダッシュボード（チーム別 未提出/提出済/受理/差戻し・締切超過フラグ） |
| POST | `/api/v1/tournaments/{tId}/submission-requirements/{reqId}/submit/me` | **自チーム ADMIN/DEPUTY** | 自チーム分の提出（F05.6 の form_submission＋workflow_request 起票へ委譲） |

- 提出・受理の実処理（form_submission 保存・workflow_request 承認）は **F05.6 の既存 API を内部委譲**で再利用し、本書の新設 API は「大会スコープのファサード（requirement とのひも付け）」に留める。

---

## 6. ファイル置き場④との役割差（明記）

| 観点 | 領域④（04_file_storage.md・共有ライブラリ） | 領域⑥（本書・提出インボックス） |
|------|---------------------------------------|------------------------------|
| 目的 | 主催者・参加チームが資料を**共有**（要項・規約・配布物） | 主催者が**提出枠を定義**し各チームが**提出**、主催者が**受理／差戻し** |
| 書込 | チーム代表＋主催者がフォルダにアップロード | チーム代表が form＋添付で提出（締切あり） |
| 一覧の出方 | フォルダ／ファイル一覧（ブラウズ） | 提出状況ダッシュボード（未提出/提出済/受理/差戻し） |
| 締切・承認 | なし（恒常的な共有） | あり（締切・承認ステップ・差戻し） |
| ストレージ | F05.5 / R2（`TOURNAMENT` / `TOURNAMENT_DIVISION` スコープ） | F05.5 / R2（workflow_request_attachments 経由・同じ R2 基盤） |

- 両者は同一 R2 ストレージ基盤（F05.5）を共有するが、**用途・UI・締切管理が異なる別機能**である。混同しないよう本表で線引きする。

---

## 7. 認可・セキュリティ

| 操作 | 許可ロール |
|------|-----------|
| 提出枠の定義／更新／削除／状況閲覧 | 主催組織 ADMIN / SYSTEM_ADMIN |
| 自チーム分の提出（submit/me） | **自チーム ADMIN/DEPUTY のみ**（対象チームに限る） |
| 提出枠一覧の閲覧 | 自チームが対象の枠＝当該チーム ADMIN/DEPUTY ／ 全件＝主催組織 ADMIN |
| 受理／差戻し | 主催組織 ADMIN（F05.6 承認ステップに準拠） |

- 自チーム以外の提出を操作できない（他チームの form_submission への INSERT/UPDATE は 403）。
- 存在しない requirement / tournament は **404**（IDOR 統一）。`form_template_id` / `team_id` 等は ID 参照のみ（クロスドメイン FK なし／原則 1）。
- 提出枠・提出の越境（提出枠の form_submission が大会スコープ外へ漏れない）を Service 層の帰属チェックで保証（`reqId → tournament_id → orgId`、`submission → reqId` 帰属）。
- `@Transactional` が tournament ドメインと workflow/forms ドメインをまたぐ箇所（submit ファサード）は越境 TODO を明記し、将来のイベント駆動化候補とする（原則 5）。
- **退会（O-4）**: `tournament_submission_requirement.created_by`（提出枠を作成した主催組織 ADMIN の user_id）は**履歴・証跡として保持**＝CLAUDE.md 退会二段モデルの**強匿名化対象外**（NULL 化しない）。表示名のみ既存の匿名化に追従させる。提出者（`form_submissions.submitted_by`）の扱いは F05.6 母体の方針に従う（本機能では変更しない）。

---

## 8. 精査ログ

### 8.1 1 回目
- **不備**: 提出枠定義・提出・受理／差戻し・状況ダッシュボード・締切・支払い条件連携（領域⑦）を網羅。汎用エンジンは F05.6 を再利用し、薄い連結テーブルのみ新設。
- **セキュリティ**: 提出＝自チーム ADMIN/DEPUTY のみ・受理＝主催組織 ADMIN・他チーム提出操作は 403・404 統一・クロスドメイン FK なし・越境 TODO 明記（原則 5）。`requires_payment` は受理前提条件としてブロック（症状を隠さない）。
- **ユーザビリティ**: form＋添付の既存 UI を流用し提出摩擦最小、提出状況ダッシュボードで未提出／締切超過を可視化。
- **見落とし**: ファイル置き場④との役割差を §6 で明記（混同防止）、F05.6 ポリモーフィック source_type／source_id 方式の踏襲、領域⑦支払いゲート連携。
- **保守性**: 提出／承認の汎用テーブルは新設せず F05.6 を再利用、新規は薄い連結 2 テーブル（UUIDv7／原則 6、子テーブルは同一ドメイン CASCADE／原則 2）。

### 8.2 2 回目（検分1周目の指摘反映＝B-3 根治）
- **型確認（実コード）**: `workflow_requests.source_id`＝`BIGINT UNSIGNED`／`source_type`＝`VARCHAR(30)`（`V5.031:14-15`）、`tournament_submission_requirement.id`＝`BINARY(16)`、`form_submissions.id`／`workflow_request_id`／`template_id`／`scope_id`＝`BIGINT UNSIGNED`（`V5.038`）、`form_templates.id`＝`BIGINT UNSIGNED`（`V5.036:3`）。
- **訂正**: UUID requirement_id を BIGINT `source_id` へ格納する当初案を撤回し、**`form_submissions` に `tournament_submission_requirement_id BINARY(16) NULL`（ID 参照・FK なし）を追加**して連結（§2.1）。workflow の native 連結は BIGINT id 同士のままで母体無改変。`source_type='TOURNAMENT_SUBMISSION'`（21 字）は `VARCHAR(30)` に収まる旨を明記。
- **O-4**: `created_by` は証跡保持＝強匿名化対象外（表示名のみ匿名化追従）。

### 8.3 未解決事項

**現時点でなし。**
