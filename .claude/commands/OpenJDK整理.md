---
description: メモリ常駐JVM（Gradleデーモン/テストワーカー/bootRun）の実測→残骸のみ停止→再計測。ディスク上のJDK整理も任意で
argument-hint: "[診断 | 掃除 | JDK]（既定: 診断→提案して裁可待ち）"
model: sonnet
---

# OpenJDK整理（メモリ常駐 JVM の回収）

> このスキルは運用作業のため `model: sonnet` 指定（殿のOpus/Fable温存・`feedback_opus_infra_ops_danger`）。

終わった足軽が残した **Gradle デーモン・テストワーカー・gradlew ランチャー**が
Windows と WSL の物理メモリを数GB単位で食い続ける。これを
**実測 → 稼働/残骸の切り分け → 残骸のみ停止 → 再計測** の順で回収する。

これは環境の運用操作であり、本陣 `C:/Claude/mannschaft` で実行してよい例外作業（CLAUDE.md 参照）。

`$ARGUMENTS` のモード:
- **未指定 / `診断`** — 手順1〜3のみ。内訳を表にして殿に提示し、停止対象を提案して裁可を待つ
- **`掃除`** — 手順1〜5（残骸の停止と再計測まで）
- **`JDK`** — 手順1〜5 に加え手順6（ディスク上の未使用 JDK の棚卸し。**削除は別途裁可**）

近縁スキル: `/Vmm管理`（WSL の vmmem 全体・Docker コンテナ込み）、`/陣払い`（worktree ディレクトリ）。
**メモリが重い原因が JVM だと分かっているならこちら、vmmem 全体なら `/Vmm管理`。**

---

## 厳守する安全則

- **CPU がゼロでも「不要」ではない。** Gradle のロック待ちで固まった足軽ビルドは CPU 0 で
  何十分も待つ（`feedback_subagent_waits_on_already_failed_build`・
  `project_gradle_shared_build_cache_lock_is_the_slowness`）。停止すればその足軽の成果は失われる。
- **Gradle デーモンは worktree をまたいで共有される。** 「アイドルなデーモン」を落としたつもりが、
  別 worktree で走行中のビルドの親を落としうる。**停止後に稼働中だったはずの JVM が消えていたら、
  道連れの可能性を隠さず報告する**（`feedback_verify_tool_output_fabrication`）。
- **WSL の常駐 BE（`bootRun`）は開発の土台。止めない。** 止めると実機E2Eが全滅する。
  本陣8080 / 検証8081（CLAUDE.md「ポート一覧」）。**8080 を残骸と誤認しない。**
- 疑わしいものは**残す**。残骸1本の見逃し（数GB）より、稼働中を殺す損失（長時間ビルドの破棄・足軽の全損）が大きい。
- **停止は必ず殿の裁可を得てから**（手順3）。`診断` で終わるのが既定。

## 計測時の落とし穴（先に読む）

- **Windows 側の数値は PowerShell ツールで取る。** Bash 経由の `powershell.exe`・`ls /c/Program Files/...` は
  フック（rtk）に出力を潰されて**空を返すことがある**（`feedback_rtk_proxy_crushes_tool_output`）。
  空が返ったら「0件」ではなく「測れていない」と読む（`feedback_empty_tool_output_is_not_absence`）。
- **`wsl.exe` の出力には NUL バイトが混じる** → `| tr -d '\0'` を通す。
- **プロセス一覧は時間とともに入れ替わる。** 手順1の PID をそのまま手順4で kill してはならない。
  **停止直前に必ず PID とコマンドラインの同一性を取り直す**（手順4-A）。
- JVM は SIGTERM 後 shutdown hook のため数秒生き残る。**即座の再確認で「死んでいない」と判断しない。**

---

## 手順

### 1. 実測（Windows 側と WSL 内を両方）

PowerShell ツールで（Bash 経由にしない）:
```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | ForEach-Object {
  $p = Get-Process -Id $_.ProcessId -EA SilentlyContinue
  "PID {0}`t{1} MB`t{2}" -f $_.ProcessId, [int]($p.WorkingSet64/1MB),
    $_.CommandLine.Substring(0,[Math]::Min(200,$_.CommandLine.Length))
}
$os = Get-CimInstance Win32_OperatingSystem
"空き {0:N1} GB / 全 {1:N1} GB" -f ($os.FreePhysicalMemory/1MB), ($os.TotalVisibleMemorySize/1MB)
```

WSL 内（Bash ツールで可）:
```bash
wsl.exe -e bash -lc 'ps -eo pid,rss,pcpu,etime,args --sort=-rss | grep "[j]ava" | cut -c1-200; echo ---; free -m | head -2' 2>&1 | tr -d '\0'
```

### 2. 素性の判別（JVM は3層ある。混ぜない）

コマンドラインの先頭で正体が割れる:

| コマンドラインの特徴 | 正体 | 典型RSS | 判定 |
|---|---|---|---|
| `--add-opens=java.base/...` が並ぶ | **Gradle デーモン** | 0.5〜2 GB | worktree 横断で共有。アイドルなら停止候補 |
| `-Dcom.mysql.cj.disableAbandonedConnectionCleanup=true -Dorg.gradle.internal.worker.tmpdir=...` | **テストワーカー**（`GradleWorkerMain`） | 1〜2 GB | 親デーモンが死んでいれば孤児＝残骸 |
| `-Xmx64m -Xms64m -Dorg.gradle.appname=gradlew` | **gradlew ランチャー** | 30〜130 MB | 生きている＝そのビルドがまだ待機中の可能性 |
| `-jar build/libs/app-*.jar` / `bootRun` | **常駐 BE** | 約3 GB | 8080/8081 は**温存**。数時間放置の検証用のみ残骸 |

`-Dorg.gradle.internal.worker.tmpdir=` と `gradle-wrapper.jar` のパスに **worktree 名が入っている**。
これで「どの足軽のものか」まで割れる。報告の表には必ずこの worktree 名を書く。

### 3. 稼働/残骸の切り分け（CPU を時間差で測る・省略不可）

一瞬のスナップショットでは分からない。**6秒の CPU 増分**で測る:
```powershell
$a=@{}; Get-Process java -EA SilentlyContinue | ForEach-Object { $a[$_.Id]=$_.CPU }
Start-Sleep -Seconds 6
"PID`tMB`tCPU_delta_sec"
Get-Process java -EA SilentlyContinue | ForEach-Object {
  "{0}`t{1}`t{2:N1}" -f $_.Id, [int]($_.WorkingSet64/1MB), ($_.CPU - $a[$_.Id])
}
```
- **CPU delta が 1秒/6秒 以上 → 稼働中。触らない**（そのビルドの全 JVM を温存対象にする）
- **CPU delta が 0〜0.5秒 → アイドル。**ただし安全則のとおり「ロック待ちの足軽」の可能性が残る。
  `git worktree list` と突き合わせ、**該当 worktree に進行中の足軽が居ないか**を確認してから候補に入れる。
- 一瞬で新しい PID が現れる／消える worktree は**ビルド進行中の証拠**。その worktree 全体を温存する。

#### 停止対象を殿に提示（`診断` モードはここで終了）

プロセス／RSS／CPU delta／worktree 名／正体／判定 の表にして提示し、「約N GB 解放見込み」まで書く。
`AskUserQuestion` で範囲（アイドルのみ／`gradlew --stop` のみ／Windows側全部／worktree掃除も同時）を選ばせる。
**裁可なしに手順4へ進まない。**

### 4. 停止（`掃除` 以降）

#### 4-A. 直前の同一性確認（省略禁止）
手順1から時間が経っている。**PID が別プロセスに再利用されていないか**を確かめる:
```powershell
$keep = @(<温存PID>); $targets = @(<停止PID>)
"=== 温存 ==="; Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $keep -contains $_.ProcessId } |
  ForEach-Object { "PID {0} : {1}" -f $_.ProcessId, ($_.CommandLine -replace '.*worktrees[\/]([^\/]+).*','$1') }
"=== 停止対象 ==="; Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $targets -contains $_.ProcessId } |
  ForEach-Object { "PID {0} : {1}" -f $_.ProcessId, $_.CommandLine.Substring(0,120) }
```
**温存対象の PID が消えていたら、そのビルドは既に終わったか入れ替わっている。**
リストを作り直す。停止対象に「知らないコマンドライン」が現れたら、そこで止めて殿に報告する。

#### 4-B. 停止（ランチャー → ワーカー → デーモンの順）
親を先に殺すと子が孤児として残る。**外側から**落とす:
```powershell
$order = @(<ランチャーPID...>, <ワーカーPID...>, <デーモンPID...>)
$freed = 0
foreach ($id in $order) {
  $p = Get-Process -Id $id -EA SilentlyContinue
  if ($p -and $p.ProcessName -eq 'java') {          # 名前で二重確認（PID再利用よけ）
    $mb = [int]($p.WorkingSet64/1MB)
    try { Stop-Process -Id $id -Force -EA Stop; $freed += $mb; "停止 PID $id ($mb MB)" }
    catch { "失敗 PID $id : $_" }
  } else { "対象外/不在 PID $id" }
}
"=== 解放合計: $freed MB ==="
```

WSL 側の残骸を落とす場合（**`bootRun` を含めない**）:
```bash
wsl.exe -e bash -lc 'kill <PID> <PID>' 2>&1 | tr -d '\0'
```
JVM は数秒かかる。**別のツール呼び出しで**死亡を裏取りする:
```bash
wsl.exe -e bash -lc 'ps -p <PID>,<PID> -o pid=,rss=,stat=; echo "---exit"' 2>&1 | tr -d '\0'
```
`---exit` だけが返れば死亡。残っていたら `kill -9`。

#### 4-C. 代替（安全側に倒す場合）
`gradlew --stop` は Gradle の正規手順でデーモンのみ落とす。ただし**孤児テストワーカー（1〜2GB級）は残る**ため
解放量は落ちる。殿が「安全最優先」を選んだときのみこちら。

### 5. 再計測（省略不可）

手順1をそのまま再実行し、**before → after** で報告する（例: `Windows JVM 16個 12.5GB → 4個 1.9GB`、
`空き 7.3GB → 18.6GB`）。「軽くなったはず」では報告しない（`feedback_verify_tool_output_fabrication`）。

**このとき温存したはずの JVM が消えていたら、隠さず書く。**
Gradle デーモン共有による道連れか、ビルドの自然終了かは断定できない ——
**断定せず両方の可能性を示し、「必要なら走らせ直してください」と明記する。**

### 6. ディスク上の JDK 棚卸し（`JDK` モードのみ・削除は別途裁可）

メモリではなくディスクの話。**混ぜて報告しない。**
```powershell
foreach ($p in @("C:\Program Files\Eclipse Adoptium","C:\Program Files\Java","C:\Program Files\Microsoft",
                 "C:\Program Files\Amazon Corretto","C:\Program Files\Zulu","$env:USERPROFILE\.jdks")) {
  if (Test-Path $p) { "=== $p"
    Get-ChildItem $p -Directory | ForEach-Object {
      "{0}`t{1:N0} MB" -f $_.Name, ((Get-ChildItem $_.FullName -Recurse -File -EA SilentlyContinue |
        Measure-Object Length -Sum).Sum/1MB) } } }
"JAVA_HOME=$env:JAVA_HOME"
```
```bash
wsl.exe -e bash -lc 'ls -d /usr/lib/jvm/*; echo ---; du -sh /usr/lib/jvm/* 2>/dev/null; readlink -f $(which java)' 2>&1 | tr -d '\0'
```

**削除してよいかの判定材料（全部揃うまで消さない）:**
- プロジェクトのツールチェーンは **Java 21 固定**（`backend/build.gradle.kts` の `JavaLanguageVersion.of(21)`）。
  ここに一致するものは**絶対に消さない**。
- `JAVA_HOME` が指すものは消さない。
- `/usr/lib/jvm/java-1.21.0-openjdk-amd64` のような**サイズ 0 はシンボリックリンク**。消しても何も減らない。
- IDE（IntelliJ）・他プロジェクトが参照している可能性が残る。**確認できないものは残す。**

削除は**この手順の外**。棚卸し表を提示し、殿の明示的な裁可を得てから別途行う。

---

## 報告フォーマット

1. **before の内訳表**（PID／RSS／CPU delta／worktree 名／正体／判定）
2. **止めたもの**（PID・正体・解放量の合計）
3. **温存したもの**（稼働中ビルドと WSL `bootRun`。止めなかった理由つき）
4. **after の数字**（JVM 個数と空きメモリ、before と並べて）
5. **想定外**（温存対象が消えていた等。道連れの可能性を隠さず、再実行の要否を書く）
6. **やらなかったこと**（ディスク上の JDK・worktree 掃除など。`/陣払い` への導線を添える）
