# CMP-261024-1830 完了検証記録

状態: **完了**。製品マージ・検証と、本人の「削除許可」に基づく専用資産撤去を完了した。

[製品PR #4089](https://github.com/kenta-0420/mannschaft/pull/4089) は2026-10-24T11:50:16Zにmerge済み。実装PRの最終headは `1c97bfaa6d8de6e6fbb9bd4ef0d5b8fe941545b9`、merge commitは `783a391314bc9bd42f754b872bb331684d09269e`。実機の製品sourceは `af5373aa64252c615067ac965b687067e49ba09a` で、その後のmain追従は業務FE/BE変更なしと殿が照合した。

- 検分: 殿自身が差分・AC・API契約・scope照合・44pxを確認、追加finding 0。
- 単体: WSL native・新lockで担当3spec/21件成功。対象ESLintとNuxt型検査（8GB heap）成功。Windows Nuxt setup timeoutは未実行として分離し、成功証拠に数えない。
- 最終CI: [Install/unit](https://github.com/kenta-0420/mannschaft/actions/runs/40089719847)、[Smoke](https://github.com/kenta-0420/mannschaft/actions/runs/40089719830)、[lockfile](https://github.com/kenta-0420/mannschaft/actions/runs/40089719840)が同一最終headで成功。BE変更なしのshard skippedは対象外であり、実行成功件数に含めない。
- 実機: 正式attempt6は4成功、skip 0、unexpected 0、flaky 0。チーム・組織それぞれの管理者/一般メンバーの導線と、非所属/別tenant/未認証の直打ちを確認。[集計](real-summary.json)。
- 探索: 3名別contextで実UI/実DBの工程を実施。A（一般×素直）・C（高齢低IT×中断放棄）は目的達成。B（モバイル×寄り道）は人数・理由・専用一覧へ未到達で目的未達。殿は工程passedと判断し、全員目的達成とは区別した。確定新不具合0。

## 回帰証拠の性質

DSTは固定UTC `2026-10-24T03:00:00Z` をLAへ変換した `2026-10-23T20:00:00-07:00` から30日後の演算を単独再現した。修正前式は `2026-11-22T20:00:00-07:00`、利用者TZの壁時計として再解釈した修正後式は `2026-11-22T20:00:00-08:00`。取得範囲の境界assertを担当単体へ追加してgreenを確認した。これは演算の最小再現であり、製品全体の先行redテストとは呼ばない。FE通常修正は試練の先行red対象外。

戻りリンクは[実測before](backlink-before.json)で装飾字を含むaccessible名、[実測after](backlink-after.json)で両scopeとも「出席確認一覧」だけになることを確認した。装飾アイコンのaria-hidden 1属性だけを変更した。

## B0実観測登録と限界

3persona・3journey・5insightを `actual-resident-artifacts` / `trusted:false` / `observed-unverified` として、ignored `.b0-local` と本陣のローカル証跡へ保存した。既存runner/dynamic runnerの実行やB0 coverage合格を意味しない。原本にないIDは登録時付与と明記し、固定P01〜P20へ偽って変換していない。Cの一覧期間初期化・±表記、Bの別RSVPへの寄り道・既存calendarの文字幅0・通知リンクのページ未検出は未検証の観測/改善候補で、今回の不具合やblockerへ昇格していない。

generatorは44 feature・95 capability・588 campaignを生成しexit 0。`validator --self-test-overlay` は既存GitHubスナップショットの参照集合不一致でexit 1、overlay自己検証へ未到達。base HEADでも131参照不足（余分0）、draft台帳では132参照不足で、今回の追加参照だけで起きた不一致ではない。snapshotを改変して成功扱いにしていない。生成overlay部分・source hash・比較結果・実行ログは本陣 `.claude/evidence/CMP-261024-1830/exploration/` と専用WTのignored `.b0-local` に保全した。自隊生成board-dataだけを復元し、本陣の既存dirty board-dataは変更していない。

## cleanupの状態・限界

- 製品WT: rootが今回26fileのorigin/mainとの差分0とtracked/untracked cleanを確認。frontend/node_modulesは本陣へのjunctionだったためtarget一致を確認してリンクのみを非再帰で外し、本陣node_modulesの存続確認後に非force worktree remove・merge済みbranch -dを実行した。
- E2E WT: 自作一時5fileをprivate-runtimeの専用e2e-reproduction-scriptsへSHA256一致で保全し、元を削除してcleanを確認。rootがcommitted2fileのorigin/mainとの差分0・変更2fileのみ・対象絶対path配下を確認後、非force worktree remove成功。branchはcherry-pick/squashにより-dが拒否されたため、全2commitの変更同一を照合しmerged-local例外の-Dで撤去した。製品remote branchも削除済み。
- 専用WSL資産: 本人の「削除許可」を受け、2026-10-24T23:06:14.811981Zに専用2root・2container・volume・自隊processを撤去。専用4port不在、共有mysql/valkeyのID・稼働状態不変を確認。最新main `949971720f` のrunner checkはexit 0（legacy unmanaged copiesは対象外）。[公開用最小集計](cleanup-summary.json)。共用npm/Gradle cacheは削除対象に含めない。
- 既存資産baseline: rootの最終checkはexit 2（対象85・削除0・保持94・stale65・全77）。事前checkのexit 1（全78・stale65・保持95）とは時点が異なる。今回の製品/E2E WTは撤去済み。既存他戦役のdirty/locked/junction/feature/orphanは削除せず、非0を合格扱いしない。最終ログは本陣 `.claude/evidence/CMP-261024-1830/worktree-check-final.txt` に保全。

今回JSONの構造・参照と台帳7列を検証した。正式completion-evidence CLIの `--input` モードで、実Git/treeから同等の変更証拠選択（今回1件）を行い、実GitHub API取得のmerge済み実装PR（26変更file・22check）・同一headの成功CI 3run metadataと照合し、exit 0を確認した。全過去証拠のgit show反復によるローカル全走査は未完中断し、合格証拠に数えない。
