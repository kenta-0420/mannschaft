# ============================================================================
# PreToolUse フック: 本陣（メイン作業ディレクトリ）での git 変更操作を拒否する。
# CLAUDE.md 大名システム / memory feedback_branch_isolation の機械的強制。
#
# 仕組み:
#   - 標準入力で PreToolUse の JSON を受け取る（tool_input.command, cwd）。
#   - コマンド行を ; / && / || 区切りでセグメントに分割し、セグメントごとに
#     判定する（複合コマンドの一部だけが変更系操作であっても見落とさない）。
#     分割はクォート認識で行い、'...' / "..." の内側の区切り文字では分割しない
#     （分断すると `wsl ... 'cd X && git checkout'` の wsl 接頭辞が失われ、
#     WSL 側なのに Windows の cwd＝本陣を対象と誤認して誤検知するため）。
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
#
# `\b` はハイフンの直前でも境界として成立するため、`git merge-base`
# （読み取り専用）が `merge` と誤認されて拒否されていた。実際に
# `git merge-base --is-ancestor` が本陣の同期作業を妨げた。
#
# 直し方として「サブコマンド末尾を `(?![\w-])` で閉じる」は採らない。それでは
# merge-recursive / merge-octopus / merge-resolve / merge-ours / merge-subtree
# や checkout-index / merge-file / merge-index / merge-one-file といった、
# インデックスや作業木を書き換える配管コマンドまで素通りし、保護に穴が開く
# （安全な側を列挙し切るのは取りこぼしやすい）。
#
# よって既定は従来どおり `\b` で拒否したままとし、**読み取り専用であることが
# 確かなものだけ**を否定先読みで除外する。既定が拒否なので、除外し忘れは
# 誤検知（不便）になるだけで、保護の穴にはならない。
#
# 判定リストは「本陣の作業木・インデックス・ローカル参照を書き換える」ものを
# 網羅する。以前は checkout/switch/commit/reset/merge/rebase/cherry-pick/pull の
# 8 つしか無く、`git stash pop`・`git clean -fd`・`git revert`・`git restore` などが
# 本陣で素通りしていた（2026-09-03 実測）。共有作業木ゆえ、これらは他セッションの
# 未コミット作業を消しうる。とくに stash は退避スタックが全作業木で共有される。
#
# push / fetch は本陣の作業木を変えないため対象外（従来どおり）。
# tag の作成は他セッションと衝突しにくく害が小さいため見送る。
# 「読み取り専用フラグが1つ以上あり、かつ他のトークンがすべて無害」なときだけ
# 除外する否定先読みを組み立てる。無害なトークンとは、$Safe に挙げたフラグか、
# フラグでない語（パス等）である。集合に無い語が1つでも混じれば除外は成立せず、
# 拒否側へ倒れる。`--no-dry-run` のような打ち消しオプションはここで弾かれる。
# なお `--` はオプション終端であり、その後ろの語は**フラグではなくパス名**である。
# `git rm -- --dry-run` は「--dry-run という名のファイルを消す」操作であって
# 試算ではない。よって読み取り専用フラグは `--` より前にあるときだけ認め、
# `--` 以降はパスとしてのみ許す（#3082 の Codex 検分が P1 として摘発）。
function Read-OnlyForm {
    param([string]$Sub, [string]$Required, [string]$Safe)
    $before = '(?:' + $Safe + '|[^-\s]\S*)'      # `--` より前に置ける語
    $after  = '(?:' + $Safe + '|--|[^-\s]\S*)'   # `--` 以降はパスも来る
    return $Sub + '(?!(?:\s+' + $before + ')*\s+(?:' + $Required + ')(?:\s+' + $after + ')*\s*$)'
}

$mutating = 'git(\s+-{1,2}\S+(\s+\S+)?)*\s+(' + (@(
    # --- 読み取り専用の形を持たないもの（無条件に拒否） ---
    'checkout', 'switch', 'restore', 'commit', 'reset', 'rebase', 'cherry-pick',
    'pull', 'revert', 'am', 'update-ref',
    # --- 読み取り専用の形があるもの（それだけを否定先読みで除外） ---
    # 既定は拒否のままにする。除外し忘れは誤検知（不便）で済むが、列挙し漏れは
    # そのまま保護の穴になるため（#3079 の Codex 検分で実際に穴が見つかった）。
    # 除外の判定は**セグメント全体**を見る。先頭トークンだけで読み取り専用と
    # 判断すると、後続の打ち消しオプションで迂回できてしまう（#3082 の Codex
    # 検分が P1 として摘発）。git は真偽オプションに `--no-` 形を自動生成するため、
    #   git clean --dry-run --no-dry-run -f   … 実際にはファイルを消す
    #   git apply --check --apply patch       … 実際にはパッチを当てる
    # がいずれも「先頭が読み取り専用フラグ」を満たしてしまう。
    #
    # よって Read-OnlyForm は「読み取り専用フラグが1つ以上あり、**かつ他の
    # トークンがすべて無害な集合に収まる**」ときだけ除外する。集合に無い語が
    # 1つでも混じれば拒否側へ倒れるので、`--no-` 形も未知のオプションも通らない。
    'merge(?!-(?:base|tree)\b)',                  # merge-base / merge-tree は読み取り専用
    'stash(?!\s+(?:list|show)\b)',                # stash list / show は読み取り専用
    (Read-OnlyForm 'clean' '-[dxXq]*n[dxXq]*|--dry-run' '-[dxXqn]+|--dry-run|--quiet'),
    (Read-OnlyForm 'apply' '--check|--stat|--numstat|--summary' '--check|--stat|--numstat|--summary|--verbose|-v'),
    (Read-OnlyForm 'rm'    '-n|--dry-run' '-n|--dry-run|-r|-q|--cached'),
    (Read-OnlyForm 'mv'    '-n|--dry-run' '-n|--dry-run|-v'),
    'sparse-checkout(?!\s+(?:list|check-rules)\b)',
    # branch は一覧表示が主用途のため、**引数を伴う形**（作成・改名・削除・追跡
    # 設定はいずれもブランチ名を取る）だけを拒否する。`git branch feature/x` の
    # ようにオプション無しの作成も共有リポジトリのローカル参照を変えるため、
    # 破壊的オプションの列挙では捕まえられない（#3082 の Codex 検分で判明）。
    'branch(?=(?:\s+-\S+)*\s+[^-\s])',
    # 引数を取らない破壊的オプション
    'branch\s+(?:--unset-upstream|--edit-description)\b'
) -join '|') + ')\b'

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

# コマンド行を ; / && / || でセグメントに分割する（複合コマンドの各セグメントを
# 個別に判定するため）。ただし **クォートの内側にある区切り文字では分割しない**。
#
# なぜクォートを見る必要があるか（誤検知の根治）:
#   wsl -d Ubuntu-24.04 -- bash -lc 'cd ~/verify && git checkout -B x origin/main'
#   を素朴に分割すると `git checkout -B x origin/main` という断片になり、
#   先頭の `wsl` が失われる。すると「wsl 経由」の判定が偽になり、本来は
#   判定不能として fail-open すべきところで Windows 側の cwd（本陣）が
#   対象とみなされ、誤って deny されていた。
#   つまり分断は「保護が緩む」方向ではなく「締まりすぎる」方向に倒れる。
#   同じことは `bash -lc 'cd X && git commit'` や
#   `powershell -Command "... && git reset"` でも起きるため、
#   wsl だけを特別扱いするのではなくクォート認識そのものを直す。
#
# 分割規則:
#   - シングルクォート内は何も解釈しない（終端の ' まで素通し）
#   - ダブルクォート内はバックスラッシュエスケープのみ解釈して素通し
#   - クォート外のバックスラッシュエスケープは次の1文字を素通し
#   - クォートが閉じないまま終端した場合、残り全体を1セグメントとして扱う
#     （判定対象が cwd のままになるだけで、保護は緩まない）
function Split-CommandSegments {
    param([string]$command)

    $segments = New-Object System.Collections.Generic.List[string]
    $sb = New-Object System.Text.StringBuilder
    $quote = $null   # 現在囲まれているクォート文字（' または "）。囲まれていなければ $null
    $i = 0

    while ($i -lt $command.Length) {
        $ch = $command[$i]

        if ($null -ne $quote) {
            if ($ch -eq '\' -and $quote -eq '"' -and ($i + 1) -lt $command.Length) {
                [void]$sb.Append($ch); [void]$sb.Append($command[$i + 1]); $i += 2; continue
            }
            if ($ch -eq $quote) { $quote = $null }
            [void]$sb.Append($ch); $i++; continue
        }

        if ($ch -eq "'" -or $ch -eq '"') {
            $quote = $ch; [void]$sb.Append($ch); $i++; continue
        }
        if ($ch -eq '\' -and ($i + 1) -lt $command.Length) {
            [void]$sb.Append($ch); [void]$sb.Append($command[$i + 1]); $i += 2; continue
        }
        if (($ch -eq '&' -or $ch -eq '|') -and ($i + 1) -lt $command.Length -and $command[$i + 1] -eq $ch) {
            [void]$segments.Add($sb.ToString()); [void]$sb.Clear(); $i += 2; continue
        }
        if ($ch -eq ';') {
            [void]$segments.Add($sb.ToString()); [void]$sb.Clear(); $i++; continue
        }

        [void]$sb.Append($ch); $i++
    }

    [void]$segments.Add($sb.ToString())
    return $segments
}

$segments = Split-CommandSegments $cmd

# 複合コマンドの途中の `cd <path>` を追跡し、以降のセグメントの実効 cwd とする。
# `cd .claude/worktrees/foo && git commit` は実際には worktree の中で走るので
# ツール呼び出し時の cwd（本陣）で判定すると誤検知になる。
# 逆に `cd C:\Claude\mannschaft && git commit` を worktree から実行した場合は
# 実効 cwd が本陣になるため、追跡することで**保護は強くなる**。
$effectiveCwd = $cwd

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

    # 単独の `cd <path>` セグメントは実効 cwd の更新として扱う（wsl 経由は除く。
    # WSL 側のファイルシステムは Windows の cwd とは別物のため追跡しない）。
    #
    # PowerShell ツール経由では `cd` の別名（Set-Location / sl / chdir）が使われる。
    # これらを追跡しないと `Set-Location <本陣>; git commit` で実効 cwd が worktree の
    # ままと誤判定され、ガードを丸ごと迂回できてしまう（当家で実際に迂回された形）。
    # なお Push-Location / pushd は意図的に扱わない。対になる Pop-Location を
    # 追跡できないため、追跡すると「pop 後も worktree 扱い」で保護が緩む方向に倒れる。
    if ((-not $isWsl) -and ($inner -match '^\s*(?:cd|chdir|sl|Set-Location)\s+(?:-(?:Path|LiteralPath)\s+)?["'']?([^"''&|;]+?)["'']?\s*$')) {
        $cdTarget = $Matches[1].Trim()
        if ($cdTarget -match '^([A-Za-z]:[\\/]|[\\/])') {
            $effectiveCwd = $cdTarget            # 絶対パス
        } else {
            $effectiveCwd = Join-Path $effectiveCwd $cdTarget   # 相対パス
        }
        continue
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
        # wsl 経由でない場合のみ、実効 cwd（cd 追跡込み）を対象とみなす。
        $targetDir = $effectiveCwd
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
