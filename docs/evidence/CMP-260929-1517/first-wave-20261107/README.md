# 共有第一陣の住民観測登録

登録parentは **CMP-260929-1517**。CMP-260903-0701・CMP-260903-0657・CMP-260909-2153との共有第一陣で、代表実UI後に別actor・別BrowserContextの3住民が目的だけを受けて自律探索した観測を登録する。製品変更、仕様判断、完了行更新は含まない。

原runは `september-first-wave-exploratory-1b885b4d-r1`、製品sourceは `1b885b4d287aed0bd1893e15cee8490196591b9b`。今回の登録base `090d131309840bc58eb3a4b195f8a214507f8e49` と区別する。原本はWSLの `<WSL_EVIDENCE_ROOT>/september-first-wave-exploratory-1b885b4d-r1/`。合成テスト住民の観測要約・API path/status・閉鎖結果・原本hash・選別PNGだけをallowlistで保存した。[選別観測](resident-observations.json)に各原本のhash、元persona/run/source、目的達成範囲と制限を保持する。

- A（慎重管理×素直）: 勤務表/交代カード/自作schedule7の親削除確認まで。既存希望保持・2153の子slot警告を探索したとは扱わない。自分の7だけUI削除済み。作成成功後の一覧遅延は独立controlled再現未実施の候補。内部ID表示は既知CMP-261021-0650と対応する。
- B（スマホ390x844×表示/導線）: 次の勤務を確認、交代状況・保存投稿・公開範囲は未達。`/shifts/requests/summary` の403×2は管理者限定APIを一覧が無条件で呼ぶsourceと一致するが、1630の `/shifts/schedules/{id}/summary` とは別経路。38pxタブ・横overflowなしを観測。書込0、console未採取、物理端末/Safari未確認。
- C（部外者×境界）: shift1は404後toast消失で本文空、timeline1/4は404後もnotfoundと戻るが残った。ナビで自分の画面へ帰還。shift表示分岐は既知CMP-261105-0348 S4と同型だが、既知403と今回404を区別する。起動失敗2回は除外し、所有PID閉鎖後の最終1contextだけを採用する。

住民観測は全件 `trusted=false` / `observed-unverified`。新規確定finding・blocker・task-listへの昇格はない。A/Bの部分達成を保持し、探索実施と全目的達成、既存実機safe集計の採用assertionを混同しない。3住民のcontext/browser/Nodeは原閉鎖記録でlocal0。本登録担当は製品アプリ・DBを起動/変更していない。

累積正本 `docs/evidence/CMP-261015-1510/resident-overlay.json` の旧55 persona /55 journey /111 insight全字段をdeep保持し、3/3/6だけ追記して58/58/117とした。既存P01-P20へ変換しない。ignored copyは専用worktreeの `.b0-local/run-september-first-wave-20261107.json`。

正式generatorを現baseの正本から再実行した。保存済みボードは602 CMPで、現task-list正本649 CMPと乖離していたため、今回はoverlayだけでなくcanonical CMP同期も含む。差分は `generatedAt`・`sources`・`sourceCounts`・`verification`・`campaigns`・`b0RunOverlay`。features/capabilities等は不変。旧overlayを用いて同じ現正本から生成したbaselineと、新生成物のoverlay/時刻以外が一致することを機械照合した。[検証結果](validation.json)に追加47 CMPと既存状態差分のID、正本hash/commitを記録する。PR4234のoverlay外不変の先例とは異なる。

正本/copy/生成overlay一致、旧全字段保持、ID非重複、既存overlay validatorと10件の不正拒否自己検査は合格。通常validatorと全体 `--self-test-overlay` はbaselineと同じGitHub snapshot参照集合不一致で停止し、後続global条件は未検査。validator/生成器を緩めていない。

専有の新規contextで静的HTMLを直接開き、[ボード取込](board-ui-safe.json)を確認した。IndexedDBは111→117→reload後117件、全ID集合一致・重複0。UIはstage別合算95→101→101件で、旧表示ID集合と全旧保存字段を保持し新6cardだけ追加。新cardのpersona・登録元・元run/sourceを含むdetail・証拠字段を照合し、reload後の全保存字段とstage別UI ID集合も一致した。[新card画像](board-new-card.png)を実見した。

既存16件はIndexedDBに保存されるがstage未設定のためB0〜B4のUI投影外となる。安定した旧stage件数はB0=40/B1=16/B2=16/B3=0/B4=23。今回の新6件を加えたUI101と、保存総数117を区別する。

[不採用4試行](board-ui-excluded-attempts.json)は保持する。初期3回はstage別表示・非同期完了待ちのharness条件不備、4回目はVM別realm配列のprototype差による比較失敗。実Playwright-core `lib/coreBundle.js:18397-18400` は `predicate()` のPromiseをtruthyと判断するため、async predicateのresolved falseでもpollが終わる。同期predicate `insights.length === expected` でHTMLの全保存→read→一括代入完了を待ち、直後に独立したIndexedDB ID集合を照合するようharnessだけ修正した。製品/library変更なし。5回目を採用し、context/browser閉鎖・Node自然exit0・harness Node残0・生成JSの正式復元を確認した。

既存importerはstored runIdに累積トップの旧値を使い、元run/sourceの字段を保存しないため `originalRunIdPrecisionPassed=false`。新6件はdetailと選別JSONに元run/sourceを保持する。本陣のローカルHTML・既存ブラウザ・IndexedDBは変更していない。

原本（生の観測ログ・スクリーンショット元データ）は非公開ローカルに保持している。本ディレクトリのパスとポートは公開用に伏せ字（`<WSL_EVIDENCE_ROOT>`、`<VERIFY_BE>`、`<VERIFY_FE>`）へ置換済み。
