# F22.1 市（Market）— 01. データモデル

> 親: [README.md](README.md) ／ 関連: [02_api_design.md](02_api_design.md) / [04_security.md](04_security.md)

---

## 1. 方針 ―「市の実体テーブルは作らない」

市は `recruitment_listings` の論理ビュー（README §3）であり、専用テーブルを持たない。
本機能で必要な DB 変更は**最小限**であり、内訳は以下の 4 点のみ。

| # | 変更 | 種別 | 対象 | クロスドメインFK |
|---|---|---|---|---|
| 1 | 地域列の追加 | **ALTER**（既存テーブル拡張） | `recruitment_listings` | 張らない（マスタ参照はService検証） |
| 2 | visibility に `FRIEND_TEAMS_ONLY` 追加 | **Java enum追加のみ・DDL不要** | `RecruitmentVisibility`（列は `VARCHAR(20)`） | — |
| 3 | フレンド宛先テーブルの新設 | **CREATE**（新規・UUIDv7） | `recruitment_friend_targets` | 張らない（friend/team 参照はService検証） |
| 4 | confirmable の source_type/source_id 連携 | **source_type は ALTER 不要（VARCHAR）／ source_id 列を新規追加** | `confirmable_notifications` | — |

> **地域マスタは新規作成しない。** 既存 `prefectures`（`code CHAR(2)`）/ `cities`（`code CHAR(5)`, `prefecture_code CHAR(2)` FK）を正典として参照する（F08.1 由来・全国約1,900件 seed 済）。市ドメインは**参照のみ**。二重マスタを作らない（CLAUDE.md 原則6 マスタ例外・再利用原則）。

---

## 2. 変更1: `recruitment_listings` に地域列を追加

市ビューの結合キー。既存 `location VARCHAR(200)`（自由入力・表示用）は残し、構造化フィルタ用にコード列を追加する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `prefecture_code` | CHAR(2) | YES | NULL | 都道府県コード（JIS X 0401）。`prefectures.code` を参照（FKなし） |
| `city_code` | CHAR(5) | YES | NULL | 市区町村コード（JIS X 0402）。`cities.code` を参照（FKなし） |

**インデックス**
```sql
-- 市ビュー（地域×ジャンル×状態）の検索用。既存の category 検索インデックスと併用
INDEX idx_rl_market_region (prefecture_code, city_code, category_id, status)
```

**制約・備考**
- **クロスドメインFKは張らない**（CLAUDE.md 原則1）。`recruitment` ドメインから `prefectures`/`cities`（共通マスタ）への参照整合性は **Service 層で検証**する:
  - `city_code` 指定時、`cities` に存在し、かつ `SUBSTRING(city_code,1,2) = prefecture_code` が成立すること（不一致は `MARKET_001`）。
  - `prefecture_code` のみ指定も可（市区町村未確定の県単位募集）。
  - 両方 NULL も可（"地域を問わない" 札。§8-1）。
- `city_code` を持つ場合の `prefecture_code` 確定順序（Service検証で固定）:
  1. `city_code` が `cities` に存在することを検証（不在は `MARKET_001`）。
  2. `prefecture_code` 未指定なら `SUBSTRING(city_code,1,2)` で**自動補完**してセット。
  3. `prefecture_code` 指定済みなら `SUBSTRING(city_code,1,2) = prefecture_code` を検証（不一致は `MARKET_001`）。
  → API レスポンスでは常に `prefecture_code` が埋まった状態を返す（不完全コードを混在させない）。
- 既存行は両列 NULL（後方互換）。地域フィルタは NULL を「地域なし区画」に振る。

---

## 3. 変更2: `RecruitmentVisibility` に `FRIEND_TEAMS_ONLY` を追加（DDL不要）

非公開札（フレンドチーム限定募集）を表す visibility 値を追加する。

**重要**: `recruitment_listings.visibility` の実体は **`VARCHAR(20) NOT NULL DEFAULT 'SCOPE_ONLY'`**（MySQL ENUM ではなく **Java enum `RecruitmentVisibility` で値を管理**。現状値: `PUBLIC` / `SCOPE_ONLY` / `SUPPORTERS_ONLY` / `CUSTOM_TEMPLATE`）。したがって**マイグレーションは不要**で、Java enum への定数追加のみで済む（`'FRIEND_TEAMS_ONLY'` は17文字 ≤ VARCHAR(20)）。

```java
// backend .../recruitment/RecruitmentVisibility.java に1値追加するだけ
public enum RecruitmentVisibility {
    PUBLIC, SCOPE_ONLY, SUPPORTERS_ONLY, CUSTOM_TEMPLATE,
    FRIEND_TEAMS_ONLY   // ★追加（フレンドチーム限定の非公開札）
}
```
> DDL を伴わないため Flyway 版番号は消費しない。文字列長が VARCHAR(20) に収まることだけ確認する。

**意味と整合性**
| visibility | 市での見え方 | 閲覧/応募可能者 |
|---|---|---|
| `PUBLIC` | **市に並ぶ**（未ログイン含む全員に見える） | 全員（PII抑制）。応募はログイン必須 |
| `SCOPE_ONLY` / `SUPPORTERS_ONLY` / `CUSTOM_TEMPLATE` | 市には並ばない（既存F03.11の閉じた配信） | 既存F03.11準拠 |
| `FRIEND_TEAMS_ONLY`（新規） | **公開市には並ばない**。宛先フレンドの「届いた札」一覧と通知にのみ出る | `recruitment_friend_targets` で解決されたフレンドチームのメンバーのみ。第三者には **404 で存在秘匿** |

- `visibility='FRIEND_TEAMS_ONLY'` のとき `recruitment_friend_targets` が**1件以上必須**（0件で `OPEN` 遷移は `MARKET_002`）。
- `distribution_targets` との関係: `FRIEND_TEAMS_ONLY` は `distribution_targets`（MEMBERS/SUPPORTERS/FOLLOWERS/PUBLIC_FEED）を**使わない**。配信先はフレンド宛先テーブルが決定する（§4）。整合性違反（`FRIEND_TEAMS_ONLY` なのに `PUBLIC_FEED` 指定など）は `RECRUITMENT_207` を踏襲して 400。

---

## 4. 変更3: `recruitment_friend_targets`（新規テーブル）

非公開札の宛先を**3粒度（全体 / フォルダ / 個別チーム）**で記録する。利用者が混在指定できる（例: フォルダAの全員＋個別にチームX）。

**なぜ `distribution_targets` を流用しないか**: 既存 `recruitment_distribution_targets` は `UNIQUE(listing_id, target_type)` で**1種別1行**・参照ID列を持たず、複数フォルダ/複数チームの宛先を表現できない。また `distribution_targets` は「新着配信スコープ（誰に通知するか）」、本テーブルは「**誰がアクセスできるか（visibility）**」という別関心であり、責務分離のため専用テーブルを新設する。

| カラム名 | 型 | NULL | デフォルト | 説明 |
|---|---|---|---|---|
| `id` | BINARY(16) | NO | （UUIDv7） | PK。新規テーブルにつき `UuidV7Entity` 継承（CLAUDE.md 原則6） |
| `listing_id` | BIGINT UNSIGNED | NO | — | FK → `recruitment_listings(id)` ON DELETE CASCADE（**同一ドメイン**なのでCASCADE可） |
| `target_kind` | ENUM('ALL_FRIENDS','FOLDER','TEAM') | NO | — | 宛先の粒度 |
| `folder_id` | BIGINT UNSIGNED | YES | NULL | `target_kind='FOLDER'` のとき必須。F01.5 フレンドフォルダID（**FKなし**・index） |
| `team_id` | BIGINT UNSIGNED | YES | NULL | `target_kind='TEAM'` のとき必須。宛先チームID（**FKなし**・index） |
| `created_at` | DATETIME | NO | CURRENT_TIMESTAMP | |

**インデックス**
```sql
INDEX idx_rft_listing (listing_id)                 -- 札→宛先一覧
INDEX idx_rft_team    (team_id)                    -- チーム→自分宛の札（届いた札一覧）
INDEX idx_rft_folder  (folder_id)
UNIQUE KEY uk_rft_listing_kind_ref
  (listing_id, target_kind, folder_id, team_id)    -- 同一宛先の重複登録防止
```

**制約・備考**
- `target_kind` と参照列の整合（Service層 + CHECK）:
  - `ALL_FRIENDS` → `folder_id IS NULL AND team_id IS NULL`
  - `FOLDER` → `folder_id IS NOT NULL AND team_id IS NULL`
  - `TEAM` → `team_id IS NOT NULL AND folder_id IS NULL`
  ```sql
  CONSTRAINT ck_rft_kind CHECK (
    (target_kind='ALL_FRIENDS' AND folder_id IS NULL AND team_id IS NULL) OR
    (target_kind='FOLDER'      AND folder_id IS NOT NULL AND team_id IS NULL) OR
    (target_kind='TEAM'        AND team_id   IS NOT NULL AND folder_id IS NULL)
  )
  ```
- **クロスドメインFKなし**（`folder_id`/`team_id` は F01.5/team ドメイン）。整合性はService検証:
  - 指定 `team_id` が札主チームと **`team_friends` で成立済みフレンド**であること（未成立は `MARKET_003`）。
    - **正規化キーで検索すること**: `team_friends` は `CHECK(team_a_id < team_b_id)`・`UNIQUE(team_a_id, team_b_id)` で正規化保存される（V9.072・実装済）。検証は `team_a_id = MIN(札主teamId, 宛先teamId) AND team_b_id = MAX(...)` で引く。単純な片側一致検索は誤判定するため不可。
  - 指定 `folder_id` が**札主チームの所有フォルダ**であること（他人のフォルダ指定は `MARKET_004`）。
- **依存関係（実装順）**: 本テーブルが参照する F01.5 のうち、`team_friends`（V9.072）/ フレンドフォルダ `team_friend_folders`（**V9.073・実装済**）はいずれも実装済。
  > **乖離C 是正（第一陣 / 2026-05-30）**: 当初「`FOLDER` は未実装ゆえ gating」としていたが、`team_friend_folders` は **V9.073 で既に実装済**であることを確認した。よって **`ALL_FRIENDS`/`FOLDER`/`TEAM` の3粒度すべてを Phase 1 で実装する（FOLDER の gating は不要）**。README §4 の gating 記述も同様に是正済。
- **フォルダ削除時の孤立対策**: フレンドフォルダ削除時、`target_kind='FOLDER'` の宛先が孤立する。F01.5 のフォルダ削除フックで該当 `recruitment_friend_targets` 行を削除する（イベント連携）。万一孤立した場合、配信/アクセス解決（§02_api_design §7）で**存在しないフォルダは「該当フレンドなし」として安全に無視**（NPEを出さず空集合扱い）し、症状を隠さずログ記録する。
- 論理削除は持たない（札の従属データ。札の取下げ＝`recruitment_listings.status` 側で表現、行は CASCADE で物理削除されても監査は `recruitment_participant_history` 側に残る）。
- **宛先の具体化（誰に届くか）は保存時に固定しない**。`ALL_FRIENDS`/`FOLDER` は配信・アクセス判定の都度 F01.5 サービスで「現在の成立フレンド集合」へ解決する（フレンド増減に追従。§02_api_design の配信フロー参照）。

---

## 5. 変更4: `confirmable_notifications.source_type` に `MARKET_FINALIZE` を追加

「札を下げる」の**最終認証**を、F04.9 確認通知（確認応答型）で実装する。札が要件充足（`FULL`）した際、札主に「この募集を確定して札を下げますか？」の確認通知を送り、確認応答で `COMPLETED` へ遷移させる。

> **⚠ 乖離是正（第一陣・部隊1 / 2026-05-30）— 実コードと設計の差分を本実装で根治した:**
> - **乖離A（JPAマッピング欠落）**: `ConfirmableNotificationEntity` には `source_type`/`source_id` が **JPAマッピングされていなかった**。本実装で `@Column(name="source_type")`（`String`）と `@Column(name="source_id")`（`Long`）の **JPAマッピングを追加**した。最終認証の連携（`MARKET_FINALIZE` 発火＋確認後リスナ）は **新規実装**である（「source_type 拡張のみで済む」は誤り。第二陣で send() オーバーロード＋確認後イベントリスナを実装する）。
> - **乖離B（source_type は VARCHAR）**: `confirmable_notifications.source_type` の実体は **`VARCHAR(40) NOT NULL DEFAULT 'EMERGENCY_CLOSURE'`**（MySQL ENUM ではない・V13.006 の CREATE TABLE で確認）。よって `MARKET_FINALIZE` 追加に **Flyway ALTER は不要**（Java 側で文字列を渡すのみ。`'MARKET_FINALIZE'` は14文字 ≤ VARCHAR(40)）。下記の旧 `MODIFY COLUMN ... ENUM(...)` は **誤り**。
> - **source_id 列の新設**: 一方 `source_id` 列は DB に **存在しなかった**ため、`source_id = recruitment_listings.id` を保持できるよう Flyway で `source_id BIGINT UNSIGNED NULL` を追加した（FK なし・`idx_cn_source(source_type, source_id)` 付与）。

```sql
-- ❌ 旧設計（誤り・ENUM 前提）。実コードは source_type が VARCHAR(40) なので ALTER 不要。
-- ALTER TABLE confirmable_notifications
--   MODIFY COLUMN source_type
--     ENUM('EMERGENCY_CLOSURE','RECRUITMENT_LISTING','MARKET_FINALIZE') NOT NULL;

-- ✅ 実装（第一陣）: source_id 列のみ追加（source_type は VARCHAR ゆえ追加変更不要）
ALTER TABLE confirmable_notifications
  ADD COLUMN source_id BIGINT UNSIGNED NULL AFTER source_type;
CREATE INDEX idx_cn_source ON confirmable_notifications (source_type, source_id);
```

- `source_type='MARKET_FINALIZE'`（文字列値）、`source_id = recruitment_listings.id`。
- **⚠ デプロイ順序の厳守**（F03.11 §8.5 と同じ轍）: F04.9 の Service/Batch/UI が**未知の source_type を安全に無視する防御コード**を**先に**デプロイしてから、`MARKET_FINALIZE` を発火する側を投入する。順序を誤ると既存確認通知処理が `IllegalArgumentException` で連鎖故障する。
- 既存の `RECRUITMENT_LISTING`（募集の確認済みボタン用）とは別用途のため、新値で分離する（混在させない）。

---

## 6. Flyway マイグレーション

> **乖離D 是正（第一陣 / 2026-05-30）— 採番は「Flyway順序での全体最大の次」**: `db/migration/` は連番 `V{major}.{minor}`（**全体最大は `V70.020`**）に、ごく一部の `V9.YYYYMMDDhhmmss`（タイムスタンプ）が混在する。**踏んだ罠**: タイムスタンプ形式は major=9 のため Flyway 数値順序では `V10`〜`V70` より**前**にソートされる。`confirmable_notifications` は `V13.006` で作成されるため、当初の `V9.*` 採番では from-scratch 適用が「未作成テーブルへの ALTER」となり**番人テストで失敗**した（CI で検知・是正済）。**新規採番は必ず全体最大（現状 `V70.020`）より後の major** を切る（本機能は `V71.001`〜）。マージ直前に `origin/main` の最大版番を再確認しリネーム（並行PR衝突は「マージ時」確定・from-scratch 番人テストが検知。memory: migration_version_collision）。

```
-- 第一陣で実際に採番したファイル（2026-05-30 / 全体最大 V70.020 の次＝V71系）
V71.001__alter_recruitment_listings_add_region.sql       -- §2 地域列 + idx_rl_market_region
V71.002__create_recruitment_friend_targets.sql           -- §4 フレンド宛先テーブル
V71.003__add_source_id_to_confirmable_notifications.sql  -- §5 source_id 列追加（source_type は VARCHAR ゆえ ALTER 不要）
```
> §3 の `FRIEND_TEAMS_ONLY` 追加は **Flyway 不要**（`visibility` は VARCHAR(20)・Java enum 管理）。Java 定数追加のみ。
> §5 の `source_type='MARKET_FINALIZE'` も **Flyway 不要**（VARCHAR・乖離B）。追加が必要だったのは `source_id` 列のみ。

**注意点**
- §5 の `confirmable_notifications` 連携は、`source_type='MARKET_FINALIZE'` を**発火する側**（第二陣の send() オーバーロード）を投入する**前に**、未知 source_type を安全に無視する防御コードを先行デプロイすること（§5 警告）。本第一陣で投入する DDL（`source_id` 列追加）は防御コードに依存せず単独適用可。
- 地域列の ALTER は既存行を NULL 埋めするのみ（バックフィル不要）。
- `recruitment_friend_targets` は `recruitment_listings` 作成後に作成（同一ドメイン・依存順）。
- from-scratch 番人テスト（Flyway 全適用）で番号衝突・順序破綻を検知する。

---

## 7. ER 図（テキスト形式）

```
（既存・本機能で参照）
prefectures (1) ──< (N) cities                      [マスタ・FKあり（同一マスタ群内）]

（既存・本機能で拡張）
recruitment_listings ── prefecture_code → prefectures.code   [FKなし/Service検証]
recruitment_listings ── city_code       → cities.code        [FKなし/Service検証]
recruitment_listings (1) ──< (N) recruitment_participants      [既存・同一ドメインCASCADE]
recruitment_listings (1) ──< (N) recruitment_distribution_targets [既存]
recruitment_listings (1) ──< (N) recruitment_reminders         [既存]

（本機能で新設）
recruitment_listings (1) ──< (N) recruitment_friend_targets    [同一ドメインCASCADE]
recruitment_friend_targets ── folder_id → (F01.5) friend_folders [FKなし/Service検証]
recruitment_friend_targets ── team_id   → teams.id              [FKなし/Service検証]

（既存・最終認証で参照）
confirmable_notifications.source_id → recruitment_listings.id   [source_type='MARKET_FINALIZE' / FKなし]
```

---

## 8. CLAUDE.md DB原則への適合

---

## 9. 追加仕様: 個人札主のデータ境界（Phase 2）

既存 `scope_type/scope_id` を TEAM/ORGANIZATION のまま破壊的に置換しない。Phase 2 は Expand として札主区分を `owner_kind`（`PERSONAL`/`TEAM`/`ORGANIZATION`）で明示し、PERSONAL の所有者は `owner_user_id`（users への既存方針に従う論理参照）へ固定する。TEAM/ORGANIZATION は既存 `scope_type/scope_id` を正典として維持し、移行期間は整合チェックを置く。

- `participation_type` と `payee_kind` は札主区分と別列・別 enum のまま維持する。
- PERSONAL では `owner_user_id IS NOT NULL`、TEAM/ORGANIZATION では `owner_user_id IS NULL` を Service 層と DB 制約で二重検証する。
- `payment_enabled=TRUE AND owner_kind='PERSONAL'` は Phase 5 まで DB/Service の双方で拒否する（既存 TEAM/ORGANIZATION 行には影響させない）。
- 主体別履歴用索引は `(owner_kind, owner_user_id, status, created_at)` と既存 scope 索引を併用し、一覧に参加者を join して N+1 を起こさない。

`FRIEND_TEAMS_ONLY` の可視性判定、Repository の `CASE`/projection、地域既定補完、Connect の TEAM/ORGANIZATION 分岐は `PERSONAL` 導入と同一 Phase で更新する。未知の `owner_kind` は SQL の ELSE で公開可能側へ落とさず、検索対象外・認可拒否とする。

| 原則 | 適合状況 |
|---|---|
| 1. クロスドメインFK禁止 | ✅ 地域マスタ参照・フレンド/チーム参照は**FKなし**、Service検証 |
| 2. CASCADEは同一ドメイン内のみ | ✅ `recruitment_friend_targets`→`recruitment_listings` は同一ドメインなのでCASCADE可。クロスドメインCASCADEなし |
| 3. コアエンティティ論理削除 | ✅ 既存 `recruitment_listings`（`deleted_at`）を踏襲。従属テーブルは物理 |
| 4. 退会時匿名化 | ✅ 札主は scope（team/org）。個人応募者の退会は既存F03.11/匿名化基盤に委譲 |
| 6. 新規テーブルPK=UUIDv7 | ✅ `recruitment_friend_targets` は `UuidV7Entity` 継承（BINARY(16)）。既存テーブルのBIGINT IDは変更しない |
| 7. テナントRepository | △ 札は scope（team/org）単位だが `recruitment_listings` は既存BIGINTで `organization_id` 絞り込みは既存F03.11方針に従う（本機能で新設するテナント絞り込みRepositoryはなし） |
| マスタ例外 | ✅ `prefectures`/`cities` は既存マスタ例外（自然キー）。新規マスタを作らない |
