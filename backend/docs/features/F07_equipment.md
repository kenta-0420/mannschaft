# F07: 備品管理

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 7
> **最終更新**: 2026-03-10
> **モジュール種別**: 選択式モジュール #5

---

## 1. 概要

チーム・組織が保有する備品・在庫を管理し、「誰が持っているか」の貸出ステータスを追跡する機能。備品の登録・編集・削除、メンバーへの貸出・返却の記録を行い、現在の貸出状況を一覧で確認できる。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全組織・チームの備品情報を参照 |
| ADMIN | 備品の作成・編集・削除、貸出・返却の管理、在庫状況の参照 |
| DEPUTY_ADMIN | `MANAGE_EQUIPMENT` 権限を持つ場合: 備品の作成・編集、貸出・返却の管理 |
| MEMBER | 備品一覧の閲覧、自分が借りている備品の確認 |
| SUPPORTER | 対象外 |
| GUEST | 対象外 |

### 対象レベル
- [x] 組織 (Organization)
- [x] チーム (Team)
- [ ] 個人 (Personal)

---

## 3. DB設計

### テーブル一覧
| テーブル名 | 役割 | 論理削除 |
|-----------|------|---------|
| `equipment_items` | 備品マスター | あり |
| `equipment_assignments` | 貸出・返却履歴 | なし |

### テーブル定義

#### `equipment_items`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams（チーム単位; NULL = 組織単位）|
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations（組織単位; NULL = チーム単位）|
| `name` | VARCHAR(200) | NO | — | 備品名（例: プロジェクター、ビブス赤 #3）|
| `description` | VARCHAR(500) | YES | NULL | 説明・備考 |
| `category` | VARCHAR(100) | YES | NULL | カテゴリ（例: 映像機器、ユニフォーム）|
| `quantity` | INT UNSIGNED | NO | 1 | 保有数量 |
| `status` | ENUM('AVAILABLE', 'ALL_ASSIGNED', 'MAINTENANCE', 'RETIRED') | NO | 'AVAILABLE' | 全体ステータス |
| `storage_location` | VARCHAR(200) | YES | NULL | 保管場所（例: 事務室棚A、倉庫B）|
| `purchase_date` | DATE | YES | NULL | 購入日 |
| `purchase_price` | DECIMAL(10,2) | YES | NULL | 購入金額 |
| `image_url` | VARCHAR(500) | YES | NULL | 備品画像 URL（S3）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**インデックス**
```sql
INDEX idx_ei_team_id (team_id)
INDEX idx_ei_organization_id (organization_id)
INDEX idx_ei_category (team_id, category)              -- カテゴリ別一覧用
INDEX idx_ei_status (team_id, status)                  -- ステータス別一覧用
```

**制約・備考**
- `team_id` と `organization_id` はどちらか一方のみ非 NULL（XOR）:
  ```sql
  CONSTRAINT chk_ei_scope
    CHECK (
      (team_id IS NOT NULL AND organization_id IS NULL)
      OR (team_id IS NULL AND organization_id IS NOT NULL)
    )
  ```
- 論理削除: `deleted_at DATETIME nullable`（SoftDeletableEntity 適用）
- `status` は denormalize: `equipment_assignments` の貸出状況から計算可能だが、一覧取得の高速化のために保持。貸出・返却時にアトミック更新
- `RETIRED` は廃棄済み備品（一覧から非表示だが記録は保持）

---

#### `equipment_assignments`

備品の貸出・返却を記録する履歴テーブル。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `equipment_item_id` | BIGINT UNSIGNED | NO | — | FK → equipment_items（ON DELETE RESTRICT）|
| `assigned_to_user_id` | BIGINT UNSIGNED | NO | — | FK → users（貸出先メンバー; ON DELETE RESTRICT）|
| `assigned_by_user_id` | BIGINT UNSIGNED | YES | NULL | FK → users（貸出操作者; SET NULL on delete）|
| `quantity` | INT UNSIGNED | NO | 1 | 貸出数量 |
| `assigned_at` | DATETIME | NO | CURRENT_TIMESTAMP | 貸出日時 |
| `expected_return_at` | DATE | YES | NULL | 返却予定日 |
| `returned_at` | DATETIME | YES | NULL | 実際の返却日時（NULL = 未返却）|
| `returned_by_user_id` | BIGINT UNSIGNED | YES | NULL | FK → users（返却操作者; SET NULL on delete）|
| `note` | VARCHAR(300) | YES | NULL | 備考 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
INDEX idx_ea_equipment (equipment_item_id)                              -- 備品ごとの貸出履歴
INDEX idx_ea_user (assigned_to_user_id, returned_at)                   -- ユーザーの貸出中一覧（returned_at IS NULL）
INDEX idx_ea_overdue (expected_return_at, returned_at)                  -- 返却遅延検知用
```

**制約・備考**
- `returned_at IS NULL` = 現在貸出中
- `equipment_item_id` ON DELETE RESTRICT: 貸出履歴がある備品は物理削除不可（論理削除を使用）
- `assigned_to_user_id` ON DELETE RESTRICT: 貸出中のユーザーは物理削除不可
- 貸出数量の整合性: `equipment_assignments` の未返却分（`returned_at IS NULL`）の `SUM(quantity)` が `equipment_items.quantity` を超えないようアプリ層で検証

### ER図（テキスト形式）
```
teams (1) ──── (N) equipment_items
organizations (1) ──── (N) equipment_items

equipment_items (1) ──── (N) equipment_assignments
users (1) ──── (N) equipment_assignments [assigned_to_user_id]
users (1) ──── (N) equipment_assignments [assigned_by_user_id]
```

---

## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/teams/{teamId}/equipment` | 必要 | チーム備品一覧取得 |
| GET | `/api/v1/organizations/{orgId}/equipment` | 必要 | 組織備品一覧取得 |
| POST | `/api/v1/teams/{teamId}/equipment` | 必要 | 備品作成（チーム） |
| POST | `/api/v1/organizations/{orgId}/equipment` | 必要 | 備品作成（組織） |
| GET | `/api/v1/teams/{teamId}/equipment/{id}` | 必要 | 備品詳細取得 |
| PUT | `/api/v1/teams/{teamId}/equipment/{id}` | 必要 | 備品情報更新 |
| DELETE | `/api/v1/teams/{teamId}/equipment/{id}` | 必要 | 備品削除（論理削除）|
| POST | `/api/v1/teams/{teamId}/equipment/{id}/assign` | 必要 | 備品を貸出 |
| PATCH | `/api/v1/teams/{teamId}/equipment/{id}/return` | 必要 | 備品を返却 |
| GET | `/api/v1/teams/{teamId}/equipment/{id}/history` | 必要 | 備品の貸出・返却履歴 |
| GET | `/api/v1/teams/{teamId}/equipment/overdue` | 必要 | 返却遅延一覧 |

### リクエスト／レスポンス仕様

#### `POST /api/v1/teams/{teamId}/equipment`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_EQUIPMENT`）

**リクエストボディ**
```json
{
  "name": "プロジェクター",
  "description": "会議室用 EPSON EB-W06",
  "category": "映像機器",
  "quantity": 2,
  "storage_location": "事務室棚A",
  "purchase_date": "2025-06-15",
  "purchase_price": 65000
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 1,
    "team_id": 10,
    "name": "プロジェクター",
    "description": "会議室用 EPSON EB-W06",
    "category": "映像機器",
    "quantity": 2,
    "status": "AVAILABLE",
    "available_quantity": 2,
    "storage_location": "事務室棚A",
    "purchase_date": "2025-06-15",
    "purchase_price": 65000,
    "image_url": null
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | 権限不足 |
| 404 | `teamId` が存在しない |

#### `POST /api/v1/teams/{teamId}/equipment/{id}/assign`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_EQUIPMENT`）

**リクエストボディ**
```json
{
  "assigned_to_user_id": 42,
  "quantity": 1,
  "expected_return_at": "2026-04-10",
  "note": "マネージャー会議用"
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "assignment_id": 100,
    "equipment_item_id": 1,
    "equipment_name": "プロジェクター",
    "assigned_to_user_id": 42,
    "assigned_to_display_name": "田中太郎",
    "quantity": 1,
    "assigned_at": "2026-03-10T10:00:00",
    "expected_return_at": "2026-04-10"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | 権限不足 |
| 404 | 備品 / ユーザーが存在しない |
| 409 | 在庫不足（available_quantity < 要求 quantity）|
| 422 | `assigned_to_user_id` がチームに所属していない |

#### `PATCH /api/v1/teams/{teamId}/equipment/{id}/return`

**認可**: ADMIN / DEPUTY_ADMIN（`MANAGE_EQUIPMENT`）

**リクエストボディ**
```json
{
  "assignment_id": 100,
  "note": "良品で返却"
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "assignment_id": 100,
    "returned_at": "2026-03-15T16:30:00",
    "equipment_status": "AVAILABLE",
    "available_quantity": 2
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | 権限不足 |
| 404 | `assignment_id` が存在しない |
| 409 | 既に返却済み |

#### `GET /api/v1/teams/{teamId}/equipment`

**認可**: MEMBER 以上

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `category` | String | No | カテゴリでフィルタ |
| `status` | String | No | ステータスでフィルタ（AVAILABLE, ALL_ASSIGNED, MAINTENANCE）|
| `name_like` | String | No | 備品名部分一致検索 |
| `page` | Integer | No | ページ番号（0始まり、デフォルト 0）|
| `size` | Integer | No | 件数（デフォルト 20、最大 100）|
| `sort` | String | No | ソート（デフォルト `name,asc`）|

**レスポンス（200 OK）**: `PagedResponse<EquipmentItemResponse>`
- 各備品には `available_quantity`（= `quantity` - 貸出中数量）を算出して含める

---

## 5. ビジネスロジック

### 主要フロー

#### 備品貸出
```
1. ADMIN/DEPUTY_ADMIN が貸出フォームを入力（対象メンバー・数量・返却予定日）
2. バックエンドがチーム所属・権限を検証
3. assigned_to_user_id のチーム/組織所属を検証
4. 在庫チェック: available_quantity >= 要求 quantity
5. equipment_assignments に INSERT + equipment_items.status を必要に応じて更新
   （全数貸出 → ALL_ASSIGNED）
6. トランザクション内でアトミックに実行
7. ApplicationEvent（EquipmentAssignedEvent）を発行
8. メンバーへ「備品が貸出されました」通知を送信
```

#### 備品返却
```
1. ADMIN/DEPUTY_ADMIN が返却操作を実行
2. equipment_assignments.returned_at を SET + equipment_items.status を更新
   （ALL_ASSIGNED → AVAILABLE）
3. トランザクション内でアトミックに実行
```

#### 返却遅延通知（バッチ）
```
1. 日次バッチで expected_return_at < 本日 かつ returned_at IS NULL のレコードを検索
2. 対象メンバーと管理者に返却遅延通知を送信
```

### 重要な判定ロジック
- **available_quantity の計算**: `equipment_items.quantity` - `SUM(equipment_assignments.quantity WHERE returned_at IS NULL AND equipment_item_id = ?)`
- **status 自動更新**: `available_quantity = 0` → `ALL_ASSIGNED`、`available_quantity > 0` → `AVAILABLE`（`MAINTENANCE`・`RETIRED` は手動設定のみ）
- **論理削除制約**: 貸出中（未返却）の備品は論理削除不可。先にすべて返却する必要がある

---

## 6. セキュリティ考慮事項

- **認可チェック**: `EquipmentService` の入り口で `teamId`/`orgId` と `currentUser` の所属・ロールを検証
- **MEMBER の操作制限**: MEMBER は閲覧のみ。貸出・返却の操作は ADMIN/DEPUTY_ADMIN に限定
- **画像アップロード**: 備品画像は S3 にアップロード。ファイルサイズ上限 5MB、許可形式 JPEG/PNG/WebP
- **レートリミット**: 備品作成 API に `Bucket4j` で 1分間に30回の制限を適用

---

## 7. Flywayマイグレーション

```
V7.010__create_equipment_items_table.sql
V7.011__create_equipment_assignments_table.sql
```

**マイグレーション上の注意点**
- `equipment_items` は `teams`・`organizations` テーブルへの FK + XOR CHECK 制約を持つ
- `equipment_assignments` は `equipment_items`・`users` テーブルへの FK を持つ

---

## 8. 未解決事項

- [ ] 備品画像の複数枚アップロード対応（1枚 or 複数枚）
- [ ] カテゴリをマスターテーブル化するか、自由入力のままにするか
- [ ] 備品の棚卸し（定期的な在庫確認）機能を Phase 7 に含めるか
- [ ] MEMBER が自分で備品貸出をリクエストする機能（承認フロー付き）の要否

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-10 | 初版作成 |
