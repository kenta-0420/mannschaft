# ドメイン境界・DB 設計原則（モジュラーモノリス）

Mannschaft は将来のマイクロサービス分割を見据えた**モジュラーモノリス**として設計する。
以下の原則は新機能実装・DB 変更時に必ず遵守すること。CLAUDE.md「アーキテクチャ思想」節の詳細版。

> 実装記録・背景の全文は [`db_scalability.md`](db_scalability.md) を参照。

---

## ドメイン境界の原則

パッケージはドメイン単位で分割し、ドメイン間の直接依存を最小化する。

```
com.mannschaft.app.user/
com.mannschaft.app.team/
com.mannschaft.app.schedule/
com.mannschaft.app.shift/
...
```

- 異なるドメインの Entity を直接参照しない（ID のみ保持する）
- ドメイン間のデータ取得は Service のメソッド呼び出し経由で行う

---

## DB 設計の原則

### 1. クロスドメイン FK は作らない
異なるドメインのテーブル間に Foreign Key 制約を追加してはならない。参照整合性はアプリケーション層で保証する。

```sql
-- NG: クロスドメインFK
ALTER TABLE shift_assignments
  ADD CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id);

-- OK: インデックスのみ（整合性はアプリ側で保証）
CREATE INDEX idx_shift_assignments_user_id ON shift_assignments(user_id);
```

### 2. CASCADE DELETE は同一ドメイン内のみ許可
`ON DELETE CASCADE` は**親子が同一ドメインに属する場合のみ**許可する。クロスドメインの削除連鎖は禁止。

```sql
-- OK: 同一ドメイン内（chat_channelとchat_messageは同じchatドメイン）
FOREIGN KEY (channel_id) REFERENCES chat_channels(id) ON DELETE CASCADE

-- NG: クロスドメイン（scheduleドメイン → teamドメイン）
FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE
```

### 3. コアエンティティは論理削除（soft delete）を使う
`users` / `teams` / `organizations` は物理削除せず `deleted_at` カラムで論理削除する。これらはすでに `deleted_at` カラムを持っている。

### 4. ユーザー退会時は匿名化（削除しない）
ユーザーが退会しても投稿・履歴・統計データは保持し、個人情報のみ消去する。

```java
// ユーザー退会処理の方針
user.anonymize();        // 氏名・メール・アイコンを匿名化
user.softDelete();       // deleted_at をセット
// 投稿・ログ・ポイント等のデータは user_id=NULL にせずそのまま残す
// → 統計・履歴の価値を保持しつつ個人情報を保護（GDPR対応）
```

**PII 消去のタイミング（2026-05-18 改訂 / マスター御裁可 §13.12）:**

PII（個人識別情報）の消去は **GDPR Art.17 の 30 日タイムリミット内であれば段階的実施を許容する**。
退会フローは「即時消去対象（弱匿名化）」と「猶予対象（強匿名化）」の二段モデルを採用する。

| 区分 | 対象ドメイン | タイミング | 根拠 |
|---|---|---|---|
| **即時消去（弱匿名化）** | 通知・カレンダー連携・天気設定・お気に入り 等の「再設定で復旧可能」かつ「個人特定リスクが残る」データ | `requestWithdrawal` 受付直後（`UserAnonymizedEvent` 即時発火）| 退会撤回時は再設定で対応可。漏洩リスクを最小化 |
| **猶予対象（強匿名化）** | auth（OAuth/2FA）・social・village 所有権・scopefolder 等の「復旧不可能」または「業務整合性に重大影響」のあるデータ | `requestWithdrawal` 受付から最大 30 日後（`AccountPurgeService` バッチ）| 退会撤回ウィンドウを保持しつつ GDPR Art.17 を遵守 |

設計詳細: [`withdrawal_flow_immediate_anonymization_fix.md`](withdrawal_flow_immediate_anonymization_fix.md) §1.3 / §13.12（PR #793 main マージ済）。

### 5. @Transactional はドメイン内に閉じる
`@Transactional` メソッドが複数ドメインの Repository をまたぐ場合は設計を見直す。やむを得ずまたぐ場合はコメントで理由を明記し、将来のイベント駆動化候補として記録する。

```java
@Transactional
// TODO: ScheduleドメインとUserドメインをまたいでいる。将来はUserUpdatedEventで分離予定
public void createSchedule(...) { ... }
```

### 6. 新規テーブルの主キーは UuidV7Entity を継承する（2026-05-11〜）
**新規に作成するテーブルの Entity** は `UuidV7Entity` を継承し、主キーを UUIDv7 にすること。既存テーブルの BIGINT ID は変更しない。

```java
// 新規 Entity はこれを継承する
public class MyNewEntity extends UuidV7Entity {
    // id フィールドは UuidV7Entity が持つ（UUID型、自動生成）
    ...
}
```

```sql
-- 新規テーブルの DDL も UUID に合わせる
CREATE TABLE my_new_table (
    id BINARY(16) NOT NULL,  -- または CHAR(36)
    ...
    PRIMARY KEY (id)
);
```

**なぜ変更したか:**
BIGINT AUTO_INCREMENT は単一の発番サーバーが必要なため、水平分割（シャーディング）ができない。
UUIDv7 は時刻順ソート可能でインデックス効率が高く、複数 DB ノードで独立して発番できる。
1000 万ユーザー規模でシャーディングが必要になったとき、既存テーブルの ID 変更は超侵襲的な作業になるため、
**新規テーブルから先行して UUIDv7 に移行することで、段階的にシャーディング対応を進める**方針とした。

**例外（UUIDv7 を適用しなくてよいテーブル）:**

原則 6 の意図は「将来シャーディングしたときに各ノードで独立発番できるようにする」ことである。
シャーディングの対象にならないテーブルは原則 6 の意図に該当しないため、自然キー / 固定値 ID のままで構わない。

| 例外区分 | 説明 | 主キーの推奨 |
|---|---|---|
| **マスタテーブル** | 全テナント・全ユーザー共通の参照データ。書き込みは運用バッチのみ、シャーディング時は全シャードに同じデータをコピーする運用。例: 郵便番号→緯度経度マスタ、国コード表、税率表 | 自然キー（複合キーでも可） |
| **シングルトン表** | 行が常に 1 行のみ存在する設定/運用状態テーブル。`CHECK (id = 1)` 等で行数を制約する。例: 取り込みバッチのメタデータ、初回マイグレーションの冪等フラグ | 固定値 ID（`TINYINT UNSIGNED CHECK (id = 1)` 等） |

判定基準: 「テナントごと・ユーザーごとに行が増えていくテーブル」=原則 6 適用。
「全テナント共通で読み取られるテーブル」「行が 1 件で固定のテーブル」=例外。

迷ったら**原則 6 を適用**しておけば後悔しない（BINARY(16) なら 16 バイト/行のオーバーヘッドだけで済む）。
ただし、上記 2 区分に該当するテーブルでは UUIDv7 化の利点が完全にゼロなので、設計書に「マスタ例外」「シングルトン例外」と明記して自然キーで設計してよい。

### 7. テナントスコープのリポジトリは AbstractTenantAwareRepository を実装する（2026-05-11〜）
`organization_id` で絞り込む Repository は `AbstractTenantAwareRepository<T, ID>` を継承すること。

```java
// Before
public interface MyRepository extends JpaRepository<MyEntity, Long> {

// After（organization_id カラムを持つ Entity の場合）
public interface MyRepository extends AbstractTenantAwareRepository<MyEntity, Long> {
```

`AbstractTenantAwareRepository` が提供するメソッド:
- `findByOrganizationIdAndDeletedAtIsNull(Long organizationId)`
- `findByOrganizationIdAndDeletedAtIsNull(Long organizationId, Pageable pageable)`
- `findByIdAndOrganizationIdAndDeletedAtIsNull(ID id, Long organizationId)`
- `countByOrganizationIdAndDeletedAtIsNull(Long organizationId)`

**なぜ変更したか:**
将来の水平シャーディングでは `organization_id` をシャードキーとして使う。
リポジトリ層で `organization_id` 絞り込みを統一しておくことで、シャーディング導入時にルーティングロジックを
一箇所（基底クラス）に追加するだけで全テナント対応が完了する設計とした。

### 8. 新規テーブルは照合順序を明示宣言する（2026-08-04〜 / issue #2589）

`CREATE TABLE` の末尾で文字セットと照合順序を必ず明示すること。

```sql
-- Before（禁止）: 宣言なし ＝ サーバ変数 collation_server を継承する
) ENGINE=InnoDB COMMENT='...';

-- After: 統一値を明示宣言する
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='...';
```

統一値は **`utf8mb4` / `utf8mb4_0900_ai_ci`** の一択。列単位の `COLLATE` 上書きも禁止する
（JOIN 相手との不一致を生むため）。

**なぜこの原則があるか:**
照合順序を宣言しない表は MySQL のサーバ変数 `collation_server` を継承する。
本番 RDS（`utf8mb4_0900_ai_ci`）とローカル docker（当時 `utf8mb4_unicode_ci`）でこの値が違ったため、
**同じ DDL から環境ごとに違う照合順序のスキーマが生まれていた**。
その結果、照合順序の異なる文字列列同士を比較する JOIN が
**ローカルでは通るのに本番だけ `Illegal mix of collations` で落ちる**という障害になった
（`MyScopeFolderItemRepository#aggregateFolderUnreadCounts`）。
通常のテストは `ddl-auto=create` かつ Flyway 無効で走るため、この差は原理的に検知できない。

**現在の防御（多重）:**

| 層 | 実体 | 役割 |
|---|---|---|
| スキーマ統一 | `V175.20260804134628__unify_table_collation.sql` | 既存の全表・全文字列列を統一値へ変換し、`ALTER DATABASE` でデータベース既定も固定（以後 `collation_server` に依存しない） |
| 環境の一致 | `docker-compose.yml` の `--collation-server` | ローカルのサーバ既定を本番 RDS と同値に揃える |
| 静的番人 | `MigrationCollationDeclarationGuardTest` | 新規 migration の `CREATE TABLE` が宣言を欠いたら Docker 不要で即 fail |
| 動的番人 | `SchemaCollationConsistencyIT` | 本番と同じ照合順序で Flyway を実際に流し、適用後の実スキーマ全体を検証 |

---

## なぜこの設計か

**1000 万ユーザー規模**で発生する具体的な問題を防ぐために段階的に設計を整備している。

| 問題 | 対応原則 |
|---|---|
| クロスドメイン FK でシャード分割不能 | 原則 1・2（FK 撤廃、CASCADE 制限）|
| 退会トリガーの連鎖削除で統計破壊 | 原則 2・3・4（CASCADE 制限、論理削除、匿名化）|
| 巨大テーブルの B-Tree 破綻 | Phase 3（パーティショニング・アーカイブ）|
| 単一 DB ノードの書き込み上限 | 原則 6・7 + Phase 4（UUID・テナント設計・レプリカ）|
| @Transactional 越境でデッドロック頻発 | 原則 5（ドメイン内 @Transactional）|

詳細な実装記録は [`db_scalability.md`](db_scalability.md) を参照。
