# 柱③-B 組織契約の請求担当と個人支払手段の分離 設計書

> 起票日: 2026-09-02（Codex 検分1巡目 P0×4/P1×8/P2×3 → 検分2巡目 P0×1/P1×5 → 検分3巡目 P0×1/P1×3 → 検分4巡目 P1×2 → 検分5巡目 P1×2 の差し戻しを受け改訂。マスターより「これが最後の改訂、検分は打ち切り、以後は殿が直接確認する」と明示された）
> 担当: 足軽（本設計書は Codex 検分の要求事項を踏まえた仕様固め）
> ステータス: 🟡 設計段階（レビュー待ち・殿の直接確認待ち。Codex検分は本改訂をもって打ち切り）
> 課題管理: CMP-260901-1538
> 参照: [`account_purge_last_admin_succession.md`](./account_purge_last_admin_succession.md) / [`withdrawal_flow_immediate_anonymization_fix.md`](./withdrawal_flow_immediate_anonymization_fix.md) / [`domain_db_design_principles.md`](./domain_db_design_principles.md)

---

## §0. 検分対応表（Codex 検分1巡目・全15件）

指摘全文は本 PR には貼らない。採否と対処のみを記す。○=採用・改訂反映済み、△=方向づけを一部調整して採用。

| # | 重大度 | 論点要約 | 採否 | 対処（反映章） |
|---|---|---|---|---|
| P0-1 | P0 | 置換方式では新旧サブスクの併存期間に二重課金が起きる | ○ | 新サブスクは `trial_end=旧 current_period_end` で作成し、旧期末まで無課金にする方式へ変更（§2.3） |
| P0-2 | P0 | Idempotency-Key は24hで失効し一次防衛にならない | ○ | 一次防衛を DB（`psp_new_subscription_ref` 永続化）＋ Stripe metadata 照合へ移す。Key は補助（§3.2〜3.4）。**※照合手段は検分3巡目でSearch APIからList Subscriptions APIへ変更（§0.2参照）** |
| P0-3 | P0 | 旧サブスクの webhook が `hardDeleteBySlot` で新契約の pointer を消しうる | ○ | `hardDeleteBySlot` をスロット単位から `contract_id` 一致条件へ変更する実装項目を追加。AC-14 を書き換え（§3.7、§8） |
| P0-4 | P0 | 新契約作成時点で pointer を持つと `uk_acp_slot` と衝突し得る | ○ | 状態機械に `PENDING_HANDOVER`（pointer 無し）を追加し、切替 TX でのみ pointer を新へ移す（§3.1） |
| P1-5 | P1 | 成功条件が複数 webhook に分散し不整合の余地がある | ○ | `checkout.session.completed` を正とし他は冪等な補強と明記。PAST_DUE/3DS/PaymentMethod無しの遷移表を追加（§3.6） |
| P1-6 | P1 | PaymentMethod 未検証のまま切替に入るリスク | ○ | ACCEPTED→SWITCHING 前の PaymentMethod 検証を必須ステップ化（§3.6、AC-16） |
| P1-7 | P1 | Idempotency-Key の単位と既存解約キーとの関係が未整理 | ○ | handover 経由の解約は `billing-handover-cancel-*`、通常解約は既存 `billing-cancel-*` のまま棲み分けと明記（§3.4） |
| P1-8 | P1 | open request の一意性を守るDB機構が未確定 | ○ | 生成列 + UNIQUE（終端外 status のみ非NULL）で確定。村の現役所属重複と同型と明記（§4.2） |
| P1-9 | P1 | purge の期末解約フォールバックと handover の交錯が未整理 | ○ | 相互条件表を追加。原則「handover が REQUESTED/SWITCHING の間は purge fallback を発火させない」（§5.4） |
| P1-10 | P1 | 「引継先候補なし」の判定が粗い | ○ | 5分岐（ADMIN 0 / 全員退会予定 / PaymentMethod無し / 承諾後認証失敗 / 複数ADMIN）でAC化（§5.5、AC-17〜21） |
| P1-11 | P1 | 5/6 と同根（PaymentMethod検証の欠落） | ○ | P1-6 と統合対応（§3.6） |
| P1-12 | P1 | 競合5種の未列挙 | ○ | 承諾×fallback・承諾×期限切れバッチ・切替×旧解約・Stripe成功×DBロールバック・webhook×同期処理をACへ追加（§8、AC-22〜26） |
| P2-13 | P2 | TEAM/ORG の payer NOT NULL 制約が未確定 | ○ | MySQL CHECK 制約の限界（非決定的関数不可）を明記の上、アプリ検証＋監視クエリで担保（§4.1） |
| P2-14 | P2 | status/scope_kind の許容値・遷移表が未整備 | ○ | 状態遷移表・許容値表を追加（§3.1、§4.2） |
| P2-15 | P2 | 保持期間・法的根拠・匿名化方式が抽象的 | ○ | 保持期間・仮名化方式・監査に残す相関IDの範囲を具体化（§7） |

### §0.1 検分2巡目対応表（残 P0×1・P1×5）

| # | 重大度 | 論点要約 | 採否 | 対処（反映章） |
|---|---|---|---|---|
| R2-P0-1 | P0 | `PENDING_HANDOVER` が既存 `ContractStatus` enum / `chk_bc_status` CHECK に無い | ○ | §4.1 の ALTER に CHECK 更新（5値→6値）と `ContractStatus` enum への値追加を明記。billing_contracts.status の遷移表に `PENDING_HANDOVER` の入口/出口を追加（§3.1改） |
| R2-P1-2 | P1 | 旧期末〜`invoice.paid` の間に entitlement 空白が生じ得る | ○ | 裁定どおり: pointer 切替は「旧期末到達」を条件に行い `invoice.paid` を待たない。entitlement は新契約 pointer で担保、初回請求失敗は通常の `PAST_DUE` 遷移に乗せる（特別扱いしない）。AC書き換え（§3.1改・AC-27/28） |
| R2-P1-3 | P1 | 成功条件（引継確定/pointer切替/健全性確認）の混同 | ○ | 3段階に分離して一本化: (a)引継確定=`checkout.session.completed` (b)pointer切替=旧期末到達 (c)`invoice.paid`=事後健全性確認。`trialing`を「請求成功」と誤読させない注記を追加（§3.6改） |
| R2-P1-4 | P1 | 旧契約が `PAST_DUE`／`current_period_end` が過去の場合の扱いが未確定 | ○ | 分岐確定: 該当時は `trial_end` 方式を使わず**引継要求自体を拒否**。先に旧契約の支払回収 or 解約を要求する。AC追加（§3.6改・AC-29） |
| R2-P1-5 | P1 | SCA/3DS対応が PaymentMethod有無確認のみで不十分 | ○ | Checkout(subscriptionモード)は3DSをCheckoutフロー内で完了させることを公式で確認。加えてtrial終了時のoff-session請求に備え`pending_setup_intent`の確認/事前認証をSWITCHING前検証に追加（§3.6改・AC-30） |
| R2-P1-6 | P1 | 引継対象契約の絞り込み条件が粗い（無償/PSP未作成契約の扱い未定義） | ○ | 検出クエリに `psp_subscription_ref IS NOT NULL AND current_period_end IS NOT NULL` を追加。無償/PSP未作成契約は「payer概念のみ更新（Stripe操作なし）」の別経路として1行定義（§5.1改） |
| R2-P0-2注記 | — | Search API の60秒を保証値であるかのように書いていた | ○（後に§0.2で不使用に変更） | 「最短でも60秒」（保証ではない旨）に文言修正（§3.2）→検分3巡目でSearch自体を不使用に変更 |

### §0.2 検分3巡目対応表（残 P0×1・P1×3）

| # | 重大度 | 論点要約 | 採否 | 対処（反映章） |
|---|---|---|---|---|
| R3-P0 | P0 | 二重サブスク作成の回復経路（Search API）が鮮度遅延を持ち一次防衛として弱い | ○ | 回復経路の照合を **List Subscriptions（`customer`指定・`status=all`）+ `metadata.handoverRequestId`クライアント側フィルタ**へ変更。Stripe公式ドキュメント（`docs.stripe.com/search`）が「read-after-writeフローにはリストアップAPIを使え。これらはSearchの鮮度遅延の影響を受けない」と明記していることを根拠に確認。「DBにref無し AND Listにも無し」の両方確認後にのみ新規作成を許可。Search APIの記述は削除し不使用に変更（§3.2） |
| R3-P1-3 | P1 | 旧解約失敗時に二重課金しうる構造的リスク（切替時cancelImmediately方式） | ○ | `cancelImmediately`方式を廃止。**承諾確定（`checkout.session.completed`）と同時に旧サブスクへ`cancel_at_period_end=true`を設定**する方式へ変更。以後どの後続手順が失敗しても旧は期末で必ず終了し二重課金の余地が構造的に無くなる。引継が期末前にFAILED確定した場合のみ`cancel_at_period_end=false`へ差し戻し継続。切替TXはローカルDB操作（pointer付替え＋状態遷移）のみとなりStripe呼び出しを含まないため冪等リトライが単純化。`PARTIALLY_COMPLETED`の意味を「Stripe側確定済み・ローカル切替のみ未了」に再定義し終端/非終端の記述矛盾（P2該当）も解消（§2.3、§3.1、§3.4、§3.5、§3.6） |
| R3-P1-2 | P1 | `pending_setup_intent`検証の実行主体・タイミングが不明確で(b)pointer切替条件（旧期末到達のみ）と矛盾しうる | ○ | 認可者を明確化: **切替バッチ（旧期末到達時）が唯一の切替TX実行者**。実行前チェックで`pending_setup_intent`が未解決なら**切替せずhandoverをFAILEDに確定**（新trialサブスクをcancelImmediately=無課金取消、旧サブスクは`cancel_at_period_end`を解除して継続）。ACCEPTED直後（1段目・通知のみ）と旧期末到達時（2段目・最終確定）の二段チェックとして遷移表に反映（§3.6） |
| R3-P1-4 | P1 | AC-6が旧仕様（cancelImmediately + invoice.paid待ち）のまま矛盾していた（伝播漏れ） | ○ | AC-6を新仕様（承諾時cancel_at_period_end予約／切替はローカルのみ）へ書き換え。本文全体を `cancelImmediately` / `invoice.paid` 切替条件 / `Search` の3語でgrepし残骸を総なめして除去（§8、全章） |
| R3-P2 | P2 | AC-25の間隔表記（60秒）が新方式（List・鮮度遅延なし）と不整合 | ○ | List Subscriptionsはread-after-write整合のため待機間隔が不要である旨に統一（§8 AC-25） |

### §0.3 検分4巡目対応表（残 P1×2・最終是正）

| # | 重大度 | 論点要約 | 採否 | 対処（反映章） |
|---|---|---|---|---|
| R4-P1-1 | P1 | List Subscriptions照会が1ページ目のみの確認で「未作成」と読める記述だった（ページング未考慮） | ○ | `has_more`に従う**全ページ走査**を必須化し、auto-paginationヘルパー使用を明記。「1ページ目に無ければ未作成」と読める記述を排除。AC-33新設（§3.2） |
| R4-P1-2 | P1 | 承諾確定時の`cancel_at_period_end`設定APIが失敗/未実行のまま残るケースが未考慮 | ○ | 二重防衛を新設: (a)設定成功を`old_cancel_scheduled_at`に永続化し、未設定のまま残る行を**夜次照合バッチがStripe実物と突合して検出・再設定**（冪等）。(b)旧期末到達時の切替バッチが切替前チェックでStripe実物の`cancel_at_period_end`を確認し、`false`ならその場で設定してから切替、それも失敗すれば手動介入待ちの専用異常系へ倒しアラート発火。AC-34/35新設（§3.6.1、§4.2） |

### §0.4 検分5巡目対応表（残 P1×2・最終改訂。以後の巡回は打ち切り）

| # | 重大度 | 論点要約 | 採否 | 対処（反映章） |
|---|---|---|---|---|
| R5-P1-1 | P1 | 切替バッチが旧期末到達後まで遅延して走り、`cancel_at_period_end=false`のまま旧が更新済み（期末境界越え）だった場合の分岐が未定義。「その場でtrue設定して続行」のままでは既に更新された期間の二重課金を見逃す | ○（撤回） | 「その場でtrue設定して続行」を撤回。旧サブスクの`current_period_start`を確認し期末境界越えを判定。**既定は`MANUAL_INTERVENTION`へ倒しアラート**（自動でのvoid/refundは行わない）。手動介入時の標準手順としてStripe公式のVoid an invoice API（未払いinvoice向け）／Create a refund API（支払済みcharge向け）を運用手順書として記載し、**自動実行は本設計のスコープ外**と明記（§3.6.1(b)） |
| R5-P1-2 | P1 | `MANUAL_INTERVENTION`状態が非公式のまま使われていた（正式定義なし） | ○ | 状態一覧（9値）・遷移表・DDL許容値・生成列open判定（非終端）・入口3種・出口（`RESUME`による`SWITCHING`復帰／`FAILED`確定の2択）・アラート先（ADMIN＋運用チーム）を正式定義。AC-35を書き直しAC-36/37を新設（§3.6.2） |
| R5-P2-1 | P2 | `old_cancel_scheduled_at`が差し戻し時にNULLクリアされる旨の記述が無かった | ○ | 差し戻し（`cancel_at_period_end=false`）と対で`old_cancel_scheduled_at`もNULLクリアする旨を明記。AC-32に追記（§3.6.1） |
| R5-P2-2 | P2 | 「Stripe API成功と同一トランザクションでDB永続化」という誤った原子性表現が複数箇所に残存 | ○ | 「Stripe API成功後にDB永続化（原子性は成立しない・夜次照合が補完）」へ修正。`old_cancel_scheduled_at`（§3.6.1）と`psp_new_subscription_ref`（§3.2）の両方を修正（pointer付替えのようなDBのみで完結する操作は同一トランザクションのままで正しいため区別して残置） |

---

## §1. 問題定義（実コード再確認済み・変更なし）

### 1.1 payer が「操作者個人」に暗黙固定されている

[`StripeBillingPaymentGateway#createSubscriptionCheckout`](../../backend/src/main/java/com/mannschaft/app/billing/StripeBillingPaymentGateway.java) は次のように決済者を解決する。

```java
String stripeCustomerId = paymentMethodService.getOrCreateStripeCustomerId(operatorUserId);
```

`operatorUserId` はチェックアウトを叩いた「今この瞬間の ADMIN 個人」の user ID であり、TEAM/ORG スコープの `billing_contracts` であっても Stripe 上の Customer・支払い手段は常にこの個人に紐づく。契約作成時に保存される `createdBy`（[`BillingContractEntity.java:119-120`](../../backend/src/main/java/com/mannschaft/app/billing/BillingContractEntity.java)）が、事実上「誰の財布で払っているか」を表す唯一の記録になっている。

### 1.2 purge は USER スコープしか見ない

[`BillingContractService#cancelAllUserContractsForPurge`](../../backend/src/main/java/com/mannschaft/app/billing/BillingContractService.java#L562) は `EntitlementScopeKind.USER` 固定でクエリしている。ある個人が TEAM/ORG 契約の実質 payer（`createdBy` = その人）であっても、この検索条件には一切引っかからない。**退会 30 日後の物理匿名化を経ても、TEAM/ORG 契約は退会者個人の Stripe Customer への課金を止めずに継続する。**

### 1.3 `WithdrawalStripeHandler` は実質スタブ

[`WithdrawalStripeHandler.java`](../../backend/src/main/java/com/mannschaft/app/gdpr/service/WithdrawalStripeHandler.java) は `WithdrawalRequestedEvent`（Day 0・退会受付）を購読するが、実際の Stripe API 呼び出しは

```java
log.warn("Stripeサブスクキャンセル未実装: userId={}", userId);
```

のとおり **未実装**。`billing_contracts`／`membership_subscriptions`（payer/beneficiary/payee 3分離済み・[`MembershipSubscriptionEntity.java:79-84`](../../backend/src/main/java/com/mannschaft/app/payment/entity/MembershipSubscriptionEntity.java)）のいずれも本ハンドラの対象外。

### 1.4 実害（変更なし）

1. 組織 ADMIN が退会 → TEAM/ORG 契約は解約されず、退会者個人の Stripe Customer への課金が継続する
2. `membership_subscriptions.payer_user_id` が退会しても、受益者への請求引継の仕組みが無い
3. 30 日後の強匿名化と、裏で継続課金しているサブスクリプションの併存という法務リスク

---

## §2. Stripe 公式資料での方式決定（P0-1 対応・裏取り済み）

### 2.1 Subscription の customer 変更は API 非対応（確定・維持）

`stripe docs api subscriptions/update`（Update a Subscription）の全パラメータを確認した結果、**`customer` は Update Subscription のパラメータに存在しない**。Subscription の customer 差し替えは REST API では非対応。

→ 方式は「新 Customer で新規サブスクリプションを作成し、旧サブスクリプションを解約する」置換方式で確定（変更なし）。

### 2.2（削除）単純な期末待ちの二重課金リスクは §2.3 で根治

Codex 検分 P0-1: 単純に「新サブスクを即時作成 → 旧を期末解約」とすると、新の初回請求が旧の残存期間と重なり、**併存期間に二重課金が発生する**。日割り返金の追加処理でも根治にならない（返金APIの追加呼び出しはそれ自体が別の失敗点になる）。よって「新サブスクの課金開始自体を旧期末まで遅らせる」構成に変更する。

### 2.3 確定方式: `trial_end` による無課金期間の構成（P0-1 根治）

Stripe公式ドキュメント（`docs.stripe.com/api/subscriptions/create`・`docs.stripe.com/billing/subscriptions/trials`）で以下を確認した。

- Subscription 作成時に `trial_end`（Unix timestamp）を指定すると、そのサブスクリプションは `trialing` ステータスで作成され、**trial_end に到達するまで一切請求が発生しない**（0円トライアルの標準動作）
- trial 終了時、`billing_cycle_anchor` は既定で `now`（＝trial_end のタイミング）にリセットされ、**日割りなしで新価格の全額を請求する新しい請求書が生成される**（Trial Offers ドキュメントで明記された標準動作。従来の `trial_end` パラメータでも同じ billing_cycle_anchor リセット挙動が適用される）

これを利用し、**新サブスクの `trial_end` に「旧契約の `current_period_end`」を指定して作成する**。

| 論点 | 確定事項 |
|---|---|
| 新サブスク作成パラメータ | `customer=新Customer`, `items=[price]`, `trial_end=旧current_period_end（Unixtimestamp）`, `proration_behavior=none`, `metadata={handoverRequestId, oldContractId}` |
| 併存期間の課金 | ゼロ。新サブスクは `trialing` のまま旧期末まで請求されない |
| 旧期末と新開始の隙間 | ゼロ。新サブスクの `trial_end` = 旧サブスクの `current_period_end` と同一 Unix timestamp を明示指定するため、隙間・重複とも発生しない（AC-15 で検証） |
| 新サブスクの初回請求日 | 旧 `current_period_end` と同時刻。`billing_cycle_anchor` はその時刻にリセットされ、以後はその日を起点に周期が回る |
| 旧サブスクの扱い | **（R3-P1-3 裁定で確定・二重課金構造裁定）** `cancelImmediately` は廃止する。**承諾確定（(a)引継確定条件 = `checkout.session.completed` 到達）と同時に、旧サブスクへ `cancel_at_period_end=true` を設定する**（既存 `StripeBillingPaymentGateway#cancelAtPeriodEnd` を流用）。これにより承諾が確定した瞬間から、旧サブスクは Stripe 側の保証で期末に必ず終了し、以後どの後続手順（切替TX・新サブスクの請求成否等）が失敗しても**旧が更新され続けて二重課金する余地が構造的に無くなる**。旧期末到達時に実行する切替TXは**ローカルDB操作（pointer付替え＋状態遷移）のみ**であり、Stripe API呼び出しを含まない（Stripe側は承諾確定時点で既に確定済みのため）。引継が期末前に `FAILED` 確定した場合のみ、旧サブスクの `cancel_at_period_end` を `false` に戻し継続させる（§3.6） |
| trial 中に新 payer が離脱した場合 | `trial_end` 到達前に新サブスクを `cancelImmediately`（新サブスク側にのみ適用。無課金のため即時解約で問題ない）すれば取消可能。このとき旧サブスクの `cancel_at_period_end` は `false` に戻し、旧の継続を回復する |
| PaymentMethod 未設定時の挙動 | trial 終了時に決済手段が無いと `customer.subscription.deleted`（Stripe既定の `missing_payment_method` 終了時動作が `cancel` の場合）または `past_due` に陥る。**そのため ACCEPTED直後（新サブスク作成前）に新 payer の PaymentMethod 有無を検証する（§3.6・P1-6/11 対応・ACCEPTED時点と期末時点の二段検証の1段目）** |
| クーポン/割引・税 | 従来どおり引き継がない（新規指定が必要。本サービスは現状未提供のため対象外、§2.4 に維持） |

### 2.4 proration・クーポン・税の扱い（変更なし部分を維持）

| 論点 | 確定事項 |
|---|---|
| クーポン/割引の引継 | 引き継がない。本サービスは現状クーポン機能未提供のため対象外 |
| 税の引継 | 引き継がない。新サブスク作成時に `automatic_tax` を新規指定する（対応時） |
| trial（本来のプラン上のトライアル） | 本サービスの TEAM/ORG プランはトライアル未提供（実コード確認済み）。§2.3 の `trial_end` は「無課金期間を作る手段」として転用しているものであり、プラン上のトライアル機能とは無関係 |

---

## §3. 冪等性と失敗回復（P0-2〜4・P1-5〜7・P1-11・P2-14 対応）

### 3.1 状態機械（PENDING_HANDOVER 追加・P0-4／R2-P0-1／R2-P1-2 対応）

`billing_payer_handover_requests.status`（要求レベルの状態機械）と `billing_contracts.status`（契約レベルの状態機械）は別物である。新契約は要求レベルが `ACCEPTED` になった時点で `billing_contracts` 行として **`PENDING_HANDOVER`** 状態で先行作成される（pointer は持たない）。

```
[要求レベル: billing_payer_handover_requests.status]
REQUESTED（要求）
   │ 対象スコープの他 ADMIN が承諾 + 新 payer の PaymentMethod 存在確認（§3.6・二段検証の1段目）
   ▼
ACCEPTED（承諾）
   │ 新サブスク作成 API 呼び出し（trial_end=旧current_period_end で作成、§2.3）
   │ 新 billing_contracts 行を PENDING_HANDOVER 状態で作成（pointer は持たない）
   │ ↓ checkout.session.completed 到達で (a)引継確定条件が成立
   │   同時に旧サブスクへ cancel_at_period_end=true を設定（R3-P1-3・旧は以後 Stripe 側の保証で必ず期末終了）
   ▼
SWITCHING（切替中・Stripe側は既に確定済み。以後はローカル切替の完了を待つだけ）
   │ 切替バッチが唯一の切替TX実行者。判定条件は「旧契約の current_period_end に到達したか」のみ（R2-P1-2・invoice.paid は待たない）
   │ 実行前チェック: pending_setup_intent が未解決なら切替せず FAILED へ（§3.6・二段検証の2段目）
   │ 切替TX（ローカルDB操作のみ・Stripe API呼び出し無し）: pointer を旧contractから新contractへ付け替え
   │   + 旧contract を CANCELLED 化 + 新contract を PENDING_HANDOVER→ACTIVE 化
   ▼
COMPLETED（完了） ─┬─ 新規作成/trial中の異常で旧期末前に脱落、または pending_setup_intent 未解決 → FAILED
                    │   （新contractはPENDING_HANDOVERのまま無効化・新trialサブスクをcancelImmediately=無課金のまま取消、
                    │    旧サブスクは cancel_at_period_end=false へ戻し継続・old_cancel_scheduled_atもNULLクリア。旧契約は実質無傷）
                    ├─ ローカル切替TXのみが未了（Stripe側は既に確定済み） → PARTIALLY_COMPLETED（§3.5 再定義・非終端・リトライ対象）
                    └─ 切替前チェックで期末境界越えを検知、またはcancel_at_period_end設定の恒久失敗 → MANUAL_INTERVENTION
                        （§3.6.2新設・非終端・運用者のRESUME待ち。自動でのtrue設定/void/refundは行わない）

[契約レベル: billing_contracts.status]（既存5値 + 新設1値。§4.1でCHECK/ enum を6値へ拡張）
PENDING_HANDOVER（新設）: 引継の新契約が作成された直後〜切替TX実行前。entitlements は「新契約の PENDING_HANDOVER 状態でも pointer 未設定のため未発行」ではなく、
                          entitlement の実体は旧契約 pointer が旧期末まで担保し続ける（R2-P1-2）。
  │ 入口: ACCEPTED（要求レベル）遷移時、新規に billing_contracts 行を PENDING_HANDOVER で作成
  │ 出口: 切替TX成功 → ACTIVE（pointer付替え完了と同時）
  │ 出口: 新規作成/trial失敗/pending_setup_intent未解決 → CANCELLED（無効化。旧契約のpointerは無傷のため利用者影響なし）
既存5値（PENDING → ACTIVE → PAST_DUE ⇄ ACTIVE、ACTIVE →（期末解約予約）→ EXPIRED、いずれも CANCELLED へ遷移可）は変更なし。
```

**pointer 一意制約との整合（P0-4 根治）**: `active_contract_pointers.uk_acp_slot`（スロット単位 UNIQUE）は「1スロットに1 pointer」を保証する制約であり、**新旧2契約が同時に pointer を持とうとすると衝突する**。そこで新契約は `PENDING_HANDOVER` の間 pointer を作らず、切替TXで「旧pointerを物理DELETE→新pointerをINSERT」を同一トランザクション内で行う（§3.7 の `hardDeleteBySlot` 修正と合わせ、旧pointerの削除条件を `contract_id` 一致に絞ることで、切替TXの外側で発生する旧webhookが誤って新pointerを消さないようにする）。

**entitlement 空白ゼロ・二重付与ゼロの根拠（R2-P1-2 裁定・維持）**: 切替TXの発火条件を「旧契約の `current_period_end` に到達したか」という**アプリ側で判定可能な時刻条件**に一本化し、Stripe側の非同期webhook（`invoice.paid`）の到達を待たない。旧契約の pointer は旧期末の瞬間まで entitlement を担保し続け、切替TXは旧pointerの削除と新pointerの作成を**同一トランザクション**で行うため、entitlement が「どちらの契約からも発行されていない空白時間」は発生しない（旧削除と新作成はDBトランザクションの原子性で同時に確定する）。同じ理由で「旧・新両方が同時にentitlementを持つ二重付与」も発生しない（pointerは常にどちらか一方の契約にのみ存在する）。**さらにR3-P1-3裁定により切替TXはStripe API呼び出しを含まないローカルDB操作のみになったため、entitlement空白ゼロの保証にStripe側の応答可用性が絡む余地も無くなった**（DBトランザクションの原子性のみに依存する、より単純な保証）。

新サブスクの**初回請求（trial終了時の請求）が失敗した場合**は、新契約は既に切替TXで `ACTIVE` に遷移済み（pointer切替は旧期末到達のみが条件のため、請求成功可否とは独立して先に完了している）であり、この失敗は**通常の継続課金の支払失敗と同型**として扱う。すなわち `ACTIVE → PAST_DUE`（`invoice.payment_failed`）という既存の状態遷移にそのまま乗せ、督促・停止フローも通常契約と同一にする。引継固有の特別な救済フローは設けない（AC-27/28、§3.6にも遷移表を追加）。

### 3.2 Idempotency-Key の限界と一次防衛の移設（P0-2 根治・R3-P0 で List Subscriptions 方式へ変更）

Stripe 公式ドキュメント（Idempotent requests）で確認した仕様: Idempotency-Key は**最短24時間で失効**し、失効後の再利用は新規リクエストとして扱われる。**24時間を跨ぐ再試行では Idempotency-Key だけでは二重作成を防げない。**

→ **一次防衛を DB 側に移す。** Idempotency-Key は「同一プロセス内・短時間の再送」に対する補助防御として残すが、正の防衛線は以下:

1. `billing_payer_handover_requests.psp_new_subscription_ref` を Stripe 新サブスク作成 API が**成功した後に** DB へ永続化する（R5-P2と同様の理由でStripe呼び出しとDB書き込みは別操作であり原子性は成立しない。この間の不整合は§3.2の DB→List Subscriptions照会の回復手順自体がカバーする設計になっている）
2. 新サブスク作成の全呼び出し（初回・リトライとも）で `metadata.handoverRequestId = {handoverRequestId}` を**必ず**指定する（回復経路で照合キーとして使うため）
3. 新サブスク作成を試みる前に、必ず次の順で確認する:
   1. DB の `psp_new_subscription_ref` が既に埋まっているか確認 → 埋まっていれば作成をスキップしてそのサブスクを使う
   2. 埋まっていなければ **Stripe List Subscriptions API を `customer={新Customer}&status=all` で照会**し、**`has_more` に従い全ページを走査**（SDKのauto-paginationヘルパー、例: Java SDKの`autoPagingIterable()`相当を使用し、手動でのページ送り漏れを起こさない実装を必須とする）した上で、返却された全件を `metadata.handoverRequestId == {id}` でクライアント側フィルタする → ヒットすれば「Stripe には作成済みだが DB 反映前に落ちた」ケースと判定し、そのサブスク ID を DB に書き戻してから続行（二重作成回避）。**「1ページ目に無ければ未作成」と読める運用は誤り**であり、対象のCustomerが多数のサブスクリプションを持つ場合、目的のサブスクが2ページ目以降に存在する可能性を排除できない限り「見つからない」と判定してはならない
   3. 「DB に `psp_new_subscription_ref` が無い」かつ「List 照会を全ページ走査した上でヒットが無い」の**両方**を確認して初めて新規作成 API を呼ぶ（このときのみ Idempotency-Key `billing-handover-create-{handoverRequestId}` を付与）

**回復経路の照合を Search API から List Subscriptions API へ変更した根拠（R3-P0・公式ドキュメントで確認済み）**: Stripe公式ドキュメント（Search: `docs.stripe.com/search`）は、Search APIの鮮度遅延について明記した直後に「read-after-write（書き込み直後の読み取り）フローが即時にデータを必要とする場合は、[請求書の一覧表示](https://docs.stripe.com/api/invoices/list)などの各種**リストアップAPI**を使用してください。これらのAPIは、上記のデータ利用の遅延の影響を受けません」と明記している。List Subscriptions API（`docs.stripe.com/api/subscriptions/list`）は Search のような検索インデックスを経由せず、Stripe側のプライマリデータを直接返す通常のリソース一覧エンドポイントであり、**検索インデックスの反映遅延という概念自体が存在しない**（read-after-write整合）。また `customer` パラメータで対象Customerに絞り込め、`status=all` を指定すれば `trialing`/`canceled` を含む全ステータスが対象になるため、直前の新規作成呼び出しが成功していれば即座に一覧へ現れる。

**Search APIは補助手段としても採用しない（格下げではなく不使用に変更・R3-P0）**: 上記のとおり List で read-after-write 整合の照合が可能なため、鮮度遅延という余分なリスクを持つ Search API を一次防衛はもとより補助手段としても使う理由が無い。§3.2の照合は常に List Subscriptions を使う。

**ページング（全ページ走査の必須化・R4-P1-1）**: List Subscriptions は1リクエストあたり最大100件（既定10件）しか返さないページング型APIであり、対象Customerが多数のサブスクリプションを持つ場合は`has_more=true`のまま複数ページにまたがる。**回復経路の照合実装は必ずauto-paginationヘルパー（Stripe SDKが提供する、`has_more`を見て`starting_after`カーソルを自動的に送りながら全件を走査するイテレータ）を使用し、1ページ目のみを見て「対象が無い」と判定する実装を禁止する。** 手動でページ送りを書く場合も同様に`has_more`を必ずチェックし尽くすこと。

4. **現行 `StripePaymentProvider`/`StripeBillingPaymentGateway` は Idempotency-Key 未対応（実コード確認: `createBillingSubscriptionCheckoutSession` 等の呼び出しにキー引数が無い）。実装 PR で Gateway 層に Idempotency-Key 引数と `metadata` 引数、および List Subscriptions 呼び出し用メソッドを追加する拡張が必須。本設計書はその拡張を PR-2 のスコープに含める（§8）**

### 3.3 相関 ID の永続化

`billing_payer_handover_requests` に `old_contract_id`／`new_contract_id`／`psp_new_subscription_ref` を持つ（§4.2）。新契約の `billing_contracts` 行自体も `PENDING_HANDOVER` の間から作成しておき、`handover_request_id` 列（新設、§4.2）で相互参照する。

### 3.4 Idempotency-Key の単位（P1-7 対応・既存キーとの棲み分け明記）

| 操作 | Idempotency-Key | 既存キーとの関係 |
|---|---|---|
| 新サブスク作成 | `billing-handover-create-{handoverRequestId}` | 新設。§3.2 のとおり一次防衛は DB+List Subscriptions、本キーは短時間再送の補助 |
| 承諾確定時の旧サブスク `cancel_at_period_end=true` 設定（R3-P1-3で新設・旧の「解約」ではなく承諾確定と同時に行う） | `billing-handover-schedule-cancel-{handoverRequestId}` | 新設。**通常の（引継を伴わない）解約が使う既存キー `"billing-cancel-" + subscriptionRef`（`StripeBillingPaymentGateway#cancelAtPeriodEnd` 実装）とは別名前空間にする**ことで、同一 `subscriptionRef` に対し「通常解約」と「引継による予約解約」が同時に走っても Idempotency-Key の衝突（パラメータ不一致エラー）を起こさない |
| FAILED確定時の旧サブスク `cancel_at_period_end=false` への差し戻し（R3-P1-3で新設） | `billing-handover-revert-cancel-{handoverRequestId}` | 新設。上記と対の操作のため別キーにする |
| 新trialサブスクの `cancelImmediately`（新payer離脱・pending_setup_intent未解決等でFAILED確定時） | `billing-handover-cancel-new-{handoverRequestId}` | 新設。新サブスク側のみに適用（旧サブスクの操作とは別対象・別キー） |

**旧サブスクへの `cancelImmediately` は本方式では使わない（R3-P1-3・§2.3/§3.1参照）。** 切替TX自体はローカルDB操作のみのためIdempotency-Keyを持たない（Stripe API呼び出しが無いため）。

### 3.5 補償（`PARTIALLY_COMPLETED` の意味を再定義・R3-P1-3で終端/非終端の矛盾を解消）

**`PARTIALLY_COMPLETED` の意味（再定義）**: R3-P1-3裁定により、Stripe側の状態確定（旧サブスクの`cancel_at_period_end=true`設定）は**承諾確定（ACCEPTED→checkout.session.completed）の時点で既に完了している**。したがって切替TX実行時点でStripe側の操作が失敗するという事態そのものが起こらない（切替TXはローカルDB操作のみのため）。`PARTIALLY_COMPLETED` が意味するのは**「Stripe側は確定済み（旧は期末で終わることが保証されている）だが、ローカルの切替TX（pointer付替え＋状態遷移）自体がDB書き込み失敗等で未完了」**という状態のみになった。

- これにより §4.2 の状態遷移表にあった旧来の「終端/終端外の記述矛盾」（Stripe操作の失敗と、ローカル操作の失敗を同じ状態名で扱っていた点）を解消する: `PARTIALLY_COMPLETED` は **非終端**（`open_old_contract_id` 生成列の対象に含める。§4.2で明記）とし、ローカル切替TXが成功するまで夜次バッチでリトライし続け、成功次第 `COMPLETED` へ遷移する
- リトライは「pointer付替え＋状態遷移」という冪等な操作（同じ`old_contract_id`/`new_contract_id`に対して再実行しても結果が同じ）のため、Stripe側との整合を都度確認する必要が無く単純化される
- 利用者影響: 新契約は「旧期末到達済み」の時点で本来 `ACTIVE` になるはずだが `PARTIALLY_COMPLETED` の間は `PENDING_HANDOVER` のまま pointer が旧のまま残っている可能性があるため、**entitlementの実体は旧pointerが担保し続ける**（旧サブスク自体は既にStripe側でcanceled済みでも、DB上の旧pointerがまだ新へ付け替わっていないだけなので、アプリ側のentitlement判定はDBの`active_contract_pointers`を見る限り連続している。§3.1の空白ゼロ原則と整合）

### 3.6 成功条件の三段階分離・PaymentMethod/SCA事前検証・PAST_DUE分岐（P1-5・P1-6・P1-11・R2-P1-3・R2-P1-4・R2-P1-5 対応）

**R2-P1-3 裁定: 「成功」を意味する条件を目的別に3段階へ分離し、単一の「成功条件」として扱わない。**

| 段階 | 条件 | 意味 | このタイミングで行うこと |
|---|---|---|---|
| (a) 引継確定条件 | `checkout.session.completed`（新サブスク作成成功・ステータスは `trialing`） | 「新 payer が新サブスクの作成に同意し、Stripeへの登録が完了した」ことの確定。**この時点ではまだ課金は一切発生していない**（trial中のため） | `billing_payer_handover_requests` を `SWITCHING` へ遷移させ、新 `billing_contracts` 行を `PENDING_HANDOVER` で確定させる**と同時に、旧サブスクへ `cancel_at_period_end=true` を設定する**（R3-P1-3・§2.3/§3.1参照。これにより旧はStripe側の保証で以後必ず期末終了する） |
| (b) pointer切替条件 | 旧契約の `current_period_end` 到達（R2-P1-2裁定・時刻ベースのアプリ側判定） | 「entitlementの担保元をどちらの契約にするか」の切替タイミング。**唯一の実行者は切替バッチ**（旧期末到達を監視する夜次/定期バッチ）であり、他の経路（webhook等）はこの判定・実行を代行しない | 切替バッチが実行前チェック（`pending_setup_intent`未解決なら中止しFAILEDへ、§3.6下記）を経て、切替TX（ローカルDB操作のみ）を実行。**旧サブスクへのStripe API呼び出しは行わない**（(a)の時点で既に`cancel_at_period_end=true`が設定済みのため） |
| (c) 事後健全性確認 | `invoice.paid`（新サブスクのtrial終了時初回請求） | 「実際に課金が成功したか」の確認。**(a)(b)いずれの判断にも使わない** | 失敗時は§3.1のとおり通常の`PAST_DUE`遷移。成功時は監視上のヘルスチェックとして記録するのみ |

**誤読防止の注記（R2-P1-3）**: Subscription作成レスポンスの `status=trialing` は「新payerが登録された」ことの確認であり、**「新payerへの請求が成功した」ことを意味しない**（trial中は請求自体が発生しないため、成功も失敗もまだ判定不能な状態）。実装・監視ダッシュボードの双方で `trialing` を「課金成功」と表示・ログしないこと（レビュー観点として明記）。

### 3.6.1 `cancel_at_period_end` 設定失敗に備えた二重防衛（R4-P1-2 対応）

R3-P1-3裁定（§2.3・§3.1）は「承諾確定と同時に旧サブスクへ `cancel_at_period_end=true` を設定する」ことで二重課金の余地を構造的に無くす方式だが、**その設定API呼び出し自体が失敗する、またはアプリが呼び出したかどうか確認せずに終わる**ケースは依然として起こり得る（プロセスクラッシュ・ネットワーク断等）。これを放置すると、承諾は`ACCEPTED`のまま先へ進んでいるのに旧サブスクは通常課金を続けてしまう恐れがある。**以下の二重防衛で担保する:**

**(a) 夜次照合バッチによる検出・再設定（第一防衛）**

1. 旧サブスクへの `cancel_at_period_end=true` 設定APIの呼び出しが**成功した後に** `billing_payer_handover_requests.old_cancel_scheduled_at` へその成功時刻を永続化する（§4.2でDDL新設）。**R5-P2: 「Stripe API呼び出し」と「DB永続化」は同一トランザクションではなく別操作であり原子性は成立しない**（Stripe側は外部システムのためDBトランザクションに巻き込めない）。この間の不整合（Stripeでは成功したがDB書き込み前にクラッシュ等）は本項(a)の夜次照合バッチが検出・補完する前提で設計する
2. 夜次照合バッチが、`status='ACCEPTED'`（またはそれ以降の非終端状態）かつ `old_cancel_scheduled_at IS NULL` の行を検出する（「承諾は確定しているのに旧サブスクへの予約が確認できていない」行）
3. 該当行について、**Stripeから実際の旧サブスクオブジェクトを取得し `cancel_at_period_end` の実値を確認**する（DBの記録を信用せずStripe側の実物と突合する）。既にStripe側で`true`になっていれば（設定APIは成功していたがDB書き込みだけ失敗していたケース）、`old_cancel_scheduled_at` をその時点の時刻で埋めて整合を回復する（Stripeへの再設定は不要・冪等）
4. Stripe側でも`false`のままであれば、設定APIを再実行する（`cancelAtPeriodEnd`はStripe側で冪等に扱える操作のため、既に`true`であっても再度`true`を設定して害はない）。成功したら`old_cancel_scheduled_at`を埋める

**R5-P2: `old_cancel_scheduled_at`のNULLクリア（差し戻し時）**: handoverが期末前に`FAILED`確定し旧サブスクの`cancel_at_period_end`を`false`へ差し戻す（§2.3・§3.1・§3.6の各FAILED経路共通）際は、**`old_cancel_scheduled_at`も同時にNULLへクリアする**。クリアしないまま残すと、この行が再度`ACCEPTED`相当の状態に戻った場合（同一契約への再要求等）に「既に予約済み」と誤認して夜次照合バッチが検出をスキップしてしまうため、差し戻し操作は必ず「Stripe側`cancel_at_period_end=false`設定」と「DB側`old_cancel_scheduled_at`のNULLクリア」を対で行う（このペアもStripe呼び出しとDB書き込みが別操作のため原子性は成立せず、不整合が起きれば同じ夜次照合バッチが検出・補正する）。

**(b) 切替バッチの実行前チェック（第二防衛）**

旧期末到達時に動く切替バッチ（唯一の切替TX実行者、§3.1・§3.6上表(b)）は、ローカルDB操作のみを行う前提だったが、**切替の直前に一度だけ、Stripeから実際の旧サブスクオブジェクトを取得し `cancel_at_period_end=true` になっていることを確認する**（(a)の夜次照合が何らかの理由で間に合わなかった場合の防衛線）。

- 確認できれば通常どおりローカル切替TXへ進む
- **`false`のままであった場合（R5-P1-1 裁定・「その場でtrue設定して続行」は撤回）**: まず旧サブスクの `current_period_start`（Stripe実物）を確認し、**旧サブスクの請求サイクルが既に更新（新しい期間へロールオーバー）されているかどうかを判定する**。これは「切替バッチが旧期末到達後まで遅延して走り、その間に`cancel_at_period_end`が未設定のまま実際の請求サイクルが更新されてしまった」という**期末境界越え**が発生していないかの確認である
  - **判定条件**: 旧サブスクの `current_period_start` が、DB上で本来の旧期末（`old_contract.current_period_end`）以降になっていれば、期末境界を越えて更新が発生済みと判定する
  - **既定の分岐（R5-P1-1裁定: (b)を既定とする）**: 期末境界越えを検知した場合、**その場での`cancel_at_period_end`設定や自動での金銭操作（invoice の void/refund）は行わない**。切替TXも実行せず、handoverを**`MANUAL_INTERVENTION`**（§3.6.2で正式定義）へ倒し、ADMIN・運用へ即時アラートを飛ばす。自動リトライはしない
  - **期末境界越えが検知されなかった場合（`cancel_at_period_end`が単に未設定なだけで、まだ旧期間内）**: **その場で設定APIを呼んでから**切替TXへ進む（旧サブスクがまだ新しい期間に入っていないため、直ちに`true`を設定すれば当初の想定どおり期末で終了する。二重課金は発生しない）
  - **運用手順書（手動介入の標準手順・スコープ限定）**: `MANUAL_INTERVENTION`で人手対応する際の選択肢の一つとして、(a')旧サブスクを`cancelImmediately`した上で、更新済み期間分の請求書について、未払い（`open`）であればStripe公式の Void an invoice API（`docs.stripe.com/api/invoices/void`。「finalizeされたinvoiceをvoidにする」操作で、確定済みだが未払いのinvoiceに使える）で取消し、既に支払い済みであれば Stripe公式の Create a refund API（`docs.stripe.com/api/refunds/create`。charge/payment_intentを指定して返金する）で返金する、という手順を運用者向けに記載する。**ただし、この`void`/`refund`操作をアプリケーションが自動実行することは本設計のスコープ外と明記する**（金銭を動かす操作を自動化する場合、返金理由・金額の確定・監査証跡・二重返金防止など別途の設計論点が生じるため、本設計は「検知してアラートを出し人手判断に委ねる」ところまでを扱う）

**遷移表（PAST_DUE 旧契約・過去期末・3DS・PaymentMethod 無し）**:

| ケース | 判定タイミング | 挙動 |
|---|---|---|
| 旧契約が `PAST_DUE` または `current_period_end` が既に過去（R2-P1-4 裁定） | **引継要求の作成時点（REQUESTED発行前）で検証** | `trial_end` 方式は「過去のタイムスタンプをtrial_endに指定する」ことになり Stripe API 上不正（trial_endは未来日時である必要がある）。よって**この場合は引継要求自体を拒否**する。ADMINには「先に旧契約の支払回収（督促の完了を待つ）または解約を完了してから引継を申請してください」という案内を返す（AC-29） |
| 新 payer に有効な PaymentMethod が無い（**二段検証の1段目**） | ACCEPTED直後・新サブスク作成前に必須検証（P1-6/11） | 検証失敗なら承諾自体を差し戻し、状態は `ACCEPTED` に留めず `REQUIRES_PAYMENT_METHOD`（新設の中間状態）へ落とす。旧契約は無傷のまま（まだ`cancel_at_period_end`も設定していない）。新 payer にカード登録を促す通知を送る |
| 新サブスク作成後、`pending_setup_intent` が非NULL（R2-P1-5／R3-P1-2・SCA/3DS対応・**認可者と二段検証の明確化**） | **1段目**: 新サブスク作成直後・(a)引継確定条件成立直後の即時チェック。**2段目（最終確定）**: (b)切替バッチが旧期末到達時に行う実行前チェック（唯一の切替TX実行者・§3.6上表） | Stripe公式ドキュメント（Subscriptionオブジェクト`pending_setup_intent`・SCA移行ガイド Scenario 2）を確認: `pending_setup_intent` は「即時課金なしでサブスクを作成した場合に、off-session課金を成功させるための事前認証収集用SetupIntent」。**Checkout Session（subscriptionモード）は公式に「SCA要件を自動的に処理する」と明記されており、通常はCheckoutフロー完了時点（=(a)引継確定条件成立時点）で3DS認証も完了している**ため`pending_setup_intent`はNULLになるのが期待値。1段目チェックで非NULLの場合（Checkoutを経由しない直接API経路等）は新payerに追加認証（SetupIntent confirm）の完了を促す通知を送るのみで、この時点ではまだ状態遷移させない（旧の`cancel_at_period_end=true`は既に設定済みのため引継自体は進行中扱い）。**2段目（切替バッチによる最終確定）**: 旧期末到達時点で改めて`pending_setup_intent`を確認し、依然として未解決（=認証未完了）であれば**切替TXを実行せず、handoverを`FAILED`に確定する**。このとき新trialサブスクを`cancelImmediately`（無課金のまま取消）し、旧サブスクの`cancel_at_period_end`を`false`へ差し戻して継続させる（§3.1状態機械参照）。**2段目チェックで`pending_setup_intent`がNULL（=認証完了済み）であれば通常どおり切替TXへ進む**（この場合の初回請求失敗は§3.1のとおり通常の`PAST_DUE`遷移で対処し特別扱いしない） |
| 新サブスクが `trialing` のまま Stripe 側で `canceled`（何らかの理由） | webhook `customer.subscription.deleted` | 状態を `FAILED` に落とし、`old_contract_id` は無傷のまま。旧サブスクの`cancel_at_period_end`を`false`へ差し戻す。ADMIN 通知 |
| 切替バッチの実行前チェックで`cancel_at_period_end=false`かつ**期末境界越え**を検知（R5-P1-1裁定） | (b)切替バッチ・旧期末到達時 | **切替TXを実行せず、handoverを`MANUAL_INTERVENTION`へ倒す**（§3.6.2で正式定義）。その場での自動`true`設定・自動void/refundは行わない。ADMIN・運用へ即時アラート |

### 3.6.2 `MANUAL_INTERVENTION` 状態の正式定義（R5-P1-2 対応）

`MANUAL_INTERVENTION`は、機械的なリトライでは安全に解消できない異常（Stripe側の実データとローカルの想定が食い違い、自動での金銭操作を伴わずには収拾できない状態）を検知した際に handover が遷移する状態である。§3.6.1(b)の「切替前チェックで`cancel_at_period_end=false`かつ期末境界越えを検知」した場合と、§3.6.1(b)の「その場での`cancel_at_period_end`設定APIが失敗した場合」の2経路がこの状態へ集約される。

**状態一覧への追加**: `billing_payer_handover_requests.status` の許容値は本改訂で8値→9値になる: `REQUESTED`/`ACCEPTED`/`REQUIRES_PAYMENT_METHOD`/`SWITCHING`/`PARTIALLY_COMPLETED`/`MANUAL_INTERVENTION`（新設）/`COMPLETED`/`FAILED`/`EXPIRED`（§4.2でDDLのCHECK/コメントを更新）。

> **PR-4 追記（V206）**: さらに `FAILING_CLEANUP` を加えて **10値** になった。失敗確定は決まったが Stripe の後始末（新 trial サブスクの即時取消・旧サブスクの `cancel_at_period_end` 差し戻し）が未了であることを表す**非終端**状態である。**非終端3値ではなく4値**（`PARTIALLY_COMPLETED`・`MANUAL_INTERVENTION`・`FAILING_CLEANUP` と進行中の各状態）が `open_old_contract_id` 生成列で値を保持する。出口は「後始末の成功を確認したうえでの `FAILED`」のみ。

**遷移表**:

| 状態 | 意味 | 遷移元（入口） | 遷移先（出口） |
|---|---|---|---|
| `MANUAL_INTERVENTION` | 自動処理では安全に解消できない異常を検知し、運用者の手動対応待ちとなっている状態 | (b)切替バッチの実行前チェックで**期末境界越えを検知**（`cancel_at_period_end`未設定のまま旧サブスクの請求サイクルが更新済みと判明）／(b)期末境界越えではないが`cancel_at_period_end`のその場設定API自体が失敗（恒久的な失敗と判断された場合）／その他、切替TXの前提が崩れていると切替バッチが判断したケース | 運用者が原因（Stripe側の実データ確認・必要なら§3.6.1(b)運用手順書に沿った手動でのvoid/refund等）を解消した後、**`RESUME`操作で明示的に遷移先を選ぶ**: (i) 切替再試行が可能と判断すれば `SWITCHING` へ戻し切替バッチの次回実行で再評価させる（`RESUME→切替再試行`）、(ii) 引継自体を諦めると判断すれば `FAILED` へ確定し旧サブスクの`cancel_at_period_end`差し戻し（§3.6.1のFAILEDフロー）を伴わせる（`RESUME→FAILED確定`。旧サブスクが既に更新済みの場合はこの差し戻しが不適切なこともあるため、運用者の判断に委ねる） |

**DDL許容値・生成列（open判定）での扱い**: `MANUAL_INTERVENTION`は**非終端**として扱う。§4.2の生成列`open_old_contract_id`のCASE式（`COMPLETED`/`FAILED`/`EXPIRED`のときのみNULL）の対象外のままとし、`open_old_contract_id`は値を保持し続ける（＝この契約への新たな引継要求は`MANUAL_INTERVENTION`解消までブロックされる。人手対応中に別の引継要求が二重に走らないようにするため意図的な設計）。

**入口（発生条件）**:
1. 切替前チェックで`cancel_at_period_end`が`false`かつ**期末境界越え**（旧サブスクの`current_period_start`が本来の旧期末以降）を検知
2. 期末境界越えではないが、切替バッチのその場`cancel_at_period_end`設定APIが失敗し、リトライしても解消しない（恒久的失敗と判断される。具体的なリトライ回数・判断基準は実装フェーズで定める）
3. `cancel_at_period_end`の恒久的な設定失敗（(a)夜次照合バッチが繰り返し検出しても解消できないケース）

**出口（解消方法）**: 運用者が原因を確認・解消した後、手動で`RESUME`操作を行う。`RESUME`は上記のとおり2種類の遷移先（`SWITCHING`への復帰／`FAILED`への確定）のいずれかを運用者が明示的に選ぶAPI/管理画面操作として実装する（実装詳細はPR-4スコープ）。

**アラート先**: ADMIN（当該スコープの管理者。引継の進行状況を把握しているため）と運用チーム（Stripe側の実データ確認・`RESUME`操作の権限を持つため）の双方に即時通知する。通知チャネルの具体（Slack/メール等）は実装フェーズで既存の運用アラート基盤に合わせて決定する。

### 3.7 `hardDeleteBySlot` の contract_id 一致化（P0-3 根治・実装項目）

現行 [`BillingContractService`](../../backend/src/main/java/com/mannschaft/app/billing/BillingContractService.java) は `activeContractPointerRepository.hardDeleteBySlot(scopeKind, scopeId, contractKind, slotAddonKey)` を**スロット単位**（scope+kind+slot）で呼んでいる。旧サブスクの webhook 処理（例: 旧の `customer.subscription.deleted` を受けて旧契約を CANCELLED にする処理）がこの `hardDeleteBySlot` を呼ぶと、**切替TXで既に新契約へ付け替わった pointer まで同一スロット条件でヒットして消えてしまう**（新旧どちらの契約か区別しないため）。

**実装項目（PR-2 スコープに追加）**: `ActiveContractPointerRepository` に `hardDeleteBySlotAndContractId(scopeKind, scopeId, contractKind, slotAddonKey, contractId)` を新設し、削除条件に `contract_id` 一致を追加する。旧契約由来の webhook 処理はこの新メソッドを使い、「自分（旧契約）が今も pointer の持ち主である場合のみ削除する」ようにする。既に切替TXで pointer が新契約に付け替わっていれば `contract_id` 不一致のため削除されない（0件更新、副作用なし）。

**AC-14 はこの根治を検証する内容に書き換える**（§8）。

---

## §4. DDL 最小案（P2-13・P2-14 反映）

### 4.1 `billing_contracts` への payer 明示列追加

現状 `createdBy`（`created_by`）を purge 判定に流用しているのが 1.2 の根本原因。**`created_by` の意味は変えない**（作成操作者の監査記録として維持）。

```sql
ALTER TABLE billing_contracts
    ADD COLUMN payer_user_id BIGINT NULL COMMENT '現在この契約の実質決済者（Stripe Customer 紐付け先）。作成時は created_by と同値で初期化し、引継後に更新される' AFTER created_by,
    ADD COLUMN handover_request_id BINARY(16) NULL COMMENT 'PENDING_HANDOVER 中に自分を作った billing_payer_handover_requests.id（新契約行のみ非NULL）' AFTER payer_user_id;

UPDATE billing_contracts SET payer_user_id = created_by WHERE payer_user_id IS NULL;

CREATE INDEX idx_billing_contracts_payer ON billing_contracts (payer_user_id, scope_kind, status);

-- R2-P0-1: status CHECK を 5 値 → 6 値へ拡張（PENDING_HANDOVER 追加）。
-- 既存 CHECK 制約名はスキーマ全域一意のため、DROP → 同名 ADD で置換する（V151 と同じ作法）。
ALTER TABLE billing_contracts
    DROP CHECK chk_bc_status;
ALTER TABLE billing_contracts
    ADD CONSTRAINT chk_bc_status CHECK (status IN ('PENDING','ACTIVE','PAST_DUE','CANCELLED','EXPIRED','PENDING_HANDOVER'));
```

**実装項目（PR-1スコープに追加・R2-P0-1）**: [`ContractStatus`](../../backend/src/main/java/com/mannschaft/app/billing/ContractStatus.java) enum（現行5値: `PENDING`/`ACTIVE`/`PAST_DUE`/`CANCELLED`/`EXPIRED`）に **`PENDING_HANDOVER`** を追加する。Javadocの状態機械コメントにも「引継の新契約が `PENDING_HANDOVER` として作成され、切替TXで `ACTIVE` へ、または新規作成/trial失敗で `CANCELLED` へ遷移する」旨を追記する（§3.1の遷移表と対応させる）。

**TEAM/ORG の `payer_user_id` NOT NULL 制約について（P2-13）**: MySQL 8.0 の `CHECK` 制約は非決定的な参照（他列の値に応じた条件分岐）自体は表現できるが、「`scope_kind IN ('TEAM','ORG')` のとき `payer_user_id IS NOT NULL`」という条件付き必須は `CHECK (scope_kind NOT IN ('TEAM','ORG') OR payer_user_id IS NOT NULL)` の形で MySQL 8.0.16+ でも表現自体は可能。ただし本リポジトリの既存規約はテーブル定義への複雑な条件付き CHECK を多用していない（`domain_db_design_principles.md` に前例なし）ため、**CHECK 制約は入れず、アプリ層検証（`BillingContractService` の契約作成時バリデーション）を一次防衛とし、定期監視クエリ（`scope_kind IN ('TEAM','ORG') AND payer_user_id IS NULL` の0件監視・既存の監視基盤に相乗り）を二次防衛とする**。USER スコープは `payer_user_id` を省略可能（契約者本人が自明の payer のため）。

### 4.2 引継要求テーブル新設（生成列+UNIQUE・状態遷移表・P1-8/P2-14 反映）

```sql
CREATE TABLE billing_payer_handover_requests (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    old_contract_id BINARY(16) NOT NULL COMMENT '引継元 billing_contracts.id',
    new_contract_id BINARY(16) NULL COMMENT '引継先 billing_contracts.id（ACCEPTED 以降で確定・PENDING_HANDOVER 状態で作成）',
    scope_kind VARCHAR(8) NOT NULL COMMENT 'TEAM または ORG のみ許容（USER は引継対象外・アプリ層で拒否）',
    scope_id BIGINT NOT NULL,
    old_payer_user_id BIGINT NOT NULL COMMENT '退会予定・引継元の payer',
    new_payer_user_id BIGINT NULL COMMENT '承諾した引継先 ADMIN（ACCEPTED 以降で確定）',
    status VARCHAR(24) NOT NULL COMMENT 'REQUESTED/ACCEPTED/REQUIRES_PAYMENT_METHOD/SWITCHING/PARTIALLY_COMPLETED/MANUAL_INTERVENTION/FAILING_CLEANUP/COMPLETED/FAILED/EXPIRED（R5-P1-2でMANUAL_INTERVENTION追加、PR-4のV206でFAILING_CLEANUP追加・10値）',
    -- 生成列: 終端状態（COMPLETED/FAILED/EXPIRED）以外のときだけ old_contract_id を値として持つ。
    -- PARTIALLY_COMPLETED は R3-P1-3 裁定で「非終端・リトライ対象」、MANUAL_INTERVENTION は R5-P1-2 裁定で
    -- 「非終端・運用者のRESUME待ち」とそれぞれ再定義したため、いずれもCASE式の対象外
    -- （＝値を保持し続ける）のままで正しい（§3.5・§3.6.2参照）。
    -- 終端状態では NULL になるため UNIQUE 制約に抵触せず、同一契約への再要求（前回終了後）を許可する。
    open_old_contract_id BINARY(16) GENERATED ALWAYS AS (
        CASE WHEN status IN ('COMPLETED', 'FAILED', 'EXPIRED') THEN NULL ELSE old_contract_id END
    ) STORED COMMENT '村の現役所属重複防止と同型: 進行中(非終端)の要求のみ値を持つ生成列',
    requested_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL COMMENT '既定 requested_at + 14日。期限内未引継は期末解約へフォールバック（§5.4）',
    accepted_at DATETIME NULL,
    completed_at DATETIME NULL,
    psp_new_subscription_ref VARCHAR(64) NULL COMMENT 'P0-2: 新サブスク作成成功時点で永続化する一次防衛の要',
    old_cancel_scheduled_at DATETIME NULL COMMENT 'R4-P1-2: 旧サブスクへの cancel_at_period_end=true 設定 API が成功した時点で永続化。NULLのままACCEPTED以降に残る行は設定が未完了/未確認であることを示し、夜次照合バッチの検出対象になる',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bphr_open_old_contract (open_old_contract_id) COMMENT '生成列+UNIQUE。同一契約に対する進行中の引継要求は同時に1件のみ（村の現役所属重複防止と同型構図）',
    INDEX idx_bphr_scope (scope_kind, scope_id, status),
    INDEX idx_bphr_expires (status, expires_at),
    INDEX idx_bphr_new_contract (new_contract_id)
) COMMENT 'billing_contracts の payer（請求担当）引継要求。UuidV7Entity 継承・自ドメイン内完結（クロスドメイン FK 無し）';
```

- クロスドメイン FK 禁止方針（`domain_db_design_principles.md` 原則1）に従い、`old_payer_user_id`/`new_payer_user_id` は auth ドメインへの直接 FK を張らずインデックスのみ
- **複数 ADMIN の同時承諾直列化**は、この UNIQUE 制約だけでは「承諾」という状態遷移そのものの直列化まではカバーしない（UNIQUE は「進行中の要求が1件」を保証するが、REQUESTED→ACCEPTED の competing update は防がない）ため、アプリ層で `SELECT ... FOR UPDATE` により対象行をロックしてから承諾処理を行う（§5.6・AC-11 は維持）

**status 許容値と遷移表（P2-14）**:

| 状態 | 意味 | 遷移元 | 遷移先 |
|---|---|---|---|
| `REQUESTED` | 通知済み・未承諾 | （初期状態） | `ACCEPTED` / `EXPIRED` / `FAILED`（退会取消時） |
| `ACCEPTED` | 承諾済み・PaymentMethod検証前 | `REQUESTED` | `REQUIRES_PAYMENT_METHOD` / `SWITCHING` |
| `REQUIRES_PAYMENT_METHOD` | PaymentMethod未登録で差し戻し中 | `ACCEPTED` | `ACCEPTED`（登録後再検証）/ `EXPIRED` |
| `SWITCHING` | 旧期末到達待ち・旧サブスクは`cancel_at_period_end=true`で確定済み | `ACCEPTED` | `COMPLETED`（切替TX一発成功時） / `PARTIALLY_COMPLETED`（切替TXのDB書き込みのみ失敗時） / `FAILED`（新規作成/trial中の異常・pending_setup_intent未解決の2段目確定時） / `MANUAL_INTERVENTION`（期末境界越え検知・その場設定API恒久失敗時。R5-P1-1/2新設） |
| `PARTIALLY_COMPLETED` | **非終端**。Stripe側は確定済み（旧は期末で終わることが保証）・ローカルの切替TX（pointer付替え＋状態遷移）のみ未了・夜次バッチのリトライ対象（§3.5再定義） | `SWITCHING` | `COMPLETED`（リトライ成功時） |
| `MANUAL_INTERVENTION` | **非終端**。自動処理では安全に解消できない異常を検知し運用者の`RESUME`待ち（§3.6.2新設） | `SWITCHING` | `SWITCHING`（`RESUME→切替再試行`） / `FAILED`（`RESUME→FAILED確定`） |
| `COMPLETED` | 切替完了（終端） | `SWITCHING` / `PARTIALLY_COMPLETED` | — |
| `FAILED` | 失敗（終端。旧契約は無傷。ただしMANUAL_INTERVENTION経由の場合は旧が既に更新済みのことがあり運用者判断に委ねる） | 各状態・`MANUAL_INTERVENTION`（`RESUME`経由） | — |
| `EXPIRED` | 期限切れ（終端） | `REQUESTED` / `REQUIRES_PAYMENT_METHOD` | — |

`scope_kind` は `TEAM`/`ORG` のみ許容（`USER` はアプリ層で作成要求自体を拒否する。USER スコープは契約者本人以外に payer が存在し得ないため引継の概念自体が無い）。

### 4.3 Flyway 方針（変更なし）

- 命名: `V{major}.{yyyyUTCタイムスタンプ}__add_billing_payer_handover.sql`（既存規約どおり）
- major 番号は実装 PR 時点で `origin/main` の最大 major+1 を採番
- `ALTER TABLE billing_contracts` と `CREATE TABLE billing_payer_handover_requests` は別ファイルに分割

---

## §5. purge 連携（P1-9・P1-10 反映）

### 5.1 検出（R2-P1-6: 候補絞り込み条件を追加）

`WithdrawalRequestedEvent`（Day 0・退会受付時点）で、退会予定ユーザーが `payer_user_id` として紐づく TEAM/ORG `billing_contracts`（`status IN (PENDING, ACTIVE, PAST_DUE)`）を検出する。

**検出クエリに以下を追加する（R2-P1-6）**: `psp_subscription_ref IS NOT NULL AND current_period_end IS NOT NULL`。

理由: §2〜§3の引継フロー（`trial_end`方式・pointer切替）はいずれも「Stripe側に実在するサブスクリプションの `current_period_end`」を前提にしており、この2条件を満たさない契約（無償契約＝価格NULLの契約、またはPSP側のサブスクがまだ作成されていない`PENDING`初期段階の契約）には**適用できない**。

**無償/PSP未作成契約の別経路（1行定義）**: 上記2条件を満たさない対象契約は、Stripe API呼び出しを一切行わず、`billing_contracts.payer_user_id` を新payerの user_id へ直接更新するだけの「payer概念のみの更新」で処理する（決済が存在しないため引継の必要な決済移行対象がそもそも無い）。

### 5.2 他 ADMIN への引継要求通知（変更なし）

対象スコープの他 ADMIN 全員に通知。文言には §2.3 の「請求日は変わらない（旧期末＝新開始のため）」ことを明記できる点が、旧設計（置換方式単純版）からの改善点として案内文言に含められる。

### 5.3 猶予期限（変更なし）

`requested_at` から14日。期限内に `ACCEPTED` にならなければ `EXPIRED` とし、purge バッチ実行時点で該当契約を期末解約（`cancelAtPeriodEnd`）に倒す。

### 5.4 purge×handover の相互条件表（P1-9 新設）

**原則: handover が `REQUESTED`/`ACCEPTED`/`REQUIRES_PAYMENT_METHOD`/`SWITCHING`/`PARTIALLY_COMPLETED`/`MANUAL_INTERVENTION`（＝非終端。§4.2の生成列定義と一致）の間は、purge 側の期末解約フォールバックを発火させない。handover の期限切れ（`EXPIRED`）または失敗（`FAILED`）が先に確定してから、purge の fallback 判定に処理を渡す。`MANUAL_INTERVENTION`は特に、旧サブスクが既に期末境界を越えて更新済みの可能性があるため、purgeのfallback（`cancelAtPeriodEnd`）を機械的に発火させると運用者の判断より先にStripe状態を動かしてしまう危険がある——非終端に含める最大の理由はこの回避にある。**

| 交錯パターン | 挙動 |
|---|---|
| `REQUESTED`/`ACCEPTED` 中に purge バッチが定期実行される | handover が非終端であることを見て fallback をスキップ（何もしない）。次回バッチに持ち越し |
| `SWITCHING` 中（新サブスク trial 中）に purge バッチが実行される | 同上でスキップ。切替完了 or 失敗を待つ |
| handover が `EXPIRED` になった直後の purge バッチ | `EXPIRED` は終端状態のため fallback 対象と判定し、旧契約を `cancelAtPeriodEnd` する |
| 承諾（`ACCEPTED`）直後に30日物理purgeが完走してしまうケース | 30日物理purge（`AccountPurgeService#purgeUser`）と14日のhandover猶予は独立タイムラインだが、猶予14日は物理purgeの30日より必ず短いため理論上は競合しない。ただし猶予をユーザーが早期に短縮する運用変更を将来行う場合は要再検証（本設計では現行の14日/30日の大小関係を前提とする） |
| `PARTIALLY_COMPLETED` のまま長時間放置 | purge fallback の対象にはしない（新契約は既にACTIVEで課金先が確定しているため、fallback で旧契約を解約しても実害はない一方、fallbackが誤って新契約を巻き込まないよう `PARTIALLY_COMPLETED` は「非終端」として扱い、purge判定からは除外し続け、リトライ処理（§3.5）にのみ委ねる） |

### 5.5 「引継先候補なし」の5分岐（P1-10 新設）

| 分岐 | 判定方法 | 挙動 |
|---|---|---|
| ① ADMIN が0人（退会者が最後のADMIN） | スコープのADMIN人数照会 | 通知を送らず即座に `FAILED` として記録し、purgeのfallback判定に委ねる（実質は§5.3の「期限内未引継」と同じ扱い） |
| ② 他ADMIN全員が退会予定（`deleted_at` 設定済 or `requestWithdrawal` 済） | ADMIN一覧のうち退会予定でない人数を照会 | ①と同様 `FAILED` |
| ③ 唯一の承諾可能なADMINにPaymentMethodが無い | §3.6 の事前検証 | `REQUIRES_PAYMENT_METHOD` へ差し戻し、猶予期限内であれば再承諾を待つ。期限超過で `EXPIRED` |
| ④ 承諾後に本人確認・3DS認証が失敗し続ける | §3.6 遷移表 | 一定時間（既定24時間）認証未完了なら `FAILED` とし、他のADMINへ再度通知を送る（複数ADMINがいる場合のみ再トライ） |
| ⑤ 複数ADMINが存在するが全員が承諾を拒否/無視 | 猶予期限到来 | `EXPIRED`（通常の期限切れと同じ扱い） |

### 5.6 引継承諾の認可（変更なし）

- 承諾 API は当該スコープの ADMIN ロールを持つユーザーのみ許可（既存 `billingOperationAuthorizer.requireCanManage`）
- 複数 ADMIN 同時承諾のレースは `old_contract_id` 行を `SELECT ... FOR UPDATE` でロックしてから判定（§4.2）

---

## §6. `membership_subscriptions`（`cancelAllForPayerOnWithdrawal`・変更なし）

`membership_subscriptions` は既に payer/beneficiary/payee の3分離が完了しているため、`billing_contracts` のような「payer 列の新設」は不要。

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public List<String> cancelAllForPayerOnWithdrawal(Long payerUserId) { ... }
```

- 対象: `payer_user_id = :payerUserId AND status IN (ACTIVE, PAST_DUE)`
- 動作: 契約単位で Stripe `cancel_at_period_end=true` を発行し、DB側 `status` を `CANCEL_SCHEDULED` 相当へ更新
- 受益者への通知: 「あなたのメンバーシップは payer の退会に伴い期末で終了します」。受益者自身が新payerになる導線は本設計ではスコープ外（通知のみ）
- 呼び出し元: `WithdrawalStripeHandler` を実装し、`WithdrawalRequestedEvent`（Day 0）購読時点で呼ぶ

### §6.1 退会経路の実行モデル（PR-3・Codex 検分1巡目の是正で追加）

PR-3 の実装に対する独立検分で、上の1行仕様のままでは**成立しない**欠陥が4件見つかった。ここに是正後の実行モデルを正本として記す。

#### (1) 退会者は対話 API の認可を必ず通れない（P0）

`UserService#requestWithdrawal` は**先に `deleted_at` を立てて commit し**、その後 `AFTER_COMMIT` で決済連携を呼ぶ。ところが対話 API 用の `BillingPayerHandoverService#requestHandover` は `billingOperationAuthorizer.requireCanManage` を通り、その認可 SQL は `users.deleted_at IS NULL AND status='ACTIVE'` を必須条件にしている。したがって**退会者は全件で認可に失敗し、TEAM/ORG の引継要求は1件も作られない**。現行 purge は USER スコープ契約しか処理しないため、旧 payer への課金がそのまま継続する。

→ 退会イベント専用の内部入口 `requestHandoverForWithdrawal(oldContractId, withdrawingPayerUserId)` を設ける。認可の根拠を「現在もそのスコープの管理者か」から**「その契約の払い手が、いま退会したその人自身か」**へ置き換える。呼び出し側は契約 ID しか渡せず、**スコープは契約行から読み出す**ため任意ユーザー・任意スコープでの越境は構造的に成立しない。判定と要求作成の間に payer が書き換わらないよう、契約行を `SELECT ... FOR UPDATE` でロックしてから読む。

#### (2) 失敗・プロセス停止の再試行経路（P1-1）

退会イベントは永続化されない Spring のインメモリイベントで、ハンドラは共有 `event-pool` 上の非同期処理である。Stripe 失敗・投入拒否・commit 直後のプロセス停止では処理そのものが失われ、退会者への課金継続をログ監視だけに委ねることになる。

→ **サブスク単位の処理状態を永続化する**（`membership_payer_withdrawal_cancellations`・V204）。**再試行の駆動（夜次バッチ）は PR-4 に委ねる**が、状態の永続化自体は PR-3 に入れる——状態が無ければ PR-4 でも対象を拾いようがないため。

**状態（6値）と非終端の定義**:

| 状態 | 意味 | 終端 |
|---|---|---|
| `PENDING` | 解約に着手したが Stripe・DB の双方の確定に至っていない | ✗（再試行対象） |
| `SUCCEEDED` | Stripe の `cancel_at_period_end=true` と DB 反映の双方が確定 | ○ |
| `FAILED` | 解約が明示的に失敗（`last_error` に理由） | ✗（再試行対象） |
| `RESTORING` | 退会取消による解除に着手したが確定に至っていない | ✗（照合対象） |
| `RESTORED` | 解除が Stripe・DB の双方で確定 | ○ |
| `SUPERSEDED` | 本人の明示操作により「退会処理由来」という由来が上書きされた | ○（復旧対象外） |

**作業行がそもそも作られない穴（2巡目 P1-2）**: 契約ごとの `prepare` で行を作る形だと、「複数契約の途中で停止し、まだ `prepare` に到達していない契約」に行が残らない。そこで **Stripe に触れる前に対象全件の行を1トランザクションで `PENDING` として commit する**（`reserveAll`）。それでも「退会本体の commit 後・非同期タスクが始まる前の停止」「`event-pool` の投入拒否」では `reserveAll` 自体が呼ばれない。この最後の穴は、作業行ではなく**退会状態そのもの**を起点にする `MembershipSubscriptionService#findWithdrawalCancelBacklog()`（`users.deleted_at IS NOT NULL` × `cancel_at_period_end = false`）が塞ぐ。行の有無に関係なく「やり残した解約」を再構築できる。

**2経路の重複排除**（3巡目 P2 / 4巡目 P2）: PR-4 の再試行バッチは「非終端の作業行」と「backlog」の2つを走査する。`findWithdrawalCancelBacklog()` は非終端の作業行を持つ契約を除外するので、**同一時点のスナップショットとしては**互いに素になる。ただし本メソッドは複数の独立クエリの組み合わせでトランザクションを張っておらず、照会の間に作業行が作られれば重なりうる——**時間軸を含めると「常に互いに素」ではない**。PR-4 の駆動側は、契約ごとの処理が行ロック下で状態を再検証する（本 PR の `prepare`/`prepareRestore` と同じ作法）ことで二重投入を無害化すること。

**非終端のまま拾われ続ける行を作らない**（3巡目 P2）: 復旧中に対象が消えた・payer が変わった・期末が到来して終端化した場合、`prepareRestore` は空を返すだけでなく作業行を `SUPERSEDED` へ**終端化する**。そうしないと照合バッチが永久に同じ行を拾い続ける。

> **リリース依存（PR-4 で解消済み）**: PR-3 単独では失敗した期末解約は自動では再試行されなかった（対象は DB に残るが、拾う主体が居ない）。**PR-4 の `MembershipPayerWithdrawalRetryBatchService`（日次 03:20 JST）が駆動主体として着地し、この依存は解消した。** 同バッチは「非終端の作業行（`PENDING`/`FAILED`）」と「`findWithdrawalCancelBacklog()`」を**払い手単位で union/dedup** してから解約側を、`RESTORING` を解除側として別パスで駆動する。dedup を入れるのは、backlog の照会が複数の独立クエリの組み合わせでトランザクションを張っておらず、**照会の間に作業行が作られれば重なりうる**ためである（「同一時点のスナップショットとしては互いに素」＝「常に互いに素」ではない）。仮に重複しても、払い手ごとの処理が行ロック下で状態を取り直して再検証するため二重発行にはならない（抽出側の dedup と処理側の再検証という二段の防御）。

#### (3) 1件の失敗が全件を巻き添えにしない（P1-2）

全件を単一の `REQUIRES_NEW` トランザクションで処理してはならない。`save` の SQL 発行は commit まで遅延しうるため、**最後の flush/commit で1件でも DB 更新が失敗すると成功した全契約の DB 変更と通知イベントがまとめてロールバック**する一方、先に成功した Stripe の `cancel_at_period_end=true` は戻らない。

→ **ID だけを抽出 → 1契約ずつ別 Bean の public メソッドで独立トランザクション**（Spring の `@Transactional` は自己呼び出しでは効かないため Bean 分離が必須）。実行単位は3段:

| 段 | 主体 | 内容 |
|---|---|---|
| tx① | `MembershipPayerWithdrawalTxService#prepare` | 行ロック → payer/status/`cancel_at_period_end` を**取り直して**再検証 → 処理状態を `PENDING` で永続化して commit |
| — | `MembershipPayerWithdrawalRunner` | Stripe `cancel_at_period_end=true`（tx 外・Idempotency-Key はサブスク ID 由来で固定） |
| tx③ | `…TxService#applyScheduled` | 行ロック → 再検証 → `saveAndFlush` で契約単位に確定 → `SUCCEEDED` 記録 → 受益者通知を publish |

各トランザクションが行ロックを取り直すのは、抽出クエリにロックが無いままだと `customer.subscription.deleted` webhook（同じ行のロックを取る）と競合し、古い ACTIVE エンティティを保持したままの UPDATE が webhook の `CANCELLED` を上書きしうるためである。確定時点で既に終端なら「課金が止まる」目的は達しているので DB は触らず `SUCCEEDED` として記録する。

#### (4) 退会取消時の復旧（P1-3）

退会は30日以内に取り消せる（`WithdrawalCancelledEvent`）。取り消したのに期末でメンバーシップが終了し、引継要求が `REQUESTED` のまま残って次の申請を塞ぎ続けるのでは筋が通らない。

- **membership 側**: `membership_subscriptions.cancel_at_period_end` は boolean であり、**本人が退会前に明示解約した契約**と**退会処理が自動予約した契約**を区別できない。単純に payer の全予約を解除すると前者まで復活させてしまう。`membership_payer_withdrawal_cancellations`（退会処理が予約した行だけが存在する）を**由来の正本**として引き、`SUCCEEDED`／`RESTORING` の行だけを Stripe（`revertSubscriptionCancelAtPeriodEnd`）と DB の双方で戻す。
- **handover 側**: §4.2 の遷移表どおり `REQUESTED → FAILED` へ終端化する。終端化しないと生成列 `open_old_contract_id` と `uk_bphr_open_old_contract` が同一契約への次の引継要求を猶予期間中ブロックし続ける。`ACCEPTED` 以降は対象にしない——新 payer 側で既に支払い手段の検証や新サブスク作成が進んでおり、退会取消だけを根拠に機械的に巻き戻すと Stripe 側と乖離するため、通常の期限・切替判定（§3.6）に委ねる。

#### (5) イベントの到達順に依存しない（2巡目 P1-1）

退会と退会取消は**どちらも**共用 `event-pool` 上の非同期処理であり、到達順は保証されない。退会受付の直後に取り消すと、取消処理が先に走って対象ゼロで終わり、そのあとに届いた古い退会イベントが期末解約と `REQUESTED` 引継要求を作ってしまう。

→ **イベントの中身ではなく、処理時点の DB の真値を見る**。auth ドメインの `WithdrawalStateQueryService#findPendingWithdrawalAttempt(userId)` が唯一の窓口で、「いま退会申請中か」と「どの退会試行か（世代 ＝ `users.deleted_at`）」の両方に1回で答える。

| 処理 | 進める条件 |
|---|---|
| 期末解約 tx①（`prepare` / `reserveAll`） | 退会申請中である |
| 期末解約 tx②（`applyScheduled`） | **tx① で見た世代と同一の退会が今も継続している** |
| 引継要求の作成（`requestHandoverForWithdrawal`） | 退会申請中である |
| 引継要求の終端化（`failRequestedOnWithdrawalCancelled`） | 退会申請中<b>ではない</b> |
| 解約の解除（`prepareRestore`） | 退会申請中<b>ではない</b> |

**tx② でも世代を再検証するのが要点である**（3巡目 P1-2）。行ロックは各短期トランザクションの中でしか保持されず、**Stripe 呼び出しを跨いだ直列化にはならない**。tx① で真値を確かめてもロックはそこで解放され、その間に退会が取り消されうる。再検証が無いと「退会取消済みなのに期末解約が確定し、作業行は終端 `SUCCEEDED`」という、backlog にも再試行にも載らない回復不能な状態が生まれる。

世代が変わっていた場合は DB へ反映せず、作業行を `RESTORING`（＝Stripe 側の予約を取り消す必要がある）にして**その場で解除へ切り替える**。Stripe には既に予約が入っている可能性があるため、放置は許されない。

> **`users` は `@SQLRestriction("deleted_at IS NULL")` を持つ**ため、JPQL・`findById` では退会申請中のユーザーが1件も返らない（＝常に「申請中でない」と誤答する）。この判定は **native クエリでなければ成立しない**（`UserRepository#findDeletedAtIncludingDeleted`）。
>
> **同じ罠が `UserService#cancelWithdrawal` 本体にもあった**（3巡目 P1-1）。`findById` で退会者を引こうとして必ず `AUTH_015` で終了し、**退会取消そのものが一度も成立していなかった**（`WithdrawalCancelledEvent` も発行されない／直後の `AUTH_032` 分岐は到達不能な死んだコード）。`findByIdForUpdateIncludingDeleted` へ是正した。**IT がこの欠陥を隠していた**——`UPDATE users SET deleted_at = NULL` の SQL 直叩きで本番経路を迂回していたためである。IT は必ず `cancelWithdrawal()` を通す。

**本人の新しい意思との衝突**: 退会取消の**後**に本人が改めて明示解約した場合、復旧処理は同じ `cancel_at_period_end=true` しか見ないため、その新しい意思まで解除してしまう。人が新しい判断を下した瞬間——`MembershipSubscriptionService#cancel`——に由来の記録を `SUPERSEDED` へ終端化し、以後の復旧対象から外す。**無効化の対象には `PENDING` を含める**（3巡目 P1-3）——古い退会処理が `PENDING` の間に「退会取消＋明示解約」が入る競合があり、`SUCCEEDED`/`RESTORING` だけを対象にすると無効化が no-op になる。

あわせて `applyScheduled` は**反映できなかったとき（`applied=false`）に `SUCCEEDED` を書かない**。「既に `cancel_at_period_end=true`」は本人の明示解約かもしれず、`SUCCEEDED` にすると復旧処理がそれを退会由来と誤認する。この場合は `SUPERSEDED`（＝自分が予約したのではない）とする。

#### (7) Stripe 冪等キーの世代分離（4巡目 P1-1）

Stripe の Idempotency-Key は**同一キーに対して最初の応答をそのまま返す**（24時間）。キーを `subscriptionId` だけから作ると、「世代Aで解約予約 → 取消で解除 → 24時間以内に世代Bで再退会」で世代Bのリクエストが世代Aと同じキーになり、**3回目の更新が実行されない**。DB は `cancel_at_period_end=true`、Stripe 実体は解除済み、という金銭事故に直結する乖離が残る。

→ 冪等キーに**退会試行ごとに必ず異なる値**を含める。ここで**時刻を使ってはならない**（5巡目 P1-3）——本番の `users.deleted_at` は `DATETIME`（小数秒なし）であり、同一秒内の「退会A → 取消 → 再退会B」では世代値が一致してしまう。**作業行の `attempt_count`**（`markAttempt` が退会試行のたびに単調増加させる、時刻に依存しない値）を使う。

    withdrawal-payer-cancel-<subscriptionId>-<attemptCount>
    withdrawal-payer-restore-<subscriptionId>-<attemptCount>

解約・解除で接頭辞を分けるため両者も衝突しない。

#### (8) 世代の再検証は「書き込む全経路」で行う（4巡目 P1-2）

確定（`applyScheduled`）だけでなく、**解除の確定（`applyRestore`）と失敗の記録（`markFailed` / `markRestoreFailed`）も世代と現在状態を再検証する**。していないと次が起きる。

- 世代Aの解除が Stripe を呼んでいる間に世代Bの再退会が完了 → 遅れて戻った世代Aが**世代Bの解約予約を解除**する
- 本人の明示解約で `SUPERSEDED` になった行に、古い Stripe 呼び出しの失敗が `FAILED` を書き戻す → 広げた復旧対象集合により**本人の明示解約が解除され得る**

`markFailed` は「自分の世代かつ `PENDING`」、`markRestoreFailed` は「自分の世代かつ `RESTORING`」のときだけ書く。

#### (9) 真値の確認はロック付きで行い、ロック順序を固定する（4巡目 P1-3・P2）

退会状態を根拠に DB を書き換える経路は、`WithdrawalStateQueryService#lockAndFindPendingWithdrawalAttempt`（`select deleted_at ... for update`）でユーザー行をロックしてから判断する。ロックなしの読み取りでは「真値を見た直後・自分が書き込む前」に `cancelWithdrawal` や再退会が commit でき、確認と反映が線形化しない。**引継要求の作成（`requestHandoverForWithdrawal`）と終端化（`failRequestedOnWithdrawalCancelled`）も例外ではない**（5巡目 P1-1。規定を書きながら呼び出し側が非ロック版のまま残っていた）。

**ロック順序の正準は `users` → `membership_subscriptions` → `membership_payer_withdrawal_cancellations`。** `PENDING` を復旧対象へ加えたことで解約側と解除側が同じ行集合を触るようになったため、順序を固定しないとデッドロックしうる。

#### (11) 「既に予約済みだからスキップ」を DB 列だけで判断しない（5巡目 P1-2）

`prepare` が `membership_subscriptions.cancel_at_period_end` だけを見てスキップすると、次の経路で **Stripe=false・DB=true・作業行=`PENDING`** という誰も進めない状態が残る。

1. 世代Aの復旧で Stripe の予約解除が成功する
2. `applyRestore` の前に世代Bの再退会が commit する
3. `applyRestore` が再退会を検出して `false` を返し、DB の `true` を残す
4. 世代Bの `reserveAll` が作業行を `PENDING` にする
5. `prepare` が DB の `true` だけを見てスキップする → 世代Bの Stripe 解約が永久に発行されない

→ **作業行が非終端（`PENDING`/`FAILED`/`RESTORING`）＝自分たちの処理が途中である間は、DB が `true` でも Stripe へ発行して揃える。** DB 列は Stripe 側の実態と乖離しうる前提で扱う。「自分たち以外が予約した」（作業行が無い、または終端）ときだけスキップする。

あわせて `applyScheduled` は、DB 列を変更できなかった（`applied=false`）場合でも**その作業行が自分たちのもの（世代一致かつ `PENDING`）なら `SUCCEEDED`** とする。`SUPERSEDED` にするのは「自分が予約したのではない」ときだけである（4巡目 P1-3 の意図はそのまま維持される）。

#### (10) 明示解約と由来の無効化は同一トランザクション（4巡目 P1-4）

`supersedeByUserDecision` の伝播は `REQUIRED`（既定）にする。`REQUIRES_NEW` だと、内側が commit した後に外側（利用者の解約 API）がロールバックした場合、**DB 解約は成立していないのに作業行だけ終端化**され、以後の退会取消で本来戻すべき予約を復旧対象と認識できなくなる。

#### (6) 復旧の「Stripe 成功・DB 失敗」（2巡目 P1-3）

Stripe の予約解除に成功した直後・DB 反映前に落ちると、**Stripe は継続・DB は `cancel_at_period_end=true`** という永続的な不整合になる。行が `SUCCEEDED` のままでは非終端集合に入らず、取消イベントも再配送されないため自動回復できない。

→ **`RESTORING` を Stripe 呼び出しの前に commit する**。以後どこで落ちても行は非終端として残り、再試行・照合の対象になる。復旧の失敗は `FAILED` へは倒さない——`FAILED` は「解約が未了」を意味し、再試行バッチの扱いが逆向きになるためである。

---

## §7. GDPR×会計保持の境界（P2-15 具体化）

### 7.1 保持期間・法的根拠

| データ | 保持期間 | 法的根拠 |
|---|---|---|
| `billing_contracts`（`payer_user_id` を含む契約履歴全体） | 契約終了後 **7年**（法人税法上の帳簿書類保存義務・国税関係書類の保存期間に合わせる） | 電子帳簿保存法・法人税法上の保存義務 |
| `billing_payer_handover_requests` | 同上7年（会計上「誰から誰に請求担当が移ったか」の説明責任を伴う証跡のため契約履歴と同じ扱い） | 同上 |
| Stripe側の取引記録 | Stripe側の法定保存義務に委ねる（Mannschaft側から削除指示を出さない） | PCI DSS・Stripe利用規約 |

### 7.2 匿名化方式（Day 30 強匿名化時）

- `billing_contracts.payer_user_id` / `created_by`、`billing_payer_handover_requests.old_payer_user_id` / `new_payer_user_id` は、Day 30 強匿名化のタイミングで **数値user_idそのものは残すが、既存の二段匿名化モデルにより `users` テーブル側のPII（氏名・メール等）が既に匿名化されているため、この参照だけでは実質的に個人特定不能になる（弱参照・既存 `created_by` と同じ性質）**
- **監査に残す相関IDの範囲を明確化**: `billing_payer_handover_requests.id`（UUIDv7）自体と、`old_contract_id`/`new_contract_id`/`psp_new_subscription_ref` は匿名化対象外としてそのまま残す。これにより「いつ・どの契約からどの契約へ・どのStripeサブスクへ切り替わったか」という会計監査に必要な相関関係は保持しつつ、個人を特定する情報（氏名・メール等）は`users`側の既存匿名化フローの対象のまま揃える
- Stripe 側 Customer/PaymentMethod自体の削除タイミングは本設計のスコープ外（既存の決済ドメインの匿名化フローに委ねる）

### 7.3 既存二段匿名化モデルとの整合

- 既存9ドメインの `*AnonymizationEventListener`（auth/favorite/notification/schedule/social/village/weather/scopefolder/chart）にbilling/paymentドメインは含まれない（実コード再確認済み）
- 本設計が追加する差分は「payer列を追加したことで、匿名化されない会計記録にuser_id参照が新たに増える」点のみであり、`created_by`が既に持っていた性質の踏襲（新規リスクではない）

---

## §8. AC 一覧（PR分割案つき・攻め口5類型の消し込み・検分反映済み）

攻め口5類型: **(a) 二重課金/二重サブスク (b) 権限昇格・IDOR (c) データ不整合(宙ぶらりん) (d) レース/冪等性破れ (e) 通知・UX欠落**

| AC番号 | 内容 | 攻め口 | 検証方法 |
|---|---|---|---|
| AC-1 | `billing_contracts.payer_user_id`/`handover_request_id` 追加後、既存行は `created_by` と同値でバックフィルされる | (c) | Flyway 適用後の SELECT で NULL 行が無いことを確認 |
| AC-2 | 新規 TEAM/ORG 契約作成時、`payer_user_id` は `created_by` と同値で初期化される | (c) | IT |
| AC-3 | `cancelAllUserContractsForPurge` は `scope_kind=USER` に加え `payer_user_id` 一致の TEAM/ORG 契約も検出する | (c) | IT |
| AC-4 | 新サブスクは `trial_end=旧current_period_end` で作成され、trial中（＝旧期末まで）は一切課金されない | (a) | IT: Stripeテストモードで trial 中の invoice が生成されないことを確認 |
| AC-5 | 新サブスクの trial 終了と旧サブスクの期末に隙間・重複が発生しない（`trial_end` と旧 `current_period_end` が同一 Unix timestamp） | (a) | IT: 両者のタイムスタンプ一致をアサーション |
| AC-6 | **（R3-P1-4で新方式へ書き換え）** 旧サブスクの `cancel_at_period_end=true` は、承諾確定（`checkout.session.completed`）と**同時に**設定される（`cancelImmediately`は使わない）。切替TX（旧期末到達時）はローカルDB操作（pointer付替え＋状態遷移）のみで、Stripe API呼び出しを含まない | (a) | IT: 承諾確定直後に旧サブスクの`cancel_at_period_end`が`true`になることを確認。また切替TX実行時にStripe API呼び出しが発生しないことをモック検証（呼び出し回数0を確認） |
| AC-7 | 同一 `handoverRequestId` で新サブスク作成を複数回試行しても Stripe 側には1つのサブスクしか作られない（DB確認→List Subscriptions確認→新規作成の順序、Idempotency-Keyは補助） | (a)(d) | IT: DB書き込み失敗を模擬したリトライで二重作成されないことを確認 |
| AC-33 | List Subscriptions照会は`has_more`に従い全ページを走査し、対象サブスクリプションが2ページ目以降（例: 同一Customerに101件以上のサブスクリプションが存在する状況）に存在していても検出できる | (a)(d) | IT: 対象Customerに100件超のダミーサブスクリプションを用意し、目的のサブスクを最終ページ側に配置した上で照会が検出することを確認 |
| AC-8 | 引継要求が期限（14日）内に `ACCEPTED` にならなければ `EXPIRED` となり、purgeバッチが対象契約を期末解約する | (c)(e) | IT |
| AC-9 | 通知文言に「請求日は変わらない（新サブスクは旧期末から開始）」旨が含まれる（i18n 6言語対応） | (e) | FEレビュー＋ロケールファイル確認 |
| AC-10 | ADMINが0人または他ADMIN全員が退会予定の場合、引継要求は発行されず即座に `FAILED`→purgeのfallback判定に委ねられる | (c) | IT |
| AC-11 | 引継承諾APIは当該スコープのADMIN以外を403で拒否する | (b) | IT |
| AC-12 | 複数ADMINが同時に承諾操作を行っても状態遷移は1回のみ有効になる（`SELECT ... FOR UPDATE` 直列化） | (d) | IT: 2並列リクエスト模擬 |
| AC-13 | `MembershipSubscriptionService#cancelAllForPayerOnWithdrawal` は `payer_user_id` 一致かつ `ACTIVE`/`PAST_DUE` のみ期末解約し、受益者へ通知する | (c)(e) | IT |
| AC-14 | 旧サブスクの `customer.subscription.deleted` webhook 処理（`hardDeleteBySlotAndContractId` 経由）は、既に切替TXで新契約へ付け替わった pointer を消さない（`contract_id` 不一致で0件更新になることを確認） | (c) | IT: 切替完了後に旧webhookを遅延投入し、新契約のpointer/entitlementが残ることを確認 |
| AC-15 | Day 30 強匿名化後も `billing_contracts.payer_user_id`／`billing_payer_handover_requests` の行・相関IDは削除されない（会計記録保持） | (c) | IT |
| AC-16 | ACCEPTED→SWITCHING遷移前に新payerのPaymentMethod有無を検証し、無ければ `REQUIRES_PAYMENT_METHOD` へ差し戻す（旧契約は不変） | (e) | IT |
| AC-17 | 「ADMIN 0人」分岐: 通知を送らず `FAILED` として記録する | (c)(e) | IT |
| AC-18 | 「他ADMIN全員退会予定」分岐: 同上 `FAILED` | (c)(e) | IT |
| AC-19 | 「PaymentMethod無し」分岐: `REQUIRES_PAYMENT_METHOD` へ差し戻し、登録後に再検証できる | (e) | IT |
| AC-20 | 「承諾後認証失敗」分岐: 24時間認証未完了で `FAILED` とし複数ADMIN在籍時は再通知する | (e) | IT |
| AC-21 | 「複数ADMIN全員無視」分岐: 猶予期限到来で `EXPIRED` | (e) | IT |
| AC-22 | 競合: 承諾操作とpurge fallbackが同時に走っても、非終端状態のhandoverに対してfallbackは発火しない | (d) | IT |
| AC-23 | 競合: 承諾直後に期限切れバッチが走っても、既に`ACCEPTED`以降の行は`EXPIRED`にされない（`SELECT...FOR UPDATE`と期限バッチのロック順序を検証） | (d) | IT |
| AC-24 | 競合: 承諾確定時の`cancel_at_period_end=true`設定処理が二重に起動されても、Idempotency-Key（`billing-handover-schedule-cancel-*`）により実害が出ない（Stripe側は同一リクエストとして扱われる） | (d) | IT |
| AC-25 | 競合: Stripe新サブスク作成が成功した直後にDBトランザクションがロールバックしても、次回リトライで List Subscriptions API（`customer`＋`status=all`指定・`metadata.handoverRequestId`でクライアント側フィルタ）から回収できる。List はread-after-write整合のため、Search API利用時に必要だった待機間隔は不要（間隔ゼロで即座に再試行可） | (d) | IT: DBロールバック直後（間隔を空けずに）リトライし、Listから回収できることを確認 |
| AC-26 | 競合: webhook処理と同期処理（切替TX）が同時に同じhandover行を更新しようとしても、行ロックにより一方が待たされ不整合が生じない | (d) | IT |
| AC-27 | 切替TXは旧契約の`current_period_end`到達のみを条件に実行され、`invoice.paid`の到達を待たない。旧pointer削除と新pointer作成が同一トランザクションで行われ、entitlementが一瞬たりとも「どちらの契約からも発行されていない」状態にならない | (c) | IT: 旧期末到達時刻ちょうどでentitlement照会を行い、途切れがないことを確認 |
| AC-28 | 新サブスクの初回請求（trial終了時）が失敗した場合、新契約は特別扱いされず通常の`ACTIVE→PAST_DUE`遷移に乗り、既存の督促・停止フローがそのまま適用される | (c) | IT: 初回請求失敗をモックし、新契約が`PAST_DUE`になり通常契約と同じ督促処理が走ることを確認 |
| AC-29 | 旧契約が`PAST_DUE`または`current_period_end`が過去の場合、引継要求の作成自体が拒否され、ADMINへ「先に旧契約を解消してください」という案内が返る | (c)(e) | IT: 該当条件のフィクスチャで引継要求APIを呼び、400系エラーと案内文言を確認 |
| AC-30 | **（R3-P1-2で新方式へ書き換え）** 新サブスク作成直後（1段目チェック）に`pending_setup_intent`が非NULLの場合、新payerへ追加認証を促す通知のみ送り状態遷移はさせない。**切替バッチが旧期末到達時（2段目・最終確定）に再度`pending_setup_intent`を確認し、依然未解決なら切替TXを実行せずhandoverを`FAILED`に確定**（新trialサブスクを`cancelImmediately`で無課金取消・旧サブスクの`cancel_at_period_end`を`false`へ差し戻し継続）。2段目で解決済みなら通常どおり切替TXへ進む | (e) | IT: 1段目で非NULLのまま2段目（旧期末到達）に達するケースをフィクスチャで再現し、`FAILED`確定・新サブスク無課金取消・旧サブスク継続の3点を確認 |
| AC-31 | 承諾確定（`checkout.session.completed`）と同時に旧サブスクへ`cancel_at_period_end=true`が設定されるため、承諾確定以降はどの後続手順（切替TX・pending_setup_intent未解決によるFAILED確定等）が失敗しても、旧サブスクが更新され続けて二重課金する余地が無い | (a) | IT: 承諾確定後に切替TX・通知処理等を意図的に全て失敗させても、旧サブスクの`cancel_at_period_end`が`true`のまま保たれ期末で終了することを確認 |
| AC-32 | handoverが期末前に`FAILED`確定した場合（新規作成失敗・trial中の異常・pending_setup_intent未解決の2段目確定等いずれの経路でも）、旧サブスクの`cancel_at_period_end`が`false`へ差し戻され、旧契約が継続することを確認する。**同時に`old_cancel_scheduled_at`がNULLへクリアされることも確認する（R5-P2）** | (c) | IT: 各FAILED経路ごとに旧サブスクの`cancel_at_period_end`が`false`に戻り、かつ`old_cancel_scheduled_at`がNULLになることを確認 |
| AC-34 | `cancel_at_period_end=true`設定APIの呼び出しがDB書き込み前後どちらで失敗しても（呼び出し自体の失敗／呼び出しは成功したが`old_cancel_scheduled_at`永続化のみ失敗）、夜次照合バッチがStripe側の実サブスクを取得して突合し、必要なら再設定・必要なければDB整合のみ回復する（冪等） | (a)(c) | IT: 2パターン（API失敗／DB書き込みのみ失敗）それぞれをフィクスチャで再現し、夜次照合バッチ実行後に`old_cancel_scheduled_at`が正しく埋まることを確認 |
| AC-35 | **（R5-P1-1/2で書き直し）** 切替バッチは切替TX実行前に必ずStripeの実サブスクで`cancel_at_period_end=true`を確認する。`false`かつ**期末境界越えを検知しない**場合はその場で設定してから切替する。`false`かつ**期末境界越えを検知した場合は自動でのtrue設定・void/refundを行わず、切替TXを実行せずに`MANUAL_INTERVENTION`へ倒し、ADMIN・運用へ即時アラートを飛ばす**（既定はこちら・R5-P1-1裁定） | (a)(c) | IT: 夜次照合バッチが未検出のまま旧期末に到達したケースをフィクスチャで再現し、(1)期末境界越えが無ければ切替バッチが検出・その場再設定して切替まで進むこと、(2)期末境界越えがあれば`MANUAL_INTERVENTION`へ倒れ切替TXが実行されずアラートが発火することの両方を確認 |
| AC-36 | `MANUAL_INTERVENTION`は非終端として扱われ、`open_old_contract_id`生成列が値を保持し続ける（同一契約への新規引継要求がブロックされる）。また purge の期末解約フォールバックも`MANUAL_INTERVENTION`の間は発火しない | (c)(d) | IT: `MANUAL_INTERVENTION`状態のhandoverが存在する契約に対し、新規引継要求APIとpurgeバッチの両方を実行し、いずれも作用しないことを確認 |
| AC-37 | `RESUME`操作は`MANUAL_INTERVENTION`からのみ許可され、運用者が明示的に指定した遷移先（`SWITCHING`への復帰、または`FAILED`への確定）へのみ遷移する。`FAILED`確定時は§3.6.1の差し戻しフロー（`cancel_at_period_end=false`・`old_cancel_scheduled_at`NULLクリア）を伴う運用判断であることをAPI/画面上で明示する | (b)(e) | IT: `RESUME`APIを両方の遷移先で呼び出し、それぞれ正しい状態へ遷移することを確認。当該スコープのADMIN以外・運用権限以外からの呼び出しが拒否されることも確認 |

### PR 分割案

1. **PR-1（DDL＋読み取り専用の土台）**: `payer_user_id`/`handover_request_id` 列追加・`chk_bc_status` CHECK 6値化・`ContractStatus` enum への `PENDING_HANDOVER` 追加・バックフィル・`billing_payer_handover_requests` テーブル新設・`ActiveContractPointerRepository#hardDeleteBySlotAndContractId` 新設（AC-1, AC-2, AC-15, AC-14の土台）
2. **PR-2（BillingContractService拡張＋Gateway拡張）**: purge検出クエリ拡張（R2-P1-6の絞り込み条件含む）・引継要求/承諾API・状態機械（`PENDING_HANDOVER`含む）・`trial_end`方式での新サブスク作成・承諾確定時の旧サブスク`cancel_at_period_end`予約/差し戻し（R3-P1-3）・旧期末到達を条件とするローカル切替TX（Stripe API呼び出し無し）・PAST_DUE/過去期末の拒否分岐（R2-P1-4）・`pending_setup_intent`の二段検証（R3-P1-2）・Idempotency-Key/metadata対応とList Subscriptions照会（全ページ走査・R4-P1-1）のためのGateway拡張・DB+List優先のリトライ手順（AC-3〜12, AC-16, AC-22〜33の大半）
3. **PR-3（membership_subscriptions連携＋WithdrawalStripeHandler実装）**: `cancelAllForPayerOnWithdrawal`新設・`WithdrawalStripeHandler`実装（旧`TeamSubscriptionEntity`参照撤去）（AC-13）
4. **PR-4（着地済み・hardDeleteBySlotAndContractId移行＋夜次バッチの結線・MANUAL_INTERVENTION/RESUME）**: 旧webhookハンドラの呼び出し先切替・`SWITCHING`詰まり監視アラート・5分岐通知の実装・`cancel_at_period_end`夜次照合バッチと切替バッチの実行前チェック実装（R4-P1-2・AC-34/35）・期末境界越え検知と`MANUAL_INTERVENTION`状態および`RESUME`操作（運用者向けAPI/画面）の実装（R5-P1-1/2・AC-35〜37）（AC-14, AC-17〜21, AC-34〜37）

各PRはBEテスト先行（CLAUDE.md「BE/API はテスト先行」原則）。PR-1はDDLのみのためマイグレーション適用確認ITを先行させる。

#### PR-4 の実装対応（着地時点の正本）

| 設計上の項目 | 実装 |
|---|---|
| 切替バッチ（唯一の切替TX実行者・§3.6 (b)） | `BillingPayerHandoverBatchService#runPayerHandoverSwitch`（毎時 hh:10 JST・ShedLock `billing_payer_handover_switch`）。抽出は `SWITCHING` に加え **`PARTIALLY_COMPLETED`** も含む（§3.5・非終端のリトライ対象。含めないと pointer が旧のまま宙ぶらりんで残る）。`MANUAL_INTERVENTION` は含めない（運用者の `RESUME` 待ち） |
| 夜次照合バッチ（§3.6.1(a)・AC-34） | `BillingPayerHandoverBatchService#runPayerHandoverNightlyReconcile`（日次 02:40 JST）。①期限超過承諾の照合（§5.3）②`old_cancel_scheduled_at` 未確認行を **Stripe 実物と突合**。抽出を `SWITCHING`/`PARTIALLY_COMPLETED` に限るのは、`ACCEPTED` 段階での未設定は**正常**（旧への予約は引継確定と同時に行う設計）であり、含めると正常な進行中の行に対して毎晩 Stripe を叩き予約すべきでない旧サブスクを予約してしまうため |
| 期末解約の再試行バッチ（§6.1 (2)） | `MembershipPayerWithdrawalRetryBatchService#runWithdrawalCancelRetry`（日次 03:20 JST）。上記「リリース依存」節を参照 |
| `MANUAL_INTERVENTION` のアラート（§3.6.2） | `BillingPayerHandoverTxService#markManualIntervention` が通知（`MANUAL_INTERVENTION_REQUIRED`・当該スコープの引継先候補 ADMIN 宛・i18n 6言語）を publish し、あわせて ERROR ログで運用へ上申する |
| `RESUME`（§3.6.2 出口・AC-37） | `POST /api/v1/{teams\|organizations}/{id}/billing/payer-handover-requests/{handoverRequestId}/resume`。`target=SWITCHING\|FAILED` を運用者が明示的に選ぶ。`FAILED` 確定時の旧サブスク差し戻しは `revertOldCancelSchedule` で**運用者が選ぶ**（旧が既に次の期間へ更新済みの場合、差し戻しは旧をさらに継続させるため不適切なことがある）。`MANUAL_INTERVENTION` 以外からは `HANDOVER_NOT_RESUMABLE`（409） |
| `SWITCHING` 詰まり監視／追加認証の期限（§5.5 ④・AC-20） | `BillingPayerHandoverBatchService` の夜次照合に `reconcileStalledSwitching` を追加。承諾確定（`accepted_at`）から24時間を過ぎた `SWITCHING` を抽出し、**Stripe 実物の `pending_setup_intent` を再検証**して未解決なら `FAILED` を確定し、新 trial サブスクを無課金取消・旧を差し戻したうえで**他の候補 ADMIN へ再通知**する。旧期末到達まで待つと、そのとき旧サブスクは既に終了していて差し戻しても継続を復旧できないため、**期末より前に決着させる**必要がある |
| 恒久失敗の `MANUAL_INTERVENTION` 化（§3.6.2 入口3） | `reconcileOldCancelSchedule` が設定 API の失敗を捕捉し、承諾確定から `CANCEL_SCHEDULE_ESCALATION`（3日）を過ぎても解消しない場合に `MANUAL_INTERVENTION` へ倒す。試行回数の列を足さずに**経過時間**で測るのは、恒久性の証拠として「何回叩いたか」より「いつまで解消しないか」のほうが確実であるため（単一要求行の経過時間であり、退会世代の推測ではない） |
| 状態遷移の CAS 化 | `executeSwitchTx` / `markPartiallyCompleted` / `markManualIntervention` / `markFailedAndClearCancelSchedule` / `failStalledSwitchingAndRenotify` の全てが**行ロック取得後に期待元状態を再検証**する。`loadSwitchContext` 〜 Stripe 照会の間は行ロックが無いため、並行実行の失敗補償が**終端状態（`COMPLETED`）を非終端へ引き戻す**経路が実在した。ShedLock は `lockAtMostFor` 超過や webhook 等の他経路との競合に対する fencing にならない |
| 失敗の恒久/一時分類（§3.6.2 入口3） | Stripe の HTTP ステータスで分類する（`4xx`（`429` 除く）＝恒久で即 `MANUAL_INTERVENTION`、`429`/`5xx`/接続断＝一時で再試行）。**時間（`CANCEL_SCHEDULE_ESCALATION`＝3日）は分類の根拠ではなく、分類が付かない一時失敗が続いた場合の上限**として併用する。分類を可能にするため `StripePaymentProviderImpl` の該当3メソッドが `StripeException` を cause として保持するよう是正した（握り潰すと呼び出し側は代理指標で推測するしかない） |
| 状態変更の CAS（呼び出し元ごとの期待元状態） | `markFailedAndClearCancelSchedule` は**期待元状態を引数で受ける**。切替バッチの失敗補償は `SWITCHING`/`PARTIALLY_COMPLETED`、`RESUME→FAILED` は `MANUAL_INTERVENTION` のみ。「終端でなければ何でも可」は CAS ではなく、古いスナップショットを持つ worker が運用者の `MANUAL_INTERVENTION` を握り潰せてしまう |
| Stripe 変更と CAS の順序 | **CAS で権利を取ってから Stripe を変更する**（取れなければ Stripe に触らない）。逆順だと、Stripe 照会中に別 worker が `COMPLETED` へ進めた場合に「DB は `COMPLETED`・Stripe は新サブスク取消済み」という乖離が残り、pointer が指す新契約と Stripe 実物が矛盾する |
| 滞留抽出のキーセット送り | 滞留抽出は**処理しても状態が変わらない行**（認証完了済みで旧期末待ちの正常な `SWITCHING`）を返しうる唯一の照合であり、固定の先頭 N 件で切ると後続の認証未解決行が永久に検査されない。`accepted_at` を carry して前へ進む。他の3照合は処理すると必ず状態が動いて集合から抜けるため先頭から詰めれば足りる |
| 旧期末までの猶予の要件化 | 引継要求の作成時に**旧期末まで `PENDING_SETUP_INTENT_DEADLINE + PERIOD_END_SAFETY_MARGIN`（30時間）以上**を要求する。加えて滞留抽出は「承諾+24時間」**または**「旧期末が 6 時間以内に迫っている」で拾う。旧期末を過ぎると `cancel_at_period_end=false` を送っても終了済みサブスクは復旧できず、「期末より前に `FAILED` として差し戻す」という安全条件が原理的に成立しないため |
| **`FAILING_CLEANUP`（非終端・V206）** | 失敗確定を「決めた瞬間」と「Stripe の後始末が終わった瞬間」に分ける。CAS で `FAILING_CLEANUP` を取り、後始末の成功を確認してから `FAILED` へ終端化する。この状態は終端3値に含まれないため、生成列 `open_old_contract_id` が値を保持し、**`uk_bphr_open_old_contract` が通常の作成入口も含めて同一契約への新規要求を物理的に拒む**（＝旧試行のサブスクが Stripe に残ったまま別 ADMIN の承諾が進む二重サブスクを構造的に防ぐ）。同時に非終端なので夜次バッチが必ず回収できる。目印列の有無で表していた前案は、目印を消した瞬間に「回収不能」と「UNIQUE 枠の解放」が同時に起きる欠陥があった |
| 終端化と再要求は同一 TX | `finalizeFailure` が「`FAILED` 化・`old_cancel_scheduled_at` クリア・新契約の無効化・AC-20 の再要求と通知」を1つの TX で確定する。分けると、その間の停止で「元要求は `FAILED`・再要求は無し」が残り、**終端は抽出対象外なので夜次バッチからも永久に見えない**。1 TX なら失敗しても `FAILING_CLEANUP` のまま残り次回が拾い直す |
| 再要求は共通の作成要件で判定 | 再要求の可否は通常の作成入口と同じ要件（旧契約が今も引継可能か・**旧期末までの猶予**・候補 ADMIN の存在）だけで決める。**「旧 payer が退会申請中か」は要件にしない**——通常の `requestHandover` が退会を要件にしていない以上、これを課すと**対話 API から始めた正当な引継**で追加認証が失敗したときに他 ADMIN が居ても再要求が作られなくなる |
| 失敗確定の3段構え（CAS → Stripe → 完了記録） | `FAILED` へ倒す全経路（切替の `pending_setup_intent` 未解決・`RESUME→FAILED`・滞留照合）が**同一の順序**を通る。①`markFailedPendingCleanup` が期待元状態つき CAS で権利を取る（取れなければ Stripe に触らない）②Stripe の後始末 ③`finishFailureCleanup` が `old_cancel_scheduled_at` を NULL クリア。②が落ちるとこの列が残るため、**「`FAILED` なのに残っている」が後始末未了の証跡**になり、夜次バッチ（`findFailedWithPendingCleanupIds`）が必ず回収する。新しい状態も列も増やさずに回収可能性を確保している |
| AC-20 の再要求は後始末の後 | 再要求（`renotifyWithFreshRequest`）は**後始末が完了してから**作る。先に作ると、後始末が落ちた場合に「旧試行のサブスクが Stripe に残ったまま、別 ADMIN が新しい承諾を進められる」＝**二重サブスク**の窓が開く。再要求は共通の作成要件（旧 payer が今も退会申請中か・旧契約が今も引継可能か・旧期末までの猶予・候補 ADMIN の存在）を**Tx 層で再検証**し、満たさなければ作らない（元要求は `FAILED` のままなので生成列の枠が空き、purge の期末解約フォールバックへ渡る） |
| 承諾時の期末猶予の再検証 | 要求は14日間有効なので、作成時の検証だけでは足りない（作成時31時間の契約でも24時間後に承諾すれば残り7時間）。`expires_at` しか見ないと**旧期末を過ぎていても承諾できて**しまい、期末後の差し戻しは終了済みサブスクを復旧できない。承諾は Stripe に新サブスクを作る不可逆な一歩なので、その直前に旧期末の猶予を再検証する |
| 滞留抽出の複合カーソルと正常行の除外 | `(accepted_at, id)` の複合カーソルで進む（`accepted_at` 単独では同一時刻行がページ境界で全て脱落する）。あわせて **V205 の `setup_intent_verified_at`** を追加し、認証完了を確認した行を抽出から外す。認証完了行は処理しても状態が変わらないため、除外しないと実行件数の上限を埋めて**認証未解決行を永久に飢餓させる**（カーソルは実行のたびに初期化されるため、上限に達する限りその先へ到達しない） |
| AC-14（`hardDeleteBySlotAndContractId` 移行） | `BillingContractService#expireSubscriptionContract`（旧サブスク由来の `customer.subscription.deleted` webhook 経路）を `contract_id` 一致条件つき削除へ移行。切替TX後に遅着した旧 webhook は 0 件更新で終わる |

---

## §9. 未決事項（実装フェーズで確定させる・変更なし）

- **`RESUME` の運用権限（PR-4 Codex 検分1巡目 P1-5・未決）**: §3.6.2 は「Stripe 実データを確認できる運用チームが `RESUME` 権限を持つ」ことを前提にしているが、本リポジトリには**テナント横断の運用権限という型が存在しない**。`BillingAccessGuard` が扱うのは当該スコープの ADMIN と課金権限付き DEPUTY_ADMIN だけであり、`AccessGuard` の `SYSTEM_ADMIN` は「常に通す」短絡であって専用権限ではない。現状の PR-4 は当該スコープの ADMIN のみに `RESUME` を許しているため、**Stripe の void/refund を確認した運用担当者自身は決着させられない**。選択肢は (a) 専用の運用 permission を新設し監査ログと対象スコープ確認を伴わせる、(b) 管理コンソール側の別 API として切り出す、(c) 当面テナント ADMIN のみとし運用手順で補う、の3案。**権限体系の新設は本設計の射程を越えるため、殿の判断を仰ぐ**（この決着までは (c) の状態である）。

- 通知基盤の具体的な実装クラスは実装フェーズで家老が偵察して決定する
- 猶予期間14日は暫定値。マスターの最終承認時に法務・UX観点で調整余地あり
- `membership_subscriptions` の受益者向け「引継UI」は本設計ではスコープ外とし通知のみ実装する
- `trial_end` を無課金期間の構成に転用する方式は、本サービスが将来トライアル機能自体を提供する場合と概念上の衝突が起きないか、実装フェーズでStripe側の「trialing状態の多重利用」に問題がないか改めてテストモードで実証する（設計としては別物だが、Stripe側の課金モデル上は同じフィールドを使うため要実証）
