# F22.1 市（Market）— 謝礼決済（Stripe Connect エスクロー）

> **ステータス**: 🟢 設計確定（実装未着手）
> **実装フェーズ**: Phase 2 後半（札の謝礼を Stripe Connect で与信→エスクロー→払出する基盤）
> **最終更新**: 2026-06-02
> **親機能**: [F22.1 市](../README.md)（Phase 2「将来の謝礼決済」の本設計）

---

## 0. この設計書の構成

複合形（F22.1 本体 / F13.1 / F03.5 と同じ分割方式）で構成する。

| ファイル | 内容 |
|---|---|
| `README.md`（本書） | 概要・スコープ・既存 payment 資産再利用方針・F13.1 設計図の流用と差分・推奨方式（案A）の理由・段階ロードマップ・変更履歴 |
| [`01_data_model.md`](01_data_model.md) | DB設計（`connect_accounts` / `escrow_transactions` / `ledger_entries` / `refunds` / `stripe_webhook_events` の新規DDL・既存テーブル最小拡張・Flyway・ER図） |
| [`02_api_design.md`](02_api_design.md) | API設計（Connect onboarding・札謝礼設定・与信/capture/transfer・返金・Connect Webhook・DTO・エラーコード・冪等性・払出保留フロー） |
| [`03_security.md`](03_security.md) | セキュリティ（認可マトリクス・PCI(SAQ-A)・Webhook署名検証＋冪等性・IDOR・GDPR/退会・資金移動業回避の根拠・レート制限）・未解決事項・税務別建て論点・ステータス確定条件 |
| [`04_ui_i18n.md`](04_ui_i18n.md) | 画面設計（口座登録導線・受領主体選択UI・謝礼提示・払出/返金通知文言）・i18n 6言語キー骨子 |

---

## 1. 概要

F22.1「市」は地域×ジャンルで札（募集）を束ねる外向きの市場である（[親 README](../README.md)）。本設計はその Phase 2 後半 ―「**札の謝礼を安全に決済する基盤**」を定義する。

審判募集・助っ人募集・練習試合などの札には**謝礼（報酬）が伴う**ことがある。現状 `recruitment_listings.payment_enabled`(BOOLEAN) / `price`(Integer) は列としては存在するが**決済処理には一切接続されていない**（札に値札が付くだけ）。本設計は、この値札を **「与信（応募成立時に与信枠を確保）→ エスクロー（市＝Stripe が資金を保有）→ 払出（最終認証時に受領者へ送金）」** の資金フローに接続する。

### 1.1 用語と既存資産マッピング

| 概念 | 市の語彙 | 実体（接続する既存資産） |
|---|---|---|
| 謝礼を設定する | 札に**値札を付ける** | `recruitment_listings.payment_enabled` / `price`（既存・決済未接続） |
| 応募が成立する＝与信 | 札の**手付** | `recruitment/entity/RecruitmentListingEntity.incrementConfirmed()`（OPEN→FULL） |
| 最終認証で払い出す | **札を下げて精算** | `recruitment/service/MarketFinalizeService` + `MarketFinalizeConfirmedListener`（FULL→COMPLETED） |
| 取下げ・期限切れで返金 | **手付を返す** | `cancelByAdmin()` / `autoCancel()` |

---

## 2. スコープ

### 2.1 対象（Phase 2 後半で扱う）
- [x] 札ごとの謝礼決済（与信→エスクロー→払出）— 出所 `source_kind=RECRUITMENT`
- [x] **受領者（Stripe Connect アカウント主体）を札ごとに「個人 / 所属チーム / 所属組織」から選択**（マスター御裁可）
- [x] Connect アカウントの onboarding（個人＝本人 / チーム・組織＝scope ADMIN）
- [x] 部分返金・全額返金・与信取消（authorization cancel）
- [x] Webhook 冪等性（`stripe_webhook_events` による event_id 一意制約）
- [x] 複式記帳の台帳（`ledger_entries`）骨格

### 2.2 対象外（本設計では扱わない・別軍議）
- [ ] **税務（謝礼の所得区分・源泉徴収・支払調書・適格請求書/インボイス）** → §9 のとおり**税理士確認の別建て論点**。設計内で確定しない。
- [ ] フリマ（物品売買）の決済 → 本設計の `escrow_transactions.source_kind=FLEAMARKET` を**転用ポイントとして確保**するのみ（フロー自体は別軍議）。
- [ ] F13.1 短期業務マッチングへの本基盤の逆流用（F13.1 が本基盤を使うかは F13.1 側の判断・`source_kind=JOBMATCHING` を確保）。
- [ ] サブスクリプション・継続課金（本基盤は単発エスクローのみ）。

### 2.3 対象ロール（誰が決済主体になれるか）
| ロール | 値札を付ける（札主） | 受領者になれる | onboarding |
|---|---|---|---|
| 個人（応募者・札主代理） | ―（札主はチーム/組織） | **○**（`payee_kind=USER`、本人 onboarding） | 本人のみ |
| チーム ADMIN / DEPUTY(`MANAGE_RECRUITMENTS`) | ○（自チームの札） | **○**（`payee_kind=TEAM`） | チーム scope ADMIN |
| 組織 ADMIN | ○（自組織の札） | **○**（`payee_kind=ORG`） | 組織 scope ADMIN |
| 支払者（応募側） | ― | ― | 支払いは Stripe Checkout/Elements（カード直送・onboarding 不要） |

> **札主と受領者は一致しなくてよい**。例: チームが審判募集の札を立て（札主=TEAM）、謝礼の受領者は審判個人（`payee_kind=USER`）。逆に、団体役務（遠征費等）はチーム/組織受領（`payee_kind=TEAM/ORG`）。**この「札ごとに受領主体を選ぶ」柔軟性がマスター御裁可の中核**（§3.2）。

---

## 3. 設計の前提（マスター御裁可・絶対）

### 3.1 推奨方式 ＝ 案A: Destination Charge + 手動キャプチャ

```
支払者カード ──[PaymentIntent(capture_method=manual)]──▶ 与信のみ（資金は動かない・Stripe が枠を確保）
                                                          │
                          最終認証（札を下げる/MarketFinalize confirm）
                                                          ▼
            PaymentIntent.capture() ＝ 確定 ＋ transfer_data.destination で受領者 Connect へ即送金
            （application_fee_amount = プラットフォーム手数料を控除）
```

**なぜ案A か（採用理由）:**

| 観点 | 案A: Destination Charge + 手動キャプチャ | 不採用案 |
|---|---|---|
| **資金移動業登録** | **不要**。資金は終始 **Stripe が保有**し、Mannschaft の口座を経由しない（Stripe 収納代行）。日本の資金決済法上の「為替取引」に該当しない（§03_security §6） | 自社口座でプールする方式（Separate Charge + 後日 Transfer の自社残高滞留）は資金移動業/前払式支払手段の登録リスク |
| **エスクロー（与信保持）** | `capture_method=manual` の **authorization hold** がそのままエスクローになる。最終認証まで資金は支払者に残り、確定時に初めて引き落とし＋送金 | Separate Charge は即時課金のため、払出まで自社が資金を保持＝法規制リスク |
| **二重払出防止** | capture と transfer が**1コールで原子的**。`MarketFinalize` の札行 `PESSIMISTIC_WRITE` ロック直下に差し込めば物理的に直列化 | 課金と送金が分離すると整合確保が複雑 |
| **税務上の帰属** | `on_behalf_of=受領者 Connect` で **Stripe ダッシュボード上も受領者の売上**として扱える | ― |

> **authorization hold の有効期限**は card-issuer 依存で最大 7 日（カード種別で 2〜7 日）。市の最終認証が長引くケースの扱いは F13.1 §8.9 の「先 capture 後返金」戦略を踏襲する（02_api_design §6 / 01_data_model `escrow_transactions.status` の `DISPUTED`）。

### 3.2 受領者は札ごとに個人/チーム/組織を選択（最も柔軟な案）

- `escrow_transactions.payee_kind ENUM(USER/TEAM/ORG)` ＋ `payee_connect_account_id`（`connect_accounts.id` 論理参照）で**札ごとの受領主体**を表現する。
- `connect_accounts.scope_kind ENUM(USER/TEAM/ORG)` ＋ `scope_id` で、**個人・チーム・組織それぞれが独立した Connect アカウント**を持てる。
- 個人役務（審判・助っ人）は個人受領、団体役務はチーム/組織受領を札主が選べる。**個人・チーム双方の onboarding・認可・台帳が成立する**（DDL/API での表現は 01/02 を参照）。

---

## 4. 既存 payment 資産の再利用方針

家老偵察により、既存 `com.mannschaft.app.payment` ドメインに**再利用可能な資産が揃っている**ことを確認済み。**ゼロから決済ドメインを作らず、既存 payment ドメインを Connect 層で増築する**（CLAUDE.md 再利用優先・重複実装禁止）。

| 機能 | 充当する既存資産 | 状態 |
|---|---|---|
| Product/Price/Customer/Checkout/全額Refund/Webhook署名検証 | `payment/stripe/StripePaymentProvider(Impl)` | ✅ 実装済（拡張する） |
| Webhook 受け口・署名検証 | `payment/controller/StripeWebhookController` + `service/StripeWebhookService`（`/api/v1/webhooks/stripe`・`/stripe/*` permitAll 稼働） | ✅ 実装済（Connect イベント分岐を追加） |
| Stripe Customer 管理 | `payment/entity/StripeCustomerEntity` | ✅ 実装済 |
| Stripe SDK | `build.gradle.kts` の `com.stripe:stripe-java:28.2.0` | ✅ 導入済 |

### 4.1 欠落＝新規増築対象（Connect 層）

既存 payment は**プラットフォーム単独課金**（自社 Stripe アカウントへの入金）専用で、**Connect（第三者受領）層が丸ごと欠落**している。本設計が増築するのは以下。

| 増築物 | 内容 | 配置 |
|---|---|---|
| Connect Account / AccountLink | Express アカウント作成・onboarding link 発行・`account.updated` 反映 | `payment.connect`（新サブパッケージ） |
| PaymentIntent 手動キャプチャ | `capture_method=manual` で与信→capture | `payment.escrow` |
| Transfer / Destination | `transfer_data.destination` + `on_behalf_of` | `payment.escrow` |
| 部分返金 / 与信取消 | `Refund.create(amount=...)` / `PaymentIntent.cancel()` | `payment.escrow` |
| エスクロー台帳 | `escrow_transactions` / `ledger_entries` / `refunds` | `payment.escrow` |
| Webhook 冪等性 | `stripe_webhook_events`（event_id UNIQUE） | `payment`（共通） |

> **既存 `StripePaymentProvider` インターフェースを破壊しない**。Connect 系メソッド（`createConnectAccount`/`createAccountLink`/`createDestinationPaymentIntent`/`captureManualPaymentIntent`/`createPartialRefund`/`cancelAuthorization`）は**追加**し、既存メソッドは温存する（02_api_design §8）。

### 4.2 前例にならないもの（混同禁止）
- **`wallet` ドメインは存在しない**（家老確認済）。本設計と混同しないこと。
- recruitment のキャンセル料決済 `RecruitmentPaymentRetryBatch` は**スタブ（TODO のみ）**で前例にならない。本設計が初の本格決済接続となる。

---

## 5. F13.1 設計図の流用と差分

F13.1（短期業務マッチング）の決済は **enum/コメントのみで実装ゼロ**（実コード確認: `jobmatching/enums/JobContractStatus.java` の MATCHED/AUTHORIZED/CAPTURED/PAID/DISPUTED は定義のみ、`jobmatching/service/JobContractService.java` は PaymentIntent/capture/transfer を一切呼ばない）。

しかし **F13.1 設計書（[`../../F13.1_short_term_job_matching/03_ui_payment.md`](../../F13.1_short_term_job_matching/03_ui_payment.md) §8 / README）は資金フロー設計図として精緻**であり、本設計はこれを**市向けに再構成・流用**する。

| 論点 | F13.1 設計図 | 市（本設計）の差分 |
|---|---|---|
| 資金フロー | Destination Charge + 手動キャプチャ | **同一**（案A）。F13.1 §8.3〜8.5 をそのまま踏襲 |
| **受領主体** | **Worker＝個人前提**（Express は Worker ごと） | **札ごとに個人/チーム/組織を選択**（`payee_kind`）。**最大の差分** |
| 与信トリガ | 採用確定直前 | **応募成立（`incrementConfirmed()` OPEN→FULL）** |
| 払出トリガ | 完了承認（QR チェックアウト後） | **最終認証（`MarketFinalize` confirm → FULL→COMPLETED）**。札行ロックで直列化済 |
| 手数料 | `application_fee_amount`（手数料＋税） | **同一**（Phase 2 後半は手数料率を設定値で保持。固定/率は §9-未解決で確定） |
| エスクロー期限 | 7 日タイマー + 6日22時間で自動 capture | **同一戦略**を踏襲（最終認証未了時の `DISPUTED`→先capture後返金） |
| QR チェックイン | あり（業務時間実測） | **市には不要**（役務の性質が異なる。市は最終認証 confirm のみ） |
| テーブル名 | `job_payments` / `stripe_connect_accounts`（BIGINT） | **新規 UUIDv7 テーブル**（`escrow_transactions` / `connect_accounts`）。F13.1 のテーブルは流用せず、市は独自に構築（CLAUDE.md 原則6・新規UUIDv7） |

> **テーブルは F13.1 と共有しない**。F13.1 のテーブルは未実装かつ BIGINT 設計のため、市は新規 UUIDv7 テーブルを `payment.escrow` に新設する。`source_kind=JOBMATCHING` を確保しておくことで、将来 F13.1 が本基盤を使う選択肢を残す（疎結合・01_data_model §2）。

---

## 6. 段階ロードマップ

| フェーズ | 範囲 | 規模 | 部隊 | モデル | test-first |
|---|---|---|---|---|---|
| **P2-a 基盤** | `connect_accounts` / Connect onboarding（個人/チーム/組織）/ `stripe_webhook_events` 冪等性 / `ledger_entries` 骨格 / `StripePaymentProvider` Connect メソッド追加 | **L** | 部隊1（BE Connect）+部隊2（BE Webhook/台帳） | opus4.8 | ○（Connect onboarding 認可＋Webhook冪等のUT/契約テスト先行） |
| **P2-b 与信** | 応募成立時の与信（`incrementConfirmed()` フック）/ `escrow_transactions` AUTHORIZED / 受領者 onboarding 未完了時の払出保留 | **M** | 部隊3（BE 与信） | opus4.8 | ○（与信→AUTHORIZED 状態遷移UT・保留フロー契約テスト先行） |
| **P2-c 払出&返金** | 最終認証時 capture+transfer（`MarketFinalize` フック・札行ロック直下）/ 部分・全額返金 / 与信取消 / エスクロー自動 capture バッチ | **L** | 部隊4（BE 払出）+部隊5（BE 返金/バッチ） | opus4.8 | ○（capture 原子性・二重払出防止のUT先行） |
| **P2-d フリマ転用** | `source_kind=FLEAMARKET` 接続（**別軍議**・本設計は転用点の確保のみ） | ― | ― | ― | ― |
| **FE/E2E** | onboarding 導線・受領主体選択UI・謝礼提示・通知文言・i18n 6言語 | **M** | 部隊6（FE）+部隊7（E2E） | FE=sonnet/opus・E2E=opus4.8 | △（BE 契約確定後・FE/E2E は後） |

> test-first 適用方針（memory `feedback_test_first_be_api`）: **BE ドメイン UT ＋ API 契約テストは実装より前に本設計から書く**。FE/E2E は後。各 Phase の規模は S/M/L で示す。

---

## 7. 決済フック点（既存メソッド・確定）

家老偵察で確定した、既存コードへの**最小侵襲フック点**。

| フェーズ | フック点（既存メソッド） | 接続内容 |
|---|---|---|
| 与信 | `recruitment/entity/RecruitmentListingEntity.incrementConfirmed()`（OPEN→FULL） | 応募成立イベント発火 → `payment.escrow` が与信（PaymentIntent manual）を作成 |
| 払出 | `recruitment/service/MarketFinalizeService` + `MarketFinalizeConfirmedListener`（FULL→COMPLETED・札行 `PESSIMISTIC_WRITE`） | confirm 直下で capture+transfer。**札行ロックで二重払出を物理的に防ぐ** |
| 返金 | `recruitment/service/...cancelByAdmin()` / `autoCancel()`（札下げ・期限超過） | 与信中なら `PaymentIntent.cancel()`、capture 済なら `Refund.create()` |

> **疎結合の境界**: `recruitment` ドメインは決済の詳細を知らない。`payment.escrow` が `ApplicationEvent`（`RecruitmentConfirmedEvent` / `MarketFinalizedEvent` / `RecruitmentCancelledEvent`）を購読する形で接続する（CLAUDE.md 原則5・ドメイン内 @Transactional 維持）。

---

## 8. 既存設計書への波及（相互参照追記）

| ドキュメント | 追記内容 |
|---|---|
| `F22.1_market/README.md` §1 対応表 | 「将来の謝礼決済 ← F13.1 ✅実装済（委譲フックのみ）」を**是正**（実態=F13.1決済は未実装・設計図のみ流用）。正しい表記＝「設計図を流用し市が独自に決済基盤を構築（本サブ設計）」へ |
| `F13.1_short_term_job_matching/README.md` | 「F22.1 が F13.1 の資金フロー設計図を流用し、市向けに独自の決済基盤を構築する」相互参照を追記（最小） |
| `F03.11_recruitment_listing.md` | 関連ドキュメントに本設計（謝礼決済の接続先）を相互参照（最小） |
| `docs/security/01_authorization_baseline.md` §3.6 | Connect Webhook は**既存 `POST /api/v1/webhooks/stripe/*`（1階層 `*`）許可で被覆済**であることを明記（`/webhooks/stripe/connect` は新規許可不要・§03_security §2） |

---

## 9. 未解決事項（解決方針付き）

[03_security.md §3](03_security.md) に全件を集約し解決方針を確定する。本書では要約のみ。

- [x] 受領者の札ごと個人/チーム/組織選択 → `payee_kind` ＋ `connect_accounts.scope_kind`（§3.2 / 01 §3）
- [x] 資金移動業の回避 → 案A（Stripe 収納代行・自社口座非経由）（03 §6）
- [x] 受領者 onboarding 未完了時の払出 → **保留（HELD）**。72h 猶予で onboarding 完了を待ち、未完了なら与信取消＋応募者へ通知（F13.1 §8.9 相当）（02 §5）
- [x] KYC 審査落ち・Connect 制限 → `connect_accounts.onboarding_status=RESTRICTED`、payouts 不可なら札の謝礼を無効化し札主へ通知（02 §3）
- [x] 通貨 JPY ゼロデシマル → `amount` は**最小単位=円そのもの**（JPY は decimal 桁数 0）。`currency` 列で明示し、Stripe へは円整数で渡す（01 §3 備考）
- [x] 退会時の資金 → 係争中/与信中の資金は**強匿名化の30日猶予側**（CLAUDE.md PII二段モデル）。Connect 切離しは払出/返金完了後（03 §5）
- [ ] **税務（所得区分・源泉徴収・支払調書・インボイス）** → **税理士確認の別建て論点**として明示（03 §3 / 本書 §2.2）。設計内で確定しない
- [x] 手数料率（固定/率）→ Phase 2 後半は**設定値で保持**（`escrow_transactions.application_fee_amount` に確定額を記録）。率の確定は運用ポリシー（別途）

---

## 10. 関連ドキュメント

- [F22.1 市 本体](../README.md)（札の実体・最終認証・札行ロックの正典）
- [F13.1 短期業務マッチング](../../F13.1_short_term_job_matching/README.md)（資金フロー設計図の流用元・実装は未着手）
  - 特に [`03_ui_payment.md`](../../F13.1_short_term_job_matching/03_ui_payment.md) §8 Stripe Connect 統合
- F03.11 募集機能（札の状態遷移・`payment_enabled`/`price` の出自）
- `docs/security/01_authorization_baseline.md`（permitAll 許可リスト・Webhook 署名検証）
- `docs/architecture/withdrawal_flow_immediate_anonymization_fix.md`（退会 PII 二段モデル）

---

## 11. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-06-02 | 初版作成（軍議・家老偵察反映・2周精査完了・🟢設計確定/実装未着手）。案A（Destination Charge + 手動キャプチャ）採用。受領者を札ごとに個人/チーム/組織選択（`payee_kind`/`connect_accounts.scope_kind`）。既存 payment 資産を Connect 層で増築。F13.1 設計図を市向けに流用（受領主体が個人前提→札ごと選択へ差分化）。税務は税理士確認の別建て論点として明示 |
