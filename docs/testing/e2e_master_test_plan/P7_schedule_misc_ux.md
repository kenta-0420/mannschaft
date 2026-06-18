# P7 スケジュール周辺・横断UX・マッチング・村・公開 E2E テスト法案

> 対象: F03.2/F03.3/F03.7/F03.8/F03.9/F03.10/F03.13 / F02.2/6/7/9/10 / F08.1/F08.3/F08.7.1/F13.1 / F14.1/F15.1/F15.4/F16.1/F17.1/F19.1 / F11.x/F12.4/F12.5
> ※ F03.1/F03.4/F03.5/F03.6/F05.4 は P8/P9 で別途。凡例は [README](./README.md) 参照。
> ⚠️ **裏取り品質 △**: 機能数が膨大で「推定」判定が多い。実機 reachability で確定する。

---

## 1. トレーサビリティ監査サマリ（優先度順・実装確認できた範囲）

| 機能 | 主要要素 | 実装 | 判定 |
|---|---|---|---|
| **F03.8 イベント管理** | CRUD(DRAFT→PUBLISHED→REGISTRATION_OPEN)/チケット種別/参加登録(メンバー・ゲスト)/QRチェックイン+ライブDB/点呼(Roll Call・代理)/タイムテーブル | EventService/EventCheckinTable.vue/roll-call.vue | 🟢 |
| F03.8 有料チケット決済(Stripe) | event_ticket_types.payment_item_id→payment_items | チェックアウトUI要確認 | 🟡 |
| F03.8 イベント公開SSR(OGP) | is_public/ogp_* | /events/{slug} 要確認 | 🟡 |
| **F03.3 Google/iCal連携** | OAuth接続/チーム同期ON-OFF/iCal購読URL(RFC5545,ETag,Valkey15分) | GoogleCalendarController/IcalController | 🟢 |
| F03.2 個人スケジュール | 個人予定CRUD(/me/calendar)/リマインダー | PersonalScheduleController | 🟢(リマインダーUI🟡) |
| **F03.7 順番待ち** | QR取得/ネット予約/リアルタイム待ち状況(WS `/topic/queue`)/呼出/NO_SHOW/一時離席 | Queue系(要実ファイル) | 🟡 推定 |
| F19.1 公開ページ+識別開示 | 公開チーム/組織/ユーザーページ(SSR)/OGP | /public/* | 🟢 推定 |
| F17.1 村機能 | 村作成/参加/掲示板/イベント・マッチング募集/ニュースレター | /villages/[id]/* | 🟡 推定 |
| F08.1 マッチング/F13.1 スキマバイト/F14.1 代理入力/F15.4 店舗検索 | 各ページ存在 | 🟡 推定(管理画面要確認) |
| F02.2/6/7/9/10 ダッシュボード系 | 横断カレンダー/お知らせ/関所/お気に入り/天気 | 一部 🟡 推定 |

---

## 2. E2E 実機シナリオ（代表・トレーサ付き）
- **[F03.8-E01〜06]** イベント作成(無料+有料チケット)→参加登録→有料は Stripe Checkout→QRチェックイン(ライブ率更新)→点呼起動→出席確認。（§5/roll-call.vue）★最重点
- **[F03.3-E01〜04]** Google OAuth接続→チーム同期ON→予定が Google同期 / iCalトークン発行→外部購読URL / ETag 304。（§4/§5）
- **[F03.7-E01〜05]** GUEST QR受付→チケット発行→待ち人数表示 / ネット予約(営業時間外422) / 呼出 CALLED→タイムアウトで自動NO_SHOW / 一時離席 hold_until延長。（§4/§5）
- **[F19.1-E01〜03]** 公開設定ON→公開URL→未ログインSSR表示→Twitter リッチプレビュー(OGP)。
- **[F17.1-E01〜03]** 村作成(村長)→参加申請→承認→村内マッチング募集。

---

## 3. このフェーズの「設計にあるが UI/導線が無い」確定（要実機確認多数）
| 機能 | 状態 |
|---|---|
| F03.2 リマインダー設定UI / Google同期設定UI(/settings/calendar-sync) | 🟡 要確認 |
| F03.8 イベント公開SSR(/events/{slug}) / 有料チケット決済UI | 🟡 要確認 |
| F03.8 イベント統計ダッシュボード | 🟡 設計完了・実装未確認 |
| F17.1 村ニュースレター設定(/newsletter-settings) | 🟡 要確認 |

## 4. 未実装(設計のみ)簡潔一覧
- F03.2 Google↔App 双方向同期(Webhook受信, Phase4+)、F03.7 営業時間終了時の自動キャンセル(翌日バッチ)、F03.8↔F05.4/F05.6 連携(イベント完了→アンケート/企画承認)。

## 5. 既存 E2E spec ギャップ / 推奨追加
- 推奨追加(P7 スコープ): event-detail(8) / schedule-detail(5) / queue(8) / matching-detail(5) / skills(4) / villages/detail(6) / public/pages(5) ≒ **41テスト**。
- **裏取り再確認**: 本フェーズの 🟡推定 は実機 reachability で UI 実在を確定すること。
