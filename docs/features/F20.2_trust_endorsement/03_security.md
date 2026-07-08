# F20.2 信任（信頼の輪）— 03. セキュリティ

> **ステータス**: 🟢 設計完了（マスター御裁可済・実装待ち）
> 親: [README.md](README.md) ／ 関連: [01_data_model.md](01_data_model.md) / [02_api_design.md](02_api_design.md)
> 参照: `docs/security/README.md` / `docs/security/01_authorization_baseline.md` / `docs/security/03_role_authority_model.md`

---

## 1. 脅威モデル（この機能で守るもの）

| 脅威 | 内容 | 対策 |
|---|---|---|
| **信任リング（sybil）** | 未認証団体を量産し互いに信任して自己認証する | 「信任元は `CERTIFIED` のみ」不変条件（構造的阻止・README §3.1・`TRUST_001`）＋アンカー起点 |
| **信任の乱発・買収的発行** | 1 団体が大量の信任を発行し認証の価値を希釈 | 年間発行上限（`TRUST_004`）＋資格条件（設立 N ヶ月・メンバー M 人・`TRUST_003`） |
| **scopeId 詐称（IDOR）** | 他団体の `endorserScopeId` を詐称して当該団体名義の信任を発行 | **scope 所有権検証必須**（§3）・404 秘匿 |
| **認証マークの信頼毀損** | REVOKED 団体・未認証団体がマークを表示 | 表示判定を `TrustBadgeVisibility` に一元化（README §6）・状態は BE がゲート（FE 判定に頼らない） |
| **非公開団体の情報漏洩** | PRIVATE 団体の存在・認証状態・信任関係が公開 API から漏れる | F00 可視性ゲート＋404 秘匿（§4） |
| **存在オラクル（ID 列挙）** | 信任付与 API の endorsee 検証応答の違い（不在=404 / 実在 PRIVATE=別応答）を悪用し、PRIVATE 団体の ID を総当りで列挙する | 付与時の endorsee 検証は **F00 可視性で行い**、不在・削除済み・不可視を**同一応答 `TRUST_007`（404）に統一**（§3・02 §2.3・README §11-6 関連脅威） |
| **再審査中の外部識別** | 公開 API の生 `state` から `UNDER_REVIEW`（信頼が揺らいでいる団体）を外部が識別 | 公開 DTO は `UNDER_REVIEW` を `CERTIFIED` に**丸めて返す**（§4.2・02 §6.1） |
| **運営 API の悪用** | 一般ユーザー/テナント管理者がアンカー付与・REVOKE | `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`（§3）＋監査ログ |

---

## 2. deny-by-default 許可リスト（SecurityConfig）

公開（未ログイン可）は **GET の 2 系統のみ**。POST/DELETE は一切 permitAll に入れない（`.authenticated()` が既定でカバー）。

```java
// SecurityConfig への追加（GET のみ・1 階層 * 限定・/** 再帰禁止）
.requestMatchers(HttpMethod.GET, "/api/v1/trust/certifications").permitAll()
.requestMatchers(HttpMethod.GET, "/api/v1/trust/endorsements").permitAll()
```

- クエリパラメータ方式（`?scopeKind=&scopeId=`）のためパスパターンは固定 2 本で済む（ワイルドカード不要）。
- `docs/security/01_authorization_baseline.md` の許可リスト表へ本 2 エンドポイントを**同一 PR で追記**する（baseline との drift を作らない）。
- 運営 API（`/api/v1/system-admin/trust/**` 相当のパス群）は permitAll に入れず、Controller クラスレベル `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` で防御（既存 SystemAdmin 系と同型・二層目は SecurityConfig の `.authenticated()`）。

---

## 3. 認可マトリクス

### 3.0 認可の実装型（実在パターンの逐語・取り違え禁止）

- **per-scope 管理者判定**は既存のガード Bean `@Component("accessGuard")`（`common.security.AccessGuard`・`AccessControlService` 委譲・null/非認証は false）を `@PreAuthorize` の SpEL から呼ぶ:

```java
// 信任の付与（endorser 側 scope の管理者であること）— scopeKind で SpEL を分岐せず、
// Controller で TrustScopeKind → 'TEAM' / 'ORGANIZATION' 文字列リテラルに解決してから判定する。
// isScopeAdmin = SYSTEM_ADMIN or 当該 scope の ADMIN/DEPUTY_ADMIN を内包
@PreAuthorize("@accessGuard.isScopeAdmin(authentication, #request.endorserScopeId, 'TEAM')")        // TEAM の場合
@PreAuthorize("@accessGuard.isScopeAdmin(authentication, #request.endorserScopeId, 'ORGANIZATION')") // ORG の場合

// 運営 API（アンカー付与・REVOKE・再審査キュー）
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
```

- `scopeType` は SpEL **文字列リテラル `'TEAM'` / `'ORGANIZATION'`**（`'ORG'` ではない・README §1.4 のマッピング表を厳守）。
- リクエスト DTO の `endorserScopeKind` により判定 scopeType が動的に変わるため、実装は (a) `@PreAuthorize` を使わず Service 冒頭で `TrustScopeResolver.requireScopeAdmin()`（内部で `accessGuard.isScopeAdmin` 相当を kind 別に呼び分け）とするか、(b) TEAM/ORG で Controller メソッドを分ける。**推奨 (a)**（エンドポイントを増やさない・kind→scopeType 解決を `TrustScopeResolver` に一元化）。
- 厳格版 `isScopeStrictAdmin`（DEPUTY_ADMIN 除外）・メンバー判定 `isScopeMember` も存在するが、本機能の信任操作は **`isScopeAdmin`（DEPUTY_ADMIN 含む）**でよい（信任は団体の対外行為だが破壊的操作ではない。厳格版が必要とマスターが判断すれば差し替えは 1 箇所）。
- **IDOR 封鎖の一般形**: 子リソース ID（`endorsementId` 等）を受ける API は、**パス変数の ID をそのまま信頼せず、Service 層で子リソースから所属 scope ID を解決し、その scope に対してロール判定**する（§3.1）。

| 操作 | 認可主体 | 実装方針 |
|---|---|---|
| 信任の付与（#1） | **endorser 団体の scope ADMIN** | `TrustScopeResolver.requireScopeAdmin(authentication, endorserScopeKind, endorserScopeId)` → 内部で `accessGuard.isScopeAdmin(authentication, scopeId, 'TEAM'|'ORGANIZATION')`（§3.0）。**endorsee は「操作者から F00 可視」の場合のみ許可**（`ContentVisibilityChecker.canView`・不在・不可視は同一応答 `TRUST_007` で存在オラクル封鎖・02 §2.3。endorsee 側の同意フローは設けない＝マスター御裁可済 §11-6(a)・§9.1） |
| 信任の取消（#2） | **当該信任の endorser 団体の scope ADMIN** | `endorsementId` から **DB の endorser scope を解決**して所有権検証（IDOR 一般形・リクエスト値を信頼しない） |
| 資格事前確認（#5） | 対象団体の scope ADMIN | `isScopeAdmin`（§3.0） |
| 認証状態取得（#3） | 公開（未ログイン可） | F00 可視性ゲート（§4）・PRIVATE は 404 秘匿 |
| 信任関係一覧（#4） | 公開（未ログイン可） | 同上。管理者向け拡張項目（`endorsementId`）は endorser ADMIN 認証時のみ（02 §6.2） |
| アンカー付与/解除（#6/#7） | **SYSTEM_ADMIN のみ** | `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`（クラスレベル・既存 SystemAdmin 系と同型） |
| REVOKE（#8） | SYSTEM_ADMIN のみ | 同上・`reason` 必須・監査ログ必須 |
| 再審査キュー/OK/NG（#9〜#11） | SYSTEM_ADMIN のみ | 同上 |

### 3.1 userID→scopeId 流用 IDOR の再発防止（前科あり・必読）

マッチングドメインで「`getCurrentUserId()` の戻り値を teamId として認可に流用」する IDOR が全域発生した前科がある（memory `project_matching_authz_userid_as_teamid_idor`・`docs/security/03_role_authority_model.md`）。本機能は信任の主体が**団体**であり、リクエストに `endorserScopeId` を含むため同型の危険がある。以下を機械的に守る:

1. `endorserScopeId` は**常にリクエスト由来の untrusted 値**として扱い、`checkAdminOrAbove(currentUserId, endorserScopeId, scopeType)` の**所有権検証を必ず通す**。`currentUserId` を scopeId 引数に渡すコードは常に誤り。
2. 取消（#2）は**リクエストの scopeId を信用せず**、`endorsementId` から DB の endorser scope を引いて検証する。
3. 認可ガードは **public 入口（Controller→Service の公開メソッド）に置く**（共有内部メソッドに置くとバッチ経路が巻き添えになる・`feedback_authz_gate_on_public_entry_not_shared_method`）。REVOKE 連鎖（02 §5.3）の内部無効化は運営 tx 内の内部処理であり、endorser ADMIN 検証を通さない（通すと運営操作が壊れる）。
4. 契約テストで「他団体 scopeId 詐称 → 403/404」（AC-13）を必須ケースにする。

### 3.2 存在秘匿（404）の規約

- 認可 NG のうち「対象の存在を漏らしたくない」ケース（無関係 scope の信任・PRIVATE 団体の照会）は `TRUST_007`（404）で秘匿する。
- 「操作者に権限がない」ことを伝えてよいケース（自団体は見えているが ADMIN でない）は `TRUST_009`（403）。
- 判定: 対象団体が viewer にとって F00 可視なら 403、不可視なら 404（F19.1/F22.1 と同じ整理）。

---

## 4. 可視性（F00 経由）と公開 DTO の禁則

### 4.1 F00 ゲート（独自述語を作らない）

- 公開 API（#3/#4）は、認証情報を返す**前**に対象 scope の可視性を F00 で判定する: **`ContentVisibilityChecker.canView(ReferenceType.TEAM | ReferenceType.ORGANIZATION, scopeId, viewerUserIdOrNull)`**（未ログインは `userId=null`）。実体は既存の `TeamVisibilityResolver` / `OrganizationVisibilityResolver` へ `ReferenceType` でディスパッチされる（`ContentVisibilityChecker` がファサード）。※F19.1 の `IdentityVisibilityResolver` は投稿者識別（氏名・アバター段階開示）用で**本機能では使わない**（別物）。
- **trust ドメイン独自の可視性述語（`teams.visibility='PUBLIC'` の直接 WHERE 等）を書かない**（`feedback_visibility_bypass_f00_audit`・漏洩源になる）。
- 信任関係一覧の**相手方**が PRIVATE 団体の場合: 信任関係自体は「双方のプロフィールに公開」が要件のため相手方の**団体名と認証マーク**は返すが、`counterpartPublicSlug` は相手方が F00 可視（PUBLIC）のときのみ返す（PRIVATE 団体への公開ページリンクを作らない・02 §6.2）。

> **確定既定（§11-7(b)）**: 「PRIVATE 団体が信任関係に載ると団体名が未ログインに露出する」点は、要件「信任関係は双方のプロフィールに公開」と F19.1 の非公開原則が衝突しうる。**本設計は安全側 §11-7(b) を確定既定とする（マスター裁可待ち）**: 公開面の incoming/outgoing 一覧は「相手方が viewer から F00 可視である信任のみ」を返し、PRIVATE 団体を相手とする信任は公開面の一覧から除外する（当該 PRIVATE 団体側のログイン済みメンバー向け画面では表示可）。件数 `validEndorsementCount` は除外に関わらず**全件のまま**カウントする（README §11-7・AC-24・[02 §6.2](02_api_design.md)）。

### 4.2 公開 DTO の禁則フィールド（CI 契約テストで機械確認）

公開レスポンス（#3/#4）に以下を**絶対に含めない**:

```
under_review_since / revoke_reason / revoked_by_user_id / granted_by_user_id /
endorsementId（公開面）/ organization_id（テナント内部値）/
email / 氏名等の個人 PII 全般（本機能は団体単位・個人情報を返す面がない）
```

- `state=REVOKED`/`UNDER_REVIEW` の**理由・経緯は公開しない**。さらに **公開 DTO は `UNDER_REVIEW` の生値を返さず `CERTIFIED` に丸める**（`TrustBadgeVisibility.publicState(state)` に一元化・値域は `UNCERTIFIED`/`CERTIFIED`/`REVOKED` の 3 値・02 §6.1）。生 state（4 値）は**当該団体管理者向け（04 §4 管理タブ）と運営向け（02 §7.4）の認証済み DTO 限定**。これにより `UNDER_REVIEW` は外形上 `CERTIFIED` と完全同一になり、外部から再審査中の団体を識別できない（**マークの見た目も CERTIFIED と同一**・04 §2。i18n にも公開向け「再審査中」文言を作らない）。

---

## 5. GDPR・退会・団体削除

| データ | 扱い | 区分 |
|---|---|---|
| `trust_endorsements`（信任台帳） | **匿名化せず保持**（監査・統計証跡）。`granted_by_user_id`/`revoked_by_user_id` は論理参照のみで、操作者の PII は user 側の退会匿名化で消える（CLAUDE.md 原則 4・user_id 残置） | 匿名化しない |
| `trust_certifications` | 団体の属性であり個人 PII を含まない。保持 | — |
| 団体（TEAM/ORG）の削除・アーカイブ | 認証行・信任行は**物理保持**（クロスドメイン CASCADE なし・原則 2）。公開 API は F00 ゲートで削除済み団体を 404 秘匿。削除団体が endorser の有効信任は**確定仕様として無効化する**（README §5.1 T12・AC-27）: 既存の `TeamDeletedEvent`/`OrganizationDeletedEvent`（origin/main 実在・削除フローが発火済み）を trust 側リスナで購読し、outgoing 有効信任を `revoke_reason='ENDORSER_DELETED'` で無効化＋被信任先を再計算（1 段・[02 §5.4](02_api_design.md)）。**耐障害性**: このカスケードは通知（ベストエフォート）と別格で、AFTER_COMMIT リスナ失敗による残留を**日次整合バッチ `TrustConsistencyBatch` の「孤児信任検出・修復」条件（§8・02 §5.4.1）で補償**する。放置すると「削除済み団体の信任」が有効件数に残り続け認証の実体が崩れる（症状を隠さない） | — |

---

## 6. レート制限

| 対象 | 制限 | 根拠 |
|---|---|---|
| 信任付与（#1） | 10 req/hour/user | 年間上限（TRUST_004）とは別に API 連打・総当り endorsee 探索を抑止 |
| 信任取消（#2） | 10 req/hour/user | 付与⇄取消の振動（フラッピング）で通知・キューを荒らす攻撃の抑止 |
| 認証状態取得/一覧（#3/#4・公開） | 60 req/min/IP | 未ログイン公開 API のスクレイピング抑止（F19.1 公開系と同水準） |
| 運営 API | なし（SYSTEM_ADMIN のみ） | 運営操作を阻害しない |

### 6.1 実結線（origin/main `common.ratelimit` 機構）

既存のレート制限基盤（`common.ratelimit`）を流用する。実クラスは以下:

- **`AbstractRateLimitFilter`**（`OncePerRequestFilter` ベース・パス/メソッドで対象判定→キー導出→`ValkeyRateLimiter` 判定→超過は 429）。trust 用に `TrustRateLimitFilter extends AbstractRateLimitFilter` を新設し、対象パスと `RateLimitRule`（窓・上限）を定義する。
- **`ValkeyRateLimiter`**: Valkey（Redis 互換・スライディング/固定窓のカウンタ）バックエンド。**Valkey 依存**（本番・E2E とも Valkey 稼働が前提。未起動だとフィルタが素通り or フェイルする挙動は既存機構の設定に従う）。
- **`RateLimitRule`**: 窓長・上限・キー種別を保持する値。付与/取消は `RateLimitRule(window=1h, limit=10, keyType=USER)`、公開 GET は `RateLimitRule(window=1min, limit=60, keyType=IP)` を登録。
- **キー導出**:
  - 付与（#1）/取消（#2）= **認証ユーザー ID キー**（`userId` を rate-limit キーに含める。認証必須エンドポイントのため IP でなく user 単位で正確に制限）。
  - 公開 GET（#3/#4）= **クライアント IP キー**（未ログインで userId が無いため。`X-Forwarded-For` の信頼は既存フィルタの IP 解決規約に従う）。
- 429 応答は既存の `RateLimitResult`／グローバルハンドラの 429 表現に合わせる（trust 独自のエラーコードは作らず既存の Too Many Requests 表現を使う）。超過は握り潰さず 429 で明示（症状を隠さない）。

---

## 7. 監査ログ（`audit_logs`）

以下を必ず記録する（既存 `AuditLogService`）:

| イベント | eventType | 記録内容（metadata） |
|---|---|---|
| 信任付与 | `TRUST_ENDORSEMENT_GRANTED` | endorser/endorsee scope・endorsementId・操作 user |
| 信任取消 | `TRUST_ENDORSEMENT_REVOKED` | 同上＋revoke_reason |
| 状態遷移 | `TRUST_STATE_CHANGED` | scope・old→new state・トリガ（付与/取消/連鎖/運営） |
| アンカー付与/解除 | `TRUST_ANCHOR_GRANTED` / `TRUST_ANCHOR_REMOVED` | scope・note・操作 SYSTEM_ADMIN |
| REVOKE | `TRUST_CERTIFICATION_REVOKED` | scope・reason・無効化件数・連鎖降格先 |
| 再審査 OK/NG | `TRUST_REVIEW_APPROVED` / `TRUST_REVIEW_REJECTED` | certificationId・note/reason |

---

## 8. その他の実装セキュリティ規約

- **課金との分離**: trust ドメインは payment/billing/entitlement のどの Service も**呼ばない**（README §1.3-4）。ArchUnit で `trust` → `payment|billing|entitlement` 依存禁止を番人化することを推奨。
- **状態遷移の唯一入口**: `state` の変更は `TrustCertificationService.recalculateState`／運営操作メソッドのみ（Controller や他ドメインから Entity の setter を直接叩かない）。
- **通知はベストエフォート**: 通知送信失敗で信任 tx をロールバックしない（AFTER_COMMIT＋REQUIRES_NEW・02 §9）。失敗はログ＋メトリクスで可視化（握り潰さない）。
- **整合バッチ `TrustConsistencyBatch`（日次・二条件）**: (a) **孤児信任の検出・修復** — 有効信任（`revoked_at IS NULL`）のうち endorser が team/org 側で削除済みのもの（削除カスケードのリスナ失敗による残留・02 §5.4.1）を `revoke_reason='ENDORSER_DELETED'` で無効化し endorsee を再計算。(b) **`valid_endorsement_count` ドリフト検出** — `trust_endorsements` の実集計と突合。いずれもアラート付きで記録（自動修復してよいが必ず記録・01 §3.1・症状を隠さない）。削除カスケードは通知（ベストエフォート）と別格で、この補償経路により最終的な整合を保証する。
- **入力検証**: `scopeKind` は enum バインドで不正値を 400/422 に（文字列比較を散在させず `TrustScopeKind` へ変換して扱う）。

---

## 9. 未解決事項（解決方針付き）とステータス確定条件

### 9.1 未解決事項

- [x] 信任リングの構造的阻止 → 「信任元は CERTIFIED のみ」＋アンカー起点（§1・README §3.1）
- [x] scopeId 詐称 IDOR → 所有権検証必須（`accessGuard.isScopeAdmin`・子リソースは DB から scope 解決）＋404 秘匿＋契約テスト（§3.0/§3.1）
- [x] 連鎖剥奪の暴走 → 1 段制限＋UNDER_REVIEW 留め（README §3.7）
- [x] 削除済み団体の信任残留 → 削除イベント購読で outgoing 無効化（§5）
- [x] 通知種別 → 通常通知（F04.9 確認必須通知は不使用・被信任側に確認義務動作がないため。README §8 に根拠明記）
- [x] **endorsee 側の同意（受任の承諾）フローの要否** → **マスター御裁可済（2026-07-08・README §11-6＝(a)＋(c)将来拡張）**: 同意フローは設けない（信任は endorser の一方向の対外表明・endorsee は「操作者から F00 可視」検証のみ＝存在オラクル封鎖・§1/§3 マトリクス・02 §2.3）。公開面 opt-out（(c)）は将来拡張点として温存
- [x] **PRIVATE 団体が当事者の信任の公開範囲** → **マスター御裁可済（2026-07-08・README §11-7＝(b)安全側）**: 相手方が F00 可視の信任のみ公開面に出す・件数 `validEndorsementCount` は全件（§4.1）
- [x] **`UNDER_REVIEW` 団体の信任発行可否** → **マスター御裁可済（2026-07-08・README §11-3＝(b)発行不可）**: 発行資格は `state=CERTIFIED` 厳密一致のみ

### 9.2 ステータス確定条件（🟢 設計完了の関門・すべて充足）

- [x] README §11 の 7 論点（11-1〜11-7）にマスター御裁可（2026-07-08）が出て設計へ反映済み（README §11・本書 §9.1）
- [x] 認可が実在パターン（`accessGuard.isScopeAdmin` / `hasRole('SYSTEM_ADMIN')`）の逐語で記述され、userID→scopeId 流用がないことを精査で確認（§3.0/§3.1）
- [x] DB 原則適合（01 §8・`AbstractTenantAwareRepository` 非継承の EscrowTransaction 前例準拠）・F00 経由の可視性（§4・`ContentVisibilityChecker`）・公開 DTO 禁則（§4.2）を精査で確認
- [x] エラーコード採番（`TRUST_0xx`）と Flyway 版番号（`V146` 仮）はマージ時に origin/main と再照合する注記を残置（02 §8 / 01 §5）
