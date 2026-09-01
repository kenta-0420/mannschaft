# F20.1 — 01 データモデル

> **ステータス**: 🟢 設計完了（マスター御裁可済・実装待ち／営利自動切替・オーナー変更は Phase 2 保留）
> **⚠️ Phase 2 保留（マスター 2026-07-08）**: 営利自動切替（org_type 自動更新）は初期スコープ外（README §3.3）。本書の中核テーブル（`entitlements`/`billing_contracts`/`feature_catalog`/`plans`/`plan_features`/`plan_price_bands`）は初期スコープに残る。ER 図の `organizations.org_type ◀ イベント` の結線のみ Phase 2。
> [README](README.md) の中核モデル（feature_key エンタイトルメント／プラン提示レイヤー／契約機構）を DB に落とす。

---

## 0. 設計原則（CLAUDE.md 準拠）

- **新規の業務テーブルは `UuidV7Entity` 継承（`BINARY(16)` 主キー・UUIDv7）**。ただし `UuidV7Entity` は **`id`（UUID）のみ提供**し `created_at`/`updated_at` を持たない（実コード確認済）ため、各 Entity で時刻列を自前定義する。
- **マスタ例外（CLAUDE.md 原則6 例外区分）**: `plans` / `plan_features` / `plan_price_bands` / `feature_catalog` は全テナント共通の参照データ（書き込みはシスアド運用のみ・シャーディング時は全シャードへコピー）ゆえ**自然キー**で設計し UUIDv7 を適用しない。設計書に「マスタ例外」と明記する（本節がその明記）。
- **クロスドメイン FK は作らない**。`scope_id`（users/teams/organizations）・`organization_id` は**論理参照（INDEX のみ）**。整合性はアプリ層で保証（§7）。
- **CASCADE は同一ドメイン内のみ**: billing ドメイン内の `plan_features → plans` のみ物理 FK＋CASCADE 可。それ以外は論理参照。
- **`organization_id` を持つ業務テーブルの扱い（escrow 前例に倣う）**: `entitlements`/`billing_contracts` は `organization_id` を **NULL 許容**で持ち、Repository は **`AbstractTenantAwareRepository<T, UUID>` を継承する**（`escrow_transactions`/`connect_accounts`/`fee_recovery_balances` の前例＝NULL 許容＋基底が要求する `deleted_at` 列を保持して適用）。**USER スコープ行は `organization_id=NULL` を許容**する（この場合テナント絞り込みメソッドの対象外になるだけで実害なし。USER スコープの照会は scope 系 finder を使う）。マスタ 4 表（§2）は `fee_policies` の前例（自然キー・`organization_id` 無し・非テナント Repository＝素の `JpaRepository`）に倣う。
- 予約語カラム回避（`year_month` 等は使わない）。`@Query` 内コメント禁止。

---

## 1. テーブル一覧

| テーブル | 主キー | 区分 | 役割 |
|---|---|---|---|
| `feature_catalog` | `feature_key`（自然キー） | マスタ例外 | 機能キーの台帳（分類・アドオン可否・単価・i18n キー） |
| `plans` | `plan_key`（自然キー） | マスタ例外 | 3 プランの提示定義 |
| `plan_features` | (`plan_key`,`feature_key`) 複合自然キー | マスタ例外 | プラン→機能の展開表 |
| `plan_price_bands` | (`plan_key`,`scope_kind`,`band_no`) 複合自然キー | マスタ例外 | 人数バンド別単価（機構のみ・実額 NULL 可） |
| `billing_contracts` | `BINARY(16)` UUIDv7 | 業務 | PLAN/ADDON 契約行（entitlements の発行元・履歴 append-only） |
| `active_contract_pointers` | `BINARY(16)` UUIDv7 | 業務 | アクティブ契約の一意性 DB 担保（H-1・§3.1.1） |
| `entitlements` | `BINARY(16)` UUIDv7 | 業務 | **権利の真実源**（1 行=1 スコープ×1 機能×1 発行元） |

> `beta_grants`（`source_kind=BETA_GRANT` の発行元）は [F20.3 01_data_model](../F20.3_beta_perks/01_data_model.md) で定義（同一 billing ドメイン・サブパッケージ `billing.beta`）。

---

## 2. マスタテーブル（自然キー・マスタ例外）

### 2.1 `feature_catalog`

```sql
CREATE TABLE feature_catalog (
    feature_key VARCHAR(64) NOT NULL COMMENT '機能キー（英小文字ドット区切り。例: reservation.notification_recipients_extended）',
    category VARCHAR(8) NOT NULL COMMENT 'INTERNAL=内向き機能（無料枠広い方針）/ REVENUE=収益機能（区分問わず有料）',
    addon_available BOOLEAN NOT NULL DEFAULT FALSE COMMENT '単品アドオン契約可か',
    addon_price_jpy INT UNSIGNED NULL COMMENT 'アドオン月額（円）。NULL=未定（実額はベータ終了時決定・機構のみ）',
    free_for_nonprofit BOOLEAN NOT NULL DEFAULT FALSE COMMENT '非営利スコープに無料開放するか（INTERNAL の無料枠の機構・値は運用設定）',
    display_name_key VARCHAR(128) NOT NULL COMMENT 'i18n 表示名キー（例: billing.features.reservation_notification_recipients_extended.name）',
    description_key VARCHAR(128) NOT NULL COMMENT 'i18n 説明キー（同 .description）',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '表示順',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'false=カタログ非表示＋isEntitled は常に false（fail-safe）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (feature_key),
    CONSTRAINT chk_fc_category CHECK (category IN ('INTERNAL','REVENUE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='機能カタログ（マスタ例外・自然キー）';
```

**初期シード（V146 系 seed migration・値はすべて運用変更可）**:

| feature_key | category | addon_available | addon_price_jpy | free_for_nonprofit | 備考 |
|---|---|---|---|---|---|
| `legacy.paid_plan_bundle` | INTERNAL | FALSE | NULL | FALSE | `hasPaidPlan` 互換ブリッジ（README §4.1） |
| `template.premium_modules` | INTERNAL | TRUE | 300 | FALSE | ModuleService `requiresPaidPlan` の正体 |
| `reservation.notification_recipients_extended` | INTERNAL | TRUE | 300 | FALSE | RESERVATION_029 ゲートの正体 |
| `ads.hide` | INTERNAL | TRUE | 300 | FALSE | F09.19 有料プラン広告非表示 |
| `monetization.paywall` | REVENUE | TRUE | 300 | FALSE（REVENUE は常に FALSE） | 収益機能の例（ペイウォール開設） |
| `monetization.membership_fee` | REVENUE | TRUE | 300 | FALSE（同上） | 収益機能の例（会費徴収の開設・F08.9 の入口側） |

- `free_for_nonprofit` の初期値は**全行 FALSE**（現行課金挙動と完全一致＝後方互換）。非営利無料枠の実開放はベータ計測後の運用判断。
- **REVENUE 行は `free_for_nonprofit=TRUE` にしてはならない**（「収益機能は区分問わず有料」の原則）。アプリ層バリデーション＋シスアド CRUD で拒否（02 §6）。DB CHECK は将来の運用変更余地を残すため付けない。

### 2.2 `plans`

```sql
CREATE TABLE plans (
    plan_key VARCHAR(32) NOT NULL COMMENT 'FREE / BASIC / FULL',
    display_name_key VARCHAR(128) NOT NULL COMMENT 'i18n キー（例: billing.plans.full.name）',
    description_key VARCHAR(128) NOT NULL COMMENT 'i18n キー（同 .description）',
    base_monthly_price_jpy INT UNSIGNED NULL COMMENT '基準月額（円・USER スコープ/バンド未定義時）。NULL=未定。FULL=2000 想定（実額はベータ終了時決定）',
    sort_order INT NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'false=新規契約不可（既存契約は維持）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (plan_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示プラン（マスタ例外・自然キー）';
```

初期シード: `('FREE', 'billing.plans.free.name', 'billing.plans.free.description', 0, 1, TRUE)` / `('BASIC', ..., NULL, 2, TRUE)` / `('FULL', ..., 2000, 3, TRUE)`。**BASIC の価格・構成は未定（README §8 R-3）**。

### 2.3 `plan_features`

```sql
CREATE TABLE plan_features (
    plan_key VARCHAR(32) NOT NULL,
    feature_key VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (plan_key, feature_key),
    CONSTRAINT fk_pf_plan FOREIGN KEY (plan_key) REFERENCES plans (plan_key) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='プラン→機能の展開表（マスタ例外）。fk は同一ドメイン内ゆえ CASCADE 可';
```

- `feature_key` への FK は張らない（feature_catalog 側の運用入替を妨げない・整合はシスアド CRUD のアプリ層検証）。
- 初期シード: `FREE` には既存の無料機能は**登録しない**（FREE 掲載＝ガード対象外の明示にのみ使う。ガードを新設した機能を後から FREE に開放する場合に行を足す）。`BASIC`/`FULL` に `legacy.paid_plan_bundle`＋各機能キーを登録。`FULL` は全 6 キー。

### 2.4 `plan_price_bands`（人数バンド・機構のみ）

```sql
CREATE TABLE plan_price_bands (
    plan_key VARCHAR(32) NOT NULL,
    scope_kind VARCHAR(8) NOT NULL COMMENT 'TEAM / ORG（USER は base_monthly_price_jpy を使用しバンド無し）',
    band_no TINYINT UNSIGNED NOT NULL COMMENT 'バンド番号（1〜・昇順）',
    min_members INT UNSIGNED NOT NULL COMMENT 'アクティブ人数下限（この値以上）',
    max_members INT UNSIGNED NULL COMMENT 'アクティブ人数上限（この値以下）。NULL=無制限（最終バンド）',
    monthly_price_jpy INT UNSIGNED NULL COMMENT '月額（円）。NULL=未定（実額はベータ終了時に実データで決定）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (plan_key, scope_kind, band_no),
    CONSTRAINT fk_ppb_plan FOREIGN KEY (plan_key) REFERENCES plans (plan_key) ON DELETE CASCADE,
    CONSTRAINT chk_ppb_scope CHECK (scope_kind IN ('TEAM','ORG'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人数バンド別単価（マスタ例外・機構のみ）';
```

- **アクティブ人数の正準**: 対象スコープの `memberships` で `scope_type = 'TEAM'|'ORGANIZATION'` かつ `scope_id = 対象ID` かつ **`left_at IS NULL`** の行数（`MembershipEntity.isActive()` と同一定義）。ORG は組織直下の memberships のみ数える（配下チームの人数は数えない・二重計上防止）。
- 初期シード（バンド割りは**例示・運用値**）: TEAM×各プランに `(1, 1, 20, NULL)` `(2, 21, 50, NULL)` `(3, 51, 100, NULL)` `(4, 101, NULL, NULL)`。価格は全 NULL（未定）。
- バンド重複・隙間はシスアド CRUD のアプリ層検証で防ぐ（`min_members = 前バンド max_members + 1` を強制・02 §6）。

---

## 3. 業務テーブル（UUIDv7）

> **2026-08-31 改訂**: `billing_customers`、`billing_price_versions`、`billing_quotes`、`billing_change_previews`、`billing_contract_changes`、`billing_contract_operations`、`billing_customer_migrations`、請求投影と既存表 ALTER は [05_billing_center.md §5](05_billing_center.md#5-完全-ddl-と-flyway) を正本とする。`psp_customer_ref` は scope-owned Customer の履歴参照であり、TEAM/ORG の Customer を操作者個人へ解決する実装は廃止する。

### 3.1 `billing_contracts`（PLAN/ADDON 契約行）

`entitlements(source_kind IN ('PLAN','ADDON'))` の発行元。**2026-07-10 実決済前倒し（D-1）**: 当初「ベータ中は決済を伴わない契約状態のみ」だったが、マスター御裁可により PSP 列を V151 で Expand 済み（下記 DDL は V150+V151 適用後の姿）。

```sql
CREATE TABLE billing_contracts (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    scope_kind VARCHAR(8) NOT NULL COMMENT 'USER / TEAM / ORG',
    scope_id BIGINT UNSIGNED NOT NULL COMMENT 'users.id / teams.id / organizations.id（論理参照・FKなし）',
    organization_id BIGINT UNSIGNED NULL COMMENT 'テナント。ORG=scope_id 自身 / TEAM=主所属組織（無所属は NULL）/ USER=NULL',
    contract_kind VARCHAR(8) NOT NULL COMMENT 'PLAN / ADDON',
    plan_key VARCHAR(32) NULL COMMENT 'contract_kind=PLAN のとき必須（論理参照・plans）',
    feature_key VARCHAR(64) NULL COMMENT 'contract_kind=ADDON のとき必須（論理参照・feature_catalog）',
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' COMMENT 'PENDING / ACTIVE / PAST_DUE / CANCELLED / EXPIRED（V151 で 5 値へ拡張）',
    member_count_snapshot INT UNSIGNED NULL COMMENT '契約時アクティブ人数スナップショット（TEAM/ORG のみ・memberships left_at IS NULL 数）',
    band_no_snapshot TINYINT UNSIGNED NULL COMMENT '契約時に解決した plan_price_bands.band_no（TEAM/ORG の PLAN のみ）',
    price_jpy_snapshot INT UNSIGNED NULL COMMENT '契約時単価スナップショット（円）。ベータ中=NULL（無償）。遡及防止の焼き付け（F22.1 fee_policy_key と同型）',
    contracted_at DATETIME(6) NOT NULL COMMENT '契約開始日時',
    cancelled_at DATETIME(6) NULL COMMENT '解約日時（無償=CANCELLED と同時／有償=期末解約予約と同時・status は ACTIVE のまま）',
    psp_customer_ref VARCHAR(255) NULL COMMENT 'Stripe Customer ID（scope-owned Customerの履歴参照。V196でbilling_customer_idへ正規化）',
    psp_subscription_ref VARCHAR(255) NULL COMMENT 'Stripe Subscription ID（sub_xxx・論理参照）。webhook 逆引きキー（V151、V196で255文字へ拡張）',
    current_period_end DATETIME(6) NULL COMMENT '現サイクル終了（valid_until 上限／期末解約の失効時刻・V151）',
    created_by BIGINT UNSIGNED NULL COMMENT '契約操作者（論理参照。シスアド手動付与時はシスアドの userId）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL COMMENT '論理削除（契約記録は原則物理削除しない）',
    PRIMARY KEY (id),
    KEY idx_bc_scope (scope_kind, scope_id, status),
    KEY idx_bc_org (organization_id),
    KEY idx_bc_plan (plan_key),
    KEY idx_bc_feature (feature_key),
    UNIQUE KEY uk_bc_psp_subscription (psp_subscription_ref),
    CONSTRAINT chk_bc_scope_kind CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_bc_contract_kind CHECK (contract_kind IN ('PLAN','ADDON')),
    CONSTRAINT chk_bc_status CHECK (status IN ('PENDING','ACTIVE','PAST_DUE','CANCELLED','EXPIRED')),
    CONSTRAINT chk_bc_kind_ref CHECK (
        (contract_kind = 'PLAN'  AND plan_key IS NOT NULL AND feature_key IS NULL) OR
        (contract_kind = 'ADDON' AND feature_key IS NOT NULL AND plan_key IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='PLAN/ADDON 契約（entitlements の発行元・PSP 非依存）';
```

- **契約の一意性は DB で保証する**（アプリ層 exists チェックだけでは TOCTOU レースで二重契約が作れるため）。§3.1.1 の「アクティブ契約ポインタ表」で物理担保する。`billing_contracts` 自体は**契約履歴（append-only）**として全行を残す（`status` を含む UNIQUE は CANCELLED→再契約の履歴を壊すので張らない）。
- **PSP 列**: `psp_customer_ref` / `psp_subscription_ref` / `current_period_end` は履歴/逆引き用に温存する。V196 で `billing_customer_id` と不変 `price_band_version_id` を追加する。親`billing_price_versions`はcatalog revision、Money/税/Stripe Priceを持つ子`billing_price_band_versions`を販売正本とし、以後の所有者・販売価格を正規化する（05 §5）。
- Repository: `BillingContractRepository extends AbstractTenantAwareRepository<BillingContractEntity, UUID>`（`organization_id` NULL 許容＋`deleted_at` 保持で適用・escrow 前例・§0）。

### 3.1.1 `active_contract_pointers`（契約一意性の DB バックストップ・H-1）

「アクティブな PLAN 契約は 1 スコープ 1 本」「アクティブな ADDON は 1 スコープ×1 feature_key」を**DB の UNIQUE で物理担保**する二層構造。履歴（何度契約/解約したか）は `billing_contracts` に残し、**現在アクティブなポインタ**だけを本表が持つ。

```sql
CREATE TABLE active_contract_pointers (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    scope_kind VARCHAR(8) NOT NULL COMMENT 'USER / TEAM / ORG',
    scope_id BIGINT UNSIGNED NOT NULL COMMENT '論理参照',
    contract_kind VARCHAR(8) NOT NULL COMMENT 'PLAN / ADDON',
    addon_feature_key VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'ADDON のとき対象 feature_key。PLAN のとき空文字（UNIQUE を1本化するため NULL でなく '''' 固定）',
    contract_id BINARY(16) NOT NULL COMMENT '現在アクティブな billing_contracts.id（論理参照・切替時に UPDATE）',
    organization_id BIGINT UNSIGNED NULL COMMENT 'テナント（billing_contracts と同値・参考列。検索はスロットキーで行う）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_acp_slot (scope_kind, scope_id, contract_kind, addon_feature_key),
    KEY idx_acp_contract (contract_id),
    KEY idx_acp_org (organization_id),
    CONSTRAINT chk_acp_scope_kind CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_acp_contract_kind CHECK (contract_kind IN ('PLAN','ADDON'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='アクティブ契約ポインタ（一意性のDB担保・履歴はbilling_contracts）';
```

> **⚠️ 本表は「論理削除（`deleted_at`）」規約の意図的な例外（①・実装トラップ）**: 解約時に `uk_acp_slot` スロットを**解放**して再契約を可能にするには、行を**物理 DELETE** しなければならない。**`deleted_at` セット（論理削除）で解約を書くと、行が残ったまま UNIQUE が効き続け、再契約が誤って `ENTITLEMENT_006`(409) で弾かれる**。そのため:
> - 本表は **`deleted_at` 列を持たない**（`billing_contracts`/`entitlements` の保持列規約に**倣わない**）。「アクティブなポインタだけを持つ現在状態表」であり、履歴・監査は `billing_contracts`（append-only・論理削除保持）が担う。
> - Repository は **`AbstractTenantAwareRepository` を継承しない**（同基底は `...DeletedAtIsNull` 派生と `deleted_at` 列を前提とし、物理 DELETE 運用と噛み合わない）。**素の `JpaRepository<ActiveContractPointerEntity, UUID>`** とし、検索はテナントでなく**スロットキー**で行う。専用メソッドを明示:
>   - `Optional<ActiveContractPointerEntity> findBySlot(String scopeKind, Long scopeId, String contractKind, String addonFeatureKey)`
>   - `@Modifying int hardDeleteBySlot(String scopeKind, Long scopeId, String contractKind, String addonFeatureKey)`（**物理 DELETE**・戻り値で削除件数を検証）
> - Entity は `UuidV7Entity` 継承（`id` のみ）＋`created_at`/`updated_at` 自前定義。`deleted_at`/`@SQLRestriction` は付けない。

**運用（擬似・02 §3.1 と対応）**:
- 契約作成: `billing_contracts` に ACTIVE 行を INSERT ＋ `active_contract_pointers` に INSERT。**`uk_acp_slot` が二重契約の並行 INSERT を物理拒否**（`DataIntegrityViolationException` → `ENTITLEMENT_006` 409）。TOCTOU レースはここで閉じる。
- プラン変更（切替）: `active_contract_pointers` の **`contract_id` を UPDATE**（旧契約 CANCELLED＋新契約 ACTIVE と同一トランザクション）。ポインタ行は増やさず付け替えるだけ。
- 解約: `hardDeleteBySlot(...)` で該当行を**物理 DELETE**（billing_contracts は CANCELLED で残す）。次回契約時に再 INSERT 可能になる。

**採用理由（`SELECT ... FOR UPDATE` 案との比較）**: 悲観ロック案は「スコープ単位のロック行」を別途要し、契約の無いスコープには初回ロック対象が無く（挿入意図ロックの取り回しが複雑）、ロック粒度・デッドロックの検討が要る。**UNIQUE 制約は初回契約から一貫して効き、実装が単純で、`fee_policies`/`membership_subscriptions` 等の既存「UNIQUE で冪等担保」パターンと同型**であるため本案を採る（memory の UNIQUE 冪等前例に整合）。

### 3.2 `entitlements`（中核・権利の真実源）

```sql
CREATE TABLE entitlements (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    scope_kind VARCHAR(8) NOT NULL COMMENT 'USER / TEAM / ORG（payment.connect.ScopeKind と同値）',
    scope_id BIGINT UNSIGNED NOT NULL COMMENT 'users.id / teams.id / organizations.id（論理参照・FKなし・INDEX）',
    feature_key VARCHAR(64) NOT NULL COMMENT 'feature_catalog.feature_key（論理参照）',
    source_kind VARCHAR(12) NOT NULL COMMENT 'PLAN / ADDON / BETA_GRANT',
    source_ref_id BINARY(16) NOT NULL COMMENT '発行元行: PLAN/ADDON=billing_contracts.id / BETA_GRANT=beta_grants.id（論理参照）',
    valid_from DATETIME(6) NOT NULL COMMENT '有効開始（含む）',
    valid_until DATETIME(6) NULL COMMENT '有効終了（含まない・半開区間）。NULL=無期限',
    revoked_at DATETIME(6) NULL COMMENT '取消日時。NOT NULL なら期間内でも無効',
    revoked_by BIGINT UNSIGNED NULL COMMENT '取消操作者（論理参照。システム自動取消は NULL）',
    organization_id BIGINT UNSIGNED NULL COMMENT 'テナント。ORG=scope_id / TEAM=主所属組織（無所属 NULL）/ USER=NULL',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL COMMENT '論理削除（通常運用では使わない。業務上の無効化は revoked_at。AbstractTenantAwareRepository 基底要求の保持列）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ent_grant (scope_kind, scope_id, feature_key, source_kind, source_ref_id, valid_from),
    KEY idx_ent_lookup (scope_kind, scope_id, feature_key, valid_until),
    KEY idx_ent_source (source_kind, source_ref_id),
    KEY idx_ent_org (organization_id),
    CONSTRAINT chk_ent_scope_kind CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_ent_source_kind CHECK (source_kind IN ('PLAN','ADDON','BETA_GRANT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='エンタイトルメント（権利の真実源・1行=1スコープ×1機能×1発行元）';
```

**設計判断（README の「精査の上確定」を確定）**:

1. **`source_ref_id` は NOT NULL**。すべての entitlement は発行元行（契約 or ベータ付与）を必ず持つ（シスアド手動付与も `billing_contracts` に契約行を起こしてから発行する）。これにより MySQL の「UNIQUE は NULL を distinct 扱いする」問題を回避し、UNIQUE 制約が実効になる。
2. **UNIQUE キーに `valid_from` を含める（限界を明示）**: F20.3 のチーム/組織特典は「2 年後の更新を**新しい entitlement 行の追加**で表現」する（同一 source_ref から複数期間の行が生じる）ため、（scope×feature×source×ref）だけでは更新行が弾かれる。`valid_from` を含めた 6 列 UNIQUE は「同一発行元から**同一開始時刻**の二重発行」しか防げず、`valid_from=now()` で発行する通常契約では実効性がほぼ無い（M-1 指摘）。**役割分担を明確化する**: (a) **アクティブ契約の一意性 = `active_contract_pointers` の UNIQUE（§3.1.1）が一次防御**、(b) **クライアント二重送信 = 契約作成 API の冪等トークン（`Idempotency-Key` ヘッダ・02 §0/§3.1・Phase 1 から必須）**、(c) `uk_ent_grant` は「同時刻の完全二重 INSERT」への最終 backstop（AC-21）。この 3 層で TOCTOU・二重押下・多重発行を塞ぐ。
3. **参照系 INDEX**: `idx_ent_lookup (scope_kind, scope_id, feature_key, valid_until)` が `isEntitled` の実行計画を担う（等値 3 列＋範囲 1 列）。`revoked_at` は選択率が低く INDEX に含めない。
4. **監査列**: `revoked_by` を追加（README の列定義＋監査要件。取消は運営操作・解約・退会処理のいずれかで、非否認のため操作者を残す）。
5. **DATETIME(6)**: README 指定どおりマイクロ秒精度（境界テスト AC-06 の「valid_until ちょうど」を秒精度の丸めで曖昧にしない）。
6. **`deleted_at` は基底要求の保持列**: 業務上の無効化は常に `revoked_at` で表現し、`deleted_at` は使わない（GDPR 例外運用のみ）。`AbstractTenantAwareRepository` の派生クエリ（`...DeletedAtIsNull`）が要求するため列だけ保持する（`fee_recovery_balances` 前例）。
- Repository: `EntitlementRepository extends AbstractTenantAwareRepository<EntitlementEntity, UUID>`（`organization_id` NULL 許容・escrow 前例・§0）。
- Entity: `EntitlementEntity extends UuidV7Entity`（`UuidV7Entity` は `id` のみ提供のため `created_at`/`updated_at`/`deleted_at` は自前定義・`@PrePersist`/`@PreUpdate`）。

### 3.3 判定クエリ（正準 JPQL 相当）

```sql
SELECT COUNT(*) > 0
FROM entitlements e
WHERE e.scope_kind = :scopeKind        -- enum は name() で String 化（feedback_cacheable_enum_key_redis と同方針）
  AND e.scope_id = :scopeId
  AND e.feature_key = :featureKey
  AND e.revoked_at IS NULL
  AND e.valid_from <= :now
  AND (e.valid_until IS NULL OR :now < e.valid_until);   -- 半開区間 [from, until)
```

### 3.4 アクティブ人数の数え方（正準・既存メソッド再利用・F20.3 と共通）

**独自 `COUNT(*)` を書かない**（M-6）。origin/main 実在の **`MembershipRepository.countActiveDistinctUsersByScope(ScopeType, Long)`** を再利用する（実 JPQL: `SELECT COUNT(DISTINCT m.userId) FROM MembershipEntity m WHERE m.scopeType = :scopeType AND m.scopeId = :scopeId AND m.leftAt IS NULL`）。**DISTINCT user_id** ゆえ、1 ユーザーが複数 membership 行を持っても二重計上しない（`COUNT(*)` だと過大になる）。F20.2 も同メソッド再利用で整合させる。

```
activeMemberCount(scopeKind, scopeId):
  TEAM → membershipRepository.countActiveDistinctUsersByScope(ScopeType.TEAM, scopeId)
  ORG  → membershipRepository.countActiveDistinctUsersByScope(ScopeType.ORGANIZATION, scopeId)
  USER → 常に 1
```

> `memberships` は多態 1 表（`scope_type ∈ {ORGANIZATION, TEAM}`・`role_kind ∈ {MEMBER, SUPPORTER}`・実コード確認済）。上記メソッドは `role_kind` を問わない総数（SUPPORTER も含む・ADMIN/DEPUTY も MEMBER 行を持つため含まれる）。**バンドの頭数はこの 1 定義に固定**して曖昧さを排除する（SUPPORTER 除外が必要ならベータ計測後の運用判断で別メソッドへ切替）。

---

## 4. 状態遷移

### 4.1 entitlement のライフサイクル

```
（発行）──▶ 予約中（valid_from > now）──(valid_from 到来)──▶ 有効
                                                        │
   有効 ──(valid_until 到来・半開区間ゆえ当日時刻ちょうどで失効)──▶ 期限切れ（行は残る・isEntitled=false）
   有効/予約中 ──(revoked_at セット)──▶ 取消（終端・復活しない）
```

- **行は UPDATE で復活させない**: 取消の取り消し・期間延長は**新しい行の発行**で表現する（append-only・監査可能・uk_ent_grant が同時刻二重発行を防ぐ）。
- **期限切れ行の掃除はしない**（履歴・計測の価値。パーティショニングは 1000 万ユーザー Phase 3 の検討事項・§8）。

### 4.2 billing_contract の状態機械（2026-07-10 実決済で 5 値へ拡張）

```
【無償フロー（価格 NULL・従来 P1）】
（契約 API）──▶ ACTIVE ──(解約 API)──▶ CANCELLED（終端・即時失効）

【決済フロー（価格設定済み・D-2/D-3/D-4）】
（契約 API）──▶ PENDING ──(invoice.paid)──▶ ACTIVE
   PENDING ──(checkout.session.expired / Checkout 生成失敗の補償)──▶ CANCELLED（pointer 解放・再挑戦可）
   ACTIVE  ──(invoice.payment_failed)──▶ PAST_DUE ──(invoice.paid)──▶ ACTIVE（回復）
   ACTIVE  ──(解約 API: cancel_at_period_end 予約・status は ACTIVE のまま cancelled_at セット)
           ──(customer.subscription.deleted)──▶ EXPIRED（終端・pointer DELETE＋残 revoke）
   PAST_DUE ──(customer.subscription.deleted: 再試行尽き)──▶ EXPIRED
```

- **無償** `ACTIVE → CANCELLED` 時、当該契約由来の entitlements（`source_ref_id = contract.id` かつ `revoked_at IS NULL`）を**同一トランザクションで全件 revoke**（AC-20/AC-36）。
- **有償解約（D-3）**: `cancel_at_period_end` を予約し、由来 entitlements の `valid_until` を `current_period_end` にセット（webhook 未達でも期末に自動失効する保険・半開区間・AC-35）。EXPIRED 確定は `customer.subscription.deleted` webhook。
- **PENDING では entitlements を発行しない**（入金確定＝`invoice.paid` で初めて発行・AC-32/33・05 §4）。
- **renewal の PAST_DUE は権利を触らない**（既存`current_period_end`まで利用可）。`invoice.payment_failed`では期間を延長せず、retryの`invoice.paid`だけが次periodを延長する。upgrade change invoiceのSCA失敗は05のREQUIRES_ACTIONであり、この遷移を流用しない（AC-37/05 §4）。
- プラン変更は `billing_contract_changes` のSagaで扱う。upgrade は `invoice.paid`、downgrade は翌月1日到達後の `customer.subscription.updated` で確定し、当月に旧権利を即時取消しない（05 §4/§5）。

---

## 5. hasPaidPlan ブリッジ（Migrate 段のデータ移行）

既存 `team_subscriptions` の ACTIVE×有料行を entitlements へブリッジする（README §4.1）。

```sql
-- V146 系 migration（擬似・実装時は INSERT ... SELECT で冪等に）
-- 1) ブリッジ契約行: 対象 = team_subscriptions WHERE status='ACTIVE' AND plan_type <> 'FREE'
INSERT INTO billing_contracts (id, scope_kind, scope_id, organization_id, contract_kind, plan_key,
                               status, contracted_at, created_by, created_at, updated_at)
SELECT /* UUIDv7 */, 'TEAM', ts.team_id, NULL, 'PLAN',
       'FULL',                -- PlanType{MODULE,PACKAGE,ORGANIZATION} はいずれも FULL へ写像（ベータ中の実害なし・要素マッピングは運用で見直し可）
       'ACTIVE', ts.created_at, NULL, NOW(6), NOW(6)
FROM team_subscriptions ts WHERE ts.status = 'ACTIVE' AND ts.plan_type <> 'FREE';
-- 2) entitlements: 上記契約行 × plan_features('FULL') を展開して発行（valid_from=契約時刻・valid_until=NULL）
```

- 開発中・本番データなしのため対象 0 件想定だが、**冪等な migration として用意**する（from-scratch 番人テストで空でも通る）。
- `PlanType{MODULE, PACKAGE, ORGANIZATION} → FULL` の写像は暫定（ベータ中は違いを提示しない）。Phase 2 で実プランに再写像。

---

## 6. ER 図（論理）

```
plans ──(FK/CASCADE)── plan_features            ┐ マスタ（自然キー・全シャード複製）
  └──(FK/CASCADE)── plan_price_bands            │
feature_catalog（plan_features とは論理整合）    ┘

billing_contracts（UUIDv7・PLAN/ADDON 契約・履歴 append-only）
  ├─(論理: contract_id)─ active_contract_pointers（UUIDv7・uk_acp_slot でアクティブ一意を DB 担保）
  └─(論理: source_ref_id)─ entitlements（UUIDv7・権利の真実源）
beta_grants（F20.3・UUIDv7）
  └─(論理: source_ref_id)─ entitlements（source_kind=BETA_GRANT）

entitlements.scope_id ─(論理)─ users / teams / organizations（クロスドメイン・FKなし）
organizations.org_type ◀─(イベント: RevenueFeatureActivatedEvent・クロスドメイン直接 UPDATE 禁止)─ billing  【Phase 2 保留・営利自動切替・初期スコープでは結線しない】
memberships（left_at IS NULL）─(論理・読取のみ)─ アクティブ人数解決
team_org_memberships（status=ACTIVE）─(論理・読取のみ)─ チームの営利/非営利導出
```

---

## 7. 整合性・アプリ層保証（クロスドメイン FK の代替）

物理 FK を張らない代わりにアプリ層で保証する不変条件:

1. `scope_id` は実在の user/team/organization（契約 API 実行時に存在＋所有権を検証・03 §2）。
2. アクティブ PLAN 契約は 1 スコープ 1 本（`ENTITLEMENT_006` 409・§3.1）。ADDON は scope×feature で 1 本。
3. `entitlements.source_ref_id` は実在の `billing_contracts` / `beta_grants` 行（発行は必ず契約/付与サービス経由・直接 INSERT 禁止）。
4. `feature_key` は `feature_catalog` に実在（契約・付与時に検証。カタログから `enabled=false` 化された機能は isEntitled が false に倒れる＝fail-safe）。
5. 契約 CANCELLED ↔ 由来 entitlements 全 revoke は同一トランザクション（宙ぶらりんの権利を残さない）。
6. `plan_features` の feature_key 整合・`plan_price_bands` のバンド連続性はシスアド CRUD のバリデーションで保証（02 §6）。

---

## 8. 非機能（1000 万ユーザー/シャーディング耐性・キャッシュ・後方互換）

- **シャードキー**: TEAM/ORG スコープ行は `organization_id` を必ず埋める（TEAM は主所属組織＝ACTIVE な `team_org_memberships` のうち最古の 1 件。無所属チームは NULL）。**USER スコープ行は `organization_id=NULL`**（escrow の「USER は主所属組織を記録」とは意図的に異なる: 個人契約は組織文脈を持たない権利であり、特定組織のシャードに縛ると退所で迷子になる。ユーザー系シャード（user_id ベース）に置く方針・全体は `docs/architecture/db_scalability.md` Phase 4 で一括確定）。判定クエリは常に（scope_kind, scope_id）等値であり、シャードルーティングは scope_id から一意に解決できる（クロスシャード JOIN なし）。
- **マスタ 4 表は全シャード複製**（マスタ例外の定義どおり）。
- **キャッシュ**: `isEntitled` は Valkey `@Cacheable(value = "entitlement:check", key = "#scopeKind.name() + ':' + #scopeId + ':' + #featureKey")`・TTL 60 秒（`RedisConfig` に個別登録。既定 30 分は取消反映が遅すぎる）。付与/取消/契約変更で `@CacheEvict`（全キー特定が困難な契約変更は cacheName 単位 `allEntries=true` で evict・発生頻度が低いため許容）。`teamPlan` キャッシュ（`TeamPlanService`）も同時 evict（README §4.1）。
- **後方互換**: `team_subscriptions`・`TeamPlanService.hasPaidPlan` シグネチャ・`RESERVATION_029`(402)・`TMPL_004` の挙動を変えない（AC-14/15）。`feature_catalog.free_for_nonprofit` 初期全 FALSE で現行課金挙動と完全一致。
- **段階拡張**: 実決済 PSP 連携は **2026-07-10 前倒し実施**（列 Expand=V151 済み・Checkout `Mode.SUBSCRIPTION` 自社受取・§3.1/§4.2）。scope-owned Customer、請求書・領収書、支払方法、日割り、変更・解約は 05 を正本とする。F22.1 連携だけは将来の検討対象である。

---

## 9. Flyway 計画

> **採番注意**: worktree 時点の全体最大 major は **V145**（`V145.20260707153053__alter_reservations_add_group_and_menu.sql`）。よって **V146 系で仮採番**するが、**major はマージ時に origin/main 全体の最大+1 で再確認・確定**する（memory `feedback_migration_version_collision`）。minor は**タイムスタンプ必須**（`date -u '+%Y%m%d%H%M%S'`・連番禁止・`FlywayTimestampNamingGuardTest` が機械的に拒否）。

| 版（仮） | 内容 |
|---|---|
| `V146.<ts1>__create_billing_master_tables.sql` | `feature_catalog` / `plans` / `plan_features` / `plan_price_bands` |
| `V146.<ts2>__create_billing_contracts.sql` | `billing_contracts`＋`active_contract_pointers`（§3.1.1・一意性 DB 担保） |
| `V146.<ts3>__create_entitlements.sql` | `entitlements` |
| `V146.<ts4>__seed_billing_master.sql` | §2 の初期シード（プラン 3 行・feature 6 行・plan_features・バンド例示行） |
| `V146.<ts5>__bridge_team_subscriptions_to_entitlements.sql` | §5 ブリッジ（冪等 INSERT...SELECT・対象 0 件でも成功） |
| （F20.3 側） | `beta_grants` ほかは [F20.3 01 §6](../F20.3_beta_perks/01_data_model.md) で採番 |

> **実採番結果**: P1 は **V150.20260710030424〜30428** の 5 本で main 済み。

| 版（実採番済み） | 内容 |
|---|---|
| `V151.20260710123257__expand_billing_contracts_psp.sql` | **実決済（D-1・2026-07-10）**: `billing_contracts` へ `psp_customer_ref`/`psp_subscription_ref`/`current_period_end` を ALTER 追加＋`uk_bc_psp_subscription`（webhook 逆引き）＋`chk_bc_status` を DROP→5 値（`PENDING`/`ACTIVE`/`PAST_DUE`/`CANCELLED`/`EXPIRED`）で再 ADD |
| `V196.20260831000000__expand_billing_center.sql`（仮番） | scope-owned Customer・価格版・請求投影・変更/移行 Saga・権限 catalog を追加し、`billing_contracts.psp_customer_ref` と `psp_subscription_ref` をともに `VARCHAR(255)` へ明示拡張する。マージ直前に Flyway 最大番号+1へ再採番する |

- **既存データ番人テストの要否**: P1（V150 系）は新規 CREATE＋シードのみで不要。**V151 は既存テーブルへの ALTER＋CHECK 置換**だが、既存行の status は全て旧 3 値内であり新 CHECK は旧値を包含する（列追加は全て NULL 許容・UPDATE 不要）ため安全。V196 の事前番人は `psp_customer_ref`/`psp_subscription_ref` の最大長が255以下、非NULL subscription ref の一意性、既存 status/金額が不変であることを検査し、不適合なら migration を停止する。適用テストは V151 既存行を投入して V196 後も参照値・status・履歴行数が不変であることを確認する。from-scratch 起動テストと `FlywayTimestampNamingGuardTest` 準拠。

---

## 10. GDPR・退会（イベント駆動・実在の仕組みに準拠）

- `entitlements`/`billing_contracts` は**契約記録**ゆえ物理削除しない（論理削除・匿名化方針）。
- **退会はイベント駆動**（M-4）: `UserWithdrawalService`（架空）ではなく、origin/main 実在の `WithdrawalRequestedEvent`（申請・猶予開始）／`AccountPurgedEvent`（物理削除・`AccountPurgeService` バッチ発火・各ドメイン `*PurgeEventListener`）を購読する。billing ドメインに **`BillingPurgeEventListener`（`@TransactionalEventListener`・REQUIRES_NEW）** を新設。
- **退会猶予との整合**（M-5・revoke は終端で復活不可）:
  - **申請（猶予開始）時**: USER スコープの契約・entitlements を revoke **しない**（撤回で復活できないため権利を維持）。`BillingPurgeEventListener` は `WithdrawalRequestedEvent` を**明示的に no-op で受ける**（何もしないことを 1 メソッドで表明・将来「猶予中は機能を一時抑止する」等の拡張余地を残すフック点・L5）。
  - **撤回（`WithdrawalCancelledEvent`）時**: 何もしない（権利維持のまま）。
  - **確定（`AccountPurgedEvent`）時**: USER スコープの ACTIVE 契約を `CANCELLED`＋`active_contract_pointers` 削除＋由来 entitlements revoke（撤回窓は閉じており復活不可で問題ない）。
- `created_by`/`revoked_by` は監査用 userId 論理参照で PII 非含有（表示名は都度解決・退会後は匿名表示）。
- 区分は CLAUDE.md PII 二段モデルの**猶予対象（強匿名化・30 日）側の purge タイミングで失効**（退会撤回窓を尊重）。金銭記録が生じる Phase 2 では保持義務を再整理する。
