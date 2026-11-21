# worktree 運用・クリーンアップ手引き

大名システム（Agent）の worktree 運用ルールの詳細。CLAUDE.md「大名システム」節から分離。

---

## クリーンアップ **【定期実施】**

大名システム（Agent）の worktree は **作業完了後に必ず掃除する**。放置すると以下の問題が起きる:

- ディスク容量の圧迫（worktree 1 つあたり数十 MB〜数百 MB、`.gradle`/`node_modules` キャッシュを含むとさらに膨らむ）
- `git worktree list` の出力が肥大化して状況把握が困難になる
- IDE のファイルウォッチャー / インデクサが大量のファイルを舐めて遅くなる
- `worktree-agent-*` という孤立ブランチが大量に積もる

### Claude が守るべきタイミング

| タイミング | 何をするか |
|---|---|
| **大名 (Agent) 起動完了直後** | その agent の commit を本リポに統合 (cherry-pick / merge) → 直ちに対応する worktree を `git worktree remove --force` で削除 |
| **セッション開始時** | `git worktree list` を確認し、自分が作ったものでない `agent-*` worktree が残っていれば原因を確認のうえ削除を提案する |
| **セッション終了時** | 自分が起動した agent の worktree がすべて消えていることを確認 |
| **週次** | `/陣払い` の dry-run で stale と残骸理由を確認し、必要時だけ明示適用 |

### コマンド集

```bash
# 残存worktreeの確認
git worktree list

# 既定は dry-run（7日超）。対象・保持理由を確認する
node scripts/worktree-cleanup.mjs --days 7

# clean な agent-* / worktree-agent-* のworktree登録だけを明示適用で撤去する（branchは削除しない）
node scripts/worktree-cleanup.mjs --days 7 --apply

# 凱旋後の必須ゲート（1日超staleと総数60超は非0）
node scripts/worktree-cleanup.mjs --days 1 --check --limit 60
```

> `/陣払い` は上記スクリプトへ委譲する。dirty/locked/index.lock/Git検査失敗、prunable登録、未登録ディレクトリ、junction は自動で消さず、理由を出して保持する。`frontend/node_modules` を含むjunctionはリンクだけを先に外し、リンク先は削除しない。

### 注意事項

- **進行中の agent の worktree は絶対に削除しない**。`git worktree list` の出力で他に動いている agent がないか確認してから削除すること
- 削除前に **当該 worktree の変更がメインリポに統合されているか** を必ず確認する。未マージの commit を消すと作業が失われる
- 作業用ブランチの削除前に PR の `MERGED` 状態と main への採用内容、対象ブランチ名・HEAD、所有者、他 worktree／他担当での利用有無を確認する。squash merge は祖先関係だけで未反映と判定せず、PR 内容と main を照合する。条件を満たす自分の作業ブランチの削除は CLAUDE.md に記録された事前許可の範囲で実施し、それ以外は確認を求める
- メインリポを間違って削除しないこと（`grep "agent-"` で必ず agent の worktree のみに絞る）
- `git worktree remove --force` は `node_modules` の junction を辿って本陣の実体を巻き込み削除しうる。掃除前に `cmd rmdir` で junction リンクを先に外すこと（memory `feedback_worktree_remove_junction_deletes_honjin_node_modules`）

---

## なぜ worktree 隔離が必須なのか

- 大名システムは内部で `git worktree add .claude/worktrees/agent-xxxxx` を使い、**物理的に別ディレクトリ** で agent を起動する。これにより複数の Claude セッションが並列に動いても HEAD 衝突しない。
- メインディレクトリで `git checkout` して作業すると、別の Claude セッションが同じディレクトリで `git checkout` した瞬間に HEAD が引っ張られ、作業中のファイルが消える / コミット前の修正が stash 待避される事故が発生する（2026-04-08 に実際に発生・記録済み）。
- worktree 隔離なら、別 Claude が何をしようとそちらのディレクトリは無傷。安心して長時間タスクを走らせられる。

## 大名システムを起動すべき場面

- **新機能の実装・大規模リファクタ**（複数ファイル・長時間にわたる作業すべて）
- **コードベース全体にまたがる調査・探索**
- **独立して並列実行できるタスク**（ビルド確認・テスト・リサーチなど）
- **E2E テスト実行・修正**（dev サーバー起動を伴うもの）
- **長時間かかる可能性のある処理**

## 並列セッションの作法

- 新機能・大規模実装を開始する前に、**着手前に必ず専用ブランチを `git worktree add` で物理ディレクトリごと隔離** すること
- 同じ作業ディレクトリで複数の Claude セッションを動かす運用は **絶対に避ける**（HEAD 衝突で作業が破壊される）
- worktree 内で commit が完了したら、メインリポジトリに `git merge` でマージする
- `frontend/node_modules` を junction で共有する場合も、Vite の依存最適化キャッシュは `frontend/nuxt.config.ts` の `cacheDir` により各 worktree の `frontend/.nuxt/vite-cache` に分離する。複数の dev server が共有キャッシュを更新しないようにする。

詳細経緯: memory `feedback_branch_isolation` / `feedback_merge_gh_only_no_honjin_git`。

## 浅い履歴（shallow）の診断と再発防止

本陣とその worktree は共通の Git 履歴を共有する。通常は `git rev-parse --is-shallow-repository` が **`false`** の状態を維持する。worktree からの fetch も共通履歴へ作用するため、各 worktree を独立した clone と考えない。

古いブランチの取り込みで「親が無い」「祖先でない」や `refusing to merge unrelated histories` が出たときは、リポジトリ破損と断定する前に、症状が出た本陣または worktree で次を確認する。

```bash
git rev-parse --is-shallow-repository
```

- **`true` の場合だけ**、履歴の取得元が意図した `origin` であることを `git remote -v` で確認し、次を順に実行する。fetch の成功後、再判定が **`false`** になったことを確認してから元の祖先判定・マージを再確認する。
  ```bash
  git fetch --unshallow origin
  git rev-parse --is-shallow-repository
  ```
- **`false` なのに症状が続く場合、判定が取得できない場合、fetch が失敗した場合、再判定が `true` の場合**は、そこで復旧操作を止める。対象コミットの履歴、remote URL / fetch refspec（`git remote -v` / `git config --get-all remote.origin.fetch`）、共通 Git ディレクトリ（`git rev-parse --git-common-dir`）を調査し、判定結果・エラーとともに殿へ報告する。破壊的な修復へ進まない。

shallow 境界では、コミットオブジェクトに親 ID があっても履歴走査が境界で止まり、親不在や非祖先に見える場合がある。`.git/shallow` の手動削除や `--allow-unrelated-histories` で症状を隠さない。`git repack -a -d` や `git fsck --lost-found` は shallow 履歴を完全化する根治手順ではない。

再発防止として、本陣の clone と、本陣・worktree から共通履歴へ行う fetch に **`--depth` / `--shallow-since` / `--shallow-exclude` を付けない**。古いブランチを個別に取得した後も `git rev-parse --is-shallow-repository` を再確認する。個別 fetch が必ず shallow 境界を再生成するとは決めつけず、実値に応じて上記の手順を使う。CI の独立した clone は本陣の共通履歴と区別する。

## 並列ビルドの交通整理

- **実証事実**: 同一マシン上で Gradle の heavy build（compileJava/test/build 等）を複数 worktree から同時に走らせると遅くなる。原因はファイルロック待ちではなく **CPU/IO リソース競合**（3並列 --info ログでロック待ちゼロ件、単独310秒 → 3並列489〜513秒、約1.6倍悪化）。build cache（`org.gradle.caching=true`）は正常に機能しており、無効化する必要はない。
- **旧処方の撤回**: 「遅ければ `--no-build-cache` を付ける」という対処は誤り。build cache は原因ではないため無効化しても改善しない。付けないこと。
- **対策**: `backend/scripts/gradle-turnstile.sh` で heavy build を1本に直列化する。
  ```bash
  cd backend
  ./scripts/gradle-turnstile.sh ./gradlew build
  ./scripts/gradle-turnstile.sh ./gradlew test
  ```
  - ロック置き場はマシン全体で固定の1箇所（`${LOCALAPPDATA:-$HOME}/gradle-turnstile`）。worktree ごとの `GRADLE_USER_HOME` には依存しない（直列化の単位はマシンなのでロックも1つでよい）
  - 取得は「一意な一時ディレクトリに info(PID/TIME/TOKEN) を書いてから `mv -T` でロック名へ改名」方式でアトミックに行う（mkdir直後の空ディレクトリが見える隙間を作らない）
  - 先客がいる場合は15秒間隔でポーリングして待機（1分毎に状況を出力）
  - stale 奪取の条件は**ロック保持プロセスの PID が死んでいること**のみ（生存中は経過時間に関わらず奪取しない）。生存中の先客を待つ時間には上限180分を設け、超過時はエラー終了して人間の確認に委ねる
  - **heavy**（このスクリプト経由が必須）: `test` / `build` / `bootJar` / `check` / `compileJava` / `compileTestJava` を含む実行
  - **軽量**（対象外）: `help` / `tasks` / `properties` / `--status` / `--stop` 等

## WSL検証コピーの寿命管理

今後のLinux/WSL検証コピーは Node.js 22以降の共通入口を使う。手作業の `cp -r`、全量tar/zip退避、`node_modules`・build・個別cacheの丸ごとコピーを既定にしない。元のGit worktreeはcleanなrootを指定し、変更はcommitしてから実行する。dirty/untrackedとsubmoduleは作成前に拒否する。Git HEADを固定して `git archive` を直接展開するため、Git管理外の生成物・`.git` は含まれない。

```bash
node scripts/verification-run.mjs \
  --source /mnt/c/Claude/mannschaft/.claude/worktrees/agent-example \
  --evidence-dir /home/kenta/verification-evidence/CMP-YYMMDD-HHMM-run1 \
  -- node scripts/example-check.mjs
# 子コマンドは必要な結果だけ $VERIFICATION_EVIDENCE_DIR に直接保存する。
node scripts/verification-run.mjs --check
```

証跡保存先の親は事前に作成し、保存先そのものは新規の外部ディレクトリを指定する。既存ディレクトリ、source内、管理親内への保存は拒否する。runnerは小さな `receipt.json`（source HEAD、元exit/signal、cleanup結果と保持理由。archive展開失敗時はpipeline rejectionのcode/最大256文字messageとgit/tarのcode/signalも役割別に記録）だけを保存し、stdout全量ログや全量バックアップは作らない。必要な証跡をCI等の正本へ確定したら、重複するローカルrawログ・全量archiveは所有と保存済みの範囲を確認して撤去する。

展開入力は固定HEADの組込み `git archive --format=tar` に限定する。GNU tarは `--ignore-zeros` で終端ゼロの後も実EOFまで読み、Node pipelineの早期close競合を避ける。member途中切れ・git/tar非0・信号の既存失敗判定は保持する。EOAだけの欠落を検知する保証はなく、EOA後の第二archiveも読むflagなので外部任意tar入力へ広げない。

一時領域は `/var/tmp/mannschaft-verification-<uid>/run-*` の自分専用領域。成功・非0終了・展開/起動失敗でも所有markerとディレクトリidentityを確認してfinallyで撤去する。SIGINT/TERMおよび端末切断のSIGHUPは子process groupへ転送し、停止・参照を確認してから撤去する。停止確認不能、脱走daemonの参照、証跡保存不能、所有境界変更、削除失敗は非0とし正確な保持パス・理由を出す。対象PIDのcmdline・開始時刻・用途を確認して停止し、証跡を保全してからその個別パスだけを処置する。全WSL停止や無差別なrm/pruneへ切り替えない。

SIGKILL・ホスト障害ではfinallyは動かない。`--check` は自分専用管理親だけを調べ、稼働中と要処置を表示し、要処置があれば非0となる。削除は行わず、既存の管理外コピー・他人の領域を対象にしない。

完了時はGit worktreeに加え、自身のWSLコピー、専用cache、配布物、起動プロセス、検証コンテナ・専用volumeを一覧と所有根拠で確認し、不要分を撤去する。共有cache・既存利用者資産は含めない。中断時に保持が必要なら、正確なパス・所有者・用途・保持理由・再確認期限・再開条件を引継書へ記録し、次回確認で解消する。無期限の全量圧縮退避にしない。

参照監査は同UIDのaccessibleな既存プロセスも対象とする。ただし起動前からEACCESで検査不能だったPIDは開始時刻を確定し、同一世代だけをbaseline例外とする。例外数はreceiptと `--check` に表示する。新規・途中のEACCESは同じ2秒の猶予内で全参照監査を再実行し、実際の終了または完全な検査で参照0を確認できた場合だけ撤去する。baseline例外への後付け昇格はしない。猶予後も生存EACCES・PID世代不明・参照ありなら保持して非0とする。すべてのプロセスに対する保護を保証するものではないため、scratchを既存共有daemonへ引き渡す、別セッションへ共有する、常駐背景プロセスを起こす運用は禁止する。Gradle検証では `--no-daemon` を付ける。停止待機は2秒で打ち切って保持し、無関係なプロセスを強制終了しない。

Linux自己テストは `node --test scripts/verification-run.test.mjs` で実行する。Node.js 22以降に加え、新規・一過性EACCESと持続EACCESの実境界を検査するため `/usr/bin/python3` の標準 `ctypes` を使用する。`--check` は管理親全体の要処置があれば1を返すため、特定の稼働copyがあることだけを終了値0の根拠にしない。

### 既存コピーの孤立コード照合

管理外の旧コピーは「未反映かもしれない」だけで無期限保持せず、まず最新の `origin/main` をfetchして比較先SHAを固定する。台帳・仕様・完了証拠もそのSHAから読み、本陣の古いworking treeを根拠に完了済み戦役を再開しない。

tracked差分・残存refに加え、untracked/ignoredを確認する。`logs/` 配下のspecやfixtureもコードとして扱う。非祖先であることだけでは未反映と判定せず、正本refへの到達、PRのsquash採用、patch-id、改行正規化後の本文、現mainの後続改善まで照合する。

必要な差分だけを秘密情報がないと確認したうえで正本branch/PRへ回収し、CMP・担当・次の一手を対応付ける。既存Draft PR/refに必要差分があれば回収branchを重複作成せず、未完理由と次の一手を既存CMPへ対応付ける。採用済み・検証専用・後続改善で不要なものは採否理由を記録する。全量archiveの追加で解消扱いにせず、使用中プロセス・共有Gitディレクトリ・他の利用者資産を確認してから不要な原コピーを個別に撤去する。未確定分は所有者・再確認期限・再開条件を付けて引き継ぐ。既存の `--check` は検知専用のままとし、無差別削除へ変更しない。

## 足軽 worktree の sparse checkout（運用の入口）

足軽 worktree は、牧場 PNG 素材3ディレクトリ（`frontend/dev-fixtures/ranch`・`frontend/public/ranch/approved`・`backend/src/main/resources/ranch/approved`、計約876MB）を展開しない。C ドライブの逼迫対策で、worktree 1本あたり約0.8GB 減る。仕組みは次の2節が正本で、ここでは入口だけを書く。

- [素材の展開・縮小（worktree-assets）](#素材の展開縮小worktree-assets): 展開・縮小のコマンドと拒否条件
- [sparse 一覧の正本と番人](#sparse-一覧の正本と番人cmp-261116-1101): 設定の一覧の生成と CI 検査

### 何が変わるか

- 新規作成する足軽 worktree は、素材3ディレクトリが作業木に無い（git の追跡は続く）。**commit しても素材の削除は記録されない**。
- 本陣（メインの作業木）は常に全展開のまま。本陣では `shrink` / `reapply` しない。
- **既存の worktree には自動適用されない**。設定の有効化後に作る新規分から効く。既存分を縮めたいときは、その worktree で `node scripts/worktree-assets.mjs shrink` を手動で実行する。
- sparse の worktree では、素材に依存する処理は次のとおり振る舞う（無言でスキップしない）。
  - FE の素材検証（`verify-ranch-*`）: 実バイト（PNG の中身・hash・寸法）の検査だけを理由つきで省略する。メタデータ検査は常に走る。`CI` 環境では省略しない。
  - 配置 CLI（`verify-ranch-production-assets.mjs`。`--repository` を付けて書き込むとき）と生成ツール（`generate-ranch-production-appearances.mjs`）: 素材が未展開なら、何も書き込む前に展開手順つきで非0になる。`--repository` なしの検証だけなら sparse でも動く。
  - 成果物検証（`verify-ranch-production-artifact.mjs`、Docker ビルドの途中でも走る）: 素材が無ければ失敗する。
  - gitleaks の公開 ID 再検査（`verify-ranch-public-identity-gitleaks.mjs`）: 承認 pack の `manifest.json` が未展開なら HEAD から `git show` で取得して実際に検査する（成功スキップはしない。sparse 除外以外の欠落と `CI` では失敗）。expand は要らない。
  - BE の牧場テスト: 実素材に依存する単位だけが理由つきで skipped になる（`@RequiresRanchApprovedAssets`）。

### 素材が要る作業と手順

次の作業の前に、その worktree で `node scripts/worktree-assets.mjs expand` を実行する。終わって素材が不要になったら `node scripts/worktree-assets.mjs shrink`。

- 牧場機能の素材を触る（画面・API・診断 formal mapping・素材の生成・登録）
- 素材の実バイト検証（`verify-ranch-*` を省略させず通したいとき）
- 配置 CLI の実行、成果物検証、ローカルでの Docker ビルド
- BE の実素材依存テストを skipped にせず実行したいとき

### main を取り込んだとき

`git merge origin/main` や rebase で `worktree.sparsePaths` の一覧が変わったら、その worktree で `node scripts/worktree-assets.mjs reapply` を実行する（`status` で設定との一致を確認できる）。

### 一覧を更新する場面（番人）

main に新しいトップレベルのディレクトリや、除外ディレクトリの兄弟を足すと、一覧が古くなって CI の `node scripts/worktree-sparse-paths.mjs --check` が落ちる。`node scripts/worktree-sparse-paths.mjs --write` で `.claude/settings.json` を更新して同じ PR に含める。

### やってはいけないこと

- `git sparse-checkout disable` / `set` などを手で打つ。`extensions.worktreeConfig` が無効な環境では共有 `.git/config` を書き換えて他の worktree に影響し、手打ちはこの確認も、未追跡・ignore 済みファイル（生成物など）の検査も、事後検証も通らないため、失う・取りこぼすおそれがある。共有 config に `core.sparsecheckout` が漏れている環境もあり、状態の判定が食い違う。展開・縮小は `worktree-assets.mjs` だけで行う。
- 本陣で `shrink` / `reapply` する（拒否される）。
- 除外ディレクトリへ junction・symlink を張る（参照先の追跡ファイルまで消えるおそれがあるため拒否される）。

### WSL 検証コピー

`scripts/verification-run.mjs` は `git archive --format=tar <HEAD>` で HEAD のコミットを展開する。archive はコミットの tree を読み、作業木の sparse 状態を見ないので、sparse の worktree を `--source` に指定しても素材3ディレクトリは WSL 側の検証コピーに全て入る（確認: sparse の worktree で `git archive HEAD frontend/public/ranch/approved` が追跡ファイル全件を出力する）。

## 素材の展開・縮小（worktree-assets）

牧場 PNG 素材（`frontend/dev-fixtures/ranch`、`frontend/public/ranch/approved`、`backend/src/main/resources/ranch/approved`。計約876MB）は、足軽 worktree では `worktree.sparsePaths`（git sparse-checkout の cone モード）で既定では展開しない運用を目指している。素材が要る作業では `scripts/worktree-assets.mjs` で展開し、終わったら縮小する。操作対象は**実行した worktree だけ**で、共有 `.git/config`・他 worktree・本陣は変えない。

> `worktree.sparsePaths` の設定の有効化は後続PRで行う。設定が未投入の間、`shrink` と `reapply` は「設定が未投入」と表示して非0で終了する（`status` と `expand` は使える）。

### expand が要る作業

- 牧場機能の開発・検証（画面・API が素材を読む）
- 診断 formal mapping
- 素材の生成・登録
- `verify-ranch-*` の実バイト検査
- ローカルでの Docker ビルド（素材をイメージに含めるため）
- 配置 CLI・成果物検証、BE の実素材依存テスト（要る作業の全体像は[運用の入口](#足軽-worktree-の-sparse-checkout運用の入口)）

### コマンド

```bash
node scripts/worktree-assets.mjs status    # sparse か・展開/除外・設定との一致（読み取りのみ）
node scripts/worktree-assets.mjs expand    # 全展開。必要容量を表示し、空きが足りなければ何も変えず非0
node scripts/worktree-assets.mjs shrink    # HEAD の worktree.sparsePaths で縮小
node scripts/worktree-assets.mjs reapply   # main を取り込んで設定の一覧が更新された後に、一覧を最新へ更新
```

素材が要らなくなったら `shrink`。main に新ディレクトリが増えて設定が更新されたのを merge/rebase で取り込んだら `reapply`（既存の変更・未追跡は保たれる）。

### 拒否される条件と対処

拒否はいずれも、何も変えずに非0で終了する（sparse 一覧・index・共有 `.git/config` は変わらない）。

| 条件 | 対処 |
|---|---|
| 本陣（メイン作業木）での `shrink` / `reapply` | 足軽の worktree で実行する（`expand` と `status` は本陣でも可） |
| `extensions.worktreeConfig` が無効（`git sparse-checkout` を呼ぶ全経路で最初に確認） | 共有 `.git/config` を書き換えてしまうため拒否 |
| `worktree.sparsePaths` が不正（文字列でない・空・前後空白・`/` 始まり・ドライブ指定・バックスラッシュ・`.`/`..` 要素・先頭 `-`・重複・HEAD に追跡ディレクトリとして無い） | 設定を直す。`--no-cone` のような値を黙って直したり git へ渡したりしない |
| junction・symlink（reparse point）が、worktree ルート・外れる/展開されるパスの祖先・外れる領域の配下のいずれかにある（git が追跡する symlink エントリ=120000 は対象外） | 参照先の追跡ファイルまで消えるおそれがあるため拒否。該当パスと理由が表示される。リンクを外してから再実行 |
| 前回の使用中プローブが強制終了で残した一時名（`<dir>.wa-probe-<pid>`） | 検出して報告するだけで勝手に消さない。表示された手順で元の名前へ戻す |
| `worktree.sparsePaths` に祖先と子孫の併記（例 `app` と `app/sub`） | git が祖先へ正規化するため拒否。どちらかを外す |
| 縮小で外れる領域に未コミット・ステージ済み・未追跡・ignore 済み（生成物）がある | 該当パスが表示される。コミット・退避・削除してから再実行 |
| 展開されるパスやその祖先の位置に、未追跡・ignore 済みのファイル/ディレクトリがある（`reapply` / `expand`） | 該当パスが表示される。上書き・削除はしない。退避してから再実行 |
| 未マージ、進行中の merge/rebase/cherry-pick/revert、`index.lock` | 操作を完了または中止、残骸のロックを所有確認のうえ除去 |
| 対象 worktree のパスを含む、または cwd が worktree 内（Linux のみ検出）の java / node / gradle が動作中 | 停止してから再実行。取得できなければ「確認できない」として拒否。確認済みなら `--skip-process-check`（プロセス一覧の検査だけを省く） |
| 使用中プローブ失敗（Windows 既定。外れるディレクトリを一時名へ rename できない） | 開いているファイル・エディタ・プロセスを閉じて再実行。`--skip-process-check` でも省かない |
| 設定・`settings.json` の履歴（直近100 commit）・本コマンドが記録した印（`git config --worktree mannschaft.sparsePathsDigest`）のどれにも当たらない sparse 一覧（`boundary-coordination.md` の管理用 worktree など。ルート直下のみの一覧も含む） | 上書きせず拒否。置き換えてよい場合のみ `--force-replace` |
| `expand` で空き容量が足りない / 取得できない | 見積りは「blob 合計×1.5＋ファイル数×4KB＋200MB」。空きを作る。取得できない環境だけ `--skip-space-check` |

### 実行中の注意

作業木を書き換える `git sparse-checkout` 呼び出しは、1つの関数（安全検査を必ず通す入口）だけから行う。一覧が既に一致していて実状態だけ未完了の再適用も同じ検査を通る。完了判定は前回との差分ではなく、常に「現在の目標一覧に対する全体検証」（展開されるべき追跡ファイルの実在と skip-worktree の解除、外れるべきファイルの不在、欠落＝status の ` D` が無いこと）で行う。欠落の扱いは「今回展開するはずだったパス」と「前回失敗の記録」（worktree 固有の git dir 内 `worktree-assets-pending.json`。共有 config は変えない）に限り、これらが欠落していれば非0で `git restore` での復元手順を表示する。操作前から欠落していた利用者の削除（記録に無いパス）は障害にしない。全展開済みの `expand` 再実行では、記録にあるパスと、設定上の除外ディレクトリ配下の欠落は非0、それ以外の欠落は利用者の削除と区別できないため注意表示のみで 0 とする。記録は一時ファイルへ書いて rename で置換し、書いた内容を読み戻して一致を確認してから作業木を書き換える（保存できなければ何も変えず非0）。記録が存在するのに読めない・解釈できない（JSON 破損・配列でない・文字列でない要素・読み取りエラー）場合は、「記録なし」とは扱わず、何も変えず非0とし、自動では消さない（`status` でも要処置として報告する。復旧: `git status` で欠落（D）を確認して `git restore -- <パス>` で戻し、確認後に記録ファイルを手で削除する）。記録は検証が通ると消える（残る制約: 記録のパスを意図して消した場合は、コミットか `git rm` するまで失敗し続ける）。

検査から実行までの間に起きた変更（事前検査の後に未追跡ファイルが作られる等）は原理的に防げない。その場合は事後検証で必ず非0になる。**実行中は対象 worktree で他の作業をしない**（エディタ・ビルド・テスト・git 操作を止める）。

### Windows の限界と事後検証

Windows では、cwd だけが worktree 内にあるプロセス（`node server.js` を相対パスで起動した等）はコマンドラインに現れず、標準手段では検出できない。使用中の検出は次の2段で補う。

1. **使用中プローブ**: 縮小で外れる最も浅いディレクトリを同じ親の一時名（`<dir>.wa-probe-<pid>`）へ rename して即座に戻す。開かれたハンドルがあると rename が失敗し、縮小を中止する。戻せない場合は5回再試行し、失敗したら一時名のパスと手動復旧手順（元の名前へ `Move-Item`）を表示して非0で終了する。この状態では他の操作をしない。
2. **事後検証**: `shrink` / `reapply` / `expand` の後に、外れるべき追跡ファイルが skip-worktree で作業木に残っていないか、展開すべきファイルが実在するか、`git status` に意図しない削除が出ていないかを確かめる。未完了なら非0で、残ったパスと再実行コマンドを表示する。一覧が既に一致していても実状態が未完了なら `git sparse-checkout reapply` で再適用して検証し直す。

途中で git が失敗した場合（ファイル使用中など）も非0で終了し、到達状態と再実行コマンドを表示する。既存の変更は失われず、再実行で完了できる。黙って全展開へフォールバックすることはない。

`shrink` / `reapply` が成功すると、適用した一覧のハッシュを worktree ローカル設定（`git config --worktree mannschaft.sparsePathsDigest`）へ記録する。`expand` で消す。共有 `.git/config` は変えない。

## sparse 一覧の正本と番人（CMP-261116-1101）

足軽 worktree に牧場 PNG 素材（約876MB）を展開しないため、Claude Code の `worktree.sparsePaths`（git sparse-checkout の cone モード）に「含めるディレクトリ」を列挙する。cone は含める一覧なので、除外ディレクトリの兄弟を全て並べる必要がある。手書きだと main に新ディレクトリが増えたとき黙って見えなくなるため、一覧は除外の正本から機械生成する。

- 除外の正本: `scripts/worktree-sparse-exclusions.json`（パスと理由）
- 生成・検査: `scripts/worktree-sparse-paths.mjs`
  - `node scripts/worktree-sparse-paths.mjs --print` — 生成結果を出力（`--ref <ref>` で対象 ref を指定、既定 HEAD。`git ls-tree --full-tree` で読むため sparse の worktree でも正しく動く）
  - `node scripts/worktree-sparse-paths.mjs --check` — `.claude/settings.json` の `worktree.sparsePaths` が生成結果と集合として一致し（順序は無視）、全追跡ファイルが展開経路に属すること（未被覆ゼロ）を検査。未設定の間は成功扱い
  - `node scripts/worktree-sparse-paths.mjs --write` — settings.json へ書き込む
  - `node scripts/worktree-sparse-paths.mjs --check --require-configured` — 未設定を失敗にする（キー誤記の素通りを根治）。設定を有効化する後続PR（部隊1b）で CI の `--check` ステップに付ける。有効化前の現状は付けない
- 番人: CI の `PR 軽量検査`（`.github/workflows/pr-light-checks.yml` の `[Harness]` step。旧 `harness-ci.yml`）が単体テスト `scripts/worktree-sparse-paths.test.mjs` と `--check` を実行する。main に新しいディレクトリが増えて一覧が古くなると `--check` が落ちるので、`--write` で更新してコミットする。
- 除外を増減する手順: 正本 JSON を編集し、`--write` で settings.json を更新して同じ PR に含める。除外パスが追跡されなくなる（改名・削除）とエラーになるので、正本から外す。
- 状態: 生成スクリプトと番人のみ導入済み。`.claude/settings.json` への有効化は後続 PR で行う。
