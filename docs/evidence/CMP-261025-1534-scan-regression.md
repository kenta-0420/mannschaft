# CMP-261025-1534 秘密情報スキャンのmerge差分回帰

対象は `.github/workflows/secret-scan.yml` の検出漏れ修正と回帰試験である。実秘密の漏えいを確認した記録ではない。2026-10-25に修正着手裁可を受け、着手PR #4115で台帳を実装中へ変更した。実装は[PR #4117](https://github.com/kenta-0420/mannschaft/pull/4117)で同日13:33:31Zに通常squash mergeされた。

## 原因と修正

固定版gitleaks 8.28.0の既定Git履歴走査は、merge時だけ追加された行を検出しない。差分・全履歴の両方へ `--diff-merges=first-parent` を渡し、mergeと第1親との差分も走査する。履歴の辿り方を制限する `--first-parent` は使わず、side branchで追加後、merge前に削除された行も走査する。

同版は不正revisionを指定してもexit 0を返す実例があったため、差分scan stepで `git log --format= --max-count=0 "$range"` を先に実行する。rangeはmerge差分オプションと分離し、Gitの非0終了を既存の `set -euo pipefail` で伝播する。allowlist、ルール、scannerバージョン、PR/pushのrange決定、fetch-depth、ゲート名は維持した。

## 試験方法

```sh
python3 scripts/test-secret-scan.py ./gitleaks
```

標準Pythonライブラリだけで一時Git repositoryを作り、workflow内の限定したdetectコマンドとGit preflightを取り出して実行する。shellや任意コマンド列は実行しない。scannerはworkflowの固定版と一致することを確認する。ローカル検証には既存の検証済みWindows版を使い、SHA256 `54230c22688d19939f316cd3e2e040cd067ece40a3a8c5b684e5110c62ecbf52` が既存の公式配布検証証拠と一致した。

値は試験中に生成した合成値で、認証に使用しない。子processの出力は捕捉し、標準出力・標準エラー・SARIFに未マスク値がないことをassertする。fixtureとSARIFは一時ディレクトリへ隔離し、終了時にcleanupする。本来のscan artifactへ混入させない。各processは20秒、suiteは300秒、CI stepは5分を上限とする。

| 条件 | 試験名 |
|---|---|
| AC-1: merge専用追加をPR/push相当rangeで検出 | `test_merge_only_addition_in_pr_and_push_range` |
| AC-2: 同追加を全履歴で検出 | `test_merge_only_addition_in_full_history` |
| AC-3: 通常commitの検出を維持 | `test_ordinary_commit_detection_is_preserved` |
| AC-3追加: side branchで追加後merge前に削除しても履歴から検出 | `test_side_branch_added_then_removed_value_remains_detectable` |
| AC-4: 対象なし・空rangeで成功 | `test_no_detection_and_empty_range_are_successful` |
| AC-5: exact path AND exact valueだけ除外 | `test_exact_allowed_path_and_value_are_excluded` |
| AC-5: 別path同値を検出 | `test_allowed_value_in_other_path_is_detected` |
| AC-5: 同path別値を検出 | `test_other_value_in_allowed_path_is_detected` |
| AC-6: 不正revisionでfail-closed | `test_invalid_revision_fails_closed_without_value_leak` |
| 追加: 3親のoctopus merge専用追加を全履歴で検出 | `test_octopus_merge_addition_in_full_history` |
| AC-7: 未マスク値の出力なし | 全scanの標準出力・標準エラー・SARIF検査 |
| AC-8: 固定版維持・正式CI | suiteの固定版照合、実装headのCIで別途確認 |

## red / green

修正前workflowで10件を137.683秒で実行し、失敗は4件だった。PR/push相当range・全履歴・octopus mergeの各専用追加、および不正revisionがいずれもexit 0で、必要な非0終了を返さなかった。残る6件の対照は成功し、環境準備エラーではないことを確認した。不正revisionの修正後期待値はGit preflightのexit 128とした。

修正後の同試験は10件すべて成功し、146.365秒で終了した。不正revisionはGit preflightのexit 128、merge専用追加・通常commit・削除済みside branch履歴は各exit 1と1件検出、除外対象と安全なrangeはexit 0だった。未マスク値の検査もすべて成功した。

## 正式CI・検分

- 実装PR headは `7606534ed3be06ede166c54fe5dd1ac02db53d45`。マージ前に全17 checkのSUCCESS 11件・SKIPPED 6件、失敗・未完了0件を確認した。
- 同headの[秘密情報スキャン run 40197838580](https://github.com/kenta-0420/mannschaft/actions/runs/40197838580)は成功。実ログで10試験が3.381秒ですべて成功したことを確認した。
- rootが実差分、AC対応、red/green原本、正式head CIを検分し、指摘0件で最終承認した。
- マージcommit `3e4629bbebb5a1ce6cbd8c1b8e92082b09853ad8` の[main push run 40198158903](https://github.com/kenta-0420/mannschaft/actions/runs/40198158903)も成功。新回帰stepの実ログは10試験4.212秒で成功し、mainの実差分スキャンも成功した。
- このpushでは週次全履歴jobはSKIPPED。実リポジトリの週次全履歴scanを今回実行したとは扱わない。合成Git repositoryのfull-history試験と区別する。
- 正式なAC・実装head・同head CI・scopeは[完了証拠JSON](CMP-261025-1534.json)に記録する。

## 適用範囲

変更対象はCI workflow、回帰script、開発証拠と台帳で、scopeはtooling。アプリの画面、API、認可、業務データ、UI導線は変更しないため、アプリ実機と住民探索は対象外とする。代わりに実Gitと実scannerの検証を行い、モックの検出結果で代用しない。週次全履歴の実リポジトリ走査は今回の小fixture検証とは区別する。新しく発見された検出結果を、この修正だけを理由にallowlistへ追加しない。
