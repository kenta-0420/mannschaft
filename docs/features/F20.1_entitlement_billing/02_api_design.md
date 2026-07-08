# F20.1 — 02 API設計

> **ステータス**: 🟡 設計中（精査待ち）
> 権利判定（`isEntitled`/`EntitlementGuard`）・プランカタログ・契約/アドオン・シスアド CRUD・org_type イベントを定義する。認可の詳細は [03_security](03_security.md)、DDL は [01_data_model](01_data_model.md)。

---

## 0. 共通

- ベースパス: `/api/v1`
- レスポンス封筒: `ApiResponse<T>`（既存規約）
- 日時は ISO-8601（`2026-07-08T12:00:00`）。金額は円整数（JPY 固定）。
- `scopeKind` の API 表現は文字列 `"USER" | "TEAM" | "ORG"`（payment `ScopeKind` と同値）。
- **契約作成/変更 API は Phase 1 から `Idempotency-Key` ヘッダ必須**（M-1）: 決済は無くても**権利発行の二重押下**を防ぐため。サーバは同一キーの再送を検出したら既存結果を返す（Valkey に `billing:idem:{userId}:{key}` を短期保存＝最初の応答の contractId を保持し、TTL 24 時間）。一意性の DB 担保は `active_contract_pointers`（01 §3.1.1）、二重送信の吸収は本冪等キー、最終 backstop は `uk_ent_grant` の 3 層（01 §3.2 設計判断 2）。
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
      （閲覧はメンバー可・契約変更は ADMIN のみ＝§3。03 §1 認可マトリクス）
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

## 3. 契約 API（PLAN / ADDON）

### 3.1 プラン契約

```
POST /api/v1/me/billing/contracts                        # USER スコープ
POST /api/v1/teams/{teamId}/billing/contracts            # TEAM スコープ
POST /api/v1/organizations/{orgId}/billing/contracts     # ORG スコープ
認可: me=本人 / teams=@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')
      / organizations=@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')（03 §2）
```

リクエスト `CreateContractRequest`:

| フィールド | 型 | 必須 | 例 | 検証 |
|---|---|---|---|---|
| `contractKind` | string | ✔ | `"PLAN"` | `"PLAN" \| "ADDON"` 以外は 400 |
| `planKey` | string | PLAN 時✔ | `"FULL"` | `plans.enabled=true` に実在。無ければ `ENTITLEMENT_001` 404 |
| `featureKey` | string | ADDON 時✔ | `"ads.hide"` | `feature_catalog.enabled=true` かつ `addon_available=true`。無ければ `ENTITLEMENT_002` 404／addon 不可は `ENTITLEMENT_008` 422 |

> ADDON 契約も同型（`active_contract_pointers` に `contract_kind='ADDON'`・`addon_feature_key=featureKey` で INSERT。`uk_acp_slot` が同一 scope×feature の二重 ADDON を物理拒否＝`ENTITLEMENT_006` 409）。REVENUE な feature の ADDON 契約は §7 イベントを発火（`sourceKind=ADDON`）。

処理（PLAN の場合・擬似コード）:

```
@Transactional
createPlanContract(scopeKind, scopeId, planKey, operatorUserId, idempotencyKey):
  if idempotencyKey seen (billing:idem): return 既存結果                         # M-1 二重送信の吸収
  assertScopeOwnership(...)                                   # @PreAuthorize 済みだが Service 層でも再検証（二重防御・03 §2）
  memberCount = activeMemberCount(scopeKind, scopeId)          # 01 §3.4 の既存メソッド再利用（USER=1）
  band = resolveBand(planKey, scopeKind, memberCount)          # TEAM/ORG のみ。バンド未定義なら NULL
  contract = INSERT billing_contracts(PLAN, planKey, status=ACTIVE,
             member_count_snapshot, band_no_snapshot, price_jpy_snapshot=NULL(ベータ), contracted_at=now)
  try:
      INSERT active_contract_pointers(scopeKind, scopeId, PLAN, addon_feature_key='', contract_id=contract.id)
  catch DataIntegrityViolationException (uk_acp_slot):        # H-1 TOCTOU 二重契約を DB が物理拒否
      throw ENTITLEMENT_006                                    # 409（アプリ層 exists チェックのレースを閉じる）
  for featureKey in plan_features(planKey):
      INSERT entitlements(scopeKind, scopeId, featureKey, source_kind=PLAN,
                          source_ref_id=contract.id, valid_from=now, valid_until=NULL)
  evictEntitlementCache(scopeKind, scopeId); evictCache("teamPlan", scopeId if TEAM)
  # ★H-5: BETA_GRANT はここを通らない（発行は F20.3 の付与サービス）。REVENUE イベントは「契約＝商用行動」でのみ発火（§7）
  if plan_features(planKey) に category=REVENUE の機能が含まれる:
      publishRevenueFeatureActivatedEvent(scopeKind, scopeId, revenueFeatureKeys, sourceKind=PLAN)   # §7
  return ContractResponse
```

レスポンス `ContractResponse`:

| フィールド | 型 | null | 例 |
|---|---|---|---|
| `contractId` | string(UUID) | 不可 | `"0198..."` |
| `scopeKind` / `scopeId` | string / number | 不可 | `"TEAM"` / `123` |
| `contractKind` | string | 不可 | `"PLAN"` |
| `planKey` | string | **可**（ADDON 時 null） | `"FULL"` |
| `featureKey` | string | **可**（PLAN 時 null） | `null` |
| `status` | string | 不可 | `"ACTIVE"` |
| `memberCountSnapshot` | number | **可**（USER 時 null） | `34` |
| `bandNoSnapshot` | number | **可** | `2` |
| `priceJpySnapshot` | number | **可**（ベータ中 null＝無償） | `null` |
| `contractedAt` | string(ISO-8601) | 不可 | `"2026-07-08T12:00:00"` |
| `grantedFeatureKeys` | string[] | 不可 | `["ads.hide", ...]` |

### 3.2 解約

```
DELETE /api/v1/me/billing/contracts/{contractId}
DELETE /api/v1/teams/{teamId}/billing/contracts/{contractId}
DELETE /api/v1/organizations/{orgId}/billing/contracts/{contractId}
認可: §3.1 と同一（scope ADMIN）。さらに Service 層で contract.scope == パスの scope を検証
      （不一致は ENTITLEMENT_007 404 秘匿・IDOR 03 §2）
```

- 処理: `status=CANCELLED`＋`cancelled_at=now`、**`active_contract_pointers` の該当行を DELETE**（一意スロットを解放・次回契約を可能に）、由来 entitlements を全件 `revoked_at=now, revoked_by=操作者` に（同一トランザクション・AC-20）。scope キャッシュ evict（M-8）。
- ベータ中は**即時解約**（無償ゆえ期末概念なし）。Phase 2 で期末解約（`cancel_at_period_end` 相当）へ拡張（注記のみ）。
- 既に CANCELLED → `ENTITLEMENT_011` 409。

### 3.3 プラン変更

```
PUT /api/v1/teams/{teamId}/billing/contracts/{contractId}
Body: { "planKey": "FULL" }         # me / organizations も同型
認可: §3.1 と同一
```

- 処理: 単一トランザクションで「旧契約 CANCELLED＋由来 entitlements revoke → 新契約 ACTIVE＋新 entitlements 発行 → **`active_contract_pointers.contract_id` を新契約へ UPDATE**（ポインタは付け替えるだけで行を増やさない）」（AC-19）。同一 planKey への変更は `ENTITLEMENT_006` 409。
- ダウングレード（FULL→BASIC）で対象外となった機能は即 false（キャッシュ evict 込み）。

---

## 4. シスアド運用 API（マスタ CRUD・手動付与）

```
GET/POST/PUT/DELETE /api/v1/system-admin/billing/plans            # plans CRUD（{planKey} 自然キー）
GET/POST/PUT/DELETE /api/v1/system-admin/billing/features         # feature_catalog CRUD（{featureKey}）
PUT                 /api/v1/system-admin/billing/plans/{planKey}/features    # plan_features 一括置換
PUT                 /api/v1/system-admin/billing/plans/{planKey}/price-bands # plan_price_bands 一括置換
POST                /api/v1/system-admin/billing/grants           # 手動付与（契約行を作って発行）
GET                 /api/v1/system-admin/billing/contracts?scopeKind=&scopeId=&status=&page=  # 横断検索
認可: @PreAuthorize("hasRole('SYSTEM_ADMIN')")（全 EP・03 §1）
```

- `fee_policies` シスアド CRUD（F22.1 P2-f・`/api/v1/system-admin/fee-policies`）と同じ設計様式（自然キー PATH・`@PreAuthorize` SYSTEM_ADMIN・DTO は `@Builder`）。
- **バリデーション（マスタ整合の一次防御・01 §7）**:
  - `plan_features` 置換: 各 featureKey が `feature_catalog` に実在しなければ 400。
  - `price-bands` 置換: バンドが `band_no` 昇順で `min_members = 前バンド max_members + 1`・最終バンドのみ `max_members NULL` を許可。違反は 400。
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
- シスアド系: `PlanUpsertRequest { displayNameKey, descriptionKey, baseMonthlyPriceJpy?, sortOrder, enabled }` / `FeatureUpsertRequest { category, addonAvailable, addonPriceJpy?, freeForNonprofit, displayNameKey, descriptionKey, sortOrder, enabled }` / `PlanFeaturesReplaceRequest { featureKeys: string[] }` / `PriceBandsReplaceRequest { bands: [{ scopeKind, bandNo, minMembers, maxMembers?, monthlyPriceJpy? }] }` / `ManualGrantRequest`（§4）
- Response DTO は `@Builder`・camelCase 1:1・**全 final マルチコンストラクタの Request DTO は `@JsonCreator` 必須**（memory `feedback_dto_all_final_multi_constructor_jackson_no_creators`）。

---

## 7. org_type イベント（営利/非営利の是正・クロスドメイン）

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

- **発火する**: **PLAN 契約購入**・**ADDON 契約購入**のうち、対象に `category=REVENUE` の feature_key が 1 つ以上含まれる場合（＝スコープが自らの意思で有料の収益機能を契約した＝商用行動）。将来の実収益行動（ペイウォール開設等）も同カテゴリ。
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

### 7.2 リスナー処理（organization ドメイン・擬似コード）

```
onRevenueFeatureActivated(event):
  orgIds = resolveTargetOrganizations(event.scopeKind, event.scopeId)
    # ORG  → [scopeId]
    # TEAM → team_org_memberships WHERE team_id=scopeId AND status='ACTIVE' の organization_id 全件
    # USER → []（個人の REVENUE 契約は組織区分に影響しない）
  for orgId in orgIds:
    org = organizationRepository.findById(orgId)
    if org.orgType == COMPANY: continue                    # 既に営利
    if event.scopeKind == ORG and org.orgType in {NPO, ASSOCIATION, COMMUNITY, OTHER}:
        org.updateOrgType(COMPANY)                          # 自動更新（organization ドメイン内の正規メソッド・直接 UPDATE 禁止の原則充足）
        auditLog(ORG_TYPE_AUTO_UPDATED, orgId, from, to, trigger=event)
        confirmableNotificationService.send(orgAdminUserIds(orgId),
            title/body = i18n "billing.orgType.autoUpdated.*")   # F04.9 確認必須通知（AC-11）
    else:
        # 公共系（GOVERNMENT/MUNICIPALITY/SCHOOL/HOSPITAL）または TEAM 経由 → 自動更新しない（AC-12/22・README R-1）
        confirmableNotificationService.send(orgAdminUserIds(orgId),
            title/body = i18n "billing.orgType.reviewRequested.*")
        operationsReviewLog(orgId, event)                   # 運営レビュー記録（audit_logs）
```

- 通知文言キーは 04 §3。**この分岐（自動更新対象の org_type 集合）は README §8 R-1 の御裁可で確定**する（本擬似コードは推奨案 (b)）。

---

## 8. キャッシュ戦略

| キャッシュ | value / key | TTL | evict |
|---|---|---|---|
| 権利判定 | `entitlement:check` / `{scopeKind.name()}:{scopeId}:{featureKey}` | **60 秒**（`RedisConfig.cacheManager` に個別登録） | **scope 単位のキー evict を第一**とする（M-8）。1 スコープの契約変更は当該 `{scopeKind}:{scopeId}:*` のみ削除（feature_key を列挙して個別 evict、または scope プレフィックスの `SCAN`+DEL）。**`allEntries=true` の全消しは日次付与バッチ（1 万件）でサンダリングヘッドを招くため使わない** |
| 有料プラン互換 | `teamPlan` / `#teamId`（既存・変更しない） | 既定 30 分 | 上記と同時に `@CacheEvict(value="teamPlan", key="#scopeId")`（TEAM スコープ変更時） |
| 非営利判定 | `billing:nonprofit` / `{scopeKind.name()}:{scopeId}` | 10 分 | org_type 変更イベントで evict |
| マスタ | `billing:catalog`（プラン一覧の組み立て結果） | 10 分 | シスアド CRUD 時に evict |

- キーの enum は必ず `name()` で String 化（Valkey 直列化事故防止・memory `feedback_cacheable_enum_key_redis`）。
- **日次付与バッチの evict（M-8）**: F20.3 の自動付与バッチは**付与済みユーザーを skip**（新規付与分のみ処理）し、**各ユーザーの scope キー evict は付与時に個別実行**（当該ユーザー分のみ）。バッチ完了時の全消し（`allEntries`）はしない。1 万件でも「新規付与された分の scope キーだけ」を触るためサンダリングヘッドを避ける。
- **取消の反映保証**: scope キー evict を必ず実行（AC-16 の観測点＝**evict 呼び出しの実行**）。evict 漏れがあっても TTL 60 秒で自然収束するが、TTL 依存の観測は非決定的なので**テストは「evict が呼ばれたこと」を検証**し、TTL 失効は別途単体テストで確認する（M-9・03 §5）。

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
```

---

## 10. OpenAPI・生成型

- 新規 DTO には `@Schema(name = "Billing〜")` を付与し**同名 nested schema 衝突を避ける**（memory `feedback_openapi_nested_schema_name_collision`）。
- BE 実装マージ後は `docs/openapi.json` 再生成＋`cd frontend && npm run generate:types` を**同一 PR** で行う（memory `project_openapi_json_chronic_drift`）。
