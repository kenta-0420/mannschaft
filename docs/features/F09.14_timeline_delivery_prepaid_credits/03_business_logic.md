# F09.14 業務ロジック・受入条件

> **ステータス**: 🟢 設計完了。機能ルールはREADME、実装境界は本書と01/02末尾で確認する。

## 1. 対象・無料判定・公開

対象はTEAM/ORGANIZATIONのトップレベル投稿で、既存の可視性規則をprojectionへ反映する。投稿者本人、重複、mute・脱退等の公開時点で対象外のaccountを除き、`DISTINCT recipient account`を作る。personal feedはDELIVERED、scope feedはPUBLISHEDだけを表示する。

同一scopeの`timeline_monthly_delivery_usages`をlockして、投稿数が100未満ならfree、100以上なら実recipient数をcredit化する。月境界は財布所有timezone（初期Asia/Tokyo）、変更は次期間から適用する。空/null/0は0件として扱い、1投稿に宛先上限を置かない。

### Tx-A / B1 / B2 / 配信

Tx-Aはscope権限、post、idempotency、paid confirmationを短く保存し201を返す。B1はrelevant source projectionのpublication cutを取得し、不可視stagingへkeyset batchで重複なしrecipientを作る。manifestにcount/hash/cut/asOfを保存する。B2はmanifestの完全性、quota、wallet、FIFO lotを短いTxで予約し、同時に`publication_status=PUBLISHED`をcommitする。巨大単一Tx、HTTP中の配信、公開後の件数再計算はしない。

cutはB1開始時点の正本であり、加入・脱退・muteの残余raceはcut sequence/asOfを監査する。staleness上限を超える場合だけ再作成し、その後はPUBLISH_BLOCKEDとする。B2は最新high-watermarkとの一致を要求しない。

workerはjobを耐久queueとしてbatch処理する。各recipientの成功でCAPTURE、恒久未達でUNDELIVERABLE_RELEASE、期限でEXPIRE_RESERVEDを一回だけ記録する。途中失敗はretryし、既capture creditを戻さない。

## 2. プリペイド・auto-topup・返金

購入はStripe Checkoutまたは広告方式の既存プリペイドパターンを使い、FIFOのactive lotから予約する。`PURCHASE(+total,+available)`、`RESERVE(available-,reserved+)`、`CAPTURE(total-,reserved-)`、`RELEASE(reserved-,available+)`を混同しない。pending入金はasset不変条件外で、成功webhookでのみavailableへ入る。

auto-topupはmandateとperiod usageを正本にする。waiting jobをcreatedAt,id順に並べ、`R=Σ requiredRemaining`、available `A`、pending未割当`P`、`S=max(0,R-A)`、`assigned=min(S,P)`、`jobNeed=max(0,S-assigned)`とする。A<Rでactive PIがあれば割当後に一度だけ再評価し、active PIなしなら購入最低50・cap内でjobNeedを満たす。R<=Aなら全jobを通常公開し、bufferは公開と独立して試す。cap不足jobだけPUBLISH_BLOCKED、同jobの反復課金は禁止する。

mandateはconsenting user/customer/payment method/consentedAt/revokedAtを持つ。owner移譲、同意ADMINの離脱・剥奪、scope脱退はrevoke、auto入金OFF、後任再同意とする。pending counterは`PENDING|CANCEL_REQUESTED` purchaseの合計で、PI作成で増え、PAID CASまたはterminal `CANCELLED|PAYMENT_FAILED|EXPIRED`でだけ減る。23:59作成・00:01成功/失敗も作成時periodに帰属する。

customer refundはdelivery jobへ計上しない。未captureのreserved/frozenをrecipient終端後にrelease/unfreezeし、available化できるcreditだけをpurchase/balanceから除外してliability commandへ渡す。capture済みは返金不可。dispute openはfreeze、won/reinstatedはunfreeze、lostはDISPUTE_LOSSで除却する。

## 3. 状態と取消

```text
PREPARING -> AWAITING_TOPUP -> PREPARING -> PROCESSING -> COMPLETED
PREPARING/PUBLISH_BLOCKED -> DELETED
PREPARING -> PUBLISH_BLOCKED -> PREPARING（新confirmation retry）
AWAITING_TOPUP -> CANCEL_RESOLVING -> DELETED
PROCESSING/FAILURE_RESOLVING -> 409（retry/DELETE競合）
```

`AUTO_TOPUP_REQUIRES_ACTION`はPUBLISH_BLOCKEDのfailureCodeである。CANCEL_RESOLVINGはユーザー削除による共有PI解決だけに使い、PREPARINGへ戻さない。DELETE後にPI successが先着しても、credit入金だけ成立、allocationはRELEASED、postは復活せずjob DELETED、ADMIN通知へ収束する。PROCESSINGのHTTP retryは不可、FAILURE_RESOLVINGまたはPUBLISH_BLOCKEDだけが新confirmation/権限を同Txで再確認してretryできる。

purchaseは`PENDING→PAID→PARTIALLY_REFUNDED/REFUND_PENDING/REFUNDED/DISPUTED`。retryable payment_failedはPENDING非終端、Checkout expiredは期限終端、payment_intent.canceledは原因別CANCELLEDまたはPAYMENT_FAILED終端である。

## 4. 可視性・既存query

既存`TimelinePostRepository`のfeed/search/my/user posts/pinned/public TEAM/ORG/detail guardは`publication_status`を契約に追加する。flag offは全query legacy、flag onでもpersonalはDELIVERED、scopeはPUBLISHEDを使う。publish時のgamification/publish eventはB2の実公開commit後だけ発行する。認可正本は`TimelinePostVisibilityAccessGuard`だけである。

## 5. AC→試験表

| AC | 仕様 | 試験観点 |
|---:|---|---|
| 1–4 | wallet/lot/ledger、pending除外、projection | before/after、FIFO、再送、out-of-order、backfill |
| 5–7 | B1/B2、Tx-A、201/409/job 200 | 10万/100万 operating point、途中失敗、残高不足 |
| 8–10 | mandate、匿名化、Stripe claim | revoke race、退会、metadata欠落、署名失敗 |
| 11–13 | refund/dispute、空/0/null、timezone | partial refund、won/lost、23:59→00:01、月初 |
| 14–16 | ADMIN/委任/flag/API | IDOR、scope横断、権限剥奪、legacy E2E |
| 17–20 | 状態/取消/confirmation | PREPARING DELETE、AWAITING cancel、PI success先着、PROCESSING retry409 |
| 21–24 | auto-topup | 0/49/50、複数job、cap100/pending60、requires_action |
| 25–28 | query/可視性/非回帰 | personal DELIVERED、scope PUBLISHED、gamification B2後 |
| 29–32 | 運用/容量/保持 | queue backpressure、archive 13か月、DLQ、tested SLO |

## 6. 実装時の技術検討事項

- 細粒度のlock、lease/fencing、manifest hash、projection event sequence、partition pruningは01の最小契約から実装時に選定する。共通lock順は`usage→balance→mandate→period usage→purchase→payment object→allocation→auto-topup job allocation→job→manifest/recipient`。
- 100万 recipients×13か月の容量は`13 * jobsPerMonth * recipientsPerJob * combinedRecipientLedgerBytes * 1.3`で見積もる。1M/同時10はtested pointでhard maxではなく、超過はqueue/backpressureで受け付ける。
- /試練ではこの表から正常・境界・並行・権限剥奪・Stripe順序逆転を実装テストへ割り当てる。
