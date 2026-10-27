# CMP-261023-0051 監査最小修正・軍議

ユーザー裁可: 2026-10-23 JST、既知循環を保守的highとして検証する修正承認。

## 設計
- 直接via advisoryの過小報告を拒否。間接参照は既存collectAdvisoriesで全末端を検証し、報告severityが低くても到達high/criticalを無視しない。
- 全到達highに既存GHSA/packages/nodes/edges/末端high/range/isDirect/lock/期限を適用。critical entryおよび未知high/criticalを拒否。
- metadata/exitをnpm生レポートのまま照合。例外対象・期限・依存経路は追加・延長しない。
- DOMPurify3.4.17 lock更新（既存range適合）だけを含みNuxt major更新はしない。

## 受け入れ条件
A1 既知builder moderate+mixed advisory実監査縮約を通す（先行red）。
A2 info/low/moderateの既知間接消費者にも全到達high検証。
A3 直接advisory過小報告拒否。
A4 未知低severity消費者拒否。
A5 低severity経由の未知node/edge/cycle拒否。
A6 間接未知high/criticalおよび既知critical化拒否。
A7 低severity経由のrange/isDirect/lock不一致拒否。
A8 期限直前許可・一致/直後/非有限拒否（両GHSA/混在）。
A9 0/閾値未満成功、null/不正JSON/参照欠落/空via/集計/exit/取得失敗拒否。
A10 既知循環/別分岐を全探索、末端なしhigh循環拒否。
A11 実audit DOMPurify消失critical0、高末端は既存2件のみ。
A12 既存18試験（拒否理由1件のみ調整）+追加green、既存visited循環停止、外部通信追加0、新依存0。
A13 新DOMPurify実配布物7assertと既存Markdown22mockunit、独立npmciの結果を区別し保全。新depsで実機回帰再実行。

5類型: 空null A9 / 境界 A8 / 失敗 A7,A9 / 許可境界 A4-A7（HTTPテナント認可変更なし）/性能 A10,A12。

## 陣立て・Phase
Phase1 worker Solmedium（Terra利用不可代替）: script/test/既存依存規約節/DOMlockの4ファイル中心。red→green→実audit→対象lint→commit保全。
root Solhigh: 本軍議検分・最終検分とAC対応/セキュリティ評価（追加Codex起動なし）。
Phase2: 独立runtime再起動、新depsで実機回帰、既存3persona観測とのUI差分再確認、最終CI/merge/証拠PR/今回資産cleanup。定型CIはLunalow専任。

## root軍議検分
実施済み。npm公式vuln.jsの自身advisory maxseverityとJSON依存名集約を確認。直接過小報告拒否と未知低報告消費者拒否を分け、全到達highを許可集合で検証する構成は意図した保守的判定。A13追加で依存実物とモック結果を区別。破壊的操作/外部権限/DB変更なし。追加finding0、着手可。既存ユーザー裁可に基づき再承認待ちは不要。
