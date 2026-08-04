# 本陣保護フック セットアップ手引き

本陣（`C:\Claude\mannschaft` 直下）での直接 commit は、並行セッションの HEAD 衝突・作業消失を招くため**フックで機械的に禁止**している。クローン直後・新環境では以下を **1 回だけ** 実施すること。CLAUDE.md「本陣保護フック」節から分離。

---

## A. git pre-commit フック（最終防衛線・全アクターに有効）

`.githooks/pre-commit` が本陣（git-dir == git-common-dir）での commit を exit 1 で拒否する。worktree では許可。
インストール（どちらか一方）:

```bash
# 推奨: hooksPath を切り替える（コミットされた .githooks をそのまま使う。更新も自動追従）
git config core.hooksPath .githooks

# あるいは: 既定の .git/hooks にコピー（hooksPath 既定のまま）
cp .githooks/pre-commit .git/hooks/pre-commit   # PowerShell: Copy-Item .githooks\pre-commit .git\hooks\pre-commit -Force
```

人間が緊急で本陣 commit したい場合の脱出口は `git commit --no-verify`。**Claude は `--no-verify` 禁止**なので迂回できない。

## B. Claude PreToolUse フック（Claude を着手段階で阻止）

`.claude/hooks/block-honjin-git.ps1` が、本陣 CWD での `git checkout/switch/commit/reset/merge/rebase/cherry-pick/pull` を deny する（worktree 対象コマンドは許可）。
`.claude/settings.local.json`（マシンローカル）の最上位 `hooks` に以下を追加し、`/hooks` を一度開く or Claude 再起動で有効化する。**パスは各自の絶対パスに合わせること**（`$CLAUDE_PROJECT_DIR` でも可）:

```json
"hooks": {
  "PreToolUse": [
    {
      "matcher": "Bash",
      "hooks": [
        {
          "type": "command",
          "command": "powershell -NoProfile -ExecutionPolicy Bypass -File \"<リポジトリ絶対パス>/.claude/hooks/block-honjin-git.ps1\"",
          "if": "Bash(git *)",
          "timeout": 15,
          "statusMessage": "本陣git操作ガード"
        }
      ]
    }
  ]
}
```

## C. dev サーバーは本陣と別 worktree で起動（任意・推奨）

画面確認用 dev サーバーを本陣と別ディレクトリで動かすと、本陣 HEAD が動いても表示が無傷。
`git worktree add .claude/worktrees/dev-main main` → そこで以下のように **検証用ポート（BE 8081 / FE 3001）** で起動する（本陣 8080/3000 と衝突しない。CLAUDE.md「常駐サーバーのポート規約」参照）:

```bash
# 検証用 worktree 内で
cd backend && ./gradlew bootRun --args='--server.port=8081' &
cd frontend && npm run dev -- --port 3001
```

詳細経緯: memory `feedback_branch_isolation` / `feedback_merge_gh_only_no_honjin_git`。
