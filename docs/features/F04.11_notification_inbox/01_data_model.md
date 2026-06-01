# F04.11: DB設計 / データモデル

> **ステータス**: 🟢 設計確定（完了・未解決事項ゼロ）
> **最終更新**: 2026-05-30
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・A/B 案比較
> - [02_api_design.md](./02_api_design.md) — API 仕様
> - [03_business_logic.md](./03_business_logic.md) — 集約ロジック・状態マージ
> - [F02.9_favorites_widget.md](../F02.9_favorites_widget.md) — `UserFavoriteEntity`（ポリモーフィック per-user 表の手本）
> - [F02.5_action_memo.md](../F02.5_action_memo.md) — `ActionMemoTagEntity` / `ActionMemoTagLinkEntity`（タグ＋多対多の手本）
> - [CLAUDE.md](../../../CLAUDE.md) — DB 設計原則 1〜7

---

## 1. テーブル一覧

本機能で新規作成するのは **per-user の triage オーバーレイ 3 表のみ**。通知の本体（5 ソース）は既存テーブルを**読み取り流用**し、一切変更しない。

| テーブル名 | 主キー | 用途 | 論理削除 |
|-----------|-------|------|---------|
| `inbox_item_states` | `id`（UUIDv7 / BINARY(16)）| 通知 1 件に対する per-user の triage 状態（スヌーズ/アーカイブ）| なし（物理削除・遅延生成）|
| `notification_labels` | `id`（UUIDv7 / BINARY(16)）| ユーザー定義の軽量ラベル（要件別）| あり（`deleted_at`）|
| `inbox_label_links` | `id`（UUIDv7 / BINARY(16)）| ラベルと通知の多対多リンク | なし（本体削除で十分）|

### 既存テーブル（読み取り流用・変更なし）

| テーブル | ドメイン / フェーズ | 本機能での用途 |
|---------|------------------|--------------|
| `notifications` | F04.3 | コア通知ソース。`priority` / `is_read` / `snoozed_until` / `source_type` / `source_id` / `action_url` を読み取り |
| `announcement_feeds` ＋ `announcement_read_status` | social.announcement / F02.8 | お知らせソース。per-user 既読 join を読み取り |
| `mentions` | mention / F04.1 | メンションソース。`mentioned_user_id` / `target_type` / `target_id` / `is_read` |
| `confirmable_notification_recipients`（＋親 `confirmable_notifications`）| F04.9 | 確認必須通知ソース。`is_confirmed` / `excluded_at` / 親の priority・deadline |
| `todos` | F02.3 | TODO 期限ソース。`status` / `priority` / `due_date` / `scope_type` / `scope_id` |

> **アーキテクチャ注記**: 本機能は B 案（仮想インボックス）を採用するため、通知本体テーブルへの **カラム追加・行生成を行わない**。triage 状態（スヌーズ/アーカイブ/ラベル）のみを上記 3 表に保持する。緊急度（priority）・種類（sourceType）による「フォルダ」は **永続化せず導出**する（§3）。

---

## 2. テーブル定義

### 2.1 inbox_item_states（triage 状態オーバーレイ）

通知 1 件（`(source_type, source_id)` で論理参照）に対する、ユーザーごとのスヌーズ・アーカイブ状態を保持する。**遅延生成**：デフォルト（未スヌーズ・未アーカイブ）は行を作らず、スヌーズ/アーカイブ操作時に upsert、両方解除されたら物理削除する（行数最小化＝ADHD 要件「整理不要なものは持たない」）。手本は `UserFavoriteEntity`（ポリモーフィック `entity_type + entity_id`）。

```sql
CREATE TABLE inbox_item_states (
    id            BINARY(16)  NOT NULL COMMENT 'UUIDv7 主キー（原則6）',
    user_id       BIGINT      NOT NULL COMMENT 'users.id（FK制約なし。クロスドメインFK禁止原則1）',
    source_type   VARCHAR(30) NOT NULL COMMENT '通知ソース種別（NOTIFICATION/ANNOUNCEMENT/MENTION/CONFIRMABLE/TODO_DUE）',
    source_id     BIGINT      NOT NULL COMMENT '各ソーステーブルのPK（FK制約なし・論理参照）',
    snoozed_until DATETIME(6) NULL     COMMENT 'スヌーズ解除予定時刻。NULL=非スヌーズ。now超過で受信箱へ自動復帰',
    snooze_notified_at DATETIME(6) NULL COMMENT 'スヌーズ復帰push送信済み時刻。NULL=未送信（再スヌーズ時はNULLへリセット）',
    archived_at   DATETIME(6) NULL     COMMENT 'アーカイブ退避時刻。NULL=受信箱、非NULL=保管庫',
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_iis_user_source (user_id, source_type, source_id),
    INDEX idx_iis_user_snooze (user_id, snoozed_until),
    INDEX idx_iis_user_archived (user_id, archived_at),
    INDEX idx_iis_snooze_revival (snoozed_until, snooze_notified_at)  -- Phase3 ②: 横断復帰バッチ用
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知インボックス：per-userのスヌーズ/アーカイブ状態（遅延生成オーバーレイ）';
```

> **`snooze_notified_at`（Phase3 ② で追加・Flyway `V9.20260601160000`）**: スヌーズ復帰 push の送信済み時刻。
> 横断バッチ `InboxSnoozeRevivalBatchService` が `snoozed_until <= now AND archived_at IS NULL AND snooze_notified_at IS NULL`
> の行を拾って push を **1 度だけ**送り、この列に時刻を刻む（冪等の根拠）。再スヌーズ（`snoozed_until` 更新）時は
> `InboxTriageService.snooze` が NULL に戻し、新しい復帰期限到来時に再度 1 度だけ push できるようにする。
> 詳細は [03_business_logic.md §5](./03_business_logic.md)。

| カラム | 型 | NULL | デフォルト | 説明 |
|-------|----|------|-----------|------|
| `id` | BINARY(16) | NO | — | UUIDv7（`UuidV7Entity` 継承で自動採番）|
| `user_id` | BIGINT | NO | — | `users.id`（FK なし。整合性はアプリ層）|
| `source_type` | VARCHAR(30) | NO | — | ソース種別 enum（サービス層バリデーション）|
| `source_id` | BIGINT | NO | — | 各ソース PK（FK なし・論理参照）|
| `snoozed_until` | DATETIME(6) | YES | NULL | スヌーズ解除予定。集約時 `> now` で受信箱から除外 |
| `snooze_notified_at` | DATETIME(6) | YES | NULL | スヌーズ復帰 push 送信済み時刻（Phase3 ②）。NULL=未送信。再スヌーズで NULL へリセット |
| `archived_at` | DATETIME(6) | YES | NULL | 非 NULL で保管庫へ。NULL で受信箱 |
| `created_at` / `updated_at` | DATETIME(6) | NO | CURRENT_TIMESTAMP(6) | |

**インデックス設計**

| インデックス | カラム | 用途 |
|------------|-------|------|
| `PRIMARY` | `id` | 主キー |
| `uq_iis_user_source` | `(user_id, source_type, source_id)` | per-user 1 通知 1 行の一意制約。upsert キー・状態まとめ取りの結合キー |
| `idx_iis_user_snooze` | `(user_id, snoozed_until)` | スヌーズ中一覧・復帰判定 |
| `idx_iis_user_archived` | `(user_id, archived_at)` | 保管庫一覧 |
| `idx_iis_snooze_revival` | `(snoozed_until, snooze_notified_at)` | Phase3 ②：全ユーザー横断の復帰 push 対象抽出（`findDueForRevival`）|

### 2.2 notification_labels（軽量ラベル・マスタ）

ユーザー定義の「要件別」ラベル。手本は `ActionMemoTagEntity`（per-user タグ名前空間・論理削除）。色は F15.2/F15.3 のフォルダ規約（`#RRGGBB`）に合わせる。**上限 20 件/ユーザー**（`UserFavorite` の上限流儀）。

```sql
CREATE TABLE notification_labels (
    id          BINARY(16)  NOT NULL COMMENT 'UUIDv7 主キー（原則6）',
    user_id     BIGINT      NOT NULL COMMENT 'users.id（FK制約なし）',
    name        VARCHAR(50) NOT NULL COMMENT 'ラベル名（ユーザー内で重複不可・サービス層検証）',
    color       CHAR(7)     NULL     COMMENT '表示色 #RRGGBB（任意）',
    icon        VARCHAR(40) NULL     COMMENT 'PrimeIcons 名（任意。例 pi-tag）',
    sort_order  INT         NOT NULL DEFAULT 0 COMMENT '表示順（昇順）',
    deleted_at  DATETIME(6) NULL     COMMENT '論理削除（@SQLRestriction）',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_nl_user_sort (user_id, sort_order),
    UNIQUE KEY uq_nl_user_name (user_id, name, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知インボックス：ユーザー定義の軽量ラベル';
```

| カラム | 型 | NULL | デフォルト | 説明 |
|-------|----|------|-----------|------|
| `id` | BINARY(16) | NO | — | UUIDv7 |
| `user_id` | BIGINT | NO | — | 所有ユーザー（FK なし）|
| `name` | VARCHAR(50) | NO | — | ラベル名。`(user_id, name, deleted_at)` で一意 |
| `color` | CHAR(7) | YES | NULL | `#RRGGBB` |
| `icon` | VARCHAR(40) | YES | NULL | PrimeIcons 名 |
| `sort_order` | INT | NO | 0 | 表示順 |
| `deleted_at` | DATETIME(6) | YES | NULL | 論理削除 |

> **一意制約の注記**: `(user_id, name, deleted_at)` を UNIQUE にすることで、論理削除済みの同名ラベルと現役ラベルの共存を許容しつつ「現役の同名重複」を防ぐ（MySQL は NULL を重複扱いしないため、`deleted_at IS NULL` の行同士のみ一意判定される）。F15.3 の `default_uniq_key` 生成列パターンより単純で、論理削除前提の本要件に十分。

### 2.3 inbox_label_links（ラベル↔通知 多対多）

ラベルと通知（`(source_type, source_id)` で論理参照）の関連。手本は `ActionMemoTagLinkEntity`（リンクは論理削除なし）。**1 通知あたりラベル上限 10**（`ActionMemoTag` の 1 メモ 10 タグ流儀）。

```sql
CREATE TABLE inbox_label_links (
    id          BINARY(16)  NOT NULL COMMENT 'UUIDv7 主キー（原則6）',
    label_id    BINARY(16)  NOT NULL COMMENT 'notification_labels.id（同一inboxドメイン内）',
    user_id     BIGINT      NOT NULL COMMENT 'users.id（冗長保持・user絞り込み高速化／所有検証）',
    source_type VARCHAR(30) NOT NULL COMMENT '通知ソース種別',
    source_id   BIGINT      NOT NULL COMMENT '各ソースPK（論理参照）',
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ill_label_source (label_id, source_type, source_id),
    INDEX idx_ill_user_source (user_id, source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知インボックス：ラベルと通知の多対多リンク';
```

| カラム | 型 | NULL | デフォルト | 説明 |
|-------|----|------|-----------|------|
| `id` | BINARY(16) | NO | — | UUIDv7 |
| `label_id` | BINARY(16) | NO | — | `notification_labels.id`（同一 inbox ドメイン内・FK なし方針）|
| `user_id` | BIGINT | NO | — | 冗長保持。一覧時の user 絞り込み・所有検証に使用 |
| `source_type` / `source_id` | VARCHAR(30) / BIGINT | NO | — | 通知の論理参照 |

**インデックス**

| インデックス | カラム | 用途 |
|------------|-------|------|
| `uq_ill_label_source` | `(label_id, source_type, source_id)` | 同一ラベルの重複付与防止 |
| `idx_ill_user_source` | `(user_id, source_type, source_id)` | インボックス一覧時にラベルを `IN` でまとめ取り（N+1 回避）/ ラベル絞り込み |

> **`label_id` の FK 不採用**: `inbox_label_links` → `notification_labels` は同一 inbox ドメイン内であり FK 制約を張る選択肢もあるが、本プロジェクトは ID 参照の一貫性を優先し他テーブルと同様 FK を張らない方針。ラベル論理削除時のリンク整合はアプリ層（一覧時に `deleted_at IS NULL` のラベルのみ join）で担保する。

---

## 3. 統一 DTO `InboxItem` と自動分類（導出・非永続）

### 3.1 統一 DTO `InboxItem`

5 ソースを単一の表示モデルへ正規化する（永続化しない・API レスポンス DTO）。

| フィールド | 型 | 由来・正規化 |
|---|---|---|
| `id` | String | 複合論理 ID = `"{sourceType}:{sourceId}"`（例 `NOTIFICATION:123`）。フロント key・triage 操作のキー |
| `sourceType` | enum | `NOTIFICATION` / `ANNOUNCEMENT` / `MENTION` / `CONFIRMABLE` / `TODO_DUE`（=**自動種類**）|
| `sourceId` | Long | 各ソース PK |
| `title` | String | notifications.title / announcement.title_cache / mention.content_snippet / confirmable 親.title / todo.title |
| `excerpt` | String | body / excerpt_cache / content_snippet（150 字目安・サニタイズ済み）|
| `priority` | enum | **自動緊急度** `URGENT/HIGH/NORMAL/LOW`（§3.2 正規化表）|
| `scope` | {type,id,name} | scope_type/scope_id ＋ 名称解決（`DashboardService` 注入の NameResolver 流用）|
| `actionUrl` | String | notifications.action_url / 他は導出（`/announcements/{id}` 等）|
| `occurredAt` | DateTime | created_at（TODO は due_date を基準キーに採用）|
| `state` | enum | `UNREAD/READ/SNOOZED/ARCHIVED`（オーバーレイ＋ソース既読のマージ結果。[03](./03_business_logic.md) §4）|
| `snoozedUntil` | DateTime? | オーバーレイ優先・無ければ `notifications.snoozed_until`（[03](./03_business_logic.md) §6）|
| `labels` | LabelDto[] | `inbox_label_links` から解決（id/name/color/icon）|

### 3.2 自動緊急度（priority）正規化表

各ソースの優先度を単一 `InboxPriority{URGENT, HIGH, NORMAL, LOW}` に写像する純粋関数 `InboxPriorityNormalizer`。

| ソース | 元の値 | → InboxPriority |
|--------|--------|----------------|
| NOTIFICATION | `URGENT/HIGH/NORMAL/LOW` | そのまま写像 |
| ANNOUNCEMENT | `URGENT` / `IMPORTANT` / `NORMAL` | `URGENT` / `HIGH` / `NORMAL` |
| MENTION | （優先度概念なし）| `HIGH` 固定（本人宛て直接言及）|
| CONFIRMABLE | 親 `NORMAL/HIGH/URGENT` | 写像。ただし **未確認かつ締切 24h 以内は `URGENT` に昇格** |
| TODO_DUE | due_date 基準（動的）| 期限切れ=`URGENT` / 当日=`HIGH` / 3 日内=`NORMAL` / それ以遠は対象外 |

### 3.3 自動種類（sourceType）グルーピング

`sourceType` をそのままタブ/チップに用いる。**永続テーブルを持たない**（クエリ結果を group-by するだけ）。これが要件「自動振り分け・手作業ゼロ」の核。手動ラベル（§2.2/§2.3）のみが実体を持つ。

---

## 4. 設計原則への準拠（CLAUDE.md アーキテクチャ思想 対照表）

| 原則 | 内容 | 本設計での準拠 |
|------|------|--------------|
| **原則 1** | クロスドメイン FK を作らない | 3 表とも `user_id` / `source_id` / `label_id` に FK を**張らない**。整合性はサービス層 |
| **原則 2** | CASCADE DELETE は同一ドメイン内のみ | FK 自体を持たないため CASCADE なし。ソース削除時の連鎖は発生しない（孤児リンクは一覧時にフィルタ・§後述）|
| **原則 3** | コアエンティティは論理削除 | 3 表はコアではない。`notification_labels` のみ論理削除（再表示の可能性配慮）、状態/リンクは物理削除で情報損失なし |
| **原則 4** | 退会時は匿名化（削除しない）| 3 表は PII を含まない**個人設定/状態**。退会時は**即時物理削除**（CLAUDE.md §13.12 弱匿名化区分＝再設定可能データ。[04](./04_security_operations.md) §3）|
| **原則 5** | `@Transactional` はドメイン内に閉じる | triage/ラベルの書き込みは inbox ドメイン内で完結。ソース読み取りは各ドメイン Repository を**読み取りのみ**で呼ぶ（書き込み越境なし。[03](./03_business_logic.md) §2）|
| **原則 6** | 新規テーブルの主キーは UUIDv7 | 3 表とも `id BINARY(16)`、`UuidV7Entity` 継承。ユーザーごとに行が増える＝シャーディング対象 |
| **原則 7** | テナント Repo は `AbstractTenantAwareRepository` | 3 表は `organization_id` を**持たない** user_id 単位の個人データのため**不適用**。`AbstractUserOwnedRepository` 系（全クエリに `user_id` 必須）を用い IDOR を防ぐ |

> **原則 7 不適用の判断記録**: 通知インボックスは「ユーザー個人の受信箱」であり組織テナント単位ではない。`organization_id` を持たず、全 Repository メソッドで `user_id` を必須条件とする（[02](./02_api_design.md) §4・[04](./04_security_operations.md) §1）。

---

## 5. ER図（テキスト形式）

```
users（既存・コア）
  id BIGINT PK
    │ アプリ層のみで整合性保証（FK制約なし）
    ├──────────────► inbox_item_states（新規・UUIDv7）
    │                   user_id BIGINT
    │                   (source_type, source_id) ── 論理参照 ─┐
    │                   snoozed_until / archived_at           │
    │                                                         │
    ├──────────────► notification_labels（新規・UUIDv7・論理削除）
    │                   user_id BIGINT / name / color / icon  │
    │                       ▲                                 │
    │                       │ label_id（同一inboxドメイン）    │
    └──────────────► inbox_label_links（新規・UUIDv7）         │
                        user_id / label_id ──────────────────┘
                        (source_type, source_id) ── 論理参照 ─┐
                                                              │
   (source_type, source_id) が指す通知本体（既存・読み取りのみ・FK制約なし）:
     NOTIFICATION → notifications.id                 （F04.3）
     ANNOUNCEMENT → announcement_feeds.id            （social.announcement）
     MENTION      → mentions.id                      （F04.1 mention）
     CONFIRMABLE  → confirmable_notification_recipients.id（F04.9）
     TODO_DUE     → todos.id                         （F02.3）
```

---

## 6. Flyway マイグレーション方針

**ファイル名（仮）**: `V**.***__create_inbox_overlay_tables.sql`（3 表を 1 マイグレーションに）

> **【重要】番号は実装時（マージ時）に確定する**。Flyway 番号は「マージ時」に確定し並行 PR と衝突する（`feedback_migration_version_collision`）。実装着手時に **`origin/main` の最新番号を再確認**し最大番号 +1 にリネームすること。
> 2026-05-30 時点の最新は **`V9.180`** 前後（F22.1 の記録参照）。実装時にこれより大きい未使用番号を採番する。from-scratch 番人テストが番号重複を検知する。

```sql
-- =============================================================
-- F04.11: 統合通知インボックス
--   per-user triage オーバーレイ 3 表（状態 / ラベル / リンク）
-- =============================================================
CREATE TABLE inbox_item_states (
    id BINARY(16) NOT NULL, user_id BIGINT NOT NULL,
    source_type VARCHAR(30) NOT NULL, source_id BIGINT NOT NULL,
    snoozed_until DATETIME(6) NULL, archived_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_iis_user_source (user_id, source_type, source_id),
    INDEX idx_iis_user_snooze (user_id, snoozed_until),
    INDEX idx_iis_user_archived (user_id, archived_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification_labels (
    id BINARY(16) NOT NULL, user_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL, color CHAR(7) NULL, icon VARCHAR(40) NULL,
    sort_order INT NOT NULL DEFAULT 0, deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_nl_user_sort (user_id, sort_order),
    UNIQUE KEY uq_nl_user_name (user_id, name, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inbox_label_links (
    id BINARY(16) NOT NULL, label_id BINARY(16) NOT NULL, user_id BIGINT NOT NULL,
    source_type VARCHAR(30) NOT NULL, source_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_ill_label_source (label_id, source_type, source_id),
    INDEX idx_ill_user_source (user_id, source_type, source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- シードデータなし（ユーザー操作で増える）。
- ロールバック方針: `DROP TABLE inbox_label_links, inbox_item_states, notification_labels`（個人状態のみで安全）。

---

## 7. 設計判断記録

| # | 論点 | 判断 | 理由 |
|---|------|------|------|
| 1 | 通知本体を実体化するか | **しない（B 案）**| 発生源不可侵・回帰リスク最小・お知らせ fan-out 回避（[README](./README.md) §4）|
| 2 | triage 状態の保持先 | オーバーレイ表 `inbox_item_states` | 通知本体を変更しないため別表。手本 `UserFavoriteEntity` |
| 3 | デフォルト状態の行 | **作らない（遅延生成）**| 「整理不要なものは持たない」。両カラム NULL 化で物理削除し行数最小 |
| 4 | 緊急度/種類フォルダ | **永続化しない（導出）**| priority/sourceType から完全導出可。手作業ゼロ要件の核 |
| 5 | ラベル主キー | UUIDv7（BINARY(16)）| 原則 6。`ActionMemoTag` は BIGINT だが新規は UUIDv7 規約 |
| 6 | ラベル論理削除 | あり（`deleted_at`）| 誤削除リカバリ・履歴。リンクは物理削除（本体削除で十分）|
| 7 | ラベル一意制約 | `(user_id, name, deleted_at)` | 現役同名重複のみ防止し論理削除済み同名と共存（NULL 非重複の MySQL 仕様を利用）|
| 8 | FK 制約 | なし（全表）| 原則 1。`label_id` は同一ドメインだが一貫性のため不採用、アプリ層で整合 |
| 9 | `AbstractTenantAwareRepository` | 不適用 | `organization_id` を持たない user_id 単位。`AbstractUserOwnedRepository` 系で `user_id` 必須 |
| 10 | 上限 | ラベル 20/ユーザー・10/通知 | `UserFavorite`（20）・`ActionMemoTag`（10/メモ）の既存流儀を踏襲 |
| 11 | 孤児リンク/状態（ソース削除）| 一覧時にソース存在突合で除外。バッチ掃除は任意（Phase 3）| F02.2「削除済み key 残存」と同じ哲学。即時整合不要 |
