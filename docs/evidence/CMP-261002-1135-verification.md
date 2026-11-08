# CMP-261002-1135 実機・探索検証（audit ゲート）

2026-11-04 実施。対象は CI の依存監査ゲート `frontend/scripts/audit-with-exemption.mjs`（実装 PR [#4259](https://github.com/kenta-0420/mannschaft/pull/4259)）。画面を持たない変更のため、実機は「本物の依存ツリーに対するゲート実行」、探索は「入力を変えたゲートの挙動観察」として行った。

## real（本物の依存ツリーで実行）

- 対象: origin/main（`252846c5d1`）相当の `frontend/`（`package-lock.json` 変更なし）。
- `npm ci --ignore-scripts` は exit 0。
- `node scripts/audit-with-exemption.mjs` は「npm audit: high/critical は0件」で exit 0。`npm audit --audit-level=high` 単体も exit 0。
- 依存版: node-forge 1.4.2（listhen 配下）、braces 3.0.3。
- スクリプト本体に除外表・期限・GHSA 除外ロジックは無く、GHSA の言及は冒頭コメント3行のみ。node-forge の除外も braces の除外も残っていない。
- main の Frontend CI の audit ステップ: [run 41720112492](https://github.com/kenta-0420/mannschaft/actions/runs/41720112492)（Install ジョブ）のログに「npm audit: high/critical は0件」。

## exploratory（入力を変えた挙動観察）

`checkAudit` を一時スクリプト（リポジトリ外のスクラッチ）から合成 JSON で呼んだ。リポジトリは変更していない。除外機構自体が撤去済みのため「期限切れ除外を足す」は適用不能で、代わりに旧除外対象の名前で high を食わせた。

| 入力 | 結果 |
|---|---|
| 勧告0件・exit 0 | 受理 |
| moderate のみ・exit 0 | 受理 |
| node-forge high（旧除外対象）・exit 1 | 拒否「許可されない脆弱性: high node-forge」 |
| braces high（旧除外対象）・exit 1 | 拒否「許可されない脆弱性: high braces」 |
| critical・exit 1 | 拒否 |
| high を含むが npm exit 0 | 拒否（終了状態と集計の不一致） |
| moderate の entry が high の advisory に到達 | 拒否（深刻度不一致） |
| 空文字列 / 壊れた JSON / undefined | 拒否（JSON.parse の例外） |
| `{"error":...}` / auditReportVersion 1 | 拒否（レポート不正） |
| npm exit 2（通信失敗） | 拒否（取得失敗） |

13件すべて期待どおり（全拒否経路が fail-closed）。確定不具合なし。軽微な所見として、空・壊れた入力の拒否メッセージは独自文言ではなく JSON.parse の素の例外文言になる（拒否はされるため合否には影響しない）。
