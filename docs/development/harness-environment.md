# 開発ハーネスの環境診断とcoverage

この文書は [開発ハーネスの入口](harness-engineering.md) から呼び出す CLI の使い方を説明する。

## セットアップ順序

1. [CLAUDE.md の作業場所規則](../../CLAUDE.md#作業開始前の必須確認)に従い、作業用worktreeから始める。
2. `frontend/package.json` の `engines` に合わせて Node.js と npm を用意する。バックエンド用に Java 21、Git、GitHub CLI (`gh`)、Docker CLI も用意する。
3. `node scripts/harness-doctor.mjs` を実行する。JSONが必要なら `--json` を付ける。これは診断だけを行い、外部接続、認証確認、DB操作、サービス操作、seed投入を行わない。
4. Gitのpre-commit hookを導入するときだけ、本陣などGit共通ディレクトリへの書込権限がある環境で `node scripts/harness-doctor.mjs --install-hooks` を明示実行する。linked worktreeのGit共通ディレクトリへ書き込めない場合は診断で停止する。既存の独自hookは上書きせず、`core.hooksPath`も変更しない。
5. DB・seedが必要な開発は、既存の [`CLAUDE.md` の開発環境コマンド](../../CLAUDE.md#よく使うコマンド)と `/陣立て` の手順に従う。この診断CLIからDBやseedを起動・変更しない。
6. 画面台帳の宣言と実ファイルを確認するときは `node scripts/guard-coverage.mjs` を実行する。機械可読な全一覧は `--json` を付ける。

## doctorの判定

doctorはNode.js、npm、Java、Git、`gh`、Docker CLIの存在と、リポジトリで必要な設定ファイルを確認する。Node/npmは `frontend/package.json` の期待versionと照合し、Javaはmajor version 21を確認する。Node/npm/Java の不一致、コマンド欠損、timeoutは別のstatusで出す。version不一致では実versionと期待versionを表示する。

診断対象はコマンドを実行したシェルの環境である。Windows側の診断でDocker CLIが見つからない場合、WSL2側のDocker環境まで停止しているとは限らない。WSL2で開発する場合はWSL2内から同じCLIを実行して、そちらのNode/npm、Java、Git、Dockerを別に確認する。

Git hookは実効 `core.hooksPath` とそのoriginを読み、`.githooks/pre-commit` と実際に呼ばれる `pre-commit` の内容を比較する。Unix系では実行bitも確認する。インストールは既存Git設定のhooksディレクトリへファイルを追加するだけで、hooksPathを切り替えない。既存の異なるpre-commitがあれば競合として停止し、上書きしない。正本と一致するが実行bitが無い場合だけ、明示的な `--install-hooks` で実行bitを付与する。他のhookファイルがあるディレクトリはそのまま保つ。

Claude Codeのlocal hookは `.claude/settings.local.json` において、BashとPowerShellそれぞれのmatcherから `.claude/hooks/block-honjin-git.ps1` を実際に呼ぶ登録かを診断する。doctorはClaude設定を作成・変更しない。登録状態の判定は設定ファイルの静的確認であり、Claude Code上でhookが実際に起動することまでは証明しない。登録方法と手動検証は[本陣保護フックのセットアップ手引き](honjin_protection_setup.md)を参照する。

環境変数は通常の項目も秘密情報らしい名前の項目も、名前と設定有無だけを表示する。値、コマンドのstdout/stderr、設定ファイル本文、資格情報は診断結果へ含めない。外部ネットワークへ接続せず、認証情報を読まず、DB/serviceの起動停止・seed投入もしない。

## coverageの判定

coverageは `docs/inventory/page-reachability.yaml` の `tracked_paths` と `pages` を限定した既存形式で読み、`frontend/app/pages` 以下の全 `.vue` ファイルをrouteへ変換する。`index.vue` は親routeに対応させる（例: `alpha/index.vue` → `/alpha`、`index.vue` → `/`）。未知のschemaや重複宣言、宣言漏れ、存在しないrouteは終了コード1になる。未検査routeは情報として表示するだけで、対象拡大が済んでいないこと自体では失敗にしない。通常表示では未検査routeの大量一覧を省き、`--json` で詳細を出す。

結果はVueファイル数、unique route数、追跡数、宣言数を別々に示す。同じrouteへ変換される複数のVueファイルは `routeCollisions` として表示し、台帳schema違反とは分ける。これらの数を割ってcoverage率と見なしてはならない。routeの重複が妥当かどうかは既存番人と実装を確認する。

coverageは宣言とファイルの対応だけを見る。静的リンク検査、画面からの到達性、バックエンドの存在、認可、機能の正しさを保証しない。既存の番人がgreenである場合も、その番人が凍結対象やどの検査範囲を実行したかを踏まえて解釈する。台帳対象26件がgreenでも、未検査Vueを含む全521ファイルの到達性を証明したことにはならない。
