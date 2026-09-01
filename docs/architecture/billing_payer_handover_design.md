# 柱③-B 組織契約の請求担当と個人支払手段の分離 設計書

> 起票日: 2026-09-02（Codex 検分1巡目 P0×4/P1×8/P2×3 差し戻しを受け 2026-09-02 改訂）
> 担当: 足軽（本設計書は Codex 検分の要求事項を踏まえた仕様固め）
> ステータス: 🟡 設計段階（レビュー待ち・検分2巡目待ち）
> 課題管理: CMP-260901-1538
> 参照: [`account_purge_last_admin_succession.md`](./account_purge_last_admin_succession.md) / [`withdrawal_flow_immediate_anonymization_fix.md`](./withdrawal_flow_immediate_anonymization_fix.md) / [`domain_db_design_principles.md`](./domain_db_design_principles.md)

---

## §0. 検分対応表（Codex 検分1巡目・全15件）

指摘全文は本 PR には貼らない。採否と対処のみを記す。○=採用・改訂反映済み、△=方向づけを一部調整して採用。

| # | 重大度 | 論点要約 | 採否 | 対処（反映章） |
|---|---|---|---|---|
| P0-1 | P0 | 置換方式では新旧サブスクの併存期間に二重課金が起きる | ○ | 新サブスクは `trial_end=旧 current_period_end` で作成し、旧期末まで無課金にする方式へ変更（§2.3） |
| P0-2 | P0 | Idempotency-Key は24hで失効し一次防衛にならない | ○ | 一次防衛を DB（`psp_new_subscription_ref` 永続化）＋ Stripe metadata 検索へ移す。Key は補助（§3.2〜3.4） |
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
| 旧サブスクの扱い | 新サブスクの trial 終了・初回請求成功（`invoice.paid`）を確認できた時点で、旧サブスクを即座に `cancelImmediately`（既存 `StripeBillingPaymentGateway#cancelImmediately`）で止める。旧は trial 終了と同時刻に終わるため、期末解約(`cancelAtPeriodEnd`)ではなく即時解約で良い（旧の期末はもう来ている） |
| trial 中に新 payer が離脱した場合 | `trial_end` 到達前に新サブスクを `cancelImmediately` すれば無課金のまま取消可能（課金が一切発生していないため返金処理不要） |
| PaymentMethod 未設定時の挙動 | trial 終了時に決済手段が無いと `customer.subscription.deleted`（Stripe既定の `missing_payment_method` 終了時動作が `cancel` の場合）または `past_due` に陥る。**そのため ACCEPTED→SWITCHING 遷移の前に新 payer の PaymentMethod 有無を検証する（§3.6・P1-6/11 対応）** |
| クーポン/割引・税 | 従来どおり引き継がない（新規指定が必要。本サービスは現状未提供のため対象外、§2.4 に維持） |

### 2.4 proration・クーポン・税の扱い（変更なし部分を維持）

| 論点 | 確定事項 |
|---|---|
| クーポン/割引の引継 | 引き継がない。本サービスは現状クーポン機能未提供のため対象外 |
| 税の引継 | 引き継がない。新サブスク作成時に `automatic_tax` を新規指定する（対応時） |
| trial（本来のプラン上のトライアル） | 本サービスの TEAM/ORG プランはトライアル未提供（実コード確認済み）。§2.3 の `trial_end` は「無課金期間を作る手段」として転用しているものであり、プラン上のトライアル機能とは無関係 |

---

## §3. 冪等性と失敗回復（P0-2〜4・P1-5〜7・P1-11・P2-14 対応）

### 3.1 状態機械（PENDING_HANDOVER 追加・P0-4 対応）

```
REQUESTED（要求）
   │ 対象スコープの他 ADMIN が承諾 + 新 payer の PaymentMethod 存在確認（§3.6）
   ▼
ACCEPTED（承諾）
   │ 新サブスク作成 API 呼び出し開始
   │ 新 billing_contracts 行を PENDING_HANDOVER 状態で作成（pointer は持たない）
   ▼
SWITCHING（切替中）
   │ 新サブスクの trial 終了・初回請求成功（invoice.paid, §3.6）を確認
   │ 切替TX: pointer を旧contractから新contractへ付け替え + 旧contract を CANCELLED 化 + 新contract を ACTIVE 化
   │ 旧サブスクを cancelImmediately
   ▼
COMPLETED（完了） ─┬─ 新規作成/trial失敗 → FAILED（新contractはPENDING_HANDOVERのまま無効化、pointerは旧に残り続けるため旧契約は無傷）
                    └─ 新は成功したが旧解約 or 切替TXの一部が失敗 → PARTIALLY_COMPLETED（§3.5 補償対象）
```

**pointer 一意制約との整合（P0-4 根治）**: `active_contract_pointers.uk_acp_slot`（スロット単位 UNIQUE）は「1スロットに1 pointer」を保証する制約であり、**新旧2契約が同時に pointer を持とうとすると衝突する**。そこで新契約は `PENDING_HANDOVER` の間 pointer を作らず、切替TXで「旧pointerを物理DELETE→新pointerをINSERT」を同一トランザクション内で行う（§3.7 の `hardDeleteBySlot` 修正と合わせ、旧pointerの削除条件を `contract_id` 一致に絞ることで、切替TXの外側で発生する旧webhookが誤って新pointerを消さないようにする）。

### 3.2 Idempotency-Key の限界と一次防衛の移設（P0-2 根治）

Stripe 公式ドキュメント（Idempotent requests）で確認した仕様: Idempotency-Key は**最短24時間で失効**し、失効後の再利用は新規リクエストとして扱われる。**24時間を跨ぐ再試行では Idempotency-Key だけでは二重作成を防げない。**

→ **一次防衛を DB 側に移す。** Idempotency-Key は「同一プロセス内・短時間の再送」に対する補助防御として残すが、正の防衛線は以下:

1. `billing_payer_handover_requests.psp_new_subscription_ref` を Stripe 新サブスク作成 API が成功した**直後・同一トランザクション**で永続化する
2. 新サブスク作成の全呼び出し（初回・リトライとも）で `metadata.handoverRequestId = {handoverRequestId}` を**必ず**指定する（Stripe Subscriptions Search API `metadata["handoverRequestId"]:"{id}"` で後から引ける状態にするため）
3. 新サブスク作成を試みる前に、必ず次の順で確認する:
   1. DB の `psp_new_subscription_ref` が既に埋まっているか確認 → 埋まっていれば作成をスキップしてそのサブスクを使う
   2. 埋まっていなければ Stripe Subscriptions Search API を `metadata["handoverRequestId"]:"{id}"` で検索 → ヒットすれば「Stripe には作成済みだが DB 反映前に落ちた」ケースと判定し、そのサブスク ID を DB に書き戻してから続行（二重作成回避）
   3. どちらも見つからない場合のみ新規作成 API を呼ぶ（このときのみ Idempotency-Key `billing-handover-create-{handoverRequestId}` を付与）

**Search API の鮮度に関する既知の制約（要注意・実装ITで踏まえる）**: Stripe公式ドキュメントは「通常運用下でも検索可能になるまで最大1分程度かかる場合があり、read-after-write（書き込み直後の読み取り）フローでの利用は推奨しない」と明記している。したがって手順3-2の Search 照会は「直前の呼び出し失敗から一定時間（最低60秒）経過した再試行」でのみ信頼できる。**60秒未満の即時リトライでは Search に頼らず、まず Idempotency-Key による同一リクエスト再送（Stripe側が最初の結果をそのまま返す）で吸収し、Search はそれより長い間隔の再試行（夜次バッチ等）でのみ二重作成検出の手段として使う。**

4. **現行 `StripePaymentProvider`/`StripeBillingPaymentGateway` は Idempotency-Key 未対応（実コード確認: `createBillingSubscriptionCheckoutSession` 等の呼び出しにキー引数が無い）。実装 PR で Gateway 層に Idempotency-Key 引数と `metadata` 引数を追加する拡張が必須。本設計書はその拡張を PR-2 のスコープに含める（§8）**

### 3.3 相関 ID の永続化

`billing_payer_handover_requests` に `old_contract_id`／`new_contract_id`／`psp_new_subscription_ref` を持つ（§4.2）。新契約の `billing_contracts` 行自体も `PENDING_HANDOVER` の間から作成しておき、`handover_request_id` 列（新設、§4.2）で相互参照する。

### 3.4 Idempotency-Key の単位（P1-7 対応・既存キーとの棲み分け明記）

| 操作 | Idempotency-Key | 既存キーとの関係 |
|---|---|---|
| 新サブスク作成 | `billing-handover-create-{handoverRequestId}` | 新設。§3.2 のとおり一次防衛は DB+Search、本キーは短時間再送の補助 |
| handover 経由の旧サブスク解約 | `billing-handover-cancel-{handoverRequestId}` | 新設。**通常の（引継を伴わない）解約が使う既存キー `"billing-cancel-" + subscriptionRef`（`StripeBillingPaymentGateway#cancelAtPeriodEnd` 実装）とは別名前空間にする**ことで、同一 `subscriptionRef` に対し「通常解約」と「引継による解約」が同時に走っても Idempotency-Key の衝突（パラメータ不一致エラー）を起こさない |

### 3.5 補償（変更なし・維持）

- 切替TXの一部（pointer 付替え or 旧解約）が失敗した場合、状態を `PARTIALLY_COMPLETED` にし、未完了の操作を夜次バッチのリトライ対象にする（既存 `findPurgedPaidSubscriptionRefsPendingStripeCancel` と同型のクエリを新設）
- 新契約は既に ACTIVE のため利用者影響は無く、`PARTIALLY_COMPLETED` は緊急度低

### 3.6 成功条件の一本化と PaymentMethod 事前検証（P1-5・P1-6・P1-11 対応）

**成功条件は `checkout.session.completed`（または直接 Subscription API 経由の場合は Subscription 作成レスポンスの `status` 確認）を正とする。** `invoice.paid`／`customer.subscription.updated` 等の webhook は「正が確定した後の状態同期の冪等な補強」と位置づけ、それ単独では切替TXのトリガーにしない（P1-5）。

**遷移表（PAST_DUE 旧契約・3DS・PaymentMethod 無し）**:

| ケース | 判定タイミング | 挙動 |
|---|---|---|
| 旧契約が既に `PAST_DUE` | ACCEPTED 承諾時点でチェック | 引継自体は許可する（PAST_DUE こそ引継の動機になり得るため）。ただし §2.3 の `trial_end` は旧契約 DB 上の `current_period_end` を使うため、PAST_DUE で滞留している期間があっても「DB に記録された period_end」を基準にする |
| 新 payer に有効な PaymentMethod が無い | **ACCEPTED→SWITCHING 遷移の直前に必須検証**（P1-6/11） | 検証失敗なら承諾自体を差し戻し、状態は `ACCEPTED` に留めず `REQUIRES_PAYMENT_METHOD`（新設の中間状態）へ落とす。旧契約は無傷のまま。新 payer にカード登録を促す通知を送る |
| 新サブスクの trial 終了時の決済が 3DS 要求（`requires_action`） | trial 終了処理（Stripe側自動） | Stripe 標準の SCA フロー（`invoice.payment_action_required` webhook）に従い、新 payer に認証操作を促す。認証完了までは新契約は `PENDING_HANDOVER` のまま pointer 未付替え・旧契約も無傷。認証失敗が一定時間継続した場合は §5.5 の「承諾後認証失敗」分岐で `FAILED` に倒す |
| 新サブスクが `trialing` のまま Stripe 側で `canceled`（何らかの理由） | webhook `customer.subscription.deleted` | 状態を `FAILED` に落とし、`old_contract_id` は無傷のまま。ADMIN 通知 |

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
```

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
    status VARCHAR(24) NOT NULL COMMENT 'REQUESTED/ACCEPTED/REQUIRES_PAYMENT_METHOD/SWITCHING/COMPLETED/PARTIALLY_COMPLETED/FAILED/EXPIRED',
    -- 生成列: 終端状態（COMPLETED/FAILED/EXPIRED）以外のときだけ old_contract_id を値として持つ。
    -- 終端状態では NULL になるため UNIQUE 制約に抵触せず、同一契約への再要求（前回終了後）を許可する。
    open_old_contract_id BINARY(16) GENERATED ALWAYS AS (
        CASE WHEN status IN ('COMPLETED', 'FAILED', 'EXPIRED') THEN NULL ELSE old_contract_id END
    ) STORED COMMENT '村の現役所属重複防止と同型: 進行中(非終端)の要求のみ値を持つ生成列',
    requested_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL COMMENT '既定 requested_at + 14日。期限内未引継は期末解約へフォールバック（§5.4）',
    accepted_at DATETIME NULL,
    completed_at DATETIME NULL,
    psp_new_subscription_ref VARCHAR(64) NULL COMMENT 'P0-2: 新サブスク作成成功時点で永続化する一次防衛の要',
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
| `SWITCHING` | 新サブスク作成済み・trial中または切替TX実行中 | `ACCEPTED` | `COMPLETED` / `PARTIALLY_COMPLETED` / `FAILED` |
| `COMPLETED` | 切替完了（終端） | `SWITCHING` | — |
| `PARTIALLY_COMPLETED` | 新は成功・旧解約またはpointer付替えが未完了（終端扱い、リトライ対象） | `SWITCHING` | `COMPLETED`（リトライ成功時） |
| `FAILED` | 失敗（終端。旧契約は無傷） | 各状態 | — |
| `EXPIRED` | 期限切れ（終端） | `REQUESTED` / `REQUIRES_PAYMENT_METHOD` | — |

`scope_kind` は `TEAM`/`ORG` のみ許容（`USER` はアプリ層で作成要求自体を拒否する。USER スコープは契約者本人以外に payer が存在し得ないため引継の概念自体が無い）。

### 4.3 Flyway 方針（変更なし）

- 命名: `V{major}.{yyyyUTCタイムスタンプ}__add_billing_payer_handover.sql`（既存規約どおり）
- major 番号は実装 PR 時点で `origin/main` の最大 major+1 を採番
- `ALTER TABLE billing_contracts` と `CREATE TABLE billing_payer_handover_requests` は別ファイルに分割

---

## §5. purge 連携（P1-9・P1-10 反映）

### 5.1 検出（変更なし）

`WithdrawalRequestedEvent`（Day 0・退会受付時点）で、退会予定ユーザーが `payer_user_id` として紐づく TEAM/ORG `billing_contracts`（`status IN (PENDING, ACTIVE, PAST_DUE)`）を検出する。

### 5.2 他 ADMIN への引継要求通知（変更なし）

対象スコープの他 ADMIN 全員に通知。文言には §2.3 の「請求日は変わらない（旧期末＝新開始のため）」ことを明記できる点が、旧設計（置換方式単純版）からの改善点として案内文言に含められる。

### 5.3 猶予期限（変更なし）

`requested_at` から14日。期限内に `ACCEPTED` にならなければ `EXPIRED` とし、purge バッチ実行時点で該当契約を期末解約（`cancelAtPeriodEnd`）に倒す。

### 5.4 purge×handover の相互条件表（P1-9 新設）

**原則: handover が `REQUESTED`/`ACCEPTED`/`REQUIRES_PAYMENT_METHOD`/`SWITCHING`（＝非終端）の間は、purge 側の期末解約フォールバックを発火させない。handover の期限切れ（`EXPIRED`）または失敗（`FAILED`）が先に確定してから、purge の fallback 判定に処理を渡す。**

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
| AC-6 | 旧サブスクの `cancelImmediately` は新サブスクの初回請求成功（`invoice.paid`）確認後にのみ呼ばれる | (a) | IT: 新サブスク作成失敗をモックし、旧が解約されないことを確認 |
| AC-7 | 同一 `handoverRequestId` で新サブスク作成を複数回試行しても Stripe 側には1つのサブスクしか作られない（DB確認→Search確認→新規作成の順序、Idempotency-Keyは補助） | (a)(d) | IT: DB書き込み失敗を模擬したリトライで二重作成されないことを確認 |
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
| AC-24 | 競合: 切替TX実行中に旧サブスクの解約処理が二重に起動されても、Idempotency-Key（`billing-handover-cancel-*`）により実害が出ない | (d) | IT |
| AC-25 | 競合: Stripe新サブスク作成が成功した直後にDBトランザクションがロールバックしても、次回リトライでSearch APIから回収できる（60秒以上の間隔を空けたリトライで検証） | (d) | IT |
| AC-26 | 競合: webhook処理と同期処理（切替TX）が同時に同じhandover行を更新しようとしても、行ロックにより一方が待たされ不整合が生じない | (d) | IT |

### PR 分割案

1. **PR-1（DDL＋読み取り専用の土台）**: `payer_user_id`/`handover_request_id` 列追加・バックフィル・`billing_payer_handover_requests` テーブル新設・`ActiveContractPointerRepository#hardDeleteBySlotAndContractId` 新設（AC-1, AC-2, AC-15, AC-14の土台）
2. **PR-2（BillingContractService拡張＋Gateway拡張）**: purge検出クエリ拡張・引継要求/承諾API・状態機械（`PENDING_HANDOVER`含む）・`trial_end`方式での新サブスク作成・Idempotency-Key/metadata対応のためのGateway拡張・DB+Search優先のリトライ手順（AC-3〜12, AC-16, AC-22〜26の大半）
3. **PR-3（membership_subscriptions連携＋WithdrawalStripeHandler実装）**: `cancelAllForPayerOnWithdrawal`新設・`WithdrawalStripeHandler`実装（旧`TeamSubscriptionEntity`参照撤去）（AC-13）
4. **PR-4（hardDeleteBySlotAndContractId移行＋webhook順序耐性の仕上げ・監視）**: 旧webhookハンドラの呼び出し先切替・`SWITCHING`詰まり監視アラート・5分岐通知の実装（AC-14, AC-17〜21）

各PRはBEテスト先行（CLAUDE.md「BE/API はテスト先行」原則）。PR-1はDDLのみのためマイグレーション適用確認ITを先行させる。

---

## §9. 未決事項（実装フェーズで確定させる・変更なし）

- 通知基盤の具体的な実装クラスは実装フェーズで家老が偵察して決定する
- 猶予期間14日は暫定値。マスターの最終承認時に法務・UX観点で調整余地あり
- `membership_subscriptions` の受益者向け「引継UI」は本設計ではスコープ外とし通知のみ実装する
- `trial_end` を無課金期間の構成に転用する方式は、本サービスが将来トライアル機能自体を提供する場合と概念上の衝突が起きないか、実装フェーズでStripe側の「trialing状態の多重利用」に問題がないか改めてテストモードで実証する（設計としては別物だが、Stripe側の課金モデル上は同じフィールドを使うため要実証）
