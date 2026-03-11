# F06: メンバー紹介 + ギャラリー

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 6
> **最終更新**: 2026-03-12

---

## 1. 概要

チーム・組織のメンバー紹介ページをメインページ＋年度別詳細ページの階層構造で管理し、各メンバーのプロフィールを画像・一言・拡張フィールド付きで掲載する機能を提供する。ギャラリーはメンバー向けの写真アルバム管理機能で、アルバム単位で閲覧権限を設定し、チーム・組織内のアクティビティ写真を体系的に蓄積・共有する。

---

## 2. スコープ

### 対象ロール

#### メンバー紹介
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全組織・チームのページを参照・強制削除 |
| ADMIN | ページの作成・編集・公開/非公開・削除、セクション管理、メンバープロフィール管理、プロフィールフィールド定義、一括登録 |
| DEPUTY_ADMIN | `MANAGE_CONTENT` 権限を持つ場合: ページの作成・編集・公開/非公開、メンバープロフィール管理 |
| MEMBER | 公開されたメンバー紹介ページの閲覧、自分のプロフィール編集（ADMIN が許可した範囲） |
| SUPPORTER | 公開されたメンバー紹介ページの閲覧 |
| GUEST | 外部公開されたメンバー紹介ページの閲覧 |

#### ギャラリー
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全組織・チームのアルバム・写真を参照・強制削除 |
| ADMIN | アルバムの作成・編集・削除、写真のアップロード・削除、閲覧権限設定 |
| DEPUTY_ADMIN | `MANAGE_CONTENT` 権限を持つ場合: アルバム作成・編集、写真アップロード・削除 |
| MEMBER | 閲覧権限のあるアルバムの参照、写真アップロード（ADMIN が許可した場合） |
| SUPPORTER | `SUPPORTERS_AND_ABOVE` 公開設定のアルバムを閲覧可 |
| GUEST | 対象外 |

### 対象レベル

#### メンバー紹介
- [x] 組織 (Organization)
- [x] チーム (Team)
- [ ] 個人 (Personal)

#### ギャラリー
- [x] 組織 (Organization)
- [x] チーム (Team)
- [ ] 個人 (Personal)

---

## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 論理削除 |
|-----------|------|---------|
| `team_pages` | メンバー紹介のページ（メイン / 年度別） | あり |
| `team_page_sections` | ページ内のセクション（テキスト・画像等） | なし |
| `member_profiles` | メンバーのプロフィール情報 | なし |
| `member_profile_fields` | プロフィールの拡張フィールド定義 | なし |
| `photo_albums` | 写真アルバム | あり |
| `photos` | アルバム内の個別写真 | なし |

### テーブル定義

#### `team_pages`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations |
| `title` | VARCHAR(200) | NO | — | ページタイトル（例: 2026年度メンバー紹介） |
| `slug` | VARCHAR(200) | NO | — | URL スラッグ |
| `page_type` | ENUM('MAIN', 'YEARLY') | NO | — | ページ種別（メインページ / 年度ページ） |
| `year` | SMALLINT UNSIGNED | YES | NULL | 対象年度（YEARLY の場合のみ。例: 2026） |
| `description` | TEXT | YES | NULL | ページの説明文 |
| `cover_image_s3_key` | VARCHAR(500) | YES | NULL | カバー画像の S3 オブジェクトキー（CDN ドメインはアプリ層で付与） |
| `visibility` | ENUM('PUBLIC', 'MEMBERS_ONLY') | NO | 'MEMBERS_ONLY' | 公開範囲 |
| `status` | ENUM('DRAFT', 'PUBLISHED') | NO | 'DRAFT' | 公開ステータス |
| `preview_token` | VARCHAR(64) | YES | NULL | 下書きプレビュー共有トークン（SecureRandom で生成。NULL = 未発行） |
| `preview_token_expires_at` | DATETIME | YES | NULL | プレビュートークン有効期限（発行から24時間） |
| `allow_self_edit` | BOOLEAN | NO | FALSE | TRUE = MEMBER が自分のプロフィール（bio, photo_s3_key, カスタムフィールド値）を直接編集可能 |
| `sort_order` | INT | NO | 0 | 表示順（メインページからの遷移順序） |
| `created_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**インデックス**
```sql
UNIQUE KEY uq_tp_slug_team (team_id, slug, deleted_at)       -- チーム内スラッグ一意性
UNIQUE KEY uq_tp_slug_org (organization_id, slug, deleted_at) -- 組織内スラッグ一意性
UNIQUE KEY uq_tp_year_team (team_id, year, deleted_at)       -- チーム内年度一意性（YEARLY ページ）
UNIQUE KEY uq_tp_year_org (organization_id, year, deleted_at) -- 組織内年度一意性
INDEX idx_tp_team_status (team_id, status, sort_order)        -- チーム別ページ一覧
INDEX idx_tp_org_status (organization_id, status, sort_order) -- 組織別ページ一覧
```

**制約・備考**
- `team_id` と `organization_id` は XOR:
  ```sql
  CONSTRAINT chk_tp_scope
    CHECK (
      (team_id IS NOT NULL AND organization_id IS NULL)
      OR (team_id IS NULL AND organization_id IS NOT NULL)
    )
  ```
- `page_type = 'MAIN'` の場合は `year` は NULL。`page_type = 'YEARLY'` の場合は `year` 必須:
  ```sql
  CONSTRAINT chk_tp_year
    CHECK (
      (page_type = 'MAIN' AND year IS NULL)
      OR (page_type = 'YEARLY' AND year IS NOT NULL)
    )
  ```
- チーム/組織あたり MAIN ページは1つのみ（アプリケーション層で制御）
- 論理削除: `deleted_at DATETIME nullable`（SoftDeletableEntity 適用）

#### `team_page_sections`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_page_id` | BIGINT UNSIGNED | NO | — | FK → team_pages（CASCADE） |
| `section_type` | ENUM('TEXT', 'IMAGE', 'MEMBER_LIST', 'HEADING') | NO | — | セクション種別 |
| `title` | VARCHAR(200) | YES | NULL | セクション見出し（HEADING / TEXT 用） |
| `content` | TEXT | YES | NULL | テキストコンテンツ（TEXT 用） |
| `image_s3_key` | VARCHAR(500) | YES | NULL | 画像の S3 オブジェクトキー（IMAGE 用） |
| `image_caption` | VARCHAR(200) | YES | NULL | 画像キャプション |
| `sort_order` | INT | NO | 0 | 表示順 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_tps_page_order (team_page_id, sort_order)  -- ページ内セクション表示順
```

**制約・備考**
- `section_type = 'MEMBER_LIST'` の場合、このセクション位置にそのページの `member_profiles` 一覧を表示する。`content`, `image_s3_key` は NULL
- ページ削除時にカスケード削除
- 物理削除

#### `member_profiles`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_page_id` | BIGINT UNSIGNED | NO | — | FK → team_pages（CASCADE） |
| `user_id` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete。退会後も掲載保持） |
| `display_name` | VARCHAR(100) | NO | — | 表示名（登録時のスナップショット） |
| `photo_s3_key` | VARCHAR(500) | YES | NULL | プロフィール画像の S3 オブジェクトキー |
| `bio` | VARCHAR(500) | YES | NULL | 一言・自己紹介 |
| `position` | VARCHAR(100) | YES | NULL | 役職・ポジション |
| `custom_field_values` | JSON | YES | NULL | カスタムフィールド値（{"field_id": "value", ...}。例: {"1": "10", "2": "A型"}） |
| `sort_order` | INT | NO | 0 | 表示順 |
| `is_visible` | BOOLEAN | NO | TRUE | 表示/非表示 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_mp_page_order (team_page_id, sort_order)  -- ページ内メンバー表示順
UNIQUE KEY uq_mp_page_user (team_page_id, user_id)  -- 同一ページへの重複登録防止
INDEX idx_mp_user (user_id)                          -- ユーザー別掲載一覧
```

**制約・備考**
- `user_id` が NULL になった場合でも `display_name` と `photo_s3_key` で掲載を継続
- **MEMBER セルフ編集**: `team_pages.allow_self_edit = true` の場合、MEMBER は自分の `member_profiles` レコード（`user_id` = 自分）に対して `bio`, `photo_s3_key`, `custom_field_values` を直接編集可能（即時反映、承認不要）。`display_name`, `position`, `sort_order`, `is_visible` は ADMIN/DEPUTY_ADMIN のみ編集可能（ページの見た目制御は管理者が責任を持つ）。不適切な内容は ADMIN が `is_visible = false` で対処
- **custom_field_values の JSON バリデーション**: Service 層で `member_profile_fields` と照合し、存在しない field_id の値を拒否・field_type に応じた型チェック（NUMBER なら数値文字列か）・is_required フィールドの未入力チェックを実施する。不正な JSON 構造は 400 エラー
- 物理削除（ページ削除時にカスケード削除）

#### `member_profile_fields`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations |
| `field_name` | VARCHAR(100) | NO | — | フィールド名（例: 背番号、血液型、趣味） |
| `field_type` | ENUM('TEXT', 'NUMBER', 'DATE', 'SELECT') | NO | 'TEXT' | データ型 |
| `options` | JSON | YES | NULL | SELECT 型の選択肢リスト |
| `is_required` | BOOLEAN | NO | FALSE | 必須フラグ |
| `sort_order` | INT | NO | 0 | 表示順 |
| `is_active` | BOOLEAN | NO | TRUE | FALSE = 新規プロフィール作成時に非表示 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_mpf_team (team_id, sort_order)          -- チーム別フィールド一覧
INDEX idx_mpf_org (organization_id, sort_order)   -- 組織別フィールド一覧
```

**制約・備考**
- `team_id` と `organization_id` は XOR（CHECK 制約）
- フィールド定義はチーム/組織全体で共通。各ページの `member_profiles.custom_field_values` JSON カラムにフィールド値を格納
- **JSON 方式を採用した理由**: プロフィールは表示用途が主で数値集計不要、JSON なら1レコードで完結し一覧取得がシンプル、MySQL 8.0 の `JSON_EXTRACT` で個別検索にも対応可能
- フィールド定義は物理削除しない。`is_active = FALSE` で非表示

#### `photo_albums`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations |
| `title` | VARCHAR(200) | NO | — | アルバムタイトル |
| `description` | VARCHAR(500) | YES | NULL | アルバムの説明 |
| `cover_photo_id` | BIGINT UNSIGNED | YES | NULL | FK → photos（アルバムのカバー写真。NULL = 先頭写真を自動使用） |
| `event_date` | DATE | YES | NULL | イベント日（任意。アルバムの時系列整理用） |
| `visibility` | ENUM('ALL_MEMBERS', 'SUPPORTERS_AND_ABOVE', 'ADMIN_ONLY') | NO | 'ALL_MEMBERS' | 閲覧権限（PUBLIC は追加しない。ギャラリーはプライバシー性が高いため認証必須の範囲に限定） |
| `allow_member_upload` | BOOLEAN | NO | FALSE | MEMBER による写真アップロード許可 |
| `allow_download` | BOOLEAN | NO | TRUE | TRUE = 個別・一括ダウンロード許可。FALSE = 閲覧のみ（カジュアルコピー抑止） |
| `photo_count` | INT UNSIGNED | NO | 0 | 写真数（denormalize） |
| `created_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**インデックス**
```sql
INDEX idx_pa_team_date (team_id, event_date DESC)       -- チーム別アルバム一覧（日付降順）
INDEX idx_pa_org_date (organization_id, event_date DESC) -- 組織別アルバム一覧
INDEX idx_pa_created_by (created_by)                     -- 作成者別一覧
```

**制約・備考**
- `team_id` と `organization_id` は XOR（CHECK 制約）
- `visibility = 'ADMIN_ONLY'` は ADMIN/DEPUTY_ADMIN のみ閲覧可能（練習風景の整理中等）
- `photo_count` は `photos` の INSERT/DELETE 時にアトミック更新
- `cover_photo_id` は photos テーブル作成後に FK 設定（循環参照回避のため ALTER TABLE で追加）
- 論理削除: `deleted_at DATETIME nullable`（SoftDeletableEntity 適用）
- ギャラリーは選択式モジュール No.6 のため、モジュール有効化チェックが必要

#### `photos`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `album_id` | BIGINT UNSIGNED | NO | — | FK → photo_albums（CASCADE） |
| `s3_key` | VARCHAR(500) | NO | — | 写真ファイルの S3 オブジェクトキー |
| `thumbnail_s3_key` | VARCHAR(500) | YES | NULL | サムネイルの S3 オブジェクトキー（非同期生成） |
| `original_filename` | VARCHAR(255) | NO | — | 元ファイル名 |
| `content_type` | VARCHAR(50) | NO | — | MIME タイプ（image/jpeg, image/png, image/webp。HEIC は変換後 image/jpeg） |
| `file_size` | INT UNSIGNED | NO | — | ファイルサイズ（bytes） |
| `width` | INT UNSIGNED | YES | NULL | 画像幅（px） |
| `height` | INT UNSIGNED | YES | NULL | 画像高さ（px） |
| `caption` | VARCHAR(300) | YES | NULL | キャプション |
| `taken_at` | DATETIME | YES | NULL | 撮影日時（EXIF から自動抽出） |
| `sort_order` | INT | NO | 0 | 表示順 |
| `uploaded_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | キャプション編集等 |

**インデックス**
```sql
INDEX idx_ph_album_order (album_id, sort_order)  -- アルバム内写真表示順
INDEX idx_ph_album_taken (album_id, taken_at)    -- アルバム内撮影日時順
INDEX idx_ph_uploaded_by (uploaded_by)            -- アップロードユーザー別一覧
```

**制約・備考**
- アルバム削除時にカスケード削除（S3 ファイルの削除はアプリケーション層で非同期実行）
- 物理削除
- サムネイルは S3 アップロード後に Lambda / バックエンド非同期ジョブで生成（短辺300px にリサイズ）
- `taken_at` は EXIF の `DateTimeOriginal` から抽出。EXIF がない場合は NULL
- アップロード可能な形式: JPEG, PNG, WebP, HEIC（HEIC は JPEG に変換して保存。`content_type` は変換後の MIME タイプを記録）
- 1枚あたりの最大ファイルサイズ: 20MB（アプリケーション層でバリデーション）
- `cover_photo_id = NULL` かつ写真が0枚のアルバムは、フロントエンドでプレースホルダー画像を表示する

### ER図（テキスト形式）

```
teams/organizations (1) ──── (N) team_pages
team_pages          (1) ──── (N) team_page_sections
team_pages          (1) ──── (N) member_profiles
member_profiles     (N) ──── (1) users

teams/organizations (1) ──── (N) member_profile_fields
member_profiles.custom_field_values (JSON) ── references ── member_profile_fields (field_id をキーに参照)

teams/organizations (1) ──── (N) photo_albums
photo_albums        (1) ──── (N) photos
photo_albums        (N) ──── (1) photos (cover_photo_id)
```

---

## 4. API設計

### エンドポイント一覧

#### メンバー紹介
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/team/pages` | 条件付き | ページ一覧取得 |
| POST | `/api/v1/team/pages` | 必要 | ページ作成 |
| GET | `/api/v1/team/pages/{id}` | 条件付き | ページ詳細取得（セクション・メンバー含む） |
| PUT | `/api/v1/team/pages/{id}` | 必要 | ページ更新 |
| DELETE | `/api/v1/team/pages/{id}` | 必要 | ページ削除（論理削除） |
| PATCH | `/api/v1/team/pages/{id}/publish` | 必要 | 公開ステータス変更 |
| GET | `/api/v1/team/pages/{id}/sections` | 条件付き | セクション一覧取得 |
| POST | `/api/v1/team/pages/{id}/sections` | 必要 | セクション追加 |
| PUT | `/api/v1/team/sections/{id}` | 必要 | セクション更新 |
| DELETE | `/api/v1/team/sections/{id}` | 必要 | セクション削除 |
| GET | `/api/v1/team/members` | 条件付き | メンバープロフィール一覧取得 |
| POST | `/api/v1/team/members` | 必要 | メンバープロフィール作成 |
| GET | `/api/v1/team/members/{id}` | 条件付き | メンバープロフィール詳細取得 |
| PUT | `/api/v1/team/members/{id}` | 必要 | メンバープロフィール更新 |
| DELETE | `/api/v1/team/members/{id}` | 必要 | メンバープロフィール削除 |
| POST | `/api/v1/team/members/bulk` | 必要 | メンバープロフィール一括登録 |
| GET | `/api/v1/team/member-fields` | 必要 | プロフィールフィールド定義一覧 |
| POST | `/api/v1/team/member-fields` | 必要 | プロフィールフィールド定義作成 |
| PUT | `/api/v1/team/member-fields/{id}` | 必要 | プロフィールフィールド定義更新 |
| DELETE | `/api/v1/team/member-fields/{id}` | 必要 | プロフィールフィールド定義無効化 |
| POST | `/api/v1/team/pages/{id}/copy-members` | 必要 | 前年度ページからメンバーをコピー |
| PATCH | `/api/v1/team/members/reorder` | 必要 | メンバー表示順の一括更新（ドラッグ＆ドロップ用） |
| POST | `/api/v1/team/pages/{id}/preview-token` | 必要 | プレビュー共有トークン発行（24時間有効） |
| DELETE | `/api/v1/team/pages/{id}/preview-token` | 必要 | プレビュートークン無効化 |

#### ギャラリー
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/gallery/albums` | 必要 | アルバム一覧取得 |
| POST | `/api/v1/gallery/albums` | 必要 | アルバム作成 |
| GET | `/api/v1/gallery/albums/{id}` | 必要 | アルバム詳細取得（写真リスト含む） |
| PUT | `/api/v1/gallery/albums/{id}` | 必要 | アルバム更新 |
| DELETE | `/api/v1/gallery/albums/{id}` | 必要 | アルバム削除（論理削除） |
| POST | `/api/v1/gallery/albums/{id}/photos` | 必要 | 写真アップロード |
| DELETE | `/api/v1/gallery/photos/{id}` | 必要 | 写真削除 |
| PUT | `/api/v1/gallery/photos/{id}` | 必要 | 写真情報更新（キャプション等） |
| GET | `/api/v1/gallery/photos/{id}/download` | 必要 | 個別写真ダウンロード（Pre-signed URL） |
| GET | `/api/v1/gallery/albums/{id}/download` | 必要 | アルバム写真の一括ダウンロード（ZIP） |
| POST | `/api/v1/system-admin/gallery/regenerate-thumbnails` | 必要 | サムネイル一括再生成（SYSTEM_ADMIN） |

### リクエスト／レスポンス仕様

#### `POST /api/v1/team/pages`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

**リクエストボディ**
```json
{
  "team_id": 1,
  "title": "2026年度メンバー紹介",
  "slug": "members-2026",
  "page_type": "YEARLY",
  "year": 2026,
  "description": "2026年度のチームメンバーを紹介します。",
  "cover_image_s3_key": "pages/cover-789.jpg",
  "visibility": "PUBLIC"
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 5,
    "slug": "members-2026",
    "status": "DRAFT"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（YEARLY で year 未指定等） |
| 403 | 権限不足 |
| 409 | slug 重複、または同一年度の YEARLY ページが既に存在 |

#### `GET /api/v1/team/pages/{id}`

**認可**: ページの `visibility` と `status` に応じたアクセス制御

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 5,
    "title": "2026年度メンバー紹介",
    "slug": "members-2026",
    "page_type": "YEARLY",
    "year": 2026,
    "description": "2026年度のチームメンバーを紹介します。",
    "cover_image_url": "https://cdn.example.com/pages/cover-789.jpg",
    "visibility": "PUBLIC",
    "status": "PUBLISHED",
    "sections": [
      {
        "id": 10,
        "section_type": "HEADING",
        "title": "チーム紹介",
        "sort_order": 0
      },
      {
        "id": 11,
        "section_type": "TEXT",
        "title": null,
        "content": "私たちのチームは2020年に設立されました...",
        "sort_order": 1
      },
      {
        "id": 12,
        "section_type": "MEMBER_LIST",
        "sort_order": 2
      }
    ],
    "members": [
      {
        "id": 1,
        "display_name": "田中太郎",
        "photo_url": "https://cdn.example.com/profiles/tanaka.jpg",
        "bio": "よろしくお願いします！",
        "position": "キャプテン",
        "sort_order": 0,
        "custom_fields": {
          "背番号": "10",
          "血液型": "A型"
        }
      }
    ],
    "created_at": "2026-03-01T10:00:00",
    "updated_at": "2026-03-05T15:30:00"
  }
}
```

#### `POST /api/v1/team/members/bulk`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

**リクエストボディ**
```json
{
  "team_page_id": 5,
  "members": [
    {
      "user_id": 10,
      "display_name": "田中太郎",
      "photo_s3_key": "profiles/tanaka.jpg",
      "bio": "よろしくお願いします！",
      "position": "キャプテン",
      "custom_fields": {
        "1": "10",
        "2": "A型"
      }
    },
    {
      "user_id": 11,
      "display_name": "鈴木花子",
      "photo_s3_key": null,
      "bio": "新メンバーです",
      "position": "マネージャー",
      "custom_fields": {
        "1": null,
        "2": "B型"
      }
    }
  ]
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "created_count": 2,
    "skipped_count": 0,
    "skipped_user_ids": []
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | 権限不足 |
| 404 | team_page_id が存在しない |
| 409 | 重複する user_id が含まれている |

#### `POST /api/v1/team/pages/{id}/copy-members`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

**リクエストボディ**
```json
{
  "source_page_id": 4
}
```

| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `source_page_id` | Long | ○ | コピー元ページ ID（通常は前年度ページ） |

**処理内容**
```
1. コピー元ページの member_profiles（is_visible = true）を取得
2. 各メンバーをコピー先ページに INSERT:
   - user_id, display_name, photo_s3_key, bio, position, custom_field_values, sort_order をコピー
   - is_visible = true（デフォルト）
3. コピー先に同じ user_id が既に存在する場合はスキップ
4. コピー後、ADMIN が卒業/退会メンバーを削除、新メンバーを追加する運用フロー
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "copied_count": 22,
    "skipped_count": 3,
    "skipped_user_ids": [15, 23, 41]
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | source_page_id 未指定、またはコピー元とコピー先が同一ページ |
| 403 | 権限不足、またはコピー元ページが異なるスコープ |
| 404 | コピー元ページが存在しない |

---

#### `POST /api/v1/gallery/albums`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

**リクエストボディ**
```json
{
  "team_id": 1,
  "title": "2026年春合宿",
  "description": "3月の合宿写真です",
  "event_date": "2026-03-01",
  "visibility": "ALL_MEMBERS",
  "allow_member_upload": true
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 8,
    "title": "2026年春合宿"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | 権限不足、またはギャラリーモジュールが未有効化 |

#### `POST /api/v1/gallery/albums/{id}/photos`

**認可**: ADMIN、DEPUTY_ADMIN（MANAGE_CONTENT）、または MEMBER（`allow_member_upload = true` のアルバム）

**リクエスト**: `multipart/form-data` または S3 Pre-signed URL 方式

Pre-signed URL 方式の場合:
```json
{
  "photos": [
    {
      "s3_key": "gallery/team-1/photo-001.jpg",
      "original_filename": "IMG_1234.jpg",
      "file_size": 3145728,
      "caption": "集合写真"
    },
    {
      "s3_key": "gallery/team-1/photo-002.jpg",
      "original_filename": "IMG_1235.jpg",
      "file_size": 2097152,
      "caption": null
    }
  ]
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "uploaded_count": 2,
    "album_photo_count": 15,
    "photos": [
      {
        "id": 101,
        "thumbnail_url": null,
        "processing_status": "PROCESSING"
      },
      {
        "id": 102,
        "thumbnail_url": null,
        "processing_status": "PROCESSING"
      }
    ]
  }
}
```

**備考**
- サムネイルは非同期生成。`processing_status = 'PROCESSING'` → 完了後に `thumbnail_url` が設定される
- 1リクエストあたり最大20枚
- EXIF から `taken_at`, `width`, `height` を自動抽出

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | ファイルサイズ超過（20MB）、非対応形式 |
| 403 | 権限不足（MEMBER で allow_member_upload = false） |
| 404 | アルバムが存在しない |

#### `GET /api/v1/gallery/albums/{id}`

**認可**: MEMBER 以上 + アルバムの `visibility` チェック

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `cursor` | Long | × | カーソル（前ページ最後の photo ID） |
| `limit` | Int | × | 取得件数（デフォルト: 30、最大: 100） |
| `sort` | String | × | `sort_order`（デフォルト）/ `taken_at` |

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 8,
    "title": "2026年春合宿",
    "description": "3月の合宿写真です",
    "event_date": "2026-03-01",
    "visibility": "ALL_MEMBERS",
    "allow_member_upload": true,
    "photo_count": 45,
    "photos": [
      {
        "id": 101,
        "file_url": "https://cdn.example.com/gallery/team-1/photo-001.jpg",
        "thumbnail_url": "https://cdn.example.com/gallery/team-1/thumb/photo-001.jpg",
        "caption": "集合写真",
        "width": 4032,
        "height": 3024,
        "taken_at": "2026-03-01T09:30:00",
        "uploaded_by": {
          "id": 10,
          "display_name": "田中太郎"
        }
      }
    ]
  },
  "meta": {
    "next_cursor": 130,
    "has_next": true
  }
}
```

#### `GET /api/v1/gallery/albums/{id}/download`

**認可**: MEMBER 以上 + アルバムの `visibility` チェック + `allow_download = true`

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `photo_ids` | String | × | 写真 ID（カンマ区切り。指定なし = アルバム全写真） |
| `limit` | Int | × | 最大枚数（デフォルト: 100、最大: 100） |

**処理内容**
```
1. allow_download = false の場合 → 403
2. 対象写真を S3 から取得し、サーバーサイドで ZIP 生成
3. ZIP を S3 の一時バケットに保存（有効期限: 1時間）
4. Pre-signed URL を返却
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "download_url": "https://s3.example.com/tmp/download-abc123.zip?...",
    "photo_count": 45,
    "expires_at": "2026-03-11T14:00:00"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | `allow_download = false`、権限不足、visibility 制限 |
| 404 | アルバムが存在しない |

#### `GET /api/v1/team/members`

**認可**: ページの `visibility` と `status` に応じたアクセス制御

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_page_id` | Long | ○ | ページ ID |
| `cursor` | Long | × | カーソル（前ページ最後の member_profile ID） |
| `limit` | Int | × | 取得件数（デフォルト: 50、最大: 100） |

**レスポンス（200 OK）**
```json
{
  "data": [...],
  "meta": {
    "next_cursor": 51,
    "has_next": true,
    "total_count": 85
  }
}
```

#### `PATCH /api/v1/team/members/reorder`

**認可**: ADMIN、または `MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN

**リクエストボディ**
```json
{
  "team_page_id": 5,
  "orders": [
    { "id": 1, "sort_order": 0 },
    { "id": 2, "sort_order": 1 },
    { "id": 3, "sort_order": 2 }
  ]
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "updated_count": 3
  }
}
```

**備考**
- `orders` の上限: 100件。超過時は 400 エラー
- 存在しない ID はスキップ（エラーにしない）

#### `POST /api/v1/team/pages/{id}/preview-token`

**認可**: ADMIN、`MANAGE_CONTENT` 権限を持つ DEPUTY_ADMIN、またはページの作成者

DRAFT 状態のメンバー紹介ページに対して24時間有効なプレビュー共有トークンを発行する。

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 5,
    "preview_token": "a1b2c3d4e5f6...",
    "preview_url": "https://example.com/team/pages/members-2026?preview_token=a1b2c3d4e5f6...",
    "expires_at": "2026-03-13T10:00:00"
  }
}
```

**備考**
- 既存トークンがある場合は上書き（再発行）
- `GET /team/pages/{id}?preview_token={token}` で DRAFT 状態のページを認証不要で閲覧可能。無効・期限切れは 410 Gone
- ページが PUBLISHED に遷移した場合、トークンを自動で NULL にリセット

#### `GET /api/v1/gallery/albums`

**認可**: MEMBER 以上 + visibility チェック

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | △ | チーム ID（team_id / organization_id のいずれか必須） |
| `organization_id` | Long | △ | 組織 ID |
| `q` | String | × | タイトル部分一致検索 |
| `from` | Date | × | event_date の開始日 |
| `to` | Date | × | event_date の終了日 |
| `visibility` | String | × | 公開範囲フィルタ（ADMIN/DEPUTY_ADMIN のみ） |
| `cursor` | Long | × | カーソル（前ページ最後のアルバム ID） |
| `limit` | Int | × | 取得件数（デフォルト: 20、最大: 50） |

**レスポンス（200 OK）**
```json
{
  "data": [...],
  "meta": {
    "next_cursor": 7,
    "has_next": true
  }
}
```

#### `PUT /api/v1/gallery/photos/{id}`

**認可**: アップロードした本人、ADMIN、または DEPUTY_ADMIN（MANAGE_CONTENT）

**リクエストボディ**
```json
{
  "caption": "集合写真（更新）",
  "sort_order": 5
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 101,
    "caption": "集合写真（更新）",
    "sort_order": 5,
    "updated_at": "2026-03-12T15:00:00"
  }
}
```

#### `GET /api/v1/gallery/photos/{id}/download`

**認可**: MEMBER 以上 + アルバムの `visibility` チェック + `allow_download = true`

**レスポンス（200 OK）**
```json
{
  "data": {
    "download_url": "https://s3.example.com/gallery/photo-001.jpg?...",
    "original_filename": "IMG_1234.jpg",
    "expires_at": "2026-03-12T14:30:00"
  }
}
```

**備考**
- Pre-signed URL（有効期限: 30分、`Content-Disposition: attachment`）
- `allow_download = false` の場合は 403

---

## 5. ビジネスロジック

### メンバー紹介ページ管理フロー

```
1. ADMIN がメインページ（MAIN）を作成 → status = DRAFT
2. セクションを追加: HEADING, TEXT, IMAGE, MEMBER_LIST を自由に配置
3. メンバープロフィールを登録（個別 or 一括）
4. プレビュー確認後、PATCH /publish で status = PUBLISHED
5. 翌年度:
   a. 新しい YEARLY ページを作成（year = 2027）
   b. POST /team/pages/{id}/copy-members で前年度ページからメンバーを一括コピー
   c. 卒業/退会メンバーを削除、新メンバーを追加
   d. メインページのセクションを更新して新年度ページへのリンクを追加
```

### ギャラリー写真アップロードフロー

```
1. フロントエンドが PUT /upload-url でアップロード用 Pre-signed URL を取得
2. ブラウザから S3 へ直接アップロード
3. POST /gallery/albums/{id}/photos で写真メタデータをバックエンドに登録
4. バックエンド非同期ジョブ:
   a. EXIF 情報抽出（撮影日時、幅、高さ）
   b. EXIF GPS 情報のストリップ（位置情報漏洩防止）
   c. サムネイル生成（短辺300px）
   d. HEIC → JPEG 変換（該当する場合）
   e. photos レコードを更新（thumbnail_s3_key, taken_at, width, height, content_type）
5. photo_albums.photo_count をアトミック更新
```

### ギャラリーの S3 ファイル削除

```
写真削除時:
1. photos レコードを物理削除
2. photo_albums.photo_count をアトミック減算
3. ApplicationEvent を発行 → 非同期リスナーが S3 ファイルを削除
   - s3_key のファイル
   - thumbnail_s3_key のファイル

アルバム論理削除時:
- photos レコードはカスケード削除されない（論理削除のため）
- 写真は引き続き S3 に存在

アルバム物理削除時（バッチ処理）:
- photos レコードをカスケード削除
- 全写真の S3 ファイルを非同期バッチで削除
```

### アクセス制御ロジック

```
メンバー紹介ページ:
1. status = PUBLISHED かつ visibility = PUBLIC → 認証不要
2. status = PUBLISHED かつ visibility = MEMBERS_ONLY → MEMBER 以上
3. status = DRAFT → ADMIN, MANAGE_CONTENT 権限保持者のみ

ギャラリー:
1. ギャラリーモジュールが有効化されていること（選択式モジュール No.6）
2. visibility = ALL_MEMBERS → チーム/組織の MEMBER 以上
3. visibility = SUPPORTERS_AND_ABOVE → SUPPORTER 以上
4. visibility = ADMIN_ONLY → ADMIN, DEPUTY_ADMIN のみ
5. 写真アップロード: ADMIN, DEPUTY_ADMIN, または allow_member_upload = true の場合 MEMBER
6. 写真削除: アップロードした本人、または ADMIN/DEPUTY_ADMIN
7. 写真ダウンロード: allow_download = true の場合のみ。閲覧権限を持つ全ユーザー
   - allow_download = false の場合: CloudFront Signed URL に Content-Disposition: inline を強制。
     フロントエンドで右クリック保存抑制（完全防止は不可能だがカジュアルコピー抑止として有効）
```

### ストレージクォータ

```
- チーム/組織あたりの上限:
  - 写真枚数: 5,000枚
  - 総容量: 10GB
- アップロード時に現在の使用量を確認し、超過時は 413 Payload Too Large を返却
- 使用量は photo_albums ごとの photo_count と photos.file_size の SUM で算出
- ADMIN は使用量をダッシュボードで確認可能（将来の利用状況 API で提供）
```

### S3 孤立ファイルクリーンアップ

```
対象: メンバープロフィール画像、ページカバー画像、セクション画像

- メンバープロフィール画像の更新時:
  1. 旧 photo_s3_key を取得
  2. 新しい photo_s3_key で UPDATE
  3. ApplicationEvent を発行 → 非同期リスナーが旧 S3 ファイルを削除

- ページカバー画像・セクション画像も同様のフロー
- 削除対象が NULL（画像未設定だった場合）は何もしない
```

### 論理削除の物理削除ポリシー

```
- 対象テーブル: team_pages, photo_albums
- 物理削除タイミング: 論理削除（deleted_at）から 90日後
- 実行間隔: 週次バッチ（日曜深夜2:00。F06_cms_blog と同一バッチ）
- 処理:
  1. team_pages: 関連する team_page_sections, member_profiles をカスケード削除
     - member_profiles.photo_s3_key の S3 ファイルを非同期削除
  2. photo_albums: 関連する photos をカスケード削除
     - 全写真の s3_key + thumbnail_s3_key を非同期バッチで S3 削除
  3. 物理 DELETE 実行
- 1回あたりの処理上限: 100件（写真数が多いため控えめに設定）
```

### サムネイル再生成

```
POST /api/v1/system-admin/gallery/regenerate-thumbnails

- 認可: SYSTEM_ADMIN のみ
- 用途: サムネイルサイズ変更、品質変更時に既存サムネイルを再生成
- 処理:
  1. 全 photos レコード（または team_id / organization_id 指定）を対象
  2. 非同期バッチジョブとして実行（202 Accepted を即時返却）
  3. 各写真の s3_key から元画像を取得し、サムネイルを再生成
  4. thumbnail_s3_key を更新
- リクエストボディ:
  { "team_id": 1 }  // 省略時は全チーム/組織
- レスポンス: { "data": { "job_id": "regen-thumb-xyz", "status": "PROCESSING" } }
```

---

## 6. セキュリティ考慮事項

- **認可チェック**: TeamPageService / GalleryService の入り口で所属検証
- **画像バリデーション**: アップロードされたファイルの Content-Type と Magic Bytes を検証（偽装防止）
- **EXIF プライバシー**: **全画像アップロード（ギャラリー写真・メンバープロフィール画像・ページカバー画像・セクション画像）**で GPS 情報を含む EXIF データをストリップする（位置情報漏洩防止）
- **S3 アクセス制御**: ギャラリー写真は CloudFront 署名付き URL（Signed URL）でのみアクセス可能。直接 S3 URL はブロック。s3_key から CDN URL への変換はアプリケーション層で実施
- **プレビュートークン**: SecureRandom で64文字のトークンを生成。有効期限24時間。推測不可能なエントロピーを確保
- **レートリミット**: 写真アップロード API に `Bucket4j` で 1分間に30回の制限
- **ファイルサイズ制限**: 1枚あたり20MB、1アルバムあたり500枚を上限
- **一括登録の保護**: `POST /team/members/bulk` は1リクエストあたり最大100件
- **ダウンロード制限**: `allow_download = false` のアルバムは一括ダウンロード API を 403 で拒否。CloudFront Signed URL に `Content-Disposition: inline` を強制し、ブラウザのダウンロードダイアログを抑制。ZIP 一括ダウンロードの一時ファイルは S3 ライフサイクルルールで1時間後に自動削除

---

## 7. Flywayマイグレーション

```
V6.015__create_team_pages.sql              -- team_pages テーブル作成
V6.016__create_team_page_sections.sql      -- team_page_sections テーブル作成
V6.017__create_member_profiles.sql         -- member_profiles テーブル作成
V6.018__create_member_profile_fields.sql   -- member_profile_fields テーブル作成
V6.019__create_photo_albums.sql            -- photo_albums テーブル作成
V6.020__create_photos.sql                  -- photos テーブル作成
V6.021__add_photo_albums_cover_fk.sql      -- photo_albums.cover_photo_id FK 追加（循環参照回避）
```

**マイグレーション上の注意点**
- 番号は V6.015 から開始（V6.001〜V6.014 は F06_cms_blog.md で使用済み）
- `team_pages` は `teams`, `organizations`, `users` テーブルに依存（Phase 1 で作成済み）
- `photo_albums.cover_photo_id` の FK は `photos` テーブル作成後に ALTER TABLE で追加
- ギャラリーテーブルは選択式モジュールだが、テーブル自体は常に作成（モジュール有効化チェックはアプリケーション層で実施）

---

## 8. 未解決事項

- [x] ~~①メンバー自身によるプロフィール編集~~ → **`team_pages.allow_self_edit` フラグで制御（承認なし・即時反映）**。TRUE の場合 MEMBER は自分の `bio`, `photo_s3_key`, `custom_field_values` を直接編集可能。`display_name`, `position`, `sort_order`, `is_visible` は ADMIN/DEPUTY_ADMIN のみ編集可能。不適切な内容は ADMIN が `is_visible = false` で対処
- [x] ~~②プロフィールフィールドの値格納方式~~ → **JSON カラム方式を採用**。`member_profiles` に `custom_field_values JSON` カラムを追加。格納形式: `{"field_id": "value", ...}`。EAV テーブル不要。理由: プロフィールは表示用途が主で数値集計不要、JSON なら1レコードで完結し一覧取得がシンプル、MySQL 8.0 の `JSON_EXTRACT` で個別検索にも対応可能
- [x] ~~③前年度メンバーのコピー機能~~ → **Phase 6 で実装**。`POST /api/v1/team/pages/{id}/copy-members` API を追加。ソースページの `is_visible = true` のメンバーを全員コピー（同一 `user_id` はスキップ）。コピー後に ADMIN が卒業/退会メンバーを削除、新メンバーを追加する運用フロー
- [x] ~~④ギャラリーの SUPPORTER 公開~~ → **`SUPPORTERS_AND_ABOVE` を visibility ENUM に追加**。`ENUM('ALL_MEMBERS', 'SUPPORTERS_AND_ABOVE', 'ADMIN_ONLY')` に拡張。PUBLIC（外部公開）は追加しない（プライバシー性の高いコンテンツのため認証必須の範囲に限定）
- [x] ~~⑤写真のダウンロード制限~~ → **`photo_albums.allow_download` フラグで制御**。TRUE = 個別ダウンロード（`GET /gallery/photos/{id}/download`）+ 一括ダウンロード（`GET /gallery/albums/{id}/download` で ZIP、最大100枚、S3 一時保存・1時間有効）。FALSE = 閲覧のみ（CloudFront Signed URL に `Content-Disposition: inline` 強制、ダウンロード API は 403）

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-10 | 初版作成 |
| 2026-03-11 | 未解決事項①〜⑤を解決: allow_self_edit フラグ追加、JSON カラム方式確定（custom_field_values）、前年度メンバーコピー API 追加、SUPPORTERS_AND_ABOVE 公開追加、allow_download フラグ＋一括ダウンロード API 追加 |
| 2026-03-11 | ページネーションレスポンスの has_more → has_next に改名（PaginationMeta 共通化） |
| 2026-03-12 | 設計改善: Flyway 番号衝突修正（V6.015〜V6.021 にリナンバリング）、全画像カラムを s3_key 方式に統一（F07 と整合）、photos に content_type・updated_at 追加、プレビュー共有トークン追加、メンバー並び替え API・個別写真ダウンロード API・写真更新 API・アルバム検索パラメータ・メンバー一覧 pagination 追加、ストレージクォータ（5,000枚/10GB）追加、S3 孤立ファイルクリーンアップ・論理→物理削除ポリシー・サムネイル再生成 API 追加、EXIF GPS ストリップを全画像に拡大適用、custom_field_values JSON バリデーション注意書き追加、0枚アルバムのプレースホルダー表示明記 |