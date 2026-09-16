# docs/openapi.json の再生成手順と失敗時の診断

`docs/openapi.json` は必須 CI チェック「OpenAPI Drift Check」の入力であり、API 表面（エンドポイント・
リクエスト/レスポンス DTO）を変えたら必ず再生成してコミットする。**手編集は禁止**（過去にエスケープが
壊れて 128 箇所が無意味な差分になった）。

## 手順

```bash
cd backend
./gradlew generateOpenApiDocs --console=plain
```

タスクは springdoc-openapi-gradle-plugin が提供する。中身は

1. `classes`（コンパイル）
2. アプリを**フォークした Spring Boot プロセス**として `openapi-gen` プロファイル・`:8082`（既定）で起動
3. `http://localhost:8082/v3/api-docs` を GET
4. 結果を `docs/openapi.json` に書き出してフォークを停止

という流れ。コールド環境では起動 + 初回スキャンで数分〜十数分かかる。

### 起動前の確認 — 順序に注意

生成は**ポートだけでなく出力先も占有する**（`docs/openapi.json` と `backend/build`。
`outputDir` / `outputFileName` は固定で `-PopenApiPort` では変わらない）。
**出力先の競合はポートを見ても分からない**ので、確認は次の順で行う。

#### 手順1: 自分の worktree で生成が走っていないか（ポート非依存）

**ポート起点ではなくプロセス起点で探す。** 別ポート（`-PopenApiPort=8099` 等）で
走っている生成は `:8082` を見ても見つからないためである。

```bash
MINE=$(git rev-parse --show-toplevel) || { echo "[!] git に失敗 → 確認不能。起動しない"; exit 2; }
MINE_NAME=$(basename "$MINE")
WIN_TMP=$(mktemp); WSL_TMP=$(mktemp)

# --- Windows 側 ---
# パイプで繋ぐと末尾の終了状態しか残らないので、まず結果をファイルに落として rc を個別に受け取る
powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" -ErrorAction Stop |
  Where-Object { \$_.CommandLine -like '*generateOpenApiDocs*' -or \$_.CommandLine -like '*-Dspring.profiles.active=openapi-gen*' } |
  ForEach-Object { \$_.ProcessId.ToString() + '|' + \$_.CommandLine }" > "$WIN_TMP" 2>/dev/null
WIN_RC=$?
[ "$WIN_RC" -ne 0 ] && { echo "[!] Windows 側の列挙に失敗 (rc=$WIN_RC) → 確認不能。起動しない"; exit 2; }

# --- WSL 側 ---
# pgrep は「該当なし」で rc=1 を返すので、それを失敗と混同しない（rc>1 だけが失敗）
wsl -e sh -c 'command -v pgrep >/dev/null 2>&1 || exit 127
  out=$(pgrep -f "[-]Dspring.profiles.active=openapi-gen"); rc=$?
  [ "$rc" -gt 1 ] && exit "$rc"
  for p in $out; do echo "$p $(readlink -f /proc/$p/cwd 2>/dev/null)"; done
  exit 0' > "$WSL_TMP" 2>/dev/null
WSL_RC=$?
[ "$WSL_RC" -ne 0 ] && { echo "[!] WSL 側の列挙に失敗 (rc=$WSL_RC) → 確認不能。起動しない"; exit 2; }

HIT=0
while IFS='|' read -r PID CMD; do
  [ -z "$PID" ] && continue
  WT=$(printf '%s' "$CMD" | grep -o 'worktrees[/\][A-Za-z0-9_-]*' | head -1 | sed 's|worktrees[/\]||')
  if [ -z "$WT" ]; then
    JAR=$(printf '%s' "$CMD" | tr ' ' '\n' | grep -o '[A-Za-z]:.*gradle-javaexec-classpath[0-9]*\.jar' | head -1 | tr '\' '/')
    [ -n "$JAR" ] && [ -f "$JAR" ] && WT=$(unzip -p "$JAR" META-INF/MANIFEST.MF 2>/dev/null | sed -e ':a' -e 'N' -e '$!ba' -e 's/\r//g' -e 's/\n //g' | tr ' ' '\n' | grep -o 'worktrees/[A-Za-z0-9_-]*' | head -1 | cut -d/ -f2)
  fi
  case "$CMD" in *generateOpenApiDocs*) KIND="Gradle 実行" ;; *) KIND="フォーク" ;; esac
  if [ -z "$WT" ]; then echo "  PID=$PID ($KIND) worktree=不明 → 確認不能"; HIT=1
  elif [ "$WT" = "$MINE_NAME" ]; then echo "  PID=$PID ($KIND) → ★自分の worktree で生成が進行中"; HIT=1
  else echo "  PID=$PID ($KIND) worktree=$WT → 他 worktree"; fi
done < "$WIN_TMP"

while read -r PID CW; do
  [ -z "$PID" ] && continue
  case "$CW" in *"$MINE_NAME"*) echo "  WSL PID=$PID → ★自分の worktree で生成が進行中"; HIT=1 ;;
    "") echo "  WSL PID=$PID cwd 取得不可 → 確認不能"; HIT=1 ;;
    *) echo "  WSL PID=$PID worktree=$CW → 他 worktree" ;; esac
done < "$WSL_TMP"

rm -f "$WIN_TMP" "$WSL_TMP"
[ "$HIT" -eq 0 ] && echo "  該当なし → 出力先は競合しない。手順2 へ" || echo "  ★または確認不能あり → 起動しない（待つ）"
```

> **なぜ「Gradle 実行そのもの」も探すのか（2026-09-16 実測）。**
> `-Dspring.profiles.active=openapi-gen` を持つ**フォークは生成の最終段階でしか現れない**。
> `compileJava` の最中に探すとフォークは **0 件**で、「競合なし」と誤判定する
> （コールドコンパイルは約 48 分かかることがあり、その間ずっと空を返す）。
> そこで**タスク名 `generateOpenApiDocs` を持つ Gradle の起動プロセス**も併せて探す。
> こちらは `-jar .../worktrees/<名前>/backend/gradle/wrapper/gradle-wrapper.jar` を
> コマンドラインに含むので、**worktree がそのまま読み取れる**うえ、
> **タスク開始から完了まで生き続ける**。
>
> 実測（2026-09-16）:
>
> | 状況 | フォーク数 | 手順1 の答え |
> |---|---|---|
> | `compileJava` 段階 | **0 件** | `PID=87608 (Gradle 実行) → ★進行中` |
> | フォークが一切無い状態（`--dry-run` 中） | **0 件** | `PID=107528 (Gradle 実行) → ★進行中` |
> | 生成の完了後 | 0 件 | `該当なし → 手順2 へ` |
>
> フォークだけを見ていた旧版は、上の 2 行で**「競合なし」と答えてしまう**。
>
> （`./gradlew` 以外の起動方法を使う場合はこの目印が変わる点に注意）

> **なぜポートではなくプロセスを見るのか（2026-09-16 実測）。**
> `-PopenApiPort=8098` で生成を走らせた状態で、両方を試した:
>
> | 見るもの | 結果 |
> |---|---|
> | 手順1（プロセス起点） | `PID=42868 port=8098 → ★自分の worktree の生成が実行中` |
> | `:8082` の両側確認（ポート起点） | `==> 両側とも空き。このポートを使ってよい` |
>
> ポートだけを見ると**二重起動を勧めてしまう**。出力先の競合はポートでは測れない。

- **★が 1 つでもあれば起動しない。既存の生成の完了を待つ。**
- **「確認不能」が出たときも起動しない**（自分の生成でない保証が無い）。
- 何も出なければ、出力先は競合しない。手順2 へ進む。

> `pgrep -f` のパターンを素の `-Dspring...` にすると、**検索している `sh` 自身の
> コマンドラインに一致して常に 1 件ヒットする**（実測で踏んだ）。先頭を `[-]` に
> することで自己一致を防げる。

#### 手順2: ポートが空いているか

手順1 が空振りしたら、次にポートを見る（下の「2.」の両側確認）。
`:8082` が**使用中なら所有者を問わず** `-PopenApiPort` で別ポートへ避ける。

```bash
cd backend
./gradlew generateOpenApiDocs -PopenApiPort=8099 --console=plain
```

`openApiPort` を省略すると既定の `8082` が使われる。指定した値は
`apiDocsUrl` とフォークの `server.port` の両方に効く（`backend/build.gradle.kts` の `openApi { }`）。

**他セッションのプロセスを止めて 8082 を空けるのではなく、自分が別ポートへ避けるのが正しい対処。**

## 所要時間の目安（実測）

| 状況 | 実測 |
|---|---|
| マシンが空いているとき（`-PopenApiPort=8099 --no-daemon --no-build-cache`） | **全体 9 分 13 秒** / アプリ起動 214 秒 |
| 同上（`-PopenApiPort=8099 --console=plain`、デーモンあり・キャッシュあり） | **全体 8 分 15 秒** / アプリ起動 245 秒 |
| 並行セッションで Gradle デーモン 3〜5 個がビジーな高負荷時 | **起動だけで 1741 秒**（その後のスキャン中に窓が閉じ、失敗） |
| 同上（2026-09-16 の再現） | **起動 1083 秒**＋スキャンが間に合わず `waited for 1800 seconds` で失敗（43 分 50 秒） |

同じコードでも負荷次第で 8 倍以上ぶれる。**空いているときに実行すること。**
`./gradlew --status` でビジーなデーモン数を確認してから始めるとよい。

## 起動ログに出る H2 の DDL エラーは無視してよい

`openapi-gen` は H2 インメモリ DB を使うため、MySQL 固有の `columnDefinition`
（`BOOLEAN ... DEFAULT FALSE` / `INT UNSIGNED` / `GENERATED ALWAYS AS` など）を持つ
**7 テーブル分の `create table` が構文エラーで落ち**、続けてそのテーブルに対する
`create index` も「テーブルが見つかりません」で落ちる。

```
GenerationTarget encountered exception accepting command : Error executing DDL "create table organizations (...
```

これは**雑音であり、spec 生成には影響しない**。springdoc は DB ではなく Controller の
シグネチャを読むため、DDL が失敗してもアプリは起動し、全 Controller がスキャンされる。
この状態で `BUILD SUCCESSFUL` になるのが正常。不安になって追いかけなくてよい。

## 失敗したときにまず見るところ

### 1. ログが出ているか（`logback-spring.xml`）

logback は root に appender が一つも紐付いていないと**そのプロセスのログを一切出さない**。
`logback-spring.xml` は `prod` / `local,default` / `test` / `ci` を `<springProfile>` で列挙しているため、
`openapi-gen` はどれにも当てはまらず、**起動失敗しても理由が一切表示されない**状態だった
（CMP-260912-1526。1800 秒タイムアウトするだけで原因が分からず、2 回の試行で 33 分・35 分を空費した）。

現在は列挙済みプロファイル以外すべてにマッチする否定式のフォールバックブロック

```xml
<springProfile name="!prod &amp; !local &amp; !default &amp; !test &amp; !ci">
```

を置いてあるので、**どのプロファイルで起動してもコンソールにログが出る**。
新しいプロファイルを足すときも、このフォールバックがある限り無音にはならない。

### 2. `:8082` を誰かが掴んでいないか — **Windows と WSL の両方を見ること**

**片側だけの確認では必ず取りこぼす。** ポートの主は Windows 側にも WSL 側にも現れうるが、
**どちらの列挙コマンドも自分の側しか見えない**からである。

| 主の居場所 | 典型例 | Windows の `Get-NetTCPConnection` / `netstat` | WSL の `ss -ltnp` |
|---|---|---|---|
| **Windows 側** | Gradle が Windows で起動したフォークの孤児（＝この手順の主対象） | **見える** | 見えない |
| **WSL 側** | 他セッションが WSL で走らせている E2E バックエンド等 | **見えない** | **見える** |

さらに WSL2 の mirrored networking のせいで、**WSL 側の listener は Windows の `localhost` から
普通に応答する**。この組み合わせが観測を二重に裏切る:

- Windows の `netstat` には **LISTENING 行が出ない**（TIME_WAIT や ESTABLISHED だけが並ぶ）
- なのに Windows から `curl http://localhost:8082/...` は **200 を返す**

2026-09-16 に実際にこれを踏んだ（`netstat` も `Get-NetTCPConnection -State Listen` も該当なし、
`curl` は `{"status":"UP"}`、実体は WSL で走る**他セッションの E2E バックエンド**だった。CMP-260912-1526）。

**`curl` は「何かが応答しているか」しか答えない。どちら側の誰かは教えてくれない。**
応答があるのに両方の列挙に出ないということは無いので、必ず両方を叩くこと。

#### まず「両側とも空いているか」だけを見る

主を特定する前に、そのポートが使えるかどうかは**両側をまとめて見ないと決まらない**。
片側の列挙結果だけで「空いている」と判断しないこと。

```bash
PORT=8082
# 各プローブは「出力が空か」より先に「コマンドが成功したか」を見る
WINLIST=$(netstat -ano); WIN_RC=$?
WSLLIST=$(wsl -e sh -c 'command -v ss >/dev/null 2>&1 || exit 127; ss -ltnp' 2>/dev/null); WSL_RC=$?

if [ "$WIN_RC" -ne 0 ]; then WIN_STATE="不明（netstat 失敗 rc=$WIN_RC）"
elif printf '%s
' "$WINLIST" | grep LISTENING | grep -q ":$PORT "; then WIN_STATE="使用中"
else WIN_STATE="空き"; fi

if [ "$WSL_RC" -ne 0 ]; then WSL_STATE="不明（wsl/ss 失敗 rc=$WSL_RC）"
elif printf '%s
' "$WSLLIST" | grep -q ":$PORT "; then WSL_STATE="使用中"
else WSL_STATE="空き"; fi

echo "Windows 側: $WIN_STATE"
echo "WSL 側: $WSL_STATE"
if [ "$WIN_STATE" = "空き" ] && [ "$WSL_STATE" = "空き" ]; then
  echo "==> 両側とも空き。このポートを使ってよい"
else
  echo "==> 使用中または確認不能。主を特定できたら別ポートへ避ける／自分の生成・所有者不明なら待つ"
fi
```

**「使ってよい」と言えるのは両側とも空きのときだけ。** 片側でも使用中なら、
主が他のものだと特定できていれば `-PopenApiPort=<別ポート>` で避けるのが最短。
**主が自分の worktree の生成そのもの、または所有者を特定できない場合は、避けずに待つこと**
（下記「3 つを使い分ける」）。


### 停止してよいのは「自分の worktree の孤児」だけ — 判定は AND

#### 「待つ」「避ける」「停止する」の 3 つを使い分ける

混同しないこと。**避ける**のはポートの話、**停止する**のはプロセスの話、
そして**待つ**のは**出力先の話**である。

`generateOpenApiDocs` が占有する資源は**ポートだけではない**。次の 3 つを占有する。

| 資源 | `-PopenApiPort` で避けられるか |
|---|---|
| `:8082`（待ち受けポート） | **避けられる** |
| `docs/openapi.json`（`outputDir` / `outputFileName` で固定） | **避けられない** |
| `backend/build`（共有ビルドディレクトリ） | **避けられない** |

だから「ポートを避ければ常に安全」ではない。選択肢は 3 つある。

| 選ぶもの | 条件 | 理由 |
|---|---|---|
| **待つ** | 使用中の主が**自分の worktree の生成そのもの**、または**所有者を特定できない**（`[0]` / `[3]`） | 前者は出力先が競合する。後者は**それが自分の生成でない保証が無い** |
| **別ポートへ避ける** | それ以外で**使用中**（所有者を問わない）。**ただし手順1 で自分の worktree の生成が無いと確認できていること** | 出力先が競合しないと分かっているのでポートだけ避ければよい |
| **プロセスを停止する** | AND 判定（条件1・2・3）をすべて満たす**自分の worktree の孤児** | 孤児は誰の役にも立っていないので終了させてよい |

**「避ける」が安全なのは、出力先が競合しないと分かっているときだけ。**
`[0]` / `[3]`（所有者を特定できない）では、その主が**自分の worktree の実行中の生成**である
可能性を排除できない。別ポートで起動すると `docs/openapi.json` と `backend/build` を
競合させ得るので、**特定できるまで待つ**（あるいは別 worktree で作業する）。

所有者が判明していて自分の生成でないなら、他 worktree のフォークでも、他セッションの
アプリでも、Docker の公開ポートでも、root のプロセスでも、等しく別ポートへ避けてよい。

**迷ったら止めるな。** 避けるか待つかで済ませてよく、停止は自分の孤児だと確定したときの
最後の手段である。

##### 境界の確認（当てはまらない例）

条件は、**当てはまらない例**を並べると境界がはっきりする。

**「避ける」に当てはまらない**のは 2 つだけ:

| 例 | どうするか |
|---|---|
| 両側とも空きだと**確認できた**（`:8097` / `:8098` / `:8099` 等） | そのまま**使ってよい** |
| 使用中の主が**自分の worktree の生成そのもの** | **待つ**（避けても出力先が競合する） |

これ以外は——使用中も確認不能も、所有者が誰であっても——**そのポートでは起動しない**。
ただし**確認不能のときは「避ける」ではなく「待つ」**（自分の生成でない保証が無いため）。

**「停止する」に当てはまらない**のは、条件1・2・3 のどれかを欠くとき:

| 例 | 欠ける条件 | どうするか |
|---|---|---|
| 他 worktree の openapi-gen フォーク（`fix-1525` 等） | 条件2（worktree 不一致） | 避ける |
| 他 worktree の Gradle / アプリのプロセス | 条件1・2 | 避ける |
| **自分の worktree の Gradle デーモン** | 条件1（`openapi-gen` ではない） | 避ける |
| **自分が今まさに走らせている生成のフォーク** | 条件3（実行中であって孤児ではない） | **待つ** |
| 所有者不明のポート（`:3306` 等） | 条件2（確認できない） | **待つ** |

注意すべきは下 2 つ。**自分のものでも停止対象とは限らない**し、
**停止できないからといってポートが使えるわけでもない**。
そして**自分の生成が走っているときだけは、避けるのではなく待つ**。

> **大原則: 迷ったら止めるな。`-PopenApiPort=<別ポート>` で避けろ。**
> 避けるか待つかはいつでも安全だが、他セッションの生成を止めるのは取り返しがつかない
> （相手は数十分の実行を失い、しかも原因が分からない）。

**`-Dspring.profiles.active=openapi-gen` だけでは判定にならない。**
並行セッションの生成**も同じプロファイルで起動する**ため、この条件だけでは
自分の孤児と他 worktree で実行中のプロセスが区別できない。
同様に**起動時刻も単独では根拠にならない**（他セッションが先に started しているだけかもしれない）。

停止してよいのは、次を**すべて**満たすときだけ:

| # | 条件 | 確認方法 |
|---|---|---|
| 1 | `-Dspring.profiles.active=openapi-gen` で起動している | Windows: `CommandLine`／WSL: `ps -o args` |
| 2 | **その worktree が自分の作業木と一致する**（必須） | Windows: **pathing jar のマニフェスト**（後述「まず Windows 側」）／WSL: `readlink -f /proc/<PID>/cwd` |
| 3 | 自分は今 `generateOpenApiDocs` を走らせていない | 自分のシェル／`gradlew --status` |

1 つでも満たさない、または**確認できない**なら**止めない**。
主を特定できていれば別ポートで避け、**特定できないなら待つ**。

> **原則: 「結果が空である」と「結果を得られなかった」を同じ結論へ畳まないこと。**
> プローブが失敗したとき（`wsl` が起動しない、`ss` や `netstat` が無い、権限が足りない）、
> 変数は空になり、素朴に書くと「居ない＝空いている／止めてよい」という**危険な側**へ倒れる。
> 判定コマンドは**出力が空かを見る前に終了ステータスを確かめ**、失敗したら「不明」として
> **安全な側（止めない・そのポートでは起動しない）**へ倒すこと。
> 主を特定できないなら別ポートでの起動もせず**待つ**。
> 標準エラーを捨てる場合は、**終了ステータスで成否を判定していること**が条件である
> （本書では WSL の `screen size is bogus` 警告を消す目的でのみ捨て、成否は rc で見ている）。
`CreationDate` / 起動時刻は 3 の裏付けに使う**補助的な手がかり**に留め、単独の根拠にしない。

| 判定 | 意味 | 対処 |
|---|---|---|
| 1・2・3 をすべて満たす | 自分が出した孤児 | 停止してよい |
| 2 が**他の worktree** | 他セッションのプロセス（実行中かもしれない） | **kill 厳禁。別ポートで避ける** |
| 2 が**確認できない** | 不明 | **kill 厳禁。別ポート起動もせず待つ** |
| `:8080` | 開発サーバー | **触らない** |

#### 判定の実行例（2026-09-16 に実測）

```bash
# 自分の作業木
MINE=$(git rev-parse --show-toplevel)

# --- Windows 側の候補を判定する ---
# 条件1 に合うプロセスを列挙し、-cp の pathing jar から worktree を読む（詳細は後述）
powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" |
  Where-Object { \$_.CommandLine -like '*-Dspring.profiles.active=openapi-gen*' } |
  Select-Object -ExpandProperty CommandLine"
# → 出力の -cp にある .jar を <jar> に入れて:
unzip -p "<jar>" META-INF/MANIFEST.MF \
  | sed -e ':a' -e 'N' -e '$!ba' -e 's/\r//g' -e 's/\n //g' \
  | tr ' ' '\n' | grep -o 'file:/C:/Claude/mannschaft/[^ ]*' | head -1
# → このパスが $MINE と同じ worktree を指していなければ、条件2 を満たさない = 止めない

# --- WSL 側の候補を判定する ---
wsl -e sh -c 'P=8082; command -v ss >/dev/null 2>&1 || { echo "[0] ss を実行できない → 確認不能。停止も別ポート起動もせず待つ"; exit 9; }; ALL=$(ss -ltnp) || { echo "[0] ss が失敗した → 確認不能。停止も別ポート起動もせず待つ"; exit 9; }; ROW=$(printf "%s\n" "$ALL" | grep ":$P "); if [ -z "$ROW" ]; then echo "[1] WSL 側には居ない（まだ結論ではない。Windows 側も確認すること）"; exit; fi; echo "$ROW"; PID=$(printf "%s\n" "$ROW" | sed -n "s/.*pid=\([0-9]*\).*/\1/p" | head -1); if [ -z "$PID" ]; then echo "[3] listener は居るが所有者不明（権限不足で pid 非表示）→ 停止も別ポート起動もせず待つ"; exit; fi; CWD=$(readlink -f /proc/$PID/cwd 2>/dev/null); if [ -z "$CWD" ]; then echo "[3] PID=$PID だが cwd を読めない（他ユーザー）→ 停止も別ポート起動もせず待つ"; exit; fi; echo "[2] PID=$PID"; ps -o pid,args= -p "$PID"; echo "cwd: $CWD"'
# → cwd が $MINE 配下でなければ条件2 を満たさない = 止めない
```

**この `wsl` 呼び出し自体が rc≠0 で終わったときも `[0]`（確認不能）と同じ扱いにすること。**
以下の分岐は `ss` の失敗までは自分で見るが、`wsl` が起動しない場合は何も出力されない。

**出力は 4 通りある。`ss` が動いたか / 一致行があるか / PID が取れたか を、
それぞれ別の軸として見ること。**
**「確認不能」は `[0]`（`ss` の実行そのものが失敗）と `[3]`（一致行はあるのに PID / cwd が取れない）の 2 つだけ。**
`[1]`（一致行なし）と `[2]`（PID / cwd 取得可）は**確認できた**状態であり、確認不能ではない。

| 出力 | 意味 | 対処 |
|---|---|---|
| `[0] 確認不能` | **`ss` が無い、または実行に失敗した**（プローブ自体が動かなかった） | **停止しない。別ポートでの起動もしない**（自分の生成でない保証が無い）。空＝不在と読まない |
| `[1] WSL 側には居ない` | `ss` の一致行そのものが無い | **まだ結論ではない。Windows 側も確認する**（下記の両側確認へ） |
| `[2] PID=...` + `cwd:` | 所有者を特定できた | AND 判定（条件2 の worktree 一致）へ進む |
| `[3] 所有者不明` | **一致行はあるが PID / cwd が取れない**（root や別ユーザーのプロセスだと `ss -p` は `pid=` を省略する） | **停止しない。別ポートでの起動もしない**（確認不能時のルール。上の「待つ」へ） |

**`[0]` と `[3]` を `[1]`（listener は無し）へ畳んではならない。** 畳むと使用中のポートを再利用してしまい、
まさにこの手順書が解こうとしている長時間タイムアウトに戻る。
所有者を突き止める必要は基本的に無い（**避ければ済む**）。どうしても必要なら昇格した権限で
`ss -ltnp` を実行することになるが、この環境では `wsl -e sudo -n ss -ltnp` は
`sudo: a password is required` を返すため、**非対話では確認できない**（2026-09-16 実測。
パスワード入力ありで `pid=` が出るかは未検証）。


実測結果（2026-09-16、自分の作業木 = `fix-1526`。その時点で存在した java プロセスへ適用。
最下行の PID 94220 のみ、後から出現したものを同じ手順で判定した）:

| 側 | PID | 条件1 `openapi-gen` | 条件2 worktree | 判定 |
|---|---|---|---|---|
| Windows | 46792 | ✅ | ✅ `fix-1526`（＝自分） | **自分のもの**（実行中のため条件3 で停止不可） |
| Windows | 58432 | ❌（Gradle デーモン） | `fix-1526`（＝自分） | 停止禁止（条件1 を満たさない） |
| Windows | 25020 | ❌ | ❌ `fix-1525` | **停止禁止**（他セッション） |
| Windows | 20172 | ❌ | ❌ `fix-1446` | **停止禁止**（他セッション） |
| WSL | 602973 | ❌ | ❌ `cmp107-affected-count` | **停止禁止**（他セッション） |
| WSL | 607410 | ❌ | ❌ `cmp107-edit-scope-ui` | **停止禁止**（他セッション） |
| Windows | 94220 | ✅ **`openapi-gen`** | ❌ `fix-1525` | **停止禁止**（他セッションが実行中の生成） |

**自分のものと判定されたのは 1 件だけ**で、他 worktree の 5 件と、自分の作業木だが
openapi-gen ではない 1 件は、いずれも正しく停止対象から外れた。

とりわけ **PID 94220 が決定的**である。これは**他セッション（`fix-1525`）が実行中の
`generateOpenApiDocs` のフォーク**で、`-Dspring.profiles.active=openapi-gen` も
`-Dserver.port=8082` も自分のものと**まったく同じ**だった（2026-09-16 に実際に遭遇）。
条件1 と起動時刻だけで判断する旧来のやり方では**これを孤児と誤認して停止し、
他セッションの数十分の実行を破壊していた**。条件2（worktree 一致）だけがこれを止める。
同じ worktree でも条件1 を満たさなければ止めない（＝Gradle デーモンを巻き込まない）点にも注意。

なお 2026-09-16 に `:8082` を掴んでいたのは WSL 側で走る他セッション
（`cmp033-e2e-session`）の E2E バックエンドだった。条件2 を満たさないため停止せず、
`-PopenApiPort=8099` で避けて生成を完走させた。**これが採るべき行動である。**


#### まず Windows 側

```powershell
# 誰が :8082 を LISTEN しているか（Windows 側のみ）
Get-NetTCPConnection -LocalPort 8082 -State Listen | Select-Object LocalAddress,LocalPort,State,OwningProcess
# そのプロセスの正体（分かるのは「openapi-gen フォークかどうか」＝条件1 だけ。worktree は別途確認）
Get-CimInstance Win32_Process -Filter "ProcessId=<PID>" | Select-Object ProcessId,CreationDate,CommandLine
```

**フォークの実際のコマンドライン**は次の形（2026-09-16 に実測）。
プロファイルとポートは `--` 形式の引数ではなく **JVM の `-D` システムプロパティ**として渡される
（`build.gradle.kts` の `customBootRun { jvmArgs.add("-Dspring.profiles.active=openapi-gen") }` 由来）。

```
"C:\Program Files\...\bin\java.exe" -cp C:\Users\<user>\AppData\Local\Temp\gradle-javaexec-classpath<乱数>.jar
  -Dserver.port=8099 -Dspring.profiles.active=openapi-gen -XX:TieredStopAtLevel=4
  -Dfile.encoding=UTF-8 ... com.mannschaft.app.MannschaftApplication
```

したがって**探すべき文字列は `-Dspring.profiles.active=openapi-gen`**（`--spring.profiles.active=...` では**見つからない**）。

```powershell
# openapi-gen のフォークだけを一覧する（成否を確かめてから 0 件を読む）
try {
  $forks = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction Stop |
    Where-Object { $_.CommandLine -like '*-Dspring.profiles.active=openapi-gen*' })
  if ($forks.Count -eq 0) { "[1] openapi-gen のフォークは Windows 側に居ない（確認できた）" }
  else { $forks | Select-Object ProcessId,ParentProcessId,CreationDate,CommandLine }
} catch { "[0] 確認不能: " + $_.Exception.Message }
```

**0 件そのものは正しい答え**（＝Windows 側にフォークは居ない）。危険なのは、
列挙が**失敗しても 0 件に見える**ことのほうである。上のように成否を確かめていれば、
0 件は「確認できた不在」、例外は `[0]` 確認不能として区別できる。
ただし**「確認不能でなければ避けなくてよい」わけではない**。列挙に成功して
所有者を特定できた場合も、そのポートが使用中である以上**そのまま使ってはならない**。
他 worktree のフォークなら避ける。**自分の worktree の生成そのものなら待つ**
（ポートを避けても出力先が競合するため）。
待つ／避ける／停止するの切り分けは上の「「待つ」「避ける」「停止する」の 3 つを使い分ける」に従うこと。

これは**候補を洗い出すための列挙にすぎない**。`CreationDate` は補助的な手がかりであって、
これだけで停止してはならない（並行セッションの生成も同じプロファイルで起動する）。
**停止してよいかは上の「停止してよいのは『自分の worktree の孤児』だけ — 判定は AND」に従うこと。**

##### Windows 側では「どの worktree のものか」がコマンドラインから分からない

`-cp` に入るのは Gradle が生成する**一時 pathing jar**（`gradle-javaexec-classpath<乱数>.jar`）で、
実際のクラスパスはその jar のマニフェストに隠れる。さらに **`Win32_Process` には
作業ディレクトリのプロパティが無い**（`WorkingSet*` はメモリの話で cwd ではない）。
つまり WSL 側のような `readlink /proc/<PID>/cwd` に相当する手段が無い。

worktree を突き止めたいときは pathing jar のマニフェストを読む（2026-09-16 実測で有効）。

```bash
# <jar> は上の CommandLine の -cp に出ている .jar のパス
unzip -p "<jar>" META-INF/MANIFEST.MF \
  | sed -e ':a' -e 'N' -e '$!ba' -e 's/\r//g' -e 's/\n //g' \
  | tr ' ' '\n' | grep -o 'file:/C:/Claude/mannschaft/[^ ]*' | head -2
```

→ `file:/C:/Claude/mannschaft/.claude/worktrees/<worktree名>/backend/build/classes/java/main/` のように出る。

##### 「親プロセスが居ない」で孤児を判定しないこと

フォークの親は **Gradle デーモン**（`org.gradle.launcher.daemon.bootstrap.GradleDaemon`）であり、
デーモンは既定で数時間常駐する。したがって**孤児になってもしばらく親は生きている**し、
逆にデーモンが落ちれば普通のフォークでも親を失う。親の有無は判定材料にならない。
候補の絞り込みには `-Dspring.profiles.active=openapi-gen` の有無を使い、
**停止の可否は上の AND 判定（worktree 一致が必須）で決めること。**

#### 次に WSL 側

```bash
# 誰が :8082 を LISTEN しているか（WSL 側のみ / 素の一覧）
# 注意: これは一覧表示にすぎず、出力が空でも「居ない」とは限らない
#       （wsl や ss の失敗でも空になる）。判定には下の分岐版を使うこと。
wsl -e sh -c "ss -ltnp 2>/dev/null | grep ':8082 '"

# 掴んでいる主の素性と作業ディレクトリ
wsl -e sh -c 'P=8082; command -v ss >/dev/null 2>&1 || { echo "[0] ss を実行できない → 確認不能。停止も別ポート起動もせず待つ"; exit 9; }; ALL=$(ss -ltnp) || { echo "[0] ss が失敗した → 確認不能。停止も別ポート起動もせず待つ"; exit 9; }; ROW=$(printf "%s\n" "$ALL" | grep ":$P "); if [ -z "$ROW" ]; then echo "[1] WSL 側には居ない（まだ結論ではない。Windows 側も確認すること）"; exit; fi; echo "$ROW"; PID=$(printf "%s\n" "$ROW" | sed -n "s/.*pid=\([0-9]*\).*/\1/p" | head -1); if [ -z "$PID" ]; then echo "[3] listener は居るが所有者不明（権限不足で pid 非表示）→ 停止も別ポート起動もせず待つ"; exit; fi; CWD=$(readlink -f /proc/$PID/cwd 2>/dev/null); if [ -z "$CWD" ]; then echo "[3] PID=$PID だが cwd を読めない（他ユーザー）→ 停止も別ポート起動もせず待つ"; exit; fi; echo "[2] PID=$PID"; ps -o pid,args= -p "$PID"; echo "cwd: $CWD"'
```

**この `wsl` 呼び出し自体が rc≠0 で終わったときも `[0]`（確認不能）と同じ扱いにすること。**
以下の分岐は `ss` の失敗までは自分で見るが、`wsl` が起動しない場合は何も出力されない。

**出力は 4 通りある。`ss` が動いたか / 一致行があるか / PID が取れたか を、
それぞれ別の軸として見ること。**
**「確認不能」は `[0]`（`ss` の実行そのものが失敗）と `[3]`（一致行はあるのに PID / cwd が取れない）の 2 つだけ。**
`[1]`（一致行なし）と `[2]`（PID / cwd 取得可）は**確認できた**状態であり、確認不能ではない。

| 出力 | 意味 | 対処 |
|---|---|---|
| `[0] 確認不能` | **`ss` が無い、または実行に失敗した**（プローブ自体が動かなかった） | **停止しない。別ポートでの起動もしない**（自分の生成でない保証が無い）。空＝不在と読まない |
| `[1] WSL 側には居ない` | `ss` の一致行そのものが無い | **まだ結論ではない。Windows 側も確認する**（下記の両側確認へ） |
| `[2] PID=...` + `cwd:` | 所有者を特定できた | AND 判定（条件2 の worktree 一致）へ進む |
| `[3] 所有者不明` | **一致行はあるが PID / cwd が取れない**（root や別ユーザーのプロセスだと `ss -p` は `pid=` を省略する） | **停止しない。別ポートでの起動もしない**（確認不能時のルール。上の「待つ」へ） |

**`[0]` と `[3]` を `[1]`（listener は無し）へ畳んではならない。** 畳むと使用中のポートを再利用してしまい、
まさにこの手順書が解こうとしている長時間タイムアウトに戻る。
所有者を突き止める必要は基本的に無い（**避ければ済む**）。どうしても必要なら昇格した権限で
`ss -ltnp` を実行することになるが、この環境では `wsl -e sudo -n ss -ltnp` は
`sudo: a password is required` を返すため、**非対話では確認できない**（2026-09-16 実測。
パスワード入力ありで `pid=` が出るかは未検証）。


**`readlink` が返す cwd が自分の作業木と一致しない限り、WSL 側のプロセスも停止してはならない。**
Windows 側と同じ AND 判定（下記）を適用すること。片側だけ厳しくしても意味がない。

孤児が残る経路は実在する。前回の実行が異常終了するとフォークだけが生き残り、
TCP は待ち受けるが応答しないため、プラグインの GET がそれに繋がって
`waitTimeInSeconds`（1800 秒）を丸ごと食い潰す（2026-09-13 の孤児が 2 日間 `:8082` を
掴み続けていた。CMP-260912-1526）。

**いずれにせよ、タイムアウトしたら待ち時間を延ばす前に必ずポートの主を確認すること。**
そして**開発サーバー（:8080）と他セッションのプロセスは決して止めないこと。**

生死不明のときはスレッドダンプで判断できる（`main` スレッドが無ければ起動自体は完了している）。

```powershell
& "C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin\jstack.exe" <PID>
```

### 3. 起動そのものが `waitTimeInSeconds` に収まっているか

`waitTimeInSeconds`（1800 秒）は「タスク開始から `/v3/api-docs` の取得完了まで」の窓である。
フォークの**起動**と、その後の**springdoc の初回スキャン**の両方がこの窓に収まらなければならない。

2026-09-15 の実測（並行セッションで Gradle デーモン 5 個がビジーな高負荷時）:

| 区間 | 所要 |
|---|---|
| `compileJava`（コールド） | 約 48 分 |
| フォークの起動完了（`Started MannschaftApplication`） | 1741 秒 |
| その後の `/v3/api-docs` 初回スキャン | 10 分以上（完了前に窓が閉じた） |

対して 2026-09-16、マシンが空いている状態では**起動 214 秒・全体 9 分 13 秒**で完走した。
窓が足りないのではなく、負荷が窓を食い潰していた。

起動を押し上げていた主犯は `BatchEndpointRegistry` だった。これは
`SmartInitializingSingleton` として**全 Bean 定義に `getBean()` を掛ける**ため、
`openapi-gen` プロファイルが指定している `spring.main.lazy-initialization: true` を
事実上打ち消して、コンテキスト全体を生成してしまう（実測で起動 30 分のうち約 22 分）。
現在は `mannschaft.batch.registry.scan-enabled: false` で `openapi-gen` のときだけ走査を止めてある。

**マシンが空いているときに実行すること。** 他セッションが Gradle を回している最中は
CPU 競合で通常の 10〜20 倍に伸びる。`./gradlew --status` でビジーなデーモン数を確認してから始めるとよい。

### 4. タイムアウト値を延ばして誤魔化さない

`waitTimeInSeconds` は既に 1800 秒（30 分）ある。1800 秒待って `:8082` が応答しないなら、
それは待ち時間の問題ではなく「フォークが起動していない」か「別プロセスが掴んでいる」かのどちらかである。
延長で押し切らず、上記 1・2 を確認すること。ポートが埋まっていて主を特定できているなら
`-PopenApiPort` で避ける（主が自分の worktree の生成、または所有者不明なら、避けずに待つ）。

## 生成後

```bash
cd frontend && npm run generate:types   # docs/openapi.json を入力に型を再生成
```

`docs/openapi.json` と `frontend/app/types/generated/index.ts` の両方をコミットする。

### 差分が出ないこともある（それが正常）

生成は毎回フルに走る（`outputs.upToDateWhen { false }`）が、API 表面が変わっていなければ
**出力の中身は変わらない**。差分ゼロは「生成に失敗した」ではなく「ドリフトが無い」という意味なので、
そのまま何もコミットしなくてよい。
**ただしそう言えるのは、下の「確認コマンドはパスに注意」の正しいコマンドで確かめたときだけ**である
（誤ったパスを見た rc=0 は、差分が無いことを何も意味しない）。

#### ⚠️ 確認コマンドはパスに注意（偽の「差分なし」を掴まされる）

**`git diff --quiet` は、指定したパスが存在しなくても rc=0 を返す。**
この手順は冒頭で `cd backend` しているため、そのまま

```bash
git diff --quiet -- docs/openapi.json    # ✗ backend/docs/openapi.json を見てしまう
```

と打つと**実在しないパスを見て必ず rc=0**、つまり本当は差分があっても
「ドリフト無し」と誤判定してコミットを省いてしまう。次のどちらかを使うこと。

```bash
# backend/ に居るまま確認する場合
git diff --quiet -- ../docs/openapi.json; echo "rc=$?"

# あるいはリポジトリルートを明示する
git -C .. diff --quiet -- docs/openapi.json; echo "rc=$?"
```

`rc=0` なら差分なし、`rc=1` なら差分あり（コミットが必要）。
**`0` / `1` 以外（`git` 自体の失敗、リポジトリ外での実行など）は「判定不能」であり、
差分なしと同じ扱いにしてはならない。** 原因を直してから測り直すこと。
**この 3 つは 2026-09-16 に実測で検証済み**: `docs/openapi.json` を意図的に 1 バイト変更した状態で、
`-- docs/openapi.json`（誤）は **rc=0** を返し、`-- ../docs/openapi.json` と `git -C ..`（正）は
いずれも **rc=1** を返した。

#### バイト数での比較はしないこと

`wc -c` やハッシュでの一致判定は**当てにならない**。`docs/openapi.json` は LF で生成されるが、
`core.autocrlf` の効いた環境では作業ツリー上 CRLF になるため、同じ内容でもバイト数が変わる
（実測: 生成直後 5,974,912 bytes → `git checkout` 後 6,213,896 bytes、いずれも `git diff` は差分なし）。
**判定は必ず `git diff` に任せること。**

**2026-09-16 時点で `docs/openapi.json` に既知のドリフトは無い**
（上記の正しいコマンドで rc=0 を確認。CMP-260912-1526）。
