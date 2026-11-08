# 住民A: SYSTEM_ADMIN / スマホ片手操作

- source: `frontend/test-results/alicization/october-first-wave-20261026/resident-mobile-admin/`
- source runId: `october-first-wave-20261026`; sourceHead: `03be10c6d87ceeec29d4b09f0bac4db0d391c514`; test account label: `ADMIN4`
- journey: 共有されたerror report #10のTimelineで長文履歴を末尾まで読み、調査コメントを残す。

390×844と360×800のmobile/touchでDOMと画面を確認。document `scrollWidth/clientWidth` は両幅とも390/390、360/360。混在長文、長URL、ASCII2000文字と改行の末尾までTimeline内で折り返した。

調査コメントPOSTはHTTP 200、入力欄は0/2000に戻った。Timelineへ戻った後の画面では新コメントを確認できず、観測ログにも保存後のTimeline再取得は記録されていない。保存失敗とは断定せず、再読・表示の未確認候補として登録する。参考画像: [Timelineへ戻った状態](screenshots/admin-11-memo-history-360.png)。

概要タブの担当者ボタンは360pxで44×140pxの縦折返し。解除buttonの右端x=337がカード右端約x=309を越えた観測。参考画像: [memo form](screenshots/admin-08-memo-form-360.png)。タブ送り「次へ」は40×57pxで幅44px未満。これらは今回の長語折返し修正範囲外の未実証候補で、blocker/CMPへ昇格しない。

制限: 最初のdriverはstdin閉鎖で操作不能となり、A/Bは逐次に再loginした。並行別contextの同時利用ではない。tabをbutton roleで探す試みはtimeoutし、tab roleでは成功。UI不備として扱わない。
