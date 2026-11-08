# CMP-261024-1856 検証コピーの寿命管理・完了証跡

Issue #4086 / 実装PR #4090。検証用WSLコピーを作成する共通入口に、終了時の撤去と失敗時の残存検知を組み込んだ。製品UI、バックエンド、フロントエンド、DBの変更はない。

## 実装と検分

実装headは `80303ddcd88135499600fc82522fe421f592b267`。main追従の統合前 `54a6e84aaa3b325692e94af55d90e624a6fd8b3f` からrunnerとテストの内容は不変。殿であるroot自身がコード・テスト・コマンド正本と8つのACを検分し、所有境界、外部証跡、並行実行、途中失敗、子孫停止、残存検知を確認した。追加のCodex検分者は起動していない。

実装PRは2026-10-24T10:42:07Zにマージ済み。merge SHAは `decf1db27ea738b130401c369ff27afd3e1aad02`。マージ前にhead上の全checkの終了を確認した。最新Task-List PR Gateは [run40086876601](https://github.com/kenta-0420/mannschaft/actions/runs/40086876601) で10:41:16Zにsuccess。以前の台帳世代・PR本文のCMP重複による失敗はmain追従と本文整理で解消し、成功世代の終了後にマージした。

[Worktree cleanup guard run40086796884](https://github.com/kenta-0420/mannschaft/actions/runs/40086796884) は同じ実装headでsuccess。Linux copy lifecycleは18 tests / pass18 / fail0 / skipped0、既存safety fixturesは5 tests / pass5 / fail0。Harness・OpenAPI・secret scan・lockfile検査・Backend集約checkもsuccess。製品コード変更のないJava shard等は変更検知によりskippedであり、全Javaテストを実行したとは扱わない。

初期実装 `0a6a88d238` の独立WSL検証では、runnerへのSIGHUP後に `runnerReturnCode=-1`、`receipt.cleanup=not-created`、scratch残存を再現した。修正前に追加fixture「端末切断SIGHUPでも子を停止しreceipt保存後に撤去する」が `receipt.signal` のnullで失敗し、共通中断処理へHUPを接続してgreenになった。凱旋の残存確認ゲートも上奏前へ移動した。再現で作った領域は自身のPID世代と所有markerを確認して撤去した。

## 実WSLでの独立確認

2026-10-24、Ubuntu-24.04、Node.js v22.23.2、UID1000。rootが小さな独立Git fixtureを作成し、正常終了・非0終了・SIGTERM・SIGHUPを実行した。signalはそのケースのrunner PIDだけに送った。

| ケース | runner終了値 | receipt.signal | receipt.cleanup | 消失を確認したscratch |
| --- | --- | --- | --- | --- |
| 正常終了 | 0 | null | removed | `/var/tmp/mannschaft-verification-1000/run-qmgEMf` |
| 子コマンドexit7 | 7 | null | removed | `/var/tmp/mannschaft-verification-1000/run-5juuEw` |
| SIGTERM | 1 | SIGTERM | removed | `/var/tmp/mannschaft-verification-1000/run-mMnewJ` |
| SIGHUP | 1 | SIGHUP | removed | `/var/tmp/mannschaft-verification-1000/run-rOcOiS` |

全ケースで外部receiptを読み取り、コピー内のtracked内容が元と一致し、`.git` とignoredの `build/` がコピーされていないこと、元sourceのGit statusがcleanであることを確認した。source HEADは `4c6b479699aff0438fc0c30f545dba975a7d23cd`。最後の `--check` はexit0・出力なし。小規模fixtureとその外部証跡ディレクトリも撤去し、この必要項目だけを残した。全量ログやソースarchiveは保存していない。

各receiptの `baselineInaccessibleCount=2`。これは起動前から同UIDでEACCESのPIDについて開始時刻を照合した例外数であり、全プロセスの参照を読めたという意味ではない。

## ACとの対応

1. clean HEAD固定、dirty/untracked/gitlink拒否: 実Git fixtureとHEAD途中更新fixture。
2. ignored生成物を除外、元source不変: clean HEAD fixtureと上記実WSL4ケース。
3. 自分専用領域と所有検証: 並行実行、tracked symlink、所有marker変更fixture。
4. 必須の外部証跡と保存不能時の保護: 保存先境界・空/不正引数・receipt保存不能fixture。
5. 成功・非0・展開/起動途中失敗の撤去: clean HEAD、非0終了、起動失敗、展開失敗fixture。
6. 中断時の子孫停止と停止不能時の保持: SIGTERM、SIGHUP、脱走daemon、停止不能fixture。
7. 残存だけを検知し稼働中を保護: 脱走daemon、停止不能fixture内の `--check` と最終実WSL確認。
8. 運用への接続: CLAUDE、凱旋5.2（上奏前）、引継、陣払い、worktree運用手引きを検分。WSLコピー・専用cache・配布物・自身のプロセス/検証コンテナ/専用volumeを完了時に確認し、必要証跡だけ確定後に不要分を撤去する。

## 制約と保持対象

SIGKILL・ホスト障害ではfinallyは動かず、次の `--check` と完了ゲートで検知する。所有や停止・参照を確認できない場合は削除せず、非0と正確なパス・理由を返す。保持時には担当、用途、理由、再確認期限、再開条件を引継ぐ。共有daemonへの引渡しや常駐背景起動は禁止し、Gradle検証は `--no-daemon` を使う。

既存の管理外コピーや20.5GiBの旧退避archiveは独自source/config/evidenceの可能性が残るため、この新CLIで無差別削除しない。本対応は今後の蓄積防止であり、VHDXの物理圧縮や既存未知データの削除を行うものではない。今回の作業用worktree・枝は完了PRのマージ後に撤去する。
