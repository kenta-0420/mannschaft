# P2 通知・チャット・タイムライン E2E テスト法案

> 対象: F04.1 / F04.2(+4.2.1) / F04.3 / F04.5 / F04.9 / F04.11 / F02.6 / F04.6
> 凡例・テスト層は [README](./README.md) 参照。

---

## 1. トレーサビリティ監査サマリ

このドメインは**比較的よく実装されている**。WebSocket(STOMP)リアルタイム配信は設計の逐語要求に対し実装裏取り済み。

| 機能 | 設計の核心(逐語) | 実装 | 判定 |
|---|---|---|---|
| **F04.1 タイムライン** | §1「プッシュ通知は使用せず、WebSocket によるリアルタイム新着配信」 | TimelineWebSocketController + `/topic/posts/{scopeId}` / TimelinePostCard.vue | 🟢 |
| F04.1 リアクション/ブックマーク/検索 | §4 | TimelineReaction/Bookmark/Search Service / FE 各UI | 🟢 |
| **F04.2 チャット** | §1「WebSocket(STOMP)+Valkey メッセージブローカー」/ §4.11「WebSocket で全チャンネルメンバーに配信」 | ChatMessagePublisher + `@SendTo("/topic/channels/{id}")` / ChatComposer.vue | 🟢 |
| F04.2 深さ無制限スレッド/既読/アーカイブ | §3.2/§1 | ChatMessageEntity(parentId/rootId/depth) / ChatThreadViewer | 🟢 |
| F04.2 メッセージ検索 | §4 | ChatMessageService.search()(FULLTEXT) | 🟡 検索UIパネル要確認 |
| **F04.3 通知** | §4.12「`/user/queue/notifications`」/ §5.1「WebSocket 接続中ユーザーにリアルタイム配信」 | NotificationWebSocketController + `@SendToUser` / NotificationPanel.vue | 🟢 |
| F04.3 既読 | §4.2 | NotificationService.markRead() | 🟢 |
| **F04.3 スヌーズ** | §4.5「30m/1h/3h/tomorrow」/ §5.1「snoozed_until に SET」 | NotificationService.snooze() / **snooze dropdown UI 要確認** | 🟡 |
| **F04.3 スヌーズ自動復帰バッチ** | §5「バッチ設計」 | **未実装(DBカラムのみ)** ※F04.11で集約時判定へ設計変更の可能性 | 🔴 |
| F04.5 通報/モデレーション | §4 | ReportService / ReportAdminService / ReportDialog.vue / AdminReportPage.vue | 🟢(一部 dialog 🟡) |
| F04.9 確認通知 送信/確認/トークン確認/保留中一覧 | §6 | ConfirmableNotificationService / 各 FE | 🟢 |
| F04.9 リマインド/期限切れバッチ | §7 | ReminderBatchService / ExpiryBatchService | 🟢(非UI) |
| **F04.9 複数スコープ一括回答 UI** | §4.12 後述 | **設計のみ未実装** | 🔴 |
| F04.11 インボックス(オーバーレイ3表/取得/アーカイブ) | 01_data_model / 02_api_design | InboxService / InboxItemState / InboxPage.vue | 🟢 |
| **F04.11 スヌーズプリセットUI / ラベル付与UI** | §1.2/§4.2 | API/テーブルあり / **UI 要現地確認** | 🟡 |
| F04.11 5ソースアダプタ集約 | §3 | NotificationInboxAdapter 他4 | 🟡 統合リスト要確認 |
| F02.6 お知らせウィジェット(横断/ピン/既読/個人集約) | §3.2/§4 | AnnouncementFeedService / WidgetAnnouncements.vue | 🟢 |
| F04.6 グローバル検索(横断/サジェスト/履歴) | §4 | GlobalSearchService / SearchResultPage.vue | 🟢 |

---

## 2. E2E 実機シナリオ（54 ケースを 5 層で起案・代表抜粋）

### Reachability（到達導線）12 件
- [T-R001] sidebar timeline → `/teams/{id}/timeline` 表示
- [T-R003] header bell → 通知パネル右スライドイン・未読バッジ
- [T-R004] dashboard → Inbox リンク → `/inbox`
- [T-R006/007] header search → モーダル・サジェスト表示
- [T-R008] SYSTEM_ADMIN → moderation → 未対応通報一覧
- [T-R010] チャット投稿クリック → スレッド展開
- [T-R012] お知らせ ピン留めアイコン（ADMIN のみ表示・MEMBER 非表示）
- ほか R002/005/009/011

### API Functional 20 件（代表）
- [T-A001] POST timeline/posts → 201・created_at
- [T-A004] スレッド返信 parent_id/root_id/depth 検証
- [T-A007] PATCH notifications/{id}/snooze {1h} → snoozed_until=NOW()+1h
- [T-A013] POST teams/{id}/confirmable-notifications → total_recipient_count・status=ACTIVE
- [T-A014] POST confirmable/{id}/confirm → is_confirmed=true・confirmed_via=APP
- [T-A018] GET /inbox → 5ソース統合・type_counts

### Realtime Delivery（WebSocket/STOMP）8 件 ★重点
- [T-RT001] タイムライン投稿が他ユーザー画面に **2秒以内・喪失0%**（`/topic/posts/{id}`）
- [T-RT002] チャットが他メンバーに **1秒以内**・unread_count 自動増
- [T-RT003] 個人通知トースト **0.5秒以内**（`/user/queue/notifications`）
- [T-RT006] WS 切断→自動再接続→`GET notifications?is_read=false` でキャッチアップ
- [T-RT008] 確認通知の確認率が admin 画面で **リアルタイム更新**（※実装が STOMP push か polling か要裏取り — C 隊で「polling 前提」の疑義あり）

### Integration（クロスドメイン）6 件
- [T-I001] @メンション投稿 → notifications INSERT → WS 配信 → action_url 遷移
- [T-I003] 確認通知の二重レコード（confirmable_notifications 親 + recipients + notifications）同期
- [T-I006] インボックス 5 ソース集約（snooze/archive 済は除外）

### Authorization（IDOR・スコープ境界）8 件
- [T-Auth001] SUPPORTER に MEMBERS_AND_ABOVE のお知らせ非表示
- [T-Auth002] 未参加チャンネル → 403
- [T-Auth003] 他者の通知を read 試行 → 403（JWT user_id 不一致）
- [T-Auth005] 通報者の匿名性（reporter_id を SYSTEM_ADMIN にも返さない）

---

## 3. このフェーズの「設計にあるが UI/導線が無い」確定

| 機能 | 状態 | 備考 |
|---|---|---|
| 通知スヌーズ自動復帰バッチ | 🔴 | DBカラムのみ。**ただし F04.11 で「集約時 `snoozed_until>NOW()` 判定」へ設計変更済の可能性** → 原典 02/03 を精読し「バッチ不要が正」か確定要 |
| 確認通知 複数スコープ一括回答 UI | 🔴 | 設計のみ |
| 通知スヌーズ プリセット UI | 🟡 | API あり・UI 要現地確認 |
| インボックス ラベル付与 UI | 🟡 | inbox_item_labels あり・UI 要現地確認 |
| チャット メッセージ検索 UI | 🟡 | FULLTEXT 実装・UI パネル要確認 |

> ⚠️ **疑義の裏取り**: 「リアルタイム既読が WebSocket か polling か」は C 隊と P2 隊で見解が割れている（C隊=polling前提、P2隊=STOMP実装あり）。**実機 E2E（T-RT008）で確定**する。思い込みで「未実装」と断じない。

---

## 4. 既存 E2E spec ギャップ
- ✅ 接続/基本: timeline/chat/notifications/confirmable spec あり
- 🟡 補強要: WS 配信遅延実測(T-RT001-008)、クロスドメイン UI 同期(T-I*)、ロール混合認可(T-Auth*)、スレッド深度 depth≥5
