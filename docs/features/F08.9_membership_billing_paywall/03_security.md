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
  - **実装（P3c-2・2026-06-05）**: `PaymentAuthorizationService` に `ProxyInputContext`（RequestScope・`AuthenticationCriticalOperationGuard` と同じ scoped proxy 注入）を注入し、`GUARDIAN_PROXY` を実評価する。条件は「GUARDIAN 成立（保護者リンク）**かつ** `proxyInputContext.isProxy()`（`X-Proxy-For-User-Id` 付き）**かつ** 切替対象の子（`subjectUserId`）＝受益者（`beneficiaryUserId`）」で、このとき `GUARDIAN_PROXY` を `GUARDIAN` より優先して返す。本人払い（`SELF`）は先に確定するため切替中でも子自身の自己払いは `SELF`。別の子へ acting-as 中（`subject≠beneficiary`）の支払いは誤分類せず `GUARDIAN`。権原評価そのものは `GUARDIAN` と同一（保護者リンクが無ければ `isProxy` でも 403）。
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
- **封印境界日（P3c-2・2026-06-05）**: `GuardianshipAgePolicy.sealDate(birthDate, clock)` が `switchAllowed` が `false` に変わる最初の日（封印発火日）を返す。JP＝満12歳に達する年度の翌4/1、フォールバック＝満13歳の誕生日。`clock` 非依存で生年月日から一意（既に封印済みでも過去日を返す）。`resolve` の境界と整合（境界日当日に `switchAllowed=false`）。自立移行ステータス（`independence-status`）と 3ヶ月前事前通知（第三波）で参照する。
- **自立移行の通知バッチ（第三波・P3c-3・2026-06-05）**: 封印の前後で「子が自分のアカウントにログインできない事故」を防ぐ保険として日次バッチ 2 本を稼働する。いずれも **birth_date 復号はバッチ内（ホットパス外）で都度行ってよい**（本節の「復号はバッチ化」方針に合致）。**Clock 注入で date-pin テスト可能**（固定日付で CI を塞がない）・**`@SchedulerLock` で多重起動防止**・**全件走査はページング**（N+1 防止）。
  - **進学予告バッチ**（`guardianship-progression-notice-batch`・03:00 JST）: 保護者リンク（parental_consent APPROVED ＋ care_links ACTIVE PARENT）の全子について `sealDate` を算出し、`today ∈ [sealDate-3ヶ月, sealDate)` の保護者へアプリ内通知＋メールで「◯月からお子さまが自立します」を事前通知。
  - **封印時未設定メールバッチ**（`guardianship-seal-unset-password-batch`・03:30 JST）: `sealDate <= today` かつパスワード未設定の子へ `AuthPasswordResetService.requestPasswordReset`（outbox 経由）でパスワード設定メールを自動送付。内部プレースホルダメール（`*.mannschaft.internal`）は送付不能ゆえスキップ＋件数をログに可視化（症状を隠さない）。
  - **重複送信防止**: 専用テーブル `guardianship_transition_notifications`（Flyway V74.20260605000020・UUIDv7・クロスドメインFKなし）で `(種別, 宛先, 子, 境界日)` を UNIQUE 化し 1 回限りに統制。「記録を先に保存 → UNIQUE 競合検知 → 競合時は送らずスキップ」で並行・時刻境界での二重送信を物理排除する。

### 3.2 切替セッションの安全境界（なりすまし防止）
- 切替は **JWT 再発行せず**、actor=保護者のまま `X-Proxy-For-User-Id=child` を `ProxyInputContextFilter`（F14.1）で検証。`isProxy()` 下の操作はすべて代理として `proxy_input_records` に記録。
- **切替中に保護者が子に対して行えないこと**（境界）：子の**パスワード変更・2FA設定・メール変更・退会・親リンク削除**。これらは認証クリティカルゆえ代理不可（403）。
- **実装（P3b・2026-06-04）**：認証クリティカル操作のガードは共通コンポーネント `AuthenticationCriticalOperationGuard.assertNotActingAs()`（`auth/guardianship` パッケージ・`ProxyInputContext` 注入）に集約し、各 Controller 入口から 1 行で呼ぶ。`isProxy()==true` なら `MEMBERSHIP_AUTHENTICATION_CRITICAL_OPERATION`（コード `MEMBERSHIP_BILLING_003`・`GlobalExceptionHandler` で 403 マップ）を投げる。
  - 現状ガード適用済み EP：`PATCH /me/password`・`PATCH /me/email`・`POST /me/email/confirm`・`DELETE /me`（退会）・`POST /me/withdrawal/cancel`（退会取消）・`POST /auth/2fa/setup`・`POST /auth/2fa/verify`・`POST /auth/2fa/backup-codes/regenerate`。
  - **2FA 無効化 EP**（`DELETE /auth/2fa` 相当）は現在未実装。将来実装時は本ガードの適用対象とすること（認証クリティカル）。
  - **親リンク削除**（`DELETE /api/v1/parental-consent/parents/{linkId}`・子側操作）は **P3c で `assertNotActingAs()` ガード適用済**（2026-06-05）。保護者が子として acting-as し、共同親権者のリンクを削除する経路を塞ぐ（なりすまし防止の安全境界）。
- 切替中に行えること：会費支払い・所属管理（参加/退会の申請補助）・プロフィール編集・閲覧。
- **実装（P3c・2026-06-05）**：切替の開始/終了は `POST`/`DELETE /api/v1/me/guardianship/switch`（サーバ側ステートレス＝セッションテーブルなし・検証＋監査記録のみ）。以降クライアントが `X-Proxy-For-User-Id=childUserId` を保持し、**毎リクエストを `ProxyInputContextFilter` の「後見切替経路」が再検証**する（consent-id ヘッダなし＝後見切替経路／consent-id ありは従来 F14.1 紙同意書経路）。再検証は (a) 保護者リンク有効（`isApprovedGuardian` または `isActiveParentWatcher`）(b) 年齢ゲート（`evaluateSwitch`）で、合格時のみ `FeatureScope.PAYMENT` のみを `ProxyInputContext.activate(...)` する（最小権限）。**境界日跨ぎ（年度末・誕生日）の自動失効は本実行時ゲートが担保**（封印後の子へは 403）。エラーコード：`GUARDIANSHIP_LINK_NOT_FOUND`（`MEMBERSHIP_BILLING_005`・403）／`GUARDIANSHIP_SWITCH_AGE_LOCKED`（`MEMBERSHIP_BILLING_004`・403）。
- 監査：切替の開始/終了・代理操作を `audit_logs`（センシティブ）＋`proxy_input_records` に二重記録。`unconfirmedVisibility` 等は対象外。
  - **実装（P3c・2026-06-05）**：開始は `audit_logs`（`GUARDIANSHIP_SWITCH_STARTED`・userId=保護者/targetUserId=子）＋ `proxy_input_records`（consent_id=NULL・`input_source=GUARDIANSHIP_SWITCH`・`feature_scope=PAYMENT`）の二重記録。終了は `audit_logs`（`GUARDIANSHIP_SWITCH_ENDED`）のみ（ステートレスゆえ解除すべきサーバ状態なし）。`proxy_input_records.proxy_input_consent_id` の NULLABLE 化は V74.010（01_data_model §6 参照）。
- 中学進学（年齢到達）で進行中の切替権原は**自動失効**（バッチ＋実行時ゲートの二重防御）。
- **自立移行の引き継ぎ（P3c-2・2026-06-05）**：`POST /api/v1/me/guardianship/children/{childUserId}/handover/initiate` は保護者本人の権原で子のメールへパスワード設定リンクを送る操作であり、後見切替セッション（acting-as）とは無関係（`X-Proxy-For-User-Id` 無しで呼ぶ）。混乱・なりすまし経路を避けるため `AuthenticationCriticalOperationGuard.assertNotActingAs()` を適用し、acting-as 中の呼び出しは 403（`MEMBERSHIP_BILLING_003`）。認可は有効な保護者リンク（`isApprovedGuardian`/`isActiveParentWatcher`）のみで、他人の子は 403（`GUARDIANSHIP_LINK_NOT_FOUND`・IDOR 防止）。メール送付は F01.9 の `AuthPasswordResetService` を流用し F09.18 outbox 経由（`EmailService.sendEmail` 直呼びしない）。
  - **レート制限・トークン期限（検証・P3c）**：濫用防止は `AuthPasswordResetService.requestPasswordReset` 内部に集約。リクエスト元 IP 単位で Valkey（`mannschaft:auth:password_reset_attempt:<ip>`）にスライディングウィンドウ（**1 分間 3 回まで**）を持ち、超過時は 429 相当。発行リンクのトークンは **30 分**で失効。`GuardianshipHandoverService` は独自のレート制限・トークン管理を持たず、この基盤に一本化する（javadoc に明記）。
  - `independence-status`（`GET`）も同じ保護者リンク検証で IDOR を塞ぐ。

#### 3.2.1 切替中の F14.1 代理入力 7 機能の挙動（subject=子・GUARDIANSHIP_SWITCH 監査・consent_id=NULL）

切替中（`isProxy()==true`・`consent_id=null`・`input_source=GUARDIANSHIP_SWITCH`）に、F14.1 由来の `isProxy()` 分岐を持つ 7 機能が発火すると、各 Service は **subject=子**として操作を実行し、`proxy_input_records` に GUARDIANSHIP_SWITCH 由来のレコードを追記する。これは §3.2「切替中に行えること（会費支払い・所属管理・参加補助・閲覧）」と整合し、**挙動は変えない**（F14.1 当初仕様の踏襲）。

| 機能（Service） | featureScope | 切替中の subject | consent_id | input_source | 監査 |
|---|---|---|---|---|---|
| アンケート回答（`SurveyResponseService`） | `SURVEY`/`SCHEDULE_ATTENDANCE` | 子（`subjectUserId`） | NULL | GUARDIANSHIP_SWITCH | `proxy_input_records` 追記 |
| 出欠回答（`ScheduleAttendanceService`） | `SCHEDULE_ATTENDANCE` | 子 | NULL | GUARDIANSHIP_SWITCH | 同上 |
| シフト希望（`ShiftRequestService`） | `SHIFT_REQUEST` | 子 | NULL | GUARDIANSHIP_SWITCH | 同上 |
| お知らせ既読（`AnnouncementReadService`／`AnnouncementCreationService`） | `ANNOUNCEMENT_READ` | 子 | NULL | GUARDIANSHIP_SWITCH | 同上 |
| 駐車場申請（`ParkingApplicationService`） | `PARKING_APPLICATION` | 子 | NULL | GUARDIANSHIP_SWITCH | 同上 |
| 回覧押印（`CirculationStampService`） | `CIRCULAR` | 子 | NULL | GUARDIANSHIP_SWITCH | 同上 |

- **consent_id=NULL 耐性（検証・P3c）**：各 Service の `buildAndSaveProxyInputRecord` は `proxyInputContext.getConsentId()` を `Long` のまま扱い（プリミティブ unboxing なし・`@NotNull` なし）、Entity `proxyInputConsentId` は NULLABLE（V74.010）。冪等性チェック `findByProxyInputConsentIdAnd...(null, ...)` は `= NULL` で常に空ヒット＝切替由来は毎回新規追記（開始/終了が繰り返す意図どおり・UNIQUE KEY は NULL を distinct 扱い）。よって **NPE / 制約違反 / 500 は発生しない**。
- **`original_storage_location` NOT NULL の根治（P3c・2026-06-05）**：当該列は `NOT NULL`（V18.012）。後見切替経路は紙原本がないが、`ProxyInputContextFilter.handleGuardianshipSwitch` が `activate(...)` の `originalStorageLocation` に **`null` ではなく固定値**（`"N/A (online guardianship switch)"`・`GuardianshipSwitchService` の切替開始記録と同一文言）を渡すことで、切替中に上記 7 機能が発火しても NOT NULL 制約違反 500 を起こさない。
- **スコープ実検証（hasScope）は別フェーズ**：上記 7 機能の `isProxy()` 分岐は **要求スコープ（`hasScope(...)`）を検証していない**（F14.1 当初からの既存仕様）。切替時に付与されるのは `PAYMENT` スコープのみだが、これらの機能は `PAYMENT` を要求せず subject=子として実行される。`hasScope` の実検証導入は **F14.1 横断の別フェーズ**に送る（本 P3c では挙動を変えずドキュメント化のみ）。

#### 3.2.2 ProxyInputContext を参照しない機能（チャット等）は保護者として実行されなりすましは発生しない

切替は **JWT を再発行しない**（actor=保護者のまま）。`ProxyInputContext` を見ない機能（例：チャット送信は `ChatMessageService` 等が `SecurityUtils.getCurrentUserId()`＝JWT の保護者 ID を author に用いる）は、切替中でも**保護者本人として**実行・記録される。つまり「切替中に子名義でチャット送信する」ような**なりすましは構造的に発生しない**。`X-Proxy-For-User-Id` を消費するのは §3.2.1 の F14.1 経路と決済の代理払い判定（`PaymentAuthorizationService`）だけであり、それ以外は保護者の権原で動く。

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
