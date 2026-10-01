# 価格改定戦役 run-260930 アリシゼーション証跡（2026-09-30 実施・2026-10-01 再撮影）

## 位置づけ

- 戦役 CMP-260930-1930（F20.1 Billing Center: 価格改定API・税コードマスタ・管理画面）のアリシゼーション（住民4体、2026-09-30 実施）。
- 確定不備は ALIC-1〜3 として台帳 `CMP-260930-1933`・`CMP-260930-1934`・`CMP-260930-1935` へ起票済み（詳細は各行を参照。本ファイルの対象外）。
- 本ファイルは、その探索中に住民が気づいたが**未実証のまま終わった観測3件**（`run-260930` の気づき箱 `docs/prototypes/.b0-local/run-260930-price-revision.json`、ID `PRICE-REV-260930-001〜003`）の記録である。ここに書いた内容は未実証であり、そのままでは **blockers や `docs/task-list.md` の正式課題へ昇格させない**。

## 環境

- FE 3001 / BE 8080（検証用 worktree 規約）。
- 検証時点の origin/main: `e6101b8c39`（テスト: 業務アラートの他テナント混入を契約ITで防止 #3533）。
- 住民4体: 住民3（組織 ADMIN、ペルソナ P10 相当）、住民4（年配 SYSTEM_ADMIN 相当、permission 的に最も近いのは P01。ただしペルソナ定義に「年配」属性の一致は無い）ほか。

## 観測1: 価格改定作成ダイアログが Esc 1回で閉じない（未確定）

- 住民4が `/system-admin/price-revisions` で作成ダイアログを開き、Esc キー1回では閉じないように見えると観測した（`23_price_revision_dialog_open.png`）。
- **2026-10-01 の再撮影では Esc 1回で閉じ、再現しなかった**（`24_price_revision_dialog_after_esc.png`）。環境差・操作のタイミング差の可能性があり、原因は断定しない。
- 気づき箱登録: `PRICE-REV-260930-001`（urgency: when-free）。

## 観測2: 年配 SYSTEM_ADMIN ペルソナが /system-admin トップの「価格改定」タイルに気付かない（未確定）

- 住民4が `/system-admin` トップ（`20_system_admin_top.png`）の管理メニュー最終行左端にある「価格改定」タイルに気付かず、`/system-admin/billing`（`21_system_admin_billing.png`）内の一文リンク経由で遠回りして辿り着いた。
- タイル自体は存在する（2026-10-01 の再撮影で確認済み）。発見しやすさ（視認性）の問題であり、導線の欠落ではない。
- 気づき箱登録: `PRICE-REV-260930-002`（urgency: normal）。

## 観測3: Billing Center とプラン一覧に税抜/税込表記・価格改定告知が無い（未確定）

- 住民3が `/organizations/org-000004/settings/billing` の Billing Center（`12_desktop_billing_settings.png`）とプラン一覧（`13_desktop_billing_plans.png`）を確認したところ、「¥2,000/月」のような金額のみで税抜/税込の別が表記されておらず、価格改定の告知も見当たらなかった。
- 2026-10-01 の再撮影で表記の欠如そのものは確認済み。ただし表記の要否・告知の要否は仕様判断が必要であり、気づきに留める。
- 気づき箱登録: `PRICE-REV-260930-003`（urgency: normal）。

## 証跡 PNG 一覧（`docs/prototypes/evidence/price-rev-run-260930/`）

| ファイル | 内容 |
| --- | --- |
| `10_mobile_billing_settings.png` | モバイル幅 Billing Center 設定画面 |
| `11_mobile_billing_plans.png` | モバイル幅 プラン一覧 |
| `12_desktop_billing_settings.png` | デスクトップ幅 Billing Center 設定画面 |
| `13_desktop_billing_plans.png` | デスクトップ幅 プラン一覧 |
| `20_system_admin_top.png` | /system-admin トップ（管理メニュー） |
| `21_system_admin_billing.png` | /system-admin/billing 画面 |
| `23_price_revision_dialog_open.png` | 価格改定作成ダイアログ（開いた直後） |
| `24_price_revision_dialog_after_esc.png` | 価格改定作成ダイアログ（Esc 1回後、2026-10-01再撮影） |

## 補足: journeyId の選定について

- `docs/prototypes/beta-inventory-board-b0-coverage.json` の B0-J1〜J10 には billing-payment を直接カバーするジャーニーが無い。
- 気づき箱の3件はいずれも暫定的に `B0-J6`（階層ごとの権限範囲）を選定した。管理コンソール・組織設定配下という点で最も近いジャーニーだが、課金専有のジャーニーではない点に注意する。
