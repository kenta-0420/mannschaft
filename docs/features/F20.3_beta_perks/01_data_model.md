# F20.3 — 01 データモデル

> **ステータス**: 🟢 設計完了（マスター御裁可済・実装待ち／営利自動切替・オーナー変更は Phase 2 保留）
> **⚠️ Phase 2 保留（マスター 2026-07-08）**: team オーナー変更の**自動イベント**購読（B-4）は初期スコープ外（README 冒頭 Phase 2 保留ブロック）。`beta_grants`・`review_flag` 列・`review_reason='MANUAL'`（手動フラグ）・審査解決フローは初期スコープに残る。`review_reason='OWNER_CHANGED'`（自動起点）は Phase 2。
> [README](README.md) の特典モデルを DB に落とす。権利の実体は F20.1 `entitlements`（[F20.1 01 §3.2](../F20.1_entitlement_billing/01_data_model.md)）であり、本書は**付与メタ（`beta_grants`）と付与条件マスタ（`beta_perk_criteria`）**のみを定義する。

---

## 0. 設計原則（CLAUDE.md 準拠）

- `beta_grants` は業務テーブル → **`UuidV7Entity` 継承（BINARY(16)・UUIDv7）**。`UuidV7Entity` は `id` のみ提供のため時刻列は自前定義。
- `beta_perk_criteria` は**マスタ例外**（全テナント共通・書き込みはシスアド運用のみ・全シャード複製）→ 複合自然キー（`beta_phase`,`grant_kind`）。
- **クロスドメイン FK なし**（scope_id・user_id は論理参照＋INDEX）。`beta_grants → entitlements` は同一 billing ドメインだが、entitlements 側の `source_ref_id` は多態参照（billing_contracts / beta_grants）のため物理 FK は張らない（論理参照・アプリ層保証）。
- `organization_id` NULL 許容＋`deleted_at` 保持で `AbstractTenantAwareRepository` を適用（escrow 前例・[F20.1 01 §0](../F20.1_entitlement_billing/01_data_model.md)）。
- パッケージは `com.mannschaft.app.billing.beta`（F20.1 と同一ドメインのサブパッケージ）。

---

## 1. `beta_grants`（付与メタ・完全 DDL）

```sql
CREATE TABLE beta_grants (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    grant_kind VARCHAR(12) NOT NULL COMMENT 'INDIVIDUAL（個人特典）/ TEAM_ORG（チーム・組織特典）',
    beta_phase TINYINT UNSIGNED NOT NULL COMMENT 'ベータ段階（1〜4。4=1万人規模）',
    scope_kind VARCHAR(8) NOT NULL COMMENT 'USER / TEAM / ORG。INDIVIDUAL は USER 固定・TEAM_ORG は TEAM/ORG（CHECK）',
    scope_id BIGINT UNSIGNED NOT NULL COMMENT 'users.id / teams.id / organizations.id（論理参照・FKなし）',
    organization_id BIGINT UNSIGNED NULL COMMENT 'テナント。ORG=scope_id / TEAM=主所属組織（無所属 NULL）/ USER=NULL',
    criteria_snapshot JSON NOT NULL COMMENT '付与時の実測値と閾値の焼き付け（例: {"activeDays":21,"requiredActiveDays":14,"membershipTenureDays":45,"requiredTenureDays":30,"evaluationWindowDays":60,"criteriaVersion":"2026-07-08T00:00:00"}）',
    active_member_count_snapshot INT UNSIGNED NULL COMMENT '付与時アクティブ人数（TEAM_ORG のみ・memberships left_at IS NULL 数。INDIVIDUAL は NULL）',
    granted_feature_keys JSON NOT NULL COMMENT '付与時に展開した feature_key 配列（plan_features(FULL) のスナップショット・例: ["ads.hide","template.premium_modules"]）',
    transferable BOOLEAN NOT NULL DEFAULT FALSE COMMENT '譲渡可否。常に FALSE（CHECK で物理固定）',
    review_flag BOOLEAN NOT NULL DEFAULT FALSE COMMENT '所有者変更等の兆候による審査待ちフラグ（true でも権利は有効のまま）',
    review_reason VARCHAR(32) NULL COMMENT 'OWNER_CHANGED / SUSPECTED_TRANSFER / MANUAL（review_flag=true のとき必須・アプリ層保証）',
    review_flagged_at DATETIME(6) NULL COMMENT 'フラグ設定日時',
    review_resolved_at DATETIME(6) NULL COMMENT '審査解決日時（問題なし）',
    review_resolved_by BIGINT UNSIGNED NULL COMMENT '審査解決者（シスアド userId・論理参照）',
    revoked_at DATETIME(6) NULL COMMENT '取消日時（終端・復活しない）',
    revoked_by BIGINT UNSIGNED NULL COMMENT '取消操作者（シスアド userId。退会等システム取消は NULL）',
    revoke_reason VARCHAR(64) NULL COMMENT '取消事由（TERMS_VIOLATION / ACCOUNT_TRANSFER / WITHDRAWAL / OTHER。revoked_at とセットで必須・アプリ層保証）',
    granted_at DATETIME(6) NOT NULL COMMENT '付与日時（チーム/組織特典の valid_until 起点）',
    granted_by BIGINT UNSIGNED NULL COMMENT '付与操作者（シスアド userId。自動付与バッチは NULL=SYSTEM）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL COMMENT '論理削除（通常は使わない。業務上の無効化は revoked_at。基底要求の保持列）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bg_scope_phase (scope_kind, scope_id, beta_phase),
    KEY idx_bg_scope (scope_kind, scope_id),
    KEY idx_bg_review (review_flag, review_flagged_at),
    KEY idx_bg_phase (beta_phase, grant_kind),
    KEY idx_bg_org (organization_id),
    CONSTRAINT chk_bg_grant_kind CHECK (grant_kind IN ('INDIVIDUAL','TEAM_ORG')),
    CONSTRAINT chk_bg_phase CHECK (beta_phase BETWEEN 1 AND 4),
    CONSTRAINT chk_bg_scope_kind CHECK (scope_kind IN ('USER','TEAM','ORG')),
    CONSTRAINT chk_bg_kind_scope CHECK (
        (grant_kind = 'INDIVIDUAL' AND scope_kind = 'USER') OR
        (grant_kind = 'TEAM_ORG'  AND scope_kind IN ('TEAM','ORG'))
    ),
    CONSTRAINT chk_bg_not_transferable CHECK (transferable = FALSE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ベータ特典の付与メタ（権利実体は entitlements source_kind=BETA_GRANT）';
```

**設計判断**:

1. **`uk_bg_scope_phase`**: 同一スコープ×同一フェーズの二重付与を物理防止（AC-10）。取消後の再付与（同フェーズ）は**同一行を復活させず不可**とする（取消は終端。誤取消の救済は次フェーズ付与 or シスアドの契約系手動付与で代替・02 §4）。
2. **`chk_bg_not_transferable`**: `transferable` は列として持ちつつ CHECK で FALSE 固定（AC-06）。「譲渡不可が仕様である」ことをスキーマ自体に語らせる（将来緩和する場合は CHECK の DROP が明示的な設計変更点になる）。
3. **`granted_feature_keys` JSON**: 付与時の FULL 構成スナップショット。entitlements 行と冗長だが、「付与時に何を渡したか」の監査を 1 行で自己完結させる（entitlements の後続 revoke/延長で行が増えても原本が分かる）。
4. **`criteria_snapshot` JSON**: 実測値・閾値・評価ウィンドウ・criteria 版時刻を焼き付け（README §2）。
5. **review 列は本体に持つ**（別テーブルにしない）: 審査は 1 grant につき高々 1 状態（フラグ→解決 or 取消）であり、履歴テーブルの複雑さに見合わない。複数回のフラグ履歴は `audit_logs` が担う（03 §5）。

- Repository: `BetaGrantRepository extends AbstractTenantAwareRepository<BetaGrantEntity, UUID>`。
- Entity: `BetaGrantEntity extends UuidV7Entity`（時刻列自前定義）。

---

## 2. `beta_perk_criteria`（付与条件マスタ・完全 DDL）

```sql
CREATE TABLE beta_perk_criteria (
    beta_phase TINYINT UNSIGNED NOT NULL COMMENT 'ベータ段階（1〜4）',
    grant_kind VARCHAR(12) NOT NULL COMMENT 'INDIVIDUAL / TEAM_ORG',
    evaluation_window_days INT UNSIGNED NOT NULL DEFAULT 60 COMMENT 'activeDays の評価ウィンドウ（日）',
    min_active_days INT UNSIGNED NULL COMMENT 'アクティブ日数の下限。NULL=この指標を評価しない（F10.8 実装前は NULL 運用・README §8）',
    min_membership_tenure_days INT UNSIGNED NULL COMMENT '所属経過日数の下限。NULL=評価しない',
    min_active_members INT UNSIGNED NULL COMMENT 'アクティブ人数の下限（TEAM_ORG のみ意味を持つ）。NULL=評価しない',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'false=このフェーズ×種別の付与を停止（自動バッチ・手動とも）',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (beta_phase, grant_kind),
    CONSTRAINT chk_bpc_phase CHECK (beta_phase BETWEEN 1 AND 4),
    CONSTRAINT chk_bpc_kind CHECK (grant_kind IN ('INDIVIDUAL','TEAM_ORG'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ベータ特典の付与条件（マスタ例外・複合自然キー・閾値は運用値）';
```

- **全指標 NULL 可＝「機構として指標を固定し、有効化は運用」**。ただし**全指標 NULL の行は「無条件付与」になるため、シスアド CRUD で最低 1 指標の NOT NULL を強制**する（「参加しただけで付与」をマスタ設定ミスで起こさない・02 §6 バリデーション。`BETA_PERK_009` 400）。
- 初期シード（8 行=4 フェーズ×2 種別・値は**例示・運用値**）: INDIVIDUAL=`(window=60, min_active_days=NULL, min_tenure=30, min_members=NULL)`／TEAM_ORG=`(window=60, NULL, 30, 5)`。`min_active_days` は F10.8 実装後に運用で設定。
- **2026-07-28 追記**: マスター御裁可（活動日数 14 日／評価ウィンドウ 60 日）に基づき、`V169.20260728050056__update_beta_perk_criteria_min_active_days.sql` で **`INDIVIDUAL` 行のみ `min_active_days` に 14 を投入済み**（`TEAM_ORG` は引き続き `NULL` 運用のまま — 活動日数は個人指標であり判定サービスも INDIVIDUAL のときのみ参照するため）。既に運用で値が入っている行は上書きしない（`WHERE min_active_days IS NULL` 条件つき）。ただし `mannschaft.beta.auto-grant.enabled` の既定は `false` のため、この値を入れても本番で自動付与が走るわけではない（本番有効化には規約第 27 条の弁護士レビュー＋マスター承認が別途必要）。
- Repository: `BetaPerkCriteriaRepository extends JpaRepository<BetaPerkCriteriaEntity, BetaPerkCriteriaId>`（複合キー・`@IdClass`。非テナント・`fee_policies` 前例）。

---

## 3. entitlements との連結（発行規約）

付与 1 件は次の行群を**単一トランザクション**で生成する:

```
grantBetaPerk(grantKind, betaPhase, scopeKind, scopeId, operator|SYSTEM):
  # 1) 付与メタ
  beta_grants 1 行（§1。criteria_snapshot / granted_feature_keys / member スナップショット焼付）
  # 2) 権利実体（F20.1 の発行サービスを呼ぶ・直接 INSERT しない）
  for featureKey in plan_features('FULL') at 付与時点:
      entitlements 1 行:
        scope_kind = scopeKind, scope_id = scopeId, feature_key = featureKey
        source_kind = 'BETA_GRANT', source_ref_id = beta_grants.id
        valid_from  = granted_at
        valid_until = NULL                          （INDIVIDUAL・「サービス提供期間中無償」）
                    | granted_at + 2年               （TEAM_ORG・下限。AC-04）
        organization_id = beta_grants と同値
  # 3) 称号バッジ（INDIVIDUAL のみ・§5）／ 4) 通知 ／ 5) キャッシュ evict（F20.1 02 §8・scope 単位）
  # ★H-5: RevenueFeatureActivatedEvent は発火しない（付与は運営の無償配布で団体の商用行動ではない）。
  #        REVENUE 機能を含む FULL 構成を渡しても org_type は変化させない（F20.1 02 §7・AC-22b 否定）。
```

- **取消**: `beta_grants.revoked_at/revoked_by/revoke_reason` セット＋ `entitlements WHERE source_kind='BETA_GRANT' AND source_ref_id=:grantId AND revoked_at IS NULL` を全件 revoke（同一トランザクション・AC-09）。
- **延長（2 年後の更新・自動更新しない）**: シスアドの延長操作が**新しい entitlement 行**（`valid_from=旧 valid_until`・`valid_until=＋指定期間`・同一 `source_ref_id`）を発行する（AC-14）。F20.1 `uk_ent_grant` が `valid_from` を含むため延長行は挿入可能・同時刻二重延長は物理拒否（[F20.1 01 §3.2 設計判断 2](../F20.1_entitlement_billing/01_data_model.md) はこの要件のためにある）。

---

## 4. 状態遷移

### 4.1 beta_grant ライフサイクル

```
（付与）──▶ 有効（revoked_at IS NULL）
   有効 ──(シスアド手動 flag-review〔初期スコープ〕 / オーナー変更イベント〔Phase 2 保留・B-4〕 / 検知)──▶ 有効＋review_flag=true（権利は有効のまま・AC-08）
   有効＋review ──(審査: 問題なし resolve)──▶ 有効（review_flag=false・resolved_at/by 記録・AC-20）
   有効／有効＋review ──(取消 revoke)──▶ 取消（終端・entitlements 同時 revoke・AC-09）
   有効（TEAM_ORG）──(valid_until 到来・entitlements 側で自然失効)──▶ 満了（grant 行は有効のまま履歴として残る。延長で復活可・AC-14）
```

- **「満了」は beta_grants の状態ではない**（grant は付与事実の記録。失効は entitlements の `valid_until` が担う）。`isEntitled=false` になるのは entitlements 側の期限切れ/取消のみ。
- review_flag の再フラグ（resolve 後に再度オーナー変更）は可（`review_flag` 再 true・履歴は audit_logs）。

### 4.2 review_flag フロー（運営運用）

```
OWNER_CHANGED（自動: チームオーナー変更イベント・02 §5）【Phase 2 保留・B-4】
SUSPECTED_TRANSFER（自動: 将来の検知拡張用の予約値）      →  review_flag=true
MANUAL（シスアド手動 flag-review・初期スコープ）              review_reason / review_flagged_at
        │
        ├─ 問題なし → resolve（フラグ解除・権利連続）
        └─ 違反確認 → revoke（revoke_reason='ACCOUNT_TRANSFER' 等）→ 権利即失効
```

---

## 5. 称号バッジのシード（F04.7 流用・DDL ではなくデータ・実 enum 照合済み）

`badges` へ system badge 1 行を Flyway シード（`V11.053__create_badges.sql` 様式・INSERT のみ）。**`badge_type` に enum 新値は足さない**（`BadgeType` の実値は `{STANDARD, MILESTONE, SPECIAL}` のみ・README §4 で訂正済み）。ベータ称号は `badge_type='SPECIAL'` の badges **行**として識別する。

| 列 | 値 | 備考 |
|---|---|---|
| `scope_type` | `'PLATFORM'`（sentinel・**要裁可 B-5**） | NOT NULL。プラットフォーム横断 badge の前例なし → sentinel で置く方針。実装前に gamification の scope クエリ影響を確認 |
| `scope_id` | `0`（sentinel） | NOT NULL |
| `name` | `'ベータテスター'`（表示は i18n・04 §3 `betaPerks.badge.name`） | VARCHAR(100) |
| `badge_type` | `'SPECIAL'`（**実 enum 値**・VARCHAR(50)） | `BadgeType.SPECIAL` |
| `condition_type` | `'MANUAL'`（実在・自動条件エンジン対象外＝本機能のバッチ/API が授与） | VARCHAR(50) |
| `is_system` | `1`（TINYINT・論理削除不可の system badge） | |
| `is_active` | `1` | |
| `icon_emoji` | `'🚀'`（仮・FE 確認で確定・VARCHAR(10)） | |

- 授与は `user_badges` INSERT（`awarded_by='SYSTEM'`・`period_label='BETA_PHASE_1'〜'BETA_PHASE_4'`・VARCHAR(20) に収まる）。`uq_ub_badge_user_period (badge_id, user_id, period_label)` が同フェーズ二重授与を物理防止（AC-11）。
- **50 バッジ上限・scope クエリへの波及（B-5・実装前確定）**: badges は本来チーム/組織スコープの機構であり、sentinel PLATFORM scope で置くと既存の `findBy...ScopeTypeAndScopeId` 系クエリ・50 バッジ上限カウント・表示各所に波及しうる。**実装前に gamification ドメインの scope 取り扱いを再確認**し、上限カウント対象なら除外条件を追加する（既存機構を壊さない）。バッジ授与の失敗は特典付与本体をロールバックせず補助チャネルとして握って継続する（付与＝entitlements 発行が本体）。

---

## 6. Flyway 計画

> **採番注意**: F20.1 と同じく **V146 系仮採番**（worktree 時点の全体最大 V145）。**major はマージ時に origin/main 全体の最大+1 で確定**・minor はタイムスタンプ必須（`FlywayTimestampNamingGuardTest`）。F20.1 の migration（[F20.1 01 §9](../F20.1_entitlement_billing/01_data_model.md)）より**後のタイムスタンプ**を採る（`beta_grants` の発行規約が `plans`/`plan_features` シードに依存するため順序保証）。

| 版（仮） | 内容 |
|---|---|
| `V146.<ts6>__create_beta_grants.sql` | `beta_grants`（§1） |
| `V146.<ts7>__create_beta_perk_criteria.sql` | `beta_perk_criteria`＋初期シード 8 行（§2） |
| `V146.<ts8>__seed_beta_tester_badge.sql` | `badges` へベータテスター system badge 1 行（`badge_type='SPECIAL'`・scope=PLATFORM/0・§5・INSERT のみ） |

- **既存データ番人テストの要否**: 既存テーブルへの ALTER なし・CHECK 追加なし（新規 CREATE＋INSERT のみ）→ **不要**。`badges` に UNIQUE 制約は無い（`INDEX idx_b_scope_active` のみ・実確認）ため、シードは `INSERT ... WHERE NOT EXISTS (SELECT 1 FROM badges WHERE scope_type='PLATFORM' AND scope_id=0 AND name='ベータテスター')` で**冪等**にする（from-scratch 番人テストで二重挿入を防ぐ）。
- F10.8 `content_type` への `FEATURE` 追加は **DDL 不要**（`VARCHAR(20)`・DB CHECK なし・アプリ enum 追加のみ。README §7）。

---

## 7. ER 図（論理）

```
beta_perk_criteria（マスタ・複合自然キー）─(読取)─ 付与判定（02 §2）
beta_grants（UUIDv7・付与メタ）
  ├─(論理: source_ref_id)─ entitlements（F20.1・source_kind=BETA_GRANT・権利実体）
  ├─(論理: scope_id)─ users / teams / organizations（クロスドメイン・FKなし）
  ├─(論理)─ user_badges（F04.7・BETA_TESTER・period_label=BETA_PHASE_n）
  └─(イベント購読)─ TeamOwnershipTransferredEvent（team ドメイン → review_flag・B-4）【Phase 2 保留・初期スコープでは購読しない】
page_view_logs（F10.8・content_type='FEATURE'・title=feature_key）─ 計測（README §7）
memberships（left_at IS NULL）─(読取)─ 人数スナップショット・tenure 判定
```

---

## 8. GDPR・退会・非機能（イベント駆動・退会猶予との整合）

- **退会はイベント駆動（実在の仕組みに合わせる）**: `UserWithdrawalService` は**架空**（存在しない）。origin/main の実体は `WithdrawalRequestedEvent`（退会申請＝猶予期間開始）→ 猶予後の物理削除で `AccountPurgedEvent`（`AccountPurgeService` バッチが発火・各ドメインの `*PurgeEventListener` が非同期処理）というイベント駆動である（`AuditLogEventListener`/`WithdrawalStripeHandler`/`GdprPurgeAuditBatchService` が前例）。本機能も **billing.beta ドメインに `BetaPerkPurgeEventListener`（`@TransactionalEventListener` / `@Async`・REQUIRES_NEW）を新設**して購読する。
- **退会猶予との整合（M-5・revoke は終端で復活不可のため）**:
  - **`WithdrawalRequestedEvent`（申請・猶予開始）受信時は grant を revoke しない**（revoke は終端で退会撤回時に復活できない）。猶予中は権利を維持する。個人特典の**新規自動付与のみ抑止**（退会申請中ユーザーは自動付与バッチの対象から除外・02 §3）。
  - **`WithdrawalCancelledEvent`（退会撤回）受信時**: 何もしない（grant は維持されたまま＝自動付与対象へ復帰）。
  - **`AccountPurgedEvent`（退会確定・物理削除）受信時**: 当該ユーザーの INDIVIDUAL grant を `revoked_at`（`revoke_reason='WITHDRAWAL'`・`revoked_by=NULL`）＋由来 entitlements を revoke する（この時点なら撤回窓は既に閉じており復活不可で問題ない・AC-19）。CLAUDE.md PII 二段モデルの「猶予対象（強匿名化・30 日）」側に整合。
  - `beta_grants` 行自体は**統計価値のため保持**（scope_id は匿名化方針に従い残置・`criteria_snapshot` は集計値のみで PII 非含有）。
- **チーム/組織の解散**: TEAM_ORG grant は scope 消滅で実質失効（isEntitled の scope が消える）。grant 行は履歴として残す（明示 revoke はバッチ掃き取り・二重防御）。
- **シャーディング**: F20.1 と同一方針（TEAM/ORG 行は `organization_id`・USER 行は NULL でユーザー系シャード・[F20.1 01 §8](../F20.1_entitlement_billing/01_data_model.md)）。`beta_grants` は低頻度書き込み（付与時のみ）でスケール懸念なし。
- **後方互換**: 既存テーブルへの変更ゼロ（badges への INSERT のみ）。F04.7・F10.8 の既存挙動を変えない。
