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
    param([string]$Command, [string]$Cwd)
    $payload = @{ cwd = $Cwd; tool_input = @{ command = $Command } } | ConvertTo-Json -Compress -Depth 5
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

    # --- 通されるべき（誤検知しないこと） ---
    @{ name = 'worktree cwd で git commit';        cwd = $worktree; expect = 'allow'; cmd = 'git commit -m "テスト"' },
    @{ name = '-C で worktree 絶対パスを指定';     cwd = $honjin;   expect = 'allow'; cmd = "git -C `"$worktree`" commit -m x" },
    @{ name = 'wsl 内クォートの && 越しの checkout'; cwd = $honjin; expect = 'allow'; cmd = "wsl -d Ubuntu-24.04 -- bash -lc 'cd ~/verify-0810 && git checkout -B verify origin/main'" },
    @{ name = 'git worktree add';                  cwd = $honjin;   expect = 'allow'; cmd = 'git worktree add .claude/worktrees/foo -b feature/foo origin/main' },
    @{ name = 'cd worktree してから commit';       cwd = $honjin;   expect = 'allow'; cmd = 'cd .claude/worktrees/fix-hook && git commit -m x' },
    @{ name = 'git 以外（変更系サブコマンド無し）'; cwd = $honjin;   expect = 'allow'; cmd = 'git status' },
    @{ name = 'bash -lc のクォート内 cd＋worktree commit'; cwd = $honjin; expect = 'allow'; cmd = "bash -lc 'cd .claude/worktrees/fix-hook && git commit -m x'" }
)

$fail = 0
foreach ($c in $cases) {
    $actual = Invoke-Hook -Command $c.cmd -Cwd $c.cwd
    $ok = ($actual -eq $c.expect)
    if (-not $ok) { $fail++ }
    $mark = if ($ok) { 'OK  ' } else { 'NG  ' }
    Write-Host ("{0}期待={1,-5} 実際={2,-5} : {3}" -f $mark, $c.expect, $actual, $c.name)
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
