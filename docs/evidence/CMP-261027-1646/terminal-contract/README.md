# Test終端wrapper契約fixture（2026-10-28）

固定HEAD `f2817eb94326b413122c69f4b0c409288c8982a6`、[原本 backend/scripts/run-ci-tests-with-observability.sh](https://github.com/kenta-0420/mannschaft/blob/f2817eb94326b413122c69f4b0c409288c8982a6/backend/scripts/run-ci-tests-with-observability.sh)、git blob OID `1c430f09be7973a8ac3c17f44c98087d7549b677`、8136bytes、SHA256 `6021ce97f5681c324910bccde6b118116fd2219390cb4cf4f33babcf3857202d`。git blobから得た原本bytesを変更せずbashで実行した。判定処理の抜粋再実装なし。原本scriptの重複コピーは同梱しない。

Gradle/Javaは起動しない。各caseのproducerがsynthetic task-state.logを書き、command0または7を返す。CI_SHARD_INDEX=0、RUNNER_TEMP/CI_DIAGNOSTIC_DIRをcase単位に隔離。これはwrapper契約fixtureであり、実Gradleや公開artifactの終端欠損を再現した証拠ではない。

| case | exit | cacheHit | completed | command | shell | passed |
|---|---:|---|---|---:|---:|---|
| fresh | 0 | False | True | 0 | 0 | True |
| from-cache | 0 | True | False | 0 | 0 | True |
| up-to-date | 0 | True | False | 0 | 0 | True |
| no-source | 125 | False | False | 0 | 125 | True |
| missing-terminal | 125 | False | False | 0 | 125 | True |
| failed-terminal-command-zero | 125 | False | False | 0 | 125 | True |
| command-seven | 7 | False | True | 7 | 7 | True |

command非0=7は元exitを保持する。fresh正常だけ採取完了true、正当cache/up-to-dateはexit0だがcompleted=false。NO-SOURCE/終端なし/failed終端(command0)は125。command7のcompleted=trueはwrapperの診断採取完了でありテスト成功ではなくtestStepExitCode=7。

同梱[results.json](results.json)にexpected/actual/result全文とsynthetic終端入力、固定git blob OID/SHAを保存した。各caseのraw result/task-state.logとwrapper原本コピーはローカル原本に保持し、重複してGitへ追加しない。所有tempはmarker/UID/絶対pathを確認して撤去済み。既存assets変更なし。
