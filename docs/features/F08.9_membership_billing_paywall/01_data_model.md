# F08.9 — 01 データモデル

> [README](README.md) の中核モデル（払い手≠受益者／後見切替／継続課金／協会請求／税からくり）を DB に落とす。
> money 移動は F22.1 `payment.escrow`/`payment.connect` を再利用し、本書は **membership ドメインの意味づけ**と**協会請求**を定義する。

---

## 0. 設計原則（CLAUDE.md 準拠）

- **新規テーブルは `UuidV7Entity` 継承（`BINARY(16)` 主キー・UUIDv7）**。既存テーブル（`member_payments` 等の BIGINT）は主キーを変えず列追加のみ。
- **クロスドメイン FK は作らない**。`user_id`/`team_id`/`organization_id` 等は**論理参照（インデックスのみ）**。整合性はアプリ層で保証。
- **CASCADE は同一ドメイン内のみ**。membership ドメイン内（例：subscription→請求明細）に限り `ON DELETE CASCADE` 可。
- **テナントは `organization_id`** を持ち、Repository は `AbstractTenantAwareRepository` を継承。
- **コア/金銭記録は論理削除**（`deleted_at`）。退会時は匿名化（GDPR・§6）。
- `@Query` 内コメント厳禁。予約語カラムはバッククォート。

---

## 1. 既存テーブルの最小拡張

> ⚠️ **本章の「追加列」は origin/main 時点では未実装**。すべて V74.xxx Flyway（§5）で新規追加する設計上の目標スキーマである。既存（origin/main）の `member_payments` は V8.012 のカラムのみを持つ。

### 1.1 `member_payments`（払い手分離・money rail 連結）

既存（V8.012・BIGINT）へ**新規列を追加**（V74.001）。**主キー・既存列は不変**。`grace_period_days` は本テーブルでなく既存 `payment_items.grace_period_days`（V8.010・実在）を引き続き用いる。

| 追加列 | 型 | NULL | 説明 |
|---|---|---|---|
| `payer_user_id` | BIGINT UNSIGNED | YES→将来NOT NULL | **払い手**（実際に決済した人）。論理参照・FKなし・INDEX。NULL は手動記録の移行期のみ許容、新規は必須 |
| `payment_proxy_grant_id` | BINARY(16) | YES | 代理払いの権原（保護者リンク経由は NULL・第三者払いは grant を指す） |
| `payer_relationship` | VARCHAR(16) | YES | 払い手と受益者の関係スナップショット：`SELF`/`GUARDIAN`/`GUARDIAN_PROXY`（後見切替セッション中の代理払い）/`PROXY_GRANT`/`ADMIN_MANUAL`（監査・表示用） |
| `escrow_transaction_id` | BINARY(16) | YES | F22.1 money rail への連結（Connect 決済時）。手動記録は NULL |
| `membership_subscription_id` | BINARY(16) | YES | 継続課金由来の支払いを親サブスクへ連結 |

- 既存の `user_id` は**受益者（beneficiary）**として意味を固定（ペイウォール・所属判定キー）。
- `existsValidPaidPayment(beneficiaryUserId, paymentItemId)` は不変（`user_id` で引く）。**払い手では引かない**。
- 追加インデックス：`idx_mp_payer (payer_user_id, status)`、`idx_mp_escrow (escrow_transaction_id)`、`idx_mp_subscription (membership_subscription_id)`。
- **クロスドメイン FK は追加しない**（既存 `user_id` の FK は legacy。`payer_user_id` は論理参照のみ）。

### 1.2 `payment_items`（継続/期別・税からくり）

| 追加列 | 型 | NULL | 説明 |
|---|---|---|---|
| `type`（既存 ENUM 拡張） | ENUM | NO | 既存 `ANNUAL_FEE`/`MONTHLY_FEE`/`ITEM`/`DONATION` に **`TERM`** を追加（期別＝有効期限つき単発） |
| `is_recurring` | BOOLEAN | NO | DEFAULT FALSE。継続課金（Stripe Subscription 管理）か |
| `billing_interval` | VARCHAR(8) | YES | `MONTHLY`/`YEARLY`（`is_recurring=true` 時）。`MONTHLY_FEE`/`ANNUAL_FEE` と整合 |
| `term_starts_on` | DATE | YES | `type=TERM` の有効期間開始 |
| `term_ends_on` | DATE | YES | `type=TERM` の有効期間終了 |
| `tax_category` | VARCHAR(16) | YES | **税からくり**：`STANDARD_10`/`REDUCED_8`/`EXEMPT`/`NON_TAXABLE`。NULL=税なし扱い（現挙動） |
| `tax_rate` | DECIMAL(5,4) | YES | 税率（将来計算用）。NULL=未設定 |
| `price_includes_tax` | BOOLEAN | YES | 税込/税抜（総額表示）。NULL=未設定 |

- `tax_*` は**列を置くが今は埋めず・強制せず**（`NoOpTaxPolicy` が既定で税額0を返す）。
- **後方互換（既存クエリ非破壊）**：`tax_*` が NULL の間、`face_amount = amount`（税考慮なし）で**現挙動と完全一致**。`PaymentFeeCalculator` の額面導出は `price_includes_tax==true` のときのみ税抜換算を行い、NULL/未設定では既存どおり `amount` をそのまま額面とする。既存 `PaymentSummaryService` 等の集計は税列を参照しないため影響なし。`tax_*` を実際に埋めるのは将来の国別 `TaxPolicy` 導入時のみ。
- `is_recurring=true` の項目は本機能 P5 で Stripe Subscription を作成する対象。

### 1.3 `connect_accounts`（受領者の税属性・からくり）

F22.1 のテーブル（V72.004）へ列追加。

| 追加列 | 型 | NULL | 説明 |
|---|---|---|---|
| `tax_registration_number` | VARCHAR(20) | YES | 適格請求書発行事業者の登録番号（T番号）。領収書拡張用・onboarding で収集 |
| `tax_status` | VARCHAR(16) | YES | `TAXABLE`（課税事業者）/`EXEMPT`（免税事業者）/NULL（未設定） |

- 暗号化不要（登録番号は公開情報）。NULL 既定で現挙動不変。

---

## 2. 新規テーブル（UuidV7・BINARY(16)）

### 2.1 `membership_subscriptions`（継続課金）

ガワだけの `team_subscriptions`(V9.055) とは別に、**会員（受益者）単位の継続課金**を表す新規テーブル。

```sql
CREATE TABLE membership_subscriptions (
    id BINARY(16) NOT NULL,                          -- UUIDv7
    organization_id BIGINT UNSIGNED NULL,            -- テナント（シャードキー候補）
    payment_item_id BIGINT UNSIGNED NOT NULL,        -- 対象会費項目（論理参照）
    beneficiary_user_id BIGINT UNSIGNED NOT NULL,    -- 受益者（会員）・論理参照
    payer_user_id BIGINT UNSIGNED NOT NULL,          -- 払い手・論理参照
    payment_proxy_grant_id BINARY(16) NULL,          -- 第三者代理払いの権原
    scope_kind VARCHAR(8) NOT NULL,                  -- TEAM/ORG（受領主体）
    scope_id BIGINT UNSIGNED NOT NULL,
    payee_connect_account_id BINARY(16) NOT NULL,    -- 受領 Connect 口座（論理参照）
    stripe_subscription_id VARCHAR(64) NULL,         -- sub_xxx（Stripe 連携・PoC 成立時）
    stripe_customer_id VARCHAR(64) NULL,             -- cus_xxx（払い手の platform Customer）
    billing_interval VARCHAR(8) NOT NULL,            -- MONTHLY/YEARLY
    billing_anchor_day TINYINT UNSIGNED NULL,        -- ユーザ指定決済日（1-28 等）
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',   -- PENDING/ACTIVE/PAST_DUE/CANCELLED/EXPIRED
    current_period_start DATE NULL,
    current_period_end DATE NULL,                    -- = 受益者の valid_until 同期
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    cancelled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ms_stripe_sub (stripe_subscription_id),
    KEY idx_ms_beneficiary (beneficiary_user_id, status),
    KEY idx_ms_payer (payer_user_id, status),
    KEY idx_ms_item (payment_item_id),
    KEY idx_ms_org (organization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- **クロスドメイン FK なし**（user/team/org/connect_account はすべて論理参照）。
- 退避策（自前バッチ）採用時も同テーブルで運用可：`stripe_subscription_id=NULL`・自前スケジューラが `current_period_end` を見て都度決済。
- `Repository` は `AbstractTenantAwareRepository<MembershipSubscriptionEntity, UUID>`。

### 2.2 `payment_requests`（協会→加盟チーム請求）

```sql
CREATE TABLE payment_requests (
    id BINARY(16) NOT NULL,                          -- UUIDv7
    organization_id BIGINT UNSIGNED NOT NULL,        -- テナント（請求元の協会）
    issuer_scope_kind VARCHAR(8) NOT NULL,           -- ORG（将来 TEAM 内請求も）
    issuer_scope_id BIGINT UNSIGNED NOT NULL,        -- 請求元 ID
    payer_scope_kind VARCHAR(8) NOT NULL,            -- TEAM（請求先）
    payer_scope_id BIGINT UNSIGNED NOT NULL,         -- 請求先チーム ID
    payee_connect_account_id BINARY(16) NOT NULL,    -- 着金先（協会の Connect 口座・論理参照）
    title VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NULL,
    face_amount INT UNSIGNED NOT NULL,               -- 額面（円整数）
    currency CHAR(3) NOT NULL DEFAULT 'JPY',
    tax_category VARCHAR(16) NULL,                   -- 税からくり（NULL=税なし）
    due_date DATE NOT NULL,                          -- 支払期限
    status VARCHAR(12) NOT NULL DEFAULT 'DRAFT',     -- DRAFT/SENT/VIEWED/PAID/OVERDUE/CANCELLED
    escrow_transaction_id BINARY(16) NULL,           -- 支払い時に money rail へ連結
    confirmable_notification_id BIGINT UNSIGNED NULL,-- 配信した確認必須通知（論理参照）
    superseded_by_id BINARY(16) NULL,                -- CANCELLED 後の再請求で新請求を指す（再発行の追跡）
    sent_at DATETIME NULL,
    viewed_at DATETIME NULL,
    paid_at DATETIME NULL,
    created_by BIGINT UNSIGNED NULL,                 -- 発行者（論理参照）
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    PRIMARY KEY (id),
    KEY idx_pr_payer (payer_scope_kind, payer_scope_id, status),
    KEY idx_pr_issuer (issuer_scope_kind, issuer_scope_id),
    KEY idx_pr_org (organization_id),
    KEY idx_pr_due (status, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- escrow 側は `payer_scope_kind=TEAM`/`payee_kind=ORG`。V72.005 の per-column CHECK（`payer_scope_kind IN (USER,TEAM,ORG)`・`payee_kind IN (USER,TEAM,ORG)`）が**この組み合わせを禁じていない**ため、ALLOW の意味で**スキーマ改修なしに表現可能**（§3 の組み合わせ表・複合 CHECK 追加案を参照）。
- `OVERDUE` 遷移は @Scheduled バッチ（ShedLock）が `status IN (SENT,VIEWED) AND due_date < CURDATE()` を更新。
- **1請求＝1チームの全額支払い**を原則とする（部分支払いは扱わない＝単一 destination charge で全額）。協会側の「回収率」は**加盟チーム数に対する PAID 件数**で集計（請求ごとに status を数える）。
- **再請求**：誤キャンセル後は**新しい `payment_requests` 行を発行**し、旧 CANCELLED 行の `superseded_by_id` に新行を指す。チーム側 UI は superseded 済みの旧請求を「無効（新請求あり）」と表示し二重支払いを防ぐ。

### 2.3 `payment_proxy_grants`（第三者代理払い許可）

保護者でない払い手（祖父母・スポンサー等）が受益者の会費を払うための明示許諾。

```sql
CREATE TABLE payment_proxy_grants (
    id BINARY(16) NOT NULL,                          -- UUIDv7
    organization_id BIGINT UNSIGNED NULL,            -- テナント
    beneficiary_user_id BIGINT UNSIGNED NOT NULL,    -- 受益者（許可を出す側）・論理参照
    payer_user_id BIGINT UNSIGNED NOT NULL,          -- 払い手（許可される側）・論理参照
    scope VARCHAR(16) NOT NULL DEFAULT 'PAYMENT',    -- 用途固定
    payment_item_id BIGINT UNSIGNED NULL,            -- 特定項目限定（NULL=受益者の全会費）
    max_amount INT UNSIGNED NULL,                    -- 1回あたり支払い上限（円・濫用抑止）。NULL=上限なし
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',   -- PENDING/ACTIVE/REVOKED/EXPIRED
    effective_from DATETIME NOT NULL,
    effective_until DATETIME NULL,                   -- NULL=無期限（取消まで）。ただし payment_item_id IS NULL の包括 grant は NOT NULL 必須（下記 CHECK）
    granted_via VARCHAR(16) NOT NULL,                -- INVITE_TOKEN/IN_APP
    revoked_at DATETIME NULL,
    revoked_by BIGINT UNSIGNED NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,                        -- 論理削除（GDPR/退会）。テナント基底の deleted_at 規約に対応・業務状態(status)とは独立
    PRIMARY KEY (id),
    KEY idx_ppg_beneficiary (beneficiary_user_id, status),
    KEY idx_ppg_payer (payer_user_id, status),
    UNIQUE KEY uk_ppg_active (beneficiary_user_id, payer_user_id, payment_item_id, status),
    CONSTRAINT chk_ppg_blanket_expiry CHECK (payment_item_id IS NOT NULL OR effective_until IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> **後見（保護者）経由の代理払いは grant 不要**：`parental_consent_links.status=APPROVED` または `user_care_links(relationship=PARENT, status=ACTIVE)` を実行時に確認すれば足りる（既存テーブルを参照するのみ・新規行不要）。`payment_proxy_grants` は**非後見の第三者払い専用**。

**status 遷移と失効**：`PENDING`（招待発行）→ `ACTIVE`（払い手が受諾）→ `REVOKED`（受益者/払い手が取消）or `EXPIRED`（`effective_until` 超過）。
- 失効は**実行時ゲート**（決済時に `status=ACTIVE AND now ∈ [effective_from, effective_until]` を都度評価）を一次防御とし、**@Scheduled バッチ（ShedLock・日次）**が `status=ACTIVE AND effective_until < now` を `EXPIRED` へ掃く（一覧表示の正確化）。
- **包括 grant（item NULL）の濫用抑止**：`effective_until` 必須（CHECK）＋ `max_amount` 推奨。受益者に新会費項目が追加されても、上限・期限の範囲でのみ有効。

### 2.4 後見切替の年齢ポリシーは新規テーブル不要（からくりは code 解決）

後見切替のしきい値（国別）は **`GuardianshipAgePolicy`（code・戦略）** で `users.country_code`＋`birthDate` から解決し、**新規テーブルを要しない**（税 `TaxPolicy` と同型）。既定 `JapanGuardianshipAgePolicy` を bundle、未対応国は満13歳フォールバック。
- **将来拡張（任意）**：運用で国別しきい値を外部調整したくなった場合のみ、マスタ表 `guardianship_age_policies`（`country_code` 自然キー＝CLAUDE.md「マスタテーブル例外」・UUIDv7 不要）を追加し code 既定を上書きする。初期スコープでは作らない。

---

## 3. F22.1 escrow 側の前提（本機能の依存・別PRで F22.1 に実装）

本機能は以下が F22.1 P2-b/P2-e で整うことを前提とする（**本書では定義せず、依存として明記**）。

| 項目 | F22.1 側の状態 | 本機能の依存 |
|---|---|---|
| `escrow_transactions.face_amount`（INT UNSIGNED） | ✅ 実装済（V73.003・main） | 手数料折半計算の基準 |
| `escrow_transactions.capture_mode`（MANUAL/AUTOMATIC） | ✅ 実装済（V73.003・main） | 会費は `AUTOMATIC`（即時） |
| `EscrowSourceKind.MEMBERSHIP` | ✅ 実装済（V73.003・main） | 会費の source_kind |
| `ConnectChargeService`（authorize/capture/refund） | ✅ 実装済（P2-a/b/c・main） | 共通送金基盤 |
| `ConnectChargeService.charge(MembershipChargeCommand)`（即時 AUTOMATIC） | ✅ 本機能 P1 Wave0 で追加（2026-06-03） | 会費の即時 charge |
| `PaymentFeeCalculator`（手数料一元化） | ✅ 実装済（main） | 折半計算（散在禁止・流用必須） |

> F22.1 P2-b/c は **main 済**（V73.003 でスキーマ・enum、`ConnectChargeService.authorize/capture/refund`・`PaymentFeeCalculator` 実装済）。会費が要する即時 `charge()` メソッドのみが欠けていたため、**F08.9 P1 Wave0 で escrow ドメインに追加**した（既存 authorize/capture は無改変・差分最小化で F22.1 並行作業との衝突を回避）。

### 3.1 source_kind × scope の許可マッピング（整合性保証）

escrow の payer/payee 組み合わせは source_kind ごとに意味が決まる。DB の per-column CHECK は値域のみを縛るため、**source_kind に応じた組み合わせはアプリ層で検証**し、加えて防御的な複合 CHECK を V74 で追加する。

| source_kind | payer_scope_kind | payee_kind | 用途 |
|---|---|---|---|
| `MEMBERSHIP` | `USER` | `TEAM` / `ORG` | 会費（会員→チーム/組織） |
| `MEMBERSHIP`（協会請求） | `TEAM` | `ORG` | 協会→加盟チーム請求 |
| `RECRUITMENT`（F22.1） | `USER` | `USER`/`TEAM`/`ORG` | 謝礼 |

```sql
-- V74 で escrow_transactions に防御的複合 CHECK を追加（F22.1 と要調整）
CONSTRAINT chk_et_membership_payee
  CHECK (source_kind <> 'MEMBERSHIP' OR payee_kind IN ('TEAM','ORG'))
```
> 既存 V72.005 は組み合わせを**禁じていない**ので「協会→チーム請求（payer=TEAM/payee=ORG）」は現状スキーマで表現可能。複合 CHECK は誤起票の防御であり、追加は F22.1 側テーブルへの ALTER ゆえ F22.1 P2 と調整する。

---

## 4. ER 図（論理）

```
users(既存)
  ├─(論理)─ memberships(既存: 受益者の所属)
  ├─(論理)─ parental_consent_links(F01.9) ─┐  後見＝代理払いの権原
  ├─(論理)─ user_care_links(F03.12) ───────┤  （保護者リンク）
  └─(論理)─ payment_proxy_grants(新) ───────┘  第三者代理払いの権原

payment_items(拡張: TERM/recurring/tax)
  ├─(論理)─ member_payments(拡張: payer_user_id/escrow_transaction_id/subscription_id)
  │              └─(論理)─ escrow_transactions(F22.1: source_kind=MEMBERSHIP)
  │                            ├─ ledger_entries(F22.1)
  │                            └─ refunds(F22.1)
  ├─(論理)─ membership_subscriptions(新) ──(論理)── stripe Subscription
  ├─(論理)─ content_payment_gates(既存: ペイウォール・受益者キー)
  └─(論理)─ *_access_requirements(既存)

payment_requests(新: 協会→チーム)
  ├─(論理)─ escrow_transactions(F22.1: payer=TEAM/payee=ORG)
  └─(論理)─ confirmable_notifications(F04.9: 配信・督促)

connect_accounts(F22.1・拡張: tax_registration_number/tax_status)
```

- 物理 FK は**同一ドメイン内のみ**（例：将来 `membership_subscription_invoices` を作る場合は subscription に CASCADE 可）。図中「(論理)」はすべて FK なし・INDEX のみ。

---

## 5. Flyway 計画

> **採番注意**（[[feedback_migration_version_collision]]）：origin/main 最大は **V73.003**（2026-06-03）。新規は **V74.xxx 系**を予定するが、**番号はマージ直前に origin/main 最大の次へ確定**する（並行PRと衝突するため）。from-scratch 番人テストで検知させる。

| 版（予定） | 内容 |
|---|---|
| `V74.001__alter_member_payments_add_payer.sql` | `payer_user_id`/`payment_proxy_grant_id`/`payer_relationship`/`escrow_transaction_id`/`membership_subscription_id` 追加・INDEX |
| `V74.002__alter_payment_items_add_term_tax.sql` | `type` ENUM に `TERM` 追加／`is_recurring`/`billing_interval`/`term_starts_on`/`term_ends_on`/`tax_category`/`tax_rate`/`price_includes_tax` 追加 |
| `V74.003__alter_connect_accounts_add_tax.sql` | `tax_registration_number`/`tax_status` 追加（F22.1 テーブルへの追記・要 F22.1 側調整） |
| `V74.004__create_membership_subscriptions.sql` | 継続課金テーブル（UUIDv7） |
| `V74.005__create_payment_requests.sql` | 協会請求テーブル（UUIDv7） |
| `V74.006__create_payment_proxy_grants.sql` | 第三者代理払い許可テーブル（UUIDv7） |

- ENUM 拡張（`payment_items.type` への `TERM`）は MySQL の `MODIFY COLUMN` で実施。H2 テスト互換に留意（[[project_mysql_reserved_word_column_fix]] と同様、ddl-auto:create テストで enum 文字列長/予約語に注意）。

---

## 6. GDPR・退会・暗号化

- **payer_user_id／beneficiary_user_id** は退会時、`user_id` の匿名化方針に従う（投稿・履歴・統計は残し PII のみ消去）。`member_payments`・`membership_subscriptions` は**金銭記録**ゆえ物理削除せず、ユーザー参照を NULL 化せずに匿名化（会計・税務保持義務）。保持期間は F12.3／F09.18 の方針に整合（決済 payload 30日・to_address 13ヶ月・メタ7年相当を踏襲・§税務確定で再調整）。
- **後見切替セッション**の代理操作は `proxy_input_records`（F14.1）に記録。退会・年齢到達（中学進学）で切替権原は自動失効（`users.status` 連動・F14.1 の DECEASED/RELOCATED 失効と同型）。
- **退会時のアトミック失効**（受益者 or 払い手の退会処理＝`UserWithdrawalService` のトランザクション内で実行・宙ぶらりんを残さない）：
  1. 当該ユーザーが受益者の `membership_subscriptions` を `CANCELLED`（＋ Stripe Subscription を cancel）
  2. 当該ユーザーが受益者/払い手の `payment_proxy_grants` を `REVOKED`
  3. 当該ユーザー関連の `proxy_input_consents(scope=PAYMENT)` を `REVOKED`
  4. 払い手が抜けた継続課金は受益者へ「支払者不在」を通知（別の払い手に切替を促す）
  - バッチ（日次）は**取りこぼしの掃き取り**（二重防御）であって主経路ではない。順序は user 失効より先に下流（grant/subscription）を倒し、不整合を残さない。
- `payment_proxy_grants` は受益者退会で `REVOKED`（上記1トランザクションに含む）。
- `connect_accounts.tax_registration_number` は公開情報ゆえ暗号化不要。会員 PII（氏名等）は領収書生成時に既存の暗号化済み `users` から都度復号（保存しない）。

---

## 7. 整合性・アプリ層保証（クロスドメイン FK の代替）

物理 FK を張らない代わりにアプリ層で保証する不変条件：

1. `member_payments.payer_user_id` は実在 user（決済時に検証）。
2. `member_payments` の受益者×項目の有効重複は `existsValidPaidPayment` で防止（既存）。
3. `membership_subscriptions.payee_connect_account_id` は `onboarding_status=READY` を起票前に確認。
4. `payment_requests` の payer(TEAM)/payee(ORG) は加盟関係（`memberships` or 組織-チーム関係）を発行時に検証。
5. 代理払いは §3.3 の権原（保護者リンク／grant／本人／ADMIN）のいずれかを決済時に必須検証（03_security §2）。
