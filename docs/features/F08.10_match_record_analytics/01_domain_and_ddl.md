# F08.10 / 01: ドメイン配置・DDL・enum・多競技対応

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-08
> **関連機能番号**: F08.10（試合記録・分析）／ F08.7 ／ F08.7.1
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・GoalNote 比較・機能番号 F08.10 の経緯
> - [03_permissions_and_recording_modes.md](./03_permissions_and_recording_modes.md) — `recorded_by_team_id` / `owning_team_id` の権限利用・二段アクセス
> - [05_tournament_integration.md](./05_tournament_integration.md) — `tournament_fixture_id`（BIGINT）リンク・既存テーブルの作り替え
> - [sports/01_soccer.md](./sports/01_soccer.md) — **サッカー競技固有カタログ**（event_type 具体値・period 具体値・スコア計算・規律コード C/S・統計定義・ポジション語彙）
> - [CLAUDE.md](../../../CLAUDE.md) — DB 設計原則（原則 1〜7）

本書は **A（ドメイン配置）／ B（新規テーブル DDL）／ D（enum・多競技対応＝拡張点 `SportEventCatalog` の定義）** を具体化する。
**競技非依存の土台（テーブル＝器・汎用 enum・拡張点の機構）がコア＝本書**、**競技固有のカタログ（中身）は競技別文書（[sports/01_soccer.md](./sports/01_soccer.md)＝サッカー）** に分離する。汎用カラム（`event_type`/`card_reason_code`/`period`/スコア列等）は値の意味付けが競技依存だが、**器としては競技非依存で共通**である。

---

## A. ドメイン配置

### A.1 新規ドメイン `com.mannschaft.app.match`

全試合の記録核として新規ドメインを新設する。tournament ドメインは「match を参照する側」へ作り替える（[05](./05_tournament_integration.md)）。

```
com.mannschaft.app.match/
├── MatchKind.java                 (enum: PRACTICE/FRIENDLY/TOURNAMENT/LEAGUE)
├── TeamSide.java                  (enum: HOME/AWAY)
├── MatchStatus.java               (enum: SCHEDULED/IN_PROGRESS/COMPLETED/POSTPONED/CANCELLED)
├── PeriodType.java                (enum: FIRST_HALF/SECOND_HALF/EXTRA_FIRST/EXTRA_SECOND/PENALTY_SHOOTOUT/QUARTER_1.. 等)
├── MatchEventType.java            (enum: GOAL/ASSIST/SUB_IN/.../OTHER サッカー基本セット＋その他)
├── Sport.java                     (enum: SOCCER/FUTSAL/...（将来拡張）)
├── entity/
│   ├── MatchEntity.java
│   ├── MatchEventEntity.java
│   └── PlayerAppearanceEntity.java
├── repository/
│   ├── MatchRepository.java                 (AbstractTenantAwareRepository 継承)
│   ├── MatchEventRepository.java            (テナント絞り込み無し・match_id スコープ専用)
│   └── PlayerAppearanceRepository.java      (テナント絞り込み無し・match_id スコープ専用)
├── service/
│   ├── MatchService.java                     (試合 CRUD・status 遷移)
│   ├── MatchEventService.java                (イベント記録・lineup・substitution)
│   ├── PlayingTimeCalculationService.java    (出場時間自動算出・02 §E)
│   ├── MatchAccessService.java               (権限・IDOR・03)
│   ├── MatchVisibilityResolver.java          (F00 ContentVisibilityResolver 実装・03 §C.3)
│   └── MatchStatsAggregationService.java     (集計 API・02 §F)
├── controller/
│   ├── MatchController.java
│   ├── MatchEventController.java
│   └── MatchStatsController.java
├── dto/ ...
└── event/
    └── MatchCompletedEvent.java              (順位表導出を tournament が受信・05)
```

### A.2 `matches` を全試合の単一の真実とする

`matches` は **練習(PRACTICE)・親善(FRIENDLY)・大会(TOURNAMENT)・リーグ(LEAGUE)** の全種別を 1 テーブルで保持する。

- **スコアは `matches` が正本**（`home_score` / `away_score`・PK 戦は `home_penalty_score` / `away_penalty_score` で本戦と分離）。tournament 側はスコアを持たない（二重持ち解消）。
- 大会の試合は `matches.tournament_fixture_id`（**BIGINT NULL**）で fixture（旧 `tournament_matches` の縮退形・**BIGINT 据え置き**）にリンクする。
- 順位表・個人ランキングは `matches` 由来のイベント／スコアから導出する（[05](./05_tournament_integration.md)）。

### A.3 ドメイン境界（FK 方針サマリ）

| 参照元 | 参照先 | ドメイン関係 | 方針 |
|--------|--------|--------------|------|
| `matches.organization_id` | organizations.id | match → organization（クロス） | ID 参照のみ（FK なし／原則 1） |
| `matches.team_id` / `opponent_team_id` | teams.id | match → team（クロス） | ID 参照のみ |
| `matches.tournament_fixture_id`（BIGINT） | tournament_fixtures.id（BIGINT） | match → tournament（クロス） | ID 参照のみ |
| `matches.schedule_id`（BIGINT） | schedules.id | match → schedule（クロス・F03.1 連携） | ID 参照のみ |
| `matches.created_by` | users.id | match → user（クロス） | ID 参照のみ |
| `match_events.match_id` | matches.id | match 内（同一ドメイン） | **FK＋ON DELETE CASCADE 可**（原則 2） |
| `match_events.linked_event_id` | match_events.id | match 内（同一テーブル自己参照） | **FK＋ON DELETE SET NULL 可**（同一 match ドメイン・原則 2／連鎖相手を消しても残イベントは保持） |
| `match_events.player_user_id` / `related_player_user_id` | users.id | match → user（クロス） | ID 参照のみ |
| `match_events.recorded_by_team_id` | teams.id | match → team（クロス） | ID 参照のみ |
| `player_appearances.match_id` | matches.id | match 内（同一ドメイン） | **FK＋ON DELETE CASCADE 可**（原則 2） |
| `player_appearances.player_user_id` | users.id | match → user（クロス） | ID 参照のみ |
| `player_appearances.owning_team_id` | teams.id | match → team（クロス） | ID 参照のみ |

### A.4 子テーブルのテナント分離 — 二段アクセスを Service 基底で強制【重要・致命的指摘の根治】

`match_events` / `player_appearances` は **`organization_id` も `deleted_at` も持たない**。
よって「子テーブルも `AbstractTenantAwareRepository` を継承する」という設計は**誤りである**。子テーブルにテナント絞り込みリポジトリを持たせない。

代わりに**二段アクセス**をドメイン Service 基底で強制する。

1. **親 `matches` を必ずテナント絞り込みで取得する**: `matchRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(matchId, orgId)`（`AbstractTenantAwareRepository`・原則 7）。これが 1 段目のテナントゲート。
2. **子は `match_id` スコープでのみ取得する**: `matchEventRepository.findByMatchId(matchId)` 等。**子 ID 直引き（`findById(eventId)`）は禁止**（テナントゲートを素通りし IDOR の温床になる）。
3. 子 ID を指定する API（`PATCH/DELETE /matches/{matchId}/events/{eventId}`）でも、まず親をテナント取得 → 次に `event.match_id == パスの matchId` を検証する（不一致は 404・[03](./03_permissions_and_recording_modes.md) §C.4）。

- 子テーブルは親 `matches` の `ON DELETE CASCADE` で必ず消えるため、**子に `deleted_at`（論理削除）は不要**。親 matches の論理削除に従う（親が論理削除されている間は子も非表示。物理 CASCADE は親の物理削除時のみ作用）。
- この二段アクセスを `MatchAccessService` / `MatchEventService` の基底で定型化し、各 Service が必ず通すことを実装規約とする（独自に子 ID 直引きする経路を作らない）。

---

## B. 新規テーブル DDL

`matches` は **UUIDv7 / BINARY(16)**（原則 6・`UuidV7Entity` 継承）。子テーブルも UUIDv7 / BINARY(16)。同一 match ドメイン内のみ FK CASCADE 可。
**tournament の fixture は BIGINT 据え置き**（原則 6「既存テーブルの BIGINT ID は変更しない」）なので、`matches.tournament_fixture_id` は **BIGINT NULL**（BINARY(16) は誤り）。
Flyway 採番は実体の最新（`V9.20260603000006`）の次として、新規はタイムスタンプ式 `V9.YYYYMMDDHHMMSS__create_xxx.sql` で連番採番する（具体的 SQL ファイルは本設計では作らず命名規則のみ規定。マージ直前に origin/main 最大番号を再確認しリネームする）。

### B.1 `matches`（汎用試合）

```sql
-- 全種別試合の単一レコード（新規テーブル → UUIDv7 / 原則 6）
CREATE TABLE matches (
    id BINARY(16) NOT NULL,                       -- UUIDv7（UuidV7Entity 継承）
    organization_id BIGINT NOT NULL,              -- テナント（organization ドメインへの ID 参照／原則 1・7）
    team_id BIGINT NOT NULL,                       -- 記録/ホーム主体チーム（team ドメイン ID 参照）
    sport VARCHAR(32) NOT NULL DEFAULT 'SOCCER',  -- 競技種別（多競技対応・D §多競技）
    kind VARCHAR(16) NOT NULL,                     -- PRACTICE/FRIENDLY/TOURNAMENT/LEAGUE（enum 文字列）
    tournament_fixture_id BIGINT NULL,            -- 大会リンク（tournament fixture を BIGINT で ID 参照・NULL=単独試合）
    schedule_id BIGINT NULL,                       -- カレンダー連携（F03.1・既存 TournamentMatchEntity.scheduleId から移管・05 §H.4）
    home_away ENUM('HOME','AWAY','NEUTRAL') NOT NULL DEFAULT 'HOME', -- 主体チームのホーム/アウェイ
    opponent_team_id BIGINT NULL,                 -- 登録相手チーム（team ドメイン ID 参照・NULL 可）
    opponent_name VARCHAR(128) NULL,              -- 未登録相手名（opponent_team_id が NULL のとき使用）
    kickoff_at DATETIME NULL,                     -- キックオフ日時（予定/実績）
    venue VARCHAR(200) NULL,                      -- 会場
    duration_minutes SMALLINT UNSIGNED NULL,      -- 試合長（分・前後半90＋延長の試合通算・出場時間 out 既定値に使用・02 §E）
    period_format VARCHAR(32) NULL,               -- 試合形式（'HALVES_45'/'QUARTERS_10' 等・D §PeriodType と対応）
    home_score SMALLINT UNSIGNED NULL,            -- ホーム本戦スコア（正本・延長得点も合算・GOAL 集計と整合チェック・02 §E.2a）
    away_score SMALLINT UNSIGNED NULL,            -- アウェイ本戦スコア（正本・延長得点も合算）
    home_penalty_score SMALLINT UNSIGNED NULL,    -- ホーム PK 戦スコア（本戦と分離・02 §E.2a）
    away_penalty_score SMALLINT UNSIGNED NULL,    -- アウェイ PK 戦スコア（本戦と分離）
    status VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED/IN_PROGRESS/COMPLETED/POSTPONED/CANCELLED
    scorekeeper_user_id BIGINT NULL,              -- 記録係ユーザー（公式戦・user ドメイン ID 参照・03 §C）
    has_scorekeeper BOOLEAN NOT NULL DEFAULT FALSE, -- 記録モード判定（TRUE=公式戦/FALSE=共同記録・03 §C）
    notes TEXT NULL,                              -- 備考
    created_by BIGINT NOT NULL,                   -- 作成者（user ドメイン ID 参照）
    version BIGINT NOT NULL DEFAULT 0,            -- 楽観ロック（@Version・メタ更新専用。イベント/appearances 再計算では触れない・02 §E.2）
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    deleted_at DATETIME NULL,                     -- 論理削除（原則 3）
    PRIMARY KEY (id),
    INDEX idx_matches_org (organization_id, deleted_at),
    INDEX idx_matches_team (team_id, kickoff_at),
    INDEX idx_matches_fixture (tournament_fixture_id),
    INDEX idx_matches_schedule (schedule_id),
    INDEX idx_matches_kind (organization_id, kind, kickoff_at)
);
```

| カラム | 型 | 説明 | ドメイン境界 |
|--------|----|----|-------------|
| id | BINARY(16) | UUIDv7 PK | — |
| organization_id | BIGINT | テナント | クロス → ID 参照（原則 1・7） |
| team_id | BIGINT | 記録主体チーム | クロス → ID 参照 |
| sport | VARCHAR(32) | 競技種別 | — |
| kind | VARCHAR(16) | 試合種別 enum | — |
| tournament_fixture_id | **BIGINT NULL** | 大会 fixture リンク（**BIGINT 据え置き**） | クロス → ID 参照 |
| schedule_id | BIGINT NULL | カレンダー連携（F03.1） | クロス → ID 参照 |
| opponent_team_id / opponent_name | BIGINT NULL / VARCHAR(128) NULL | 相手（登録 or 未登録） | クロス → ID 参照 |
| home_score / away_score | SMALLINT UNSIGNED NULL | 本戦スコア正本 | — |
| home_penalty_score / away_penalty_score | SMALLINT UNSIGNED NULL | PK 戦スコア（本戦と分離） | — |
| status | VARCHAR(16) | 進行状態（POSTPONED 含む） | — |
| scorekeeper_user_id / has_scorekeeper | BIGINT NULL / BOOLEAN | 記録係・記録モード | クロス → ID 参照 |
| version | BIGINT | 楽観ロック（メタ更新専用） | — |
| deleted_at | DATETIME NULL | 論理削除 | — |

> **fixture が BIGINT である理由**: tournament ドメインは全テーブルが `BaseEntity`（BIGINT AUTO_INCREMENT）で構成されており、CLAUDE.md 原則 6 は「**既存テーブルの BIGINT ID は変更しない**」と定める。本機能のために tournament を UUID 全面移行することは超侵襲かつ原則違反になるため、fixture（旧 `tournament_matches` の縮退形）は BIGINT のまま。**matches からは BIGINT で fixture を ID 参照する**（`tournament_fixture_id BIGINT NULL`）。新規 match ドメインは原則 6 に従い UUIDv7、tournament は据え置き、という非対称はクロスドメイン ID 参照（原則 1・FK なし）なので問題ない。

> **enum の保持方式**: 既存 tournament ドメインは `@Enumerated(EnumType.STRING)`（`VARCHAR`）を採用している。本機能もそれに揃え `kind`/`status`/`sport` は `VARCHAR`＋`@Enumerated(STRING)` とする（MySQL `ENUM` 型はマイグレーション拡張時の `ALTER` コストが高いため、拡張が見込まれる列は VARCHAR を採る）。`home_away` のみ値が固定的なので `ENUM` でも可（実装時に統一方針へ寄せる）。

> **延長戦スコアの扱い（MVP 方針・延長別カラムは持たない）**: 既存 `TournamentMatchEntity` は `homeExtraScore`/`awayExtraScore` を本戦と別管理していたが、新 `matches` は**延長別カラムを持たない**。延長中の `GOAL`/`PENALTY_GOAL` イベントは本戦スコア（`home_score`/`away_score`）に**合算**する（サッカーの最終スコア「延長の末 3-2」は 3-2 が正＝合算が正しいセマンティクス）。**PK 戦のみ** `home_penalty_score`/`away_penalty_score` で本戦と別管理する。延長別の内訳が必要になった場合は将来 `match_periods`（ピリオド別スコア子テーブル）で吸収する余地を残す（[05](./05_tournament_integration.md) §H.1 移行表・§未解決 3）。

### B.1.1 MatchStatus 照合表（tournament との整合）【POSTPONED 追加】

既存 `com.mannschaft.app.tournament.MatchStatus` は **5 値**（`SCHEDULED` / `IN_PROGRESS` / `COMPLETED` / `POSTPONED` / `CANCELLED`）である。match 側にも **POSTPONED を追加**して値域を一致させる（fixture 化で tournament status を match 側へ寄せるため・[05](./05_tournament_integration.md) §H.1）。

| match 側 `MatchStatus` | tournament 側 `MatchStatus`（→ fixture が参照） | 意味 |
|------------------------|------------------------------------------------|------|
| SCHEDULED | SCHEDULED | 予定 |
| IN_PROGRESS | IN_PROGRESS | 進行中 |
| COMPLETED | COMPLETED | 終了（確定再計算・順位導出トリガー・02 §E.3） |
| **POSTPONED** | **POSTPONED** | 延期（再日程待ち・順位導出対象外） |
| CANCELLED | CANCELLED | 中止（順位導出対象外） |

### B.2 `match_events`（時系列イベント）

```sql
-- 時系列イベント（match ドメイン内 → 親 matches へ CASCADE 可／原則 2）
-- organization_id / deleted_at は持たない（テナント分離は親 matches・A.4 二段アクセス）
CREATE TABLE match_events (
    id BINARY(16) NOT NULL,                       -- UUIDv7
    match_id BINARY(16) NOT NULL,                 -- matches(id)（同一ドメイン → FK CASCADE 可）
    minute SMALLINT UNSIGNED NULL,                -- 経過分（タイマー連動・手動訂正可・NULL=分不明）
    stoppage_minute SMALLINT UNSIGNED NULL,       -- アディショナルタイム（例 45+2 の "2"・NULL=なし）
    period VARCHAR(24) NOT NULL,                  -- PeriodType（器は競技非依存・具体値は競技別＝サッカーは sports/01_soccer.md §3 の前半/後半/延長/PK 等）
    event_type VARCHAR(24) NOT NULL,             -- MatchEventType（器は競技非依存・許容値は競技別カタログ＝サッカーは sports/01_soccer.md §2 の GOAL/ASSIST/SUB_IN/.../OTHER）
    card_reason_code VARCHAR(8) NULL,            -- 警告/退場の標準理由コード（競技別カタログの列挙値・値の具体はサッカー＝sports/01_soccer.md §5 の C1〜C8 / S1〜S6 / CS）。集計・絞り込み可能にする構造化カラム（器は競技非依存・note は補足の自由記述として併存）。警告/退場系 event_type 以外では NULL
    custom_label VARCHAR(64) NULL,                -- event_type=OTHER（その他）時の自由ラベル名（D・04 §G.2）
    team_side ENUM('HOME','AWAY') NOT NULL,       -- どちらのチームのイベントか
    player_user_id BIGINT NULL,                   -- 主体選手（user ドメイン ID 参照・未登録は NULL）
    player_name VARCHAR(128) NULL,                -- 未登録選手名（player_user_id NULL のとき）
    jersey_number SMALLINT UNSIGNED NULL,         -- 背番号（未登録選手の同一性キーの一部・D 同一性）
    related_player_user_id BIGINT NULL,           -- 関連選手（アシスト者/交代相手・user ドメイン ID 参照）
    related_player_name VARCHAR(128) NULL,        -- 関連未登録選手名
    note VARCHAR(255) NULL,                        -- 理由・メモ自由記述（例「コーナーキックから」「スルーパスから右足」・04 §G.2・03 §C.4b 検証）
    linked_event_id BINARY(16) NULL,             -- 時系列連鎖の相手イベント（同一テーブル自己参照・例 アシスト⤵得点・04 §G.2）
    detail JSON NULL,                             -- 拡張属性（競技別の追加情報・最大 4KB・03 §C.6 検証）
    recorded_by_team_id BIGINT NULL,             -- 記録したチーム（共同記録の権限判定・03 §C・NULL=記録係記録）
    sort_seq INT NOT NULL DEFAULT 0,             -- 同分内の表示順（タイムライン安定ソート）
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_match_events_match (match_id, period, minute, sort_seq),
    INDEX idx_match_events_player (player_user_id),
    INDEX idx_match_events_linked (linked_event_id),
    CONSTRAINT fk_match_events_match FOREIGN KEY (match_id)
      REFERENCES matches (id) ON DELETE CASCADE,  -- 同一 match ドメイン内（原則 2）
    CONSTRAINT fk_match_events_linked FOREIGN KEY (linked_event_id)
      REFERENCES match_events (id) ON DELETE SET NULL  -- 同一テーブル自己参照（同一 match ドメイン・原則 2／連鎖相手削除でも残イベント保持）
);
```

- 交代は **SUB_IN / SUB_OUT を別イベント**として記録する（同分・同 `related_player_*` で対を成す。出場時間算出は 02 §E）。**複数交代・再出場**（一度 OUT した選手が再び IN するケース）にも対応する（02 §E.1）。
- アシストは **GOAL とは独立した固有イベント**（`event_type=ASSIST`・自身の `player_user_id`/`jersey_number`/`note` を持つ）であり、**集計の一意性のため GOAL は得点者・ASSIST は別イベント**を正とする（02 §F で確定）。GOAL と ASSIST の時系列対応は **`linked_event_id`（同一テーブル自己参照）で双方向に連鎖**させる（GOAL→ASSIST でも ASSIST→GOAL でも、2 つの独立イベントを `linked_event_id` で結ぶ。入力フローは [04](./04_frontend_and_ux.md) §G.2・タイムライン表示で連鎖を視覚的に束ねる）。**集計（02 §F）は従来どおり各イベント単体（GOAL=得点者の goals、ASSIST=アシスト者の assists）をカウントする**ため、`linked_event_id` は表示・関連付けのメタ情報であり集計の二重計上を生まない。
- `note VARCHAR(255)` は各イベントの理由・メモ自由記述（例「コーナーキックから」「スルーパスから右足」）。`custom_label VARCHAR(64)` は `event_type=OTHER`（その他）時の自由ラベル名（§D.2・[04](./04_frontend_and_ux.md) §G.2）。いずれもユーザー入力ゆえ入力検証（最大長・制御文字除去・trim・出力時 XSS/CRLF サニタイズ）の対象（[03](./03_permissions_and_recording_modes.md) §C.4b）。
- `card_reason_code VARCHAR(8)` は警告/退場イベントの**標準理由コード（選択式・構造化）の器（競技非依存）**で、`note`（補足の自由記述）と**併存**する（理由コード＝構造化＋補足メモ＝自由記述の両方を 1 イベントに持てる）。**値（コード一覧）は競技固有**であり、サッカーの具体コード一覧（`CautionCode` C1〜C8 / `SendingOffCode` S1〜S6 / CS）と event_type↔コード対応は **競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §5 参照**。サーバー検証規約は汎用に「**その競技カタログ（`match.sport` 紐づき）の列挙値であること、かつ event_type と整合すること**」とする（[03](./03_permissions_and_recording_modes.md) §C.4b）。`event_type` が警告/退場系以外の場合は NULL。コードは固定記号で言語非依存（表示ラベルは i18n・[04](./04_frontend_and_ux.md) §G.6・サッカーの namespace は [sports/01_soccer.md](./sports/01_soccer.md) §9）。
  - **index は必須でない**（カードは試合あたり件数が少なく `idx_match_events_match` で十分）。ただし**規律統計（チーム/選手の警告・退場理由の集計）で横断絞り込みする要件が顕在化したら** `INDEX idx_match_events_card_reason (card_reason_code)` を追加する余地を残す。
- `detail JSON` は競技別拡張（例: バスケのショット位置）に用いる予備領域。サッカー基本セットでは未使用でよい。サイズ上限 4KB・サーバー側スキーマ検証（[03](./03_permissions_and_recording_modes.md) §C.6）。

### B.3 `player_appearances`（出場時間）

```sql
-- 出場時間（match ドメイン内 → 親 matches へ CASCADE 可／原則 2）
-- organization_id / deleted_at は持たない（テナント分離は親 matches・A.4 二段アクセス）
CREATE TABLE player_appearances (
    id BINARY(16) NOT NULL,                       -- UUIDv7
    match_id BINARY(16) NOT NULL,                 -- matches(id)（同一ドメイン → FK CASCADE 可）
    player_user_id BIGINT NULL,                   -- 選手（user ドメイン ID 参照・未登録は NULL）
    player_name VARCHAR(128) NULL,                -- 未登録選手名
    team_side ENUM('HOME','AWAY') NOT NULL,       -- 所属サイド
    is_starter BOOLEAN NOT NULL DEFAULT FALSE,    -- 先発フラグ
    position VARCHAR(30) NULL,                     -- ポジション（器は競技非依存・語彙は競技別＝サッカーは sports/01_soccer.md §7 の GK/DF/MF/FW 等）
    jersey_number SMALLINT UNSIGNED NULL,         -- 背番号（未登録選手の同一性キーの一部）
    first_in_minute SMALLINT UNSIGNED NULL,       -- 最初の出場開始分（STARTER=0 / 初回 SUB_IN・代表値・02 §E.1）
    last_out_minute SMALLINT UNSIGNED NULL,       -- 最後の退場分（代表値・02 §E.1）
    computed_minutes SMALLINT UNSIGNED NULL,      -- 自動算出出場分＝全 in/out 区間の合計（再出場対応・02 §E.1）
    owning_team_id BIGINT NOT NULL,              -- 自チーム編集権限の判定（team ドメイン ID 参照・03 §C）
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    -- 同一試合に同一登録選手は 1 行（未登録選手 player_user_id=NULL は UNIQUE 対象外＝複数可）
    UNIQUE KEY uq_appearance_match_player (match_id, player_user_id),
    INDEX idx_appearance_match (match_id, team_side),
    INDEX idx_appearance_player (player_user_id),
    CONSTRAINT fk_appearance_match FOREIGN KEY (match_id)
      REFERENCES matches (id) ON DELETE CASCADE   -- 同一 match ドメイン内（原則 2）
);
```

- `player_appearances` は **1 選手 1 行のサマリ**を維持しつつ、`computed_minutes` は**全 in/out 区間の合計**で再出場に対応する（02 §E.1）。`first_in_minute` / `last_out_minute` は代表値（タイムライン表示用）。

> **UNIQUE と NULL の扱い**: MySQL の UNIQUE は NULL を重複とみなさないため、`player_user_id=NULL`（未登録選手）の行は複数許容される。登録済み選手は `UNIQUE(match_id, player_user_id)` で 1 試合 1 行（自動算出の upsert キー・02 §E）。未登録選手の同一性は `(jersey_number, player_name, team_side)` をアプリ層キーとして判定する（§D 未登録選手の同一性キー）。

### B.4 DDL まとめ

| 種別 | テーブル | 主キー | 親 FK | organization_id / deleted_at | 原則 |
|------|---------|--------|-------|------------------------------|------|
| 新規 | `matches` | UUIDv7 / BINARY(16) | なし（全クロスドメインは ID 参照・fixture は BIGINT） | あり / あり（AbstractTenantAwareRepository 継承） | 1・3・6・7 |
| 新規 | `match_events` | UUIDv7 / BINARY(16) | `match_id` → matches CASCADE ／ `linked_event_id` → match_events 自己参照 SET NULL | **なし / なし**（親 matches で分離・A.4） | 1・2・6 |
| 新規 | `player_appearances` | UUIDv7 / BINARY(16) | `match_id` → matches CASCADE | **なし / なし**（親 matches で分離・A.4） | 1・2・6 |

---

## D. enum・多競技対応

### D.1 基本 enum（match ドメイン）

```java
public enum MatchKind { PRACTICE, FRIENDLY, TOURNAMENT, LEAGUE }

public enum TeamSide { HOME, AWAY }

// tournament 側 MatchStatus と値域を一致させる（POSTPONED を含む 5 値・B.1.1 照合表）
public enum MatchStatus { SCHEDULED, IN_PROGRESS, COMPLETED, POSTPONED, CANCELLED }

// 試合形式に応じてピリオドを表す（器は競技非依存）。どの period 値を使うかは競技別カタログが定義する。
//   サッカーが使う具体値（FIRST_HALF/SECOND_HALF/EXTRA_FIRST/EXTRA_SECOND/PENALTY_SHOOTOUT）→ sports/01_soccer.md §3 参照。
//   多競技拡張（バスケの QUARTER_1..4/OVERTIME 等）は各競技カタログが使う。
public enum PeriodType {
    // サッカー（競技固有の利用は sports/01_soccer.md §3）
    FIRST_HALF, SECOND_HALF,
    EXTRA_FIRST, EXTRA_SECOND,
    PENALTY_SHOOTOUT,
    // 多競技拡張（バスケ等・各競技カタログが使う period 値）
    QUARTER_1, QUARTER_2, QUARTER_3, QUARTER_4, OVERTIME
}

// 多競技カタログは案 A（enum＋定数）で確定（§D.3 拡張点）。まず SOCCER を実装し、将来 enum を追加する。
public enum Sport { SOCCER /*, FUTSAL, BASKETBALL ...（将来）*/ }
```

> **競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §3 参照**: サッカーがどの `PeriodType` 値を使うか（前半/後半/延長前後半/PK 戦）の具体は競技カタログ側で定義する。`PeriodType` enum 自体（器）はコアに残し、多競技のクォーター等も enum 値として保持する。

> ⚠️ **tournament ドメインに既存の `Match*`（`MatchStatus` / `MatchResult` / `MatchSlot` / `MatchController` / `MatchService`）がある**。本機能の match ドメインで同名 enum/クラスを使うと**名前衝突**が起きる。**tournament 側を `Fixture*` へ改称**して衝突を回避する（[05](./05_tournament_integration.md) §H.4・§H.6）。match 側 `MatchStatus` は POSTPONED を加えて tournament 側と値域を一致させ、fixture は match の status を参照する（B.1.1）。

### D.2 MatchEventType（イベント種別 enum＝競技非依存の器）

`MatchEventType` enum は **全競技のイベントを enum で保持する器（競技非依存）**であり（§D.3 案 A の設計）、`match_events.event_type`（VARCHAR）に格納される。各競技が**どの値を利用できるか**は `SportEventCatalog`（§D.3）で競技別に定義する。

> **競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §2 参照**: サッカーの event_type 具体セット（STARTER/SUB_IN/SUB_OUT/GOAL/ASSIST/OWN_GOAL/PENALTY_GOAL/PENALTY_MISS/PENALTY_SHOOTOUT/YELLOW_CARD/RED_CARD/SECOND_YELLOW/SAVE/INJURY/PERIOD_START/PERIOD_END/OTHER）と、各イベントの**出場時間・スコアへの影響表**は、サッカー競技カタログに集約した。
>
> enum の器（コア）に「サッカー以外の競技が追加する値」が将来加わる場合も、`MatchEventType` enum へ値を追加し各競技カタログ（`SportEventCatalog`）でその競技に紐づける（§D.3・[sports/01_soccer.md](./sports/01_soccer.md) §10 新競技の追加手順）。

なお、出場時間自動算出の**枠組み**（STARTER/SUB_IN/SUB_OUT＋退場で区間を閉じる→duration→computed_minutes・多くの競技で共通の交代・出場時間ロジック）はコア [02](./02_playing_time_and_aggregation.md) §E に残す。具体的 event_type 名で記述される影響表のみサッカー側へ移した。

### D.3 多競技拡張機構（拡張点 `SportEventCatalog`）— 案 A（enum＋定数）で確定【殿裁可】

今回は**サッカーを具体実装**し、他競技は拡張余地として設計のみ行う。多競技カタログの実装方式は **案 A（enum＋コード定数カタログ）で確定**する（DB マスタ化は将来余地）。

- `matches.sport`（VARCHAR）で競技を識別する（まず `SOCCER`）。
- イベント種別カタログは「競技 → 利用可能 `event_type` 集合」を **コード定数 `SportEventCatalog`** で表現する。

| 案 | 内容 | 採否 |
|----|------|------|
| **案 A（enum＋コード定数カタログ）** | `MatchEventType` に全競技のイベントを enum で持ち、`Map<Sport, Set<MatchEventType>>` をコード定数で定義 | **採用（確定）**。DB 変更不要・型安全 |
| 案 B（DB マスタテーブル `sport_event_catalog`） | 競技別イベントをマスタテーブルで保持（原則 6 例外＝マスタテーブルは自然キー可） | 将来余地。SYSTEM_ADMIN が競技を動的追加する要件が顕在化したら移行。その際は CLAUDE.md 原則 6 の**マスタテーブル例外**（自然キー可）に該当する旨を明記 |

**拡張点 `SportEventCatalog`（機構そのもの＝コア）**: 「競技 → 利用可能 `event_type` 集合」を `Map<Sport, Set<MatchEventType>>` のコード定数で表現する。この機構（インターフェース/規約）はコアに置き、**各競技がどの値を持つか（中身）は競技別文書のカタログ**で定義する。

```java
// 案 A（確定）: 競技別イベントカタログ（コード定数）— 機構はコア・中身は競技別
public final class SportEventCatalog {
    public static final Map<Sport, Set<MatchEventType>> CATALOG = Map.of(
        // 各競技の具体集合（正準）は競技別カタログ文書で定義する。ここでは値を列挙しない。
        Sport.SOCCER, SoccerCatalog.EVENT_TYPES   // → 正準: sports/01_soccer.md §2
        // 将来: Sport.BASKETBALL, Sport.FUTSAL ...（各競技カタログ文書が定義・sports/01_soccer.md §10 手順）
    );
}
```

> **正準の所在（単一参照点）**: 各競技が利用する `event_type` の具体集合は **競技別カタログ文書を唯一の正準** とする。サッカーは [sports/01_soccer.md](./sports/01_soccer.md) §2 が正準であり、本書（コア）は集合の具体値を**定義として持たない**（重複・ドリフト防止）。コアが定義するのは「競技ごとに集合を持つ」という**機構のみ**。

- イベント記録時に `event_type ∈ CATALOG.get(match.sport)` を Service で検証する（不正値は 400・症状を隠さず根治）。**この検証規約（競技カタログの列挙値であること）が競技非依存のコア**であり、サッカーの具体列挙値は [sports/01_soccer.md](./sports/01_soccer.md) §2 を正準とする。
- `detail JSON` 列で競技固有の追加属性（バスケのショット座標等）を保持し、コアスキーマを汚さない。
- **新競技の追加手順（拡張点の使い方）= [sports/01_soccer.md](./sports/01_soccer.md) を雛形に複製し差分を書く**（§10 新競技の追加手順）。`Sport` enum 追加 → `SportEventCatalog` にその競技の集合を追加 → 競技別文書（`sports/0N_xxx.md`）に event_type/period/規律コード/統計/ポジション/UX/i18n の差分を記述する。コアのテーブル（器）は一切変更しない。

### D.4 未登録選手（player_user_id=NULL）の同一性キー【殿裁可】

`player_user_id=NULL`（手入力選手）の同一性は、`UNIQUE(match_id, player_user_id)` が効かない（NULL は重複扱いされない）ため、**アプリ層キー `(jersey_number, player_name, team_side)`** で判定する。

- フル再計算 upsert（02 §E.2）はこのキーで**決定性**を担保する（同一試合内で同じ未登録選手のイベントを 1 つの appearance に集約する）。
- **キャリア横断集計（個人統計）は登録ユーザー（`player_user_id` 非 NULL）のみ**を対象とする。未登録選手は `userId` が無いためキャリア横断で名寄せできない。NULL 選手の集計は**その試合内（チーム統計・タイムライン）に限る**（02 §F）。
- アプリ登録への誘導 UX（[04](./04_frontend_and_ux.md)）で未登録選手を緩和する。

### D.5 警告・退場の理由コードカタログ（機構＝コア／具体コード＝競技固有）

警告（Caution）・退場（Sending-off）の理由を**選択式（構造化）の標準コード**で記録できるようにする**機構**を定義する。コードはコアの汎用カラム `match_events.card_reason_code`（§B.2）に保持し、補足の自由記述（既存 `note`）と**両方を併せ持てる**。

- **競技非依存（コア）の確定事項**: (1) `card_reason_code`（構造化・集計/絞り込み用）と `note`（補足の自由記述）は**併存**し、`custom_label`（OTHER 用）・`linked_event_id`（連鎖）とも独立して共存する。(2) いずれのコードも**任意（NULL 可）**で後から補完できる。(3) 理由コードカタログは `SportEventCatalog`（§D.3・案 A）と同じく**競技別カタログ**（`Map<Sport, CardReasonCatalog>`）として `match.sport` に紐づけ、DB マスタ化は将来余地（§D.3 案 B と同方針）。(4) 検証規約は「**その競技カタログの列挙値であること、かつ event_type と整合すること**」（[03](./03_permissions_and_recording_modes.md) §C.4b・汎用表現）。

> **競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §5 参照**: **具体コード一覧はサッカー固有**であり、サッカー競技カタログに集約した。
> - `CautionCode`（警告 C1〜C8）／ `SendingOffCode`（退場 S1〜S6・CS）の enum・短ラベル表
> - `event_type`↔コード群の対応（`YELLOW_CARD`→C 系／`RED_CARD`→S1〜S6／`SECOND_YELLOW`→CS）
> - JFA 競技規則（出典 <https://www.jfa.jp/laws/>）への準拠・公式改定追従の保守方針
>
> **多競技拡張時は競技ごとに別カタログ（理由コード集合）を持つ**こと（バスケのテクニカルファウル等は別体系・[sports/01_soccer.md](./sports/01_soccer.md) §10）。

---

## 未解決事項（全項目解決済み／MVP外の先送り決定を含む）

1. **既存 `tournament_match_player_stats`（EAV）の扱い** — 解決済み（殿裁可）: 基本スタッツ（出場・先発・得点・アシスト・カード）は `match_events` / `player_appearances` へ統合する。大会主催者が任意定義する独自 `statKey`（例: 独自 MVP ポイント）**のみ** tournament 側に `tournament_fixture_stat`（fixture×user×statKey・EAV）として残す（[05](./05_tournament_integration.md) §H.3・§H.6）。
2. **多競技カタログの実装方式** — 解決済み（殿裁可）: **案 A（enum＋定数）で確定**。DB マスタ化（案 B）は将来余地（§D.3）。
3. **未登録選手（player_user_id=NULL）の同一性キー** — 解決済み（殿裁可）: `(jersey_number, player_name, team_side)` をアプリ層キーとする。キャリア横断集計は登録ユーザーのみ、NULL 選手は試合内集計に限る（§D.4・[02](./02_playing_time_and_aggregation.md)）。
4. **`home_away=NEUTRAL`（中立地）時の team_side マッピング** — 解決済み（殿裁可）: 中立地でも主体チームを物理的に **HOME 側 `team_side`** に割り当てる（集計のホーム/アウェイ別成績は `home_away=NEUTRAL` を別カテゴリとして扱い、HOME/AWAY 勝率に混入させない・[02](./02_playing_time_and_aggregation.md) §F.3）。
