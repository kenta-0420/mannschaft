# F09.14 セキュリティ・UI・i18n・運用

> **ステータス**: 🟢 設計完了。認可正本は`TimelinePostVisibilityAccessGuard`、機能用語はREADMEを参照する。

## 1. 認可と個人情報

scopeはDBのteam/organization serviceで解決し、URLのIDだけで権限を推測しない。非所属、scope横断、委任範囲外、job/purchaseのIDORは404。`SEND_PAID_TIMELINE`（投稿）とscope ADMIN（財布・購入・mandate・返金・委任変更）を分離し、SYSTEM_ADMINは監査read-onlyとする。scheduler・scheduled publishはinternal workerだけが実行する。

投稿作成、購入、mandate設定、retry、refund commandなど新規mutation/paid分岐だけにFeatureGateAspectをmethod-level付与する。既存job GET、refund/liability/audit GETには付与しない。flag offではlegacy POST/feedを完全維持し、新規mutationは403、作成済みjob観測と法的read GETは認可後200を維持する。

actor/recipient/approver/consenting userはUSERならnullable IDとHMAC subject hash/key version、SYSTEM/NONEならNULLを保存する。recipientはUSER固定でhash必須。高entropy erasure tokenを退会時にcryptographic eraseし、active中の仮名化とerase後の匿名化を区別する。ログ・画面には本文、recipient、token、Stripe secretを出さない。

## 2. 決済・操作安全

Stripe署名検証後に専用inboxをclaimし、inbox永続化失敗はretryable 5xx、永続化後のdispatcher失敗は200+retryとする。webhookだけを入金正本とし、browser return/cancelは購入を直接変更しない。requires_actionは再認証UIではなくdurable cancel commandとし、画面CTAは「手動補充して再試行」。

投稿同意tokenはhash、scope、actor、期限、consume時刻を保存する。`allowPaidAtPublish=true`の予約だけfuture consentを保存し、false/省略は有料化時`PUBLISH_BLOCKED`、新token retryで復帰する。権限剥奪後は新たにeligibleなactorが自分のconfirmationを作る。

## 3. UI状態とpoll

状態の正本は`publicationStatus + deliveryJob.status + failureCode`であり、top-levelの別delivery statusを表示しない。`exactRecipientCount=null`は計算中、確定後の0は0件として表示する。

| job status | 表示 | 操作 |
|---|---|---|
| PREPARING | 準備中 | cancelは直接DELETED |
| AWAITING_TOPUP | 補充待ち | cancelはCANCEL_RESOLVING、入金待ち |
| PROCESSING | 配信中 | retry/DELETEは409 |
| PUBLISH_BLOCKED | 公開待ち | `PAID_CONFIRMATION_REQUIRED_ASYNC`は同意retry、`AUTO_TOPUP_REQUIRES_ACTION`は手動補充 |
| FAILURE_RESOLVING | 障害解決中 | 権限者の安全なretry |
| COMPLETED | 完了 | WSまたは手動refresh |
| CANCEL_RESOLVING | 削除解決中 | cancel workerのみ、PREPARINGへ戻さない |
| DELETED | 削除済み | 認可済みGETだけ観測 |

通常の準備・配信は最初2秒間隔、長時間は5〜15秒へ緩和する。AWAITING_TOPUP/CANCEL_RESOLVING/FAILURE_RESOLVINGは低頻度、PUBLISH_BLOCKED/COMPLETED/DELETEDは終端としてWSまたは手動refreshを使う。Checkout returnはopaque purchaseIdをAPIで再認可し、PENDING/PAID/PAYMENT_FAILED/CANCELLED/EXPIREDを表示する。browser close後は購入履歴から復帰でき、timeoutは「処理中・履歴で確認」とする。

## 4. UI・i18n

| key | 日本語（例） |
|---|---|
| `timeline.delivery.estimate` | 到達見込み：{count}件（{asOf}時点） |
| `timeline.delivery.paidConsent` | 有料配信になる可能性を確認しました |
| `timeline.delivery.blocked` | 権限または残高の確認が必要です |
| `timeline.delivery.autoTopupRequiresAction` | 手動補充して再試行してください |
| `timeline.delivery.cancelResolving` | 削除を処理しています |
| `timeline.credit.purchasePending` | 決済を確認しています |
| `timeline.credit.refundPending` | 返金を確認しています |

既存の6言語i18n仕組みへ同じkeyを追加し、金額は税込円、件数はaccount単位で表記する。「通」など通知配信と混同する語は使わず、「投稿」「宛先」「終端」を用いる。

## 5. 運用と非回帰

監視はjob lag、PUBLISH_BLOCKED、auto-topup失敗、Stripe inbox retry/DLQ、ledger reconciliation、wallet underflow、二重配信・二重課金を対象にする。100万 recipients/job・同時10 jobはtested operating pointでありhard maxではない。超過はHTTP拒否せずqueue/backpressureで受け付け、容量・index・retention・alertは実装時に測定する。raw recipient/ledgerは13か月後にarchive export後削除する。

flag offのpersonal/team timeline E2Eを回帰資産とする。flag onではpersonal feed=DELIVERED、scope feed=PUBLISHED、publish/gamification eventはB2公開後だけとする。主要受入試験は、0/null、月境界、同時投稿、権限剥奪、脱退/mute race、DELETEとStripe success逆順、retryable payment_failed、canceled/expired終端、legacy queryである。

## 6. 実装時の技術検討事項

- rate limitは既存基盤を継承し、権限判定はDB scope + `TimelinePostVisibilityAccessGuard`で統一する。
- 監査のsubject hash、Stripe payload、DLQ保持期間、SLO/容量式、詳細poll間隔は運用環境に合わせて確定する。
- /試練では02のAPI表、03のAC表、既存flag-off E2Eを契約テストの起点にする。
