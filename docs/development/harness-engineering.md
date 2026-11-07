# 開発ハーネス

ハーネスの詳細な仕組みと運用は各ツールの正本を参照する。

- 開発環境の診断: `node scripts/harness-doctor.mjs`。Git hookの導入は明示して `node scripts/harness-doctor.mjs --install-hooks` を実行する。
- 環境の準備手順: [`harness-environment.md`](harness-environment.md)。
- フロントエンド画面の検査範囲: `node scripts/guard-coverage.mjs`。
- 作業結果の再現性計測: [`harness-benchmark.md`](harness-benchmark.md) と `node scripts/harness-benchmark.mjs <記録.json>`。
- 凱旋時の完了証拠: [`completion-evidence.md`](completion-evidence.md)。

この文書は入口のみを示す。各スクリプトの引数・判定・schemaを複製しない。
