# F08.9 会員決済・後見つきマルチ受益者・ペイウォール・継続課金

> **ステータス**: 🟢 設計確定（御裁可反映済・二度の敵対的精査反映済・**2026-06-04 統一決済アーキ正典化反映**［サブスク解約/今月スキップ・立替記録案3・F22.1依存是正・3層結合・手数料ランク化連動］。設計内の論点は全クローズ。残る外部関門は税理士[§11-2]＋実装前 PoC[§11-3] のみ。実装＝P1 main 済・P2 以降未着手）
> **最終更新**: 2026-06-04
> **親機能**: [F08.2 支払い・コンテンツアクセス制御](../F08.2_payments_access_control.md) を上位拡張 ／ 決済レールは [F22.1 統一決済プラットフォーム](../F22.1_market/payment/README.md) を再利用
> **関連**: [F01.9 年齢確認・保護者同意](../F01.9_age_verification_parental_consent.md) ／ [F03.12 見守り通知](../F03.12_care_recipient_event_watch_notification.md) ／ [F14.1 代理入力](../F14.1_proxy_input_for_offline_residents.md) ／ [F00 可視性基盤](../F00_content_visibility_resolver.md) ／ [F04.9 確認必須通知](../F04.9_confirmable_notification.md) ／ [F04.11 統合通知インボックス](../F04.11_notification_inbox/README.md)

---

## 0. この設計書の構成

複合形（F22.1 payment / F13.1 / F03.5 と同じ分割方式）で構成する。

| ファイル | 内容 |
|---|---|
| `README.md`（本書） | 概要・中核モデル（払い手≠受益者／年齢段階つき後見切替／既存機構再利用）・継続/期別課金方式・ペイウォール・協会→チーム請求・可視化・領収書/税からくり・F08.2/F22.1 統合・段階ロードマップ・未解決→確定・変更履歴 |
| [`01_data_model.md`](01_data_model.md) | DB設計（`member_payments` 拡張＝払い手分離／`membership_subscriptions` 新規／`payment_requests`（協会請求）新規／`payment_items` 税・継続列追加／`connect_accounts` 税登録番号追加／`payment_proxy_grants`（代理払い許可）新規・ER図・Flyway 計画） |
| [`02_api_design.md`](02_api_design.md) | API設計（代理払い・後見切替セッション・継続課金(Subscription+invoice上書き)・期別単発・協会請求発行/支払・ペイウォール判定・集計/CSV/領収書・Webhook フロー・DTO・エラーコード・冪等性） |
| [`03_security.md`](03_security.md) | セキュリティ（認可マトリクス・年齢段階ゲート・代理払い認可・ペイウォールIDOR・受益者キー判定・PCI(SAQ-A)・Webhook署名/冪等・GDPR/退会・税務別建て論点・レート制限・未解決→確定） |
| [`04_ui_i18n.md`](04_ui_i18n.md) | 画面設計（後見まとめ支払い・子アカウント切替・ペイウォール施錠UI・継続課金管理・協会請求受信/支払・領収書ダウンロード）・i18n 6言語キー骨子 |

---

## 1. 概要

本機能は「**誰が払い・誰の所属とアクセスに効くか**」を一級市民として扱う会員決済基盤である。柱は5つ。

1. **(A) 払い手≠受益者**：1人の親が、上の子の『サッカークラブ』と下の子の『学習塾』の会費を **1アカウントでまとめて支払う**。「子供ごとにアカウント作成」という最悪UXを避けつつ、子は**自分のログインアカウント**を持つ（早期のITリテラシー育成）。
2. **(A) 年齢段階つき後見切替**：**小学生まで**は保護者が子アカウントへ**切り替えて強権で管理**でき、**中学生以降**は切替を**自動で封印**し子に自立（プライバシー）を渡す。
3. **(B) ペイウォール**：月額/年額/期別を支払い済みの**子プロフィール（＝受益者）のみ**閲覧できるブログ/お知らせ。チーム/組織ごとに設定。
4. **(C) 継続・期別課金**：月額・年一括・夏期講習/冬期講習などの期別課金。継続は **Stripe Subscription**、期別は**単発 destination charge**。
5. **(E) 協会→加盟チーム請求**：組織(協会)が加盟チームに「リーグ参加費」を**通知経由で請求**し、チーム管理者が通知内の支払いボタンから決済する（payer=TEAM／payee=ORG）。

加えて **(D) 支払いユーザの可視化**（誰が払ったか一覧・未払/支払済/期限切れ・期別集計・CSV）と **(F) F08.2 と F22.1 統一レールの結節**を担う。

### 1.1 設計の根本原則

- **新しいプロフィール表は作らない**。受益者＝既存の `users` 行。子は実在のログインアカウントであり、既に `memberships` で所属・出欠・投稿の主体になれている。**80超の `user_id` 参照テーブルを付け替える大工事は行わない**（②全面移行を採らず、波及は決済層のみに閉じる）。
- **決済レールは F22.1 統一 Connect 基盤を再利用**し重複実装しない。会費は `source_kind=MEMBERSHIP`・即時モードで `ConnectChargeService` を通す（F22.1 ロードマップ P2-e の具体化）。
- **既存の後見機構を流用**する：親子の紐付けは [F01.9 保護者同意](../F01.9_age_verification_parental_consent.md)（`parental_consent_links`）と [F03.12 見守り](../F03.12_care_recipient_event_watch_notification.md)（`user_care_links`）、代理権の枠組みは [F14.1 代理入力](../F14.1_proxy_input_for_offline_residents.md)（`proxy_input_consents` ＋ `ProxyInputContext`）。
- **税は「からくりだけ」**。多国対応の差し込み口（nullable 列＋`TaxPolicy` 戦略＋領収書拡張点）のみ仕込み、税計算・適格請求書生成の実装は将来へ送る。

### 1.2 既存実装の棚卸し（origin/main 実機確認・2026-06-03）

| 機構 | 状態 | 流用可否 |
|---|---|---|
| F22.1 統一レール P2-a/P2-b/P2-c（`connect_accounts`/`escrow_transactions`/`ledger_entries`/`refunds`/`ConnectAccountService`/`ConnectWebhookService`/`ConnectChargeService`/`PaymentFeeCalculator`） | ✅**実装済（main・V73.003）** | ◎ 会費の money rail に再利用。`source_kind=MEMBERSHIP`／`face_amount`／`capture_mode`／`ConnectChargeService`（authorize/capture/refund）／`PaymentFeeCalculator` も **実装済**。即時 `charge()` は本機能 P1 Wave0 で追加済。**残＝手数料ランク化（`fee_policies`・F22.1 P2-f・本軍議で正典化）** |
| F08.2（`payment_items`/`member_payments`/`content_payment_gates`/`*_access_requirements`/`PaymentSummaryService`/CSV） | ✅実装済（現状は**素のCheckout・自社集金・Connect未使用**） | ◎ 受益者キー判定・集計は流用。払い手分離・Connect化が新規 |
| F01.9 保護者同意 `parental_consent_links` | ✅実装済 | ○ 親子の確立に流用。**継続的な代理操作権は無し**（同意承認のみ） |
| F03.12 見守り `user_care_links`（relationship=PARENT/CHILD） | ✅実装済 | ○ 親子＋見守り関係の確立に流用。**通知設定のみ**で操作権は無し |
| F14.1 代理入力 `proxy_input_consents` ＋ `ProxyInputContext`（`X-Proxy-For-User-Id`） | ✅実装済・**汎用代理権枠組み** | ◎ 「後見切替セッション」「代理払い」の認可基盤に流用 |
| 無ログイン管理アカウント `users.accountCreatedByWatcherUserId` | ❌フィールドのみ・未実装 | ✗ **採用しない**（子は自前アカウントを持つ方針） |
| 代理払い（`MemberPaymentService.createCheckout` は払い手＝受益者で固定） | ❌未実装 | — 払い手分離が本機能の新規実装 |
| Subscription（`SubscriptionController`/`TeamSubscriptionEntity`/`TeamPlanService`） | ⚠️ガワのみ（"Phase 4 実装予定" を返すだけ・Stripe Subscription API 未呼出） | △ ガワを本機能の継続課金で本実装 |
| 通知（`NotificationEntity.actionUrl`／`ConfirmableNotificationService.send(List<userId>)`／`InboxSourceAdapter`） | ✅実装済 | ◎ 協会請求の一斉配信・inbox 集約に流用 |
| F00 可視性（`AbstractContentVisibilityResolver.evaluateCustom()`） | ✅実装済・拡張点あり | ◎ ペイウォール判定の差し込み口 |

---

## 2. スコープ

### 2.1 対象（in）
- [ ] 払い手≠受益者の決済記録（`member_payments.payer_user_id` 分離）
- [ ] 後見まとめ支払い（保護者が複数の子の会費を1画面で一括決済）
- [ ] 年齢段階つき後見切替セッション（小学生まで強権・中学生以降封印）
- [ ] 代理払い認可（保護者リンク or F14.1 代理権スコープ `PAYMENT`）
- [ ] 会費の F22.1 統一 Connect レール化（`source_kind=MEMBERSHIP`・即時 capture）
- [ ] 継続課金（月額/年額）＝ Stripe Subscription ＋ invoice 固定手数料上書き
- [ ] 期別課金（夏期講習等）＝ 単発 destination charge
- [ ] ペイウォール（受益者キー判定・blog/お知らせ・F00 evaluateCustom 連結）
- [ ] 協会→加盟チーム請求（`payment_requests`・通知配信・payer=TEAM/payee=ORG）
- [ ] 支払いユーザ可視化（払い手/受益者・未払/支払済/期限切れ・期別集計・CSV）
- [ ] 領収書（受領者名義・金額のみ）＋ 月次手数料明細（Mannschaft 名義）
- [ ] 税からくり（nullable 税列・`TaxPolicy` 戦略 NoOp 既定・領収書拡張点）

### 2.2 対象外（out）
- [ ] 税計算・適格請求書の税内訳/登録番号レンダリングの**実装**（からくりのみ。実装は将来・国別）
- [ ] 無ログインの管理子アカウント（方針として不採用）
- [ ] 子プロフィールを出欠・シフト・スケジュール等**全ドメインの参加主体に付け替える全面移行**（子は既存 `users` 行のままで足りる）
- [ ] 物販(`ITEM`)・寄付(`DONATION`)の継続化（単発のまま）
- [ ] 多通貨対応（`JPY` 固定。`connect_accounts.default_currency` の器は既存）
- [ ] 既存会員データの移行バッチ（開発中・本番データ無しのため不要）

---

## 3. 中核モデル

### 3.1 払い手≠受益者（payer / beneficiary の分離）

決済記録 `member_payments` に **払い手 `payer_user_id`** を追加し、従来の `user_id`（＝受益者・会費の効く会員）と分離する。

```
member_payments
  user_id        … 受益者（beneficiary）＝この会員の所属/ペイウォールに効く  ← ペイウォール判定キー
  payer_user_id  … 払い手（payer）＝実際に決済した人（親・本人・祖父母・スポンサー等）  ← 新規
  payment_item_id, status, valid_from, valid_until, escrow_transaction_id(新規) …
```

- **ペイウォール・所属判定は受益者キー**：`existsValidPaidPayment(beneficiaryUserId, paymentItemId)` は受益者で引く。**誰が払ったかに関わらず**、受益者に有効な支払いがあれば閲覧可。
- **Stripe Customer・決済導線は払い手キー**：与信・カード保存・領収書送付先は払い手。
- 「**実際に払うのは親とは限らない**」を自然に表現（払い手は決済ごとに記録）。本人が自分の会費を払えば `payer_user_id == user_id`。

> 統一レールでは money 移動を `escrow_transactions` が担う：`payer_scope_kind=USER`/`payer_scope_id=payer_user_id`、`payee_kind`=TEAM or ORG/`payee_connect_account_id`。`member_payments` は membership ドメインの**意味づけ記録**として残し、`escrow_transaction_id` で money rail に連結する（ドメイン境界を保つ・01_data_model §1）。

### 3.2 年齢段階つき後見切替（acting-as）

保護者が子アカウントへ「切り替えて」操作する**後見切替セッション**を設ける。F14.1 `ProxyInputContext`（`X-Proxy-For-User-Id` ヘッダ＋RequestScope Bean）を土台に、**年齢で段階を切る**。

| 区分 | 保護者の権限 | 切替 |
|---|---|---|
| **初等教育まで**（国別ポリシーの「切替可」段階・日本既定＝小学生まで） | **強い後見**：子アカウントへ切替し、会費支払い・所属管理・プロフィール編集・閲覧を**代理操作**可 | 切替可 |
| **前期中等教育以降**（国別ポリシーの「自立」段階・日本既定＝中学生以降） | **自立**：保護者は**切替不可**。会費の代理払いのみ（後述 §3.3）は子の許諾下で別途可 | **切替を自動封印** |

- **★年齢しきい値は国別「からくり」（`GuardianshipAgePolicy`）で解決**（税の `TaxPolicy` と同型）。初等教育の終了年齢は国によって異なる（日本＝満12歳/小学校卒業、米国＝grade5 で ~11歳、英国＝Year6 で ~11歳 等）ため、**しきい値を焼き付けず、子の `users.country_code`（ISO 3166-1 alpha-2・既存 V13.013）でポリシーを引く**。
  - `GuardianshipAgePolicy.resolve(birthDate, clock) → { switchAllowed, stageKey }`。`stageKey` は i18n ラベルに対応。
  - 既定実装＝**`JapanGuardianshipAgePolicy`（満12歳に達する年度の3月末で封印）**。対応国の追加は**Policy を差すだけ**（schema 改修不要）。
  - 国別ポリシー未整備の `country_code` は**安全側の既定**（満13歳の誕生日で封印）にフォールバックし、ログに「未対応国＝既定適用」を記録（症状を隠さない）。
  - 運用調整用に国別しきい値を外部化したい場合は、マスタ表 `guardianship_age_policies`（country_code 自然キー・CLAUDE.md マスタ例外）で上書き可能とする拡張点を設ける（初期は不要・コード既定で足りる）。
  - `users.birthDate`（暗号化保存・既存）から算出。`careCategory=MINOR` と整合。
- **切替の監査**：切替の開始/終了・代理操作はすべて `audit_logs` ＋ F14.1 `proxy_input_records` に「保護者Xが子Yとして操作」を記録（症状を隠さず追跡可能に）。
- **切替の表現**：JWT は再発行せず、リクエスト時ヘッダ `X-Proxy-For-User-Id`（子）＋ 後見切替の権原（後述）を `ProxyInputContext` で検証。SecurityContext の actor は保護者のまま、`subjectUserId` に子を載せる（なりすましでなく**明示的な代理**）。

### 3.3 代理払いの認可（pay-on-behalf）

「払い手が受益者の会費を払ってよいか」の認可は、用途で2系統を使い分ける。

1. **日常の後見代理払い（軽量・主経路）**：払い手が受益者の**有効な保護者/見守り者**（`parental_consent_links.status=APPROVED` または `user_care_links.status=ACTIVE` かつ relationship=PARENT）であれば、**追加同意なしに**代理払い可。子の年齢段階に依存しない（中学生の子の会費も親は払える＝切替できないだけ）。
2. **非後見の代理払い（明示許諾）**：保護者でない第三者（祖父母・スポンサー等）が払う場合は、受益者側が発行する **`payment_proxy_grants`**（代理払い許可・有効期限つき・上限額つき）を要する。**これが第三者払いの正規経路**。F14.1 の代理権スコープ `PAYMENT` は紙同意書ベースの**組織代理の重い経路**として温存し、日常の代理払い認可（03_security §2 `authorizePayment`）には含めない。

> ⚠️ **proxy scope `PAYMENT` は実在の枠組みに値を1つ足すだけ（マスター確定 2026-06-04・是正）**: 代理権スコープは実在の `proxy_input_consent_scopes.feature_scope`（VARCHAR(64)・V18.011・実機確認済）に格納され、enum `ProxyInputConsentScopeEntity.FeatureScope`（SURVEY/SCHEDULE_ATTENDANCE/SHIFT_REQUEST/ANNOUNCEMENT_READ/PARKING_APPLICATION/CIRCULAR/SUPPORTER_VIEW）で表現される。**代理払いの組織代理経路は、この enum に値 `PAYMENT` を1つ追加するだけ**（`feature_scope` は VARCHAR ゆえ**列追加・DDL 不要**・CHECK 制約なし）。`proxy_input_consents` 本体に新規列を作るのではなく、**既存のスコープ行に `PAYMENT` を1つ足す**だけで代理払い認可・退会失効（F14.1 の scope 行失効）はこの scope 行で判定できる。
3. **本人払い**：`payer_user_id == beneficiaryUserId` は常に可。
4. **管理者手動記録**：チーム/組織 ADMIN による現金等の手動記録は従来通り（`recorded_by`）。

> 後見切替セッション中の決済も本節1（保護者リンク）で権原が立つが、記録上は `payer_relationship=GUARDIAN_PROXY` と区別する（02_api §1.1）。中学進学で切替は封じられるが、**保護者の代理払い自体は本節1で継続可**（自立移行フロー＝02_api §2.3）。

> いずれの経路でも、決済確定時に「払い手・受益者・権原（保護者リンクID or grant ID）」を記録し、IDOR を封じる（03_security §2）。

### 3.4 統一レール統合（source_kind=MEMBERSHIP）

会費徴収を F22.1 `ConnectChargeService` の**即時モード**（`capture_mode=AUTOMATIC`）に載せる。

- 受領者（チーム/組織）が **Connect 口座へ直接着金**（Mannschaft は資金を保持しない＝資金移動業回避）。
- `escrow_transactions(source_kind=MEMBERSHIP)`：`capture_mode=AUTOMATIC`・`hold_expires_at=NULL`（与信→手動captureの2段を経ない即時）。**charge() 起票時は `status=AUTHORIZED`**（PaymentIntent 作成済・確認待ち）とし、**CAPTURED 確定と複式記帳（ledger）起票は `payment_intent.succeeded` Webhook に一元委譲**する（既存 `EscrowWebhookService` の冪等ゲート＝event_id UNIQUE＋行ロックに相乗り）。起票時点で CAPTURED にすると succeeded webhook が no-op となり ledger が欠落するため、AUTHORIZED 起票が正（実装 P1 Wave0 で確認・2026-06-03）。
- 手数料は **`PaymentFeeCalculator` に一元化**：手数料は **F22.1 `fee_policies`（率%＋固定額¥）で解決**し折半50/50固定（`FeePolicyResolver(source_kind=MEMBERSHIP)` → `total_fee = round(percent×face)+flat`、`application_fee_amount = total_fee`、払い手請求額 = `face + round(total_fee/2)`、受取側着金 = `face − round(total_fee/2)`）。**DEFAULT パターン（率5%＋固定0）では従来どおり `application_fee=round(face×0.05)`／請求=`face+round(face×0.025)`** と完全一致（F22.1 README §3.4 / 02 §3.5）。解決した `fee_policy_key` は escrow に焼き付け遡及防止。
- 現行 F08.2 の素のCheckout（自社集金）は本機能完成をもって**廃止**＝**第三者受取からの撤去**（自社受取は残置・F22.1 README §3.0）、新規の第三者受取会費はすべて Connect 経由とする（F08.2 §冒頭の移行宣言を実装で回収）。

---

## 4. 継続・期別課金（C）

### 4.1 期別（夏期講習・自動更新なし）＝ 単発 destination charge

- `payment_items.type=TERM`（新規 enum 値・後述）。`term_starts_on`/`term_ends_on` で有効期間を持つ。
- 決済は **単発 PaymentIntent（destination charge・固定 `application_fee_amount`）**。サブスク不要。
- 有効期間（`valid_from`/`valid_until`）は term の期間に一致。

### 4.2 継続（月額/年額）＝ Stripe Subscription ＋ invoice 固定手数料上書き

Stripe に**期日決済・再試行(dunning)・カード更新・SCA**を背負わせ、唯一の難点「Connectサブスクは手数料が率(`application_fee_percent`)でしか取れず固定額不可」を **invoice 上書き**で回避する。

**フロー**
1. 初回：払い手のカードを **SetupIntent** で platform 側 Customer に保存（off_session）。**Stripe Subscription** を作成：**明細は 2 本**（`price`＝会費＝額面 ¥10,000 ／ `price`＝支払側手数料＝`FeeBreakdown.payerFee`＝¥250。`payerFee=0` のときは手数料明細を作らない）、`transfer_data[destination]`＝受領者 Connect 口座、`on_behalf_of`＝同、`billing_cycle_anchor`＝ユーザ指定決済日。

   > ⚠️ **明細を「会費のみ（額面）」にしてはならない。** invoice 合計が ¥10,000 のままだと、`application_fee_amount` を ¥500 に上書きしても受取側の着金が ¥9,750 ではなく **¥9,500** になり、受取側が毎サイクル「額面の 2.5%」を余分に負担する（下表と食い違う）。支払側の上乗せ分は **invoice の明細として加算**しなければ折半は成立しない。2 明細にすることで invoice 合計 ¥10,250 ＝ 初回サイクルの単発 charge 金額（`chargeAmount`）と一致する。
2. 各サイクル：Stripe が invoice を自動生成 → **`invoice.created`** webhook で、その invoice の **`application_fee_amount` を固定円（加入時に焼き付けた `fee_policy_key` で解決した `total_fee`・DEFAULT なら `round(face_amount×0.05)`）に上書き**。料率改定は新規加入のみ反映・既存サブスクは加入時 policy で固定（遡及防止）。
3. Stripe が保存カードで自動決済 → **`invoice.paid`** webhook で `escrow_transaction(source_kind=MEMBERSHIP, status=CAPTURED)` ＋ `ledger_entries` を起票し、受益者の `valid_until` を1サイクル延長。
4. 失敗：Stripe smart retries → `past_due` に落ちれば状態反映・払い手へ督促通知（§6 配信基盤）・猶予(`grace_period_days`)後にペイウォール失効。

**手数料の実数（額面 ¥10,000・固定上書きあり・DEFAULT パターン＝率5%＋固定0）**

| 項目 | 金額 | 備考 |
|---|---:|---|
| 額面（会費・税込） | ¥10,000 | |
| 払い手の支払額 | ¥10,250 | 額面＋2.5%上乗せ |
| application_fee（固定） | ¥500 | 額面×5%・毎サイクルぴったり |
| 受取側の着金 | ¥9,750 | 額面−2.5%（追加負担ゼロ） |
| Stripe 実手数料 | ≈¥369 | gross×約3.6%・Mannschaft の ¥500 から差引 |
| Mannschaft 純益 | ≈¥131 | **約1.31%・単発でも継続でも同一** |

> 率方式(`application_fee_percent`)は gross 基準で丸めるため折半が崩れる。固定上書きにより純益 1.31% を維持（単発charge と同値）。「目減り」は起きない。

### 4.3 二系統の真実源と突合

Stripe Subscription（スケジュールの主）と escrow 台帳（金の記録）を `stripe_subscription_id`／`stripe_invoice_id` で結線。冪等性は既存 `stripe_webhook_events.event_id` UNIQUE で二重起票を封じ、日次で Stripe ↔ 台帳を突合する。

### 4.5 解約・今月スキップ（マスター確定・精緻化軍議 2026-06-04）

継続課金に対し、利用者が **「今月スキップ」「解約」「再開」** を行えるようにする（わかりやすい UX を必須・[04 §2](04_ui_i18n.md)）。

| 操作 | Stripe 機構 | 列・状態 | ペイウォール（受益者キー）への影響 |
|---|---|---|---|
| **解約** | `cancel_at_period_end=true`（期末まで閲覧可・**日割り返金なし**・期末前は再有効化可） | `membership_subscriptions.cancel_at_period_end=true`・`cancelled_at` | 期末まで `valid_until` 有効＝閲覧可。期末で `CANCELLED`・以降失効 |
| **今月スキップ** | `pause_collection[behavior=void, resumes_at=次回サイクル+1]` | `membership_subscriptions.skip_until`（**新規列**・[01 §2.1](01_data_model.md)）に再開予定をセット | **スキップ月は invoice が void → `invoice.paid` 発火せず → `valid_until` を延ばさない＝閲覧も延びない**（次課金で再開） |
| **再開** | `pause_collection` 解除 | `skip_until` クリア | 次サイクルから通常課金・延長再開 |

- **解約＝`cancel_at_period_end`**（F08.9 既設計 [02 §4.1](02_api_design.md) どおり）。即時解約・日割り返金はしない（期末まで利用可・返金が必要なら受取側 ADMIN の F22.1 返金フロー）。
- **今月スキップ＝`pause_collection(behavior=void, resumes_at=次回+1サイクル)` ＋ `skip_until` 列**。スキップ月は invoice が **void** されるため `invoice.paid` が発火せず、`valid_until` を延ばさない。**ペイウォール（受益者キー `existsValidPaidPayment`＝`valid_until` 基準）は無改修で「既払期間内のみ閲覧可」が自然成立**する（スキップ月は閲覧も延びない＝整合）。
- **わかりやすい UX**: 継続課金管理画面に「今月スキップ／解約（**○月○日まで利用可と日付明記**）／再開」を出し、**次回課金日・利用期限を明示**＋確認ダイアログ（[04 §2](04_ui_i18n.md)）。API は `POST /membership-subscriptions/{id}/skip`・`/resume`・解約 `DELETE`（[02 §4.3](02_api_design.md)）。i18n 6言語。
- サブスクの invoice 固定手数料上書きは **`fee_policy`（F22.1 §3.4）の値で算出**（率→固定額の上書き要件＝§4.4 PoC 論点）。スキップ月は invoice 自体が void ゆえ上書き対象外。

### 4.4 関門（実装前 PoC）と退避策 — **PoC 成立済（2026-06-05・条件付き）**

- **PoC 成立**：「invoice の固定 `application_fee_amount` 上書き × `transfer_data[destination]` × `on_behalf_of`」が期待どおり噛み合うことを **Stripe テスト環境で実証済**（`run_20260605_213236`）。更新サイクル invoice の draft 窓で `application_fee_amount=53` 固定上書き → finalize→pay 後の charge へ `53` が伝播（subscription の `application_fee_percent=5` 自動計算 50 を完全上書き）。検証スクリプト・結果詳細＝`scripts/poc/README_f089_p5_poc.md` §0。
- **成立条件（実装時に必ず順守）**：
  1. **API バージョン `2025-02-24.acacia` 固定**（stripe-java 28.2.0 の固定版）。最新版（basil = 2025-03-31 以降）では invoice の `application_fee_amount` / `transfer_data` / `charge` が存在せず HTTP 200 で黙殺される。**stripe-java を 29.x（basil）以降へ上げる際は invoice 上書き機構の作り直し（新 Invoice Payments 構造への移行）が必要** — 依存更新時の必須チェック項目。Dependabot 等の一括更新事故を機械的に防ぐため、`build.gradle.kts` の stripe-java メジャーバージョンが 28 以外になるとビルドを fail させる番人テスト `StripeJavaVersionGuardTest`（`backend/src/test/java/com/mannschaft/app/common/architecture/`）を設置済み。
  2. **初回 invoice は上書き不可（即 finalize で窓なし）→ 案 b 採用**：初回会費は P1 同型の単発 destination charge で徴収し、Subscription は `billing_cycle_anchor`/trial で次サイクルから起動する（全 invoice が更新型 = 全サイクルで `fee_policy` 固定値が正確に通る）。案 a（percent 併設）は初回のみ flat/丸め誤差が出るため不採用。
  3. **transfer 額の帳簿表現**：destination charge では transfer=額面**全額**・app fee は受取側残高から別途回収（純着金=額面−fee）。escrow/ledger の複式記帳はこの 2 段（transfer 全額＋fee 回収）を意識して起票する。
- **退避策（自前バッチ）は不要に**：PoC 成立のため Subscription 連携を本線とする。ただし設計（01/02）は引き続き `MembershipSubscriptionService` の差し替え可能な実装として閉じ込め、将来 SDK メジャー更新で機構再設計が必要になった場合の退避余地（@Scheduled＋ShedLock＋off_session PaymentIntent＋固定 application_fee_amount）は温存する。

---

## 5. ペイウォール（B）

### 5.1 受益者キー判定

- 既存 `content_payment_gates(payment_item_id, content_type, content_id, is_title_hidden)` を流用。
- コンテンツ閲覧時、紐づく payment_items すべてについて **`existsValidPaidPayment(viewerBeneficiaryUserId, itemId)`** が真であれば閲覧可。「誰が払ったか」でなく「閲覧者＝受益者に有効な支払いがあるか」で判定。
- `is_title_hidden=true` は未払い者にタイトルごと秘匿、`false` は「🔒 タイトル」を表示し購入導線を出す。

### 5.2 F00 可視性基盤との連結

- blog/お知らせの可視性に **支払い条件**を重ねる場合、`AbstractContentVisibilityResolver.evaluateCustom(projection, userId, snapshot)` をオーバーライドし `paymentGateService.isAccessibleByBeneficiary(userId, contentRef)` を呼ぶ（visibility=`CUSTOM` 経路）。
- ペイウォールは**可視性(visibility)と直交**する第二条件：`visibility` を満たし、かつ支払い済みであること。両者の AND をリゾルバ内で評価し、メトリクス・監査は既存の CUSTOM dispatch 計測に相乗り。
- 対象コンテンツ種別：`POST`（blog）・`ANNOUNCEMENT`（お知らせ）を第一弾。`FILE`/`SCHEDULE` は既存 enum にあり後続。

---

## 6. 協会→加盟チーム請求（E）

### 6.1 請求モデル `payment_requests`

組織(協会=ORG)が加盟チーム(TEAM)に対して発行する請求書。

```
payment_requests（新規・UUIDv7）
  issuer_scope_kind=ORG, issuer_scope_id      … 請求元（協会）
  payer_scope_kind=TEAM, payer_scope_id       … 請求先（加盟チーム）
  organization_id                              … テナント（協会）
  title, description, amount(face), currency
  due_date                                     … 支払期限
  status … DRAFT / SENT / VIEWED / PAID / OVERDUE / CANCELLED
  escrow_transaction_id                        … 支払い時に money rail へ連結
  created_by, created_at, updated_at, deleted_at
```

- 支払いは escrow `payer_scope_kind=TEAM`/`payee_kind=ORG`（**スキーマ上表現可能**＝V72.005 の CHECK 制約が許可）。
- 手数料折半は会費と同モデル（手数料は F22.1 `fee_policies` で解決・協会が額面−折半着金、加盟チームが折半上乗せ）。**※協会間請求の手数料負担方針はマスター御裁可点（§11-4・会費と同折半）**。

### 6.3 payer=TEAM の Customer 解決と立替/精算記録（案3・マスター確定 2026-06-04）

「チームが払う」をどう Stripe で表現するかは **案3** を採る（チーム残高直接払い＝将来の案2候補は §6.4）。

- **案3＝操作した ADMIN 個人の Stripe Customer で課金**する。escrow は `payer_scope=TEAM`（請求の主体はチーム）だが、Stripe の課金カードは**操作したチーム ADMIN 個人**の保存カード（その個人の `stripe_customers`）で行う。
- **領収書はチーム名義**（destination charge＋`on_behalf_of`＝役務提供者は協会、支払元の名義表示はチーム名）。
- **立替/精算記録を持つ**: ADMIN 個人が立替えた事実と、後にチームから精算された事実を `team_payment_advances`（**新規・UUIDv7**・[01 §2.5](01_data_model.md)）に記録する。協会請求支払い時に `PENDING` 起票し、**F04.9 確認必須通知**で精算確認 → `SETTLED`。チーム ADMIN が閲覧/確認できる画面（[04 §1](04_ui_i18n.md)）。
- これにより「チームの金をチーム ADMIN が立替えて協会へ払い、後でチームから精算を受ける」という現実の運用を、Stripe 上の課金主体（個人 Customer）と業務上の請求主体（チーム）の乖離を**立替記録で埋めて**表現する。

### 6.4 チーム残高直接払い（将来の案2候補・本設計では非採用）

チームの Connect 残高から直接支払う「案2」は、立替が不要になる利点があるが、Stripe Connect の account-to-account 送金の制約・残高有無の運用が重い。**本設計では採らず将来候補**とする。可否は別途**家老偵察**（Stripe Connect 残高間送金の実挙動・要件）に委ねる（本設計では立替モデル＝案3 を正典とする）。

### 6.2 通知配信・督促

- 発行(`SENT`)時、加盟チームの**管理者群**へ一斉配信：チーム ADMIN の `user_id` リストを集約し `ConfirmableNotificationService.send(recipientUserIds)`（確認必須通知・`actionUrl`＝支払い画面・`deadlineAt`＝due_date・`firstReminderMinutes`/`secondReminderMinutes` で自動督促）。
- F04.11 inbox に **新ソースアダプタ `PaymentRequestInboxAdapter`**（`InboxSourceType.PAYMENT_REQUEST`）を追加し、請求を inbox に集約。
- `OVERDUE` 自動遷移は @Scheduled バッチ（ShedLock）。督促は既存 confirmable のリマインド機構に委譲。

---

## 7. 支払いユーザの可視化（D）

- 既存 `PaymentSummaryService`（項目別 集計：支払済件数・未払件数・合計額）＋ CSV エクスポート（BOM付UTF-8）を拡張。
- 追加軸：**払い手列**（誰が払ったか）・**受益者列**・**未払/支払済/期限切れの3区分**・**期別(term)集計**・**継続課金の次回請求日**。
- 画面：チーム/組織管理者の「会費ダッシュボード」。項目×期別のマトリクス、受益者ごとの状態、CSV/PDF 出力（PDF は F12.1 基盤）。

---

## 8. 領収書・税からくり

### 8.1 領収書は受領者名義

- destination charge ＋ `on_behalf_of` により **役務提供者＝受領者（チーム/組織）**。会費の領収書は**受領者名義で発行**。
- 第一弾は「**受領者名義・金額のみ**」のシンプル領収書（Stripe `stripe_receipt_url` が受領者ブランドで出る分を活かしつつ、自前 PDF でも出力可）。
- **適格請求書の税内訳・登録番号欄は"拡張点"として枠だけ**用意（§8.2）。
- 別建てで **Mannschaft 名義の月次手数料明細**（受領者向け・仕入税額控除用）を出力する枠を設ける。

### 8.2 税は「からくりだけ」（多国対応の差し込み口）

| 差し込み口 | 内容 | 今の既定 |
|---|---|---|
| `payment_items.tax_category` / `tax_rate` / `price_includes_tax`（nullable） | 税区分・税率・税込/税抜 | NULL（税なし扱い・現挙動不変） |
| `connect_accounts.tax_registration_number` / `tax_status`（nullable） | 受領者の登録番号・課税区分 | NULL |
| `TaxPolicy` 戦略インターフェース `resolve(country, item, amount) → TaxBreakdown` | 国別税計算 | **`NoOpTaxPolicy`**（不課税・税額0を返す） |
| 領収書テンプレートの税内訳/登録番号セクション | 適格請求書要件 | 非表示（枠のみ） |

将来、`JapanConsumptionTaxPolicy` 等を**国別に差すだけ**で税計算・適格請求書が効く。**実装は本機能スコープ外**。税法上の確定（会費の課税判定／手数料・上乗せ分の課税関係／インボイス発行主体／前受金の繰延）は **§11-2 要税理士確認**として残置。

---

## 9. F08.2 と F22.1 統一レールの結節・重複排除（F）

| レイヤ | 担当 | 重複排除の方針 |
|---|---|---|
| money 移動（与信/capture/transfer/返金/台帳） | **F22.1 `payment.escrow` ＋ `ConnectChargeService`** | 会費も謝礼も**単一の Connect 送金基盤**。会費は即時モード。返金は受取側 ADMIN・`reverse_transfer:true`/`refund_application_fee:false` |
| 手数料計算 | **F22.1 `PaymentFeeCalculator`** | 散在禁止・一元化。会費/謝礼/協会請求すべて同一計算 |
| 会費の意味づけ（項目・有効期間・受益者・ペイウォール・集計） | **F08.2 ＋ 本 F08.9** | membership ドメインに閉じる。money rail とは `escrow_transaction_id` で疎結合 |
| Connect 口座・onboarding・Webhook 冪等 | **F22.1 `payment.connect`** | USER/TEAM/ORG を抽象化済。会費受領も同口座 |
| 継続課金 | **本 F08.9 `MembershipSubscriptionService`** | F08.2 の `SubscriptionController`/`TeamSubscriptionEntity` ガワを本実装で回収。Stripe Subscription 連携を内包 |

- **F08.2 の現状（素のCheckout・自社集金）は本機能で Connect 化して廃止**＝**第三者受取からの撤去**（F22.1 README §3.0・受取人で二分する統一アーキ原則）。**Mannschaft 自社受取（F09.13 通知クレジット等）は素 Checkout 残置**。移行は **Expand→Migrate→Contract**。
- **F22.1 payment への追記**（別PR・本軍議の付帯）：`source_kind=MEMBERSHIP` 実装・`face_amount`/`capture_mode` 列・手数料ランク（`fee_policies`）・Subscription/invoice 上書き・税からくり・領収書を README/01/02 に反映（**手数料ランク化は F22.1 側で正典化済・2026-06-04**）。

### 9.1 大会参加費（E 要件）× `tournament_fee` の3層結合（廃さず整理・マスター確定 2026-06-04）

大会/リーグ参加費まわりの既存資産は**廃止せず3層に整理**する（既存の `tournament_fee` 大会連結と `payment_requests` 請求書ライフサイクルはいずれも正典維持）。

| 層 | テーブル | 役割 | 正典性 |
|---|---|---|---|
| **大会連結** | `tournament_fee`（F08.7.1 07・既存） | 参加費 payment_item を大会/ディビジョンに結ぶ薄い連結 | **正典維持**（大会×参加費の対応の真実源） |
| **請求書ライフサイクル** | `payment_requests`（F08.9 §6・新規） | 協会→チーム請求の発行/送信/支払/期限/督促のライフサイクル | **正典維持**（請求の状態機械の真実源） |
| **ハブ** | `payment_items`（既存） | 上2層を結合する**ハブ**。金額・通貨・税からくりを一元管理 | 結合点（`tournament_fee.payment_item_id` ⇔ `payment_requests` の対象 item） |
| **意味づけ** | `member_payments`（既存・払い手分離拡張） | 「誰が・誰のために・どの項目を払ったか」の意味づけ記録（受益者キー・ペイウォール・集計） | 意味づけの真実源 |
| **money rail** | `escrow_transactions`（F22.1・`source_kind=MEMBERSHIP`／後続 `TOURNAMENT`） | 与信/capture/transfer/返金/台帳（Connect 送金） | 金の真実源（F22.1） |

- **`tournament_fee`（大会連結）と `payment_requests`（請求書）は `payment_items` をハブに結合**する。同じ参加費を「大会に紐づけて見せる（tournament_fee）」「協会がチームへ請求書として送る（payment_requests）」両面から扱えるが、**金額・税は payment_item 1箇所**に集約し二重管理しない。
- **member_payments** は意味づけ（受益者・払い手・状態）、**escrow_transactions** は money rail（F22.1）として疎結合（`escrow_transaction_id` 連結）。
- F08.7.1 参加費が暫定の素 Checkout から **F22.1 Connect（`source_kind=TOURNAMENT`）へ移行**する際も、この3層（連結/請求書/意味づけ）はそのまま、money rail のみ Connect に差し替える（F22.1 README §3.0.1）。

---

## 10. 段階ロードマップ（G）

依存と規模(S/M/L)を明示。各段は test-first（BEドメインUT＋API契約テスト先行）。

| 段 | 名称 | 規模 | 依存 | 主要成果 |
|---|---|---|---|---|
| **P1** | 払い手分離＋会費Connect化（即時） | **M** | F22.1 P2-b（`ConnectChargeService`/`PaymentFeeCalculator`/`face_amount`/`capture_mode`）| `member_payments.payer_user_id`＋`escrow_transaction_id`／`source_kind=MEMBERSHIP` 即時 capture／本人払い・管理者手動記録の Connect 化／受益者キー判定の維持 |
| **P2** | 後見まとめ支払い＋代理払い認可 | **M** | P1・F01.9・F03.12・F14.1 | 保護者リンク経由の代理払い／`payment_proxy_grants`／後見まとめ支払い画面（複数子の会費一括） |
| **P3** | 年齢段階つき後見切替 | **M** | P2・`users.birthDate` | `X-Proxy-For-User-Id` 後見切替セッション／年齢ゲート（小学生まで強権・中学生以降封印）／監査連結 |
| **P4** | ペイウォール（受益者キー） | **S** | P1・F00 | `content_payment_gates` の受益者キー判定／`evaluateCustom` 連結／blog・お知らせ施錠UI |
| **P5** | 継続課金（Subscription＋invoice上書き） | **L** | P1・**PoC 成立済（2026-06-05・§11-3）** | `MembershipSubscriptionService`／Subscription 作成・`invoice.created` 上書き・`invoice.paid` 起票／dunning 状態反映。**初回=単発 destination charge＋次サイクル開始（案 b）／API バージョン acacia 固定**。自前バッチ退避は不要 |
| **P6** | 期別課金（単発） | **S** | P1 | `payment_items.type=TERM`／term 期間／単発 destination charge |
| **P7** | 協会→チーム請求 | **M** | P1・F04.9・F04.11 | `payment_requests`／payer=TEAM/payee=ORG 決済／確認必須通知配信・督促／inbox アダプタ |
| **P8** | 可視化拡張＋領収書＋税からくり | **M** | P1〜P7 | 払い手/受益者・3区分・期別集計・CSV/PDF／受領者名義領収書＋月次手数料明細／nullable 税列＋`NoOpTaxPolicy`＋領収書拡張枠 |

> **依存ハードライン**：P1〜P8 は F22.1 **P2-b**（`ConnectChargeService`/`PaymentFeeCalculator`/`face_amount`/`capture_mode`）と **P2-e**（`EscrowSourceKind.MEMBERSHIP`）の完了を前提とする。F22.1 P2-b/e のマイルストーンを固定し、その完了後に本機能 P1 着手（並行着手で blocked にしない）。
>
> 新規ドメイン/改修：`payment`（payer分離・subscription・payment_request・proxy grant・tax からくり）／`membership`（受益者×支払いの結線）／`notification`・`inbox`（協会請求配信）／`cms`・`social.announcement`（ペイウォール連結）。F22.1 `payment.escrow`/`payment.connect` は**再利用のみ**。

---

## 11. 未解決 → 確定（H）

| # | 論点 | 区分 | 確定/方針 |
|---|---|---|---|
| **11-1** | 後見切替の年齢しきい値 | **確定（御裁可済 2026-06-03）** | 提案採用。ただし**初等教育終了年齢は国で異なる**ため、しきい値を焼き付けず**国別 `GuardianshipAgePolicy` のからくり**で解決（税の `TaxPolicy` と同型）。既定＝`JapanGuardianshipAgePolicy`（満12歳年度末）。未対応国は満13歳誕生日にフォールバック（§3.2／03_security §3.1） |
| **11-2** | 税務（会費の課税判定／手数料・2.5%上乗せ分の課税関係／適格請求書の発行主体／前受金の繰延） | **要税理士確認** | 実装スコープ外。からくり（nullable 列＋`TaxPolicy`＋領収書拡張枠）のみ。確定まで `NoOpTaxPolicy`（不課税） |
| **11-3** | 継続課金の invoice 固定手数料上書きが destination charge と噛み合うか | **確定（PoC 成立 2026-06-05・条件付き）** | Stripe テスト環境で実証済。更新サイクル invoice の draft 窓で `application_fee_amount=53` 固定上書き→charge へ伝播（subscription の percent 自動計算 50 を完全上書き）を確認。**条件**：①API バージョン `2025-02-24.acacia` 固定（stripe-java 28.2.0／SDK メジャー更新時は機構再設計）、②初回 invoice は上書き不可ゆえ**案 b**（初回=単発 destination charge＋Subscription は次サイクル開始）、③transfer は額面全額・fee は受取側から別途回収（純額=額面−fee）を帳簿に明記。**自前バッチ退避は不要**に。固定上書き値は `fee_policy`（F22.1 §3.4・率→固定額）で算出。PoC 詳細＝`scripts/poc/README_f089_p5_poc.md` §0 |
| **11-8** | サブスク解約/今月スキップ/再開 | **確定（マスター御裁可済 2026-06-04）** | 解約＝`cancel_at_period_end`（期末まで利用可・日割り返金なし）／今月スキップ＝`pause_collection(void)`＋`skip_until` 列（invoice void で valid_until 不延長＝ペイウォール無改修で整合）／再開＝pause 解除（§4.5 / 01 §2.1 / 02 §4.3 / 04 §2） |
| **11-9** | payer=TEAM の決済表現（立替モデル） | **確定（マスター御裁可済 2026-06-04・案3）** | 操作 ADMIN 個人 Customer で課金・escrow payer_scope=TEAM・領収書チーム名義・`team_payment_advances` で立替/精算（F04.9 確認）。チーム残高直接払い（案2）は将来候補・家老偵察（§6.3 / §6.4 / 01 §2.5 / 02 §7） |
| **11-4** | 協会→チーム請求の手数料負担（2.5%折半を会費と同じくするか・協会間B2Bで上乗せ表示が妥当か） | **確定（御裁可済 2026-06-03）** | 提案採用＝**会費と同折半**（チーム2.5%上乗せ・協会97.5%着金） |
| **11-5** | 非後見の代理払い（祖父母・スポンサー）の許諾UX（`payment_proxy_grants` 軽量 grant か F14.1 同意書か） | 設計内確定 | 日常は保護者リンク自動許可、第三者は軽量 grant（有効期限つき）。F14.1 は組織代理の重い経路として温存（§3.3） |
| **11-6** | 既存会員データ移行 | 解決済 | **不要**（開発中・本番データ無し・マスター確認済 2026-06-03） |
| **11-7** | 無ログイン管理子アカウント | 解決済 | **不採用**（子は自前アカウントでITリテラシー育成・マスター確認済 2026-06-03） |

> 11-1/11-4 は**御裁可済（提案採用・2026-06-03）**。11-1 は国別 `GuardianshipAgePolicy` のからくりとして確定。11-3 は **PoC 成立（2026-06-05・条件付き）で確定**（自前バッチ退避は不要に）。残る外部関門は 11-2（税理士）のみで、設計はからくり先行で待てる。**設計内の論点はすべてクローズ**。

### 11.1 二度精査で補強・設計内確定した論点（2026-06-03・敵対的検分2手）

検分で洗い出した穴は**すべて設計に反映済み**（「あとで決める」を残さない）。

| 論点 | 反映先 | 確定内容 |
|---|---|---|
| 中学進学＝切替封印時の**自立移行**（子が自分のアカウントを使い始める導線） | 02_api §2.3 | 3ヶ月前予告＋パス設定メール＋未引継ぎ保険。保護者の会費代理払いは封印後も継続（滞納防止） |
| 継続課金の**失効状態機械**（PAST_DUE→ACTIVE 復帰・grace） | 02_api §4.2 | 既存 `payment_items.grace_period_days` を流用・失効トリガーは「期限切れ」一本に統一・`invoice.paid` で復帰 |
| 会費**値上げ時の既存サブスク** | 02_api §4.1 | 加入時 price で固定。改定は新規のみ・既存者は確認必須通知で乗換選択 |
| 共同親権の**二重払い可視化** | 02_api §1.2 | `payable-dues` が `alreadyPaid`/`paidBy` を返す・bulk は起票直前に再認可 |
| **後見切替中の払い手記録** | 02_api §1.1 / 03_security §2 | 払い手は保護者のまま・`payer_relationship=GUARDIAN_PROXY` で区別 |
| 退会時の**サブスク/grant 失効** | 01_data_model §6 | `UserWithdrawalService` トランザクション内でアトミック失効・バッチは掃き取りの二重防御 |
| Connect 口座**無効化**時の払い手体験 | 02_api §1.1 / 04 §3 | 払い手へ「受け取り準備中」表示・恒久/一時を `onboarding_status` で判定 |
| 第三者 grant の**過大権限** | 01_data_model §2.3 | 包括 grant は `effective_until` 必須(CHECK)＋`max_amount` |
| 協会請求の**再請求** | 01_data_model §2.2 | `superseded_by_id` で旧 CANCELLED を新請求へ連結・回収率は PAID 件数集計 |
| 税列の**後方互換** | 01_data_model §1.2 | NULL の間は現挙動と完全一致・既存集計に影響なし |
| escrow の**組み合わせ整合** | 01_data_model §3.1 | source_kind×scope マッピング表＋防御的複合 CHECK 案 |
| 年齢境界の**テスト** | 03_security §3.1 | 3/31↔4/1・4/1生まれの必須テストケース明記 |

### 11.2 是正・実装ノート（マスター確定 2026-06-04・正典）

合同軍議で確定した F22.1 依存の是正・実装上の注意点を正典として明記する。

| 論点 | 反映先 | 確定内容 |
|---|---|---|
| **F22.1 依存の過大記述の是正** | §1.2 / 01 §3 | P2-b/c は origin/main 実装済（V73.003・`ConnectChargeService`(authorize/capture/refund)・`PaymentFeeCalculator`・即時 `charge()` も実装済）。旧「P2-b/e 未実装」記述を「**実装済・残は手数料ランク化（`fee_policies`・F22.1 P2-f）等**」に訂正 |
| **proxy scope `PAYMENT` は実在の枠組みに値1つ追加** | §3.3 / 03 §2 | `proxy_input_consent_scopes.feature_scope`（VARCHAR(64)・V18.011・CHECK なし）に enum 値 `PAYMENT` を1つ足すだけ（**列追加・DDL 不要**）。代理払い認可・退会失効はこの scope 行で判定 |
| **後見切替中の payer 固定** | §3.2 / 02 §1.1 / 03 §2 | 後見切替セッション中（`X-Proxy-For-User-Id`）でも**払い手は保護者のまま固定**（子になりすまさない）。`payer_relationship=GUARDIAN_PROXY` で区別記録 |
| **年齢判定の birth_date 復号はバッチ化** | 03 §3.1 | `users.birthDate`（暗号化保存）の復号は決済/切替の都度ではなく、**年齢段階の判定をバッチで事前算出**（`switchAllowed`/`stageKey` のスナップショット）してホットパスで復号を持ち回らない。境界日（年度末・誕生日）に再計算（Clock 注入・date-pin テスト） |
| **country_code NULL フォールバック** | §3.2 / 03 §3.1 | `users.country_code` 欠落・未対応国は**安全側フォールバック**（満13歳誕生日で封印）＋ログ記録（症状を隠さない） |
| **協会請求 payer=TEAM の Customer 解決（案3）** | §6.3 / 01 §2.5 / 02 §7 | チームが払う＝**操作 ADMIN 個人の Stripe Customer で課金**・escrow `payer_scope=TEAM`・**領収書チーム名義**・`team_payment_advances` に立替/精算を記録（F04.9 確認必須通知で精算確認 → SETTLED）。チーム残高直接払いは将来案2候補（家老偵察） |

---

## 12. 変更履歴

| 日付 | 内容 |
|---|---|
| 2026-06-03 | 初版。マスター軍議（払い手≠受益者・年齢段階後見切替・継続=Subscription+invoice上書き・税からくり・移行不要/管理アカウント不採用の確定）を反映し起草。origin/main 実機棚卸し（F22.1 payment P2-a／F08.2／後見機構 F01.9・F03.12・F14.1／通知・F00）を一次ソースに設計 |
| 2026-06-03 | マスター御裁可（§11-1 年齢しきい値・§11-4 協会請求手数料＝いずれも提案採用）。**初等教育終了年齢が国で異なる**との指摘を受け、後見切替しきい値を**国別 `GuardianshipAgePolicy` のからくり**へ一般化（税 `TaxPolicy` と同型・`users.country_code` で解決・JP既定・未対応国は満13歳フォールバック・schema改修不要）。README §3.2／03 §3.1／02 §2／04 i18n を国別語彙へ統一。ステータス＝御裁可2点反映済 |
| 2026-06-04 | **統一決済アーキ正典化の反映（マスター承認済・合同軍議/精緻化軍議）**。(C) **サブスク解約＝`cancel_at_period_end`／今月スキップ＝`pause_collection(void)`＋`skip_until` 列**・再開を §4.5／01 §2.1／02 §4.3／04 §2 に追加（スキップ月は invoice void で `valid_until` を延ばさず＝ペイウォール無改修で整合）。(D) **payer=TEAM の Customer 解決＝案3**（操作 ADMIN 個人 Customer で課金・領収書チーム名義・`team_payment_advances` 立替/精算記録・F04.9 確認）を §6.3／01 §2.5／02 §7 に追加。(E) **F22.1 依存の是正**（P2-b/c は main 実装済・「未実装」記述を訂正・§1.2／01 §3）／**proxy scope `PAYMENT` は `proxy_input_consent_scopes.feature_scope` に値1つ追加で実現＝列追加不要**（§3.3／03 §2）／後見切替 payer 固定・birth_date 復号バッチ化・country_code NULL フォールバックを §11.2 実装ノートに明記。(3層結合) `tournament_fee`（大会連結）＋`payment_requests`（請求書）を `payment_items` ハブで結合・`member_payments`（意味づけ）・`escrow_transactions`（money rail）に整理（§9.1・廃さず）。手数料は F22.1 `fee_policies`（率%＋固定額）連動（DEFAULT で従来一致）。 |
| 2026-06-03 | **二度の敵対的精査**（安全性整合／UX保守完了性の2手）を実施し、検出した重大・要修正をすべて反映。主な補強：自立移行フロー（02 §2.3）・継続課金失効状態機械＋grace（02 §4.2）・価格固定（02 §4.1）・二重払い可視化＋bulk再認可（02 §1.2）・後見切替時の払い手記録 GUARDIAN_PROXY（02 §1.1/03 §2）・退会時アトミック失効（01 §6）・Connect無効化時の払い手体験（02 §1.1）・grant上限/期限CHECK（01 §2.3）・協会請求の再請求 superseded（01 §2.2）・税列後方互換（01 §1.2）・escrow組合せ整合＋複合CHECK案（01 §3.1）・年齢境界テスト（03 §3.1）。member_payments 追加列が未実装である旨を明示（01 §1）。ステータス 🟡→🟢 |
