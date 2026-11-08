# 住民探索 — サポータ

⚔️足軽3（住民・サポータ）: 基本情報の閲覧、チーム・組織の往復、管理表示設定への直アクセスを確かめ申した。観測範囲で入口の混在や管理設定の保存操作への到達は見られず。

- 登録元: アリシゼーションテスト
- runId: cmp261025-2202 / journeyId: resident-supporter
- 人格: 一般（平均リテラシーの組織サポータ）× 隙間狙い
- 環境: FE http://localhost:3001 / API http://localhost:8081。本陣ポートは操作していない。
- 対象: organizations-1792955082319（numericId 22）。比較は専用 teams-1792955080937。
- actor: 専用ユーザー id 5。対象ORG画面の「サポーター」「サポーターです」を確認。
- 条件: Chromium headless、ja-JP、Asia/Tokyo、1280×720、100%。desktop全条件や他viewportの確認ではない。
- 実探索: 2026-10-26 04:16頃〜04:23:11 JST（最終閉鎖時刻 2026-10-25T19:23:11.882Z）。前段にサーバ停止による待機あり。

## 観測の時系列

1. 起動前、FE robots.txt が ERR_CONNECTION_REFUSED。FE/APIとも接続不能を確認して親へ報告し、ログイン前に停止。親の復旧通知まで待機した。
2. 復旧後、認証補助は loginViaApi と同じログイン・本人情報・localStorage復元のみ。最初のランナーはstdin閉鎖で操作不能となり、当該ランナーと子ブラウザを全て終了後、同じ専用actorを逐次再ログインした。並行ログインはしていない。以後1 Contextで探索。
3. 対象ORGへの初回 navigation は30秒 load timeout。01-initial.png は中央loadingのみ、リンク・ボタン0、pageerror/失敗HTTPなし。その後自然解消し、ORGのダッシュボード、基本情報、メンバー、所属チームの入口が現れた。初回warmupの可能性はあるが原因未確定。
4. 組織ボタンからメニューを開いた。直接ジャンプは対象ORGのみ。03-org-menu.pngを画像確認。
5. 基本情報リンクをクリックし、/info で組織名・所在地未設定・公開・メンバー2人・サポーター機能有効を確認（05-info-stable.png）。組織の表示設定タブや保存ボタンは見えない。
6. チームメニューに専用TEAMが見えたためクリック。TEAMのサポータ表示と「連絡・共有」に掲示板入口を確認（07、08画像）。TEAMで抽出したhrefは /teams/teams-1792955080937/bulletin。
7. 組織メニューから対象ORGへ戻り、「連絡・共有」を展開。タイムライン・ブログ・ギャラリーの3件のみ、掲示板なし（10-org-return-stable.png）。TEAMの掲示板入口がORGへ混ざる事象は観測しなかった。基本情報入口は維持。
8. TEAMで見たURLのscopeを書き換え、ORG /bulletin を直アクセス。掲示板と専用スレッドが表示された（11-direct-bulletin.png）。非表示は入口制御であり既存直URL権限を変えないとの仕様に整合する観測。
9. 管理URLを推測し /settings へ直アクセスしたが404。「このページは見つかりませんでした」とホームへ戻るボタンのみ（12-direct-settings.png）。正しい管理URLと誤認しない。
10. 親から共有された正しい管理表示設定URL /modules を直アクセス。最終URLはORGダッシュボード。保存、表示設定switch、inputを認めず（13、安定後14画像）。この操作に起因する4xx/5xxは観測なし。管理設定を変更する操作には到達できなかった。
11. リダイレクト先から基本情報リンクを再度クリックし /info へ到達（16-final-stable.png）。通常UIへの復帰を確認した。

## 迷い・エラー・限定事項

- 組織メニューを閉じた直後、getByRole(link, 基本情報 exact) は30秒timeout。DOMでは実寸88×44のa[href=.../info]があり、href指定クリックで到達した。通常クリック不能とは断定しない。
- route遷移直後はloadingが既存画面に重なる状態や骨組み表示があった。09、13、15は過渡状態。安定確認は10、14、16を参照。
- 捕捉pageerrorは0。失敗HTTPとして記録されたのは推測した /settings のdocument 404のみ。レスポンス本文・認証値・token/cookieは出力していない。
- 最終infoの実測: viewport 1280×720、documentElement.scrollWidth=clientWidth=1280。ページ全体の横はみ出しなし。全画面・全要素の寸法検査ではない。
- APIによる表示設定変更、DB操作、scope削除、他データの変更、外部送信はしていない。掲示板直URLの通常UI表示でアプリ自身が行う通信は観測対象として扱った。
- /modulesで設定UIに入れなかったことは観測事実であり、認可実装・APIの裏取りや不具合確定は親が担当する。

## 証跡と閉鎖

画像保存先: frontend/test-results/alicization/cmp261025-2202/supporter/。初期01、組織メニュー03、目的達成05、比較TEAM07/08、ORG戻り10、直URL11/12、管理URL復帰14、最終16の画像を実視認した。08は展開アニメーション途中で、掲示板リンクの観測は同時採取DOMによる。

最終eventsは同ディレクトリ events.json。探索用ランナーは .claude/runtime/cmp261025-resident-supporter/runner.cjs のみ。2026-10-26 04:23:11 JSTにcontext.closeとbrowser.closeが成功し、その後runner process.exit(0)、exit code 0を確認。コード・設定・既存文書の編集とcommitはしていない。この新規記録は未commit。


## 永続証跡

親の統合時に代表画像・寸法JSONを [screenshots/supporter/](screenshots/supporter/) へ保存した。上記のfrontend/test-resultsは実行時の保存先であり、初期loading等の過渡状態を含む全ファイルは永続化しない。現行の判定と代表証拠は [統合判定](exploratory.md) を参照。
