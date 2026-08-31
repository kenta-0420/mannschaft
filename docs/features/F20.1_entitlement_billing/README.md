# F20.1 課金・エンタイトルメント基盤（プラン提示 × feature_key 単位の権利管理）

> **ステータス**: 🟢 設計完了（マスター御裁可済／P1 main 済・**Phase 2b 実決済 実施中**／営利自動切替・オーナー変更は Phase 2 保留）
> **最終更新**: 2026-08-31
> **関連**: [F20.3 ベータ特典](../F20.3_beta_perks/README.md)（`source_kind=BETA_GRANT` の発行元）／ [F22.1 統一決済プラットフォーム](../F22.1_market/payment/README.md)（Phase 2 実決済レール）／ [F08.9 会員決済](../F08.9_membership_billing_paywall/README.md)（**逆向きの課金**・混同禁止 §4.5）／ [F12.2 フィーチャーフラグ](../F12.2_feature_flag.md)（**意味論が別**・§4.4）／ [F09.19 広告配信](../F09.19_ad_slot_serving.md)（有料プラン広告非表示の結線先・同 §7.5）

---

## ⚠️⚠️ Phase 2 保留（マスター決定 2026-07-08）— 初期実装スコープ外 ⚠️⚠️

> **収益化は進めるが急ぎではない。以下 2 機能は「初期実装スコープから外し Phase 2 へ保留」する**（設計内容は削除せず本書に温存＝将来そのまま使う。スコープのみ移動）。核となる収益化（プリセット＝3プラン提示＋アドオン・エンタイトルメント判定・スコープ別 BE ゲート・信任〔F20.2〕・ベータ特典〔F20.3〕の付与/判定）は**初期スコープに残す**。

| Phase 2 保留機能 | 本書での該当箇所 | 初期スコープでの代替 |
|---|---|---|
| **営利自動切替（本書 F20.1）**: 非営利宣言団体が REVENUE 機能を契約したとき `org_type` を `COMPANY` へ自動変異させる仕組み一式（`RevenueFeatureActivatedEvent` 発火・org_type 自動更新・確認必須通知・誤変異の運営差し戻し API・監査 `ORG_TYPE_AUTO_UPDATED`/`ORG_TYPE_REVERTED`・R-1 の自動判定ロジック） | §2.1・§3.3・§4.6・§5 AC-11/12/22/22b/24/25/26/27・§6・§8 R-1・02 §7・03 §1/§7/§9・01 §ER・04 §orgType | **`org_type` は自己申告のまま**（利用者が申告・自動変異しない）。REVENUE 機能は営利/非営利を問わず有料ゆえ課金は破綻しない。将来 Phase 2 で「収益機能を有効化した非営利宣言団体を**運営レビューのキューに積むソフトなシグナル**（自動変異しない）」として再設計する余地を残す |
| **team オーナー変更イベント（[F20.3](../F20.3_beta_perks/README.md) B-4）** | F20.3 側で保留（本書は依存しない） | 当面は**規約第 17 条（権利義務譲渡禁止）＋手動の再審査**で足りる |

**保留理由（営利自動切替）**: 価格は「**機能の性質**」に付ける設計（収益機能は営利/非営利問わず有料）ゆえ `org_type` は課金額を変えず、ラベル自動補正の価値が低い。非営利優遇は**信任（F20.2）で担保**する設計であり、自己申告ラベルに優遇をぶら下げていない。加えてクロスドメイン結線（billing→organization）と「団体を機械的に営利認定する」法的・心理的リスクが割に合わない。

**核フローの非依存（確認済み）**: エンタイトルメント判定（`isEntitled`/`EntitlementGuard`）・契約/アドオン・プラン提示・シスアド CRUD・信任（F20.2）・ベータ特典の付与/判定は、**営利自動切替にも team オーナー変更イベントにも一切依存しない**。両機能を Phase 2 に送っても初期スコープは完結する（§6 ロードマップ・§4.6 参照）。

---

## ⚠️ 冒頭注記（法令・用語）

1. **資金決済法回避（絶対）**: 本機能は**クレジット・残高・ポイント等の前払い概念を一切持ち込まない**。扱うのは**サブスクリプション（役務の継続提供の対価）のみ**である。F18 残高凍結（資金決済法・機能フラグ凍結中）・F09.13 プリペイドの轍を踏まない。「チャージ」「残高」「ポイント購入」に相当するテーブル・API・UI を本ドメインに追加してはならない。
2. **単価・人数バンドはすべて「機構のみ定義」**: 本設計に登場する金額（FULL=月¥2,000、アドオン=¥300/機能 等）はすべて**想定値**であり、**実額はベータ終了時に実データ（F20.3 §7 計測）で決定**する。価格・バンド割りはマスタデータ（`plans`/`plan_price_bands`/`feature_catalog`）として**運用変更可能**な設計とし、コードに焼き付けない。
3. **用語**: F20.2 で扱う団体認証の呼称は「**信任**」である（NG語: 保証/後見/承認/相互認証）。本設計から F20.2 に言及する場合も「信任」を用いる。

---

## 0. この設計書の構成

複合形（F08.9 / F22.1 payment と同じ分割方式）で構成する。

| ファイル | 内容 |
|---|---|
| `README.md`（本書） | 概要・中核モデル（3プラン提示×feature_key エンタイトルメント）・営利/非営利イベント駆動・既存機構との境界と移行結線表・受け入れ条件・段階ロードマップ・要裁可論点・変更履歴 |
| [`01_data_model.md`](01_data_model.md) | DB設計（`entitlements`／`billing_contracts`／`feature_catalog`／`plans`／`plan_features`／`plan_price_bands` の完全 DDL・状態遷移・ER図・Flyway 計画・シャーディング耐性） |
| [`02_api_design.md`](02_api_design.md) | API設計（プランカタログ・契約/アドオン・エンタイトルメント照会・`EntitlementGuard`/`isEntitled` 擬似コード・org_type イベント・シスアド CRUD・DTO 全フィールド・エラーコード・キャッシュ） |
| [`03_security.md`](03_security.md) | セキュリティ（認可マトリクス・scopeId 所有権検証＝IDOR 対策・402/403 使い分け・FE のみペイウォール禁止・キャッシュと取消の整合・GDPR/退会） |
| [`04_ui_i18n.md`](04_ui_i18n.md) | 画面設計（プラン一覧・ペイウォールモーダル・スコープ別課金管理）・i18n 6言語キー一覧（`billing.json` 新設） |
| [`05_billing_center.md`](05_billing_center.md) | 誠実な料金・契約センター、scope-owned Stripe Customer、暦月日割り、請求/支払方法、取消/撤回、webhook、移行、受入条件 |

---

> **Phase 2b 追補（2026-08-31）**: 本書で Phase 2 扱いとしていた Stripe 実決済の顧客所有、日割り、請求書/領収書、支払方法、解約・プラン変更は [05_billing_center.md](05_billing_center.md) で設計確定した。本書の「日割り/按分は対象外」「有償プラン変更を拒否」の旧記述は当該追補により置換される。


## 1. 概要

Mannschaft の SaaS 課金（**運営 → 団体/個人**）の基盤を定義する。

- **表の顔は 3 プラン**: `FREE` / `BASIC` / `FULL`（FULL=月¥2,000 想定）＋**個別アドオン**（¥300/機能 想定）。利用者には「プランを選ぶ」体験を提示する。
- **内部は feature_key 単位のエンタイトルメント**: プランは `plan_features` で feature_key の束に展開され、権利の真実源は常に `entitlements` 行である。判定 API は `isEntitled(scopeKind, scopeId, featureKey, now)` の 1 本に統一する。
- **判定は「操作が行われているスコープ」に対して行う**: チーム契約→そのチーム内では全メンバーが機能を使える／同じユーザーでも他チームでは使えない。個人契約→USER スコープの操作のみ有効。**ユーザー個人に権利を紐づけない**。
- **BE ゲート必須**: `EntitlementGuard.require(...)` がサーバー側で強制する（未充足→402/403）。**FE だけのペイウォールは禁止**（過去事故: FE のみ制御で本文 API 丸見え・memory `project_paywall_be_body_gate_required`）。
- **ベータ中は実決済なし**: Phase 1 は権利管理の機構のみ（付与は F20.3 ベータ特典＋シスアド手動）。実決済（PSP 連携）は Phase 2 で F22.1 決済レールに接続する（本設計は **PSP 非依存**で先行）。

### 1.1 ドメイン配置

- 新規ドメイン **`com.mannschaft.app.billing`**（モジュラーモノリス原則に従い独立パッケージ）。
- ベータ特典（F20.3）は同ドメインのサブパッケージ `com.mannschaft.app.billing.beta` に置く（`beta_grants` → `entitlements` は同一ドメイン内の親子）。
- スコープ用語は payment の実在 enum `com.mannschaft.app.payment.connect.ScopeKind { USER, TEAM, ORG }` に**準拠**する（値・綴りを一致させる。billing ドメインには同名 enum `EntitlementScopeKind` を新設し値を揃える＝クロスドメインの enum 直接参照はしない）。UI 表示は「個人／チーム／組織」。

---

## 2. スコープ

### 2.1 対象（in）
- [ ] `entitlements` 中核テーブルと判定サービス `EntitlementQueryService.isEntitled(...)`
- [ ] BE ゲート `EntitlementGuard.require(...)`（未充足 402/403・Valkey キャッシュ）
- [ ] プラン提示レイヤー（`plans`/`plan_features`/`plan_price_bands`/`feature_catalog`・シスアド CRUD）
- [ ] 契約機構 `billing_contracts`（PLAN/ADDON・ベータ中は決済なしで契約状態のみ管理）
- [ ] ~~営利/非営利のイベント駆動是正（非営利 org_type × REVENUE 機能有効化 → org_type 更新＋確認必須通知）~~ → **【Phase 2 保留】初期スコープ外**（§3.3・冒頭 Phase 2 保留ブロック）。初期は org_type 自己申告のまま自動変異しない
- [ ] 既存 `TeamPlanService.hasPaidPlan` の内部委譲移行（結線先 3 箇所・§4.1）
- [ ] i18n 6言語（`billing.json` 新設）

### 2.2 対象外（out・別フェーズ/別機能）
- [ ] **営利自動切替（org_type 自動変異一式）** → **Phase 2 保留**（マスター 2026-07-08・冒頭 Phase 2 保留ブロック／§3.3）。初期は org_type 自己申告のまま。結線先（organization/notification/audit ドメイン）も初期スコープでは不要
- [ ] F22.1 との将来連携（実決済、請求、領収書自体は 05 で設計確定）
- [ ] ベータ特典の付与条件判定・beta_grants → [F20.3](../F20.3_beta_perks/README.md)
- [ ] 会費徴収（チーム→メンバー） → F08.9（逆向きの課金・§4.5）
- [ ] 年額・返金（暦月日割り/upgrade・downgrade按分は 05 で設計確定）
- [ ] 多通貨（JPY 固定・列は `_jpy` サフィックスで明示）

---

## 3. 中核モデル

### 3.1 エンタイトルメント（権利の真実源）

```
entitlements（1行 = 1スコープ × 1機能 × 1発行元 の権利）
  scope_kind   … USER / TEAM / ORG（操作スコープの種別）
  scope_id     … users.id / teams.id / organizations.id（論理参照・FKなし）
  feature_key  … feature_catalog の機能キー
  source_kind  … PLAN / ADDON / BETA_GRANT（発行元区分）
  source_ref_id… billing_contracts.id または beta_grants.id（発行元行・NOT NULL）
  valid_from / valid_until(NULL=無期限) / revoked_at(NULL=未取消)
```

**判定式（正準・全実装がこれに従う）**:

```
isEntitled(scopeKind, scopeId, featureKey, now):
  feature = featureCatalog.find(featureKey)
  if feature が存在しない or feature.enabled == false:
      return false  # fail-safe: 不明キーは拒否側 + WARN ログ（症状を隠さない）
  if featureKey ∈ planFeatures(FREE):
      return true   # FREE プラン掲載機能は契約なしで全スコープ利用可
  if feature.free_for_nonprofit == true and isNonProfitScope(scopeKind, scopeId):
      return true   # INTERNAL 系の非営利無料枠（機構のみ・値は運用設定）
  return exists e in entitlements where
      e.scope_kind = scopeKind and e.scope_id = scopeId
      and e.feature_key = featureKey
      and e.revoked_at IS NULL
      and e.valid_from <= now
      and (e.valid_until IS NULL or now < e.valid_until)   # valid_until ちょうど＝失効（半開区間 [from, until)）
```

- **境界の正準**: 有効期間は半開区間 `[valid_from, valid_until)`。`now == valid_until` は **false**（AC-06）。
- スコープをまたいだ継承はしない（ORG 契約が配下チームに自動で効く…等は**本設計では行わない**。組織一括契約は「ORG スコープの操作」にのみ効く。配下チームへの展開は将来拡張・§8 R-4=御裁可済(a)＝展開しない）。

### 3.2 プラン提示レイヤー（表の顔）

| プラン | 想定月額 | 内容 |
|---|---|---|
| `FREE` | ¥0 | 基本機能（`plan_features` で列挙）。契約行不要＝既定 |
| `BASIC` | 未定（機構のみ） | 中間セット |
| `FULL` | **¥2,000 想定**（実額はベータ終了時決定） | 全機能セット |
| （アドオン） | **¥300/機能 想定**（同上） | `feature_catalog.addon_available=true` の機能を単品契約 |

- チーム/組織の価格は**アクティブ人数バンド**（`plan_price_bands`）で変動する機構を持つ。バンド割り・各バンド単価は**マスタデータ**（実額未定・NULL 可）。人数の数え方の正準は **`memberships` の `left_at IS NULL` の行数**（F20.3 のスナップショットと同一定義・01 §3.4）。
- `feature_catalog.category` は **`INTERNAL`（内向き機能・無料枠を広く取る方針）／`REVENUE`（収益機能＝スコープの区分を問わず有料）** の 2 値。非営利無料枠は `free_for_nonprofit` フラグ（機構）で表現し、初期値は現行課金挙動を壊さない（01 §2.1 シード）。

### 3.3 営利/非営利（org_type のイベント駆動是正）【Phase 2 保留・初期スコープ外】

> **【Phase 2 保留】この節の仕組み一式（`RevenueFeatureActivatedEvent` 発火・org_type 自動更新・確認必須通知・公共系の自動判定・差し戻し）は初期実装スコープ外**（マスター 2026-07-08）。**理由**=価格は機能の性質に付く設計ゆえ org_type は課金額を変えず、ラベル自動補正の価値が低い／非営利優遇は信任（F20.2）で担保する／クロスドメイン結線と機械的な営利認定の法的・心理的リスクが割に合わない。**初期スコープでは `org_type` は自己申告のまま自動変異しない**（REVENUE 機能は区分問わず有料ゆえ課金は成立する）。以下の設計は Phase 2 でそのまま使うため温存する。

- 自己申告区分は既存 `organizations.org_type`（V9.091 で ENUM 化・実 enum 値は `GOVERNMENT / MUNICIPALITY / COMPANY / HOSPITAL / ASSOCIATION / SCHOOL / NPO / COMMUNITY / OTHER`）。**初期スコープはこの自己申告値を読むだけで、自動では書き換えない**（以下の自動更新記述はすべて Phase 2）。
- **非営利系 org_type のスコープが REVENUE 機能を「商用行動」で有効化**した瞬間、billing ドメインが **`RevenueFeatureActivatedEvent`** を発火し、**organization ドメインのリスナー**が org_type を営利（`COMPANY`）へ更新＋ **F04.9 確認必須通知**を org ADMIN へ送る。**クロスドメイン直接 UPDATE 禁止・イベント駆動**（02 §7）。
- **★発火は「団体自身の商用行動」に限る（H-5）**: 発火するのは **PLAN 契約購入・ADDON 契約購入**（対象に REVENUE 機能を含む）のみ。**ベータ特典の付与（`source_kind=BETA_GRANT`）・シスアド手動付与は発火しない**（運営が無償で配る行為であり団体の商用行動ではない。NPO にベータ特典を配っただけで org_type が COMPANY に自動変異してはならない）。発火点×org_type の全分岐は 02 §7.0 のマトリクス、否定 AC は §5 AC-22b。
- 自動更新の対象 org_type は **`NPO` / `ASSOCIATION` / `COMMUNITY` / `OTHER`** を推奨既定とし、公共系（`GOVERNMENT` / `MUNICIPALITY` / `SCHOOL` / `HOSPITAL`）は自動更新せず**通知＋運営レビューのみ**（市役所が COMPANY になるのは不合理）→ **§8 R-1=マスター御裁可済(b)（2026-07-08）**。
- **チームの区分は所属組織に従う**: チーム↔組織は `team_org_memberships`（V2.011・多対多・`status ∈ {PENDING, ACTIVE}`）。判定は「**ACTIVE な所属組織のうち 1 つでも営利（`COMPANY`）なら営利扱い**、全所属が非営利なら非営利扱い」。
- **無所属チーム**（`team_org_memberships` に ACTIVE 行が無いチーム）: `teams` テーブルに区分列は**存在しない**（V2.004 実確認）。**非営利扱いを既定**とし、REVENUE 機能は区分問わず有料のため悪用余地は INTERNAL 無料枠のみ → 列追加はしない → **§8 R-2=マスター御裁可済(c)（2026-07-08）**。
- チーム経由（TEAM スコープ）の REVENUE 有効化では、所属組織の org_type は**自動更新せず**、**所属する全 ACTIVE 組織の ADMIN へ確認必須通知のみ**（1 チームの行動で組織全体の区分を書き換えない）→ **§8 R-1=御裁可済(b) に内包**。
  - **通知先を「全 ACTIVE 親組織」とする正当化（L-4）**: チームは多対多で複数組織に所属しうる（`team_org_memberships`）。どの親組織にとっても「傘下チームが収益機能を使い始めた」ことは区分見直しの判断材料になるため、**ACTIVE な全親組織の ADMIN に通知**する（漏らさない）。過剰通知の懸念はあるが、REVENUE 有効化はチーム単位で頻度が低く、確認必須通知（F04.9）は受信者が確認すれば消えるため負荷は限定的。絞り込み（例: 主所属組織のみ）は誤って営利判断の機会を逃すリスクの方が大きいと評価し、全 ACTIVE 親組織を既定とする（R-1 で運用調整可）。

### 3.4 BE ゲート `EntitlementGuard`

```java
// 利用側（例: 予約通知宛先の 4 件目追加）
entitlementGuard.require(EntitlementScopeKind.TEAM, teamId,
        FeatureKeys.RESERVATION_NOTIFICATION_RECIPIENTS_EXTENDED);
// 未充足時:
//   アドオン/上位プランで購入可能  → BusinessException(ENTITLEMENT_003) → HTTP 402
//   購入手段なし・スコープ不適合   → BusinessException(ENTITLEMENT_004) → HTTP 403
```

- 判定は Valkey キャッシュ（`@Cacheable` 60 秒 TTL ＋ 付与/取消時 `@CacheEvict`。`TeamPlanService`/`FeatureFlagService` の前例に倣う。キーの enum は `name()` で String 化・memory `feedback_cacheable_enum_key_redis`）。詳細 02 §8。
- 402/403 の使い分け正準は 03 §3。

---

## 4. 既存機構との境界と移行結線【結線表】

### 4.1 `team_subscriptions` / `TeamPlanService.hasPaidPlan`（温存＋内部委譲）

既存実装（origin/main 実機確認・2026-07-08）:

- `payment/entity/TeamSubscriptionEntity`（V9.055・BIGINT・`BaseEntity` 継承）: `teamId` / `planType`（enum **`FREE, MODULE, PACKAGE, ORGANIZATION`**）/ `status`（enum **`ACTIVE, CANCELLED, EXPIRED, PAST_DUE`**）/ `stripeSubscriptionId` / `currentPeriodStart` / `currentPeriodEnd` / `cancelledAt`。
- `payment/service/TeamPlanService`: public メソッドは 1 本のみ
  ```java
  @Cacheable(value = "teamPlan", key = "#teamId")
  public boolean hasPaidPlan(Long teamId) {
      return teamSubscriptionRepository.hasActivePaidPlan(teamId);  // status='ACTIVE' AND planType<>'FREE'
  }
  ```

**移行方針（Expand → Migrate → Contract・廃止せず温存）**:

| 段 | `hasPaidPlan(teamId)` の中身 | 状態 |
|---|---|---|
| 現状 | `team_subscriptions` のみ | main |
| **Expand**（本機能 P1） | `team_subscriptions 判定 OR isEntitled(TEAM, teamId, "legacy.paid_plan_bundle", now)`（どちらかで true） | 本設計 |
| **Migrate**（P1 内） | 既存 ACTIVE×有料 `team_subscriptions` 行から `entitlements` へブリッジ行を発行（`source_kind=PLAN`・ブリッジ契約行経由・01 §5） | 本設計 |
| **Contract**（Phase 2） | `isEntitled` 一本化。`team_subscriptions` は読み取り専用の履歴として温存（DROP しない） | 将来 |

- ブリッジ用 feature_key **`legacy.paid_plan_bundle`** を feature_catalog に置く。`BASIC`/`FULL` の `plan_features` に含め、「有料プランなら true」の互換意味を保つ。
- `hasPaidPlan` のシグネチャ・`@Cacheable(value="teamPlan")` は**変更しない**（呼び出し元 3 箇所を壊さない）。キャッシュ evict は entitlement 変更イベントで `teamPlan` も evict する（02 §8）。

### 4.2 結線先 3 箇所（既存の有料ゲートの正体）

| # | 結線先 | 現行実装（実機確認済） | 移行後 |
|---|---|---|---|
| 1 | `template/ModuleService` | `module.getRequiresPaidPlan() && !teamPlanService.hasPaidPlan(teamId)` → `TMPL_004`。**組織側カタログは常に `hasPaidPlan(false)` 固定・requiresPaidPlan チェックなし** | feature_key **`template.premium_modules`**。`EntitlementGuard.require(TEAM, teamId, ...)` へ置換（Expand 期は hasPaidPlan のままで挙動同一）。組織側は現行どおり非対象（変更しない） |
| 2 | 予約 `ReservationNotificationRecipientService` | `count >= FREE_RECIPIENT_LIMIT(3) && !hasPaidPlan(teamId)` → `RESERVATION_029`（`ERROR_CODE_STATUS_MAP` で **402**） | feature_key **`reservation.notification_recipients_extended`**。エラーコード・402 は不変（後方互換・AC-15） |
| 3 | 広告 F09.19 有料プラン広告非表示（F09.19 §7.5「有料プランゲート・BE 判定」） | 設計上 `TeamPlanService.hasPaidPlan(teamId)` を既存メソッドのまま使用（BE 実装は進行中） | feature_key **`ads.hide`**。Expand 期は hasPaidPlan の OR 委譲で挙動同一・Migrate 期に `isEntitled` 直接参照へ置換 |

### 4.3 F22.1 決済レール（Phase 2 で利用・本設計は PSP 非依存）

- 実決済フェーズ（Phase 2）では F22.1 の決済機構を利用予定。ただし本件は**受取人が Mannschaft 自社**（SaaS 利用料）のため、F22.1 §3.0 の統一アーキ原則では「自社受取＝素 Checkout 可（Connect 不要）」に該当する。Connect/escrow は使わない見込みだが、確定は Phase 2 軍議。
- 本設計のテーブル（`billing_contracts`）は `stripe_*` 等の PSP 列を**持たない**。Phase 2 で列追加（Expand）できる余地のみ確保する（01 §2.4）。

### 4.4 F12.2 フィーチャーフラグ（意味論が別・共存）

| | F12.2 フィーチャーフラグ | F20.1 エンタイトルメント |
|---|---|---|
| 意味 | **運用トグル**（カナリアリリース・障害時 kill switch・AB テスト） | **契約上の権利**（誰がどの機能を使う権利を持つか） |
| 変更主体 | SYSTEM_ADMIN がデプロイなしで ON/OFF（管理 API `/api/v1/system-admin/feature-flags`） | 契約行為（プラン契約・アドオン・ベータ特典） |
| 解決単位 | USER > ORG > GLOBAL の 3 段オーバーライド | 操作スコープ（USER/TEAM/ORG）×feature_key |
| 反映速度 | キャッシュ TTL 1 分で即時反映 | 契約変更時 evict（TTL 60 秒・02 §8） |
| 寿命 | 機能安定後にフラグ撤去 | 契約が続く限り恒久 |

- **両者は共存**し、判定順序は「**フラグ（kill switch）が上位**」: フラグ OFF の機能は entitlement があっても提供されない。逆にフラグ ON でも entitlement が無ければ 402/403。`EntitlementGuard` はフラグを見ない（責務分離・各機能の入口で両方のガードを重ねる）。

### 4.5 F08.9 会費徴収（逆向きの課金・混同禁止）

- F08.9 は「**チームがメンバーから徴収**する」課金（払い手≠受益者・受取人は第三者＝Connect レール）。
- 本 F20.1 は「**運営が団体/個人へ SaaS 課金**する」（受取人は Mannschaft 自社）。**向きが逆**であり、テーブル・サービス・用語を混ぜない。`member_payments`/`payment_items` を本機能で参照・拡張してはならない。

### 4.6 実装スコープ注記（billing 外ドメインへの要求・軍議で足軽担当に含める）

> **【Phase 2 保留】以下の org_type 結線（organization/notification/audit ドメイン）は初期スコープでは不要**（マスター 2026-07-08・§3.3）。初期は org_type 自己申告のまま自動変異しないため、これらの新設物（`updateOrgType`・差し戻し API・`findActiveOrganizationIdsByTeamId`・監査アクション・通知文言）は **Phase 2 で実装する**。初期スコープの軍議・試練の対象からは外す。設計は Phase 2 のため温存する。

org_type イベント結線（§3.3・02 §7.2）は **billing.beta ドメインの外**に実装を要求する（**Phase 2**）。Phase 2 の軍議のタスク分解で**足軽の担当範囲に明示的に含める**こと（billing だけ実装して結線先が無い状態を防ぐ）:

| # | ドメイン | 要求物（origin/main 実物照合済み） |
|---|---|---|
| 1 | organization | `OrganizationEntity.updateOrgType(OrgType)` を**新設**（現在 grep で不在）＋誤変異ロールバック API `POST /system-admin/organizations/{orgId}/org-type-revert`（R-1・03 §7） |
| 2 | team | `TeamOrgMembershipRepository.findActiveOrganizationIdsByTeamId(teamId)` を**新設**（status='ACTIVE'・`team_org_memberships` V2.011） |
| 3 | audit | 監査アクション `ORG_TYPE_AUTO_UPDATED` / `ORG_TYPE_REVIEW_REQUESTED` / `ORG_TYPE_REVERTED`（`AuditEventType` に無ければ追加） |
| 4 | notification（messages） | `messages*.properties` 6 言語に `notification.billing.org_type_auto_updated.*` / `org_type_review_requested.*` |

> 宛先は既存 `userRoleRepository.findAdminUserIdsByOrganizationId(orgId)`・通知は既存 `ConfirmableNotificationService.send(...)`（実 overload・02 §7.2）を**再利用**（新設不要）。

---

## 5. 受け入れ条件（AC）

> `/試練` はこの表から red テストを起こす。番号は本設計内で恒久（追補は末尾連番）。
> **【Phase 2 タグ】** `[P2]` を付した AC（org_type 自動変異・確認通知・不発火の否定 AC を検証するもの＝AC-11/12/22/22b/24/25/26/27）は**営利自動切替に属し初期スコープの試練対象外**（マスター 2026-07-08・§3.3）。初期スコープの試練は `[P2]` 以外の AC（エンタイトルメント判定・契約・後方互換・IDOR 等）から起こす。`[P2]` の AC は Phase 2 実装時に有効化する（設計は温存）。

| # | 区分 | 誰が・何をしたら・どうなる（観測可能） |
|---|---|---|
| AC-01 | 正常 | FULL プラン契約中のチーム T のメンバーが、T スコープで FULL 対象機能を要求 → `isEntitled(TEAM, T, key)=true`・ガード通過 |
| AC-02 | 正常/境界 | チーム T1 に契約があるユーザー U が、**T1 では利用可・無契約の T2 では同一機能が 402**（権利はスコープに紐づきユーザーに紐づかない） |
| AC-03 | 正常/境界 | USER スコープの個人契約を持つ U は、自分の USER スコープ操作で利用可・**U が属するチームの TEAM スコープ操作では 402** |
| AC-04 | 正常 | アドオン契約（feature_key=X）で X のみ `isEntitled=true`、同プラン外の Y は false |
| AC-05 | 正常 | FREE プラン掲載機能は契約ゼロのスコープでも `isEntitled=true` |
| AC-06 | 境界 | `valid_until` 経過後の `isEntitled` は **false**。`now == valid_until` ちょうどで false・`now == valid_until - 1秒` で true（半開区間） |
| AC-07 | 異常 | `revoked_at` セット済み行は期間内でも false |
| AC-08 | 境界 | `valid_from` が未来の行は false |
| AC-09 | 異常 | ガード未充足時、アドオン購入可能な機能は **HTTP 402**＋購入導線情報、購入不可（スコープ不適合等）は **HTTP 403** |
| AC-10 | 異常 | チーム T1 の ADMIN が **T2 の scopeId を指定した契約 API** を呼ぶ → **403**（scopeId 所有権検証・IDOR） |
| AC-11 | 正常 `[P2]` | **【Phase 2】**非営利 org_type（例 `NPO`）の組織 O の ADMIN が REVENUE 機能を含む契約を有効化 → `RevenueFeatureActivatedEvent` 発火 → **O の org_type が `COMPANY` へ更新**され、O の ADMIN へ**確認必須通知（F04.9）**が届く（初期スコープでは org_type は自己申告のまま不変） |
| AC-12 | 境界 `[P2]` | **【Phase 2】**公共系 org_type（`GOVERNMENT`/`MUNICIPALITY`/`SCHOOL`/`HOSPITAL`）の組織が REVENUE 機能を有効化 → org_type は**更新されず**、確認必須通知＋運営レビュー記録のみ（R-1 御裁可後に確定） |
| AC-13 | 境界 | 無所属チーム（`team_org_memberships` に ACTIVE 行なし）は非営利扱いで判定される（R-2 御裁可後に確定） |
| AC-14 | 後方互換 | 既存 `team_subscriptions`（`status=ACTIVE` かつ `planType<>FREE`）を持つチームは、移行後も `hasPaidPlan=true` |
| AC-15 | 後方互換 | 予約通知宛先 4 件目追加は、無権利チームで従来どおり `RESERVATION_029`・**HTTP 402** |
| AC-16 | 正常 | 契約取消（revoke）時、当該 scope の権利キャッシュ **evict が呼ばれる**（観測点＝evict 呼び出しの実行。TTL 失効の時間依存観測は行わず、キャッシュミス後 `isEntitled=false` を別途単体テストで検証・M-9） |
| AC-17 | 異常 | SYSTEM_ADMIN 以外が plans/feature_catalog/price_bands の CRUD API を呼ぶ → 403 |
| AC-18 | 異常 | `feature_catalog` に存在しない feature_key の `require` → 拒否（403）＋ WARN ログ（fail-safe） |
| AC-19 | 正常 | upgrade は `invoice.paid` 後に新権利を発行、downgrade は翌月1日到達まで旧権利を維持する（05 §3/§4） |
| AC-20 | 正常 | 有償契約の解約予約は当月末まで権利を維持し、`customer.subscription.deleted` で entitlements を revokeする。無償契約のみ即時取消 |
| AC-21 | 異常 | 同一（scope×feature×source_kind×source_ref×valid_from）の重複 INSERT → UNIQUE 制約違反として 409 |
| AC-22 | 境界 `[P2]` | **【Phase 2】**TEAM スコープの REVENUE 有効化（PLAN/ADDON 契約）では所属組織の org_type は更新されず、**所属する全 ACTIVE 組織**の ADMIN へ通知のみ |
| AC-22b | 異常（否定・H-5）`[P2]` | **【Phase 2】NPO 組織にベータ特典（BETA_GRANT）を付与しても `RevenueFeatureActivatedEvent` は発火せず org_type は変化しない**（運営の無償配布は商用行動ではない） |
| AC-23 | 正常（M-2） | 権利サマリ `GET .../entitlements` の `entitledFeatures` が、FREE 掲載機能（`sourceKind=FREE`）・非営利無料枠（`NONPROFIT_FREE`）を virtual 合成し、**一覧の feature_key 集合＝`isEntitled=true` の集合**に一致する（UI「利用できる機能」と BE 判定の齟齬ゼロ） |
| AC-24 | 境界 `[P2]` | **【Phase 2】**既に `COMPANY` の組織が REVENUE 契約 → org_type は変化せず通知もしない（冪等） |
| AC-25 | 境界 `[P2]` | **【Phase 2】**INTERNAL 機能のみの PLAN/ADDON 契約 → `RevenueFeatureActivatedEvent` は不発火 |
| AC-26 | 異常（否定・H-5）`[P2]` | **【Phase 2】**シスアド手動付与で REVENUE 機能を付けても org_type は変化しない（運営操作は商用行動ではない） |
| AC-27 | 境界 `[P2]` | **【Phase 2】**USER スコープの REVENUE 契約は org 区分に影響しない（イベント不発火） |
| AC-28 | 正常（H-1） | 同一スコープへの ACTIVE PLAN 契約の**並行 2 リクエスト**は、`active_contract_pointers` の UNIQUE により 1 件のみ成功・他は `ENTITLEMENT_006` 409（TOCTOU 二重契約が作れない） |
| AC-29 | 正常（M-5） | 退会申請（猶予中）では契約・entitlements は revoke されず権利維持。退会確定（purge）で失効。撤回時は権利維持のまま |
| AC-30 | 境界（L2） | 契約作成の**完全同時再送**では冪等キー check-then-set の非原子により片方が `active_contract_pointers` UNIQUE で `ENTITLEMENT_006`(409) になる。二重契約・二重発行は生じない（FE は 409 を「契約済み」として再取得） |
| AC-31 | 正常（実決済 D-4） | **価格 NULL**（マスタ未設定）の契約 POST → 決済なし無償契約（従来 P1 フロー・即 ACTIVE＋entitlements 発行・`checkoutUrl=null`）。既存フローの回帰なし |
| AC-32 | 正常（実決済 D-4） | **価格設定済み**の契約 POST → `checkoutUrl` 返却・契約は `PENDING`＋`price_jpy_snapshot` 焼付・**entitlements 未発行**。PENDING スロット占有中の再契約は `ENTITLEMENT_016`(409) |
| AC-33 | 正常（実決済・2026-08-31改訂） | `checkout.session.completed` は Customer/Subscription 参照の照合・焼付のみ。**`invoice.paid` 到達で初めて** `PENDING→ACTIVE`＋entitlements 発行。未達/失敗なら未発行のまま（05 §4） |
| AC-34 | 冪等（実決済） | 同一 webhook イベントの再送は冪等（`WebhookIdempotencyService` の event_id ゲート＋status 済チェックの**二層**・二重発行ゼロ） |
| AC-35 | 正常（実決済 D-3） | 有償解約＝`cancel_at_period_end`。契約は ACTIVE のまま `cancelled_at` セット・由来 entitlements の `valid_until`＝`current_period_end`（半開区間・期末ちょうど false）。`customer.subscription.deleted` で `EXPIRED`＋pointer DELETE＋残 revoke |
| AC-36 | 正常（実決済 D-3） | 無償解約＝即時失効（既存フロー不変・PSP 呼び出しなし） |
| AC-37 | 正常（実決済） | `invoice.payment_failed` → `PAST_DUE`（**権利は触らない**＝`current_period_end` まで利用可）。`invoice.paid` で期末延長＋`PAST_DUE→ACTIVE` 回復 |
| AC-38 | 境界（実決済 D-2） | F08.9 会費の `invoice.*`（billing に無い subscriptionId）は membership 側へ・billing は関与しない。billing の subscriptionId（`psp_subscription_ref` 逆引きヒット）は membership が処理しない（**相互 no-op**） |
| AC-39 | 異常（実決済） | webhook 署名なし/不正 → 400・未処理（既存 `StripeWebhookController` の検証が billing イベントでも有効） |
| AC-40 | 境界（実決済 D-4） | 価格入力後の**新規契約のみ**決済必須へ切替。入力前に結ばれた無償契約（`price_jpy_snapshot=NULL`）は不変（解約も即時のまま・遡及なし） |
| AC-44 | 正常（実決済） | 新 preview/change API は upgrade を `invoice.paid` で確定、downgrade を翌月1日のSubscription Schedule適用で確定する。旧 changePlan は互換409のみ |
| AC-45 | 正常（実決済 検分差し戻し2番） | 退会 purge 確定（`AccountPurgedEvent`）で USER スコープの PENDING/ACTIVE/PAST_DUE 契約を CANCELLED＋pointer DELETE＋entitlements revoke＋evict し、有償契約は Stripe サブスクを**即時解約**（期末解約ではない・課金継続事故防止）。申請（猶予中）・撤回は明示 no-op（権利維持） |
| AC-46 | 異常（実決済 検分差し戻し3番） | 期末解約予約済み（ACTIVE のまま `cancelled_at` セット済み）の有償契約への再解約 DELETE は `ENTITLEMENT_011`(409)（cancel_at_period_end 再送・valid_until 再上書きの防止） |
| AC-47 | 正常（実決済） | `checkout.session.expired` で PENDING 契約は CANCELLED＋pointer 物理 DELETE（`uk_acp_slot` スロット解放＝同一スコープで再挑戦可能）。PENDING 以外への再送は no-op（冪等） |

---

## 6. 段階ロードマップ

| 段 | 名称 | 規模 | 依存 | 主要成果 |
|---|---|---|---|---|
| **P1** | エンタイトルメント機構＋プラン提示＋結線 | **L** | なし | 全テーブル・`isEntitled`/`EntitlementGuard`・シスアド CRUD・`hasPaidPlan` Expand 委譲・i18n（**org_type イベント〔営利自動切替〕は含めない＝Phase 2 保留**・§3.3） |
| **P2** | ベータ特典接続 | **M** | P1・F20.3 | `source_kind=BETA_GRANT` の発行・取消（F20.3 が主管） |
| **P3** | FE 課金 UI | **M** | P1 | プラン一覧・ペイウォールモーダル・課金管理画面（04） |
| **Phase 2a** | 営利自動切替（org_type 自動変異一式） | **M** | P1・organization/notification/audit ドメイン | `RevenueFeatureActivatedEvent`・org_type 自動更新・確認必須通知・差し戻し API・監査・R-1 自動判定（§3.3・02 §7・**別軍議**）。または「運営レビューのキューに積むソフトなシグナル」への再設計 |
| **Phase 2b** | 実決済（PSP 連携） | **L** | P1〜P3・ベータ価格確定 | **🚧 実施中**: scope-owned Customer、Stripe Checkout/Subscription Schedule、invoice投影、請求書/領収書、支払方法、Webhook、期末解約・撤回を [05](05_billing_center.md) の正本で実装する。F22.1連携のみ将来軍議 |

> BE/API はテスト先行（memory `feedback_test_first_be_api`）: 軍議 AC → `/試練` red → `/出陣` green → `/検分` 照合。

---

## 7. 計測（ベータ中の課金判断用データ）

- スコープ別 feature_key 利用回数・人数分布の計測は **F20.3 §7** が主管（F10.8 アクセス解析へ利用イベント 1 種を追加連携）。
- 集計ダッシュボードは本 F20.1 の**将来拡張**（Phase 2 以降）と位置づけ、本設計では作らない。

---

## 8. 要裁可論点 → マスター御裁可済（2026-07-08）

> **2026-07-08 マスター御裁可**: R-1=(b)確定・R-2=(c)確定・R-4=(a)確定。R-3 は運用データ待ち（実装ブロックせず）。以下の「確定」列がマスター裁可の結果。

| # | 論点 | 選択肢 | **御裁可済（2026-07-08）** |
|---|---|---|---|
| **R-1** `[P2]` | org_type 自動更新の対象範囲＋**誤変異のロールバック経路（M-3）** | (a) `COMPANY` 以外すべて自動更新／(b) `NPO`・`ASSOCIATION`・`COMMUNITY`・`OTHER` のみ自動更新、公共系（`GOVERNMENT`/`MUNICIPALITY`/`SCHOOL`/`HOSPITAL`）は通知＋運営レビューのみ＋差し戻し | **✅ (b) 確定**（**この自動判定ロジック自体が営利自動切替＝Phase 2 保留のため、R-1 のスコープ判定は Phase 2 実装時に適用する**・§3.3・冒頭 Phase 2 保留ブロック）。NPO・任意団体等のみ自動で営利切替、公共系（学校・市役所等）は自動変異せず通知＋運営レビュー＋差し戻し。差し戻しは運営 API `POST /api/v1/system-admin/organizations/{orgId}/org-type-revert`＋監査（org_type の権威は organization ドメイン）。確認必須通知に「区分が違う場合はお問い合わせください」の異議導線を含める |
| **R-2** | 無所属チームの営利/非営利区分の持ち方 | (a) `teams` に区分列を追加／(b) billing ドメイン側に scope 区分テーブルを新設／(c) **列追加なし**・「ACTIVE 所属組織から都度導出、無所属は非営利扱い」 | **✅ (c) 確定**。列追加なし・都度導出・無所属=非営利扱い。REVENUE 機能は区分問わず有料のため悪用余地は INTERNAL 無料枠のみで小さい（YAGNI） |
| **R-3** | BASIC プランの機能構成・想定価格 | 機構は本設計で完成。構成・価格は運用データ待ち | 🕒 ベータ計測（F20.3 §7）後に決定（実装ブロックせず・設計は NULL 可で先行） |
| **R-4** | ORG 契約の配下チーム展開（組織一括契約でチームスコープにも効かせるか） | (a) 展開しない（本設計）／(b) `plan_features` 側に展開フラグ | **✅ (a) 確定**。展開しない。実需が出れば (b) を Phase 2 で検討 |

---

## 9. 変更履歴

| 日付 | 内容 |
|---|---|
| 2026-07-10 | **実決済（Phase 2b）前倒し実装**（マスター御裁可 D-1〜D-4）。D-1: PSP 列（`psp_customer_ref`/`psp_subscription_ref`/`current_period_end`・V151）前倒し／D-2: Stripe Checkout `Mode.SUBSCRIPTION`・**自社受取（Connect 不使用）**・webhook は `psp_subscription_ref` 逆引きで F08.9 会費と分離／D-3: 無償解約=即時失効・有償解約=期末解約（`cancel_at_period_end`＋valid_until 保険）／D-4: 価格マスタ NULL=無償ワンクリック（既存 P1 フロー）・価格設定済み=Checkout 決済フロー（PENDING→入金で ACTIVE）・既存無償契約に遡及しない。`ContractStatus` に `PENDING`/`PAST_DUE` 追加・AC-31〜40 追補・`ENTITLEMENT_015`/`016` 採番 |
| 2026-07-08 | 初版。マスター合意済み要求仕様（3プラン提示×feature_key エンタイトルメント・スコープ判定・BE ゲート必須・営利/非営利イベント駆動・既存機構温存＋内部委譲・資金決済法回避・PSP 非依存先行）を反映して起草。origin/main 実機棚卸し（`ScopeKind`/`TeamSubscriptionEntity`/`TeamPlanService`/`ModuleService`/`ReservationNotificationRecipientService`/`organizations.org_type` V9.091/`team_org_memberships` V2.011/`teams` V2.004 区分列なし）を一次ソースに設計 |
