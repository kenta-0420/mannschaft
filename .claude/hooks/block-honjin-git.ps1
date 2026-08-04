# ============================================================================
# PreToolUse フック: 本陣（メイン作業ディレクトリ）での git 変更操作を拒否する。
# CLAUDE.md 大名システム / memory feedback_branch_isolation の機械的強制。
#
# 仕組み:
#   - 標準入力で PreToolUse の JSON を受け取る（tool_input.command, cwd）。
#   - 変更系 git サブコマンド（checkout/switch/commit/reset/merge/rebase/
#     cherry-pick/pull）でなければ素通り（exit 0）。
#   - コマンドが worktree（.claude/worktrees/）を対象にしていれば許可。
#   - cwd で `git rev-parse` を実行し、git-dir == git-common-dir なら本陣 →
#     permissionDecision=deny を返す。worktree（不一致）や判定不能時は許可
#     （commit 自体は別途 .git/hooks/pre-commit が確実に弾くため fail-open）。
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

# 変更系 git サブコマンドにマッチしなければ関知しない。
# `git -C <path> commit` のように git と本体サブコマンドの間に
# -C/--git-dir 等のオプションが挟まるケースも拾えるよう、
# 間に任意個のオプション（先頭が - のトークン）を許容する。
$mutating = 'git(\s+-{1,2}\S+(\s+\S+)?)*\s+(checkout|switch|commit|reset|merge|rebase|cherry-pick|pull)\b'
if ($cmd -notmatch $mutating) { exit 0 }

# worktree を明示的に対象にしているコマンドは許可
if ($cmd -match '\.claude[\\/]+worktrees') { exit 0 }
if ($cmd -match 'git\s+worktree\b') { exit 0 }

# WSL 経由のコマンドは関知しない。
# 本フックは Windows 側 CWD からの git-dir 判定しかできないため、
# `wsl git -C /home/kenta/mannschaft commit ...` のように操作対象が
# WSL 側の別リポジトリであっても、ツール呼び出し時の Windows 側 cwd
# （本陣 C:\Claude\mannschaft）に基づいて誤検知・誤 deny してしまう。
# WSL 経由コマンドはこの Windows 側フックの対象外とする
# （WSL 側リポジトリの保護は WSL 側の pre-commit フック等で別途担保する想定）。
if ($cmd -match '(^|[\s;&|`])wsl(\.exe)?([\s;&|`]|$)') { exit 0 }

# git コマンド中に明示的な対象ディレクトリ指定（-C <path> / --git-dir=<path>）が
# あれば、そちらを判定対象にする（cwd だけで判定すると、cwd は本陣のまま
# 明示的に別リポジトリ＝WSL 側パス等を対象にしているケースを見落とす／
# 逆に誤検知することがある）。
$targetDir = $cwd
if ($cmd -match 'git\s+(?:\S+\s+)*-C\s+["'']?([^\s"'']+)') {
    $targetDir = $Matches[1]
} elseif ($cmd -match '--git-dir=["'']?([^\s"'']+)') {
    $targetDir = $Matches[1]
}

# 対象ディレクトリが WSL 側の絶対パス（POSIX パス）を指している場合も関知しない。
if ($targetDir -match '^/') { exit 0 }

# 対象ディレクトリが無ければ安全側で素通り（pre-commit フックが最終防衛線）
if ([string]::IsNullOrWhiteSpace($targetDir)) { exit 0 }

# 対象ディレクトリで git-dir と git-common-dir を比較。一致＝本陣。
$gitDir = (& git -C "$targetDir" rev-parse --path-format=absolute --absolute-git-dir 2>$null)
$common = (& git -C "$targetDir" rev-parse --path-format=absolute --git-common-dir 2>$null)
if ([string]::IsNullOrWhiteSpace($gitDir)) { exit 0 }   # git 管理外なら関知しない

$norm = { param($p) ($p -replace '/','\').TrimEnd('\').ToLower() }
if ((& $norm $gitDir) -ne (& $norm $common)) { exit 0 }  # worktree なら許可

# --- ここに到達したら本陣での変更系 git 操作 → deny ---
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
