# I01 統合sourceの独立住民観測（未完了）

製品source `d383c4494d5e6debb5cdb833f654fa5e5ae0430b`、元runId `CMP0616-I01-cmp0649-a875668f1796` の独立住民3体を登録する。全員の目的達成はpartialであり、実施3人と目的達成を区別する。正式なexploratory完了・AC合格・coverage合格・campaign完了は認定していない。全3観測は `actual-resident-artifacts` / `trusted: false` / `observed-unverified` を維持する。

- A（ADHD傾向×中断・放棄）: 18時担当の再発見、B候補と理由入力、中断後の担当への復帰を確認。提出は自動承認審査に2回拒否され未送信。再読込後に未提出の理由欄が空だった観測は保存不具合と断定しない。提出済み状態は未検証。
- B（一般×素直）: 既存勤務と自分の予定への復帰を確認。新18時相談はA未送信に依存し、発見・受諾・成立確認は未検証。受信データの消失や導線不存在とは断定しない。
- D（高齢・低リテラシー×隙間狙い）: dashboardから既存変更依頼と交換一覧、18時の現在割当、通常画面への復帰を確認。新相談処理と変更後の旧新担当予定照合は未送信依存で未実施。S3/Xのprimaryチーム403とシフト・変更依頼404を観測し、情報は得られなかった。S3のaux PUBLICチーム閲覧は別の正常閲覧として区別する。

各要約は `I01-resident-A-summary.json`、`I01-resident-B-summary.json`、`I01-resident-D-summary.json`。root裁定は `I01-root-adjudication-summary.json`。巨大DOM、資格情報、全rawは含めず、private証拠への参照とSHAだけを保存した。AのjourneyIdは原報告に無いためrootが登録時付与した。A/B原報告末尾の文字化け補正は観測事実の変更ではない。

原標準runの失敗は維持する。rootの別V2導線確認は測定範囲で成功したが、標準runや未実施の提出・受諾・承認経路の合格へ読み替えない。既存CMP-261010-1432-A / PR #3832の変更依頼審査UIはrootが操作不在を確認済みだが、今回の修正裁可・取り込み・修正は無い。未実証観測をblockersや新CMPへ昇格していない。campaignは未完了。

rootの実監査では住民3Nodeと所有browserの不在、および業務4表（shift_swap_requests、shift_slots、shift_assignments、shift_change_requests）の前後hash不変を確認した。今回の文書workerはbrowser・Node・製品runtimeを操作していない。

累積正本の旧16 insight / 9 persona / 9 journeyを全値保持して3件だけ追加し、19 / 12 / 12とした。toprunIdは `october-wave2-login-resident-overlay-20261026` のまま。UIがIndexedDB.runIdへtoprunIdを保存する既知限界があるため、実runId/sourceHeadは各宣言・detail・証拠へ保持した。旧7safeファイルは変更していない。

旧全体validatorのGitHub snapshot参照集合不整合によるFAILは維持する。補助overlay構造・deep保全検証を全体GREENと扱わない。Python補助検証で正本・生成overlayのdeep一致、旧16/9/9全値保持、旧safe7ファイルのGit blob不変、ID一意性、persona/journeyのrun・head整合、19/12/12、置換文字・連続疑問符なしを確認した。diffチェックも成功した。

rootは隔離headlessの気づき箱実UIで、旧16件の取り込みから今回19件への追加、旧16 ID保持、新3 IDが各1件、reload後も19件不変、実runId/sourceHeadの本文表示を確認した。`I01-board-import-summary.json` は安全な結果要約であり、住民による製品画像の視覚確認を意味しない。利用者の既存browserは更新していない。検証browser閉鎖・子プロセスなし・専用temp撤去を確認し、全体validatorのFAILとcampaign未完了を維持する。
