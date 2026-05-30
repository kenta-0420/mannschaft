# F04.11: ビジネスロジック

> **ステータス**: 🟢 設計確定（完了・未解決事項ゼロ）
> **最終更新**: 2026-05-30
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・A/B 案比較
> - [01_data_model.md](./01_data_model.md) — DB / DTO
> - [02_api_design.md](./02_api_design.md) — API
> - [F02.2_dashboard.md](../F02.2_dashboard.md) — `DashboardService`（集約パターンの手本）

---

## 1. サービス層構成

| サービス | 責務 | 手本 |
|---------|------|------|
| `InboxAggregationService` | 5 ソースを読み取り集約し `InboxItem` に正規化。状態/ラベルのオーバーレイをまとめ取りしてマージ | `DashboardService.getPersonalDashboard` |
| `InboxTriageService`（`@Transactional`）| snooze/unsnooze/archive/unarchive の upsert・遅延削除 | `NotificationService.snoozeNotification`（検証）|
| `InboxLabelService`（`@Transactional`）| ラベル CRUD・付与/解除・上限検証 | `ActionMemoTagService` |

`@Transactional` はすべて inbox ドメイン内に閉じる（原則 5）。ソース読み取りは各ドメイン Repository を**読み取り専用**で呼ぶ（書き込み越境なし）。

---

## 2. 5 ソースアダプタ仕様

各ソースを `InboxItem` に変換する **アダプタ・インターフェース** `InboxSourceAdapter` を定義し、ソースごとに実装する。新ソース追加は「アダプタ 1 実装の追加」で済む（保守性・[04](./04_security_operations.md) §6）。

```
interface InboxSourceAdapter {
  InboxSourceType sourceType();
  List<InboxItemRaw> fetch(Long userId, FetchWindow window);  // 読み取りのみ・ハードリミット付き
}
```

| アダプタ | ソース表 / リポジトリ | 取得条件 | title / excerpt | actionUrl | occurredAt |
|---------|--------------------|---------|----------------|-----------|-----------|
| `NotificationInboxAdapter` | `notifications`（F04.3）| `user_id` 一致・直近 N 件 | title / body | `action_url` | created_at |
| `AnnouncementInboxAdapter` | `AnnouncementFeedQueryRepository.findByScope`（＋`announcement_read_status`）| 本人の所属スコープ・未失効 | title_cache / excerpt_cache | 導出 `/announcements/{id}` | created_at |
| `MentionInboxAdapter` | `mentions`（F04.1）| `mentioned_user_id` 一致 | content_snippet | `MentionService.resolveUrl` 流用 | created_at |
| `ConfirmableInboxAdapter` | `confirmable_notification_recipients`（F04.9）| `user_id` 一致・`excluded_at IS NULL` | 親.title / 親.body | `action_url` or `/confirmations/{id}` | 親.created_at |
| `TodoDueInboxAdapter` | `todos`（F02.3）| 本人担当・`status IN (OPEN,IN_PROGRESS)`・`due_date` が近接/超過 | title | `/todos/{id}` | due_date |

> **announcement の注意**: `PersonalAnnouncementController.getPersonalFeed` は未実装（空返却）のため依存しない。`AnnouncementFeedQueryRepository.findByScope`（`DashboardService` が使用中）を直接利用する。
>
> **既読の取得**: 各アダプタはソース固有の既読（`is_read` / `announcement_read_status` の有無 / `is_confirmed`）を `InboxItemRaw.sourceRead` に載せる。TODO は既読概念がないため「完了=対象外」で表現。

---

## 3. priority 正規化（自動緊急度）

[01](./01_data_model.md) §3.2 の `InboxPriorityNormalizer`（純粋関数）で単一 `InboxPriority` に写像。種類（sourceType）はアダプタが自明に付与。**いずれも永続化せず、毎リクエスト導出**する（手作業ゼロ・要件 6）。

> **タイムゾーン（重要）**: TODO_DUE の境界判定（期限切れ/当日/3 日内）と confirmable の「締切 24h 以内」昇格は、**ユーザーのアカウントタイムゾーン**（タイムゾーン基盤・FE は `useDatetime`）で評価する。「当日」はサーバ UTC ではなくユーザー TZ の暦日で判定する。スヌーズプリセット（今晩/明日の朝/来週）の `snoozedUntil` 計算も同様にユーザー TZ で行い、API には絶対時刻（ISO8601・オフセット付き）で送る。サーバ側の `snoozed_until <= now` 比較は UTC 絶対時刻同士で行うため TZ 非依存。

---

## 4. 状態マージ（LEFT JOIN 相当）と一覧フロー

```
1. 各アダプタを呼び InboxItemRaw を集める（ソース毎ハードリミット・例: 各100件）
2. オーバーレイをまとめ取り:
     inbox_item_states を user_id で1クエリ取得 → Map<(sourceType,sourceId), state>
     inbox_label_links（+ notification_labels 現役）を user_id で1クエリ取得 → Map<(sourceType,sourceId), List<label>>
   （★N+1回避の肝。item毎には引かない）
3. 各 InboxItemRaw に state/labels を被せて InboxItem を確定:
     archived_at != null              → ARCHIVED
     snoozed_until > now              → SNOOZED
     上記以外 かつ sourceRead = true  → READ
     それ以外                         → UNREAD
4. state フィルタ（INBOX/SNOOZED/ARCHIVED/ALL）・priority・sourceType・labelId で絞り込み
5. (priority DESC, occurredAt DESC) でメモリソート → page*size でスライス
```

- **ページング方針**: 複数ソースマージのため真のカーソルは持てない。ソース毎ハードリミット内でのオフセットページング。「インボックス＝直近の仕分け場」と割り切り、深いページの網羅は非保証（[04](./04_security_operations.md) §5 に明記）。
- **孤児の除外**: アダプタが返すのは生存ソースのみ。オーバーレイにあってソースが消えた `(sourceType,sourceId)` はマージで自然に脱落（表示されない）。物理掃除は任意（Phase 3）。

---

## 5. スヌーズ自動復帰（バッチ不要）

B 案では復帰は **集約時判定** で実現する：

- スヌーズ＝`inbox_item_states.snoozed_until = 指定時刻` を upsert。
- 一覧 `state=INBOX` のクエリ条件に `(snoozed_until IS NULL OR snoozed_until <= now)` を含めるため、**時刻到来で自動的に受信箱へ復帰**（行の書き換え・バッチ不要）。
- `state=SNOOZED` は `snoozed_until > now` のみ。
- 復帰時に **push 再通知** を出したい場合のみ将来バッチを追加（Phase 3）。MVP では「開いたら戻っている」挙動で十分（ADHD 配慮＝勝手に流れてくる）。

---

## 6. 既存スヌーズの不整合是正（根治）

現状 `notifications` のスヌーズは **フロント/バックエンドが疎通していない**：

- フロント `useNotificationApi.snooze(id, duration)` は `{ duration }` を送る。
- バックエンド `SnoozeRequest` は `snoozedUntil`（`@Future LocalDateTime`）を期待。
- → フィールド名不一致で実質機能していない（対処療法で隠さず根治する＝CLAUDE.md 障害対応原則）。

**是正方針（MVP に含む）**:
1. インボックス API は `snoozedUntil`（ISO 日時）で統一。フロントがプリセットから日時を計算して送る。
2. 既存ベル UI 経由の snooze も同 DTO に揃える（`useNotificationApi.snooze` を `{ snoozedUntil }` 送信に修正）。
3. **二重管理の回避**: インボックス経路のスヌーズは `inbox_item_states` に一本化。集約の状態判定は **オーバーレイ優先 → 無ければ `notifications.snoozed_until` にフォールバック**（既存ベル経由 snooze との後方互換）。将来 `notifications.snoozed_until` をオーバーレイへ移送して deprecate（Phase 3、[04](./04_security_operations.md) §8）。

---

## 7. confirmable（確認必須通知）の二重持ち回避

確認状態の正本は `confirmable_notification_recipients.is_confirmed`（F04.9）。インボックスの archive/snooze は **確認状態とは独立**：

- インボックス上で「アーカイブ」してもタスク（確認）は完了しない。
- 「確認する」操作は F04.9 の確認 API（`action_url` 経由）へ誘導する。UI で「確認はこちら」を明示し、アーカイブ＝視界から消すだけ、確認＝義務の履行、を区別する（[04](./04_security_operations.md) §4 UX）。

---

## 8. 名寄せ（重複）方針

同一事象が複数ソースに現れる場合（例: 同じブログ記事が announcement と notification 両方）：

- **MVP は名寄せしない**（各行を素直に並べる）。ソース間で sourceType/targetType の語彙が不一致（VARCHAR vs enum vs targetType）で、安易な突合は「片方だけ既読/アーカイブ」になり ADHD ユーザーを混乱させるため。
- **Phase 3** で正規化辞書 `InboxDedupeKeyResolver`（`BLOG_POST` 等の終端 EntityKey へ正規化）を導入し、同一 EntityKey を 1 カードに畳んで「2 件の通知」とバッジ表示する（[04](./04_security_operations.md) §8 に将来課題として記録）。

---

## 9. 主要フロー（まとめ）

```
[一覧]   GET /inbox → アダプタ並列読取 → オーバーレイ/ラベルまとめ取り
         → 状態マージ → フィルタ → ソート → ページスライス
[退避]   POST /inbox/snooze|archive → inbox_item_states upsert（楽観更新で即UI反映）
[復帰]   時刻到来 → 次回 GET /inbox（state=INBOX）で自動的に再表示（バッチなし）
[ラベル] POST /inbox/labels/{id}/assign → inbox_label_links insert（重複は冪等）
```
