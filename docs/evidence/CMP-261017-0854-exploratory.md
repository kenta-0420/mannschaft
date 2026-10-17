# CMP-261017-0854 独立住民探索

状態: **passed**。対象の3文言に未解決findingはない。3住民は独立認証・別目的で並列探索し、全員目的達成・close exit0。rootが対象画像と観測区分を独立確認した。d5ec700e24と最終fc37284e91の製品sourceは同一（後続は証拠文書だけ）。

| 住民 | 目的・行動 | 結果・制約 |
|---|---|---|
| admin / mobile | 390/360幅で設定/予定/詳細/追加取消を通常操作 | de見出し・追加・作成者を読めた。横パンなし、追加44px。作成送信やデータ作成はしない |
| member / locale | 初期jaから設定UIでdeを保存し、予定詳細・キープ・設定からenへ変更 | de/enの対象3文言、予定名/日時を確認。追加編集は非表示。キープ入力は未入力・未送信 |
| other_admin / boundary | 自チームの予定詳細→対象の非所属チームへ直URL→ダッシュボードへ戻る | 自チームGET200、越境team/modules403・他人の予定非表示、帰還GET200。拒否は正しい境界として分類 |

## 独立性と保全

同runの3persona/journeyを区別する。admin transcript-1792216236916-27543、member transcript-1792216259777-27777、other_admin transcript-1792216328066-28334。filename epochの開始は順に05:50:36.916 / 05:50:59.777 / 05:52:08.066 UTC、最終writeは05:55:06.445 / 05:55:55.812 / 05:54:28.956 UTC。少なくとも05:52:08～05:54:28 UTCで3driverの活動区間が重複する。これはブラウザー活動の開始と最終writeの記録であり、区間全体の連続操作を示すものではない。private transcriptは公開しない。

元要約はresident-mobile.json / resident-locale.json / resident-boundary.json、画像は各専用出力に保全。退避済み原本は `.claude/private-archives/schedule-i18n-20261017-0854/role-evidence/` も参照し、秘密を除いた検証要約だけを本書へ記載する。rootがmobile360作成者、en1024付近の表示、他チーム拒否の画像も実視認した。

## 対象外観測とB0

周辺の日本語（作成dialogや未選択表示等）、既存小tap（キープ/試合記録/進む等）、delegations404、狭幅の予定行クリック1timeout（1920幅では成功）は原因未確定・変更範囲外。住民所見だけでblocker/taskへ昇格させず、B0箱に観測登録する。B0箱UI登録はpassed。専用team schedule keyが正本にないため、意味の異なるshift/village-eventsは使わずcross-cutting（その他横断）へ「CMP0854/F03.1・F05チーム予定、専用key未定義」と注記した。隔離コピーの初期stageフィルタだけを未設定へ切り替え、正本board HTML/data・元IDB・公式plan/coverageは変更していない。

最終receiptは `.claude/evidence/schedule-i18n-20261017-0854/b0-viewer/final-receipt.json`、UI証拠は同dirのui-ingestion-proof.json。rootがui-reload.pngを実視認し、3persona/7insights表示・専用IDB reload後7件/unique7・pageerrors0を確認。最小env/localhost限定再実行も成功、外部要求0・専用server停止。参照3JSON+4PNGの全7SHA一致、元board2fileのbefore/after hash一致。正本validateRunOverlayで7件受理・不正8件拒否。これは今回overlayと箱UIの合格であり、global board全文の既存baseline不整合を解消した主張や、公式B0 journey/coverage成功の主張ではない。
