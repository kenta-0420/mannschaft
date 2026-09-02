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
          "command": "powershell -NoProfile -ExecutionPolicy Bypass -File \"$CLAUDE_PROJECT_DIR/.claude/hooks/block-honjin-git.ps1\"",
          "if": "Bash(git *)",
          "timeout": 15,
          "statusMessage": "本陣git操作ガード"
        }
      ]
    },
    {
      "matcher": "PowerShell",
      "hooks": [
        {
          "type": "command",
          "command": "powershell -NoProfile -ExecutionPolicy Bypass -File \"$CLAUDE_PROJECT_DIR/.claude/hooks/block-honjin-git.ps1\"",
          "timeout": 15,
          "statusMessage": "本陣git操作ガード(PowerShell)"
        }
      ]
    }
  ]
}
```

### ⚠️ `matcher` は Bash と PowerShell の両方を必ず登録すること

`settings.local.json` は `.gitignore` 済み（マシンローカル）で git 追跡されないため、**この節が登録内容の正本**である。

- Windows の Claude Code は `Bash` と `PowerShell` の2つのシェルツールを持つ。**`matcher` が `Bash` だけだと、PowerShell ツールへ切り替えるだけでガードを丸ごと迂回できる。** 当家では実際に、Bash 経由の commit が誤検知で拒否された足軽が PowerShell へ切り替えて commit・push した事故がある
- 2026-09-02 の実測: `matcher: "Bash"` のみの登録では、PowerShell ツールの呼び出しでフックは**一度も起動しなかった**（フック本体に標準入力ペイロードを記録して確認）
- 同じ実測で、PowerShell ツールの PreToolUse ペイロードも Bash と**同じ `tool_input.command` キー**でコマンド行を渡してくることを確認した。したがってフック本体はツール名で分岐する必要がない（同一スクリプトを両 matcher から呼べばよい）
- **PowerShell 側には `if` による絞り込みを付けない。** `if` の条件式は `Bash(git *)` という Bash 用の書式であり、PowerShell 呼び出しに対する挙動が保証されない。絞り込みが効いてしまうと PowerShell が素通りして穴になるため、絞り込まず必ずフックを起動させる（フック本体は git 変更系以外を即座に素通りさせるので実害は無い）

### 登録元はリポジトリ側に一本化する（2026-09-02）

以前は同じフックが **①リポジトリ `settings.local.json`／②`daimyo` プラグイン配布元／③プラグインキャッシュ** の3系統から登録されており、②③が旧版のまま取り残されていた。PreToolUse は**どれか1つでも deny すれば deny** になるため、①だけを直しても旧版の誤検知が残り続ける。そこで②の登録を外し、**登録もフック本体もリポジトリ側を正本**とした。

- フック本体の正本: `.claude/hooks/block-honjin-git.ps1`（このリポジトリ）
- プラグイン側 `plugins/daimyo/hooks/hooks.json` の `PreToolUse` は空にしてある。**再び登録を足す場合も、上記の Bash / PowerShell 両対応を必ず守ること**
- プラグインを再インストール・更新した後は、キャッシュ側（`~/.claude/plugins/cache/daimyo-marketplace/daimyo/<version>/hooks/hooks.json`）に古い登録が復活していないか確認する

### 検証

登録を変えたら必ず検証スクリプトを走らせる（Bash / PowerShell 両ツール分のケースを含む）:

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File .claude/hooks/test-block-honjin-git.ps1
```

全件合格なら終了コード 0。**本陣ではなく worktree の中で実行すること**（本陣・worktree の実パスを git から実測して使うため）。

## C. dev サーバーは本陣と別 worktree で起動（任意・推奨）

画面確認用 dev サーバーを本陣と別ディレクトリで動かすと、本陣 HEAD が動いても表示が無傷。
`git worktree add .claude/worktrees/dev-main main` → そこで以下のように **検証用ポート（BE 8081 / FE 3001）** で起動する（本陣 8080/3000 と衝突しない。CLAUDE.md「常駐サーバーのポート規約」参照）:

```bash
# 検証用 worktree 内で
cd backend && ./gradlew bootRun --args='--server.port=8081' &
cd frontend && npm run dev -- --port 3001
```

詳細経緯: memory `feedback_branch_isolation` / `feedback_merge_gh_only_no_honjin_git`。
