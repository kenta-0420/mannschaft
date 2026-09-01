# 柱③-B 組織契約の請求担当と個人支払手段の分離 設計書

> 起票日: 2026-09-02
> 担当: 足軽（本設計書は Codex 検分2巡の要求事項を踏まえた仕様固め）
> ステータス: 🟡 設計段階（レビュー待ち）
> 課題管理: CMP-260901-1538
> 参照: [`account_purge_last_admin_succession.md`](./account_purge_last_admin_succession.md) / [`withdrawal_flow_immediate_anonymization_fix.md`](./withdrawal_flow_immediate_anonymization_fix.md) / [`domain_db_design_principles.md`](./domain_db_design_principles.md)

---

## §1. 問題定義（実コード再確認済み）

### 1.1 payer が「操作者個人」に暗黙固定されている

[`StripeBillingPaymentGateway#createSubscriptionCheckout`](../../backend/src/main/java/com/mannschaft/app/billing/StripeBillingPaymentGateway.java) は次のように決済者を解決する。

```java
String stripeCustomerId = paymentMethodService.getOrCreateStripeCustomerId(operatorUserId);
```

`operatorUserId` はチェックアウトを叩いた「今この瞬間の ADMIN 個人」の user ID であり、TEAM/ORG スコープの `billing_contracts` であっても Stripe 上の Customer・支払い手段は常にこの個人に紐づく。契約作成時に保存される `createdBy`（[`BillingContractEntity.java:119-120`](../../backend/src/main/java/com/mannschaft/app/billing/BillingContractEntity.java)）が、事実上「誰の財布で払っているか」を表す唯一の記録になっている。

### 1.2 purge は USER スコープしか見ない

[`BillingContractService#cancelAllUserContractsForPurge`](../../backend/src/main/java/com/mannschaft/app/billing/BillingContractService.java#L562) は

```java
billingContractRepository.findByScopeKindAndScopeIdAndStatusInAndDeletedAtIsNull(
        EntitlementScopeKind.USER, userId, ...)
```

と `EntitlementScopeKind.USER` に固定してクエリしている。ある個人が TEAM/ORG 契約の実質 payer（`createdBy` = その人）であっても、この検索条件には一切引っかからない。**退会 30 日後の物理匿名化を経ても、TEAM/ORG 契約は退会者個人の Stripe Customer への課金を止めずに継続する。**

### 1.3 `WithdrawalStripeHandler` は実質スタブ

[`WithdrawalStripeHandler.java`](../../backend/src/main/java/com/mannschaft/app/gdpr/service/WithdrawalStripeHandler.java) は `WithdrawalRequestedEvent`（Day 0・退会受付）を購読するが、参照しているのは `StripeCustomerRepository` / `TeamSubscriptionRepository`（F20.1 以前の旧テーブル）であり、実際の Stripe API 呼び出しは

```java
// 2. StripePaymentProviderにサブスクリプションキャンセルメソッドが未実装のため、
//    DBのACTIVEサブスクリプションのステータスをCANCELLEDに更新するのみ実施する。
log.warn("Stripeサブスクキャンセル未実装: userId={}", userId);
```

のとおり **未実装**。`billing_contracts`／`membership_subscriptions`（payer/beneficiary/payee 3分離済み・[`MembershipSubscriptionEntity.java:79-84`](../../backend/src/main/java/com/mannschaft/app/payment/entity/MembershipSubscriptionEntity.java)）のいずれも本ハンドラの対象外。

### 1.4 実害

1. 組織 ADMIN が退会 → TEAM/ORG 契約は解約されず、退会者個人の Stripe Customer への課金が継続する
2. `membership_subscriptions.payer_user_id` が退会しても、受益者（`beneficiary_user_id`）へのサービス提供継続可否や請求引継の仕組みが無い
3. 退会者の Stripe Customer / PaymentMethod は 30 日後に強匿名化されるが、その裏で継続課金しているサブスクリプションが存在すればカード情報の実質的な利用が止まらない（1.3 と連動する法務リスク）

---

## §2. Stripe 公式資料での方式確定（推測禁止・裏取り済み）

### 2.1 Subscription の customer 変更は API 非対応（確定）

`stripe docs api subscriptions/update`（Update a Subscription）のパラメータ一覧を全項目確認した結果、**`customer` は Update Subscription のパラメータに存在しない**。Subscription オブジェクトの `customer` は生成時にのみ確定するフィールドであり、REST API 経由での「Subscription を維持したまま customer を差し替える」操作は提供されていない（Stripe Dashboard 上でも同様に非対応）。

→ **方式は「置換方式」で確定**: 新 Customer（新 payer）で新規サブスクリプションを作成し、その成功を確認してから旧サブスクリプション（旧 payer）を解約する。

### 2.2 置換方式のパラメータ表（Stripe 公式ドキュメント裏取り済み）

| 論点 | 確定事項 | 根拠 |
|---|---|---|
| **新サブスク作成方法** | 新 payer の Stripe Customer に対し `POST /v1/subscriptions` を新規発行（既存の `StripeBillingPaymentGateway.createSubscriptionCheckout` と同じ Checkout Session 経路、`operatorUserId` を新 payer の userId に差し替えるだけで既存実装を再利用可能） | `subscriptions/update` API に `customer` パラメータが存在しないことから消去法で確定 |
| **旧サブスクの扱い** | 新サブスクの初回請求が成功したことを webhook (`invoice.paid` または `customer.subscription.created` かつ `status=active`) で確認してから、旧サブスクを `cancel_at_period_end=true`（期末解約）で止める。既存 `StripeBillingPaymentGateway#cancelAtPeriodEnd` をそのまま再利用可能 | 二重契約防止のため「新が生きたことを確認 → 旧を止める」の順序を厳守（§3 冪等性参照） |
| **proration** | 新規サブスクは新規契約として扱う（旧サブスクの未消化期間分の按分は行わない）。旧サブスクは期末解約のため、期末までの分は旧 payer への請求が発生し得る点を UI で告知する。`proration_behavior` は新サブスク作成時は無関係（新規作成に proration の概念はない）、旧サブスク解約は `cancelAtPeriodEnd`（既存実装）のため即時請求は発生しない | Update Subscription の `proration_behavior` は「価格変更・数量変更時の按分」の話であり、customer 変更（＝サブスク自体の置換）には適用されない |
| **billing_cycle_anchor 引継** | 引き継がない。新サブスクは新 Customer の契約開始日を起点とする新しい請求サイクルになる（旧の周期とは非連続）。**引継 UI では「請求日が変わる」ことを新 payer に明示する** | `billing_cycle_anchor` は Update Subscription では `now`/`unchanged` の enum のみで「他サブスクの周期を引き継ぐ」用途のパラメータは無い |
| **trial 残日数** | 引き継がない。本サービスの TEAM/ORG プランは無料トライアルを提供していない（`BillingContractService` に `trial` 関連ロジックなし・実コード確認済み）ため対象外。将来トライアル付きプランを追加する場合は `trial_end` を明示 Unix timestamp で新サブスクに個別設定する方針とする | 実コード（`BillingContractService.java` createContractInternal 系）に trial 相当のフィールドが存在しないことを確認 |
| **初回請求の有無** | 発生する。新サブスク作成は新規契約であり、Checkout Session 経由で即時決済が走る（既存 `createSubscriptionCheckout` と同じ挙動） | 既存実装のフローそのもの |
| **クーポン/割引の引継** | 引き継がない（引継元の `discounts` は新規作成時に明示的に再指定しない限り適用されない）。本サービスは現状クーポン機能を提供していないため対象外。将来対応時は新サブスク作成時に `discounts` パラメータで明示指定する | Update Subscription の `discounts` は「populated array で上書き／空配列で維持／空文字列でクリア」という当該サブスクへの操作であり、他サブスクへの継承機構は無い |
| **税の引継** | 引き継がない。新サブスク作成時に `automatic_tax` を新規指定する（既存実装が対応していれば流用、未対応なら本設計のスコープ外として現状維持） | 同上、`automatic_tax` は Update 対象サブスク自身への設定であり継承概念なし |

**結論**: 引継は「新規契約の作成＋旧の期末解約」であり、値を移し替える機構は存在しない。UI/通知文言には必ず「請求日・初回課金額が変わる場合がある」旨を含めること（AC-7 参照）。

---

## §3. 冪等性と失敗回復

### 3.1 状態機械

引継は次の状態を持つ（永続化は §4 DDL の `billing_payer_handover_requests` テーブル）。

```
REQUESTED（要求）
   │ 対象スコープの他 ADMIN が承諾
   ▼
ACCEPTED（承諾）
   │ 新サブスク作成 API 呼び出し開始
   ▼
SWITCHING（切替中）
   │ 新サブスク active 確認 → 旧サブスク cancel_at_period_end 発行
   ▼
COMPLETED（完了） ─┬─ 新規作成失敗 → FAILED（要求ごとやり直し、旧契約は無傷のまま）
                    └─ 新は成功したが旧解約が失敗 → PARTIALLY_COMPLETED（旧解約のみ再試行対象）
```

- `REQUESTED` → 猶予期限（既定 14 日、§4 参照）を過ぎても `ACCEPTED` にならなければ `EXPIRED` とし、purge バッチが期末解約へフォールバックする（§4）。
- `SWITCHING` に入ってから一定時間（30分)経過しても `COMPLETED`/`FAILED` に遷移しない場合は監視アラート対象（詰まった引継を人手検知するため）。

### 3.2 Idempotency-Key の付与単位

Stripe 公式ドキュメント（Idempotent requests）で確認した仕様: Idempotency-Key は最短 24 時間保持され、**同一キーに対するパラメータ不一致はエラーになる**（誤って別リクエストに使い回すと即座に検出される安全設計）。この性質を利用し、以下の単位でキーを固定する。

| 操作 | Idempotency-Key | 生成規則 |
|---|---|---|
| 新サブスク作成（Checkout Session / Subscription 作成） | `billing-handover-create-{handoverRequestId}` | `handoverRequestId` は `billing_payer_handover_requests.id`（UUIDv7）。**再試行時も同じ ID を使う**ため、二重サブスク作成を Stripe 側でも機械的に防ぐ |
| 旧サブスク期末解約 | `billing-handover-cancel-{handoverRequestId}` | 同上。既存 `StripeBillingPaymentGateway#cancelAtPeriodEnd` の呼び出し規約（`"billing-cancel-" + subscriptionRef"`）と衝突しないよう接頭辞を分ける |

### 3.3 新旧サブスクの相関 ID永続化

`billing_payer_handover_requests` に `old_contract_id`（旧 `billing_contracts.id`）・`new_contract_id`（新規発行後に埋める）・`new_psp_subscription_ref` を持たせる。新サブスク作成 API 呼び出しが成功した時点で **同一トランザクションで** `new_contract_id` と `new_psp_subscription_ref` を保存してから状態を `SWITCHING` → 旧解約実行、とする（DB 更新と Stripe 呼び出しの間に落ちても、次回リトライ時に「新は既に作成済みか」を判定できるようにするため）。

### 3.4 「新作成成功→DB更新失敗→再試行」で二重サブスクにしない手順

1. `handoverRequestId` を先に採番し `ACCEPTED` として1トランザクションでコミット（Stripe 呼び出し前に確定させる）
2. Stripe 新サブスク作成を `billing-handover-create-{handoverRequestId}` で呼ぶ
3. 成功レスポンスを受けたら `new_contract_id`/`new_psp_subscription_ref`/状態=`SWITCHING` を1トランザクションで保存
4. 手順3が失敗（DB落ち等）した場合、リトライは**再度同じ `handoverRequestId` で手順2から**始める。Idempotency-Key が同一のため Stripe 側は新規サブスクを二重作成せず、直前の成功レスポンスをそのまま返す（Stripe 公式: 「同一キーの再試行は最初のレスポンスを返す」）
5. これにより「Stripe には作られたが DB に無い」状態が発生しても、次回同じキーでの呼び出しで整合を回復できる

### 3.5 「旧解約失敗」の補償

- 旧解約 (`cancelAtPeriodEnd`) 呼び出しが失敗した場合、状態を `PARTIALLY_COMPLETED` にし、`old_contract_id` を「解約未確認」として retry queue（既存の `findPurgedPaidSubscriptionRefsPendingStripeCancel` と同型のクエリを本テーブル用に新設）に載せる
- 新サブスクは既に active であり利用者影響は無いため、`PARTIALLY_COMPLETED` は緊急度低（次回夜次バッチで解消。既存 `BillingPurgeEventListener#retryPurge` と同じリトライ設計思想を踏襲）

### 3.6 webhook 順序逆転への対処

旧サブスクの `customer.subscription.deleted`／`customer.subscription.updated`(cancel_at_period_end) webhook が、新サブスク作成の webhook より **先に** 届くケースがあり得る（ネットワーク遅延・Stripe 側キューイング）。

- 各 webhook ハンドラは `subscription_ref`（Stripe Subscription ID）をキーに `billing_contracts` の該当行を更新するだけであり、`billing_payer_handover_requests` の状態遷移とは疎結合にする
- 旧サブスクの webhook は「旧 `billing_contracts` 行の status 更新」のみ行い、`billing_payer_handover_requests` の状態は **アプリ側の同期呼び出し結果（§3.4/3.5）でのみ** 進める。webhook 到達順序に引継状態機械の正当性を依存させない設計とする
- 既存の `BillingWebhookService`（webhook 冪等性: `stripe_event_id` の一意制約で重複処理防止、実装済み）をそのまま流用する前提。新規 webhook 種別追加は無し（新旧いずれも既存の `customer.subscription.*` イベントで完結する）

---

## §4. DDL 最小案

### 4.1 `billing_contracts` への payer 明示列追加

現状 `createdBy`（`created_by`）を purge 判定に流用しているのが 1.2 の根本原因。**`created_by` の意味は変えない**（「誰が作成操作をしたか」の監査記録として維持）。新たに「現在の請求担当者」を表す列を追加する。

```sql
ALTER TABLE billing_contracts
    ADD COLUMN payer_user_id BIGINT NULL COMMENT '現在この契約の実質決済者（Stripe Customer 紐付け先）。作成時は created_by と同値で初期化し、引継後に更新される' AFTER created_by;

-- 既存行のバックフィル（作成時点では payer = 作成者）
UPDATE billing_contracts SET payer_user_id = created_by WHERE payer_user_id IS NULL;

CREATE INDEX idx_billing_contracts_payer ON billing_contracts (payer_user_id, scope_kind, status);
```

- `payer_user_id` を `NULL` 許容にする理由: USER スコープ契約は「契約者本人＝payer」が自明なため `payer_user_id` は運用上省略可（クロスドメイン FK 禁止のため user 側への参照整合はアプリ層で保証。§7 節でも USER スコープはこの列を必須にしない方針を明記）
- purge 判定は `cancelAllUserContractsForPurge` の対象クエリを `scopeKind=USER` 固定から `payer_user_id = :userId OR (scope_kind = USER AND scope_id = :userId)` に拡張する（§5）

### 4.2 引継要求テーブル新設

```sql
CREATE TABLE billing_payer_handover_requests (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    old_contract_id BINARY(16) NOT NULL COMMENT '引継元 billing_contracts.id',
    new_contract_id BINARY(16) NULL COMMENT '引継先 billing_contracts.id（SWITCHING 以降で確定）',
    scope_kind VARCHAR(8) NOT NULL COMMENT 'TEAM / ORG',
    scope_id BIGINT NOT NULL,
    old_payer_user_id BIGINT NOT NULL COMMENT '退会予定・引継元の payer',
    new_payer_user_id BIGINT NULL COMMENT '承諾した引継先 ADMIN（ACCEPTED 以降で確定）',
    status VARCHAR(24) NOT NULL COMMENT 'REQUESTED/ACCEPTED/SWITCHING/COMPLETED/PARTIALLY_COMPLETED/FAILED/EXPIRED',
    requested_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL COMMENT '既定 requested_at + 14日。期限内未引継は期末解約へフォールバック',
    accepted_at DATETIME NULL,
    completed_at DATETIME NULL,
    new_psp_subscription_ref VARCHAR(64) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bphr_old_contract_open (old_contract_id, status) COMMENT '同一契約への複数同時要求防止は部分インデックス相当をアプリ層 SELECT ... FOR UPDATE で担保（MySQL は条件付き UNIQUE 非対応のため）',
    INDEX idx_bphr_scope (scope_kind, scope_id, status),
    INDEX idx_bphr_expires (status, expires_at)
) COMMENT 'billing_contracts の payer（請求担当）引継要求。UuidV7Entity 継承・自ドメイン内完結（クロスドメイン FK 無し）';
```

- クロスドメイン FK 禁止方針（`domain_db_design_principles.md` 原則1）に従い、`old_payer_user_id`/`new_payer_user_id` は auth ドメインへの直接 FK を張らずインデックスのみ
- 複数 ADMIN の同時承諾直列化は `uk_bphr_old_contract_open` の代わりに、アプリ層で `SELECT ... FOR UPDATE` により対象 `old_contract_id` の `status IN (REQUESTED, ACCEPTED, SWITCHING)` 行をロックしてから承諾処理を行う（MySQL 8.0 は部分 UNIQUE INDEX 非対応のため悲観ロックで直列化する。§5.3 参照）

### 4.3 Flyway 方針

- 命名: `V{major}.{yyyyUTCタイムスタンプ}__add_billing_payer_handover.sql`（既存規約どおり）
- major 番号は実装 PR 時点で `origin/main` の最大 major+1 を採番（本設計書では確定しない。実装フェーズで採番）
- 1マイグレーションに `ALTER TABLE billing_contracts` と `CREATE TABLE billing_payer_handover_requests` を同居させず、**別ファイルに分割**（ロールバック単位を独立させるため。既存規約に「1マイグレーション1目的」の明文規定は無いが、他ドメインの慣行に合わせる）

---

## §5. purge 連携

### 5.1 検出

`AccountPurgeService#purgeUser`（既存の30日後物理削除バッチ）の前段、または `WithdrawalRequestedEvent`（Day 0・退会受付時点）のいずれかで、退会予定ユーザーが `payer_user_id` として紐づく TEAM/ORG `billing_contracts`（`status IN (PENDING, ACTIVE, PAST_DUE)`）を検出する。

**検出タイミングは Day 0（退会受付時点）を推奨**: 30日の猶予期間をそのまま引継の交渉期間として使えるため。既存の「退会取消（Day 0〜30 の任意時点で撤回可能）」フローとも整合する（引継が完了する前に退会が撤回されれば、引継要求は `FAILED` 扱いでキャンセルする — AC-9）。

### 5.2 他 ADMIN への引継要求通知

対象スコープ（TEAM/ORG）の他 ADMIN 全員に通知を送る（既存の通知基盤 `NotificationService` 相当を利用。具体実装はドメイン既存パターンに従う）。通知文言には §2.2 で確定した「請求日が変わる」旨を含める。

- 猶予: `requested_at` から 14 日（`account_purge_last_admin_succession.md` の後任 ADMIN 指名フローと揃え、退会30日猶予の枠内に収まる期間として設定）
- 期限内に誰も `ACCEPTED` にしなければ `EXPIRED` とし、purge バッチ実行時点で該当契約を **期末解約**（`cancelAtPeriodEnd`、即時解約ではなく既払い分を無駄にしないため）に倒す。ADMIN が他にいない（最後の ADMIN が payer だった）場合も同じフォールバックとする

### 5.3 引継承諾の認可

- 承諾 API は当該スコープの ADMIN ロールを持つユーザーのみ許可（既存 `billingOperationAuthorizer.requireCanManage(operatorUserId, scopeKind, scopeId)` をそのまま流用）
- 複数 ADMIN が同時に承諾しようとするレース: §4.2 のとおり `old_contract_id` 行を `SELECT ... FOR UPDATE` でロックしてから `status` を確認し、既に `ACCEPTED` 以降であれば `HANDOVER_ALREADY_ACCEPTED`（409）を返す

---

## §6. `membership_subscriptions`（`cancelAllForPayerOnWithdrawal`）

`membership_subscriptions` は既に payer/beneficiary/payee の3分離が完了しているため、`billing_contracts` のような「payer 列の新設」は不要。不足しているのは退会連携メソッドのみ。

### 6.1 仕様

```java
/**
 * GDPR退会時: 指定ユーザーが payer の ACTIVE/PAST_DUE 会費サブスクリプションを全件、
 * 期末解約する（既払い分を無駄にしないため即時解約はしない）。
 */
@Transactional(propagation = Propagation.REQUIRES_NEW)
public List<String> cancelAllForPayerOnWithdrawal(Long payerUserId) { ... }
```

- 対象: `payer_user_id = :payerUserId AND status IN (ACTIVE, PAST_DUE)`（`beneficiary_user_id`/`payee_connect_account_id` は問わない）
- 動作: 契約単位（`membership_subscriptions` 1行ごと）で Stripe `cancel_at_period_end=true` を発行し、DB 側 `status` は `CANCEL_SCHEDULED` 相当（既存 `MembershipSubscriptionStatus` の値に準拠）に更新
- 受益者への通知: 「あなたのメンバーシップは payer の退会に伴い期末（`current_period_end`）で終了します」という文言で `beneficiary_user_id` に通知。受益者自身が新たな payer になりたい場合の導線（引継 UI）は §5 と同型の要求/承諾フローを将来スコープとし、本設計では「通知のみ」を最小スコープとする（AC-12 参照）
- 呼び出し元: `WithdrawalStripeHandler`（1.3 のスタブ）を実装する。`WithdrawalRequestedEvent`（Day 0）購読時点で呼ぶ（`billing_contracts` の引継要求検出（§5.1）と同じタイミングに揃える）

---

## §7. GDPR×会計保持の境界

### 7.1 purge 対象外データの明確化

既存の二段匿名化モデル（`withdrawal_flow_immediate_anonymization_fix.md` §1.3/§13.12）は「即時弱匿名化（Day 0）＋ 30日後強匿名化（Day 30）」の二段構成。本設計は以下を追加する。

| データ | 匿名化タイミング | 理由 |
|---|---|---|
| `billing_contracts.payer_user_id`（旧 payer 分の履歴行） | **匿名化しない（Day 30 でも）**。ただし `payer_user_id` が指す user が既に強匿名化済みであることは `users` 側の状態で表現され、`billing_contracts` 側は user_id をそのまま保持する（クロスドメイン FK 禁止の原則どおり、参照整合はアプリ層） | 会計監査記録として契約履歴・支払者履歴を保持する必要がある（税務・紛争対応）。個人を特定する PII（氏名・メール）は auth ドメイン側で匿名化されるため、`billing_contracts` に残る `user_id` だけでは実質的に個人特定不能（弱参照） |
| `billing_contracts.psp_customer_ref` | **匿名化しない**（Stripe 側 Customer ID の保持自体は PII ではない。Stripe 側での顧客データ削除は別途 Stripe Customer 削除 API の運用対象だが本設計のスコープ外） | Stripe 側の請求記録との突合に必要（会計保持要件） |
| `billing_payer_handover_requests` | Day 30 経過後も削除しない（引継の監査証跡） | 「誰から誰に請求担当が移ったか」は会計上の説明責任に関わる |
| Stripe 側の保持要件 | Stripe は取引記録を独自に保持する（Stripe 利用規約・PCI DSS 準拠のため、Mannschaft 側から削除指示を出す設計にはしない） | 決済事業者としての Stripe 側の法定保存義務はプラットフォーム側で制御しない |

### 7.2 既存二段匿名化モデルとの整合・差分

- 既存モデルは「`users` テーブルの PII 列（氏名・メール等）」を対象にした匿名化であり、`billing_contracts`/`billing_payer_handover_requests` はそもそも既存モデルの匿名化対象に **含まれていない**（既存9ドメインリスナー一覧に billing/payment ドメインは含まれない — `withdrawal_flow_immediate_anonymization_fix.md` §3 の表を実コードで再確認済み: `*AnonymizationEventListener` は auth/favorite/notification/schedule/social/village/weather/scopefolder/chart の9ドメインのみ）
- 本設計が追加する差分は「**payer 列を追加したことで、匿名化されない会計記録に user_id 参照が新たに増える**」点のみ。これは既存の `created_by` が既に同じ性質（匿名化対象外の user_id 参照）を持っていたため、**新規のリスクではなく既存パターンの踏襲**である
- Stripe 側の PaymentMethod/Customer 自体の削除は、既存の（本設計の対象外の）決済ドメインの匿名化フローに委ねる。本設計は「契約が payer の Customer に紐付いたまま残り続けること」を防ぐのが目的であり、Customer 自体の削除タイミングには立ち入らない

---

## §8. AC 一覧（PR分割案つき・攻め口5類型の消し込み）

攻め口5類型: **(a) 二重課金/二重サブスク (b) 権限昇格・IDOR (c) データ不整合(宙ぶらりん) (d) レース/冪等性破れ (e) 通知・UX欠落**

| AC番号 | 内容 | 攻め口 | 検証方法 |
|---|---|---|---|
| AC-1 | `billing_contracts.payer_user_id` 追加後、既存行は `created_by` と同値でバックフィルされる | (c) | Flyway 適用後の SELECT で NULL 行が無いことを確認 |
| AC-2 | 新規 TEAM/ORG 契約作成時、`payer_user_id` は `created_by` と同値で初期化される | (c) | IT: `createContractInternal` 呼び出し後の `payer_user_id` アサーション |
| AC-3 | `cancelAllUserContractsForPurge` は自スコープ契約（`scope_kind=USER`）に加え、`payer_user_id` が一致する TEAM/ORG 契約も検出できる（解約対象になるかは AC-6/AC-8 のフォールバック経路に従う） | (c) | IT: TEAM 契約の payer を退会ユーザーに設定 → purge 対象クエリに含まれることを確認 |
| AC-4 | 同一 `handoverRequestId` で新サブスク作成 API を2回呼んでも Stripe 側には1つのサブスクしか作られない（Idempotency-Key 一致） | (a)(d) | IT: Stripe テストモードで同一キー2回呼び出し → `subscription.id` が同一であることを確認 |
| AC-5 | 新サブスクが `active` になったことを確認する前に旧サブスクは解約されない | (a) | IT: 新サブスク作成失敗をモックし、旧サブスクの `cancel_at_period_end` が呼ばれていないことを確認 |
| AC-6 | 引継要求が期限（14日）内に `ACCEPTED` にならなければ `EXPIRED` となり、purge バッチが対象契約を期末解約する | (c)(e) | IT: `expires_at` を過去に設定したフィクスチャで purge バッチ実行 → 契約が `cancel_at_period_end` 状態になることを確認 |
| AC-7 | 引継承諾 UI/通知に「請求日・初回課金額が変わる場合がある」旨の文言が含まれる（i18n 6言語対応） | (e) | FE: 通知テンプレート・確認ダイアログの文言レビュー＋ロケールファイル存在確認 |
| AC-8 | 対象スコープに他 ADMIN が存在しない場合、引継要求は発行されず即座に期末解約フォールバックへ倒れる | (c) | IT: ADMIN 1名のみのチームで退会 → 通知が発行されず契約が期末解約対象になることを確認 |
| AC-9 | 退会取消（Day 0〜30 の任意時点）が発生した場合、進行中の引継要求（`REQUESTED`/`ACCEPTED`）は `FAILED` として打ち切られる | (c) | IT: 退会取消 API 呼び出し後、`billing_payer_handover_requests.status` が `FAILED` になることを確認 |
| AC-10 | 引継承諾 API は当該スコープの ADMIN 以外を 403 で拒否する（IDOR 防止） | (b) | IT: 非 ADMIN ユーザーでの承諾 API 呼び出しが 403 になることを確認 |
| AC-11 | 複数 ADMIN が同時に承諾操作を行っても、`billing_payer_handover_requests` の状態遷移は1回のみ有効になり、2件目は 409（`HANDOVER_ALREADY_ACCEPTED`）を返す | (d) | IT: 2並列リクエストを模擬し、片方が 409 になることを確認（`SELECT ... FOR UPDATE` の直列化検証） |
| AC-12 | `MembershipSubscriptionService#cancelAllForPayerOnWithdrawal` は `payer_user_id` 一致かつ `status IN (ACTIVE, PAST_DUE)` の契約のみ期末解約し、受益者へ通知する | (c)(e) | IT: payer/beneficiary が異なるフィクスチャで退会イベント発火 → 対象契約のみ解約されることと通知発火を確認 |
| AC-13 | `WithdrawalStripeHandler` は `billing_contracts`（payer一致）と `membership_subscriptions`（payer一致）の両方を処理する（旧 `TeamSubscriptionEntity` 参照は撤去） | (c) | IT: 旧クラス参照が無いことをコンパイル時に保証＋新経路の呼び出しをモック検証 |
| AC-14 | webhook の到達順序が逆転しても（旧サブスク解約 webhook が新サブスク作成 webhook より先着）、`billing_payer_handover_requests` の状態機械は不整合な状態に遷移しない | (d) | IT: webhook を意図的に逆順で投入し、最終状態が正しいことを確認 |
| AC-15 | 強匿名化（Day 30）後も `billing_contracts.payer_user_id`／`billing_payer_handover_requests` の行は削除されない（会計記録保持） | (c) | IT: `AccountPurgeService#purgeUser` 実行後、対象テーブルの行数が変化しないことを確認 |

### PR 分割案

1. **PR-1（DDL＋読み取り専用の土台）**: `payer_user_id` 列追加・バックフィル・`billing_payer_handover_requests` テーブル新設（AC-1, AC-2, AC-15 の一部）
2. **PR-2（BillingContractService 拡張）**: purge 検出クエリ拡張・引継要求/承諾 API・状態機械・Idempotency-Key 対応（AC-3〜AC-11）
3. **PR-3（membership_subscriptions 連携＋WithdrawalStripeHandler 実装）**: `cancelAllForPayerOnWithdrawal` 新設・`WithdrawalStripeHandler` の実装（旧参照撤去）（AC-12, AC-13）
4. **PR-4（webhook 順序耐性の仕上げ・監視）**: webhook 逆順対応・`SWITCHING` 詰まり監視アラート（AC-14）

各 PR は BE テスト先行（CLAUDE.md「BE/API はテスト先行」原則）。PR-1 は DDL のみのためマイグレーション適用確認 IT を先行させる。

---

## §9. 未決事項（実装フェーズで確定させる）

- 通知基盤の具体的な実装クラス（既存の `NotificationService` 系のどれを使うか）は実装フェーズで家老が偵察して決定する
- 猶予期間14日は暫定値。マスターの最終承認時に法務・UX観点で調整余地あり
- `membership_subscriptions` の受益者向け「引継 UI」（受益者自身が新 payer になる導線）は本設計ではスコープ外とし、通知のみ実装する。将来必要になった場合は §5 の TEAM/ORG 引継フローと同型で拡張する
