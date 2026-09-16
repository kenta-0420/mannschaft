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
2. アプリを**フォークした Spring Boot プロセス**として `openapi-gen` プロファイル・`:8082` で起動
3. `http://localhost:8082/v3/api-docs` を GET
4. 結果を `docs/openapi.json` に書き出してフォークを停止

という流れ。コールド環境では起動 + 初回スキャンで数分〜十数分かかる。

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

### 2. `:8082` を誰かが掴んでいないか

`generateOpenApiDocs` は **固定ポート :8082** を使う。前回の実行が異常終了すると、
フォークされた Spring Boot プロセスだけが孤児として残り、`:8082` を掴んだまま生き続けることがある。
この状態では

- 新しいフォークは bind できずに死ぬ
- プラグインの HTTP GET は**孤児側に繋がってしまう**ため「接続はできるが応答が返らない」状態になり、
  `waitTimeInSeconds`（1800 秒）を丸ごと待ってからタイムアウトする

という、原因が非常に見えにくい失敗になる。実際に 2026-09-13 の失敗実行の孤児が 2 日間 `:8082` を
掴み続けており、それ以降の `generateOpenApiDocs` がすべてこれで潰れていた（CMP-260912-1526）。

**タイムアウトしたら、待ち時間を延ばす前に必ずポートの主を確認すること。**

```powershell
# 誰が :8082 を掴んでいるか
Get-NetTCPConnection -LocalPort 8082 | Select-Object LocalPort,State,OwningProcess
# そのプロセスの正体（コマンドラインで openapi-gen フォークかどうか判別できる）
Get-CimInstance Win32_Process -Filter "ProcessId=<PID>" | Select-Object CreationDate,CommandLine
```

コマンドラインが
`... com.mannschaft.app.MannschaftApplication --spring.profiles.active=openapi-gen --server.port=8082`
で、かつ起動時刻が過去の失敗実行のものであれば孤児。停止してから再実行してよい。
**開発サーバー（:8080）や他セッションのプロセスは決して止めないこと。**

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
延長で押し切らず、上記 1・2 を確認すること。

## 生成後

```bash
cd frontend && npm run generate:types   # docs/openapi.json を入力に型を再生成
```

`docs/openapi.json` と `frontend/app/types/generated/index.ts` の両方をコミットする。
