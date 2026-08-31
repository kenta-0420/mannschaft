# F20.1 — 05 料金・契約センター／実決済運用

> **ステータス**: 🟡 精査中（2026-08-31）
> **適用範囲**: Phase 2b の実決済を、利用者が自分で理解・管理・停止できる形に完成させる。本書は、既存 01〜04 のうち実決済、請求表示、解約、顧客所有に関する記述を補完し、矛盾時は本書を優先する。

---

## 1. 決定事項と目的

### 1.1 利用者に約束すること

1. **個人・チーム・組織は、それぞれ独立した契約者（課金所有者）**である。チーム/組織の管理者が操作しても、その人個人の Stripe Customer、支払方法、請求履歴を流用しない。
2. ログイン後の **`/billing`（料金・契約）** で、本人が課金管理できる全スコープを一か所に一覧・切替して管理する。現在選んでいるチーム/組織を変えても、別スコープの履歴は消えない。
3. 月別の請求・支払明細、契約内容、次回請求日、支払方法、請求先情報、請求書/領収書への導線を常時提供する。価格は **税込を主表示**し、税額・税抜額も同じ画面で示す。
4. 通常の販売単位は **月次自動更新**である。年額は販売開始するまで選べず、1か月だけのパスは提供しない。
5. **暦月課金**とする。初月は契約日（含む）から月末までの日割り、翌月以降は毎月1日から月末までの満額。アップグレードは即時・残期間の差額日割り、ダウングレードは翌月1日から反映する。
6. 解約は一度の確認で受け付け、当月末で終了する。終了日・以後請求されないこと・失う機能を確認画面に明示し、当月末までは解約予定を撤回できる。引き止め、電話、隠れた導線、多段階確認は置かない。

### 1.2 非目標

- 価格、BASIC の内容、年額価格をコードや設計書に固定しない。販売可能なのは、運用マスタに有効な Stripe Price と税込表示に必要な価格情報がそろった商品だけである。
- F08.9 の「チーム→メンバー」会費、F22.1 のマーケット決済、プリペイド/残高は対象外。
- 自動税計算は、Stripe Tax の税務登録が有効化されるまで有効にしない。未登録時は運用マスタの税率スナップショットで明示計算し、税の請求責任を Stripe に委ねたように表示しない。

## 2. As-Is と To-Be

| 論点 | As-Is | To-Be |
|---|---|---|
| Stripe Customer | `PaymentMethodService.getOrCreateStripeCustomerId(operatorUserId)` を呼ぶため、TEAM/ORG 契約も操作管理者個人に属する | `billing_customers` の scope-owned Customer を使用。USER/TEAM/ORG ごとに 1 Customer |
| 導線 | `/settings/billing`、`/teams/{slug}/settings/billing`、`/organizations/{slug}/settings/billing`、`/billing/plans` が分散 | 認証後は `/billing` に統合。既存 URL は削除せず、scope を選択した `/billing?scopeKind=...&scopeId=...` に遷移 |
| 請求表示 | 契約・権利中心。請求月一覧、請求書、領収書、支払方法管理が不足 | スコープごとに月別明細、請求書/領収書、支払方法・請求先を提供 |
| プラン変更 | 有償変更は `ENTITLEMENT_017` で拒否 | upgrade/downgrade の時点と金額を事前表示し、Stripe と同期して実行 |
| 取消 | `cancel_at_period_end` はあるが、撤回・表示・文脈復帰が不足 | 月末終了、撤回 API/UI、終了日表示、成功/中止後も同じ scope に復帰 |

既存 `billing_contracts.psp_customer_ref` は履歴スナップショットとして残すが、以後の正規の Customer 所有者は `billing_customers` とする。`stripe_customers`（個人用 payment ドメイン）の読み書きを TEAM/ORG の課金で行ってはならない。

## 3. 画面と導線

### 3.1 公開料金ページ `/pricing`

- 未認証でも閲覧可能、SSR/SEO 対象。料金の比較、対象スコープ、月次自動更新、初月日割り、税表示、解約/撤回、支払方法、問い合わせ先を平易な言葉で掲載する。
- 有効価格のないプラン（BASIC を含む）は「準備中」と表示し、金額・購入 CTA を出さない。ログイン後 CTA は `/billing` へ戻す。
- 「いつでも解約できます」だけで済ませず、「解約受付後も当月末まで利用でき、翌月以降の請求はない」と表記する。

### 3.2 認証後ハブ `/billing`

```
料金・契約
 [個人: 山田 太郎] [チーム: ○○] [組織: △△]     ← 課金管理可能な scope のみ
 ─────────────────────────────────────────────
 現在の契約 / 次回請求日 / 今月（税込） / 解約予定
 [プランと機能] [請求・支払い] [支払方法・請求先] [解約]
```

- `GET /me/billing/scopes` で列挙する。USER は常に本人、TEAM/ORG は ADMIN または DEPUTY_ADMIN のみ。SYSTEM_ADMIN は通常利用者としての横断表示を得ず、専用管理画面を用いる。
- URL は `scopeKind` と `scopeId` を持つが、いずれもサーバーが返した選択肢以外は選べない。未指定時は個人、なければ先頭の管理可能スコープを選ぶ。
- チーム/組織への所属又は課金管理権限を失った時だけ当該スコープを一覧から除く。契約・請求履歴は削除せず、現管理者が同じ scope を選択して引き継ぐ。
- チーム/組織の一般メンバーは既存どおり権利サマリだけを読める。金額、支払方法、請求先、請求書/領収書、契約変更は ADMIN/DEPUTY_ADMIN 限定とする。
- 既存の 3 課金管理 URL は互換リダイレクトとして残し、同じ画面に該当 scope を選択して開く。`/billing/plans` は `/billing?tab=plan` へ移す。Checkout の success/cancel URL も同じ scope と `tab` を保持する。
- 初回 Checkout 前に請求先メールアドレスと宛名を確認・編集できる。USER は本人の確認済みメールを初期表示、TEAM/ORG は今回の管理者のメールを**候補**として初期表示するだけで、保存先は scope-owned `billing_customers` である。後任管理者は同じ請求先を引き継ぎ、個人の payment customer には保存しない。

### 3.3 誠実な UI 文言と操作

- 購入前: 「本日から月末まで」「初回合計（税込）」「翌月1日以降の月額（税込）」「自動更新」「解約方法」を一画面に表示する。アップグレード時は今回の差額日割りと即時に増える機能、ダウングレード時は翌月からの価格/失う機能を表示する。
- 解約確認: 主文は `「{終了日} まで利用できます。{翌月1日} 以降は請求されません。」`。確認ボタンは `「{終了日}で解約する」`、戻るボタンは同じ強さで `「続けて利用する」`。理由の入力、電話、チャット、複数画面は要求しない。
- 解約予約中は警告バナーに終了日、残る機能、`「解約予定を取り消す」` を表示する。取り消しは追加確認なしで即時実行し、監査/トーストで結果を示す。
- 請求失敗は失敗月、再試行状況、利用権への影響予定、支払方法の更新導線を表示する。恐怖を煽る文言や、支払方法更新と解約操作を混在させない。

すべての表示文言は `billing.json` の 6 言語キーに追加する。日付・通貨は `locale.value` を用いた `Intl` で表示し、価格を UI に直書きしない。

## 4. 請求期間・契約状態

### 4.1 暦月と日割りの正準

| 操作 | 請求/権利 |
|---|---|
| 新規月額契約 | 契約日時からその月末 24:00 JST（翌月1日00:00 JST）まで日割り請求。権利は支払い成功時から同じ期間に有効 |
| 翌月以降 | 毎月1日 00:00 JST に当月末までを月額請求 |
| アップグレード | 即時反映。旧/新プラン差額を変更時から月末まで日割りし、プレビューと確定額を表示 |
| ダウングレード | 当月の権利・料金は維持。翌月1日から新プラン/価格へ変更。返金・負額クレジットはしない |
| 解約 | 当月末で終了。解約操作月の残額返金なし、翌月以降は請求なし |
| 解約撤回 | 当月末まで即時に取消し、同じ契約を継続。期末後は新規契約 |

月末境界は `Asia/Tokyo` を基準に計算し、Stripe には UTC epoch seconds で渡す。内部永続化は既存規約どおり `DATETIME(6)` の JST ローカル時刻を使用し、表示と比較の境界を統一する。年額を将来販売する場合も本章の「月額」前提をコピーしてはならず、別の販売・解約・税・按分仕様を軍議で決める。

### 4.2 Stripe の責務

- 新規契約は Stripe Checkout `mode=subscription`。scope-owned Customer を明示し、`subscription_data.billing_cycle_anchor` に直近の翌月1日 00:00 JST を UTC timestamp 化して指定する。初回 Checkout では既定の `proration_behavior=create_prorations` を用い、初回の部分月を Stripe Invoice として確定する。
- 未来 anchor は Stripe の制約どおり、契約開始より未来かつ次の自然更新日より前でなければならない。月末日の作成、時計ずれ、Checkout の有効期限を計算時に検証し、満たせない場合は Checkout を作らず再試行可能な 502 を返す。
- upgrade は Subscription update + `proration_behavior=always_invoice` とする。変更前に Stripe の upcoming invoice/proration preview を取得し、確定時は `payment_behavior=pending_if_incomplete` を使う。SCA 又は支払失敗の間は変更を `PENDING_PAYMENT` として保持し、**`invoice.paid` を受けるまで active plan/entitlements を切り替えない**。失敗・期限切れでは予約を破棄して旧プランを維持する。
- downgrade は `proration_behavior=none` の即時 update を使わない（料金だけでなく機能まで即時に落ちるため）。Subscription Schedule 又はローカル予約 + 期末の Stripe update で翌月1日 00:00 JST にだけ反映し、即時の負額日割りを作らない。
- 解約/撤回は Subscription の `cancel_at_period_end=true/false`。本アプリが正本であり、Customer Portal の cancel と price change は無効化する。
- Customer Portal は短命 session を発行し、支払方法、請求先情報、Stripe の請求書閲覧だけに限定する。return URL は許可済みの `/billing` 固定パス（scope を署名済み state で復元）で、任意 URL を受け取らない。
- Webhook が契約/請求状態の唯一の確定通知である。画面遷移や API の成功レスポンスだけで支払い済み・権利発行としない。

### 4.3 状態遷移

```
PENDING --checkout.session.completed/invoice.paid--> ACTIVE
PENDING --session expired/cancelled--> CANCELLED
ACTIVE --payment_failed--> PAST_DUE --invoice.paid--> ACTIVE
ACTIVE --cancel scheduled--> ACTIVE(cancel_at_period_end=true)
ACTIVE(cancel scheduled) --resume--> ACTIVE(cancel_at_period_end=false)
ACTIVE(cancel scheduled) --period end/subscription.deleted--> CANCELLED
ACTIVE --downgrade scheduled--> ACTIVE(next_plan_key set)
ACTIVE --upgrade requested--> ACTIVE(change=PENDING_PAYMENT)
ACTIVE(change=PENDING_PAYMENT) --invoice.paid--> ACTIVE(new contract/entitlements atomically switched)
ACTIVE(change=PENDING_PAYMENT) --payment failed/expired--> ACTIVE(old plan retained)
```

`current_period_end` は必ず次の JST 月初、`cancelled_at` は「解約を予約した時刻」、終了状態の確定は webhook で記録する。権利の `valid_until` は期末まで保持し、解約予約だけで revoke しない。支払失敗の猶予・失効時刻は Stripe の status と運用ポリシーを UI にそのまま反映し、ローカルの推測で再請求しない。

同じ契約での予約競合は次の優先順で直列化する。(1) `PENDING_PAYMENT` の upgrade 中は解約・downgrade・別 change を 409 で拒否し、先に支払完了又は失敗を確定する。(2) 解約予約中は新たな upgrade/downgrade を 409 で拒否し、利用者が先に解約予定を撤回する。(3) downgrade 予約中に解約を選んだ場合は downgrade 予約を取り消して解約予約を作る。(4) 解約予約中の撤回後にのみ change を受け付ける。いずれも行ロックではなく契約の楽観ロック/version と idempotency key で一意に直列化し、Stripe 呼出し前後の状態を webhook で再照合する。

## 5. データ設計・Flyway

既存テーブルを破壊せず Expand → Backfill → Switch → Contract の順で移行する。クロスドメイン FK は作らない。業務表の ID は UUIDv7 `BINARY(16)`、外部 Stripe ID は `VARCHAR(255)`、`organization_id` は既存の tenant 規約に従い NULL 許容の論理参照とする。

```sql
CREATE TABLE billing_customers (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    scope_kind VARCHAR(8) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    organization_id BIGINT UNSIGNED NULL,
    psp_customer_ref VARCHAR(255) NOT NULL COMMENT 'Stripe cus_...',
    billing_email VARCHAR(254) NULL,
    billing_name VARCHAR(255) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bcu_scope (scope_kind, scope_id),
    UNIQUE KEY uk_bcu_psp_customer (psp_customer_ref),
    KEY idx_bcu_org (organization_id),
    CONSTRAINT chk_bcu_scope CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_bcu_status CHECK (status IN ('ACTIVE','LEGACY_MIGRATION_REQUIRED','CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='scope 所有の Stripe Customer';

CREATE TABLE billing_invoices (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    billing_customer_id BINARY(16) NOT NULL,
    contract_id BINARY(16) NULL,
    scope_kind VARCHAR(8) NOT NULL,
    scope_id BIGINT UNSIGNED NOT NULL,
    psp_invoice_ref VARCHAR(255) NOT NULL,
    psp_subscription_ref VARCHAR(255) NULL,
    billing_reason VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    period_start DATETIME(6) NULL,
    period_end DATETIME(6) NULL,
    currency CHAR(3) NOT NULL DEFAULT 'JPY',
    subtotal_amount BIGINT NOT NULL,
    tax_amount BIGINT NOT NULL DEFAULT 0,
    total_amount BIGINT NOT NULL,
    paid_at DATETIME(6) NULL,
    finalized_at DATETIME(6) NULL,
    voided_at DATETIME(6) NULL,
    hosted_invoice_url_snapshot VARCHAR(2048) NULL,
    invoice_pdf_url_snapshot VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_psp_invoice (psp_invoice_ref),
    KEY idx_bi_scope_period (scope_kind, scope_id, period_end),
    KEY idx_bi_customer_period (billing_customer_id, period_end),
    CONSTRAINT chk_bi_scope CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_bi_status CHECK (status IN ('DRAFT','OPEN','PAID','UNCOLLECTIBLE','VOID'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Stripe invoice のローカル投影';

CREATE TABLE billing_invoice_lines (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    invoice_id BINARY(16) NOT NULL,
    psp_line_ref VARCHAR(255) NOT NULL,
    description_snapshot VARCHAR(500) NOT NULL,
    quantity DECIMAL(12,3) NOT NULL DEFAULT 1,
    amount_excluding_tax BIGINT NOT NULL,
    tax_amount BIGINT NOT NULL DEFAULT 0,
    amount_including_tax BIGINT NOT NULL,
    period_start DATETIME(6) NULL,
    period_end DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_bil_invoice_line (invoice_id, psp_line_ref),
    CONSTRAINT fk_bil_invoice FOREIGN KEY (invoice_id) REFERENCES billing_invoices(id),
    CONSTRAINT chk_bil_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='請求明細の不変スナップショット';
```

- 金額は JPY 最小単位（円）の整数。クーポン等で負額明細が生じうるため金額列を `UNSIGNED` にしない。invoice/line は会計証跡であり物理削除・内容上書きをしない。URL は取得時点の補助情報にすぎないため、ダウンロード時には Stripe から最新リンクを再取得する。
- `billing_contracts` に `billing_customer_id BINARY(16) NULL`、`billing_cycle_anchor_at DATETIME(6) NULL`、`scheduled_plan_key VARCHAR(64) NULL`、`scheduled_change_at DATETIME(6) NULL`、`cancel_scheduled_at DATETIME(6) NULL` を追加する。新規契約後は customer を必須にし、旧契約は NULL を許容して移行状態を表す。
- `billing_webhook_events`（Stripe event id UNIQUE、event type、received/payload hash、processed_at、failed_at、attempt_count）を追加する。生 payload は暗号化保管が必要な最小期間（30日）に限定し、PII のある payload を監査ログへ複写しない。
- Flyway は `Vxxx__expand_billing_scope_customers_and_invoices.sql`、backfill 用の再実行可能ジョブ、`Vxxx__switch_billing_contract_customer_ref.sql`、旧 read path 除去の順。migration は `utf8mb4`、CHECK、index、コメントを含める。E2E 全表 truncate リストも更新する。

## 6. API

全成功応答は `ApiResponse`、一覧は `PagedResponse` / cursor response、失敗は既存 `EntitlementErrorCode` を拡張する。変更系は必ず `Idempotency-Key`（UUID）を要求し、同じ key + actor + method + canonical request hash は同じ結果を返す。

| API | 権限 | 用途 |
|---|---|---|
| `GET /api/v1/me/billing/scopes` | 本人 | 管理可能な scope 一覧 |
| `GET /api/v1/me/billing/summary?scopeKind&scopeId` | USER本人 / TEAM・ORG管理者 | 契約、次回請求、解約予約、機能、税込金額 |
| `GET /api/v1/me/billing/invoices?scopeKind&scopeId&cursor` | 同上 | 月別請求一覧 |
| `GET /api/v1/me/billing/invoices/{invoiceId}` | 同上 | 明細。scope 条件付き取得で 404 秘匿 |
| `POST /api/v1/me/billing/invoices/{invoiceId}/document-session` | 同上 | Stripe 最新請求書 URL への短期 redirect/session |
| `POST /api/v1/me/billing/checkout-sessions` | 同上 | 新規契約 Checkout。初回日割り見積を返す |
| `POST /api/v1/me/billing/contracts/{contractId}/change-preview` | 同上 | upgrade/downgrade の金額・反映時点を取得 |
| `POST /api/v1/me/billing/contracts/{contractId}/changes` | 同上 | preview token に基づく変更実行 |
| `POST /api/v1/me/billing/contracts/{contractId}/cancel` | 同上 | 月末解約を予約 |
| `DELETE /api/v1/me/billing/contracts/{contractId}/cancel` | 同上 | 月末前の解約予定撤回 |
| `POST /api/v1/me/billing/portal-sessions` | 同上 | 支払方法・請求先・請求書限定 Customer Portal |
| `POST /api/v1/billing/webhooks/stripe` | Stripe 署名のみ | 非公開 webhook 受信 |

`scopeKind/scopeId` は query の利便性のために用いるが、Service は必ず契約/請求書から scope を解決して二重照合する。USER は `scopeId == currentUserId` のみ、TEAM/ORG は `@accessGuard.isScopeAdmin` と Repository の `findByIdAndScopeKindAndScopeId` で防御する。他 scope の invoice/contract id は 404、無権限 scope の指定は 403 とする。Portal URL、Stripe ID、任意 redirect URL をクライアント入力に受けない。

主要エラー: `BILLING_CUSTOMER_MIGRATION_REQUIRED`(409)、`BILLING_PRICE_NOT_SELLABLE`(409)、`BILLING_PREVIEW_EXPIRED`(409)、`BILLING_CANCEL_NOT_SCHEDULED`(409)、`BILLING_PERIOD_ALREADY_ENDED`(409)、`BILLING_INVOICE_NOT_FOUND`(404)、`BILLING_SCOPE_FORBIDDEN`(403)、`BILLING_STRIPE_UNAVAILABLE`(502)。クライアント起因は WARN/4xx、Stripe/API 不整合は ERROR/502 とし、`GlobalExceptionHandler` に明示マップする。

## 7. Webhook、整合性、監査

1. Stripe 署名を raw body で検証し、endpoint secret は secret store のみから読む。検証前に JSON parse やログ出力をしない。
2. event id を `billing_webhook_events` の UNIQUE で先に確保して冪等化する。同一 event の再配信は 200 を返し副作用を繰り返さない。異なる event の順不同は Stripe object の `created` と current state を照合し、古い状態で新しい状態を巻き戻さない。
3. `checkout.session.completed` は Customer/contract の紐付け確認だけを行い、支払済み確定は invoice/subscription event に従う。`invoice.finalized/paid/payment_failed/voided` は `billing_invoices` と行明細を upsert、`customer.subscription.updated/deleted` は取消予約・period・scheduled change を同期する。
4. handler は DB transaction を短くし、外部 Stripe 再読込は transaction 外で行う。失敗は指数 backoff で再処理可能にし、最大回数後は運営アラートと照合キューへ送る。event を握りつぶして 2xx にしない。
5. `BILLING_CHECKOUT_CREATED`、`BILLING_CHANGE_SCHEDULED`、`BILLING_UPGRADED`、`BILLING_CANCEL_SCHEDULED`、`BILLING_CANCEL_RESCINDED`、`BILLING_PORTAL_OPENED`、`BILLING_INVOICE_VIEWED`、`BILLING_WEBHOOK_PROCESSED/FAILED` を audit_logs に actor/scope/contract/Stripe object ref/金額スナップショット（カード番号・住所全文・payload は除外）で記録する。

請求書/領収書のローカル投影は、請求後 7 年保持する。退会・脱退で表示権限を失っても法定・会計保持対象を削除せず、USER の個人情報は既存退会ポリシーに従い匿名化し、請求の金額・時期・法人名等の保存根拠をプライバシー通知に明記する。

## 8. 既存 operator-owned Customer の移行

Stripe Subscription の Customer をサイレントに別 Customer へ付け替えない。支払方法の権限と請求先の混同を起こすためである。

1. 新規 USER/TEAM/ORG 契約は即時に `billing_customers` を作成し、新 Customer のみを使う。
2. 既存 USER 契約は本人 Customer と scope が一致することを確認して `billing_customers` を backfill する。一致しないものも legacy 扱いにする。
3. 既存 TEAM/ORG 契約で `psp_customer_ref` が操作管理者個人 Customer のものは `LEGACY_MIGRATION_REQUIRED`。現管理者には「現在の支払方法を引き継がず、次回更新日からチーム/組織の新しい契約へ切り替える」一回限りの移行 UI を出す。新 Customer で Checkout を完了し、開始日は旧 subscription の `current_period_end`、旧 subscription は同時点で `cancel_at_period_end=true` とする。重複請求のないことを webhook で確認して完了とする。
4. 移行を拒否/未完了なら旧契約は期末で終了し、新しいスコープ Customer での再契約を案内する。旧 Customer のカード情報、請求先、他の個人契約は画面にも API にも出さない。
5. 切替前に、契約数、Stripe subscription status、period end、既存 customer と scope 対応を dry-run 出力し、運営承認後に対象を段階実行する。失敗時は新 subscription を期首前に cancel し、旧契約を維持するため、既存 Customer を削除・detach しない。

## 9. 非機能・テスト・受入条件

- 料金ハブ/請求一覧は P95 500ms を目標とし、invoice 一覧は cursor pagination（既定20、最大100）。Stripe API をページ表示のたびに同期呼出しせず、webhook 投影を読む。請求書 download のみ短命 URL を取得する。
- Checkout/変更/解約/撤回/Portal は scope ごとに 10 回/時のレート制限。個人情報を URL、フロントログ、監査ログ、例外メッセージに出さない。CSP は Stripe/Portal の許可先を最小限にする。
- 単体: JST 月末/うるう年/年跨ぎ日割り、upgrade/downgrade、取消/撤回境界、price 未設定、idempotency、scope resolver、webhook 順不同・重複。
- 結合: MySQL UNIQUE/索引、Flyway upgrade、権限（他 scope 403/404）、invoice 投影、legacy dry-run、退会後保持。
- E2E: 個人・チーム・組織を一画面で切替、既存導線の文脈維持、税込内訳、Checkout success/cancel、解約一確認/撤回、メンバー非表示、支払失敗、6言語。Stripe は test clock/fixture と webhook 署名を用いる。

| AC | 受入条件 |
|---|---|
| AC-BC-01 | 操作者が異なっても、同じ TEAM/ORG は一つの scope-owned Customer を使い、操作者個人 Customer を参照しない |
| AC-BC-02 | `/billing` は管理可能な全 scope を列挙し、active scope の変更だけで別 scope の履歴を失わない |
| AC-BC-03 | 初回部分月、翌月満額、upgrade 差額、downgrade 翌月反映を JST 境界で正しく表示・請求する |
| AC-BC-04 | 解約は一確認で月末終了、撤回は月末前のみ成功し、翌月の請求を作らない |
| AC-BC-05 | 月別 invoice、税込主表示・税内訳、請求書/領収書、支払方法/請求先に管理者だけが到達できる |
| AC-BC-06 | 無権限の scope/contract/invoice/portal 要求で他者の契約・請求情報を得られない |
| AC-BC-07 | 同一 webhook/同一 idempotency key の再送で二重契約、二重請求、二重監査が起きない |
| AC-BC-08 | legacy TEAM/ORG 契約は個人 Customer を再利用せず、期末切替または終了のいずれでも二重課金しない |
| AC-BC-09 | Stripe Tax 登録なしで automatic_tax を有効化せず、販売不能な価格は CTA/API とも拒否する |

## 10. ロールアウトとロールバック

1. migration と read-only hub を feature flag 下で投入し、実データを dry-run 照合する。
2. 新 Customer の新規契約、請求投影、Portal（限定設定）、取消/撤回、最後に有償変更を順に有効化する。
3. 各段で webhook 遅延・invoice 差分・二重請求ゼロを監視する。異常時は新規 checkout/change flag を即時 OFF にし、既存 subscription/webhook 処理を維持する。既に作成した Customer、invoice、audit は削除しない。
4. old operator-owned read path は、legacy 契約がゼロか移行期限終了後にのみ削除する。削除前に Stripe/DB の subscription 件数と scope customer の 1:1 制約を照合する。
