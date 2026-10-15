# CMP-019 Wave 10 実機・アリシゼーション（2026-09-27）

- 対象: Issue #3477。管理者の手動督促 UI、現役 MEMBER への通知リンク、管理画面の MEMBER 分母。
- 環境: 実 BE `localhost:8081`、実 FE `localhost:3003`、実 MySQL、Playwright Chromium。BE JAR は JDK 21 で作成し、ローカル実行には JDK 25 を使用した。

## 決めたシナリオの実機 E2E

`frontend/tests/e2e/real/cmp-019-shift-reminder-ui.real.spec.ts` は **1 passed (4.0m)**。共有 seed のロールと在籍を実 DB で確認し、新規シフト ID 405 を作成した。チーム ADMIN が詳細 `/shift/405` のボタンと確認画面から手動督促を送信し、応答・通知 API・実 DB の配信先が現役 MEMBER 7 人で一致した。SUPPORTER とチーム非所属者は受信せず、両者が通知 URL を直接開いても対象シフト名と入力フォームは表示されなかった。管理者の希望一覧とサマリー API は提出 `0 / 7`・未提出 7。MEMBER は実際の[通知](evidence/cmp019-wave10/member-received.png)をクリックし、[対象シフトの希望入力フォーム](evidence/cmp019-wave10/member-form.png)へ到達した。削除済みシフトの同 URL では MEMBER にも対象情報を表示しなかった。代表画像は実際に開いて確認した。

fc-u-18 の seed に現役 GUEST はない。実機の非対象者確認は SUPPORTER とチーム非所属者であり、GUEST の実機確認とは扱わない。

## 3住民の自由探索

決めたシナリオとは別の ID 404（`CMP019_W10_EXPLORE_1790446563621`）を用意し、ADMIN・MEMBER・SUPPORTER の別 Agent に目的だけを渡した。各住民は別 BrowserContext と別アカウントを使用し、共有データは変更していない。通知7件の実 DB 配信先は全員現役 TEAM MEMBER（user_id 3、4、5、6、7、23、24）で、actionUrl は全件 `/my/shift-request?teamId=1&scheduleId=404`。探索後に住民の追加送信はない。

| 住民 | 観測 |
| --- | --- |
| ADMIN（高齢・低リテラシー） | 初回は自然な `/shift` → 詳細の導線に督促ボタンがなく目的未達。この発見を受け詳細画面にボタンを追加した。再探索では同じ導線から[詳細画面](evidence/cmp019-wave10/admin/03-schedule.png)のボタンと[確認画面](evidence/cmp019-wave10/admin/04-reminder-confirmation.png)へ到達し、キャンセルで閉じた。対象人数や既送信履歴は確認画面に表示されない。 |
| MEMBER（通知から希望提出へ進む） | 通知画面に既存通知が大量にあり、先頭50件に対象がなく、追加閲覧のタイムアウトで本人の自然探索では対象通知に到達しなかった。API と DB は受信を確認。決めた E2E は通知一覧をさらに読み込み、対象行をクリックしてフォームまで到達した。 |
| SUPPORTER（誤配信・誤誘導を探す） | API ログインヘルパー後の画面は公開トップだった。通常ログインもフォーム検証でボタンが無効のままタイムアウトし、認証済み通知画面には未到達。したがって住民本人の UI 探索では不配信・非誘導を確認できていない。[ログイン時の画面](evidence/cmp019-wave10/supporter-login.png)を保存した。実機 E2E の通知 API・画面・直接 URL と DB では不配信・非誘導を確認した。 |

## 観測と残課題

- ADMIN の初回発見は詳細画面のボタン追加後、本人による再探索で解消した。決めた E2E でも詳細からの送信を確認した。
- MEMBER の自然探索では通知が大量の既存通知に埋もれていた。配信・リンク先の正しさとは別に、対象通知を自然に見つけやすいかは未解決。
- SUPPORTER 住民は UI 認証に到達できず、本人の自由探索による結論は出せない。自動 E2E と実 DB の結果で対象外を検証した。

## 後始末

今回の schedule ID 401〜405 はすべて DELETE API で soft delete 済み。各 ID に紐づく `SHIFT_REQUEST_REMINDER_MANUAL` 通知だけを `source_type='SHIFT_SCHEDULE'` と `source_id` で限定削除した。実 DB の最終確認は **401〜405 の各 ID が `DELETED:0 notifications`**。共有 seed の他者データは変更していない。
