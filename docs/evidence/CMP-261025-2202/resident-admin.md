# 住民探索: 高齢・低リテラシー運営者 × 中断・放棄

⚔️足軽1（住民・高齢運営者）: 目的は達成いたしました。掲示板をメンバーOFF・サポータONとして保存し、別画面へ移って戻っても維持され、最後は通常の管理者表示へ帰還しております。以下は住民の観測であり、不備確定ではありません。

- 登録元: アリシゼーションテスト
- runId: cmp261025-2202 / journeyId: team-feature-display-admin-abandon
- 人格: 高齢・低リテラシーの整骨院チーム運営者 / 攻め口: 中断・放棄
- 対象: TEAM `teams-1792955080937` / numericId 25
- FE: http://localhost:3001 / API: http://localhost:8081
- locale: ja（BrowserContext locale ja-JP、timezone Asia/Tokyo）
- 終了時刻: 2026-10-26 04:25:52 JST
- 本人確認: userId 3、GET `/api/v1/teams/teams-1792955080937/me/permissions` 200 / roleName ADMIN
- BrowserContext: isMobile=true、hasTouch=true。同一context/pageのviewportを390x844から360x800へ変更。別ユーザーへのログインなし。

## 操作と観測

1. チームに入ると「メンバーの権限を初期設定」モーダルが開いた。目的と違うため「あとで決める」で閉じた。
2. 「メニュー」を開き、下へスクロールして「設定」を展開。「管理コンソール」→「設定」へ進んだが、表示されたのはシフトリマインド・FAQ・公開設定・ステータスラベル管理だった。目的の設定は見つからず、ブラウザの戻るでチームへ帰った。この往復は迷いとして記録する。
3. チームの「機能設定」タブから `/teams/teams-1792955080937/modules` へ進んだ。モジュール一覧のさらに下、「一般機能の表示」で目的の行を発見。390pxでは見出しへスクロールした時点のscrollYは4825。「メンバーに表示」「サポータに表示」と用途説明があった。
4. `#feature-display-bulletin-member` をOFFにし、保存せず「基本情報」へ離脱。確認ダイアログは観測されなかった。再び「機能設定」へ戻るとmember=true/supporter=trueだった。未保存の操作は残らなかった。
5. `label[for="feature-display-bulletin-member"]` を押しOFFにした。supporterはONのまま。「保存」を押すと「表示設定を保存しました」が出た。
6. 「基本情報」へ離脱後、「機能設定」へ戻るとmember=false/supporter=trueだった。390-06-return.pngで視覚確認。360でも同じ値を確認し、変更しないまま保存ボタンを押して成功表示を確認した。
7. ダッシュボードで「メンバー」表示へ切り替えると「連絡・共有」の概要が「タイムライン / チャット ほか2件」「4」となった。「管理者」へ戻すと「掲示板 / タイムライン ほか3件」「5」へ戻った。
8. 最終URLは http://localhost:3001/teams/teams-1792955080937 。`[data-lens="admin"]` のaria-pressed=trueを確認し、contextとbrowserを明示的にcloseした。

## 画像とDOM計測

証跡基点: `frontend/test-results/alicization/cmp261025-2202/admin/`。下表のPNGは実際に画像を開いて確認済み。同名JSONにURL・locale・viewport・controlのbounding box/computed styleがある。

| viewport | 状態 | PNG |
|---|---|---|
| 390x844 | 初期モーダル | 390-01-initial.png |
| 390x844 | メニュードロワー | 390-02-menu.png |
| 390x844 | 設定初期ON/ON | 390-04-settings.png |
| 390x844 | 保存成功・保存ボタン | 390-05-saved.png |
| 390x844 | 離脱後の設定OFF/ON | 390-06-return.png |
| 390x844 | 最終管理者表示 | 390-07-final-admin.png |
| 360x800 | ダッシュボード初期 | 360-01-initial.png |
| 360x800 | メニュードロワー | 360-02-menu.png |
| 360x800 | 設定OFF/ON | 360-04-settings.png |
| 360x800 | 保存成功 | 360-05-saved.png |
| 360x800 | 管理者表示へ復帰 | 360-06-return-admin.png |

全記録でdocument.scrollWidth/clientWidthは390/390または360/360。ページ全体の横パンは観測しなかった。上部タブ列はコンテナ内の横スクロール。長いチーム名・ダッシュボード概要には省略表示があり、設定表の文字は360pxで複数行へ折り返した。画像上で操作不能な文字切れ・重なりは観測しなかった。360設定状態でh1/h2/h3/p/th/td/label/buttonの表示中要素を対象にhidden/clip下の内容寸法超過を検査し、該当0件（360-clipping.json）。

| selector/操作 | 390pxでのbox (x,y,w,h) | 360pxでのbox (x,y,w,h) | style/備考 |
|---|---|---|---|
| `#feature-display-bulletin-member` | 167,703,44,44 | 137,701,44,44 | display:block、overflow:visible、white-space:normal |
| `#feature-display-bulletin-supporter` | 263,703,44,44 | 233,701,44,44 | 同上 |
| `label[for="feature-display-bulletin-member"]` | 145,703,88,44 | 115,701,88,44 | display:flex、overflow:visible。有効なlabel押下で切替成功 |
| `label[for="feature-display-bulletin-supporter"]` | 241,703,88,44 | 211,701,88,44 | 同上。360-tap.jsonに記録 |
| 保存ボタン | 247,739,82,44 | 217,695,82,44 | display:flex、overflow:hidden、white-space:normal |
| 保存成功toastの「閉じる」 | 340,26,28,28 | 310,26,28,28 | display:flex、overflow:hidden、white-space:pre-line。44px未満の補助操作として観測 |

メニュー開閉、表示切替、保存、対象ToggleSwitchの主要操作は44px以上だった。初期モーダル内の「使い方と上限を見る」はinlineリンク126x20で、今回の目的達成には使っていない。

## エラー・制約・環境待機

探索中のpageerrorとHTTP400以上のresponse記録は0件（session-summary.json）。アプリ操作によるログアウト・保存失敗・意図しない外部遷移は観測しなかった。ネットワーク遮断中の保存、サポータ本人画面、PC表示は担当範囲外で未確認。

04:09 JST時点ではFE/BEともcurl status000で、親も停止を確認した。復旧通知まで操作停止し、この待機は探索時間に含めなかった。初回のログイン失敗には接続不能と、住民ランナーがPASSWORD定義の引用符・改行を誤捕捉したAUTH_001があった。後者はランナー修正で解消したもので、アプリ不備とは扱わない。認証値・tokens・cookiesは出力も証拠記録もしていない。

標準入力を保持しなかった最初の成功ランナーは、所有PID 26800とそのbrowser子プロセスを確認して閉じ、tty保持で作り直した。同時に2つの住民contextを使っていない。最終ランナーはcontext.close()/browser.close()後にexit0となった。

コード・設定・既存文書の変更なし、commitなし、DB直接操作なし。変更した業務データは指定TEAMの掲示板member表示設定のみ。scope削除は親担当へ委ねる。未commitの本報告と画像・JSONを親へ返却する。


## 永続証跡

親の統合時に代表画像・寸法JSONを [screenshots/admin/](screenshots/admin/) へ保存した。上記のfrontend/test-resultsは実行時の保存先であり、初期loading等の過渡状態を含む全ファイルは永続化しない。現行の判定と代表証拠は [統合判定](exploratory.md) を参照。
