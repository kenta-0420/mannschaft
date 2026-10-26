# ⚔️足軽3（住民・ドイツ語）: 探索報告

- runId: timeline-refresh-20261023 / personaId: TL-P3 / journeyId: TL-J3
- 人格A: ドイツ語利用者 / 攻め口B: 表示崩れ
- 目的: チームの新しい連絡を読み、内容を取り直す操作を探す。
- 対象: /teams/<exploration-fixture>/timeline
- 実サーバー・実Chromium・独立BrowserContext・ADMIN専用loginViaApi。API mock/page.route/DB直接操作なし。

目的達成にござる。設定→個別設定一覧→言語・タイムゾーン→Deutsch→保存でドイツ語に変更。PUT /api/v1/users/me が200、locale deを確認（[観測要約](./german-observations-summary.json)）。タイムラインへ戻り、投稿欄の下にあるAktualisierenを発見。クリックによりGET /api/v1/timeline/feed?scopeType=TEAM&scopeId=<exploration-fixture> が200になった（[観測要約](./german-observations-summary.json)）。連絡3件が表示されたまま、意図しない遷移・ログアウトはなかった。

初期viewportでは投稿欄が先に現れ、1280x720、1440x900、1024x576、1152x720の上端では更新ボタンを下スクロールして探した。1920x1080の上端では更新ボタンも見えた。ドイツ語の操作名Aktualisierenは省略なく表示され、クリックできた。

## 視覚確認

1280x720・1440x900・1920x1080は100%条件。1024x576・1152x720は125%相当のviewport近似であり、実ブラウザzoom確認ではない。

stable-de-{幅}x{高さ}-top.png と stable-de-{幅}x{高さ}-refresh.png の計10枚をモデルが実画像として閲覧した。初期表示と更新/投稿一覧表示を確認。全5条件でページscrollWidth=clientWidth、Aktualisierenは150.15625x44px、min-height/min-widthは44px、font-size16px、文字のscrollWidth=clientWidth148px。文字切れ・横パン・更新操作と固定ヘッダーの被りは観測しなかった。上部チームタブ列は右矢印とフェードを備えたコンテナ内スクロールであり、ページ横パンと区別した。公開証跡: [5条件の寸法](./german-stable-geometry.json)、[狭幅画像](./german-narrow-stable.png)。原本10画像のうち公開は狭幅1枚。

deでもページ見出しh1の表示文字列が「タイムライン」のまま残ることを画像とDOMで観測（[見出し観測](./german-title-observation.json)）。更新ラベルAktualisierenとは分けた既存翻訳候補として殿へ報告。住民として不具合確定はしていない。

最初のde-...画像には滑らかスクロール途中の描画が混じったため、instantスクロールで撮り直したstable-de-...を最終証拠とする。

## 復元・データ・制限

既存UIのDeutsch→日本語→Speichernで元のjaに復元。PUT200後、GET /api/v1/users/me 200・locale ja、画面の選択値日本語を確認（[観測要約](./german-observations-summary.json)）。さらにタイムラインに戻り「更新」の日本語表示を確認（[観測要約](./german-observations-summary.json)、復元画像は原本で確認）。

投稿・編集・削除・draft入力はしなかった。作成した業務データなし。期間中のHTTP400以上レスポンスとpageerrorはいずれも0。検証ドライバーのBOM、参照パス、待機/selector誤りで再起動やエラーがあったが製品のエラーとは分離した。前の独立ブラウザ終了後にのみ再ログインし、セッションを並行共有していない。