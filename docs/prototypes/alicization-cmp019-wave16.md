# CMP-019 Wave 16 実機・アリシゼーション証跡（2026-09-29）

## 判定とコミット

- 統合HEADは `1a71a3718623791c673db0798c1d9233583471bf`、統合元mainは `7fa5f00d25`。runtime BEは `f82b5fee7c6dd6670faaa68ff76b28b8e0dd09e2`であり、recruitment/notification/GlobalExceptionHandlerのf82対HEAD差分は0、actual差分も0である。
- f82の10 workflowはsuccess（OpenAPI `36499471665`、Frontend `36499471559`、Backend `36499471788`、Generated Types `36499471571`、Smoke `36499471555`、Lighthouse `36499471621`、API `36499471833`、cleanup `36499471842`、lock `36499471909`、gitleaks `36499471619`）。現在の判定はPR #3499の最新チェックと検分を参照する。
- Wave 16実装は実機E2Eとcontrolled UI再実証まで確認済み。ただしB0-J5全体はpartialであり、CMP-019、F03.11、Issue #3502、Issue #3503の完了を示さない。

## 実機E2E

- 実BE 8081・FE 3004・MySQLでretest10を実施。session `89577`、actual CLI exit 0、expected 2、unexpected/skipped/flaky 0、duration 667521.279ms。TEAM 393557ms、ORGANIZATION 262691msで2/2 passした。
- 本人確認、既読独立、reload後の確認済み保持、未読POST 200後の確認済み保持、権限境界、期限切れ応募201・DB 1件、cleanup対象0と履歴2件保持を確認した。再triggerのHTTP成功は実batch実行またはno-opの証明ではない。
- rootのreadonly strict owned DB確認はactual exit 0（r123 confirmed 1、n3286060 read 1、c67 COMPLETED/DELIVERED）。実penaltyとrelative timeの正常性は、createdAtだけをUTC+10分へ人工調整したowned synthetic UI fixtureの証明対象外である。
- 非秘密要約: `.claude/handoffs/cmp019-wave16-evidence-20260929/real-e2e-retest10-summary.json`。

## 3住民の自由探索（fresh3）

- admin `W16-RESIDENT-1790659868391-rkfzfj`、outsider `W16-RESIDENT-1790660097178-5d25o7`（user 90245、TEAM1非所属）、mobile `W16-RESIDENT-1790660747742-8nbzso`を別BrowserContextで実行し、各actual CLI exit 0・1 passed。既存run IDと観測を参照した自由探索である。観測軸はadmin「ADHD × 中断放棄」、outsider「一般 × 隙間狙い」、mobile「スマホ片手 × 表示崩れ」。
- adminは初期設定モーダルによりnumeric 67/user 23の管理確認に未到達。安全性未確認の設定変更は行わなかった。Bell 32pxの観測はinboxとの誤認のため棄却し、実通知buttonは44x44である。
- outsiderは正規GET `/api/v1/teams/1/confirmable-notifications/67` が403、本文を読まないことを確認した。正規UI direct gotoは未証明であり、未知routeの404は境界証明に使わない。
- mobileは通知一覧まで到達したが、背景通知に対象が埋もれて確認・既読を操作していない。本文圧縮とCTA 35pxはPNG/DOMで確認された修正対象である。menu/filter/inboxの44px未満候補は未実証insightに留め、B0-J5は目的未達のため合格に数えない。原因と未実証事項は断定しない。

## controlled UI再実証

- mobile `W16-RESIDENT-1790664134734-8d3rpj`はsession `56570`、actual CLI exit 0、1 passed（06:42:14.880Z–06:48:20.934Z）。390/360でtitle 2行、body全文5行、width 262/232、height 100、CTA 100.875×44、viewport overflow 0。実UIで「確認する」後に未確認1→0、離脱復帰後も確認済み保持を確認した。
- admin `W16-RESIDENT-1790664536894-e8qqvv`はsession `67281`、actual CLI exit 0、1 passed（06:48:57.255Z–06:52:17.895Z）。正規GET 67=200でID/title一致、安定した「確認通知設定」で履歴先頭のowned通知完了1/1（100%）を確認。1920×1080と1152×720でoverflow 0、card 862×155。1152×720は125%相当viewportでありbrowser zoomの実測ではない。
- 画像とmetricsは `.claude/handoffs/cmp019-wave16-evidence-20260929/mobile-stable/`、`admin-stable/`、`stable-ui-manifest.json`および`controlled-ui-summary.json`を参照する。6 PNGはactual view済みでLoadingはない。

## UI-only synthetic DB証跡

- r123 confirmed 1、n3286060 read 1、c67 COMPLETED/DELIVEREDはcontrolled UI用のowned synthetic fixtureのstrict readonly確認である。createdAtだけをUTC+10分へ人工調整しており、実penalty・relative timeの正常性を証明しない。

## Issue #3498 AC対応

| AC | テスト・実機証跡 | 状態 |
| --- | --- | --- |
| 新規適用だけを本人へURGENT 1件送信 | `RecruitmentPenaltyAppliedNotificationListenerTest#sendsUrgentConfirmationToPenaltyOwnerFromSystemUser`、`RecruitmentPenaltyServiceTest#evaluate_existingGlobal_doesNotCreateAgain`。実機TEAM/ORGANIZATION 2/2。既存有効ペナルティの更新送信は対象外。 | testのgreenはPR #3499最終CIを参照 |
| 設定無・無効・閾値未満を除外 | `RecruitmentPenaltyServiceTest#evaluate_noSetting_returnsEmpty`、`#evaluate_settingDisabled_returnsEmpty`、`#evaluate_belowThreshold_returnsEmpty`。 | testのgreenはPR #3499最終CIを参照 |
| PERSONAL・REVOKEDを除外 | `RecruitmentNoShowConfirmBatchTest#PERSONALは確定しても発動判定しない`、`#REVOKEDは確定しても発動判定しない`。 | testのgreenはPR #3499最終CIを参照 |
| 再実行・並行実行 | `RecruitmentPenaltyAppliedNotificationListenerTest#neverResendsEvenAfterRecipientHasConfirmed`。並行は`RecruitmentNoShowConfirmPenaltyIT#concurrentGlobalEvaluationFromDifferentTeamsCreatesOnePenaltyAndOneDeliveredNotification`。再trigger HTTPは実batch証明外。 | testのgreenはPR #3499最終CIを参照 |
| rollback・after-commit配送失敗 | `RecruitmentNoShowConfirmPenaltyIT#rollbackKeepsNoShowUnconfirmedAndCreatesNeitherPenaltyNorNotification`、`RecruitmentPenaltyAppliedNotificationListenerTest#deliveryFailureDoesNotChangeCommittedPenalty`。 | testのgreenはPR #3499最終CIを参照 |
| TEAM/ORGANIZATIONと権限境界 | 実機2ケース、本人確認、outsider正規GET 403本文非読。正規UI direct gotoは未証明。 | 部分実証 |

## B0-J5とデータ取扱い

- B0-J5（capabilities: `notification-inbox-notification-delivery`、`notification-inbox-inbox`）はpartialを維持する。fresh3の未達・未操作とcontrolled再実証を区別する。
- personaIdは固定P01..P20を捏造せず省略し、run/URL/画像/insightはB0スキーマ許容欄に記録する。blindfresh3の10 PNGはrootが保全済みの`.claude/handoffs/cmp019-wave16-evidence-20260929/blind-exploration-manifest.json`を参照し、raw report/config/env/trace/auth/storageStateは保存・出力しない。
- cleanupは所有確認済み試験fixtureだけに限定し、許可済み。session `21963` は2026-09-29T07:06:07.606Zにactual exit 0。preguard `1|1|1|1|1512`から、owned派生sender alert 1511件、application 1件、recipient 1件、confirmable notification 1件だけを削除し、strict proof `0|0|0|0|0`を確認した。seed user 1/3/23、TEAM1、他通知、履歴は変更していない。native Playwright specはfixtureがcontext closeを管理するため、個別bridge closeの架空ログを証拠にしない。

## ハッシュ

- 最終List SHA-256: `a43aed545d02ef47cc44bfc8c6bcf902e30667e8f7d74be77bfb9ad93358c7ea`（Git blob SHA-1 `4cc363`）。`AF3D95...`はpre-CSS Listの当時ハッシュであり最終ハッシュではない。
