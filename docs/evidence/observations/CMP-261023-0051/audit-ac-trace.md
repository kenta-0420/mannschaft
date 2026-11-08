# 監査修正 Phase1 AC対応

追跡対象: frontend/scripts/audit-with-exemption.mjs、同.test.mjs、frontend/package-lock.json、docs/security/04_dependency_and_supply_chain.md。未commit・未push。

|AC|試験・証拠|確認範囲|
|---|---|---|
|A1/A2|A1/A2: 低severityの間接参照も混在循環の全highを既存例外で検証する|info/low/moderateの3条件、known highとmoderate末端混在|
|A3|A3: 許可packageでも直接advisoryの深刻度を過小報告すると拒否する|直接advisory過小報告を拒否|
|A4|high advisoryへの未知消費者をmoderateと報告しても拒否する|既存拒否を維持、期待理由だけ未知消費者へ変更|
|A5|A5: 低severityの既知消費者も未知node・依存辺・循環辺を拒否する|3 mutation|
|A6|A4/A6: 低severityの間接参照でも未知highとcriticalを拒否する / 同じGHSAでもcritical・別package・別range・直接依存・別versionは拒否する|前者は新到達critical、後者は既知GHSA critical化を担保|
|A7|A7: 低severityの間接参照でも末端range・直接化・lock版を検証する|3 mutation|
|A8|A8: 低severityの間接参照でも期限直前のみ通し一致・直後・非有限時刻を拒否する|直前/一致/直後/NaN/±Infinity|
|A9|指摘0件と閾値未満の指摘は例外を使わず通る / 不正JSON・構造欠落・error・npm異常終了は拒否する / 参照欠落・末端のない循環・空via・unknown severityを拒否する / レポート集計と依存情報の不一致を拒否する|既存試験を変更せず維持|
|A10|A4/A6: 低severityの間接参照でも未知highとcriticalを拒否する / 実監査の2advisory混在19件は循環とforge専用辺を含めて通る|新fixtureはbuilder先頭、known+unknown末端が同じ循環の別分岐に存在。先行high entryの拒否に代替させない。|
|A11|audit-live.log / 先行DOM修正proof|実npm audit exit0、critical0/high16/moderate3、DOMPurify指摘なし。既存2GHSAのみ例外。|
|A12|audit-green.log / 静的差分確認|24件pass、外部I/O追加0、collectAdvisories/visitedは不変更。EXEMPTIONS prefix同一、metadata/exit照合不変更。|
|A13|docs/evidence/observations/CMP-261023-0051/dompurify-summary.json|実DOM7pass/mock22pass/独立npmci exit0は個別記録。新deps/b1cで1pass/exit0/skip0を観測。docs/evidence/observations/CMP-261023-0051/audit-recheck-real-run.log参照。|

redは先行試験追加後exit1（20pass/4fail）。CRLF差異と補助変数未定義で最初の2回の実装反映が成立せず、未変更のred試験を余分に再実行した。最終実装反映後・対象Prettier後のgreenは24pass/0fail exit0であり、未反映の実行をgreenに数えていない。

ESLint対象mjs2本exit0、Prettier対象mjs2本+lock exit0、git diff --check exit0。security MarkdownのHEAD原本自体が既存Prettier不一致のため無関係な全節整形は実施しない。

最終検分・commit保全はroot待ち。EXEMPTIONS/期限/許可paths・edgesは不変更。
