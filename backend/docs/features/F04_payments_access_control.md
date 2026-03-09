# F04: 支払い管理・コンテンツアクセス制御

> **ステータス**: 🟡 設計中
> **実装フェーズ**: Phase 3
> **最終更新**: 2026-03-10

---

## 1. 概要

チーム・組織内での年会費・月謝・アイテム代金等の支払いを管理し、支払い状況に応じてコンテンツへのアクセスを制御する。Stripe によるオンライン決済と、ADMIN が現金・振込等を手動で記録するオフライン決済のハイブリッド方式に対応する。アクセス制御は「チーム/組織全体のロック」と「特定コンテンツ単位のゲーティング」の両方をサポートし、ADMIN が用途に応じて使い分けられる。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全支払い記録の参照・強制修正 |
| ADMIN | 支払い項目の作成・管理、メンバーの支払い状況参照・手動記録・修正、アクセス要件設定 |
| DEPUTY_ADMIN | `MANAGE_PAYMENTS` 権限を持つ場合: 支払い状況参照・手動記録 |
| MEMBER | 自分の支払い状況参照、Stripe 決済の実行 |
| SUPPORTER | 自分の支払い状況参照（支払い要件が設定されている場合）|
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
| `payment_items` | 支払い項目定義（チーム/組織ごとに ADMIN が作成）| あり |
| `stripe_customers` | ユーザーの Stripe 顧客 ID 管理 | なし |
| `member_payments` | 支払い記録（Stripe 自動 / ADMIN 手動）| なし（status で管理）|
| `team_access_requirements` | チーム全体ロックに必要な支払い項目の紐付け | なし |
| `organization_access_requirements` | 組織全体ロックに必要な支払い項目の紐付け | なし |
| `content_payment_gates` | コンテンツ単位のアクセスゲート設定（ポリモーフィック）| なし |

### テーブル定義

#### `payment_items`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | YES | NULL | FK → teams（チーム単位; NULL = 組織単位）|
| `organization_id` | BIGINT UNSIGNED | YES | NULL | FK → organizations（組織単位; NULL = チーム単位）|
| `name` | VARCHAR(100) | NO | — | 項目名（例: 2025年度 年会費）|
| `description` | VARCHAR(500) | YES | NULL | 説明 |
| `type` | ENUM('ANNUAL_FEE', 'MONTHLY_FEE', 'ITEM') | NO | — | 種別（年会費 / 月謝 / アイテム代金）|
| `amount` | DECIMAL(10,2) | NO | — | 金額 |
| `currency` | CHAR(3) | NO | 'JPY' | 通貨コード（ISO 4217）|
| `stripe_product_id` | VARCHAR(100) | YES | NULL | Stripe Product ID（オンライン決済利用時）|
| `stripe_price_id` | VARCHAR(100) | YES | NULL | Stripe Price ID |
| `is_active` | BOOLEAN | NO | TRUE | FALSE = 新規支払い受け付け停止（既存記録は有効）|
| `created_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**インデックス**
```sql
INDEX idx_pi_team_id (team_id)
INDEX idx_pi_organization_id (organization_id)
INDEX idx_pi_stripe_price (stripe_price_id)   -- webhook 受信時の逆引き用
```

**制約・備考**
- `team_id` と `organization_id` はどちらか一方のみ非 NULL（XOR; アプリ層でバリデーション）
- `stripe_price_id` を持つ場合のみオンライン決済（Stripe Checkout）が利用可能
- 論理削除時は `team_access_requirements` / `organization_access_requirements` / `content_payment_gates` の関連レコードをアプリ層でカスケード削除する

---

#### `stripe_customers`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE CASCADE）|
| `stripe_customer_id` | VARCHAR(100) | NO | — | Stripe Customer ID（cus_xxxxxxxxxx）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_sc_user (user_id)
UNIQUE KEY uq_sc_stripe_customer (stripe_customer_id)
```

**制約・備考**
- ユーザーが初めて Stripe 決済を行う際にレコードを作成する（遅延生成）
- 1ユーザーにつき1つの Stripe Customer を使い回す（複数チームへの支払いも同一 Customer）

---

#### `member_payments`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users |
| `payment_item_id` | BIGINT UNSIGNED | NO | — | FK → payment_items |
| `amount_paid` | DECIMAL(10,2) | NO | — | 実際に支払った金額（割引・調整後）|
| `currency` | CHAR(3) | NO | 'JPY' | 通貨コード |
| `payment_method` | ENUM('STRIPE', 'MANUAL') | NO | — | 支払い方法 |
| `status` | ENUM('PENDING', 'PAID', 'REFUNDED', 'CANCELLED') | NO | 'PENDING' | 支払いステータス |
| `valid_from` | DATE | YES | NULL | 有効期間開始日（NULL = paid_at 当日から）|
| `valid_until` | DATE | YES | NULL | 有効期間終了日（NULL = 無期限）|
| `stripe_checkout_session_id` | VARCHAR(100) | YES | NULL | Stripe Checkout Session ID |
| `stripe_payment_intent_id` | VARCHAR(100) | YES | NULL | Stripe Payment Intent ID |
| `paid_at` | DATETIME | YES | NULL | 実際の支払い完了日時 |
| `recorded_by` | BIGINT UNSIGNED | YES | NULL | FK → users（手動記録した ADMIN; SET NULL on delete）|
| `note` | VARCHAR(500) | YES | NULL | 備考（振込日・現金確認メモ等）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
INDEX idx_mp_user_item (user_id, payment_item_id)
INDEX idx_mp_payment_item (payment_item_id)
INDEX idx_mp_status (status)
INDEX idx_mp_valid_until (valid_until)                              -- 期限切れバッチ用
UNIQUE KEY uq_mp_checkout_session (stripe_checkout_session_id)     -- NULL 除く
UNIQUE KEY uq_mp_payment_intent (stripe_payment_intent_id)         -- NULL 除く
```

**制約・備考**
- `status = 'PENDING'` は Stripe Checkout セッション作成後・支払い完了前の状態。MANUAL 支払いは直接 `PAID` で INSERT する
- `valid_from` / `valid_until` は ADMIN が手動で任意設定可能（例: 4月〜翌3月の年度区切り）。Stripe 決済時はアプリ層で自動計算（ANNUAL_FEE = +365日 / MONTHLY_FEE = +31日 / ITEM = NULL）
- 有効判定: `status = 'PAID'` AND (`valid_until IS NULL` OR `valid_until >= CURDATE()`)
- `REFUNDED` は Stripe 払い戻し完了 webhook または ADMIN 手動操作で設定する
- 同一ユーザー・同一 payment_item に対する有効な PAID レコードの重複はアプリ層で防止する（409）

---

#### `team_access_requirements`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams（ON DELETE CASCADE）|
| `payment_item_id` | BIGINT UNSIGNED | NO | — | FK → payment_items |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_tar_team_item (team_id, payment_item_id)
INDEX idx_tar_payment_item (payment_item_id)
```

**制約・備考**
- チームへのアクセスに複数の支払い項目を要求できる（M:N）。**全件が有効な PAID 状態**の場合のみアクセス可
- ADMIN は `GET /teams/{id}/members` のレスポンスに各メンバーの支払い状況を付加して確認できる

---

#### `organization_access_requirements`

`team_access_requirements` と同一の設計。`team_id` の代わりに `organization_id` FK を持つ。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `organization_id` | BIGINT UNSIGNED | NO | — | FK → organizations（ON DELETE CASCADE）|
| `payment_item_id` | BIGINT UNSIGNED | NO | — | FK → payment_items |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_oar_org_item (organization_id, payment_item_id)
INDEX idx_oar_payment_item (payment_item_id)
```

---

#### `content_payment_gates`

コンテンツ単位のアクセスゲート。各コンテンツモジュール（投稿・ファイル・お知らせ・スケジュール）の実装時に参照するポリモーフィック設計。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `payment_item_id` | BIGINT UNSIGNED | NO | — | FK → payment_items |
| `content_type` | ENUM('POST', 'FILE', 'ANNOUNCEMENT', 'SCHEDULE') | NO | — | コンテンツ種別 |
| `content_id` | BIGINT UNSIGNED | NO | — | 対象コンテンツの ID |
| `created_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_cpg_item_content (payment_item_id, content_type, content_id)
INDEX idx_cpg_content (content_type, content_id)   -- コンテンツ取得時の逆引き用
```

**制約・備考**
- 1つのコンテンツに複数の `payment_item` を紐付け可能（**全件**有効な PAID 状態で閲覧可）
- コンテンツ削除時は各コンテンツモジュールのアプリ層でこのテーブルのレコードもカスケード削除する
- `content_id` は各コンテンツテーブルへの論理 FK（物理 FK は設定しない）

---

### ER図（テキスト形式）
```
payment_items (N) ──── (M) teams                    ※ via team_access_requirements
payment_items (N) ──── (M) organizations             ※ via organization_access_requirements
payment_items (1) ──── (N) member_payments
payment_items (1) ──── (N) content_payment_gates
users (1) ──── (1) stripe_customers
users (1) ──── (N) member_payments
```

---

## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/teams/{id}/payment-items` | 必要（ADMIN）| 支払い項目一覧 |
| POST | `/api/v1/teams/{id}/payment-items` | 必要（ADMIN）| 支払い項目作成 |
| PATCH | `/api/v1/teams/{id}/payment-items/{itemId}` | 必要（ADMIN）| 支払い項目更新 |
| DELETE | `/api/v1/teams/{id}/payment-items/{itemId}` | 必要（ADMIN）| 支払い項目論理削除 |
| GET | `/api/v1/teams/{id}/payment-items/{itemId}/payments` | 必要（ADMIN）| メンバー支払い状況一覧（未払い含む）|
| POST | `/api/v1/teams/{id}/payment-items/{itemId}/payments` | 必要（ADMIN）| 手動支払い記録 |
| PATCH | `/api/v1/teams/{id}/payment-items/{itemId}/payments/{paymentId}` | 必要（ADMIN）| 支払い記録修正（金額・期間・メモ）|
| DELETE | `/api/v1/teams/{id}/payment-items/{itemId}/payments/{paymentId}` | 必要（ADMIN）| 支払い記録取り消し（CANCELLED）|
| GET | `/api/v1/teams/{id}/access-requirements` | 必要（ADMIN）| チーム全体ロック設定の確認 |
| PUT | `/api/v1/teams/{id}/access-requirements` | 必要（ADMIN）| チーム全体ロック設定（一括指定）|
| GET | `/api/v1/organizations/{id}/payment-items` | 必要（ADMIN）| 組織支払い項目一覧 |
| POST | `/api/v1/organizations/{id}/payment-items` | 必要（ADMIN）| 組織支払い項目作成 |
| PATCH | `/api/v1/organizations/{id}/payment-items/{itemId}` | 必要（ADMIN）| 組織支払い項目更新 |
| DELETE | `/api/v1/organizations/{id}/payment-items/{itemId}` | 必要（ADMIN）| 組織支払い項目論理削除 |
| GET | `/api/v1/organizations/{id}/payment-items/{itemId}/payments` | 必要（ADMIN）| 組織メンバー支払い状況一覧 |
| POST | `/api/v1/organizations/{id}/payment-items/{itemId}/payments` | 必要（ADMIN）| 組織手動支払い記録 |
| PATCH | `/api/v1/organizations/{id}/payment-items/{itemId}/payments/{paymentId}` | 必要（ADMIN）| 組織支払い記録修正 |
| DELETE | `/api/v1/organizations/{id}/payment-items/{itemId}/payments/{paymentId}` | 必要（ADMIN）| 組織支払い記録取り消し |
| GET | `/api/v1/organizations/{id}/access-requirements` | 必要（ADMIN）| 組織全体ロック設定の確認 |
| PUT | `/api/v1/organizations/{id}/access-requirements` | 必要（ADMIN）| 組織全体ロック設定 |
| POST | `/api/v1/payment-items/{itemId}/checkout` | 必要（MEMBER+）| Stripe Checkout セッション作成（オンライン決済開始）|
| GET | `/api/v1/me/payments` | 必要 | 自分の支払い状況一覧 |
| POST | `/api/v1/webhooks/stripe` | 署名検証のみ | Stripe Webhook 受信 |

### リクエスト／レスポンス仕様

#### `POST /api/v1/teams/{id}/payment-items`

**リクエストボディ**
```json
{
  "name": "2025年度 年会費",
  "description": "2025年4月〜2026年3月分",
  "type": "ANNUAL_FEE",
  "amount": 5000,
  "currency": "JPY",
  "is_active": true
}
```

> - `is_active` は省略可（デフォルト `true`）。`true` の場合はバックエンドが Stripe Product / Price を自動生成する
> - `stripe_price_id` を直接指定することも可能（既存 Stripe 運用からの移行目的）。その場合はバックエンドが Stripe から金額・通貨を取得し `amount` / `currency` と一致するか検証する（詳細は「Stripe Price 管理フロー」参照）

**レスポンス（201 Created）**

`is_active = true` で作成した場合（Stripe Price 自動生成済み）:
```json
{
  "data": {
    "id": 1,
    "name": "2025年度 年会費",
    "type": "ANNUAL_FEE",
    "amount": 5000,
    "currency": "JPY",
    "is_active": true,
    "stripe_product_id": "prod_xxxxxxxxxxxxxxxx",
    "stripe_price_id": "price_xxxxxxxxxxxxxxxx",
    "created_at": "2026-03-01T10:00:00Z"
  }
}
```

`is_active = false` で作成した場合（オフライン専用・Stripe 未連携）:
```json
{
  "data": {
    "id": 2,
    "name": "現金払い専用 月謝",
    "type": "MONTHLY_FEE",
    "amount": 3000,
    "currency": "JPY",
    "is_active": false,
    "stripe_product_id": null,
    "stripe_price_id": null,
    "created_at": "2026-03-01T10:00:00Z"
  }
}
```

**エラーレスポンス（作成時）**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（amount ≤ 0、不正な currency 等）|
| 403 | 権限不足 |
| 422 | `stripe_price_id` 手動指定時に Stripe 上の金額・通貨と不一致 |
| 502 | Stripe API との通信失敗（DB ロールバック済み）|

---

#### `PATCH /api/v1/teams/{id}/payment-items/{itemId}`

`name` / `description` / `amount` / `currency` / `is_active` を部分更新する。

**リクエストボディ**（変更するフィールドのみ指定）
```json
{
  "amount": 6000
}
```

> - `amount` または `currency` を変更した場合、既存の Stripe Price をアーカイブし新しい Price を自動生成する（Stripe Price は作成後に金額変更不可のため）
> - `is_active: false` に変更した場合、既存の Stripe Price を Stripe 側でアーカイブする
> - `is_active: true` に再変更した場合（アーカイブ済み Price は再有効化不可のため）新規 Price を自動生成する
> - `stripe_price_id` を直接指定することも可能（移行目的）。指定時は Stripe からの金額・通貨バリデーションを実施する

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 1,
    "name": "2025年度 年会費",
    "type": "ANNUAL_FEE",
    "amount": 6000,
    "currency": "JPY",
    "is_active": true,
    "stripe_product_id": "prod_xxxxxxxxxxxxxxxx",
    "stripe_price_id": "price_yyyyyyyyyyyyyyyy",
    "updated_at": "2026-04-01T10:00:00Z"
  }
}
```

**エラーレスポンス（更新時）**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | 権限不足 |
| 404 | 支払い項目が存在しない / 論理削除済み |
| 422 | `stripe_price_id` 手動指定時に Stripe 上の金額・通貨と不一致 |
| 502 | Stripe API 通信失敗（DB ロールバック済み）|

---

#### `POST /api/v1/teams/{id}/payment-items/{itemId}/payments`（手動記録）

**リクエストボディ**
```json
{
  "user_id": 42,
  "amount_paid": 5000,
  "paid_at": "2026-04-01T00:00:00Z",
  "valid_from": "2026-04-01",
  "valid_until": "2027-03-31",
  "note": "現金払い確認済み"
}
```

> `valid_from` / `valid_until` は任意。省略時は `paid_at` 当日から `type` に応じて自動計算（ANNUAL_FEE = +365日 / MONTHLY_FEE = +31日 / ITEM = NULL）。

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 10,
    "user_id": 42,
    "payment_item_id": 1,
    "amount_paid": 5000,
    "payment_method": "MANUAL",
    "status": "PAID",
    "valid_from": "2026-04-01",
    "valid_until": "2027-03-31",
    "paid_at": "2026-04-01T00:00:00Z",
    "note": "現金払い確認済み"
  }
}
```

---

#### `GET /api/v1/teams/{id}/payment-items/{itemId}/payments`

支払い項目ごとの全メンバー支払い状況を返す。未払いメンバーも含む。

**クエリパラメータ**
| パラメータ | 型 | 説明 |
|-----------|---|------|
| `status` | String | `PAID` / `PENDING` / `UNPAID`（未レコード含む）でフィルタ |
| `page` | Int | ページ番号（0始まり）|
| `size` | Int | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "user_id": 42,
      "display_name": "田中太郎",
      "payment_status": "PAID",
      "amount_paid": 5000,
      "payment_method": "MANUAL",
      "valid_from": "2026-04-01",
      "valid_until": "2027-03-31",
      "paid_at": "2026-04-01T00:00:00Z"
    },
    {
      "user_id": 55,
      "display_name": "鈴木花子",
      "payment_status": "UNPAID",
      "amount_paid": null,
      "payment_method": null,
      "valid_from": null,
      "valid_until": null,
      "paid_at": null
    }
  ],
  "meta": {
    "page": 0,
    "size": 50,
    "total_elements": 24,
    "total_pages": 1,
    "paid_count": 18,
    "unpaid_count": 6
  }
}
```

---

#### `POST /api/v1/payment-items/{itemId}/checkout`（Stripe 決済開始）

**リクエストボディ**
```json
{
  "success_url": "https://mannschaft.app/payment/success",
  "cancel_url": "https://mannschaft.app/payment/cancel"
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "checkout_url": "https://checkout.stripe.com/pay/cs_test_xxxxxxxx",
    "session_id": "cs_test_xxxxxxxx",
    "expires_at": "2026-03-02T10:00:00Z"
  }
}
```

> フロントエンドは `checkout_url` にリダイレクトする。Checkout セッションの有効期限は Stripe デフォルト（30分）。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 409 | 対象ユーザーの有効な PAID レコードが既に存在する |
| 422 | `stripe_price_id` が未設定（オンライン決済が有効化されていない）|

---

#### `PUT /api/v1/teams/{id}/access-requirements`

チーム全体ロックに必要な支払い項目を一括設定する。空配列でロック解除。

**リクエストボディ**
```json
{
  "payment_item_ids": [1, 3]
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "team_id": 10,
    "required_payment_items": [
      {"id": 1, "name": "2025年度 年会費"},
      {"id": 3, "name": "ユニフォーム代金"}
    ]
  }
}
```

---

#### `POST /api/v1/webhooks/stripe`

**ヘッダー**
```
Stripe-Signature: t=xxxx,v1=xxxx
```

**リクエストボディ**: Stripe の生 JSON イベントペイロード（Content-Type: application/json）

**対応イベント**
| Stripe イベント | 処理内容 |
|----------------|---------|
| `checkout.session.completed` | `member_payments.status` を PAID に更新、`paid_at` / `valid_until` を設定 |
| `payment_intent.payment_failed` | `member_payments.status` を CANCELLED に更新 |
| `charge.refunded` | `member_payments.status` を REFUNDED に更新 |

**レスポンス**
- 200 OK（処理成功。Stripe の再送防止のため必ず返す）
- 400（署名検証失敗）

**エラーレスポンス（共通）**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 401 | 未認証 |
| 403 | 権限不足 |
| 404 | リソース不存在 |
| 409 | 競合（有効な PAID レコードが既存）|
| 422 | ビジネスロジックエラー（Stripe 未設定での Checkout 開始等）|

---

## 5. ビジネスロジック

### 支払い項目作成フロー

```
1. POST /api/v1/teams/{id}/payment-items を受付
2. 操作者が当該チームの ADMIN か確認
3. バリデーション（amount > 0、currency は許容リスト内）
4. payment_items に INSERT（stripe_product_id / stripe_price_id は NULL のまま）
5. is_active = TRUE の場合 → Stripe Price 管理フロー「A. 自動作成」を同期実行:
   - 成功: stripe_product_id / stripe_price_id を payment_items に UPDATE
   - 失敗（Stripe API エラー）: payment_items を DELETE してロールバック → 502 を返す
   ※ is_active = FALSE の場合は Stripe API を呼び出さない（stripe_price_id = NULL のまま）
6. audit_logs に PAYMENT_ITEM_CREATED を記録
7. 201 Created を返す
```

---

### Stripe Price 管理フロー

> Stripe API は同期呼び出しで実装する。Stripe 失敗時は DB の変更をロールバックし不整合（アプリ更新済み / Stripe 未更新）を防ぐ。

#### A. 自動作成（新規・再有効化）

```
1. Stripe API で Product を作成（payment_item と 1:1 で作成・金額変更時は流用）:
     POST /v1/products {
       name: payment_items.name,
       metadata: { payment_item_id: "<ID>" }
     }
   → stripe_product_id を保存
2. Stripe API で Price を作成:
     POST /v1/prices {
       unit_amount: <通貨換算後の金額 ※>,
       currency: payment_items.currency,
       product: stripe_product_id
     }
   → stripe_price_id を保存
```

> ※ **通貨別の unit_amount 変換**:
> - JPY（ゼロ小数点通貨）: `unit_amount = amount`（例: ¥5000 → 5000）
> - USD / EUR 等: `unit_amount = amount × 100`（例: $50.00 → 5000）
> - Stripe のゼロ小数点通貨リストを参照し、通貨コードに応じて分岐する（[Stripe Docs: Zero-decimal currencies](https://docs.stripe.com/currencies#zero-decimal)）

#### B. 手動紐付け（既存 Price ID を直接指定する移行フロー）

```
1. リクエストに stripe_price_id が含まれる場合
2. Stripe API で Price を取得: GET /v1/prices/{stripe_price_id}
3. price.unit_amount と payment_items.amount を通貨換算して比較 → 不一致は 422
4. price.currency と payment_items.currency を比較 → 不一致は 422
5. price.product を stripe_product_id として保存
6. stripe_price_id を payment_items に保存
```

#### C. 金額・通貨変更時（PATCH で amount / currency を変更 + is_active = TRUE）

```
1. 変更前の stripe_price_id が存在する場合:
     Stripe API で既存 Price をアーカイブ（非アクティブ化）:
     POST /v1/prices/{old_stripe_price_id} { active: false }
2. 変更後の金額・通貨でフロー A（自動作成）の step 2 を実行（Product は既存を流用）
3. payment_items.stripe_price_id を新 Price ID に UPDATE
```

> `amount`・`currency` いずれかが変更された場合にのみ実行する。変更がない PATCH（name や description のみ）は Stripe API を呼び出さない（冪等性保証）

#### D. is_active 変更時

| 変更内容 | Stripe 操作 |
|---------|------------|
| TRUE → FALSE | 既存 `stripe_price_id` を Stripe でアーカイブ（`active: false`）。`stripe_price_id` は DB に保持（履歴として残す）|
| FALSE → TRUE | フロー A を実行して新しい Price を作成（アーカイブ済み Price は再有効化不可）|
| TRUE → TRUE（変化なし）| Stripe API 呼び出しなし |
| FALSE → FALSE（変化なし）| Stripe API 呼び出しなし |

---

### Stripe 決済フロー（メンバーによるオンライン支払い）

```
1. POST /api/v1/payment-items/{itemId}/checkout を受付
2. payment_items が is_active = TRUE かつ stripe_price_id が存在するか確認 → なければ 422
3. 対象ユーザーの PAID かつ valid_until >= CURDATE() な member_payments が既存か確認 → あれば 409
4. stripe_customers にユーザーのレコードがなければ Stripe API で Customer を作成し INSERT
5. member_payments に (status=PENDING, payment_method=STRIPE) で INSERT
6. Stripe API で Checkout Session を作成:
   - price: payment_items.stripe_price_id
   - customer: stripe_customers.stripe_customer_id
   - metadata: {"member_payment_id": "<上記で作成した ID>"}
7. member_payments.stripe_checkout_session_id を UPDATE
8. checkout_url を 200 OK で返す
```

---

### Stripe Webhook 受信フロー

```
1. POST /api/v1/webhooks/stripe を受付
2. Stripe-Signature ヘッダーを Webhook Signing Secret で検証 → 失敗で 400
3. イベント種別を判定:

   [checkout.session.completed]
   a. session の metadata から member_payment_id を取得
   b. member_payments を SELECT ... FOR UPDATE で取得
   c. status が PENDING であることを確認（PAID は冪等処理でスキップ）
   d. stripe_payment_intent_id を保存
   e. valid_until を payment_items.type に応じて計算:
      - ANNUAL_FEE  → valid_from（paid_at 当日）から +365日
      - MONTHLY_FEE → valid_from から +31日
      - ITEM        → NULL（無期限）
   f. status = PAID、paid_at = NOW() で UPDATE
   g. audit_logs に PAYMENT_COMPLETED を記録
   h. メンバーに支払い完了通知を送信（通知機能実装後）

   [charge.refunded]
   a. stripe_payment_intent_id で member_payments を取得
   b. status = REFUNDED で UPDATE
   c. audit_logs に PAYMENT_REFUNDED を記録

4. 200 OK を返す（Stripe の再送防止）
```

---

### 手動支払い記録フロー（ADMIN）

```
1. POST /api/v1/teams/{id}/payment-items/{itemId}/payments を受付
2. 操作者が ADMIN（または MANAGE_PAYMENTS 権限を持つ DEPUTY_ADMIN）か確認
3. 対象ユーザーが当該チームのメンバーか確認
4. 対象ユーザーの PAID かつ有効期間内の member_payments が既存か確認 → あれば 409
5. リクエストボディの valid_from / valid_until を使用（省略時は paid_at を基準に type から自動計算）
6. member_payments に (status=PAID, payment_method=MANUAL, recorded_by=操作者) で INSERT
7. audit_logs に PAYMENT_MANUALLY_RECORDED を記録（target_user_id = 対象ユーザー）
8. 201 Created を返す
```

---

### アクセス制御解決ロジック

コンテンツや画面を表示する際、以下の順序で支払い状況を確認する。**ADMIN / SYSTEM_ADMIN は支払い状況に関わらず全コンテンツにアクセス可能**（管理操作のため）。

```
[全体ロックの確認]
1. 対象 team_id / organization_id の access_requirements を取得
2. 要件が存在する場合、各 payment_item_id に対して
   member_payments WHERE user_id = リクエスト者
     AND payment_item_id = X
     AND status = 'PAID'
     AND (valid_until IS NULL OR valid_until >= CURDATE())
   が存在するか確認
3. 未払いの要件が1件でもある → ロック状態（全コンテンツを返さずロック画面を表示）

[コンテンツ単位ゲートの確認]
4. 全体ロックをパスした場合、表示対象コンテンツに対して
   content_payment_gates WHERE content_type = X AND content_id = Y を取得
5. ゲートが存在する場合: 上記と同様に member_payments を確認
6. 未払いの場合 → 当該コンテンツのみ 403（「この内容を閲覧するには支払いが必要です」）
```

**ロック状態の表示仕様**

| 状態 | フロントエンドの表示 |
|------|----------------|
| 全体ロック（未払い）| チーム/組織のトップに「〇〇の支払いが必要です」バナーを表示し、コンテンツ一覧は非表示 |
| コンテンツ単位ロック | コンテンツ一覧には「🔒 タイトル」で表示（存在は示すが内容は隠す）|

> コンテンツ単位ロックでは、タイトルのみ表示することで「支払うと何が見られるか」をユーザーが判断できるようにする。

---

### 有効期限切れバッチ（参考）

`member_payments.valid_until < CURDATE()` のレコードを定期バッチで検知し、メンバーへ期限切れ通知を送る（例: 期限7日前・当日）。`status` の変更は行わない（履歴保持）。アクセス制御の有効判定は常にリアルタイムの `valid_until >= CURDATE()` で行う。

---

## 6. セキュリティ考慮事項

- **Webhook 署名検証**: `Stripe-Signature` ヘッダーを必ず検証する。検証失敗は即 400 を返す（Stripe SDK の `constructEvent` を使用）
- **冪等性**: Stripe のリトライに備え `checkout.session.completed` は `member_payment_id` 単位で冪等処理する（PAID 済みは UPDATE をスキップ）
- **Checkout セッションの整合性検証**: Webhook 受信時に metadata の `member_payment_id` でDBを引き、`payment_item_id` や `user_id` が一致することを確認する
- **手動記録の監査**: ADMIN による手動記録・修正・取り消しは全件 audit_logs に記録する（不正操作の追跡）
- **レートリミット**: `POST /payment-items/{id}/checkout` に Bucket4j で 5 req/min per user を適用（二重決済防止）
- **認可チェック**: `payment_items` は作成したチーム/組織スコープ内でのみ参照可能。他チームの payment_item を access_requirements に設定することはアプリ層で禁止する（横断アクセス防止）
- **金額整合性**: Webhook 受信時に Stripe の支払い金額と `payment_items.amount` が一致するかを確認し、不一致は audit_logs に記録する

---

## 7. Flywayマイグレーション

```
V3.001__create_payment_items_table.sql
V3.002__create_stripe_customers_table.sql
V3.003__create_member_payments_table.sql
V3.004__create_team_access_requirements_table.sql
V3.005__create_organization_access_requirements_table.sql
V3.006__create_content_payment_gates_table.sql
```

**マイグレーション上の注意点**
- V3.001 は V2.001（organizations）/ V2.002（teams）/ V2.008（user_roles）完了後に実行
- V3.003（member_payments）は V3.001（payment_items）/ V3.002（stripe_customers）完了後
- V3.004 / V3.005 は V3.001（payment_items）完了後
- V3.006 は V3.001（payment_items）完了後（content_id は各コンテンツテーブルへの論理 FK; 物理 FK は設定しない）

---

## 8. 未解決事項

- [x] `payment_items.stripe_price_id` の設定方法: **自動生成を基本とし、手動紐付けも移行目的でサポートする方式に決定**（Section 5「Stripe Price 管理フロー」参照）。①`is_active = TRUE` で支払い項目を作成すると Stripe Product・Price を同期生成（フロー A）。②既存 Stripe 運用からの移行用として `stripe_price_id` を直接指定した場合は Stripe から金額・通貨を取得してバリデーション（フロー B）。③`amount`/`currency` 変更時は旧 Price をアーカイブし新 Price を生成（フロー C）。④`is_active` 変更時の Stripe 操作も定義済み（フロー D）。Stripe API は同期呼び出し・失敗時は DB ロールバック
- [ ] Stripe Subscription（自動更新）対応（Phase 4 以降）: 月謝のように毎月自動で請求・更新するケースの設計
- [ ] 有効期限切れバッチのスケジュール（毎日 AM3:00 等）と通知タイミング（期限7日前・当日等）の確定
- [ ] `content_payment_gates.content_type` に追加する値（CHAT_MESSAGE、VIDEO 等）を各コンテンツ機能の設計時に確定する
- [ ] 返金操作の提供範囲: Stripe ダッシュボード操作 + webhook による状態同期のみとするか、ADMIN が API 経由で返金を実行できるエンドポイントを提供するかを確定する
- [ ] DEPUTY_ADMIN への `MANAGE_PAYMENTS` パーミッション追加: F03 の `permissions` シードに追記が必要（F03 の未解決事項と連動）
- [ ] コンテンツ単位ロックのタイトル表示: タイトルすら非表示にする「完全非公開」オプションの必要性を確認する

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-10 | `stripe_price_id` 管理フロー確定（未解決①解決）: POST/PATCH に `is_active` フィールド追加・Stripe 自動生成フロー（A）・手動紐付けフロー（B）・金額変更フロー（C）・is_active 変更フロー（D）を追加。PATCH 仕様セクション新規追加。支払い項目作成フローに Stripe 同期呼び出し・ロールバック処理を追加 |
| 2026-02-21 | 初版作成 |
