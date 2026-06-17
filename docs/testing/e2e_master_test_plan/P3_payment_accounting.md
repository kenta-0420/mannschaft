# P3 決済・会計・アクセス制御・ポイント E2E テスト法案

> 対象: F08.2 / F08.3 / F08.5 / F08.6 / F08.9 / F18 / F09.13
> 凡例・テスト層は [README](./README.md) 参照。**このドメインは「BE は厚いが FE の UI/導線が欠ける」が顕著。**

---

## 1. トレーサビリティ監査サマリ

### F08.5 回数券（Stripe + 現地決済ハイブリッド）（`docs/features/F08.5_ticket_book.md`）
| 機能要素 | BE | FE/導線 | 判定 |
|---|---|---|---|
| Stripe Checkout 購入 | TicketCheckoutController | teams/[slug]/tickets.vue | 🟢 |
| 顧客チケット一覧 | MyTicketController | tickets.vue「マイチケット」 | 🟢 |
| 手動発行(現地決済) | TicketBookController.issue() | スタッフ画面要確認 | 🟡 |
| **QR スキャン消化** | consumeByQr() | **スタッフ向け QR 読取画面が無い** | ⚫到達不能 |
| **複数同時消化** | bulkConsume() | UI 無し | ⚫到達不能 |
| **有効期限延長** | extend() 要確認 | UI 無し | 🔴 |
| チケット返金 | refund() | Admin 導線要確認 | 🟡 |
| 領収書ダウンロード | MyTicketController.getReceipt()(PDF) | UI 要確認 | 🟡 |
| 回数券統計 | API あり | Admin UI 無し | 🔴 |

### F08.6 予算・会計管理（`docs/features/F08.6_budget_accounting.md`）
| 機能要素 | BE | FE/導線 | 判定 |
|---|---|---|---|
| 年度/費目/予算配分 CRUD | BudgetFiscalYear/Category/Allocation Controller | Admin 画面要確認 | 🟡 |
| トランザクション記録 | BudgetTransactionController | 記録フォーム要確認 | 🟡 |
| **取消仕訳(逆仕訳)** | reverse() 実装済 | **UI 無し** | ⚫/🔴 |
| **CSV インポート(プレビュー+確定)** | BudgetCsvController | **upload UI 無し** | 🔴 |
| 承認WF連携(閾値超過→F05.6) | BudgetWorkflowListener | F05.6 依存 | 🟡 要実機検証 |
| 会費自動計上(F08.2) | BudgetPaymentListener | 自動 | 🟡 PaymentCompletedEvent 発火要確認 |
| 消化率集計/CSV出力/報告書PDF(@Async) | Summary/Csv/Report Controller | UI 要確認 | 🟡 |
| **年度再開(REOPEN)** | 要確認 | UI 無し | 🔴 |

### F09.13 通知プリペイドクレジット / F18 ポイントカード
- F09.13: 残高/購入/パッケージ/Checkout/消費/月次リセット/失効バッチ = BE 実装、**管理UI 多数要確認**、Webhook→購入完了の経路 🟡 要実機検証
- F18: **Phase1 設計のみ、本実装 Phase2 凍結中（2026-05-17 マスター指示）**。提示モードUI・Fuzzy Match 未確認 🔴/🟡

---

## 2. E2E 実機シナリオ（代表・トレーサ付き）
- **[E2E-F08.5-01]** チケット購入→消化→誤消化取消(72h以内)→全額返金。Stripe Webhook で `ticket_payments.status=PAID`・`ticket_books.status=ACTIVE`・監査ログ。（トレース: §3.1/§4.6/§4.7）
- **[E2E-F08.5-02]** 現地決済(手動発行)→QR取得(Valkey TTL5分)→QRスキャン消化→複数同時消化(6件以上で400)。**※QRスキャン UI が無いため API 直叩きでの検証になる。**（§3.1/§4.8/§4.9）
- **[E2E-F08.6-01]** 年度→費目→予算配分→取引記録(閾値超過で PENDING_APPROVAL+WR生成)→F05.6承認→APPROVED→消化率再計算→取消仕訳→報告書PDF(@Async)。（§3/§5）
- **[E2E-F09.13-01]** クレジット購入(Stripe)→`credit_balance`加算→一斉通知で consume(PESSIMISTIC_WRITE)→月次リセット→残高不足で猶予72h→超過で 402。（§1）

---

## 3. このフェーズの「設計にあるが UI/導線が無い」確定
| 機能 | 状態 | 影響 |
|---|---|---|
| F08.5 QR スキャン消化・複数同時消化 | ⚫到達不能 | **整骨院/美容院の現場運用が成立しない** |
| F08.5 有効期限延長UI・統計UI | 🔴 | |
| F08.6 取消仕訳UI・CSVインポートUI・年度再開UI | 🔴 | BE完備・操作導線無し |
| F09.13 残高/購入/管理UI | 🟡 | Webhook経路も要検証 |
| F18 提示モードUI・Fuzzy Match | 🔴/🟡 | Phase2 凍結 |

---

## 4. 決済 E2E の実機可否（Stripe）
- **実決済不可**。テストカード(4242-4242-4242-4242)＋`stripe listen`(Stripe CLI)で Webhook 到達まで検証。
- 検証可能: Checkout→Webhook→`PAID`遷移、`budget_transactions`自動計上、消化/返金/取消仕訳の状態遷移。
- 要テスト支援: `NotificationCreditService.consume()` 直呼びでの無料枠/猶予検証、CSV インポートのプレビュー→確定。

## 5. 既存 E2E spec ギャップ
- 設計記載だが実装品質要確認: F08.5 誤消化取消72h制限、F08.6 承認WF連携の実 WR 生成・CSV import_token冪等(Valkey TTL15分)、F09.13 無料枠リセット時の残高相殺、F18 AES-256-GCM 暗号化適用。
