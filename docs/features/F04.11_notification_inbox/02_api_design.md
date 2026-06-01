# F04.11: API設計

> **ステータス**: 🟢 設計確定（完了・未解決事項ゼロ）
> **最終更新**: 2026-05-30
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要
> - [01_data_model.md](./01_data_model.md) — DB / DTO
> - [03_business_logic.md](./03_business_logic.md) — 集約・状態マージ
> - [F04.3_push_notification.md](../F04.3_push_notification.md) — `NotificationController`（スタイル手本・既存 snooze API）

---

## 1. 設計方針

- すべて `/api/v1/inbox` 配下。`NotificationController` のスタイル（`SecurityUtils.getCurrentUserId()`・`ApiResponse`/`PagedResponse` ラッパ）を踏襲。
- **本人のデータのみ**：全エンドポイントで `currentUserId` を必須条件にし、他人の通知・ラベルへの操作を構造的に不可能にする（[04](./04_security_operations.md) §1）。
- triage 操作の対象指定は **`(sourceType, sourceId)` の複合論理キー**（`InboxItem.id = "{sourceType}:{sourceId}"`）。
- レスポンス DTO は [01](./01_data_model.md) §3 の `InboxItem` / `LabelDto`。

---

## 2. エンドポイント一覧

| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/inbox` | 必要 | インボックス一覧（フィルタ・ページング）|
| GET | `/api/v1/inbox/summary` | 必要 | 状態別・緊急度別・種類別の件数（タブ/バッジ用）|
| POST | `/api/v1/inbox/snooze` | 必要 | スヌーズ（upsert）|
| POST | `/api/v1/inbox/unsnooze` | 必要 | スヌーズ解除 |
| POST | `/api/v1/inbox/archive` | 必要 | アーカイブ（保管庫へ）|
| POST | `/api/v1/inbox/unarchive` | 必要 | アーカイブ解除（受信箱へ戻す）|
| POST | `/api/v1/inbox/bulk` | 必要 | 一括操作（archive/snooze/label）※Phase 2 |
| GET | `/api/v1/inbox/labels` | 必要 | ラベル一覧 |
| POST | `/api/v1/inbox/labels` | 必要 | ラベル作成 |
| PUT | `/api/v1/inbox/labels/{labelId}` | 必要 | ラベル更新（名前/色/アイコン/順序）|
| DELETE | `/api/v1/inbox/labels/{labelId}` | 必要 | ラベル論理削除 |
| POST | `/api/v1/inbox/labels/{labelId}/assign` | 必要 | ラベルを通知へ付与 |
| DELETE | `/api/v1/inbox/labels/{labelId}/assign` | 必要 | ラベル付与解除 |

---

## 3. リクエスト／レスポンス仕様

### 3.1 `GET /api/v1/inbox`

**クエリパラメータ**

| 名 | 型 | 既定 | 説明 |
|----|----|------|------|
| `state` | enum | `INBOX` | `INBOX`（未アーカイブ＆スヌーズ期限切れ含む）/ `SNOOZED`（`snoozed_until > now`）/ `ARCHIVED` / `ALL` |
| `priority` | enum[] | — | `URGENT/HIGH/NORMAL/LOW`（複数可）|
| `sourceType` | enum[] | — | `NOTIFICATION/ANNOUNCEMENT/MENTION/CONFIRMABLE/TODO_DUE`（複数可）|
| `labelId` | UUID | — | ラベル絞り込み |
| `page` | int | 0 | オフセットページング |
| `size` | int | 20 | 1〜50（ハードリミット）|

**レスポンス（200 OK）**
```json
{
  "data": {
    "items": [
      {
        "id": "TODO_DUE:88",
        "sourceType": "TODO_DUE",
        "sourceId": 88,
        "title": "決算提出",
        "excerpt": "2026-05-31 締切のTODO",
        "priority": "URGENT",
        "scope": { "type": "ORGANIZATION", "id": 12, "name": "マンション管理組合" },
        "actionUrl": "/todos/88",
        "occurredAt": "2026-05-31T00:00:00+09:00",
        "state": "UNREAD",
        "snoozedUntil": null,
        "labels": [{ "id": "0193...", "name": "経理", "color": "#f59e0b", "icon": "pi-wallet" }]
      }
    ],
    "page": 0, "size": 20, "totalEstimated": 12, "hasMore": false
  }
}
```

> `totalEstimated` は複数ソース集約のため**概算**（境界付きウィンドウ内・畳み込み後の件数）。Phase3 ③ の**境界付きウィンドウページング**（全ソース `Pageable`・完全全順序・取りこぼしゼロ）により当該ページの直近上位は欠落しない。詳細は [03](./03_business_logic.md) §4.1・[04](./04_security_operations.md) §5。

### 3.2 `GET /api/v1/inbox/summary`

**レスポンス（200 OK）**
```json
{
  "data": {
    "byState": { "INBOX": 12, "SNOOZED": 3, "ARCHIVED": 40 },
    "byPriority": { "URGENT": 2, "HIGH": 5, "NORMAL": 5, "LOW": 0 },
    "bySourceType": { "NOTIFICATION": 4, "ANNOUNCEMENT": 3, "MENTION": 2, "CONFIRMABLE": 1, "TODO_DUE": 2 }
  }
}
```

### 3.3 triage 操作（snooze / unsnooze / archive / unarchive）

**`POST /api/v1/inbox/snooze` リクエストボディ**
```json
{ "sourceType": "NOTIFICATION", "sourceId": 123, "snoozedUntil": "2026-05-31T08:00:00+09:00" }
```
- `snoozedUntil` は ISO8601。フロントがプリセット（3 時間後 / 今晩 / 明日朝 / 来週）から**日時を計算して送る**（[03](./03_business_logic.md) §6 — 既存 snooze の `duration` 送信バグの是正方針）。
- サーバは過去時刻を拒否（`INBOX_INVALID_SNOOZE_TIME`）。`NotificationService.snoozeNotification` の `@Future` 検証を流用。
- upsert：`(user_id, source_type, source_id)` で `inbox_item_states` を作成/更新。

**`POST /api/v1/inbox/archive` リクエストボディ**
```json
{ "sourceType": "ANNOUNCEMENT", "sourceId": 45 }
```
- `archived_at = now()` を upsert。`unarchive` は `archived_at = NULL`。両カラムが NULL になったら行を物理削除（[01](./01_data_model.md) §2.1 遅延削除）。

**レスポンス（200 OK）**: 更新後の `InboxItem`（楽観更新の確定反映用）。

### 3.4 ラベル CRUD ＋ 付与/解除

**`GET /api/v1/inbox/labels` レスポンス（200 OK）**
```json
{ "data": [ { "id": "0193...", "name": "経理", "color": "#f59e0b", "icon": "pi-wallet", "sortOrder": 0 } ] }
```
- 現役（`deleted_at IS NULL`）のラベルのみ・`sort_order` 昇順。

**`POST /api/v1/inbox/labels`**
```json
{ "name": "要返信", "color": "#3b82f6", "icon": "pi-reply" }
```
- レスポンス 201：作成された `LabelDto`。上限 20 超過は `INBOX_LABEL_LIMIT_EXCEEDED`、同名は `INBOX_LABEL_NAME_DUPLICATE`。

**`POST /api/v1/inbox/labels/{labelId}/assign`**
```json
{ "sourceType": "MENTION", "sourceId": 9 }
```
- ラベル所有者 = currentUser を検証（`findByIdAndUserIdAndDeletedAtIsNull`。他人ラベル・**論理削除済みラベル**は `INBOX_LABEL_NOT_FOUND`）。付与対象通知が本人に可視かも検証（[04](./04_security_operations.md) §1.2）。1 通知 10 ラベル超過は `INBOX_LABEL_PER_ITEM_EXCEEDED`。重複付与は冪等（204 で無視 or 既存返却）。

### 3.5 `POST /api/v1/inbox/bulk`（Phase 2）
```json
{ "action": "ARCHIVE", "items": [{ "sourceType": "NOTIFICATION", "sourceId": 1 }, { "sourceType": "MENTION", "sourceId": 9 }] }
```
- `action`: `ARCHIVE` / `UNARCHIVE` / `SNOOZE`（`snoozedUntil` 同梱）/ `LABEL_ADD`（`labelId` 同梱）。
- レスポンスは成功/失敗件数（`BulkAssignResultResponse` 手本）。部分失敗を許容し全体は 200。

### 3.6 エラーコード（`InboxErrorCode`・Java enum・文字列コード）

| コード | HTTP | 条件 |
|--------|------|------|
| `INBOX_INVALID_SOURCE_TYPE` | 400 | 未知の sourceType |
| `INBOX_INVALID_SNOOZE_TIME` | 400 | snoozedUntil が過去 or 未指定 |
| `INBOX_LABEL_NOT_FOUND` | 404 | ラベル不存在 or 他人のラベル（IDOR 秘匿）|
| `INBOX_LABEL_NAME_DUPLICATE` | 409 | 同名ラベルが現役で存在 |
| `INBOX_LABEL_LIMIT_EXCEEDED` | 422 | ラベル 20 件上限超過 |
| `INBOX_LABEL_PER_ITEM_EXCEEDED` | 422 | 1 通知 10 ラベル上限超過 |
| `INBOX_SOURCE_NOT_FOUND` | 404 | triage 対象通知が存在しない/本人宛てでない |

> エラーコードは Java enum で定義（DB ENUM 不使用＝追加頻度高）。F15.3 `ScopeFolderErrorCode`・F04.9 `ConfirmableNotificationErrorCode` と同方式。

---

## 4. 認可・レートリミット

| 項目 | 方針 |
|------|------|
| 認可 | 全 EP で `currentUserId` 必須。triage/ラベル操作は **対象が本人のデータか**をサービス層で検証（[04](./04_security_operations.md) §1）。`source_id` が本人宛て通知を指すかは集約アダプタの可視性判定を再利用 |
| IDOR 防止 | ラベル操作は `findByIdAndUserId`、triage は `(user_id, source_type, source_id)` キーで本人行のみ対象。他人の通知への状態付与は集約時の可視性判定で弾く（[04](./04_security_operations.md) §1.2）|
| レートリミット（Bucket4j）| `GET /inbox`・`/summary`: 120 req/min。triage（snooze/archive 系）: 240 req/min（1 タップ多発を許容）。ラベル作成: 30 req/hour、付与/解除: 120 req/min、bulk: 30 req/min |
| 監査ログ | triage/ラベルは個人設定操作のため `audit_logs` 記録は**不要**（金銭/権限/削除の重要操作のみ記録する方針に合致）|
