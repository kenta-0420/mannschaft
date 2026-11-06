# ローカル実機検証用 BE・FE の起動

検証するコミットを固定し、専用ポートで BE と FE を起動する手順。環境診断は [harness-environment.md](harness-environment.md)、コピーの所有・終了処理の正本は [worktree 運用](worktree_operations.md#wsl検証コピーの寿命管理)を参照する。本陣の常駐環境を起動する `/陣立て` と、ここで作る検証環境は用途が異なる。

## 1. 実行場所と接続先を決める

- 編集・commit は専用 Git worktree で行い、検証前に必要な変更を commit する。`git status --porcelain=v1 --untracked-files=all` が空で、`git rev-parse HEAD` が検証対象 SHA と一致することを確認する。
- Windows で直接 `gradlew bootRun` を実行しない。WSL/Linux の Node.js 22 以降、Java 21、Git、GNU tar と必要なミドルウェア接続を用意し、WSL 内から `node scripts/harness-doctor.mjs` を実行する。FE の Node/npm 条件は `frontend/package.json` の `engines` に従う。
- NVM を使う環境で `node` が見つからない場合は、そのシェルで既存の NVM を初期化してインストール済みの適切な Node 22 以降を選び、`node --version`・`npm --version` が上記条件を満たすことを確認する。
- `/mnt/c` 上の Gradle はファイル I/O や注釈処理が極端に遅くなる場合がある。下記 runner が固定 HEAD を WSL ネイティブの `/var/tmp` に展開するので、検証用の別 clone や build/cache 全量コピーを増やさない。
- DB・Valkey の接続先、所有者、利用範囲を起動前に確定する。BE は起動時に migration 等を実行しうる。既存利用者 DB を検証用と見なさず、接続が許可された DB だけを使う。共有 BE、コンテナ、volume はこの手順のために再起動・再作成しない。

| 用途 | BE | FE | 注意 |
|---|---|---|---|
| 本陣の常駐環境 | 8080 | 3000 | 本手順から停止・上書きしない |
| 検証環境 | 8081 | 3001 | 起動前に Windows・WSL 両方の listener と担当者を確認 |
| OpenAPI 生成 | 8082 | — | `backend/build.gradle.kts` の `generateOpenApiDocs` 専用 |

8081/3001 が使用中なら所有者に確認するか、担当間で別の専有ポートを決めて BE・FE・CORS・証跡を一緒に変更する。8082 を実機用に流用しない。生成タスクには `-PopenApiPort=<専用ポート>` の上書きもあるが、生成用 listener を実機 BE と共有しない。

## 2. ローカル設定を値を出さずに持ち込む

`backend/src/main/resources/application-local.yml` は [backend/.gitignore](../../backend/.gitignore) の対象で、Git worktree や runner の archive へ複製されない。`application.yml` の既定プロファイルは `local` だが、プロファイル名だけで未追跡設定が読み込まれるわけではない。

既存 DB の利用を許可された場合は、その DB に対応する既存設定を、承認済みのローカル適用手順で安全な外部ディレクトリへ複製する。本陣の元ファイルは変更しない。以下は **WSL 内で実行する例**で、本文を画面・履歴へ出さず、既存の複製先は上書きしない。コピー元の存在と利用許可を確認してから実行する。

```bash
umask 077
local_config_dir="$HOME/.config/mannschaft-verification/CMP-YYMMDD-HHMM"
mkdir -p "$local_config_dir"
chmod 700 "$local_config_dir"
test ! -e "$local_config_dir/application-local.yml" || exit 1
install -m 600 /mnt/c/Claude/mannschaft/backend/src/main/resources/application-local.yml \
  "$local_config_dir/application-local.yml"
export SPRING_PROFILES_ACTIVE=local
export SPRING_CONFIG_ADDITIONAL_LOCATION="file:$local_config_dir/"
```

ディレクトリ指定末尾の `/` を付け、必須設定を隠す `optional:` は付けない。実際の接続先・鍵の一致は設定管理者とローカルで照合し、報告には「確認済み／不一致／未確認」だけを残す。設定ファイルを shell の `source` で読み込まない。環境変数で既存設定を上書きしていないかも、名前と設定有無だけで確認する。

[EncryptionConfig](../../backend/src/main/java/com/mannschaft/app/config/EncryptionConfig.java) が使う設定は次の2つで、鍵の導出フォールバックはない。

| プロパティ | 環境変数 | 条件 |
|---|---|---|
| `mannschaft.encryption.key` | `MANNSCHAFT_ENCRYPTION_KEY` | Base64、復号後32バイト |
| `mannschaft.encryption.hmac-key` | `MANNSCHAFT_HMAC_KEY` | Base64、復号後32バイト以上 |

既存 DB に任意の新しい鍵を与えない。暗号鍵が違うと復号時に `AEADBadTagException: Tag mismatch` 等になり、HMAC 鍵が違うと検索用インデックスとの整合も崩れる。新規 DB 用の鍵を使う場合は DB・鍵をセットで分離する。DB URL、ユーザー、パスワード等も local 設定の持込対象であり、設定欠落で別 DB／H2 が起動しても目的の環境ができたとは判断しない。

鍵や資格情報をコマンド引数、commit、PR、スクリーンショット、全環境変数出力へ載せない。`set -x`、設定本文の `cat`、`/actuator/env` の保存を避ける。runner は環境変数を継承するが保存しない。子コマンドの出力はそのまま端末へ出るので、子にも秘密値を出させない。

## 3. 固定 HEAD を runner から起動する

以下は WSL の作業用 Git root で実行する。証跡の親ディレクトリは事前作成し、`--evidence-dir` 自体は毎回新規とする。source 内と runner 管理親内は指定できない。

```bash
mkdir -p "$HOME/verification-evidence"
export MANNSCHAFT_ALLOWED_ORIGINS=http://localhost:3001,http://localhost:8081
node scripts/verification-run.mjs \
  --source "$PWD" \
  --evidence-dir "$HOME/verification-evidence/CMP-YYMMDD-HHMM-be-run1" \
  -- bash -c 'cd backend && exec bash ./scripts/gradle-turnstile.sh bash ./gradlew bootRun --no-daemon --args="--server.port=8081"'
```

runner は Git root の clean HEAD を固定し、tracked ファイルだけを展開する。外部 local 設定は `SPRING_CONFIG_ADDITIONAL_LOCATION` から読む。scratch には `.git` がないため、実行中の `git rev-parse` ではなく外部 `receipt.json` の `source`・`sourceHead`・`scratch` を起動根拠にする。

Gradle の heavy build は [turnstile](worktree_operations.md#並列ビルドの交通整理) 経由とし、`--no-daemon` を付ける。archive の `gradlew` は実行 bit がないため上例では `bash ./gradlew` で呼ぶ。bootRun の先行コンパイルも対象になる。同じ検証のための重複 build や別 worktree での並列 heavy build を起こさず、ロック待ちとコンパイル中を区別する。bootRun の寿命中はこの turnstile を保持するので、他の heavy build を進める必要がある場合は担当間で実機検証の時間を調整する。

FE は別端末で、同じ検証対象 SHA を source に指定して同じ runner を使う。scratch は毎回新規なので依存関係の導入もそこで行う。設定は BE 用端末から自動で移らない。

```bash
export NUXT_PUBLIC_API_BASE=http://localhost:8081
export NUXT_INTERNAL_API_BASE=http://localhost:8081
node scripts/verification-run.mjs \
  --source "$PWD" \
  --evidence-dir "$HOME/verification-evidence/CMP-YYMMDD-HHMM-fe-run1" \
  -- bash -c 'cd frontend && npm ci && exec npm run dev -- --port 3001'
```

`NUXT_PUBLIC_API_BASE` はブラウザ用、`NUXT_INTERNAL_API_BASE` は SSR 用。`frontend/nuxt.config.ts` の既定はともに8080なので検証先へ明示する。BE の `MANNSCHAFT_ALLOWED_ORIGINS` には実際の FE origin を含める。`localhost` と `127.0.0.1` は別 origin なのでブラウザで使うホスト名も揃える。API の直叩きが成功してもブラウザの CORS 成功は証明しない。

## 4. 起動待ちと実通信の合格条件

BE は多数のクラス・Bean を処理するため起動時間は環境に依存する。過去の「20〜30分」「compileJava が60〜70分」という観測値を待ち時間や合格条件にしない。ログの段階・プロセスの生存・health を確認し、失敗や進捗停止があれば原因を調べる。同じ起動を繰り返して負荷を増やさない。

1. BE の起動ログで `local` プロファイルと意図した接続先をローカル確認する。DB・鍵の確認結果だけを証跡に記録する。資格情報を含む行を貼らない。
2. `curl --fail --silent --show-error http://localhost:8081/actuator/health` が成功し `status=UP` であることを確認する。接続失敗や非0終了を成功扱いにしない。
3. Windows の `Get-NetTCPConnection` と WSL の `ss -ltnp` 等で listener の PID を確認する。WSL の `/proc/<PID>/cwd`、開始時刻、親子関係から今回の runner の scratch に属する Java/Nuxt であることを照合する。PID 再利用や既存 listener を排除し、receipt の SHA と併せて記録する。引数・環境の全量表示はしない。
4. ブラウザで `http://localhost:3001` を開き、Network で8081への API 実通信と期待した応答を確認する。SSR とブラウザ双方が同じ BE を使うこと、認証を伴う対象操作も成功することを確かめる。画面表示や health だけでログイン／復号の成功と見なさない。

本陣の `start-backend.ps1` → WSL `backend-loop.sh` は常駐開発用で、再起動時に `git pull --ff-only` して main に追従する。作業ツリーの HEAD や8080の health だけでは、今回の固定 source/PID の証明にならない。本手順でそのループや `.stop-backend` を操作しない。

## 5. 失敗時の切り分けと終了

| 症状 | 確認・処置 |
|---|---|
| 鍵未設定、Base64/鍵長エラー | local 設定の存在・外部パス・profile、環境変数による上書きを値非表示で確認。任意鍵で回避しない |
| ログイン500、`Tag mismatch` | 接続 DB と対応する既存暗号鍵を設定管理者と照合。不一致の環境を止め、既存データの再暗号化や鍵交換をしない |
| DB 接続失敗、H2／意図しない DB | `SPRING_PROFILES_ACTIVE`、外部設定の読込、接続先の同一性を確認。migration/seed投入・DB作成で症状を隠さない |
| 直叩きは通るがブラウザ403、`Invalid CORS request` | Network の Origin と BE の許可 origin を照合。CORS と業務上の403を区別し、認可を緩めない。FE の API_BASE も確認 |
| bind 失敗、health は通るが source 不一致 | 既存 listener の所有者とポートを確認。今回の起動を終了し、専有ポートを調整。他人の PID を止めない |
| Gradle 待機／起動中断 | turnstile の保持者と進捗を確認。WSL ネイティブ配置・Java 21を確認。共有 cache の削除や無差別な daemon 停止へ進まない |

起動端末で Ctrl+C を送り runner の終了を待つ。runner は自分が開始した process group に信号を送り、停止・参照・所有を確認して scratch を撤去する。背景 daemon 化、`nohup`、共有 daemon への scratch 引渡しはしない。FE/BE 両方を終了後、次を確認する。

```bash
node scripts/verification-run.mjs --check
```

各 receipt の `cleanup=removed` と `--check` の要処置なし、今回の listener の消滅を確認する。コマンド失敗や中断の exit/signal は cleanup 成功と別に記録する。`retained` や要処置は完了扱いにせず、正確なパス・所有 marker・PID 世代・保持理由を確認する。`--check` は検知のみで削除しない。全 WSL 停止、無差別な `kill` / `rm` / prune は使わない。

必要な最小証跡（SHA、所有 PID の確認結果、health、FE 実通信、終了結果）を正本へ残す。秘密を含まないことを確認してから [完了証拠](completion-evidence.md)に対応付ける。外部 local 設定・専用 cache 等も自身が作成した範囲と用途を確認して片付け、共有資産と元の `application-local.yml` は保持する。中断保持の記録項目は worktree 運用の正本に従う。
