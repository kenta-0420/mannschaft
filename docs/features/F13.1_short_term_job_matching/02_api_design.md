## 6. API設計

### 6.1 エンドポイント一覧

| メソッド | パス | 認証 | 権限 | 説明 |
|---------|-----|------|------|------|
| GET | `/api/v1/jobs` | 必要 | Worker候補 | 求人一覧（visibility フィルター後） |
| GET | `/api/v1/jobs/{id}` | 必要 | Worker候補 | 求人詳細 |
| POST | `/api/v1/jobs` | 必要 | ADMIN/DEPUTY(MANAGE_JOBS) | 求人作成（DRAFT） |
| PATCH | `/api/v1/jobs/{id}` | 必要 | Requester本人 | 求人更新（応募前に限定変更、応募後は一部項目のみ） |
| POST | `/api/v1/jobs/{id}/publish` | 必要 | Requester本人 | DRAFT → OPEN |
| POST | `/api/v1/jobs/{id}/close` | 必要 | Requester本人 | 募集終了 |
| DELETE | `/api/v1/jobs/{id}` | 必要 | Requester本人 | 論理削除 |
| POST | `/api/v1/jobs/fee-preview` | 必要 | 全認証ユーザー | 手数料試算（`{base_reward}` → 内訳） |
| POST | `/api/v1/jobs/{id}/applications` | 必要 | MEMBER/SUPPORTER | 応募 |
| DELETE | `/api/v1/jobs/{id}/applications/me` | 必要 | 本人 | 応募取消 |
| GET | `/api/v1/jobs/{id}/applications` | 必要 | Requester本人 | 応募者一覧 |
| POST | `/api/v1/jobs/{id}/applications/{appId}/accept` | 必要 | Requester本人 | 採用確定 → 契約成立・PaymentIntent作成 |
| POST | `/api/v1/jobs/{id}/applications/{appId}/reject` | 必要 | Requester本人 | 不採用 |
| GET | `/api/v1/job-contracts` | 必要 | 本人 | 自分の契約一覧（Requester/Worker両面） |
| GET | `/api/v1/job-contracts/{id}` | 必要 | 当事者 | 契約詳細 |
| POST | `/api/v1/job-contracts/{id}/start` | 必要 | Worker | 業務開始マーク（互換用、通常は QR チェックインで代替）|
| POST | `/api/v1/job-contracts/{id}/qr-tokens` | 必要 | Requester | **QR トークン発行**（IN/OUT 種別指定、TTL 60秒デフォルト、自動ローテーション）|
| GET | `/api/v1/job-contracts/{id}/qr-tokens/current` | 必要 | Requester | 現在有効な QR トークン取得（自動再発行含む、SSE or polling）|
| POST | `/api/v1/jobs/check-ins` | 必要 | Worker | **チェックイン／アウト登録**（QR スキャン結果 or 手動コード送信。オフラインキュー経由も対応）|
| POST | `/api/v1/job-contracts/{id}/report-completion` | 必要 | Worker | 完了報告（前提: CHECKED_OUT 済み）|
| POST | `/api/v1/job-contracts/{id}/approve` | 必要 | Requester | 承認 → Capture |
| POST | `/api/v1/job-contracts/{id}/reject-completion` | 必要 | Requester | 差し戻し |
| POST | `/api/v1/job-contracts/{id}/cancel` | 必要 | 当事者 | キャンセル |
| POST | `/api/v1/job-contracts/{id}/reviews` | 必要 | Requester ADMIN/DEPUTY(MANAGE_JOBS) | **内部評価メモ記入**（公開なし、チーム内限定）|
| GET | `/api/v1/job-contracts/{id}/reviews` | 必要 | 同一チーム ADMIN / Reviewee 本人 | 内部評価メモ取得（スコープ外は 403）|
| GET | `/api/v1/teams/{teamId}/jobs/history` | 必要 | 当該チーム ADMIN/DEPUTY(MANAGE_JOBS) | **履歴ダッシュボード**（期間・Worker・ステータス・金額レンジフィルタ、CSV出力対応）|
| GET | `/api/v1/teams/{teamId}/jobs/history/export.csv` | 必要 | 同上 | CSV エクスポート（Content-Disposition: attachment）|
| GET | `/api/v1/teams/{teamId}/workers/{workerId}/history` | 必要 | 当該チーム ADMIN/DEPUTY(MANAGE_JOBS) or 募集投稿者本人 | **再応募時の過去履歴パネル用**（契約回数・総業務時間・総支払額・前回評価メモ・直近3件）|
| GET | `/api/v1/me/jobs/history` | 必要 | Worker 本人 | **Worker マイページ履歴**（自分の過去契約一覧、他人には見えない）|
| POST | `/api/v1/job-contracts/{id}/disputes` | 必要 | 当事者 | 紛争オープン |
| POST | `/api/v1/job-disputes/{id}/resolve` | 必要 | ADMIN/SYSTEM_ADMIN | 紛争仲裁 |
| POST | `/api/v1/stripe/connect/onboarding-link` | 必要 | 本人 | Express onboarding account_link 取得 |
| GET | `/api/v1/stripe/connect/me` | 必要 | 本人 | 自分の Connect 口座状態 |
| POST | `/api/v1/stripe/connect/login-link` | 必要 | 本人 | Express ダッシュボードログインリンク取得 |
| POST | `/api/v1/webhooks/stripe/connect` | 署名検証 | — | Stripe Connect 専用 Webhook 受信 |
| POST | `/api/v1/webhooks/stripe/platform` | 署名検証 | — | Stripe Platform（Destination Charges）Webhook 受信 |
| GET | `/api/v1/users/me/job-notification-preferences` | 必要 | 本人 | 通知設定取得 |
| PUT | `/api/v1/users/me/job-notification-preferences` | 必要 | 本人 | 通知設定更新 |
| **=== 第三版新規 ===** | | | | |
| POST | `/api/v1/teams/{teamId}/jobbers/invite` | 必要 | ADMIN/DEPUTY(MANAGE_JOBS) | **JOBBER 招待発行**（72 時間 TTL トークン生成） |
| GET | `/api/v1/teams/{teamId}/jobbers/invitations` | 必要 | ADMIN/DEPUTY | 招待一覧 |
| DELETE | `/api/v1/teams/{teamId}/jobbers/invitations/{invitationId}` | 必要 | 発行者 | 招待取消（未受諾のもののみ） |
| POST | `/api/v1/jobber-invitations/{token}/accept` | 必要 | 招待対象本人 | **招待受諾**（`team_members` に JOBBER として INSERT） |
| POST | `/api/v1/jobber-invitations/{token}/decline` | 必要 | 招待対象本人 | 招待辞退 |
| GET | `/api/v1/teams/{teamId}/jobbers` | 必要 | ADMIN/DEPUTY/同チーム JOBBER 本人 | 当該チームの JOBBER 一覧 |
| DELETE | `/api/v1/teams/{teamId}/jobbers/{userId}` | 必要 | ADMIN/DEPUTY or JOBBER 本人 | JOBBER 離脱 / 解除 |
| GET | `/api/v1/me/jobber-profile` | 必要 | 本人 | 自分の Jobber プロフィール取得 |
| PUT | `/api/v1/me/jobber-profile` | 必要 | 本人 | Jobber プロフィール更新（総合掲示板 opt-in 含む） |
| GET | `/api/v1/jobs/public-board` | 必要 | `jobber_profiles.is_public_board_opt_in=TRUE` | **Jobber 総合掲示板**（絞り込み + ページング） |
| POST | `/api/v1/job-contracts/{id}/time-confirmations` | 必要 | ADMIN/DEPUTY(MANAGE_JOBS) | **運営側業務時間確定** |
| GET | `/api/v1/job-contracts/{id}/time-confirmations` | 必要 | 当事者 + ADMIN | 時間確定レコード一覧（version 順） |
| POST | `/api/v1/job-contracts/{id}/time-confirmations/{confId}/approve` | 必要 | Worker 本人 | **Worker 承認** |
| POST | `/api/v1/job-contracts/{id}/time-confirmations/{confId}/dispute` | 必要 | Worker 本人 | **Worker 異議提起** |
| POST | `/api/v1/job-payments/{id}/early-release` | 必要 | 当事者（Requester / Worker 両方の押下が必要） | **エスクロー早期 release 押下** |
| POST | `/api/v1/job-payments/{id}/dispute` | 必要 | 当事者 | **エスクロー期間中の異議申立** |
| GET | `/api/v1/job-payments/{id}/escrow-status` | 必要 | 当事者 | エスクロー状態 + 残り時間取得 |
| POST | `/api/v1/todos/{id}/convert-to-job-posting` | 必要 | TODO 作成者 + ADMIN/DEPUTY(MANAGE_JOBS) | **TODO → 求人変換** |
| PATCH | `/api/v1/todos/{id}/jobber-flag` | 必要 | TODO 作成者 + ADMIN/DEPUTY(MANAGE_JOBS) | フラグ ON/OFF（OFF は `is_jobber_recruiting=FALSE` + 求人未リンク時のみ） |

### 6.2 主要 DTO

#### `POST /api/v1/jobs/fee-preview`

**リクエスト**
```json
{ "base_reward_jpy": 5000 }
```

**レスポンス（200 OK）**
```json
{
  "data": {
    "base_reward_jpy": 5000,
    "requester_fee_jpy": 600,
    "requester_fee_tax_jpy": 60,
    "requester_total_payment_excl_tax_jpy": 5600,
    "requester_total_payment_incl_tax_jpy": 5660,
    "worker_fee_jpy": 200,
    "worker_receipt_jpy": 4800,
    "platform_gross_margin_excl_tax_jpy": 800,
    "platform_consumption_tax_hold_jpy": 60,
    "estimated_stripe_fee_jpy": 204,
    "platform_net_margin_jpy": 596
  }
}
```

> **備考**:
> - `platform_net_margin_jpy` = `platform_gross_margin_excl_tax_jpy` - `estimated_stripe_fee_jpy`（税別粗利からStripe手数料を差し引いた純利益）
> - `platform_consumption_tax_hold_jpy` は預かり消費税で別途納税義務あり（運用ダッシュボードで分離表示）
> - `estimated_stripe_fee_jpy` は税込総額に対する 3.6% の概算値。実確定は `charge.updated` Webhook 受信後に更新

#### `POST /api/v1/jobs/{id}/applications/{appId}/accept`

**前提条件**
- 対応 Worker の `stripe_connect_accounts.status = 'READY'`
- `job_postings.status = 'OPEN'`
- 定員未充足

**トランザクション**
1. `SELECT ... FOR UPDATE` で `job_postings` 行ロック
2. `MySQL GET_LOCK("job_posting_<id>", 10)` でさらに排他
3. `job_applications.status = 'ACCEPTED'` に更新
4. `job_contracts` 新規作成（料金スナップショット）
5. Stripe PaymentIntent 作成（`capture_method=manual`、`application_fee_amount`、`transfer_data.destination`）
6. `job_payments` レコード作成（`status=REQUIRES_PAYMENT_METHOD`）
7. チャットルーム自動作成（F04.2）
8. 通知送信（`JOB_MATCHED`、強制配信）
9. 定員充足なら `job_postings.status = 'CLOSED'`

**レスポンス（201 Created）**
```json
{
  "data": {
    "contract_id": 123,
    "payment_intent_client_secret": "pi_xxx_secret_xxx",
    "chat_room_id": 456
  }
}
```

**エラー**
| ステータス | 条件 |
|-----------|------|
| 400 | Worker の Connect 未準備 / 求人が OPEN でない / 定員充足 |
| 403 | Requester 本人でない |
| 409 | 既に別応募者が採用確定（楽観的ロック） |
| 503 | Stripe API 一時障害（Circuit Breaker） |

#### `POST /api/v1/job-contracts/{id}/qr-tokens`

**リクエスト**
```json
{ "type": "IN", "ttl_seconds": 60 }
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsImtpZCI6InYxIn0.eyJjaWQiOjEyMywid2lkIjo0NTYsInR5cCI6IklOIiwibm9uY2UiOiJiNGE3YmU4Yi0...","
    "short_code": "384172",
    "type": "IN",
    "expires_at": "2026-04-21T12:01:00Z",
    "nonce": "b4a7be8b-...-..."
  }
}
```

> **備考**: `token` は QR に埋め込まれる署名付き JWT。`short_code` は手動入力用 6 桁数字（TTL 連動）。Requester 画面は `expires_at` - 5 秒前に自動で次のトークンを取得し QR を更新。

#### `POST /api/v1/jobs/check-ins`

**リクエスト（オンライン・スキャン成立）**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "scanned_at": "2026-04-21T12:00:45.123Z",
  "geolocation": {
    "latitude": 35.681236,
    "longitude": 139.767125,
    "accuracy_m": 12.5
  },
  "offline_submitted": false,
  "manual_code_fallback": false
}
```

**リクエスト（手動コード入力フォールバック）**
```json
{
  "short_code": "384172",
  "scanned_at": "2026-04-21T12:00:45.123Z",
  "manual_code_fallback": true
}
```

**サーバー処理**
1. `token` 署名検証（HMAC-SHA256、`kid` で鍵選択）
2. `nonce` を `job_qr_tokens` から SELECT FOR UPDATE、`used_at IS NULL AND expires_at > NOW()` を確認
   - オフライン送信時は `scanned_at` が `issued_at` 〜 `expires_at` 範囲内なら許可
3. `job_qr_tokens.used_at = NOW()` に更新（再利用防止）
4. 契約の Worker 本人（`worker_user_id` 一致）であることを検証
5. `job_check_ins` INSERT
6. `job_contracts.status` を `MATCHED → CHECKED_IN`（IN の場合）または `IN_PROGRESS → CHECKED_OUT`（OUT の場合）に遷移
7. OUT の場合は `work_duration_minutes = (checked_out_at - checked_in_at) / 60` を計算
8. Geolocation が業務場所から 500m 以上乖離していれば `geo_anomaly=TRUE` + `JOB_GEO_ANOMALY` 通知
9. Requester へ `JOB_CHECKED_IN` / `JOB_CHECKED_OUT` 通知

**レスポンス（201 Created）**
```json
{
  "data": {
    "check_in_id": 789,
    "contract_id": 123,
    "type": "IN",
    "new_status": "CHECKED_IN",
    "work_duration_minutes": null,
    "geo_anomaly": false
  }
}
```

**エラー**
| ステータス | 条件 |
|-----------|------|
| 400 | トークン期限切れ / nonce 既使用 / 既に同種チェックイン存在 |
| 401 | 署名検証失敗 |
| 403 | 契約の Worker 本人でない / 同時刻の別契約チェックイン衝突 |
| 409 | OUT 時に IN が未登録 |
| 422 | Geolocation 同意拒否時は警告ログに記録（拒否は成立させるがアラート） |

#### `GET /api/v1/teams/{teamId}/jobs/history`

**クエリパラメータ**
- `from` / `to`（ISO-8601、業務日 DESC フィルタ）
- `worker_user_id` (optional)
- `status` (optional, comma 区切り)
- `amount_min` / `amount_max` (JPY)
- `cursor` / `limit`（最大 100、デフォルト 50）

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "contract_id": 123,
      "posting_title": "大会受付係",
      "work_date": "2026-04-18",
      "worker": { "user_id": 456, "display_name": "山田花子", "avatar_url": "..." },
      "work_duration_minutes": 240,
      "total_paid_jpy": 5660,
      "status": "COMPLETED",
      "has_internal_review": true
    }
  ],
  "next_cursor": "eyJpZCI6MTAwfQ"
}
```

#### `GET /api/v1/teams/{teamId}/workers/{workerId}/history`

**レスポンス（200 OK）** — 再応募時パネル用
```json
{
  "data": {
    "team_id": 10,
    "worker_user_id": 456,
    "total_contracts": 7,
    "total_work_minutes": 1680,
    "total_paid_jpy": 42350,
    "last_contract_at": "2026-04-18T09:00:00Z",
    "last_review_comment_preview": "段取りが良く、再度お願いしたい",
    "recent_contracts": [
      { "contract_id": 123, "title": "大会受付係", "work_date": "2026-04-18", "status": "COMPLETED" },
      { "contract_id": 118, "title": "駐車場係",   "work_date": "2026-03-30", "status": "COMPLETED" },
      { "contract_id": 112, "title": "設営手伝い", "work_date": "2026-03-15", "status": "COMPLETED" }
    ]
  }
}
```

> **権限**: リクエスト元が当該チーム ADMIN / DEPUTY(MANAGE_JOBS) or 当該 job_posting の `created_by` 本人でない場合は 403。

#### `GET /api/v1/me/jobs/history`

**レスポンス**: 自分視点の契約一覧。他人の情報は含まない。チーム名は `team_public_name` のみ返し、同一チーム内他 Worker の存在は隠す。

#### `POST /api/v1/teams/{teamId}/jobbers/invite`（第三版新規）

**リクエスト（既存ユーザー招待）**
```json
{
  "invitee_user_id": 789,
  "message": "大会シーズンにピンポイントで撮影をお願いしたいです",
  "proposed_hourly_wage_jpy": 1500,
  "proposed_categories": ["PHOTO"]
}
```

**リクエスト（メール招待）**
```json
{
  "invitee_email": "photographer@example.com",
  "message": "...",
  "proposed_hourly_wage_jpy": 1500
}
```

**レスポンス（201 Created）**
```json
{
  "data": {
    "invitation_id": 42,
    "team_id": 10,
    "status": "PENDING",
    "expires_at": "2026-04-24T10:00:00Z",
    "invitation_url": "https://app.mannschaft.example/jobber-invitations/accept?t=..."
  }
}
```

**エラー**
| ステータス | 条件 |
|-----------|------|
| 400 | invitee_user_id/email 両方 NULL / 既に JOBBER 登録済み / 自分への招待 |
| 403 | 権限なし（MANAGE_JOBS 欠落） |
| 409 | 同一 invitee に PENDING 招待が存在 |

#### `POST /api/v1/jobber-invitations/{token}/accept`

**サーバー処理**
1. token_hash で `jobber_team_invitations` を検索、PENDING + 期限内を確認
2. 認証ユーザーが `invitee_user_id`（or 新規作成の場合は `invitee_email` 紐付け）と一致
3. `memberships (user_id, scope_type='TEAM', scope_id=teamId, role_kind='MEMBER', joined_at=NOW())` を INSERT（UPSERT。既にアクティブな memberships が存在する場合は 409）
4. `jobber_profiles` がなければ作成
5. 招待ステータスを `ACCEPTED` に更新
6. 監査ログ `JOB_JOBBER_INVITATION_ACCEPTED`
7. 招待者に通知 `JOB_JOBBER_ACCEPTED`

**レスポンス（200 OK）**
```json
{ "data": { "team_id": 10, "role": "JOBBER", "joined_at": "..." } }
```

#### `GET /api/v1/jobs/public-board`（第三版新規）

**クエリパラメータ**
- `lat` / `lng` / `distance_km`（位置検索）
- `reward_min` / `reward_max`
- `skills`（カンマ区切り）
- `from` / `to`
- `category`（カンマ区切り）
- `time_confirmation_method` (`QR_CHECKIN` | `ORG_CONFIRM`)
- `location_type` (`ONSITE` | `ONLINE` | `HYBRID`)
- `cursor` / `limit`（最大 50、デフォルト 20）

**レスポンス（200 OK）**
```json
{
  "data": [
    {
      "job_posting_id": 9001,
      "title": "大会当日の受付係（4 時間）",
      "team_public_name": "Mannschaft FC",
      "team_id": 12,
      "work_start_at": "2026-05-10T09:00:00Z",
      "work_end_at": "2026-05-10T13:00:00Z",
      "location_type": "ONSITE",
      "location_prefecture": "東京都",
      "distance_km_from_user": 8.4,
      "base_reward_jpy": 5000,
      "worker_receipt_jpy": 4800,
      "capacity": 2,
      "remaining_slots": 2,
      "time_confirmation_method": "QR_CHECKIN",
      "auto_join_jobber_on_apply": true,
      "application_deadline_at": "2026-05-08T23:59:59Z"
    }
  ],
  "next_cursor": "..."
}
```

**権限**
- 閲覧には `jobber_profiles.is_public_board_opt_in = TRUE` が必要。未 opt-in の場合 403 + 「Jobber 総合掲示板を使うには Jobber プロフィールを公開設定にしてください」誘導

#### `POST /api/v1/job-contracts/{id}/time-confirmations`（第三版新規）

**リクエスト**
```json
{
  "work_start_at": "2026-05-10T09:05:00Z",
  "work_end_at": "2026-05-10T13:10:00Z",
  "break_minutes": 30,
  "note": "当日15分遅延、5分残業で計算"
}
```

**サーバー処理**
1. 契約が `time_confirmation_method = 'ORG_CONFIRM'` であることを検証（`QR_CHECKIN` なら 400）
2. `job_contracts.status IN ('MATCHED','CHECKED_IN','IN_PROGRESS')` を検証（CHECKED_OUT 以降の上書きは 409）
3. `calculated_work_minutes = (work_end_at - work_start_at) - break_minutes`
4. 既存の PENDING `time_confirmations` があれば `REVOKED` に更新（version + 1）
5. 新規 INSERT、`status = PENDING_WORKER_APPROVAL`
6. Worker に `JOB_TIME_CONFIRMATION_REQUESTED` 通知（強制配信）
7. 監査ログ `JOB_TIME_CONFIRMATION_CREATED`

**レスポンス（201 Created）**
```json
{
  "data": {
    "time_confirmation_id": 100,
    "version": 1,
    "status": "PENDING_WORKER_APPROVAL",
    "calculated_work_minutes": 215,
    "auto_approval_at": "2026-05-13T13:15:00Z"
  }
}
```

#### `POST /api/v1/job-contracts/{id}/time-confirmations/{confId}/approve`

**サーバー処理**
1. 認証ユーザー = 契約の `worker_user_id` を検証
2. `time_confirmations.status = 'PENDING_WORKER_APPROVAL'` を検証
3. `status = 'APPROVED_BY_WORKER'`, `worker_approved_at = NOW()` 更新
4. `job_contracts.status = 'TIME_CONFIRMED'`, `work_duration_minutes = calculated_work_minutes` 更新
5. Requester に `JOB_TIME_CONFIRMED_BY_WORKER` 通知

**レスポンス（200 OK）**
```json
{ "data": { "status": "APPROVED_BY_WORKER", "contract_status": "TIME_CONFIRMED" } }
```

#### `POST /api/v1/job-payments/{id}/early-release`（第三版新規）

**リクエスト**
```json
{}
```

**サーバー処理**
1. 認証ユーザー = 契約の Requester / Worker いずれかを判定
2. `escrow_status = 'HOLDING'` を検証（NOT_STARTED/RELEASED/DISPUTED は 409）
3. 押下者に応じて `early_release_requester_approved_at` or `early_release_worker_approved_at` を `NOW()` で更新
4. 両方 NOT NULL になった時点で Stripe PaymentIntent capture 実行
5. `escrow_status = 'RELEASED'`, `captured_at = NOW()`, `status = 'SUCCEEDED'` 更新
6. 両者へ `JOB_EARLY_RELEASE_COMPLETED` 通知

**レスポンス（200 OK）**
```json
{
  "data": {
    "escrow_status": "HOLDING",   // まだ片方のみ承認の場合
    "requester_approved": true,
    "worker_approved": false,
    "waiting_for": "WORKER"
  }
}
```

両者承認時:
```json
{
  "data": {
    "escrow_status": "RELEASED",
    "captured_at": "2026-05-11T10:00:00Z"
  }
}
```

#### `POST /api/v1/job-payments/{id}/dispute`

**リクエスト**
```json
{ "reason": "業務内容が大きく異なっていた。掃除のはずが荷物の搬入だった" }
```

**サーバー処理**
1. `escrow_status IN ('HOLDING','NOT_STARTED')` を検証
2. `dispute_window_ends_at >= NOW()` を検証（期限切れは 409）
3. `escrow_status = 'DISPUTED'` 更新
4. `job_dispute_cases` に `dispute_source = 'ESCROW_WINDOW'` + `escrow_payment_id = {id}` で INSERT
5. `job_contracts.status = 'DISPUTED'`
6. ADMIN / SYSTEM_ADMIN / 相手方に通知

#### `POST /api/v1/todos/{id}/convert-to-job-posting`（第三版新規）

**リクエスト**
```json
{
  "work_start_at": "2026-05-10T09:00:00Z",
  "work_end_at": "2026-05-10T13:00:00Z",
  "base_reward_jpy": 5000,
  "capacity": 2,
  "visibility_scope": "JOBBER_INTERNAL",
  "time_confirmation_method": "QR_CHECKIN",
  "category": "SETUP",
  "location_type": "ONSITE",
  "location_address": "東京都渋谷区...",
  "auto_join_jobber_on_apply": false
}
```

**サーバー処理**
1. 認証ユーザー = TODO の `created_by` + ADMIN/DEPUTY(`MANAGE_JOBS`) を検証
2. `todos.is_jobber_recruiting = FALSE` かつ `todos.job_posting_id IS NULL` を検証（409 で重複防止）
3. `todos.title` / `description` / `due_date` を自動補完しつつ、リクエストで上書き可能
4. `job_postings` INSERT（DRAFT or OPEN を選択可）
5. `todos.job_posting_id = <new id>`, `todos.is_jobber_recruiting = TRUE` 更新
6. 監査ログ `JOB_TODO_CONVERTED_TO_POSTING`
7. visibility_scope に応じて通知配信

**レスポンス（201 Created）**
```json
{
  "data": {
    "job_posting_id": 9001,
    "todo_id": 5678,
    "visibility_scope": "JOBBER_INTERNAL",
    "status": "DRAFT"
  }
}
```

#### `PATCH /api/v1/todos/{id}/jobber-flag`

**リクエスト**
```json
{ "is_jobber_recruiting": true }
```

- ON に切り替えた場合、フロントエンド側で上記 convert-to-job-posting モーダルが開く（実 API 呼び出しは別段階）。
- 直接 OFF → `is_jobber_recruiting=FALSE` かつ `job_posting_id IS NULL` になる（既に求人作成済みの場合は 409: 先に求人削除が必要）

---

