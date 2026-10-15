
## 恐竜の部屋 Phase 1

本人専用 `/my/ranch`、診断 `/my/ranch/results`、出生本人確認、24問診断、永久装飾と3枠の導線を追加。牧場未参加・非表示でも設定から本人結果へ到達する。素材manifest未登録時は文字fallback、公開gateは解除しない。新ranch.jsonは統合隊がnuxt.configの六localeへ登録する。合成UTは実機や本番素材の完成証拠ではない。


### 正式 v3 検証の現在地

恐竜牧場の担当 4 spec は 808 件通過、担当差分 32 TS/Vue files の ESLint は通過。
全体 `nuxt typecheck` は 4096 MiB の heap 上限で OOM / exit 1 となり、型検査は未通過。
固定 Unicode 17 境界は `unicode-segmenter=0.17.3` と共有公式 fixture で検証し、native Intl fallback を使わない。
検証 head/UTC/exit と実機・素材・生成 DTO・Settings/locale 登録の残件は `docs/ranch-phase1-worker-report.md` を参照。
本番 64 mapping/assets と診断 master の承認・実 API の検分が揃うまで有効化しない。
