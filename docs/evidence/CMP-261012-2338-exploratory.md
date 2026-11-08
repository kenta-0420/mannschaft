# CMP-261012-2338 住民探索

正式実機後、run `october-wave4-residents-20261027` と固定source84944f1cで3独立住民が探索した。殿のexploratory判定passedは探索実施の判定であり、全利用目標達成や未実証の気づき解消を意味しない。全rawは `trusted:false` / `observed-unverified` を維持。

- R1：多言語de・素直な管理者actor3。予約機能の有効化と開始場所の理解は未達。deでカード名・説明が日本語となり選択できず。原因未調査。44px未満の実要素寸法、低いviewportで下スクロール必要を観測。言語は実UI ja→de→ja保存後reload復元済み。
- R2：スマホ片手・表示崩れを気にするMEMBER actor5。390×844/360×800で予定と予約確認の空状態には到達、新規予約入口は探索範囲で見つからず部分達成。文字省略、幅40pxのタブ送り、高さ30pxの予定リンク、28×32pxの開閉を観測。意図・原因・親ヒット領域は未確定。
- R3：ADHD傾向・中断放棄する別team管理者actor6。1440×900で自teamと公開別teamを別タブ比較、設定へ復帰し説明を確認。機能選定・有効化は未達の部分達成。locator timeoutや遷移中PNGを製品欠陥にしない。

全住民のmodule/権限変更・新規データ作成0、全browser/context閉鎖。ページ全体の横はみ出しは観測なし。導線・言語・小操作領域の気づきは原因未検証で保持し、新規confirmed blockerや今回削除の回帰に昇格しない。R1復元を含む代表5PNGを殿が実見済み。

[代表PNG](october-fourth-wave-20261027/image-index.json) / [source対応](october-fourth-wave-20261027/source-correspondence.json)。最新main baseline12/12/19と既存W3累積15/15/31の全object・metadataを保持してW4独自3persona/3journey/11insightを追加し、累積18/18/42の未commit草案とした。W4 provenanceはrunEnvironmentsの専用runへ追加し、元runId/environmentとW3metadataは変更しない。

最終採用：PR #4156 head 0ac4c26439bbc837ca3214017d95ad0c323400b5、merge 09854466d335cd5e99b9dc6b80d69e683d761644（2026-10-27T05:49:03Z）。全check完了後の通常採用でat-merge eligible=true。[最終採用メタデータ](october-fourth-wave-20261027/source-correspondence.json)のadoption節へ関連付け、上記の原source・初回失敗・住民未達/partial・原観測SHAは変更しない。
