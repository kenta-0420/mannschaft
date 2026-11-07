# 作業資産の片付け

## 共有worktreeの保全確認

完了前の基準確認として、本陣から `node scripts/worktree-cleanup.mjs --days 1 --check --limit 60` を読み取り専用で実行した（2026-10-26 JST）。exit 1、全worktree 76、残存stale 67。dirty、locked、index.lock、junction、保護対象の業務ブランチ、未登録ディレクトリを検出した。ほかの戦役・利用者の資産であり、本機能の片付けとして削除・unlock・変更はしていない。

今回の対象は `agent-feature-display-261025` と `CMP-261025-2202` の所有確認済み検証資産だけである。実機と住民検証で作成したTEAM/ORGの削除後404は `resident-cleanup.json` および `real-e2e-result.json`、独立した住民ブラウザ終了は各住民記録に残した。専用8081/3001サーバーと専用MySQL/Valkeyコンテナも終了・撤去済み。共有サーバー・共有cacheは対象外。

最新バックエンド再検証は全量成功。専用一時資産の終了確認は下記に記録する。完了記録PRのマージ後に対象worktreeを撤去し、共有ゲートを再確認する。他戦役の保全理由は最終報告へ残す。

## 実機配布物の撤去

Windowsの `.claude/runtime/cmp261025-feature-be/mannschaft.jar` とWSLの `/var/tmp/cmp261025-feature-be-runtime-resume/mannschaft.jar` は、ともに実機記録のSHA-256 `0541d562c6d764cb70aaf308ce2c83678defb897b3141f707517e8a92bcc3340` と一致することを確認して撤去した。WSLはUID1000・CMP用途・exact path・ディレクトリ内容2件・runtime参照プロセスなしを確認し、jarとowner記録だけをunlinkして空ディレクトリを除去した。Windowsはexact path・非リンクの通常ファイル・同一hashを確認し、jar単体を削除した。続いて起動receiptもCMP用途・固定source・8081・停止済みstateを照合し、receipt単体と空の専用ディレクトリだけを撤去した。永続証拠は保持している。

## worktreeリンク確認

対象worktreeの全ディレクトリを、ReparsePointを辿らない明示的な走査で確認した。検出は `frontend/node_modules` → 本陣 `frontend/node_modules` のjunction 1件だけ。撤去前にこのリンクだけを外し、リンク先の共有依存ファイルを保護する。

## Dockerデータ領域の追加確認

専用コンテナ撤去後にvolume一覧と匿名volumeの作成日時を読み取り確認した。CMP名・所有ラベルに該当するvolumeはなく、現存する匿名11件の最新作成日時も2026-10-24 05:39 JSTで、本戦役開始（10/25）より前だった。今回新規に作成したvolumeの残存は見当たらない。既存volumeの削除や無差別pruneはしていない。古いDockerイベントは保持されていなかったため、そこから所有関係を推測して削除することもしていない。

## 終了済みFE検証の一時ファイル

`real-e2e-result.json`・`resident-cleanup.json`・`resident-observations.json`のparse成功、住民3報告・統合判定・FE検証記録の存在、代表画像/寸法JSON40件を確認した。専用 `.claude/runtime/cmp261025-feature-fe` のexact pathとリンク不在を確認して、古い実機結果・スクリーンショットの重複・起動補助ファイルを含む一時ディレクトリを撤去した。テストsourceと永続証拠は削除していない。

本陣 `.claude/tmp` に残ったBE用helperは、作成担当が今回CMPの所有を確認した9ファイルだけを対象にした。`feature-be-{green-command,green-resume,green2-command,green2,red-assert,red-command,red-resume}.sh` と `feature-be-{runtime-receipt,start}.py` について、exact path・通常ファイル・CMP名/対象class参照を全件事前照合し、ファイル単位で削除した。一時フォルダー全体やほかの利用者のhelperは削除していない。

## Linux検証履歴の確認

環境失敗・中断を含む8実行の原本receiptを親が照合した。[履歴の最小記録](verification-receipts.json)のとおりscratchは全8件ともremoved。SIGHUP・実行前失敗・fixture失敗を成功テストに数えていない。固定371fccの専用Linux sourceも、tracked/untracked変更なしとexact top-levelを確認した。最新CIの判定後、永続証拠を残してrawと専用sourceを撤去する。

## 最終撤収確認（2026-10-26）

最新head609eeの全量CI成功と証拠export後、上記の専用Linux sourceと8件のraw evidenceディレクトリを撤去した。全exact path、UID1000、非symlink、receiptのsource/exit/signal/cleanup一致、専用Git sourceのclean/固定headを確認した。同UIDの保護プロセス参照は通常権限で読めなかったためrootで再確認し、全生存プロセスのcwd/fd/maps参照0件を確認してから撤去した。共有cacheへは触れていない。

WSLのverification-run --checkはexit0。ほかの戦役の稼働中scratch3件を検出したため保全した。自分の8実行に要処置scratchはない。Windows側の専用runtime7ディレクトリはexact path・全配下非ReparsePointを照合して撤去済み。frontend/node_modulesのjunctionもリンクだけを外し、本陣の共有node_modulesが残ることを確認した。
