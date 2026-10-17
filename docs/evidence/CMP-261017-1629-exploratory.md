# CMP-261017-1629 独立住民探索

探索stage: **passed（原観測と下記限界を保持）**。実装PR #3952 / head `3a40f88ca967c0127dd6e29f40534be7913189af`。root-final-stage-verification.jsonに最終判定を保存した。最終headのCI4run成功と実装mergeをAPIで照合済み（検分文書参照）。

## 3住民と修正後の影響再確認

初回3住民はsource fc8109b042bf638cff1e5a7bbb26257cdb9a1314で実UI探索した。原JSONのsource・observed-unverified/trusted=falseを保ち、修正後headへ書き換えない。原観測から製品欠陥を確定し、再確認と出荷を判断する担当はrootである。

| 住民 | 初回の目的と観測 | 修正後の再確認 |
|---|---|---|
| admin/mobile | de/en×360/390でteam予定読解/編集取消とorg/個人calendar。14画像実見。calendar横欠落により初回は部分達成 | 最終headでde/en×360/390全4の日時・状態・作成者・長い所属名を読み、close復帰。7画像実見、全context/shutdown終了exit0。rootは原JSONとen390詳細/en360閉後を独立実見 |
| member/locale | 英語で所属team予定を探し日時/状態/作成者を理解。17画像中1枚usable=falseを保持。View allの/events誤導線を観測 | d4f434c8478380b8540f79388d8368a2386cf254で1440/1152のView all→team /schedule、詳細読解とUI復帰。rootは原JSONと画像を独立確認 |
| other_admin/boundary | 自team予定読解、非所属team直URLの拒否案内、390/360でdashboard帰還。7画像実見 | 同d4f434で真mobile390/360自予定読解・他team403・44pxreturn→dashboard。rootは原JSONと画像を独立確認 |

root-postresident-review.jsonがmember/boundary再確認sourceと最終headの製品差分0を照合する。再確認はresident-mobile-reverify.json / resident-locale-reverify.json / resident-boundary-reverify.jsonへ分け、初回JSONを上書きしない。全driver/contextは正常終了、account解放済み。

member再確認のAPI計数0はdelegation限定driver記録のためで、全アプリAPI不存在を意味しない。boundaryのdelegation200計4回は各幅2mount。真mobile contextのlocator.clickでありtouch tap固有実証ではない。125%相当viewport近似を実ブラウザズームとして扱わない。

## B0最終dispositionと独立viewer

初回3persona/3journey/8insightのsourcefc810と3JSON＋8PNGの11hashを保持。rootVerificationを別metadataとして最終headと再確認sourceへ関連付け、calendar横欠落とUpcoming誤導線の2cardを修正再確認済みへ更新した。resident-reverification-manifest.jsonの再確認JSON3件＋画像4件、計7filesのhashが一致する。

正本validateRunOverlay関数と不正8変異の限定検査はpass。全体validatorはsnapshotのdocs/task-list.md欠落ENOENTで未検証であり、正式B0 runner/coverageの合格ではない。独立viewer最終再実行は8cards/8rows、reload後8unique、宣言証拠18件HTTP200、外部通信0、plan/coverage不変、pageerrors0、wrapper exit0。元board/元IndexedDBは変更しない。

初回表示はinitial-*に保全。rootは最終ui-first-pass.pngを実見し、ui-reload.pngも同SHA256 `0d2440371e389c46bf69a413b269722d969cbed6cbdef02514ff255d53ecf2f3`と照合した。専有server/browserは正常終了。元receiptはb0-viewer/ui-ingestion-proof.jsonとREADME.md。

安全原本基準は `.claude/evidence/schedule-surroundings-20261017-1629/`。再確認JSONのSHA256はmobile `ef09406e3b34522263e9826af76c019c18af2ae21f6b14902dbeb2d2f0fabcf7`、locale `f527c410b4c1c72dffd3423cd68d85f74be604d1ba2c189b66509d4dd2cabfda`、boundary `e19383ee72f019cafbda37a8edf205a8195ca137c5d69b538044684ad2dc365b`。秘密・認証state・private transcriptを公開しない。今回以外の未実証観測は解消扱いせず、安全観測archiveは全361entryの独立読戻し一致を確認済み（実機文書参照）。撤収は未実施・今回専有資産の本人承認待ち（runtime-cleanup-final.json）。