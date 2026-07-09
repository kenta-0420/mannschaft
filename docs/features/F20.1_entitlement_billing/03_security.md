# F20.1 — 03 セキュリティ

> **ステータス**: 🟢 設計完了（マスター御裁可済・実装待ち／営利自動切替・オーナー変更は Phase 2 保留）
> **⚠️ Phase 2 保留（マスター 2026-07-08）**: 営利自動切替（org_type 自動更新・確認通知・差し戻し API・監査 `ORG_TYPE_*`）は初期スコープ外（README §3.3・冒頭 Phase 2 保留ブロック）。本書の org_type 関連の認可・監査記述はすべて Phase 2。
> 認可基盤は `@EnableMethodSecurity`＋`@accessGuard`（`docs/security/03_role_authority_model.md`）を再利用。横断方針は [docs/security/README.md](../../security/README.md) に従う。ベータ中（Phase 1）は決済なし＝PCI 論点なし（Phase 2 で F22.1/決済系の規約を適用）。

---

## 1. 認可マトリクス

| 操作 | 許可される主体 | 検証（`@PreAuthorize` レベル） |
|---|---|---|
| プランカタログ閲覧（`GET /billing/plans`） | 認証ユーザー | `isAuthenticated()` |
| 権利サマリ閲覧（`GET .../entitlements`） | USER=本人 / TEAM=当該チームのメンバー以上 / ORG=当該組織のメンバー以上 | me は本人固定（scopeId をパラメータで受けない）／`@accessGuard.isScopeMember(authentication, #teamId, 'TEAM')` 等 |
| 単一判定（`GET /billing/entitlements/check`） | 当該スコープのメンバー以上（USER は本人のみ） | Service 層で scopeKind 別に所有権検証（§2.2） |
| 契約作成/解約/変更（TEAM/ORG） | 当該スコープの ADMIN（DEPUTY_ADMIN 含む） | `@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')` / `@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')` |
| 契約作成/解約/変更（USER） | 本人 | `/me/...` パス＝`SecurityUtils.getCurrentUserId()` 固定（scopeId を受けない） |
| マスタ CRUD・手動付与・契約横断検索 | SYSTEM_ADMIN | `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` |
| org_type 自動更新 **【Phase 2 保留】** | システム（イベントリスナー）のみ | **営利自動切替に属し初期スコープ外**（マスター 2026-07-08・README §3.3）。Phase 2 で実装。API 経由の直接更新は organization ドメイン既存 API の認可に従う（billing からは**イベントのみ**・直接 UPDATE 禁止） |

- `@accessGuard.isScopeAdmin(...)` は **SYSTEM_ADMIN または当該 scope の ADMIN/DEPUTY_ADMIN** を許可する実在パターン（scopeType は SpEL 文字列リテラル `'TEAM'` / `'ORGANIZATION'`）。厳格版 `isScopeStrictAdmin`（DEPUTY 除外）は本機能では使わない（契約操作は DEPUTY_ADMIN にも許可＝モジュール ON/OFF と同等の運用権限とみなす）。
- `isAdmin` 常時 true 等の負論理・独自可視性述語を禁止（memory `feedback_visibility_bypass_f00_audit`）。

---

## 2. scopeId 所有権検証（IDOR 対策の核心）

**過去事故**: マッチングドメインで `getCurrentUserId()` を teamId に流用した認可漏れ IDOR（memory `project_matching_authz_userid_as_teamid_idor`・#2134/#2147 で全閉塞）。本ドメインは scopeKind×scopeId を常に受けるため、同型の事故を設計段階で封じる。

### 2.1 原則（全 API に適用）

1. **`getCurrentUserId()` を scopeId として流用することは常に誤り**。USER スコープは `/me/...` パスで scopeId をリクエストから**受けない**（本人固定）。TEAM/ORG はパス変数の scopeId に対して**ロール判定**を行う。
2. **パス変数の ID をそのまま別スコープの ID として信頼しない**。子リソース（`contractId`）を受ける API は、**Service 層で contractId から所属スコープを解決し、パスのスコープと一致検証**する（一般形: 子リソース ID → 所属 scopeID を解決 → その scope に対しロール判定）。

```java
// 解約 API（§02 3.2）の二重防御（擬似コード）
@PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")   // 1段目: パスの teamId に対する権限
public void cancelContract(Long teamId, UUID contractId) {
    BillingContractEntity c = billingContractRepository.findById(contractId)
        .orElseThrow(() -> new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND));
    if (c.getScopeKind() != TEAM || !c.getScopeId().equals(teamId)) {          // 2段目: 契約の所属スコープ一致
        throw new BusinessException(EntitlementErrorCode.CONTRACT_NOT_FOUND);  // 404 秘匿（存在も明かさない）
    }
    ...
}
```

3. **所有者系クエリは `findByIdAndXxx` で物理封鎖**する（`ShiftPdfService.checkMemberAndNotSupporter` の実装済先例と同型。「find してから if」より「条件付き find」を優先し、検証漏れをクエリ構造で不可能にする）: `findByIdAndScopeKindAndScopeId(contractId, scopeKind, scopeId)`。
4. **他チームの scopeId を指定した契約 API は 403**（AC-10・`ENTITLEMENT_005` または `@accessGuard` の deny）。他スコープの契約 ID を指定した参照/解約は **404 秘匿**（`ENTITLEMENT_007`・存在自体を明かさない）。

### 2.2 判定 API（check）の探索防止

`GET /billing/entitlements/check` は scopeKind/scopeId をクエリで受けるため、**呼び出し元が当該スコープのメンバーであることを Service 層で必須検証**する:

```
assertScopeReadable(caller, scopeKind, scopeId):
  USER → scopeId == caller.userId でなければ ENTITLEMENT_005 (403)
  TEAM → accessGuard.isScopeMember(caller, scopeId, 'TEAM') でなければ 403
  ORG  → accessGuard.isScopeMember(caller, scopeId, 'ORGANIZATION') でなければ 403
```

- 「どのチームがどのプランか」は営業秘密ではないが、**無認可の横断列挙**（scopeId を走査して契約状況をスキャンする）を防ぐ。シスアドの横断検索は専用 EP（02 §4）に隔離。

---

## 3. 402 / 403 の使い分け（正準）

| 状況 | HTTP | コード | FE の期待挙動 |
|---|---|---|---|
| 権利なし・**購入手段あり**（addon_available=true または enabled な非 FREE プランに掲載） | **402 Payment Required** | `ENTITLEMENT_003` | ペイウォールモーダル（04 §2）を開き購入導線を表示 |
| 権利なし・購入手段なし（カタログ enabled=false・どのプランにも未掲載） | **403 Forbidden** | `ENTITLEMENT_004` | 「この機能は利用できません」表示のみ |
| スコープ所有権なし（IDOR） | **403** | `ENTITLEMENT_005` | 権限エラー表示 |
| 対象リソース秘匿（他スコープの契約 ID 等） | **404** | `ENTITLEMENT_007` | not found 表示 |

- **402 の前例**: `RESERVATION_029`・`AD_CAMPAIGN_CREDIT_EXCEEDED`・`MEMBERSHIP_BILLING_023`（いずれも `ERROR_CODE_STATUS_MAP` に `HttpStatus.PAYMENT_REQUIRED` 登録済）。本機能はこの前例に従い**「支払えば解決する」場合のみ 402** を使う。
- 既存結線先（`RESERVATION_029`/`TMPL_004`）は**既存コード・ステータスを維持**（02 §5・FE 後方互換）。

---

## 4. FE のみのペイウォール禁止（BE ゲート必須）

- **過去事故**: FE のみのペイウォールで本文 API が丸見え（memory `project_paywall_be_body_gate_required`）。
- 本機能でゲートする**すべての機能**は、FE の表示制御（`GET /billing/entitlements/check` による出し分け）に加えて、**BE の該当 EP 入口で `EntitlementGuard.require(...)` を必ず呼ぶ**。FE 制御だけの機能追加は検分で差し戻す（トレーサビリティ照合対象・AC-09）。
- ガードは**public 入口（Controller→Service の入口）に置く**。共有内部メソッドに置くとバッチ・イベント処理が巻き添えで 402 になる（memory `feedback_authz_gate_on_public_entry_not_shared_method`）。バッチ/システム経路はガードを通らない設計とし、その旨を各結線の実装時に明記する。

---

## 5. キャッシュと取消の整合

- 判定キャッシュ TTL 60 秒＋変更時 **scope 単位 evict**（02 §8・M-8）。**取消後も evict が確実に呼ばれる**ことを仕様の観測点とする（AC-16・M-9: テストは「evict 呼び出しの実行」を検証し、TTL 依存の時間観測は非決定的ゆえ CI に載せない。キャッシュミス後の `isEntitled=false` は別途単体テストで確認）。攻撃視点の「取消直後の駆け込み利用」は evict が即時なので実質発生せず（evict 失敗時のみ最大 TTL 60 秒）、ベータ（無償）・Phase 2（月額課金）いずれでも実害は軽微。決済連動の厳密失効が要る場合（Phase 2）は取消系のみ write-through＋短 TTL を再評価。
- **fail-safe**: カタログ不整合（feature_key 不明・enabled=false）は**拒否側**に倒す（02 §1.1・症状を隠さず WARN ログ）。

---

## 6. レート制限・濫用対策

- 契約作成/解約は scope 単位でレート制限（例: 1 スコープ 10 回/時・Valkey スライディングウィンドウ）。「契約⇔解約の高速反復」でイベント（org_type 通知）・監査ログを氾濫させる濫用を防ぐ。
- `GET /billing/entitlements/check` は認可検証（§2.2）で横断列挙を封じたうえ、通常のグローバルレート制限に委ねる（専用制限なし）。
- シスアド CRUD は SYSTEM_ADMIN 限定のため専用制限なし（audit_logs 記録のみ）。

---

## 7. 監査ログ

| イベント | 記録先 | 内容 |
|---|---|---|
| 契約作成/解約/変更 | `audit_logs` | 操作者・scope・plan/feature・スナップショット値 |
| 手動付与（シスアド） | `audit_logs` | シスアド userId・対象 scope・理由 note |
| entitlement 取消 | `entitlements.revoked_by`＋`audit_logs` | 取消者・由来（解約/退会/運営） |
| org_type 自動更新 **【Phase 2】** | `audit_logs`（`ORG_TYPE_AUTO_UPDATED`） | from/to・トリガーイベント内容（02 §7.2）。**営利自動切替＝Phase 2 保留**（README §3.3） |
| org_type 差し戻し（誤変異回復・M-3）**【Phase 2】** | `audit_logs`（`ORG_TYPE_REVERTED`） | 運営操作者・from(`COMPANY`)/to(元区分)・異議理由（R-1 ロールバック API）。**Phase 2 保留** |
| 運営レビュー要請（公共系/TEAM 経由）**【Phase 2】** | `audit_logs` | 対象 org・トリガー。**Phase 2 保留** |

---

## 8. GDPR・退会

- 退会は**イベント駆動**（M-4・01 §10）: `WithdrawalRequestedEvent`（申請・猶予開始）／`AccountPurgedEvent`（確定）を `BillingPurgeEventListener` が購読。**申請時は revoke せず権利維持**（撤回で復活不可のため・M-5）、**確定（purge）時に** USER スコープの ACTIVE 契約 CANCELLED＋pointer 削除＋entitlements revoke。撤回時は権利維持のまま。
- `created_by`/`revoked_by` は userId 論理参照のみで PII 非含有。表示は都度解決（退会者は匿名表示）。
- ベータ中は金銭記録が発生しないため会計保持義務なし。Phase 2 で決済記録の保持期間を F08.9 §6 と同型で再整理する。

---

## 9. ステータス確定条件 → マスター御裁可済（2026-07-08）

論点（README §8 と対応）はすべてマスター裁可済み:

| # | 論点 | **御裁可済（2026-07-08）** |
|---|---|---|
| R-1 **`[P2]`** | org_type 自動更新の対象範囲（公共系の扱い・TEAM 経由の扱い）＋誤変異ロールバック（M-3） | **✅ 確定**（**この自動判定ロジック自体が営利自動切替＝Phase 2 保留のため、R-1 のスコープ判定は Phase 2 実装時に適用**・README §3.3）: 自動更新は ORG 自身の商用行動×{NPO, ASSOCIATION, COMMUNITY, OTHER} のみ・公共系は不変で通知＋運営レビュー・BETA_GRANT/手動付与は不発火・誤変異は運営差し戻し API（`ORG_TYPE_REVERTED`）で回復 |
| R-2 | 無所属チームの区分の持ち方 | **✅ 確定**: 列追加なし・都度導出・無所属=非営利扱い |
| R-3 | BASIC プランの構成・価格 | 🕒 ベータ計測後の運用決定（設計は NULL 可で先行・実装ブロックせず） |
| R-4 | ORG 契約の配下チーム展開 | **✅ 確定**: 展開しない・Phase 2 で再評価 |

> R-1/R-2/R-4 は御裁可済み（イベントリスナーの分岐・判定サービスの導出ロジックが確定）。R-3 のみ運用データ待ちだが実装をブロックしない（マスタデータの後入れ）。
> **【Phase 2 保留】R-1（org_type 自動更新＝営利自動切替）は初期スコープ外**（マスター 2026-07-08・README §3.3）。裁可内容は確定済みだが実装は Phase 2。R-2/R-4 は初期スコープの判定サービスに残る（無所属チーム＝非営利扱いの都度導出・配下チーム非展開は org_type を読むだけで自動変異には依存しない）。
