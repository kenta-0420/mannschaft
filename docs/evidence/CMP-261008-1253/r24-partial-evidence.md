# CMP-261008-1253 R24 部分証拠の追加

最新main追従の採用は [静的UI入力の同値橋渡し](board-ui-main-bridge-r25-20261109.json)。現在統合 `af13a156be7ba03a2367c3ad92759c7d6ce3fb47`／main `038079b8c401645622024a5e6a6250be6c60b1a7`、通常generator1回exit0、台帳680行・一意680、生成680 campaign、生成JS SHA256 `07591fb6498abc4a8a05ff4d6c9dd2892c56463c06936c66441585e5cb67d7a7`。HTML raw同一、overlay/全B0 plan/120件25field純粋投影/新B・Cのfinding・feature/capability/decision/stage（B4）が旧受入入力と厳密同値のため、fa5/data06cの既存静的UI証拠を来歴不変で採用し、新ブラウザを実行していない。新bytesの通常validator/自己検証全体は各exit1/1で既存L46 FAILを保持する。実機は旧f938と差分影響評価を用い、fresh FE/通常CIは後続ゲート。

前回統合時点は `fa5aa4fae6b5ac04c1fbe632028f1c4b8d8129b1`（main `7dde81a72240a61650cafd0f78c4ee848da9df87` 追従）。実機sourceは引き続き `f93884c6a43ae9be5973cf005dfe52ebef7d1262`、初回統合 `de0cdda46ff29c304efd9279d07e0d0dcf722187` は登録時点の履歴であり、公開観測JSONも初回de0を保持する。現head直接実機は未実施。旧f938実機と差分影響評価を使い、fresh FE/通常CIが後続ゲートである。最新採用は [main追従の通常生成証拠](b0-main-follow-normal-output-r25.json)。現在台帳CMP 680行／一意680件、通常生成680 campaign、生成JS SHA256 `06c1aee980e985a4ab7270c38bade1e4879a1f366ce51ad9cd7c52c88fd2c2b6`。旧r24/r25 proofと生成候補は保持した。通常generatorを1回実行し、通常validatorと `--self-test-overlay` を新bytesで各1回実行したactual結果を新proofに保存する。global FAILは全体greenへ読み替えず、overlay/validatorコードの不変をhashで照合したため抜出し自己検証は既存exit0を同値再利用した。

前回登録時点の生成履歴（現在の最新採用は上記main追従proof）: 最終生成・検証は [通常生成680行の採用証拠](b0-final-normal-output-r25.json) に保存した。通常generator exit0の全量680 campaignを最終採用し、保存済normal exact bytesのSHA256 `7f72c3725b73184e511c4b704ff161060e5317ec2d56e01b0195a9f1584eb938` と一致した。最新mainの602行を保持する限定案は現台帳680行のうち78行を欠落させるため棄却した。602候補のL37 CMP行数不一致FAILと途中証拠は [中間候補検証](b0-overlay-checks-r24.json) に保持する。Cの「0人」は原表示「0件未割当」へ訂正し、意味・目的判定を変えず新bytesで生成・検証を取り直した。最終680行の通常validatorと `--self-test-overlay` はL46の既知GitHub参照集合不一致で両exit1。正本/copy/通常生成overlayはdeepEqual、旧61/61/118全field・トップrunId/environmentを保持して64/64/120。overlay限定のコード抜き出し自己検証はexit0で、empty・複数head/run受理、不正10例拒否。global snapshot同期済・全体greenとはしない。静的ボードUIと新head CIは別担当・別ゲートである。

R24実sourceは `f93884c6a43ae9be5973cf005dfe52ebef7d1262`（base `63d88c1eb4172b3df689d5cfc7a62602436c82e5`）。最新mainを通常mergeしたintegration `de0cdda46ff29c304efd9279d07e0d0dcf722187` とは来歴を分ける。integration headの直接実機は未実施、fresh FE/通常CIはこの記録時点で未取得。旧source→新headの影響評価に加え、fresh FEと通常CIが後続ゲートである。

[選別住民観測](resident-observations-r24-20261108.json) はA/B/Cの実runId、目的、private画像path/SHA256、介入・時間・観測制約を保存する。原runIdのr21 prefixは変更しない。Aは割当変更と再訪後の表示を達成、Bは予定確認未達、Cは読み取り確認範囲のみ達成。B/Cの2件だけを未実証の気づきとしてB0へ登録し、Aのlocator制約をbugにしない。BのDRAFT/未割当fixture、CのPDF取得・内容未確認を保持する。

root採択原本はprivate `.claude/campaigns/cmp1253-r24-root-review-20261108/root-r24-final-partial-adoption.json`（SHA256 `4c4cd3233c728bee2ef36a3b21b77dcec13d9bae54122b99f7d3878b3e8ca16f`）。native exit0で4 phaseが完走し、root監査で所有67 PID/start世代不在、35 stage終了・停止、Docker所有照会4件空、scratch ENOENTを確認。未記録の全親process不在は主張しない。

coded mainflowは実UIの割当PATCHでversion 0→1→2→3→4→5、stale version4は409・日本語通知・再送なし。API割当代行例外は空だがfixture/login/read-only GETは存在するためAPI通信0とはしない。主導線7枚＋role8枚の計15 PNGをrootが実閲覧済み（16枚は過去件数の誤継承）。住民7 PNGはrootと各actorが実閲覧済み。原 `visuallyChecked=false` は書換えず、画像実体は公開commitに含めない。DTO全fieldの完全照合は主張しない。

自然UIのslot moveは導線不在で未実施、第2 PATCH途中失敗はunitのみ。住民phaseは約44分で15分/SLA合格ではない。初期root中継のHTTPメタデータ混入後、ARIA/画像だけを使う指示に変更した制約も保持する。long整数契約全体、CMP1253全体、Issue close、正式完了JSON、台帳状態変更は対象外。

[旧R20部分証拠](../CMP-261008-1253-slot-version-precondition-20261108.md) は歴史として保持する。旧実機1194をR24へ付け替えず、R21 FE240 timeout、R22 Q archive60 timeout、R23 P entry30102 failをR24成功で根因解決扱いにしない。

B0生成・validatorの実結果は `b0-overlay-checks-r24.json` へ記録する。通常generatorの全量同期結果と、棄却した中間候補（overlay以外をdeep保持した限定採用）を区別する。全棚卸同期済・global validator合格を意味しない。


静的ボードUIはRoot受入済み。[選別UI証拠](board-ui-r25-20261109.json) に初回120/reload120、全25field deep一致、旧118原本/IDB投影保持、新B/CのB4カード表示を記録した。prototypeボード画像 [B](board-r25-B.png)・[C](board-r25-C.png) は原bytes/hashを保持しRoot実閲覧済み。住民private PNG本体は公開していない。current HTMLはrunId=originalRunId優先でoriginalRunId/sourceHead/trusted/sourceStatus/sourceEvidencePathも実保存し、今回120件のsource保持を確認した。run値そのものはカード本文に表示されない。generic originalRunIdPrecisionPassed=falseは全歴史runの意味精度を一括保証しない意味で、新B/C保存契約passedと分ける。旧版の常にトップrunIdを保存する制限を今回へ持ち込まない。fresh local IndexedDBを使用し、製品app/API/SQL/deployは0。total28.488秒/cleanup4.071秒、全cleanup検査合格、native Node/outer exit0をRootが確認した。旧2失敗は各Node1/cleanup正常/PNG0のhelper起因履歴として保持し、製品redへ昇格しない。最新通常生成/通常validator L46 FAIL/overlay限定passと、この静的UI passは別判定である。R24の3run/source f938/約44分/A達成・B未達・C読取範囲のみ/PDF取得未知/trusted=falseは不変。
