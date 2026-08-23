# F09.14 セキュリティ・UI・i18n・運用

> **ステータス**: 🟡 設計精査中

## 1. セキュリティと不正利用対策

### 認可・IDOR

- Controllerは既存の認可guardを入口に置き、Serviceでscope membershipとpermissionを再検証する。team/organization Repositoryをtimelineから直接越境参照しない。
- POSTのscope、deliveryScope、confirmation token、jobId、purchaseIdはすべて server-sideのscope/actorと照合する。clientが渡すrecipient数、金額、credit残高は一切信用しない。
- ContentVisibilityResolver は詳細・検索・投稿一覧・返信の表示に必ず使う。配下配信は上位scopeのbrowse入場権を付与しない。muteは表示設定であり検索を隠さない。
- 財務明細はADMINのみ。`VIEW_TIMELINE_COST`にはaccount ID、purchase Stripe ID、返金/dispute詳細を返さない。audit/ledgerのrecipient IDは13か月で匿名化する。

### 決済・webhook

- Stripe Checkout URLだけを返し、カード番号・PaymentMethod・Webhook秘密鍵をDB/API/log/localStorageに保存しない。Stripe customer/payment intent IDは必要最小限の外部参照として暗号化設定・アクセスログ方針に従う。
- webhookはraw bodyの署名検証、Stripe event IDの永続冪等、metadataのpurchase/scope照合、許可event種別ホワイトリストを必須とする。署名不正は400、再送可能な内部失敗は既存dead-letter/alertへ送る。
- すべてのStripe作成・refund呼出にサーバ生成idempotency keyを付ける。ブラウザリトライは request `idempotencyKey` をpurchase/jobのactor/scopeに束縛する。
- 取消可能なのは完全未使用購入だけ。表示上の取消不能はchargebackを妨げないため、dispute eventを必ず扱う。

### 乱用・可用性

| 操作 | 制限 | 根拠 |
|---|---:|---|
| 概算 | actor/scopeごと 30回/分 | 階層探索・countの増幅防止 |
| 投稿公開 | 既存timeline投稿制限に加え paid確認 10回/分 | token総当たり・queue flood防止 |
| checkout作成 | ADMIN/scope 5回/時 | Checkout濫造防止 |
| retry | job 5回、actor 10回/時 | 永久障害の再投入防止 |
| ledger | cursor size最大100、ADMIN 60回/分 | 明細列挙防止 |

rate limitは既存 Valkey `AbstractRateLimitFilter`系を使い、Valkey障害時の既存fail-open/fail-closed方針を継承する。ただし支払確定・残高予約はレート制限が落ちてもDB lock/冪等で整合を守る。エラー文・構造化ログに本文、recipient、token、Stripe secretを含めない。

## 2. UI/UX

### 投稿フォーム

- TEAM/ORGANIZATION のトップレベル投稿で、概算 `「配信先 約1,240アカウント（11:30時点）」` を常時表示する。数値は概算であり公開時に変動する旨を控えめに添える。
- `VIEW_TIMELINE_COST`があれば無料利用 `82 / 100投稿` と残りを表示する。80/100以降は視覚的に注意を上げるが、無料投稿を妨げない。
- 101件目以降の見込みでは、`「約1,240通 / 税込 ¥1,240」`、利用可能credit、`「公開時に件数を確定します。増減しても再確認はありません」`を示し、単一の明示確認チェック/ボタンで送信する。無料の送信には確認を追加しない。
- `SEND_PAID_TIMELINE`がなければ有料送信ボタンを無効化し、ADMINへ委任を依頼する説明を表示する。残高不足・wallet停止は原因とADMIN向け導線を表示し、部分送信の選択肢は出さない。
- `PROCESSING`は投稿カード/フォームに処理中ラベルと到達数を出し、編集・削除を非活性にする。完了/最終失敗は `GET timeline-delivery/jobs/{jobId}` のpollを正本に反映し、WebSocket `timeline.deliveryJobChanged` は更新を早める補助通知だけにする。

### ダッシュボードと管理画面

- scopeを明示して、概算配信先、asOf、無料使用/残り、paid delivery、wallet statusをダッシュボードに表示する。TEAMとORGANIZATIONを合算しない。
- ADMIN画面だけに残高・予約・購入履歴・refund/dispute・auto-topup設定を置く。任意top-up入力と50/100/500/1000等のpresetを示すが、割引を示唆しない。
- auto-topupは初回手動購入までdisabled理由を明示し、threshold/refill/monthly capを同一フォームで必須にする。cap到達時は送信者にも「ADMINの補充待ち」と表示する。
- 完全未使用購入の取消は購入履歴から明示し、使用済みは理由と取消不可を表示する。scope削除・所有移転の確認画面は残高・予約・auto設定・返金/債務を表示する。

アクセシビリティ: 金額・状態は色だけで伝えずテキスト/aria-liveを付ける。確認dialogは初期focus・Escape・戻る動作を備え、送信中の二重submitを防ぐ。小数や通貨の自由入力は受けず、整数creditをlocale書式で表示する。すべての可視文言はi18nキーを使い直書きしない。

## 3. i18n キー（6言語必須）

実装では専用 `frontend/app/locales/{ja,en,zh,ko,es,de}/timeline_delivery.json` を新設し、全キーを同時追加する。値は以下を基準に各言語へ自然に翻訳し、6言語parity testでキー欠落を検出する。`common.json` や既存timeline namespaceには混在させない。

| key | ja基準文言 |
|---|---|
| `timeline.delivery.estimate` | 配信先 約{count}アカウント |
| `timeline.delivery.asOf` | {time}時点の概算です。公開時に確定します。 |
| `timeline.delivery.freeUsage` | 今月の無料投稿: {used} / 100 |
| `timeline.delivery.freeRemaining` | 無料投稿の残り: {count}件 |
| `timeline.delivery.paidEstimate` | 約{deliveries}通 / 税込 ¥{amount} |
| `timeline.delivery.confirmPaid` | この金額で配信することを確認しました |
| `timeline.delivery.confirmHint` | 公開時の人数変動では再確認しません。 |
| `timeline.delivery.processing` | 配信を処理しています |
| `timeline.delivery.completed` | 配信が完了しました |
| `timeline.delivery.failed` | 配信を完了できませんでした。管理者へ通知しました。 |
| `timeline.delivery.permissionRequired` | 有料配信の権限が必要です。管理者へ依頼してください。 |
| `timeline.delivery.insufficientCredits` | クレジット残高が不足しています。配信は行われませんでした。 |
| `timeline.credit.balance` | 利用可能クレジット: {count} |
| `timeline.credit.reserved` | 処理中に予約済み: {count} |
| `timeline.credit.topUp` | クレジットを購入 |
| `timeline.credit.minimumPurchase` | 購入は50クレジット（¥50）からです。 |
| `timeline.credit.autoTopup` | 自動補充 |
| `timeline.credit.autoTopupCap` | 今月の自動補充上限 |
| `timeline.credit.autoTopupBlocked` | 自動補充の上限に達しました。 |
| `timeline.credit.cancelUnavailable` | 使用済みの購入は取り消せません。 |
| `timeline.credit.disputeFrozen` | 係争中のため有料配信を停止しています。 |
| `timeline.credit.refundLiability` | 返金を処理中です。管理者が対応します。 |
| `timeline.delivery.scheduledBlocked` | 予約投稿は残高不足のため公開されませんでした。 |

## 4. 運用・アラート・法務表示

- price、purchase receipt、ledger、確認dialogのすべてで `税込` と `¥` を明記する。税率/インボイス事業者情報は既存決済・法務表示コンポーネントを再利用し、F09.13と表示を乖離させない。
- 監査イベントは `TIMELINE_CREDIT_PURCHASED`、`...RESERVED`、`...CAPTURED`、`...REFUNDED`、`...EXPIRED`、`...AUTO_TOPUP_*`、`...DISPUTE_*`、`TIMELINE_DELIVERY_*` をPAYMENT/TIMELINEカテゴリに記録する。監査に投稿本文・recipient一覧を複製しない。
- SLO初期値: 公開API p95 1秒未満（1万recipientでもenqueueまで）、queue開始p95 30秒以内、最終配信99.9% 15分以内（外部障害除く）。超過、queue lag、reservation滞留15分超、refund liability、disputeはpager/通知対象にする。
- 日次reconciliationで、wallet available/reserved、purchase remaining、ledger合計、recipient終端数を突合し差異は自動修正せず`FAILURE_RESOLVING`と監査アラートにする。Stripe payout/refund/disputeも日次照合する。
- backup/restoreではjob・recipient・ledgerの冪等キーを保ち、復元後のqueue再投入でも二重消費しない。feature flagをoffにしても既存PROCESSING jobはdrain/返金まで継続する。

## 5. 公式Stripe参照

- JPYの最低課金額50円: <https://docs.stripe.com/currencies>
- disputeへの応答・証拠: <https://docs.stripe.com/disputes/responding>
- webhook event種別: <https://docs.stripe.com/api/events/types>
- refund APIと状態: <https://docs.stripe.com/api/refunds>

## 6. 第1精査で確定した権限・運用詳細

`SEND_PAID_TIMELINE` と `VIEW_TIMELINE_COST` はpermission catalog/Flywayに同一PRで登録する。ADMINには両方をdefault grant、DEPUTYにはdefault grantしない（ADMINがpermission group経由で委任する）。`SEND_PAID_TIMELINE`はpaid confirmationに必要な概算人数・概算税込額・tokenを閲覧できるが、wallet残高、purchase、refund、dispute、ledgerの財務詳細は閲覧できない。TEAM/ORGANIZATION双方にpermission migration・実Flyway ITを置く。

wallet statusは`CLOSED > CLOSING > SETTLEMENT_BLOCKED > FROZEN > ACTIVE`の優先順で導出する。CLOSINGはscope delete eventから入り、new publish/auto-topupを止め、jobsを終端化し、lot allocation/remaining lotをFIFOでrefundし、成功ならCLOSED、Stripe返金不能ならREFUND_LIABILITYを残してCLOSEDとする。ownership transferは`ScopeOwnershipTransferredEvent`を受け、walletのscopeを変えずowner表示/監査だけを更新する。APIは既存ownership transfer confirmation画面でbalance/reserved/auto/disputeを双方へ表示する。

feature flagは `FEATURE_TIMELINE_DELIVERY_PREPAID_ENABLED`。offでは既存無料投稿を完全後方互換で公開し、新しいpaid/auto UI/APIは404、既にPREPARING/PROCESSING jobはdrainまたはrefund終端まで継続する。状態反映はpollが正本、WebSocketは`timeline.deliveryJobChanged`の補助通知だけで、欠落してもpollで状態を回復する。

i18nは専用 `timeline_delivery.json` の6言語parity testで全keyを強制する。rate limit Valkey障害は概算/readは既存fail-open、Checkout/paid publish/retryはfail-closed（429/503）とし、DB整合ではなく濫用防止だけを担う。
