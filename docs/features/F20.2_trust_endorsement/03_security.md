# F20.2 信任（信頼の輪）— 03. セキュリティ

> **ステータス**: 🟡 設計中（精査待ち）
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

| 操作 | 認可主体 | 実装方針 |
|---|---|---|
| 信任の付与（#1） | **endorser 団体の scope ADMIN** | `TrustScopeResolver.requireScopeAdmin(currentUserId, endorserScopeKind, endorserScopeId)` → 内部で `AccessControlService.checkAdminOrAbove(userId, scopeId, scopeType)`。TEAM は `scopeType="TEAM"`・ORG は `scopeType="ORGANIZATION"`（**取り違え禁止**・README §1.4） |
| 信任の取消（#2） | **当該信任の endorser 団体の scope ADMIN** | endorsement 行から endorser scope を引いて所有権検証（リクエスト値でなく**DB の値**で検証） |
| 資格事前確認（#5） | 対象団体の scope ADMIN | 同上 |
| 認証状態取得（#3） | 公開（未ログイン可） | F00 可視性ゲート（§4）・PRIVATE は 404 秘匿 |
| 信任関係一覧（#4） | 公開（未ログイン可） | 同上。管理者向け拡張項目（`endorsementId`）は endorser ADMIN 認証時のみ（02 §6.2） |
| アンカー付与/解除（#6/#7） | **SYSTEM_ADMIN のみ** | `@PreAuthorize("hasRole('SYSTEM_ADMIN')")`（PLATFORM 運営ロール） |
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

- 公開 API（#3/#4）は、認証情報を返す**前**に対象 scope の可視性を F00 で判定する: `PublicTeamVisibilityResolver.canView(teamId, viewerUserIdOrNull)` / `PublicOrganizationVisibilityResolver.canView(orgId, ...)`（F19.1 で実装済・`ReferenceType.TEAM`/`ORGANIZATION`）。
- **trust ドメイン独自の可視性述語（`teams.visibility='PUBLIC'` の直接 WHERE 等）を書かない**（`feedback_visibility_bypass_f00_audit`・漏洩源になる）。
- 信任関係一覧の**相手方**が PRIVATE 団体の場合: 信任関係自体は「双方のプロフィールに公開」が要件のため相手方の**団体名と認証マーク**は返すが、`counterpartPublicSlug` は相手方が F00 可視（PUBLIC）のときのみ返す（PRIVATE 団体への公開ページリンクを作らない・02 §6.2）。

> ⚠️ **要精査注記**: 「PRIVATE 団体が信任関係に載ることで団体名が未ログインに露出する」点は、要件「信任関係は双方のプロフィールに公開」から導いた帰結である。PRIVATE 団体は通常 F19.1 で団体名も非公開のため、**厳密には矛盾がありうる**。安全側の代替案 =「PRIVATE 団体が当事者の信任は、当該 PRIVATE 団体側のページ（ログイン済みメンバー向け）でのみ表示し、公開面では相手方一覧から除外する」。本設計は**安全側の代替案を既定**とし、公開面の incoming/outgoing 一覧は「相手方が F00 可視である信任のみ」を返す（件数 `validEndorsementCount` は全件のまま）。→ 精査時に確定すること（README §11 要裁可論点に準ずる実装判断）。

### 4.2 公開 DTO の禁則フィールド（CI 契約テストで機械確認）

公開レスポンス（#3/#4）に以下を**絶対に含めない**:

```
under_review_since / revoke_reason / revoked_by_user_id / granted_by_user_id /
endorsementId（公開面）/ organization_id（テナント内部値）/
email / 氏名等の個人 PII 全般（本機能は団体単位・個人情報を返す面がない）
```

- `state=REVOKED`/`UNDER_REVIEW` の**理由・経緯は公開しない**（`badgeVisible` の boolean と state 値のみ）。`UNDER_REVIEW` は外形上「認証済み表示維持」であり、公開面で「再審査中」と示す必要はない（**マークの見た目は CERTIFIED と同一**・04 §2）。→ ただし state 値自体は #3 で返るため、FE 公開面では `badgeVisible` のみを使い state を表示文言化しない（i18n にも公開向け「再審査中」文言を作らない）。

---

## 5. GDPR・退会・団体削除

| データ | 扱い | 区分 |
|---|---|---|
| `trust_endorsements`（信任台帳） | **匿名化せず保持**（監査・統計証跡）。`granted_by_user_id`/`revoked_by_user_id` は論理参照のみで、操作者の PII は user 側の退会匿名化で消える（CLAUDE.md 原則 4・user_id 残置） | 匿名化しない |
| `trust_certifications` | 団体の属性であり個人 PII を含まない。保持 | — |
| 団体（TEAM/ORG）の削除・アーカイブ | 認証行・信任行は**物理保持**（クロスドメイン CASCADE なし・原則 2）。公開 API は F00 ゲートで削除済み団体を 404 秘匿。削除団体が endorser の有効信任は**運営 REVOKE と同型の無効化を行うか**が論点 → **既定: 団体削除イベント（既存の team/org 削除フロー）を購読し、当該団体の outgoing 有効信任を `revoke_reason='OPERATOR'` で無効化＋被信任先を再計算（1 段）**。放置すると「削除済み団体の信任」が有効件数に残り続け認証の実体が崩れる（症状を隠さない） | — |

---

## 6. レート制限

| 対象 | 制限 | 根拠 |
|---|---|---|
| 信任付与（#1） | 10 req/hour/user | 年間上限（TRUST_004）とは別に API 連打・総当り endorsee 探索を抑止 |
| 信任取消（#2） | 10 req/hour/user | 付与⇄取消の振動（フラッピング）で通知・キューを荒らす攻撃の抑止 |
| 認証状態取得/一覧（#3/#4・公開） | 60 req/min/IP | 未ログイン公開 API のスクレイピング抑止（F19.1 公開系と同水準・既存の公開 API レートリミッタ機構を流用） |
| 運営 API | なし（SYSTEM_ADMIN のみ） | 運営操作を阻害しない |

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
- **`valid_endorsement_count` の整合バッチ**: 日次で `trust_endorsements` の実集計と突合し、ドリフトはアラート（自動修復してよいが必ず記録・01 §3.1）。
- **入力検証**: `scopeKind` は enum バインドで不正値を 400/422 に（文字列比較を散在させず `TrustScopeKind` へ変換して扱う）。

---

## 9. 未解決事項（解決方針付き）

- [x] 信任リングの構造的阻止 → 「信任元は CERTIFIED のみ」＋アンカー起点（§1・README §3.1）
- [x] scopeId 詐称 IDOR → 所有権検証必須＋404 秘匿＋契約テスト（§3.1）
- [x] 連鎖剥奪の暴走 → 1 段制限＋UNDER_REVIEW 留め（README §3.7）
- [x] 削除済み団体の信任残留 → 削除イベント購読で outgoing 無効化（§5）
- [ ] **PRIVATE 団体が当事者の信任の公開範囲** → 既定=安全側（相手方が F00 可視の信任のみ公開面に出す・件数は全件）。精査で確定（§4.1 要精査注記）
- [ ] **`UNDER_REVIEW` 団体の信任発行可否** → 推奨 (b) 発行不可（README §11-3・マスター裁可待ち）
