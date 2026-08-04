# ============================================================================
# PreToolUse フック: 本陣（メイン作業ディレクトリ）での git 変更操作を拒否する。
# CLAUDE.md 大名システム / memory feedback_branch_isolation の機械的強制。
#
# 仕組み:
#   - 標準入力で PreToolUse の JSON を受け取る（tool_input.command, cwd）。
#   - コマンド行を ; / && / || 区切りでセグメントに分割し、セグメントごとに
#     判定する（複合コマンドの一部だけが変更系操作であっても見落とさない）。
#   - 各セグメントについて、先頭が wsl(.exe) 呼び出しならそれを剥がし、
#     内側のコマンドを本体として扱う。
#   - 変更系 git サブコマンド（checkout/switch/commit/reset/merge/rebase/
#     cherry-pick/pull）でなければそのセグメントは関知しない。
#   - セグメントが `.claude/worktrees/` や `git worktree` を対象にしていれば
#     そのセグメントは許可。
#   - 対象ディレクトリを解決する:
#       - `-C <path>` / `--git-dir=<path>` があればそれを使う
#       - 無ければ、wsl 経由でない場合のみツール呼び出し時の cwd を使う
#         （wsl 経由かつ -C 等の明示指定が無い場合は WSL 側の実際の cwd が
#         不明なため、そのセグメントは判定不能として関知しない＝fail-open）
#   - 対象パスが `/mnt/<ドライブ文字>/...` 形式（大文字小文字・末尾スラッシュ
#     不問）であれば Windows パスへ読み替える（例: /mnt/c/Claude/mannschaft
#     → C:\Claude\mannschaft）。WSL からは /mnt 経由で本陣そのものに手が
#     届くため、これを一律免除すると素通りの抜け道になる。読み替えた後は
#     通常の Windows パスと同じ本陣判定にかける。
#   - 読み替え後もなお `/` 始まりのパス（例: /home/kenta/mannschaft など
#     WSL ネイティブ側のパス）は、本陣とは別のリポジトリとみなし関知しない。
#   - 解決した対象ディレクトリで `git rev-parse` を実行し、git-dir ==
#     git-common-dir なら本陣 → permissionDecision=deny を返す。
#     worktree（不一致）や判定不能時は許可（commit 自体は別途
#     .git/hooks/pre-commit が確実に弾くため fail-open）。
#   - いずれかのセグメントが deny 判定になった時点でそのコマンド全体を deny。
#
# 注意: このファイルは日本語を含むため UTF-8 BOM 付きで保存すること
#       （PowerShell 5.1 は BOM 無し UTF-8 を ANSI 誤認して構文エラーになる）。
# ============================================================================
$ErrorActionPreference = 'SilentlyContinue'
# stdout を UTF-8 にする（deny メッセージの日本語が文字化けしないように）
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false } catch {}

try {
    $raw = [Console]::In.ReadToEnd()
    if ([string]::IsNullOrWhiteSpace($raw)) { exit 0 }
    $payload = $raw | ConvertFrom-Json
} catch { exit 0 }

$cmd = [string]$payload.tool_input.command
$cwd = [string]$payload.cwd
if ([string]::IsNullOrWhiteSpace($cmd)) { exit 0 }

# 変更系 git サブコマンドの判定パターン。
# `git -C <path> commit` のように git と本体サブコマンドの間に
# -C/--git-dir 等のオプションが挟まるケースも拾えるよう、
# 間に任意個のオプション（先頭が - のトークン）を許容する。
$mutating = 'git(\s+-{1,2}\S+(\s+\S+)?)*\s+(checkout|switch|commit|reset|merge|rebase|cherry-pick|pull)\b'

# `/mnt/<ドライブ文字>/...` 形式の WSL パスを Windows パスへ読み替える。
# 大文字小文字・末尾スラッシュを問わない。読み替え不可なら元の文字列を返す。
function Resolve-WindowsPathFromWsl {
    param([string]$p)
    if ($p -match '^/mnt/([A-Za-z])(/(.*))?$') {
        $drive = $Matches[1].ToUpper()
        $rest = $Matches[3]
        if ([string]::IsNullOrEmpty($rest)) {
            return "$drive`:\"
        }
        $rest = ($rest -replace '/', '\').TrimEnd('\')
        return "$drive`:\$rest"
    }
    return $p
}

# 与えられたディレクトリが本陣（git-dir と git-common-dir が一致する非 worktree
# リポジトリのルート）かどうかを判定する。判定できなければ $false（fail-open）。
function Test-IsHonjinDir {
    param([string]$dir)
    if ([string]::IsNullOrWhiteSpace($dir)) { return $false }

    # 読み替えてもなお POSIX 絶対パス（例: /home/kenta/mannschaft）なら
    # WSL ネイティブ側の別リポジトリとみなし、本陣とは判定しない。
    $resolved = Resolve-WindowsPathFromWsl $dir
    if ($resolved -match '^/') { return $false }

    $gitDir = (& git -C "$resolved" rev-parse --path-format=absolute --absolute-git-dir 2>$null)
    $common = (& git -C "$resolved" rev-parse --path-format=absolute --git-common-dir 2>$null)
    if ([string]::IsNullOrWhiteSpace($gitDir)) { return $false }   # git 管理外なら関知しない

    $norm = { param($p) ($p -replace '/', '\').TrimEnd('\').ToLower() }
    if ((& $norm $gitDir) -ne (& $norm $common)) { return $false }  # worktree なら本陣ではない
    return $true
}

# コマンド行を ; / && / || で素朴に分割する（複合コマンドの各セグメントを
# 個別に判定するため）。クォート内の区切り文字までは考慮しないが、その場合
# 誤ってセグメントが分断されても既定の判定対象（cwd）が変わるわけではない
# ので、保護が緩む方向には倒れない。
$segments = [regex]::Split($cmd, '\s*(?:&&|\|\||;)\s*')

$denyReason = $null
foreach ($segment in $segments) {
    if ([string]::IsNullOrWhiteSpace($segment)) { continue }

    # 先頭の wsl(.exe) 呼び出しを剥がし、内側のコマンドを本体として扱う。
    # 「セグメントのどこかに wsl という語がある」だけで全体を免除すること
    # はしない（それでは無関係な wsl を混ぜるだけで抜け道になる）。
    $isWsl = $false
    $inner = $segment
    if ($segment -match '^\s*wsl(\.exe)?\s+(.*)$') {
        $isWsl = $true
        $inner = $Matches[2]
    }

    if ($inner -notmatch $mutating) { continue }

    # worktree を明示的に対象にしているセグメントは許可
    if ($inner -match '\.claude[\\/]+worktrees') { continue }
    if ($inner -match 'git\s+worktree\b') { continue }

    # 対象ディレクトリを解決する。
    $targetDir = $null
    if ($inner -match 'git\s+(?:\S+\s+)*-C\s+["'']?([^\s"'']+)') {
        $targetDir = $Matches[1]
    } elseif ($inner -match '--git-dir=["'']?([^\s"'']+)') {
        $targetDir = $Matches[1]
    } elseif (-not $isWsl) {
        # wsl 経由でない場合のみ、ツール呼び出し時の cwd を対象とみなす。
        $targetDir = $cwd
    }
    # wsl 経由かつ -C 等の明示指定が無い場合、WSL 側の実際の cwd は
    # このフックからは分からないため $targetDir は $null のまま
    # （＝判定不能として fail-open。pre-commit フックが最終防衛線）。

    if ([string]::IsNullOrWhiteSpace($targetDir)) { continue }

    if (Test-IsHonjinDir $targetDir) {
        $denyReason = $segment
        break
    }
}

if (-not $denyReason) { exit 0 }

# --- 本陣での変更系 git 操作 → deny ---
$reason = @'
本陣（メイン作業ディレクトリ）での git 変更操作は禁止です。
別セッションとの HEAD 衝突・作業消失を防ぐため、worktree に隔離してから作業してください。

  git worktree add .claude/worktrees/<名前> -b feature/<説明> origin/main
  cd .claude/worktrees/<名前>    # この中で checkout / commit する

（CLAUDE.md 大名システム / feedback_branch_isolation）
'@

$result = @{
    hookSpecificOutput = @{
        hookEventName            = 'PreToolUse'
        permissionDecision       = 'deny'
        permissionDecisionReason = $reason
    }
}
$result | ConvertTo-Json -Compress -Depth 5
exit 0
