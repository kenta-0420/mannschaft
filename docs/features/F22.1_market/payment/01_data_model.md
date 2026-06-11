# F22.1 市（Market）謝礼決済 — 01. データモデル

> 親: [README.md](README.md) ／ 関連: [02_api_design.md](02_api_design.md) / [03_security.md](03_security.md)

---

## 1. 方針

- **新規ドメイン `payment.escrow` / `payment.connect` に閉じる**。`market`/`recruitment`/`team` からは ID 論理参照のみ。**クロスドメインFKは張らない**（CLAUDE.md 原則1）。
- **新規テーブルは全て `UuidV7Entity` 継承（`id BINARY(16)`）**（CLAUDE.md 原則6）。F13.1 の BIGINT テーブルは流用しない。
- **CASCADE は payment ドメイン内のみ**（`escrow_transactions` → `ledger_entries`/`refunds`）。外部（recruitment/team/user）は論理参照（CLAUDE.md 原則2）。
- **テナントスコープ**: `organization_id` を持つテーブルは `AbstractTenantAwareRepository` を実装（CLAUDE.md 原則7）。`connect_accounts`/`escrow_transactions` は `organization_id` を保持。
- **通貨**: JPY はゼロデシマル通貨。`amount` 系は**円そのもの（最小単位）の整数**で保持し、`currency CHAR(3)` で明示。Stripe へは円整数で渡す。
- **暗号化**: `connect_accounts.stripe_account_id`・`escrow_transactions.stripe_payment_intent_id` は識別子であり PII ではないが、Connect の `requirements_due`（KYC 要件）は個人特定に繋がりうるため JSON で最小化保持し、内容は Stripe を正典とする（自社は鏡像最小化）。
- **実装原則（統一基盤・正典）**: (1) **クロスドメインFK禁止**（recruitment/team/user/membership への参照は論理参照のみ）、(2) **ドメイン間は疎結合（ApplicationEvent）**で接続し `payment.escrow` の `@Transactional` はドメイン内に閉じる（README §7・CLAUDE.md 原則5）、(3) **`@Query` 内コメント厳禁**（JPQL/native query 内に SQL コメントを書かない・パース不整合回避）、(4) 謝礼・会費の手数料計算は `PaymentFeeCalculator` に一元化し文字列・数式を散在させない（02 §3.5）。

---

## 2. テーブル一覧

| テーブル名 | 役割 | ドメイン | 論理削除 | 主キー |
|---|---|---|---|---|
| `connect_accounts` | 受領者（個人/チーム/組織）の Stripe Connect Express アカウント管理 | `payment.connect` | あり（`deleted_at`） | UUIDv7 |
| `escrow_transactions` | エスクロー取引（PaymentIntent 1:1・与信→capture→払出/返金の状態管理） | `payment.escrow` | なし（status 管理） | UUIDv7 |
| `ledger_entries` | 複式記帳台帳（取引ごとの借方/貸方・残高） | `payment.escrow` | なし（追記専用） | UUIDv7 |
| `refunds` | 返金記録（部分/全額） | `payment.escrow` | なし | UUIDv7 |
| `stripe_webhook_events` | Webhook 冪等性キー（event_id 一意） | `payment`（共通） | なし（TTL 物理削除） | UUIDv7 |
| `fee_policies` | **手数料パターンのマスタ表**（率%＋固定額¥・自然キー `policy_key`） | `payment`（共通） | なし（`enabled` で無効化） | **自然キー `policy_key`**（マスタ例外） |
| `fee_policy_assignments` | 手数料パターンの割当（source_kind＋任意 sub_key → policy_key） | `payment`（共通） | あり（`deleted_at`） | UUIDv7 |

> `source_kind` で RECRUITMENT（謝礼・市）/ **MEMBERSHIP（会費・F08.2）** / **TOURNAMENT（大会参加費・F08.7.1 移行後）** / JOBMATCHING（F13.1 将来）/ FLEAMARKET（フリマ将来）を**1テーブルで束ねる**（統一決済プラットフォーム・README §1.0）。P2-b/P2-c が接続するのは RECRUITMENT（エスクローモード）、P2-e が MEMBERSHIP（即時モード）。TOURNAMENT/JOBMATCHING/FLEAMARKET は値だけ確保（転用点）。会費専用台帳は作らない。
> **手数料ランク化（README §3.4・正典 2026-06-04）**: 手数料は定数でなく `fee_policies`（マスタ・率%＋固定額¥）で持ち、`fee_policy_assignments` で source_kind＋任意 sub_key に割当てる。`escrow_transactions.fee_policy_key` に適用パターンを焼き付け遡及防止（§3.2 / §3.6）。`fee_policies` は **CLAUDE.md「マスタテーブル例外」**（全テナント共通の参照データ・書込はシスアド運用のみ）として**自然キー `policy_key`**（UUIDv7 不要）で設計する。割当表 `fee_policy_assignments` はテナント横断の運用データだが行が増えるため UUIDv7 とする。

---

## 3. テーブル定義

### 3.1 `connect_accounts`（受領者の Connect アカウント）

札ごとの受領主体（個人/チーム/組織）が**それぞれ独立した Connect アカウント**を持てるよう、`scope_kind` ＋ `scope_id` で抽象化する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `id` | BINARY(16) | NO | (UUIDv7) | PK |
| `scope_kind` | VARCHAR(8) | NO | — | `USER` / `TEAM` / `ORG`（受領主体の種別） |
| `scope_id` | BIGINT UNSIGNED | NO | — | 主体ID（USER=users.id / TEAM=teams.id / ORG=organizations.id）。**論理参照（FKなし）** |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | テナント絞り込み用（TEAM/ORG は所属組織、USER は主所属組織を記録。シャードキー候補） |
| `stripe_account_id` | VARCHAR(32) | NO | — | Stripe Connect アカウントID（`acct_xxx`）。一意 |
| `onboarding_status` | VARCHAR(16) | NO | `'PENDING'` | `PENDING`（リンク発行前）/ `ONBOARDING`（hosted 中）/ `READY` / `RESTRICTED`（要件不足）/ `DISABLED`（deauthorized） |
| `charges_enabled` | BOOLEAN | NO | FALSE | Stripe `charges_enabled` の鏡像 |
| `payouts_enabled` | BOOLEAN | NO | FALSE | Stripe `payouts_enabled` の鏡像（**払出可否の判定軸**） |
| `requirements_due` | JSON | YES | NULL | Stripe `requirements.currently_due`（不足項目）の鏡像（最小化） |
| `country` | CHAR(2) | NO | `'JP'` | アカウント国（当面 JP 固定） |
| `default_currency` | CHAR(3) | NO | `'JPY'` | 既定通貨 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除（退会/解約時の切離し。§GDPR） |

**制約・インデックス**
```sql
CONSTRAINT chk_ca_scope_kind CHECK (scope_kind IN ('USER','TEAM','ORG'))
CONSTRAINT chk_ca_onboarding CHECK (onboarding_status IN ('PENDING','ONBOARDING','READY','RESTRICTED','DISABLED'))
UNIQUE KEY uk_ca_stripe_account (stripe_account_id)
-- 1 主体につき 1 アカウント（論理削除を除く）。アプリ層で deleted_at IS NULL を加味
UNIQUE KEY uk_ca_scope (scope_kind, scope_id, deleted_at)
INDEX idx_ca_org (organization_id)
INDEX idx_ca_payouts (payouts_enabled)
```
> `uk_ca_scope` に `deleted_at` を含めるのは、退会で論理削除後に同一主体が再 onboarding できるようにするため（NULL 同士は MySQL UNIQUE で重複許容＝1アクティブ行＋複数削除済を許す）。

---

### 3.2 `escrow_transactions`（エスクロー取引・PaymentIntent 1:1）

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `id` | BINARY(16) | NO | (UUIDv7) | PK |
| `source_kind` | VARCHAR(12) | NO | — | `RECRUITMENT`（謝礼・エスクローモード）/ `MEMBERSHIP`（会費・即時モード）/ `JOBMATCHING` / `FLEAMARKET`（出所種別・転用点・README §1.0） |
| `capture_mode` | VARCHAR(10) | NO | `'MANUAL'` | `MANUAL`（エスクローモード＝謝礼・与信後 capture）/ `AUTOMATIC`（即時モード＝会費・即 capture）。`ConnectChargeService` が Stripe `capture_method` へマッピング |
| `source_id` | BIGINT UNSIGNED | NO | — | 出所ID（RECRUITMENT=recruitment_listings.id）。**論理参照（FKなし）** |
| `source_participant_id` | BIGINT UNSIGNED | YES | NULL | 個別応募の特定用（recruitment_participants.id 論理参照・1札に複数払出があり得る場合） |
| `payer_scope_kind` | VARCHAR(8) | NO | — | 支払者種別 `USER` / `TEAM` / `ORG` |
| `payer_scope_id` | BIGINT UNSIGNED | NO | — | 支払者ID（論理参照） |
| `payer_stripe_customer_id` | VARCHAR(32) | YES | NULL | 支払者の Stripe Customer（`cus_xxx`・既存 `stripe_customers` 再利用可） |
| `payee_kind` | VARCHAR(8) | NO | — | **受領者種別 `USER` / `TEAM` / `ORG`（札ごとの受領主体）** |
| `payee_connect_account_id` | BINARY(16) | NO | — | **`connect_accounts.id`（payment ドメイン内・論理参照）**。受領先 |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | テナント絞り込み（札主の組織・シャードキー候補） |
| `stripe_payment_intent_id` | VARCHAR(32) | YES | NULL | `pi_xxx`（与信作成後にセット）。一意 |
| `face_amount` | INT UNSIGNED | NO | — | **額面**（受取側が設定した謝礼額/会費額・円整数・最小単位）。手数料計算の基準。`amount = face_amount + round(face_amount × 0.025)` |
| `amount` | INT UNSIGNED | NO | — | **課金額（支払者請求額＝額面+2.5%上乗せ）**（**JPY＝円整数**・最小単位）。額面 10,000 円なら `amount=10,250`（README §3.4.1）。Stripe へ渡す金額 |
| `currency` | CHAR(3) | NO | `'JPY'` | ISO 4217 |
| `application_fee_amount` | INT UNSIGNED | NO | 0 | **総プラットフォーム手数料**（円整数・README §3.4）。`fee_policies` で解決した `percent_rate × face_amount + flat_fee_minor` の確定額（DEFAULT＝率5%＋固定0なら額面10,000で500）。受領者送金額 = `amount - application_fee_amount`。`chk_et_fee: application_fee_amount ≤ amount` を充足（安全ガード・README §3.4.4） |
| `fee_policy_key` | VARCHAR(40) | NO | `'DEFAULT'` | **適用した手数料パターンの自然キー**（`fee_policies.policy_key` 論理参照・遡及防止の焼き付け）。charge/与信時に解決した値を記録し、以後 `fee_policies` を改定しても本取引の料率は固定（README §3.4.2）。既定 `DEFAULT`（率5%＋固定0・後方互換） |
| `status` | VARCHAR(20) | NO | `'AUTHORIZED'` | 状態（下表）。**ENUM ではなく VARCHAR**（拡張容易性・既存 payment と整合）。即時モード（MEMBERSHIP）は INSERT 時 `CAPTURED` |
| `authorized_at` | DATETIME | YES | NULL | 与信成立日時（UTC） |
| `captured_at` | DATETIME | YES | NULL | capture（払出確定）日時（UTC） |
| `cancelled_at` | DATETIME | YES | NULL | 与信取消日時（UTC） |
| `hold_expires_at` | DATETIME | YES | NULL | authorization hold 失効予定（最大7日・自動 capture バッチの基準）。**即時モード（MEMBERSHIP）は NULL**（与信フェーズを経ないため） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**status 値（状態遷移）**

> **モード別の初期 status**: エスクローモード（RECRUITMENT・`capture_mode=MANUAL`）は **`PENDING_CONFIRMATION`**（PI 作成済・札主未 confirm。onboarding 未完なら `HELD`）から開始。即時モード（MEMBERSHIP・`capture_mode=AUTOMATIC`）は INSERT 時点で **`CAPTURED`**（与信フェーズなし・即 transfer）。
>
> **第一陣 status 意味論の根治（2026-06-10・V80.001）**: manual-capture PaymentIntent は札主が Stripe.js で confirm するまで真の与信（`amount_capturable`）が立たない。PI 作成直後に `AUTHORIZED` にすると capture が未確認 PI で失敗するため、PI 作成済・札主未 confirm の中間状態 `PENDING_CONFIRMATION` を新設した。`AUTHORIZED`（capture 可能）への昇格は `payment_intent.amount_capturable_updated` webhook 受信時のみ行う。
>
> **第三陣-b「7日超 fallback（完了時即時払い）」（2026-06-10・V81.001・マスター裁可）**: カード与信は Stripe 仕様で約7日で失効する。成立〜役務完了（札の `start_at`）が7日を超える謝礼は、成立時に与信を立てると役務完了前に失効するため、成立時には与信せず `DEFERRED`（PI 未作成・`capture_mode=AUTOMATIC`）で起票する。最終認証（役務完了）時に即時払い（会費 F08.9 と同型の destination charge・即 capture）へフォールバックし、`DEFERRED → AUTHORIZED`（PI 作成済・`hold_expires_at=NULL`・succeeded webhook 待ち）→ 札主の confirm で `payment_intent.succeeded` → `CAPTURED`。札主への `clientSecret` 返却は第二陣の決済確認 EP を再利用する。`AUTHORIZED`＋`hold_expires_at=NULL` に置くことで第三陣の自動取消バッチ（PENDING_CONFIRMATION の `created_at` 猶予・HELD/AUTHORIZED の `hold_expires_at` 失効）に掛からず誤取消されない。成立〜役務日が7日以内、または役務日不明（`start_at` 未設定・助っ人等）は安全側で従来どおり与信（`MANUAL`）を立て、与信の失効ハンドリングは第三陣バッチに委ねる。

| 値 | 意味 |
|---|---|
| `PENDING_CONFIRMATION` | 与信前段: manual-capture PI 作成済だが札主未 confirm（真の与信未確定）。エスクローモードのみ |
| `DEFERRED` | 完了時即時払い予定（成立〜役務日が7日超で成立時に与信せず・PI 未作成。最終認証時に即時払いへフォールバック）。エスクローモードのみ |
| `AUTHORIZED` | 与信済（資金未移動・エスクロー保持中・真の与信確定＝capture 可能）。エスクローモードのみ。第三陣-b では完了時即時払いの AUTOMATIC PI 作成済・succeeded 待ちもこの状態 |
| `HELD` | 払出保留（受領者 onboarding 未完了で capture 待ち。§02 §5） |
| `CAPTURED` | capture 済（払出確定・受領者へ transfer 完了） |
| `PARTIALLY_REFUNDED` | 部分返金済 |
| `REFUNDED` | 全額返金済 |
| `CANCELLED` | 与信取消（capture 前の札下げ/期限切れ/hold 失効） |
| `DISPUTED` | 係争中（最終認証未了で hold 失効回避のため先 capture が必要になりうる。F13.1 §8.9 戦略） |

```
PENDING_CONFIRMATION ──(札主confirm: amount_capturable_updated)──▶ AUTHORIZED
PENDING_CONFIRMATION ──(confirm前 cancel/payment_failed)──▶ CANCELLED
DEFERRED ──(最終認証=完了時即時払い: AUTOMATIC PI作成)──▶ AUTHORIZED ──(札主confirm: succeeded)──▶ CAPTURED   ※第三陣-b 7日超fallback
DEFERRED ──(最終認証前の札下げ/取消)──▶ CANCELLED
                ┌─ HELD ──(onboarding完了→confirm)──┐
AUTHORIZED ─────┤                                    ├──▶ CAPTURED ──▶ PARTIALLY_REFUNDED / REFUNDED
                └─(payouts_enabled・札主confirm)──────┘
AUTHORIZED ──(札下げ/期限切れ/hold失効)──▶ CANCELLED
AUTHORIZED ──(最終認証未了でhold接近)──▶ DISPUTED ──(先capture)──▶ CAPTURED ──(仲裁結果)──▶ REFUNDED/確定
```

**制約・インデックス**
```sql
CONSTRAINT chk_et_source_kind CHECK (source_kind IN ('RECRUITMENT','MEMBERSHIP','JOBMATCHING','FLEAMARKET'))
CONSTRAINT chk_et_capture_mode CHECK (capture_mode IN ('MANUAL','AUTOMATIC'))
CONSTRAINT chk_et_payee_kind  CHECK (payee_kind IN ('USER','TEAM','ORG'))
CONSTRAINT chk_et_payer_kind  CHECK (payer_scope_kind IN ('USER','TEAM','ORG'))
CONSTRAINT chk_et_status CHECK (status IN
  ('PENDING_CONFIRMATION','DEFERRED','AUTHORIZED','HELD','CAPTURED','PARTIALLY_REFUNDED','REFUNDED','CANCELLED','DISPUTED'))
  -- V80.001 で PENDING_CONFIRMATION、V81.001 で DEFERRED を許容集合へ追加（既存行非破壊・DROP→ADD で原子的張替）。
CONSTRAINT chk_et_fee CHECK (application_fee_amount <= amount)
UNIQUE KEY uk_et_pi (stripe_payment_intent_id)
INDEX idx_et_source (source_kind, source_id)
INDEX idx_et_payee (payee_connect_account_id)
INDEX idx_et_org (organization_id)
INDEX idx_et_status_hold (status, hold_expires_at)   -- 自動 capture バッチ用
```
> `payee_connect_account_id` は `connect_accounts.id`（同一 payment ドメイン）への参照。**ドメイン内なので FK を張ってもよい**が、エスクロー取引は監査証跡として永続保持し Connect アカウント論理削除でも消えてはならないため、**FKは張らず論理参照とし `ON DELETE` 連鎖を発生させない**（台帳の不変性優先）。

---

### 3.3 `ledger_entries`（複式記帳台帳・追記専用）

エスクロー取引の資金移動を**借方/貸方の複式**で記録し、整合性監査（Stripe balance との突合）に用いる。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `id` | BINARY(16) | NO | (UUIDv7) | PK |
| `escrow_transaction_id` | BINARY(16) | NO | — | `escrow_transactions.id`（payment ドメイン内 FK・CASCADE 可） |
| `entry_type` | VARCHAR(24) | NO | — | `AUTHORIZE` / `CAPTURE` / `TRANSFER_OUT` / `FEE` / `REFUND` / `CANCEL`（記帳種別） |
| `account` | VARCHAR(16) | NO | — | 勘定 `ESCROW` / `PAYEE` / `PLATFORM_FEE` / `PAYER`（複式の相手勘定） |
| `direction` | CHAR(1) | NO | — | `D`（借方 Debit）/ `C`（貸方 Credit） |
| `amount` | INT UNSIGNED | NO | — | 金額（円整数・最小単位） |
| `currency` | CHAR(3) | NO | `'JPY'` | |
| `running_balance` | BIGINT | NO | — | 当該取引の累積残高（署名付き・整合検算用） |
| `stripe_object_id` | VARCHAR(48) | YES | NULL | 対応する Stripe オブジェクト（`tr_xxx`/`re_xxx`/`txn_xxx` 等・突合キー） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | 追記時刻（不変） |

**制約・インデックス**
```sql
CONSTRAINT fk_le_escrow FOREIGN KEY (escrow_transaction_id)
  REFERENCES escrow_transactions(id) ON DELETE CASCADE   -- 同一ドメイン内のみ CASCADE 許可（CLAUDE.md 原則2）
CONSTRAINT chk_le_direction CHECK (direction IN ('D','C'))
CONSTRAINT chk_le_entry_type CHECK (entry_type IN
  ('AUTHORIZE','CAPTURE','TRANSFER_OUT','FEE','REFUND','CANCEL'))
INDEX idx_le_escrow (escrow_transaction_id, created_at)
INDEX idx_le_stripe_obj (stripe_object_id)
```
> 追記専用（UPDATE/DELETE しない）。1 取引の借方合計＝貸方合計が常に成立することを整合バッチで検算する（Stripe balance_transaction との突合・02 §6 リコンシリエーション）。

---

### 3.4 `refunds`（返金記録）

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `id` | BINARY(16) | NO | (UUIDv7) | PK |
| `escrow_transaction_id` | BINARY(16) | NO | — | `escrow_transactions.id`（payment 内 FK・CASCADE 可） |
| `stripe_refund_id` | VARCHAR(32) | NO | — | `re_xxx`（一意） |
| `amount` | INT UNSIGNED | NO | — | **支払者へ戻す返金額（円整数・最小単位・transferAmount ベース）**。支払者負担モデル（02 §6.1・マスター確定 2026-06-03）では返金上限が受取側の受取正味＝`transferAmount`（`escrow.amount − application_fee_amount`）であり、`amount < face_amount`（さらに `< escrow.amount`）。全額返金でも 9,750（額面 10,000 例）であって 10,000 や 10,250 ではない。部分返金で `< transferAmount` |
| `currency` | CHAR(3) | NO | `'JPY'` | |
| `reason` | VARCHAR(32) | NO | — | `requested_by_customer` / `duplicate` / `dispute_resolution` / `cancellation` 等 |
| `reason_detail` | VARCHAR(500) | YES | NULL | 運営・札主の補足（PII 非含意） |
| `refunded_by_user_id` | BIGINT UNSIGNED | YES | NULL | 返金操作者（論理参照・監査） |
| `status` | VARCHAR(12) | NO | `'PENDING'` | `PENDING` / `SUCCEEDED` / `FAILED`（Stripe `charge.refunded` Webhook で確定） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |

**制約・インデックス**
```sql
CONSTRAINT fk_rf_escrow FOREIGN KEY (escrow_transaction_id)
  REFERENCES escrow_transactions(id) ON DELETE CASCADE   -- 同一ドメイン内
CONSTRAINT chk_rf_status CHECK (status IN ('PENDING','SUCCEEDED','FAILED'))
UNIQUE KEY uk_rf_stripe (stripe_refund_id)
INDEX idx_rf_escrow (escrow_transaction_id)
```

---

### 3.5 `stripe_webhook_events`（Webhook 冪等性キー）

Connect/Platform 両 Webhook の**再送（at-least-once）を冪等化**する共通テーブル。同一 `event_id` の二重処理を一意制約で物理拒否する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `id` | BINARY(16) | NO | (UUIDv7) | PK |
| `event_id` | VARCHAR(64) | NO | — | Stripe イベントID（`evt_xxx`）。**冪等性キー（UNIQUE）** |
| `type` | VARCHAR(64) | NO | — | イベント種別（`account.updated` / `payment_intent.succeeded` 等） |
| `livemode` | BOOLEAN | NO | FALSE | 本番/テスト区分 |
| `received_at` | DATETIME | NO | CURRENT_TIMESTAMP | 受信時刻 |
| `processed_at` | DATETIME | YES | NULL | 処理完了時刻（NULL=受信のみ・処理未完／再試行対象） |
| `process_status` | VARCHAR(12) | NO | `'RECEIVED'` | `RECEIVED` / `PROCESSED` / `IGNORED` / `FAILED` |

**制約・インデックス**
```sql
CONSTRAINT chk_swe_status CHECK (process_status IN ('RECEIVED','PROCESSED','IGNORED','FAILED'))
UNIQUE KEY uk_swe_event (event_id)   -- 冪等性の要。INSERT 競合＝既処理として安全にスキップ
INDEX idx_swe_type (type)
INDEX idx_swe_received (received_at)  -- TTL 物理削除バッチ用（保持期間後に古行を削除）
```
> 処理フロー: Webhook 受信→`INSERT ... event_id`（重複なら ON DUPLICATE で no-op＝既処理）→ハンドラ実行→`process_status=PROCESSED`。**INSERT を冪等ゲートに使う**ことで、同一 event の同時並行処理も一意制約で直列化される（02 §4）。

---

### 3.6 `fee_policies`（手数料パターンのマスタ表・率%＋固定額¥）

手数料を「率(`percent_rate`)＋固定額(`flat_fee_minor`)」のパターンとして持つ**マスタ表**（README §3.4）。**CLAUDE.md「マスタテーブル例外」**に該当する（全テナント共通の参照データ・書込はシスアド運用のみ・税率表と同型）ため、**主キーは自然キー `policy_key`**（UUIDv7 不要）とする。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `policy_key` | VARCHAR(40) | NO | — | **PK・自然キー**（例 `DEFAULT` / `RECRUITMENT_HELPER` / `MEMBERSHIP_STANDARD`）。`escrow_transactions.fee_policy_key` の焼き付け参照先 |
| `display_name` | VARCHAR(80) | NO | — | 管理画面表示名（i18n キーでなく管理者向け表示名・直接表示は管理 UI のみ） |
| `percent_rate` | DECIMAL(6,4) | NO | — | 総手数料の率（例 `0.0500`＝5%・`0.0300`＝3%）。`0 ≤ percent_rate < 1` |
| `flat_fee_minor` | INT UNSIGNED | NO | 0 | 総手数料の固定額（円・最小単位）。`0` で率のみ |
| `enabled` | BOOLEAN | NO | TRUE | 無効化フラグ（無効パターンは新規割当・解決から除外。既存焼き付け取引には影響しない） |
| `description` | VARCHAR(500) | YES | NULL | 補足説明（運用メモ） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | 料率改定時刻（改定は新規徴収のみに反映・遡及しない＝§3.2 焼き付け） |

**制約・インデックス・シード**
```sql
PRIMARY KEY (policy_key)
CONSTRAINT chk_fp_percent CHECK (percent_rate >= 0 AND percent_rate < 1)
-- 固定 ID/自然キーのマスタ表ゆえ organization_id を持たない（全テナント共通）

-- 初期シード（DEFAULT＝率5%＋固定0＝既存挙動と完全一致・後方互換）
INSERT INTO fee_policies (policy_key, display_name, percent_rate, flat_fee_minor, enabled, description)
VALUES ('DEFAULT', '標準（率5%・折半）', 0.0500, 0, TRUE, '既定の手数料パターン。総手数料=額面×5%、支払者・受取側で折半');
```
> - **総手数料 = `round(percent_rate × face_amount) + flat_fee_minor`**。負担は折半 50/50 固定（README §3.4.1・支払者は `face + round(総手数料/2)`、受取側は `face − round(総手数料/2)`、`application_fee_amount = 総手数料`）。
> - **DEFAULT は削除不可**（解決のフォールバック終端）。シスアドは新パターンの追加・既存パターンの率/固定額/enabled 更新を行う（§3.7 で source_kind＋sub_key に割当）。
> - **料率改定は新規徴収のみ反映**（既存取引・既存サブスクは `escrow_transactions.fee_policy_key`/`membership_subscriptions` に焼き付けた値で固定・§3.2・README §3.4.2）。

### 3.7 `fee_policy_assignments`（手数料パターンの割当）

source_kind（＋任意 sub_key）に対しどの `fee_policies` を適用するかの割当（README §3.4.2）。テナント横断の運用データで行が増えるため **UUIDv7**。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `id` | BINARY(16) | NO | (UUIDv7) | PK |
| `source_kind` | VARCHAR(12) | NO | — | `RECRUITMENT`/`MEMBERSHIP`/`TOURNAMENT`/`JOBMATCHING`/`FLEAMARKET`（解決キー） |
| `sub_key` | VARCHAR(40) | YES | NULL | 任意の細分キー（**助っ人＝`recruitment_category` 値**等）。NULL＝source_kind の既定割当 |
| `policy_key` | VARCHAR(40) | NO | — | 適用する `fee_policies.policy_key`（論理参照） |
| `organization_id` | BIGINT UNSIGNED | YES | NULL | 将来テナント別上書きの拡張点（R1 は常に NULL・解決順序にも挟まない・02 §3.5.3） |
| `enabled` | BOOLEAN | NO | TRUE | 割当の有効/無効 |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |
| `updated_at` | DATETIME | NO | CURRENT_TIMESTAMP ON UPDATE | |
| `deleted_at` | DATETIME | YES | NULL | 論理削除 |

**制約・インデックス**（実装 `V74.008` に一致）
```sql
CONSTRAINT chk_fpa_source_kind CHECK (source_kind IN ('RECRUITMENT','MEMBERSHIP','TOURNAMENT','JOBMATCHING','FLEAMARKET'))
-- (source_kind, sub_key, organization_id) ごとに 1 割当（NULL sub_key は source_kind 既定・R1 は organization_id 常に NULL）。
-- 論理削除を加味した重複防止はアプリ層で行う（MySQL は filtered unique 非対応）。
UNIQUE KEY uk_fpa_target (source_kind, sub_key, organization_id)
INDEX idx_fpa_policy (policy_key)
```
> **解決順序（README §3.4.2）**: ① `(source_kind, sub_key)` 完全一致 → ② `(source_kind, sub_key IS NULL)` source_kind 既定 → ③ `DEFAULT`。いずれも `enabled=TRUE`・`deleted_at IS NULL` かつ参照先 `fee_policies.enabled=TRUE` を満たすものに限る（解決ロジックは `FeePolicyResolver`・02 §3.5.1）。複雑なテナント別上書きは作らない（将来拡張点・02 §3.5.3）。

---

## 4. 既存テーブルへの最小拡張

### 4.1 `recruitment_listings`（札ごとの受領主体）

既存列 `payment_enabled BOOLEAN` / `price INTEGER` を活用しつつ、**札ごとの受領主体**を表現する列を追加する（市の決済の中核）。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `payee_kind` | VARCHAR(8) | YES | NULL | **受領主体 `USER` / `TEAM` / `ORG`（札ごと選択）**。`payment_enabled=TRUE` 時に必須 |
| `payee_user_id` | BIGINT UNSIGNED | YES | NULL | `payee_kind=USER` の受領者（論理参照）。審判/助っ人個人など |

```sql
ALTER TABLE recruitment_listings
  ADD COLUMN payee_kind VARCHAR(8) NULL,
  ADD COLUMN payee_user_id BIGINT UNSIGNED NULL;

-- payment_enabled=TRUE のとき payee_kind と price は必須（既存 PRICE_REQUIRED 検証を拡張）
ALTER TABLE recruitment_listings
  ADD CONSTRAINT chk_rl_payee
    CHECK (payment_enabled = FALSE
        OR (payee_kind IN ('USER','TEAM','ORG') AND price IS NOT NULL));
-- payee_user_id は payee_kind=USER のとき必須、それ以外（TEAM/ORG/NULL）では NULL でなければならない
-- （TEAM/ORG は札主 scope_type/scope_id を受領主体に流用するため個人ID列は持たない）
ALTER TABLE recruitment_listings
  ADD CONSTRAINT chk_rl_payee_user
    CHECK ((payee_kind = 'USER' AND payee_user_id IS NOT NULL)
        OR (payee_kind <> 'USER' AND payee_user_id IS NULL)
        OR (payee_kind IS NULL  AND payee_user_id IS NULL));
INDEX idx_rl_payee_user (payee_user_id);
```
> - `payee_kind=TEAM/ORG` の場合、受領主体ID は**札主の `scope_type`/`scope_id`（`recruitment_listings` 既存列・TEAM なら teams.id、ORG なら organizations.id）をそのまま使う**ため専用列は不要（札主＝受領者）。
> - `payee_kind=USER` の場合のみ `payee_user_id` で個人受領者を保持（札主はチーム/組織だが、謝礼の受け手は個人）。
> - 既存の `payment_enabled && price==null → PRICE_REQUIRED` 検証を、`payee_kind` 必須も含むよう Service 側で拡張（02 §3・実コード `PAYMENT_C010`）。
> - `escrow_transactions` 作成時、`payee_kind`/受領主体ID から `connect_accounts`（READY かつ payouts_enabled）を解決する。未 onboarding なら `HELD`（02 §5）。

> ⚠️ **実装注意 — `payee_kind` 値と `RecruitmentScopeType` のマッピング**
> 本設計の `payee_kind` は `USER` / `TEAM` / `ORG` の3値を想定しているが、実在 enum `com.mannschaft.app.recruitment.RecruitmentScopeType` は **`TEAM` / `ORGANIZATION`** の2値のみ（`USER` は持たない）。実装時は以下のマッピングで変換すること:
> - `payee_kind = 'TEAM'` → `RecruitmentScopeType.TEAM`（1:1 対応）
> - `payee_kind = 'ORG'` → `RecruitmentScopeType.ORGANIZATION`（文字列不一致に注意）
> - `payee_kind = 'USER'` → `RecruitmentScopeType` には対応値なし。個人受領は `payee_user_id` カラムで表現し、scope 解決時に `RecruitmentScopeType` を介さず直接 `users.id` を参照する（独立パス）。
> 変換ロジックは `ConnectAccountService` 等に一箇所集約し、文字列直比較が散在しないようにすること。

> **既存値は不変**: 既存行は `payee_kind=NULL`（決済無効札）で後方互換。`payment_enabled=FALSE` の既存札は CHECK を満たす。

### 4.2 `stripe_customers`（既存・変更なし）
支払者の Stripe Customer は既存 `payment/entity/StripeCustomerEntity`（`stripe_customers` 想定）を**再利用**。新規列は追加しない。

---

## 5. Flyway マイグレーション

> **版番号の鉄則**（memory `feedback_flyway_version_sort_after_global_max`）: 全体最大の**次の major** を採る。`V9.<timestamp>` 形式は V10〜V72 より前にソートされ from-scratch を壊す**罠＝採用禁止**。

- **実装済（main）**: P2-a 基盤（`V72.004`〜`V72.009`）＋統一基盤化 ALTER（`V73.003`＝`escrow_transactions` への `source_kind=MEMBERSHIP`/`capture_mode`/`face_amount` 追加）＋F08.9 P1（`V74.001` 等）はすべて main マージ済（2026-06-04 実測 `db/migration`）。以下は **手数料ランク化（P2-f）に伴う追加 DDL** を示す。
- 新規 DDL は **着手時に `origin/main` の最大版番号を再確認し、衝突しない次番号へ確定**する（並行PRでズレるため・from-scratch 番人テストが検知）。下表は **仮番号**（`V75.xxx` 系を想定するが着手時に再確認）。

| 版番号（着手時再確認） | 内容 |
|---|---|
| `V74.007`（R1・実装済） | `fee_policies` マスタ表 CREATE（自然キー `policy_key`）＋ `DEFAULT`（率5%＋固定0）シード |
| `V74.008`（R1・実装済） | `fee_policy_assignments` CREATE（UUIDv7・source_kind＋sub_key → policy_key・`organization_id` 拡張点列）。UNIQUE は `(source_kind, sub_key, organization_id)`（マスター裁可・本表 R1 確定） |
| `V74.009`（R1・実装済） | `escrow_transactions` に `fee_policy_key VARCHAR(40) NOT NULL DEFAULT 'DEFAULT'` 追加（既存行は DEFAULT で後方互換・遡及防止の焼き付け列） |
| `V75.004`（仮・将来） | `escrow_transactions.source_kind` CHECK に `TOURNAMENT` 追加（F08.7.1 Connect 移行時・§2 / README §3.0.1） |

> P2-a（CREATE 群）＋ V73.003（統一基盤化 ALTER）は完了・main 済。本表は手数料ランク化（`fee_policies`/`fee_policy_assignments`/`fee_policy_key`）に伴う **追加 DDL** を示す。`fee_policies` はマスタ例外ゆえ自然キー、割当・焼き付けは非破壊（既存行は `fee_policy_key='DEFAULT'`＝既存挙動不変）。テーブル作成順の FK 依存（`escrow_transactions` → `ledger_entries`/`refunds`）は P2-a で確定済。`fee_policies`/`fee_policy_assignments` は payment ドメイン内だが他テーブルと FK を張らない（焼き付けは論理参照＝改定で過去取引が壊れない不変性優先）。

---

## 6. ER 図

```mermaid
erDiagram
    connect_accounts {
        BINARY16 id PK
        VARCHAR8 scope_kind "USER/TEAM/ORG"
        BIGINT scope_id "論理参照"
        VARCHAR32 stripe_account_id UK "acct_xxx"
        VARCHAR16 onboarding_status
        BOOLEAN payouts_enabled
    }
    escrow_transactions {
        BINARY16 id PK
        VARCHAR12 source_kind "RECRUITMENT/MEMBERSHIP/JOBMATCHING/FLEAMARKET"
        VARCHAR10 capture_mode "MANUAL(謝礼)/AUTOMATIC(会費)"
        BIGINT source_id "論理参照→recruitment_listings 等"
        VARCHAR8 payee_kind "USER/TEAM/ORG"
        BINARY16 payee_connect_account_id "論理参照→connect_accounts"
        VARCHAR32 stripe_payment_intent_id UK "pi_xxx"
        INT face_amount "額面 JPY整数"
        INT amount "課金額=額面+折半上乗せ JPY整数"
        INT application_fee_amount "総手数料(fee_policies解決)"
        VARCHAR40 fee_policy_key "適用パターン焼付(遡及防止)"
        VARCHAR20 status
    }
    fee_policies {
        VARCHAR40 policy_key PK "自然キー(マスタ例外)"
        VARCHAR80 display_name
        DECIMAL percent_rate "率"
        INT flat_fee_minor "固定額(円)"
        BOOLEAN enabled
    }
    fee_policy_assignments {
        BINARY16 id PK
        VARCHAR12 source_kind
        VARCHAR40 sub_key "助っ人=recruitment_category 等"
        VARCHAR40 policy_key "→fee_policies"
        BIGINT organization_id "将来拡張点(R1はNULL)"
    }
    ledger_entries {
        BINARY16 id PK
        BINARY16 escrow_transaction_id FK
        VARCHAR24 entry_type
        CHAR1 direction "D/C"
        INT amount
        BIGINT running_balance
    }
    refunds {
        BINARY16 id PK
        BINARY16 escrow_transaction_id FK
        VARCHAR32 stripe_refund_id UK
        INT amount
    }
    stripe_webhook_events {
        BINARY16 id PK
        VARCHAR64 event_id UK "evt_xxx 冪等性キー"
        VARCHAR64 type
        VARCHAR12 process_status
    }
    recruitment_listings {
        BIGINT id PK "既存"
        BOOLEAN payment_enabled "既存"
        INTEGER price "既存"
        VARCHAR8 payee_kind "追加"
        BIGINT payee_user_id "追加"
    }

    escrow_transactions ||--o{ ledger_entries : "FK CASCADE (同一ドメイン)"
    escrow_transactions ||--o{ refunds : "FK CASCADE (同一ドメイン)"
    escrow_transactions }o..|| connect_accounts : "論理参照 (payee)"
    escrow_transactions }o..|| recruitment_listings : "論理参照 (source・クロスドメイン)"
    escrow_transactions }o..|| fee_policies : "fee_policy_key 焼付 (論理参照・遡及防止)"
    fee_policy_assignments }o..|| fee_policies : "policy_key 解決 (論理参照)"
    recruitment_listings }o..|| connect_accounts : "payee_kind で解決 (実線FKなし)"
```

> 実線 FK は payment ドメイン内（`escrow_transactions`→`ledger_entries`/`refunds`）のみ。点線は論理参照（クロスドメイン・Service 検証）。`fee_policies`/`fee_policy_assignments` は payment ドメイン内だが、料率改定で過去取引が壊れない不変性のため **FK は張らず論理参照**（`fee_policy_key` の焼き付けは値のコピー・README §3.4.2）。

---

## 7. DB 原則への適合チェック（CLAUDE.md）

| 原則 | 適合 |
|---|---|
| 1. クロスドメインFK禁止 | ✅ recruitment/team/user への参照はすべて論理参照（FKなし） |
| 2. CASCADE は同一ドメイン内のみ | ✅ `escrow_transactions`→`ledger_entries`/`refunds` の payment 内 CASCADE のみ |
| 3. コアエンティティ論理削除 | ✅ `connect_accounts.deleted_at`。escrow/ledger/refund は監査証跡で物理保持 |
| 4. 退会時匿名化 | ✅ Connect 切離しは払出/返金完了後・強匿名化30日猶予側（03 §5） |
| 5. @Transactional ドメイン内 | ✅ `payment.escrow` 内に閉じる。recruitment 連携は ApplicationEvent（README §7） |
| 6. 新規テーブル UUIDv7 | ✅ `fee_policy_assignments` 等は `UuidV7Entity`（BINARY(16)）。**例外**: `fee_policies` は CLAUDE.md「マスタテーブル例外」（全テナント共通参照・書込はシスアド運用のみ・税率表と同型）ゆえ**自然キー `policy_key`**（§3.6 に明記） |
| 7. テナント Repository | ✅ `connect_accounts`/`escrow_transactions` は `organization_id` 保持・`AbstractTenantAwareRepository` 実装。`fee_policies`/`fee_policy_assignments` は全テナント共通（マスタ/運用）ゆえ非テナント Repository |
