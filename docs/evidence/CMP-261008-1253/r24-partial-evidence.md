# CMP-261008-1253 R24 部分証拠の追加

最終生成・検証は [通常生成680行の採用証拠](b0-final-normal-output-r25.json) に保存した。通常generator exit0の全量680 campaignを最終採用し、保存済normal exact bytesのSHA256 `7f72c3725b73184e511c4b704ff161060e5317ec2d56e01b0195a9f1584eb938` と一致した。最新mainの602行を保持する限定案は現台帳680行のうち78行を欠落させるため棄却した。602候補のL37 CMP行数不一致FAILと途中証拠は [中間候補検証](b0-overlay-checks-r24.json) に保持する。Cの「0人」は原表示「0件未割当」へ訂正し、意味・目的判定を変えず新bytesで生成・検証を取り直した。最終680行の通常validatorと `--self-test-overlay` はL46の既知GitHub参照集合不一致で両exit1。正本/copy/通常生成overlayはdeepEqual、旧61/61/118全field・トップrunId/environmentを保持して64/64/120。overlay限定のコード抜き出し自己検証はexit0で、empty・複数head/run受理、不正10例拒否。global snapshot同期済・全体greenとはしない。静的ボードUIと新head CIは別担当・別ゲートである。

R24実sourceは `f93884c6a43ae9be5973cf005dfe52ebef7d1262`（base `63d88c1eb4172b3df689d5cfc7a62602436c82e5`）。最新mainを通常mergeしたintegration `de0cdda46ff29c304efd9279d07e0d0dcf722187` とは来歴を分ける。integration headの直接実機は未実施、fresh FE/通常CIはこの記録時点で未取得。旧source→新headの影響評価に加え、fresh FEと通常CIが後続ゲートである。

[選別住民観測](resident-observations-r24-20261108.json) はA/B/Cの実runId、目的、private画像path/SHA256、介入・時間・観測制約を保存する。原runIdのr21 prefixは変更しない。Aは割当変更と再訪後の表示を達成、Bは予定確認未達、Cは読み取り確認範囲のみ達成。B/Cの2件だけを未実証の気づきとしてB0へ登録し、Aのlocator制約をbugにしない。BのDRAFT/未割当fixture、CのPDF取得・内容未確認を保持する。

root採択原本はprivate `.claude/campaigns/cmp1253-r24-root-review-20261108/root-r24-final-partial-adoption.json`（SHA256 `4c4cd3233c728bee2ef36a3b21b77dcec13d9bae54122b99f7d3878b3e8ca16f`）。native exit0で4 phaseが完走し、root監査で所有67 PID/start世代不在、35 stage終了・停止、Docker所有照会4件空、scratch ENOENTを確認。未記録の全親process不在は主張しない。

coded mainflowは実UIの割当PATCHでversion 0→1→2→3→4→5、stale version4は409・日本語通知・再送なし。API割当代行例外は空だがfixture/login/read-only GETは存在するためAPI通信0とはしない。主導線7枚＋role8枚の計15 PNGをrootが実閲覧済み（16枚は過去件数の誤継承）。住民7 PNGはrootと各actorが実閲覧済み。原 `visuallyChecked=false` は書換えず、画像実体は公開commitに含めない。DTO全fieldの完全照合は主張しない。

自然UIのslot moveは導線不在で未実施、第2 PATCH途中失敗はunitのみ。住民phaseは約44分で15分/SLA合格ではない。初期root中継のHTTPメタデータ混入後、ARIA/画像だけを使う指示に変更した制約も保持する。long整数契約全体、CMP1253全体、Issue close、正式完了JSON、台帳状態変更は対象外。

[旧R20部分証拠](../CMP-261008-1253-slot-version-precondition-20261108.md) は歴史として保持する。旧実機1194をR24へ付け替えず、R21 FE240 timeout、R22 Q archive60 timeout、R23 P entry30102 failをR24成功で根因解決扱いにしない。

B0生成・validatorの実結果は `b0-overlay-checks-r24.json` へ記録する。通常generatorの全量同期結果と、棄却した中間候補（overlay以外をdeep保持した限定採用）を区別する。全棚卸同期済・global validator合格を意味しない。
