# F08.9 — 03 セキュリティ

> 認可基盤は F00 `ContentVisibilityResolver` / `AccessControlService` / `@EnableMethodSecurity`（2026-06-02 点火・#1266）を再利用。決済 PCI は SAQ-A（カード情報は Stripe・Mannschaft 非保持）。
> 横断方針は [docs/security/README.md](../../security/README.md) に従う。

---

## 1. 認可マトリクス

| 操作 | 許可される主体 | 検証 |
|---|---|---|
| 会費決済（受益者指定） | 払い手＝本人 / 後見保護者 / 有効 grant 保有者 / チーム・組織 ADMIN(手動記録) | §2 代理払い認可 |
| 後見まとめ支払い | 本人（払い手） | `payable-dues` は自分が払える対象のみ返す |
| 後見切替開始 | 子の有効保護者 **かつ** 国別ポリシーが切替可（`switchAllowed`） | §3 年齢ゲート |
| 代理払い grant 発行 | 受益者本人（または切替可能な段階の子に代わり保護者） | 受益者所有権 |
| 継続課金 加入/解約/今月スキップ/再開 | 払い手本人 / 後見保護者 | サブスク所有権（payer_user_id）。skip/resume も同所有権（02 §4.3） |
| 協会請求の立替/精算確認 | 当該チーム ADMIN | `team_payment_advances.team_id` の team ADMIN（案3・02 §7） |
| ペイウォール設定 | チーム/組織 ADMIN | scope 所有権（既存 ContentPaymentGateController） |
| 集計・CSV・手数料明細 | チーム/組織 ADMIN | scope ADMIN（既存 PaymentSummary） |
| 領収書取得 | 受益者本人 / 払い手 / scope ADMIN | 当該支払いの関係者のみ |
| 協会請求 発行/取消/集計 | 組織(協会) ADMIN | org scope ADMIN |
| 協会請求 支払い | 請求先チームの ADMIN | `payment_requests.payer_scope_id == teamId` かつ team ADMIN |
| 返金 | 受領側 scope ADMIN（チーム/組織） | F22.1 返金規約（reverse_transfer:true / refund_application_fee:false） |

- `@PreAuthorize` で method-security を効かせる（#1266 で実効化済）。`isAdmin` 常時 true 等の負論理を禁止（[[feedback_visibility_bypass_f00_audit]]）。

---

## 2. 代理払いの認可（payer ≠ beneficiary の核心 IDOR 対策）

決済時、**払い手が受益者の会費を払う権原**を以下のいずれかで必須検証する（欠落時 `MEMBERSHIP_PAYER_NOT_AUTHORIZED` 403）。

```
authorizePayment(payerUserId, beneficiaryUserId, paymentItemId):
  if payerUserId == beneficiaryUserId: return SELF
  if parentalConsentLink(child=beneficiary, parent=payer).status == APPROVED: return GUARDIAN
  if userCareLink(recipient=beneficiary, watcher=payer, relationship=PARENT).status == ACTIVE: return GUARDIAN
  if paymentProxyGrant(beneficiary, payer, item|null).status == ACTIVE
       and now in [effective_from, effective_until]: return PROXY_GRANT
  if caller is ADMIN of scope(paymentItem) and manualRecord: return ADMIN_MANUAL
  else: throw MEMBERSHIP_PAYER_NOT_AUTHORIZED
```

- 結果（`SELF`/`GUARDIAN`/`GUARDIAN_PROXY`/`PROXY_GRANT`/`ADMIN_MANUAL`）と権原ID（grant_id 等）を `member_payments.payer_relationship`/`payment_proxy_grant_id` に**記録**（監査・非否認）。**後見切替セッション中（`X-Proxy-For-User-Id` 付き）の決済は `GUARDIAN`（保護者リンクで権原成立）だが `payer_relationship=GUARDIAN_PROXY` として区別記録**し、「子の自己払い」と誤読させない。
- **F14.1 の代理権は本 authorizePayment の経路に含めない**（日常の代理払いは SELF/保護者リンク/grant/ADMIN の4経路のみ）。代理権スコープ `PAYMENT` は紙同意書ベースの**組織代理の重い経路**として温存し、必要時に別途評価する（README §3.3 と一致）。
  - **是正（2026-06-04）**: scope `PAYMENT` は実在の `proxy_input_consent_scopes.feature_scope`（VARCHAR(64)・V18.011・CHECK なし・実機確認済）に **enum 値 `PAYMENT` を1つ足すだけ**で表現する（`proxy_input_consents` 本体への列追加・DDL は不要）。代理払い認可・退会失効はこの scope 行（同意書ごとの許可スコープ）で判定する。
  - **実装（P3b・2026-06-04）**: `FeatureScope.PAYMENT` を追加（DDL 不要）。`ProxyInputContextFilter` は検証済み同意書の許可スコープ集合を `ProxyInputContext.activate(...)` に渡し、決済系 Service は `ProxyInputContext.hasScope(FeatureScope.PAYMENT)` で代理払いの要求スコープを検証できる（素地）。実際の代理払い認可経路（`authorizePayment` での scope `PAYMENT` 評価）は P1/P3c の管轄。
- **IDOR 防止**：`beneficiaryUserId` を payload で受けるが、上記権原検証なしには一切起票しない。`payable-dues` も「自分が払える受益者」だけを返し、他人の未払いを列挙させない。**まとめ決済(bulk-checkout)は一覧取得後の権原失効・支払い済み化に備え、起票直前に明細ごと再認可**（02_api §1.2）。
- **権原の失効**：保護者リンク取消・grant 失効・受益者退会で即時に権原消失（毎回実行時評価・キャッシュしない or 短TTL）。

---

## 3. 後見切替の年齢ゲートと安全境界

### 3.1 年齢判定（国別ポリシーのからくり）
- **しきい値は国別 `GuardianshipAgePolicy` で解決**（初等教育終了年齢は国で異なるため焼き付けない）。`GuardianshipAgePolicyRegistry.forCountry(child.country_code)` → `policy.resolve(birthDate, clock) → { switchAllowed, stageKey }`。
  - 既定 `JapanGuardianshipAgePolicy`：満12歳に達する年度の3月末まで `switchAllowed=true`（小学生）、翌年度4月から `false`（中学生以降・封印）。
  - 未対応 `country_code` は**安全側フォールバック**（満13歳の誕生日で封印）＋ログ記録（症状を隠さない）。
- `birthDate`・`country_code` から算出。`birthDate` は暗号化保存（既存）、判定は復号値で実行し結果（`switchAllowed`/`stageKey`）のみ扱い生年月日は持ち回らない。
- **birth_date 復号はバッチ化（是正 2026-06-04）**: 決済/切替の都度に暗号化 `birthDate` を復号するのではなく、**年齢段階判定を @Scheduled バッチで事前算出**（`switchAllowed`/`stageKey` をスナップショット）し、ホットパスでは復号を持ち回らない。境界日（年度末・誕生日）に再計算する（Clock 注入・date-pin テストで CI を塞がない）。バッチ未到達の境界跨ぎは実行時ゲートが二重防御（封印漏れを防ぐ）。
- **Clock 注入必須**（date-pin テストで CI を塞がぬよう・[[project_f0411_inbox_complete]] の JobQrTokenServiceTest 教訓）。**ポリシーごとに**境界の必須ケースを置く（Clock 固定）：
  - JP：2013-04-02 生まれ × 2026-03-31 → `switchAllowed=true`／× 2026-04-01 → `false`。学年早生まれ（4/1 生まれ＝前学年）を明記しテスト化。
  - フォールバック：未対応国コード × 満13歳前後で境界が満13歳誕生日になること。
  - 共通：`switchAllowed=true` のときのみ切替 API が成功し、`false` で 403。`country_code` 欠落時はフォールバック適用。

### 3.2 切替セッションの安全境界（なりすまし防止）
- 切替は **JWT 再発行せず**、actor=保護者のまま `X-Proxy-For-User-Id=child` を `ProxyInputContextFilter`（F14.1）で検証。`isProxy()` 下の操作はすべて代理として `proxy_input_records` に記録。
- **切替中に保護者が子に対して行えないこと**（境界）：子の**パスワード変更・2FA設定・メール変更・退会・親リンク削除**。これらは認証クリティカルゆえ代理不可（403）。
- **実装（P3b・2026-06-04）**：認証クリティカル操作のガードは共通コンポーネント `AuthenticationCriticalOperationGuard.assertNotActingAs()`（`auth/guardianship` パッケージ・`ProxyInputContext` 注入）に集約し、各 Controller 入口から 1 行で呼ぶ。`isProxy()==true` なら `MEMBERSHIP_AUTHENTICATION_CRITICAL_OPERATION`（コード `MEMBERSHIP_BILLING_003`・`GlobalExceptionHandler` で 403 マップ）を投げる。
  - 現状ガード適用済み EP：`PATCH /me/password`・`PATCH /me/email`・`DELETE /me`（退会）・`POST /me/withdrawal/cancel`（退会取消）・`POST /auth/2fa/setup`・`POST /auth/2fa/verify`・`POST /auth/2fa/backup-codes/regenerate`。
  - **親リンク削除**は後見切替 API（P3c）で `guardianship` ドメインの該当 EP 実装時に同ガードを適用する（本 P3b 範囲外・実装と設計の乖離を明示）。
- 切替中に行えること：会費支払い・所属管理（参加/退会の申請補助）・プロフィール編集・閲覧。
- 監査：切替の開始/終了・代理操作を `audit_logs`（センシティブ）＋`proxy_input_records` に二重記録。`unconfirmedVisibility` 等は対象外。
- 中学進学（年齢到達）で進行中の切替権原は**自動失効**（バッチ＋実行時ゲートの二重防御）。

---

## 4. ペイウォールの安全性

- **受益者キー判定**：`existsValidPaidPayment(viewerBeneficiaryUserId, itemId)` は閲覧者自身の支払い状態のみ評価。他人の支払いで解錠されない。
- **F00 経由徹底**：ペイウォール判定は独自述語を作らず `evaluateCustom` 経由で `PaymentGateService` を呼ぶ（[[feedback_visibility_bypass_f00_audit]]：独自 visibility 述語は漏洩源）。可視性 AND ペイウォールの二条件。
- **タイトル秘匿**：`is_title_hidden=true` は存在ごと秘匿（404相当・列挙不可）、`false` はロック表示＋購入導線（タイトルのみ露出）。
- **fail-safe**：判定不能（gate 設定不整合・itemId 欠落）は**閲覧拒否側**に倒す（症状を隠さず、漏洩より過剰遮断を選ぶ）。

---

## 5. 決済・Webhook・PCI

- **PCI SAQ-A**：カード情報は Stripe Elements/Checkout のみ。Mannschaft は PAN を一切受けない・保存しない。
- **Webhook 署名検証**：`StripeWebhookController` で署名必須・`event_id` UNIQUE 冪等（既存）。`invoice.created`/`invoice.paid`/`invoice.payment_failed`/`customer.subscription.deleted` を追加処理。
- **二重課金防止**：起票系は `Idempotency-Key`、Webhook は冪等ゲート＋行ロック。
- **手数料取りこぼしの可視化**：`invoice.created` 上書き失敗を握りつぶさず記録・再試行・アラート（[[feedback_root_cause_fix]]）。
- **資金移動業回避**：会費も destination charge で受領者へ直接着金。Mannschaft は資金を保持しない（F22.1 §資金移動業回避の根拠を踏襲）。

---

## 6. GDPR・退会・データ保持

- 金銭記録（`member_payments`/`membership_subscriptions`/`payment_requests`/`escrow_transactions`）は**物理削除せず**、退会時はユーザー PII を匿名化し記録は保持（会計・税務保持義務）。F12.3／F09.18 の保持期間方針に整合。
- 退会・年齢到達（中学進学）で **後見切替権原・代理払い grant を自動失効**（F14.1 の自動失効と同型）。
- 領収書の会員 PII（氏名）は生成時に暗号化済み `users` から都度復号し、ファイルに残さない（ダウンロード都度生成 or 短期署名URL）。
- `tax_registration_number` は公開情報・非 PII。

---

## 7. 税務 — 別建て論点（要税理士確認・実装スコープ外）

> からくり（nullable 税列＋`TaxPolicy`＋領収書拡張枠）のみ実装。以下は**確定まで `NoOpTaxPolicy`（不課税・税額0）** とし、税理士確認後に国別 Policy を差す。

1. **会費の課税判定**：役務対価（指導/授業）なら課税、純粋会費なら不課税。項目単位の判断主体（受領者が選ぶ）。
2. **プラットフォーム手数料5%の課税関係**：手数料は Mannschaft の課税役務か。受領者の仕入税額控除のための適格請求書発行義務。
3. **2.5%上乗せ分の課税扱い**：払い手上乗せ分が誰の課税売上に属するか。
4. **適格請求書の発行主体**：destination charge＋on_behalf_of で役務提供者＝受領者。会費の適格請求書は受領者名義・登録番号。Mannschaft は手数料分のみ自名義で発行。
5. **前受金の繰延**：年額/期別前払いの収益認識（役務提供期間にわたる期間配分）。
6. **多国対応**：国ごとに税率/制度が異なる。`TaxPolicy` を国別に実装（今回未実装）。

---

## 8. レート制限・濫用対策

- 代理払い grant 招待・後見切替開始は受益者単位でレート制限（招待スパム防止・既存 ParentalConsent のレートリミットに倣う）。
- まとめ支払いの明細数に上限（一度の決済対象数）。
- 協会請求の一斉配信は配信先数・頻度に上限（通知スパム防止）。

---

## 9. ステータス確定条件（未解決 → 確定）

本設計を 🟢 確定とするための残点（README §11 と対応）：

| # | 残点 | 確定条件 |
|---|---|---|
| 11-1 | 後見切替の年齢しきい値 | **御裁可済**（国別 `GuardianshipAgePolicy` のからくり・JP既定＝満12歳年度末・未対応国は満13歳） |
| 11-2 | 税務6論点 | 税理士確認（実装はからくりのみで先行可・NoOp 既定） |
| 11-3 | invoice 固定手数料上書き × destination charge | Stripe テスト環境 PoC 成立（不成立時は自前バッチ退避） |
| 11-4 | 協会請求の手数料負担 | **御裁可済**（会費と同折半） |
| 11-5 | 第三者代理払いの許諾UX | 設計内確定（保護者は自動・第三者は grant） |
| 11-6 | 既存データ移行 | 解決済（不要・データ無し） |
| 11-7 | 無ログイン管理子アカウント | 解決済（不採用） |

> 11-1/11-4 は御裁可済（提案採用）。11-2/11-3 は**実装をブロックしない**（からくり先行＋PoC は P5 着手前）。設計の論理的整合は全点クローズ済み＝**設計ステータス 🟢 確定**。
