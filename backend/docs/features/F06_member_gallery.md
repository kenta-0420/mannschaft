# F06: メンバー紹介 + ギャラリー

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 6
> **最終更新**: 2026-03-10

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
| SUPPORTER | 対象外（メンバーのみが閲覧・アップロードできる） |
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
| `cover_image_url` | VARCHAR(500) | YES | NULL | カバー画像 URL（S3） |
| `visibility` | ENUM('PUBLIC', 'MEMBERS_ONLY') | NO | 'MEMBERS_ONLY' | 公開範囲 |
| `status` | ENUM('DRAFT', 'PUBLISHED') | NO | 'DRAFT' | 公開ステータス |
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
| `image_url` | VARCHAR(500) | YES | NULL | 画像 URL（IMAGE 用、S3） |
| `image_caption` | VARCHAR(200) | YES | NULL | 画像キャプション |
| `sort_order` | INT | NO | 0 | 表示順 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_tps_page_order (team_page_id, sort_order)  -- ページ内セクション表示順
```

**制約・備考**
- `section_type = 'MEMBER_LIST'` の場合、このセクション位置にそのページの `member_profiles` 一覧を表示する。`content`, `image_url` は NULL
- ページ削除時にカスケード削除
- 物理削除

#### `member_profiles`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_page_id` | BIGINT UNSIGNED | NO | — | FK → team_pages（CASCADE） |
| `user_id` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete。退会後も掲載保持） |
| `display_name` | VARCHAR(100) | NO | — | 表示名（登録時のスナップショット） |
| `photo_url` | VARCHAR(500) | YES | NULL | プロフィール画像 URL（S3） |
| `bio` | VARCHAR(500) | YES | NULL | 一言・自己紹介 |
| `position` | VARCHAR(100) | YES | NULL | 役職・ポジション |
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
- `user_id` が NULL になった場合でも `display_name` と `photo_url` で掲載を継続
- MEMBER は自分の `bio` のみ編集可能（ADMIN が `allow_self_edit` を有効にした場合。フラグは `team_pages` に持たせる案あり → 未解決事項①）
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
- フィールド定義はチーム/組織全体で共通。各ページの `member_profiles` にフィールド値を格納
- プロフィールフィールドの値は `member_profiles` に JSON カラムとして持つか、EAV テーブルを別途作るか → 未解決事項②
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
| `visibility` | ENUM('ALL_MEMBERS', 'ADMIN_ONLY') | NO | 'ALL_MEMBERS' | 閲覧権限 |
| `allow_member_upload` | BOOLEAN | NO | FALSE | MEMBER による写真アップロード許可 |
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
| `file_url` | VARCHAR(500) | NO | — | 写真ファイル URL（S3） |
| `thumbnail_url` | VARCHAR(500) | YES | NULL | サムネイル URL（S3。自動生成） |
| `original_filename` | VARCHAR(255) | NO | — | 元ファイル名 |
| `file_size` | INT UNSIGNED | NO | — | ファイルサイズ（bytes） |
| `width` | INT UNSIGNED | YES | NULL | 画像幅（px） |
| `height` | INT UNSIGNED | YES | NULL | 画像高さ（px） |
| `caption` | VARCHAR(300) | YES | NULL | キャプション |
| `taken_at` | DATETIME | YES | NULL | 撮影日時（EXIF から自動抽出） |
| `sort_order` | INT | NO | 0 | 表示順 |
| `uploaded_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

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
- アップロード可能な形式: JPEG, PNG, WebP, HEIC（HEIC は JPEG に変換して保存）
- 1枚あたりの最大ファイルサイズ: 20MB（アプリケーション層でバリデーション）

### ER図（テキスト形式）

```
teams/organizations (1) ──── (N) team_pages
team_pages          (1) ──── (N) team_page_sections
team_pages          (1) ──── (N) member_profiles
member_profiles     (N) ──── (1) users

teams/organizations (1) ──── (N) member_profile_fields
member_profiles     ── custom field values ── member_profile_fields (※値の格納方式は未解決事項②)

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
  "cover_image_url": "https://s3.example.com/pages/cover-789.jpg",
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
    "cover_image_url": "https://s3.example.com/pages/cover-789.jpg",
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
        "photo_url": "https://s3.example.com/profiles/tanaka.jpg",
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
      "photo_url": "https://s3.example.com/profiles/tanaka.jpg",
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
      "photo_url": null,
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
      "file_url": "https://s3.example.com/gallery/photo-001.jpg",
      "original_filename": "IMG_1234.jpg",
      "file_size": 3145728,
      "caption": "集合写真"
    },
    {
      "file_url": "https://s3.example.com/gallery/photo-002.jpg",
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
        "status": "PROCESSING"
      },
      {
        "id": 102,
        "thumbnail_url": null,
        "status": "PROCESSING"
      }
    ]
  }
}
```

**備考**
- サムネイルは非同期生成。`status = 'PROCESSING'` → 完了後に `thumbnail_url` が設定される
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
        "file_url": "https://s3.example.com/gallery/photo-001.jpg",
        "thumbnail_url": "https://s3.example.com/gallery/thumb/photo-001.jpg",
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
    "has_more": true
  }
}
```

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
   b. メンバーを新規登録 or 前年度ページからコピー（将来拡張）
   c. メインページのセクションを更新して新年度ページへのリンクを追加
```

### ギャラリー写真アップロードフロー

```
1. フロントエンドが PUT /upload-url でアップロード用 Pre-signed URL を取得
2. ブラウザから S3 へ直接アップロード
3. POST /gallery/albums/{id}/photos で写真メタデータをバックエンドに登録
4. バックエンド非同期ジョブ:
   a. EXIF 情報抽出（撮影日時、幅、高さ）
   b. サムネイル生成（短辺300px）
   c. HEIC → JPEG 変換（該当する場合）
   d. photos レコードを更新（thumbnail_url, taken_at, width, height）
5. photo_albums.photo_count をアトミック更新
```

### ギャラリーの S3 ファイル削除

```
写真削除時:
1. photos レコードを物理削除
2. photo_albums.photo_count をアトミック減算
3. ApplicationEvent を発行 → 非同期リスナーが S3 ファイルを削除
   - file_url のファイル
   - thumbnail_url のファイル

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
3. visibility = ADMIN_ONLY → ADMIN, DEPUTY_ADMIN のみ
4. 写真アップロード: ADMIN, DEPUTY_ADMIN, または allow_member_upload = true の場合 MEMBER
5. 写真削除: アップロードした本人、または ADMIN/DEPUTY_ADMIN
```

---

## 6. セキュリティ考慮事項

- **認可チェック**: TeamPageService / GalleryService の入り口で所属検証
- **画像バリデーション**: アップロードされたファイルの Content-Type と Magic Bytes を検証（偽装防止）
- **EXIF プライバシー**: GPS 情報が含まれる EXIF データは保存時にストリップする（位置情報漏洩防止）
- **S3 アクセス制御**: ギャラリー写真は CloudFront 署名付き URL（Signed URL）でのみアクセス可能。直接 S3 URL はブロック
- **レートリミット**: 写真アップロード API に `Bucket4j` で 1分間に30回の制限
- **ファイルサイズ制限**: 1枚あたり20MB、1アルバムあたり500枚を上限
- **一括登録の保護**: `POST /team/members/bulk` は1リクエストあたり最大100件

---

## 7. Flywayマイグレーション

```
V6.012__create_team_pages.sql              -- team_pages テーブル作成
V6.013__create_team_page_sections.sql      -- team_page_sections テーブル作成
V6.014__create_member_profiles.sql         -- member_profiles テーブル作成
V6.015__create_member_profile_fields.sql   -- member_profile_fields テーブル作成
V6.016__create_photo_albums.sql            -- photo_albums テーブル作成
V6.017__create_photos.sql                  -- photos テーブル作成
V6.018__add_photo_albums_cover_fk.sql      -- photo_albums.cover_photo_id FK 追加（循環参照回避）
```

**マイグレーション上の注意点**
- `team_pages` は `teams`, `organizations`, `users` テーブルに依存（Phase 1 で作成済み）
- `photo_albums.cover_photo_id` の FK は `photos` テーブル作成後に ALTER TABLE で追加
- ギャラリーテーブルは選択式モジュールだが、テーブル自体は常に作成（モジュール有効化チェックはアプリケーション層で実施）

---

## 8. 未解決事項

- [ ] ①メンバー自身によるプロフィール編集: MEMBER が自分の `bio` や写真を直接編集できるか、ADMIN 承認制にするか。`team_pages.allow_self_edit` フラグで制御する案
- [ ] ②プロフィールフィールドの値格納方式: `member_profiles` に `custom_fields JSON` カラムを追加する方式か、`member_profile_values`（EAV）テーブルを別途作成する方式か。JSON 方式はシンプルだがフィールド単位の検索・集計が困難
- [ ] ③前年度メンバーのコピー機能: 年度更新時に前年度ページのメンバーを新年度ページにコピーする機能の要否
- [ ] ④ギャラリーの SUPPORTER 公開: 現在の設計ではメンバー限定だが、SUPPORTER にも公開するオプションを追加するか
- [ ] ⑤写真のダウンロード制限: メンバーによる一括ダウンロードを許可するか、閲覧のみに制限するか

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-10 | 初版作成 |
