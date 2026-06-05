# F08.9 P5 §11-3 Stripe PoC — invoice 固定手数料 × destination charge

継続課金（P5）の前提検証。**検証命題**:

> **Subscription（destination charge: `transfer_data[destination]` + `on_behalf_of`）の更新サイクル invoice に対し、`invoice.created` の draft 窓で `application_fee_amount` を「固定値」に上書きできるか。**
> 固定値は fee_policy（percent × face の round ＋ flat）で算出した額。不成立なら P5 は自前バッチへ退避。

机上精査（公式 docs.stripe.com）の結論は **条件付き成立**。詳細は本書末尾の「机上精査の結論」を参照。本スクリプトはキー投入後にその成立を実 API で実証するためのもの。

---

## 1. 前提・依存

| 項目 | 内容 |
|---|---|
| 実行環境 | Windows **Git Bash**（bash + curl + jq）/ WSL / macOS いずれも可 |
| 必須コマンド | `bash` `curl` `jq`（jq は Git Bash に同梱されないため [別途インストール](https://jqlang.github.io/jq/) が必要）|
| Stripe キー | **test キーのみ**（`sk_test_...` / `rk_test_...`）。本番キーは起動時にブロックされる |
| モード | test mode 専用。**Test Clock** で更新サイクルを早送りする |
| 通貨 | JPY 月額 ¥1,000、connected account は JP |

スクリプトは `STRIPE_SECRET_KEY` を**環境変数からのみ**受け取り、echo / ログ / ファイルへ一切書き出さない。test キー以外（`sk_live_` 等）は即エラー終了。

---

## 2. 実行手順（キー投入後）

```bash
# 1. test キーを環境変数に置く（履歴に残したくない場合は先頭にスペース or read -s 推奨）
export STRIPE_SECRET_KEY='sk_test_'<あなたのテストキー>   # 例: sk_test_ で始まる test mode の Secret key

# 2. 実行（本陣でなく worktree から、もしくは clone 先どこでも可）
bash scripts/poc/f089_p5_invoice_fee_override_poc.sh
```

- **所要時間**: 約 1〜3 分（test clock の advance が非同期で数秒×2回 + ポーリング）。
- **出力**: 各 API レスポンス JSON は `scripts/poc/out/run_<日時>/` に保存（`.gitignore` 済み）。標準出力末尾に **PASS/FAIL サマリ表**。
- **後始末**: スクリプト末尾で **test clock を DELETE** すると、紐づく customer / subscription / invoice が**連鎖削除**される。connected account のみ clock 配下でないため個別に DELETE する（スクリプトが自動実行）。手動で残骸を消したい場合は Stripe ダッシュボード（test mode）→ Customers / Connect で確認。

### 検証で使う「判別用」固定値

face = ¥1,000 の 5% = 50 とは**敢えてズラした素数的な `53`** を `application_fee_amount` に投入する。
最終 charge の `application_fee_amount` が **ピッタリ 53** で返れば、「percent 自動計算ではなく fee_policy 算出の固定値がそのまま通った」ことが一意に判定できる。

---

## 3. スクリプトの流れと期待結果

| # | ステップ | 期待 |
|---|---|---|
| 1 | test clock 作成 → clock 付き customer → テスト PM（`tok_visa`）attach + default | 成功 |
| 2 | Connect カスタムテスト口座作成（JP・magic 住所 `address_full_match` 等）→ capability 確認 | `charges_enabled=true` / `transfers` active（JP は US magic 値と差異あり得るので INFO 許容）|
| 3 | product / price（月額 ¥1,000）作成 | 成功 |
| 4 | subscription 作成（`transfer_data[destination]` + `on_behalf_of` + `default_payment_method` + `collection_method=charge_automatically`、安全側で `application_fee_percent=5` 併設）| `status=active` |
| 5 | **初回 invoice の観察**（`billing_reason=subscription_create`）+ 固定値上書き試行 | **上書き不可（HTTP 4xx）を PASS として記録** — 初回は即 finalize で窓無し |
| 6 | test clock を +32 日 advance → `subscription_cycle` の **draft** invoice をポーリング取得 | draft invoice 検出 |
| 7 | **draft invoice に `application_fee_amount=53` を update** | **HTTP 2xx で fee=53 に上書き（★命題の核心）** |
| 8 | invoice finalize → pay → 最終 charge の `application_fee_amount` / `transfer.amount` を assert | `charge.application_fee_amount=53` かつ `transfer.amount = amount - 53` |
| 9 | （オプション）`pause_collection[behavior]=void` → さらに +32 日 advance | void 月は invoice が voided / 未課金（§4.5 スキップ設計と整合）|
| 10 | 後始末（clock + account 削除）+ PASS/FAIL サマリ表 | — |

命題の核心（#7 上書き + #8 charge 伝播）が FAIL なら、スクリプトは**非ゼロ終了**する。

---

## 4. 机上精査の結論（公式ドキュメント・出典 URL 付き）

### 総合判定: **条件付き成立**

更新サイクル invoice については **成立見込み（高）**。ただし**初回 invoice に上書き窓が無い**という明確な罠があり、回避策の併用が必須。

### 4.1 Invoice Update API は `application_fee_amount` を draft で更新可

- `application_fee_amount`（integer・最小通貨単位）は **draft invoice でのみ更新可能**。finalize 後は monetary value として**変更不可**。
- 出典: [Update an invoice | Stripe API](https://docs.stripe.com/api/invoices/update) — 「Draft invoices are fully editable. Once an invoice is finalized, monetary values … become uneditable.」

### 4.2 Subscription × destination charge と fee の優先関係

- Subscription に `transfer_data[destination]`・`on_behalf_of`・`application_fee_percent` を同時指定できる。
- **invoice に直接設定した `application_fee_amount` は、`application_fee_percent` の自動計算額を「すべて上書き」し、invoice 最終額を上限とする。** → 固定手数料を入れたいなら invoice-level `application_fee_amount` が正攻法。
- 変動・固定手数料の推奨実装は **`invoice.created` を listen する webhook で `application_fee_amount` を都度セット**（Stripe 公式が明記）。
- 出典: [Create subscriptions with Stripe Billing | Stripe](https://docs.stripe.com/connect/subscriptions) / [Collect application fees | Stripe](https://docs.stripe.com/connect/marketplace/tasks/app-fees)

### 4.3 `invoice.created` の draft 窓（~1時間 / auto_advance）

- API/サブスク更新で作られた invoice は最初 **`status=draft`** で生成され、`invoice.created` を webhook 通知。**draft の間のみ編集可能**。
- Stripe は全 webhook の成功応答後 **約1時間待って** finalize → 課金を試みる。この間が編集窓。
- 出典: [Automatic invoice advancement | Stripe](https://docs.stripe.com/invoicing/integration/automatic-advancement-collection) / [Using webhooks with subscriptions | Stripe](https://docs.stripe.com/billing/subscriptions/webhooks)

### 4.4 ★初回 invoice の罠（最重要）

- **`collection_method=charge_automatically` の subscription の初回 invoice（`billing_reason=subscription_create`）は即 finalize・即課金される。初回 invoice は finalize 前に更新できない。更新できるのは 2 回目以降（更新サイクル）の invoice。**
- 出典: [Subscription invoices | Stripe](https://docs.stripe.com/billing/invoices/subscription)（"first invoice … finalized and charged immediately … can't update the first invoice before it's finalized, but you can update subsequent invoices"）/ [Using webhooks with subscriptions](https://docs.stripe.com/billing/subscriptions/webhooks)（同期課金は遅延しない旨）

#### 回避策の比較（初回 invoice 対策 a / b / c）

| 案 | 内容 | 長所 | 短所 / 整合性 |
|---|---|---|---|
| **a) `application_fee_percent` 安全側併設** | subscription-level に percent を既定で付け、初回はそれで徴収。2回目以降は invoice webhook で `application_fee_amount` に上書き | 実装最小・全サイクルで何らかの手数料は必ず徴収される | **初回のみ percent 計算**となり fee_policy の **flat 分・round 差が初回だけズレる**。member_payments / escrow 起票額と Stripe 実徴収額が初回だけ不一致 → 起票時に「初回は percent 概算」と明示するか調整仕訳が必要 |
| **b) 初回は P1 同型の単発 destination charge + Subscription を次サイクル開始** | 初回会費は **P1 の単発 destination charge（PaymentIntent に `application_fee_amount` 固定）**で徴収。Subscription は `trial_period_days` または `billing_cycle_anchor` で**次サイクルから**起動 → **全 invoice が更新型（subscription_cycle）= 全サイクル draft 窓で固定値上書き可** | **全サイクルで fee_policy 固定値が正確に通る**（初回含め誤差ゼロ）。escrow 起票・価格固定・member_payments と完全整合。P1 実装資産を再利用 | 初回と継続で経路が2系統になり実装がやや増える。trial 中は invoice 0円 or 発行されない点の UX 整理が必要 |
| **c) trial_period_days=0 ではなく短期 trial + webhook** | trial を挟んで初回 invoice 自体を trial 終了時に回す（実質 b の Subscription 単独版）| 経路が1系統 | trial 終了時の最初の課金 invoice が `subscription_cycle` 扱いになるかは要実機確認。trial→active の最初の invoice の billing_reason / 即時 finalize 有無が論点 |

**設計視点の推奨: 案 b（初回=P1単発、継続=Subscription更新サイクル）。** fee_policy の round＋flat 固定値が**全サイクルで正確に**通り、escrow を AUTHORIZED 起票 → CAPTURED で複式記帳する P1/P2 の会計整合（[[feedback]] 即時 escrow は AUTHORIZED 起票）をそのまま継続課金へ延長できる。案 a は実装は軽いが初回のみ会計不一致が出るため、価格固定（face 厳守）を謳う F08.9 のポリシーと相性が悪い。本 PoC スクリプトは **案 a を安全側で併設**しつつ（subscription に `application_fee_percent=5`）、**更新サイクルでの固定値上書き（命題の核心）と初回の上書き不可**を同時に実証する構成にしてある。実装フェーズで案 b に寄せる判断材料を得るのが目的。

### 4.5 `pause_collection[behavior]=void`

- void 設定中の請求期日には invoice は**生成されるが即 voided**。Stripe は以後 **invoice メール・webhook を送らず**、サブスクのステータスも変えない。→ §4.5 の「void 月スキップ」設計と整合（void 月は徴収しない）。
- 出典: [Pause payment collection | Stripe](https://docs.stripe.com/billing/subscriptions/pause-payment)

### 4.6 Test Clocks（PoC 実行基盤）

- `POST /v1/test_helpers/test_clocks`（`frozen_time`）でクロック作成 → `POST /v1/customers`（`test_clock`）で顧客を紐付け → `POST /v1/test_helpers/test_clocks/{id}/advance`（未来 `frozen_time`）で早送り。
- advance は**非同期**（`status: advancing → ready`）、進行中に **billing webhook（invoice.created 等）が発火**。`DELETE /v1/test_helpers/test_clocks/{id}` で**顧客・サブスクを連鎖削除**。
- 制約: **1クロックあたり顧客最大3 / 顧客あたりサブスク最大3 / 1回の advance は最短請求間隔×2（月額なら最大2ヶ月）まで / サブスク無しなら最大2年 / 30日後に自動削除**。
- 出典: [Test clocks | Stripe](https://docs.stripe.com/billing/testing/test-clocks) / [API and advanced usage | Stripe](https://docs.stripe.com/billing/testing/test-clocks/api-advanced-usage)

### 4.7 Connect テスト口座（charges_enabled）

- `POST /v1/accounts`（`type=custom`）に `capabilities[card_payments][requested]` / `[transfers][requested]`・`tos_acceptance`・individual 情報・external_account を投入。**magic 値**（DOB `1901-01-01`、住所 line1 `address_full_match`、US は SSN `000000000`、bank routing/account のテスト値）で**即 `charges_enabled=true`**。
- 注: 本スクリプトは **JP** 口座で作成。JP は US の SSN magic 値が不要だが、`charges_enabled` 化の magic データが US と一部異なるため、PASS にならない場合は INFO 扱いとし、ダッシュボードで requirements を確認のうえ test data を調整する。
- 出典: [Testing Connect | Stripe](https://docs.stripe.com/connect/testing)

---

## 5. キー投入後の実行コマンド（コピペ可）

```bash
cd "C:/Claude/mannschaft/.claude/worktrees/agent-p5-poc"   # または clone 先の該当ディレクトリ
export STRIPE_SECRET_KEY='sk_test_'<あなたのテストキー>   # 例: sk_test_ で始まる test mode の Secret key
bash scripts/poc/f089_p5_invoice_fee_override_poc.sh
```

実行後、標準出力末尾の **PASS/FAIL サマリ表**で命題成否を確認。`#7 更新invoice上書き` と `#8 charge application_fee_amount一致` がともに **PASS** なら、§11-3 の命題は実機でも成立 → **P5 は Stripe Subscription で実装可能**（初回は案 b を推奨）。FAIL なら自前バッチへの退避を検討する。
