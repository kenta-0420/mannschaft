# F08.7.1 / 07: 大会費用支払い（F08.2 決済基盤 再利用）

> **ステータス**: 🟢 設計完了（決済レール＝**暫定で素 Checkout・後続で F22.1 Connect 移行**＝統一アーキ原則の確定により格下げ・§0.1）
> **最終更新**: 2026-06-04
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・トレーサビリティ
> - [F08.2_payments_access_control.md](../F08.2_payments_access_control.md) — **母体**。汎用決済基盤（`payment_items` / `member_payments` / `stripe_customers` / Stripe Checkout＋MANUAL / `team_access_requirements` / `content_payment_gates` / grace_period / webhook）。本書はこれを大会参加費として再利用する
> - [F22.1 統一決済プラットフォーム](../F22.1_market/payment/README.md) — **後続の Connect 移行先**（§0.1）。統一アーキ原則（受取人で二分・README §3.0）により大会参加費は第三者受取＝Connect レールが正

---

## 0.1 【正典注記・2026-06-04】決済レールの格下げ＝暫定（素 Checkout）→ 後続 Connect 移行

> **マスター承認済（合同軍議・統一決済アーキ原則確定）**。本書 §1・§4 の「F08.2 素 Checkout（主催組織の Stripe アカウント直課金）で集金し、Stripe Connect は対象外」という記述は、[F22.1 統一決済アーキ原則](../F22.1_market/payment/README.md#30-統一決済アーキ原則-受取人で二分する合同軍議確定正典)（受取人で二分する）に照らすと**原則違反**である:
>
> - 大会/リーグ参加費は **主催組織（＝第三者）受取**であり、統一アーキ原則では**第三者受取の集金は全て F22.1 Connect レール**（destination charge・受取側直接着金・資金移動業回避・手数料 application_fee）が正。素 Checkout（自社集金）は **Mannschaft 自社受取**（通知クレジット等）に限る。
> - したがって本書の「Stripe Connect は対象外」は**恒久方針ではなく、暫定（現行段階）の記述**として読み替える（**格下げ**）。
>
> **二段移行（マスター確定）**:
> 1. **暫定（現行・本書 §1〜§6）**: FE 実装済・E2E 進行中ゆえ、**素 Checkout のまま出荷を完遂**する（既存を壊さない）。本書 §4 の「新たな Stripe Connect 実装は対象外」はこの暫定段階に限る。
> 2. **後続（Connect 移行）**: 統一基盤の安定後、`source_kind=TOURNAMENT`（F22.1 README §3.0.1 / 01 §2 で確保）で本 Connect レールへ載せ替える。`tournament_fee` 連結はそのまま、money rail のみ `escrow_transactions(source_kind=TOURNAMENT)` へ差し替え、手数料はランク（`fee_policies`・F22.1 README §3.4）で `TOURNAMENT` パターンを割当可能とする。
>
> 移行自体は **F22.1 P2-d（別軍議）** で扱う。本書では「暫定の素 Checkout 出荷を完遂し、後続で Connect に載せ替える」方針のみを正典として記す。
> - [06_document_submission.md](./06_document_submission.md) — 書類提出受付（提出受理を「支払い済み」条件にゲートする連携元）
> - [01_communication.md](./01_communication.md) — 連絡スペース（参加チーム解決・代表ロール規則を共有）

本書は確定要件 ⑫（**大会費用支払い**＝既存 F08.2 決済基盤を再利用。大会参加費を payment_item として主催組織に紐付け、参加チーム代表が支払い）を具体化する。

---

## 1. 中核思想 — 汎用決済は新規構築しない（F08.2 が完成済み）

**汎用支払い機能は既存 F08.2 で完成済み**であり、本書では**新規の汎用決済基盤を一切作らない**。大会参加費を F08.2 の payment_item として扱う統合設計のみを定義する。

| 既存資産（F08.2） | 再利用方法 |
|------------------|-----------|
| `payment_items`（支払い項目・金額・通貨・Stripe Product/Price） | 大会参加費を 1 つの payment_item として作成 |
| `member_payments`（支払い実体・PAID/PENDING/REFUNDED/CANCELLED） | 各参加チームの支払い記録 |
| `stripe_customers` / Stripe Checkout（`POST /payment-items/{itemId}/checkout`） | 参加チーム代表のオンライン決済フロー |
| MANUAL 記録（現金・振込を主催者が手動記録） | オフライン入金の記録 |
| `team_access_requirements` / `content_payment_gates` | 未払い時のゲート（アクセス制御）を流用 |
| grace_period（`grace_period_days`） | 支払い猶予期間をそのまま使用 |
| webhook 署名検証・リコンサイル・返金（REFUNDED/CANCELLED） | 決済確定・返金・欠損リカバリを流用 |

- 大会への紐付けは薄い連結テーブル `tournament_fee`（tournament/division と payment_item を結ぶ）だけを新設する。
- **新たな Stripe Connect 実装は本設計の対象外**（§4 で明記。必要なら別軍議）。

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

### 3.2 支払い（参加チーム代表）

- 支払者＝**参加チーム代表（チーム ADMIN/DEPUTY）**。チーム単位の参加費を代表が支払う。
- オンライン＝F08.2 の `POST /payment-items/{itemId}/checkout` → Stripe Checkout を流用。
- オフライン＝主催者が MANUAL で入金記録（現金・振込）を F08.2 既存フローで登録。
- 決済確定は F08.2 の webhook（署名検証・冪等処理）で `member_payments` を PAID に更新。

### 3.3 未払い時のゲート（アクセス制御）

未払いチームには以下を「支払い済み」条件にゲートできる（F08.2 のアクセス制御を流用）:

- `team_access_requirements`（チーム全体ロック）
- **提出受理**（領域⑥ `tournament_submission_requirement.requires_payment=TRUE`）
- **エントリー確定**（`tournament_participants` の確定操作を支払い済み条件にする）

grace_period（`grace_period_days`）も F08.2 の既存機能で対応する。

---

## 4. 入金先・精算・Stripe Connect の扱い

> ⚠️ **暫定（§0.1 参照）**: 以下は**現行の暫定方式**である。統一アーキ原則（受取人で二分・F22.1 README §3.0）では大会参加費＝第三者受取ゆえ**後続で F22.1 Connect レール（`source_kind=TOURNAMENT`）へ移行**する。本書は暫定の素 Checkout 出荷を完遂し、移行は F22.1 P2-d（別軍議）で扱う。

- 入金先＝**主催組織の Stripe アカウント**（F08.2 の org 決済と同様・**暫定**）。後続では主催組織の Connect 口座へ destination charge で直接着金（自社口座非経由＝資金移動業回避）に移行する。
- クロス組織の精算は F08.2 既存の枠内（MANUAL 記録 or 主催 org の Stripe）で対応する（暫定）。
- **新たな Stripe Connect 実装は本設計の対象外**（**暫定段階に限る**・§0.1）。統一アーキ原則の確定により、参加費の Connect 移行は「対象外」ではなく「**後続で必ず移行**」が正典方針。協会間の自動分配等が必要になった場合も同 F22.1 基盤上で扱う。

---

## 5. 返金・キャンセル

- 返金・キャンセルは F08.2 の `member_payments` ステータス（REFUNDED / CANCELLED）と返金 API（`POST /payments/{paymentId}/refund`）・`charge.refunded` webhook をそのまま流用する。
- 大会中止・チーム辞退時の返金も F08.2 の既存フロー（全額／部分返金・監査ログ）で対応する。本書で新規の返金ロジックは作らない。

---

## 6. 認可・セキュリティ

| 操作 | 許可ロール |
|------|-----------|
| 参加費（tournament_fee）の作成／更新／削除 | 主催組織 ADMIN / SYSTEM_ADMIN |
| 自チーム分の支払い（checkout） | **自チーム ADMIN/DEPUTY のみ**（対象チームに限る） |
| MANUAL 入金記録 | 主催組織 ADMIN（F08.2 の `recorded_by` は JWT から自動設定） |
| 支払い状況の閲覧 | 自チーム分＝当該チーム ADMIN/DEPUTY ／ 全件＝主催組織 ADMIN |
| 返金 | 主催組織 ADMIN（F08.2 の MANAGE_PAYMENTS に準拠） |

- 支払いの実処理（checkout・webhook・返金）は **F08.2 の既存認可・冪等処理に委譲**する。本書の新設 API は「大会スコープのファサード（fee とのひも付け）」に留める。
- 自チーム以外の支払いを操作できない（他チーム分 checkout は 403）。存在しない fee / tournament は **404**（IDOR 統一）。
- `payment_item_id` / `team_id` は ID 参照のみ（クロスドメイン FK なし／原則 1）。`@Transactional` が tournament ドメインと payment ドメインをまたぐファサードは越境 TODO を明記（原則 5）。
- **退会（O-4）**: `tournament_fee.created_by`（参加費を作成した主催組織 ADMIN の user_id）は**履歴・証跡として保持**＝CLAUDE.md 退会二段モデルの**強匿名化対象外**（NULL 化しない）。表示名のみ既存の匿名化に追従させる。支払者・入金記録者（F08.2 の `member_payments`/`recorded_by`）の扱いは F08.2 母体の方針に従う（本機能では変更しない）。

---

## 7. 精査ログ

### 7.1 1 回目
- **不備**: 参加費作成・支払い（STRIPE/MANUAL）・未払いゲート（アクセス制御／提出受理／エントリー確定）・返金を網羅。汎用決済は F08.2 を再利用し、薄い連結テーブルのみ新設。
- **セキュリティ**: 支払い＝自チーム ADMIN/DEPUTY のみ・返金＝主催組織 ADMIN・他チーム支払い操作は 403・404 統一・クロスドメイン FK なし・越境 TODO 明記（原則 5）。決済確定は F08.2 の webhook 署名検証・冪等処理に委譲。
- **ユーザビリティ**: F08.2 の checkout / MANUAL 記録 UI を流用し支払い摩擦最小。grace_period で猶予対応。
- **見落とし**: Stripe Connect を対象外と明記（マルチアカウント精算は別軍議）、金額・grace_period は F08.2 側で一元管理し二重管理を回避、領域⑥提出受理ゲート連携。
- **保守性**: 決済の汎用テーブルは新設せず F08.2 を再利用、新規は薄い連結 2 テーブル（UUIDv7／原則 6、子テーブルは同一ドメイン CASCADE／原則 2）。

### 7.2 未解決事項

**現時点でなし。**
