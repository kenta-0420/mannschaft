# F06: CMS（ブログ・お知らせ）+ 活動記録

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 6
> **最終更新**: 2026-03-11

---

## 1. 概要

チーム・組織が「外部公開用」と「メンバー限定用」のブログ記事やお知らせを作成・配信し、活動内容をカスタムフィールド付きで記録・統計する機能を提供する。ブログは SEO 対応の SSR ページとして公開でき、アクセス解析（F24: アクセス解析）と連携してページビューを計測する。活動記録はチームの活動履歴を体系的に蓄積し、参加者・スコア・評価等を業種に応じた項目で柔軟に記録する。

---

## 2. スコープ

### 対象ロール

#### CMS（ブログ・お知らせ）
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全組織・チームのブログ記事を参照・強制削除 |
| ADMIN | 記事の作成・編集・公開/非公開・削除、タグ管理、お知らせ配信、MEMBER 投稿の承認・却下 |
| DEPUTY_ADMIN | `MANAGE_CONTENT` 権限を持つ場合: 記事の作成・編集・公開/非公開、MEMBER 投稿の承認・却下 |
| MEMBER | メンバー限定記事の閲覧。`WRITE_BLOG` 権限を持つ場合: BLOG 記事の作成・自分の記事の編集・削除（ANNOUNCEMENT は不可）。公開には ADMIN/DEPUTY_ADMIN の承認が必要（チーム設定で承認不要モードも可） |
| SUPPORTER | サポーター向け公開記事の閲覧 |
| GUEST | 外部公開記事の閲覧 |

#### 活動記録
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全組織・チームの活動記録を参照 |
| ADMIN | 活動記録の作成・編集・削除、カスタムフィールド定義、参加者管理、統計閲覧 |
| DEPUTY_ADMIN | `MANAGE_CONTENT` 権限を持つ場合: 活動記録の作成・編集・参加者管理 |
| MEMBER | 活動記録の閲覧、自分が参加した記録の確認 |
| SUPPORTER | 活動記録の閲覧（公開設定されたもの） |
| GUEST | 活動記録の閲覧（公開設定されたもの） |

### 対象レベル
- [x] 組織 (Organization)
- [x] チーム (Team)
- [ ] 個人 (Personal)

---

## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 論理削除 |
|-----------|------|---------|
| `blog_posts` | ブログ記事・お知らせ本体 | あり |
| `blog_tags` | タグマスター | なし |
| `blog_post_tags` | 記事とタグの中間テーブル | なし |
| `blog_post_revisions` | 記事のリビジョン（版管理） | なし |
| `blog_post_series` | 連載シリーズ定義 | なし |
| `blog_image_uploads` | ブログ画像管理（S3 アップロード追跡・孤立画像クリーンアップ用） | なし |
| `activity_results` | 活動記録本体 | あり |
| `activity_participants` | 活動記録の参加者 | なし |
| `activity_custom_fields` | 活動記録のカスタムフィールド定義（活動レベル / 参加者レベル） | なし |
| `activity_custom_values` | 活動レベルのカスタムフィールド値 | なし |
| `activity_participant_values` | 参加者レベルのカスタムフィールド値 | なし |
| `activity_templates` | 活動記録テンプレート（公式 / チーム・組織 / 共有） | あり |
| `activity_template_fields` | テンプレートに含まれるフィールド定義 | なし |

### テーブル定義

#### `blog_posts`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams（チーム単位; NULL = 組織単位） |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations（組織単位; NULL = チーム単位） |
| `author_id` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete） |
| `title` | VARCHAR(200) | NO | — | 記事タイトル |
| `slug` | VARCHAR(200) | NO | — | URL スラッグ（SEO 用。スコープ内で一意） |
| `body` | TEXT | NO | — | 記事本文（Markdown 形式。フロントエンドで `markdown-it` + `DOMPurify` で HTML レンダリング） |
| `excerpt` | VARCHAR(500) | YES | NULL | 要約・抜粋（一覧表示・OGP 用） |
| `cover_image_url` | VARCHAR(500) | YES | NULL | カバー画像 URL（S3） |
| `post_type` | ENUM('BLOG', 'ANNOUNCEMENT') | NO | 'BLOG' | 記事種別（ブログ / お知らせ） |
| `visibility` | ENUM('PUBLIC', 'MEMBERS_ONLY', 'SUPPORTERS_AND_ABOVE') | NO | 'MEMBERS_ONLY' | 公開範囲 |
| `priority` | ENUM('NORMAL', 'IMPORTANT', 'CRITICAL') | NO | 'NORMAL' | お知らせの重要度（BLOG では常に NORMAL） |
| `status` | ENUM('DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'ARCHIVED') | NO | 'DRAFT' | 公開ステータス |
| `published_at` | DATETIME | YES | NULL | 公開日時（予約公開対応。NULL = 即時公開） |
| `archive_at` | DATETIME | YES | NULL | 予約アーカイブ日時（期限付き公開。NULL = 手動アーカイブのみ） |
| `pinned` | BOOLEAN | NO | FALSE | ピン留め（一覧の先頭に固定） |
| `allow_comments` | BOOLEAN | NO | FALSE | コメント許可（将来拡張用。Phase 6 ではコメント機能未実装のため FALSE 固定） |
| `target_type` | ENUM('ALL', 'ROLE', 'GROUP') | NO | 'ALL' | お知らせ配信先種別（Phase 6 では ALL のみ使用。ROLE/GROUP は将来拡張用） |
| `target_id` | BIGINT UNSIGNED | YES | NULL | 配信先 ID（target_type = ROLE: role_id、GROUP: group_id。ALL の場合は NULL） |
| `cross_post_to_timeline` | BOOLEAN | NO | FALSE | 公開時に著者の個人タイムラインにリンク投稿を自動作成 |
| `timeline_post_id` | BIGINT UNSIGNED | YES | NULL | FK → timeline_posts（クロスポスト先。NULL = 未クロスポスト） |
| `rejection_reason` | VARCHAR(500) | YES | NULL | 却下理由（REJECTED 時に ADMIN/DEPUTY_ADMIN が記入） |
| `view_count` | INT UNSIGNED | NO | 0 | 閲覧数（denormalize。page_view_daily_stats との整合はバッチで補正） |
| `reading_time_minutes` | SMALLINT UNSIGNED | NO | 0 | 推定読了時間（分）。保存時にバックエンド算出（日本語: 500文字/分） |
| `version` | INT UNSIGNED | NO | 1 | 楽観的ロック用バージョン番号。更新時に `WHERE version = :expected` で検証し、不一致は 409 Conflict |
| `preview_token` | VARCHAR(64) | YES | NULL | 下書きプレビュー共有トークン（発行時に SecureRandom で生成。NULL = 未発行） |
| `preview_token_expires_at` | DATETIME | YES | NULL | プレビュートークン有効期限（発行から24時間） |
| `series_id` | BIGINT UNSIGNED | YES | NULL | FK → blog_post_series（連載シリーズ。NULL = 単独記事） |
| `series_order` | SMALLINT UNSIGNED | YES | NULL | シリーズ内の順番（1始まり。series_id が非 NULL の場合のみ） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**インデックス**
```sql
-- MySQL の NULL != NULL 特性を利用した論理削除対応 UNIQUE 制約。
-- deleted_at IS NULL 同士のみ一意性を保証。論理削除済み（deleted_at IS NOT NULL）は
-- 同一 slug が複数共存するが、各 deleted_at 値が異なるため UNIQUE 違反にならない。
UNIQUE KEY uq_bp_slug_team (team_id, slug, deleted_at)      -- チーム内スラッグ一意性
UNIQUE KEY uq_bp_slug_org (organization_id, slug, deleted_at) -- 組織内スラッグ一意性
INDEX idx_bp_team_status (team_id, status, published_at)      -- チーム別記事一覧取得
INDEX idx_bp_org_status (organization_id, status, published_at) -- 組織別記事一覧取得
INDEX idx_bp_author (author_id)                                -- 著者別記事一覧
INDEX idx_bp_post_type (post_type, status, published_at)       -- 種別フィルタ
FULLTEXT INDEX ft_bp_title_body (title, body) WITH PARSER ngram  -- タイトル＋本文の複合全文検索（MySQL ngram。日本語対応）
INDEX idx_bp_timeline_post (timeline_post_id)                   -- クロスポスト先逆引き
INDEX idx_bp_series (series_id, series_order)                    -- シリーズ内記事順序
```

**制約・備考**
- `team_id` と `organization_id` はどちらか一方のみ非 NULL（XOR）:
  ```sql
  CONSTRAINT chk_bp_scope
    CHECK (
      (team_id IS NOT NULL AND organization_id IS NULL)
      OR (team_id IS NULL AND organization_id IS NOT NULL)
    )
  ```
- `slug` は URL セーフな文字列（`[a-z0-9-]`）のみ許可。アプリケーション層でバリデーション
- **slug UNIQUE 制約の設計意図**: `UNIQUE (team_id, slug, deleted_at)` は MySQL の `NULL != NULL` 特性を利用した論理削除対応パターン。未削除（`deleted_at IS NULL`）の同一 slug は1件のみ保証され、削除済みレコードは `deleted_at` の値が異なるため複数共存する（意図通り）。slug 作成時に 409 Conflict を返せば十分であり、追加のアプリ層チェックは不要
- `published_at` が未来日時の場合は予約公開。バッチまたはスケジューラで `published_at <= NOW()` かつ `status = 'DRAFT'` の記事を `PUBLISHED` に遷移させる
- `archive_at` が設定されている場合、スケジューラで `archive_at <= NOW()` かつ `status = 'PUBLISHED'` の記事を `ARCHIVED` に自動遷移。期限付きキャンペーンやお知らせに使用
- **楽観的ロック**: `version` カラムで同時編集を検知。`PUT /blog/posts/{id}` および `PATCH /blog/posts/{id}/auto-save` でリクエストに `version` を含め、DB の値と一致しない場合は 409 Conflict を返却。フロントエンドはリロードを促す
- **プレビュー共有**: `preview_token` が発行されている場合、`GET /blog/posts/{slug}?preview_token={token}` で DRAFT/PENDING_REVIEW 状態の記事を認証不要で閲覧可能。有効期限（24時間）超過時は 410 Gone
- `ANNOUNCEMENT` の `priority` が `IMPORTANT` / `CRITICAL` の場合、プッシュ通知を自動送信する
- 論理削除: `deleted_at DATETIME nullable`（SoftDeletableEntity 適用）
- `view_count` は参考値。正確な集計は `page_view_daily_stats` を参照
- `cross_post_to_timeline`: チーム/組織の `allow_blog_timeline_share` 設定が有効な場合のみ `TRUE` を設定可能。記事が PUBLISHED に遷移した時点で `timeline_posts` にリンク投稿を自動生成し、`timeline_post_id` に保存する
- `timeline_post_id` の FK は SET NULL on delete（タイムライン投稿が削除されてもブログ記事は残る）
- **MEMBER 承認ワークフロー**: `WRITE_BLOG` 権限を持つ MEMBER が作成した記事は `DRAFT` → `PENDING_REVIEW`（レビュー提出）→ ADMIN/DEPUTY_ADMIN が `PUBLISHED`（承認）or `REJECTED`（却下）。チーム設定で `blog_member_auto_publish = true` の場合、MEMBER も DRAFT → PUBLISHED を直接遷移可能（承認不要モード）
- `REJECTED` 記事は著者に通知され、`rejection_reason` とともに DRAFT に戻して再編集可能
- **お知らせ配信先**: Phase 6 では `target_type = 'ALL'` 固定（全メンバー配信）。`ROLE`/`GROUP` による絞り込みは将来フェーズで実装。`target_type = 'ALL'` の場合 `target_id` は NULL。バックエンドは `target_type != 'ALL'` のリクエストを 400 で拒否（Phase 6）
- **slug 自動生成**: `slug` 未指定時は `title` から自動生成。日本語タイトルは `nanoid`（12文字英数字）をベースに生成。重複時はサフィックス `-2`, `-3` を自動付与。ユーザーが手動指定した場合はそちらを優先
- **body サイズ制限**: `body` の最大文字数は **50,000文字**。超過時は 400 エラー。画像は S3 URL のため body に含まれるのは Markdown テキストのみ
- **reading_time_minutes**: `body` の保存時にバックエンドで算出。日本語文字数 ÷ 500（切り上げ）。最小1分
- **series_id**: 連載記事の紐付け用。`blog_post_series.id` への FK（SET NULL on delete）。同一シリーズ内で `series_order` は一意（アプリ層で検証）

#### `blog_tags`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations |
| `name` | VARCHAR(50) | NO | — | タグ名 |
| `color` | VARCHAR(7) | NO | '#6B7280' | タグの表示色（HEX。例: #EF4444）。フロントエンドでバッジ色として使用 |
| `post_count` | INT UNSIGNED | NO | 0 | 紐付き記事数（denormalize。BLOG + ANNOUNCEMENT 合算） |
| `sort_order` | INT | NO | 0 | 表示順（管理画面での並び替え用） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_bt_name_team (team_id, name)          -- チーム内タグ名一意性
UNIQUE KEY uq_bt_name_org (organization_id, name)    -- 組織内タグ名一意性
INDEX idx_bt_team_order (team_id, sort_order)         -- チーム別タグ一覧（表示順）
INDEX idx_bt_org_order (organization_id, sort_order)  -- 組織別タグ一覧（表示順）
```

**制約・備考**
- `team_id` と `organization_id` は XOR（`blog_posts` と同様の CHECK 制約）
- **タグは BLOG・ANNOUNCEMENT 共用**。`post_type` に関わらず同じタグプールから選択する。例: 「大会」タグを BLOG 記事にも ANNOUNCEMENT にも付けられる
- `post_count` は `blog_post_tags` の INSERT/DELETE 時にアトミック更新。論理削除された記事は減算する
- タグは物理削除。タグ削除時は `blog_post_tags` もカスケード削除
- `color` はフロントエンドのタグバッジ色。プリセット8色から選択（アプリケーション層でバリデーション）
- **1記事あたりのタグ数上限: 10個**。アプリケーション層でバリデーション。過剰なタグ付けによる AND フィルタクエリの性能劣化を防止

#### `blog_post_tags`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `blog_post_id` | BIGINT UNSIGNED | NO | — | FK → blog_posts（CASCADE） |
| `blog_tag_id` | BIGINT UNSIGNED | NO | — | FK → blog_tags（CASCADE） |

**インデックス**
```sql
PRIMARY KEY (blog_post_id, blog_tag_id)
INDEX idx_bpt_tag (blog_tag_id)   -- タグ別記事一覧取得
```

#### `blog_post_revisions`

記事の版管理。PUBLISHED 状態の記事を再編集した際、または手動「版を保存」操作時にスナップショットを作成する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `blog_post_id` | BIGINT UNSIGNED | NO | — | FK → blog_posts（ON DELETE CASCADE） |
| `revision_number` | INT UNSIGNED | NO | — | 版番号（1始まり、blog_post_id 内で連番） |
| `title` | VARCHAR(200) | NO | — | その時点のタイトル |
| `body` | TEXT | NO | — | その時点の本文 |
| `editor_id` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete。編集者） |
| `change_summary` | VARCHAR(200) | YES | NULL | 変更概要（手動入力。NULL = 自動保存） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_bpr_post_revision (blog_post_id, revision_number)  -- 記事×版番号で一意
INDEX idx_bpr_post_date (blog_post_id, created_at DESC)          -- 版履歴一覧（新しい順）
```

**制約・備考**
- 記事削除時にカスケード削除
- **保存タイミング**: ① PUBLISHED 記事を再編集して保存した時点で、編集前の状態を自動保存 ② エディタ内の「版を保存」ボタンで手動保存
- **上限10版**: 11版目の作成時に最も古い版を物理削除（FIFO）
- 復元操作: リビジョンの `title` / `body` を現在の記事にコピーする（新しい版として扱う）

#### `blog_post_series`

連載記事のシリーズ定義。同じシリーズに属する記事は閲覧画面で「前の記事」「次の記事」ナビゲーションが表示される。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations |
| `name` | VARCHAR(100) | NO | — | シリーズ名（例: Spring Boot 入門講座） |
| `description` | VARCHAR(500) | YES | NULL | シリーズの説明 |
| `created_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_bps_team (team_id)                -- チーム別シリーズ一覧
INDEX idx_bps_org (organization_id)          -- 組織別シリーズ一覧
```

**制約・備考**
- `team_id` と `organization_id` は XOR（CHECK 制約）
- シリーズ削除時は `blog_posts.series_id` が SET NULL になる（記事自体は残る）
- 1シリーズあたりの記事数上限は設けない

#### `blog_image_uploads`

ブログ記事に挿入された画像の管理テーブル。S3 にアップロードされた画像を追跡し、孤立画像のクリーンアップに使用する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `blog_post_id` | BIGINT UNSIGNED | YES | NULL | FK → blog_posts（SET NULL on delete。NULL = 未紐付け / アップロード中） |
| `uploader_id` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete） |
| `s3_key` | VARCHAR(500) | NO | — | S3 オブジェクトキー |
| `file_size` | INT UNSIGNED | NO | — | ファイルサイズ（bytes） |
| `content_type` | VARCHAR(50) | NO | — | MIME タイプ（image/jpeg, image/png 等） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
INDEX idx_biu_post (blog_post_id)                          -- 記事別画像一覧
INDEX idx_biu_orphan (blog_post_id, created_at)            -- 孤立画像クリーンアップ用
UNIQUE KEY uq_biu_s3_key (s3_key)                          -- S3 キー一意性
```

**制約・備考**
- 画像アップロード時に `blog_post_id = NULL` で INSERT。記事保存時に `body` 内の S3 URL を解析し、該当レコードの `blog_post_id` を更新
- **孤立画像クリーンアップ**: `blog_post_id IS NULL AND created_at < NOW() - INTERVAL 72 HOUR` のレコードを日次バッチで S3 削除 + 物理削除
- 記事編集で画像を本文から除去した場合、次回保存時に `blog_post_id` を NULL にリセット → 72時間後にクリーンアップ対象
- 1記事あたりの画像数上限: 30枚。アプリケーション層でバリデーション

#### `activity_results`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations |
| `template_id` | BIGINT UNSIGNED | YES | NULL | FK → activity_templates（SET NULL on delete。使用したテンプレート。NULL = テンプレート未使用） |
| `title` | VARCHAR(200) | NO | — | 活動タイトル（例: 第5回練習試合） |
| `description` | TEXT | YES | NULL | 活動内容の詳細 |
| `activity_date` | DATE | NO | — | 活動日 |
| `location` | VARCHAR(200) | YES | NULL | 活動場所 |
| `visibility` | ENUM('PUBLIC', 'MEMBERS_ONLY', 'SUPPORTERS_AND_ABOVE') | NO | 'MEMBERS_ONLY' | 公開範囲 |
| `cover_image_url` | VARCHAR(500) | YES | NULL | カバー画像 URL（S3） |
| `schedule_event_id` | BIGINT UNSIGNED | YES | NULL | FK → schedules（UNIQUE。スケジュールイベントから自動生成された場合に紐付け。NULL = 手動作成） |
| `participant_count` | INT UNSIGNED | NO | 0 | 参加者数（denormalize） |
| `view_count` | INT UNSIGNED | NO | 0 | 閲覧数（denormalize） |
| `created_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**インデックス**
```sql
INDEX idx_ar_team_date (team_id, activity_date DESC)     -- チーム別活動一覧（日付降順）
INDEX idx_ar_org_date (organization_id, activity_date DESC) -- 組織別活動一覧
INDEX idx_ar_created_by (created_by)                      -- 作成者別一覧
INDEX idx_ar_template (template_id)                       -- テンプレート別活動一覧
UNIQUE KEY uq_ar_schedule_event (schedule_event_id)       -- 1スケジュールイベントにつき1活動記録
```

**制約・備考**
- `team_id` と `organization_id` は XOR（CHECK 制約）
- `template_id` は活動記録がどのテンプレートから作成されたかを記録。テンプレート削除時は SET NULL（記録自体は残る）。`template_id` が非 NULL の場合、テンプレートの `use_count` をアトミック更新する
- `schedule_event_id` は ON DELETE SET NULL。スケジュール削除後も活動記録は残る。UNIQUE 制約により1イベント1記録を保証
- `participant_count` は `activity_participants` の INSERT/DELETE 時にアトミック更新
- 論理削除: `deleted_at DATETIME nullable`（SoftDeletableEntity 適用）

#### `activity_participants`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `activity_result_id` | BIGINT UNSIGNED | NO | — | FK → activity_results（CASCADE） |
| `user_id` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete。退会ユーザーも記録保持） |
| `display_name` | VARCHAR(100) | NO | — | 参加者表示名（登録時のスナップショット） |
| `participation_type` | ENUM('STARTER', 'SUBSTITUTE', 'BENCH', 'STAFF', 'OTHER') | NO | 'OTHER' | 出場区分（スタメン / 途中出場 / ベンチ入り / スタッフ / その他） |
| `minutes_played` | INT UNSIGNED | YES | NULL | 出場時間（分）。NULL = 時間管理なしの活動 |
| `note` | VARCHAR(500) | YES | NULL | 備考 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_ap_activity_user (activity_result_id, user_id)  -- 同一活動への重複参加防止
INDEX idx_ap_user (user_id)                                    -- ユーザー別参加履歴
INDEX idx_ap_type (activity_result_id, participation_type)     -- 出場区分別フィルタ
```

**制約・備考**
- `user_id` が NULL になった場合でも `display_name` で参加記録を保持
- `participation_type` と `minutes_played` は構造化カラム（全テンプレート共通で利用可能な基本項目）。テンプレート固有の追加データは `activity_participant_values` で管理
- `STARTER` / `SUBSTITUTE` はスポーツ系テンプレート向け。`STAFF`（スタッフ参加）や `OTHER`（汎用）は非スポーツ系で使用
- 物理削除（活動記録の論理削除時は残存。活動記録の物理削除時にカスケード削除）

#### `activity_custom_fields`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations |
| `scope` | ENUM('ACTIVITY', 'PARTICIPANT') | NO | 'ACTIVITY' | フィールドの適用スコープ |
| `field_name` | VARCHAR(100) | NO | — | フィールド名 |
| `field_type` | ENUM('TEXT', 'NUMBER', 'DATE', 'SELECT', 'CHECKBOX') | NO | — | データ型 |
| `options` | JSON | YES | NULL | SELECT 型の選択肢リスト（例: ["勝ち","負け","引き分け"]） |
| `unit` | VARCHAR(20) | YES | NULL | 単位（例: 分, 点, km）。NUMBER 型でグラフ軸ラベルに使用 |
| `is_required` | BOOLEAN | NO | FALSE | 必須フラグ |
| `sort_order` | INT | NO | 0 | 表示順 |
| `is_active` | BOOLEAN | NO | TRUE | FALSE = 新規入力時に非表示（既存値は保持） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_acf_id_scope (id, scope)                         -- 複合 FK 用（値テーブルから scope 込みで参照）
INDEX idx_acf_team_scope (team_id, scope, sort_order)          -- チーム別・スコープ別フィールド一覧
INDEX idx_acf_org_scope (organization_id, scope, sort_order)   -- 組織別・スコープ別フィールド一覧
```

**制約・備考**
- `team_id` と `organization_id` は XOR（CHECK 制約）
- **`scope` の使い分け**:
  - `ACTIVITY`: 活動全体に1つの値（例: 対戦相手、天気、試合結果）→ `activity_custom_values` に格納
  - `PARTICIPANT`: 参加者ごとに値が異なる（例: ポジション、得点、出場時間）→ `activity_participant_values` に格納
- `unit` は統計グラフの軸ラベルや表示に使用（例: 「出場時間（分）」「得点（点）」）
- フィールド定義は物理削除しない。`is_active = FALSE` で非表示にし、既存の値データを保護する

#### `activity_custom_values`

活動レベル（`scope = 'ACTIVITY'`）のフィールド値を格納する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `activity_result_id` | BIGINT UNSIGNED | NO | — | FK → activity_results（CASCADE） |
| `custom_field_id` | BIGINT UNSIGNED | NO | — | FK → activity_custom_fields（複合 FK で scope 込み参照） |
| `scope` | ENUM('ACTIVITY', 'PARTICIPANT') | NO | 'ACTIVITY' | スコープ（CHECK 制約で 'ACTIVITY' 固定。複合 FK の一部） |
| `value` | TEXT | YES | NULL | 値（型に関わらず文字列で格納。アプリ層で型変換） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_acv_result_field (activity_result_id, custom_field_id)  -- 1記録につき1フィールド1値
INDEX idx_acv_field (custom_field_id)                                  -- フィールド別集計用
```

**制約・備考**
- **scope の DB レベル強制**:
  ```sql
  CONSTRAINT chk_acv_scope CHECK (scope = 'ACTIVITY')
  CONSTRAINT fk_acv_field_scope
    FOREIGN KEY (custom_field_id, scope)
    REFERENCES activity_custom_fields (id, scope)
    ON DELETE RESTRICT ON UPDATE RESTRICT
  ```
  `scope` カラムは CHECK 制約で `'ACTIVITY'` 固定のため、複合 FK により `activity_custom_fields` の `scope = 'ACTIVITY'` のレコードのみ参照可能。PARTICIPANT スコープのフィールドを誤って紐付けることを DB レベルで完全に防止する
- `custom_field_id` の FK は RESTRICT（フィールド定義が使用中の場合は削除不可）
- NUMBER 型の値は `CAST(value AS DECIMAL)` で集計可能

#### `activity_participant_values`

参加者レベル（`scope = 'PARTICIPANT'`）のフィールド値を格納する。参加者 × フィールドの組み合わせで1レコード。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `participant_id` | BIGINT UNSIGNED | NO | — | FK → activity_participants（CASCADE） |
| `custom_field_id` | BIGINT UNSIGNED | NO | — | FK → activity_custom_fields（複合 FK で scope 込み参照） |
| `scope` | ENUM('ACTIVITY', 'PARTICIPANT') | NO | 'PARTICIPANT' | スコープ（CHECK 制約で 'PARTICIPANT' 固定。複合 FK の一部） |
| `value` | TEXT | YES | NULL | 値（型に関わらず文字列で格納。アプリ層で型変換） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_apv_participant_field (participant_id, custom_field_id)  -- 1参加者×1フィールドで1値
INDEX idx_apv_field (custom_field_id)                                   -- フィールド別集計用
INDEX idx_apv_field_value (custom_field_id, value(100))                 -- フィールド値検索（先頭100文字）
```

**制約・備考**
- **scope の DB レベル強制**:
  ```sql
  CONSTRAINT chk_apv_scope CHECK (scope = 'PARTICIPANT')
  CONSTRAINT fk_apv_field_scope
    FOREIGN KEY (custom_field_id, scope)
    REFERENCES activity_custom_fields (id, scope)
    ON DELETE RESTRICT ON UPDATE RESTRICT
  ```
  `scope` カラムは CHECK 制約で `'PARTICIPANT'` 固定のため、複合 FK により `activity_custom_fields` の `scope = 'PARTICIPANT'` のレコードのみ参照可能。ACTIVITY スコープのフィールドを誤って紐付けることを DB レベルで完全に防止する
- `participant_id` 経由で `activity_result_id` → `user_id` を辿れるため、「田中選手の全活動での得点」のような集計が可能:
  ```sql
  SELECT ap.user_id, SUM(CAST(apv.value AS DECIMAL)) AS total_goals
  FROM activity_participant_values apv
  JOIN activity_participants ap ON ap.id = apv.participant_id
  WHERE apv.custom_field_id = :goals_field_id
  GROUP BY ap.user_id
  ```
- NUMBER 型フィールドの集計: SUM / AVG / MIN / MAX を統計 API で自動提供
- SELECT 型フィールドの集計: 選択肢ごとの分布を統計 API で自動提供

#### `activity_templates`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `scope_type` | ENUM('SYSTEM', 'TEAM', 'ORGANIZATION') | NO | — | スコープ種別 |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams（TEAM スコープ時） |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations（ORGANIZATION スコープ時） |
| `name` | VARCHAR(100) | NO | — | テンプレート名（例: 練習試合、定例ボランティア） |
| `description` | VARCHAR(500) | YES | NULL | テンプレートの説明（公式テンプレートでは用途・業種の解説） |
| `icon` | VARCHAR(50) | YES | NULL | アイコン識別子（フロントエンドで表示。例: trophy, music, volunteer） |
| `default_title_pattern` | VARCHAR(200) | YES | NULL | タイトルパターン（例: `第{n}回 練習試合`。`{n}` は自動連番） |
| `default_visibility` | ENUM('PUBLIC', 'MEMBERS_ONLY', 'SUPPORTERS_AND_ABOVE') | NO | 'MEMBERS_ONLY' | デフォルト公開範囲 |
| `default_location` | VARCHAR(200) | YES | NULL | デフォルト活動場所 |
| `source_template_id` | BIGINT UNSIGNED | YES | NULL | FK → activity_templates（コピー元。NULL = オリジナル） |
| `share_code` | VARCHAR(20) | YES | NULL | 共有コード（8文字英数字。UNIQUE。NULL = 共有無効） |
| `is_shared` | BOOLEAN | NO | FALSE | 共有を有効にしているか |
| `is_official` | BOOLEAN | NO | FALSE | 公式テンプレートフラグ（SYSTEM スコープ時のみ TRUE） |
| `use_count` | INT UNSIGNED | NO | 0 | このテンプレートから作成された活動記録数（denormalize） |
| `import_count` | INT UNSIGNED | NO | 0 | 他チーム/組織にインポートされた回数（denormalize。公式・共有テンプレートのみ有意） |
| `created_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**インデックス**
```sql
INDEX idx_at_team (team_id, deleted_at)                -- チーム別テンプレート一覧
INDEX idx_at_org (organization_id, deleted_at)          -- 組織別テンプレート一覧
INDEX idx_at_system (scope_type, is_official, deleted_at) -- 公式テンプレート一覧
UNIQUE KEY uq_at_share_code (share_code)               -- 共有コード一意性
INDEX idx_at_source (source_template_id)               -- コピー元追跡
```

**制約・備考**
- `scope_type = 'SYSTEM'` の場合: `team_id` = NULL, `organization_id` = NULL, `is_official` = TRUE
- `scope_type = 'TEAM'` の場合: `team_id` 必須, `organization_id` = NULL
- `scope_type = 'ORGANIZATION'` の場合: `team_id` = NULL, `organization_id` 必須
  ```sql
  CONSTRAINT chk_at_scope
    CHECK (
      (scope_type = 'SYSTEM' AND team_id IS NULL AND organization_id IS NULL)
      OR (scope_type = 'TEAM' AND team_id IS NOT NULL AND organization_id IS NULL)
      OR (scope_type = 'ORGANIZATION' AND team_id IS NULL AND organization_id IS NOT NULL)
    )
  ```
- **チーム/組織あたり上限10個**（論理削除されていないもの）。アプリケーション層でバリデーション。公式テンプレートは上限対象外
- `share_code` は共有有効化時に自動生成（8文字英数字、ランダム）。共有無効化時に NULL にリセット
- `source_template_id` でコピー元を追跡。公式テンプレートからの派生数を集計可能
- `default_title_pattern` の `{n}` はアプリケーション層でそのチーム/組織内の同テンプレート使用回数 + 1 に置換
- 論理削除: `deleted_at DATETIME nullable`（SoftDeletableEntity 適用）
- `use_count` は活動記録作成時にアトミック更新

#### `activity_template_fields`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `template_id` | BIGINT UNSIGNED | NO | — | FK → activity_templates（CASCADE） |
| `scope` | ENUM('ACTIVITY', 'PARTICIPANT') | NO | 'ACTIVITY' | フィールドの適用スコープ |
| `field_name` | VARCHAR(100) | NO | — | フィールド名（例: 得点、出場時間） |
| `field_type` | ENUM('TEXT', 'NUMBER', 'DATE', 'SELECT', 'CHECKBOX') | NO | — | データ型 |
| `options` | JSON | YES | NULL | SELECT 型の選択肢リスト |
| `unit` | VARCHAR(20) | YES | NULL | 単位（例: 分, 点, km） |
| `is_required` | BOOLEAN | NO | FALSE | 必須フラグ |
| `default_value` | VARCHAR(500) | YES | NULL | デフォルト値（テンプレート適用時に初期入力される値） |
| `sort_order` | INT | NO | 0 | 表示順 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
INDEX idx_atf_template_scope (template_id, scope, sort_order)  -- テンプレート内スコープ別フィールド表示順
```

**制約・備考**
- テンプレート削除時にカスケード削除
- テンプレートのインポート（保存）時に、このテーブルのフィールド定義を `activity_custom_fields`（`scope` 含む）にコピーする
- 公式テンプレートのフィールド定義は SYSTEM_ADMIN のみ編集可能
- 1テンプレートあたりフィールド数上限: 活動レベル + 参加者レベル合わせて20個

#### チーム/組織ブログ設定（既存テーブル拡張）

以下の設定はチーム/組織の設定テーブル（Phase 1 で定義済み）にカラム追加、または `team_settings` / `organization_settings` の JSON 設定で管理する:

| 設定キー | 型 | デフォルト | 説明 |
|---------|---|-----------|------|
| `blog_member_auto_publish` | BOOLEAN | FALSE | TRUE = MEMBER の記事を承認不要で即時公開可能 |
| `allow_blog_timeline_share` | BOOLEAN | FALSE | TRUE = 記事公開時にタイムラインへのクロスポストを許可 |

### ER図（テキスト形式）

```
teams/organizations (1) ──── (N) blog_posts
blog_posts          (N) ──── (N) blog_tags       [via blog_post_tags]
blog_posts          (N) ──── (1) users (author)
blog_posts          (N) ──── (0..1) timeline_posts (cross-post link)
blog_posts          (1) ──── (N) blog_post_revisions
blog_post_series    (1) ──── (N) blog_posts (series_id)
blog_posts          (1) ──── (N) blog_image_uploads (S3 画像管理)

teams/organizations (1) ──── (N) activity_results
schedules           (1) ──── (0..1) activity_results (schedule_event_id: スケジュール連携)
activity_results    (1) ──── (N) activity_participants
activity_participants (N) ── (1) users

teams/organizations (1) ──── (N) activity_custom_fields (scope = ACTIVITY / PARTICIPANT)
activity_results    (1) ──── (N) activity_custom_values (活動レベル値)
activity_custom_values (N) ─ (1) activity_custom_fields (scope = ACTIVITY)

activity_participants      (1) ──── (N) activity_participant_values (参加者レベル値)
activity_participant_values (N) ─── (1) activity_custom_fields (scope = PARTICIPANT)

activity_templates       (1) ──── (N) activity_template_fields
activity_templates       (N) ──── (0..1) activity_templates (source_template_id: コピー元)
SYSTEM (scope_type)      (1) ──── (N) activity_templates (is_official = true: 公式)
teams/organizations      (1) ──── (N) activity_templates (scope_type = TEAM/ORGANIZATION)

page_view_logs / page_view_daily_stats ──── blog_posts (target_type = BLOG_POST)
page_view_logs / page_view_daily_stats ──── activity_results (target_type = ACTIVITY_RECORD)
```

---

## 4. API設計

### エンドポイント一覧

#### ブログ
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/blog/posts` | 条件付き | 記事一覧取得（PUBLIC は認証不要） |
| POST | `/api/v1/blog/posts` | 必要 | 記事作成 |
| GET | `/api/v1/blog/posts/{slug}` | 条件付き | 記事詳細取得（slug 指定） |
| PUT | `/api/v1/blog/posts/{id}` | 必要 | 記事更新 |
| DELETE | `/api/v1/blog/posts/{id}` | 必要 | 記事削除（論理削除） |
| PATCH | `/api/v1/blog/posts/{id}/publish` | 必要 | 公開ステータス変更 |
| GET | `/api/v1/blog/tags` | 条件付き | タグ一覧取得（記事数・種別内訳付き） |
| POST | `/api/v1/blog/tags` | 必要 | タグ作成 |
| PUT | `/api/v1/blog/tags/{id}` | 必要 | タグ更新（名前・色・表示順） |
| DELETE | `/api/v1/blog/tags/{id}` | 必要 | タグ削除 |
| POST | `/api/v1/blog/posts/{id}/duplicate` | 必要 | 記事の複製（DRAFT 状態でコピー） |
| GET | `/api/v1/blog/posts/{id}/revisions` | 必要 | リビジョン一覧取得 |
| POST | `/api/v1/blog/posts/{id}/revisions/{revisionId}/restore` | 必要 | 指定リビジョンに復元 |
| GET | `/api/v1/blog/series` | 必要 | シリーズ一覧取得 |
| POST | `/api/v1/blog/series` | 必要 | シリーズ作成 |
| PUT | `/api/v1/blog/series/{id}` | 必要 | シリーズ更新 |
| DELETE | `/api/v1/blog/series/{id}` | 必要 | シリーズ削除 |
| PATCH | `/api/v1/blog/posts/{id}/auto-save` | 必要 | 下書き自動保存（エディタ 30 秒間隔） |
| POST | `/api/v1/blog/posts/{id}/preview-token` | 必要 | プレビュー共有トークン発行（24時間有効） |
| DELETE | `/api/v1/blog/posts/{id}/preview-token` | 必要 | プレビュートークン無効化 |
| PATCH | `/api/v1/blog/posts/bulk` | 必要 | 一括ステータス変更（ARCHIVE / DELETE / PUBLISH） |
| GET | `/api/v1/blog/feed` | 不要 | RSS/Atom フィード取得（PUBLIC 記事のみ） |

#### 活動記録
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/activities` | 条件付き | 活動記録一覧取得 |
| POST | `/api/v1/activities` | 必要 | 活動記録作成 |
| GET | `/api/v1/activities/{id}` | 条件付き | 活動記録詳細取得 |
| PUT | `/api/v1/activities/{id}` | 必要 | 活動記録更新 |
| DELETE | `/api/v1/activities/{id}` | 必要 | 活動記録削除（論理削除） |
| POST | `/api/v1/activities/{id}/duplicate` | 必要 | 活動記録の複製（参加者・カスタム値なしでコピー） |
| POST | `/api/v1/activities/generate-from-schedule` | 必要 | スケジュールイベントから活動記録を自動生成 |
| POST | `/api/v1/activities/{id}/participants` | 必要 | 参加者追加 |
| DELETE | `/api/v1/activities/{id}/participants` | 必要 | 参加者削除 |
| GET | `/api/v1/activities/custom-fields` | 必要 | カスタムフィールド定義一覧 |
| POST | `/api/v1/activities/custom-fields` | 必要 | カスタムフィールド定義作成 |
| PUT | `/api/v1/activities/custom-fields/{id}` | 必要 | カスタムフィールド定義更新 |
| DELETE | `/api/v1/activities/custom-fields/{id}` | 必要 | カスタムフィールド定義無効化 |
| GET | `/api/v1/activities/stats` | 必要 | 活動全体の統計取得 |
| GET | `/api/v1/activities/stats/members/{userId}` | 必要 | メンバー個人の統計取得 |
| GET | `/api/v1/activities/stats/ranking` | 必要 | フィールド別ランキング取得 |
| GET | `/api/v1/activities/export` | 必要 | 活動記録 CSV エクスポート |

#### 活動記録テンプレート
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/activities/templates` | 必要 | チーム/組織のテンプレート一覧取得 |
| POST | `/api/v1/activities/templates` | 必要 | テンプレート作成 |
| GET | `/api/v1/activities/templates/{id}` | 必要 | テンプレート詳細取得（フィールド定義含む） |
| PUT | `/api/v1/activities/templates/{id}` | 必要 | テンプレート更新 |
| DELETE | `/api/v1/activities/templates/{id}` | 必要 | テンプレート削除（論理削除） |
| POST | `/api/v1/activities/templates/{id}/share` | 必要 | 共有コード発行・共有有効化 |
| DELETE | `/api/v1/activities/templates/{id}/share` | 必要 | 共有無効化 |
| GET | `/api/v1/activities/templates/official` | 必要 | 公式テンプレート一覧取得 |
| POST | `/api/v1/activities/templates/import` | 必要 | 共有コードまたは公式テンプレートからインポート |
| GET | `/api/v1/system-admin/activity-templates` | 必要 | 公式テンプレート管理一覧（SYSTEM_ADMIN） |
| POST | `/api/v1/system-admin/activity-templates` | 必要 | 公式テンプレート作成（SYSTEM_ADMIN） |
| PUT | `/api/v1/system-admin/activity-templates/{id}` | 必要 | 公式テンプレート更新（SYSTEM_ADMIN） |
| DELETE | `/api/v1/system-admin/activity-templates/{id}` | 必要 | 公式テンプレート削除（SYSTEM_ADMIN） |

### リクエスト／レスポンス仕様

#### `POST /api/v1/blog/posts`

**認可**: ADMIN、`MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN、または `WRITE_BLOG` 権限を持つ MEMBER

**リクエストボディ**
```json
{
  "team_id": 1,
  "title": "春の大会レポート",
  "slug": "spring-tournament-report-2026",
  "body": "## 大会結果\n\n素晴らしい成績を収めました...",
  "excerpt": "2026年春の大会結果をお届けします",
  "cover_image_url": "https://s3.example.com/blog/cover-123.jpg",
  "post_type": "BLOG",
  "visibility": "PUBLIC",
  "priority": "NORMAL",
  "tag_ids": [1, 3, 5],
  "published_at": null,
  "archive_at": null,
  "cross_post_to_timeline": true
}
```

**MEMBER 固有の制約**
- `post_type` は `BLOG` のみ指定可能（`ANNOUNCEMENT` は 403）
- `pinned` は設定不可（無視される）
- `cross_post_to_timeline` はチーム/組織の `allow_blog_timeline_share` が有効な場合のみ指定可能

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 42,
    "slug": "spring-tournament-report-2026",
    "status": "DRAFT"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（slug 形式不正、title 空等） |
| 403 | 権限不足、MEMBER が ANNOUNCEMENT を作成しようとした、クロスポスト非許可 |
| 409 | slug 重複 |

#### `GET /api/v1/blog/posts`

**認可**: 公開範囲に応じたアクセス制御
- `PUBLIC`: 認証不要
- `SUPPORTERS_AND_ABOVE`: SUPPORTER 以上
- `MEMBERS_ONLY`: MEMBER 以上

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | △ | チーム ID（team_id / organization_id のいずれか必須） |
| `organization_id` | Long | △ | 組織 ID |
| `post_type` | String | × | BLOG / ANNOUNCEMENT でフィルタ |
| `tag_ids` | String | × | タグ ID（カンマ区切りで複数指定可。例: `1,3,5`）。複数指定時は AND 条件（全タグを持つ記事のみ） |
| `status` | String | × | DRAFT / PENDING_REVIEW / PUBLISHED / REJECTED / ARCHIVED でフィルタ（ADMIN/DEPUTY_ADMIN のみ。MEMBER は自分の記事に限り DRAFT/PENDING_REVIEW/REJECTED も取得可） |
| `author_id` | Long | × | 著者 ID でフィルタ |
| `visibility` | String | × | 公開範囲フィルタ（ADMIN/DEPUTY_ADMIN のみ） |
| `q` | String | × | 全文検索キーワード（FULLTEXT） |
| `cursor` | Long | × | カーソル（前ページ最後の ID） |
| `limit` | Int | × | 取得件数（デフォルト: 20、最大: 50） |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 42,
      "title": "春の大会レポート",
      "slug": "spring-tournament-report-2026",
      "excerpt": "2026年春の大会結果をお届けします",
      "cover_image_url": "https://s3.example.com/blog/cover-123.jpg",
      "post_type": "BLOG",
      "visibility": "PUBLIC",
      "priority": "NORMAL",
      "status": "PUBLISHED",
      "published_at": "2026-03-10T10:00:00",
      "pinned": false,
      "view_count": 150,
      "author": {
        "id": 5,
        "display_name": "田中太郎"
      },
      "tags": [
        { "id": 1, "name": "大会" },
        { "id": 3, "name": "レポート" }
      ],
      "created_at": "2026-03-09T20:00:00"
    }
  ],
  "meta": {
    "next_cursor": 41,
    "has_next": true,
    "total_count": 85
  }
}
```

#### `GET /api/v1/blog/posts/{slug}`

**認可**: 記事の `visibility` に応じたアクセス制御

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 42,
    "title": "春の大会レポート",
    "slug": "spring-tournament-report-2026",
    "body": "## 大会結果\n\n素晴らしい成績を収めました...",
    "excerpt": "2026年春の大会結果をお届けします",
    "cover_image_url": "https://s3.example.com/blog/cover-123.jpg",
    "post_type": "BLOG",
    "visibility": "PUBLIC",
    "priority": "NORMAL",
    "status": "PUBLISHED",
    "published_at": "2026-03-10T10:00:00",
    "pinned": false,
    "allow_comments": true,
    "view_count": 151,
    "reading_time_minutes": 3,
    "author": {
      "id": 5,
      "display_name": "田中太郎"
    },
    "tags": [
      { "id": 1, "name": "大会" },
      { "id": 3, "name": "レポート" }
    ],
    "series": {
      "id": 2,
      "name": "Spring Boot 入門講座",
      "current_order": 3,
      "total_posts": 5,
      "prev": { "slug": "spring-boot-part-2", "title": "第2回: DI コンテナ" },
      "next": { "slug": "spring-boot-part-4", "title": "第4回: JPA 入門" }
    },
    "created_at": "2026-03-09T20:00:00",
    "updated_at": "2026-03-10T10:00:00"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 公開範囲外のユーザー |
| 404 | slug に該当する記事が存在しない、または論理削除済み |

#### `PATCH /api/v1/blog/posts/{id}/publish`

**認可**: ADMIN、`MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN、または `WRITE_BLOG` 権限を持つ MEMBER（自分の記事のみ、`PENDING_REVIEW` への遷移のみ可）

**リクエストボディ**
```json
{
  "status": "PUBLISHED",
  "published_at": "2026-03-15T09:00:00"
}
```

MEMBER がレビュー提出する場合:
```json
{
  "status": "PENDING_REVIEW"
}
```

ADMIN/DEPUTY_ADMIN が却下する場合:
```json
{
  "status": "REJECTED",
  "rejection_reason": "画像の著作権を確認してください"
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 42,
    "status": "PUBLISHED",
    "published_at": "2026-03-15T09:00:00"
  }
}
```

**ステータス遷移ルール**

ADMIN / DEPUTY_ADMIN:
- `DRAFT` → `PUBLISHED`: 公開（`published_at` が未来なら予約公開）
- `PUBLISHED` → `ARCHIVED`: アーカイブ（一覧から非表示、URL 直接アクセスは可）
- `ARCHIVED` → `PUBLISHED`: 再公開
- `PUBLISHED` → `DRAFT`: 非公開に戻す
- `DRAFT` → `ARCHIVED`: 不可（未公開記事はアーカイブできない）
- `PENDING_REVIEW` → `PUBLISHED`: 承認（MEMBER 投稿を公開）
- `PENDING_REVIEW` → `REJECTED`: 却下（`rejection_reason` 必須）

MEMBER（`WRITE_BLOG` 権限・自分の記事のみ）:
- `DRAFT` → `PENDING_REVIEW`: レビュー提出（承認不要モード時は `DRAFT` → `PUBLISHED` も可）
- `REJECTED` → `DRAFT`: 却下後に再編集へ戻す
- `DRAFT` → `PUBLISHED`: `blog_member_auto_publish = true` の場合のみ可

#### `GET /api/v1/blog/tags`

**認可**: 公開範囲に応じたアクセス制御（PUBLIC 記事にタグが付いていれば認証不要で取得可）

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | △ | チーム ID（team_id / organization_id のいずれか必須） |
| `organization_id` | Long | △ | 組織 ID |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "name": "大会",
      "color": "#EF4444",
      "sort_order": 0,
      "post_count": 12,
      "breakdown": {
        "blog": 8,
        "announcement": 4
      }
    },
    {
      "id": 2,
      "name": "お知らせ",
      "color": "#3B82F6",
      "sort_order": 1,
      "post_count": 7,
      "breakdown": {
        "blog": 0,
        "announcement": 7
      }
    },
    {
      "id": 3,
      "name": "レポート",
      "color": "#10B981",
      "sort_order": 2,
      "post_count": 5,
      "breakdown": {
        "blog": 5,
        "announcement": 0
      }
    }
  ]
}
```

**備考**
- `post_count` は PUBLISHED 状態の記事のみカウント（DRAFT / ARCHIVED は含まない）
- `breakdown` は `post_type` ごとの内訳。フロントエンドでタグフィルタ UI のカウント表示に使用
- 結果は `sort_order` 昇順で返却

#### `POST /api/v1/blog/tags`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

**リクエストボディ**
```json
{
  "team_id": 1,
  "name": "大会",
  "color": "#EF4444"
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 4,
    "name": "大会",
    "color": "#EF4444"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（name 空、color 形式不正） |
| 403 | 権限不足 |
| 409 | 同一スコープ内にタグ名重複 |

#### `PUT /api/v1/blog/tags/{id}`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

**リクエストボディ**
```json
{
  "name": "公式大会",
  "color": "#F59E0B",
  "sort_order": 0
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 1,
    "name": "公式大会",
    "color": "#F59E0B",
    "sort_order": 0
  }
}
```

#### `POST /api/v1/blog/posts/{id}/duplicate`

**認可**: ADMIN、`MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN、または `WRITE_BLOG` 権限を持つ MEMBER（自分の記事のみ）

既存記事を DRAFT 状態でコピーする。定例レポート等の同じ構成の記事を効率的に作成できる。

**コピー対象**: `title`（末尾に「（コピー）」付与）、`body`、`excerpt`、`cover_image_url`、`post_type`、`visibility`、`tag_ids`、`series_id`
**コピーしない**: `slug`（自動再生成）、`status`（DRAFT 固定）、`published_at`、`pinned`、`view_count`、`reading_time_minutes`（再算出）

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 55,
    "title": "春の大会レポート（コピー）",
    "slug": "spring-tournament-report-2026-copy-ab3k",
    "status": "DRAFT"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 権限不足 |
| 404 | コピー元記事が存在しない |

#### `GET /api/v1/blog/posts/{id}/revisions`

**認可**: ADMIN、`MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN、または記事の著者

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 3,
      "revision_number": 3,
      "title": "春の大会レポート（改訂版）",
      "editor": { "id": 5, "display_name": "田中太郎" },
      "change_summary": "結果セクションを追加",
      "created_at": "2026-03-12T10:00:00"
    },
    {
      "id": 2,
      "revision_number": 2,
      "title": "春の大会レポート",
      "editor": { "id": 5, "display_name": "田中太郎" },
      "change_summary": null,
      "created_at": "2026-03-11T15:00:00"
    }
  ]
}
```

**備考**: `body` はリスト取得時に含めない（サイズ節約）。個別リビジョンの body 確認は復元プレビュー UI で `GET /blog/posts/{id}/revisions/{revisionId}` を追加実装（Phase 6+ で検討）。

#### `POST /api/v1/blog/posts/{id}/revisions/{revisionId}/restore`

**認可**: ADMIN、`MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

指定リビジョンの `title` / `body` を現在の記事に上書きする。上書き前の状態は新しいリビジョンとして自動保存される。

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 42,
    "title": "春の大会レポート",
    "status": "DRAFT",
    "restored_from_revision": 2,
    "new_revision_number": 4
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 権限不足 |
| 404 | リビジョンが存在しない |

**備考**: 復元後は `status = DRAFT` に強制遷移（PUBLISHED のまま復元させない。再確認を促す）。

#### `POST /api/v1/blog/posts/{id}/preview-token`

**認可**: ADMIN、`MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN、または記事の著者

DRAFT / PENDING_REVIEW 状態の記事に対して24時間有効なプレビュー共有トークンを発行する。外部レビュワー（デザイナー、関係者等）に認証不要で下書きを確認してもらう用途。

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 42,
    "preview_token": "a1b2c3d4e5f6...",
    "preview_url": "https://example.com/blog/spring-report?preview_token=a1b2c3d4e5f6...",
    "expires_at": "2026-03-12T10:00:00"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 権限不足 |
| 409 | 既に PUBLISHED 状態（プレビュー不要） |

**備考**
- 既存トークンがある場合は上書き（再発行）。有効期限はリセット
- `GET /blog/posts/{slug}?preview_token={token}` でアクセス時、トークンが有効であれば status / visibility に関わらず記事を返却。無効・期限切れは 410 Gone
- 記事が PUBLISHED に遷移した場合、`preview_token` / `preview_token_expires_at` を自動で NULL にリセット

#### `PATCH /api/v1/blog/posts/bulk`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

**リクエストボディ**
```json
{
  "ids": [1, 2, 3],
  "action": "ARCHIVE"
}
```

**action の種類**
| action | 動作 | 対象 status |
|--------|------|-------------|
| `ARCHIVE` | 一括アーカイブ | PUBLISHED のみ |
| `DELETE` | 一括論理削除 | 全 status |
| `PUBLISH` | 一括公開 | DRAFT のみ（PENDING_REVIEW は対象外） |

**レスポンス（200 OK）**
```json
{
  "data": {
    "processed_count": 3,
    "skipped_ids": [],
    "action": "ARCHIVE"
  }
}
```

**備考**
- `ids` の上限: 50件。超過時は 400 エラー
- 対象 status でない記事は `skipped_ids` に含めてスキップ（エラーにしない）
- MEMBER は使用不可（403）

#### `GET /api/v1/blog/feed`

**認可**: 不要（PUBLIC 記事のみ）

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | △ | チーム ID（team_id / organization_id のいずれか必須） |
| `organization_id` | Long | △ | 組織 ID |
| `format` | String | × | `rss`（デフォルト）/ `atom` |

**レスポンス（200 OK）**
- `Content-Type: application/rss+xml` or `application/atom+xml`
- 最新20件の PUBLISHED かつ `visibility = PUBLIC` の記事を降順で返却
- 各エントリに `title`, `link`（slug ベース URL）, `description`（excerpt）, `pubDate`, `author` を含む

**備考**
- キャッシュ: `Cache-Control: public, max-age=600`（10分キャッシュ）
- SEO 向上のため、公開ページの `<head>` に `<link rel="alternate" type="application/rss+xml">` を自動挿入

#### `POST /api/v1/activities/{id}/duplicate`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

既存の活動記録をコピーして新規作成する。定例活動（毎週の練習等）で同じ構成を再利用する用途。

**コピー対象**: `title`（末尾に「（コピー）」付与）、`description`、`location`、`visibility`、`template_id`、`cover_image_url`
**コピーしない**: `activity_date`（当日日付を設定）、`schedule_event_id`、`participant_count`（0）、`view_count`（0）、参加者データ、カスタムフィールド値

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 25,
    "title": "第5回練習試合 vs チームB（コピー）",
    "activity_date": "2026-03-11",
    "participant_count": 0
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 権限不足 |
| 404 | コピー元の活動記録が存在しない |

---

#### `POST /api/v1/activities`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

**リクエストボディ**
```json
{
  "team_id": 1,
  "template_id": 3,
  "title": "第5回練習試合 vs チームB",
  "description": "ホームグラウンドでの練習試合。3-2で勝利。",
  "activity_date": "2026-03-08",
  "location": "市民グラウンド",
  "visibility": "MEMBERS_ONLY",
  "cover_image_url": "https://s3.example.com/activities/cover-456.jpg",
  "participants": [
    {
      "user_id": 10,
      "participation_type": "STARTER",
      "minutes_played": 90,
      "note": null,
      "custom_values": [
        { "custom_field_id": 5, "value": "FW" },
        { "custom_field_id": 6, "value": "2" },
        { "custom_field_id": 7, "value": "1" }
      ]
    },
    {
      "user_id": 11,
      "participation_type": "SUBSTITUTE",
      "minutes_played": 30,
      "note": "後半60分から出場",
      "custom_values": [
        { "custom_field_id": 5, "value": "MF" },
        { "custom_field_id": 6, "value": "0" },
        { "custom_field_id": 7, "value": "0" }
      ]
    }
  ],
  "custom_values": [
    { "custom_field_id": 1, "value": "チームB" },
    { "custom_field_id": 2, "value": "勝ち" },
    { "custom_field_id": 3, "value": "晴れ" }
  ]
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 15,
    "title": "第5回練習試合 vs チームB",
    "activity_date": "2026-03-08",
    "participant_count": 2
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（必須カスタムフィールド未入力等） |
| 403 | 権限不足 |
| 404 | 指定されたチーム/組織が存在しない |

#### `POST /api/v1/activities/generate-from-schedule`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

スケジュールイベント（F05）から活動記録を自動生成する。出欠データから参加者を自動ポピュレートし、天気情報を自動取得する。

**リクエストボディ**
```json
{
  "schedule_event_id": 100,
  "template_id": 3,
  "title": "第5回練習試合 vs チームB",
  "description": null
}
```

**処理フロー**
```
1. schedule_event_id の存在・チーム所属を検証
2. 既に schedule_event_id で活動記録が存在する場合 → 409 Conflict
3. schedule_attendances から status = ATTENDING / PARTIAL の出席者を取得
4. activity_results を INSERT（schedule_event_id を設定）
5. 出席者を activity_participants に一括 INSERT
   - participation_type: ATTENDING → 'STARTER'、PARTIAL → 'SUBSTITUTE'
6. 天気自動取得（オプション）:
   - チーム/組織のカスタムフィールドに field_name = '天気' かつ field_type = 'SELECT' が存在する場合
   - スケジュールの日付 + location で OpenWeatherMap API を呼び出し
   - 取得した天気を activity_custom_values に自動 INSERT
   - API 失敗時は天気フィールドを空のまま（エラーにしない）
7. ApplicationEvent（ActivityGeneratedFromScheduleEvent）を発行
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 15,
    "title": "第5回練習試合 vs チームB",
    "activity_date": "2026-03-08",
    "schedule_event_id": 100,
    "participant_count": 11,
    "weather_auto_filled": true
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 権限不足 |
| 404 | `schedule_event_id` が存在しない / チームに所属していない |
| 409 | 指定スケジュールから既に活動記録が生成済み |

---

#### `POST /api/v1/activities/{id}/participants`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

**リクエストボディ**
```json
{
  "participants": [
    {
      "user_id": 12,
      "participation_type": "STARTER",
      "minutes_played": 90,
      "note": "クリーンシート",
      "custom_values": [
        { "custom_field_id": 5, "value": "GK" },
        { "custom_field_id": 6, "value": "0" }
      ]
    }
  ]
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "added_count": 1,
    "participant_count": 3
  }
}
```

#### `DELETE /api/v1/activities/{id}/participants`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

**リクエストボディ**
```json
{
  "user_ids": [12]
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "removed_count": 1,
    "participant_count": 2
  }
}
```

#### `GET /api/v1/activities`

**認可**: 公開範囲に応じたアクセス制御
- `PUBLIC`: 認証不要
- `SUPPORTERS_AND_ABOVE`: SUPPORTER 以上
- `MEMBERS_ONLY`: MEMBER 以上

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | △ | チーム ID（team_id / organization_id のいずれか必須） |
| `organization_id` | Long | △ | 組織 ID |
| `visibility` | String | × | 公開範囲フィルタ（ADMIN/DEPUTY_ADMIN のみ） |
| `from` | Date | × | 期間開始日 |
| `to` | Date | × | 期間終了日 |
| `cursor` | Long | × | カーソル（前ページ最後の ID） |
| `limit` | Int | × | 取得件数（デフォルト: 20、最大: 50） |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 15,
      "title": "第5回練習試合 vs チームB",
      "activity_date": "2026-03-08",
      "location": "市民グラウンド",
      "visibility": "MEMBERS_ONLY",
      "participant_count": 11,
      "cover_image_url": "https://s3.example.com/activities/cover-456.jpg",
      "created_at": "2026-03-08T18:00:00"
    }
  ],
  "meta": {
    "next_cursor": 14,
    "has_next": true,
    "total_count": 42
  }
}
```

---

#### `GET /api/v1/activities/stats`

**認可**: MEMBER 以上

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | △ | チーム ID |
| `organization_id` | Long | △ | 組織 ID |
| `from` | Date | × | 期間開始日 |
| `to` | Date | × | 期間終了日 |

**レスポンス（200 OK）**
```json
{
  "data": {
    "total_activities": 24,
    "total_participants": 156,
    "period": {
      "from": "2026-01-01",
      "to": "2026-03-10"
    },
    "monthly_breakdown": [
      { "month": "2026-01", "count": 8 },
      { "month": "2026-02", "count": 10 },
      { "month": "2026-03", "count": 6 }
    ],
    "activity_field_aggregates": [
      {
        "field_id": 1, "field_name": "対戦相手", "scope": "ACTIVITY",
        "field_type": "TEXT", "unique_count": 12
      },
      {
        "field_id": 2, "field_name": "結果", "scope": "ACTIVITY",
        "field_type": "SELECT",
        "distribution": { "勝ち": 15, "負け": 5, "引き分け": 4 }
      },
      {
        "field_id": 3, "field_name": "天気", "scope": "ACTIVITY",
        "field_type": "SELECT",
        "distribution": { "晴れ": 14, "曇り": 6, "雨": 4 }
      }
    ],
    "participant_field_aggregates": [
      {
        "field_id": 5, "field_name": "ポジション", "scope": "PARTICIPANT",
        "field_type": "SELECT",
        "distribution": { "FW": 45, "MF": 52, "DF": 40, "GK": 19 }
      },
      {
        "field_id": 6, "field_name": "得点", "scope": "PARTICIPANT",
        "field_type": "NUMBER", "unit": "点",
        "sum": 87, "avg": 0.56, "min": 0, "max": 5
      }
    ],
    "participation_summary": {
      "starter_count": 264,
      "substitute_count": 48,
      "bench_count": 12,
      "total_minutes_played": 18720,
      "avg_minutes_per_activity": 78.0
    }
  }
}
```

#### `GET /api/v1/activities/stats/members/{userId}`

**認可**: MEMBER 以上（自分のデータ）。他メンバーのデータは ADMIN / DEPUTY_ADMIN のみ

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | △ | チーム ID |
| `organization_id` | Long | △ | 組織 ID |
| `from` | Date | × | 期間開始日 |
| `to` | Date | × | 期間終了日 |
| `template_id` | Long | × | テンプレートでフィルタ（例: 練習試合のみ） |

**レスポンス（200 OK）**
```json
{
  "data": {
    "user_id": 10,
    "display_name": "田中太郎",
    "period": { "from": "2026-01-01", "to": "2026-03-10" },
    "total_activities": 18,
    "participation_summary": {
      "starter": 14,
      "substitute": 3,
      "bench": 1,
      "total_minutes_played": 1350,
      "avg_minutes_per_activity": 75.0
    },
    "field_aggregates": [
      {
        "field_id": 5, "field_name": "ポジション", "field_type": "SELECT",
        "distribution": { "FW": 10, "MF": 6, "DF": 2 }
      },
      {
        "field_id": 6, "field_name": "得点", "field_type": "NUMBER", "unit": "点",
        "sum": 12, "avg": 0.67, "min": 0, "max": 3
      },
      {
        "field_id": 7, "field_name": "アシスト", "field_type": "NUMBER", "unit": "回",
        "sum": 5, "avg": 0.28, "min": 0, "max": 2
      }
    ],
    "trend": [
      { "month": "2026-01", "activities": 7, "minutes": 580, "field_6_sum": 5 },
      { "month": "2026-02", "activities": 6, "minutes": 450, "field_6_sum": 4 },
      { "month": "2026-03", "activities": 5, "minutes": 320, "field_6_sum": 3 }
    ]
  }
}
```

**備考**
- `trend` は月別の推移データ。フロントエンドで折れ線グラフに使用
- `trend[].field_6_sum` のように NUMBER 型フィールドの月別合計を動的に含める
- MEMBER が自分のデータを見る場合は「マイ統計」画面で表示

#### `GET /api/v1/activities/stats/ranking`

**認可**: MEMBER 以上

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | △ | チーム ID |
| `organization_id` | Long | △ | 組織 ID |
| `field_id` | Long | ○ | ランキング対象フィールド ID（NUMBER 型のみ） |
| `aggregation` | String | × | `sum`（デフォルト）/ `avg` / `max` / `count` |
| `from` | Date | × | 期間開始日 |
| `to` | Date | × | 期間終了日 |
| `limit` | Int | × | 上位N人（デフォルト: 10、最大: 50） |

**レスポンス（200 OK）**
```json
{
  "data": {
    "field_id": 6,
    "field_name": "得点",
    "unit": "点",
    "aggregation": "sum",
    "ranking": [
      { "rank": 1, "user_id": 10, "display_name": "田中太郎", "value": 12 },
      { "rank": 2, "user_id": 15, "display_name": "山田次郎", "value": 9 },
      { "rank": 3, "user_id": 22, "display_name": "佐藤三郎", "value": 7 }
    ]
  }
}
```

**備考**
- 参加者レベル（`scope = 'PARTICIPANT'`）の NUMBER 型フィールドのみ対象
- `aggregation = 'count'` は SELECT 型でも使用可能（例: 「スタメン回数ランキング」）
- 同値の場合は同率順位（次の順位はスキップ）

#### `GET /api/v1/activities/export`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_CONTENT`）

活動記録をカスタムフィールド値・参加者データ含めて CSV エクスポートする。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | △ | チーム ID（team_id / organization_id のいずれか必須） |
| `organization_id` | Long | △ | 組織 ID |
| `from` | Date | × | 期間開始日 |
| `to` | Date | × | 期間終了日 |
| `include_participants` | Boolean | × | 参加者データを含めるか（デフォルト: false。true の場合、参加者ごとに行が展開される） |

**レスポンス（200 OK / ≤1,000件）**
- `Content-Type: text/csv; charset=UTF-8`（BOM 付き）
- CSV カラム: `活動ID, タイトル, 活動日, 場所, 参加者数, [カスタムフィールド...]`
- `include_participants = true` の場合: `活動ID, タイトル, 活動日, 参加者名, 出場区分, 出場時間(分), [参加者カスタムフィールド...]`

**レスポンス（202 Accepted / >1,000件）**
```json
{
  "data": {
    "job_id": "export-act-xyz789",
    "status": "PROCESSING",
    "message": "エクスポートを開始しました。完了後に通知します。"
  }
}
```

**備考**
- CSV インジェクション対策: セル値の先頭が `=`, `+`, `-`, `@` の場合はシングルクォートを先頭に付与
- 非同期ジョブのダウンロードリンクは S3 Pre-signed URL（有効期限: 24時間、7日後に自動削除）

#### `GET /api/v1/activities/templates/official`

**認可**: MEMBER 以上

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "name": "練習試合",
      "description": "スポーツチーム向け。対戦相手・スコア・参加者を記録",
      "icon": "trophy",
      "default_title_pattern": "第{n}回 練習試合",
      "default_visibility": "MEMBERS_ONLY",
      "import_count": 342,
      "fields": [
        { "scope": "ACTIVITY", "field_name": "対戦相手", "field_type": "TEXT", "is_required": true },
        { "scope": "ACTIVITY", "field_name": "天気", "field_type": "SELECT", "options": ["晴れ","曇り","雨","雪"], "is_required": false },
        { "scope": "ACTIVITY", "field_name": "結果", "field_type": "SELECT", "options": ["勝ち","負け","引き分け"], "is_required": true },
        { "scope": "PARTICIPANT", "field_name": "ポジション", "field_type": "SELECT", "options": ["GK","DF","MF","FW"], "is_required": true },
        { "scope": "PARTICIPANT", "field_name": "得点", "field_type": "NUMBER", "unit": "点", "is_required": false, "default_value": "0" },
        { "scope": "PARTICIPANT", "field_name": "アシスト", "field_type": "NUMBER", "unit": "回", "is_required": false, "default_value": "0" }
      ]
    },
    {
      "id": 2,
      "name": "ボランティア活動",
      "description": "NPO・ボランティア団体向け。活動内容・参加人数・成果を記録",
      "icon": "heart",
      "default_title_pattern": "{n}回目 ボランティア活動",
      "default_visibility": "PUBLIC",
      "import_count": 156,
      "fields": [
        { "scope": "ACTIVITY", "field_name": "活動カテゴリ", "field_type": "SELECT", "options": ["清掃","支援","教育","その他"], "is_required": true },
        { "scope": "ACTIVITY", "field_name": "受益者数", "field_type": "NUMBER", "unit": "人", "is_required": false },
        { "scope": "PARTICIPANT", "field_name": "担当エリア", "field_type": "TEXT", "is_required": false },
        { "scope": "PARTICIPANT", "field_name": "作業時間", "field_type": "NUMBER", "unit": "時間", "is_required": false }
      ]
    }
  ]
}
```

#### `POST /api/v1/activities/templates/import`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

**リクエストボディ（公式テンプレートからインポート）**
```json
{
  "team_id": 1,
  "source_template_id": 1
}
```

**リクエストボディ（共有コードからインポート）**
```json
{
  "team_id": 1,
  "share_code": "aB3kX9mP"
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 15,
    "name": "練習試合",
    "source_template_id": 1,
    "fields_count": 4
  }
}
```

**処理内容**
1. ソーステンプレートの `activity_template_fields` を新テンプレートにコピー
2. ソーステンプレートのフィールド定義を `activity_custom_fields`（チーム/組織用）にも自動コピー（既に同名フィールドが存在する場合はスキップ）
3. ソーステンプレートの `import_count` をアトミック更新

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | `source_template_id` と `share_code` の両方が指定された |
| 403 | 権限不足 |
| 404 | テンプレート or 共有コードが存在しない |
| 409 | チーム/組織のテンプレート上限（10個）に到達 |

#### `POST /api/v1/activities/templates/{id}/share`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN（自チーム/組織のテンプレートのみ）

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 15,
    "share_code": "aB3kX9mP",
    "is_shared": true
  }
}
```

**備考**
- 共有コードは8文字英数字をランダム生成
- 共有中でもテンプレートの編集は可能（既にインポート済みのコピーには影響しない）
- `DELETE /api/v1/activities/templates/{id}/share` で共有コードを無効化

---

## 5. ビジネスロジック

### ブログ公開フロー（ADMIN / DEPUTY_ADMIN）

```
1. ADMIN/DEPUTY_ADMIN が記事を作成 → status = DRAFT
2. プレビュー確認後、PATCH /publish で status = PUBLISHED に変更
3a. published_at が NULL or 過去 → 即時公開
3b. published_at が未来 → 予約公開（スケジューラが定期実行で公開処理）
4. 公開時:
   - visibility = PUBLIC の場合、SSR 用ページを生成（SEO 対応）
   - post_type = ANNOUNCEMENT かつ priority >= IMPORTANT の場合、プッシュ通知送信
   - cross_post_to_timeline = true の場合、タイムラインクロスポスト処理（後述）
5. アーカイブ: 一覧から非表示になるが、直接 URL アクセスは可能
```

### ブログエディタ仕様

#### エディタ構成

Markdown ベースのエディタを提供する。技術に詳しくないユーザーでも mixi 日記感覚で気軽に書けるよう、ツールバー UI で Markdown 記法を補助する。

```
■ 2つの編集モード（タブ切り替え）

1. ツールバーモード（デフォルト）:
   - 上部ツールバーに書式ボタンを配置
   - ボタン押下 → カーソル位置に対応する Markdown 記法を自動挿入
   - テキスト選択 → ボタン押下で選択範囲を記法で囲む
   - 入力欄はプレーンテキスト（Markdown テキストがそのまま見える）
   - 右側 or 下部にリアルタイムプレビューを表示（分割ペイン、トグル可）

2. プレーンテキストモード:
   - ツールバー非表示、テキストエリアのみの Markdown 直書きモード
   - 慣れたユーザー向け。プレビューはトグルボタンで表示/非表示

■ ツールバーボタン一覧:
   | ボタン | 挿入される記法 |
   |--------|-------------|
   | 見出し (H2/H3/H4) | `## ` / `### ` / `#### ` |
   | 太字 | `**テキスト**` |
   | 斜体 | `*テキスト*` |
   | 箇条書き | `- ` |
   | 番号付きリスト | `1. ` |
   | リンク | `[テキスト](URL)` |
   | 画像 | `![代替テキスト](URL)` ※ S3 アップロード連携 |
   | 引用 | `> ` |
   | コードブロック | ``` ` ``` |
   | 水平線 | `---` |
   | テーブル | Markdown テーブルテンプレート挿入 |

■ 画像挿入:
   - ツールバーの画像ボタン or ドラッグ＆ドロップで S3 に Pre-signed URL アップロード
   - アップロード完了後、`![ファイル名](S3 URL)` を自動挿入
```

#### 目次（TOC）自動生成とアウトライン先行執筆

```
■ アウトライン先行ワークフロー:
   1. 記事作成画面を開くと「まず見出しから書き始めましょう」のガイドを表示
   2. ユーザーが ## / ### で見出しを入力すると、左サイドバーに目次が自動生成
   3. 見出しの骨格ができたら、各見出しの下に本文を書き込んでいく
   4. サイドバーの目次項目をクリック → エディタ内の該当見出し位置にスクロール

■ 目次（TOC）生成仕様:
   - body の Markdown テキストから `##`〜`####` の見出しを正規表現で抽出
   - 見出しレベルに応じてインデント表示（H2 = 最上位、H3 = 1段字下げ、H4 = 2段字下げ）
   - 各見出しに自動 ID を付与: 見出しテキストから slug 生成（日本語はそのまま encodeURIComponent）
     例: `## 大会結果` → `<h2 id="大会結果">大会結果</h2>`
   - `# H1` は記事タイトルと重複するため TOC 対象外（使用非推奨）

■ 閲覧画面の TOC 表示:
   - 公開記事の閲覧画面でも目次をサイドバー（デスクトップ）or 折りたたみパネル（モバイル）で表示
   - 目次項目クリック → 該当セクションにスムーズスクロール（CSS scroll-behavior: smooth）
   - スクロール連動: 現在表示中のセクションに対応する目次項目をハイライト（Intersection Observer API）
   - 見出しが 2 個未満の記事では目次を非表示

■ フロントエンド実装:
   - markdown-it の heading_open トークンにカスタムプラグインで id 属性を付与
   - TOC データはレンダリング時に抽出（API レスポンスに TOC フィールドは不要。フロントエンドで動的生成）
   - エディタ内の TOC はリアルタイム更新（debounce 300ms で見出しを再抽出）
```

### MEMBER ブログ執筆フロー

```
前提: ADMIN が team_role_permissions で WRITE_BLOG 権限を MEMBER に付与済み

1. MEMBER が BLOG 記事を作成 → status = DRAFT
   - post_type は BLOG のみ（ANNOUNCEMENT は 403）
   - pinned は設定不可
2a. 承認制モード（デフォルト）:
   a. MEMBER が PATCH /publish で status = PENDING_REVIEW に遷移
   b. ADMIN/DEPUTY_ADMIN に通知（プッシュ通知 + 通知一覧）
   c. ADMIN/DEPUTY_ADMIN がプレビュー確認
   d. 承認: PENDING_REVIEW → PUBLISHED
   e. 却下: PENDING_REVIEW → REJECTED（rejection_reason 必須）
      → MEMBER に却下理由とともに通知
      → MEMBER は REJECTED → DRAFT に戻して再編集・再提出可能
2b. 承認不要モード（blog_member_auto_publish = true）:
   a. MEMBER が DRAFT → PUBLISHED を直接遷移可能
   b. 通常の公開フローと同様に処理
3. 公開時:
   - cross_post_to_timeline = true かつ allow_blog_timeline_share = true の場合、
     タイムラインクロスポスト処理を実行
```

### タイムラインクロスポスト

```
ブログ記事が PUBLISHED に遷移した時点で実行:

1. cross_post_to_timeline = true を確認
2. チーム/組織の allow_blog_timeline_share 設定を確認（false なら何もしない）
3. timeline_posts に以下のリンク投稿を INSERT:
   - content: 「📝 ブログを投稿しました: {記事タイトル}」+ excerpt（先頭100文字）
   - scope_type: 投稿先はチーム/組織のタイムライン
   - author_id: ブログ記事の author_id
   - attachment_type: BLOG_LINK（ref_type 拡張）
   - ref_id: blog_posts.id
4. blog_posts.timeline_post_id に生成された timeline_posts.id を保存
5. タイムラインの WebSocket push で新着通知

ブログ記事が非公開/削除された場合:
- timeline_post_id の timeline_posts レコードも論理削除
- ただしタイムライン側でリアクション・返信がある場合は
  「この記事は非公開になりました」のプレースホルダーに差し替え
```

### 予約公開・予約アーカイブスケジューラ

```
- 実行間隔: 1分ごと
- 処理①（予約公開）:
  1. SELECT * FROM blog_posts WHERE status = 'DRAFT' AND published_at IS NOT NULL AND published_at <= NOW() AND deleted_at IS NULL
  2. 対象記事の status を PUBLISHED に更新
  3. 通知対象の場合はプッシュ通知を送信
- 処理②（予約アーカイブ）:
  1. SELECT * FROM blog_posts WHERE status = 'PUBLISHED' AND archive_at IS NOT NULL AND archive_at <= NOW() AND deleted_at IS NULL
  2. 対象記事の status を ARCHIVED に更新
  3. archive_at を NULL にリセット（再処理防止）
  4. ADMIN にアプリ内通知「記事が自動アーカイブされました: {記事タイトル}」を送信
- 失敗ハンドリング:
  - 公開/アーカイブ処理が例外で失敗した場合、status はそのまま残し次回スケジューラで再試行
  - 同一記事が3回連続失敗した場合、published_at / archive_at を NULL にリセットし ADMIN にアプリ内通知「予約処理に失敗しました: {記事タイトル}」を送信
  - 失敗回数は Redis（`blog:schedule_retry:{postId}`、TTL 1時間）で管理
```

### 活動記録テンプレートフロー

```
■ 公式テンプレートの配布と利用
1. SYSTEM_ADMIN が公式テンプレートを作成（業種別の見本）
   - 例: 「練習試合」「ボランティア活動」「演奏会」「定例ミーティング」
2. チーム/組織の ADMIN が公式テンプレート一覧を閲覧
3. 「保存」ボタンでチーム/組織にインポート
   → テンプレート + フィールド定義がコピーされる
   → activity_custom_fields にもフィールドが自動登録される
4. コピーされたテンプレートは自由に編集可能（名前・フィールド追加/削除）

■ テンプレートからの活動記録作成
1. 活動記録作成画面でテンプレート選択ドロップダウンを表示
2. テンプレート選択 → タイトル・公開範囲・場所・フィールドが自動入力
3. default_title_pattern の {n} は同テンプレート使用回数 + 1 に自動置換
4. 自動入力された値は個別に上書き可能
5. template_id なし（テンプレート未使用）での自由作成も引き続き可能

■ テンプレートの共有
1. ADMIN が自チーム/組織のテンプレートで「共有」ボタンを押す
2. 8文字の共有コードが発行される（例: aB3kX9mP）
3. 知り合いのチーム ADMIN にコードを伝える（チャット・SNS 等）
4. 相手チーム ADMIN が「インポート」画面で共有コードを入力
5. テンプレート + フィールド定義がコピーされ、自由に編集可能
   → source_template_id でコピー元を追跡
6. 共有を停止したい場合は「共有解除」で share_code を無効化
   → 既にインポート済みのコピーには影響しない
```

### 活動記録のカスタムフィールド（2レイヤー構造）

```
■ フィールド定義（ADMIN がチーム/組織ごとに作成、またはテンプレートからインポート）
  - scope = ACTIVITY: 活動全体に1つの値（例: 対戦相手、天気、試合結果）
  - scope = PARTICIPANT: 参加者ごとに異なる値（例: ポジション、得点、アシスト）

■ 値の格納
  - ACTIVITY フィールド → activity_custom_values に格納（1活動×1フィールド＝1レコード）
  - PARTICIPANT フィールド → activity_participant_values に格納（1参加者×1フィールド＝1レコード）

■ 入力フロー
1. 活動記録作成画面を開く
2. テンプレートを選択（任意）→ フィールドが自動表示
3. 活動レベルの値を入力（対戦相手、天気 等）
4. 参加者メンバー表を入力:
   - メンバー追加 → participation_type, minutes_played を設定
   - 参加者レベルのフィールド値を入力（ポジション、得点 等）
5. is_required = true のフィールドは入力必須
6. 保存 → activity_custom_values + activity_participant_values に一括書き込み

■ フィールド管理
  - カスタムフィールドの削除は is_active = false で論理無効化
    → 既存の活動記録に紐付いた値は保持される

■ 統計・グラフ化
  - NUMBER 型: SUM / AVG / MIN / MAX を自動集計。月別推移を折れ線グラフ化
  - SELECT 型: 選択肢ごとの分布を円グラフ化
  - 参加者レベルの集計は user_id 軸で横断可能
    → 「田中選手の今シーズン得点推移」「ポジション別出場回数」等
  - ランキング API で「得点王」「出場時間トップ10」等を自動生成
```

### アクセス制御ロジック

```
記事/活動記録の閲覧判定:
1. deleted_at IS NOT NULL → 全ユーザーに非表示
2. status = DRAFT → 作成者本人、ADMIN、MANAGE_CONTENT 権限保持者のみ
3. status = PENDING_REVIEW → 作成者本人、ADMIN、MANAGE_CONTENT 権限保持者のみ
4. status = REJECTED → 作成者本人、ADMIN、MANAGE_CONTENT 権限保持者のみ
5. status = ARCHIVED → URL 直接アクセス時のみ表示（一覧からは除外）
6. status = PUBLISHED かつ visibility = PUBLIC → 認証不要で閲覧可
7. status = PUBLISHED かつ visibility = SUPPORTERS_AND_ABOVE → SUPPORTER 以上
8. status = PUBLISHED かつ visibility = MEMBERS_ONLY → MEMBER 以上

記事の編集・削除判定:
1. ADMIN → 全記事を編集・削除可
2. DEPUTY_ADMIN（MANAGE_CONTENT）→ 全記事を編集・削除可
3. MEMBER（WRITE_BLOG）→ 自分が author_id の記事のみ編集・削除可
4. 上記以外 → 403
```

### 孤立画像クリーンアップバッチ

```
- 実行間隔: 日次（深夜3:00）
- 処理:
  1. SELECT * FROM blog_image_uploads WHERE blog_post_id IS NULL AND created_at < NOW() - INTERVAL 72 HOUR
  2. 対象レコードの s3_key で S3 オブジェクトを削除
  3. blog_image_uploads から物理削除
  4. 処理件数をログ出力
- 補足:
  - 72時間の猶予は、エディタでの画像アップロード→記事保存までの十分な余裕を確保するため
  - 記事保存時に body 内の S3 URL を解析し、blog_image_uploads.blog_post_id を紐付ける
  - 記事編集で画像参照を削除した場合、次回保存時に blog_post_id を NULL にリセット
```

### view_count 同期バッチ

```
- 実行間隔: 日次（深夜4:00）
- 処理:
  1. page_view_daily_stats から target_type = 'BLOG_POST' の合計を集計
  2. blog_posts.view_count と比較し、差異があるレコードを UPDATE
  3. 同様に target_type = 'ACTIVITY_RECORD' で activity_results.view_count を補正
- 補足:
  - view_count はリアルタイム性より表示速度を優先した denormalize 値
  - リアルタイム加算は API リクエスト時に UPDATE blog_posts SET view_count = view_count + 1 で行う（厳密な正確性は不要）
  - バッチは page_view_daily_stats を正として補正するため、カウント漏れやレースコンディションの蓄積を解消
```

### 論理削除の物理削除ポリシー

```
- 対象テーブル: blog_posts, activity_results, activity_templates
- 物理削除タイミング: 論理削除（deleted_at）から 90日後
- 実行間隔: 週次バッチ（日曜深夜2:00）
- 処理:
  1. SELECT id FROM {table} WHERE deleted_at IS NOT NULL AND deleted_at < NOW() - INTERVAL 90 DAY
  2. 関連テーブルをカスケード削除（FK で自動。blog_post_tags, blog_post_revisions, activity_participants 等）
  3. blog_image_uploads の関連画像を S3 から削除
  4. 物理 DELETE 実行
- 補足:
  - 90日間は復元可能期間（SYSTEM_ADMIN が管理画面から論理削除を取り消せる）
  - バッチ実行前に対象件数をログ出力し、異常に多い場合はアラート
  - 1回あたりの処理上限: 1,000件（大量削除時はバッチを複数回に分割）
```

---

## 6. セキュリティ考慮事項

- **認可チェック**: BlogService / ActivityService の入り口で `team_id` / `organization_id` と `currentUser` の所属を検証
- **XSS 対策**: `body` は Markdown テキストとして DB に保存し、HTML 変換はフロントエンドで実施する。レンダリングパイプライン: `markdown-it`（Markdown → HTML）→ `DOMPurify`（許可タグのホワイトリスト方式でサニタイズ）→ DOM 挿入。バックエンドは生 Markdown を保存するのみで HTML 変換は行わない
- **slug インジェクション防止**: slug は `[a-z0-9-]` のみ許可。最大200文字
- **カバー画像**: S3 Pre-signed URL でアップロード。URL はドメインバリデーション（自ドメインの S3 バケットのみ許可）
- **レートリミット**: 記事作成 API に `Bucket4j` で 1分間に10回の制限
- **FULLTEXT 検索のリソース保護**: 検索クエリの最小文字数を3文字に制限。空クエリでの全件スキャンを防止
- **楽観的ロック**: `blog_posts.version` による同時編集検知。`PUT /blog/posts/{id}` でリクエストの `version` と DB の値を比較し、不一致は 409 Conflict。自動保存（auto-save）でも同様に検証
- **プレビュートークン**: SecureRandom で64文字のトークンを生成。有効期限24時間。トークンは推測不可能（URL に含まれるため、十分なエントロピーを確保）

---

## 7. Flywayマイグレーション

```
V6.001__create_blog_post_series.sql            -- blog_post_series テーブル作成（blog_posts.series_id の FK 参照先）
V6.002__create_blog_posts.sql                  -- blog_posts テーブル作成 + FULLTEXT 複合インデックス
V6.003__create_blog_tags.sql                   -- blog_tags テーブル作成
V6.004__create_blog_post_tags.sql              -- blog_post_tags 中間テーブル作成
V6.005__create_blog_post_revisions.sql         -- blog_post_revisions テーブル作成
V6.006__create_blog_image_uploads.sql          -- blog_image_uploads テーブル作成（画像管理）
V6.007__create_activity_results.sql            -- activity_results テーブル作成
V6.008__create_activity_participants.sql       -- activity_participants テーブル作成
V6.009__create_activity_custom_fields.sql      -- activity_custom_fields テーブル作成
V6.010__create_activity_custom_values.sql      -- activity_custom_values テーブル作成
V6.011__create_activity_participant_values.sql  -- activity_participant_values テーブル作成
V6.012__create_activity_templates.sql          -- activity_templates テーブル作成
V6.013__create_activity_template_fields.sql    -- activity_template_fields テーブル作成
V6.014__seed_official_activity_templates.sql   -- 公式テンプレート初期データ投入
```

**マイグレーション上の注意点**
- `blog_post_series` は `blog_posts.series_id` の FK 参照先のため、`blog_posts` より先に作成（V6.001）
- `blog_post_revisions` は `blog_posts` に依存するため V6.002 より後に作成（V6.005）
- `blog_image_uploads` は `blog_posts` に依存するため V6.002 より後に作成（V6.006）
- `blog_posts` は `teams`, `organizations`, `users` テーブルに依存（Phase 1 で作成済み）
- FULLTEXT インデックスは `(title, body)` の複合インデックスを InnoDB で作成（MySQL 5.6+ で対応済み）
- `activity_custom_values.custom_field_id` と `activity_participant_values.custom_field_id` の FK は RESTRICT のため、マイグレーション順序に注意
- `activity_participant_values` は `activity_participants` に依存するため V6.008 より後に作成
- `V6.014` の公式テンプレート初期データは SYSTEM_ADMIN が管理画面から追加・編集可能なため、最小限の見本のみ投入（5〜10件程度）

---

## 8. 未解決事項

- [x] ~~①ブログ記事本文のフォーマット~~ → Markdown のみ。ツールバー付きエディタ（mixi 日記風）で Markdown 記法を補助 + プレーンテキスト直書きモードの2モード切替。目次（TOC）自動生成でアウトライン先行執筆を推奨。レンダリングは `markdown-it` + `DOMPurify`（フロントエンド）
- [x] ~~②お知らせ（ANNOUNCEMENT）の配信先制御~~ → Phase 6 は全メンバー配信（`target_type = 'ALL'`）のみ。`blog_posts` に `target_type ENUM('ALL','ROLE','GROUP')` + `target_id BIGINT nullable` を追加済み。ロール別・グループ別配信は将来フェーズで `target_type` を活用して実装
- [x] ~~③活動記録とスケジュールの連携~~ → 対応する。`activity_results.schedule_event_id` FK（UNIQUE）でスケジュールイベントと 1:1 紐付け。`POST /api/v1/activities/generate-from-schedule` API で出欠データ（`schedule_attendances`）から参加者を自動ポピュレート。天気は OpenWeatherMap API で自動取得（カスタムフィールドに「天気」SELECT 型がある場合）
- [x] ~~④ブログ記事のコメント機能~~ → `allow_comments` のデフォルトを `FALSE` に変更。コメントテーブル（`blog_comments`）の設計は将来拡張とし、Phase 6 ではカラムのみ保持。ADMIN が記事ごとに `TRUE` に切り替え可能だが、Phase 6 では UI 上コメント欄は表示しない
- [x] ~~⑤活動記録のカスタムフィールド上限数~~ → テンプレートあたり活動レベル + 参加者レベル合わせて20個。チーム/組織あたりのフィールド定義総数は制限なし（テンプレート上限10個 × 20フィールドで実質200個が上限）

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-10 | 初版作成 |
| 2026-03-10 | MEMBER ブログ執筆権限（WRITE_BLOG）追加、承認ワークフロー（PENDING_REVIEW/REJECTED）追加、タイムラインクロスポスト機能追加 |
| 2026-03-10 | タグ機能強化: BLOG/ANNOUNCEMENT 共用タグ明記、色・表示順・記事数追加、複数タグ AND フィルタ、タグ更新 API 追加 |
| 2026-03-10 | 活動記録テンプレート機能追加: 公式テンプレート配布・チーム/組織テンプレート（上限10個）・共有コードによるインポート |
| 2026-03-11 | 活動記録2レイヤーカスタムフィールド: 活動レベル（ACTIVITY）+ 参加者レベル（PARTICIPANT）に分離。activity_participant_values テーブル追加。activity_participants に participation_type・minutes_played 構造化カラム追加。メンバー個人統計 API・ランキング API 追加 |
| 2026-03-11 | ページネーション方式を Cursor-based（cursor/limit）に統一、has_more → has_next に改名、GET /activities 一覧 API のクエリパラメータ・レスポンス仕様追加 |
| 2026-03-11 | ブログエディタ仕様追加: Markdown 専用確定、ツールバーモード（mixi 日記風）+ プレーンテキスト直書きモードの 2 モード、目次（TOC）自動生成＋アウトライン先行執筆ワークフロー、閲覧画面 TOC ナビゲーション。body カラムを Markdown 専用に更新、XSS 対策をフロントエンドレンダリング方式に変更 |
| 2026-03-11 | 設計改善: Flyway 欠落修正（blog_post_series / blog_post_revisions / blog_image_uploads 追加、マイグレーション順序整理）、FULLTEXT 複合インデックス化、楽観的ロック（version カラム）追加、プレビュー共有トークン追加、予約アーカイブ（archive_at）追加、一括操作 API 追加、RSS フィード追加、活動記録複製 API 追加、blog_image_uploads テーブル追加（孤立画像クリーンアップ）、タグ数上限（10個/記事）追加、論理→物理削除ポリシー追加、view_count 同期バッチ仕様追加 |
| 2026-03-12 | slug UNIQUE 制約: インデックス定義に MySQL NULL 特性の設計意図コメントを追加。activity_custom_values / activity_participant_values: scope カラム + CHECK 制約 + 複合 FK で scope 不整合を DB レベルで完全防止（activity_custom_fields に uq_acf_id_scope UNIQUE 追加） |
