# F22.1: ウィジェット定義（厳選8枚）

> **ステータス**: 🟢 設計確定
> **最終更新**: 2026-05-30
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要
> - [02_api_design.md](./02_api_design.md) — データ取得 API（既存拡張 + action-required 新設）
> - [F02.2_dashboard.md](../F02.2_dashboard.md) — 既存 widget_key（流用元・重複回避の基準）
> - [F02.2.1_dashboard_widget_role_visibility.md](../F02.2.1_dashboard_widget_role_visibility.md) — 表示ロール（min_role）制御

---

## 1. 個人パネル（変更なし）

個人パネルは**既存 `frontend/app/pages/dashboard.vue` をそのまま内包**する。ウィジェット構成・widget_key・データソースは F02.2 §3「個人ダッシュボード（`scope_type = 'PERSONAL'`）」のまま。本機能では一切変更しない（要件 3）。

マイカレンダーの `CalendarGrid` はウィジェット表示時に遅延読み込みし、祝日定義は国コード・表示言語ごとに再利用する。予定・祝日の表示とウィジェット配置は維持する。

> 個人パネルにはタグ行・検索フォームを**出さない**（個人には所属スコープ選択も「チーム/組織検索」も不要。03 §3.9 で根拠を明記）。

---

## 2. チーム / 組織パネルの厳選 8 ウィジェット

タイムラインウィジェットは通常投稿を先頭3件に制限し、`TimelineFeed` の `limit` を描画だけでなくフィードAPIへも渡す。個人集約フィードでも指定上限をAPIへ渡す。上限付き表示では追加取得を行わず、上限を指定しない一覧のページングは維持する。

ダッシュボードと個人TODO等が同時に所属組織を要求する場合、同一Pinia storeの進行中要求を共有する。成功・失敗後は次回取得でき、`clear()`で無効になった旧要求は一覧・loading状態へ反映しない。異なるstoreインスタンス間で取得結果を共有しない。

初期パネルが `PERSONAL` の場合、`dashboard.vue` のsetupで本人確定後に個人ウィジェット設定GETを開始し、ページをawaitで止めない。個人パネルはprovide/injectで同じAsyncData handleを使い、mount時の重複GETを行わない。初期TEAM/ORGANIZATIONでは個人設定を先行取得せず、後から個人パネルを開く場合と単独mountは従来の取得経路を使う。本人未復元時は初回復元を待つ。取得開始時・応答後に認証セッションの本人と世代を照合し、別ユーザー・ログアウト・同じIDの再ログイン後の旧応答を成功にしない。既存のデータキー、クライアント専用取得、空配列の既定値、取得成功/失敗でreadyとなる契約、保存順・非表示設定と保存処理は維持する。

個人パネルのお知らせと固定司令塔による `GET /dashboard/action-required` は、初回取得が同じ本人・セッションで重なった場合だけ実行中のPromiseを共有する。パネル破棄時に共有を終了し、成功・空・失敗のいずれも完了後の結果は保持しない。返却項目は各callerで変換し、表示用itemsの参照を共有しない。お知らせの全タブ先読み・タブ切替・選択タブ単位のloadingと更新世代、両ウィジェットの個別状態・失敗時の縮退表示は維持する。

通常の更新取得または設定ダイアログの操作開始後は、新たな初回callerも旧実行中Promiseへ参加させない。既に参加したcallerの応答は、本人・セッションが有効な間はそのまま返す。非重複取得、更新・モーダル操作後に呼ぶ通常取得、providerなしの単独利用は新たに通信する。SSR・本人未確定・代理入力・後見切替・管理者変身では共有せず、取得中のログアウト・同じIDの再ログイン・別ユーザーへの切替・代理モード開始後の旧成功も採用しない。通常のtoken refreshは既存認証セッションの同じ世代として扱う。共有通信の失敗は各callerへ伝え、追加の自動retryは行わない。

要件 4 に基づき、チーム/組織パネルは以下の 8 枚に厳選する。課金サマリー・アクセス解析など**管理者限定ウィジェットは（メンバーレンズの）厳選 8 枚に含めない**（F02.2 の詳細ダッシュボードページで閲覧）。

> **管理者レンズ（F10.1.1）**: ADMIN / DEPUTY_ADMIN 向けの管理者ウィジェット群（`ADMIN_*` プレフィックス。team=予約/予算/承認待ち/メンバー統計/アラート/通報/モジュール/コンソール、org=承認待ち/予算/支払/メンバー統計/アラート/通報/ポイントカード/コンソール）は、本書の `SWIPE_*` メンバー向け 8 枚とは別の名前空間で定義され、チーム/組織パネルの**レンズトグル**で切り替えて表示する。可視性は `min_role`（3値）ではなく `StandardVisibility.ADMINS_AND_ABOVE` コード固定（F02.2.1 の min_role 管理対象外）。一覧と可視性は [F10.1.1/02_admin_lens_widgets.md](../F10.1.1_team_org_admin_console/02_admin_lens_widgets.md) を参照。横スワイプの軸（スコープ）は不変で、第4タブは追加しない。

### 2.1 命名方針（F02.2 との重複回避）

F02.2 の既存 widget_key（`TEAM_NOTICES` / `TEAM_LATEST_POSTS` 等）は**チーム/組織の詳細ダッシュボードページ**で使われている。本機能の横スワイプパネルは「厳選ビュー」であり、ウィジェット可視性設定（`dashboard_widget_settings`）を詳細ページと共有すると、片方の非表示がもう片方に波及して混乱する。

→ 本機能は **`SWIPE_` プレフィックスの新 widget_key** を用い、F02.2 既存 key と名前空間を分離する。`dashboard_widget_settings` の `widget_key` 列にそのまま格納できる（テーブル変更不要）。F02.2.1 のロール別可視性（min_role）判定もこの新 key 単位で適用する。

> **判断記録**: 横スワイプパネルと詳細ダッシュボードページは「別ビュー・別カスタマイズ単位」と位置づける。共有すると UX が予測不能になるため key を分離。データソース自体（API / Service）は F02.2 のものを流用する（key だけ新設、実装は再利用）。

> **トーナメント成績ウィジェットとの棲み分け**: F08.7.1 の大会成績ウィジェット（`TEAM_TOURNAMENT_RECORD` / `TEAM_DIVISION_STANDINGS` / `ORG_TOURNAMENT_SUMMARY`）は **F02.2 系の詳細ダッシュボードに置く**（本機能の `SWIPE_` 別名前空間には含めない・名前空間混同防止）。詳細は [F08.7.1/02_dashboard_widgets.md](../F08.7.1_tournament_extensions/02_dashboard_widgets.md)。

---

## 3. チームパネル（`scope_type = 'TEAM'`）widget_key 表

| # | widget_key | ウィジェット名 | 表示ロール（min_role）| データソース | 既存流用 / 新規 |
|---|-----------|-------------|:-----------------:|------------|--------------|
| ① | `SWIPE_TEAM_UPCOMING` | 今後の予定 | MEMBER | `GET /dashboard/team/{id}` の `teamUpcomingEvents`（実装済み）| 既存流用 |
| ② | `SWIPE_TEAM_TIMELINE` | タイムライン | MEMBER | `teamLatestPosts`（実装済み）| 既存流用 |
| ③ | `SWIPE_TEAM_BULLETIN` | 掲示板 | MEMBER | `teamUnreadThreads` の掲示板部分（実装済み）| 既存流用 |
| ④ | `SWIPE_TEAM_BLOG` | ブログ | MEMBER | `teamLatestBlogPosts`（**新規サマリ**・02 §3.3）| 新規（blog Service 流用） |
| ⑤ | `SWIPE_TEAM_CHAT` | チャット | MEMBER | `teamChatSummary`（**新規サマリ**・02 §3.3）| 新規（chat Service 流用） |
| ⑥ | `SWIPE_TEAM_CALENDAR` | カレンダー | MEMBER | `teamCalendarSummary`（**新規サマリ**・02 §3.3）| 新規（schedule Service 流用） |
| ⑦ | `SWIPE_TEAM_TODO` | TODO | MEMBER | `teamTodo`（実装済み）| 既存流用 |
| ⑧ | `SWIPE_TEAM_ACTION_REQUIRED` | 要対応 | MEMBER | `teamActionRequired` + `GET /dashboard/team/{id}/action-required`（**新規**）| 新規（§5 集約） |

---

## 4. 組織パネル（`scope_type = 'ORGANIZATION'`）widget_key 表

| # | widget_key | ウィジェット名 | 表示ロール（min_role）| データソース | 既存流用 / 新規 |
|---|-----------|-------------|:-----------------:|------------|--------------|
| ① | `SWIPE_ORG_UPCOMING` | 今後の予定 | MEMBER | `orgUpcomingEvents`（**新規**・組織スコープのイベント集約は未実装）| 新規（schedule Service） |
| ② | `SWIPE_ORG_TIMELINE` | タイムライン | MEMBER | `orgLatestPosts`（**新規**・組織未実装）| 新規（timeline Service） |
| ③ | `SWIPE_ORG_BULLETIN` | 掲示板 | MEMBER | `orgUnreadThreads`（**新規**・組織未実装）| 新規（bulletin Service） |
| ④ | `SWIPE_ORG_BLOG` | ブログ | MEMBER | `orgLatestBlogPosts`（**新規**）| 新規（blog Service） |
| ⑤ | `SWIPE_ORG_CHAT` | チャット | MEMBER | `orgChatSummary`（**新規**）| 新規（chat Service） |
| ⑥ | `SWIPE_ORG_CALENDAR` | カレンダー | MEMBER | `orgCalendarSummary`（**新規**）| 新規（schedule Service） |
| ⑦ | `SWIPE_ORG_TODO` | TODO | MEMBER | `orgTodo`（実装済み）| 既存流用 |
| ⑧ | `SWIPE_ORG_ACTION_REQUIRED` | 要対応 | MEMBER | `orgActionRequired` + `GET /dashboard/organization/{id}/action-required`（**新規**）| 新規（§5 集約） |

チーム・組織の「今後の予定」は予定データを表示し、「すべて見る」はそれぞれ `/teams/{slug}/schedule`・`/organizations/{slug}/schedule` の予定一覧へ移動する。

> **組織スコープの未実装に関する根治方針**: F02.2 のチーム dashboard は ①②③ を返すが、組織 dashboard はタイムライン/掲示板/チャット/ブログ/カレンダーを返していない。本機能では症状を隠さず、組織スコープでもこれらを返す Service / レスポンスフィールドを**新規実装する**（02 §3.3）。組織スコープのタイムライン/掲示板等の「実体がそもそも無い」場合は、空配列（`items:[]`）+ `total:0` を正直に返し、FE は「まだ投稿がありません」の空状態を表示する（フラグで握りつぶさない）。

---

### 4.1 所属ウィジェットの詳細モーダル（TEAM / ORGANIZATION 共通）

個人ダッシュボードの所属パネルでは、①予定・③掲示板・④ブログ・⑤チャット・⑦TODOの項目をクリック、Enter、Spaceで選ぶと詳細モーダルを開く。⑥カレンダーも概要をモーダルで確認できる。選択だけではページ遷移せず、モーダルの「所属先のページを開く」から次の正規ページへ進む。②タイムライン・⑧要対応と既存のお知らせ詳細の操作は維持する。

| 項目 | 選択時の取得と表示 | 遷移先（`{base}` は所属slug付きチーム/組織パス） |
|---|---|---|
| 予定 | 既存の所属付き `getSchedule`。実DTOのタイトル・開始/終了・場所・出欠回答要否・対象人数（説明本文は提供されない） | `{base}/schedule?eventId={id}`。全件リンクも `{base}/schedule` |
| 掲示板 | `getScopedThread` に内部所属IDとthread IDを指定し、本文・投稿者・更新日時を取得 | `{base}/bulletin?threadId={id}` |
| ブログ | サマリに追加した記事slugと内部所属IDで `getPost` を呼び、本文・投稿者・公開日時を取得 | 既存 `blogPostReadRoute` の `/blog/posts/{slug}?teamId=` または `?organizationId=` |
| チャット | 既存 `getChannel` の所属・チャンネル情報、説明・最新メッセージ・人数 | `{base}/chat` |
| カレンダー | 認可済みサマリの今日/今週件数・次の予定。0件・次予定なしも表示し、追加APIは呼ばない | `{base}/schedule` |
| TODO | 所属付き `getTodo` の説明・期限。空説明・期限なしも正常表示 | `{base}/todos/{id}` |

- 詳細APIは選択時だけ呼ぶ。一覧描画時の追加GETや新規API・DB変更はない。ブログサマリの `slug` 追加は既存取得・認可・クエリ数を変えない。
- 詳細応答の対象IDを照合し、掲示板/TODO/ブログ/チャットでは所属も照合する。予定の実DTOには所属IDがないため、所属付きAPIの認可と対象ID一致を根拠にする。内部所属IDが未解決なら不正なAPIを呼ばず、解決後に再試行できる。
- loading・取得失敗・403/404・不一致応答では本文と遷移リンクを表示しない。通信失敗は再試行でき、403/404では取得不可の案内を表示する。連続選択・閉じる・所属切替・アンマウント後の古い成功/失敗を破棄する。
- HTMLは既存サニタイズ、Markdownは既存renderer経由とし、TODO/チャットの説明はテキスト補間で表示する。空配列/nullから架空の項目を作らない。
- サニタイズの実除去は、CI smoke対象の `f221-swipe-dashboard.spec.ts` で実Chromium上のHTML/Markdownにscript・イベント属性・危険URLを含めて検証する。同居unitは既存サニタイザー/rendererへの入力と出力の接続を検証する。DOMPurifyが安全性を保証しないhappy-domを実除去の証明に使わない（[DOMPurify公式のサーバー実行上の注意](https://github.com/cure53/DOMPurify#running-dompurify-on-the-server)）。
- 項目はネイティブbutton、最小44pxの操作領域、390px幅に収まるDialogと本文内スクロールを使う。Esc・閉じる・背景操作で閉じ、残っている起点へフォーカスを戻す。

---

## 5. 統合「要対応」ウィジェット（⑧）詳細集計仕様

3 ドメイン（回覧板・アンケート・出席確認）を 1 枚に集約する本機能の中核ウィジェット。

### 5.1 集計定義

| 区分 | カウントの定義 | 直近アイテム | ソースドメイン / テーブル |
|------|--------------|------------|------------------------|
| 回覧板 | 当該スコープで当該ユーザーが**未確認（未スタンプ）**の回覧件数 | 直近 3 件（title / circulatedAt / deadline）| circulation（`circulation_documents` / `circulation_recipients`）|
| アンケート | 当該スコープで当該ユーザーが**未回答**のアンケート件数 | 直近 3 件（title / deadline）| survey（surveys / survey_responses）|
| 出席確認 | 当該スコープの直近イベントで当該ユーザーが**未回答（PENDING）**の出欠件数 | 直近 3 件（eventTitle / startsAt）| schedule（`schedules` / `schedule_attendances`）|

`total_action_count = 回覧板未確認 + アンケート未回答 + 出欠未回答`。タグバッジ（§02 §3.1 `unread_count`）にも反映。

### 5.2 表示仕様（UI）

```
┌─ ⑧ 要対応 ─────────────────────────────┐
│ 📋 回覧板        2件未確認            > │
│   ・5月度 回覧（〜6/2）                 │
│ 📝 アンケート    1件未回答            > │
│   ・懇親会の出欠（〜6/1）               │
│ ✅ 出席確認      3件未回答            > │
│   ・定例ミーティング（6/3 10:00）       │
└────────────────────────────────────────┘
  各区分の「>」タップ → 各機能の一覧ページへ遷移（回覧板/アンケート/カレンダー）
  ウィジェット「もっと見る」→ GET .../action-required（遅延取得・ページング）
```

- 3 区分すべて 0 件の場合: 「対応が必要な項目はありません」の空状態（チェックマーク）。
- 各区分の見出し・件数ラベルは i18n（03 §4）。

### 5.3 認可・集約の原則（再掲・厳守）

- 集約は `ScopeActionRequiredFacade`（dashboard ドメイン）が各ドメイン Service を呼ぶ。**各ドメインの per-scope 認可を必ず通す**（02 §3.4）。回覧板の閲覧権がないユーザーには当該区分を 0 件 / 非表示にする（集計バイパス禁止）。
- 3 区分を `CompletableFuture` で並行集計。N+1 回避のため各ドメイン Service は IN 句バッチ取得。
- `@Transactional` をドメイン跨ぎにしない（原則 5）。読み取り集計のためトランザクション境界は各ドメイン Service 内に閉じる。

### 5.4 F02.2 との関係

- 個人ダッシュボード（F02.2）には統合「要対応」ウィジェットは**存在しない**（個人パネルは F02.2 のまま）。本ウィジェットはチーム/組織パネル専用の新規ウィジェット。
- F02.2 の `TEAM_MEMBER_ATTENDANCE`（出欠集計＝チーム全体の出席○/欠席×/未回答人数）とは目的が異なる。本ウィジェットの出席確認は「**自分が**未回答の出欠」であり、観点（自分の TODO 的視点 vs チーム全体の集計）が別。混同しないよう widget_key を `SWIPE_*_ACTION_REQUIRED` として分離。

---

## 6. ウィジェット可視性・並び順の扱い

- 8 ウィジェットの表示/非表示・並び順は既存 `dashboard_widget_settings`（F02.2）を `widget_key = 'SWIPE_*'` でそのまま使用する（テーブル変更なし）。
- 取得は既存 `GET /api/v1/dashboard/widgets?scopeType=TEAM&scopeId={id}`、更新は `PUT /api/v1/dashboard/widgets`（変更なし）。
- F02.2.1 のロール別可視性（`min_role`）は新 key 単位で適用。本機能の 8 枚はすべて `min_role = MEMBER`（管理者限定ウィジェットを含めない方針のため、SUPPORTER 可視は各スコープの F02.2.1 管理者設定に委ねる）。
- レコードが存在しないウィジェットはデフォルト表示（F02.2 と同じ遅延作成・UPSERT）。
