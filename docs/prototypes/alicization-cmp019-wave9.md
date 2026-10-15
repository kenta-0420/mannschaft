# CMP-019 Wave 9 実機・アリシゼーション（2026-09-27）

- 対象: Issue #3473、シフト希望の手動督促を現役 TEAM MEMBER の未提出者へ送る。
- 環境: 実 BE `localhost:8081`、実 FE `localhost:3003`、実 MySQL。JDK 21 で `bootJar` を作成し、ローカル JDK 21 実行が停止したため同じ JAR を JDK 25 で起動した。FE 3001 は別 worktree が使用中のため触れていない。
- ローカルの対象 UT は 16/16 green。実 DB IT は Testcontainers の Docker 検出で実行できず、ローカル green と扱わない。CI での確認が必要。

## 決めたシナリオの実機 E2E

`frontend/tests/e2e/real/cmp-019-shift-reminder-members.real.spec.ts` を実 BE/FE/MySQL の Playwright Chromium で実行し、**1 passed**。証跡用の再実行も **1 passed (2.4m)**。管理者・現役 MEMBER・SUPPORTER・チーム非所属者の seed アカウントを別 BrowserContext で使用し、TEAM ロールと在籍状態を実 DB で事前確認した。新規スケジュールを希望収集中にして管理者が詳細画面を開き、手動督促 API を呼んだ後、応答・通知 API・通知画面・実 DB の配送先を照合した。MEMBER に届き、SUPPORTER と非所属者には届かなかった。[MEMBER 受信画面](evidence/cmp019-wave9/member-received.png)は実画像を確認済み。

管理画面に手動督促の UI 操作が存在しないため、送信操作だけは管理者の実 BE API `POST /api/v1/shifts/schedules/{id}/remind` を使用した。従って UI から送信する完全な E2E は未達。fc-u-18 に現役 GUEST fixture がなく、実機で GUEST 除外は直接確認していない。GUEST はバックエンドの実 DB IT の CI 結果で別途確認する。

## 3住民の自由探索

決めたシナリオとは別に、新規スケジュール ID 399（`CMP019_W9_EXPLORE_1790438266514_3q4kgb`）を用意し、3住民が独立 Agent・別アカウント・別 BrowserContext で目的だけを受けて探索した。住民には DB 操作・実装変更をさせていない。

| 住民 | 人格 × 攻め口 | 観測 |
| --- | --- | --- |
| ADMIN | 高齢・低リテラシー × 素直 | UI で対象シフト詳細と希望一覧まで到達。「提出率 0%」「0 / 9」「未提出 9」は読めるが、未提出者の名前や督促操作が見つからず目的未達。画面上の 4xx/5xx・console error はなし。 |
| MEMBER | 一般 × 素直（通知から希望提出へ進む） | 実 API には通知1件があるが、既存通知が多い画面で住民は対象を見つけられなかった。通知の actionUrl `/shifts/schedules/399` を開くと FE 404。 |
| SUPPORTER | 一般 × 素直（非対象者視点） | 通知 API 3ページ計208件を探索して対象通知0件。マイシフト画面は表示できた。 |

代表画像: [管理者のシフト詳細](evidence/cmp019-wave9/admin-schedule-detail.png)、[管理者の希望一覧](evidence/cmp019-wave9/admin-requests.png)、[MEMBER の通知一覧](evidence/cmp019-wave9/member-notification-list.png)、[MEMBER の通知遷移先404](evidence/cmp019-wave9/member-notification-404.png)、[SUPPORTER の通知一覧](evidence/cmp019-wave9/supporter-notifications.png)。画像を実際に確認した。その他の住民のローカル証跡は `frontend/test-results/alicization/cmp019-wave9/` に生成した。

## 観測の裏取りと残課題

探索 fixture ID 399 の手動督促 API は `remindedCount=7`。実 DB の通知7行は全員、現役 TEAM MEMBER（user_id 3, 4, 5, 6, 7, 23, 24）。通知 API 先頭100件では seed MEMBER に1件、seed SUPPORTER に0件。SUPPORTER の全208件も対象0件。画面上の到達が難しくても配送先判定は正しい。

- 管理画面の「未提出 9」は配信対象7人と一致しない。`ShiftRequestService.java` のサマリーは `userRoleRepository.countByTeamId(teamId)` を使い、実 DB の `user_roles` 9行には現役 MEMBER 7人と SUPPORTER 2人が入っていた。`requests.vue` はこの `pendingCount` を表示する。これは集計表示の別課題であり、本 Wave の督促配送先修正の合否とは分ける。
- 管理画面の詳細・希望一覧に手動督促の導線がなく、ADMIN 住民は API を介さずに送信できなかった。これは既存の FE 未実装範囲。
- 督促通知の `actionUrl` は BE `ShiftPreferenceReminderBatchService.java` で `/shifts/schedules/{id}` と作成され、FE `NotificationList.vue` はそのまま遷移する。FE に同ルートがなく、住民の実画面で 404 を確認した。配送は成功しても通知から目的の希望提出へ進めない別課題。
- MEMBER 住民が対象通知を見つけられなかった点は、通知画面に多数の既存通知があり探索困難だった事実として記録する。実 E2E は対象行を画面で探し、受信画像を取得している。

住民観測は以上の実画面・API・DB・コードで裏取りした。未実証の観測を本 Wave の blocker として扱わない。

## 後始末

決めたシナリオの schedule ID 397、398、400 と探索 fixture ID 399 はすべて DELETE API で soft delete 済み。各 ID に紐づく今回の `SHIFT_REQUEST_REMINDER_MANUAL` 通知だけを `source_type='SHIFT_SCHEDULE'` と `source_id` で限定削除した。実 DB の最終確認は **ID397/398/399/400 の全件 `DELETED:0 notifications`**。共有 fixture の他者データは変更していない。
