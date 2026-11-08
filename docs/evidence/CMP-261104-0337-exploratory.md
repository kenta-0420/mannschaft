# 探索（アリシゼーション）CMP-261104-0337 — STOMP の Cookie 認証と空 Bearer の廃止

実施 2026-11-08。run-20261108-emergency-closure。住民4人、--視覚 mobile。

## 判定
- 本戦役（BE PR #4286（Cookie 認証ハンドシェイクと x-error-code）、試験修正 PR #4327、FE PR #4309（空 Bearer 廃止））で直した点が、元に戻っている箇所は無かった。
- 実機E2E は PR #4404（9件すべて通過）。spec は `frontend/tests/e2e/real/emergency-closure-cmp261104.real.spec.ts`。

## 新しく確定した不備
本戦役の射程外。台帳の CMP-261108-1050〜1059 と 1100 に起票済み（PR #4406）。

## 証跡
リポジトリには含めない。
- 実機E2E: C:\Claude\evidence-archive\jikki\run-20261108-emergency-closure\
- アリシゼーション: C:\Claude\evidence-archive\alicization\run-20261108-emergency-closure\
