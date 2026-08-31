# F20.1 — 02 API設計

> **2026-08-31 改訂**: 実決済 API、月次日割り、upgrade/downgrade、月末解約の撤回、invoice/Portal は [05_billing_center.md §3〜§7](05_billing_center.md#3-暦月stripe-の正準) を正本とする。本書の旧 Checkout/customer、`checkout.session.completed` での ACTIVE 化、有償 changePlan 拒否は削除して同書に置換する。

> **旧 route 互換境界（現役仕様ではない）**: 次の`BillingContractController`実在routeは新規UI/APIが使わない互換層であり、全routeを専用`BillingAccessGuard.manage=true`（USER本人、TEAM/ORGのADMIN又はscope対応課金permissionを明示付与されたDEPUTY）で認可する。MEMBER/未許可DEPUTYは403である。`POST /api/v1/{me|teams/{id}|organizations/{id}}/billing/contracts`は**無償ADDONだけ**を継続し、scope/feature/price-bandをserver再検証して直ちに無償契約を作る。有償PLAN/ADDONは状態変更なしの`409 ENTITLEMENT_026 (FLOW_REQUIRED)`を返し、必ず`POST /api/v1/me/billing/quotes → POST /api/v1/me/billing/checkout-sessions`へ誘導する。旧`PUT .../contracts/{id}`は既存`ENTITLEMENT_017`の互換409、旧`DELETE .../contracts/{id}`は無償のみ既存即時取消を継続し、有償は新`POST /api/v1/me/billing/contracts/{id}/cancel`のcontract-operation Sagaへ内部委譲する（同じidempotency/lease/監査を必須、委譲不能なら410）。旧routeは新Flowを迂回してPrice/Stripe/権利を直接更新してはならない。

| 旧 route | 条件 | 確定応答/委譲先 | E2E |
|---|---|---|---|
| POST me/team/org contracts | 無償ADDON | 201、既存無償entitlement作成 | USER/ADMIN/許可DEPUTY、重複409 |
| POST me/team/org contracts | 有償PLAN/ADDON | 409 `ENTITLEMENT_026` (`FLOW_REQUIRED`)、quote APIへ | Price/Stripe/pointer不変 |
| PUT me/team/org contracts/{id} | 全て | 409 `ENTITLEMENT_017`、changes preview/changeへ | downgradeも旧権利不変 |
| DELETE me/team/org contracts/{id} | 無償 | 200、既存即時取消 | entitlement revoke |
| DELETE me/team/org contracts/{id} | 有償 | 新cancel Sagaへ委譲、成功200/進行202、委譲不能410 | legacy/new同時・IDOR・期末 |

> **ステータス**: 🟢 設計完了（マスター御裁可済・実装待ち／営利自動切替・オーナー変更は Phase 2 保留）
> **⚠️ Phase 2 保留（マスター 2026-07-08）**: §7 の org_type イベント（営利自動切替）は初期スコープ外（README §3.3・冒頭 Phase 2 保留ブロック）。権利判定・契約/アドオン・シスアド CRUD は初期スコープに残る。
> 権利判定（`isEntitled`/`EntitlementGuard`）・プランカタログ・契約/アドオン・シスアド CRUD・org_type イベント（**§7・Phase 2 保留**）を定義する。認可の詳細は [03_security](03_security.md)、DDL は [01_data_model](01_data_model.md)。

---

## 0. 共通

- ベースパス: `/api/v1`
- レスポンス封筒: `ApiResponse<T>`（既存規約）
- 日時は ISO-8601（`2026-07-08T12:00:00`）。金額は円整数（JPY 固定）。
- `scopeKind` の API 表現は文字列 `"USER" | "TEAM" | "ORG"`（payment `ScopeKind` と同値）。
- **契約作成/変更 API は Phase 1 から `Idempotency-Key` ヘッダ必須**（M-1）: 決済は無くても**権利発行の二重押下**を防ぐため。サーバは同一キーの再送を検出したら既存結果を返す（Valkey に `billing:idem:{userId}:{key}` を短期保存＝最初の応答の contractId を保持し、TTL 24 時間）。一意性の DB 担保は `active_contract_pointers`（01 §3.1.1）、二重送信の吸収は本冪等キー、最終 backstop は `uk_ent_grant` の 3 層（01 §3.2 設計判断 2）。
  - **冪等キーの check-then-set は非原子（L2・限界の明示）**: Valkey の「見てから書く」は同時再送に対して原子的でない（両リクエストが「未登録」と判定して両方 INSERT へ進みうる）。よって**冪等キーは時間差リトライ（ネットワーク再送・ユーザー再押下）の吸収に限定**し、**厳密な同時性は `active_contract_pointers.uk_acp_slot` が backstop**する（同時 2 INSERT の一方が 409）。**完全同時の再送で片方が `ENTITLEMENT_006`(409) になるのは許容仕様**（FE は 409 を「既に契約済み」として扱い再取得・AC-30）。原子化が必要なら Valkey `SET NX` を採るが、本用途では uk backstop で十分。
- **Controller の認可は `@PreAuthorize` を入口に置く**（03 §2 の逐語パターン）。

---

## 1. サービス層の中核

### 1.1 `EntitlementQueryService.isEntitled(...)`（判定の単一実装）

```java
@Service
public class EntitlementQueryService {

    /**
     * 権利判定の正準実装（README §3.1 の判定式）。全ゲートはこの1本を通る。
     * キャッシュキーの enum は name() で String 化（feedback_cacheable_enum_key_redis）。
     */
    @Cacheable(value = "entitlement:check",
               key = "#scopeKind.name() + ':' + #scopeId + ':' + #featureKey")
    public boolean isEntitled(EntitlementScopeKind scopeKind, Long scopeId, String featureKey) {
        // now はキャッシュキーに含めない（TTL 60 秒の粒度で評価。境界厳密性は AC-16 の許容範囲）
        LocalDateTime now = LocalDateTime.now(clock);   // Clock 注入（date-pin テスト可能に）
        FeatureCatalogEntity feature = featureCatalogRepository.findById(featureKey).orElse(null);
        if (feature == null || !feature.isEnabled()) {
            log.warn("isEntitled: unknown/disabled feature_key={} scope={}:{}", featureKey, scopeKind, scopeId);
            return false;                               // fail-safe: 不明キーは拒否（AC-18）
        }
        if (planFeatureRepository.existsByPlanKeyAndFeatureKey("FREE", featureKey)) {
            return true;                                // FREE 掲載機能（AC-05）
        }
        if (feature.isFreeForNonprofit()
                && scopeClassificationService.isNonProfitScope(scopeKind, scopeId)) {
            return true;                                // 非営利無料枠（機構・初期値は全 FALSE）
        }
        // ★isNonProfitScope の scope 別分岐（E）:
        //   USER  → 常に false（個人に営利/非営利の区分は無い。将来も個人無料枠は free_for_nonprofit では表現しない）
        //   ORG   → organizations.org_type が非営利系（NPO/ASSOCIATION/COMMUNITY/OTHER のうち R-1 で確定した集合）
        //   TEAM  → 所属 ACTIVE 組織のいずれかが非営利なら非営利扱い（無所属チームは非営利扱い・README §3.3 / R-2）
        //   初期は free_for_nonprofit 全 FALSE ゆえ本分岐は初期 E2E で到達しないが、USER 既定 false を明示して将来の詰まりを予防。
        return entitlementRepository.existsActive(scopeKind.name(), scopeId, featureKey, now);
        // 01 §3.3 の判定クエリ（半開区間 [valid_from, valid_until)・revoked_at IS NULL）
    }
}
```

### 1.2 `EntitlementGuard.require(...)`（BE ゲート）

```java
@Component
public class EntitlementGuard {

    /** 未充足なら 402/403 の BusinessException を投げる。FE のみのペイウォールは禁止（BE ゲート必須）。 */
    public void require(EntitlementScopeKind scopeKind, Long scopeId, String featureKey) {
        if (entitlementQueryService.isEntitled(scopeKind, scopeId, featureKey)) {
            return;
        }
        FeatureCatalogEntity feature = featureCatalogRepository.findById(featureKey).orElse(null);
        boolean purchasable = feature != null && feature.isEnabled()
                && (feature.isAddonAvailable()
                    || planFeatureRepository.existsPurchasablePlanContaining(featureKey)); // enabled な非FREEプランに掲載
        if (purchasable) {
            throw new BusinessException(EntitlementErrorCode.FEATURE_NOT_ENTITLED);       // → 402（AC-09）
        }
        throw new BusinessException(EntitlementErrorCode.FEATURE_FORBIDDEN_FOR_SCOPE);    // → 403（AC-09/18）
    }
}
```

- 402 の `ApiResponse` エラー詳細には**購入導線情報**を含める: `details: { featureKey, addonAvailable, addonPriceJpy?, plansContaining: ["BASIC","FULL"] }`（FE がペイウォールモーダルを出すための最小情報・04 §2）。
- **F12.2 フラグは見ない**（責務分離・README §4.4。kill switch は各機能入口で別途評価）。

### 1.3 `hasPaidPlan` の内部委譲（Expand 期・README §4.1）

```java
// TeamPlanService（シグネチャ・@Cacheable("teamPlan") は不変）
public boolean hasPaidPlan(Long teamId) {
    return teamSubscriptionRepository.hasActivePaidPlan(teamId)                       // 既存判定
        || entitlementQueryService.isEntitled(EntitlementScopeKind.TEAM, teamId,
               FeatureKeys.LEGACY_PAID_PLAN_BUNDLE);                                  // OR 委譲（Expand）
}
```

---

## 2. プランカタログ（利用者向け・読み取り）

### 2.1 プラン一覧

```
GET /api/v1/billing/plans
認可: 認証ユーザー（permitAll にしない。カタログは営業情報だが未認証公開の必要なし）
```

レスポンス `PlanCatalogResponse`:

| フィールド | 型 | null | 例 |
|---|---|---|---|
| `plans` | `PlanItem[]` | 不可 | — |
| `plans[].planKey` | string | 不可 | `"FULL"` |
| `plans[].displayNameKey` | string | 不可 | `"billing.plans.full.name"`（FE が $t で解決） |
| `plans[].descriptionKey` | string | 不可 | `"billing.plans.full.description"` |
| `plans[].baseMonthlyPriceJpy` | number | **可**（未定） | `2000` |
| `plans[].features` | `FeatureItem[]` | 不可 | — |
| `plans[].priceBands` | `PriceBand[]` | 不可（空配列可） | — |
| `plans[].priceBands[].scopeKind` | string | 不可 | `"TEAM"` |
| `plans[].priceBands[].bandNo` | number | 不可 | `2` |
| `plans[].priceBands[].minMembers` | number | 不可 | `21` |
| `plans[].priceBands[].maxMembers` | number | **可**（無制限） | `50` |
| `plans[].priceBands[].monthlyPriceJpy` | number | **可**（未定） | `null` |

`FeatureItem`（共通・以降の API でも使用）:

| フィールド | 型 | null | 例 |
|---|---|---|---|
| `featureKey` | string | 不可 | `"reservation.notification_recipients_extended"` |
| `category` | string | 不可 | `"INTERNAL"` \| `"REVENUE"` |
| `addonAvailable` | boolean | 不可 | `true` |
| `addonPriceJpy` | number | **可**（未定） | `300` |
| `displayNameKey` | string | 不可 | `"billing.features.reservation_notification_recipients_extended.name"` |
| `descriptionKey` | string | 不可 | 同 `.description` |
| `sortOrder` | number | 不可 | `10` |

- `enabled=false` のプラン・機能は返さない。`legacy.paid_plan_bundle` は**内部ブリッジ用途のため一覧から除外**する（`sort_order=-1` を除外条件にせず、feature_key 明示除外のホワイトリスト運用はしない。**`display` 除外は `feature_catalog.enabled` とは別の `visible_in_catalog BOOLEAN NOT NULL DEFAULT TRUE` 列で制御**…とはせず、シンプルに **`legacy.` プレフィックスをカタログ表示除外**とする規約で固定する）。

### 2.2 スコープの権利サマリ（現在の契約と有効機能）

```
GET /api/v1/me/entitlements                       # USER スコープ（scopeId=自分）
GET /api/v1/teams/{teamId}/entitlements           # TEAM スコープ
GET /api/v1/organizations/{orgId}/entitlements    # ORG スコープ
認可: me=認証ユーザー / teams=当該チームのメンバー以上 / organizations=当該組織のメンバー以上
      （既存 entitlements 閲覧だけはメンバー可。financial billing UI/API の契約変更・金額・invoice・Portal は `BillingAccessGuard.manage=true`、すなわちADMIN又はscope一致の対応billing permissionを明示付与されたDEPUTYだけ。MEMBER/未許可DEPUTYは403＝05 §2）
```

レスポンス `EntitlementSummaryResponse`:

| フィールド | 型 | null | 例 |
|---|---|---|---|
| `scopeKind` | string | 不可 | `"TEAM"` |
| `scopeId` | number | 不可 | `123` |
| `activePlan` | `ActiveContract` | **可**（無契約） | — |
| `activePlan.contractId` | string(UUID) | 不可 | `"0198..."` |
| `activePlan.planKey` | string | 不可 | `"FULL"` |
| `activePlan.contractedAt` | string(ISO-8601) | 不可 | `"2026-07-01T10:00:00"` |
| `activePlan.priceJpySnapshot` | number | **可**（ベータ無償） | `null` |
| `activeAddons` | `ActiveContract[]`（`featureKey` 付き） | 不可（空配列可） | — |
| `entitledFeatures` | `EntitledFeature[]` | 不可 | — |
| `entitledFeatures[].featureKey` | string | 不可 | `"ads.hide"` |
| `entitledFeatures[].sourceKind` | string | 不可 | `"PLAN"` \| `"ADDON"` \| `"BETA_GRANT"` \| **`"FREE"`** \| **`"NONPROFIT_FREE"`** |
| `entitledFeatures[].validUntil` | string(ISO-8601) | **可**（無期限・virtual は常に null） | `"2028-07-01T00:00:00"` |

**virtual feature の合成（M-2・UI と `isEntitled` の一致）**: `entitledFeatures` は `entitlements` 行だけを返すと、**FREE プラン掲載機能**（契約行なし）と**非営利無料枠**（`free_for_nonprofit=true`）が UI の「利用できる機能」に現れず、`isEntitled=true` なのに一覧に出ない不整合が生じる。これを防ぐため、サマリ生成は次を**合成**する:
- entitlements 行由来（`sourceKind ∈ {PLAN, ADDON, BETA_GRANT}`・`validUntil` は行の値）
- **FREE 掲載機能を `sourceKind="FREE"`・`validUntil=null` の virtual エントリとして追加**（`plan_features('FREE')` 全件）
- スコープが非営利かつ `free_for_nonprofit=true` の機能を **`sourceKind="NONPROFIT_FREE"`・`validUntil=null`** の virtual エントリとして追加
- feature_key で重複排除（実 entitlement を virtual より優先）。これで**「利用できる機能」一覧＝`isEntitled=true` となる feature_key 集合**が保証される（AC-23）。

### 2.3 単一機能の判定（FE ゲート補助・BE が正）

```
GET /api/v1/billing/entitlements/check?scopeKind=TEAM&scopeId=123&featureKey=ads.hide
認可: 当該スコープのメンバー以上（USER は本人のみ）。他スコープの権利有無の探索を防ぐ（03 §2）
```

レスポンス `EntitlementCheckResponse`: `{ entitled: boolean, featureKey: string, purchasable: boolean, addonPriceJpy: number|null, plansContaining: string[] }`
— FE の表示出し分け専用。**FE がこの結果だけで機能を解放してはならない**（BE の `EntitlementGuard` が常に正・03 §4）。

---

## 3. 旧契約 API の互換詳細（現役の新規導線ではない）

### 3.1 プラン契約

```
POST /api/v1/me/billing/contracts                        # USER スコープ
POST /api/v1/teams/{teamId}/billing/contracts            # TEAM スコープ
POST /api/v1/organizations/{orgId}/billing/contracts     # ORG スコープ
認可: me=本人 / teams=@billingAccessGuard.canManage(authentication, #teamId, 'TEAM')
      / organizations=@billingAccessGuard.canManage(authentication, #orgId, 'ORGANIZATION')（`BillingAccessGuard.manage=true`。ADMIN又はscope対応課金permissionを明示付与されたDEPUTYのみ、03 §2）
```

リクエスト `CreateContractRequest`:

| フィールド | 型 | 必須 | 例 | 検証 |
|---|---|---|---|---|
| `contractKind` | string | ✔ | `"PLAN"` | `"PLAN" \| "ADDON"` 以外は 400 |
| `planKey` | string | PLAN 時✔ | `"FULL"` | `plans.enabled=true` に実在。無ければ `ENTITLEMENT_001` 404 |
| `featureKey` | string | ADDON 時✔ | `"ads.hide"` | `feature_catalog.enabled=true` かつ `addon_available=true`。無ければ `ENTITLEMENT_002` 404／addon 不可は `ENTITLEMENT_008` 422 |

> ADDON 契約も同型（`active_contract_pointers` に `contract_kind='ADDON'`・`addon_feature_key=featureKey` で INSERT。`uk_acp_slot` が同一 scope×feature の二重 ADDON を物理拒否＝`ENTITLEMENT_006` 409）。REVENUE な feature の ADDON 契約は §7 イベントを発火（`sourceKind=ADDON`）**【Phase 2 保留・初期スコープでは発火しない】**（§7 冒頭）。

この旧 `BillingContractController` の新規有償契約擬似コードは廃止する。現役の有償PLAN/ADDONは、認可済み `POST /api/v1/me/billing/quotes` でサーバーがscope-owned Customer、人数、税、ACTIVE price band、暦月期間を再計算し、確認済みquoteを一回だけ `POST /api/v1/me/billing/checkout-sessions` へ渡す。Checkoutはscope-owned Customerとband Stripe Priceだけを使い、`invoice.paid` が唯一のACTIVE/権利確定点である。無償ADDONだけは本書冒頭の旧POST互換表に従う。旧POST/PUT/DELETEは新しい価格・Checkout・Stripe・権利更新を直接実行しない。

旧 `ContractResponse`（特に`checkoutUrl`を契約POSTが返す形）は廃止する。現役response型は05 §7の `Quote`、`CheckoutSession`、discriminated `Contract`、`InvoiceSummary`/`InvoiceDetail` を正本とする。

### 3.2 解約

現役の有償取消/撤回は `POST /api/v1/me/billing/contracts/{id}/cancel` と `DELETE /api/v1/me/billing/contracts/{id}/cancel` のcontract-operation Sagaだけで行う（05 §7）。終了日・影響を一度だけ確認して`cancel_at_period_end`を予約し、期末まで同じ契約だけ撤回できる。旧DELETEは本書冒頭の互換表どおり、無償ADDONのみ既存即時取消を継続し、有償は同一lease/idempotency/監査を使う新Sagaへ委譲するか410である。

### 3.3 プラン変更

旧 `PUT /api/v1/{me|teams/{id}|organizations/{id}}/billing/contracts/{id}` は全条件で`409 ENTITLEMENT_017`を返す互換APIであり、契約、pointer、Stripe、entitlements、cacheを変更しない。現役の変更は `POST /api/v1/me/billing/contracts/{id}/change-previews` と `POST /api/v1/me/billing/contracts/{id}/changes` である。upgradeは即時日割りの`invoice.paid`、downgradeは翌月1日のSchedule照合まで旧entitlementを保持してから、contract bandとentitlementsを原子的に切り替える（05 §3/§4/§7）。

### 3.4 決済 Webhook（2026-07-10 実決済・D-2）

エンドポイントは既存の `POST /api/v1/webhooks/stripe`（platform・署名検証は既存 `StripeWebhookController`/`StripePaymentProvider.constructEvent` のまま・AC-39）。`StripeWebhookService` のルーティングに billing 分岐を追加した（`MembershipSubscriptionWebhookService` 本体は不変・F08.9 テスト全緑維持）:

| イベント | billing 所有判定 | billing 側処理（`BillingSubscriptionWebhookService`） |
|---|---|---|
| `checkout.session.completed` | `session.metadata.billingContractId` と session.customer/subscription が scope-owned Customer に一致 | `psp_customer_ref`/`psp_subscription_ref`/`current_period_end` を焼付するのみ。**ACTIVE 化・entitlements 発行は禁止**（`invoice.paid` が唯一の確定点） |
| `checkout.session.expired` | 同上 | `PENDING→CANCELLED`＋pointer 物理 DELETE（再挑戦可能に） |
| `invoice.paid` | subscription/customer を contract/billing_customer に二重照合 | `PENDING→ACTIVE` と entitlements 発行、又は有償upgradeの確定、`PAST_DUE→ACTIVE` 回復（05 §4） |
| `invoice.payment_failed` | renewal invoiceだけを対象に同上 | `ACTIVE→PAST_DUE`（**entitlements は触らない**＝既存期末まで利用可、期間は延長しない）。upgrade changeのrequires_action/pending updateは05 §4のREQUIRES_ACTIONを維持 |
| `customer.subscription.deleted` | 同上 | `→EXPIRED`・pointer 物理 DELETE・由来 entitlements revoke・evict（AC-35） |

- **冪等の二層（AC-34）**: 全イベントを既存 `WebhookIdempotencyService`（`stripe_webhook_events` の event_id UNIQUE ゲート・FAILED は再処理可）に通し、さらに各状態遷移メソッドが status 済みチェックで no-op（二重発行ゼロ）。F09.13 の `checkout.session.completed` 経路（ゲート非経由）とは異なり billing は必ずゲートを通す。
- **F08.9 との分離（D-2・AC-38）**: `invoice.*`/`customer.subscription.deleted` は先に billing の `psp_subscription_ref` 逆引きを試みる。miss時はStripe Subscriptionを取得し、metadataのcontractId/scope/customerIdをscope-owned Customerと厳密照合できた場合だけbilling refをbindして処理する。照合不能なものだけ従来どおりmembershipへevent id未消費でfallthroughする（相互no-op）。billing所有の一時失敗は `StripeWebhookRetryableException` として5xx、署名不正は400。
- **失敗の握り潰し禁止**: billing ハンドラ失敗は `markFailed`＋再送出（Stripe at-least-once 再送でリカバリ・F08.9 と同流儀）。

### 3.5 退会 purge 連動（AC-45・03 §8・検分差し戻し2番）

`BillingPurgeEventListener`（billing ドメイン）が退会イベントを購読する:

| イベント | 処理 |
|---|---|
| `WithdrawalRequestedEvent`（申請・猶予開始） | **明示 no-op**（revoke は復活不可のため猶予中は権利維持・01 §10 M-5） |
| `WithdrawalCancelledEvent`（撤回） | **明示 no-op**（権利維持のまま） |
| `AccountPurgedEvent`（確定・30日後物理削除） | `BillingContractService.cancelAllUserContractsForPurge`（**REQUIRES_NEW**・memory の掟）で USER スコープの PENDING/ACTIVE/PAST_DUE 契約を CANCELLED＋pointer 物理 DELETE＋entitlements revoke＋evict → **tx 外**で有償契約の Stripe サブスクを**即時解約**（`Subscription.cancel`・期末解約ではない・退会後の課金継続事故防止） |

- 順序は「DB 確定 → Stripe 即時解約」: Stripe 失敗時も GDPR 上必須の権利失効は完了済み（revoke 済みのため invoice webhook が届いても権利は復活しない）。Stripe 側の課金継続のみ ERROR ログで手動照合に上申する。
- 例外はイベント基盤へ伝播させない（他ドメインの purge リスナーを妨げない・`ChartPurgeEventListener` 前例）。

---

## 4. シスアド運用 API（マスタ CRUD・手動付与）

```
GET/POST/PUT/DELETE /api/v1/system-admin/billing/plans            # plans CRUD（{planKey} 自然キー）
GET/POST/PUT/DELETE /api/v1/system-admin/billing/features         # feature_catalog CRUD（{featureKey}）
PUT                 /api/v1/system-admin/billing/plans/{planKey}/features    # plan_features 一括置換
POST                /api/v1/system-admin/billing/price-revisions  # DRAFT revision+bands作成
GET                 /api/v1/system-admin/billing/price-revisions/{id}
POST                /api/v1/system-admin/billing/price-revisions/{id}/provision
POST                /api/v1/system-admin/billing/price-revisions/{id}/retry-provision
POST                /api/v1/system-admin/billing/price-revisions/{id}/activate
PUT                 /api/v1/system-admin/billing/plans/{planKey}/price-bands # 互換入力。revision Sagaへ委譲し直接更新しない
POST                /api/v1/system-admin/billing/grants           # 手動付与（契約行を作って発行）
GET                 /api/v1/system-admin/billing/contracts?scopeKind=&scopeId=&status=&page=  # 横断検索
認可: @PreAuthorize("hasRole('SYSTEM_ADMIN')")（全 EP・03 §1）
```

- `fee_policies` シスアド CRUD（F22.1 P2-f・`/api/v1/system-admin/fee-policies`）と同じ設計様式（自然キー PATH・`@PreAuthorize` SYSTEM_ADMIN・DTO は `@Builder`）。

| price revision API | request | response | status |
|---|---|---|---|
| `POST /price-revisions` | `Idempotency-Key` + `PriceRevisionCreateRequest` | `PriceRevisionResponse(status='DRAFT')` | 201/400/409 |
| `GET /price-revisions/{id}` | UUIDv7 | `PriceRevisionResponse`（band別Provision state/errorを含む） | 200/404 |
| `POST /price-revisions/{id}/provision` | `Idempotency-Key` + `{lockVersion:int64}` | `PriceRevisionResponse(status='PROVISIONING')` | 202/404/409/502 |
| `POST /price-revisions/{id}/retry-provision` | `Idempotency-Key` + `{lockVersion:int64}` | `PriceRevisionResponse(status='PROVISIONING')` | 202/404/409/502 |
| `POST /price-revisions/{id}/activate` | `Idempotency-Key` + `{lockVersion:int64}` | futureは`SCHEDULED`、即時は`ACTIVE`の`PriceRevisionResponse` | 200/404/409 |
| 旧 `PUT /plans/{planKey}/price-bands` | legacy `PriceBandsReplaceRequest` | 201 revision Saga委譲結果（直接更新なし） | 201/400/409/410 |

すべてSYSTEM_ADMIN限定で、idempotency/`lockVersion`/row lockを必須とする。`catalogRevision`とサーバー採番`revisionNo`は不変で、CAS対象ではない。Provisionの外部Stripe呼出はDBの`PROVISIONING` reservation commit後にのみ行い、Stripe成功後DB失敗はPrice metadataのrevisionId/bandIdで照合して回収する。activateは全band READY以外を409とし、future revisionはSCHEDULED、開始時に旧ACTIVEをRETIRED、新版をACTIVEへ遷移する。旧版を物理更新・削除しない。

- **バリデーション（マスタ整合の一次防御・01 §7）**:
  - `plan_features` 置換: 各 featureKey が `feature_catalog` に実在しなければ 400。
  - `price-bands` 互換入力は `PriceRevisionCreateRequest`（`productKind:'PLAN'|'ADDON'`,`productKey`,`scopeKind`,`effectiveFrom`,`effectiveUntil?`,`bands[]`）に変換し、DRAFT `billing_price_versions`親revision（不変`catalogRevision`/server採番`revisionNo`、可変`lockVersion`）とDRAFT `billing_price_band_versions`子行を一transactionで作る。`PriceBandInput={bandNo:int32,minMembers:int32,maxMembers:int32?,inputAmount:int64,taxBehavior:'INCLUSIVE'|'EXCLUSIVE',taxCode:string}`。taxCodeは必須のTax master snapshotを取得し、JPY端数規則でtax/excluding/includingを導出する。バンドは`band_no`昇順、`min_members = 前band max_members + 1`、最終だけ`max_members=null`、同一product/scopeのeffective interval・人数範囲重複なしをrow lockで検証する。ただしDRAFT/READY future Bは現ACTIVE A open-endedと共存でき、activate transactionがA.effective_until=B.effective_fromを設定する。違反は400/409。clientはStripe Price ref・計算済税額を渡せない。
  - Provision はrevisionごとに非同期で各DRAFT bandのStripe Priceをmetadata照合して作成し、`provisionStatus:'DRAFT'|'PROVISIONING'|'PROVISION_FAILED'|'READY'|'SCHEDULED'|'ACTIVE'|'RETIRED'`、`provisionError?:string`、band別`stripePriceRef?:string`を返す。部分成功はREADYでなくPROVISION_FAILEDのまま、retryは失敗bandだけをidempotency key付きで再実行する。全bandがStripe refと税属性一致した場合だけactivate APIがlockVersion CASし、future BをSCHEDULED、即時BをACTIVEにする。future開始時schedulerがAをRETIRED、BをACTIVEへ遷移し、次のCも同じ境界で予約できる。同時activateは一方409であり、既存契約は保存済bandのまま次周期の価格確定時だけ新ACTIVE revisionを選ぶ。
  - `feature_catalog` 更新: `category=REVENUE` かつ `free_for_nonprofit=true` は 400（README 原則「収益機能は区分問わず有料」）。
  - `plans`/`feature_catalog` の DELETE は**参照中（ACTIVE 契約・plan_features 登録あり）なら 409**（`enabled=false` への運用を促す）。
- 手動付与 `POST grants` Body: `{ scopeKind, scopeId, contractKind, planKey?, featureKey?, note? }` — 処理は §3.1 と同一（`created_by`=シスアド）。ベータ検証・サポート対応用。

---

## 5. 結線先の置換仕様（既存 3 箇所・README §4.2）

| 結線先 | 置換前（実装済） | 置換後 |
|---|---|---|
| `ModuleService.toggleTeamModule` | `module.getRequiresPaidPlan() && !teamPlanService.hasPaidPlan(teamId)` → `TMPL_004` | `if (module.getRequiresPaidPlan()) entitlementGuard.require(TEAM, teamId, FeatureKeys.TEMPLATE_PREMIUM_MODULES)`（エラーは ENTITLEMENT_003=402 へ変更**しない**。既存 `TMPL_004` を維持するため `isEntitled` を if 判定で使い既存例外を投げる＝FE 後方互換） |
| `ReservationNotificationRecipientService.addRecipient` | `count >= FREE_RECIPIENT_LIMIT && !teamPlanService.hasPaidPlan(teamId)` → `RESERVATION_029`(402) | `count >= FREE_RECIPIENT_LIMIT && !entitlementQueryService.isEntitled(TEAM, teamId, FeatureKeys.RESERVATION_NOTIFICATION_RECIPIENTS_EXTENDED)` → **`RESERVATION_029`(402) を不変で維持**（AC-15） |
| F09.19 広告非表示（F09.19 §7.5） | `TeamPlanService.hasPaidPlan(teamId)` を既存メソッドのまま使用（BE ゲートは F09.19.2 系で実装進行中） | Expand 期は hasPaidPlan の OR 委譲で挙動同一。Migrate 期に広告配信判定で `isEntitled(scope..., FeatureKeys.ADS_HIDE)` を直接使用へ置換 |

> **置換の原則**: 既存エラーコード・HTTP ステータス・FE 文言は**変えない**。変えるのは判定の内部実装のみ（Expand 期は hasPaidPlan の OR 委譲だけで挙動同一・置換は Migrate 期）。

---

## 6. DTO 一覧（骨子）

- `PlanCatalogResponse` / `PlanItem` / `FeatureItem` / `PriceBand`（§2.1）
- `EntitlementSummaryResponse` / `ActiveContract` / `EntitledFeature`（§2.2）
- `EntitlementCheckResponse`（§2.3）
- `CreateContractRequest` / `ContractResponse`（§3.1）
- シスアド系: `PlanUpsertRequest { displayNameKey, descriptionKey, baseMonthlyPriceJpy?, sortOrder, enabled }` / `FeatureUpsertRequest { category, addonAvailable, addonPriceJpy?, freeForNonprofit, displayNameKey, descriptionKey, sortOrder, enabled }` / `PlanFeaturesReplaceRequest { featureKeys: string[] }` / `PriceRevisionCreateRequest { productKind,productKey,scopeKind,effectiveFrom,effectiveUntil?,bands:PriceBandInput[] }` / `PriceBandInput { bandNo,minMembers,maxMembers?,inputAmount:int64,taxBehavior:'INCLUSIVE'|'EXCLUSIVE',taxCode:string }` / `PriceRevisionResponse { id:UUIDv7,catalogRevision:string,revisionNo:int64,productKind,productKey,scopeKind,effectiveFrom,effectiveUntil?,status:'DRAFT'|'PROVISIONING'|'PROVISION_FAILED'|'READY'|'SCHEDULED'|'ACTIVE'|'RETIRED',provisionAttempts:int32,lastProvisionErrorCode?:string,bands:PriceBandVersionResponse[],lockVersion:int64 }` / `PriceBandVersionResponse { id:UUIDv7,bandNo,minMembers,maxMembers?,inputAmount:int64,taxBehavior,taxCode,amountExcludingTax:int64,taxAmount:int64,amountIncludingTax:int64,stripePriceRef?:string,status,provisionErrorCode?:string,lockVersion:int64 }` / `ManualGrantRequest`。旧`PriceBandsReplaceRequest`は互換入力だけで新revision Sagaへ委譲し、直接更新DTOではない（§4）。
- Response DTO は `@Builder`・camelCase 1:1・**全 final マルチコンストラクタの Request DTO は `@JsonCreator` 必須**（memory `feedback_dto_all_final_multi_constructor_jackson_no_creators`）。

---

## 7. org_type イベント（営利/非営利の是正・クロスドメイン）【Phase 2 保留・初期スコープ外】

> **【Phase 2 保留】この節（§7 全体＝`RevenueFeatureActivatedEvent` 発火・org_type 自動更新リスナー・確認必須通知・発火点マトリクス・organization/audit ドメイン結線）は営利自動切替に属し、初期実装スコープ外**（マスター 2026-07-08・README 冒頭 Phase 2 保留ブロック／README §3.3）。**理由**=価格は機能の性質に付く設計ゆえ org_type は課金額を変えず自動補正の価値が低い／非営利優遇は信任（F20.2）で担保／機械的な営利認定の法的・心理的リスク。**初期スコープでは org_type は自己申告のまま自動変異せず、契約サービスは本イベントを発火しない**（下記 §3.1 疑似コードの `publishRevenueFeatureActivatedEvent` 呼び出しは Phase 2 で有効化）。設計は Phase 2 でそのまま使うため温存する。関連 AC（AC-11/12/22/22b/24/25/26/27）は README で `[P2]` タグ付き＝初期試練対象外。

### 7.1 イベント定義（billing 発火）

```java
/** スコープ自身の商用行動で REVENUE 機能が有効化された（billing 発火・organization 購読） */
public record RevenueFeatureActivatedEvent(
        EntitlementScopeKind scopeKind,   // 有効化したスコープ
        Long scopeId,
        List<String> revenueFeatureKeys,  // 有効化された REVENUE 機能（category=REVENUE のみ）
        String sourceKind,                // "PLAN" | "ADDON"（商用行動の別。BETA_GRANT は含めない）
        Long operatorUserId) {}
```

**★発火点の再定義（H-5・営利誤認定の是正）**: マスター要求の原意は「**団体自身の商用行動**で営利区分へ切替」である。よって発火は**スコープ自身が REVENUE 機能を能動的に取得した場合に限る**:

- **発火する**: **PLAN 契約購入**・**ADDON 契約購入**のうち、対象に `category=REVENUE` の feature_key が 1 つ以上含まれる場合（＝スコープが自らの意思で有料の収益機能を契約した＝商用行動）。
  - **★発火点は「契約」であり「実利用」ではない（②・乖離を閉じる）**: ベータ特典（BETA_GRANT）で REVENUE 機能を無償で得たスコープが、その機能を**実利用**（ペイウォール開設・実課金発生）しても、本設計では**営利検知しない**（契約イベントが発火点で、実利用イベントは購読しない）。すなわち「ベータ無償で REVENUE 機能を使う NPO」は org_type が非営利のまま残る。これは **H-5 の割り切りの帰結**であり許容する（無償配布で営利変異させない原意を優先）。**実利用（実収益行動）による営利検知は Phase 2 で実収益イベント連携として別途検討**する（本設計スコープ外・🟢 を阻害しない Phase 2 送り）。
- **発火しない（除外）**:
  - **`source_kind=BETA_GRANT`（ベータ特典の付与）**: 運営が**無償で配る**行為であり団体の商用行動ではない。NPO にベータ特典を配っただけで org_type が COMPANY に変異してはならない（**AC-22b 否定 AC**）。F20.3 の付与サービスは本イベントを**発火しない**（§3.1 擬似コードのコメント参照・F20.3 01 §3 発行規約にも「REVENUE イベント非発火」と明記）。
  - **シスアド手動付与（`ManualGrantRequest`）**: 運営操作であり団体の商用行動ではない → **発火しない**（サポート対応で REVENUE 機能を付けても営利変異させない）。
- 発火はコミット後（`@TransactionalEventListener(phase = AFTER_COMMIT)`・リスナーは **`@Transactional(propagation = REQUIRES_NEW)`**・memory `feedback_transactional_event_listener_requires_new`）。

#### 7.0 発火点 × org_type 分岐マトリクス（M-19 統合・検証可能化）

| 起点 | source_kind | scopeKind | 対象 org_type | 結果 | AC |
|---|---|---|---|---|---|
| PLAN/ADDON 契約購入（REVENUE 含む） | PLAN/ADDON | ORG | `NPO`/`ASSOCIATION`/`COMMUNITY`/`OTHER` | org_type→`COMPANY`＋確認必須通知 | AC-11 |
| PLAN/ADDON 契約購入（REVENUE 含む） | PLAN/ADDON | ORG | `GOVERNMENT`/`MUNICIPALITY`/`SCHOOL`/`HOSPITAL` | 更新せず・通知＋運営レビュー | AC-12 |
| PLAN/ADDON 契約購入（REVENUE 含む） | PLAN/ADDON | ORG | `COMPANY` | 何もしない（既に営利） | AC-24 |
| PLAN/ADDON 契約購入（REVENUE 含む） | PLAN/ADDON | TEAM | 所属 ACTIVE 組織 | 組織 org_type は更新せず・所属組織 ADMIN へ通知のみ | AC-22 |
| PLAN/ADDON 契約購入（INTERNAL のみ） | PLAN/ADDON | 任意 | 任意 | イベント不発火（REVENUE 無し） | AC-25 |
| **ベータ特典付与** | **BETA_GRANT** | 任意 | 任意 | **イベント不発火・org_type 不変** | **AC-22b（否定）** |
| **シスアド手動付与** | 手動 | 任意 | 任意 | **イベント不発火・org_type 不変** | AC-26 |
| PLAN/ADDON 契約購入（REVENUE 含む） | PLAN/ADDON | USER | — | 個人は org 区分に影響しない（不発火） | AC-27 |

### 7.2 リスナー処理（organization ドメイン・実シグネチャで焼き込み・B）

> リスナーは **organization ドメイン**に置く（billing は org_type を直接触らない・イベント経由・原則1/5）。宛先解決・通知は **origin/main 実在 API** を使う（下記は実シグネチャに合わせた擬似コード）。

```java
@Component
@RequiredArgsConstructor
public class OrgTypeAutoUpgradeListener {
    private final OrganizationRepository organizationRepository;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final UserRoleRepository userRoleRepository;              // 実在: findAdminUserIdsByOrganizationId
    private final ConfirmableNotificationService confirmableNotificationService;
    private final MessageSource messageSource;                       // messages*.properties を String 解決
    private final AuditLogService auditLogService;

    @TransactionalEventListener(phase = AFTER_COMMIT)
    @Transactional(propagation = REQUIRES_NEW)                       // feedback_transactional_event_listener_requires_new
    public void onRevenueFeatureActivated(RevenueFeatureActivatedEvent ev) {
        List<Long> orgIds = switch (ev.scopeKind()) {
            case ORG  -> List.of(ev.scopeId());
            case TEAM -> teamOrgMembershipRepository.findActiveOrganizationIdsByTeamId(ev.scopeId()); // status='ACTIVE'
            case USER -> List.of();                                  // 個人の REVENUE 契約は組織区分に影響しない
        };
        for (Long orgId : orgIds) {
            OrganizationEntity org = organizationRepository.findById(orgId).orElse(null);
            if (org == null || org.getOrgType() == OrgType.COMPANY) continue;   // 既に営利は何もしない（AC-24）
            List<Long> adminIds = userRoleRepository.findAdminUserIdsByOrganizationId(orgId);  // 実在メソッド
            if (adminIds.isEmpty()) continue;                        // 宛先ゼロは通知不能（send が SEND_FAILED を投げるため事前ガード）
            boolean autoUpgrade = ev.scopeKind() == EntitlementScopeKind.ORG
                    && EnumSet.of(OrgType.NPO, OrgType.ASSOCIATION, OrgType.COMMUNITY, OrgType.OTHER).contains(org.getOrgType());
            if (autoUpgrade) {
                OrgType from = org.getOrgType();
                org.updateOrgType(OrgType.COMPANY);                  // ★organization ドメインに【新設】するメソッド（下記注記）
                auditLogService.record("ORG_TYPE_AUTO_UPDATED", orgId, Map.of("from", from, "to", "COMPANY", "trigger", ev.revenueFeatureKeys()));
                send(adminIds, orgId, "notification.billing.org_type_auto_updated.title",
                                       "notification.billing.org_type_auto_updated.body");
            } else {
                // 公共系（GOVERNMENT/MUNICIPALITY/SCHOOL/HOSPITAL）または TEAM 経由 → 更新せず通知＋運営レビュー（AC-12/22・R-1）
                auditLogService.record("ORG_TYPE_REVIEW_REQUESTED", orgId, Map.of("trigger", ev.revenueFeatureKeys()));
                send(adminIds, orgId, "notification.billing.org_type_review_requested.title",
                                       "notification.billing.org_type_review_requested.body");
            }
        }
    }

    private void send(List<Long> adminIds, Long orgId, String titleKey, String bodyKey) {
        // ★ConfirmableNotificationService.send の実在 overload（title/body は解決済み String を渡す）:
        //   send(ScopeType scopeType, Long scopeId, String title, String body,
        //        ConfirmableNotificationPriority priority, LocalDateTime deadlineAt,
        //        Integer firstReminderMinutes, Integer secondReminderMinutes,
        //        String actionUrl, Long templateId, Long createdByUserId, List<Long> recipientUserIds)
        Locale ja = Locale.JAPANESE;
        confirmableNotificationService.send(
            ScopeType.ORGANIZATION, orgId,
            messageSource.getMessage(titleKey, null, ja),            // ← keys ではなく解決済み文字列を渡す
            messageSource.getMessage(bodyKey, null, ja),
            ConfirmableNotificationPriority.HIGH,
            LocalDateTime.now().plusDays(14),                        // deadlineAt（区分確認の期限・運用値）
            null, null,                                              // reminder は scope 設定の default に委譲
            "/organizations/" + orgId + "/settings",                // actionUrl（区分確認導線）
            null,                                                    // templateId 不要
            null,                                                    // createdByUserId=システム
            adminIds);
    }
}
```

**実物照合で確定した点（B）**:
- **`OrganizationEntity.updateOrgType(OrgType)` は origin/main に存在しない → organization ドメインに【新設】する**。billing からの直接 UPDATE は禁止（原則1）、本メソッドは organization ドメイン内の自 Entity 更新として新設し、リスナーもロールバック API（R-1・03 §7 `ORG_TYPE_REVERTED`）も同ドメインに置く。
- **宛先は実在の `userRoleRepository.findAdminUserIdsByOrganizationId(orgId)`**（role ドメイン・`NotificationCreditService`/`TeamPaymentAdvanceService` 等で使用実績）。
- **`ConfirmableNotificationService.send(...)` は title/body を「解決済み String」で受ける**（i18n キーではなく `MessageSource` で解決してから渡す）。実在 overload は上記 12 引数版（`ScopeType`/`ConfirmableNotificationPriority`/`recipientUserIds` 等）。宛先ゼロは `SEND_FAILED` を投げるため事前に空チェック（`payment_requests` の `PAYMENT_REQUEST_NO_RECIPIENTS` と同様の配慮）。
- **`ORG_TYPE_AUTO_UPDATED`・`ORG_TYPE_REVIEW_REQUESTED`・`ORG_TYPE_REVERTED` は監査アクション名**（`audit_logs` の action 文字列。既存の `AuditEventType` enum に無ければ**追加が必要**＝organization/audit ドメイン側作業）。
- **TEAM→組織 ID 解決は `TeamOrgMembershipRepository.findActiveOrganizationIdsByTeamId(teamId)`（status='ACTIVE'）を新設**（`team_org_memberships` 実在テーブル V2.011）。
- 通知文言キー（`notification.billing.org_type_*`）は BE `messages*.properties` 6 言語に追加（04 §3）。**この分岐（自動更新対象 org_type 集合・ロールバック）は README §8 R-1 = マスター御裁可済 (b)（2026-07-08）**: 自動更新は {NPO, ASSOCIATION, COMMUNITY, OTHER} のみ・公共系は不変で通知＋運営レビュー。上記擬似コードがその確定仕様。

> **⚠️ 実装スコープ注記（B・軍議で足軽担当に含める）【Phase 2 保留】**: 本結線は **2 設計書（billing.beta）の外＝organization/notification/audit ドメインへの実装を要求**する: (1) `OrganizationEntity.updateOrgType` ＋ ロールバック API（R-1）、(2) `TeamOrgMembershipRepository.findActiveOrganizationIdsByTeamId`、(3) 監査アクション `ORG_TYPE_AUTO_UPDATED`/`ORG_TYPE_REVIEW_REQUESTED`/`ORG_TYPE_REVERTED`、(4) `messages*.properties` の通知文言 6 言語。**これらは営利自動切替に属し初期スコープ外＝Phase 2 で実装する**（マスター 2026-07-08・README §3.3/§4.6）。Phase 2 の軍議のタスク分解で足軽の担当範囲に含める（billing ドメインだけ実装して結線先が無い、を防ぐ）。README §4.6 の実装スコープ表にも反映。

---

## 8. キャッシュ戦略

| キャッシュ | value / key | TTL | evict |
|---|---|---|---|
| 権利判定 | `entitlement:check` / `{scopeKind.name()}:{scopeId}:{featureKey}` | **60 秒**（`RedisConfig.cacheManager` に個別登録） | **個別キー evict の 1 方式に確定**（A）。契約/付与/取消サービスが**発行/取消した feature_key 集合を戻り値で返し**、その集合ぶんだけ `key = scopeKind.name()+":"+scopeId+":"+featureKey` を組み立てて `cacheManager.getCache("entitlement:check").evict(key)` を呼ぶ。**`SCAN`+DEL 案・`@CacheEvict` のプレフィックス一括・`allEntries=true` は不採用**（Redis の `@CacheEvict` はプレフィックス一括削除不可・全消しは日次付与バッチ 1 万件でサンダリングヘッド）|
| 有料プラン互換 | `teamPlan` / `#teamId`（既存・変更しない） | 既定 30 分 | 上記と同時に `@CacheEvict(value="teamPlan", key="#scopeId")`（TEAM スコープ変更時） |
| 非営利判定 | `billing:nonprofit` / `{scopeKind.name()}:{scopeId}` | 10 分 | org_type 変更イベントで evict |
| マスタ | `billing:catalog`（プラン一覧の組み立て結果） | 10 分 | シスアド CRUD 時に evict |

- キーの enum は必ず `name()` で String 化（Valkey 直列化事故防止・memory `feedback_cacheable_enum_key_redis`）。
- **evict する feature_key 集合の確定（A）**: 契約作成/変更/取消・付与/取消の各サービスは処理結果として**「発行または取消した feature_key の集合」を返し**、呼び出し側（または同サービス内）がその集合の各キーを個別 evict する。集合は: PLAN 契約＝`plan_features(planKey)`／ADDON＝`{featureKey}`／プラン変更＝旧プラン ∪ 新プランの feature_key／ベータ付与/取消＝`granted_feature_keys`。**AC-16 の観測点「evict 呼び出しの実行」は、この集合ぶんの `evict(key)` が呼ばれたことを指す**（集合が確定しているため検証可能）。
- **日次付与バッチの evict（M-8）**: F20.3 の自動付与バッチは**付与済みユーザーを skip**（新規付与分のみ処理）し、付与時に当該ユーザーの `granted_feature_keys` ぶんだけ個別 evict する（当該ユーザー分のみ）。バッチ完了時の全消し（`allEntries`）はしない。
- **取消の反映保証**: 上記個別 evict を必ず実行（AC-16）。evict 漏れがあっても TTL 60 秒で自然収束するが、TTL 依存の観測は非決定的なので**テストは「evict が呼ばれたこと」を検証**し、TTL 失効は別途単体テストで確認する（M-9・03 §5）。

---

## 9. エラーコード（`EntitlementErrorCode`・新規 enum）

> **採番注記**: `ENTITLEMENT_` プレフィックスは新設（既存 enum に同プレフィックスなし・121 ファイル走査済）。**新規採番は現在の最大+1 で予約、確定はマージ時に再確認**（並行 PR で billing 系コードが増えていないか `git grep "ENTITLEMENT_0"` で照合）。HTTP ステータスは `GlobalExceptionHandler.ERROR_CODE_STATUS_MAP` に**明示登録**する（登録漏れは Severity 既定 400/500 にフォールバックする前科 #1279）。

| enum 値 | コード | HTTP | Severity | 意味 |
|---|---|---|---|---|
| `PLAN_NOT_FOUND` | `ENTITLEMENT_001` | 404 | WARN | 指定 planKey が存在しない/enabled=false |
| `FEATURE_NOT_FOUND` | `ENTITLEMENT_002` | 404 | WARN | 指定 featureKey がカタログに存在しない |
| `FEATURE_NOT_ENTITLED` | `ENTITLEMENT_003` | **402** | WARN | 権利なし・**購入手段あり**（アドオン/上位プラン）。details に購入導線 |
| `FEATURE_FORBIDDEN_FOR_SCOPE` | `ENTITLEMENT_004` | **403** | WARN | 権利なし・購入手段なし（スコープ不適合・カタログ無効含む） |
| `SCOPE_FORBIDDEN` | `ENTITLEMENT_005` | 403 | WARN | scopeId の所有権なし（IDOR・03 §2） |
| `CONTRACT_ALREADY_ACTIVE` | `ENTITLEMENT_006` | 409 | WARN | ACTIVE な PLAN 契約が既に存在（/同一 plan への変更/同一 ADDON 重複） |
| `CONTRACT_NOT_FOUND` | `ENTITLEMENT_007` | 404 | WARN | 契約が存在しない/スコープ不一致（IDOR 秘匿） |
| `ADDON_NOT_AVAILABLE` | `ENTITLEMENT_008` | 422 | WARN | featureKey が addon_available=false |
| `INVALID_SCOPE_KIND` | `ENTITLEMENT_009` | 400 | WARN | scopeKind が USER/TEAM/ORG 以外 |
| `PLAN_MASTER_VALIDATION_FAILED` | `ENTITLEMENT_010` | 400 | WARN | シスアド CRUD のマスタ整合違反（§4 バリデーション） |
| `CONTRACT_NOT_CANCELLABLE` | `ENTITLEMENT_011` | 409 | WARN | 既に CANCELLED/EXPIRED の契約への解約・変更 |
| `PLAN_MASTER_IN_USE` | `ENTITLEMENT_012` | 409 | WARN | 参照中マスタの DELETE（enabled=false を案内） |
| `DUPLICATE_ENTITLEMENT` | `ENTITLEMENT_013` | 409 | WARN | uk_ent_grant 違反（同一発行元×同時刻の二重発行・AC-21） |
| `INVALID_CONTRACT_KIND` | `ENTITLEMENT_014` | 400 | WARN | contractKind が PLAN/ADDON 以外（P1 実装で追補採番） |
| `CHECKOUT_SESSION_FAILED` | `ENTITLEMENT_015` | **502** | ERROR | Stripe Checkout 生成失敗（2026-07-10 実決済。PENDING 契約は補償済み＝孤児なし） |
| `CONTRACT_PENDING_PAYMENT` | `ENTITLEMENT_016` | 409 | WARN | PENDING（入金前）スロット占有中の再契約（2026-07-10 実決済・AC-32） |
| `CONTRACT_CHANGE_REQUIRES_PAYMENT` | `ENTITLEMENT_017` | 409 | WARN | 旧 `changePlan` API の互換応答。新しい変更 API は使用せず、05 §7 の preview/change API へ移行する |
| `BILLING_FLOW_REQUIRED` | `ENTITLEMENT_026` | 409 | WARN | 旧有償POSTだけがquote→Checkoutを迂回しようとした互換応答。旧PUTは既存017であり、017を流用しない。05 §7の新エラー018〜025と既存017を横断確認した次番号 |

`GlobalExceptionHandler` への追記（設計に含む）:

```java
Map.entry("ENTITLEMENT_001", HttpStatus.NOT_FOUND),
Map.entry("ENTITLEMENT_002", HttpStatus.NOT_FOUND),
Map.entry("ENTITLEMENT_003", HttpStatus.PAYMENT_REQUIRED),   // 402（RESERVATION_029 と同型）
Map.entry("ENTITLEMENT_004", HttpStatus.FORBIDDEN),
Map.entry("ENTITLEMENT_005", HttpStatus.FORBIDDEN),
Map.entry("ENTITLEMENT_006", HttpStatus.CONFLICT),
Map.entry("ENTITLEMENT_007", HttpStatus.NOT_FOUND),
Map.entry("ENTITLEMENT_008", HttpStatus.UNPROCESSABLE_ENTITY),
Map.entry("ENTITLEMENT_009", HttpStatus.BAD_REQUEST),
Map.entry("ENTITLEMENT_010", HttpStatus.BAD_REQUEST),
Map.entry("ENTITLEMENT_011", HttpStatus.CONFLICT),
Map.entry("ENTITLEMENT_012", HttpStatus.CONFLICT),
Map.entry("ENTITLEMENT_013", HttpStatus.CONFLICT),
Map.entry("ENTITLEMENT_014", HttpStatus.BAD_REQUEST),
Map.entry("ENTITLEMENT_015", HttpStatus.BAD_GATEWAY),    // 2026-07-10 実決済
Map.entry("ENTITLEMENT_016", HttpStatus.CONFLICT),       // 2026-07-10 実決済
Map.entry("ENTITLEMENT_017", HttpStatus.CONFLICT),       // AC-44 changePlan 決済ガード
Map.entry("ENTITLEMENT_026", HttpStatus.CONFLICT),       // legacy paid route: FLOW_REQUIRED
```

---

## 10. OpenAPI・生成型

- 新規 DTO には `@Schema(name = "Billing〜")` を付与し**同名 nested schema 衝突を避ける**（memory `feedback_openapi_nested_schema_name_collision`）。
- BE 実装マージ後は `docs/openapi.json` 再生成＋`cd frontend && npm run generate:types` を**同一 PR** で行う（memory `project_openapi_json_chronic_drift`）。
