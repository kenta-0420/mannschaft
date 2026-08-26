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
| **週次** | 全 `worktree-agent-*` ブランチと残骸ディレクトリを一括削除 |

### コマンド集

```bash
# 残存worktreeの確認
git worktree list

# 個別削除（コミットを取り込み済みであることを確認してから）
git worktree remove --force .claude/worktrees/agent-xxxxx
git branch -D worktree-agent-xxxxx

# 全 agent worktree を一括削除（変更が残っていても強制削除する）
for wt in $(git worktree list --porcelain | grep "^worktree" | grep "agent-" | awk '{print $2}'); do
  git worktree remove --force "$wt"
done

# 孤立した worktree-agent-* ブランチを一括削除
git branch -D $(git branch | grep "worktree-agent-" | tr -d ' ')

# stale entries（既にディレクトリが消えた worktree のメタ情報）を削除
git worktree prune

# .claude/worktrees/ 配下に空ディレクトリが残っていれば削除
rmdir .claude/worktrees/agent-* 2>/dev/null || true
```

> 一括掃除はスキル `/陣払い` でも実行できる（既定で 7 日以上前の足軽 worktree を撤去）。

### 注意事項

- **進行中の agent の worktree は絶対に削除しない**。`git worktree list` の出力で他に動いている agent がないか確認してから削除すること
- 削除前に **当該 worktree の変更がメインリポに統合されているか** を必ず確認する。未マージの commit を消すと作業が失われる
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

詳細経緯: memory `feedback_branch_isolation` / `feedback_merge_gh_only_no_honjin_git`。

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
