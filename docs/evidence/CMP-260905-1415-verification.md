# CMP-260905-1415 文書検証記録

検証日: 2026-11-07 JST。対象はローカル検証環境の起動手順を整備する tooling 変更である。

- 実装PR: [#4332](https://github.com/kenta-0420/mannschaft/pull/4332)
- 実装head: `5720ca8afdb4eae00a96ef83153a363c5b87ffeb`
- squash merge: `412aad16134a093510c89aa4b2a05c9e251bc222`
- merge時刻: `2026-11-06T15:24:03Z`。GitHub の `state=MERGED` と head 一致を確認済み。
- 変更: `docs/development/local-verification.md` 追加、`harness-environment.md` からの入口リンク。

## 受け入れ条件と検証

| AC | 手順の対応箇所 | 実施した確認・結果 |
|---|---|---|
| AC-1 前提・設定の在処と安全な持込が分かる | 起動手順§1–2 | `application.yml` の既定localプロファイル・暗号/HMAC環境変数、`backend/.gitignore` の未追跡local設定、`EncryptionConfig` と `EncryptionService` のBase64/鍵長条件を照合。一致。秘密値を出さない外部設定持込と既存DB/鍵の対応を記述 |
| AC-2 実機/生成ポートとCORS、ブラウザだけ403を切り分けられる | §1・3・5 | `CLAUDE.md` の8081/3001、`backend/build.gradle.kts` の生成8082とopenApiPort、`application.yml` の許可origin、`frontend/nuxt.config.ts` のpublic/internal API_BASEを照合。一致。直叩きとブラウザCORSを分離 |
| AC-3 Windows/WSL配置・固定source・runner/turnstile・後始末が現行規約と合う | §1・3・5 | `worktree_operations.md`、`verification-run.mjs` の引数・clean HEAD/archive・外部receipt・finally・check、`gradle-turnstile.sh` の実行/ロック寿命を照合。一致。`git ls-tree` でgradlew=100644を確認し `bash ./gradlew` を採用。NVM初期化後のversion確認も追記 |
| AC-4 起動成功をhealth・source/PID所有・FE実通信で判定する | §4 | runnerのreceipt保存時点とsourceHead/scratchを実ソース照合。`backend-loop.sh` の再起動時pullと固定source証明を区別。経過時間だけで合格にしない条件を確認 |
| AC-5 復号/プロファイル/DB/ポート異常を安全に診断・停止できる | §5 | 暗号設定・runnerのprocess groupとPID世代/所有境界の照合結果に基づき、症状別診断・任意鍵/他人PID停止の禁止・retained時の処置を確認。共有BE/既存DBの再起動・変更を要求しない |
| AC-6 既存入口から到達でき、参照先が存在する | 環境診断の入口・新手順 | `harness-environment.md` からのリンクを確認。更新2文書の相対リンクを抽出し、作業木の存在または `git cat-file -e HEAD:<path>` で11件すべて実在確認 |

文書中のbashコードブロック4件を抽出して Git Bash の `bash -n` に渡し、全件終了コード0。`git diff --check` 成功。最終追記はNVM確認の1文だけでコマンドブロック・リンクは変更せず、その差分とdiff checkを再確認した。アプリ・起動スクリプトは変更していないため、新規テストやローカルBEフルbuildは追加しなかった。

## 独立検分

root Codex が最終headの差分全文、runnerのreceipt保存時点、暗号ソース、turnstileと規約を照合し、指摘0で合格と判定した。初回検分のNVM補足は最終headへ反映し、rootが追記差分も確認済み。検分者の追加起動は行っていない。

## 最終headのCI

マージ直前に全checkがsuccessまたは対象外skipで、失敗・実行中がないことを確認した。以下のrunはすべて実装headと一致し、`conclusion=success` を確認済み。

- [Harness CI: 42242307061](https://github.com/kenta-0420/mannschaft/actions/runs/42242307061)
- [Backend CI: 42242307055](https://github.com/kenta-0420/mannschaft/actions/runs/42242307055)
- [Task-List PR Gate: 42242303203](https://github.com/kenta-0420/mannschaft/actions/runs/42242303203)

Backendのsuccessは文書変更に応じたCI判定であり、アプリの全テストを実行した主張ではない。

## 実行範囲と限界

実施したのはソース照合・文書/構文/リンク検証と読取診断。WSL Ubuntu-24.04のJava 21とDocker Serverへ到達できた。WSLの非login/login bashではnode未解決で、既存NVMローダーの存在を確認した。検証ポートのlistenerは確認できず、8081 healthは3秒timeoutだった。本陣local設定は存在だけを確認し、本文・鍵・資格情報は表示・保存していない。

BE/FEのフル起動、ログイン、DB復号、ブラウザ実通信、実機E2E、探索は未実施。既存サービス/DB/コンテナの起動停止・変更もしていない。文書だけのtooling変更なので real/exploratory は対象外であり、アプリ実機の合格を意味しない。ここで完了とするのは手順整備のCMPである。
