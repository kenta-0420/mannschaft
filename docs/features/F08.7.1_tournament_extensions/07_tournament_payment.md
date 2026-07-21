# F08.7.1 / 07: 大会費用支払い（F08.2 決済基盤 再利用 + F22.1 Connect 決済併用）

> **ステータス**: 🟡 実装乖離是正済（Connect 決済は実装済み・旧経路併存で Expand→Migrate→Contract の Expand 段階に停止・残務は §0.2）
> **最終更新**: 2026-07-21
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・トレーサビリティ
> - [F08.2_payments_access_control.md](../F08.2_payments_access_control.md) — **母体**。汎用決済基盤（`payment_items` / `member_payments` / `stripe_customers` / Stripe Checkout＋MANUAL / `team_access_requirements` / `content_payment_gates` / grace_period / webhook）。本書はこれを大会参加費として再利用する
> - [F22.1 統一決済プラットフォーム](../F22.1_market/payment/README.md) — 大会参加費の Connect レール母体（統一アーキ原則・受取人で二分・README §3.0）。§3.0.1 参照
> - [06_document_submission.md](./06_document_submission.md) — 書類提出受付（提出受理を「支払い済み」条件にゲートする連携元）
> - [01_communication.md](./01_communication.md) — 連絡スペース（参加チーム解決・代表ロール規則を共有）

---

## 0. 実装状況（2026-07-21 是正・実装との乖離を解消）

本書は 2026-06-04（commit `4afbe3762`）を最後に「決済レールは暫定の素 Checkout、Stripe Connect は本設計の対象外・後続で移行」という前提で書かれていたが、**その後 PR #1432（BE, commit `f9adaa2e3`, 2026-06-10 main マージ）／PR #1433（FE, commit `121f6b71c`, 同日）で Connect 決済（選手個人の自払いチェックアウト）が実装・main マージ済み**であり、上記前提はもはや誤りである。本節で実態を正とし、旧記述を置き換える。

### 0.1 Connect 決済（選手個人の自払い）は実装済み

- `TournamentFeeCheckoutController`（`backend/src/main/java/com/mannschaft/app/tournament/fee/TournamentFeeCheckoutController.java`）が以下の2エンドポイントを提供する:
  - `GET /api/v1/tournament-fees/my` — 認証ユーザー本人が対象の大会参加費一覧（支払い済みフラグ付き）
  - `POST /api/v1/tournament-fees/{feeId}/checkout` — 参加費の Connect 決済チェックアウト
- 実処理は `TournamentFeePaymentService.checkoutFee()`（`TournamentFeePaymentService.java:174-203`）が `MemberPaymentService.createConnectCheckout()` に委譲する。受益者・払い手はともに認証ユーザー本人（SELF・選手自払い）。`createConnectCheckout` は Destination PaymentIntent を発行し、主催組織の Stripe Connect 口座へ直接着金する（自社口座非経由）。
- FE 側も `frontend/app/pages/me/tournament-fees.vue`・`frontend/app/composables/useTournamentFeeApi.ts`（PR #1433）で一覧表示・チェックアウト導線を実装済み。
- よって「新たな Stripe Connect 実装は本設計の対象外」（旧 §1・§4 の記述）は**現在は誤り**。Connect は既に実装されている。

### 0.2 未完了の残務（Expand→Migrate→Contract の Expand で停止）

ただし上記の Connect 実装は「新レールを並走させる」Expand 段階に留まり、旧レールの Migrate（切替）・Contract（撤去）には進んでいない。以下3点が未解決の残務である。

1. **旧経路（素 Checkout・チーム代表による支払い）が併存している**
   `TournamentFeeController`（`TournamentFeeController.java:79`）の `POST /fees/{feeId}/teams/{teamId}/checkout`（自チーム ADMIN/DEPUTY_ADMIN による支払い）は、`TournamentFeeService.checkout()`（`TournamentFeeService.java:174`）経由で旧 `MemberPaymentService.createCheckout()`（素 Checkout・F08.2 母体そのまま）に委譲したままである。「チーム代表がチーム分をまとめて払う」旧フローと「選手個人が自分の分を Connect で払う」新フローが並存しており、統合・一本化はまだ行われていない。

2. **`source_kind=TOURNAMENT` が未定義（CHECK 制約が非対称）**
   `EscrowSourceKind`（`backend/src/main/java/com/mannschaft/app/payment/escrow/EscrowSourceKind.java`）に `TOURNAMENT` の値は存在せず、`RECRUITMENT` / `MEMBERSHIP` / `JOBMATCHING` / `FLEAMARKET` の4値のみである。`escrow_transactions.source_kind` の CHECK 制約（`V73.003__alter_escrow_transactions_add_face_amount_capture_mode_membership.sql:45`）も同じ4値のみで `TOURNAMENT` を含まない。
   一方、`fee_policy_assignments.source_kind` の CHECK 制約（`V74.008__create_fee_policy_assignments.sql:18`）には `TOURNAMENT` が既に含まれており、**2つの CHECK 制約が非対称**になっている。
   実際の大会参加費 Connect 決済は `ConnectChargeService`（`ConnectChargeService.java:612`）が `EscrowSourceKind.MEMBERSHIP` に固定して `escrow_transactions` へ記録しており、大会参加費は会費（`MEMBERSHIP`）に相乗りしている状態である。
   **この「`MEMBERSHIP` への相乗り」を選んだ経緯は、PR 本文・設計書・台帳のいずれにも記録がなく不明。** 意図的な暫定策と断定できる根拠はないため、本書では「経緯不明」とのみ記す。

3. **手数料計算（`PaymentFeeCalculator` 連携）が未配線**
   `TournamentFeePaymentService.getMyTournamentFees()` 内（`TournamentFeePaymentService.java:152`）で `payerSurcharge` は `0` 固定であり、コメントで「`PaymentFeeCalculator` 連携は今後対応」と明記されている。F22.1 の手数料ランク制（`fee_policies`）とはまだ接続されていない。

> 上記3点は「実装が壊れている」わけではなく、Expand→Migrate→Contract の Migrate/Contract フェーズが未着手という設計上の残務である。着手する場合は別軍議で扱う。

---

## 1. 中核思想 — 汎用決済は新規構築しない（F08.2 が完成済み・Connect は F22.1 基盤を再利用）

**汎用支払い機能は既存 F08.2（素 Checkout・チーム代表払い）と F22.1（Connect・選手個人自払い）で完成済み**であり、本書では**新規の汎用決済基盤を一切作らない**。大会参加費を F08.2 の payment_item として扱う統合設計と、F22.1 の `ConnectChargeService` を利用した個人自払いチェックアウトの2系統を定義する。

| 既存資産 | 再利用方法 |
|------------------|-----------|
| `payment_items`（支払い項目・金額・通貨・Stripe Product/Price） | 大会参加費を 1 つの payment_item として作成 |
| `member_payments`（支払い実体・PAID/PENDING/REFUNDED/CANCELLED） | 各参加チーム／各選手の支払い記録 |
| `stripe_customers` / Stripe Checkout（`POST /payment-items/{itemId}/checkout`） | 参加チーム代表のオンライン決済フロー（旧・素 Checkout） |
| `MemberPaymentService.createConnectCheckout`（F22.1 `ConnectChargeService` 経由） | 選手個人の Connect 自払いチェックアウト（新・§0.1） |
| MANUAL 記録（現金・振込を主催者が手動記録） | オフライン入金の記録 |
| `team_access_requirements` / `content_payment_gates` | 未払い時のゲート（アクセス制御）を流用 |
| grace_period（`grace_period_days`） | 支払い猶予期間をそのまま使用 |
| webhook 署名検証・リコンサイル・返金（REFUNDED/CANCELLED） | 決済確定・返金・欠損リカバリを流用 |

- 大会への紐付けは薄い連結テーブル `tournament_fee`（tournament/division と payment_item を結ぶ）だけを新設する。
- Connect 決済は本設計の対象外ではなく、**§0.2 で述べた残務を除き既に実装済み**である。

---

## 2. データモデル — 薄い連結テーブル `tournament_fee`

大会参加費（payment_item）を、どの大会／ディビジョンに、誰が、いつまでに支払うかを表す薄い連結テーブルのみを新設する。

```sql
-- 大会参加費（payment_item と tournament/division を結ぶ薄い連結。新規テーブル → UUIDv7 / 原則 6）
CREATE TABLE tournament_fee (
    id BINARY(16) NOT NULL,                  -- UUIDv7（UuidV7Entity 継承）
    tournament_id BIGINT NOT NULL,           -- 対象大会（tournament ドメインへの ID 参照）
    division_id BIGINT NULL,                 -- 対象ディビジョン（NULL = 大会全体）。tournament ドメインへの ID 参照
    payment_item_id BIGINT NOT NULL,         -- payment ドメインの payment_item への ID 参照（クロスドメイン FK なし／原則 1）
    title VARCHAR(255) NOT NULL,             -- 表示名（例「2026 春季リーグ 参加費」）
    target_scope ENUM('ALL_TEAMS', 'SPECIFIC_TEAMS') NOT NULL DEFAULT 'ALL_TEAMS',  -- 対象＝全参加チーム / 特定チーム
    payment_due DATETIME NULL,               -- 支払期限（NULL = 期限なし）。grace_period は F08.2 の payment_item 側を使用
    organization_id BIGINT NOT NULL,         -- 主催組織（入金先・テナント絞り込み）
    created_by BIGINT NOT NULL,              -- 作成した主催組織 ADMIN の user_id
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    deleted_at DATETIME NULL,                -- soft delete（履歴保持・クロスドメイン CASCADE なし／原則 2）
    PRIMARY KEY (id),
    INDEX idx_tournament_fee_tournament (tournament_id, division_id),
    INDEX idx_tournament_fee_org (organization_id)
);

-- 特定チームを対象にする場合の対象チーム明細（target_scope = SPECIFIC_TEAMS のとき。同一ドメインの子 → CASCADE 可／原則 2）
CREATE TABLE tournament_fee_target (
    id BINARY(16) NOT NULL,                  -- UUIDv7
    fee_id BINARY(16) NOT NULL,              -- 親 tournament_fee（同一ドメイン）
    team_id BIGINT NOT NULL,                 -- 対象チーム（team ドメインへの ID 参照／クロスドメイン FK なし）
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_tournament_fee_target_fee (fee_id),
    CONSTRAINT fk_tournament_fee_target_fee FOREIGN KEY (fee_id)
      REFERENCES tournament_fee (id) ON DELETE CASCADE
);
```

- `payment_item_id` / `team_id` / `tournament_id` / `division_id` は他ドメインへの **ID 参照のみ**（クロスドメイン FK なし／原則 1）。
- 金額・通貨・Stripe Product/Price・MANUAL/STRIPE 区分・grace_period は **すべて F08.2 の `payment_items` 側で管理**し、本テーブルは持たない（二重管理を避ける）。
- `organization_id` で主催組織に絞り込めるため、Repository は `AbstractTenantAwareRepository`（原則 7）の適用候補。

---

## 3. フロー

### 3.1 参加費の作成（主催組織）

1. 主催組織が「大会参加費」payment_item を F08.2 の既存 API（`POST /payment-items`）で作成（amount/currency、Stripe Product/Price 自動生成 or MANUAL）。
2. その payment_item を `tournament_fee` で大会／ディビジョンに紐付け（支払期限・対象チームを指定）。

### 3.2 支払い（2系統が併存・§0.2 ①）

大会参加費の支払いには、実装上2つの経路が併存している。

**A. チーム代表による支払い（旧・素 Checkout）**
- 支払者＝**参加チーム代表（チーム ADMIN/DEPUTY）**。チーム単位の参加費を代表がまとめて支払う。
- `POST /fees/{feeId}/teams/{teamId}/checkout`（`TournamentFeeController.java:79`）→ `TournamentFeeService.checkout()` → F08.2 の `MemberPaymentService.createCheckout()`（素 Checkout・主催組織の Stripe アカウントへ直課金）。

**B. 選手個人による自払い（新・Connect・§0.1）**
- 支払者＝**認証ユーザー本人**（受益者・払い手ともに SELF）。
- `GET /api/v1/tournament-fees/my` で自分が対象の参加費一覧を取得 → `POST /api/v1/tournament-fees/{feeId}/checkout`（`TournamentFeeCheckoutController.java`）→ `TournamentFeePaymentService.checkoutFee()` → `MemberPaymentService.createConnectCheckout()`（Destination PaymentIntent・主催組織の Stripe Connect 口座へ直接着金）。

- オフライン＝主催者が MANUAL で入金記録（現金・振込）を F08.2 既存フローで登録（両経路共通）。
- 決済確定は F08.2 の webhook（署名検証・冪等処理）で `member_payments` を PAID に更新（両経路共通）。

### 3.3 未払い時のゲート（アクセス制御）

未払いチームには以下を「支払い済み」条件にゲートできる（F08.2 のアクセス制御を流用）:

- `team_access_requirements`（チーム全体ロック）
- **提出受理**（領域⑥ `tournament_submission_requirement.requires_payment=TRUE`）
- **エントリー確定**（`tournament_participants` の確定操作を支払い済み条件にする）

grace_period（`grace_period_days`）も F08.2 の既存機能で対応する。

---

## 4. 入金先・精算・Stripe Connect の扱い

- **A. チーム代表払い（旧・素 Checkout）**: 入金先＝**主催組織の Stripe アカウント**（F08.2 の org 決済と同様）。
- **B. 選手個人自払い（新・Connect・§0.1）**: 入金先＝**主催組織の Stripe Connect 口座**（Destination PaymentIntent による直接着金・自社口座非経由）。ただし `source_kind` は `TOURNAMENT` 専用ではなく `MEMBERSHIP` に相乗りしている（§0.2 ②・経緯不明）。
- クロス組織の精算は F08.2 既存の枠内（MANUAL 記録 or 主催 org の Stripe）で対応する。
- 手数料の按分（application_fee・折半50/50 等）は F22.1 の `fee_policies` ランク制に未接続（§0.2 ③）のため、大会参加費固有の手数料設定はまだできない。

---

## 5. 返金・キャンセル

- 返金・キャンセルは F08.2 の `member_payments` ステータス（REFUNDED / CANCELLED）と返金 API（`POST /payments/{paymentId}/refund`）・`charge.refunded` webhook をそのまま流用する（旧・素 Checkout 経路）。
- Connect 経路（新・選手個人自払い）の返金フローが F08.2 既存 API と同一か、F22.1 側の別フロー（`reverse_transfer` 等）を使うかは**本改訂では未検証・不明**。着手時に別途確認すること。
- 大会中止・チーム辞退時の返金も既存フローで対応する。本書で新規の返金ロジックは作らない。

---

## 6. 認可・セキュリティ

| 操作 | 許可ロール |
|------|-----------|
| 参加費（tournament_fee）の作成／更新／削除 | 主催組織 ADMIN / SYSTEM_ADMIN |
| 自チーム分の支払い（checkout・旧・素 Checkout） | **自チーム ADMIN/DEPUTY のみ**（対象チームに限る） |
| 自分の参加費一覧取得・自払いチェックアウト（新・Connect） | **認証ユーザー本人のみ**（`SecurityUtils.getCurrentUserId()` で解決・他人分は操作不可） |
| MANUAL 入金記録 | 主催組織 ADMIN（F08.2 の `recorded_by` は JWT から自動設定） |
| 支払い状況の閲覧 | 自チーム分＝当該チーム ADMIN/DEPUTY ／ 全件＝主催組織 ADMIN |
| 返金 | 主催組織 ADMIN（F08.2 の MANAGE_PAYMENTS に準拠） |

- 支払いの実処理（checkout・webhook・返金）は F08.2／F22.1 の既存認可・冪等処理に委譲する。本書の新設 API は「大会スコープのファサード（fee とのひも付け）」に留める。
- 自チーム以外・他人分の支払いを操作できない（他チーム分 checkout・他人の参加費 checkout は 403）。存在しない fee / tournament は **404**（IDOR 統一）。
- `payment_item_id` / `team_id` は ID 参照のみ（クロスドメイン FK なし／原則 1）。`@Transactional` が tournament ドメインと payment ドメインをまたぐファサードは越境 TODO を明記（原則 5）。
- **退会（O-4）**: `tournament_fee.created_by`（参加費を作成した主催組織 ADMIN の user_id）は**履歴・証跡として保持**＝CLAUDE.md 退会二段モデルの**強匿名化対象外**（NULL 化しない）。表示名のみ既存の匿名化に追従させる。支払者・入金記録者（F08.2 の `member_payments`/`recorded_by`）の扱いは F08.2 母体の方針に従う（本機能では変更しない）。

---

## 7. 精査ログ

### 7.1 1 回目（2026-06-04 時点）
- **不備**: 参加費作成・支払い（STRIPE/MANUAL）・未払いゲート（アクセス制御／提出受理／エントリー確定）・返金を網羅。汎用決済は F08.2 を再利用し、薄い連結テーブルのみ新設。
- **セキュリティ**: 支払い＝自チーム ADMIN/DEPUTY のみ・返金＝主催組織 ADMIN・他チーム支払い操作は 403・404 統一・クロスドメイン FK なし・越境 TODO 明記（原則 5）。決済確定は F08.2 の webhook 署名検証・冪等処理に委譲。
- **ユーザビリティ**: F08.2 の checkout / MANUAL 記録 UI を流用し支払い摩擦最小。grace_period で猶予対応。
- **見落とし**: Stripe Connect を対象外と明記（マルチアカウント精算は別軍議）、金額・grace_period は F08.2 側で一元管理し二重管理を回避、領域⑥提出受理ゲート連携。
- **保守性**: 決済の汎用テーブルは新設せず F08.2 を再利用、新規は薄い連結 2 テーブル（UUIDv7／原則 6、子テーブルは同一ドメイン CASCADE／原則 2）。

> 上記「Stripe Connect を対象外と明記」は 2026-06-04 時点の記述であり、**2026-06-10 の PR #1432/#1433 マージ以降は誤り**。§0 参照。

### 7.2 未解決事項（2026-06-04 時点）

現時点でなし。

### 7.3 2026-07-21 是正時の検分

**不備**: 2026-06-04 更新後、PR #1432/#1433（2026-06-10 main マージ）で Connect 決済（選手個人自払い）が実装されたが、本書は追随更新されておらず「Connect は対象外」という誤った記述のまま放置されていた。本改訂で実態に合わせて是正した（§0）。

**未解決事項（残務・§0.2 参照。実装バグではなく Migrate/Contract 未着手）**:
1. 旧経路（チーム代表・素 Checkout）と新経路（選手個人・Connect）が併存し、未統合。
2. `source_kind=TOURNAMENT` が `EscrowSourceKind` / `escrow_transactions` の CHECK に未定義（`fee_policy_assignments` 側 CHECK とは非対称）。実態は `MEMBERSHIP` への相乗り（経緯不明）。
3. `PaymentFeeCalculator`（手数料ランク制）が大会参加費に未配線（`payerSurcharge` 固定 0）。
