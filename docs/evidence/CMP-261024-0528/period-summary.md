# CMP-261024-0528 期間交差取得の修正証跡

- 修正source: `3a368126895188715941c8a2845d0e3490a9065b`
- main追従merge: `ef4b93a007d5f96e627175e40528bfd86398a8a3`（origin/main `e897e83c10`、競合なし）
- main差分は掲示板prod1/spec2・docs3の6ファイルだけ。BE・依存差分なし。
- worktree: `agent-dashboard-calendar-sparse-261024-0528`
- native実行場所: `/tmp/dashboard-be-unit-261024/backend`

## 根因と修正

個人一覧・team/org一覧・横断カレンダーの開始日時BETWEEN検索が、取得下限以前に始まり取得期間まで継続する予定を落としていた。owner/team/orgをANDで束縛した別queryを3個追加し、カレンダー取得入口6箇所だけを切り替えた。条件は `startAt <= to AND (endAt > from OR (endAt IS NULL AND startAt >= from))`。所有者・所属・可視性・対象者・ソート等の既存処理は維持。softlimit/attendance/ical等の開始日時検索は残す。新API・DBスキーマ変更なし。

## 赤の実測

- 修正前prodはbase `a4af2024f39059baa8838355dfeff8967309c3df` と同一。
- `ScheduleAuthzScopeContractIT$CalendarPeriod` の6経路（PERSONAL/TEAM/ORGANIZATION/CALENDAR/CALENDAR_TEAM/CALENDAR_ORGANIZATION）がすべて実assert赤、exit1、skip0。
- APIは200で開始下限・上端の2IDだけを返し、期待3IDのうち月跨ぎIDだけが欠落。例: CALENDAR_TEAM期待[39,40,41]→実[40,41]、ORG期待[48,49,50]→実[49,50]。
- red.log・TEST-*.xml・red-test-source.javaに当時の実行source/実assertを保存。
- 赤snapshotでは初回positive assertで全6件失敗するため、未到達の負例を赤の根拠にはしていない。その後、CALENDAR_TEAM/ORGの非所属者でも本人個人予定1件は返るfixture期待を是正し、緑側で負例まで到達した。

## 緑の実測

`green.sh` に記載のturnstile経由target test（exit0、7m22s）。

| 対象 | 成功 | 失敗/skip |
| --- | ---: | ---: |
| ScheduleAuthzScopeContractIT 全体（新期間6を含む） | 66 | 0/0 |
| PersonalScheduleServiceTest | 29 | 0/0 |
| CalendarColorResolutionTest | 15 | 0/0 |
| PersonalScheduleColorTest | 5 | 0/0 |
| ScheduleQueryServiceTest | 12 | 0/0 |
| 全ArchUnit engine（標準test依存） | 37 | 0/0 |
| 凍結ストア整合検査（標準依存） | 2 | 0/0 |

対象test合計127、必須Arch依存39、合計166成功。期間6件では前月開始、終了==from排他、endnullの単一点、start==to包含、期間外/0件、別所有者/非所属の共有非露出を確認。実MySQLは基底の使い捨てTestcontainers、共有DBは不使用。green.logとgreen-xmlへ保存。

- `bootJar` はturnstile経由exit0、32s（build.sh/build.log）。本番Javaソースのコンパイル・jar作成を確認。全unit全件を走らせたという意味ではない。
- `git diff --check` 成功。Checkstyle等の新規導入や門番変更なし。
- 再起動helper `restart-runtime.py` はrootへ引渡。既存専用PID46594のcwd/argv完全照合後、秘密環境値を出力せず引継ぎ、専用jarをbackupしdetached起動する。workerはhelper未実行。

## 実行方式の中断履歴

- 既定forkEvery180による全class discovery/re-forkが支配しAPI未到達だったため、root了解の上で自隊Gradleだけを中断。attempt-default-fork-interrupted.logに保存。未実行を赤/成功に数えない。
- WSLからWindows .git参照が解決できない同期試行を即中断。attempt-sync-interrupted.logへ保存、Windows生成changed-backend-files.txtを使い同期し直した。API未実行、テスト不具合の赤には数えない。
- 以後は規約記載のローカル設定 `-Pfork.every=0 -Pmax.parallel.forks=1` を使用。全ArchUnit依存・検査対象・timeoutは維持。

## main追従後

native FE unitへmain追加FE3ファイル、devへprod BulletinThreadDetail.vueを同期済み。追加全体typecheckは `NODE_OPTIONS=--max-old-space-size=8192 npm run typecheck` でexit0（main-typecheck.sh/main-typecheck.log、source ef4b93a）。既存duplicated-import警告のみ、型エラーなし。カレンダーFE127件の重複実行はroot方針により省略。E2E所有ファイルはworker commit対象外。
