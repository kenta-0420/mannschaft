# 検証runnerのarchive終端不具合の完了証拠

対象は CMP-261024-2357 / PR #4100。既存0647調査で発見した共通toolの不具合だけを切り出した。実装headは `4ed9a8a4857ef528e6f9a9d001c6bb440c3c728f`、mergeは `184750f1b90b9281e87ef945fb33738d53e23559`。

## 問題と修正

正常な固定HEADの組込み `git archive --format=tar` とtarが両方exit 0でも、tarが終端の2連続512-byteゼロ（EOA）で早期に閉じるとNode pipelineが `ERR_STREAM_PREMATURE_CLOSE` になった。GNU tarへ `--ignore-zeros` を追加し、実EOFまで読む。allSettledの失敗述語、非0・信号、所有境界とfinally cleanupは変更していない。

展開失敗時だけpipeline/git/tarの3結果をreceiptへ保存する。rejectionはcodeと最大256文字message、子processはcode/signalのみとし、archive本文・環境全量・秘密値は記録しない。変更はrunner・既存test・運用文書の3ファイルだけ。

## 検分とAC

rootが診断差分3ファイルとEOF差分3ファイルをそれぞれ全文検分しfinding 0。診断草案SHA256は `9666e19f6e8b21c8cc48dcc2c7709c11ce1db13947b10fd28ae851699beb759c`、EOF草案は `d47cbb14fcacf2c93c8139996d4204eaee7bcbba16b7091d5c079375cff10af3`。実装担当の子へ同じ差分の重複検分を起動していない。

| AC | 検証 |
|---|---|
| AC-1 | 実tiny Git repoのarchive本文・exitを変えずEOFだけ100ms遅延する回帰fixture。展開payload全SHA一致 |
| AC-2 | 実pipeline EPIPE、Git archive exit17、tar exit23、信号、message上限を既存testで識別。失敗判定は不変 |
| AC-3 | 既存のSIGTERM/SIGHUP・停止不能・所有marker・symlink境界を含むLinux全21testと実guard3のcleanup |

## red/greenと独立fixture

- 旧診断tool `518438d49a9691c1abf7cf5c73fbc618a8bfde64` でEOF遅延fixtureはexit1となり、成功を要求する実assertがred。
- `--ignore-zeros`追加後 `1c910059c43537c164dfb1f2571aafafdeb9d4a1` で同fixtureがgreen。Linux全21test成功、skip0、約9.6秒。Node構文2本とdiffcheckも成功。
- 専有tmpの16KiB生成payload、正確なEOA後に20ms遅延したゼロpaddingから実EOFを流す独立14caseを確認。旧tarはpayload全SHA一致でもsource EOF前に終了して失敗、新flagは全SHA一致・source EOF後終了で成功。
- member途中切れtar2、合成archive非0 marker17、tar23、SIGTERMは新旧とも失敗。marker17は合成faultであり、実Git非0拒否は別の既存testでも確認した。
- EOAだけ欠落した入力は新旧とも成功した。全切断検知を強めるflagとは扱わない。EOA後の第二archiveも読む意味変更のため、入力を固定HEADのbuiltin Git archiveに限定し外部任意tarへ広げない。

## 実WSL確認

実source `47b6a8ad6c51d2755ac27021b4632e176c5b60e7` clean exactへ、上記tool raw SHA256 `f1f78972710ecf9fb7092ec0da388d4c9ab33645ad263e3f435f7425ae897f7f` を使いguard3を1回実行した。offline・最大2worker・turnstile待機120秒・command720秒・外枠900秒。

| 実行 | 結果 |
|---|---|
| guard1/2 | archive段階で失敗、Gradle未開始。guard2はpipeline Premature close / git0 / tar0。原本保持 |
| guard3 | archive通過、必要compile＋既存4profile guardのみ、exit0、BUILD SUCCESSFUL 2分59秒 |
| 4profile | 9371 class /3387 tops、取りこぼし・重複・migration漏れ・ネスト分断0 |
| cleanup | receipt exitCode0/errornull/cleanup removed、scratch `/var/tmp/mannschaft-verification-1000/run-0Cl82p`撤去、--check0 |
| process | launcher75518とfinite single-use daemon75563の既知PID世代消滅、後続ownedJava0、既存Java/cache操作0 |

計画daemon0との差としてsingle-use daemon1を実測・報告し、rootが同一有限実行の継続を承認した。PID75563/親75518/startTicks1795745、開始UTC14:45:46.45、sampledRSS4029784KiB。launcher2gをdaemon実heap制限と同一視しない。常駐daemon・Test task・DB・製品runtimeは起動していない。

## CIと保持

最終実装headの [Worktree cleanup guard](https://github.com/kenta-0420/mannschaft/actions/runs/40105243179) がsuccess。PR merge前は11success/6skip、必須Compile & Test/OpenAPI Drift Check/Task-List PR Gateも成功。BE全6/nightlyの追加dispatchは0。

失敗/再現原本、root検分2草案、red/green、14case原UTF16結果は `.claude/campaigns/sept-cmp-jikki/cmp0647-runner-eof-20261024` へファイル単位/全SHA照合で移管した。原本の変換上書き、source/node_modules/build/fullarchiveの複製は行っていない。guard1/2/3とweightsの専有diagWTは親統合待ちで保持する。

元MFA2失敗の原因、新配分CIの壁時計や上限内完走は未証明。weightsの親統合、元PR4053、保留main-follow、security forge採用、本番環境構築・deploy・公開は今回の完了対象に含まない。
