# 住民B: ORG_ADMIN / 高齢・低リテラシー

- source: `frontend/test-results/alicization/october-first-wave-20261026/resident-org-literacy/`
- source runId: `october-first-wave-20261026`; sourceHead: `03be10c6d87ceeec29d4b09f0bac4db0d391c514`; test account label: `ORG_ADMIN7`
- journey: 共有障害報告URLを開いた後、通常の組織業務へ戻れるか確認。

390/360pxの画像とDOMで実画面を確認。共有URLからdashboardへ自動遷移し、組織パネルへ戻ってお知らせ・予定を確認できた。HTTP403は観測されず、詳細ページや操作modalへは到達していない。

組織名buttonのDOM領域は170×38px。360px画像では名称が短くしか見えない。位置は安定画面のDOM観測でx=16, y=654, w=170, h=38（別viewport/scroll状態ではxが変化）。参考画像: [組織パネル・360px](screenshots/org-08-org-360.png)。文字切れは追加確認候補。

住民はdashboard上で理由・必要権限を見つけられず、共有URLとの関係に迷ったと報告した。ただし別の住民Cは遷移前に「アクセスできません / 管理者権限が必要です」のtoastを見ており、製品全体に説明がないとは結論できない。権限説明の所在と遷移時点の理解に関する未実証候補として記録する。

制限: 組織名を推定accessible nameでクリックするlocatorはtimeoutした。実UIが無反応だったという意味ではない。DOM visible値はCSS寸法で、別パネルの画面外要素を表示崩れに数えない。
