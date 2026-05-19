# F03.5 シフト管理 — §6 セキュリティ / §7 Flyway / §8 未解決事項 / §8.5 運用 / §9 変更履歴

> このファイルは [F03.5_shift/README.md](README.md) の一部です。

## 6. セキュリティ考慮事項

- **認可チェック**: 全 API エンドポイントでチームメンバーシップとロールを検証
- **希望データのプライバシー**: メンバーの希望詳細（preference / note）は ADMIN / DEPUTY_ADMIN のみ閲覧可能。一般メンバーは自分の希望のみ参照可
- **時給データのプライバシー**: メンバーの時給（`shift_hourly_rates`）は **本人 + ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）のみ** 閲覧可能。他メンバーの時給は一切表示しない。給与概算も本人の `GET /shifts/my` でのみ返却
- **監査ログ**: 以下のイベントを監査ログに記録（F10.3 連携）
  - `SHIFT_SCHEDULE_DELETED`: シフトスケジュールの削除
  - `SHIFT_SCHEDULE_STATUS_CHANGED`: ステータス遷移（遷移元・遷移先を記録）
  - `SHIFT_PUBLISHED`: シフト確定・公開（published_by を記録）
  - `SHIFT_ASSIGNMENT_CHANGED`: 公開後の割り当て変更（変更前後の user_id を記録）
  - `SHIFT_SWAP_APPROVED`: シフト交代承認（requester_id, accepter_id, slot_id, resolved_by を記録）
  - **【v2】** `SHIFT_AUTO_ASSIGN_EXECUTED`: 自動割当実行（`run_id`, `strategy`, `triggered_by`, `parameters` を記録）
  - **【v2】** `SHIFT_AUTO_ASSIGN_CONFIRMED`: 自動割当提案の確定（`run_id`, `confirmed_count`, `triggered_by` を記録）
  - **【v2】** `SHIFT_AUTO_ASSIGN_REVOKED`: 自動割当提案の破棄（`run_id`, `revoked_count`, `triggered_by` を記録）
  - **【v2】** `SHIFT_WORK_CONSTRAINT_CHANGED`: チームデフォルト/個別制約の変更（変更前後の値、`target_user_id`, `triggered_by` を記録）
  - **【v2.1】** `SHIFT_CHANGE_REQUEST_CREATED`: 確定前変更依頼の作成（`requester_user_id`, `schedule_id`, `slot_id`, `request_type` を記録）
  - **【v2.1】** `SHIFT_CHANGE_REQUEST_REVIEWED`: 管理者による審査（`reviewed_by`, `action ∈ {ACCEPTED, REJECTED}`, `admin_note` を記録）
  - **【v2.1】** `SHIFT_CHANGE_REQUEST_WITHDRAWN`: 依頼者本人による取下（`requester_user_id` を記録）
  - **【v2.1】** `SHIFT_OPEN_CALL_CREATED`: オープンコール作成（`requester_id`, `slot_id` を記録）
  - **【v2.1】** `SHIFT_OPEN_CALL_CLAIMED`: 手挙げ成功（`claimed_by`, `swap_request_id` を記録）
  - **【v2.1】** `SHIFT_OPEN_CALL_CLAIMER_SELECTED`: 候補確定（`selected_by`, `accepter_user_id` を記録。`selected_by != claimed_by` の場合は管理者裁量による差し替えとして識別可能）
  - **【v2.1】** `SHIFT_SCHEDULE_VISUAL_REVIEW_CONFIRMED`: 目視確認承認（`run_id`, `schedule_id`, `visual_review_confirmed_by`, `visual_review_note` を記録）
  - **【v2.2】** `SHIFT_PDF_EXPORTED`: シフト表PDF出力（`requester_id`, `schedule_id`, `team_id`, `layout ∈ {team, personal}`, `member_id`（personal時のみ）, `include_draft_watermark`, `locale`, `output_size_bytes`, `generated_at` を記録）。**誰が・どのスケジュールを・どのレイアウトで・いつ・対象メンバーは誰か** を永続記録。紙配布事故が発生した際のフォレンジック用途
- **レートリミット**: `Bucket4j` で以下の制限を適用（スパム・DoS 防止）
  - 希望提出 API（`POST /shifts/requests`）: 1分間10件
  - 一括スロット作成 API（`POST /slots/bulk`）: 1分間5件
  - 手動リマインド API（`POST /schedules/{id}/remind`）: 同一スケジュールに対して1時間に1回
  - 交代リクエスト作成 API（`POST /swap-requests`）: 1分間5件
  - **【v2】自動割当 API（`POST /schedules/{id}/auto-assign`）**: 同一スケジュールに対して1分間3件（重たい処理のため厳しめ）
  - **【v2】D&D 差分割当（`PATCH /slots/{id}/assignments`）**: 1ユーザー1分間60件（UI 操作ベースのため比較的緩め）
  - **【v2.1】変更依頼作成（`POST /shifts/change-requests`）**: 1ユーザー1分間10件 + 同一スケジュールに対する OPEN 状態の依頼は 5 件まで（`shift_change_requests` 制約として判定）
  - **【v2.1】オープンコール作成（`POST /shifts/swap-requests` with `is_open_call=true`）**: 同一ユーザーが当月に作成できる件数は 3 件まで（通知疲労防止）。上限超過時は 429。`shift_swap_requests` の month/year + requester_id 集計で判定
  - **【v2.1】オープンコール手挙げ（`POST /swap-requests/{id}/claim`）**: 1ユーザー1分間30件（UI ボタンの連打・botting 防止）
  - **【v2.1】目視確認（`POST /assignment-runs/{runId}/confirm-visual-review`）**: 1ユーザー1分間10件（誤タップ対策、厳しい制限は不要）
  - **【v2.2】PDF出力（`GET /shifts/schedules/{id}/pdf`）**: 1ユーザー1分間10件（PDF 生成は CPU コストがあるため抑制。`include_draft_watermark=true` かどうかに関わらず同一バケット）。超過時 429 + `Retry-After: 60`。DoS 時の保険として **チームあたり1分間30件** の追加バケットも併用
- **assigned_user_ids の検証**: スロット更新時に配列内の全 user_id がチームの有効メンバーであることを検証（退会済みユーザーの ID が含まれていないことを確認）
- **ステータス遷移の保護**: 許可されていないステータス遷移はアプリ層で厳密にブロック（例: DRAFT → PUBLISHED の直接遷移は不可）
- **IDOR 防止**: `PUT /shifts/requests/{id}` と `DELETE /shifts/requests/{id}` はリクエストの `user_id` が認証ユーザーと一致することを必ず検証（他人の希望を編集・削除できないようにする）
- **スロット更新時のスケジュール所属検証**: `PUT /shifts/slots/{id}` / `PATCH /shifts/slots/{id}/assignments` でスロットが認証ユーザーのチームに属するスケジュールのものであることを検証（他チームのスロットを URL 直打ちで更新されることを防止）
- **【v2】自動割当 API のスコープ検証**: `POST /auto-assign` / `/auto-assign/confirm` / `/auto-assign` DELETE / `GET /assignment-runs/{runId}` は、対象スケジュール・run が認証ユーザーの管理対象チームに属することを必ず検証
- **【v2】希望の note（個人の休み理由）**: 他メンバーには絶対に露出させない。`GET /schedules/{id}/requests` は ADMIN / DEPUTY_ADMIN のみが叩ける。個人の希望は本人の `GET /shifts/my` + 管理者経路のみ
- **【v2】勤務制約の閲覧権限**: `GET /work-constraints` は非管理者の場合、自分の個別制約のみ返却（他メンバーの `max_monthly_hours` 等が学生かどうか推測できる情報になるため、一覧公開しない）。チームデフォルトは全員閲覧可能（透明性のため）
- **【v2】自動割当スコアの露出**: `GET /assignment-runs/{runId}` は ADMIN / DEPUTY_ADMIN のみ。個別メンバーのスコア（なぜ選ばれ/選ばれなかったか）が他メンバーに見えると人間関係トラブルになる
- **【v2】自動割当の監査完備**: 全実行に対して `SHIFT_AUTO_ASSIGN_EXECUTED` 監査ログ + `shift_assignment_runs` レコードを残す。誰がいつどのパラメータで実行したか追跡可能
- **【v2.1】変更依頼の IDOR 防止（重要）**:
  - `POST /shifts/change-requests` において、`SWAP_SELF / CANCEL_SELF / CHANGE_DATE / CHANGE_SLOT` の slot_id は「認証ユーザーが `assigned_user_ids` に含まれるスロット」または「未割当だが認証ユーザーに関係する日付」のみ許可
  - 他人の割当スロットを指定した場合は Service 層で **403 Forbidden**（404 ではなく 403 で意図的に「その操作は禁止」と明示、リソースの存在情報は隠さない方針に従い 404 で返すのも選択肢だが、本機能ではチームメンバー内の既知情報のため 403 で運用）
  - `target_user_id` / `target_slot_id` / `target_slot_date` は**あくまでヒント**。バリデーションでチームメンバー・同一スケジュール内であることを確認するのみで、他人の割当の「強制変更」手段として悪用できない設計（管理者の審査が必須なワンクッション）
- **【v2.1】オープンコール手挙げのレース条件対策**:
  - 楽観ロック（@Version）+ `SELECT ... FOR UPDATE` の二重防御で「先着1名」セマンティクスを保証
  - `claimed_by` 列は UNIQUE ではなく `status` 列の整合性で保護（`OPEN_CALL` 状態 1 つに対して claim は 1 回）。2人目は `status='CLAIMED'` になった時点で `status = 'OPEN_CALL'` の条件で UPDATE しても 0 行更新 → アプリで 409
  - 本番デプロイ前の並列テスト必須（k6 で 100 並列 claim → 1 件成功・99 件 409 を検証）
- **【v2.1】オープンコールの通知疲労・悪用防止**:
  - 月次上限 3 件（§5 §6 で既述）
  - 通知オプトアウト: `receive_shift_open_call_broadcast` フラグを `user_notification_preferences` に追加し、OFF の場合は配信対象から除外
  - チーム管理者は「過度なオープンコールを使うユーザー」の件数をダッシュボードで確認可能（将来拡張）
- **【v2.1】変更依頼の個人理由の露出制御**:
  - `shift_change_requests.reason` と `admin_note` は**依頼者本人・管理者のみ**が閲覧可能。他メンバーは一切閲覧不可（個人的な事情が書かれる前提のため）
  - `GET /shifts/change-requests` は MEMBER の場合、サーバ側で強制的に `requester_user_id = 認証ユーザーID` に絞り込む。URL のクエリパラメータで他人の ID を指定しても無視（あるいは 403）
- **【v2.1】目視確認の監査完備・抜け道なし**:
  - PATCH /publish で自動割当履歴がある場合、目視未確認なら必ず 409。サーバ側で強制判定するためフロントエンドの改竄では突破不可
  - 目視確認は run 単位で記録 → 自動割当を再実行すると新 run は未確認状態になり再度ゲートが発動。「1度目視確認した後に再実行して目視確認せず公開」という抜け道を構造的に封じる
  - 監査ログ `SHIFT_SCHEDULE_VISUAL_REVIEW_CONFIRMED` で誰がいつどのメモで確認したかを永続記録。公開後に不正が発覚した場合のフォレンジック可能
  - 公開後の `assigned_user_ids` 変更（PATCH /slots）も既存の `SHIFT_ASSIGNMENT_CHANGED` 監査ログで追跡可能
- **【v2.2】PDF 出力の認可（IDOR 防止）**:
  - `GET /shifts/schedules/{id}/pdf` は `schedule.team_id` に対するチームメンバーシップを Service 層で必ず検証。他チームのスケジュール ID を URL 直打ちされた場合は `404`（情報隠蔽）で返す
  - `layout=personal` + `member_id` 指定は「認証ユーザー = member_id」または ADMIN / DEPUTY_ADMIN のみ許可。MEMBER の他人指定は `403`（同一チーム内の情報とはいえ個人勤務表は本人所有物とみなす）
  - SUPPORTER は確定シフトを画面閲覧できるが PDF 化は不可（`403`）。紙/PDF は二次配布・紛失リスクが画面閲覧より大きいため権限を分離
  - URL 推測による総当たり対策: `schedule_id` は連番だが、レートリミット（1ユーザー1分10件）と監査ログにより異常アクセスを検知可能
- **【v2.2】PDF 内の個人情報露出制御（厳格）**:
  - PDF に含めてよい個人情報: **氏名・シフト割当情報のみ**（日付・時間帯・ポジション・備考）
  - PDF に**絶対に含めてはならない情報**: 変更依頼の `reason` / `admin_note`、自動割当スコア・重み、勤務制約の個別上限値（`max_monthly_hours` 等）、時給（`shift_hourly_rates`）、給与概算、希望の `note`（介護・受験等の個人事情）、電話番号、住所、メールアドレス
  - Service 層で PDF テンプレート変数を組み立てる段階で**ホワイトリスト方式**（必要なフィールドのみコピー）を採用。`Map.putAll(entity)` 等の全量コピーは禁止
  - レビューチェック項目: 新しいフィールドを PDF 変数に追加する際は必ず「個人情報配慮チェック」をコードレビューで確認（PR テンプレート化を推奨）
- **【v2.2】PDF 生成結果の二次配布リスク低減**:
  - `Cache-Control: private, no-store` で中間プロキシ・ブラウザキャッシュの残存を防ぐ（ブラウザ履歴 / 一時ファイルには残る可能性はある）
  - `X-Frame-Options: DENY` で他サイトからの PDF 埋め込みを抑止
  - 生成されたファイルはサーバ側でディスクに保存しない（オンメモリ `byte[]` → レスポンス直書き）。将来キャッシュ層を導入する場合は F12.1 §11 で別途議論
  - PDF パスワード保護・印刷禁止フラグは設定しない（F01.8 と同様、実用性とトレードオフ。代わりに配布運用ガイドライン §8.5 で補完）
- **【v2.2】ウォーターマーク版の漏洩対策**:
  - `include_draft_watermark=true` は ADMIN / DEPUTY_ADMIN のみ。MEMBER がクエリ改竄して指定しても Service 層で `403`
  - ウォーターマーク付き PDF は「DRAFT/COLLECTING/ADJUSTING 状態かつ ADMIN 以上」でのみ発行可能なので、一般メンバーへの流出経路は管理者が PDF を故意に共有した場合のみ
  - ウォーターマーク文字は全ページに描画（方式 A の OpenPDF 後処理）。1 ページだけ削除されて流用されるリスクを下げる
- **【v2.2】ファイル名の情報漏洩リスク**:
  - ファイル名にチーム名・メンバー氏名が含まれる（運用要件）。メール添付や共有フォルダ経由での漏洩時、ファイル名だけで機密性を判定される可能性に留意。運用ガイドライン（§8.5）でパスワード ZIP 推奨を明記
  - ASCII フォールバック名（F12.1 §4.3）は `shift_team.pdf` / `shift_personal.pdf` に固定し、日本語部分を漏らさない
- **【v2.2】PDF 監査ログの用途**:
  - `SHIFT_PDF_EXPORTED` は情報の「印刷・配布された事実」の追跡手段。紙配布の紛失事故が後日発覚した場合、誰がいつ何を PDF 化したかを逆引きできる
  - 監査ログ自体には PDF バイナリを保存しない（サイズ肥大化防止）。`output_size_bytes` のみ記録することで「異常に大きい PDF」の抽出を可能にする

---

## 7. Flywayマイグレーション

### v1（既存・適用済み）

```
V3.029__add_manage_shifts_permission.sql    -- MANAGE_SHIFTS パーミッション追加 + role_permissions シード
V3.030__create_shift_schedules.sql          -- シフトスケジュールテーブル作成（last_auto_transition_at, is_low_submission_alerted 含む）
V3.031__create_shift_positions.sql          -- ポジションマスターテーブル作成
V3.032__create_shift_slots.sql              -- シフトスロットテーブル作成（position_id FK → shift_positions）
V3.033__create_shift_requests.sql           -- シフト希望テーブル作成（preference 3段階: PREFERRED / AVAILABLE / UNAVAILABLE）
V3.034__create_shift_swap_requests.sql      -- シフト交代リクエストテーブル作成
V3.035__create_member_availability_defaults.sql  -- デフォルト可否プロファイルテーブル作成（preference 3段階）
V3.036__create_shift_hourly_rates.sql            -- 時給設定テーブル作成
```

> **注**: 実装時の Flyway 番号は `V3.070〜V3.076` として採番済（リポジトリの既存ファイル参照）。本設計書は論理的な連番で記述している

### v2（v2.0 時点で追加）

```
V3.077__migrate_shift_request_preference_v2.sql  -- preference 3段階→5段階データ移行（UNAVAILABLE → STRONG_REST）
V3.078__add_reminder_48h_to_shift_schedules.sql  -- is_reminder_sent_48h カラム追加
V3.079__create_shift_assignments.sql             -- 自動割当履歴テーブル作成
V3.080__create_shift_assignment_runs.sql         -- 自動割当実行ログテーブル作成
V3.081__create_member_work_constraints.sql       -- 任意勤務制約テーブル作成
V3.082__update_shift_request_preference_check_constraint.sql -- CHECK 制約を5値に更新（shift_requests / member_availability_defaults）
```

### v2.1（今回追補）

```
V3.083__create_shift_change_requests.sql                   -- 確定前の変更依頼テーブル（A-1）を作成
V3.084__add_open_call_columns_to_shift_swap_requests.sql   -- shift_swap_requests にオープンコール（A-3）用カラム追加
V3.085__add_visual_review_columns_to_shift_assignment_runs.sql  -- 目視確認カラムを追加
```

### v2.2（今回追補・シフト表PDF出力）

**Flyway マイグレーションは不要**。理由と方針:

- PDF 生成は完全に読取系 API（`GET /shifts/schedules/{id}/pdf`）であり、新規テーブル・カラムを要しない
- 生成した PDF バイナリはサーバ側に保存しない（オンメモリ生成 → レスポンス直送信）。F12.1 §11 の方針に準拠
- 監査ログは既存 `audit_logs` テーブルに `event_type='SHIFT_PDF_EXPORTED'` の行を追加するのみ。DDL 変更不要（`event_type` は VARCHAR で自由値追加可能）
- 監査ログの `metadata` JSON に `{ layout, member_id, include_draft_watermark, locale, output_size_bytes }` を格納
- **`shift_pdf_exports` 監査専用テーブルを新設しない方針の根拠**: ①PDF は毎回生成（キャッシュ・保管なし）のため `file_hash` / `download_count` 追跡は不要 ②既存の `audit_logs` で「誰が・いつ・どのスケジュールを」は十分追跡可能 ③将来的に「再ダウンロード追跡」「PDF バージョン管理」要件が出たら V3.086 以降で `shift_pdf_exports` 新設を検討（§9 未来拡張に明記）

**v2.2 で発生する非 Flyway 作業**
- `backend/src/main/resources/templates/pdf/shift-team-matrix.html` を新規追加（F12.1 のテンプレートディレクトリ配下）
- `backend/src/main/resources/templates/pdf/shift-personal-timeline.html` を新規追加
- `backend/src/main/resources/messages_{ja,en,zh,ko,es,de}.properties` に `shift.pdf.*` キーを追加（サーバサイド MessageSource）
- `frontend/app/locales/{ja,en,zh,ko,es,de}/common.json` にフロント側 PDF 操作ボタンの i18n キー追加
- `docs/features/F12.1_pdf_generation.md` §2 命名規約テーブル・§5 対象機能別実装仕様への F03.5 行追記（既存方式と同じ: F01.8 が §5 に節を追加した実績に倣う）

**マイグレーション上の注意点（v1・既存）**
- V3.029: `MANAGE_SHIFTS` を `permissions` テーブルに INSERT し、`role_permissions` に SYSTEM_ADMIN・ADMIN 用のシードを追加。DEPUTY_ADMIN は権限グループで明示設定。scope = TEAM
- `shift_schedules.team_id` は `teams` テーブルへの FK → Phase 1 で作成済み
- `shift_positions.team_id` は `teams` テーブルへの FK → Phase 1 で作成済み。V3.031 で作成
- `shift_slots.schedule_id` は `shift_schedules.id` への FK（ON DELETE CASCADE）→ V3.030 が先に実行されること
- `shift_slots.position_id` は `shift_positions.id` への FK（ON DELETE SET NULL）→ V3.031 が先に実行されること
- `shift_requests.schedule_id` は `shift_schedules.id` への FK（ON DELETE CASCADE）→ V3.030 が先に実行されること
- `shift_requests.slot_id` は `shift_slots.id` への FK（ON DELETE CASCADE）→ V3.032 が先に実行されること
- `shift_requests.user_id` は `users` テーブルへの FK → Phase 1 で作成済み
- `shift_swap_requests.slot_id` は `shift_slots.id` への FK（ON DELETE CASCADE）→ V3.032 が先に実行されること
- `member_availability_defaults.user_id` / `team_id` は Phase 1 で作成済み
- V3.022〜V3.028 は安否確認（F03.6）で使用済み。シフト管理は V3.029 から採番

**マイグレーション上の注意点（v2・今回追加）**
- **V3.077 の実行前に 必ず V3.078 以降が来る順序を厳守**。`is_reminder_sent_48h` カラムが無い状態で新コードをデプロイすると起動失敗するため、V3.078 を先行・V3.077 は同一トランザクション内でデータ変換のみ
- **V3.077（データ移行）**:
  ```sql
  -- 後方互換: 既存の UNAVAILABLE を STRONG_REST に一括変換
  UPDATE shift_requests
  SET preference = 'STRONG_REST'
  WHERE preference = 'UNAVAILABLE';

  UPDATE member_availability_defaults
  SET preference = 'STRONG_REST'
  WHERE preference = 'UNAVAILABLE';
  ```
  - **ロールバック手順**: もし問題が発生した場合、`UPDATE shift_requests SET preference = 'UNAVAILABLE' WHERE preference = 'STRONG_REST';` で戻せる（WEAK_REST / ABSOLUTE_REST は v2 でのみ発生するため混入しない想定。ただしロールバック時には先に v2 コードを停止する必要がある）
  - **in-flight データ**: マイグレーション実行中にユーザーが新規提出する可能性を避けるため、**メンテナンスウィンドウ中に実行**（推奨 1〜2分のダウンタイム）。オンライン実行する場合は、バックエンドのリリース前にマイグレーションのみを先行適用し、旧コード（UNAVAILABLE を送信する）は STRONG_REST として格納されるよう、DB 層の CHECK 制約を先に緩める（`UNAVAILABLE` も一時的に許可）→ コードデプロイ後に V3.082 で CHECK を5値に絞る二段階作戦も可
- **V3.078（is_reminder_sent_48h 追加）**: `ALTER TABLE shift_schedules ADD COLUMN is_reminder_sent_48h BOOLEAN NOT NULL DEFAULT FALSE;`。既存レコードは全て FALSE（まだ送ってない扱い）
- **V3.079〜V3.080**:
  - `shift_assignments.slot_id` は `shift_slots.id` への FK（ON DELETE CASCADE）
  - `shift_assignments.run_id` は `shift_assignment_runs.id` への FK（ON DELETE SET NULL）→ V3.080 が先に実行
  - `shift_assignments.user_id` / `assigned_by` は `users` への FK
  - UNIQUE KEY `(slot_id, user_id, status)` → MySQL では NULL 以外の値で機能するので status は NOT NULL
- **V3.081（member_work_constraints）**: チームデフォルト投入は**行わない**（オプトイン方式。既存チームに影響を広げないため）。初期データは別途シードスクリプトまたは管理画面から投入
- **V3.082（CHECK 制約更新）**:
  ```sql
  -- MySQL 8.0 は DROP/ADD で CHECK を置き換える
  ALTER TABLE shift_requests DROP CHECK chk_shift_requests_preference;
  ALTER TABLE shift_requests ADD CONSTRAINT chk_shift_requests_preference
      CHECK (preference IN ('PREFERRED','AVAILABLE','WEAK_REST','STRONG_REST','ABSOLUTE_REST'));
  ALTER TABLE member_availability_defaults DROP CHECK chk_member_availability_defaults_preference;
  ALTER TABLE member_availability_defaults ADD CONSTRAINT chk_member_availability_defaults_preference
      CHECK (preference IN ('PREFERRED','AVAILABLE','WEAK_REST','STRONG_REST','ABSOLUTE_REST'));
  ```
  - 既存 v1 マイグレーションで CHECK 制約が定義されていない場合は DROP を省略する Flyway 記述にする（`SET FOREIGN_KEY_CHECKS = 0` 等は使わない）
- **Feature Flag**: 自動割当・D&D UI・勤務制約機能は `feature.shift.v2.enabled`（application.yml のプロパティ）でトグル可能。初期デフォルトは `true`。本番ロールアウト時に問題があればフラグを false に戻せば旧画面（v1 3段階）で運用を継続できる（ただし DB の preference は既に変換済みのため、UI 側で WEAK_REST / ABSOLUTE_REST を `UNAVAILABLE` 扱いで表示する）

**マイグレーション上の注意点（v2.1・今回追補）**
- **V3.083（`shift_change_requests` 新規作成）**:
  - PK / FK / UNIQUE / INDEX / CHECK 制約は §3 のテーブル定義どおり
  - CHECK 制約: `CHECK (request_type IN ('SWAP_SELF','CHANGE_DATE','CHANGE_SLOT','CANCEL_SELF','OTHER'))` と `CHECK (status IN ('OPEN','ACCEPTED','REJECTED','WITHDRAWN'))`
  - FK: `schedule_id → shift_schedules(id) ON DELETE CASCADE`, `slot_id → shift_slots(id) ON DELETE CASCADE`, `requester_user_id → users(id) ON DELETE CASCADE`, `target_user_id → users(id) ON DELETE SET NULL`, `target_slot_id → shift_slots(id) ON DELETE SET NULL`, `reviewed_by → users(id) ON DELETE SET NULL`
  - 既存データは 0 件（新規テーブル）。ロールバックは `DROP TABLE shift_change_requests` で即時可能
- **V3.084（`shift_swap_requests` カラム追加）**:
  - `ALTER TABLE shift_swap_requests ADD COLUMN target_user_id BIGINT UNSIGNED NULL, ADD COLUMN is_open_call BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN claimed_by BIGINT UNSIGNED NULL, ADD COLUMN claimed_at DATETIME NULL, ADD COLUMN version BIGINT NOT NULL DEFAULT 0;`
  - FK 追加: `target_user_id → users(id) ON DELETE SET NULL`, `claimed_by → users(id) ON DELETE SET NULL`
  - インデックス追加: `idx_shift_swap_requests_open_call (is_open_call, status, slot_id)`, `idx_shift_swap_requests_target (target_user_id, status)`
  - CHECK 制約: `CHECK ((is_open_call = TRUE AND target_user_id IS NULL) OR (is_open_call = FALSE))` で排他を保証
  - **既存レコードの扱い**: v2.0 時点の既存 swap_requests は全て個別指名（`is_open_call = FALSE` デフォルトで埋まる）、`target_user_id` は NULL で問題なし。`version` は 0 でスタート
  - **status 拡張**: 既存のステータス列挙値（`PENDING / ACCEPTED / APPROVED / REJECTED / CANCELLED`）に加えて `OPEN_CALL / CLAIMED` が追加される。既存 CHECK 制約があれば DROP して再作成（`CHECK (status IN ('PENDING','OPEN_CALL','CLAIMED','ACCEPTED','APPROVED','REJECTED','CANCELLED'))`）
  - ロールバック: カラム DROP で v2.0 相当に戻せる（オープンコール機能未使用の間なら安全）
- **V3.085（`shift_assignment_runs` カラム追加）**:
  - `ALTER TABLE shift_assignment_runs ADD COLUMN visual_review_confirmed_by BIGINT UNSIGNED NULL, ADD COLUMN visual_review_confirmed_at DATETIME NULL, ADD COLUMN visual_review_note VARCHAR(500) NULL;`
  - FK: `visual_review_confirmed_by → users(id) ON DELETE SET NULL`
  - インデックス追加: `idx_shift_assignment_runs_visual_review (schedule_id, visual_review_confirmed_at)`
  - 既存レコード: 全て NULL（未確認扱い）。v2.1 デプロイ直後に既存の公開済みスケジュールを遡及して PUBLISHED できなくなる事態を避けるため、**ゲートは自動割当履歴ありのスケジュールが次回 PUBLISHED 遷移を試みる時点からのみ発動**（既存 PUBLISHED は影響なし）
  - ロールバック: カラム DROP で v2.0 相当に戻せる
- **V3.083→V3.084→V3.085 の順序**: 互いに依存しない独立した DDL だが、PR 単位で小刻みに適用する場合は番号順に適用すること（Flyway の履歴整合性維持）
- **Feature Flag（v2.1 追加）**: v2.1 の 3 機能（変更依頼・オープンコール・目視確認）は `feature.shift.v2_1.enabled` でトグル可能（初期デフォルト `true`）。false にすると API は全て 404 / 501 を返し、UI では該当ボタンが非表示。**ただし目視確認ゲートだけは `feature.shift.v2_1.visual_review_gate.enabled` で別トグル**（`true` 時のみ PATCH /publish の 409 VISUAL_REVIEW_REQUIRED が発動）。本番ロールアウトで問題が出たら段階的に解除可能な設計

---

## 8. 未解決事項

- [x] ~~`MANAGE_SHIFTS` パーミッションが `permissions` テーブルに定義済みか確認~~ → **V3.029 で `MANAGE_SHIFTS` を `permissions` テーブルに INSERT + `role_permissions` シードを追加**。scope = TEAM。SYSTEM_ADMIN・ADMIN に付与、DEPUTY_ADMIN は権限グループで明示設定。`MANAGE_PAYMENTS`（V3.007）と同じパターン
- [x] ~~シフト確定後に個人の Google カレンダーへ自動同期するか~~ → **Phase 3 では対応しない**。`ShiftPublishedEvent` を発行する設計は組み込み済みのため、将来このイベントを購読して Google Calendar API に連携するリスナーを追加するだけで対応可能。FUTURE_CONSIDERATIONS.md に記載
- [x] ~~複数チーム兼務メンバーのシフト重複チェック~~ → **公開時に警告表示のみ（エラーにはしない）**。`PATCH /publish` 実行時に割り当て済みユーザーの同一日他チームシフトを横断検索し、時間帯重複があればレスポンスの `warnings` 配列に詳細を返却。管理者が「このまま公開」or「修正する」を選択
- [x] ~~前週コピー機能のフロントエンド UX~~ → **`POST /shifts/schedules` に `copy_from_schedule_id` パラメータを追加**。サーバーサイドでコピー元のスロットを日付読み替え + バッチ INSERT を1トランザクションで実行。フロントエンドは「前回シフトからコピー」チェックボックス + コピー元ドロップダウン（直近5件）を実装
- [x] ~~希望強度は3段階で十分か？~~ → **v2 で5段階に拡張**。`PREFERRED / AVAILABLE / WEAK_REST / STRONG_REST / ABSOLUTE_REST`。既存 UNAVAILABLE は STRONG_REST に変換（V3.077）
- [x] ~~自動割当アルゴリズムは何を採用するか~~ → **MVP は貪欲法（GreedyShiftAssignmentStrategy）**。`ShiftAssignmentStrategy` interface で抽象化して将来 CSP ソルバへ差し替え可能
- [x] ~~時間帯×必要人数を柔軟に設定する仕組み~~ → **`shift_slots` の同一日複数レコード運用で対応**。`(schedule_id, slot_date, position_id, start_time, end_time)` の複数 INSERT で時間帯分割を表現
- [x] ~~D&D ライブラリの選定~~ → **`vuedraggable` を第一候補**として採用。PrimeVue 4 と共存可能。代替は `@formkit/drag-and-drop`
- [x] ~~希望提出リマインドの具体仕様~~ → **48h 前 + 24h 前の2段階通知 + 管理者向け低提出率アラート**。`is_reminder_sent_48h` カラムを追加（V3.078）
- [x] ~~月次上限・連続勤務制約は必須にするか任意にするか~~ → **任意項目（オプトイン）**。`member_work_constraints` テーブルで全項目 NULL 可能。個別 > チームデフォルト > 制約なし の解決順序
- [x] ~~確定前（COLLECTING/ADJUSTING）のメンバーからの変更依頼をどう扱うか~~ → **v2.1 で `shift_change_requests` 新規テーブル + 5 API 新設**（A-1 パターン）。公開後の交代（`shift_swap_requests`）とライフサイクルを分離。管理者審査フロー + 監査ログ完備
- [x] ~~公開後の代打を急募する場合のフロー~~ → **v2.1 でオープンコール（A-3）を `shift_swap_requests` に拡張**。`is_open_call / target_user_id / claimed_by / claimed_at / version` カラム追加。先着優先を楽観ロックで保証、月 3 件上限、通知オプトアウト設定あり
- [x] ~~自動割当結果を誤って公開してしまう事故を防げるか~~ → **v2.1 で目視確認必須化**。`shift_assignment_runs.visual_review_confirmed_*` カラム + `POST /assignment-runs/{runId}/confirm-visual-review` API + `PATCH /publish` のゲート 409 VISUAL_REVIEW_REQUIRED で構造的にブロック。監査ログ `SHIFT_SCHEDULE_VISUAL_REVIEW_CONFIRMED` 記録
- [x] ~~変更依頼が溜まって管理者が見切れなくなった場合の UX~~ → **MVP では件数レートリミット（同一スケジュール OPEN 5 件/ユーザー、全体数はなし）+ 一覧の OPEN 優先ソート**。ARCHIVED 遷移時の OPEN 依頼自動 WITHDRAWN 化は Phase 4 拡張（日次バッチ）として切り出し
- [x] ~~紙運用施設（整骨院・飲食店等）向けのシフト表 PDF 出力~~ → **v2.2 で追補**。`GET /shifts/schedules/{id}/pdf?layout=team|personal` を新設。F12.1 共通基盤を利用した同期生成、Flyway 不要、監査ログ `SHIFT_PDF_EXPORTED` 記録、権限は ADMIN/DEPUTY_ADMIN/本人のみ、SUPPORTER は不可、未公開はウォーターマーク付きで ADMIN のみ

### v2.2 以降で検討する未来拡張

- **PDF 非同期生成 + 通知**: 100 名超の大規模チームで生成が 5 秒を超える場合、生成ジョブキュー + ダウンロード準備完了通知に切り替える。`PdfGenerationJob` テーブル新設 + 署名付き URL + F04.3 プッシュ通知で「PDFが準備できました」連携。現時点では MVP の同期生成で十分と判定
- **全メンバー分の個人シフトをまとめて ZIP ダウンロード**: ADMIN が一括で全個人表を生成して ZIP で配布するユースケース。`GET /shifts/schedules/{id}/pdf/bulk.zip` として新設。生成時間が長いため非同期化と連動
- **日別詳細レイアウト**: `layout=daily` を追加し、1 日 1 ページで時間帯×メンバーのタイムテーブル形式を提供
- **ポジション別レイアウト**: `layout=by_position` を追加し、ポジションごとに 1 ページずつ該当メンバーのみ表示
- **`shift_pdf_exports` 専用テーブル**: 「再ダウンロード追跡」「PDF バージョン管理（version hash）」「失効制御（有効期限付き署名 URL）」の要件が出たら新設を検討（V3.086 以降）
- **PDF パスワード保護**: OpenPDF の所有者パスワード / ユーザーパスワード機能を利用し、配布時点でパスワード保護。組織ポリシーで要求された場合のみ追加
- **PDF テンプレートのカスタマイズ**: チームロゴ・ブランドカラー・フッター文言をチーム別にカスタマイズ（F12.1 の共通機構を拡張）

---

## 8.5. 運用上の留意事項【v2.1 新設】

### 自動割当結果の目視確認は構造的に強制される（最重要）

v2.1 では「自動割当を実行したスケジュール」の公開時に、管理者による目視確認承認（`shift_assignment_runs.visual_review_confirmed_at IS NOT NULL`）を**バックエンドで必須チェック**する。フロントエンドの改竄だけでは突破できない設計とする。

| 段階 | 仕組み | 補足 |
|---|---|---|
| UI レベル | 自動割当結果画面に固定の赤系ハイライトバナー「⚠️ 目視確認が必要です」+ 5 項目チェックリスト | §5「自動割当結果の目視確認フロー」参照 |
| UI レベル | 公開ボタン押下時に確認ダイアログ「すべての割当を目視で確認しましたか？」→ `visual_review_acknowledged=true` を送信 | §4 `PATCH /publish` リクエストボディ |
| API レベル | `PATCH /publish` で `visual_review_acknowledged != true` なら 400 | 最低限のゲート |
| API レベル | `PATCH /publish` で自動割当履歴あり + 最新 run 未確認なら 409 VISUAL_REVIEW_REQUIRED | 構造的なゲート（改竄突破不可）|
| 監査レベル | `SHIFT_SCHEDULE_VISUAL_REVIEW_CONFIRMED` 監査ログ + `visual_review_note` メモ | 誰がいつどのメモで確認したか永続記録 |

**自動割当が拾えない文脈の具体例（§1 冒頭と同じ内容を再掲、管理者トレーニングにも使う）**:

1. **希望の背景事情**: `shift_requests.note` にしか書かれていない「介護で17時退勤が望ましい」「受験直前のため短時間希望」等の個人的事情
2. **人間関係・配置配慮**: 「A さんと B さんは過去のトラブルで同時間帯を避けたい」「C さんは新人なので必ず先輩をペアに」等、システム外の文脈
3. **季節要因・突発イベント**: 繁忙期（クリスマス・年末年始・連休）、祭事、地域イベント、臨時休業、大型予約の入り具合
4. **スキル・資格のバランス**: 有資格者（整体師免許・食品衛生責任者・防火管理者・アルコール販売責任者等）が各時間帯に最低1人いるか
5. **新人教育のペアリング意図**: 新人と指導役を必ず同枠にする、逆に自立期にはあえて分けて独り立ちさせる等

### 変更依頼運用のコツ（管理者向け）

- **OPEN 依頼の優先処理**: 管理画面の `/shifts/change-requests` は `OPEN` を先頭にソート。放置すると依頼者のストレスと調整遅延を招く
- **却下時の理由必須**: `admin_note` を空欄で REJECTED にすると依頼者が納得しにくい。UI で「理由の記入」を強く促す（バリデーションはせず運用でカバー）
- **ACCEPTED の運用**: 本 API は「審査完了宣言」のみ。実際のスロット変更は別途 D&D で手動実施する。手順の取り違えを防ぐため、UI では「先にスロット変更を完了 → 依頼を受諾する」の順序を案内する
- **変更依頼からの目視確認連動**: 受諾後にスロットを変更した場合、自動割当結果からの差分が増える。PUBLISHED 遷移時には改めて目視確認ボタンを押すべき（冪等操作）

### オープンコール運用のコツ（管理者向け）

- **月 3 件の上限**: 同一ユーザーの月次乱用防止。上限に達したユーザーが連絡してきた場合、管理者が個別指名（A-2）で調整する運用
- **先着 1 名の仕様**: 早い者勝ちなので、手挙げ時に確認ダイアログ等で追加のステップを入れない（摩擦ゼロ）。誤押下で claim してしまったら `/swap-requests/{id}` を削除（取下）で戻せる
- **管理者裁量の差し替え**: `select-claimer` で `claimed_by` 以外を指定できるが、先着者に配慮して理由を伝えること（運用ガイドで明文化）
- **通知疲労対策**: `receive_shift_open_call_broadcast` OFF のユーザーにはプッシュが届かないが、アプリ内通知センターには残す（完全な無音ではなく緩い通知）

### 変更依頼 × 目視確認の相互作用

- 確定前（ADJUSTING 中）に変更依頼（A-1）を受けて手動でスロット変更 → その後に自動割当再実行 → 新 run が目視未確認で公開ブロック、という流れが正常系
- 変更依頼を受諾したあとに自動割当を再実行しない（手動調整のみで公開する）場合、`shift_assignment_runs` の既存 run は依然として存在する。最新 run が目視確認済みなら公開可能（このため目視確認は冪等）

### PDF 配布時のガイドライン【v2.2 新設】

PDF 出力は**紙掲示・印刷配布・メール添付・チャット共有**など多様な二次利用が発生する運用機能である。紙は失効/回収が困難、電子ファイルは転送が容易という特性を踏まえ、以下のガイドラインを管理者向けに明示する。

#### 紙配布の留意事項

- **誤廃棄・紛失リスク**: シフト表には全メンバーの氏名と勤務時間が含まれる。ゴミ箱に捨てる際はシュレッダー必須。忘れ物として落とした場合は個人情報流出事故に該当
- **ロッカー・休憩室への掲示**: 第三者（取引業者・アルバイト家族等）の目に触れないエリアに限定。顧客動線上（受付・待合室）には掲示しない
- **旧版の回収**: シフト変更があった場合は旧版を即座に回収・破棄し、新版と差し替える。旧版が残存すると混乱の原因となる
- **個人配布版（`layout=personal`）は当人にのみ手渡し**: 他人に個人シフトが渡ってしまうと、勤務パターンから生活圏・家庭事情が推測できてしまう

#### 電子配布の留意事項

- **パスワード付 ZIP 推奨**: メール添付・チャット共有する際はパスワード付 ZIP にして別経路でパスワードを共有（いわゆる PPAP は近年セキュリティ的に批判されるが、代替の Secure 配信基盤がない現場では現行実務として許容）。将来的にアプリ内「PDF 共有」機能（`SecureLink` 基盤）が整ったら切り替え
- **LINE・SNS での共有は原則禁止**: 個人 LINE グループで PDF を投げると、端末ロックなしの家族閲覧・スクショ拡散などが起こりうる。LINE 共有を運用に組み込む場合はチーム管理者が事前に**メンバー全員の同意を書面で取得**することを推奨
- **クラウドストレージ共有**: 「リンクを知っている人全員」設定は禁止。メンバー指名限定の共有に留める。有効期限を短く（1〜2週間）設定
- **メール誤送信防止**: 宛先補完機能で別部署・別会社にシフト表 PDF を送信した事故は複数の企業で実例あり。送信前の宛先確認をチームルール化

#### 変更後の旧版失効周知

- シフト変更が発生した場合、**旧版 PDF は即座に無効**とする旨をメンバーへアナウンス（アプリ内通知 + 紙掲示の差し替え）
- 旧版 PDF のファイル名は生成日時を含むが、メンバーが手元の PDF を見返した時に「これは最新か？」判断できるよう、**印刷時に「YYYY-MM-DD 時点」の明記**を PDF 本文に含める（§4 `customNote` 既定文で自動対応済）
- 重要な変更（出勤日そのものの変更）は PDF 再配布前に個別の電話/チャット通知を推奨。PDF は補助資料との位置づけ

#### PDF 出力は情報の二次配布手段であると自覚する

- SUPPORTER に PDF 出力権限を与えていない理由は「紙・電子を問わず二次配布リスクが大きい」ため
- ADMIN / DEPUTY_ADMIN が PDF を発行した時点で、その情報は**アプリ外へ流出可能な形式に変換された**と見なす。監査ログ `SHIFT_PDF_EXPORTED` で出力履歴は残るが、**配布先・二次配布先は追跡できない**
- 「誰に」「どの経路で」配布したかを管理者が台帳管理することを運用で推奨（アプリ側では提供しない）
- `include_draft_watermark=true` の未公開 PDF は特に取り扱い注意。「内部確認用」文字があっても、公開後のシフトとして誤解される事故の可能性あり

#### PDF 出力 vs アプリ内閲覧の使い分け

- **アプリ内閲覧で十分なケース**: メンバーがスマホで自分の当週シフトを確認する日常用途。オフライン対応（F11.1）済のため電波不良でも閲覧可
- **PDF 出力が必要なケース**: ①バックオフィスの壁・休憩室掲示 ②紙運用が主の整骨院・飲食店 ③アプリ未導入メンバーへの共有（過渡期） ④役所・労基署等への提出（勤務表として） ⑤月末の帳票アーカイブ
- アプリ内閲覧で代替できるならそれが最も安全。PDF は紙/電子の二次配布が避けられない時のみ活用する方針を推奨

---

## 9. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-03-13 | 初版作成: 3テーブル（shift_schedules, shift_slots, shift_requests）、18 API エンドポイント、5段階ステータスライフサイクル、希望収集・調整・公開フロー、ダッシュボード連携、テンプレートコピー、自動アーカイブバッチ |
| 2026-03-13 | 精査①: ENUM→VARCHAR（§23準拠）・楽観的ロック version 追加（shift_schedules + shift_slots、§22準拠）・is_reminder_sent カラム追加・ステータス遷移表に ADJUSTING→PUBLISHED 追加・監査ログイベント拡充・バッチ処理詳細化（実行間隔・エラーハンドリング・ログ出力）・publish 400 エラー文言明確化・MEMBER/SUPPORTER 可視範囲定義・一括作成 API レートリミット追加・assigned_user_ids 超過時の挙動明記・JSON パフォーマンス対策メモ |
| 2026-03-13 | 精査②: PATCH /status・/publish に version 追加（楽観的ロック漏れ修正）・POST/PATCH/GET レスポンスの version 網羅・COLLECTING→DRAFT 差し戻し時の is_reminder_sent リセット・自動アーカイブに deleted_at IS NULL 条件追加・request_deadline=NULL 時のリマインド動作明記・深夜跨ぎ重複チェックの2区間分割注記・slot_count/request_count の N+1 回避・COLLECTING 中スロット削除の連動通知・slot_date 整合性バリデーション・POST /requests 配列上限(200件)・GET /shifts/my 未提出表示明確化・PUT /schedules 変更不可フィールド明記・エンドポイント一覧説明修正 |
| 2026-03-14 | 精査③: DEPUTY_ADMIN スコープに統計閲覧追加・slot_date の深夜跨ぎ時の意味を明記・GET /schedules の from/to フィルタ条件明記（期間重複判定）・POST /requests の slot_id 所属スケジュール検証追加・GET /shifts/my のエラー 403→401 修正・PUT /slots の DRAFT/COLLECTING 時 assigned_user_ids 送信を 400 エラー化・request_deadline 過去日時バリデーション追加・重複チェッククエリに deleted_at IS NULL 追加・COLLECTING 中スケジュール削除時のメンバー通知追加 |
| 2026-03-14 | 機能拡張: 時給設定・給与概算表示（shift_hourly_rates テーブル + 4 API + GET /shifts/my に estimated_pay/pay_summary 追加 + summary に labor_cost_estimate 追加）。V3.036 追加、テーブル計7個 |
| 2026-03-14 | 機能拡張8件: ①希望提出の一括デフォルト設定（UXガイド追加）②シフト交代リクエスト（shift_swap_requests テーブル + 5 API + 3ステップワークフロー）③連勤・過剰勤務の公開時警告（5連勤/週40時間閾値）④デフォルト可否プロファイル（member_availability_defaults テーブル + 2 API + COLLECTING 遷移時自動適用）⑤手動リマインド API + 低提出率アラート（is_low_submission_alerted カラム追加）⑥ステータス遷移イベント統一（ShiftStatusChangedEvent 共通親 + 個別イベント継承）⑦ポジションマスター化（shift_positions テーブル + 4 API + shift_slots.position→position_id FK 変更）⑧自動遷移バッチ冪等性保証（last_auto_transition_at カラム + イベント駆動通知分離）。Flyway V3.031-V3.035 追加、テーブル計6個に拡張 |
| 2026-03-14 | 精査④: `reminder_sent`→`is_reminder_sent`（§10 Boolean命名規約準拠）・GET /schedules 一覧に version 追加・POST /slots レスポンスに version 明記・DELETE のステータス制限に ARCHIVED 追加・ADJUSTING 削除時の通知追加・PUT /requests の slot_id/slot_date 変更不可を明記・GUEST 希望提出不可を 403 エラーに明記・summary の position=NULL 集計方法明記・UNIQUE KEY の NULL 動作注記拡充・PATCH /status エンドポイント説明の publish 分離明確化・ADJUSTING→COLLECTING 差し戻し時の再収集通知追加・IDOR 防止・スロット所属検証のセキュリティ項目追加・§25 に論理削除 DELETE=200 OK の例外注記追加・copy_from_schedule_id を ADJUSTING にも拡張・自動アーカイブバッチの OptimisticLockException スキップ明記・request_deadline 過去時の希望提出・編集・取り下げブロック追加（自動遷移バッチ猶予期間対策）・FUTURE_CONSIDERATIONS.md に shift_slot_assignments 正規化テーブル検討追記 |
| 2026-04-23 | **v2 大幅改訂**: ①希望強度を3段階から5段階に拡張（PREFERRED / AVAILABLE / WEAK_REST / STRONG_REST / ABSOLUTE_REST）、既存 UNAVAILABLE は STRONG_REST に自動移行 ②時間帯分割スロット対応（同一日に複数スロットを INSERT 可能） ③自動割当アルゴリズム設計（Strategy パターン、MVP は GreedyShiftAssignmentStrategy、将来 CSP 差し替え可能） ④任意の勤務制約（member_work_constraints テーブル: 月次時間・日数・連勤・夜勤・勤務間隔） ⑤D&D 編集 UI 設計（vuedraggable、楽観的更新、モバイル対応、アクセシビリティ） ⑥希望提出リマインド強化（48h 前通知追加、is_reminder_sent_48h カラム） ⑦自動割当関連 API 7本追加（/auto-assign, /auto-assign/confirm, /auto-assign DELETE, /assignment-runs, /assignment-runs/{id}, /slots/{id}/assignments, /work-constraints 3本） ⑧新規テーブル3つ（shift_assignments, shift_assignment_runs, member_work_constraints） ⑨Flyway V3.077〜V3.082 追加（データ移行含む） ⑩Feature Flag `feature.shift.v2.enabled` 導入 |
| 2026-04-23 | v2 第一精査: 不備（CHECK 制約の記述漏れ補完・`shift_assignments.status` の UNIQUE 制約注記追加・PROPOSED/CONFIRMED/REVOKED 遷移ルール追記・scope_breakdown のフィールド名統一・member_work_constraints の全項目 NULL 拒否ルール明記）／セキュリティ（自動割当スコアの露出制限・work_constraints の他人分非公開・監査ログ v2 4種追加・自動割当レートリミット追加）／ユーザビリティ（キーボード代替・aria-label・オフラインキュー連携・モバイル長押し遅延）／見落とし（ABSOLUTE_REST のみ提出でも提出済み扱い・退職者除外・兼務者の他チーム時間帯重複・同点処理の決定論的タイブレーク・FAILED 実行の stale 検知タイムアウト60秒）／保守（ロールバック手順・in-flight データ戦略・Feature Flag の旧 UI 互換モード）|
| 2026-04-23 | v2 第二精査: §5 に「テスト観点」セクション新設（バックエンド12カテゴリ・フロントエンド5カテゴリ・パフォーマンス3シナリオ）・POST /auto-assign のレスポンスを 200 OK（SUCCEEDED）中心に整理し 202 は非同期拡張用として分離・auto-assign/confirm の楽観的ロック範囲を明記（schedule 単位でロック、slot version はリアルタイム取得）・他機能連携マトリクス追加（F03.1/F04.3/F10.3/F02.3/F11.1）・希望提出画面 UI をADHD考慮で詳細化（ラジオカード5色・一括操作・note 折りたたみ・モバイル最小タップサイズ）・F02.3 連携の機能名を「タスク管理（Todo）」に改めて記載・ロールバック時の v2 コード停止順序明記・保留課題の検知ワード全件 grep 確認済み（本設計書に残存なし） |
| 2026-04-23 | **v2.1 追補**: ①**シフト変更依頼 3 パターン**を整備 - (A-1) 確定前の変更依頼用に `shift_change_requests` テーブル新設 + 5 API（POST/GET/GET{id}/PATCH review/DELETE withdraw）+ 監査ログ 3 種（CREATED/REVIEWED/WITHDRAWN）、(A-2) 個別指名交代は既存 `shift_swap_requests` に `target_user_id` / `is_open_call` カラム追加で明示化、(A-3) オープンコール（全体募集）を `shift_swap_requests` に追加（`is_open_call=true, target_user_id=null, claimed_by, claimed_at, version`）+ 2 API（claim/select-claimer）+ 月 3 件上限 + 楽観ロック先着優先 + 通知オプトアウト `receive_shift_open_call_broadcast` ②**自動割当結果の目視確認必須化** - `shift_assignment_runs` に `visual_review_confirmed_by / visual_review_confirmed_at / visual_review_note` 3 カラム追加 + `POST /assignment-runs/{runId}/confirm-visual-review` API 新設 + `PATCH /publish` に `visual_review_acknowledged` 必須 + 最新 run 未確認で 409 VISUAL_REVIEW_REQUIRED ゲート + 監査ログ `SHIFT_SCHEDULE_VISUAL_REVIEW_CONFIRMED` ③ §1 冒頭に最重要注意喚起ブロック（自動割当が拾えない 5 観点: 希望背景・人間関係・季節/イベント・スキル/資格・教育ペアリング）④ Flyway V3.083〜V3.085 追加 ⑤ Feature Flag `feature.shift.v2_1.enabled` と `feature.shift.v2_1.visual_review_gate.enabled` 導入 ⑥ §8.5「運用上の留意事項」セクション新設 |
| 2026-04-23 | v2.1 第一精査（5観点網羅）: **不備** = `shift_change_requests.CHECK` 制約の明記・FK の ON DELETE 挙動統一（requester CASCADE / target SET NULL）・`shift_swap_requests.status` の拡張列挙値（OPEN_CALL/CLAIMED 追加）と既存 CHECK 再作成手順追加、**セキュリティ** = 変更依頼 IDOR 防止ルールを権限モデル節に明記（他人の割当を指した場合 403）・オープンコール claim のレース条件対策を 2 重防御（楽観ロック + SELECT FOR UPDATE）に増強・v2.1 監査ログ 7 種を §6 に追加・v2.1 レートリミット 4 種（change-requests 10/分・open-call 月 3・claim 30/分・visual-review 10/分）を §6 に追加・目視確認の構造的抜け道封じ（ゲートがバックエンド強制、再実行で再確認必須）、**ユーザビリティ** = 理由テンプレートチップ（通院/家族用事/試験等）で ADHD 配慮・変更依頼フォームの入力摩擦ゼロ設計・オフライン時の `Idempotency-Key` ヘッダで重複送信防止・オープンコール手挙げは確認ダイアログなしで摩擦ゼロ、**見落とし** = PATCH /publish 時の OPEN 依頼警告表示・自動割当再実行時の目視確認リセット挙動・既存 PUBLISHED スケジュールの遡及適用除外（v2.1 デプロイ時に過去公開済み分は影響なし）・SUPPORTER/GUEST の claim 禁止・依頼者本人の claim 禁止、**保守** = Feature Flag 2 段階（機能 ON/OFF と目視確認ゲート ON/OFF の分離）・v2.1 ロールバックはカラム DROP / テーブル DROP で段階可能 |
| 2026-04-23 | v2.1 第二精査（5観点再点検）: **不備** = `POST /swap-requests` のリクエスト例を A-2/A-3 両モードで併記・`shift_swap_requests` のステータスライフサイクルを個別交代・オープンコールで表形式に整理・`shift_change_requests` と `shift_swap_requests` のスコープ境界表を §5 に新設、**セキュリティ** = 変更依頼の `reason` 個人情報露出制御（本人・管理者のみ閲覧、MEMBER の URL クエリ改竄無視）・オープンコール依頼者本人の自己 claim 禁止を Service 層で 403・k6 で 100 並列 claim テスト必須化、**ユーザビリティ** = 変更依頼の管理画面 OPEN 優先ソート・却下理由 UI 促進・目視確認チェックリスト 5 項目を UI チェックボックス化してから「承認」ボタン活性化、**見落とし** = Idempotency-Key による PWA オフライン復帰時の重複 INSERT 防止・オープンコール取下時に claimed_by ユーザーへの通知・確定 ≠ 目視確認の運用上の区別（§5 参照）・ARCHIVED 遷移時の OPEN 依頼残存問題を Phase 4 課題として §8 に明記、**保守** = §8.5「運用上の留意事項」セクション新設（目視確認が拾えない 5 観点の具体例を §1 と対応付け、管理者トレーニング資料化）・変更履歴の重複・矛盾チェック実施・保留系検知ワード（実装未完・要調整マーカー全種）で 0 件検証済み（本設計書に残存なし） |
| 2026-04-23 | **v2.2 追補（シフト表PDF出力）**: ① `GET /api/v1/shifts/schedules/{id}/pdf?layout=team\|personal&member_id=...&include_draft_watermark=...&locale=...` を新設。`layout=team`（A4 横・マトリクス）/ `layout=personal`（A4 縦・時系列）の 2 レイアウト ② F12.1 PDF 生成共通基盤（Flying Saucer + Thymeleaf + OpenPDF + NotoSansJP 埋込）を使用し同期生成。署名付き URL / ジョブキューは採用せず認可付き同期 `byte[]` レスポンス ③ 権限: ADMIN/DEPUTY_ADMIN は team / 任意メンバーの personal 可、MEMBER は自分の personal のみ、SUPPORTER / GUEST は不可、非 PUBLISHED のウォーターマーク版は ADMIN のみ ④ 監査ログ `SHIFT_PDF_EXPORTED` を `audit_logs` 既存テーブルに追加（DDL 不要、Flyway マイグレーション不要）⑤ レートリミット 1ユーザー1分10件 + チーム1分30件 ⑥ 個人情報保護: ホワイトリスト方式で変数組立、`reason` / `admin_note` / 時給 / 自動割当スコア / 勤務制約個別値 / 希望 note / 連絡先を PDF に絶対含めない ⑦ ウォーターマーク方式 A（OpenPDF `PdfStamper` で全ページオーバーレイ）採用 ⑧ i18n: サーバ MessageSource (`messages_{ja,en,zh,ko,es,de}.properties`) で PDF 本文 6 言語対応、フロントは操作ボタン i18n のみ ⑨ ファイル名規約を F12.1 §2 に追記（`{発行日}_シフト表_{チーム名}_{開始日}-{終了日}.pdf` / `{発行日}_個人シフト_{氏名}_...`、内部確認用版は `_内部確認用` 接尾）⑩ §8.5 に「PDF 配布時のガイドライン」節新設（紙/電子二次配布の実務ガイド）⑪ §8 既知課題に v2.2 で対応の 1 行追加＋ v2.2 以降の未来拡張（非同期化・ZIP 一括・日別/ポジション別・パスワード・専用監査テーブル）を明示 |
| 2026-04-23 | v2.2 第一精査（5観点網羅）: **不備** = ①`GET /pdf` の認可フロー（`checkTeamMember` → layout 別分岐）を §5 ビジネスロジックに詳細記述・②ファイル名規約（`{発行日}_シフト表_{チーム名}_{期間}.pdf`）を F12.1 §2 追記要件として §7 v2.2 節に明記・③エラーコード `SHIFT_PDF_001`（非 PUBLISHED + 非ウォーターマーク要求 409）を §4 エラー表に新設・④`layout=personal` かつ対象メンバー期間内割当ゼロ件で 400（空 PDF 防止）を API 仕様とテスト観点に追加、**セキュリティ** = ①個人情報ホワイトリスト方式を §6 に強制ルール化（`Map.putAll(entity)` 禁止、PR テンプレートでレビュー必須）・②`Cache-Control: private, no-store` + `X-Frame-Options: DENY` を必須レスポンスヘッダに追加・③SUPPORTER を PDF 出力不可として確定シフト閲覧権限と分離（画面閲覧は可だが PDF は二次配布リスクで不可）・④ウォーターマーク版の MEMBER アクセスを 403 で封じる（クエリ改竄不可）・⑤ファイル名にチーム名/氏名が含まれる漏洩リスクを §6 に明記、**ユーザビリティ** = ①モバイル `Blob` ダウンロード → OS 既定 PDF ビューア起動のフロー明記・②ボタンスピナー + タイムアウト 20 秒・③6 言語対応で日本語メンバー氏名はそのまま維持・④生成 20 秒超対策として §9 非同期化の余地を明記、**見落とし** = ①多ページ時の改ページ指定（`page-break-inside: avoid`）・②欠員の色覚配慮（赤背景 + `✕` 記号併用）・③ARCHIVED スケジュールの出力可否（PUBLISHED 扱いで可）・④深夜跨ぎスロットの個人タイムライン表示（開始日扱い）・⑤ASCII フォールバック名でのチーム名情報漏洩防止（`shift_team.pdf` 固定）、**保守** = ①Flyway 不要（既存 `audit_logs` に event_type 追加のみ）で v2.2 単独でのロールバックが容易・②Thymeleaf テンプレートと `messages_*.properties` のみ追加で実装範囲が明瞭・③F12.1 共通基盤経由で変更時の影響範囲が限定的・④F12.1 §2 命名規約 / §5 対象機能別一覧への追記を実装時チェックリスト化 |
| 2026-04-23 | v2.2 第二精査（5観点再点検）: **不備** = ①i18n キー命名規則を `shift.pdf.*` で統一し 17 キー全列挙（§5 に表形式で掲載）・②ファイル名 `_内部確認用` 接尾ルールをファイル名規約節に統一記載・③ウォーターマーク方式 A（OpenPDF `PdfStamper`）と方式 B（事前画像）の比較を再確認、方式 A 採用根拠（画像リソース不要・多言語対応容易）を §4 注記追加、**セキュリティ** = ①個人情報「絶対に含めない」リストを §4 §6 の 2 箇所に重複掲載（設計書抜けを防止）・②`SHIFT_PDF_EXPORTED` 監査ログに `output_size_bytes` を記録し異常サイズ（DoS 兆候）検出の手がかりを確保・③レートリミットをユーザー単位 + チーム単位の 2 層バケットに増強・④ウォーターマーク付き PDF を MEMBER が取得する抜け道が無いことを再確認（Service 層の 403 判定 + クエリ値の無効化）、**ユーザビリティ** = ①`customNote` 既定文に「YYYY-MM-DD 時点の確定版です」の日付自動埋込で旧版混乱防止・②色覚多様性配慮で欠員は赤背景 + `✕` 記号併用・③フロント側 `shift.pdf.*` i18n キーを 7 種に整理（操作ボタン・ダウンロード中・エラーのみ、本文はサーバ管理）・④確定シフト画面のスプリットボタンから MEMBER/ADMIN でメニュー項目を切替・⑤モバイルタップサイズ 44x44px、**見落とし** = ①ARCHIVED スケジュールも PDF 出力可（PUBLISHED の最終形として有効）と §4 エラー節に明記・②FAILED な非同期ジョブの扱いは将来拡張時に設計（v2.2 は同期のため該当なし）・③Apache PDFBox を用いたテスト「PDF 内にテキストとして `reason`/時給/スコア等が出現しないこと」を個人情報非露出テストに追加・④多言語テスト全 6 言語の PDF 生成・フォント埋込チェック・⑤印刷時の「この PDF は最新か？」判断を助ける本文内 `YYYY-MM-DD 時点` 明記、**保守** = ①§8 未解決に v2.2 解決済み 1 行 + 未来拡張 7 項目を明示（非同期化・ZIP 一括・日別/ポジション別/パスワード/`shift_pdf_exports` 専用テーブル/テンプレートカスタマイズ）・②変更履歴に v2.2 追補と精査 2 回分を記録・③保留系マーカー（実装未完・未解決マーカー全種）を本設計書全体で grep 検証し 0 件であることを宣言（本設計書に残存なし）・④F12.1 §2 §5 への F03.5 行追記を「実装時の前提作業」としてマイグレーション注記に記録し、実装抜けを防止 |

---

*前: [03_business_logic.md](03_business_logic.md) | [README.md](README.md) へ戻る*
