# 住民観測の裏取り・殿の裁定

2026-11-08。実機 source 4ce、r17の同一専用環境。root所有PTY72271から通常UIで再実施しexit0。元住民とは別context、owner/otherの旧探索context閉鎖後にログイン。fixture/mock/APIによる状態変更なし。

## 結論

正式3住民は別ログイン・目的のみで探索し、各目的に到達した。新たに本素材統合が導入した確定不具合は0。以下の観測は未実証の気づきとして保持する。全UIの無欠陥を意味せず、小さい操作領域を44px以上と読み替えない。

### PC設定・mobileタイトルの操作領域

rootの1440×900実測でcombobox内部spanは高さ40px、音量spinbuttonは42px。360×800 / isMobile=true / hasTouch=trueのwidgetタイトルリンクは134.15625×28px、display:flex・上下padding0・line-height24px。住民の幾何観測と一致した。実寸が小さいという観測自体は有効。

RanchSettingsPanel / RanchWidgetContent / DashboardWidgetCard / DinosaurSceneは、統合前main6aeと実機4ceでgit diffが空。blobは順にfada14472bf1e2947e35203ec3319b7de51b96c4、1c12f36edc2f472bb12debae3e50c3909a76937d、26cefeccc2b8e34e399ef319884043190f2bf596、3706a93a758760e2f82e68ef941a11fe2f8f7e62。今回変更していない既存UIである。住民全員が操作できており、本素材接続の回帰や到達不能とは確定しない。B0気づきへ残し、根拠なくblockerや本campaignの修正済み項目へ昇格しない。

### ふれあい直後の素材準備中表示

元mobile PNG19の準備中表示はrootも視認した。根拠のある観測として保持する。元時点の成功画像要求・figure datasetは未採取なので、その直接原因は未確定。

同じother個体Archelon BABY V3を360×800でrootが再訪し、自然な初回widgetふれあいを実行。成功ACK201は同個体ID・同Server。idle/happy画像は各HTTP200、happy取得は21ms、実draw sourceRectは0/96/192/288のABCDすべてを観測した。clickから約6.085秒の100ms間隔58 sampleではcanvas可視・placeholderなし。happyは約220–2568ms、約2684msにidleへ復帰した。rootは終了時のviewport PNGとfigure PNGを実視認した。取得失敗や持続する画像欠落は再現しなかった。

source上、action変更はatlasのloadedをfalseにして非同期取得し、loadedをreadyへ接続してplaybackを制御する。一時的な読み込み表示を取り得る構造だが、それを元PNGの確定原因とはしない。100ms sampleより短い変化の不在も主張しない。元観測を修復済み/誤報として削除せず、非再現・原因未確定の気づきとして残す。

## 証拠と後始末

- result.json SHA256: 0dc7b3bef3f4ae897792781c52af17add5989dfbd202f054ca2752cfc9c9ef35。
- root実行driverはb4904191189059b5bd628a1c4ceca7656a58de19129045b5b9775a3776133972に固定。
- resultのcleanupはcontextsClosed=true / browserClosed=true、rootPTY72271 exit0。
- 観測詳細は同directoryのresult.jsonとPNG。認証値を記録しない。
- 正式3探索は実施完了として採択。r17元suiteのraw FAILと未測定項目は別に保持し、全自動試験greenとは記録しない。
- 新head CI/build、環境cleanup、通常merge・完了台帳はこの裁定の時点で未完。
