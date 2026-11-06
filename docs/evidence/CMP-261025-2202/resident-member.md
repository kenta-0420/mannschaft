# 住民探索: スマホ片手メンバー

⚔️足軽2（住民・スマホ片手メンバー）: 所属組織の予定と掲示板を探す目的を達成いたしました。以下は住民の観測であり、不備確定ではございません。

- 登録元: アリシゼーションテスト
- ペルソナID: resident-member / 人格: スマホ片手操作 / 攻め口: 表示崩れ
- runId: cmp261025-2202 / journeyId: member-org-schedule-bulletin
- 対象: `http://localhost:3001/organizations/organizations-1792955082319`（numericId 22）
- 本人: id 12。API本人プロフィールから取得したIDと保存済み本人IDが一致。対象画面に「メンバー」バッジを実視認。
- 条件: Chromium headless、locale ja-JP（HTML lang ja）、Asia/Tokyo、390x844 → 同一contextで360x800、isMobile/hasTouch true。
- 証跡基点: `frontend/test-results/alicization/cmp261025-2202/member/`。各安定画像と同名JSONにURL・全本文・viewport・要素selector/class・bounds・computed style・エラーを保存。
- 時刻: 2026-10-26 04:17〜04:26 JST（画像時刻はUTCでsession-summary.json）。環境停止前の接続試行・待機は探索時間外。
- ログイン成功1回、独立BrowserContext 1個。DB直接操作・他ユーザー操作・外部送信・データ作成更新はなし。2026-10-25T19:26:01.795Z にcontextとbrowser閉鎖済み。

## 時系列と目的成否

1. 初回はFE接続拒否のためログイン前に停止し、contextを閉鎖。親の環境再開通知後に新たな専用contextでログイン。再開後の対象初回遷移はDCL30秒timeoutとなり、`390-initial.png` はloadingのみ。継続待機で自然にホームを表示。準備・初回描画待ちであり機能不備と断定しない。
2. 390幅ホームで「予定・活動」を発見しタップ。カレンダーの「詳細」リンクから `/schedule` へ到達。`390-org.png`、`390-schedule-expanded.png`、`390-calendar-visible.png`、`390-schedule-final.png` を取得し実画像を確認。
3. 月カレンダーの予定名は視覚的にはアイコンだけに省略され、文字列spanへのtapは幅0のためnot visibleでtimeout。見えているイベント親をtapすると日セルが選択され、予定名と2026/11/02 04:04〜05:04の詳細が表示。`390-event.png`。予定を見る目的は達成。
4. ヘッダーの「メニューを開く」→「組織」→対象名の直接ジャンプでホームに戻った。`390-menu.png`、`390-scope-menu.png`、`390-home-return.png`。組織内の小さなメニューボタンも発見し、メニュー内「掲示板」をtap。`390-feature-menu.png`、`390-bulletin-visible.png`。掲示板一覧に2件と「表示復元で保持される本文」を確認。非表示設定の組織情報入口はこのメニューになく、掲示板入口は存在。
5. 同contextを360幅へ変更。`360-bulletin.png`、`360-feature-menu.png` を実視認。戻ろうとしてgetByRole(link, ダッシュボード)は対象が見つからずtimeout。画面内の「戻る」は機能しホームへ到達。これは自動操作のlocator不一致として記録し、実ユーザーの操作不能とは扱わない。
6. `360-home.png`、`360-home-communication.png`。ホーム「連絡・共有」→「掲示板」のウィジェット自体をtapしても一覧へ遷移。ブラウザの戻るからホームへ戻り「予定・活動」→カレンダー「詳細」で再び予定へ到達。`360-home-schedule.png`、`360-schedule.png`。
7. 360幅で日セルをtapし予定詳細を表示し、スクロールで名称・日時を確認。`360-event-detail.png`、`360-event-readable.png`。目的達成後にcontextを閉鎖。

## 視覚・寸法の観測

記載した主要画像はすべて tools.view_image で実画像を確認した。初期loadingからの復帰、メニュー、ホーム、予定、掲示板で文字の重なりやページ横パンは観測しなかった。ホームの長い組織名・要約はellipsis、掲示板タイトルと予定詳細は折り返す。予定ウィジェットの「カレンダー」は狭幅で2行に折り返すが重なりはない。

| 状態 | 390幅 / 360幅のdocument clientWidth・scrollWidth | 主な証跡 |
|---|---|---|
| 組織ホーム | 390/390、360/360 | 390-org、360-home |
| 組織内メニュー | 390/390、360/360 | 390-feature-menu、360-feature-menu |
| 全体メニュー・スコープ選択 | 390/390（360未確認） | 390-menu、390-scope-menu |
| 予定 | 390/390、360/360 | 390-schedule-final、360-schedule |
| 掲示板 | 390/390、360/360 | 390-bulletin-visible、360-bulletin |

上部タブ列は内部横スクロールであり、右側タブがviewport外にあることをページ全体のoverflowとして扱っていない。ホーム・メニュー・スコープ選択の主要button/linkは測定範囲で44x44以上。掲示板一覧の新規スレッド・検索入力等も44px以上。

### 親での裏取りを要する観測候補

1. 掲示板の「一覧」「保管庫」buttonの高さが38px。両viewportで一覧72x38、保管庫86x38。390幅のboundsはそれぞれx40/y209、x120/y209。selectorは `button.-mb-px.border-b-2.px-3.py-2.text-sm.font-medium.transition-colors`、fontSize14px、whiteSpace normal、overflow visible。`390-bulletin-visible.png/json`、`360-bulletin.png/json`。44px基準未満だが操作不能は観測せず、機能表示設定と独立の既存箇所である可能性あり。
2. 月カレンダーのイベント文字 `span.min-w-0.flex-1.truncate` は390幅でwidth0/height20、360幅でもwidth0/height20。360幅はx119/y518、clientWidth0/scrollWidth421、display block、overflow hidden、whiteSpace nowrap、flex `1 1 0%`。親イベントdivは25x24（x99/y516）、日セルは34x60（x95/y485）、日セルoverflow hidden。`360-event-measure.json`。390幅の文字spanはx123.28125/y562。同じ日セルをtapすれば詳細を読めるため目的は達成している。月表示の省略仕様との区別、および日セルの幅44px未満の扱いは親の裏取りへ渡す。

## エラー・未確認

- 取得したpageerrorとHTTP>=400レスポンスのイベントは0件。初回描画待機、自動操作locator待機のtimeoutは上記のとおり。
- 読み取り中心の探索であり、新規スレッド、出欠回答、コメント投稿等の変更操作・フォーム検証・エラーモーダルは行っていない。
- 360幅の全体メニューとスコープ選択は未確認。全体メニューは390幅、組織内メニューは両幅で確認。
- 非表示組織情報への直URL権限試行は本担当では行わず。表示入口の設定であり既存直URL許可維持という親の条件を尊重した。
- 観測候補は親へ即時連絡済み。blockersや台帳への昇格・コード修正・commitは行っていない。


## 永続証跡

親の統合時に代表画像・寸法JSONを [screenshots/member/](screenshots/member/) へ保存した。上記のfrontend/test-resultsは実行時の保存先であり、初期loading等の過渡状態を含む全ファイルは永続化しない。現行の判定と代表証拠は [統合判定](exploratory.md) を参照。
