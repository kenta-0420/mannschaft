# F04: 支払い管理・コンテンツアクセス制御

> **ステータス**: 🟢 設計完了
> **実装フェーズ**: Phase 3
> **最終更新**: 2026-03-10

---

## 1. 概要

チーム・組織内での年会費・月謝・アイテム代金・寄付等の支払いを管理し、支払い状況に応じてコンテンツへのアクセスを制御する。Stripe によるオンライン決済と、ADMIN が現金・振込等を手動で記録するオフライン決済のハイブリッド方式に対応する。アクセス制御は「チーム/組織全体のロック」と「特定コンテンツ単位のゲーティング」の両方をサポートし、ADMIN が用途に応じて使い分けられる。寄付（DONATION）はアクセス制御に紐付けず、SUPPORTER+ がチーム・組織を善意で支援するための決済手段を提供する。

---

## 2. スコープ

### 対象ロール
| ロール | 操作可能な範囲 |
|--------|--------------|
| SYSTEM_ADMIN | 全支払い記録の参照・強制修正 |
| ADMIN | 支払い項目の作成・管理、メンバーの支払い状況参照・手動記録・修正、アクセス要件設定 |
| DEPUTY_ADMIN | `MANAGE_PAYMENTS` 権限を持つ場合: 支払い状況参照・手動記録 |
| MEMBER | 自分の支払い状況参照、Stripe 決済の実行（自己支払い）|
| SUPPORTER | 自分の支払い状況参照、Stripe 決済の実行（自己支払い・寄付）— 支払い要件が設定されている場合 |
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
| `type` | ENUM('ANNUAL_FEE', 'MONTHLY_FEE', 'ITEM', 'DONATION') | NO | — | 種別（年会費 / 月謝 / アイテム代金 / 寄付）|
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
- `team_id` と `organization_id` はどちらか一方のみ非 NULL（XOR）。Flyway で CHECK 制約を追加する:
  ```sql
  CONSTRAINT chk_pi_scope
    CHECK (
      (team_id IS NOT NULL AND organization_id IS NULL)
      OR (team_id IS NULL AND organization_id IS NOT NULL)
    )
  ```
- `stripe_price_id` を持つ場合のみオンライン決済（Stripe Checkout）が利用可能
- 論理削除時の関連レコード処理: `team_access_requirements` / `organization_access_requirements` / `content_payment_gates` への `payment_item_id` FK は **物理 CASCADE を設定せず**、アプリ層の `PaymentItemService.softDelete()` で明示的に削除する。理由: 支払い記録（`member_payments`）は論理削除後も保持する必要があるため物理 CASCADE ではなくアプリ層で制御を統一する
- **Phase 4 追加予定カラム**（V4.001 で ALTER TABLE）:
  - `is_recurring BOOLEAN NOT NULL DEFAULT FALSE` — TRUE の場合 Stripe Subscription（自動更新決済）を利用。FALSE = Phase 3 の一回払い Checkout
  - `billing_interval ENUM('MONTHLY', 'YEARLY') NULL` — `is_recurring = TRUE` 時のみ有効。Stripe Price の `recurring.interval` に対応（NULL = `is_recurring = FALSE`）
  - `is_recurring = TRUE` の場合、`stripe_price_id` は `type=recurring` の Stripe Price を参照する（Phase 3 の `type=one_time` Price とは別物）

---

#### `stripe_customers`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE RESTRICT）|
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
- `ON DELETE RESTRICT`: ユーザーの物理削除を DB レベルで防止。退会時は `users.deleted_at` による論理削除 + 個人情報匿名化（スクラビング）を行い、Stripe Customer マッピングは返金・照会のため保持する

---

#### `member_subscriptions`（Phase 4 追加予定）

Stripe Subscription の状態を管理するテーブル。`is_recurring = TRUE` の `payment_items` に対して1ユーザー1サブスクリプションが存在する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE RESTRICT）|
| `payment_item_id` | BIGINT UNSIGNED | NO | — | FK → payment_items（ON DELETE RESTRICT）|
| `stripe_subscription_id` | VARCHAR(100) | NO | — | Stripe Subscription ID（sub_xxxx）|
| `status` | ENUM('ACTIVE', 'PAST_DUE', 'CANCELLED', 'PAUSED') | NO | 'ACTIVE' | サブスクリプション状態 |
| `current_period_start` | DATE | NO | — | 現在の課金期間の開始日 |
| `current_period_end` | DATE | NO | — | 現在の課金期間の終了日（この日以降に次の Invoice が発行される）|
| `cancel_at_period_end` | BOOLEAN | NO | FALSE | TRUE = 期末解約予約済み（期間中は ACTIVE のままサービス利用可）|
| `cancelled_at` | DATETIME | YES | NULL | 実際に解約が完了した日時（`customer.subscription.deleted` webhook 受信時）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**インデックス**
```sql
UNIQUE KEY uq_ms_user_item (user_id, payment_item_id)        -- 同一 payment_item への重複加入防止
UNIQUE KEY uq_ms_stripe_sub (stripe_subscription_id)
INDEX idx_ms_status (status)
INDEX idx_ms_period_end (current_period_end)                 -- 期限切れ通知バッチ用
```

**制約・備考**
- `status = 'ACTIVE'` かつ `current_period_end >= CURDATE()` の場合、対応する `member_payments` のうち最新の PAID レコードを有効期間の判定に使用する
- サブスクリプションが生成する各課金サイクルの支払いは `member_payments` に PAID レコードとして記録する（webhook `invoice.payment_succeeded` で INSERT）
- `cancel_at_period_end = TRUE` の状態でも `status = 'ACTIVE'` のまま。Stripe Subscription 内部的に `cancel_at_period_end: true` を保有し、期末到達時に Stripe 側で `status = 'canceled'` に遷移する。アプリ側は `customer.subscription.deleted` webhook を受け取って `status = 'CANCELLED'` に更新する
- `cancel_at_period_end = TRUE` でも `current_period_end` までは `member_payments` の PAID レコードが有効（`valid_until = current_period_end`）のためサービスアクセス可能。解約後の `member_payments` レコードは削除・変更しない（有効期間の自然失効に委ねる）
- `member_subscriptions` は Webhook が正とし、DB の状態は Stripe のサブスクリプション状態を鏡写しにする
- `ON DELETE RESTRICT`（user_id / payment_item_id）: 支払い履歴・サブスクリプション記録の証跡保護。ユーザー退会時は物理削除ではなく論理削除を使用する

---

#### `member_payments`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `user_id` | BIGINT UNSIGNED | NO | — | FK → users（ON DELETE RESTRICT）|
| `payment_item_id` | BIGINT UNSIGNED | NO | — | FK → payment_items（ON DELETE RESTRICT）|
| `amount_paid` | DECIMAL(10,2) | NO | — | 実際に支払った金額（STRIPE: Stripe の実受領額 / MANUAL: ADMIN 入力額）|
| `currency` | CHAR(3) | NO | 'JPY' | 通貨コード |
| `payment_method` | ENUM('STRIPE', 'MANUAL') | NO | — | 支払い方法 |
| `status` | ENUM('PENDING', 'PAID', 'REFUNDED', 'CANCELLED') | NO | 'PENDING' | 支払いステータス |
| `valid_from` | DATE | YES | NULL | 有効期間開始日（PENDING 時は NULL; PAID 遷移時に確定）|
| `valid_until` | DATE | YES | NULL | 有効期間終了日（NULL = 無期限）|
| `stripe_checkout_session_id` | VARCHAR(100) | YES | NULL | Stripe Checkout Session ID |
| `stripe_payment_intent_id` | VARCHAR(100) | YES | NULL | Stripe Payment Intent ID |
| `paid_at` | DATETIME | YES | NULL | 実際の支払い完了日時 |
| `recorded_by` | BIGINT UNSIGNED | YES | NULL | FK → users（手動記録した ADMIN; SET NULL on delete）|
| `note` | VARCHAR(500) | YES | NULL | 備考（振込日・現金確認メモ等）|
| `stripe_refund_id` | VARCHAR(100) | YES | NULL | Stripe Refund ID（re_xxxx; 返金時に設定）|
| `refunded_at` | DATETIME | YES | NULL | 返金完了日時（`status = 'REFUNDED'` 時に設定）|
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
UNIQUE KEY uq_mp_refund (stripe_refund_id)                         -- NULL 除く（Webhook 冪等処理用）
```

**制約・備考**
- **`amount_paid` の設定方針**:
  - **STRIPE**: Webhook（`checkout.session.completed`）受信時に `payment_intent.amount_received` を通貨換算して記録する。現時点では `payment_items.amount` と一致するが、将来 Stripe Coupon / Promotion Code を導入した場合に乖離する可能性があるため、常に Stripe の実受領額を正として記録する
  - **MANUAL**: ADMIN がリクエストボディで指定した金額を記録する。端数処理・分割受領・現金過不足等により `payment_items.amount` と異なる場合がある
  - **PENDING 時**: Checkout Session 作成時点では `amount_paid` は `payment_items.amount` を仮セットする（PAID 遷移時に Stripe の実受領額で上書き）
- `status = 'PENDING'` は Stripe Checkout セッション作成後・支払い完了前の状態。MANUAL 支払いは直接 `PAID` で INSERT する
- `valid_from` / `valid_until` は ADMIN が手動で任意設定可能（例: 4月〜翌3月の年度区切り）。Stripe 決済時はアプリ層で自動計算（ANNUAL_FEE = +365日 / MONTHLY_FEE = +31日 / ITEM = NULL / DONATION = NULL）
- `valid_from` は `status = 'PENDING'` 時は NULL（`paid_at` 未確定のため）。`PAID` 遷移時にアプリ層で必ずセットする（Webhook: `paid_at` の日付を自動セット / MANUAL: リクエストの `valid_from` または `paid_at` の日付）。`status = 'PAID'` かつ `valid_from IS NULL` は不正状態としてアプリ層で防止する
- `valid_until = NULL`（ITEM 型）は「購入後永続アクセス」を意味する（デジタル教材の買い切り等）。有効期間を設けたい場合は ADMIN が POST / PATCH 時に `valid_until` を明示的に指定する
- **DONATION 型の特性**: 寄付はアクセス制御に紐付けない（`team_access_requirements` / `content_payment_gates` に設定不可）。`valid_from` / `valid_until` ともに NULL。ADMIN が寄付項目を作成し、SUPPORTER+ が自発的に Checkout で決済する。寄付履歴は `member_payments` に記録され、ADMIN が支払い状況一覧で確認可能
- 有効判定: `status = 'PAID'` AND (`valid_until IS NULL` OR `valid_until >= CURDATE()`)
- `REFUNDED` は Stripe 払い戻し完了 webhook または ADMIN 手動返金操作で設定する
- 同一ユーザー・同一 payment_item に対する有効な PAID レコードの重複はアプリ層で防止する（409）。判定条件: `status = 'PAID' AND (valid_until IS NULL OR valid_until >= CURDATE())`（支払い方法 STRIPE / MANUAL 問わず同一条件で判定）。**ただし `type = 'DONATION'` は重複制限なし**（同一ユーザーが同一寄付項目に複数回支払い可能）
- `ON DELETE RESTRICT`（user_id）: 支払い履歴は会計・税務上の証跡として保持が必須。ユーザー退会時は `users.deleted_at` による論理削除 + 個人情報匿名化を行い、支払い記録は保持する。物理削除を試みた場合は DB レベルでエラーとなる

---

#### `team_access_requirements`

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `team_id` | BIGINT UNSIGNED | NO | — | FK → teams（ON DELETE CASCADE）|
| `payment_item_id` | BIGINT UNSIGNED | NO | — | FK → payment_items（ON DELETE RESTRICT）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_tar_team_item (team_id, payment_item_id)
INDEX idx_tar_payment_item (payment_item_id)
```

**制約・備考**
- チームへのアクセスに複数の支払い項目を要求できる（M:N）。**全件が有効な PAID 状態**の場合のみアクセス可
- ADMIN は `GET /teams/{id}/members` のレスポンスに各メンバーの支払い状況を付加して確認できる
- `type = 'DONATION'` の payment_items は紐付け不可（アプリ層で 422 を返す）。寄付はアクセス制御の対象外

---

#### `organization_access_requirements`

`team_access_requirements` と同一の設計。`team_id` の代わりに `organization_id` FK を持つ。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `organization_id` | BIGINT UNSIGNED | NO | — | FK → organizations（ON DELETE CASCADE）|
| `payment_item_id` | BIGINT UNSIGNED | NO | — | FK → payment_items（ON DELETE RESTRICT）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_oar_org_item (organization_id, payment_item_id)
INDEX idx_oar_payment_item (payment_item_id)
```

**制約・備考**
- `team_access_requirements` と同一の設計方針。`type = 'DONATION'` の payment_items は紐付け不可（422）

---

#### `content_payment_gates`

コンテンツ単位のアクセスゲート。各コンテンツモジュール（投稿・ファイル・お知らせ・スケジュール）の実装時に参照するポリモーフィック設計。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---------|---|------|-----------|------|
| `id` | BIGINT UNSIGNED | NO | AUTO_INCREMENT | PK |
| `payment_item_id` | BIGINT UNSIGNED | NO | — | FK → payment_items（ON DELETE RESTRICT）|
| `content_type` | VARCHAR(50) | NO | — | コンテンツ種別（下記「許容値一覧」参照）|
| `content_id` | BIGINT UNSIGNED | NO | — | 対象コンテンツの ID |
| `is_title_hidden` | BOOLEAN | NO | FALSE | TRUE = 未払い者にコンテンツの存在自体を返さない（完全非公開）。FALSE = 「🔒 + タイトル」を表示（デフォルト）|
| `created_by` | BIGINT UNSIGNED | YES | NULL | FK → users（SET NULL on delete; users は物理削除しないためフェイルセーフ目的）|
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
UNIQUE KEY uq_cpg_item_content (payment_item_id, content_type, content_id)
INDEX idx_cpg_content (content_type, content_id)   -- コンテンツ取得時の逆引き用
```

**`content_type` 許容値一覧**

`ENUM` ではなく `VARCHAR(50)` を採用し、バリデーションはアプリ層（`ContentGateType` 定数クラス）で管理する。新しいコンテンツモジュールが追加されても F04 の DB スキーマを ALTER TABLE せず拡張できる（疎結合・OCP 遵守）。

| 値 | 対応コンテンツ | 追加フェーズ |
|----|--------------|------------|
| `POST` | 投稿（掲示板・ブログ等）| Phase 3 |
| `FILE` | ファイル（ドキュメント・画像等）| Phase 3 |
| `ANNOUNCEMENT` | お知らせ | Phase 3 |
| `SCHEDULE` | スケジュール | Phase 3 |
| `VIDEO` | 動画コンテンツ | Phase 4+ |
| `CHAT_MESSAGE` | チャットメッセージ / スレッド | Phase 4+ |

> 上記以外の値が API 経由で送信された場合は **422 Unprocessable Entity** を返す（アプリ層で `ContentGateType` 定数との照合バリデーションを実施）。

**Java 実装指針**
```java
// com.mannschaft.app.feature.payment.constant.ContentGateType
public final class ContentGateType {
    public static final String POST         = "POST";
    public static final String FILE         = "FILE";
    public static final String ANNOUNCEMENT = "ANNOUNCEMENT";
    public static final String SCHEDULE     = "SCHEDULE";
    // Phase 4+: VIDEO, CHAT_MESSAGE を追加（DB マイグレーション不要）

    public static final Set<String> SUPPORTED = Set.of(POST, FILE, ANNOUNCEMENT, SCHEDULE);

    public static boolean isSupported(String value) {
        return SUPPORTED.contains(value);
    }
}
```
> 新しいコンテンツモジュール追加時は `SUPPORTED` セットに定数を追加し、該当モジュールの Service を `ContentGateResolver`（ストラテジーパターン）に登録するだけで F04 のコアロジックを無変更で拡張できる。

**制約・備考**
- 1つのコンテンツに複数の `payment_item` を紐付け可能（**全件**有効な PAID 状態で閲覧可）
- コンテンツ削除時は各コンテンツモジュールのアプリ層でこのテーブルのレコードもカスケード削除する
- `content_id` は各コンテンツテーブルへの論理 FK（物理 FK は設定しない）
- **ENUM → VARCHAR 変更理由**: ENUM は値追加のたびに ALTER TABLE（テーブルロック）が必要なため避ける。VARCHAR + アプリ層バリデーションにより、新モジュール追加時の DB 変更コストをゼロにする
- `type = 'DONATION'` の payment_items は紐付け不可（422）。寄付はコンテンツゲートの対象外
- **CRUD API**: `PUT /teams/{id}/content-payment-gates` / `PUT /organizations/{id}/content-payment-gates` で一括設定（各モジュール側ではなく F04 が一元管理）。コンテンツの存在確認は `ContentGateResolver`（ストラテジーパターン）を経由して各モジュールに委譲する

---

### ER図（テキスト形式）
```
[Phase 3: 一回払い]
payment_items (N) ──── (M) teams                    ※ via team_access_requirements
payment_items (N) ──── (M) organizations             ※ via organization_access_requirements
payment_items (1) ──── (N) member_payments
payment_items (1) ──── (N) content_payment_gates
users (1) ──── (1) stripe_customers
users (1) ──── (N) member_payments

[Phase 4: Subscription（追加予定）]
payment_items (1) ──── (N) member_subscriptions      ※ is_recurring = TRUE の payment_items のみ
users (1) ──── (N) member_subscriptions
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
| GET | `/api/v1/teams/{id}/payment-items/{itemId}/payments` | 必要（ADMIN / MANAGE_PAYMENTS）| メンバー支払い状況一覧（未払い含む）|
| POST | `/api/v1/teams/{id}/payment-items/{itemId}/payments` | 必要（ADMIN / MANAGE_PAYMENTS）| 手動支払い記録 |
| PATCH | `/api/v1/teams/{id}/payment-items/{itemId}/payments/{paymentId}` | 必要（ADMIN / MANAGE_PAYMENTS）| 支払い記録修正（金額・期間・メモ）|
| DELETE | `/api/v1/teams/{id}/payment-items/{itemId}/payments/{paymentId}` | 必要（ADMIN）| 支払い記録取り消し（CANCELLED）|
| GET | `/api/v1/teams/{id}/access-requirements` | 必要（ADMIN）| チーム全体ロック設定の確認 |
| PUT | `/api/v1/teams/{id}/access-requirements` | 必要（ADMIN）| チーム全体ロック設定（一括指定）|
| GET | `/api/v1/organizations/{id}/payment-items` | 必要（ADMIN）| 組織支払い項目一覧 |
| POST | `/api/v1/organizations/{id}/payment-items` | 必要（ADMIN）| 組織支払い項目作成 |
| PATCH | `/api/v1/organizations/{id}/payment-items/{itemId}` | 必要（ADMIN）| 組織支払い項目更新 |
| DELETE | `/api/v1/organizations/{id}/payment-items/{itemId}` | 必要（ADMIN）| 組織支払い項目論理削除 |
| GET | `/api/v1/organizations/{id}/payment-items/{itemId}/payments` | 必要（ADMIN / MANAGE_PAYMENTS）| 組織メンバー支払い状況一覧 |
| POST | `/api/v1/organizations/{id}/payment-items/{itemId}/payments` | 必要（ADMIN / MANAGE_PAYMENTS）| 組織手動支払い記録 |
| PATCH | `/api/v1/organizations/{id}/payment-items/{itemId}/payments/{paymentId}` | 必要（ADMIN / MANAGE_PAYMENTS）| 組織支払い記録修正 |
| DELETE | `/api/v1/organizations/{id}/payment-items/{itemId}/payments/{paymentId}` | 必要（ADMIN）| 組織支払い記録取り消し |
| GET | `/api/v1/organizations/{id}/access-requirements` | 必要（ADMIN）| 組織全体ロック設定の確認 |
| PUT | `/api/v1/organizations/{id}/access-requirements` | 必要（ADMIN）| 組織全体ロック設定 |
| GET | `/api/v1/teams/{id}/content-payment-gates` | 必要（ADMIN）| コンテンツゲート一覧（チーム内） |
| PUT | `/api/v1/teams/{id}/content-payment-gates` | 必要（ADMIN）| コンテンツゲート一括設定（チーム内） |
| GET | `/api/v1/organizations/{id}/content-payment-gates` | 必要（ADMIN）| コンテンツゲート一覧（組織内） |
| PUT | `/api/v1/organizations/{id}/content-payment-gates` | 必要（ADMIN）| コンテンツゲート一括設定（組織内） |
| POST | `/api/v1/teams/{id}/payment-items/{itemId}/payments/{paymentId}/refund` | 必要（ADMIN / MANAGE_PAYMENTS）| 全額返金実行（Stripe 払い戻し）|
| POST | `/api/v1/organizations/{id}/payment-items/{itemId}/payments/{paymentId}/refund` | 必要（ADMIN / MANAGE_PAYMENTS）| 組織支払いの全額返金実行 |
| POST | `/api/v1/payment-items/{itemId}/checkout` | 必要（SUPPORTER+）| Stripe Checkout セッション作成（自己支払い。一回払い / Subscription 開始）|
| GET | `/api/v1/me/payments` | 必要 | 自分の支払い状況一覧 |
| POST | `/api/v1/webhooks/stripe` | 署名検証のみ | Stripe Webhook 受信 |
| GET | `/api/v1/me/subscriptions` | 必要 | 自分の有効サブスクリプション一覧（**Phase 4**）|
| DELETE | `/api/v1/payment-items/{itemId}/subscriptions/{subscriptionId}` | 必要（本人 or ADMIN）| サブスクリプション期末解約（**Phase 4**）|
| PATCH | `/api/v1/payment-items/{itemId}/subscriptions/{subscriptionId}/resume` | 必要（本人 or ADMIN）| 期末解約の取り消し（**Phase 4**）|

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

`name` / `description` / `amount` / `currency` / `is_active` を部分更新する。`type` は変更不可（既存の `access_requirements` / `content_payment_gates` との整合性が壊れるため。変更が必要な場合は削除して再作成する → 422）。

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
      "status": "PAID",
      "amount_paid": 5000,
      "payment_method": "MANUAL",
      "valid_from": "2026-04-01",
      "valid_until": "2027-03-31",
      "paid_at": "2026-04-01T00:00:00Z"
    },
    {
      "user_id": 55,
      "display_name": "鈴木花子",
      "status": "UNPAID",
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

**リクエストボディ**: なし（空ボディ）

> **リダイレクト URL のセキュリティ方針**: `success_url` / `cancel_url` はフロントエンドから受け取らず、サーバー側の環境変数 `APP_FRONTEND_URL`（例: `https://mannschaft.app`）を基に固定パスで生成する。これによりオープンリダイレクト脆弱性を構造的に排除する。
>
> ```
> success_url: ${APP_FRONTEND_URL}/payment/complete?session_id={CHECKOUT_SESSION_ID}
> cancel_url:  ${APP_FRONTEND_URL}/payment/cancelled
> ```
>
> フロントエンドの `/payment/complete` ページは URL パラメータの `session_id` を使って `GET /api/v1/me/payments` を呼び出し、支払い結果を表示する。

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
| 409 | 対象ユーザーの有効な PAID レコードが既に存在する（判定: `status = 'PAID' AND (valid_until IS NULL OR valid_until >= CURDATE())`。`type = 'DONATION'` は対象外）|
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

#### `GET /api/v1/teams/{id}/content-payment-gates`（コンテンツゲート一覧）

指定チーム内のコンテンツに設定されたゲート一覧を返す。組織版（`/organizations/{id}/content-payment-gates`）も同一仕様。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `content_type` | String | — | `POST` / `FILE` / `ANNOUNCEMENT` / `SCHEDULE` でフィルタ |
| `content_id` | Long | — | 特定コンテンツのゲートのみ取得 |
| `page` | Int | 0 | ページ番号（0始まり）|
| `size` | Int | 50 | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "content_type": "POST",
      "content_id": 123,
      "is_title_hidden": false,
      "payment_item": {
        "id": 1,
        "name": "2025年度 年会費",
        "type": "ANNUAL_FEE",
        "amount": 5000,
        "currency": "JPY"
      },
      "created_by": 42,
      "created_at": "2026-03-01T10:00:00Z"
    }
  ],
  "meta": {
    "page": 0,
    "size": 50,
    "total_elements": 3,
    "total_pages": 1
  }
}
```

---

#### `PUT /api/v1/teams/{id}/content-payment-gates`（コンテンツゲート一括設定）

指定コンテンツのゲートを一括で設定する（既存のゲートを全件置換）。空配列でゲート解除。組織版（`/organizations/{id}/content-payment-gates`）も同一仕様。

**リクエストボディ**
```json
{
  "content_type": "POST",
  "content_id": 123,
  "gates": [
    { "payment_item_id": 1, "is_title_hidden": false },
    { "payment_item_id": 3, "is_title_hidden": true }
  ]
}
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "content_type": "POST",
    "content_id": 123,
    "gates": [
      {
        "id": 10,
        "payment_item_id": 1,
        "payment_item_name": "2025年度 年会費",
        "is_title_hidden": false
      },
      {
        "id": 11,
        "payment_item_id": 3,
        "payment_item_name": "ユニフォーム代金",
        "is_title_hidden": true
      }
    ]
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 操作者が当該チーム/組織の ADMIN でない |
| 404 | 指定した `content_type` + `content_id` のコンテンツが存在しない、または当該チーム/組織に属していない |
| 422 | `content_type` が `ContentGateType.SUPPORTED` に含まれない |
| 422 | `payment_item_id` が当該チーム/組織スコープ外の支払い項目 |
| 422 | `payment_item_id` の `type` が `DONATION`（寄付はゲート対象外）|

**バリデーションフロー**
```
1. 操作者が当該チーム/組織の ADMIN か確認 → でなければ 403
2. content_type が ContentGateType.SUPPORTED に含まれるか → でなければ 422
3. content_id が当該チーム/組織に属するコンテンツとして存在するか確認:
   - ContentGateResolver（ストラテジーパターン）を通じて各モジュールの
     existsInScope(contentType, contentId, teamId/orgId) を呼び出す
   → 存在しなければ 404
4. 各 payment_item_id について:
   a. payment_items テーブルに存在し、当該チーム/組織スコープ内か → でなければ 422
   b. payment_items.type ≠ 'DONATION' か → DONATION なら 422
   c. payment_items.deleted_at IS NULL か → 論理削除済みなら 422
5. 既存の content_payment_gates（同一 content_type + content_id）を DELETE
6. 新しいゲート設定を INSERT
7. audit_logs に CONTENT_GATE_UPDATED を記録
```

> **セキュリティ上の重要事項**:
> - **スコープ横断防止**: `payment_item_id` が当該チーム/組織に属することを必ず検証する。他チームの `payment_item` を自チームのコンテンツにゲートとして設定できてしまうと、他チームの ADMIN が間接的にコンテンツアクセスを制御できる脆弱性になる
> - **コンテンツ存在確認**: `content_id` の存在確認は `ContentGateResolver` を経由し、各モジュールの Service に委譲する。F04 が直接他モジュールのテーブルを参照しない（疎結合）
> - **IDOR 防止**: `content_id` はチーム/組織スコープ内に存在するかを必ず検証し、他チームのコンテンツ ID を指定した攻撃を防ぐ
> - **トランザクション**: step 5-6（DELETE + INSERT）は `@Transactional` で囲み、中途半端な状態を防止する

---

#### `GET /api/v1/me/payments`（自分の支払い状況一覧）

ログインユーザー自身の全支払い記録を返す。チーム・組織横断で取得可能。

**クエリパラメータ**
| パラメータ | 型 | デフォルト | 説明 |
|-----------|---|-----------|------|
| `status` | String | — | `PAID` / `PENDING` / `CANCELLED` / `REFUNDED` でフィルタ（未指定 = 全件）|
| `type` | String | — | `ANNUAL_FEE` / `MONTHLY_FEE` / `ITEM` / `DONATION` でフィルタ |
| `team_id` | Long | — | 特定チームの支払いに絞り込み |
| `organization_id` | Long | — | 特定組織の支払いに絞り込み |
| `page` | Int | 0 | ページ番号（0始まり）|
| `size` | Int | 20 | 1ページ件数（最大100）|

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 10,
      "payment_item": {
        "id": 1,
        "name": "2025年度 年会費",
        "type": "ANNUAL_FEE",
        "amount": 5000,
        "currency": "JPY"
      },
      "scope": {
        "type": "TEAM",
        "id": 5,
        "name": "サッカー部"
      },
      "amount_paid": 5000,
      "currency": "JPY",
      "payment_method": "STRIPE",
      "status": "PAID",
      "valid_from": "2025-04-01",
      "valid_until": "2026-03-31",
      "paid_at": "2025-04-01T10:00:00Z"
    },
    {
      "id": 15,
      "payment_item": {
        "id": 8,
        "name": "応援寄付",
        "type": "DONATION",
        "amount": 1000,
        "currency": "JPY"
      },
      "scope": {
        "type": "ORGANIZATION",
        "id": 2,
        "name": "FC Mannschaft"
      },
      "amount_paid": 1000,
      "currency": "JPY",
      "payment_method": "STRIPE",
      "status": "PAID",
      "valid_from": null,
      "valid_until": null,
      "paid_at": "2025-06-15T14:30:00Z"
    }
  ],
  "meta": {
    "page": 0,
    "size": 20,
    "total_elements": 5,
    "total_pages": 1
  }
}
```

> - `scope` はその支払い項目が属するチームまたは組織を示す（`payment_items.team_id` / `organization_id` から解決）
> - PENDING / CANCELLED の支払いも返す（ユーザーが決済途中の状態を確認できるようにする）
> - `stripe_checkout_session_id` / `stripe_payment_intent_id` / `stripe_refund_id` 等の Stripe 内部 ID はエンドユーザー向けレスポンスには含めない（ADMIN API のみ）
> - ソート: `paid_at DESC NULLS LAST, created_at DESC`（最新の支払い完了順。PENDING は作成日順で末尾）

---

#### `POST /api/v1/teams/{id}/payment-items/{itemId}/payments/{paymentId}/refund`（全額返金）

リクエストボディなし。ADMIN が対象の Stripe 決済を全額返金する。

> **操作の不可逆性について**: 返金は取り消しができない。フロントエンドは実行前に確認ダイアログ（「本当に返金しますか？この操作は取り消せません」）を必ず表示すること。一部返金や返金後の再処理など複雑な操作が必要な場合は Stripe ダッシュボード（`https://dashboard.stripe.com`）で直接操作する。

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 10,
    "user_id": 42,
    "payment_item_id": 1,
    "amount_paid": 5000,
    "payment_method": "STRIPE",
    "status": "REFUNDED",
    "stripe_refund_id": "re_xxxxxxxxxxxxxxxx",
    "refunded_at": "2026-03-10T10:00:00Z"
  }
}
```

**エラーレスポンス（返金時）**
| ステータス | 条件 |
|-----------|------|
| 403 | 権限不足（ADMIN でない）|
| 404 | 支払い記録が存在しない、または当該チーム/組織のスコープ外 |
| 409 | すでに REFUNDED または CANCELLED 済み |
| 422 | `payment_method = 'MANUAL'`（Stripe 返金の対象外。手動払いの取り消しは DELETE エンドポイントを使用）|
| 502 | Stripe API 通信失敗（DB 変更なし）|

---

#### `POST /api/v1/webhooks/stripe`

**ヘッダー**
```
Stripe-Signature: t=xxxx,v1=xxxx
```

**リクエストボディ**: Stripe の生 JSON イベントペイロード（Content-Type: application/json）

**対応イベント — Phase 3（一回払い）**
| Stripe イベント | 処理内容 |
|----------------|---------|
| `checkout.session.completed`（`mode=payment`）| `member_payments.status` を PENDING → PAID に更新、`paid_at` / `stripe_payment_intent_id` / `valid_from` / `valid_until` を設定 |
| `checkout.session.expired` | `member_payments.status` を PENDING → CANCELLED に更新（Checkout ページ期限切れ = 最終的な決済不成立）|
| `charge.refunded` | `member_payments.status` を REFUNDED に更新（アプリ内返金後の Webhook、または Stripe ダッシュボードからの直接返金どちらも対応）|

**対応イベント — Phase 4（Subscription）**
| Stripe イベント | 処理内容 |
|----------------|---------|
| `checkout.session.completed`（`mode=subscription`）| `member_subscriptions` を INSERT（ACTIVE）。`member_payments` の INSERT は `invoice.payment_succeeded` で行う（重複を避けるため）|
| `invoice.payment_succeeded` | サブスク課金成功: `member_payments` に PAID で INSERT（`valid_from/until` = 課金期間）、`member_subscriptions.current_period_end` を UPDATE |
| `invoice.payment_failed` | サブスク課金失敗: `member_subscriptions.status` を `PAST_DUE` に UPDATE、メンバーに通知 |
| `invoice.payment_action_required` | SCA（追加認証）が必要: メンバーに認証 URL を通知 |
| `customer.subscription.deleted` | 解約完了: `member_subscriptions.status` を CANCELLED、`cancelled_at` を UPDATE、メンバーに通知 |
| `customer.subscription.updated` | Stripe 側でサブスク状態が変化: `member_subscriptions.status` / `current_period_end` を同期 |

**レスポンス**
- 200 OK（処理成功。Stripe の再送防止のため必ず返す）
- 400（署名検証失敗）

> Webhook ハンドラ内では 5xx を返さない設計とする。内部エラー（DB 障害等）発生時は 200 を返しつつ audit_logs にエラーを記録し、アラートで検知する。Stripe は 2xx 以外を受け取ると数日かけてリトライするが、DB 整合性が壊れた状態で同一イベントを再処理するリスクがあるため、エラーは通知検知→手動対応の方針とする。

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
   - 失敗（Stripe API エラー）: RuntimeException をスローし @Transactional で DB 全体をロールバック → 502 を返す
     ※ Stripe 側の補償処理（後述）を catch ブロック内で実行した後にスロー
   ※ is_active = FALSE の場合は Stripe API を呼び出さない（stripe_price_id = NULL のまま）
6. audit_logs に PAYMENT_ITEM_CREATED を記録
7. 201 Created を返す
```

> **トランザクション管理方針**: メソッド全体を `@Transactional` で囲む。Stripe API 失敗時は RuntimeException のスローにより INSERT を含む全 DB 操作が自動ロールバックされるため、手動での DELETE は不要。ただし Stripe API は DB トランザクションの外にあるため、途中まで成功した Stripe リソース（Product 作成済み・Price 作成失敗等）に対しては catch ブロック内で補償処理を行う。
>
> **Stripe 側の補償処理**（catch ブロック内で実行、失敗しても DB ロールバックは続行）:
> - Product が作成済み → `POST /v1/products/{id} { active: false }` でアーカイブ
> - Price が作成済み → `POST /v1/prices/{id} { active: false }` でアーカイブ
> - 補償処理自体が失敗した場合は WARN ログを出力し、Stripe ダッシュボードでの手動削除に委ねる（孤立リソースが課金に使用されることはない）

---

### Stripe Price 管理フロー

> Stripe API は同期呼び出しで実装する。Stripe 失敗時は `@Transactional` による DB 自動ロールバック + Stripe 側の補償処理（作成済みリソースのアーカイブ）で不整合を防ぐ。

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

### Stripe 決済フロー（自己支払い / 寄付）

```
1. POST /api/v1/payment-items/{itemId}/checkout を受付
2. payment_items の team_id / organization_id を取得し、操作者が当該チーム/組織の SUPPORTER 以上のロールを持つか確認 → なければ 403
   ※ GUEST は支払い対象外（支払い要件が課されるコンテンツにアクセスしない前提）
3. payment_items が is_active = TRUE かつ stripe_price_id が存在するか確認 → なければ 422
4. type ≠ DONATION の場合: 対象ユーザーの PAID かつ有効期間内の member_payments が既存か確認 → あれば 409
   type = DONATION の場合: 重複チェックをスキップ（複数回寄付可能）
5. stripe_customers にユーザーのレコードがなければ Stripe API で Customer を作成し INSERT
6. member_payments に (status=PENDING, payment_method=STRIPE) で INSERT
7. Stripe API で Checkout Session を作成:
   - price: payment_items.stripe_price_id
   - customer: stripe_customers.stripe_customer_id
   - metadata: {"member_payment_id": "<上記で作成した ID>"}
8. member_payments.stripe_checkout_session_id を UPDATE
9. checkout_url を 200 OK で返す
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
   e. amount_paid = payment_intent.amount_received を通貨換算して上書き（PENDING 時の仮値を Stripe 実受領額で確定）
   f. valid_from = paid_at の日付（DATE）をセット（PENDING 時は NULL だったものを確定）
   g. valid_until を payment_items.type に応じて計算:
      - ANNUAL_FEE  → valid_from から +365日
      - MONTHLY_FEE → valid_from から +31日
      - ITEM        → NULL（無期限）
      - DONATION    → NULL（アクセス制御に紐付けない）
   h. status = PAID、paid_at = NOW() で UPDATE
   i. audit_logs に PAYMENT_COMPLETED を記録
   j. メンバーに支払い完了通知を送信（通知機能実装後）

   [checkout.session.expired]
   a. session の metadata から member_payment_id を取得
   b. member_payments を SELECT ... FOR UPDATE で取得
   c. status が PENDING であることを確認（PAID / CANCELLED は冪等処理でスキップ）
   d. status = CANCELLED で UPDATE
   e. audit_logs に PAYMENT_CHECKOUT_EXPIRED を記録
      （metadata: checkout_session_id, payment_item_id, user_id）
   f. メンバーに「決済が完了しませんでした」通知を送信（通知機能実装後）

   [charge.refunded]
   a. stripe_payment_intent_id で member_payments を SELECT ... FOR UPDATE で取得
      （アプリ内返金 API と webhook の同時実行を防ぐためロック必須）
   b. status が既に 'REFUNDED' ならスキップ（冪等）
   c. 全額返金か部分返金かを判定:
      - refund.amount == payment_intent.amount → 全額返金
      - refund.amount < payment_intent.amount  → 部分返金（Stripe ダッシュボード経由のみ発生）
   d. 全額返金の場合:
      status = REFUNDED、stripe_refund_id = refund.id、refunded_at = NOW() で UPDATE
   e. 部分返金の場合:
      status は PAID のまま変更しない（アクセス権を維持）。
      audit_logs に PAYMENT_PARTIALLY_REFUNDED を記録（refund.id, refund.amount, 残額）。
      ADMIN に通知を送信し、Stripe ダッシュボードでの確認を促す
   f. audit_logs に PAYMENT_REFUNDED（アプリ内返金後の Webhook）または
      PAYMENT_REFUNDED_VIA_WEBHOOK（Stripe ダッシュボードからの直接返金）を記録

4. 200 OK を返す（Stripe の再送防止）
```

---

### Stripe Subscription フロー（Phase 4 追加予定）

> **Phase 4 実装**。`payment_items.is_recurring = TRUE` の支払い項目に対する自動更新決済。月謝・年会費の毎期自動請求に対応する。設計思想は Phase 3 と同一：**アプリが主（正）、Stripe が従（鏡）**。Stripe 操作は同期呼び出しとし、失敗時は DB 変更をロールバックして不整合を防ぐ。

#### Stripe Subscription Price の生成（Phase 4 における Stripe Price 管理フロー A の変更点）

```
is_recurring = TRUE の payment_item を POST / PATCH 時:
  Phase 3（is_recurring=FALSE）: POST /v1/prices { currency, unit_amount, product }
  Phase 4（is_recurring=TRUE）:  POST /v1/prices { currency, unit_amount, product,
                                                   recurring: { interval: "month" or "year" } }
  ※ billing_interval = 'MONTHLY' → interval: "month"
     billing_interval = 'YEARLY'  → interval: "year"
  ※ 一回払い Price と自動更新 Price は Stripe 上で別オブジェクト（相互変換不可）
    → is_recurring の変更は禁止（422 を返す）。変更が必要な場合は削除して再作成
```

#### Subscription 開始フロー

> Checkout Session（`mode=subscription`）を経由することで、SCA・カード保管・初回課金をすべて Stripe に委譲する。バックエンドが Stripe Subscriptions API を直接呼ぶ場面はない。

```
[POST /api/v1/payment-items/{itemId}/checkout（is_recurring=TRUE の場合）]
1. payment_items.is_recurring=TRUE かつ stripe_price_id（recurring Price）が存在するか確認 → なければ 422
2. 対象ユーザーの ACTIVE または PAST_DUE な member_subscriptions が既存か確認 → あれば 409
3. stripe_customers にレコードがなければ Stripe Customer を作成し INSERT
4. Stripe API で Checkout Session を作成（同期呼び出し）:
     POST /v1/checkout/sessions {
       mode: "subscription",
       customer: stripe_customers.stripe_customer_id,
       line_items: [{ price: payment_items.stripe_price_id, quantity: 1 }],
       metadata: { user_id: "<ID>", payment_item_id: "<ID>" },
       success_url: ..., cancel_url: ...
     }
   失敗時: 502 を返す（DB 変更なし）
5. checkout_url を 200 OK で返す

[webhook: checkout.session.completed（mode=subscription）]
6. session.payment_status = 'paid' かつ session.subscription が存在することを確認
7. metadata から user_id / payment_item_id を取得
8. stripe_subscription_id で member_subscriptions を SELECT → 既存（重複 webhook）はスキップ（冪等）
9. Stripe API で Subscription を GET（current_period_start / current_period_end を取得）
10. member_subscriptions に INSERT:
    { user_id, payment_item_id, stripe_subscription_id, status: 'ACTIVE',
      current_period_start, current_period_end, cancel_at_period_end: false }
    ※ member_payments の INSERT は invoice.payment_succeeded webhook で行う（重複を避けるため）

[webhook: invoice.payment_succeeded]
11. invoice.billing_reason: "subscription_create"（初回）or "subscription_cycle"（更新）のみ処理
    （"manual" 等は対象外）
12. stripe_subscription_id で member_subscriptions を取得 → 存在しない場合は処理をスキップ（順序逆転対策）
13. invoice.payment_intent で member_payments を SELECT → 既存はスキップ（冪等）
14. member_payments に INSERT:
    { user_id, payment_item_id, amount_paid: invoice.amount_paid,
      payment_method: 'STRIPE', status: 'PAID',
      valid_from: current_period_start, valid_until: current_period_end,
      stripe_payment_intent_id: invoice.payment_intent, paid_at: NOW() }
15. member_subscriptions.current_period_end を UPDATE（次期分に更新）
16. audit_logs に PAYMENT_COMPLETED を記録
```

#### Subscription 解約フロー（期末解約）

> **Stripe 先、DB 後**の原則を徹底する。Stripe API 失敗時は DB を変更しない。

```
[DELETE /api/v1/payment-items/{itemId}/subscriptions/{subscriptionId}]
1. member_subscriptions を取得。status ≠ 'ACTIVE' または対象ユーザーでなければ 404/403
2. cancel_at_period_end = TRUE 既存なら 409（すでに解約予約済み）
3. Stripe API で期末解約を設定（同期呼び出し）:
     POST /v1/subscriptions/{stripe_subscription_id} { cancel_at_period_end: true }
   失敗時: DB 変更なし → 502 を返す（ロールバック不要。DB はまだ変更していない）
4. Stripe API 成功後のみ: member_subscriptions.cancel_at_period_end = TRUE に UPDATE
5. 200 OK を返す

[webhook: customer.subscription.deleted]
6. stripe_subscription_id で member_subscriptions を取得
7. status が既に CANCELLED ならスキップ（冪等）
8. member_subscriptions を UPDATE: { status: 'CANCELLED', cancelled_at: NOW() }
9. メンバーに解約完了通知
```

#### Subscription 解約取り消しフロー（期末解約のキャンセル）

```
[PATCH /api/v1/payment-items/{itemId}/subscriptions/{subscriptionId}/resume]
1. member_subscriptions を取得。cancel_at_period_end ≠ TRUE なら 422
2. current_period_end が過去日なら 422（期限切れのため再有効化不可）
3. Stripe API で解約取り消し（同期呼び出し）:
     POST /v1/subscriptions/{stripe_subscription_id} { cancel_at_period_end: false }
   失敗時: DB 変更なし → 502 を返す
4. Stripe API 成功後のみ: member_subscriptions.cancel_at_period_end = FALSE に UPDATE
5. 200 OK を返す
```

#### Subscription 整合性の保証

| 状況 | 対応 |
|------|------|
| `checkout.session.completed` が `invoice.payment_succeeded` より先に届く | 設計上の正常順序。checkout で subscriptions 作成 → invoice で payments 作成 |
| `invoice.payment_succeeded` が先に届く（順序逆転）| member_subscriptions が存在しない場合はスキップ。Stripe は後続の webhook を必ず送信するため最終的に整合する |
| Webhook 重複送信（Stripe リトライ）| `stripe_subscription_id` / `stripe_payment_intent_id` を冪等キーとしてチェック |
| `invoice.payment_failed` → `PAST_DUE` 状態 | Stripe の督促（dunning）設定に従い自動リトライ。最終失敗時に `customer.subscription.deleted` が発火 |

---

### 全額返金フロー（ADMIN によるアプリ内返金）

> **設計方針**: アプリ内から直接 Stripe 返金 API を呼び出し、管理者が複数ツールを行き来する負担を排除する。まず「全額返金」のみ提供し、一部返金など複雑な操作は Stripe ダッシュボードへ誘導する。**Stripe 先・DB 後**の原則を厳守し、Stripe 失敗時は DB を変更しない。

```
1. POST /api/v1/teams/{id}/payment-items/{itemId}/payments/{paymentId}/refund を受付
2. 操作者が当該チームの ADMIN（または MANAGE_PAYMENTS 権限を持つ DEPUTY_ADMIN）か確認 → でなければ 403
3. member_payments を SELECT ... FOR UPDATE で取得
   （Stripe Webhook の charge.refunded と競合しないようロック取得）
   → 存在しない、または当該チームスコープ外なら 404
4. payment_method = 'STRIPE' であることを確認 → MANUAL なら 422
   （手動払いの取り消しは DELETE エンドポイント（CANCELLED）を使用）
5. status = 'PAID' であることを確認:
   - 'REFUNDED': 409（返金済み）
   - 'CANCELLED': 409（取り消し済み）
   - 'PENDING': 422（決済未完了の PENDING は返金不可。決済完了後に操作すること）
6. Stripe API で全額返金を同期呼び出し（Stripe 先）:
     POST /v1/refunds {
       payment_intent: member_payments.stripe_payment_intent_id,
       metadata: { member_payment_id: "<ID>", refunded_by: "<操作者の user_id>" }
     }
   失敗時（502）: DB を一切変更しない → 502 を返す
7. Stripe API 成功後のみ DB を更新:
   - member_payments.status          = 'REFUNDED'
   - member_payments.stripe_refund_id = refund.id（re_xxxx）
   - member_payments.refunded_at      = NOW()
8. audit_logs に PAYMENT_REFUNDED を記録（refunded_by = 操作者の user_id, operator_role = ADMIN or DEPUTY_ADMIN）
9. 200 OK を返す

[webhook: charge.refunded の冪等処理]
  ※ アプリ内返金後、Stripe から charge.refunded webhook が届く。
    a. stripe_payment_intent_id で member_payments を取得
    b. status が既に 'REFUNDED' ならスキップ（冪等）
    c. 全額返金か部分返金かを判定（refund.amount vs payment_intent.amount）:
       - 全額返金かつ REFUNDED でない場合: step 7 と同様に status / stripe_refund_id / refunded_at を更新し
         audit_logs に PAYMENT_REFUNDED_VIA_WEBHOOK を記録
       - 部分返金の場合: status は PAID のまま維持。audit_logs に PAYMENT_PARTIALLY_REFUNDED を記録し ADMIN に通知
```

**フロントエンド実装指針**

| 項目 | 仕様 |
|------|------|
| 返金ボタン | ADMIN（または MANAGE_PAYMENTS 権限を持つ DEPUTY_ADMIN）の支払い詳細画面に表示（`payment_method = 'STRIPE'` かつ `status = 'PAID'` の場合のみ活性）|
| 確認ダイアログ | 「本当に返金しますか？この操作は取り消せません」という強力な警告を必ず表示 |
| 複雑な操作への誘導 | 一部返金・返金後の再請求など複雑な操作が必要な場合は「Stripe ダッシュボードで操作してください」リンクを提示 |

---

### 手動支払い記録フロー（ADMIN）

```
1. POST /api/v1/teams/{id}/payment-items/{itemId}/payments を受付
2. 操作者が ADMIN（または MANAGE_PAYMENTS 権限を持つ DEPUTY_ADMIN）か確認
3. 対象ユーザーが当該チームのメンバーか確認
4. 対象ユーザーの PAID かつ有効期間内の member_payments が既存か確認 → あれば 409
   （判定: `status = 'PAID' AND (valid_until IS NULL OR valid_until >= CURDATE())`）
5. リクエストボディの valid_from / valid_until を使用（省略時は paid_at の日付を valid_from に自動セットし、type から valid_until を計算）
6. member_payments に (status=PAID, payment_method=MANUAL, recorded_by=操作者) で INSERT
7. audit_logs に PAYMENT_MANUALLY_RECORDED を記録（operator_user_id = 操作者, operator_role = ADMIN or DEPUTY_ADMIN, target_user_id = 対象ユーザー）
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
6. 未払いの場合:
   - is_title_hidden = FALSE → 当該コンテンツを「🔒 + タイトル」付きで一覧に含める（内容は返さない）
   - is_title_hidden = TRUE  → 当該コンテンツをレスポンスから除外する（存在自体を隠す）
```

> **一覧取得 SQL のフィルタリング指針（is_title_hidden = TRUE の場合）**
>
> コンテンツ一覧クエリ（例: `SELECT * FROM posts WHERE team_id = ?`）に以下を付加する:
> ```sql
> -- 未払い × 完全非公開のコンテンツを除外
> AND NOT EXISTS (
>   SELECT 1 FROM content_payment_gates cpg
>   WHERE cpg.content_type = 'POST'
>     AND cpg.content_id = posts.id
>     AND cpg.is_title_hidden = TRUE
>     AND NOT EXISTS (
>       SELECT 1 FROM member_payments mp
>       WHERE mp.user_id         = :currentUserId
>         AND mp.payment_item_id = cpg.payment_item_id
>         AND mp.status          = 'PAID'
>         AND (mp.valid_until IS NULL OR mp.valid_until >= CURDATE())
>     )
> )
> ```
> 上記は「is_title_hidden=TRUE のゲートが存在する、かつ未払い」なコンテンツのみを除外する。払い済みコンテンツおよび is_title_hidden=FALSE のコンテンツは通常通り返す。

**ロック状態の表示仕様**

| 状態 | `is_title_hidden` | フロントエンドの表示 |
|------|:-----------------:|----------------|
| 全体ロック（未払い）| — | チーム/組織のトップに「〇〇の支払いが必要です」バナーを表示し、コンテンツ一覧は非表示 |
| コンテンツ単位ロック（タイトル表示）| FALSE（デフォルト）| コンテンツ一覧には「🔒 タイトル」で表示（存在は示すが内容は隠す）。「支払うと何が見られるか」をユーザーが判断できる |
| コンテンツ単位ロック（完全非公開）| TRUE | レスポンスからコンテンツを除外。存在自体が未払い者に伝わらない。機密性の高い限定情報に適用 |

> **ADMIN 向けの設定ガイドライン（フロントエンドの設定 UI で表示推奨）**:
> - `is_title_hidden = FALSE`（デフォルト）: 支払いを促したい「魅力的なコンテンツ」に使用。タイトルを見せることで購入動機を作れる
> - `is_title_hidden = TRUE`: 機密性の高い「限定情報」に使用。コンテンツの存在すら非公開にする

---

### 有効期限切れバッチ

「判定（AM 3:00）」と「通知送信（AM 8:00）」の2フェーズに分離する。AM 3:00 の低トラフィック帯に重い集計クエリを済ませてキューを生成し、ユーザーが活動する AM 8:00 に軽い読み取りで通知を送ることで、**サーバー負荷の分散**と**通知開封率の向上**を両立する。

#### フェーズ 1: 有効期限監視バッチ（毎日 AM 3:00 JST）

```
@Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
@ShedLock(name = "payment-expiry-detection", lockAtMostFor = "30m")

1. 通知対象を2種類のクエリで取得（どちらも同一のフィルタ条件を適用）:

   共通フィルタ:
   - mp.status = 'PAID'
   - 更新済みスキップ（下記 NOT EXISTS）:
       NOT EXISTS (
         SELECT 1 FROM member_payments mp2
         WHERE mp2.user_id          = mp.user_id
           AND mp2.payment_item_id  = mp.payment_item_id
           AND mp2.status = 'PAID'
           AND mp2.valid_until > mp.valid_until  -- より新しい有効 PAID が存在 = 更新済み
       )
   - Subscription 自動更新メンバーをスキップ（Stripe dunning が担当するため重複通知を防ぐ）:
       NOT EXISTS (
         SELECT 1 FROM member_subscriptions ms
         WHERE ms.user_id          = mp.user_id
           AND ms.payment_item_id  = mp.payment_item_id
           AND ms.status IN ('ACTIVE', 'PAST_DUE')  -- 自動更新中 or 督促中はスキップ
       )

   [7日前リマインド対象]
   WHERE mp.valid_until = DATE_ADD(CURDATE(), INTERVAL 7 DAY)
   + 上記共通フィルタ

   [期限当日対象]
   WHERE mp.valid_until = CURDATE()
   + 上記共通フィルタ

2. 各対象を通知キューに PENDING で登録（通知機能との連携。通知種別: PAYMENT_EXPIRY_REMINDER_7D / PAYMENT_EXPIRY_DAY）
3. audit_logs に PAYMENT_EXPIRY_QUEUED を記録（通知対象件数・種別）
```

#### フェーズ 2: 有効期限通知バッチ（毎日 AM 8:00 JST）

```
@Scheduled(cron = "0 0 8 * * *", zone = "Asia/Tokyo")
@ShedLock(name = "payment-expiry-notification", lockAtMostFor = "30m")

1. フェーズ 1 でキューされた当日 PENDING の通知対象を取得
2. ユーザーごとにプッシュ通知 / メール通知を送信（通知機能 spec 参照）
   - PAYMENT_EXPIRY_REMINDER_7D: 「{payment_item.name} の有効期限が7日後（{valid_until}）に迫っています」
   - PAYMENT_EXPIRY_DAY:        「{payment_item.name} の有効期限が本日（{valid_until}）です。お早めにお手続きください」
3. 送信済みを SENT にマーク・失敗を FAILED にマーク
4. audit_logs に PAYMENT_EXPIRY_NOTIFIED を記録（送信成功件数）
```

#### status 非変更の設計意図

`member_payments.status` は期限切れ後も `PAID` のまま変更しない。

| 理由 | 説明 |
|------|------|
| 事実の保持 | 「過去に確かに支払った」という事実は `PAID` として永続する。遡及修正・返金トレースに不可欠 |
| 有効判定の一元化 | 有効かどうかの判定は `valid_until >= CURDATE()` の計算で行い、バッチによる status 書き換えに依存しない |
| バッチ停止耐性 | バッチが遅延・未実行でも「EXPIRED 書き換え済み / 未済」の中間状態が発生せず、アクセス制御は常に正確に機能する |
| ADMIN 手動延長の簡潔さ | `valid_until` を UPDATE するだけで即時アクセス回復。status を EXPIRED → PAID に戻す操作が不要 |

#### アクセス制御との連携

```
有効判定（Section 5「アクセス制御解決ロジック」より）:
  status = 'PAID'
  AND (valid_until IS NULL OR valid_until >= CURDATE())

→ valid_until < CURDATE() のレコードはバッチ実行の有無にかかわらずリアルタイムにアクセス不可
→ バッチは「通知を送る」ためのみ存在し、アクセス制御の判定に関与しない
```

---

## 6. セキュリティ考慮事項

- **Webhook 署名検証**: `Stripe-Signature` ヘッダーを必ず検証する。検証失敗は即 400 を返す（Stripe SDK の `constructEvent` を使用）
- **冪等性**: Stripe のリトライに備え、全 Webhook イベントを `member_payment_id` 単位で冪等処理する（`checkout.session.completed`: PAID 済みはスキップ、`checkout.session.expired`: CANCELLED 済みはスキップ、`charge.refunded`: REFUNDED 済みはスキップ）
- **Checkout セッションの整合性検証**: Webhook 受信時に metadata の `member_payment_id` でDBを引き、`payment_item_id` や `user_id` が一致することを確認する
- **手動記録の監査**: ADMIN による手動記録・修正・取り消しは全件 audit_logs に記録する（不正操作の追跡）
- **リダイレクト URL の固定**: Checkout Session の `success_url` / `cancel_url` はフロントから受け取らず、サーバー側の `APP_FRONTEND_URL` 環境変数 + 固定パスで生成する。オープンリダイレクト脆弱性を構造的に排除
- **レートリミット**: `POST /payment-items/{id}/checkout` に Bucket4j で 5 req/min per user を適用（二重決済防止）
- **認可チェック**: `payment_items` は作成したチーム/組織スコープ内でのみ参照可能。他チームの payment_item を `access_requirements` や `content_payment_gates` に設定することはアプリ層で禁止する（横断スコープアクセス防止）。`content_payment_gates` への `payment_item_id` 設定時は、当該コンテンツ所属チーム/組織内の payment_item であることをアプリ層で検証する
- **金額整合性**: Webhook 受信時に `payment_intent.amount_received` を `amount_paid` に記録する。`payment_items.amount` との乖離が発生した場合（将来の Stripe Coupon 適用等）は audit_logs に `PAYMENT_AMOUNT_MISMATCH` として記録し、ADMIN に通知する（不正割引の検知）
- **手動操作の監査**: ADMIN および `MANAGE_PAYMENTS` 権限を持つ DEPUTY_ADMIN による手動記録・修正・取り消しは全件 audit_logs に記録する（`operator_user_id` / `operator_role` を必ず含める）
- **返金の監査**: ADMIN による返金操作は audit_logs に全件記録する（`refunded_by`、対象 `member_payment_id`、返金金額）。Stripe ダッシュボードからの直接返金は webhook `charge.refunded` で検知し `PAYMENT_REFUNDED_VIA_WEBHOOK` として別ラベルで記録する
- **返金スコープ**: 返金エンドポイントは `payment_method = 'STRIPE'` のレコードのみ受け付ける。手動払いの取り消しは `DELETE` エンドポイント（`status = 'CANCELLED'`）に一本化し、Stripe 操作を伴う返金と明確に分離する
- **部分返金の安全処理**: アプリ内では全額返金のみ提供する。Stripe ダッシュボードから部分返金が行われた場合、`charge.refunded` Webhook で検知するが `status` は `PAID` のまま維持しアクセス権を保護する。ADMIN に通知して手動確認を促す
- **`type` イミュータブル**: `payment_items.type` は PATCH で変更不可。`ANNUAL_FEE` → `DONATION` 等の変更は `access_requirements` / `content_payment_gates` との紐付け整合性を破壊するため 422 で拒否する

### Stripe API キー・Webhook Secret 管理方針

**環境変数一覧**
| 変数名 | 用途 | 環境別 |
|--------|------|--------|
| `STRIPE_SECRET_KEY` | Stripe API 認証（Secret Key: `sk_live_xxx` / `sk_test_xxx`） | 環境ごとに異なるキー |
| `STRIPE_WEBHOOK_SECRET` | Webhook 署名検証（`whsec_xxx`） | 環境ごとに異なるシークレット |
| `STRIPE_PUBLISHABLE_KEY` | フロントエンド用 Publishable Key（`pk_live_xxx` / `pk_test_xxx`） | 環境ごとに異なるキー |

**設計方針**
- **ソースコード非格納**: すべての Stripe シークレットは環境変数から注入する。`application.yml` にはプレースホルダ（`${STRIPE_SECRET_KEY}`）のみ記載し、デフォルト値は設定しない（未設定時はアプリ起動失敗で即検知）
- **環境分離**: 開発（`sk_test_*`）・ステージング（`sk_test_*` で別アカウント）・本番（`sk_live_*`）で異なる Stripe アカウント / API キーを使用する。テスト環境のキーで本番データにアクセスすることは Stripe の仕様上不可能（構造的分離）
- **ローテーション**: Stripe API キーは Stripe ダッシュボードから即座にロール（失効・再発行）可能。キー漏洩が疑われる場合は Stripe ダッシュボードで即座にキーをロールし、サーバー側の環境変数を更新・再デプロイする。定期ローテーション（年1回推奨）の手順を運用ドキュメントに記載する
- **Webhook Secret のローテーション**: Stripe ダッシュボードで新しい Webhook エンドポイントを作成 → 新旧両方のシークレットで署名検証を並行実施 → 旧エンドポイントを削除、の手順でダウンタイムなくローテーションする
- **アクセス制限**: Stripe ダッシュボードへのアクセスは 2FA 必須。API キーを閲覧できるロールを最小限に制限する（Stripe の Team 機能を活用）
- **ログ出力禁止**: API キー・Webhook Secret をアプリケーションログに出力しない。Stripe SDK のリクエスト/レスポンスログにはキーがマスクされた状態で出力されることを確認する

---

## 7. Flywayマイグレーション

**Phase 3**
```
V3.001__create_payment_items_table.sql
V3.002__create_stripe_customers_table.sql
V3.003__create_member_payments_table.sql
  -- ※ stripe_refund_id / refunded_at を含む完全な定義で作成（旧 V3.007 を統合）
V3.004__create_team_access_requirements_table.sql
V3.005__create_organization_access_requirements_table.sql
V3.006__create_content_payment_gates_table.sql
  -- ※ is_title_hidden を含む完全な定義で作成（旧 V3.009 を統合）
```

**Phase 3 追加マイグレーション（権限）**
```
V3.007__add_manage_payments_permission.sql
  -- permissions に MANAGE_PAYMENTS（display_name='支払い管理', scope='ORGANIZATION'）を INSERT
  -- role_permissions に追加:
  --   (SYSTEM_ADMIN, MANAGE_PAYMENTS, is_default=TRUE)
  --   (ADMIN,        MANAGE_PAYMENTS, is_default=TRUE)
  --   (DEPUTY_ADMIN, MANAGE_PAYMENTS, is_default=FALSE)
  ※ F03 の V3.007 と同一ファイル。F03 と F04 は同一 Phase 3 実装でまとめて投入する
```

> **統合済みマイグレーション**: `stripe_refund_id` / `refunded_at`（旧 V3.007）は V3.003 に、`is_title_hidden`（旧 V3.009）は V3.006 にそれぞれ統合。Phase 3 未実装のため ALTER TABLE ではなく CREATE TABLE に含める

**Phase 3 マイグレーション注意点**
- V3.001 は V2.001（organizations）/ V2.002（teams）/ V2.008（user_roles）完了後に実行
- V3.003（member_payments）は V3.001（payment_items）/ V3.002（stripe_customers）完了後
- V3.004 / V3.005 は V3.001（payment_items）完了後
- V3.006 は V3.001（payment_items）完了後（content_id は各コンテンツテーブルへの論理 FK; 物理 FK は設定しない）

**Phase 4（Stripe Subscription 対応）**
```
V4.001__add_recurring_to_payment_items.sql
  -- payment_items テーブルへの追加:
  --   is_recurring    BOOLEAN NOT NULL DEFAULT FALSE
  --   billing_interval ENUM('MONTHLY', 'YEARLY') NULL
  --   CHECK chk_pi_billing_interval
  --     (is_recurring = FALSE AND billing_interval IS NULL)
  --     OR (is_recurring = TRUE AND billing_interval IS NOT NULL)

V4.002__create_member_subscriptions_table.sql
  -- member_subscriptions テーブル新規作成
  -- FK: user_id → users (ON DELETE RESTRICT)
  -- FK: payment_item_id → payment_items (ON DELETE RESTRICT)
  -- UNIQUE: (user_id, payment_item_id)
  -- UNIQUE: stripe_subscription_id
```

---

## 8. 未解決事項

- [x] `payment_items.stripe_price_id` の設定方法: **自動生成を基本とし、手動紐付けも移行目的でサポートする方式に決定**（Section 5「Stripe Price 管理フロー」参照）。①`is_active = TRUE` で支払い項目を作成すると Stripe Product・Price を同期生成（フロー A）。②既存 Stripe 運用からの移行用として `stripe_price_id` を直接指定した場合は Stripe から金額・通貨を取得してバリデーション（フロー B）。③`amount`/`currency` 変更時は旧 Price をアーカイブし新 Price を生成（フロー C）。④`is_active` 変更時の Stripe 操作も定義済み（フロー D）。Stripe API は同期呼び出し・失敗時は DB ロールバック
- [x] Stripe Subscription（自動更新）対応（Phase 4 以降）: **Phase 4 設計を確定**（Section 3・5・7 に追加済み）。`payment_items.is_recurring` フラグで一回払い / 自動更新を切り替え。Stripe Checkout `mode=subscription` 経由で加入（SCA・カード保管を Stripe に委譲）。解約・再有効化 API は「Stripe 先、DB 後」でロールバック一貫性を保証。Webhook 冪等性は `stripe_subscription_id` / `stripe_payment_intent_id` をキーとして保証。`invoice.payment_succeeded` で毎期の `member_payments` PAID レコードを生成（アクセス制御ロジックはそのまま流用可能）。`is_recurring` の後変更は禁止（422）
- [x] 有効期限切れバッチのスケジュール（毎日 AM3:00 等）と通知タイミング（期限7日前・当日等）の確定: **2フェーズ設計に確定**。AM 3:00 監視バッチ（ShedLock）で通知対象を抽出してキュー登録、AM 8:00 通知バッチで送信。通知は「7日前リマインド（REMINDER_7D）」と「期限当日（EXPIRY_DAY）」の2種。更新済みメンバー（新しい有効 PAID が存在）および Subscription 自動更新中メンバーはスキップ。`member_payments.status` は変更しない（有効判定は `valid_until >= CURDATE()` でリアルタイム計算）
- [x] `content_payment_gates.content_type` に追加する値（CHAT_MESSAGE、VIDEO 等）を各コンテンツ機能の設計時に確定する: **VARCHAR(50) + アプリ層バリデーション方式に変更**（ENUM 廃止）。Phase 3 初期値: `POST` / `FILE` / `ANNOUNCEMENT` / `SCHEDULE`。Phase 4+ 予定: `VIDEO` / `CHAT_MESSAGE`。新モジュール追加時は `ContentGateType` 定数に追加するのみで DB ALTER TABLE 不要（OCP 遵守）。不正値は 422 で弾く
- [x] 返金操作の提供範囲: **アプリ内全額返金エンドポイントを提供する方式に確定**（Section 4・5 参照）。ADMIN が支払い詳細画面の「返金実行」ボタンから直接 Stripe Refund API を呼び出し、`member_payments.status` を `REFUNDED` に更新する。まず「全額返金のみ」をサポートし、一部返金などの複雑な操作は Stripe ダッシュボードへ誘導する。`charge.refunded` Webhook 受信時も同様のステータス更新ロジックを走らせ冪等性を確保。手動払い（`payment_method = 'MANUAL'`）は返金エンドポイントの対象外とし、既存の DELETE（CANCELLED）エンドポイントで対応
- [x] DEPUTY_ADMIN への `MANAGE_PAYMENTS` パーミッション追加: **F03 の V3.007 マイグレーションで確定**（Section 7 参照）。SYSTEM_ADMIN ✓ / ADMIN ✓ / DEPUTY_ADMIN △（権限グループ経由で個別委譲）。MEMBER には付与不可。scope = ORGANIZATION（F04 が組織・チーム両方の支払い管理 API を持つため ORGANIZATION に確定）
- [x] コンテンツ単位ロックのタイトル表示: **`is_title_hidden` フラグ（BOOLEAN, DEFAULT FALSE）を `content_payment_gates` に追加して解決**（Section 3・5 参照）。FALSE = 「🔒 + タイトル」表示（購入動機を訴求したいコンテンツ向け）/ TRUE = 完全非公開（コンテンツの存在自体を除外・機密情報向け）。一覧取得クエリでの NOT EXISTS フィルタリング指針を Section 5 に追記

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-10 | 精査②: ①V4.002 Flyway の ON DELETE CASCADE → RESTRICT 修正 ②部分返金 Webhook ハンドリング追加（PAID 維持 + ADMIN 通知） ③`type` イミュータブル制約を PATCH 仕様に追加 ④DEPUTY_ADMIN（MANAGE_PAYMENTS）のエンドポイント認可を一覧表に反映（GET/POST/PATCH payments） ⑤`payment_item_id` FK に ON DELETE RESTRICT を全テーブルで明示 ⑥Phase 3 マイグレーション統合（V3.007/V3.009 を V3.003/V3.006 に吸収） ⑦SUPPORTER スコープに DONATION を明記 ⑧`created_by` SET NULL のフェイルセーフ注釈追加 |
| 2026-03-10 | 精査対応（設計完了）: ①`payment_items` に XOR CHECK 制約（`chk_pi_scope`）を明記し DB レベルの排他保証を追加 ②`payment_item_id` の関連テーブルカスケード削除をアプリ層制御と明示（物理 CASCADE なし）③`valid_from` は NULL 禁止・`paid_at` 当日を自動セット ④ITEM 型 `valid_until = NULL` は「買い切り永続アクセス」と確定 ⑤409 判定条件を SQL 式で明確化 ⑥Webhook イベント表を Phase 3（一回払い）/ Phase 4（Subscription）に分割 ⑦Webhook ハンドラで 5xx を返さない設計方針を追加 ⑧返金 API と `charge.refunded` Webhook の競合を `SELECT ... FOR UPDATE` で解決 ⑨`cancel_at_period_end` ライフサイクルと解約後 `member_payments` の扱いを明記 ⑩DEPUTY_ADMIN の操作も audit_logs 対象に（`operator_role` カラム記録）⑪`content_payment_gates` の横断スコープ防止を明示 ⑫`payment_status` フィールドを `status` に統一（レスポンスの用語揺れ修正）⑬ドキュメントステータスを「設計完了」に更新 |
| 2026-03-10 | `content_payment_gates` に `is_title_hidden` フラグ追加（未解決⑦解決）: 未払い者向けの秘匿レベルを FALSE（🔒 + タイトル表示）/ TRUE（完全非公開）の2択で制御。一覧取得クエリの NOT EXISTS フィルタリング SQL 指針・ADMIN 向け設定ガイドラインを追加。Flyway V3.009 追加 |
| 2026-03-10 | `MANAGE_PAYMENTS` パーミッション scope を ORGANIZATION に確定し F04 未解決⑥を解決: F03 の V3.007 マイグレーションで SYSTEM_ADMIN/ADMIN/DEPUTY_ADMIN に追加。DEPUTY_ADMIN は天井（is_default=FALSE）として定義し権限グループ経由で個別委譲可能 |
| 2026-03-10 | アプリ内全額返金機能を追加（未解決⑤解決）: ADMIN が支払い詳細画面から直接 Stripe 返金 API を呼び出せるエンドポイント（`POST /payments/{paymentId}/refund`）を追加。返金ボタン・強力な確認ダイアログ・Stripe ダッシュボード誘導リンクの UI 指針を定義。`member_payments` に `stripe_refund_id` / `refunded_at` カラム追加（V3.007 マイグレーション）。`charge.refunded` Webhook との冪等処理・手動払いとの分離・監査ログ設計を明文化 |
| 2026-03-10 | `content_payment_gates.content_type` を ENUM → VARCHAR(50) に変更（未解決④解決）: 疎結合・OCP 遵守のため VARCHAR + アプリ層バリデーション方式を採用。`ContentGateType` 定数クラスと許容値一覧・Java 実装指針を追記。Phase 3 初期値（POST/FILE/ANNOUNCEMENT/SCHEDULE）と Phase 4+ 予定値（VIDEO/CHAT_MESSAGE）を文書化 |
| 2026-03-10 | 有効期限切れバッチ設計確定（未解決③解決）: AM 3:00 監視バッチ + AM 8:00 通知バッチの2フェーズ設計。通知タイミング（7日前・当日）・Subscription 自動更新メンバーのスキップ条件・status 非変更の設計根拠を追記 |
| 2026-03-10 | Stripe Subscription 設計確定（未解決②解決）: Phase 4 設計として `is_recurring` / `billing_interval` カラム追加計画・`member_subscriptions` テーブル定義・Subscription 開始 / 解約 / 再有効化フロー・新規 Webhook イベント（`invoice.payment_succeeded` 等）・Flyway Phase 4 マイグレーション（V4.001〜002）を追加。Checkout エンドポイントに mode=subscription 分岐を追記 |
| 2026-03-10 | `stripe_price_id` 管理フロー確定（未解決①解決）: POST/PATCH に `is_active` フィールド追加・Stripe 自動生成フロー（A）・手動紐付けフロー（B）・金額変更フロー（C）・is_active 変更フロー（D）を追加。PATCH 仕様セクション新規追加。支払い項目作成フローに Stripe 同期呼び出し・ロールバック処理を追加 |
| 2026-02-21 | 初版作成 |
