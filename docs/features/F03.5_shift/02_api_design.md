# F03.5 シフト管理 — §4 API設計

> このファイルは [F03.5_shift/README.md](README.md) の一部です。

## 4. API設計

### エンドポイント一覧
| メソッド | パス | 認証 | 説明 |
|---------|-----|------|------|
| GET | `/api/v1/shifts/schedules` | 必要 | シフトスケジュール一覧取得 |
| POST | `/api/v1/shifts/schedules` | 必要 | シフトスケジュール作成 |
| POST | `/api/v1/shifts/schedules/{id}/duplicate` | 必要 | シフトスケジュール複製（前月コピー等） |
| GET | `/api/v1/shifts/schedules/{id}` | 必要 | シフトスケジュール詳細取得 |
| PATCH | `/api/v1/shifts/schedules/{id}` | 必要 | シフトスケジュール更新（部分更新、PUT から PATCH へ変更） |
| DELETE | `/api/v1/shifts/schedules/{id}` | 必要 | シフトスケジュール削除（論理削除） |
| POST | `/api/v1/shifts/schedules/{id}/transition` | 必要 | ステータス遷移統合（DRAFT→COLLECTING→ADJUSTING→PUBLISHED→ARCHIVED）。リクエストボディの `target_status` で遷移先を指定（旧 PATCH `/status` + PATCH `/publish` を統合） |
| GET | `/api/v1/shifts/schedules/{id}/slots` | 必要 | スロット一覧取得 |
| POST | `/api/v1/shifts/schedules/{id}/slots` | 必要 | スロット作成 |
| POST | `/api/v1/shifts/schedules/{id}/slots/bulk` | 必要 | スロット一括作成 |
| PATCH | `/api/v1/shifts/slots/{id}` | 必要 | スロット更新（部分更新、PUT から PATCH へ変更） |
| DELETE | `/api/v1/shifts/slots/{id}` | 必要 | スロット削除（物理削除） |
| PATCH | `/api/v1/shifts/slots/{id}/assignments` | 必要 | **【v2】D&D UI 用の差分割当 API（個別ユーザー追加・削除）** |
| GET | `/api/v1/shifts/requests` | 必要 | 希望一覧取得（管理者用、クエリパラメータ `scheduleId` でスケジュール絞り込み。旧 `GET /shifts/schedules/{id}/requests` を統合） |
| GET | `/api/v1/shifts/requests/summary` | 必要 | 希望集計サマリー取得（管理者用） |
| POST | `/api/v1/shifts/requests` | 必要 | 希望提出 |
| PATCH | `/api/v1/shifts/requests/{id}` | 必要 | 希望編集（部分更新、PUT から PATCH へ変更） |
| DELETE | `/api/v1/shifts/requests/{id}` | 必要 | 希望取り下げ |
| GET | `/api/v1/shifts/my/requests` | 必要 | 自分の希望提出一覧取得（旧 `GET /shifts/my` を改名） |
| GET | `/api/v1/shifts/schedules/{id}/summary` | 必要 | 日付別・ポジション別の充足状況サマリー **【🟢 実装済 Phase 11 第二陣 2-α / N+1 修正 Phase 11 事後検分 fixup / 2026-05-17】** |
| POST | `/api/v1/shifts/schedules/{id}/remind` | 必要 | 未提出者への手動リマインド送信（管理者用） **【🟢 実装済 Phase 11 第二陣 2-α / Valkey ロック追加 Phase 11 事後検分 fixup / 2026-05-17】** |
| GET | `/api/v1/shifts/positions` | 必要 | ポジションマスター一覧取得 |
| POST | `/api/v1/shifts/positions` | 必要 | ポジション作成 |
| PATCH | `/api/v1/shifts/positions/{id}` | 必要 | ポジション更新（部分更新、PUT から PATCH へ変更） |
| DELETE | `/api/v1/shifts/positions/{id}` | 必要 | ポジション削除（is_active=FALSE） |
| POST | `/api/v1/shifts/swap-requests` | 必要 | シフト交代リクエスト作成 |
| POST | `/api/v1/shifts/swap-requests/{id}/accept` | 必要 | シフト交代を引き受ける（旧 PATCH → POST に変更） |
| POST | `/api/v1/shifts/swap-requests/{id}/resolve` | 必要 | シフト交代の承認/却下統合（管理者用、リクエストボディの `decision` で `APPROVE`/`REJECT` を指定。旧 PATCH `/approve` + PATCH `/reject` を統合） |
| DELETE | `/api/v1/shifts/swap-requests/{id}` | 必要 | シフト交代リクエスト取り下げ |
| GET | `/api/v1/shifts/availability` | 必要 | 自分のデフォルト可否プロファイル取得 |
| PUT | `/api/v1/shifts/availability` | 必要 | デフォルト可否プロファイル一括更新 |
| DELETE | `/api/v1/shifts/availability` | 必要 | デフォルト可否プロファイルを削除（既定値に戻す） |
| GET | `/api/v1/shifts/hourly-rate` | 必要 | 自分の時給設定取得 |
| POST | `/api/v1/shifts/hourly-rate` | 必要 | 自分の時給設定登録・更新（旧 PUT → POST に変更、Service 側 upsert 動作） |
| GET | `/api/v1/shifts/hourly-rates` | 必要 | チームメンバーの時給一覧取得（管理者用） **【v2 計画、現状未実装】** |
| PUT | `/api/v1/shifts/hourly-rates/{userId}` | 必要 | メンバーの時給設定（管理者用） **【v2 計画、現状未実装】** |
| POST | `/api/v1/shifts/schedules/{id}/auto-assign` | 必要 | **【v2】自動割当を実行（PROPOSED 状態で shift_assignments にドラフト書き込み）** |
| POST | `/api/v1/shifts/schedules/{id}/auto-assign/confirm` | 必要 | **【v2】自動割当の提案を確定（PROPOSED → CONFIRMED、shift_slots.assigned_user_ids を更新）** |
| DELETE | `/api/v1/shifts/schedules/{id}/auto-assign` | 必要 | **【v2】未確定の自動割当提案を破棄（REVOKED 化）** |
| GET | `/api/v1/shifts/schedules/{id}/assignment-runs` | 必要 | **【v2】自動割当の実行履歴一覧** |
| GET | `/api/v1/shifts/assignment-runs/{runId}` | 必要 | **【v2】自動割当実行の詳細（スコアリング内訳・警告）** |
| PATCH | `/api/v1/shifts/slots/{id}/assignments` | 必要 | **【v2】D&D UI 用の差分割当 API（個別ユーザー追加・削除）** |
| GET | `/api/v1/shifts/teams/{teamId}/work-constraints` | 必要 | **【v2】チームの勤務制約一覧（デフォルト + 個別、チームスコープ化）** |
| GET | `/api/v1/shifts/teams/{teamId}/work-constraints/default` | 必要 | **【v2】チームデフォルト勤務制約を取得** |
| PUT | `/api/v1/shifts/teams/{teamId}/work-constraints/default` | 必要 | **【v2】チームデフォルト勤務制約を作成・更新（upsert）** |
| DELETE | `/api/v1/shifts/teams/{teamId}/work-constraints/default` | 必要 | **【v2】チームデフォルト勤務制約を削除** |
| GET | `/api/v1/shifts/teams/{teamId}/work-constraints/members/{userId}` | 必要 | **【v2】メンバー個別の勤務制約を取得** |
| PUT | `/api/v1/shifts/teams/{teamId}/work-constraints/members/{userId}` | 必要 | **【v2】メンバー個別の勤務制約を作成・更新（upsert）** |
| DELETE | `/api/v1/shifts/teams/{teamId}/work-constraints/members/{userId}` | 必要 | **【v2】メンバー個別の勤務制約を削除（デフォルトに戻す）** |
| POST | `/api/v1/shifts/change-requests` | 必要 | **【v2.1】確定前の変更依頼を作成（MEMBER/DEPUTY_ADMIN が自分に関する範囲で）** |
| GET | `/api/v1/shifts/change-requests` | 必要 | **【v2.1】変更依頼一覧取得（MEMBER は自分の、ADMIN は担当スケジュール全件）** |
| GET | `/api/v1/shifts/change-requests/{id}` | 必要 | **【v2.1】変更依頼の詳細取得** |
| PATCH | `/api/v1/shifts/change-requests/{id}/review` | 必要 | **【v2.1】管理者が変更依頼を受諾（ACCEPTED）または却下（REJECTED）** |
| DELETE | `/api/v1/shifts/change-requests/{id}` | 必要 | **【v2.1】依頼者本人が変更依頼を取下（WITHDRAWN）** |
| POST | `/api/v1/shifts/swap-requests/{id}/claim` | 必要 | **【v2.1】オープンコール（全体募集）への手挙げ（先着優先、楽観ロック）** |
| POST | `/api/v1/shifts/swap-requests/{id}/select-claimer` | 必要 | **【v2.1】オープンコール候補選定（依頼者または管理者が accepter を確定）** |
| POST | `/api/v1/shifts/assignment-runs/{runId}/confirm-visual-review` | 必要 | **【v2.1】自動割当結果の目視確認を承認（PUBLISHED 遷移の前提条件）** |
| GET | `/api/v1/shifts/schedules/{id}/pdf` | 必要 | **【v2.2】シフト表PDF出力（`layout=team` チーム全体マトリクス / `layout=personal` 個人タイムライン）** |

### リクエスト／レスポンス仕様

#### `GET /api/v1/shifts/schedules`

チーム内のシフトスケジュール一覧を取得する。

**認可**（認可根治 Wave6 追加戦 / 実装は `ShiftScheduleService#checkTeamReadAccess`）

シフト表の閲覧は一般メンバーの日常操作のため、管理者に限定せず**当該チームのメンバー**を粒度とする。
`ShiftSlotService#checkScheduleReadAccess`・`ShiftPdfService` と同一方針（PDF で SUPPORTER に
伏せている情報を生 API から取得できては意味がないため、SUPPORTER は除外する）。

| 呼び出し元 | 判定 |
|---|---|
| SYSTEM_ADMIN | 許可（短絡）|
| 当該チームの ADMIN / DEPUTY_ADMIN / MEMBER | 許可 |
| 当該チームの SUPPORTER | `COMMON_002`（403）|
| 他チームのユーザー（ADMIN 含む）・無所属ユーザー | `COMMON_002`（403）|

`from` / `to` を指定した期間検索も同一の認可を経由する（迂回経路を作らない）。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | 必須 | チーム ID |
| `status` | String | — | ステータスでフィルタ（カンマ区切りで複数指定可） |
| `from` | String | — | 指定日以降に期間が重なるシフトに絞り込み（`end_date >= from`） |
| `to` | String | — | 指定日以前に期間が重なるシフトに絞り込み（`start_date <= to`） |
| `cursor` | String | — | ページネーションカーソル |
| `limit` | Integer | — | 取得件数（デフォルト: 20、最大: 50） |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "team_id": 5,
      "title": "2026年3月第2週シフト",
      "period_type": "WEEKLY",
      "start_date": "2026-03-09",
      "end_date": "2026-03-15",
      "status": "COLLECTING",
      "request_deadline": "2026-03-05T23:59:00+09:00",
      "slot_count": 21,
      "request_count": 8,
      "member_count": 12,
      "created_by": { "id": 10, "display_name": "田中太郎" },
      "version": 1,
      "created_at": "2026-03-01T09:00:00+09:00"
    }
  ],
  "meta": {
    "next_cursor": "eyJpZCI6MX0",
    "has_more": true
  }
}
```

- `slot_count`: スロット総数（`LEFT JOIN shift_slots + COUNT` でスケジュール取得クエリに結合し N+1 を回避）
- `request_count`: 希望提出済みメンバー数（`LEFT JOIN shift_requests + COUNT(DISTINCT user_id)` で同様に結合）
- `member_count`: チームの対象メンバー総数（希望提出率の分母）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | チームへのアクセス権限がない |

---

#### `POST /api/v1/shifts/schedules`

シフトスケジュールを新規作成する。初期状態は `DRAFT`。

**リクエストボディ**
```json
{
  "team_id": 5,
  "title": "2026年3月第2週シフト",
  "period_type": "WEEKLY",
  "start_date": "2026-03-09",
  "end_date": "2026-03-15",
  "request_deadline": "2026-03-05T23:59:00+09:00",
  "note": "来週は繁忙期のため人数多めでお願いします",
  "copy_from_schedule_id": 1
}
```

- `title`: 必須・1〜200文字
- `period_type`: 必須
- `start_date` / `end_date`: 必須。`end_date >= start_date`
- `request_deadline`: 任意（NULL = 管理者が手動で締め切る）。指定時は現在日時より未来であること（過去日時は 400 エラー）
- `note`: 任意・最大5,000文字
- `copy_from_schedule_id`: 任意。指定時はコピー元スケジュールのスロットを日付読み替えて自動コピー（同一チームの ADJUSTING / PUBLISHED / ARCHIVED スケジュールのみ指定可能。DRAFT / COLLECTING はスロット構成が未確定のためコピー元として不可）。`assigned_user_ids` はコピーしない

**レスポンス（201 Created）**
```json
{
  "data": {
    "id": 1,
    "team_id": 5,
    "title": "2026年3月第2週シフト",
    "period_type": "WEEKLY",
    "start_date": "2026-03-09",
    "end_date": "2026-03-15",
    "status": "DRAFT",
    "request_deadline": "2026-03-05T23:59:00+09:00",
    "note": "来週は繁忙期のため人数多めでお願いします",
    "copied_slot_count": 21,
    "version": 0,
    "created_by": { "id": 10, "display_name": "田中太郎" },
    "created_at": "2026-03-01T09:00:00+09:00"
  }
}
```

- `copied_slot_count`: `copy_from_schedule_id` 指定時のみ返却。コピーされたスロット数。未指定時は `null`
- `version`: 楽観的ロックバージョン（新規作成時は `0`）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（期間不正等） |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | `copy_from_schedule_id` に指定されたスケジュールが存在しない / 同一チームではない |

---

#### `GET /api/v1/shifts/schedules/{id}`

シフトスケジュールの詳細を取得する。スロット一覧と希望提出状況のサマリーを含む。

**認可**（認可根治 Wave6 追加戦 / 実装は `ShiftScheduleService#checkTeamReadAccess`）

一覧と同一の粒度（当該チームのメンバー。SUPPORTER は不可）。
scope は**パス変数でなくスケジュール実体の `team_id` から解決**してから判定するため、
他チームの `id` を直接指定する BOLA は `COMMON_002`（403）で拒否される。

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 1,
    "team_id": 5,
    "title": "2026年3月第2週シフト",
    "period_type": "WEEKLY",
    "start_date": "2026-03-09",
    "end_date": "2026-03-15",
    "status": "COLLECTING",
    "request_deadline": "2026-03-05T23:59:00+09:00",
    "note": "来週は繁忙期のため人数多めでお願いします",
    "published_at": null,
    "published_by": null,
    "slots": [
      {
        "id": 101,
        "slot_date": "2026-03-09",
        "start_time": "09:00",
        "end_time": "17:00",
        "position": { "id": 1, "name": "ホール" },
        "required_count": 3,
        "assigned_users": null,
        "note": null,
        "version": 0,
        "request_summary": {
          "preferred": 2,
          "available": 3,
          "weak_rest": 1,
          "strong_rest": 0,
          "absolute_rest": 1
        }
      }
    ],
    "request_stats": {
      "total_members": 12,
      "submitted_count": 8,
      "not_submitted_count": 4
    },
    "version": 1,
    "created_by": { "id": 10, "display_name": "田中太郎" },
    "created_at": "2026-03-01T09:00:00+09:00",
    "updated_at": "2026-03-01T09:00:00+09:00"
  }
}
```

- `version`: 楽観的ロックバージョン。PUT / PATCH リクエスト時にこの値を送信する
- `slots[].request_summary`: スロット別の希望集計（ADMIN/DEPUTY_ADMIN のみ表示。MEMBER には非表示）
- `slots[].assigned_users`: `PUBLISHED` 状態の場合のみ割り当て済みユーザーを返却。それ以外は `null`（ADMIN は `ADJUSTING` 以降で閲覧可能）
- `request_stats`: 希望提出の進捗（ADMIN/DEPUTY_ADMIN のみ表示）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | チームへのアクセス権限がない |
| 404 | シフトスケジュールが存在しない |

---

#### `PATCH /api/v1/shifts/schedules/{id}`

> ※ 旧設計は PUT。Phase 2 以降の実装でフィールド部分更新 (PATCH) に統一。


シフトスケジュールの情報を更新する。`DRAFT` または `COLLECTING` 状態のみ更新可能。

**リクエストボディ**
```json
{
  "title": "【変更】2026年3月第2週シフト",
  "request_deadline": "2026-03-06T23:59:00+09:00",
  "note": "締切を1日延長しました",
  "version": 1
}
```

- `version`: 必須。取得時の version をそのまま送信（楽観的ロック）
- `team_id`, `period_type`, `start_date`, `end_date` は変更不可（スロットの日付整合性が崩れるため。変更する場合は新規作成 + コピーで対応）

**レスポンス（200 OK）**: GET 詳細と同一形式（slots は除く。`version` は更新後の値を返却）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | シフトスケジュールが存在しない |
| 409 | `ADJUSTING` 以降のステータスで更新不可 / 楽観的ロック競合（version 不一致） |

---

#### `DELETE /api/v1/shifts/schedules/{id}`

シフトスケジュールを論理削除する。`PUBLISHED` / `ARCHIVED` 状態のシフトは削除不可（アーカイブで対応）。`COLLECTING` / `ADJUSTING` 状態で削除した場合は希望提出済みメンバーに中止通知を送信（ApplicationEvent: ShiftScheduleDeletedEvent）。

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 1,
    "deleted_at": "2026-03-13T15:00:00+09:00"
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | シフトスケジュールが存在しない |
| 409 | `PUBLISHED` / `ARCHIVED` 状態のシフトは削除不可 |

---

#### `POST /api/v1/shifts/schedules/{id}/transition`

> ※ 旧設計は `PATCH /status` + `PATCH /publish` の 2 本。Phase 2 で `POST /transition` 1 本に統合し、リクエストボディの `target_status` で遷移先を指定する方式に変更。以降のサブセクションは旧設計の参考情報として残す。

#### 【旧】`PATCH /api/v1/shifts/schedules/{id}/status`

シフトスケジュールのステータスを変更する。許可される遷移のみ受け付ける。

**リクエストボディ**
```json
{
  "status": "COLLECTING",
  "version": 1
}
```

- `version`: 必須。取得時の version をそのまま送信（楽観的ロック）

**許可されるステータス遷移**
| 現在 | → | 遷移先 | 説明 |
|------|---|--------|------|
| DRAFT | → | COLLECTING | 希望収集開始。メンバーに通知 |
| COLLECTING | → | ADJUSTING | 希望締切。管理者調整フェーズへ |
| COLLECTING | → | DRAFT | 希望収集を一旦中止（既存希望は保持。`is_reminder_sent` を `FALSE` にリセット） |
| ADJUSTING | → | COLLECTING | 追加希望を受け付ける場合（差し戻し。`is_reminder_sent` を `FALSE` にリセット。全メンバーに再収集開始通知を送信） |
| ADJUSTING | → | PUBLISHED | シフト確定・公開（`PATCH /publish` で遷移。全メンバーに通知） |
| PUBLISHED | → | ARCHIVED | 期間終了後のアーカイブ |

- `DRAFT → COLLECTING`: スロットが1件以上存在することを検証。0件の場合は 400 エラー
- `COLLECTING → ADJUSTING`: `request_deadline` 前でも手動で遷移可能

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 1,
    "status": "COLLECTING",
    "previous_status": "DRAFT",
    "version": 2
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | 許可されていないステータス遷移 / スロットが0件（DRAFT → COLLECTING） |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | シフトスケジュールが存在しない |
| 409 | 楽観的ロック競合（version 不一致） |

---

#### 【旧】`PATCH /api/v1/shifts/schedules/{id}/publish`

シフトを確定・公開する。`ADJUSTING` 状態からのみ遷移可能。全メンバーにプッシュ通知を配信する。

**リクエストボディ**
```json
{
  "version": 3,
  "visual_review_acknowledged": true
}
```

- `version`: 必須。取得時の version をそのまま送信（楽観的ロック）
- `visual_review_acknowledged`: **【v2.1 新規】** 必須（`true` のみ受理）。UI の確認ダイアログで「すべての割当を目視で確認しましたか？」に同意した証跡を明示的にリクエストへ含める。`false` または省略時は 400 を返す

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 1,
    "status": "PUBLISHED",
    "published_at": "2026-03-07T10:00:00+09:00",
    "published_by": { "id": 10, "display_name": "田中太郎" },
    "notified_count": 12,
    "version": 4
  },
  "warnings": [
    {
      "type": "CROSS_TEAM_CONFLICT",
      "user": { "id": 10, "display_name": "田中太郎" },
      "conflict_team": "カフェ渋谷店",
      "conflict_date": "2026-03-09",
      "conflict_time": "09:00-17:00",
      "message": "同日に他チームのシフトが割り当てられています"
    }
  ]
}
```

- `notified_count`: 通知を送信したメンバー数
- `warnings`: 公開はブロックしないが注意が必要な事項。複数チーム兼務メンバーの時間帯重複チェック結果。重複がない場合は空配列

**【v2.1】目視確認ゲート**
- 対象スケジュールで自動割当（`POST /auto-assign`）を**1回でも実行した履歴**がある場合、最新成功 run の `visual_review_confirmed_at IS NOT NULL` を必須チェック
- 条件を満たさない場合は **409 Conflict** を返し、`error.code = "VISUAL_REVIEW_REQUIRED"` でクライアントに目視確認 API (`POST /assignment-runs/{runId}/confirm-visual-review`) を案内
- 自動割当を使わず完全手動で組んだスケジュール（`shift_assignment_runs` が 0 件）は本チェックをスキップ。ただし UI は常に「目視で確認しましたか？」の確認ダイアログを表示（`visual_review_acknowledged=true` の送信は常に必須）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | `ADJUSTING` 以外のステータスから公開しようとした / 全スロットの `assigned_user_ids` が NULL（1件も割り当てなし。一部欠員は警告のみで公開可能）/ **【v2.1】** `visual_review_acknowledged != true` |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | シフトスケジュールが存在しない |
| 409 | 楽観的ロック競合（version 不一致）/ **【v2.1】** `VISUAL_REVIEW_REQUIRED`（自動割当履歴ありで最新 run が目視未確認） |

---

#### `GET /api/v1/shifts/schedules/{id}/slots`

スケジュール内のスロット一覧を日付順で取得する。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `date` | String | — | 特定日付のスロットに絞り込み |
| `position_id` | Long | — | ポジション ID でフィルタ |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 101,
      "slot_date": "2026-03-09",
      "start_time": "09:00",
      "end_time": "17:00",
      "position": { "id": 1, "name": "ホール" },
      "required_count": 3,
      "assigned_users": [
        { "id": 10, "display_name": "田中太郎" },
        { "id": 11, "display_name": "佐藤花子" }
      ],
      "note": null,
      "version": 1,
      "vacancy": 1
    }
  ]
}
```

- `assigned_users`: `PUBLISHED` 後は全員に表示。`ADJUSTING` 中は ADMIN/DEPUTY_ADMIN のみ表示
- `vacancy`: `required_count - assigned_users.length`（欠員数。0以下の場合は0）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | チームへのアクセス権限がない |
| 404 | シフトスケジュールが存在しない |

---

#### `POST /api/v1/shifts/schedules/{id}/slots`

スロットを1件作成する。

**リクエストボディ**
```json
{
  "slot_date": "2026-03-09",
  "start_time": "09:00",
  "end_time": "17:00",
  "position_id": 1,
  "required_count": 3,
  "note": null
}
```

- `slot_date`: 必須。`shift_schedules` の `start_date` 〜 `end_date` の範囲内であること
- `start_time` / `end_time`: 必須
- `position_id`: 任意（NULL = ポジション指定なし）。指定時は同一チームの `shift_positions` に存在し `is_active = TRUE` であること
- `required_count`: 必須・1〜50

**レスポンス（201 Created）**: スロット単体を返却（`version: 0` を含む）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（日付範囲外、時間不正等） |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | シフトスケジュールが存在しない |
| 409 | `PUBLISHED` / `ARCHIVED` 状態ではスロット追加不可 |

---

#### `POST /api/v1/shifts/schedules/{id}/slots/bulk`

スロットを一括作成する。テンプレート的な使用（毎週同じ枠を設定）を想定。

**リクエストボディ**
```json
{
  "slots": [
    {
      "slot_date": "2026-03-09",
      "start_time": "09:00",
      "end_time": "17:00",
      "position_id": 1,
      "required_count": 3
    },
    {
      "slot_date": "2026-03-09",
      "start_time": "09:00",
      "end_time": "17:00",
      "position_id": 2,
      "required_count": 2
    }
  ]
}
```

- 最大100件まで一括作成可能
- 全件バリデーション通過後にバッチ INSERT（1件でもエラーがあれば全件ロールバック）

**レスポンス（201 Created）**
```json
{
  "data": {
    "created_count": 2,
    "slots": [...]
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（100件超過含む） |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | シフトスケジュールが存在しない |
| 409 | `PUBLISHED` / `ARCHIVED` 状態ではスロット追加不可 |

---

#### `PATCH /api/v1/shifts/slots/{id}`

> ※ 旧設計は PUT。実装は PATCH に統一。

スロットを更新する。`ADJUSTING` 状態では `assigned_user_ids` の設定も可能。

**リクエストボディ**
```json
{
  "start_time": "10:00",
  "end_time": "18:00",
  "position_id": 1,
  "required_count": 4,
  "assigned_user_ids": [10, 11, 12, 13],
  "note": "繁忙のため1名増員",
  "version": 1
}
```

- `assigned_user_ids`: `ADJUSTING` / `PUBLISHED` 状態でのみ設定可能。配列内の user_id はチームメンバーであることを検証。`DRAFT` / `COLLECTING` 状態でリクエストに含まれた場合は 400 エラー（フロントエンドのバグ検知のため無視ではなくエラーにする）
- `PUBLISHED` 状態でのスロット更新は `assigned_user_ids` と `note` のみ変更可能（時間帯・ポジション・必要人数の変更は不可）。変更時に該当メンバーに再通知
- `version`: 必須。取得時の version をそのまま送信（楽観的ロック）

**レスポンス（200 OK）**: スロット単体を返却（`version` は更新後の値を返却）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー / `assigned_user_ids` にチームメンバー以外が含まれる |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | スロットが存在しない |
| 409 | `PUBLISHED` 状態で時間帯・ポジション・必要人数を変更しようとした / 楽観的ロック競合（version 不一致） |

---

#### `DELETE /api/v1/shifts/slots/{id}`

スロットを物理削除する。`PUBLISHED` / `ARCHIVED` 状態では削除不可。

**レスポンス（204 No Content）**

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | スロットが存在しない |
| 409 | `PUBLISHED` / `ARCHIVED` 状態では削除不可 |

---

#### `GET /api/v1/shifts/requests?scheduleId={id}`

> ※ 旧設計は `GET /shifts/schedules/{id}/requests` の階層パス。実装は `GET /shifts/requests?scheduleId=` のクエリパラメータ方式に統一（`ShiftRequestController#listRequests`）。

#### 【旧パス】`GET /api/v1/shifts/schedules/{id}/requests`

管理者用の希望一覧取得。全メンバーの希望をスロット別・ユーザー別にマトリクス表示するためのデータを返す。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `date` | String | — | 特定日付に絞り込み |

**レスポンス（200 OK）**
```json
{
  "data": {
    "schedule_id": 1,
    "members": [
      {
        "user": { "id": 10, "display_name": "田中太郎" },
        "submitted": true,
        "requests": [
          {
            "id": 201,
            "slot_id": 101,
            "slot_date": "2026-03-09",
            "preference": "PREFERRED",
            "note": null
          },
          {
            "id": 202,
            "slot_id": 102,
            "slot_date": "2026-03-09",
            "preference": "STRONG_REST",
            "note": "通院のため"
          }
        ]
      },
      {
        "user": { "id": 12, "display_name": "鈴木一郎" },
        "submitted": false,
        "requests": []
      }
    ]
  }
}
```

- `submitted`: メンバーが1件以上の希望を提出したかどうか
- 未提出メンバーも `submitted: false` で一覧に含める（未提出者の把握用）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | シフトスケジュールが存在しない |

---

#### `POST /api/v1/shifts/requests`

メンバーが自分のシフト希望を提出する。複数枠の希望を一括で送信可能。

**リクエストボディ**
```json
{
  "schedule_id": 1,
  "requests": [
    {
      "slot_id": 101,
      "slot_date": "2026-03-09",
      "preference": "PREFERRED",
      "note": null
    },
    {
      "slot_id": 102,
      "slot_date": "2026-03-09",
      "preference": "ABSOLUTE_REST",
      "note": "通院のため"
    },
    {
      "slot_id": null,
      "slot_date": "2026-03-10",
      "preference": "WEAK_REST",
      "note": "できれば休みたいが緊急なら出られます"
    }
  ]
}
```

- `schedule_id`: 必須。対象スケジュールが `COLLECTING` 状態 かつ `request_deadline` が未来（または NULL）であること
- `requests`: 必須。1件以上、最大200件。同一 `schedule_id + slot_id + slot_date` の既存希望がある場合は 400 エラー（既存希望の変更は `PUT /requests/{id}` を使用）
- `slot_id`: 任意（NULL = 日付レベルの希望）。指定時は `schedule_id` に属するスロットであることを検証（他スケジュールの slot_id を指定した場合は 400 エラー）
- `preference`: 必須。**v2 の5値** `PREFERRED / AVAILABLE / WEAK_REST / STRONG_REST / ABSOLUTE_REST` のいずれか（それ以外は 400 エラー）
- `note`: 任意・最大200文字

**レスポンス（201 Created）**
```json
{
  "data": {
    "schedule_id": 1,
    "submitted_count": 3,
    "requests": [...]
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー / 同一枠の重複希望 |
| 403 | チームメンバーではない / SUPPORTER・GUEST は希望提出不可 |
| 404 | シフトスケジュール/スロットが存在しない |
| 409 | `COLLECTING` 以外のステータス / `request_deadline` が過去（希望提出期限切れ） |

---

#### `PATCH /api/v1/shifts/requests/{id}`

> ※ 旧設計は PUT。実装は PATCH に統一。

提出済みの希望を編集する。`COLLECTING` 状態のみ可能。`slot_id` / `slot_date` は変更不可（変更する場合は DELETE + 再提出で対応）。

**リクエストボディ**
```json
{
  "preference": "AVAILABLE",
  "note": "午前なら出られます"
}
```

**レスポンス（200 OK）**: 希望単体を返却

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | 投稿者本人ではない |
| 404 | 希望が存在しない |
| 409 | `COLLECTING` 以外のステータス / `request_deadline` が過去（希望提出期限切れ） |

---

#### `DELETE /api/v1/shifts/requests/{id}`

希望を取り下げる（物理削除）。`COLLECTING` 状態のみ可能。

**レスポンス（204 No Content）**

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 投稿者本人ではない |
| 404 | 希望が存在しない |
| 409 | `COLLECTING` 以外のステータス |

---

#### `GET /api/v1/shifts/my/requests`

> ※ 旧設計は `GET /shifts/my`。実装は `/shifts/my/requests` に改名（`ShiftRequestController#listMyRequests`）。

ログインユーザーの確定シフトと希望提出状況を取得する。個人ダッシュボードでの表示用。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `from` | String | — | 開始日（デフォルト: 今日） |
| `to` | String | — | 終了日（デフォルト: 今日+30日） |
| `team_id` | Long | — | 特定チームに絞り込み |

**レスポンス（200 OK）**
```json
{
  "data": {
    "confirmed_shifts": [
      {
        "schedule_id": 1,
        "schedule_title": "2026年3月第2週シフト",
        "team": { "id": 5, "name": "カフェ新宿店" },
        "slot": {
          "id": 101,
          "slot_date": "2026-03-09",
          "start_time": "09:00",
          "end_time": "17:00",
          "position": { "id": 1, "name": "ホール" }
        },
        "estimated_pay": {
          "hours": 8.0,
          "hourly_rate": 1200.00,
          "amount": 9600.00
        }
      }
    ],
    "pay_summary": {
      "total_hours": 32.0,
      "total_amount": 38400.00,
      "period": "2026-03-09 〜 2026-04-08"
    },
    "pending_requests": [
      {
        "schedule_id": 2,
        "schedule_title": "2026年3月第3週シフト",
        "team": { "id": 5, "name": "カフェ新宿店" },
        "status": "COLLECTING",
        "request_deadline": "2026-03-12T23:59:00+09:00",
        "my_request_count": 5,
        "total_slot_count": 21
      }
    ]
  }
}
```

- `confirmed_shifts`: `PUBLISHED` 状態のスケジュールで自分がアサインされているスロット
- `confirmed_shifts[].estimated_pay`: 時給設定がある場合のみ返却。`hours` はスロットの実勤務時間（深夜跨ぎ対応）、`hourly_rate` は `slot_date` 時点の適用時給、`amount = hours × hourly_rate`。時給未設定の場合は `null`
- `pay_summary`: 取得期間内の全確定シフトの給与概算合計。時給未設定の場合は `null`。複数チームの場合はチーム別に集計しない（合計のみ）
- `pending_requests`: `COLLECTING` 状態のスケジュールで自分が所属するチームのもの。未提出の場合は `my_request_count: 0` で含める（未提出の気づき促進）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 401 | 未認証（トークン無効・期限切れ） |

---

#### `GET /api/v1/shifts/schedules/{id}/summary` 【🟢 実装済（Phase 11 第二陣 2-α / 2026-05-17）】

> ※ 実装: `ShiftScheduleController#getScheduleSummary` / `ShiftScheduleService#getScheduleSummary`（PR #829 main マージ済）。Controller に `@PreAuthorize("hasRole('ADMIN')")` を宣言済（`@EnableMethodSecurity` 付与フェーズで実機認可が効く）。
>
> **N+1 修正（Phase 11 事後検分 fixup / 2026-05-17）:**
> 初版は slot ごとに `findAllBySlotId()` を呼び出す N+1 クエリだった。`ShiftAssignmentRepository#findAllByScheduleId(scheduleId)`（JPQL JOIN）を追加して 1 回 SQL に統合し、Java 側で `slotId` でグルーピングする形に改修した。slot 数 N に対して SQL は 1 回（schedule 1 + slot 1 + assignment 1 + request 1 + position 1）。

日付別・ポジション別の充足状況サマリーを取得する。管理者のシフト調整画面で使用。

**レスポンス（200 OK）**
```json
{
  "data": {
    "schedule_id": 1,
    "dates": [
      {
        "date": "2026-03-09",
        "positions": [
          {
            "position": { "id": 1, "name": "ホール" },
            "required": 3,
            "assigned": 2,
            "preference_counts": {
              "preferred": 4,
              "available": 2,
              "weak_rest": 1,
              "strong_rest": 1,
              "absolute_rest": 0
            },
            "vacancy": 1
          },
          {
            "position": { "id": 2, "name": "キッチン" },
            "required": 2,
            "assigned": 2,
            "preference_counts": {
              "preferred": 3,
              "available": 1,
              "weak_rest": 0,
              "strong_rest": 2,
              "absolute_rest": 0
            },
            "vacancy": 0
          }
        ],
        "total_required": 5,
        "total_assigned": 4
      }
    ]
  }
}
```

- `position` が NULL のスロットは `"position": null` として集計に含める（「ポジション指定なし」枠）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | シフトスケジュールが存在しない |

---

#### `POST /api/v1/shifts/schedules/{id}/remind` 【🟢 実装済（Phase 11 第二陣 2-α / 2026-05-17）】

> ※ 実装: `ShiftScheduleController#remindUnsubmitted` / `ShiftPreferenceReminderBatchService#triggerManualReminder`（PR #829 main マージ済）。Controller に `@PreAuthorize("hasRole('ADMIN')")` を宣言済（`@EnableMethodSecurity` 付与フェーズで実機認可が効く）。
>
> **二重起動防止（Phase 11 事後検分 fixup / 2026-05-17）:**
> cron バッチ (`processReminders`) は `@SchedulerLock` で保護されているが、初版の手動 API は無保護で ADMIN 連打により重複通知のリスクがあった。`triggerManualReminder()` 冒頭で Valkey SET NX EX（キー `shift:manual-reminder:lock:{scheduleId}`、TTL 15 秒）を取得する形に改修した。連打時は `SHIFT_036 MANUAL_REMINDER_THROTTLED` で 400 を返す。cron 側ロックとは別の名前空間のため業務的に衝突しない。

管理者が未提出メンバーに手動でリマインド通知を送信する。自動リマインド（24時間前）とは別に、任意のタイミングで送信可能。

**レスポンス（200 OK）**
```json
{
  "data": {
    "schedule_id": 1,
    "reminded_count": 4,
    "reminded_users": [
      { "id": 12, "display_name": "鈴木一郎" },
      { "id": 15, "display_name": "山田次郎" }
    ]
  }
}
```

- `reminded_count`: リマインド送信対象の未提出メンバー数。0件の場合は全員提出済み
- `COLLECTING` 状態でのみ実行可能

**エラーレスポンス**
| ステータス | エラーコード | 条件 |
|-----------|---|------|
| 400 | `SHIFT_036` (`MANUAL_REMINDER_THROTTLED`) | 同一スケジュールへの 15 秒以内の連打（Valkey ロック取得失敗） |
| 403 | - | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | `SHIFT_001` | シフトスケジュールが存在しない |
| 409 | `SHIFT_012` | `COLLECTING` 以外のステータス |

---

#### `GET /api/v1/shifts/positions`

チームのポジションマスター一覧を取得する。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | 必須 | チーム ID |
| `is_active` | Boolean | — | 有効フラグでフィルタ（デフォルト: true のみ表示） |

**レスポンス（200 OK）**
```json
{
  "data": [
    { "id": 1, "name": "ホール", "display_order": 1, "is_active": true },
    { "id": 2, "name": "キッチン", "display_order": 2, "is_active": true },
    { "id": 3, "name": "レジ", "display_order": 3, "is_active": true }
  ]
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | チームへのアクセス権限がない |

---

#### `POST /api/v1/shifts/positions`

ポジションを作成する。

**リクエストボディ**
```json
{
  "team_id": 5,
  "name": "ホール",
  "display_order": 1
}
```

- `name`: 必須・1〜50文字。同一チーム内で重複不可
- `display_order`: 任意（デフォルト: 0）

**レスポンス（201 Created）**: ポジション単体を返却

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー / 同一チーム内の名前重複 |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |

---

#### `PATCH /api/v1/shifts/positions/{id}`

> ※ 旧設計は PUT。実装は PATCH に統一。

ポジションの名前・表示順・有効フラグを更新する。

**リクエストボディ**
```json
{
  "name": "ホール担当",
  "display_order": 1,
  "is_active": true
}
```

**レスポンス（200 OK）**: ポジション単体を返却

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー / 同一チーム内の名前重複 |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | ポジションが存在しない |

---

#### `DELETE /api/v1/shifts/positions/{id}`

ポジションを無効化する（`is_active = FALSE` に更新。物理削除ではない）。既存スロットの参照は維持される。

**レスポンス（200 OK）**
```json
{
  "data": { "id": 1, "name": "ホール", "is_active": false }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | ポジションが存在しない |

---

#### `POST /api/v1/shifts/swap-requests`

メンバーがシフト交代リクエストを作成する。`PUBLISHED` 状態のスケジュールに属する、自分がアサインされているスロットのみ対象。**v2.1 より 2 つのモードをサポート**: (A-2) 特定メンバー指名、(A-3) オープンコール（全体募集）。

**リクエストボディ（A-2 個別指名）**
```json
{
  "slot_id": 101,
  "reason": "体調不良のため",
  "target_user_id": 11,
  "is_open_call": false
}
```

**リクエストボディ（A-3 オープンコール = 全体募集）**【v2.1】
```json
{
  "slot_id": 101,
  "reason": "急な通院で出られません。代わりに入れる方を募集します",
  "target_user_id": null,
  "is_open_call": true
}
```

- `slot_id`: 必須。自分が `assigned_user_ids` に含まれるスロットであること
- `reason`: 任意・最大500文字（オープンコール時はメッセージ訴求のため実質必須運用）
- `target_user_id`: **【v2.1】** 任意。指名交代の場合に相手ユーザー ID を指定。オープンコール時は `null`
- `is_open_call`: **【v2.1】** 任意（デフォルト `false`）。`true` の場合は `target_user_id` を `null` にすること（Service 層で排他チェック）。両方指定した場合は 400

**レスポンス（201 Created）**: 交代リクエスト単体を返却

- **A-2**: 指名相手にプッシュ + アプリ内通知（「田中さんが3/9 ホール 09:00-17:00 の交代を依頼しています」）。既存 `ShiftSwapRequestedEvent` 継続
- **A-3**【v2.1】: チーム全員（SUPPORTER/GUEST 除く、依頼者自身除く、通知オプトアウト設定者除く）にプッシュ + アプリ内通知（「田中さんが3/9 ホール 09:00-17:00 の代打を募集しています」）。`ShiftOpenCallCreatedEvent` を発行

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | 自分がアサインされていないスロット / 同一スロットに未解決の交代リクエストが既存 / `is_open_call=true` かつ `target_user_id` 指定（排他違反） |
| 403 | チームメンバーではない |
| 404 | スロットが存在しない / 指名した `target_user_id` がチームメンバーではない |
| 409 | `PUBLISHED` 以外のステータス |
| 429 | **【v2.1】** オープンコール月間上限（3件/月/ユーザー）超過 |

---

#### `POST /api/v1/shifts/swap-requests/{id}/accept`

> ※ 旧設計は PATCH。実装は POST に変更。

他メンバーがシフト交代を引き受ける。

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 1,
    "status": "ACCEPTED",
    "accepter": { "id": 11, "display_name": "佐藤花子" }
  }
}
```

- 依頼者と管理者にプッシュ通知（「佐藤花子さんがシフト交代を引き受けました。管理者の承認をお待ちください」）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | 依頼者本人は引き受け不可 |
| 403 | チームメンバーではない |
| 404 | 交代リクエストが存在しない |
| 409 | `PENDING` 以外のステータス |

---

#### `POST /api/v1/shifts/swap-requests/{id}/resolve`

> ※ 旧設計は `PATCH /approve` + `PATCH /reject` の 2 本。実装は `POST /resolve` 1 本に統合し、リクエストボディの `decision: "APPROVE" | "REJECT"` で分岐する方式に変更。以降のサブセクションは旧設計の参考情報として残す。

#### 【旧】`PATCH /api/v1/shifts/swap-requests/{id}/approve`

管理者がシフト交代を承認する。`assigned_user_ids` の自動更新を含む。

**リクエストボディ**
```json
{
  "admin_note": "承認します",
  "slot_version": 2
}
```

- `slot_version`: 必須。スロットの楽観的ロック（`assigned_user_ids` 更新時の競合防止）

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 1,
    "status": "APPROVED",
    "resolved_by": { "id": 10, "display_name": "田中太郎" },
    "resolved_at": "2026-03-10T10:00:00+09:00"
  }
}
```

- 承認時の処理（1トランザクション）: スロットの `assigned_user_ids` から `requester_id` を除去 → `accepter_id` を追加 → 両メンバーにプッシュ通知
- ApplicationEvent: `ShiftSwapApprovedEvent`

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | 交代リクエストが存在しない |
| 409 | `ACCEPTED` 以外のステータス / 楽観的ロック競合 |

---

#### 【旧】`PATCH /api/v1/shifts/swap-requests/{id}/reject`

管理者がシフト交代を却下する。

**リクエストボディ**
```json
{
  "admin_note": "当日は人員が不足するため交代不可"
}
```

**レスポンス（200 OK）**: 交代リクエスト単体を返却（`status: "REJECTED"`）

- 依頼者と引き受け者にプッシュ通知

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | 交代リクエストが存在しない |
| 409 | `PENDING` / `ACCEPTED` 以外のステータス |

---

#### `DELETE /api/v1/shifts/swap-requests/{id}`

交代リクエストを取り下げる（`status = CANCELLED` に更新）。

**レスポンス（200 OK）**: 交代リクエスト単体を返却（`status: "CANCELLED"`）

- **オープンコールの取下時（v2.1）**: 既に `CLAIMED` 状態でも依頼者本人なら取下可能。手挙げ済みだった `claimed_by` ユーザーに「募集が取下されました」通知を送信

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 依頼者本人ではない |
| 404 | 交代リクエストが存在しない |
| 409 | `APPROVED` / `REJECTED` / `CANCELLED` は取り下げ不可（既に ACCEPTED/CLAIMED は取下可） |

---

#### `POST /api/v1/shifts/swap-requests/{id}/claim`【v2.1 新規】

オープンコール（`is_open_call=true`, `status=OPEN_CALL`）に対して「代わりに入ります」と手を挙げる API。**先着優先**を楽観的ロックで保証する。

**リクエストボディ**
```json
{
  "version": 0
}
```

- `version`: 必須。クライアントが取得した時点の `shift_swap_requests.version`。サーバ側で `WHERE version = :version` で更新し、競合時は 409

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 1,
    "slot_id": 101,
    "status": "CLAIMED",
    "claimed_by": { "id": 12, "display_name": "高橋次郎" },
    "claimed_at": "2026-04-23T10:05:00+09:00",
    "version": 1
  }
}
```

- **処理（1トランザクション）**:
  1. 対象 swap_request を `SELECT ... FOR UPDATE` でロック（念押しの悲観ロック併用）
  2. `status != 'OPEN_CALL'` なら 409（既に他ユーザーが claim 済み or 取下済み）
  3. `version` 不一致なら 409（並行 claim の片方が先着したケース）
  4. `claimed_by = 認証ユーザーID`, `claimed_at = NOW()`, `status = 'CLAIMED'`, `version + 1` に更新
  5. 依頼者・管理者にプッシュ通知（「高橋次郎さんが代打に応じました。候補を確定してください」）
  6. ApplicationEvent: `ShiftOpenCallClaimedEvent`

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | `is_open_call = FALSE` の swap_request を対象にした（個別指名には claim 不可） |
| 403 | チームメンバーではない / SUPPORTER/GUEST |
| 403 | 依頼者本人が自分の募集に claim しようとした |
| 404 | swap_request が存在しない |
| 409 | `status != 'OPEN_CALL'`（既に他者が先着、または CANCELLED）/ 楽観的ロック競合 |

---

#### `POST /api/v1/shifts/swap-requests/{id}/select-claimer`【v2.1 新規】

オープンコールの `CLAIMED` 状態から `ACCEPTED` 状態へ遷移させ、`accepter_id` を確定する API。**依頼者または管理者**が実行可能。管理者は先着者（`claimed_by`）以外の候補に差し替える裁量を持つ（例: 手挙げ者が複数いた場合、過去に手挙げしたがキャンセルされた候補を選ぶ、スキル要件を満たす別メンバーを選ぶ）。

**リクエストボディ**
```json
{
  "accepter_user_id": 12,
  "version": 1
}
```

- `accepter_user_id`: 必須。確定する候補者の user_id。通常は `claimed_by` と同一値。管理者のみ異なる値を指定可能
- `version`: 必須。楽観的ロック

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 1,
    "status": "ACCEPTED",
    "accepter": { "id": 12, "display_name": "高橋次郎" },
    "version": 2
  }
}
```

- 以降は通常の `PATCH /swap-requests/{id}/approve` フローで管理者承認に進む
- 依頼者・確定された accepter の両方にプッシュ通知

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | `accepter_user_id` がチームメンバーではない / 依頼者本人を指定 / SUPPORTER/GUEST 指定 |
| 403 | 依頼者本人でも管理者でもない / 依頼者が `claimed_by` 以外の値を指定（管理者のみ裁量可） |
| 404 | swap_request が存在しない |
| 409 | `status != 'CLAIMED'` / 楽観的ロック競合 |

---

#### `GET /api/v1/shifts/availability`

ログインユーザーの週間デフォルト可否プロファイルを取得する。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | 必須 | チーム ID |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1,
      "day_of_week": 0,
      "start_time": null,
      "end_time": null,
      "preference": "AVAILABLE",
      "note": null
    },
    {
      "id": 2,
      "day_of_week": 2,
      "start_time": "09:00",
      "end_time": "12:00",
      "preference": "ABSOLUTE_REST",
      "note": "大学の授業のため"
    }
  ]
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | チームメンバーではない |

---

#### `PUT /api/v1/shifts/availability`

週間デフォルト可否プロファイルを一括更新する。既存レコードを全削除して全件 INSERT する置換方式。

**リクエストボディ**
```json
{
  "team_id": 5,
  "availabilities": [
    { "day_of_week": 0, "start_time": null, "end_time": null, "preference": "AVAILABLE", "note": null },
    { "day_of_week": 2, "start_time": "09:00", "end_time": "12:00", "preference": "ABSOLUTE_REST", "note": "大学の授業のため" },
    { "day_of_week": 4, "start_time": null, "end_time": null, "preference": "PREFERRED", "note": "金曜は終日可" },
    { "day_of_week": 6, "start_time": null, "end_time": null, "preference": "WEAK_REST", "note": "日曜は家族と過ごしたいができれば休みたい" }
  ]
}
```

- `availabilities`: 最大50件。空配列の場合は全件削除（プロファイルなし = 全曜日の初期値なし）
- `day_of_week`: 0〜6（月〜日）

**レスポンス（200 OK）**: 更新後の全プロファイルを返却

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（50件超過、day_of_week 範囲外等） |
| 403 | チームメンバーではない |

---

#### `GET /api/v1/shifts/hourly-rate`

ログインユーザーの時給設定を取得する。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | 必須 | チーム ID |

**レスポンス（200 OK）**
```json
{
  "data": {
    "current_rate": {
      "id": 1,
      "hourly_rate": 1200.00,
      "effective_from": "2026-01-01"
    },
    "history": [
      { "id": 1, "hourly_rate": 1200.00, "effective_from": "2026-01-01" },
      { "id": 0, "hourly_rate": 1100.00, "effective_from": "2025-04-01" }
    ]
  }
}
```

- `current_rate`: `effective_from <= TODAY` の最新レコード。未設定の場合は `null`
- `history`: 時給改定履歴（新しい順）

**認可**（認可根治 Wave6 追加戦 / 実装は `ShiftHourlyRateService#checkHourlyRateAccess`）

`01_db_design.md` の「時給の閲覧権限: 本人 + ADMIN / DEPUTY_ADMIN のみ」に準拠する。

| 呼び出し元 | `user_id` が本人 | `user_id` が他メンバー |
|---|---|---|
| SYSTEM_ADMIN | 許可 | 許可 |
| 当該チームの ADMIN / DEPUTY_ADMIN | 許可 | 許可（対象も当該チームのメンバーである場合のみ）|
| 当該チームの一般メンバー | 許可 | **403** |
| 当該チームの非メンバー（別チーム ADMIN 含む）| **403** | **403** |

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | チームメンバーではない / 他メンバーの時給を管理権限なしで参照した |

---

#### `POST /api/v1/shifts/hourly-rate`

> ※ 旧設計は PUT。実装は POST に変更（`ShiftAvailabilityController#createHourlyRate`、Service 側で upsert）。

自分の時給を登録・更新する。

**リクエストボディ**
```json
{
  "team_id": 5,
  "hourly_rate": 1200.00,
  "effective_from": "2026-04-01"
}
```

- `hourly_rate`: 必須。1〜999999.99
- `effective_from`: 必須。未来日も過去日も指定可能（過去日指定で「入社時の時給を遡って登録」に対応）
- 同一 `effective_from` のレコードが既存の場合は上書き更新

**レスポンス（200 OK）**: 更新後の全時給履歴を返却

**認可**（認可根治 Wave6 追加戦 / 実装は `ShiftHourlyRateService#checkHourlyRateAccess`）

参照（`GET /api/v1/shifts/hourly-rate`）と同一の判定を書込にも適用する。
ボディの `user_id` が本人以外を指す場合は、呼び出し元が当該チームの ADMIN / DEPUTY_ADMIN であり、
かつ**対象ユーザーも当該チームのメンバーである**ことを要求する（対象側 BOLA 封鎖）。

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | チームメンバーではない / 他メンバーの時給を管理権限なしで登録した / 対象ユーザーが当該チームの非メンバー |

---

#### `GET /api/v1/shifts/hourly-rates` 【v2 計画・現状未実装】

> ※ 管理者向け時給一覧 API。現状実装は単数 `/shifts/hourly-rate` のみ。v2 で `ShiftHourlyRateAdminController` 追加予定（triage_log `shifts.md` §5-2 参照）。

チームメンバーの時給一覧を取得する。管理者がチーム全体の人件費を把握するための画面用。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | 必須 | チーム ID |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "user": { "id": 10, "display_name": "田中太郎" },
      "current_rate": 1200.00,
      "effective_from": "2026-01-01"
    },
    {
      "user": { "id": 11, "display_name": "佐藤花子" },
      "current_rate": 1100.00,
      "effective_from": "2025-10-01"
    },
    {
      "user": { "id": 12, "display_name": "鈴木一郎" },
      "current_rate": null
    }
  ]
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |

---

#### `PUT /api/v1/shifts/hourly-rates/{userId}` 【v2 計画・現状未実装】

> ※ 管理者によるメンバー時給設定 API。現状未実装、v2 で対応予定。

管理者がメンバーの時給を設定する。メンバー本人が設定していない場合に管理者が代理登録するケースを想定。

**リクエストボディ**
```json
{
  "team_id": 5,
  "hourly_rate": 1300.00,
  "effective_from": "2026-04-01"
}
```

**レスポンス（200 OK）**: 対象ユーザーの時給履歴を返却

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | ユーザーがチームメンバーではない |

---

#### `POST /api/v1/shifts/schedules/{id}/auto-assign`【v2 新規】

自動割当を実行する。`ADJUSTING` 状態のみ実行可能。戦略（`strategy`）を指定し、結果を `shift_assignments` に `PROPOSED` 状態で書き込む。**既存の `PROPOSED` レコードがあれば REVOKED に遷移させてから新規提案を書き込む**（1実行 = 1提案セット）。

**リクエストボディ**
```json
{
  "strategy": "GREEDY_V1",
  "parameters": {
    "preference_weight": 1.0,
    "fairness_weight": 0.5,
    "consecutive_penalty_weight": 0.8,
    "respect_work_constraints": true,
    "overwrite_existing": false
  }
}
```

- `strategy`: 必須。`GREEDY_V1` のみサポート（MVP）。将来 `CSP_V1` 等を追加
- `parameters`: 任意。省略時は Java 側の `GreedyShiftAssignmentStrategy` のデフォルト値を使用
  - `preference_weight`: 希望強度スコアの重み（デフォルト 1.0）
  - `fairness_weight`: 夜勤公平性スコアの重み（デフォルト 0.5）
  - `consecutive_penalty_weight`: 連勤ペナルティ重み（デフォルト 0.8）
  - `respect_work_constraints`: true = ハード制約として扱う（デフォルト true）。false = ソフト制約（警告のみ）
  - `overwrite_existing`: true = 既存の `CONFIRMED` 割当も提案対象に含める（上書き）。false = 空きスロットのみ対象（デフォルト）

**レスポンス（200 OK / 同期完了時）**
```json
{
  "data": {
    "run_id": 1024,
    "status": "SUCCEEDED",
    "strategy": "GREEDY_V1",
    "schedule_id": 1,
    "started_at": "2026-04-23T10:00:00+09:00",
    "finished_at": "2026-04-23T10:00:02+09:00",
    "duration_ms": 2150,
    "slots_total": 100,
    "slots_filled": 95,
    "warnings_summary": { "vacancy": 5, "constraint_violations": 2 }
  }
}
```

**レスポンス（202 Accepted / 非同期受付時・将来拡張用）**
```json
{
  "data": {
    "run_id": 1024,
    "status": "RUNNING",
    "strategy": "GREEDY_V1",
    "schedule_id": 1,
    "started_at": "2026-04-23T10:00:00+09:00"
  }
}
```

- 実行は同期処理（MVP）。100スロット×100名規模なら3秒以内で `200 OK` を返却。詳細な割当結果は `GET /shifts/assignment-runs/{runId}` で取得
- 将来 CSP ソルバ等で処理が長引く場合は `202 Accepted` を返して非同期化。クライアントは `status` フィールドで分岐（`SUCCEEDED` / `RUNNING`）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー / 未対応の strategy |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | シフトスケジュールが存在しない |
| 409 | `ADJUSTING` 以外のステータス / 既に `RUNNING` 中の実行がある |
| 429 | レートリミット超過（同一スケジュールで1分以内に3回実行） |

---

#### `POST /api/v1/shifts/schedules/{id}/auto-assign/confirm`【v2 新規】

自動割当の提案を確定する。`shift_assignments` の `PROPOSED` レコードを `CONFIRMED` に遷移させ、同時に `shift_slots.assigned_user_ids` を一括更新する。

**リクエストボディ**
```json
{
  "run_id": 1024,
  "assignment_ids": null,
  "schedule_version": 3
}
```

- `run_id`: 必須。確定対象の実行 ID
- `assignment_ids`: 任意。特定の `shift_assignments.id` 配列を指定すると部分確定（例: 一部のみ採用）。NULL / 空配列の場合は run_id 配下の全 PROPOSED を確定
- `schedule_version`: 必須。スケジュールの楽観的ロック

**レスポンス（200 OK）**
```json
{
  "data": {
    "run_id": 1024,
    "confirmed_count": 85,
    "skipped_count": 5,
    "affected_slots": 90,
    "schedule_version": 4
  }
}
```

- `skipped_count`: 既に CONFIRMED 状態の割当により上書きが必要だがスキップされた件数（将来の競合チェック用）
- 確定後、影響スロットの `shift_slots.version` もインクリメント
- **楽観的ロックの範囲**: 本 API は `schedule_version` のみ検証する（複数スロットを一括更新するため、スロット個別の version チェックは行わない）。複数管理者が同時に confirm しないよう、**スケジュール単位のロック**で競合検出。個別スロットの手動編集と衝突した場合は、confirm 実行直前に `shift_slots.version` の最新値を取得して更新するため、手動編集は失われない（自動割当確定 → 手動微調整の順序が前提）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | 実行 ID が存在しない / 他スケジュールの run_id |
| 409 | 楽観的ロック競合 / run_id の status が SUCCEEDED ではない |

---

#### `DELETE /api/v1/shifts/schedules/{id}/auto-assign`【v2 新規】

未確定の自動割当提案を破棄する。`PROPOSED` 状態の全 `shift_assignments` を `REVOKED` に遷移させる（物理削除しない = 監査証跡）。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `run_id` | Long | — | 特定の実行のみ破棄。省略時はスケジュール内の全 PROPOSED を破棄 |

**レスポンス（200 OK）**
```json
{
  "data": {
    "revoked_count": 90
  }
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | シフトスケジュールが存在しない |

---

#### `GET /api/v1/shifts/schedules/{id}/assignment-runs`【v2 新規】

自動割当の実行履歴を取得する。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `limit` | Integer | — | 取得件数（デフォルト 20、最大 50） |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 1024,
      "strategy": "GREEDY_V1",
      "status": "SUCCEEDED",
      "triggered_by": { "id": 10, "display_name": "田中太郎" },
      "started_at": "2026-04-23T10:00:00+09:00",
      "finished_at": "2026-04-23T10:00:02+09:00",
      "duration_ms": 2150,
      "slots_total": 100,
      "slots_filled": 95,
      "warnings_summary": { "vacancy": 5, "constraint_violations": 2 }
    }
  ]
}
```

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | シフトスケジュールが存在しない |

---

#### `GET /api/v1/shifts/assignment-runs/{runId}`【v2 新規】

自動割当実行の詳細（スコアリング内訳・警告・提案の全リスト）を取得する。

**レスポンス（200 OK）**
```json
{
  "data": {
    "id": 1024,
    "schedule_id": 1,
    "strategy": "GREEDY_V1",
    "status": "SUCCEEDED",
    "parameters": {
      "preference_weight": 1.0,
      "fairness_weight": 0.5,
      "consecutive_penalty_weight": 0.8,
      "respect_work_constraints": true,
      "overwrite_existing": false
    },
    "assignments": [
      {
        "id": 50001,
        "slot_id": 101,
        "user": { "id": 10, "display_name": "田中太郎" },
        "status": "PROPOSED",
        "score": 45,
        "score_breakdown": {
          "preference": 50,
          "fairness": -5,
          "consecutive": 0
        }
      }
    ],
    "warnings": [
      {
        "type": "VACANCY",
        "slot_id": 102,
        "required": 3,
        "assigned": 2
      },
      {
        "type": "CONSTRAINT_VIOLATION",
        "user": { "id": 11, "display_name": "佐藤花子" },
        "constraint": "MAX_CONSECUTIVE_DAYS",
        "expected": 5,
        "actual": 6
      }
    ]
  }
}
```

- `score_breakdown`: 各スコア成分の値。デバッグ用に内訳を公開
- `assignments` は `PROPOSED` / `CONFIRMED` / `REVOKED` 全て含む

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | 実行 ID が存在しない |

---

#### `PATCH /api/v1/shifts/slots/{id}/assignments`【v2 新規】

D&D UI 用の差分割当 API。個別ユーザーの追加・削除を `shift_slots.assigned_user_ids` に即時反映する。**楽観的更新（optimistic UI）対応**: クライアント側で即座に UI を更新し、サーバ検証で失敗したら差し戻す。

**リクエストボディ**
```json
{
  "add_user_ids": [12],
  "remove_user_ids": [10],
  "slot_version": 3
}
```

- `add_user_ids`: 追加する user_id の配列（0件可）
- `remove_user_ids`: 削除する user_id の配列（0件可）
- `slot_version`: 必須。スロットの楽観的ロック
- 両配列が空の場合は 400 エラー

**レスポンス（200 OK）**
```json
{
  "data": {
    "slot_id": 101,
    "assigned_user_ids": [11, 12, 13],
    "slot_version": 4,
    "warnings": [
      {
        "type": "CONSTRAINT_VIOLATION",
        "user_id": 12,
        "constraint": "MAX_CONSECUTIVE_DAYS",
        "expected": 5,
        "actual": 6,
        "message": "田中太郎さんが6連勤になります"
      }
    ]
  }
}
```

- `warnings`: ハード制約違反（`ABSOLUTE_REST`）は 409 で失敗。ソフト制約（連勤・月次時間等）は警告のみで成功

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（add/remove 両方空、無効な user_id 等） |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | スロットが存在しない |
| 409 | 楽観的ロック競合 / 追加対象ユーザーが ABSOLUTE_REST を提出している / PUBLISHED 状態で時間帯・必要人数変更に該当する操作 |

---

#### `GET /api/v1/shifts/teams/{teamId}/work-constraints`

> ※ 旧設計は `/shifts/work-constraints`（フラット）。実装は `/shifts/teams/{teamId}/work-constraints` のチームスコープに変更（`MemberWorkConstraintController`、複数チーム所属時の制約管理に対応）。

#### 【旧パス】`GET /api/v1/shifts/work-constraints`【v2 新規】

チームの勤務制約一覧（デフォルト + 個別オーバーライド）を取得する。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `team_id` | Long | 必須 | チーム ID |

**レスポンス（200 OK）**
```json
{
  "data": {
    "default": {
      "max_monthly_hours": 160.00,
      "max_monthly_days": 23,
      "max_consecutive_days": 5,
      "max_night_shifts_per_month": 8,
      "min_rest_hours_between_shifts": 11.00,
      "note": null
    },
    "overrides": [
      {
        "user": { "id": 10, "display_name": "田中太郎" },
        "max_monthly_hours": 80.00,
        "max_monthly_days": null,
        "max_consecutive_days": null,
        "max_night_shifts_per_month": null,
        "min_rest_hours_between_shifts": null,
        "note": "学生のため月80時間まで"
      }
    ]
  }
}
```

- `default`: チームデフォルト（`user_id IS NULL` のレコード）。未設定なら `null`
- `overrides`: 個別オーバーライド（`user_id IS NOT NULL`）の配列
- 本人（非管理者）は **自分の制約のみ閲覧可能**（フィルタ後の `overrides` を返却）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | チームメンバーではない |

---

#### `PUT /api/v1/shifts/teams/{teamId}/work-constraints/default`

> ※ 旧設計は `/shifts/work-constraints`（フラット）。実装は `/shifts/teams/{teamId}/work-constraints/default` のチームスコープ。

#### 【旧パス】`PUT /api/v1/shifts/work-constraints`【v2 新規】

チームデフォルトの勤務制約を更新する。ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）のみ。

**リクエストボディ**
```json
{
  "team_id": 5,
  "max_monthly_hours": 160.00,
  "max_monthly_days": 23,
  "max_consecutive_days": 5,
  "max_night_shifts_per_month": 8,
  "min_rest_hours_between_shifts": 11.00,
  "note": null
}
```

- 全項目が NULL 可能。NULL を明示的に送信するとその制約は適用されない
- 全項目 NULL の場合はデフォルトレコードを削除（`DELETE` 相当の挙動）

**レスポンス（200 OK）**: 更新後の `default` オブジェクトを返却

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（負値、上限超過等） |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |

---

#### `PUT /api/v1/shifts/teams/{teamId}/work-constraints/members/{userId}`

> ※ 旧設計は `/shifts/work-constraints/{userId}`（フラット）。実装は `/shifts/teams/{teamId}/work-constraints/members/{userId}` のチームスコープ。

#### 【旧パス】`PUT /api/v1/shifts/work-constraints/{userId}`【v2 新規】

メンバー個別の勤務制約を設定・更新する。

**リクエストボディ**: `PUT /api/v1/shifts/work-constraints` と同一スキーマ（`team_id` 必須）

**レスポンス（200 OK）**: 更新後の個別制約オブジェクトを返却

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | ユーザーがチームメンバーではない |

---

#### `DELETE /api/v1/shifts/teams/{teamId}/work-constraints/members/{userId}`

> ※ 旧設計は `/shifts/work-constraints/{userId}`（フラット）。実装は `/shifts/teams/{teamId}/work-constraints/members/{userId}` のチームスコープ。

#### 【旧パス】`DELETE /api/v1/shifts/work-constraints/{userId}`【v2 新規】

メンバー個別の勤務制約を削除し、チームデフォルトに戻す。

**レスポンス（204 No Content）**

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | 個別制約レコードが存在しない |

---

#### `POST /api/v1/shifts/change-requests`【v2.1 新規】

確定前（`DRAFT / COLLECTING / ADJUSTING`）のスケジュールに対して、メンバーが変更依頼を作成する。

**リクエストボディ**
```json
{
  "schedule_id": 1,
  "slot_id": 101,
  "request_type": "SWAP_SELF",
  "target_user_id": 12,
  "target_slot_id": null,
  "target_slot_date": null,
  "reason": "その日は通院予約が入ってしまいました。誰かと交代してもらえないでしょうか"
}
```

- `schedule_id`: 必須。`status ∈ {DRAFT, COLLECTING, ADJUSTING}` のスケジュールであること
- `slot_id`: 任意。特定スロットを対象にする場合のみ。NULL は日付/スケジュール全体に対する依頼
- `request_type`: 必須。`SWAP_SELF / CHANGE_DATE / CHANGE_SLOT / CANCEL_SELF / OTHER` のいずれか
- `target_user_id`: 任意（`SWAP_SELF` のヒント）
- `target_slot_id`: 任意（`CHANGE_SLOT` のヒント）
- `target_slot_date`: 任意（`CHANGE_DATE` のヒント）
- `reason`: 必須・最大1000文字。理由のテンプレートボタン（「通院」「家族の用事」「大学の試験」等）から選べる UI を提供（ADHD 配慮で入力摩擦ゼロ）

**レスポンス（201 Created）**: 作成された change_request を返却（`status: "OPEN"`）

- 管理者全員（ADMIN + `MANAGE_SHIFTS` を持つ DEPUTY_ADMIN）にプッシュ + アプリ内通知（「田中さんから変更依頼が届いています」）
- ApplicationEvent: `ShiftChangeRequestCreatedEvent`
- 監査ログ: `SHIFT_CHANGE_REQUEST_CREATED`

**権限モデル（IDOR 防止）**
- MEMBER / DEPUTY_ADMIN の依頼範囲は **自分に関する範囲のみ**:
  - `request_type = SWAP_SELF / CANCEL_SELF / CHANGE_DATE / CHANGE_SLOT`: 対象 slot の `assigned_user_ids` に自分が含まれる、または自分が未割当で当該日に関する依頼のみ可。他人の割当スロットを指定した場合は 403
  - `request_type = OTHER`: `slot_id = NULL` のみ許可（特定他者のスロットを指すには必ず 403）
- ADMIN は全範囲可能（ただし管理者は通常 API を使わず直接編集する運用）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | バリデーションエラー（reason 空、request_type 不正、target_slot_id が他スケジュール等） |
| 403 | チームメンバーではない / 他人の割当に対する依頼 / SUPPORTER・GUEST |
| 404 | schedule_id / slot_id が存在しない |
| 409 | `status` が `PUBLISHED / ARCHIVED`（shift_swap_requests の管轄に案内） |
| 429 | 同一スケジュールに対する自分の `OPEN` 状態の依頼数が 5 件を超過 |

---

#### `GET /api/v1/shifts/change-requests`【v2.1 新規】

変更依頼の一覧を取得する。

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `schedule_id` | Long | 必須 | 対象スケジュール |
| `status` | String | — | `OPEN,ACCEPTED,REJECTED,WITHDRAWN` カンマ区切り（デフォルト: 全件） |
| `request_type` | String | — | 種別フィルタ |
| `requester_user_id` | Long | — | 依頼者フィルタ（ADMIN のみ指定可。非管理者は強制的に自分の ID で絞り込まれる） |
| `cursor` / `limit` | — | — | ページネーション（limit デフォルト 20、最大 50） |

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "id": 501,
      "schedule_id": 1,
      "slot_id": 101,
      "requester": { "id": 10, "display_name": "田中太郎" },
      "request_type": "SWAP_SELF",
      "target_user": { "id": 12, "display_name": "高橋次郎" },
      "target_slot_id": null,
      "target_slot_date": null,
      "reason": "通院予約のため",
      "status": "OPEN",
      "reviewed_by": null,
      "reviewed_at": null,
      "admin_note": null,
      "version": 0,
      "created_at": "2026-04-23T10:00:00+09:00"
    }
  ],
  "meta": { "next_cursor": null, "has_more": false }
}
```

- **権限フィルタ**: MEMBER は自分の依頼のみ返却（バックエンドで強制適用。URL 直打ちで他人の一覧は取得不可）。ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）はスケジュール全体を閲覧可能
- **管理画面ソート**: `status = OPEN` を先頭に、続いて `created_at DESC` の複合ソート（未処理を優先表示）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | チームメンバーではない |
| 404 | schedule_id が存在しない |

---

#### `GET /api/v1/shifts/change-requests/{id}`【v2.1 新規】

変更依頼の詳細を取得する。

**レスポンス（200 OK）**: 一覧の1要素と同一スキーマ

- MEMBER は自分の依頼のみ閲覧可能（他人の ID を URL に入れると 403）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 自分の依頼ではない（非管理者） |
| 404 | 依頼が存在しない |

---

#### `PATCH /api/v1/shifts/change-requests/{id}/review`【v2.1 新規】

管理者が変更依頼を受諾（ACCEPTED）または却下（REJECTED）する。

**リクエストボディ**
```json
{
  "action": "ACCEPTED",
  "admin_note": "別日で調整しました。ご確認ください",
  "version": 0
}
```

- `action`: 必須。`ACCEPTED` または `REJECTED`
- `admin_note`: 任意（`REJECTED` の場合は実質必須運用。「理由なし却下は避ける」旨を UI で促す）
- `version`: 必須。楽観的ロック

**レスポンス（200 OK）**: 更新後の change_request を返却

- **重要（ACCEPTED の運用）**: 本 API は `status` フラグの更新のみで、**実スロットの変更は別途管理者が D&D UI や `PATCH /slots/{id}/assignments` で実施する**。本ボタンは「その作業が終わった」宣言として使う設計。理由: 依頼内容が多岐にわたる（単純な交代 / 別日移動 / スロット削除等）ため、画面上で自由に編集してから「この依頼を受諾した」と記録する方が柔軟
- 依頼者にプッシュ + アプリ内通知（「変更依頼が受諾されました」「変更依頼が却下されました: {admin_note}」）
- ApplicationEvent: `ShiftChangeRequestReviewedEvent`
- 監査ログ: `SHIFT_CHANGE_REQUEST_REVIEWED`（`action`, `reviewed_by`, `admin_note` を記録）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 400 | `action` が ACCEPTED / REJECTED 以外 |
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | 依頼が存在しない |
| 409 | `status != 'OPEN'`（二重審査防止）/ 楽観的ロック競合 |

---

#### `DELETE /api/v1/shifts/change-requests/{id}`【v2.1 新規】

依頼者本人が自分の変更依頼を取下（WITHDRAWN）する。論理フラグ更新のみ（物理削除しない）。

**リクエストボディ**
```json
{
  "version": 0
}
```

**レスポンス（200 OK）**: 更新後の change_request を返却（`status: "WITHDRAWN"`）

- 管理者にプッシュ通知（未処理一覧から消える旨）
- 監査ログ: `SHIFT_CHANGE_REQUEST_WITHDRAWN`

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | 依頼者本人ではない |
| 404 | 依頼が存在しない |
| 409 | `status != 'OPEN'` / 楽観的ロック競合 |

---

#### `POST /api/v1/shifts/assignment-runs/{runId}/confirm-visual-review`【v2.1 新規】

自動割当結果の**目視確認**を記録する。`shift_assignment_runs.visual_review_confirmed_by` / `visual_review_confirmed_at` を更新し、以降の `PATCH /publish` を可能にする。

**リクエストボディ**
```json
{
  "visual_review_note": "有資格者が17時台に不在だったため2名を手動差替済。繁忙対応も目視確認OK"
}
```

- `visual_review_note`: 任意・最大500文字。目視で気づいた調整内容や外部文脈（`§1 概要` の注意喚起に挙げた「人間関係・季節要因・スキルバランス等」）をメモとして残せる

**レスポンス（200 OK）**
```json
{
  "data": {
    "run_id": 1024,
    "schedule_id": 1,
    "visual_review_confirmed_by": { "id": 10, "display_name": "田中太郎" },
    "visual_review_confirmed_at": "2026-04-23T11:00:00+09:00",
    "visual_review_note": "…"
  }
}
```

- **ApplicationEvent**: `ShiftAssignmentVisualReviewConfirmedEvent`
- **監査ログ**: `SHIFT_SCHEDULE_VISUAL_REVIEW_CONFIRMED`（`run_id`, `schedule_id`, `visual_review_confirmed_by`, `visual_review_note` を記録）
- **運用ルール**: 目視確認は冪等に再実行可能（`visual_review_confirmed_at` は直近実行時刻で上書き）。自動割当の再実行（`POST /auto-assign`）を行うと新 run が作成されるため、再度の目視確認が必要になる
- **PUBLISHED 遷移との連動**: 自動割当を 1 回でも実行したスケジュールは、`PATCH /publish` 実行時に「**最新成功 run** の `visual_review_confirmed_at IS NOT NULL`」を必須チェック（§5 ビジネスロジック参照）

**エラーレスポンス**
| ステータス | 条件 |
|-----------|------|
| 403 | ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）ではない |
| 404 | run_id が存在しない |
| 409 | run の `status != 'SUCCEEDED'`（FAILED / CANCELLED は目視確認不要・拒否） |

#### `GET /api/v1/shifts/schedules/{id}/pdf`【v2.2 新規】

確定または確定予定のシフト表を PDF として生成・ダウンロードする。F12.1「PDF 生成共通基盤」の `PdfGeneratorService`・`PdfFileNameBuilder`・`PdfResponseHelper` を利用した同期生成。印刷・紙掲示・メンバー配布（整骨院・飲食店・美容室等の実店舗運営想定）・個人配布を用途とする。

**権限**
- `layout=team`: ADMIN / DEPUTY_ADMIN（`MANAGE_SHIFTS`）のみ
- `layout=personal`:
  - MEMBER: `member_id` が**自分自身の user_id と一致する場合のみ**出力可（未指定時は自動的に自分を対象）
  - ADMIN / DEPUTY_ADMIN: 任意メンバーの個人PDFを出力可
  - SUPPORTER / GUEST: 常に 403（確定シフト閲覧権限はあっても PDF 化は情報の二次配布リスクが高いため不可）

**クエリパラメータ**
| パラメータ | 型 | 必須 | 説明 |
|-----------|---|------|------|
| `layout` | String | 必須 | `team`（チーム全体マトリクス）/ `personal`（個人タイムライン）。その他値は 400 |
| `member_id` | Long | 条件付き | `layout=personal` 時のみ有効・任意。未指定時は認証ユーザー自身。MEMBER は他人 ID 指定で 403 |
| `include_draft_watermark` | Boolean | — | 省略時 FALSE。TRUE かつ schedule.status ∈ {DRAFT, COLLECTING, ADJUSTING} のとき「内部確認用」ウォーターマーク付きで出力（ADMIN/DEPUTY_ADMIN のみ、MEMBER は常に 403）。PUBLISHED の場合はこのフラグに関わらずウォーターマークは付与しない |
| `locale` | String | — | 省略時は `Accept-Language` ヘッダに従う。明示指定可（`ja / en / zh / ko / es / de`）。未対応ロケールは 400 ではなく `ja` フォールバック |

**レスポンス（200 OK）**
```
HTTP 200 OK
Content-Type: application/pdf
Content-Disposition: attachment;
  filename="20260423_shift_team.pdf";
  filename*=UTF-8''20260423_%E3%82%B7%E3%83%95%E3%83%88%E8%A1%A8_...pdf
Content-Length: {バイト数}

{PDFバイナリ}
```

**ファイル名規則（F12.1 命名規約に追加）**
- `layout=team`: `{発行日}_シフト表_{チーム名}_{開始日}-{終了日}.pdf`
  - 例: `20260423_シフト表_〇〇整骨院_20260501-20260507.pdf`
- `layout=personal`: `{発行日}_個人シフト_{対象者氏名}_{開始日}-{終了日}.pdf`
  - 例: `20260423_個人シフト_山田花子_20260501-20260507.pdf`
- 内部確認用（`include_draft_watermark=true`）: 上記のファイル名末尾に `_内部確認用` を付与（例: `..._20260501-20260507_内部確認用.pdf`）
- 日付は `yyyyMMdd` 形式。使用不可文字（`/\:*?"<>|`）は `_` に置換（F12.1 §2 に準拠）。個人氏名に「様」敬称は付与しない（内部帳票扱い）
- チーム名・氏名が長大で合計 100 文字を超える場合は末尾を切り詰めて `.pdf` で終端（F12.1 §2 に準拠）

**レスポンスヘッダー**
- `Cache-Control: private, no-store` を必須設定（個人情報を含むため中間キャッシュ禁止）
- `X-Frame-Options: DENY`（クリックジャッキング対策で PDF ビューア埋め込みを抑止）
- `Pragma: no-cache` / `Expires: 0`（旧 HTTP/1.0 プロキシ対策）

**エラーレスポンス**
| ステータス | エラーコード | 条件 |
|-----------|------------|------|
| 400 | `COMMON_001` | `layout` が `team`/`personal` 以外、`member_id` が数値でない、`locale` 形式不正 |
| 400 | `COMMON_001` | `layout=personal` かつ `member_id` 指定ユーザーが当該スケジュールの期間内いずれのスロットにも割当されていない（ゼロ件PDFは生成拒否。空の個人PDFが流出するのを防ぐ） |
| 401 | 各既存コード | 未認証 |
| 403 | `COMMON_002` | SUPPORTER / GUEST、または MEMBER による他人の個人PDF取得、または `include_draft_watermark=true` を MEMBER が指定、または SUPPORTER が team PDF を要求 |
| 404 | — | スケジュールが存在しない／論理削除済み／他チーム所属 |
| 409 | `SHIFT_PDF_001` | `include_draft_watermark=false` にもかかわらず status が `DRAFT` / `COLLECTING` / `ADJUSTING` のいずれか（これらは通常PDF不可）。`PUBLISHED` および `ARCHIVED` は通常 PDF 出力可（`ARCHIVED` は期間終了後の確定版の最終形態として扱う） |
| 429 | `COMMON_029` | レートリミット超過（1ユーザー1分10件） |
| 500 | `PDF_002` | PDF 生成失敗（F12.1 エラーコードに準拠） |
| 500 | `PDF_003` | フォント読み込み失敗（F12.1） |

**含まれる情報**
- ヘッダ: チーム名・期間（`start_date`〜`end_date`）・バージョン文言（例: 「v1 - 2026-04-23 公開」）・出力日時・生成者氏名
- 本文（`layout=team`）: 横軸＝日付（曜日付き）、縦軸＝メンバー。セル＝その日のスロット時間帯＋ポジション。必要人数未達スロットは赤系記号（✕）と「欠員N名」表記で視覚的に警告
- 本文（`layout=personal`）: 対象者氏名・期間内の全スロット時系列（日付・曜日・開始-終了時刻・ポジション・備考）、合計勤務時間
- 注意書き: 管理者が事前に `schedule.note` または PDF 出力時オプションで設定したカスタム文言（未設定時は既定文「本シフトは YYYY-MM-DD 時点の確定版。変更がある場合は追って連絡します」）
- 管理者署名欄（`layout=team` 時のみ表示、固定 1 行分の罫線 + 「管理者署名」ラベル。押印/サインを想定。v2.2 MVP ではクエリパラメータで ON/OFF せず、team レイアウト時のみ常時表示する既定運用。個人レイアウトでは表示しない）
- フッター: 生成日時 / 生成者氏名 / `{pageN} / {pageTotal}`
- **絶対に含めない情報（個人情報配慮）**: 変更依頼 `shift_change_requests.reason`・`admin_note`・自動割当スコア・勤務制約の個別上限値・時給（`shift_hourly_rates`）・希望 `note`・電話番号/住所等。PDF に含めるのは氏名とシフト情報のみ（§6 セキュリティ参照）

**リクエスト例**
```
GET /api/v1/shifts/schedules/42/pdf?layout=team HTTP/1.1
Authorization: Bearer ...
Accept-Language: ja

GET /api/v1/shifts/schedules/42/pdf?layout=personal&member_id=77 HTTP/1.1
Authorization: Bearer ...
```

**関連**
- **ApplicationEvent**: 発行しない（読取系 API、副作用最小）
- **監査ログ**: `SHIFT_PDF_EXPORTED`（§6 参照）
- **レートリミット**: 1ユーザー1分10件（§6 参照）
- **権限ゲート**: `AccessControlService.checkAdminOrAbove(userId, teamId, "TEAM")`（team レイアウト時）/ `AccessControlService.checkTeamMember(userId, teamId)`（personal レイアウト時・本人チェックは Service 層で追加実施）
- **ウォーターマーク挙動**: `PUBLISHED` 以外は赤系の「内部確認用 / CONFIRMATION ONLY」文字列を各ページ中央に 45° 回転・半透明で描画。**Flying Saucer は `transform` 未サポート**のため、F12.1 §7.2 方式 A（OpenPDF 後処理で `PdfContentByte` によりオーバーレイテキスト）か、方式 B（事前に回転済 PNG を `<img>` で配置）を採用。本設計は方式 A を既定とする（画像リソースを増やさない）。なお MEMBER は常に 403 なのでメンバー個人 PDF にウォーターマーク版は発生しない

---


---

*前: [01_db_design.md](01_db_design.md) | 次: [03_business_logic.md](03_business_logic.md)*
