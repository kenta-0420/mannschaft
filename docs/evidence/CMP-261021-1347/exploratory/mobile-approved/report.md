⚔️足軽2（住民・スマホ片手操作×表示崩れ）: 承認後の独立探索報告

登録元: アリシゼーションテスト
personaId: ashigaru-2-mobile-layout
runId: clipv3-1792574010514-3a8a8367
journeyId: CMP-261021-1347-mobile-approved-20261022

人間の直接回答「許可する（検証専用3アカウントのみ）」後、sea-juvenileだけを新BrowserContext1個・通常UIログイン1回で操作。FE51359/BE18724のみ。390×844/360×800、ja-JP、mobile/touchはcontext作成時から有効。FE commit40437bf7、BE commit2bd44c9b。起動時とmutation前に元runのidentity guardが成功。旧証跡は上書きしておりません。

到達したこと
- メニュー→設定→恐竜の部屋の設定で部屋に到達。開始時は試験sea-j成体120XP、96ドット・動きを控える。
- ふれあいPOST201、「自分の分身に触れました。知り合いはじめ」。成長120XPは変わらず。
- 通常2D・通常へ変更して保存PUT200。保存直後のbody採取には素材準備中の文言が一時存在。その後18-room-normal-360画像で通常2Dの海の恐竜を直接確認。
- 遊び方モーダルを390/360両幅で確認、内部scrollして末尾へ届き下の閉じるも操作成功。
- logoで普段のdashboardへ戻り、マイコンテンツ・所属→恐竜widget展開で同じ試験sea-j成体を390/360両幅で確認。部屋再訪後も普段の画面へ戻り、再展開して同名恐竜を確認して帰りました。

迷い・未確定の気づき
初期dashboardには恐竜が見えず設定を探しました。普段の画面はグループとwidgetの二段階展開で表示。再訪後のdashboardも恐竜widgetが折畳みになっており、恐竜文字を待った自動化が1回30秒timeout。実UIを読み直して展開すれば到達し、業務エラーとして扱いません。

恐竜widgetへの見出しリンク134.156×28pxは今回も実測。tapは成功しており、不備確定は殿の裏取りへ委ねます（24/25画像とsteps.json）。360幅widgetのふれあいが2行になるが、文字切れ/横パン/ボタン重なりは画像で見えませんでした。

旧観測との違い
今回は状態欄の白文字に対し背景ancestorがrgb(24,24,27)。初回390、360、部屋再訪でも黒い背景で読め、旧cream背景rgb(243,239,224)による低視認は再現しませんでした。修正済みとは断定せず、今回の観測だけを申し上げます。09-status-390、10-status-360、30-status-revisit-360とcontrast JSONを参照。

寸法・エラー・限界
全33採取状態でpage scrollWidth=clientWidth。ごはん/ふれあい/ようす/保存/閉じるは44px以上の高さ。combobox内部40px/外側42pxだがlabel66px、checkbox20pxだがlabel44pxのため内部部品だけで不備認定しません。主要操作不能な見切れは未観測。backend4xx/5xxなし、pageerrorなし。04/05は遷移中画像で安定後06を正本にします。07はふれあい応答待ち、応答後08を優先。30画像はDOMheader y0と画像上余白に差があり撮影/viewport状態差を排除できないので重なりバグ断定しません。

卵/孵化/名前validationは開始時成体なので未観測。業務error画面に自然遭遇せず未観測。非表示/休止/交換/想起/音再生は未操作。実スマホではなくmobile/touch emulation、実親指到達距離は未測定。製品source/spec/他resident報告は未読。

後始末
専用accountのふれあい1回と表示設定変更のみ。新規データ物体作成なし。所有context/browserだけ正常終了、ownedContexts0/connectedfalse、HTTP listenerなし。driver exitは別保存。

証跡
docs/evidence/CMP-261021-1347/exploratory/mobile-approved/report.json、steps.json、network.json、page-errors.json、contrast-390.json、contrast-revisit-360.json、public-run-check.json、browser-close.json。PNGはfinalize WT/frontend/test-results/alicization/CMP-261021-1347/mobile-approved/。
