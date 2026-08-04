# 1000万ユーザー耐久DB再構築 設計書

> 作成日: 2026-05-11  
> 対象ブランチ: 各 `feature/db-phase*` → `main` にマージ済み  
> 担当: kenta

---

## 概要

Mannschaft は将来 10万〜1000万ユーザー規模への拡大を見据え、**段階的なDB再構築**を実施した。
本ドキュメントはその Phase 1〜4 の設計・実装内容をまとめた Single Source of Truth である。

### 目標

| 目標 | 内容 |
|---|---|
| スケーラビリティ | 1000万ユーザー規模まで無停止で対応できるDB設計 |
| マイクロサービス分割対応 | 将来の分割コストを最小化するドメイン境界の物理的実施 |
| クエリ性能 | N+1 問題・フルスキャン・findAll() 乱用の根絶 |
| 運用コスト削減 | パーティショニングによる物理削除の瞬時化・アーカイブ戦略 |

### Phase 構成

| Phase | テーマ | 主要な Flyway バージョン |
|---|---|---|
| Phase 1 | ドメイン境界の物理的徹底 | V62.001〜V62.016 |
| Phase 2 | インデックス・クエリ性能 | V63.001 |
| Phase 3 | パーティショニング・アーカイブ戦略 | V64.001〜V64.006 |
| Phase 4 | 水平分割への布石 | V65.001〜V65.005 |

---

## Phase 1: ドメイン境界の物理的徹底

### 背景

設計当初から「クロスドメイン FK は作らない」という原則があったが、実装の積み重ねによりクロスドメイン FK が大量に蓄積していた。これらは将来のマイクロサービス分割の最大の障害となるため、段階的に除去を進めている。

> **⚠️ 進行中（2026-06-17 偵察実測値）**
>
> V62.001〜V62.016 の撤廃波で **408件** のクロスドメイン FK を撤廃済み。
> しかし撤廃は **未完了** であり、現在もクロスドメイン FK が **149件残存** している。
>
> | 残存 ON DELETE 種別 | 件数 |
> |---|---|
> | ON DELETE SET NULL | 77件 |
> | ON DELETE CASCADE | 37件 |
> | RESTRICT（明示） | 18件 |
> | 未指定（≒RESTRICT） | 17件 |
> | **合計** | **149件** |
>
> 参照先テーブル別では、`users` を参照するクロスドメイン FK が **80件**（残存全体の過半）を占める。
> 次点: `organizations` 11件、`teams` 11件。
>
> **物理削除のある経路はユーザー1経路のみ**（`AccountPurgeService.purgeUser()`）。
> チーム・組織は論理削除のみのため、それらを参照する CASCADE/SET NULL は現運用では
> 発火しないデッドコードだが、DDL 上は残存しており引き続き撤廃対象。
>
> **残存 FK の約 50% は無名制約**（CONSTRAINT 名なし）であり、撤廃には
> `INFORMATION_SCHEMA` または `SHOW CREATE TABLE` での実 DB 制約名特定が必要。

### 実施内容

#### 1-1. クロスドメイン FK 撤廃第一波（V62.001〜V62.016）— 408件撤廃済み、149件残存

異なるドメインのテーブル間の `FOREIGN KEY` 制約を削除し、`INDEX` のみに置き換えた。

**変更前（NG）:**
```sql
ALTER TABLE shift_assignments
  ADD CONSTRAINT fk_shift_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;
```

**変更後（OK）:**
```sql
-- FK 制約を削除
ALTER TABLE shift_assignments DROP FOREIGN KEY fk_shift_user;
-- インデックスのみ残す（参照整合性はアプリケーション層で保証）
CREATE INDEX idx_shift_assignments_user_id ON shift_assignments(user_id);
```

Wave 別の対象テーブル:

| Wave | 対象ドメイン群 |
|---|---|
| V62.001〜V62.005 | organization → user FK 群（org_members, org_roles 等） |
| V62.006〜V62.008 | team → organization FK 群（team_members, team_invites 等） |
| V62.009〜V62.011 | team → user FK 群（team_roles, team_join_requests 等） |
| V62.012〜V62.016 | その他クロスドメイン FK 群（shift_assignments, schedule 等） |

#### 1-2. クロスドメイン CASCADE DELETE の除去

同一ドメイン内の親子関係 CASCADE は維持し、クロスドメインの CASCADE DELETE を `RESTRICT` または `SET NULL` から FK 自体の削除に変更した。

**CASCADE DELETE を許可する条件（同一ドメイン内のみ）:**
```sql
-- OK: chatドメイン内（chat_channels → chat_messages）
FOREIGN KEY (channel_id) REFERENCES chat_channels(id) ON DELETE CASCADE

-- NG → 修正済み: scheduleドメイン → teamドメイン
-- FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE  ← 削除済み
```

#### 1-3. @Transactional クロスドメイン箇所への TODO コメント追加

各 Service のクロスドメイン @Transactional メソッドに将来のイベント駆動化候補として TODO コメントを追加した。

```java
@Transactional
// TODO: ScheduleドメインとUserドメインをまたいでいる。将来はUserUpdatedEventで分離予定
public void createSchedule(CreateScheduleRequest req, Long userId) { ... }
```

### 設計原則（再掲）

```
com.mannschaft.app.user/       ← userドメイン
com.mannschaft.app.team/       ← teamドメイン
com.mannschaft.app.schedule/   ← scheduleドメイン
com.mannschaft.app.shift/      ← shiftドメイン
...
```

- **異なるドメインの Entity を直接参照しない**（ID のみ保持する）
- **ドメイン間のデータ取得は Service のメソッド呼び出し経由**で行う
- **参照整合性はアプリケーション層で保証**する

---

## Phase 2: インデックス・クエリ性能

### 背景

`findAll()` の乱用・N+1 問題・フルスキャンが各所に散在しており、ユーザー数が増加すると致命的な性能劣化が発生する。根治治療として以下を実施した。

### 実施内容

#### 2-1. 複合インデックス追加（V63.001）

よく使われるフィルタリングパターンに合わせた複合インデックスを追加した。

```sql
-- 監査ログ: ユーザー別・時系列検索の高速化
CREATE INDEX idx_audit_logs_user_created
  ON audit_logs(user_id, created_at DESC);

-- シフト割当: ユーザー別・ステータス・時系列検索の高速化
CREATE INDEX idx_shift_assignments_user_status_created
  ON shift_assignments(user_id, status, created_at DESC);
```

#### 2-2. findAll() → スコープ絞り込みクエリへの変更

| 対象クラス | 変更前 | 変更後 |
|---|---|---|
| `IncomingWebhookService` | `repository.findAll()` | スコープ（organization_id）でフィルタ |
| `ApiKeyService` | `repository.findAll()` | スコープ（organization_id）でフィルタ |
| `SkillSearchService` | `repository.findAll()` | チーム・組織スコープでフィルタ |
| `SkillMatrixService` | `repository.findAll()` | チーム・組織スコープでフィルタ |
| `SkillCsvService` | `repository.findAll()` | チーム・組織スコープでフィルタ |

#### 2-3. count クエリの最適化

```java
// 変更前（全件取得してストリームでカウント）
long count = digestRepository.findAll().stream()
    .filter(d -> d.getStatus() == DigestStatus.PENDING)
    .count();

// 変更後（カウントクエリを直接発行）
long count = digestRepository.countByStatus(DigestStatus.PENDING);
```

対象: `DigestGenerationService`

#### 2-4. バッチ取得への変更（N+1 根治）

```java
// 変更前（N+1 問題: ルール数分のクエリが発生）
rules.forEach(rule -> {
    var alert = analyticsAlertRepository.findById(rule.getAlertId()).orElseThrow();
    // ...
});

// 変更後（バッチ取得で 1クエリに削減）
var alertIds = rules.stream().map(AnalyticsRule::getAlertId).toList();
var alerts = analyticsAlertRepository.findAllById(alertIds);
```

対象: `AnalyticsAlertService`

```java
// 変更前（ループ内 findById: N+1）
effectiveRoles.forEach(roleId -> {
    var role = roleRepository.findById(roleId).orElseThrow();
    permissions.addAll(role.getPermissions());
});

// 変更後（バッチ取得）
var roles = roleRepository.findAllById(effectiveRoles);
roles.forEach(role -> permissions.addAll(role.getPermissions()));
```

対象: `RoleService.resolveEffectivePermissions()`

---

## Phase 3: パーティショニング・アーカイブ戦略

### 背景

`audit_logs` / `chat_messages` / `notifications` は時間とともに行数が爆発的に増加する。通常の `DELETE` では行レベルロックにより長時間のテーブルロックが発生するため、**パーティション DROP による瞬時削除**・**アーカイブテーブルへの退避**を採用した。

### 実施内容

#### 3-A. audit_logs 月次レンジパーティション（V64.001〜V64.002）

```sql
-- audit_logs テーブルを月次レンジパーティションに変換
ALTER TABLE audit_logs
  PARTITION BY RANGE (TO_DAYS(created_at)) (
    PARTITION p202401 VALUES LESS THAN (TO_DAYS('2024-02-01')),
    PARTITION p202402 VALUES LESS THAN (TO_DAYS('2024-03-01')),
    -- ... 2024-01〜2029-12 の各月パーティション
    PARTITION p_future VALUES LESS THAN MAXVALUE
  );
```

**パーティション自動メンテナンス: `AuditLogPartitionMaintenanceBatchService`**

| 項目 | 内容 |
|---|---|
| 実行タイミング | 毎月1日 AM 1:00（Spring `@Scheduled`） |
| 処理内容 | 翌々月のパーティションを事前追加（`p_future` の前に挿入） |
| 理由 | 翌月分を常に1ヶ月以上前に用意し、パーティション不足によるエラーを防ぐ |

**アーカイブ・削除: `AuditLogArchiveBatchService`**

| 項目 | 内容 |
|---|---|
| 実行タイミング | 毎月1日 AM 2:00（Spring `@Scheduled`） |
| 処理内容 | 保持期限超過パーティションを R2 に JSONL で一括アップロード後、`ALTER TABLE ... DROP PARTITION` で瞬時削除 |
| 削除方式 | `DROP PARTITION`（行レベルロックなし・瞬時完了） |
| R2 保存パス | `audit-logs/{yyyy}/{MM}/audit_log_{yyyyMM}.jsonl.gz` |

#### 3-B. chat_messages_archive テーブル（V64.003〜V64.004）

論理削除済みの古いチャットメッセージを専用アーカイブテーブルに退避する。

```sql
-- アーカイブ先テーブル（FK なし・FULLTEXT インデックスなし）
CREATE TABLE chat_messages_archive (
  id          BIGINT       NOT NULL,
  channel_id  BIGINT       NOT NULL,
  sender_id   BIGINT           NULL,
  content     TEXT             NULL,
  created_at  DATETIME(6)  NOT NULL,
  deleted_at  DATETIME(6)      NULL,
  archived_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

設計のポイント:
- **FK なし**: アーカイブテーブルは参照整合性チェックのコストを排除
- **FULLTEXT インデックスなし**: 全文検索は本テーブル側でのみ提供（アーカイブは検索対象外）
- **archived_at カラム**: アーカイブ実行日時を記録し、R2 への再アップロード判断に利用

**アーカイブバッチ: `ChatMessageArchiveBatchService`**

| 項目 | 内容 |
|---|---|
| 実行タイミング | 毎日 AM 3:30（Spring `@Scheduled`） |
| 処理内容 | 論理削除から6ヶ月超のメッセージを `chat_messages_archive` に INSERT → 本テーブルから DELETE |
| バッチサイズ | 1,000件ずつ処理（大量データ時のメモリ圧迫防止） |

#### 3-C. notifications 夜間保持バッチ（アーカイブ移送・索引 V173.20260730033807）

> **移送型への是正（P2 Wave1/Wave2-A）**: 従来は物理削除のみで、移送先表・専用索引も存在せず
> `is_read + created_at` の範囲を掃く走査は実質フルスキャン相当だった。P2 Wave1 で
> `notifications_archive` 表と移送索引 `idx_notifications_read_created` を
> `V173.20260730033807__create_notifications_archive_and_read_index.sql` で新設し、
> Wave2-A で `NotificationCleanupBatchService` を物理削除から**アーカイブ移送型**へ是正した。

```sql
-- 保持期間超過の通知を notifications_archive へ移送してから本体を削除（索引を使った範囲移送）
-- 既読90日超 OR 未読365日超が対象。id 単位の存在確認付き DELETE で欠落なし・重複なし。
INSERT IGNORE INTO notifications_archive (...) SELECT ... FROM notifications
WHERE (is_read = TRUE  AND created_at < DATE_SUB(NOW(), INTERVAL 90  DAY))
   OR (is_read = FALSE AND created_at < DATE_SUB(NOW(), INTERVAL 365 DAY))
ORDER BY created_at ASC LIMIT ?;

DELETE FROM notifications
WHERE ((is_read = TRUE  AND created_at < DATE_SUB(NOW(), INTERVAL 90  DAY))
    OR (is_read = FALSE AND created_at < DATE_SUB(NOW(), INTERVAL 365 DAY)))
  AND id IN (SELECT id FROM notifications_archive) LIMIT ?;
```

**保持バッチ: `NotificationCleanupBatchService`**

| 項目 | 内容 |
|---|---|
| 実行タイミング | 毎日 AM 4:00（Spring `@Scheduled`） |
| 処理内容 | 既読90日超・未読365日超を `notifications_archive` へアーカイブ移送し、archive 収録済みの id のみ本体から削除 |
| バッチサイズ | 10,000件ずつ処理（チャンク単位で独立コミット＝at-least-once） |
| インデックス利用 | `idx_notifications_read_created`（`is_read, created_at`・V173 新設）を利用した効率的な範囲移送 |

---

## Phase 4: 水平分割への布石

### 背景

将来のシャーディング・マルチテナント対応・リードレプリカ導入に備え、インフラ・コード両面で布石を打った。

### 実施内容

#### 4-A. UUIDv7 基底クラス（V65.001）

時系列順でソート可能な UUIDv7 を新規テーブルの標準 ID 型として採用する。

```java
/**
 * UUIDv7（時系列順・衝突耐性）を ID に使う Entity の基底クラス。
 * 新規テーブル作成時はこのクラスを継承する。
 * 既存テーブルの ID 変更は行わない（互換性維持）。
 */
@MappedSuperclass
public abstract class UuidV7Entity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", updatable = false, nullable = false, length = 36)
    private String id;
}
```

**採用ルール:**
- **新規テーブル**: `UuidV7Entity` を継承する
- **既存テーブル**: ID 変更は行わない（BIGINT AUTO_INCREMENT のまま維持）
- **理由**: UUIDv7 は時系列順のため B-Tree インデックスの断片化が少なく、分散環境での ID 衝突がない

#### 4-B. notifications テーブルへの organization_id 追加（V65.002）

```sql
-- マルチテナント対応のための organization_id カラム追加
ALTER TABLE notifications
  ADD COLUMN organization_id BIGINT NULL AFTER user_id;

-- シャーディングキーとしてインデックスを追加
CREATE INDEX idx_notifications_org_id ON notifications(organization_id);
```

**設計のポイント:**
- **クロスドメイン FK なし**: `organization_id` はインデックスのみ（FK 制約なし）
- **NULL 許容**: 組織に紐付かない個人向け通知も存在するため NULL を許容
- **将来の用途**: organization_id をシャーディングキーとして使用する布石

#### 4-C. AbstractTenantAwareRepository（V65.003）

マルチテナント対応リポジトリの基底インターフェース。

```java
/**
 * テナント（organization）スコープでのデータアクセスを強制する基底インターフェース。
 * findAll() の代わりに organization_id を指定したメソッドを使用させることで、
 * テナント間のデータリークを防ぐ。
 */
@NoRepositoryBean
public interface AbstractTenantAwareRepository<T, ID> extends JpaRepository<T, ID> {

    List<T> findByOrganizationIdAndDeletedAtIsNull(Long organizationId);

    Page<T> findByOrganizationIdAndDeletedAtIsNull(Long organizationId, Pageable pageable);

    Optional<T> findByIdAndOrganizationId(ID id, Long organizationId);

    long countByOrganizationId(Long organizationId);
}
```

**適用済みリポジトリ:**
- `ScheduleRepository`: 最初の適用例として実装済み

**今後の適用候補:**
- `ShiftRepository` / `AnnouncementRepository` / `AuditLogRepository` 等、organization スコープが明確なリポジトリ

#### 4-D. リードレプリカ自動ルーティング（V65.004）

`@Transactional(readOnly=true)` を付与したメソッドを自動的にリードレプリカへルーティングする仕組みを実装した。

```java
/**
 * @Transactional(readOnly=true) → REPLICA データソース
 * @Transactional（デフォルト）  → PRIMARY データソース
 */
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            ? DataSourceType.REPLICA
            : DataSourceType.PRIMARY;
    }
}
```

**設定:**

```yaml
# application.yml（デフォルト: レプリカ無効）
app:
  datasource:
    replica:
      enabled: false   # 本番環境では環境変数 APP_DATASOURCE_REPLICA_ENABLED=true で有効化
      url: ${REPLICA_DB_URL:${spring.datasource.url}}
      username: ${REPLICA_DB_USERNAME:${spring.datasource.username}}
      password: ${REPLICA_DB_PASSWORD:${spring.datasource.password}}
```

**運用フロー:**
1. 開発・ステージング環境: `replica.enabled=false`（プライマリのみ使用）
2. 本番環境: `APP_DATASOURCE_REPLICA_ENABLED=true` を環境変数で設定してレプリカを有効化
3. レプリカが遅延している場合でも書き込み整合性は PRIMARY で保証される

#### 4-E. Valkey キャッシュ拡張（V65.005）

読み取り頻度が高く変更頻度が低いデータを Valkey（Redis 互換）にキャッシュする。

| キャッシュ名 | TTL | 対象 | 実装クラス |
|---|---|---|---|
| `role-permissions` | 5分 | ロール別権限セット | `RoleService` |
| `team-detail` | 10分 | チーム詳細情報 | `TeamService` |
| `org-detail` | 10分 | 組織詳細情報 | `OrganizationService` |

```java
// キャッシュ登録
@Cacheable(value = "role-permissions", key = "#roleId")
public Set<Permission> getPermissionsByRoleId(Long roleId) { ... }

// キャッシュ無効化（更新時）
@CacheEvict(value = "role-permissions", key = "#roleId")
public void updateRolePermissions(Long roleId, UpdatePermissionsRequest req) { ... }
```

**Valkey 設定:**
```yaml
spring:
  data:
    redis:
      host: ${VALKEY_HOST:mannschaft-valkey}
      port: ${VALKEY_PORT:6379}
  cache:
    type: redis
    redis:
      time-to-live: 300000   # デフォルト TTL: 5分（ms）
      cache-null-values: false
```

#### 4-E-2. キャッシュ基盤障害時の fail-open（`LoggingCacheErrorHandler`）

Spring 既定の `SimpleCacheErrorHandler` はキャッシュ操作の例外を**そのまま再送出**するため、
Valkey 断のときに `@CacheEvict` を持つミューテーション（`RoleService.changeRole` 等）が
`RedisConnectionFailureException` で 500 になる。すなわち
**「キャッシュ基盤が落ちると降格・除名ができない」**状態だった。

方針（マスター御裁可・可用性優先）: **Redis が落ちている間も権限の変更は成功させる。**
緊急時に悪意あるユーザーを降格・除名できない方が、旧権限が最大 TTL ぶん残ることより危険なため。

| クラス | 役割 |
|---|---|
| `LoggingCacheErrorHandler` | get / put / evict / clear の 4 フックで例外を握り潰し、`log.warn` ＋ Micrometer カウンタで可視化する |
| `CacheErrorHandlingConfig` | `CachingConfigurer#errorHandler()` としてハンドラを配線する（素の `@Bean CacheErrorHandler` は Spring が拾わない） |

- **安全性の根拠**: TTL 無しのキャッシュは 1 件も無く（既定 30 分・認可系は 5 分以下）、evict を取りこぼしても**自然収束**する。
  番人テスト `CacheConfigurationGuardTest` が「TTL 無しキャッシュの混入」を機械的に拒否する
- **「静かな無効化」にしない**: fail-open は必ず `mannschaft.cache.failopen`（tag: `operation` = get/put/evict/clear, `cache` = キャッシュ名）で観測できる。
  `operation=evict` / `clear` は認可情報が腐りうるため、get/put より重い扱いとする
- 既存の fail-open 実装（`ValkeyRateLimiter` / `MembershipChangedListener` / `EntitlementCacheEvictor`）と同方針であり、
  本ハンドラはそれをアノテーション経由の `@Cacheable`/`@CacheEvict` にも水平展開したもの

---

## 今後の課題

### 未実施の対応（将来フェーズ）

| 項目 | 優先度 | 概要 |
|---|---|---|
| **クロスドメイン FK 撤廃の継続（残 149件）** | **高** | 2026-06-17 実測。内訳: SET NULL 77件 / CASCADE 37件 / RESTRICT 18件 / 未指定 17件。撤廃方針: (1) team/org 参照の CASCADE/SET NULL（現運用で発火しないデッドFK）→機械一括撤廃、(2) user 参照で CASCADE の肩代わり実装が未整備のもの→アプリ層実装後に撤廃、(3) SET NULL→nullify 肩代わりをアプリ層に移植後に撤廃。**残存 FK の約 50% は無名制約のため `INFORMATION_SCHEMA`/`SHOW CREATE TABLE` で実 DB 制約名を特定してから撤廃すること（要追加調査）** |
| `AbstractTenantAwareRepository` の全面適用 | 高 | `ScheduleRepository` のみ適用済み。他リポジトリへの順次適用が必要 |
| イベント駆動アーキテクチャへの移行 | 中 | `@Transactional` クロスドメイン箇所（TODO コメント済み）をドメインイベントで分離 |
| シャーディング本実装 | 低 | `organization_id` をシャーディングキーとした水平分割。UUIDv7 導入済みで基盤は整備済み |
| 既存テーブルの UUIDv7 移行 | 低 | 現在は新規テーブルのみ。既存 BIGINT ID テーブルの移行は別軍議で検討 |
| リードレプリカの本番適用 | 中 | `replica.enabled=false` のままのため、本番環境の DB 構成確定後に有効化 |
| audit_logs パーティション 2030年以降 | 中 | V64.001 で 2029-12 まで定義済み。`AuditLogPartitionMaintenanceBatchService` が自動追加するため人手対応は不要 |
| chat_messages_archive の R2 アップロード | 低 | 現状はアーカイブテーブルへの退避のみ。将来は R2 への JSONL.gz 保存も検討 |

### 監視・アラート推奨項目

| 項目 | 閾値（目安） | 備考 |
|---|---|---|
| 最大パーティションサイズ | 5GB 超でアラート | `audit_logs` の月次パーティションサイズ監視 |
| `notifications` テーブル行数 | 5000万行超でアラート | クリーンアップバッチが正常動作しているかの確認 |
| Valkey キャッシュヒット率 | 70% 未満でアラート | キャッシュ設定の見直しトリガー |
| `mannschaft.cache.failopen`（`operation=evict`/`clear`） | 発生でアラート | キャッシュ無効化の失敗＝認可情報の反映遅延。Valkey 断の一次シグナル（§4-E-2） |
| `mannschaft.cache.failopen`（`operation=get`/`put`） | 継続発生でアラート | キャッシュが機能せず DB に素通りしている状態（性能劣化の予兆） |
| リードレプリカ遅延 | 5秒超でアラート | レプリカ遅延によるデータ不整合リスク |
| `AuditLogArchiveBatchService` 実行時間 | 10分超でアラート | R2 アップロード・DROP PARTITION の異常検知 |

---

## 関連ドキュメント

| ドキュメント | 内容 |
|---|---|
| `docs/db_design_details.md` | テーブル詳細定義・R2 ストレージ設計 |
| `docs/operations/PRODUCTION_SETUP.md` | 本番環境セットアップ手順（リードレプリカ設定含む） |
| `CLAUDE.md` §アーキテクチャ思想 | ドメイン境界・DB設計原則（コーディング必読） |
| `backend/BACKEND_CODING_CONVENTION.md` | Java コーディング規約 |
