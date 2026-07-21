# F22.1 市（Market）— 統一決済プラットフォーム（Stripe Connect・謝礼＋会費共通基盤）

> **ステータス**: 🟢 設計確定（実装: P2-a/P2-b/P2-c 完了・main済／手数料ランク化＝本設計で正典化・実装未着手）
> **実装フェーズ**: Phase 2 後半（謝礼＝札主→応じ手・会費＝会員→チーム/組織 を **1つの共通 Connect 送金基盤** で扱う統一決済プラットフォーム）
> **最終更新**: 2026-07-21（§3.0.1・関連ロードマップ・関連ドキュメント一覧の F08.7.1 記述を実装実態に合わせて是正。詳細は §11 変更履歴）
> **親機能**: [F22.1 市](../README.md)（Phase 2「将来の謝礼決済」の本設計）／統一基盤として [F08.2 支払い・アクセス制御](../../F08.2_payments_access_control.md) の会費徴収も本基盤へ移行

---

## 0. この設計書の構成

複合形（F22.1 本体 / F13.1 / F03.5 と同じ分割方式）で構成する。

> **【正典更新・2026-06-03】統一決済プラットフォーム化（マスター承認済）**
> 本設計は当初「謝礼単独・受取側が手数料を全額負担」前提だったが、マスター御裁可により以下の確定モデルへ更新（正典化）した。詳細は §3.3〜§3.5。
> - **A. 統一基盤化**: 謝礼（F22.1）と**会費徴収（F08.2・会員→チーム/組織）**を、1つの共通 Connect 送金サービス `ConnectChargeService` に集約。受取主体は `connect_accounts.scope_kind`=USER/TEAM/ORG で抽象化。2モード（即時＝会費 / エスクロー＝謝礼）。将来 F13.1 スキマバイト謝礼も同基盤相乗り。
> - **B. 手数料 5% を支払者2.5%・受取側2.5%で折半**（案あ確定）。受取側が全額負担する旧記述は破棄。
> - **C. 返金はチーム/組織 ADMIN が操作**（運営非関与）・Stripe `reverse_transfer:true` ＋ `refund_application_fee:false`（設定A）。決済手数料は返金されない。
> - **D. 決済確認画面に手数料内訳を明示**・受取側設定画面に受取額（額面−2.5%）を表示・i18n 6言語・利用規約明記。
> - **E. ロードマップ更新**（§6）。
>
> **【正典更新・2026-06-04】統一決済アーキ原則の正典化＋手数料ランク化（マスター承認済・合同軍議／精緻化軍議確定）**
> 合同軍議・精緻化軍議の確定事項を正典として反映した。詳細は §3.0（統一アーキ原則）／§3.4（手数料ランク `fee_policies`）。
> - **A0. 受取人で二分する統一アーキ原則**: **第三者（チーム/組織/個人）受取の集金は全て本 Connect レール**（destination charge・受取側直接着金・資金移動業回避・手数料 application_fee）。**Mannschaft 自社受取（例: F09.13 通知クレジット）のみ素 Checkout 可**。F08.2 の素 Checkout 全廃は「第三者受取からの撤去」であって自社受取は残置（Expand→Migrate→Contract）。F08.7.1 大会/リーグ参加費は **PR #1432/#1433（2026-06-10 main マージ）で選手個人自払いの Connect 決済を実装済み**だが、チーム代表による旧・素 Checkout 経路も併存しており Migrate/Contract は未完了（§3.0.1）。
> - **B0. 手数料は「率(%)＋固定額(¥)」をマスタ表 `fee_policies` で持つ（旧: 5% 定数を撤廃）**。負担は **折半50/50固定**（総手数料＝`percent_rate×face ＋ flat_fee` を支払者・受取側で半分ずつ）。**DEFAULT パターン＝率5%＋固定0円**＝既存挙動と完全一致（後方互換）。他パターンはシスアドが随時 CRUD 追加し source_kind＋任意 sub_key（助っ人＝recruitment_category 等）で割当・解決。少額決済で「総手数料 > 額面」になる破綻を防ぐ**安全ガード**を必須化。`PaymentFeeCalculator` を定数撤廃→policy 注入計算へ。レートは charge/加入時に解決した `fee_policy_key` と金額を escrow に焼き付け（遡及防止）。`/api/v1/system-admin/fee-policies` でシスアド CRUD。

| ファイル | 内容 |
|---|---|
| `README.md`（本書） | 概要・統一決済プラットフォームモデル（謝礼＋会費）・スコープ・既存 payment 資産再利用方針・F13.1 設計図の流用と差分・推奨方式（案A）の理由・**手数料折半（5%＝2.5%+2.5%）と具体例表**・段階ロードマップ・変更履歴 |
| [`01_data_model.md`](01_data_model.md) | DB設計（`connect_accounts` / `escrow_transactions`（会費＝`MEMBERSHIP` 即時モード含む）/ `ledger_entries` / `refunds` / `stripe_webhook_events` の新規DDL・既存テーブル最小拡張・Flyway・ER図） |
| [`02_api_design.md`](02_api_design.md) | API設計（`ConnectChargeService` 共通基盤・2モード・Connect onboarding・札謝礼設定・会費徴収・与信/capture/transfer・**手数料折半の計算規約と具体例表**・返金（ADMIN操作・reverse_transfer）・Connect Webhook・DTO・エラーコード・冪等性・払出保留フロー） |
| [`03_security.md`](03_security.md) | セキュリティ（認可マトリクス・**返金＝受取側 scope ADMIN**・PCI(SAQ-A)・Webhook署名検証＋冪等性・IDOR・GDPR/退会・資金移動業回避の根拠・**利用規約／返金注意書き（手数料非返還）**・レート制限）・未解決事項・税務別建て論点・ステータス確定条件 |
| [`04_ui_i18n.md`](04_ui_i18n.md) | 画面設計（口座登録導線・受領主体選択UI・謝礼/会費提示・**決済確認画面の手数料内訳表示**・**受取側設定画面の受取額表示**・払出/返金通知文言）・i18n 6言語キー骨子 |

---

## 1. 概要

F22.1「市」は地域×ジャンルで札（募集）を束ねる外向きの市場である（[親 README](../README.md)）。本設計はその Phase 2 後半 ―「**謝礼と会費を安全に決済する統一プラットフォーム基盤**」を定義する。

### 1.0 統一決済プラットフォームモデル（正典・マスター承認済）

Mannschaft の「個人・チーム・組織への送金が伴う決済」を、**1つの共通 Connect 送金サービス `ConnectChargeService`** に集約する。受取主体は `connect_accounts.scope_kind`（USER/TEAM/ORG）で抽象化し、出所は `escrow_transactions.source_kind` で識別する。

| 出所 | 出所 enum | 支払者 → 受取側 | モード | 採用フェーズ |
|---|---|---|---|---|
| **謝礼**（F22.1） | `RECRUITMENT` | 札主 → 応じ手（個人/チーム/組織） | **エスクローモード**（手動キャプチャ） | P2-b/P2-c |
| **会費徴収**（F08.2） | `MEMBERSHIP` | 会員 → チーム/組織 | **即時モード**（即 capture） | P2-e |
| **大会/リーグ参加費**（F08.7.1・選手個人自払いは Connect 実装済／チーム代表払いは旧・素 Checkout 併存） | `MEMBERSHIP` に相乗り（`TOURNAMENT` 専用値は `EscrowSourceKind` 未定義・§3.0.1） | 選手個人 → 主催組織 | 即時モード（実装済） | 実装済（Expand。専用 `source_kind` 移行と旧経路撤去は未着手） |
| スキマバイト謝礼（F13.1・将来） | `JOBMATCHING` | 発注者 → ワーカー | エスクロー（相乗り） | 別軍議 |
| フリマ（将来） | `FLEAMARKET` | 買い手 → 売り手 | 別軍議 | 別軍議 |

> `source_kind` は手数料ランク（§3.4）の解決キーも兼ねる。`fee_policy_assignments.source_kind` の CHECK には `TOURNAMENT` が確保済みだが、`EscrowSourceKind`（Java enum）と `escrow_transactions.source_kind` の CHECK にはまだ追加されておらず、実際の大会参加費 Connect 決済は `MEMBERSHIP` に相乗りしている（§3.0.1）。

> **会費専用の決済基盤は作らない**。F08.2 の会費徴収は本統一基盤（`source_kind=MEMBERSHIP`）へ集約・移行する（§8 / [F08.2](../../F08.2_payments_access_control.md) 相互参照）。

### 1.0.1 2モード（共通基盤の振る舞い分岐）

| | **即時モード（会費）** | **エスクローモード（謝礼）** |
|---|---|---|
| 課金方式 | Destination Charge `capture_method=automatic`（即 CAPTURED） | Destination Charge `capture_method=manual`（与信→手動 capture） |
| 与信トリガ | 会員の支払操作で即時 | 応募成立（`incrementConfirmed()` OPEN→FULL）で与信 |
| 払出トリガ | 課金と同時（即 transfer） | 最終認証（`MarketFinalizeService` 札行 `PESSIMISTIC_WRITE` 直下）で capture+transfer |
| 台帳初期 status | `CAPTURED`（INSERT 時・`hold_expires_at` NULL） | `AUTHORIZED`（受取側 onboarding 未完なら `HELD`・72h 猶予） |
| 共通台帳 | `escrow_transactions`（`source_kind=MEMBERSHIP`） | `escrow_transactions`（`source_kind=RECRUITMENT`） |

> 資金は **Stripe Connect ダイレクトチャージで受取側口座へ直接** 入金され、**Mannschaft は資金を保持しない**（資金移動業リスク回避・§3.1 / 03 §6）。両モードとも同じ `ConnectChargeService` が `EscrowSourceKind` と `capture_method` で分岐するだけで、台帳・送金経路・手数料計算は共通。

### 1.0.2 旧前提からの変更点

審判募集・助っ人募集・練習試合などの札には**謝礼（報酬）が伴う**ことがある。現状 `recruitment_listings.payment_enabled`(BOOLEAN) / `price`(Integer) は列としては存在するが**決済処理には一切接続されていない**（札に値札が付くだけ）。本設計は、この値札を **「与信（応募成立時に与信枠を確保）→ エスクロー（Stripe が資金を保有）→ 払出（最終認証時に受領者へ送金）」** の資金フローに接続する。

> ⚠️ **旧前提の破棄**: 当初設計の「受取側が決済手数料を全額負担」「謝礼単独前提」は破棄し、**手数料 5% を支払者2.5%・受取側2.5%で折半**（§3.4・案あ確定）／**会費も同一基盤**（§1.0）に正典更新した。

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
- [x] **共通 Connect 送金サービス `ConnectChargeService`**（謝礼＋会費を1基盤に集約・2モード）
- [x] 札ごとの謝礼決済（与信→エスクロー→払出）— 出所 `source_kind=RECRUITMENT`・エスクローモード
- [x] **会費徴収（会員→チーム/組織）の本基盤集約** — 出所 `source_kind=MEMBERSHIP`・即時モード（P2-e）
- [x] **受領者（Stripe Connect アカウント主体）を札ごとに「個人 / 所属チーム / 所属組織」から選択**（マスター御裁可）
- [x] Connect アカウントの onboarding（個人＝本人 / チーム・組織＝scope ADMIN）
- [x] **手数料ランク制（`fee_policies`・率%＋固定額¥・折半50/50固定）**（§3.4・DEFAULT＝率5%＋固定0＝旧「総5%折半」と完全一致・後方互換）。安全ガード・遡及防止焼き付け・シスアド CRUD 含む
- [x] 部分返金・全額返金・与信取消（authorization cancel）— **チーム/組織 ADMIN が操作**・Stripe `reverse_transfer:true`/`refund_application_fee:false`（§3.5・設定A）
- [x] Webhook 冪等性（`stripe_webhook_events` による event_id 一意制約）
- [x] 複式記帳の台帳（`ledger_entries`）骨格・Stripe 実手数料を `FEE` に記録し日次照合

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

### 3.0 統一決済アーキ原則 ＝ 受取人で二分する（合同軍議確定・正典）

Mannschaft の全決済（集金）は **受取人が誰か** で二分し、レールを使い分ける。これが統一決済アーキの第一原理である。

| 受取人 | 例 | レール | 根拠 |
|---|---|---|---|
| **第三者**（チーム / 組織 / 個人） | 謝礼（F22.1）・会費（F08.2/F08.9）・大会参加費（F08.7.1）・協会→チーム請求（F08.9 §6）・スキマバイト謝礼（F13.1 将来）・フリマ（将来） | **本 F22.1 Connect レール（destination charge）** | 受取側へ**直接着金**し Mannschaft の口座を経由しない＝**資金移動業回避**。手数料は `application_fee` で徴収（§3.1 / 03 §6） |
| **Mannschaft 自社**（自社が役務提供者＝受取人） | F09.13 通知クレジット購入 等 | **素 Checkout 可**（Connect 不要） | 自社売上＝第三者送金が発生しない。資金移動業に該当しないため Connect を経由する必要がない |

**判定基準**: 「集金した金が**最終的に Mannschaft 以外の主体へ渡る**」なら第三者受取＝Connect 必須。「集金した金が**Mannschaft の売上として留まる**」なら自社受取＝素 Checkout 可。

> **F08.2 素 Checkout の「全廃」は、正しくは「第三者受取からの撤去」**である。会費・参加費等の第三者受取は本 Connect 基盤へ移行するが、**自社受取（通知クレジット等）は素 Checkout のまま残置**する。移行は **Expand→Migrate→Contract**（新レール並走→移行→旧レール撤去）の順で行い、一斉切替はしない（README §8.1 / F08.2 相互参照）。

#### 3.0.1 F08.7.1 大会/リーグ参加費 ＝ Connect 実装済（Expand）・旧経路併存で Migrate/Contract 未完了（2026-07-21 是正）

[F08.7.1 大会費用支払い](../../F08.7.1_tournament_extensions/07_tournament_payment.md) は大会参加費を **主催組織（＝第三者）受取** で集金する。**PR #1432（BE, commit `f9adaa2e3`）／PR #1433（FE, commit `121f6b71c`）が 2026-06-10 に main マージ済**であり、選手個人自払いの Connect 決済（`POST /api/v1/tournament-fees/{feeId}/checkout` → `MemberPaymentService.createConnectCheckout` → Destination PaymentIntent）が既に実装されている。**§3.0 の統一アーキ原則に照らした「原則違反」は解消済み**（旧・本節はこの実装より前の 2026-06-04 時点の記述であり、以下は 2026-07-21 の是正後の実態）。

ただし移行は Expand→Migrate→Contract の **Expand で停止**しており、以下が未完了である:

1. **旧経路の併存**: `TournamentFeeController` の `POST /fees/{feeId}/teams/{teamId}/checkout`（チーム代表による支払い）は、依然として F08.2 の旧 `MemberPaymentService.createCheckout()`（素 Checkout・主催組織の Stripe アカウント直課金）に委譲したままである。Migrate（旧経路の切替）・Contract（旧経路の撤去）は未着手。
2. **専用 `source_kind=TOURNAMENT` への移行が未着手**: `fee_policy_assignments.source_kind` の CHECK には `TOURNAMENT` が確保済みだが、`EscrowSourceKind`（Java enum）と `escrow_transactions.source_kind` の CHECK にはまだ追加されていない。実際の Connect 決済は `EscrowSourceKind.MEMBERSHIP` に相乗りして記録されている（この相乗りを選んだ経緯は記録がなく不明）。
3. **手数料ランク制（§3.4）への未接続**: 大会参加費の Connect チェックアウトは `payerSurcharge` を `0` 固定で返しており、`PaymentFeeCalculator`／`fee_policies` とまだ接続されていない。

> F08.7.1 設計書側にも本実装状況を反映済み（[F08.7.1 07_tournament_payment.md](../../F08.7.1_tournament_extensions/07_tournament_payment.md) §0）。手数料ランク表（§3.4）に確保済みの `TOURNAMENT` は、専用 `source_kind` 移行が完了した際に参加費専用の手数料パターンを割当できるようにするための備え（現時点では未接続）。

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

### 3.3 統一基盤化（謝礼＋会費を `ConnectChargeService` に集約・マスター承認済）

- 謝礼（札主→応じ手）と会費（会員→チーム/組織）を **1つの共通 Connect 送金サービス `ConnectChargeService`** に集約する。両者は「個人/チーム/組織への Connect ダイレクトチャージ」という同一構造であり、`EscrowSourceKind`（RECRUITMENT / MEMBERSHIP）＋ `capture_method`（manual / automatic）で振る舞いを分岐する（§1.0.1）。
- `escrow_transactions` を**両モード共通台帳**とし、`EscrowSourceKind` に **`MEMBERSHIP` を追加**する。即時モード（会費）は INSERT 時点で `status=CAPTURED`・`hold_expires_at=NULL`（与信フェーズを経ない）。
- 会費専用の決済台帳は作らない。将来 F13.1 スキマバイト謝礼（`JOBMATCHING`）も同基盤に相乗りできるよう `source_kind` で拡張する。
- 資金は **Stripe Connect ダイレクトチャージで受取側口座へ直接**入金され、**Mannschaft は資金を保持しない**（資金移動業リスク回避・§3.1 / 03 §6）。

### 3.4 手数料 ＝ ランク制（`fee_policies` マスタ・率%＋固定額¥・折半50/50固定）（マスター確定・精緻化軍議）

> **【正典更新・2026-06-04】定数 5% を撤廃しランク制へ**。手数料を「率(`percent_rate` %)＋固定額(`flat_fee_minor` ¥)」の**マスタ表 `fee_policies`** で持ち、機能/種別ごとにパターンを割当できるようにした。**DEFAULT パターン＝率5%＋固定0円**で既存挙動と完全一致（後方互換）。詳細スキーマは [01_data_model §3.6](01_data_model.md)、計算規約は [02_api_design §3.5](02_api_design.md)。

#### 3.4.1 手数料の構成（率＋固定額・折半固定）

- **総手数料 = `percent_rate × face_amount ＋ flat_fee_minor`**（率分＋固定額分）。`percent_rate` は `DECIMAL`、`flat_fee_minor` は固定額（円・最小単位）。
- **負担は折半 50/50 固定**（マスター確定）:
  - **支払者**は `face_amount ＋ round(総手数料 ÷ 2)` を請求される（額面＋総手数料の半分を上乗せ）。
  - **受取側**は `face_amount − round(総手数料 ÷ 2)` を受領する（額面から総手数料の半分を差引）。
  - `application_fee_amount = 総手数料`（Mannschaft が徴収する全額）。受取送金額 = 課金額 − `application_fee_amount`。
- **四捨五入・円ゼロデシマル**。`escrow_transactions.application_fee_amount` に確定額を記録（`chk_et_fee: application_fee_amount ≤ amount` を充足）。`stripe_fee_rate` は設定値（既定 0.036・`mannschaft.payment.stripe-fee-rate`）で純益試算に用いる（参考値）。
- **純益の可視化**: `ledger_entries`(FEE) に Stripe 実手数料を記録し日次照合で純益の微変動を可視化する（症状を隠さない・CLAUDE.md 根治原則）。

#### 3.4.2 `fee_policies` マスタ表とパターン解決（概要）

- **マスタ表 `fee_policies`**（自然キー `policy_key`・CLAUDE.md マスタ例外＝税率表と同型）: `policy_key` / `display_name` / `percent_rate DECIMAL` / `flat_fee_minor INT`(固定額・円) / `enabled` / `description`。スキーマ詳細は [01 §3.6](01_data_model.md)。
- **DEFAULT パターン**＝`percent_rate=0.05`・`flat_fee_minor=0`（＝現状の折半5%＋固定0円・既存挙動不変）。
- **割当・解決**: `source_kind`（RECRUITMENT / MEMBERSHIP / TOURNAMENT / JOBMATCHING / FLEAMARKET）＋任意 `sub_key`（**助っ人＝recruitment_category** 等）でパターンを解決する。解決順序は **完全一致（source_kind＋sub_key）→ source_kind 既定 → DEFAULT**。複雑なテナント別上書きは作らない（将来拡張点としてのみ言及・[02 §3.5.3](02_api_design.md)）。
- **シスアド CRUD**: `/api/v1/system-admin/fee-policies`（@PreAuthorize SYSTEM_ADMIN・`{policyKey}` 自然キー）＋割当 CRUD で随時パターン追加。[02 §11](02_api_design.md) / [03 §3](03_security.md)。
- **遡及防止（レート固定）**: charge/与信/サブスク加入時に解決した `policy_key` と算出金額を `escrow_transactions.fee_policy_key`（**新規列**・[01 §3.2](01_data_model.md)）/ `membership_subscriptions` に**焼き付ける**。料率改定（`fee_policies` の更新）は**新規徴収のみに反映**し、既存取引・既存サブスクは焼き付けた料率で固定する。

#### 3.4.3 手数料の具体例 — DEFAULT（率5%＋固定0円・額面 10,000 円・JPY ゼロデシマル）

DEFAULT パターン（`percent_rate=0.05`/`flat_fee_minor=0`）では総手数料＝`0.05×10,000 + 0 = 500`。折半 250/250。**旧 5% 折半モデルと完全一致**（後方互換）。

| 項目 | 金額 | 計算 |
|---|---|---|
| 額面（謝礼/会費） | **10,000 円** | 受取側が設定した額面 |
| 総手数料（DEFAULT 率5%＋固定0） | **500 円** | `round(0.05 × 10,000) + 0` |
| 支払手数料（総手数料の半分） | **+250 円** | `round(500 ÷ 2)` |
| **お支払い合計（課金額 `amount`）** | **10,250 円** | 額面 + 支払手数料 |
| `application_fee_amount`（総手数料） | **500 円** | 総手数料そのもの |
| 受取側送金額 | **9,750 円** | 課金額 10,250 − application_fee 500 |
| Stripe 実手数料（≈3.6%・課金額基準） | **≈369 円** | `round(10,250 × 0.036)` |
| **Mannschaft 純益** | **≈131 円**（額面の ≈1.31%） | application_fee 500 − Stripe 369 |

> 受取側「額面 10,000 → 受取 9,750（−250）」、支払者「額面 10,000 → 請求 10,250（+250）」。総手数料 500 を双方が折半。

#### 3.4.4 手数料の具体例 — 固定額入りパターン（率3%＋固定100円・額面 10,000 円）

シスアドが追加する例: `policy_key='RECRUITMENT_HELPER'`（助っ人募集 sub_key=recruitment_category）に `percent_rate=0.03`・`flat_fee_minor=100` を割当てた場合。総手数料＝`round(0.03×10,000) + 100 = 300 + 100 = 400`。折半 200/200。

| 項目 | 金額 | 計算 |
|---|---|---|
| 額面 | **10,000 円** | 受取側が設定した額面 |
| 総手数料（率3%＋固定100） | **400 円** | `round(0.03 × 10,000) + 100` |
| 支払手数料（総手数料の半分） | **+200 円** | `round(400 ÷ 2)` |
| **お支払い合計（課金額 `amount`）** | **10,200 円** | 額面 + 支払手数料 |
| `application_fee_amount`（総手数料） | **400 円** | 総手数料そのもの |
| 受取側送金額 | **9,800 円** | 課金額 10,200 − application_fee 400 |

> **既存 `JobFeeCalculator`（F13.1・10%＋100円）の統合は将来**（本設計では非統合）: jobmatching ドメインの `JobFeeCalculator` は「率＋固定額」を既に持つため、本 `fee_policies`（率%＋固定額¥）の器でそのまま表現可能（`percent_rate=0.10`・`flat_fee_minor=100`・`source_kind=JOBMATCHING` 割当）。ただし F13.1 の決済自体が未実装ゆえ、**本設計では `JobFeeCalculator` の統合は行わない**（将来 F13.1 が本基盤に相乗りする際に `fee_policies` へ移行する余地としてのみ記す）。

> **安全ガード（必須・少額決済の破綻防止）**: 固定額（`flat_fee_minor`）が混在すると、少額の額面では「総手数料 > 額面」になり得る（例: 固定100円・率5%・額面 1,000 円なら総手数料＝150 円は OK だが、固定 1,000 円・額面 500 円では総手数料 1,025 円 > 額面 500 円で破綻）。これは Stripe の `application_fee_amount ≤ amount` 制約違反かつ「払った額より手数料が高い」破綻を招く。**「総手数料が額面を超えない」検証（または最低決済額の検証）を必須ガードとする**（業務上の上限/下限キャップ自体は設けない）。違反時はエラーコード（`ConnectPaymentErrorCode` 系・`PAYMENT_C0xx`・`ERROR_CODE_STATUS_MAP` 登録）で拒否し、症状を隠さない。詳細は [02 §3.5.2](02_api_design.md) / [03 §3](03_security.md)。

### 3.5 返金（マスター確定: 設定A）

- **返金操作はチーム/組織の管理者（受取側 scope の ADMIN）が行う**。**Mannschaft 運営は関与しない**。返金 API `POST /api/v1/payment/escrow/{id}/refund` は**受取側 scope の ADMIN** に認可し、無関係 scope は 404 秘匿する（03 §3/§4）。
- Stripe Refund に **`reverse_transfer: true`**（返金額を受取側 Connect 残高から戻す＝Mannschaft 負担ゼロ）＋ **`refund_application_fee: false`**（徴収済み Mannschaft 手数料は返金しない＝設定A）を指定する。
- **自前の逆仕訳ロジックは作らない**（金を動かすのは Stripe）。`refunds` テーブルは記録専用、`ledger_entries`(REFUND) は監査追記のみ。
- **capture 前**（AUTHORIZED/HELD）は返金ではなく**与信取消**（`PaymentIntent.cancel()`・支払者課金なし）。
- **Stripe の決済手数料（3.6%）は返金時に返らない**（Stripe 仕様）＝消える。これを**利用規約＋決済画面の注意書き**で「決済手数料は返金されません」と明示して整理する（§3.5.1 / 03 / 04）。
- 受取側残高不足時のマイナス残高は **Stripe が自動回収**し（後続入金・口座引き落とし）、**Mannschaft には請求が来ない**。この運用注意を 03 §6 に記す。

#### 3.5.1 手数料非返還の明示（利用規約・決済画面）

返金しても **Stripe 決済手数料（額面の約3.6%相当）は戻らない**ため、以下で利用者に事前周知する:
- **決済確認画面**（カード入力直前）に「※ご返金が発生した場合でも、決済手数料は返金されません」を表示（04 §3.1）。
- **利用規約**に同旨を明記（03 §10）。

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
| 手数料 | `application_fee_amount`（手数料＋税） | **確定: 総5%＝支払者2.5%+受取側2.5%で折半**（案あ・§3.4）。`application_fee_amount=round(額面×0.05)`・純益≈1.31% |
| エスクロー期限 | 7 日タイマー + 6日22時間で自動 capture | **同一戦略**を踏襲（最終認証未了時の `DISPUTED`→先capture後返金） |
| QR チェックイン | あり（業務時間実測） | **市には不要**（役務の性質が異なる。市は最終認証 confirm のみ） |
| テーブル名 | `job_payments` / `stripe_connect_accounts`（BIGINT） | **新規 UUIDv7 テーブル**（`escrow_transactions` / `connect_accounts`）。F13.1 のテーブルは流用せず、市は独自に構築（CLAUDE.md 原則6・新規UUIDv7） |

> **テーブルは F13.1 と共有しない**。F13.1 のテーブルは未実装かつ BIGINT 設計のため、市は新規 UUIDv7 テーブルを `payment.escrow` に新設する。`source_kind=JOBMATCHING` を確保しておくことで、将来 F13.1 が本基盤を使う選択肢を残す（疎結合・01_data_model §2）。

---

## 6. 段階ロードマップ（統一基盤・正典更新）

| フェーズ | 範囲 | 規模 | 状態 | モデル | test-first |
|---|---|---|---|---|---|
| **P2-a 基盤** | `connect_accounts` / Connect onboarding（個人/チーム/組織）/ `stripe_webhook_events` 冪等性 / `ledger_entries` 骨格 / `StripePaymentProvider` Connect メソッド追加 | **L** | ✅ **完了（main 済）** | opus4.8 | ○ |
| **P2-b 共通送金サービス＋謝礼与信** | **共通 `ConnectChargeService`（2モード基盤）** / 応募成立時の与信（`incrementConfirmed()` フック）/ `escrow_transactions` AUTHORIZED / **手数料折半計算（`PaymentFeeCalculator`）** / 受領者 onboarding 未完了時の払出保留（HELD） | **M** | ✅ **完了（main 済・V73.003）** | opus4.8 | ○（与信→AUTHORIZED 状態遷移UT・手数料計算UT・保留フロー契約テスト先行） |
| **P2-c 謝礼払出＋返金** | 最終認証時 capture+transfer（`MarketFinalize` フック・札行ロック直下）/ **返金（受取側 ADMIN 操作・`reverse_transfer:true`/`refund_application_fee:false`）** / 与信取消 / エスクロー自動 capture バッチ | **L** | ✅ **完了（main 済）** | opus4.8 | ○（capture 原子性・二重払出防止・返金 reverse_transfer のUT先行） |
| **P2-e 会費 Connect 化** | 会費徴収（会員→チーム/組織）を本基盤に集約（`source_kind=MEMBERSHIP`・即時モード `capture_method=automatic`）。F08.2 のプラットフォーム集金から Connect ダイレクトチャージへ移行（即時 `charge()` は F08.9 P1 Wave0 で追加・main 済） | **M** | ✅ **P1 着手済（main 済・F08.9 §3）** | opus4.8 | ○（即時モード CAPTURED 状態UT・手数料折半UT先行） |
| **P2-f 手数料ランク化（`fee_policies`）** | `fee_policies` マスタ表（率%＋固定額¥）/ source_kind＋sub_key 割当・解決 / `PaymentFeeCalculator` 定数撤廃→policy 注入 / 安全ガード（総手数料≤額面）/ `escrow_transactions.fee_policy_key` 焼き付け（遡及防止）/ シスアド CRUD `/system-admin/fee-policies` | **M** | ✅ **完了（main 済・R1 #1326＝Entity/Repository/Resolver/Calculator/Flyway V74.007-009、R2 #1328＝シスアド CRUD/i18n）** | opus4.8 | ○（DEFAULT で既存テスト不変・固定額パターン UT・安全ガード境界 UT・遡及防止 UT 先行） |
| **P2-d フリマ／FE・E2E／parking・大会参加費 Migrate/Contract** | `source_kind=FLEAMARKET` 接続・FE（onboarding/受領主体/手数料内訳/受取額表示）・E2E・**parking の Connect 資産統合**・**F08.7.1 参加費の専用 `source_kind=TOURNAMENT` 移行＋旧・素 Checkout 経路の Migrate/Contract**（§3.0.1・Connect 決済自体は PR #1432/#1433 で実装済み・残るのは旧経路撤去と専用 source_kind 化）（**別軍議**） | ― | 別軍議（Connect 決済の Expand は完了・Migrate/Contract のみ残務） | FE=sonnet/opus・E2E=opus4.8 | △（BE 契約確定後・FE/E2E は後） |

> test-first 適用方針（memory `feedback_test_first_be_api`）: **BE 全フェーズ opus・BE ドメイン UT ＋ API 契約テストは実装より前に本設計から書く**。FE/E2E は後。各 Phase の規模は S/M/L で示す。

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
| [`F08.2_payments_access_control.md`](../../F08.2_payments_access_control.md) | **会費徴収は F22.1 統一 Connect 基盤（`ConnectChargeService`・`source_kind=MEMBERSHIP`・即時モード）へ移行予定**であることを相互参照で追記（現状はプラットフォーム集金・第三者受取からの撤去）。**本 PR で追記済**（§8.1） |
| [`F08.7.1_tournament_extensions/07_tournament_payment.md`](../../F08.7.1_tournament_extensions/07_tournament_payment.md) | 大会/リーグ参加費＝**第三者受取なのに素 Checkout＝暫定・後続で `source_kind=TOURNAMENT` で Connect 移行**の格下げ注記（§3.0.1）。F08.7.1 07 §0.1/§4 に反映済 |
| `docs/security/01_authorization_baseline.md` §3.6 | Connect Webhook は**既存 `POST /api/v1/webhooks/stripe/*`（1階層 `*`）許可で被覆済**であることを明記（`/webhooks/stripe/connect` は新規許可不要・§03_security §2） |

### 8.1 会費徴収（F08.2）の統一基盤移行

[F08.2 支払い・アクセス制御](../../F08.2_payments_access_control.md) は現状、会費・月謝等を **Mannschaft 自社の Stripe アカウントへプラットフォーム集金**する方式（`member_payments` + 自社 Checkout）。本統一基盤の確定により、**第三者受取**（会費＝会員→チーム/組織・参加費＝チーム→主催組織 等）は **P2-e で本 `ConnectChargeService`（`source_kind=MEMBERSHIP`/`TOURNAMENT`・即時モード・Connect ダイレクトチャージ）へ移行**し、**チーム/組織が直接受領**（自社口座非経由＝資金移動業リスク回避）する。移行時の `payment_items`/`member_payments` と `escrow_transactions` のマッピングは P2-e 軍議で確定する。

> **「全廃」の正確な意味＝第三者受取からの撤去**（§3.0）: F08.2 の素 Checkout を一律全廃するのではなく、**第三者受取のみ Connect へ移行**する。**Mannschaft 自社受取（F09.13 通知クレジット購入 等）は素 Checkout のまま残置**する。移行は Expand→Migrate→Contract（新レール並走→移行完了→旧レール撤去）の順とし一斉切替はしない。F08.7.1 大会参加費は第三者受取だが FE 実装済のため**暫定で素 Checkout 出荷を完遂し後続で Connect 移行**する（§3.0.1）。

> 📘 **会費側の上位設計**: `source_kind=MEMBERSHIP` を消費する会員決済の上位機能（払い手≠受益者・後見切替・ペイウォール・継続課金 Subscription＋invoice 上書き・協会→チーム請求・領収書/税からくり）は [F08.9 会員決済・後見つきマルチ受益者・ペイウォール・継続課金](../../F08.9_membership_billing_paywall/README.md) で設計する。本基盤（P2-b：`ConnectChargeService`/`PaymentFeeCalculator`/`face_amount`/`capture_mode`、P2-e：`EscrowSourceKind.MEMBERSHIP`）は F08.9 P1 の前提依存。継続課金で各 invoice の `application_fee_amount` を固定上書きする要件（率→固定額）と `connect_accounts` への税登録番号列追加も F08.9 由来。

### 8.2 parking の Connect 資産統合（別軍議）

既存の `parking`（駐車場予約決済）にも Connect 系の資産が存在する可能性があり、本統一基盤との**重複・統合は別軍議**とする。本設計では parking には踏み込まず、統一基盤側の `source_kind` 拡張余地を残すに留める。

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
- [x] **手数料率（確定: 案あ＝DEFAULT・2026-06-04 ランク化）** → **手数料はマスタ表 `fee_policies`（率%＋固定額¥）で持ち折半50/50固定**。DEFAULT＝率5%＋固定0円＝旧「総5%折半」と完全一致（後方互換）。他パターンはシスアドが source_kind＋sub_key で割当。少額決済の「総手数料>額面」破綻を防ぐ**安全ガード必須**。レートは escrow `fee_policy_key` に焼き付け遡及防止。具体例＝§3.4.3（DEFAULT）/§3.4.4（固定額入り）/ 01 §3.6 / 02 §3.5 / §11
- [x] **統一決済アーキ原則（確定: 受取人で二分）** → 第三者受取（チーム/組織/個人）は全て本 Connect レール／Mannschaft 自社受取（通知クレジット等）のみ素 Checkout 可（§3.0）。F08.2 素 Checkout 全廃＝第三者受取からの撤去（自社受取残置・Expand→Migrate→Contract）。F08.7.1 大会参加費は選手個人自払いの Connect 決済を実装済み（PR #1432/#1433・2026-06-10 main マージ）だが、チーム代表による旧・素 Checkout 経路も併存し Migrate/Contract は未完了（§3.0.1・2026-07-21 是正）
- [x] **返金の操作主体・方式（確定: 設定A）** → 受取側 scope ADMIN が操作（運営非関与）・Stripe `reverse_transfer:true`/`refund_application_fee:false`・決済手数料は返金されない（§3.5 / 03 §3）
- [x] **会費徴収の本基盤集約（確定）** → `source_kind=MEMBERSHIP`・即時モード・P2-e で F08.2 から移行（§1.0 / §8.1）

---

## 10. 関連ドキュメント

- [F22.1 市 本体](../README.md)（札の実体・最終認証・札行ロックの正典）
- [F13.1 短期業務マッチング](../../F13.1_short_term_job_matching/README.md)（資金フロー設計図の流用元・実装は未着手）
  - 特に [`03_ui_payment.md`](../../F13.1_short_term_job_matching/03_ui_payment.md) §8 Stripe Connect 統合
- [F08.7.1 大会費用支払い](../../F08.7.1_tournament_extensions/07_tournament_payment.md)（大会/リーグ参加費＝第三者受取・選手個人自払いの Connect 決済は実装済み（PR #1432/#1433）、チーム代表の旧・素 Checkout 経路も併存・専用 `source_kind=TOURNAMENT` 移行は未着手・§3.0.1）
- [F08.9 会員決済・ペイウォール・継続課金](../../F08.9_membership_billing_paywall/README.md)（`source_kind=MEMBERSHIP` の上位機能・払い手分離/サブスク/協会請求/立替記録）
- F03.11 募集機能（札の状態遷移・`payment_enabled`/`price` の出自）
- `docs/security/01_authorization_baseline.md`（permitAll 許可リスト・Webhook 署名検証）
- `docs/architecture/withdrawal_flow_immediate_anonymization_fix.md`（退会 PII 二段モデル）

---

## 11. 変更履歴

| 日付 | 変更内容 |
|------|---------|
| 2026-07-21 | **F08.7.1 大会参加費の記述を実装実態に合わせて是正（ドリフト修正・.md のみ）**。§3.0.1・§1.0 の出所表・§6 ロードマップ P2-d・§9 チェックリスト・§10 関連ドキュメント一覧を更新。旧記述「F08.7.1 は素 Checkout のまま・Connect は後続で移行」は 2026-06-04 時点のものだったが、**PR #1432（BE, `f9adaa2e3`）／PR #1433（FE, `121f6b71c`）が 2026-06-10 に main マージ済**で、選手個人自払いの Connect 決済（`MemberPaymentService.createConnectCheckout` 経由の Destination PaymentIntent）は既に実装されていた。統一アーキ原則（§3.0）に照らした「原則違反」は解消済みと訂正。ただし Expand→Migrate→Contract の Expand で停止しており、(1) チーム代表による旧・素 Checkout 経路の併存、(2) 専用 `source_kind=TOURNAMENT` が `EscrowSourceKind`/`escrow_transactions` CHECK に未定義（`fee_policy_assignments` 側 CHECK とは非対称・実態は `MEMBERSHIP` 相乗り・経緯不明）、(3) `PaymentFeeCalculator`（手数料ランク制）が大会参加費に未配線、の3点を残務として明記。F08.7.1 07 側にも同日付で対応する是正を反映済み |
| 2026-06-02 | 初版作成（軍議・家老偵察反映・2周精査完了・🟢設計確定/実装未着手）。案A（Destination Charge + 手動キャプチャ）採用。受領者を札ごとに個人/チーム/組織選択（`payee_kind`/`connect_accounts.scope_kind`）。既存 payment 資産を Connect 層で増築。F13.1 設計図を市向けに流用（受領主体が個人前提→札ごと選択へ差分化）。税務は税理士確認の別建て論点として明示 |
| 2026-06-04 | **統一決済アーキ原則の正典化＋手数料ランク化（マスター承認済・合同軍議/精緻化軍議）**。(A0) **受取人で二分する統一アーキ原則**（第三者受取＝全て Connect レール／Mannschaft 自社受取のみ素 Checkout 可・F08.2 全廃＝第三者受取からの撤去・Expand→Migrate→Contract）を §3.0 に正典化。F08.7.1 大会参加費＝第三者受取なのに素 Checkout＝暫定・後続 `source_kind=TOURNAMENT` で Connect 移行を §3.0.1 に明記（F08.7.1 07 §0/§4 へ格下げ注記）。(B0) **手数料を定数5%から `fee_policies` マスタ（率%＋固定額¥）のランク制へ**（§3.4）。折半50/50固定・DEFAULT=率5%＋固定0円（後方互換）・source_kind+sub_key 割当解決・少額破綻の安全ガード必須・`escrow_transactions.fee_policy_key` 焼き付けで遡及防止・シスアド CRUD。具体例＝§3.4.3（DEFAULT）/§3.4.4（固定額入り）。`source_kind` に `TOURNAMENT` 追加。ロードマップに P2-f（手数料ランク化）追加、P2-b/c 完了・P2-e P1 着手済を反映。ステータス＝🟢 設計確定（P2-a/b/c 完了・手数料ランク化は本設計で正典化・実装未着手） |
| 2026-06-05 | **P2-f 手数料ランク化を実装に追従（ドリフト修正・.md のみ）**。R1（#1326＝`FeePolicy`/`FeePolicyEntity`/`FeePolicyResolver`/`PaymentFeeCalculator` policy 注入・Flyway `V74.007`〜`V74.009`）＋ R2（#1328＝`SystemAdminFeePolicy(Assignment)Controller`/`FeePolicyAdminService`/i18n `system_admin_fee_policy.json`）main マージ済を反映。(1) **02 §7 エラーコード表を実 enum `ConnectPaymentErrorCode`（`PAYMENT_C0xx`）と `ERROR_CODE_STATUS_MAP` の実ステータスに一致**（欠落していた `C054`/`C055`/`C056` 追加・安全ガードは `C050` でなく `C060`/422＝`C050` は `STRIPE_API_ERROR`/500 と訂正・概念番号 `PAYMENT_0xx` を実コードへ統一）。(2) **01 §3.7 `fee_policy_assignments` に `organization_id` 列を追記し UNIQUE を実 DDL `(source_kind, sub_key, organization_id)` に訂正**（旧記載 `(…, deleted_at)` は誤り）。(3) 03/04 の安全ガード `C050`→`C060` 訂正・各所の概念番号を実コードへ。(4) **04 §7.1 i18n 骨子を実ファイル `system_admin_fee_policy.json`（`validation.*`/`assignmentCount`/`assignment.applyNote` 等）へ更新**。P2-f ロードマップ状態を ✅ 完了に更新。計算式・DEFAULT 保護・解決順・シスアド EP・バリデーションは既に設計＝実装一致を確認。F08.9（別セッション・#1315）との意味的整合確認済（編集せず） |
| 2026-06-03 | **統一決済プラットフォームモデルへ正典更新（マスター承認済）**。(A) 謝礼＋会費を共通 `ConnectChargeService`（2モード・即時/エスクロー）に集約、`EscrowSourceKind` に `MEMBERSHIP` 追加。(B) 手数料 5% を支払者2.5%・受取側2.5%で折半（案あ確定）・具体例表追加（§3.4.1）・Mannschaft 純益 ≈1.31%。(C) 返金は受取側 ADMIN 操作・`reverse_transfer:true`/`refund_application_fee:false`（設定A）・決済手数料非返還。(D) 決済確認画面の手数料内訳表示・受取側設定画面の受取額表示・i18n 6言語・利用規約明記。(E) ロードマップ更新（P2-a完了/P2-b共通送金+謝礼与信/P2-c謝礼払出+返金/P2-e会費Connect化/P2-d フリマ・FE・parking 別軍議）。(F) 旧「受取側が手数料全額負担」記述を 2.5%折半に訂正・F08.2/parking 相互参照追記。ステータス＝🟢設計確定（実装: P2-a完了/P2-b以降未着手） |
