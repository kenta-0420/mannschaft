# CMP-261021-1347: Frontend E2E runner の固定

## 原因と判断

2026-10-22 の実 CI ログ `outputs/clip-frontend-317dd3-failure-20261022.log` では、Playwright 1.60.0 が runner を `ubuntu26.04-x64` と認識し、`npx playwright install --with-deps chromium` 中に `Playwright does not support chromium on ubuntu26.04-x64` で停止した。smoke E2E 本体は開始していないため、これは製品不具合や E2E テスト失敗ではなく、runner セットアップ失敗である。

GitHub 公式告知 [Ubuntu 26 runner image generally available](https://github.blog/changelog/2026-09-17-ubuntu-26-generally-available-and-latest-migration/) は `ubuntu-latest` の移行期間を 2026-10-19〜11-19 とし、未移行の利用者に `ubuntu-24.04` 固定を案内している。これに基づき、`.github/workflows/frontend-e2e-ci.yml` の `jobs.e2e.runs-on` だけを `ubuntu-24.04` に固定し、日本語理由コメントを加えた。依存、E2E の spec・timeout・実行条件、他 workflow は変更していない。

## 確認

- 作業木 HEAD: `317dd3e224c35c04efd07e9b78f28f6a349d39ea`
- 既存の `frontend/node_modules/yaml` で変更前後の workflow を parse。
- YAML 構造を比較し、`jobs.e2e.runs-on` 以外が同一であることを確認。
- テキスト比較で、差分が runner 行と指定の短い日本語コメントだけであることを確認。
- `git diff --check` 成功。
- GitHub CI の再実行はしていない。
- commit なし。
