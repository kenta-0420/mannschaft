# F04.11: ビジネスロジック

> **ステータス**: 🟢 設計確定（完了・未解決事項ゼロ）
> **最終更新**: 2026-06-01
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
  List<InboxItemDto> fetch(Long userId, int window);  // 読み取りのみ・境界付きウィンドウ（最大 window 件）
  boolean isVisibleTo(Long userId, Long sourceId);    // IDOR 防止（triage 書き込み前検証）
}
```

> **境界付きウィンドウ（Phase 3 ③）**: `fetch` は「自ソース内の正しい順序の上位 `window` 件」だけを返す。集約サービスが全ソースの上位ウィンドウを完全全順序でマージ・スライスして取りこぼさない（§4.1）。各アダプタは `Pageable`（`PageRequest.of(0, window)`）等で取得件数を境界付ける（無制限 fetch なし）。

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

## 4. 状態マージ（LEFT JOIN 相当）と一覧フロー — 境界付きウィンドウページング（Phase 3 ③ 実装済み）

```
0. 取得ウィンドウを算出: window = (page+1)*size + SAFETY_MARGIN（= 20）
1. 各アダプタを window 件で呼び InboxItemRaw を集める（境界付きウィンドウ・各アダプタは Pageable で window 件まで）
2. オーバーレイをまとめ取り:
     inbox_item_states を user_id で1クエリ取得 → Map<(sourceType,sourceId), state>
     inbox_label_links（+ notification_labels 現役）を user_id で1クエリ取得 → Map<(sourceType,sourceId), List<label>>
   （★N+1回避の肝。item毎には引かない）
3. 各 InboxItemRaw に state/labels を被せて InboxItem を確定:
     archived_at != null              → ARCHIVED
     snoozed_until > now              → SNOOZED
     上記以外 かつ sourceRead = true  → READ
     それ以外                         → UNREAD
4. 名寄せ畳み込み（§8 foldByCanonicalRef）をウィンドウ全体に対して行う（畳み後の件数でページングが効く）
5. state フィルタ（INBOX/SNOOZED/ARCHIVED/ALL）・priority・sourceType・labelId で絞り込み
6. 完全な全順序（priority DESC → occurredAt DESC → sourceType名 → sourceId）でメモリソート
   → [page*size, (page+1)*size) をスライス。hasMore = 畳み後フィルタ後件数 > (page+1)*size
```

### 4.1 ページング方針 — なぜ「境界付きウィンドウ」か（方針更新 2026-06-01）

> **旧方針（〜2026-05-30）**: 「ソース毎ハードリミット（各100件等）＋オフセットスライス・深いページは取りこぼし許容」。
> **新方針（Phase 3 ③ 以降）**: 「**境界付きウィンドウページング**＝各ソースを `Pageable` で window 件まで取得し、完全全順序で決定的にマージ・スライス。当該ページの直近上位を取りこぼさない」。

**複合キーセットカーソルを採らない理由**: 5 ソース横断で「真のカーソル」（複合キーセット）を持つと、各ソースの順序キーが異なる（priority・occurredAt・タイブレークの混在）ため境界条件のバグ温床になり、`{items, page, size, totalEstimated, hasMore}` という **既存 FE レスポンス契約も壊す**。よって複合カーソルは採らず、**境界付きウィンドウ**で「決定的（重複なし・load-more 連続）」を達成する。

**ページング保証の正確な定義（不変条件と限界・是正 2026-06-01）**:

> ⚠️ **以前の「取りこぼしゼロ」断定は不正確だった**。境界付きウィンドウの「欠落しない」不変条件は、**各ソースの fetch 順がグローバル全順序（priority 第一）と整合するとき**にのみ成立する。実 fetch 順が priority と独立なソース（ANNOUNCEMENT/CONFIRMABLE）は、古いが高 priority の項目が window 外へ脱落しうる。以下のとおり**ソース別に保証レベルを正直に記す**。

- 境界付きウィンドウは「`window = (page+1)*size + margin >= (page+1)*size = K` 件を各ソースから取り、集約後に上位 K 件をスライスする」。
- **不変条件**: 各ソースが「自ソース内を**グローバル全順序と整合する順序**で並べた上位 `window` 件」を返せば、グローバル上位 K 件はその和集合に必ず含まれ、当該ページ `[page*size, (page+1)*size)` の項目は欠落しない。
- ソートは **完全な全順序**（priority DESC → occurredAt DESC → **sourceType名 → sourceId** のタイブレーク）。同着をタイブレークで一意化し、ページ境界で並びが揺れて隣接ページに項目が漏れる事故を防ぐ（決定的スライス）。
- `SAFETY_MARGIN`（20）は名寄せ畳み込みによる件数減・境界の同着連なりを吸収する余裕。

**ソース別の取りこぼし保証**:

| アダプタ | 取得方法 | 自ソース fetch 順 | グローバル順との整合 | 取りこぼし保証 |
|---|---|---|---|---|
| NOTIFICATION | `findInboxByUserIdOrderByPriorityThenCreatedAtDesc(userId, excludedType, PageRequest.of(0, window))`（**priority 第一順クエリを新設**） | priority 降順（URGENT→HIGH→NORMAL→LOW）→ created_at 降順 | **整合** | **取りこぼしなし** |
| MENTION | `findByMentionedUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, window))`（**Pageable 版**） | created_at 降順 | **整合**（priority 一律 HIGH ゆえ priority による並べ替えが起きない） | **取りこぼしなし** |
| TODO_DUE | `findMyDueTodos(userId, cutoff, PageRequest.of(0, window))`（DB 側で「未完了∧due_date≤cutoff」に絞り due_date 昇順） | due_date 昇順（期限切れ→当日→近接） | **整合**（due_date 昇順 ↔ priority 降順 URGENT→HIGH→NORMAL が等価） | **取りこぼしなし** |
| ANNOUNCEMENT | `findByScope(..., limit=window)` をスコープ毎に（**DashboardService と共有・ORDER BY 改変不可**） | ピン留め優先→created_at 降順 | **独立**（priority と無関係） | **限界あり**（下記） |
| CONFIRMABLE | `findByUserIdAndIsConfirmedFalseAndExcludedAtIsNullWithNotification(userId, PageRequest.of(0, window))`（JOIN FETCH＋Pageable） | 親 created_at 降順 | **独立**（24h 昇格は時刻依存で SQL 順序化困難） | **限界あり**（下記） |

> **ANNOUNCEMENT の限界（正直な明文化）**: 取得順は `is_pinned, created_at` で priority と独立。`findByScope` は `DashboardService.getTeamDashboard`/`getOrgDashboard` と**共有**しており、ここで ORDER BY を priority 第一に改変すると他機能（ダッシュボードのお知らせ表示順）へ波及するため**改変しない**。結果として、**古い URGENT お知らせが多数の新着お知らせに埋もれて window 外へ脱落**し、後ページ送りになりうる。ただし pinned/有効なお知らせ件数は通常小さく、「直近の仕分け場」用途では実害が限定的。
>
> **CONFIRMABLE の限界（正直な明文化）**: 親 created_at 降順で取得するが、priority は「未確認かつ締切 24h 以内なら URGENT 昇格」（時刻依存・[01](./01_data_model.md) §3.2）。この昇格は現在時刻に依存するため SQL の ORDER BY で順序化するのが困難で、**created_at が古いが締切 24h 以内で URGENT 昇格すべき項目が window 外へ脱落**し、稀に順位逆転で後ページ送りになりうる。ただし**保留中（未確認）の確認通知は通常ごく少数**ゆえ window 内にほぼ収まり、実害は限定的。
>
> ANNOUNCEMENT・CONFIRMABLE の完全な priority 第一化は、共有クエリの分岐 or 専用クエリ新設・時刻依存順序のマテリアライズが必要であり、将来課題とする。

> **CONFIRMABLE / TODO_DUE の従来「無制限 fetch」を根絶**: 以前は MENTION/CONFIRMABLE/TODO_DUE が全件取得していた。Phase 3 ③ で全ソースを `Pageable`（または DB 側絞り込み＋ Pageable）に統一し、メモリに載る件数を `window` で境界付けた。TODO_DUE は従来 `findMyTodos` の全件取得＋Java フィルタだったが、DB 側で「未完了∧近接/超過」に絞ってから上限を掛ける `findMyDueTodos` に置換した。

> **サマリ（タブ/バッジ）**: `getSummary` は概算で十分なため `SUMMARY_WINDOW`（500）の広めウィンドウで集計する。

- **孤児の除外**: アダプタが返すのは生存ソースのみ。オーバーレイにあってソースが消えた `(sourceType,sourceId)` はマージで自然に脱落（表示されない）。物理掃除は任意（Phase 3）。

---

## 5. スヌーズ自動復帰（バッチ不要）

B 案では復帰は **集約時判定** で実現する：

- スヌーズ＝`inbox_item_states.snoozed_until = 指定時刻` を upsert。
- 一覧 `state=INBOX` のクエリ条件に `(snoozed_until IS NULL OR snoozed_until <= now)` を含めるため、**時刻到来で自動的に受信箱へ復帰**（行の書き換え・バッチ不要）。
- `state=SNOOZED` は `snoozed_until > now` のみ。
- 復帰時に **push 再通知** を出したい場合のみ将来バッチを追加（Phase 3）。MVP では「開いたら戻っている」挙動で十分（ADHD 配慮＝勝手に流れてくる）。

### 5.1 スヌーズ復帰 push 再通知（Phase 3 ②・実装済み）

MVP の「開いたら戻っている」だけでは能動的な催促が無いため、Phase 3 ② で **復帰 push 再通知バッチ**を追加した。

- **バッチ**: `inbox/batch/InboxSnoozeRevivalBatchService`（`@BatchEndpoint(name="inbox-snooze-revival")`）。
  - スケジュール: 5 分毎（`cron="0 */5 * * * *" zone="Asia/Tokyo"`）。`@SchedulerLock(lockAtMostFor="PT15M", lockAtLeastFor="PT10S")` で多重起動を防ぐ。
  - 横断クエリ: `InboxItemStateRepository.findDueForRevival(now, Pageable)` が **全ユーザー横断**で
    `snoozed_until <= now AND snooze_notified_at IS NULL AND archived_at IS NULL` を `snoozed_until` 昇順に取得（1 回 500 件上限で暴走防止）。
  - **復帰 push はベストエフォート 1 回**: 各行へ push を **1 度だけ**試行し、**成否に関わらず** `snooze_notified_at = now` を刻んで保存する。これにより **2 回目以降の実行では再送しない（冪等・上限 1 回）**。
    push 送信が例外でも `snooze_notified_at` を刻んで再送しない（旧仕様の「失敗行は stamp せず次回バッチで再試行」は **5 分毎の無限再試行**を招くため反転した）。
    失敗した事実は `log.error` に必ず残す（症状を隠さない＝根治原則）。`sent` カウンタは成功時のみ加算する。
    **恒久失敗のサブスク掃除は委譲**: 送信先が無効（HTTP 410/404）な Web Push サブスクは `WebPushService` が `deleteByEndpoint` で失効掃除し、429/5xx は内部で `MAX_RETRY_COUNT` までリトライしてから諦める。
    したがって本バッチ側に **DLQ・リトライ上限列は設けない**（恒久失敗の後始末は push 基盤に集約）。
- **再スヌーズ時のリセット**: `InboxTriageService.snooze` が upsert 時に `snooze_notified_at` を NULL に戻す。
  これにより新しい `snoozed_until` 到来時に再度 1 度だけ push できる。

#### 二重 push / 自己増殖の回避（最重要設計判断）

push 基盤（`NotificationDispatchService.dispatch` → WebSocket `/user/{userId}/queue/notifications` ＋ Web Push）は
`NotificationEntity` を引数に取る設計で、**「通知行を作らず push のみ送る」クリーンな経路は存在しない**
（`sendViaWebSocket`/`sendViaPush` ともに `NotificationMapper.toNotificationResponse(entity)` で DTO 化するため、
永続化されていない transient entity を渡すのは脆い）。そのため設計書 §5 が提示する **方針 2** を採用した:

- 復帰 push は `NotificationHelper.notify` で **専用通知種別 `INBOX_SNOOZE_REVIVAL`** として発行する
  （定数: `com.mannschaft.app.inbox.InboxNotificationTypes.INBOX_SNOOZE_REVIVAL`）。
  - `source_type="INBOX_REVIVAL"` / `source_id=null` とし、`NotificationService` の visibility ガードは
    fail-soft で通過する（元項目の可視性はスヌーズ時に検証済み）。`actionUrl="/inbox"`。
- **`NotificationInboxAdapter.fetch` でこの種別を除外**する
  （Phase3 ③ で priority 第一順の `NotificationRepository.findInboxByUserIdOrderByPriorityThenCreatedAtDesc` に切替。
  種別除外の WHERE 条件は同一・取得順を priority 第一にしたのが差分。created_at 降順のみの
  `findByUserIdAndNotificationTypeNotOrderByCreatedAtDesc` は他用途向けに温存）。
  → 復帰 push は**ベル/通知一覧には出る**（「あとで見るがそろそろ」の催促）が、
  **インボックス受信箱には新規カードを生まない**。受信箱には元のスヌーズ項目が §4・§5 の集約時判定で
  自然に復帰するのみ。これにより push 通知が NOTIFICATION ソースの inbox 項目を増殖させる二重化を根治する。

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

## 8. 名寄せ（重複）方針 — Phase 3 ① 実装済み

同一終端実体が複数ソースに現れる場合（例: 同じブログ記事が announcement と notification 両方）に、1 カードへ畳んで「N 件」とバッジ表示する。誤突合は ADHD ユーザーを最も混乱させる最高リスク領域のため、**「正規化に成功し、かつ終端実体キーが一致するときに限り畳む」**ことを厳守する。

### 8.1 正規化キー解決（`InboxDedupeKeyResolver`）

各ソース通知が指す**終端実体**を `canonicalRef`（文字列 `"{ReferenceType}:{terminalId}"`）へ正規化する。正規化辞書は既存の `NotificationSourceTypeMapper.resolve(String) → Optional<ReferenceType>` を流用する（語彙の二重管理を避ける）。

| ソース | 終端実体の取り方 | canonicalRef |
|---|---|---|
| NOTIFICATION | `notifications.sourceType`（VARCHAR）+ `sourceId` を正規化 | 成功時 `"BLOG_POST:123"` 等／不能時は自分自身 `"NOTIFICATION:{id}"` |
| ANNOUNCEMENT | `announcement_feeds.sourceType`（enum `AnnouncementSourceType`）+ `sourceId` を正規化（**feed は終端 sourceType+sourceId を保持する**） | 成功時 `"BLOG_POST:123"` 等／不能時は `"ANNOUNCEMENT_FEED:{feedId}"`（畳まれない） |
| MENTION | `mentions.targetType`（VARCHAR）+ `targetId` を正規化 | 成功時 `"TIMELINE_POST:42"` 等／不能時（`TIMELINE_COMMENT` 等 ReferenceType 未マッピング語）は自分自身 `"MENTION:{id}"` |
| TODO_DUE | 固有実体（畳む相手なし） | 常に自分自身 `"TODO_DUE:{id}"` |
| CONFIRMABLE | 固有実体（畳む相手なし） | 常に自分自身 `"CONFIRMABLE:{recipientId}"` |

**誤突合の安全弁**: `terminalId` が null、または `sourceType` が `ReferenceType` に未マッピングの場合は正規化不能（`Optional.empty()`）とし、各アダプタが**自分自身キー**（当該項目に固有・他項目と決して衝突しない値）を `canonicalRef` に詰める。よって正規化不能な項目は決して他項目と同一グループにならない。

### 8.2 畳み込み（`InboxAggregationService.foldByCanonicalRef`）

`collectMergedItems` の後段で `canonicalRef` でグルーピングし、**2 件以上のグループのみ** 1 代表へ畳む（単一はアダプタ既定 `groupCount=1`・`groupMembers` 自分 1 件のまま）。

- **代表**: グループ内で `ITEM_ORDER` 最上位（priority 最優先 → 新着）。
- **`groupCount`**（`InboxItemDto.groupCount`・int）: 構成メンバー件数。FE の「N 件」バッジ用。
- **`groupMembers`**（`List<InboxItemRef{sourceType, sourceId}>`）: 全構成メンバーの `(sourceType, sourceId)` を公開。FE は **Phase 2 の bulk triage API** で各メンバーへ一括適用し「片方だけ既読/アーカイブ」を防ぐ（BE triage API は単一のまま＝今回はデータ公開のみ）。
- **state**: 構成メンバーの最も未処理側（UNREAD > READ > SNOOZED > ARCHIVED の優先順）。
- **labels**: 全メンバーのラベル和集合（`labelId` で重複排除）。

`InboxItemDto` に `canonicalRef`（String）・`groupCount`（int）・`groupMembers`（List<InboxItemRef>）の 3 フィールドを追加した。`InboxItemRef` は新規小 record（`{InboxSourceType sourceType, Long sourceId}`）。`sourceId` は各ソースのチャネル行 PK（triage オーバーレイ `inbox_item_states` のキー）であり終端実体 ID ではない点に注意。

---

## 9. 主要フロー（まとめ）

```
[一覧]   GET /inbox → アダプタ並列読取 → オーバーレイ/ラベルまとめ取り
         → 状態マージ → フィルタ → ソート → ページスライス
[退避]   POST /inbox/snooze|archive → inbox_item_states upsert（楽観更新で即UI反映）
[復帰]   時刻到来 → 次回 GET /inbox（state=INBOX）で自動的に再表示
         ＋ Phase3 ②: 5分毎バッチが復帰push（INBOX_SNOOZE_REVIVAL）を1度だけ送る（受信箱には増殖させない・§5.1）
[ラベル] POST /inbox/labels/{id}/assign → inbox_label_links insert（重複は冪等）
[提案]   GET /inbox の各カードに suggestedLabels[] を導出（非永続）
         → 提案チップ 1 タップ → POST /inbox/labels/suggest-apply（find-or-create + assign・冪等）
```

---

## 10. 自動ラベリング提案（案C「提案＋1タップ付与」・Phase 4）

マスター御裁可＝**案C**。AI/ヒューリスティックで**自動付与はしない**。代わりに静的ルールで「提案」を**読み取り時に導出（非永続・DDL なし）**し、ユーザーが提案チップを **1 タップ**したときだけ実ラベルを find-or-create して付与する。入力摩擦ゼロ・誤分類リスクなし（ADHD 要件）。

### 10.1 提案ルール表（最終形）

`InboxLabelSuggestionRules.suggest(sourceType, priority)` が `(InboxSourceType, InboxPriority)` から提案キー（`InboxLabelSuggestion` enum）を導出する**純関数**。**1 アイテムあたり提案は最大 1 件**に絞る（提案過多を避ける）。

| sourceType | priority 条件 | 提案キー（enum） | 既定色 |
|---|---|---|---|
| `MENTION` | 不問 | `REPLY_NEEDED` | `#2563EB` |
| `CONFIRMABLE` | `URGENT` または `HIGH` | `ACTION_NEEDED` | `#DC2626` |
| `TODO_DUE` | `URGENT`（期限切れ） | `URGENT` | `#EA580C` |
| `ANNOUNCEMENT` | 不問 | `READ_LATER` | `#6B7280` |
| `NOTIFICATION` | `URGENT` | `ACTION_NEEDED` | `#DC2626` |
| 上記以外 | — | （提案なし＝空リスト） | — |

- **suggestionKey は enum**。UI 表示名は **BE に持たせない**（FE が i18n で解決する）。BE は提案キー・既定色・名寄せ用の既定名（ja）だけを持つ。
- **既定色**は `InboxLabelSuggestion.defaultColor()`（CHAR(7) hex）。ユーザーは付与後に変更可。

### 10.2 提案の導出（読み取り時・`InboxAggregationService`）

`InboxItemDto` に **`suggestedLabels: List<SuggestedLabelDto>`** を追加（`SuggestedLabelDto{ suggestionKey(enum), color, existingLabelId(UUID|null) }`）。名寄せ畳み込み後の各カードへルールを適用して算出する。**DB には保存しない**。

- **抑制条件（重複提案回避）**: ユーザーが既に同義ラベル（提案キーの既定名と一致する手作成ラベル）を持ち、かつ**そのラベルが当該カードに付与済み**なら提案を外す。付与済みでなければ `existingLabelId` にその id を埋めて提案する（FE は再 create せず既存 id を使える）。同義ラベルが無ければ `existingLabelId=null`（FE は suggest-apply の find-or-create に倒す）。
- **N+1 を増やさない**: ユーザーの現役ラベルは提案が出るカードがあるときに **1 回だけ**まとめ取りして名前→id 写像を作る（カード件数に依らず定数回）。

### 10.3 1 タップ付与 API（冪等・find-or-create）

`POST /api/v1/inbox/labels/suggest-apply`（body `SuggestApplyRequest{ name, color, sourceType, sourceId }`・API 仕様は 02 §3.5a）。`InboxLabelService.suggestApply(userId, name, color, sourceType, sourceId)`:

1. ユーザーの**現役同名ラベルを探す（find）。無ければ `createLabel` で作成**（上限 20 超は既存 `INBOX_LABEL_LIMIT_EXCEEDED`／色形式不正は `COMMON_001`）。
2. そのラベルを `assignLabel` で当該カードに付与（可視性検証・1 通知 10 ラベル上限 `INBOX_LABEL_PER_ITEM_EXCEEDED`）。
3. **冪等**: 既に同ラベルが付いていれば二重付与せず正常（200）で付与後の `LabelDto` を返す。

**新規エラーコードは設けず既存を再利用**する。レスポンスは付与済みの `LabelDto`。
