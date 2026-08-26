# CMP-049 着手手順 — テスト用 Connect 口座の用意

CMP-049（Stripe 実徴収の実機検証）は「payouts が有効な Connect 実口座」が無いと着手できない、と台帳に記録されている。本書はその口座をテストモードで用意し、`ConnectChargeService` が `HELD` で止まらなくなるまでを手順化したものである。

**所要時間の目安: 15〜30分。実在の銀行口座も本人確認書類も不要。**

---

## 0. 前提の確認（なぜこれで足りるのか）

先に結論を書く。**新しく Stripe のアカウントを契約する必要は無い。** いま使っているテストキーと同じ環境の中で、受け取り側の connected account を1つ作ってオンボーディングを完了させるだけでよい。

根拠は実装側にある。徴収が `HELD` で止まる分岐が見ているのは **DB に鏡像された `payouts_enabled` ただ一つ**である。

`backend/src/main/java/com/mannschaft/app/payment/escrow/ConnectChargeService.java:163, 230-240`

```java
boolean payoutsEnabled = Boolean.TRUE.equals(payee.getPayoutsEnabled());
...
if (!payoutsEnabled) {
    // 受取側 onboarding 未完了 → HELD。PaymentIntent は作らない
    EscrowTransactionEntity held = builder.status(EscrowStatus.HELD)
            .holdExpiresAt(now.plusHours(HELD_GRACE_HOURS)).build();
```

この値は `account.updated` webhook 経由で Stripe から届いたものが DB に落ちる（`StripePaymentProviderImpl.toConnectAccountInfo` → `ConnectAccountService.applyAccountUpdated` → `ConnectAccountEntity.payoutsEnabled`）。したがって **Stripe 側で `payouts_enabled: true` になり、その webhook がローカルの BE に届けば** 前提は満たされる。

> **補足（実装と Stripe 正典のズレ）**
> Stripe の現行正典では `payouts_enabled` は非推奨の v1 フィールドで、受け取り可否は
> `configuration.recipient.capabilities.stripe_balance.stripe_transfers.status === 'active'` を見るべきとされている。
> しかし本実装は v1 世代（`type: express` + `Account.create` + `payouts_enabled`）で書かれており、
> **v2 の痕跡は backend 全体でゼロ**である。加えて Stripe Java SDK は `28.2.0` に意図的にピン留めされており
> （`backend/build.gradle.kts:141-148`。29.x 以降は F08.9 P5 の invoice 手数料上書きが壊れる旨のコメントと
> `StripeJavaVersionGuardTest` による番人つき）、**28.2.0 の jar には v2 Accounts のクラスが存在しない**。
> よって v2 移行は別建ての技術負債であり、**CMP-049 のブロッカーではない**。本手順は v1 のまま進める。

---

## 1. 事前準備

### 1.1 テストキー

`backend/src/main/resources/application-local.yml`（git 追跡外）に設定済み。

```yaml
mannschaft:
  stripe:
    secret-key: sk_test_...
```

### 1.2 Webhook シークレット

`payouts_enabled` は **webhook 経由でしか DB に入らない**ので、Stripe CLI の転送が必須である。

```bash
stripe listen \
  --forward-to localhost:8080/api/v1/webhooks/stripe \
  --forward-connect-to localhost:8080/api/v1/webhooks/stripe/connect
```

表示された `whsec_...` を `application-local.yml` に設定する。**1つのコマンドで両方を指定した場合、署名シークレットは1つで、両方に同じ値を使うのが正しい。** 別々に `stripe listen` を2回起動した場合は値が2つ出るので、それぞれに対応させること。

```yaml
mannschaft:
  stripe:
    secret-key: sk_test_...
    webhook-secret: whsec_...
    connect-webhook-secret: whsec_...
```

> `stripe listen` で得られるシークレットは **CLI を起動するたびに変わる**。BE 起動前に貼り直すこと。

### 1.3 BE / FE の起動

本陣のポートを使う（`8080` / `3000`）。

---

## 2. Connect 口座を作る

**アプリ自身に作らせること。** Stripe ダッシュボードや CLI で直接作った口座は `connect_accounts` テーブルに鏡像が無いため、`ConnectChargeService` から見えない。

### 2.1 オンボーディング導線

FE に既に実装がある（`frontend/app/components/payment/MarketConnectOnboarding.vue`）。設置ページは次の2つ。

| ページ | パス |
|---|---|
| 募集詳細 | `/market/listings/{id}` |
| 謝礼の受け取り設定 | `/me/recruitment-payments` |

`/me/recruitment-payments` から入るのが素直。ここで受け取り設定を開始すると、BE の `POST /api/v1/payment/connect/onboarding-link` が呼ばれ、Stripe のホスト型オンボーディングへ遷移する。

このとき BE 側で行われること（`ConnectAccountService.createOnboardingLink`）:

- `Account.create` で **国 `JP` 固定**の Express アカウントを作成（`DEFAULT_COUNTRY = "JP"`、通貨 `JPY`）
- `connect_accounts` に `payouts_enabled=false` / `onboarding_status=ONBOARDING` で保存
- AccountLink を発行して URL を返す

---

## 3. オンボーディングをテスト値で完了させる

Stripe のホスト型画面に遷移したら、以下の**テスト専用の値**を入力する。実在の情報は一切不要。出典は Stripe 公式ドキュメント（Connect のテスト）。

| 項目 | 入力する値 | 効果 |
|---|---|---|
| SMS 認証コード | `000-000` | テストアカウントの認証を通過 |
| 生年月日 | `1901-01-01` | 生年月日の照合に成功する。**これ以外の値は照合されない** |
| 住所 | `address_full_match` | **支払いと入金の両方が有効になる** |
| 銀行コード（Routing・日本） | `1100000` | 入金が成功する組み合わせ |
| 口座番号（日本） | `0001234` | 同上 |

> **住所トークンは順序に注意。** Stripe の仕様上、**後からより緩い検証条件のトークンへ変更できない**。
> `address_full_match` を一度使うと、そのアカウントで入金を無効化する方向のテストはできなくなる。
> 失敗系（入金ブロック等）も試したい場合は、**別のアカウントを新規に作る**こと。

入金の失敗系を試したい場合の口座番号（日本）:

| Routing | Account | 挙動 |
|---|---|---|
| `1100000` | `0001234` | 入金成功 |
| `1100000` | `1111116` | `no_account` で失敗 |
| `1100000` | `1111113` | `account_closed` で失敗 |
| `1100000` | `2222227` | `insufficient_funds` で失敗 |
| `1100000` | `3333335` | `debit_not_authorized` で失敗 |
| `1100000` | `4444440` | `invalid_currency` で失敗 |

---

## 4. 前提が満たされたことの確認

順に確かめる。**どれか一つでも欠けていると `HELD` のままになる。**

### 4.1 webhook が届いたか

`stripe listen` のコンソールに `account.updated` が流れることを確認する。届いていなければ CLI の転送先か署名シークレットを疑う。

### 4.2 DB に反映されたか

```sql
SELECT stripe_account_id, onboarding_status, charges_enabled, payouts_enabled
FROM connect_accounts
WHERE deleted_at IS NULL
ORDER BY created_at DESC LIMIT 5;
```

`payouts_enabled = 1` かつ `onboarding_status = READY` になっていること。

> ⚠️ **共有開発 DB へのワイルドカード DML は禁止。** 参照のみに留めること。

### 4.3 アプリから見えるか

`GET /api/v1/payment/connect/status?scopeKind=USER` が `READY` を返すこと。

---

## 5. ここまで済んだら CMP-049 本体へ

前提が揃うと `ConnectChargeService.authorize` が `HELD` を返さなくなり、PaymentIntent が作られる。ここから設計書 `docs/features/F03.11.1_cancellation_fee_payment.md` §3.2 の3経路を実機で踏む。

| 経路 | 期待する挙動 |
|---|---|
| 与信のみ（`AUTHORIZED`） | **部分キャプチャ**され、残額が自動解放される |
| 確定済み（`CAPTURED`） | **`参加費 − キャンセル料` が返金**される |
| 与信なし（`DEFERRED` 等） | 徴収不能 → `FAILED` → リトライ上限到達で終端 `UNCOLLECTIBLE` |

**注意**: CMP-024 でユニット・契約 IT は全件緑、実機 E2E も 5/5 緑だが、**いずれも Stripe の実決済を伴っていない**。「CMP-024 が完了であること」と「実際に金が動く経路が未検証であること」は両立する。この区別を失うと「決済は検証済み」と誤認する。

---

## 6. 踏みやすい落とし穴

- **ダッシュボードや CLI で直接作った Connect 口座は使えない。** `connect_accounts` に鏡像が無く、アプリから見えない。必ずアプリの導線から作ること。
- **`stripe listen` を止めると webhook が届かなくなる。** 検証中は起動したままにする。
- **シークレットは CLI 再起動のたびに変わる。** 「昨日動いたのに今日 `HELD` のまま」の大半はこれ。
- **`application.yml` に鍵を書かないこと。** このリポジトリは公開である。`application-local.yml`（git 追跡外）か環境変数を使う。
- **`address_full_match` を使った口座では入金無効化のテストができない。** 失敗系は別口座で。

---

## 出典

- Stripe 公式ドキュメント: Connect のテスト（テスト値・日本の入金テスト口座）
- 同梱の Stripe 公式スキル `.agents/skills/stripe-best-practices/references/connect.md`（v1/v2 の区別、非推奨フィールド）
- 実装: `ConnectChargeService.java` / `ConnectAccountService.java` / `StripePaymentProviderImpl.java`
- 台帳: `docs/task-list.md` CMP-049
