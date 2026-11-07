# CMP-261017-0854 監査例外期限の再承認

2026-10-17、ユーザーの「1圧縮。２延長可」により、既存node-forge/bracesの2例外を2026-10-23 UTC当日まで7日延長した。2026-10-24T00:00:00Zから拒否する。脆弱性解消や本番非到達の証明ではない。

監査gate全体を変更前と比較し、期限定数と日付説明以外が同一であることを機械的に確認した。URL、版、high、間接依存、全許可packages/nodes/edges、未知high/criticalや不正監査を拒否する条件は不変。

## red / green

新しい期限の試験だけを先行適用した旧gateで、16件中14成功・2失敗（exit 1）。混在2advisoryとnode-forge単独が10月23日末に旧期限切れで失敗した。

期限変更後、`node --test --test-reporter=tap frontend/scripts/audit-with-exemption.test.mjs` は16件成功・失敗0（exit 0）。10月23日末の成功、10月24日0時ちょうど・11月1日の拒否、別GHSA/high/critical/版/範囲/直接依存、未知消費者/node/edge、監査取得失敗・不正JSON等の拒否を維持した。

Node標準テストのみをSystemRoot/TEMP/TMPの最小環境で実行した。npm ci・依存更新・製品ビルドは実行していない。元出力は `.claude/evidence/audit-expiry-20261017/{red,green}.txt` に別々に保全した。

関連するsecurity04 §4.3/§4.4と既存解除台帳2行を同期した。台帳対象行の一意性・7列、`git diff --check` は成功。CI・root検分は別途必要。翻訳機能の正式red/green・実機・探索が完了したという証拠ではない。
