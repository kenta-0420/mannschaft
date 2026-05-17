# API Drift Check CI 統合（Stage 3 第一陣 1-β / 案 B warning-only）

## 概要

PR 作成時に `backend/scripts/scan_api_drift.py` を自動実行し、`main` ブランチの
baseline と PR 上での再スキャン結果を比較して **PR コメントに差分サマリを投稿** する
GitHub Actions ワークフローを Stage 3 で導入した。

**判定方針**: 警告のみ（warning-only）。drift が増えても PR を fail させない。
あくまで「殿様判断資料」として可視化する用途。

## 関連ファイル

| ファイル | 役割 |
|---|---|
| `.github/workflows/api-drift-check.yml` | ワークフロー本体（pull_request イベントで起動） |
| `backend/scripts/scan_api_drift.py` | 既存スキャナ v4（**この PR では変更しない**） |
| `backend/scripts/ci_drift_summary.py` | 新規補助スクリプト。main / PR の baseline.md を読み込んで差分 Markdown を生成 |
| `docs/internal/api_drift_baseline.md` | main 上の最新 baseline（手動コミット運用） |
| `docs/internal/api_drift_exclusions.yml` | 既知の除外パターン |

## ワークフローの動作

トリガー: `pull_request` イベントで以下のいずれかが変更された PR。

- `backend/src/main/java/**/controller/**/*.java`
- `docs/features/**`
- `backend/scripts/scan_api_drift.py`
- `backend/scripts/ci_drift_summary.py`
- `docs/internal/api_drift_baseline.md`
- `docs/internal/api_drift_exclusions.yml`
- `.github/workflows/api-drift-check.yml`

実行ステップ:

1. PR ブランチをチェックアウト（`fetch-depth: 0`）
2. `origin/<base>:docs/internal/api_drift_baseline.md` を `/tmp/drift/baseline_main.md` に取得
3. PR ブランチで `scan_api_drift.py` を実行 → `docs/internal/api_drift_baseline.md` が上書き生成
4. 生成結果を `/tmp/drift/baseline_pr.md` にコピー
5. `git checkout -- docs/internal/api_drift_baseline.md` で working tree を元に戻す
6. `ci_drift_summary.py --main … --pr … --output /tmp/drift/comment.md` で差分サマリ生成
7. `actions/github-script@v7` で PR コメントを post／update（マーカー `<!-- api-drift-check-marker -->` で既存コメントを判定）
8. `::notice::` で warning-only であることを明示

## なぜ scan_api_drift.py を直接改造しなかったか

- 既存スキャナは出力先固定（`docs/internal/api_drift_baseline.md`）の設計で安定運用中
- 並行進行中の足軽 1-α が同スクリプトの v5 改修を行っているためコンフリクトを避けたい
- 「出力先を選べる別スクリプトを差し込む」よりも、「既存スキャナを 2 回走らせて差分を取る」方が
  運用上の複雑さが少ない（main 側 baseline は手動 commit されているので `git show` で取れる）

## コメント本文の例

```
## 🔍 API Drift Check

このPRで検出された API 乖離の差分サマリ:

| 区分                                  | main baseline | この PR | 差分 |
|---|---:|---:|---:|
| missing_impl（設計あり・実装なし）    |          1223 |    1225 |   +2 |
| missing_design（実装あり・設計なし）  |           925 |     923 |   -2 |
| matched（一致）                       |          1514 |    1516 |   +2 |

### 新規発生した drift（この PR で増えた分）
…

### 解消された drift（この PR で減った分）
…

_このチェックは警告のみで PR をブロックしません。詳細は `docs/internal/api_drift_baseline.md` を参照。_
<!-- api-drift-check-marker -->
```

## 既存 CI との関係

- `backend-ci.yml`, `frontend-ci.yml`, `frontend-e2e-ci.yml` とは **完全に独立並列**
- concurrency group: `api-drift-check-${{ github.event.pull_request.number }}`（他 CI と被らない）
- 依存ジョブなし。失敗しても他 CI に影響しない

## 運用上の注意

- main の baseline 更新は **手動** で `python backend/scripts/scan_api_drift.py` を実行し、
  生成された `docs/internal/api_drift_baseline.md` をコミット → main へマージする
- ベースライン更新 PR を出すと、自分自身のワークフローが「差分ゼロ」または「期待された増減」を
  示すコメントを投稿してくれるので、レビュー時に確認できる
- 将来 fail させたくなったら、`ci_drift_summary.py` に `--max-new` 等の閾値オプションを追加して
  ワークフローの最終ステップで exit code を切り替えるだけで OK（現状は常に exit 0）

## 履歴

- 2026-05-17: Stage 3 第一陣 1-β にて新規導入（案 B warning-only）
