# F03.5 シフト管理 — §5 ビジネスロジック

> このファイルは [F03.5_shift/README.md](README.md) の一部です。

## 5. ビジネスロジック

### シフトスケジュール作成・運用フロー

```
1. 管理者がシフトスケジュールを作成（DRAFT）
   a. 期間（start_date / end_date）、期間種別（WEEKLY / MONTHLY / CUSTOM）を設定
   b. 希望収集の締切日時を任意で設定

2. スロット（枠）の設定
   a. 日付×時間帯×ポジションの組み合わせでスロットを作成
   b. 一括作成 API で前週のシフトをテンプレートとしてコピー可能
   c. ポジションなし（position = NULL）の場合は時間帯のみで管理

3. 希望収集開始（DRAFT → COLLECTING）
   a. ステータスを COLLECTING に変更
   b. チームの対象メンバー（MEMBER 以上）にプッシュ通知 + お知らせ欄で希望提出を依頼
     （ApplicationEvent: ShiftCollectionStartedEvent）
   c. メンバーは各スロットに **5段階の希望強度** を登録:
      - `PREFERRED`（出勤希望）/ `AVAILABLE`（指定なし）/ `WEAK_REST`（出れなくはない）/ `STRONG_REST`（できれば休み）/ `ABSOLUTE_REST`（絶対休み）

4. 希望収集締切（COLLECTING → ADJUSTING）
   a. request_deadline 到達時にバッチで自動遷移（5分間隔で実行）、または管理者が手動で遷移
   b. 自動遷移時は管理者にプッシュ通知（「希望収集が締め切られました。シフト調整を行ってください」）
   c. 未提出メンバーがいる場合は管理者に未提出者リストを含む通知
   d. バッチエラー時: ERROR ログ出力 + 次回実行で再試行（対象レコードをスキップしない）

5. 管理者がシフトを調整（ADJUSTING）
   a. 希望一覧マトリクスを参照しながら assigned_user_ids を設定
   b. 充足サマリーで欠員を確認、必要に応じてメンバーに個別連絡
   c. 希望の差し戻し（ADJUSTING → COLLECTING）も可能
   d. **【v2】自動割当（オートアサイン）**: POST /auto-assign で貪欲法スコアリング実行 → PROPOSED 状態で shift_assignments に提案 → 管理者が UI で確認 → /confirm で確定、または /auto-assign DELETE で破棄
   e. **【v2】D&D UI**: マトリクス表上でメンバーを枠間でドラッグ移動。PATCH /slots/{id}/assignments で差分反映。ハード制約（ABSOLUTE_REST）は 409 でブロック、ソフト制約（連勤等）は警告のみ

6. シフト確定・公開（ADJUSTING → PUBLISHED）
   a. 全スロットの assigned_user_ids が設定されていることを検証（欠員がある場合も公開可能だが警告を表示）
   b. published_at / published_by を記録
   c. 全メンバーにプッシュ通知 + お知らせ欄で確定通知
     （ApplicationEvent: ShiftPublishedEvent）
   d. 各メンバーのダッシュボードに確定シフトが自動反映

7. アーカイブ（PUBLISHED → ARCHIVED）
   a. シフト期間終了後、手動または日次バッチで ARCHIVED に遷移
   b. 自動アーカイブ: end_date から7日経過後にバッチで自動遷移
```

### 希望収集リマインド

```
1. request_deadline の24時間前に未提出メンバーへリマインド通知
   （ApplicationEvent: ShiftRequestReminderEvent）
2. リマインドは1回のみ送信。shift_schedules.is_reminder_sent フラグで二重送信を防止
   - バッチ条件: status = 'COLLECTING' AND is_reminder_sent = FALSE
     AND request_deadline BETWEEN NOW() AND NOW() + INTERVAL 24 HOUR
   - 送信後に is_reminder_sent = TRUE に更新（同一トランザクション内）
3. 管理者には未提出者の人数を含む通知を送信
4. request_deadline = NULL（手動締め切り）の場合: 自動リマインドは送信されない。管理者が手動で ADJUSTING に遷移する運用
```

### 前週シフトのテンプレートコピー

```
1. POST /shifts/schedules の copy_from_schedule_id パラメータで実現（サーバーサイド一括処理）
2. 処理（1トランザクション）:
   a. スケジュール作成（shift_schedules INSERT）
   b. コピー元の shift_slots を取得
   c. slot_date を新しい期間に読み替え:
      - WEEKLY: コピー元と新規の start_date の差分（日数）で全 slot_date をオフセット
        例: コピー元 start_date=3/2, 新規 start_date=3/9 → 差分7日 → 3/2→3/9, 3/3→3/10, ...
      - MONTHLY: 日番号をそのまま使用（例: 3日目 → 3日目）。新期間に存在しない日（例: 2月30日）はスキップ
      - CUSTOM: コピー元期間の先頭からの相対日数で変換
   d. start_time / end_time / position / required_count / note をコピー
   e. assigned_user_ids はコピーしない（毎週変わるため）
   f. shift_slots へバッチ INSERT
3. コピー元の制約: 同一チームの ADJUSTING / PUBLISHED / ARCHIVED スケジュールのみ（DRAFT / COLLECTING はスロット構成が未確定のため不可）
4. フロントエンド: 「新規作成」画面に「前回シフトからコピー」チェックボックス + コピー元選択ドロップダウン（同一チームの直近5件を表示）
```

### 自動アーカイブバッチ

```
1. 日次バッチ（深夜帯に実行。@Scheduled で毎日 03:00 JST）
2. 条件: status = 'PUBLISHED' AND end_date < CURDATE() - INTERVAL 7 DAY AND deleted_at IS NULL
3. 対象レコードの status を ARCHIVED に更新
4. バッチサイズ: 100件ずつ UPDATE
5. 処理件数を application log に出力（0件の場合もログ出力）
6. エラー時: ERROR ログ出力。部分的に失敗した場合は処理済み分はコミットし、未処理分は次回バッチで再処理
7. 楽観的ロック競合（`OptimisticLockException`）: 管理者が同時に割り当て変更中の場合に発生しうる。該当レコードをスキップし WARN ログ出力、次回バッチで再試行
```

### ダッシュボード連携

```
1. 個人ダッシュボードの「今日のシフト」ウィジェット:
   a. GET /shifts/my?from={today}&to={today} で当日の確定シフトを取得
   b. 開始時刻の30分前にプッシュ通知でリマインド（ApplicationEvent: ShiftReminderEvent）
2. チームダッシュボードの「本日の出勤者」ウィジェット:
   a. 当日の PUBLISHED スロットの assigned_user_ids を集計
   b. ポジション別の出勤者一覧を表示
```

### 他機能との連携ポイント

| 機能 | 連携方向 | 内容 |
|---|---|---|
| F03.1 スケジュール・出欠管理 | 双方向 | シフトスロットの時間帯は F03.1 個人カレンダーにも表示。シフト変更時に F03.1 の表示も更新 |
| F04.3 プッシュ通知 | 送信 | `SHIFT_REMINDER`（開始前リマインド）、`SHIFT_REQUEST_REMINDER`（希望提出リマインド 48h/24h 前）、`SHIFT_PUBLISHED`（確定通知）、`SHIFT_SWAP_*`（交代ワークフロー）、`SHIFT_CANCELLED`（COLLECTING/ADJUSTING 中の削除通知）を発行。**【v2.1】** `SHIFT_CHANGE_REQUEST_CREATED / REVIEWED / WITHDRAWN`（変更依頼の各ステージ）、`SHIFT_OPEN_CALL_CREATED / CLAIMED / SELECTED`（オープンコールの各ステージ）、`SHIFT_VISUAL_REVIEW_CONFIRMED`（目視確認承認）。また**通知オプトアウト設定**として `receive_shift_open_call_broadcast`（デフォルト ON）を F04.3 通知設定に追加 |
| F10.3 監査ログ | 書き込み | §6 セキュリティに列挙した `SHIFT_*` イベントを記録 |
| F02.3 タスク管理（Todo） | 将来拡張 | 確定シフトの開始時刻に自動でタスク項目を作成する機能は **Phase 4 の別機能として切り出す**（本 v2 設計の MVP 対象外）。`ShiftPublishedEvent` を購読する `ShiftToTaskListener` を将来追加する余地は残してある |
| F11.1 PWA/オフライン | 送受信 | D&D 操作と希望提出のオフラインキュー対応。`useOfflineQueue` 連携 |
| **【v2.2】F12.1 PDF 生成共通基盤** | 受動 | 確定シフト表PDFの生成に `PdfGeneratorService` / `PdfFileNameBuilder` / `PdfResponseHelper` を利用。Thymeleaf テンプレート `pdf/shift-team-matrix.html` および `pdf/shift-personal-timeline.html` を F12.1 §3 のテンプレートディレクトリに追加（F12.1 §2 命名規約・§5 対象機能別一覧に `シフト表` 行を追記） |

### 複数チーム兼務メンバーの重複チェック

```
1. PATCH /publish 実行時（公開直前）に自動チェック
2. 処理:
   a. 対象スケジュールの全スロットから assigned_user_ids を収集
   b. 各ユーザーについて、同一日の他チームの PUBLISHED シフトスロットを検索:
      SELECT ss.*, ssc.team_id, t.name AS team_name
      FROM shift_slots ss
      JOIN shift_schedules ssc ON ss.schedule_id = ssc.id
      JOIN teams t ON ssc.team_id = t.id
      WHERE ssc.status = 'PUBLISHED'
        AND ssc.deleted_at IS NULL
        AND ssc.team_id != {current_team_id}
        AND ss.slot_date IN ({target_dates})
        AND JSON_CONTAINS(ss.assigned_user_ids, CAST({user_id} AS JSON))
   c. 時間帯の重複判定: start_time < other.end_time AND end_time > other.start_time
      ※ 深夜跨ぎスロット（end_time < start_time）は Service 層で2区間に分割して判定
        例: 22:00-06:00 → [22:00-24:00] + [00:00-06:00] に分解してから重複チェック
3. 重複が検出された場合: レスポンスの warnings 配列に詳細を含めて返却（公開はブロックしない）
4. フロントエンド: 警告ダイアログを表示し「このまま公開」or「修正する」を選択
```

### シフト交代ワークフロー（A-2 個別指名 / A-3 オープンコール）

**v2.1 より 2 パターンを明確に分岐する。**

#### A-2: 個別交代依頼（特定メンバー指名）

```
1. メンバーが交代リクエスト作成（POST /swap-requests, is_open_call=false, target_user_id=相手ID）
   a. PUBLISHED スケジュールの自分がアサインされたスロットのみ対象
   b. 指名相手にプッシュ通知（ApplicationEvent: ShiftSwapRequestedEvent）
   c. 48 時間以内の応答がない場合、自動的に CANCELLED（将来拡張・Phase 4）。MVP では手動取下のみ

2. 指名相手が引き受け（PATCH /swap-requests/{id}/accept）
   a. PENDING → ACCEPTED に遷移
   b. 依頼者と管理者に通知

3. 管理者が承認（PATCH /swap-requests/{id}/approve）
   a. ACCEPTED → APPROVED に遷移
   b. スロットの assigned_user_ids を自動更新（requester → accepter の差し替え）
   c. 両メンバーにプッシュ通知（ApplicationEvent: ShiftSwapApprovedEvent）
   d. 監査ログ: SHIFT_SWAP_APPROVED を記録

4. 管理者が却下（PATCH /swap-requests/{id}/reject）
   a. PENDING / ACCEPTED → REJECTED に遷移
   b. 依頼者と引き受け者に通知
```

#### A-3: オープンコール（全体募集）【v2.1 新規】

```
1. メンバーが全体募集を作成（POST /swap-requests, is_open_call=true, target_user_id=null）
   a. PUBLISHED スケジュールの自分がアサインされたスロットのみ対象
   b. Service 層でレートリミット判定: 同一ユーザーが当月作成したオープンコール数 < 3 件
      - SELECT COUNT(*) FROM shift_swap_requests
        WHERE requester_id = :me AND is_open_call = TRUE
          AND YEAR(created_at) = :y AND MONTH(created_at) = :m
      - 3 件以上の場合は 429 Too Many Requests（スパム防止）
   c. status = OPEN_CALL で INSERT
   d. チーム全員（SUPPORTER/GUEST 除く、依頼者自身除く、通知オプトアウト設定者除く）にプッシュ + アプリ内通知
      （ApplicationEvent: ShiftOpenCallCreatedEvent）
   e. F04.3 通知設定で「代打募集通知を受け取らない」が ON のユーザーは送信対象から除外

2. 候補メンバーが手を挙げる（POST /swap-requests/{id}/claim）
   a. SELECT ... FOR UPDATE + version チェックで先着1名のみ成功
   b. OPEN_CALL → CLAIMED に遷移、claimed_by = 応募者ID, claimed_at = NOW()
   c. 2人目以降は 409 Conflict（「別の方が先に応じたため締め切られました」Toast 表示でクライアント側を同期）
   d. 依頼者・管理者にプッシュ通知（「○○さんが代打に応じました。候補を確定してください」）
      （ApplicationEvent: ShiftOpenCallClaimedEvent）

3. 依頼者または管理者が候補を確定（POST /swap-requests/{id}/select-claimer, accepter_user_id=確定する人）
   a. CLAIMED → ACCEPTED に遷移、accepter_id = claimed_by（通常）
   b. 管理者の裁量で accepter を別メンバーに差し替え可能（先着者がスキル不足等）
   c. 以降は通常フロー（4. 管理者承認）に合流

4. 管理者が承認（PATCH /swap-requests/{id}/approve）
   → A-2 と同一フロー

[並列発生時の競合対策]
- 手挙げ競合: 楽観ロック（@Version）+ SELECT FOR UPDATE の二重防御。DB 制約違反ではなくアプリ層で 409
- 依頼者が取下中に手挙げ: トランザクション内でステータス再確認（claim 処理の最初に status=OPEN_CALL を再検証）
- 依頼者と管理者が同時に select-claimer: 楽観ロックで先勝ち、後続は 409
```

### シフト変更依頼ワークフロー（A-1 確定前の依頼）【v2.1 新規】

**対象**: `DRAFT / COLLECTING / ADJUSTING` 状態のスケジュール。`PUBLISHED` 以降は A-2/A-3 の交代依頼フロー（`shift_swap_requests`）に案内する。

**スコープ境界（shift_swap_requests との使い分け）**:

| パターン | 対象ステータス | 使うテーブル | 承認者 | 影響 |
|---|---|---|---|---|
| A-1 確定前の変更依頼 | DRAFT / COLLECTING / ADJUSTING | `shift_change_requests` | スケジュール作成者（ADMIN / DEPUTY_ADMIN） | 調整作業の参考情報（実スロット変更は管理者が手動実施） |
| A-2 個別交代依頼 | PUBLISHED | `shift_swap_requests` | 管理者 | 承認で `assigned_user_ids` 自動差替 |
| A-3 オープンコール | PUBLISHED | `shift_swap_requests` | 管理者（`select-claimer` 後 `approve`）| 承認で `assigned_user_ids` 自動差替 |

```
1. メンバーが変更依頼を提出（POST /shifts/change-requests）
   a. 前提: スケジュール status ∈ {DRAFT, COLLECTING, ADJUSTING}
   b. 権限チェック（IDOR 防止）:
      - SWAP_SELF / CANCEL_SELF: 自分が slot.assigned_user_ids に含まれている必要あり
      - CHANGE_DATE / CHANGE_SLOT: 自分の割当スロットまたは自分に関する日付の依頼のみ
      - OTHER: slot_id = NULL のみ許可（特定他者のスロットは指せない）
      - 違反時 403
   c. レートリミット: 同一ユーザーが同一スケジュールに持てる OPEN 状態の依頼は 5 件まで（429）
   d. status = OPEN で INSERT
   e. 管理者全員（ADMIN + DEPUTY_ADMIN MANAGE_SHIFTS 保有者）にプッシュ + アプリ内通知
      （ApplicationEvent: ShiftChangeRequestCreatedEvent）
   f. 監査ログ: SHIFT_CHANGE_REQUEST_CREATED

2. 管理者が審査（PATCH /shifts/change-requests/{id}/review）
   ＜ACCEPTED のケース＞
   a. 管理者が先に D&D UI / PATCH /slots/{id}/assignments などで実スロットを調整
      （依頼内容に従って人を差し替え・別日へ移動・スロットから外す等）
   b. 作業完了後に「この依頼を受諾した」ボタンで PATCH /review を action=ACCEPTED で呼ぶ
   c. shift_change_requests.status = ACCEPTED, reviewed_by, reviewed_at, admin_note を記録
   d. 依頼者にプッシュ通知（「変更依頼が受諾されました」）
   e. 監査ログ: SHIFT_CHANGE_REQUEST_REVIEWED (ACCEPTED)

   ＜REJECTED のケース＞
   a. 管理者が admin_note に理由を記載して action=REJECTED を送信
   b. shift_change_requests.status = REJECTED
   c. 依頼者にプッシュ通知（理由込み）。ADHD 配慮で「却下理由が空欄のまま送信しない」旨を UI でガード
   d. 監査ログ: SHIFT_CHANGE_REQUEST_REVIEWED (REJECTED)

3. 依頼者本人が取下（DELETE /shifts/change-requests/{id}）
   a. status = WITHDRAWN に遷移（物理削除しない）
   b. 管理者に通知（未処理一覧から消える旨）
   c. 監査ログ: SHIFT_CHANGE_REQUEST_WITHDRAWN

4. PUBLISHED 遷移との関係
   a. PATCH /publish 実行時、同スケジュール内に status=OPEN の change_request があれば
      レスポンスの warnings 配列に「未処理の変更依頼 N 件」を含める（公開はブロックしない）
   b. 管理者は「未処理依頼ありでも公開する」or「却下してから公開する」を選択
   c. 公開後に残った OPEN 依頼は、フロント側で表示時に status を再チェック
      「公開済みのため変更依頼は受け付けられません。交代依頼をご利用ください」と案内

5. 期限切れの自動掃除（Phase 4 拡張）
   a. ARCHIVED に遷移したスケジュールに紐づく OPEN 依頼は、日次バッチで WITHDRAWN に自動変換
   b. 理由: 期限切れスケジュールへの依頼が溜まり続けるのを防ぎ、管理画面を清潔に保つ
   c. MVP では手動運用（管理者が一括却下する UI を提供予定）
```

**入力摩擦の最小化（ADHD 配慮）**:
- 理由テンプレート（チップ選択）: 「通院」「家族の用事」「大学の試験」「体調不良」「冠婚葬祭」「その他（自由記述）」
- `request_type` は選択式のセグメントコントロール（SWAP_SELF / CHANGE_DATE / CHANGE_SLOT / CANCEL_SELF / OTHER）
- `target_*` フィールドは「ヒントなのでわからなければ空欄で OK」と UI で明示
- 送信後の編集は不可（誤送信防止の取下ボタンのみ）。送信前の確認ダイアログ1ステップで確定

**オフライン対応（PWA）**:
- オフラインで依頼作成したときは `useOfflineQueue` で IndexedDB に退避。オンライン復帰時に自動送信。サーバ側で幂等キー（UUID）を `idempotency_key` として受け付け、重複 INSERT を防止（F11.1 連携。ヘッダ名は `Idempotency-Key`）

---

### 自動割当結果の目視確認フロー【v2.1 新規 / 最重要】

> ⚠️ **自動割当は提案に過ぎず、公開前に管理者が必ず目視で確認すること。** §1 冒頭の注意喚起ブロックで列挙したようにシステム外の文脈（人間関係・スキル・繁忙期・教育ペアリング等）は自動割当が拾えないため、最終判断は人間の管理者が担う。

```
1. 自動割当実行（POST /shifts/schedules/{id}/auto-assign）
   a. 結果は shift_assignment_runs に run レコード + shift_assignments に PROPOSED レコードで記録
   b. run.visual_review_confirmed_at は NULL（未確認状態）
   c. UI: 結果プレビュー画面の先頭に「⚠️ 自動割当結果です。目視で必ず確認してください」
      の赤系ハイライトバナーを表示（固定表示、閉じれない）
   d. 具体的にチェックすべき観点を UI でチェックリスト化（§1 と同じ 5 項目）:
      - □ 希望の背景事情（note 欄）と矛盾していないか
      - □ 人間関係・配置配慮に問題ないか
      - □ 繁忙期・臨時イベントに対応できているか
      - □ 有資格者が各時間帯に最低1名いるか
      - □ 新人教育のペアリング意図に沿っているか
   e. 管理者は D&D UI / /confirm / /auto-assign DELETE で微調整

2. 確定（POST /shifts/schedules/{id}/auto-assign/confirm）
   a. PROPOSED → CONFIRMED, shift_slots.assigned_user_ids へ反映
   b. この段階でも visual_review_confirmed_at は NULL のまま（確定 ≠ 目視確認）

3. 目視確認の記録（POST /shifts/assignment-runs/{runId}/confirm-visual-review）
   a. 管理者が確認チェックリストを全てチェックしたら「目視確認を承認」ボタンが活性化
   b. 確認時のメモ（visual_review_note）を任意で残せる
      例: 「17時台のホールに有資格者が不在だったため高橋を手動差替」
   c. shift_assignment_runs.visual_review_confirmed_by = 管理者ID
      shift_assignment_runs.visual_review_confirmed_at = NOW()
      shift_assignment_runs.visual_review_note = メモ
   d. ApplicationEvent: ShiftAssignmentVisualReviewConfirmedEvent
   e. 監査ログ: SHIFT_SCHEDULE_VISUAL_REVIEW_CONFIRMED

4. 公開（PATCH /shifts/schedules/{id}/publish）
   a. visual_review_acknowledged = true を必須（UI の確認ダイアログ「すべての割当を目視で
      確認しましたか？」に同意した証跡）
   b. **ゲート**: 自動割当履歴あり（shift_assignment_runs が 1 件以上）の場合、
      最新成功 run の visual_review_confirmed_at IS NOT NULL を必須チェック
      - 違反時: 409 Conflict, error.code = "VISUAL_REVIEW_REQUIRED"
      - クライアント側で目視確認 API に誘導（モーダル表示）
   c. 自動割当を 1 度も実行していないスケジュール（完全手動組み）はゲートをスキップ
      ただし UI の確認ダイアログは常に表示し、visual_review_acknowledged の送信は必須
   d. 公開時の warnings に VISUAL_REVIEW_NOTE（管理者メモ）を含めて運用ログに残す

5. 自動割当の再実行時
   a. POST /auto-assign を再度叩くと新 run が作成される → 新 run は目視未確認状態
   b. 既存 run の visual_review_confirmed_at は保持されるが、最新 run が未確認なら
      PUBLISHED 遷移は再度ブロック（確認のやり直しが必要）
   c. この挙動により「自動割当を再実行したが目視確認せず公開」の事故を構造的に防ぐ

[構造的な抜け道の塞ぎ]
- PATCH /slots/{id}/assignments（D&D）で大幅に構成を書き換えても、目視確認フラグはリセットしない
  → 代わりに UI 上で「自動割当後に X 件の手動変更があります」警告表示し、必要なら再度
    目視確認ボタンを押せるようにする（確認は冪等操作）
- 監査証跡: SHIFT_SCHEDULE_VISUAL_REVIEW_CONFIRMED は run_id 単位で記録されるため、
  「どの run を確認したか」が明確に追跡可能
```

---

### デフォルト可否プロファイルの自動適用

```
1. メンバーが週間デフォルト可否を事前登録（PUT /shifts/availability）
   例: 月曜=AVAILABLE（終日）、水曜午前=ABSOLUTE_REST（大学の授業）、日曜=WEAK_REST（できれば休み）

2. DRAFT → COLLECTING 遷移時に自動適用:
   a. 各メンバーの member_availability_defaults を取得
   b. スケジュール内の各スロットの slot_date から曜日を算出
   c. マッチする可否プロファイルがあれば shift_requests に初期値として INSERT
   d. 時間帯マッチング: プロファイルの start_time/end_time とスロットの start_time/end_time の重複判定
      - 終日プロファイル（start_time=NULL）: 全スロットにマッチ
      - 時間帯指定: スロットの時間帯がプロファイルの時間帯に完全包含される場合にマッチ
   e. マッチしない曜日・時間帯のスロットには初期値なし（メンバーが手動で提出）

3. フロントエンド:
   a. 希望提出画面で自動セットされた初期値をプリフィル表示
   b. メンバーは初期値を自由に変更可能（プロファイルと異なる希望も提出可）
   c. 「すべて AVAILABLE で一括登録」ボタンも提供（プロファイル未設定のメンバー向け）
```

### 希望提出画面 UI（v2 5段階対応）

ADHD 傾向のあるメンバーも入力摩擦なく提出できるよう、以下の設計とする:

1. **ラジオカード UI（PrimeVue SelectButton）**: 各スロット行に「絶対休み / できれば休み / 出れなくはない / 指定なし / 出勤希望」の5択を横並びのカラーチップで表示
   - 色: ABSOLUTE_REST=赤、STRONG_REST=橙、WEAK_REST=黄、AVAILABLE=灰、PREFERRED=緑
   - タップ1回で選択完了（モーダル・セカンドステップなし）
   - 長文の説明は `<Tooltip>` に押し込む（画面をごちゃつかせない）
2. **初期値優先**: デフォルト可否プロファイルがあれば自動適用 → プリフィル。メンバーは差分のみ変更
3. **一括操作**:
   - 「全て指定なし」「全て絶対休み」「全て出勤希望」ボタンで1タップ反映
   - 曜日選択で「月曜のみ絶対休み」等の週ループ系ショートカット
4. **note（補足）**:
   - デフォルトで折りたたみ。「メモを追加」タップで展開
   - STRONG_REST / ABSOLUTE_REST 選択時のみ、「理由を共有しますか？」と柔らかく促す（任意、未入力でも提出可）
5. **提出確定前の確認**:
   - 提出サマリー表示（「出勤希望 3日、絶対休み 2日」）
   - 「提出」ボタン1回で完了。後から編集可能（COLLECTING 中）
6. **ダミー入力防止**:
   - 未入力スロットが多い場合、「12枠中3枠のみ提出済み。他は指定なしとして扱いますか？」と確認
   - ここで「全て指定なしで扱う」を選べば1タップで残りを AVAILABLE として INSERT
7. **モバイルファースト**:
   - 各スロットカードをフルワイドで縦並び。5択チップは幅均等
   - 指で誤タップしないよう、タップターゲット最小 44×44 px（WCAG 準拠）
8. **オフライン提出**:
   - PWA 起動中は F11.1 `useOfflineQueue` で提出データを IndexedDB に退避
   - オンライン復帰時に自動再送信。失敗時は画面に通知

### 連勤・過剰勤務の警告

```
1. PATCH /publish 実行時（公開直前）に自動チェック
2. チェック項目:
   a. 連勤チェック: 割り当て済みメンバーの同一チーム PUBLISHED シフトを過去・未来方向に検索し、
      連続勤務日数を算出。閾値（デフォルト: 5連勤）を超える場合は警告
   b. 週間労働時間チェック: 同一週（月曜起算）の合計勤務時間を算出。
      閾値（デフォルト: 40時間）を超える場合は警告
   ※ 深夜跨ぎスロットの勤務時間は2日にまたがる実時間で計算
3. 警告レスポンス（warnings 配列に追加。公開はブロックしない）:
   {
     "type": "CONSECUTIVE_DAYS",
     "user": { "id": 10, "display_name": "田中太郎" },
     "consecutive_days": 6,
     "date_range": "2026-03-07 〜 2026-03-12",
     "message": "6連勤になります"
   },
   {
     "type": "WEEKLY_HOURS_EXCEEDED",
     "user": { "id": 11, "display_name": "佐藤花子" },
     "weekly_hours": 45.5,
     "week": "2026-03-09 〜 2026-03-15",
     "message": "週40時間を超過します（45.5時間）"
   }
4. 閾値はチーム設定としてハードコード（Phase 3）。将来的にチーム設定画面で変更可能にする
```

### 希望収集リマインド強化（v2 詳細化）

```
1. 送信トリガー（バッチスキャン間隔: 10分ごと、@Scheduled(fixedDelay)）:
   a. 48時間前リマインド【v2 新規】: request_deadline から 48h 前で、is_reminder_sent_48h = FALSE
      かつ提出率 < 100% のメンバーに送信
   b. 24時間前リマインド（既存）: request_deadline から 24h 前で、is_reminder_sent = FALSE
      かつ提出率 < 100% のメンバーに送信
   c. 管理者向け低提出率アラート（既存）: 48h 前で提出率 < 50% の場合、管理者にアラート
      （is_low_submission_alerted で制御）
2. 送信対象の絞り込み:
   a. チーム内の対象メンバー（MEMBER 以上）のうち、当該 schedule_id に対する shift_requests が
      0件のメンバーのみ。ABSOLUTE_REST のみ提出したメンバーも「提出済み」と見なす
   b. SUPPORTER・GUEST は対象外
   c. 個別メンバーのリマインド有効/無効フラグ（F04.3 通知設定）で無効化されている場合は送信しない
3. 通知チャネル:
   a. プッシュ通知（F04.3 連携。`NotificationType.SHIFT_REMINDER`）
   b. アプリ内通知（未読バッジ + 通知センター）
   c. メール通知は MVP では送らない（将来的にチーム設定で ON/OFF）
4. 冪等性:
   - shift_schedules に `is_reminder_sent`（24h 前用、既存）・`is_reminder_sent_48h`（48h 前用、v2 新規）を保持
   - バッチは UPDATE + フラグセットを1トランザクションで実行
   - 同一ユーザーに対して同一 schedule の同一区間リマインドは必ず1回のみ
5. バッチ設計:
   a. Spring Scheduler `@Scheduled(fixedDelay = 10分, initialDelay = 1分)` で稼働
   b. 処理対象ロック: `SELECT ... FOR UPDATE SKIP LOCKED` で複数サーバ並列実行時の二重送信防止
   c. 送信失敗時（プッシュ基盤エラー等）: ERROR ログ出力 + 次回バッチで再試行（フラグはセットしない）
   d. バッチ1回あたりのメトリクス（対象件数・成功件数・失敗件数）を application log に出力
6. 手動リマインド（既存）: POST /shifts/schedules/{id}/remind
   a. COLLECTING 状態でのみ実行可能
   b. 未提出メンバー（shift_requests が0件のメンバー）にプッシュ通知を送信
   c. レートリミット: 同一スケジュールに対して1時間に1回まで（スパム防止）
   d. ApplicationEvent: ShiftManualReminderEvent
7. 管理者向け未提出アラート（既存）:
   a. request_deadline の48時間前の時点で提出率が50%未満の場合、管理者にプッシュ通知
     （「シフト希望の提出率が {rate}% です。未提出者: {count}名」）
   b. バッチ条件: status = 'COLLECTING' AND request_deadline BETWEEN NOW() + INTERVAL 24 HOUR AND NOW() + INTERVAL 48 HOUR
      かつ提出率 < 50%
   c. 通知は1回のみ（shift_schedules に is_low_submission_alerted BOOLEAN DEFAULT FALSE を追加）
8. リマインド間隔のカスタマイズ【Phase 4】:
   - チーム設定画面で「48h 前・24h 前・12h 前」のいずれかの組み合わせを選択可能にする
   - MVP は 48h + 24h 固定（チーム毎の差別化なし）
```

### 自動割当アルゴリズム（Strategy パターン）【v2 新規】

管理者が `POST /shifts/schedules/{id}/auto-assign` を叩くと、指定された戦略でスロットにメンバーを自動割当する。MVP では貪欲法（スコアリング方式）を採用し、将来 CSP ソルバへ差し替え可能なよう Strategy パターンで抽象化する。

#### 戦略 interface

```java
// backend/src/main/java/com/mannschaft/app/shift/assignment/ShiftAssignmentStrategy.java
public interface ShiftAssignmentStrategy {

    String getStrategyType();  // "GREEDY_V1" / "CSP_V1" ...

    AssignmentResult assign(AssignmentContext ctx);
}

public record AssignmentContext(
    Long scheduleId,
    List<ShiftSlot> slots,
    List<ShiftRequest> requests,
    List<MemberWorkConstraint> constraints,
    Map<Long, List<ShiftSlot>> existingAssignmentsByUser,  // 当月の既存確定シフト（連勤計算用）
    AssignmentParameters parameters
) {}

public record AssignmentResult(
    List<AssignmentProposal> proposals,  // PROPOSED として shift_assignments へ書き込む
    List<AssignmentWarning> warnings
) {}
```

#### 貪欲法 `GreedyShiftAssignmentStrategy`（MVP）

```
1. 事前準備:
   a. スロットを日付×開始時刻順にソート
   b. 各 user について現在の月次労働時間・夜勤回数・直近連勤を集計
   c. ABSOLUTE_REST のリクエストを持つ (user, slot) の組をブラックリストに登録

2. スロット単位のループ:
   for slot in sortedSlots:
     candidates = チームメンバー全員
       - ブラックリスト除外
       - 既に他スロットで同一時間帯に割当済みのユーザーを除外（時間帯重複）
       - ハード制約（respect_work_constraints = true のときの全制約）違反を除外
     score each candidate by:
       preference_score = スコア表の値 * preference_weight
       fairness_score   = -(月次夜勤回数 - チーム平均夜勤回数) * fairness_weight
       consecutive_score = -(直近連勤日数^2) * consecutive_penalty_weight
       constraint_score = ソフト制約マージンに基づくペナルティ
     total = preference_score + fairness_score + consecutive_score + constraint_score

     top_n = 上位 slot.required_count 件を選択
     if top_n 件未満:
       warning.vacancy に登録

     各候補について shift_assignments に PROPOSED レコード INSERT

3. 事後処理:
   a. 集計警告を生成（欠員合計・制約違反合計）
   b. shift_assignment_runs に status = SUCCEEDED / warnings_json を書き込み

4. 同点処理:
   - total スコア同点の場合は (既存割当回数が少ない user_id 昇順、それも同じなら user_id 昇順) の決定論的タイブレーク
   - 再現性を保つため乱数は使わない
```

#### パフォーマンス目標

- **1チーム100名 × 1ヶ月 × 100スロット**: 3秒以内に完了
- 計測方法: `shift_assignment_runs.duration_ms` を監視。95% パーセンタイルが 3,000ms を超えたらアラート
- 最悪計算量: `O(slots × users × constraints)` ≒ `100 × 100 × 10 = 10^5` 規模 → 単一プロセスで十分

#### エッジケース

| ケース | 挙動 |
|---|---|
| 全候補が ABSOLUTE_REST を提出 | 当該スロットは欠員。warning に `VACANCY` を1件追加 |
| 必要人数が集まらない（候補不足） | 割当可能な分だけ PROPOSED で書き込み、不足分は VACANCY |
| 同点複数候補 | 決定論的タイブレーク（上記） |
| ハード制約で全員除外される | 当該スロットは欠員。warning に `VACANCY` + `CONSTRAINT_INFEASIBLE` |
| respect_work_constraints = false | 制約違反は警告のみ、割当は成立させる |
| overwrite_existing = true | 既存 CONFIRMED 割当も候補対象にリセットし再計算 |
| チームメンバー0人 | 400 エラー（strategy 実行前に検知） |
| 退職済みユーザーが shift_requests を持つ | 候補から除外（メンバーシップを再確認） |
| 兼務者（複数チーム所属） | 他チームの PUBLISHED シフトとの時間帯重複を除外（§5.7 クロスチーム重複チェックを流用） |
| スロットが0件 | 400 エラー |

#### 将来の CSP 差し替え

- `CspShiftAssignmentStrategy` を `ShiftAssignmentStrategy` 実装クラスとして追加するだけで切り替え可能
- Choco-solver などの Java 製 CSP ライブラリ想定。ハード制約は制約伝播、ソフト制約は目的関数の重みで表現
- interface の AssignmentContext / AssignmentResult は変えない（後方互換）
- 戦略ごとに異なるパラメータは `AssignmentParameters` を拡張（既存貪欲用フィールドは NULL 可能でオプショナル）

### D&D シフト編集 UI（v2 新規）

#### ライブラリ選定

| 候補 | 採用 | 理由 |
|---|---|---|
| `vuedraggable` (vue.draggable.next) | **第一候補（採用）** | Vue 3 互換、アクティブメンテ、PrimeVue 4 と共存可能、Sortable.js ベースで touch イベント対応済、npm 週間 DL 50万超 |
| `@formkit/drag-and-drop` | 代替（第二候補） | 軽量（2KB）、モダン API、PrimeVue との相性も良好。将来 vuedraggable が停滞した場合のバックアップ |
| `vueuse useDraggable` | 低レベル API | 細かな制御が必要な場合のみ。MVP では採用しない |

#### 画面設計

```
[ヘッダ] スケジュール選択 | [自動割当] [確定] [破棄] [前回コピー]
┌──────────────────────────────────────────────────────┐
│  日付 / 時間帯 │ ホール (3名) │ キッチン (2名) │ レジ (1名) │
├──────────────────────────────────────────────────────┤
│ 3/9(月) 11-15  │ [田中] [佐藤] │ [鈴木] [山田] │ [加藤]    │
│           15-18│ [田中] [欠員] │ [鈴木]        │ [加藤]    │
│ 3/10(火) 11-15 │ [佐藤] [高橋] │ [山田]        │ [加藤]    │
└──────────────────────────────────────────────────────┘
[右ペイン] 未割当メンバー一覧（希望強度・月次残時間・連勤数を表示）
```

- **軸**: 行 = 日付×時間帯（時間帯分割スロット対応）、列 = ポジション
- **セル**: 割り当てられたメンバーを chip で表示。D&D で列間・行間を移動できる
- **右ペイン**: 未割当メンバーのプール。ここから各セルへドラッグで追加

#### インタラクション

| 操作 | 挙動 | API |
|---|---|---|
| メンバー chip をセル間にドラッグ | 移動元から削除、移動先に追加 | PATCH /slots/{fromId}/assignments (remove) + PATCH /slots/{toId}/assignments (add)。2リクエストを順次発行 |
| 右ペインからセルへドラッグ | セルに追加 | PATCH /slots/{id}/assignments (add_user_ids) |
| セルからメンバーを削除（x ボタン or 右ペインへ戻す） | セルから削除 | PATCH /slots/{id}/assignments (remove_user_ids) |
| 「自動割当」ボタン | モーダルで strategy とパラメータを選択 → 実行 | POST /schedules/{id}/auto-assign |
| 「確定」ボタン | 提案を確定 | POST /schedules/{id}/auto-assign/confirm |
| 「破棄」ボタン | 提案を破棄 | DELETE /schedules/{id}/auto-assign |

#### 楽観的 UI 更新

1. ユーザーがドラッグで移動した瞬間、**クライアント側で即座に UI を更新**（useShiftBoard composable でローカル状態を進める）
2. 並行して PATCH /slots/{id}/assignments を発行
3. **成功時**: slot_version を更新、UI はそのまま
4. **失敗時（409 / 制約違反）**:
   - ABSOLUTE_REST 違反（409）: **ロールバック**（UI を元に戻す）。PrimeVue Toast で「田中太郎さんは3/9を絶対休みとして提出しています」と表示
   - ソフト制約警告（200 with warnings）: UI はそのまま、ただし chip に黄色ハイライト + ツールチップで警告内容を表示
   - 楽観的ロック競合（409）: スロットを再取得して UI を最新状態に同期、Toast で「他の管理者が先に編集しました。最新の状態を表示しています」

#### モバイル対応（PWA）

- `vuedraggable` は Sortable.js の touch イベントに依存するため、iOS Safari / Android Chrome で動作する
- **タップ長押し 300ms** でドラッグ開始（スクロールと誤操作しないように `delay` を設定）
- セル幅は横スクロール前提。デバイス幅で1〜2ポジションを表示
- `touch-action: none` を D&D エリアに設定してブラウザのスクロールと干渉させない
- オフライン時: サービスワーカーで `IndexedDB キュー` に PATCH を溜め、オンライン復帰時に送信（F11.1 `useOfflineQueue` を活用）。競合時は最新を取得して UI 再構築

#### アクセシビリティ

- D&D のみに頼らず、**キーボード操作の代替** を提供:
  - セルフォーカス時に Tab → スペースキーでメンバー選択 → 矢印キーで移動先セル → Enter で確定
  - vuedraggable の `handle` で明示的にドラッグハンドルを指定
- スクリーンリーダー向けに `aria-label`「3月9日 ホール 11時〜15時 割当済み: 田中太郎, 佐藤花子」を各セルに付与

### 任意の勤務制約（v2 新規）

`member_work_constraints` テーブルで定義される制約を、以下のポイントで参照する。

#### 制約の種類と判定

| 制約 | 判定ロジック | 違反時の扱い |
|---|---|---|
| `max_monthly_hours` | 当月の PUBLISHED + PROPOSED / CONFIRMED 割当の合計時間 | ソフト制約（警告） |
| `max_monthly_days` | 当月の PUBLISHED + PROPOSED / CONFIRMED 割当日数の DISTINCT COUNT | ソフト制約（警告） |
| `max_consecutive_days` | 前後の割当を連続性判定し連続勤務日数を算出 | ソフト制約（警告） |
| `max_night_shifts_per_month` | 当月の夜勤スロット数（start_time >= 22:00 OR end_time <= 06:00） | ソフト制約（警告） |
| `min_rest_hours_between_shifts` | 前シフト end_time と次シフト start_time の差を時間換算 | ソフト制約（警告） |

- **ハード制約**: `ABSOLUTE_REST` のみ（それ以外は全てソフト）
- **respect_work_constraints=true** の自動割当時は、全制約を**除外条件**として扱う（候補から完全に除外）
- **respect_work_constraints=false** の自動割当時は、スコアペナルティとして加算（`-1000 * 違反係数` 等）

#### 解決順序

1. メンバー個別レコード（`user_id = {userId}`）が存在すれば優先
   - 各項目が NULL なら「この項目はチームデフォルトに従う」ではなく「この項目は制約なし」と解釈する（個別オーバーライド完結）
2. 個別レコードがなければチームデフォルト（`user_id IS NULL`）を適用
3. どちらもなければ制約なし（従来どおりのフリー割当）

#### 反映ポイント

| シーン | 反映方法 |
|---|---|
| 希望提出画面 | メンバー本人の制約を画面上部に表示（「月80時間まで / 連勤5日まで」）。超過しそうな場合は UI でソフト警告 |
| 自動割当 | 上記アルゴリズム内で候補フィルタ + スコアリング |
| D&D 手動割当 | PATCH /slots/{id}/assignments のレスポンス warnings に制約違反を含める |
| 公開時チェック | PATCH /publish の警告に全制約違反を集約 |

#### 既存の連勤・週40時間警告との関係

- 既存（§`連勤・過剰勤務の警告` セクション）は**チーム一律のハードコード閾値**だった（連勤5・週40h）
- v2 ではこれらを `member_work_constraints` に置き換える:
  - 従来のハードコード値は「MVP のデフォルト値」として `V3.081__seed_default_work_constraints.sql` でチームデフォルトとして INSERT（任意、ON/OFF フラグはチーム設定で将来追加）
  - `max_consecutive_days = 5`、`max_monthly_hours` は 160.00（週40h × 4週）を初期値とする
- 既存チームについては**マイグレーション時点では INSERT しない**（影響を広げないため）。チーム管理者が明示的に設定して初めて適用されるオプトイン方式

### ステータス遷移イベント統一

```
1. 共通親イベント: ShiftStatusChangedEvent
   - scheduleId: Long
   - previousStatus: String
   - newStatus: String
   - triggeredBy: Long（userId。バッチの場合は NULL）
   - occurredAt: Instant

2. 個別イベントは ShiftStatusChangedEvent を継承:
   - ShiftCollectionStartedEvent（DRAFT → COLLECTING）
   - ShiftPublishedEvent（ADJUSTING → PUBLISHED）
   - ShiftScheduleDeletedEvent（論理削除）

3. リスナー側:
   - 通知リスナー: ShiftStatusChangedEvent を購読し、遷移パターンに応じて通知内容を分岐
   - 監査ログリスナー: ShiftStatusChangedEvent を購読し、全遷移を記録
   - Google Calendar リスナー（将来）: ShiftPublishedEvent のみ購読

4. 利点: 新しいステータス遷移を追加しても、イベントクラスの新規作成なしで
   通知・監査ログが自動的に対応する
```

### 自動遷移バッチの冪等性保証

```
1. shift_schedules に last_auto_transition_at DATETIME NULL を追加
2. バッチ処理フロー:
   a. 対象レコード取得:
      status = 'COLLECTING'
      AND request_deadline < NOW()
      AND (last_auto_transition_at IS NULL OR last_auto_transition_at < request_deadline)
   b. ステータス更新: COLLECTING → ADJUSTING
   c. last_auto_transition_at = NOW() を記録（同一トランザクション）
   d. 通知送信: ApplicationEvent 発行（@TransactionalEventListener で分離）
3. 障害時のリカバリ:
   - ステータス更新成功 + 通知失敗: last_auto_transition_at が更新済みのため
     次回バッチでは対象外（通知の再送は運用対応）
   - ステータス更新失敗: last_auto_transition_at が未更新のため次回バッチで再試行
4. 通知送信をイベント駆動（@TransactionalEventListener(AFTER_COMMIT)）にすることで、
   ステータス更新のトランザクションと通知送信を疎結合にする
```

### 給与概算の計算ロジック

```
1. GET /shifts/my のレスポンスに給与概算を付与:
   a. 各 confirmed_shift のスロットから勤務時間を算出:
      - 通常スロット: (end_time - start_time) を時間単位に変換
      - 深夜跨ぎスロット: (24:00 - start_time) + (end_time - 00:00)
        例: 22:00-06:00 → 2h + 6h = 8h
   b. slot_date 時点の適用時給を取得:
      SELECT hourly_rate FROM shift_hourly_rates
      WHERE user_id = {userId} AND team_id = {teamId}
        AND effective_from <= {slot_date}
      ORDER BY effective_from DESC LIMIT 1
   c. amount = hours × hourly_rate（小数点以下切り捨て）
   d. pay_summary: 取得期間内の全スロットの合計

2. セキュリティ:
   - 給与概算は本人の GET /shifts/my でのみ表示
   - 管理者はスケジュール詳細や summary で他メンバーの時給を参照可能（人件費概算）
   - 他メンバーの GET /shifts/my を参照する API は存在しない（IDOR 防止）

3. 管理者向け人件費概算:
   - GET /shifts/schedules/{id}/summary のレスポンスに以下を追加（ADMIN/DEPUTY_ADMIN のみ）:
     "labor_cost_estimate": {
       "total_hours": 120.0,
       "total_amount": 144000.00,
       "members_without_rate": 2
     }
   - 時給未設定メンバーの分は概算に含めず、未設定人数を返却
```

### 重要な判定ロジック

- **シフト管理権限**: ADMIN or DEPUTY_ADMIN（`MANAGE_SHIFTS` 権限）
- **希望提出権限**: MEMBER 以上（SUPPORTER は閲覧のみ）
- **希望編集可能期間**: `COLLECTING` 状態 かつ `request_deadline` 前のみ。`ADJUSTING` 以降は変更不可。`request_deadline` が過去の場合はステータスが `COLLECTING` でも提出・編集・取り下げ不可（自動遷移バッチ実行前の猶予期間中の操作を防止）
- **スロット追加可能期間**: `DRAFT` / `COLLECTING` / `ADJUSTING` 状態で可能。`PUBLISHED` / `ARCHIVED` では不可
- **COLLECTING 中のスロット削除**: FK CASCADE により紐づく `shift_requests` も連動削除される。該当スロットに希望を提出済みのメンバーには通知を送信し、再提出を促す
- **公開の前提条件**: `ADJUSTING` 状態のみ。全スロットの割り当て完了は推奨だが必須ではない（欠員公開可能）
- **PUBLISHED 後の変更**: `assigned_user_ids` と `note` のみ変更可能。変更時は該当メンバーに再通知
- **深夜跨ぎスロット**: `end_time < start_time` の場合は翌日跨ぎとして解釈。表示はフロントエンド側で対応
- **同一時間帯の重複割り当て防止**: 同一ユーザーが同一日の重複する時間帯に割り当てられた場合は警告表示（エラーにはしない。管理者判断で許可）
- **`assigned_user_ids` 要素数の上限**: `required_count` を超える人数の割り当ては許可するが、超過時は UI で「過剰割り当て」として警告表示。バリデーションエラーにはしない（ヘルプ人員の追加等、管理者判断に委ねる）
- **スケジュール一覧の可視範囲**:
  - ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）: 全ステータス表示
  - MEMBER: `COLLECTING`（希望提出のため）および `PUBLISHED`（確定シフト閲覧）のみ表示。`DRAFT` / `ADJUSTING` は非表示
  - SUPPORTER: `PUBLISHED` のみ表示
  - バックエンドで可視範囲をフィルタリングし、権限外ステータスのスケジュール詳細アクセスは 403 を返却
- **【v2.3】希望強度5段階の重み**: PREFERRED=+100, AVAILABLE=0, WEAK_REST=-30, STRONG_REST=-80, ABSOLUTE_REST=-∞（割当不可）。ABSOLUTE_REST は自動割当でも手動割当でもハード制約として扱い、409 Conflict で拒否。Phase 1 MVP 実装値に合わせて +100/-30 に上方修正（旧 v2.2 は +50/-20）。Phase 2 自動割当の実機テスト結果を踏まえ動的重み調整の余地を残す
- **【v2】自動割当の実行権限**: ADMIN / DEPUTY_ADMIN（MANAGE_SHIFTS）のみ。ADJUSTING 状態のみ実行可能。同一スケジュールに対して `RUNNING` 状態の run が既にある場合は 409 でブロック（並行実行禁止）
- **【v2】自動割当の確定/破棄**: PROPOSED 状態の shift_assignments は shift_slots.assigned_user_ids には反映されない。確定操作（/auto-assign/confirm）で初めて反映。確定前に DELETE /auto-assign で破棄するとスコアリング結果は REVOKED 化されるが、run 履歴は残る（監査証跡）
- **【v2】勤務制約（任意）**: `member_work_constraints` テーブルが全項目 NULL 可能。設定されていない制約は単純に無視。個別レコード > チームデフォルト > 制約なし の解決順序
- **【v2】時間帯分割スロット**: 同一 `schedule_id + slot_date + position_id` に複数レコード INSERT 可能。`start_time / end_time` が完全重複する場合のみ 409 エラー。部分重複（例: 11-15 と 14-18）は意図的配置として許容
- **【v2】D&D UI のハード制約違反**: PATCH /slots/{id}/assignments は ABSOLUTE_REST ユーザーを add_user_ids に含めた場合 409 を返す。クライアントは UI をロールバックし Toast で通知

### シフト表PDF出力フロー【v2.2 新規】

`GET /api/v1/shifts/schedules/{id}/pdf` のサーバ側処理。F12.1 PDF 生成共通基盤（Flying Saucer + Thymeleaf + OpenPDF）を使用した**同期生成**を採用する。署名付き URL / 生成ジョブキューは現行 F12.1 の方針に従い採用しない（§9 未来拡張に非同期化の余地のみ残す）。

```
[前提チェック]
0. Bearer 認証 → 認証ユーザー userId 取得
1. schedule = shiftScheduleRepository.findByIdAndNotDeleted(scheduleId)
   - 未発見 → 404
2. AccessControlService.checkTeamMember(userId, schedule.teamId)
   - 他チーム → 404（IDOR 情報隠蔽）
3. ロール / layout に応じた認可分岐
   - layout=team: checkAdminOrAbove(userId, teamId, "TEAM") で 403 判定
   - layout=personal:
     - MEMBER の場合: member_id 未指定 → 自分、member_id 指定 != userId → 403
     - ADMIN/DEPUTY_ADMIN: member_id 任意
     - SUPPORTER / GUEST: 常に 403
4. include_draft_watermark=true は ADMIN/DEPUTY_ADMIN のみ。MEMBER 指定で 403
5. status ∈ {DRAFT, COLLECTING, ADJUSTING} かつ include_draft_watermark=false → 409 SHIFT_PDF_001（PUBLISHED と ARCHIVED は通常 PDF として出力可）
6. layout=personal で対象メンバーの期間内スロットがゼロ件 → 400（空PDF防止）
7. レートリミット: 1ユーザー1分10件（Bucket4j）

[データ取得]
8. 該当期間の shift_slots を slot_date, start_time 昇順で取得（position JOIN 済）
9. layout=team: team の active メンバー一覧取得（並び順はメンバー表示順）
10. layout=personal: 対象 member のみに絞り込み、合計勤務時間を算出

[テンプレート変数組み立て]
11. 含める情報（§4 §6 参照。個人情報は厳格に除外）
    - team: id / name
    - schedule: title / startDate / endDate / status / publishedAt / version（「v1 - 2026-04-23 公開」文字列組立）
    - slots: 日付×メンバーの 2 次元マトリクス（team）、または時系列配列（personal）
    - shortageCells: 必要人数未達スロットのリスト（欠員 N 名表記用）
    - customNote: 管理者が schedule.note に記載したカスタム文言。空の場合は既定文
    - generatedAt: 生成日時（Asia/Tokyo）
    - generatedBy: { id, displayName }（認証ユーザー）
    - locale: 解決後のロケール（Accept-Language or クエリ）
    - watermarkText: "内部確認用" / "CONFIRMATION ONLY"（ロケール別、非該当時は null）
    - i18nMessages: Thymeleaf MessageSource 経由で解決した全ラベル（§5 i18n キー一覧参照）

[PDF 生成]
12. pdfGeneratorService.generateFromTemplate(
        layout == TEAM ? "pdf/shift-team-matrix" : "pdf/shift-personal-timeline",
        vars
    ) → byte[] pdfBytes
13. watermarkText != null の場合、OpenPDF の PdfStamper で全ページ中央に 45° 回転・半透明テキスト描画（F12.1 §7.2 方式 A）
14. PdfSizeGuard.check(pdfBytes)（F12.1 §11 のサイズ上限 10MB 超過で PDF_005）

[ファイル名 / レスポンス]
15. fileName = PdfFileNameBuilder.of("シフト表"|"個人シフト")
        .date(LocalDate.now()).identifier(team/memberName + "_" + yyyyMMdd + "-" + yyyyMMdd)
        .build()（F12.1 §4.2）
    - include_draft_watermark=true なら identifier 末尾に "_内部確認用" を連結
16. 監査ログ SHIFT_PDF_EXPORTED を記録（§6 参照）
17. PdfResponseHelper.toResponse(pdfBytes, fileName) で ResponseEntity<byte[]> 返却
    + Cache-Control: private, no-store / X-Frame-Options: DENY を明示追加
```

**レイアウトパターン（v2.2 MVP は 2 種）**

| layout 値 | テンプレート | 用途 | 主な UI 要素 |
|---|---|---|---|
| `team` | `pdf/shift-team-matrix.html` | 全体シフト表（壁貼り・朝礼配布・紙運用） | A4 横向き。横軸=日付（曜日併記）、縦軸=メンバー氏名。セルは「開始-終了 / ポジション短縮名」。欠員は赤背景に `✕ 欠員N名` |
| `personal` | `pdf/shift-personal-timeline.html` | 個人への手渡し / メール添付配布 | A4 縦向き。先頭に対象者氏名・期間・合計勤務時間サマリ。以降に日付単位で時系列（当日ゼロ件は「休」セル） |

将来拡張候補（v2.2 時点では非対応、§9 未来拡張に明記）:
- 日別詳細（1日1ページ、時間帯×メンバーの詳細表）
- ポジション別（職種ごとに 1 ページ、当該職種メンバーのみ）
- 全員分の個人シフトをまとめて ZIP ダウンロード
- 非同期生成＋署名付 URL でのダウンロード準備完了通知

**PDF テンプレート設計要点（F12.1 §7 CSS 制限事項に準拠）**

- Flying Saucer は CSS 2.1 のみサポート。`transform` / `rgba()` / `flexbox` / `grid` / `border-radius` は使用不可。レイアウトは `table` + `float` + `position: absolute/relative` で構成する
- A4 横向き（`layout=team`）は `@page { size: A4 landscape; margin: 10mm 10mm; }` で指定
- 多ページ時の改ページ: チームマトリクスはメンバー行数に応じて自然改ページ（30 名/ページ目安）。個人タイムラインは日付単位で `page-break-inside: avoid` を指定し日内項目の分断を回避
- `pdf-common.css` の `NotoSansJP` を継承。PDF 埋め込みフォントとして確実に出力（非埋め込み警告を防ぐ）
- 必要人数未達セルは `.shift-shortage`（赤背景 / 白文字）クラスで明示。色覚多様性配慮として `✕` 記号を必ず併用
- ウォーターマークは方式 A（OpenPDF 後処理）で全ページ共通オーバーレイ。Thymeleaf テンプレート内部では watermark を描画しない（F12.1 §7.2 制限への対応）
- 日付の曜日・時刻表示は `#temporals.format()`（Thymeleaf Java8Time Dialect）で `i18nMessages.get(locale)` の書式トークンに従い描画

**i18n**

PDF ヘッダー・ラベル・注意書き既定文は 6 言語対応（§5 i18n 注記参照）。メンバー氏名は DB の表記をそのまま使用（言語切替しない）。使用 i18n キー（ServerSide Thymeleaf MessageSource 経由、`backend/src/main/resources/messages_*.properties`）:

| キー | 用途 | ja 例 |
|---|---|---|
| `shift.pdf.team.title` | チーム表タイトル | シフト表 |
| `shift.pdf.personal.title` | 個人表タイトル | 個人シフト |
| `shift.pdf.period` | 期間ラベル | 期間 |
| `shift.pdf.team_name` | チーム名ラベル | チーム名 |
| `shift.pdf.version` | 版ラベル（例: v1 - 2026-04-23 公開） | 版 |
| `shift.pdf.member` | メンバー列見出し | メンバー |
| `shift.pdf.date` | 日付列見出し | 日付 |
| `shift.pdf.position` | ポジション | ポジション |
| `shift.pdf.time_range` | 時間帯 | 時間帯 |
| `shift.pdf.shortage` | 欠員表示 | 欠員 |
| `shift.pdf.off_day` | 非勤務日表示 | 休 |
| `shift.pdf.total_hours` | 合計勤務時間 | 合計 |
| `shift.pdf.generated_at` | 生成日時 | 発行日時 |
| `shift.pdf.generated_by` | 生成者 | 発行者 |
| `shift.pdf.signature` | 管理者署名欄 | 管理者署名 |
| `shift.pdf.note.default` | 既定注意書き | 本シフトは {date} 時点の確定版です。変更がある場合は追って連絡します。 |
| `shift.pdf.watermark.draft` | ウォーターマーク文字 | 内部確認用 |
| `shift.pdf.page_n_of_m` | ページ番号 | {n} / {m} ページ |

> 初期実装ではフロント i18n（`frontend/app/locales/*`）の `common.json` にもボタン文言のみ `shift.pdf.download`（例: 「PDF出力」）等を追加する。PDF 本文文字列はサーバ MessageSource 側で管理し、フロント i18n とは分離する（F01.8 §5.5 の設計思想に揃える）。未翻訳言語は日本語値をそのまま暫定登録（CLAUDE.md i18n ルール準拠）

**フロントエンド UI（v2.2 MVP）**

| 要素 | 仕様 |
|---|---|
| エントリーポイント | 確定シフト画面（`frontend/app/pages/shifts/[id]/index.vue` を想定）の右上ツールバーに「PDF出力」スプリットボタン |
| ドロップダウン項目 | 「チーム表（マトリクス）」/「個人表（自分）」。ADMIN/DEPUTY_ADMIN には追加で「個人表（メンバー選択…）」（選択モーダル表示） |
| ウォーターマーク付き出力 | `status != PUBLISHED` の場合のみドロップダウン最下部に「内部確認用PDF出力（未公開）」を別項目で表示（ADMIN/DEPUTY_ADMIN 限定） |
| 進捗表示 | クリック → ボタン内スピナー表示 → 受信完了で Blob ダウンロード（`a.download = '' + Content-Disposition のファイル名使用`）。タイムアウト 20 秒 |
| モバイル対応 | `Blob` から `URL.createObjectURL` → `<a>` クリックで OS 既定 PDF ビューア起動（iOS Safari / Android Chrome 両対応）|
| 将来拡張ヒント | 全員分一括 ZIP は「開発中」グレーアウト項目としてプレースホルダ表示（§9 未来拡張 connect） |

**i18n キー（フロント `common.json`）**

| キー | ja | en |
|---|---|---|
| `shift.pdf.button` | PDF出力 | Export PDF |
| `shift.pdf.button.team` | チーム表（マトリクス） | Team matrix |
| `shift.pdf.button.personal_self` | 個人表（自分） | My schedule |
| `shift.pdf.button.personal_other` | 個人表（メンバー選択…） | Personal (select member…) |
| `shift.pdf.button.draft_watermark` | 内部確認用PDF出力（未公開） | Internal review PDF (unpublished) |
| `shift.pdf.downloading` | PDFを生成中… | Generating PDF… |
| `shift.pdf.error` | PDFの生成に失敗しました | Failed to generate PDF |

zh / ko / es / de は同一キーで翻訳、未翻訳時は日本語値を暫定登録（CLAUDE.md i18n ルール準拠）。

### テスト観点（v2）

`TEST_CONVENTION.md` 準拠でバックエンド・フロントエンド双方に対して以下の観点を網羅する。

#### バックエンド（JUnit 5 / Mockito）

| カテゴリ | テストケース |
|---|---|
| 5段階 preference バリデーション | ①正常系: 5値すべて受け付ける ②異常系: `UNAVAILABLE`（旧値）送信時に 400 エラー ③CHECK 制約違反時 DB 例外が 400 にマッピングされる |
| 希望提出 API | ①ABSOLUTE_REST 複数件の正常提出 ②note ありの STRONG_REST が正しく格納 ③COLLECTING 外で 409 |
| 自動割当（GreedyShiftAssignmentStrategy） | ①スコアリング正確性（希望強度の既定重み×5パターンで期待スコアを検算） ②ABSOLUTE_REST ユーザーは候補から除外 ③同点処理が決定論的（同一入力→同一出力） ④respect_work_constraints=true で max_consecutive_days 違反ユーザーを除外 ⑤overwrite_existing=false で既存 CONFIRMED を保持 ⑥100スロット×100名規模で 3 秒以内 ⑦全員 ABSOLUTE_REST スロットで欠員警告 |
| 自動割当 API | ①POST /auto-assign 成功時 200 で SUCCEEDED 返却 ②同時実行 409 ③レートリミット 429 ④ADJUSTING 外 409 |
| confirm / DELETE (auto-assign) | ①全件確定で assigned_user_ids 更新 + slot.version インクリメント ②部分確定（assignment_ids 指定）で指定分のみ CONFIRMED ③DELETE で REVOKED 化、履歴は残る |
| D&D 差分割当 PATCH | ①add のみで正常追加 ②remove のみで正常削除 ③add/remove 両方空で 400 ④ABSOLUTE_REST ユーザー追加で 409 ⑤楽観的ロック競合で 409 ⑥ソフト制約違反時 warnings 返却 |
| 勤務制約 | ①個別レコード > チームデフォルト の解決順序 ②全項目 NULL INSERT で 400 ③夜勤判定の境界（22:00 / 06:00） ④連勤判定で前月またぎ ⑤勤務間隔 < 11h で警告 |
| リマインドバッチ | ①48h 前 / 24h 前 / 通常時で送信対象が正しく絞り込まれる ②is_reminder_sent_48h が TRUE の場合スキップ ③ABSOLUTE_REST のみ提出も提出済み扱い ④SELECT FOR UPDATE SKIP LOCKED で並列実行時二重送信なし |
| データ移行 V3.077 | ①UNAVAILABLE → STRONG_REST の変換 ②WEAK_REST / ABSOLUTE_REST は既存データに混入しない ③rollback（STRONG_REST → UNAVAILABLE）で v1 状態に戻る |
| 監査ログ | 全 SHIFT_AUTO_ASSIGN_* イベントが AuditLog に記録される |
| 時間帯分割スロット | ①同一 slot_date + position_id + 異なる時間帯で複数 INSERT ②完全時間帯重複で 409 ③部分重複は 200（警告なし） |
| **【v2.1】変更依頼 (A-1)** | ①MEMBER が自分の割当に SWAP_SELF 作成（201）②MEMBER が他人の割当を指定して作成（403 IDOR）③PUBLISHED 状態に対して作成（409）④同一スケジュール OPEN 6 件目（429）⑤管理者が ACCEPTED / REJECTED レビュー ⑥依頼者が WITHDRAWN 取下 ⑦ADMIN 以外が /review 叩く（403）⑧楽観ロック競合（409）⑨監査ログ CREATED/REVIEWED/WITHDRAWN が記録される |
| **【v2.1】個別交代 (A-2)** | ①is_open_call=false + target_user_id 必須相手指定で正常作成 ②target_user_id 未指定 + is_open_call=false で 400（排他違反チェック） ③指名相手以外が /accept 試行で 403 |
| **【v2.1】オープンコール (A-3)** | ①is_open_call=true + target_user_id=null で作成（201）②排他違反で 400 ③月次 4 件目で 429 ④通知オプトアウト設定 ON のユーザーには送信されない ⑤claim 先着 1 名のみ成功（楽観ロック）⑥2 人目の claim で 409 ⑦SUPPORTER の claim で 403 ⑧依頼者自身の claim で 403 ⑨CLAIMED で select-claimer 呼出 → ACCEPTED ⑩管理者が claimed_by 以外を select（許可）⑪依頼者が claimed_by 以外を select（403） |
| **【v2.1】目視確認** | ①PATCH /publish 時に自動割当履歴あり + 目視未確認で 409 VISUAL_REVIEW_REQUIRED ②履歴ありで確認済みなら公開成功 ③履歴なし完全手動組みなら確認スキップで公開成功 ④visual_review_acknowledged=false で 400 ⑤再実行した新 run が未確認なら 409 に戻る ⑥FAILED run に対する confirm-visual-review で 409 ⑦監査ログ SHIFT_SCHEDULE_VISUAL_REVIEW_CONFIRMED が記録される |
| **【v2.1】Flyway V3.083-V3.085** | ①V3.083 で shift_change_requests テーブル作成・CHECK 制約が機能 ②V3.084 で shift_swap_requests に is_open_call/target_user_id/claimed_by/claimed_at/version 列追加 + CHECK 制約 ③V3.085 で shift_assignment_runs に visual_review_* 3 列追加 ④ロールバック: 新規カラム drop で旧コード復活可能 |
| **【v2.2】PDF 出力（共通）** | ①PUBLISHED スケジュールで layout=team を ADMIN で要求 → 200 / application/pdf / Content-Disposition の filename に `シフト表_{チーム名}_{開始日}-{終了日}.pdf` ②layout 値不正で 400 ③存在しない scheduleId で 404 ④他チームの scheduleId で 404（IDOR 情報隠蔽）⑤Cache-Control: private, no-store ヘッダが付与される ⑥DRAFT 状態 + include_draft_watermark=false で 409 SHIFT_PDF_001 ⑦ARCHIVED は PUBLISHED 相当で出力可能 ⑧レートリミット 11 件目で 429 |
| **【v2.2】PDF 権限** | ①SUPPORTER の layout=team 要求 → 403 ②SUPPORTER の layout=personal 要求（自分 ID）→ 403（情報二次配布リスクで PDF は一切不可）③MEMBER が他人の member_id 指定で layout=personal → 403 ④MEMBER が自分の member_id で layout=personal → 200 ⑤MEMBER が include_draft_watermark=true → 403 ⑥DEPUTY_ADMIN without MANAGE_SHIFTS で layout=team → 403 ⑦GUEST は常に 401/403 |
| **【v2.2】PDF 内容の個人情報非露出** | ①PDF テキスト抽出（Apache PDFBox）で `reason` / `admin_note` / 時給 / 自動割当スコア / 勤務制約個別値 / 希望 note / 電話番号 / 住所のいずれも含まれていないことを検証 ②含まれるのは氏名・シフト情報（日付/時間帯/ポジション）のみ ③ウォーターマーク付き PDF でも同様に機微情報非露出 |
| **【v2.2】レイアウト崩れ** | ①長い氏名（全角 20 文字以上）で折り返しが発生してもテーブル幅破綻なし ②空スロット日（全員休み）でも空行が潰れて詰まらない ③必要人数未達セルが赤背景 + `✕ 欠員N名` で表示 ④複数ページ（月単位 30 名 × 31 日）で改ページと `pdf-common.css` の @bottom-center ページ番号が機能 ⑤個人タイムラインで日付跨ぎ（深夜 22:00-翌 06:00）が「当日枠」として正しく表示 |
| **【v2.2】多言語 PDF** | ①locale=en 指定で「Shift schedule」「Member」「Date」等に切替 ②zh/ko/es/de 全言語で PDF が正常生成・フォント埋め込み確認 ③未対応ロケール `fr` 指定で `ja` フォールバック（400 ではない）④Accept-Language と locale クエリが両方ある場合、クエリ優先 |
| **【v2.2】日本語フォント埋め込み** | ①NotoSansJP が PDF に埋め込まれていること（PDFBox の Font Embedded フラグ検証）②Acrobat/Preview で開いたときに代替フォント警告が出ない ③PDF/A 非準拠でも通常 PDF として印刷・画面閲覧に支障なし |
| **【v2.2】ウォーターマーク方式 A** | ①include_draft_watermark=true かつ status=DRAFT で「内部確認用」文字が全ページ中央に 45° 回転で描画される（PDFBox でテキスト検出可能）②PUBLISHED の場合はフラグに関わらずウォーターマーク無し ③多ページドキュメントで全ページに描画される |
| **【v2.2】監査ログ** | ①SHIFT_PDF_EXPORTED が AuditLog に INSERT される（requester_id, schedule_id, team_id, layout, member_id, include_draft_watermark, generated_at, output_size_bytes）②失敗時（PDF 生成例外・403）は成功扱いのログを残さない |
| **【v2.2】空スロット防止** | ①layout=personal で member が期間内いずれにも割り当てられていない → 400（空 PDF 防止）②member が 1 枠のみ割当 → 200 正常生成 |

#### フロントエンド（Vitest / Playwright）

| カテゴリ | テストケース |
|---|---|
| 希望提出画面 (Vitest) | ①5段階ラジオカードが6言語（ja/en/zh/ko/es/de）で正しく表示 ②プリフィル適用後の変更フロー ③一括操作ボタン ④未入力スロット確認モーダル ⑤オフライン時 `useOfflineQueue` へエンキュー |
| D&D 編集 UI (Playwright) | ①ドラッグでメンバー移動（成功時 UI 反映・サーバ PATCH 発行） ②ABSOLUTE_REST への add で UI ロールバック + Toast ③楽観的ロック競合時に最新状態再取得 ④ソフト制約違反時 chip 黄色ハイライト ⑤キーボード操作のみで割当完了（Tab → Space → 矢印 → Enter） |
| 自動割当 UI (Playwright) | ①「自動割当」ボタン押下で strategy 選択モーダル → 実行 → プレビュー表示 ②「確定」で CONFIRMED 反映 ③「破棄」で REVOKED ④実行履歴一覧からドリルダウン ⑤**【v2.1】**自動割当実行直後に目視確認バナーが赤系ハイライトで表示される ⑥**【v2.1】**チェックリスト全 5 項目をチェックしないと「目視確認承認」ボタンが非活性 |
| モバイル D&D (Playwright mobile viewport) | ①長押し 300ms でドラッグ開始 ②スクロールと干渉しない ③タップターゲット 44x44px 以上 |
| アクセシビリティ (Playwright + axe) | ①各セルに aria-label ②キーボードフォーカス順序 ③色コントラスト（5色チップ） |
| **【v2.1】変更依頼 UI (Playwright)** | ①MEMBER ダッシュボードから変更依頼フォームを開く ②理由テンプレート（チップ）選択で reason が自動入力 ③SWAP_SELF を選ぶと target_user_id セレクタが表示、その他タイプでは非表示 ④送信確認ダイアログで「送信」押下後は編集不可 ⑤管理者未読リストに赤バッジ ⑥却下時 admin_note 未入力警告 |
| **【v2.1】オープンコール UI (Playwright)** | ①「代打を募集」ボタンから is_open_call=true で作成できる ②作成後、他メンバー画面にプッシュ通知 + 通知センターに一覧表示 ③「代わりに入ります」ボタン押下で claim 成功 → UI が「応募済み」にロック ④2 ウィンドウで同時に claim 試行 → 片方は成功、もう片方は Toast「別の方が先に応じました」+ UI 更新 ⑤オプトアウト設定画面で「代打募集通知を受け取らない」トグル操作 |
| **【v2.1】公開前目視確認 UI (Playwright)** | ①自動割当実行後、公開ボタン押下でダイアログ「すべての割当を目視で確認しましたか？」が表示 ②ダイアログで「まだ」を選ぶと目視確認モーダルへ誘導 ③モーダルで 5 項目チェック + メモ入力後「承認」→ /confirm-visual-review 呼出 ④公開ボタン再押下で成功 ⑤完全手動組みスケジュールでは確認モーダル誘導はスキップされるがダイアログは表示される |
| **【v2.2】PDF出力ボタン UI (Playwright)** | ①PUBLISHED スケジュール画面に「PDF出力」ボタン表示 ②ADMIN では「チーム表」「個人表（自分）」「個人表（メンバー選択…）」の 3 項目 ③MEMBER では「個人表（自分）」のみ ④SUPPORTER ではボタン自体が非表示 ⑤クリックでスピナー表示 → 完了で Blob ダウンロード（Playwright `context.waitForEvent('download')`）⑥ダウンロード後のファイル名に想定チーム名/期間が含まれる ⑦非 PUBLISHED 状態 + ADMIN では「内部確認用PDF出力（未公開）」が追加表示 ⑧モバイルビューポートでもボタンが 44x44px 以上 ⑨エラー時（403/500）にトースト `shift.pdf.error` が表示 |

#### パフォーマンステスト（Gatling / k6）

| シナリオ | 合格条件 |
|---|---|
| POST /auto-assign（100スロット×100名） | p95 < 3,000ms |
| PATCH /slots/{id}/assignments（1秒間 50 リクエスト並列） | p95 < 500ms、楽観的ロック競合率 < 5% |
| 48h 前リマインドバッチ（1000 スケジュール対象） | 10分以内に全スキャン完了 |
| **【v2.2】PDF 生成（チーム表 50 名×7 日）** | p95 < 3,000ms、レスポンスサイズ < 2MB |
| **【v2.2】PDF 生成（チーム表 100 名×31 日）** | p95 < 5,000ms、レスポンスサイズ < 5MB。これを超える場合は §9 未来拡張の非同期化を検討 |
| **【v2.2】PDF 同時生成（10 並列）** | フォント読み込み競合なし、タイムアウト発生なし、全件 200 |

---


---

*前: [02_api_design.md](02_api_design.md) | 次: [04_security_operations.md](04_security_operations.md)*
