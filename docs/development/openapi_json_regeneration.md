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

### ポートが空いていないとき: `-PopenApiPort`

`:8082` を他が掴んでいる場合は**別ポートを指定して回避する**（詳しい診断は下の「2.」を読むこと）。

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
| 並行セッションで Gradle デーモン 3〜5 個がビジーな高負荷時 | **起動だけで 1741 秒**（その後のスキャン中に窓が閉じ、失敗） |

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

#### まず Windows 側

```powershell
# 誰が :8082 を LISTEN しているか（Windows 側のみ）
Get-NetTCPConnection -LocalPort 8082 -State Listen | Select-Object LocalAddress,LocalPort,State,OwningProcess
# そのプロセスの正体（コマンドラインで openapi-gen フォークか判別できる）
Get-CimInstance Win32_Process -Filter "ProcessId=<PID>" | Select-Object ProcessId,CreationDate,CommandLine
```

コマンドラインが
`... com.mannschaft.app.MannschaftApplication --spring.profiles.active=openapi-gen --server.port=8082`
で、起動時刻が過去の失敗実行のものなら**自分が出した孤児**。停止してから再実行してよい。

#### 次に WSL 側

```bash
# 誰が :8082 を LISTEN しているか（WSL 側のみ）
wsl -e sh -c "ss -ltnp 2>/dev/null | grep ':8082 '"

# 掴んでいる主の素性と作業ディレクトリ
wsl -e sh -c 'PID=$(ss -ltnp 2>/dev/null | grep ":8082 " | sed -n "s/.*pid=\([0-9]*\).*/\1/p" | head -1); echo "PID=$PID"; ps -o pid,args -p $PID; readlink -f /proc/$PID/cwd'
```

**`cwd` / コマンドラインの見方が判断の分かれ目:**

| 主の正体 | 見分け方 | 対処 |
|---|---|---|
| **他セッションの生きたプロセス** | `cwd` が**他の worktree**（`.claude/worktrees/<別の名前>/...`）を指す | **kill 厳禁。`-PopenApiPort=8099` で避ける** |
| **自分が出した孤児** | 自分の worktree で、引数が `--spring.profiles.active=openapi-gen --server.port=8082` | 停止してよい |
| 開発サーバー | `:8080`（そもそも別ポート） | **触らない** |

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
延長で押し切らず、上記 1・2 を確認すること。ポートが埋まっているなら `-PopenApiPort` で避ける。

## 生成後

```bash
cd frontend && npm run generate:types   # docs/openapi.json を入力に型を再生成
```

`docs/openapi.json` と `frontend/app/types/generated/index.ts` の両方をコミットする。

### 差分が出ないこともある（それが正常）

生成は毎回フルに走る（`outputs.upToDateWhen { false }`）が、API 表面が変わっていなければ
**出力の中身は変わらない**。差分ゼロは「生成に失敗した」ではなく「ドリフトが無い」という意味なので、
そのまま何もコミットしなくてよい。

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
