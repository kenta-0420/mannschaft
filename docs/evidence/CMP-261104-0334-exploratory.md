# 探索（アリシゼーション）CMP-261104-0334 — 緊急休業の「送信する」連打による多重作成

実施 2026-11-08。run-20261108-emergency-closure。住民4人、--視覚 mobile。

## 判定
- 本戦役（FE PR #4281（連打の再入防止）、BE PR #4290（Idempotency-Key による冪等））で直した点が、元に戻っている箇所は無かった。
- 実機E2E は PR #4404（9件すべて通過）。spec は `frontend/tests/e2e/real/emergency-closure-cmp261104.real.spec.ts`。

## 新しく確定した不備
本戦役の射程外。台帳の CMP-261108-1050〜1059 と 1100 に起票済み（PR #4406）。

## 証跡
リポジトリには含めない。
- 実機E2E: C:\Claude\evidence-archive\jikki\run-20261108-emergency-closure\
- アリシゼーション: C:\Claude\evidence-archive\alicization\run-20261108-emergency-closure\
