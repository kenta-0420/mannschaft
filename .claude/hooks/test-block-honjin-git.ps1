# ============================================================================
# block-honjin-git.ps1 の検証スクリプト。
#
# PreToolUse フックへ実際の JSON ペイロードを標準入力から流し込み、
# deny / allow が期待どおりかを確かめる。
#
# 実行:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .claude/hooks/test-block-honjin-git.ps1
# 終了コード: 全件合格なら 0、1件でも不一致があれば 1。
#
# 注意: このファイルは日本語を含むため UTF-8 BOM 付きで保存すること
#       （PowerShell 5.1 は BOM 無し UTF-8 を ANSI 誤認して構文エラーになる）。
# ============================================================================
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch {}

$hookPath = Join-Path $PSScriptRoot 'block-honjin-git.ps1'
if (-not (Test-Path $hookPath)) { throw "フック本体が見つかりません: $hookPath" }

# 本陣と worktree の実パスを git から実測する（ハードコードしない）。
$here = (& git -C $PSScriptRoot rev-parse --show-toplevel) -replace '/', '\'
$commonGitDir = (& git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir) -replace '/', '\'
$honjin = (Split-Path -Parent $commonGitDir)   # <本陣>\.git の親＝本陣
$worktree = $here

Write-Host "本陣    : $honjin"
Write-Host "worktree: $worktree"
Write-Host ''

function Invoke-Hook {
    param([string]$Command, [string]$Cwd, [string]$Tool = 'Bash')
    # tool_name は Bash / PowerShell のどちらでも与える。実測（2026-09-02）では
    # PowerShell ツールの PreToolUse ペイロードも Bash と同じ `tool_input.command`
    # キーでコマンド行を渡してくるため、フック本体はツール名で分岐しない。
    # ここで tool_name を載せるのは、どのツールを模したケースかを明示するため。
    $payload = @{ cwd = $Cwd; tool_name = $Tool; tool_input = @{ command = $Command } } | ConvertTo-Json -Compress -Depth 5
    $out = $payload | & powershell -NoProfile -ExecutionPolicy Bypass -File $hookPath
    if ([string]::IsNullOrWhiteSpace($out)) { return 'allow' }
    if ($out -match '"permissionDecision"\s*:\s*"deny"') { return 'deny' }
    return 'allow'
}

$cases = @(
    # --- deny されるべき（保護が効くこと） ---
    @{ name = '本陣 cwd で git commit';            cwd = $honjin;   expect = 'deny';  cmd = 'git commit -m "テスト"' },
    @{ name = '本陣 cwd で git checkout -b';       cwd = $honjin;   expect = 'deny';  cmd = 'git checkout -b feature/x' },
    @{ name = '本陣 cwd で git reset --hard';      cwd = $honjin;   expect = 'deny';  cmd = 'git reset --hard origin/main' },
    @{ name = '本陣 cwd で git merge';             cwd = $honjin;   expect = 'deny';  cmd = 'git merge origin/main' },
    @{ name = '本陣 cwd で git pull';              cwd = $honjin;   expect = 'deny';  cmd = 'git pull' },
    @{ name = '-C で本陣を明示して commit';        cwd = $worktree; expect = 'deny';  cmd = "git -C `"$honjin`" commit -m x" },
    @{ name = '複合の後段だけが本陣 commit';       cwd = $honjin;   expect = 'deny';  cmd = 'echo hi && git commit -m x' },
    @{ name = 'wsl 経由でも /mnt 経由で本陣を指す'; cwd = $worktree; expect = 'deny'; cmd = "wsl -d Ubuntu-24.04 -- git -C /mnt/c/Claude/mannschaft commit -m x" },
    @{ name = 'wsl 断片の後に Windows の本陣 commit'; cwd = $honjin; expect = 'deny'; cmd = "wsl -- bash -lc 'echo a' && git commit -m x" },
    @{ name = 'worktree から cd で本陣へ移って commit'; cwd = $worktree; expect = 'deny'; cmd = "cd `"$honjin`" && git commit -m x" },
    # ヒアドキュメント本文中の `git commit` は「実行されない文字列」だが、
    # `bash <<EOF ... EOF` のように本文が実際に実行される形と区別できないため、
    # 保護側に倒して deny のままとする（緩める方向の変更はしない）。
    @{ name = 'ヒアドキュメント本文の git commit（保護側に倒して deny）'; cwd = $honjin; expect = 'deny'; cmd = "cat <<'EOF'`nまず git commit する手順`nEOF" },

    # ハイフン付きの配管コマンドのうち、インデックスや作業木を書き換えるものは
    # 拒否を維持する。読み取り専用のものだけを除外する方式が「安全な側を列挙し
    # 切れているか」を守る番人であり、除外方式へ変えた際の取りこぼしを検出する。
    @{ name = '本陣 cwd で git checkout-index（変更系ゆえ deny 維持）'; cwd = $honjin; expect = 'deny'; cmd = 'git checkout-index -a -f' },
    @{ name = '本陣 cwd で git merge-file（変更系ゆえ deny 維持）';     cwd = $honjin; expect = 'deny'; cmd = 'git merge-file a.txt base.txt b.txt' },
    @{ name = '本陣 cwd で git merge-recursive（変更系ゆえ deny 維持）'; cwd = $honjin; expect = 'deny'; cmd = 'git merge-recursive base -- HEAD other' },
    @{ name = '本陣 cwd で git merge-octopus（変更系ゆえ deny 維持）';   cwd = $honjin; expect = 'deny'; cmd = 'git merge-octopus -- HEAD a b' },
    @{ name = '本陣 cwd で git merge-ours（変更系ゆえ deny 維持）';      cwd = $honjin; expect = 'deny'; cmd = 'git merge-ours base -- HEAD other' },
    @{ name = '本陣 cwd で git merge-index（変更系ゆえ deny 維持）';     cwd = $honjin; expect = 'deny'; cmd = 'git merge-index git-merge-one-file -a' },

    # 作業木・インデックス・ローカル参照を書き換えるサブコマンド群。
    # 2026-09-03 まで判定リストに無く、本陣で素通りしていた（実測で確認）。
    # 本陣は全セッション共有のため、これらは他セッションの未コミット作業を消しうる。
    @{ name = '本陣 cwd で git revert';        cwd = $honjin; expect = 'deny'; cmd = 'git revert HEAD' },
    @{ name = '本陣 cwd で git restore';       cwd = $honjin; expect = 'deny'; cmd = 'git restore .' },
    @{ name = '本陣 cwd で git stash push';    cwd = $honjin; expect = 'deny'; cmd = 'git stash push -u -m wip' },
    # stash の退避スタックは全作業木で共有される。pop は他セッションの退避を取り出しうる
    @{ name = '本陣 cwd で git stash pop';     cwd = $honjin; expect = 'deny'; cmd = 'git stash pop' },
    @{ name = '本陣 cwd で git clean -fd';     cwd = $honjin; expect = 'deny'; cmd = 'git clean -fd' },
    @{ name = '本陣 cwd で git apply';         cwd = $honjin; expect = 'deny'; cmd = 'git apply fix.patch' },
    @{ name = '本陣 cwd で git am';            cwd = $honjin; expect = 'deny'; cmd = 'git am 0001.patch' },
    @{ name = '本陣 cwd で git rm';            cwd = $honjin; expect = 'deny'; cmd = 'git rm -r src' },
    @{ name = '本陣 cwd で git mv';            cwd = $honjin; expect = 'deny'; cmd = 'git mv a.txt b.txt' },
    @{ name = '本陣 cwd で git sparse-checkout'; cwd = $honjin; expect = 'deny'; cmd = 'git sparse-checkout set backend' },
    @{ name = '本陣 cwd で git update-ref';    cwd = $honjin; expect = 'deny'; cmd = 'git update-ref refs/heads/main HEAD' },
    @{ name = '本陣 cwd で git branch -D';     cwd = $honjin; expect = 'deny'; cmd = 'git branch -D feature/x' },
    # ブランチ「作成」も共有リポジトリのローカル参照を変える。破壊的オプションの
    # 列挙では捕まらないため、引数を伴う形を拒否する（#3082 Codex 検分）。
    @{ name = '本陣 cwd で git branch 作成';   cwd = $honjin; expect = 'deny'; cmd = 'git branch feature/x' },
    @{ name = '本陣 cwd で git branch -t 作成'; cwd = $honjin; expect = 'deny'; cmd = 'git branch -t feature/x origin/main' },
    @{ name = '本陣 cwd で git branch --unset-upstream'; cwd = $honjin; expect = 'deny'; cmd = 'git branch --unset-upstream' },
    # 打ち消しオプションによる迂回。git は真偽オプションに `--no-` 形を自動生成する
    # ため、先頭トークンだけで読み取り専用と判断すると保護を素通りできる（#3082 P1）。
    @{ name = 'clean の --no-dry-run 迂回';    cwd = $honjin; expect = 'deny'; cmd = 'git clean --dry-run --no-dry-run -f' },
    @{ name = 'apply の --apply 迂回';         cwd = $honjin; expect = 'deny'; cmd = 'git apply --check --apply fix.patch' },
    @{ name = 'rm の --no-dry-run 迂回';       cwd = $honjin; expect = 'deny'; cmd = 'git rm -n --no-dry-run src' },
    @{ name = 'clean の未知オプション混入';    cwd = $honjin; expect = 'deny'; cmd = 'git clean -n --unknown-flag' },

    # --- 通されるべき（誤検知しないこと） ---
    @{ name = 'worktree cwd で git commit';        cwd = $worktree; expect = 'allow'; cmd = 'git commit -m "テスト"' },
    @{ name = '-C で worktree 絶対パスを指定';     cwd = $honjin;   expect = 'allow'; cmd = "git -C `"$worktree`" commit -m x" },
    @{ name = 'wsl 内クォートの && 越しの checkout'; cwd = $honjin; expect = 'allow'; cmd = "wsl -d Ubuntu-24.04 -- bash -lc 'cd ~/verify-0810 && git checkout -B verify origin/main'" },
    @{ name = 'git worktree add';                  cwd = $honjin;   expect = 'allow'; cmd = 'git worktree add .claude/worktrees/foo -b feature/foo origin/main' },
    @{ name = 'cd worktree してから commit';       cwd = $honjin;   expect = 'allow'; cmd = 'cd .claude/worktrees/fix-hook && git commit -m x' },
    @{ name = 'git 以外（変更系サブコマンド無し）'; cwd = $honjin;   expect = 'allow'; cmd = 'git status' },
    # 読み取り専用のハイフン付きサブコマンド。`merge` がハイフン直前でも
    # 境界として成立するため `merge` と誤認され、本陣の同期作業が拒否された。
    @{ name = 'git merge-base（読み取り専用・誤検知しない）'; cwd = $honjin; expect = 'allow'; cmd = 'git merge-base --is-ancestor HEAD origin/main' },
    @{ name = 'git merge-tree（読み取り専用・誤検知しない）'; cwd = $honjin; expect = 'allow'; cmd = 'git merge-tree main feature/x' },
    # 上で拒否したサブコマンドの、読み取り専用の形。締めすぎていないことを守る番人。
    @{ name = 'git stash list（読み取り専用・誤検知しない）'; cwd = $honjin; expect = 'allow'; cmd = 'git stash list' },
    @{ name = 'git stash show（読み取り専用・誤検知しない）'; cwd = $honjin; expect = 'allow'; cmd = 'git stash show stash@{0}' },
    @{ name = 'git branch（一覧表示・誤検知しない）';         cwd = $honjin; expect = 'allow'; cmd = 'git branch --list' },
    # 変更系の語がオプションの値として現れるだけの場合。字面で拾わないことを守る番人。
    @{ name = 'git branch（引数なし・誤検知しない）';          cwd = $honjin; expect = 'allow'; cmd = 'git branch' },
    @{ name = 'git branch -a（一覧表示・誤検知しない）';       cwd = $honjin; expect = 'allow'; cmd = 'git branch -a' },
    @{ name = 'git branch --show-current（誤検知しない）';     cwd = $honjin; expect = 'allow'; cmd = 'git branch --show-current' },
    # 変更を伴わない診断形。これらまで拒否すると本陣で状態確認もできなくなる。
    @{ name = 'git clean -n（試算のみ・誤検知しない）';        cwd = $honjin; expect = 'allow'; cmd = 'git clean -n' },
    @{ name = 'git clean --dry-run（誤検知しない）';           cwd = $honjin; expect = 'allow'; cmd = 'git clean --dry-run -d' },
    @{ name = 'git apply --check（検証のみ・誤検知しない）';   cwd = $honjin; expect = 'allow'; cmd = 'git apply --check fix.patch' },
    @{ name = 'git apply --stat（誤検知しない）';              cwd = $honjin; expect = 'allow'; cmd = 'git apply --stat fix.patch' },
    @{ name = 'git sparse-checkout list（誤検知しない）';      cwd = $honjin; expect = 'allow'; cmd = 'git sparse-checkout list' },
    @{ name = 'git clean -n -d（誤検知しない）';               cwd = $honjin; expect = 'allow'; cmd = 'git clean -n -d' },
    @{ name = 'git clean -n -- path（誤検知しない）';          cwd = $honjin; expect = 'allow'; cmd = 'git clean -n -- backend/build' },
    @{ name = 'git apply --numstat（誤検知しない）';           cwd = $honjin; expect = 'allow'; cmd = 'git apply --numstat fix.patch' },
    @{ name = 'git rm -n（試算のみ・誤検知しない）';           cwd = $honjin; expect = 'allow'; cmd = 'git rm -n -r src' },
    @{ name = 'git log --grep=revert（誤検知しない）';        cwd = $honjin; expect = 'allow'; cmd = 'git log --grep=revert' },
    # worktree 側では当然すべて通る
    @{ name = 'worktree cwd で git stash pop';                cwd = $worktree; expect = 'allow'; cmd = 'git stash pop' },
    @{ name = 'worktree cwd で git revert';                   cwd = $worktree; expect = 'allow'; cmd = 'git revert HEAD' },
    @{ name = 'bash -lc のクォート内 cd＋worktree commit'; cwd = $honjin; expect = 'allow'; cmd = "bash -lc 'cd .claude/worktrees/fix-hook && git commit -m x'" },

    # --- PowerShell ツール経由（Windows の主シェル側からの迂回を塞ぐ） ---
    # 背景: 本陣保護フックの登録 matcher が長らく Bash のみだったため、
    # PowerShell ツールへ切り替えるだけでガードを丸ごと迂回できた（実事故あり）。
    # settings.local.json の PreToolUse に matcher "PowerShell" を足して塞いだ。
    @{ tool = 'PowerShell'; name = 'PS: 本陣 cwd で git commit';        cwd = $honjin;   expect = 'deny';  cmd = 'git commit -m "テスト"' },
    @{ tool = 'PowerShell'; name = 'PS: worktree cwd で git commit';    cwd = $worktree; expect = 'allow'; cmd = 'git commit -m "テスト"' },
    @{ tool = 'PowerShell'; name = 'PS: Set-Location 本陣 ; git commit'; cwd = $worktree; expect = 'deny';  cmd = "Set-Location `"$honjin`" ; git commit -m x" },
    @{ tool = 'PowerShell'; name = 'PS: sl 本陣 ; git commit（別名）';   cwd = $worktree; expect = 'deny';  cmd = "sl `"$honjin`" ; git commit -m x" },
    @{ tool = 'PowerShell'; name = 'PS: Set-Location -Path 本陣 ; git commit'; cwd = $worktree; expect = 'deny'; cmd = "Set-Location -Path `"$honjin`" ; git commit -m x" },
    @{ tool = 'PowerShell'; name = 'PS: -C で本陣を明示して commit';    cwd = $worktree; expect = 'deny';  cmd = "git -C `"$honjin`" commit -m x" },
    @{ tool = 'PowerShell'; name = 'PS: Set-Location worktree ; git commit（誤検知しない）'; cwd = $honjin; expect = 'allow'; cmd = "Set-Location `"$worktree`" ; git commit -m x" },
    @{ tool = 'PowerShell'; name = 'PS: 本陣 cwd で git status（誤検知しない）'; cwd = $honjin; expect = 'allow'; cmd = 'git status --short' }
)

$fail = 0
foreach ($c in $cases) {
    $tool = if ($c.ContainsKey('tool')) { $c.tool } else { 'Bash' }
    $actual = Invoke-Hook -Command $c.cmd -Cwd $c.cwd -Tool $tool
    $ok = ($actual -eq $c.expect)
    if (-not $ok) { $fail++ }
    $mark = if ($ok) { 'OK  ' } else { 'NG  ' }
    Write-Host ("{0}[{1,-10}] 期待={2,-5} 実際={3,-5} : {4}" -f $mark, $tool, $c.expect, $actual, $c.name)
    if (-not $ok) { Write-Host ("      コマンド: {0}" -f ($c.cmd -replace "`n", '\n')) }
}

Write-Host ''
if ($fail -eq 0) {
    Write-Host ("全 {0} 件合格" -f $cases.Count)
    exit 0
} else {
    Write-Host ("{0} 件不一致（全 {1} 件）" -f $fail, $cases.Count)
    exit 1
}
