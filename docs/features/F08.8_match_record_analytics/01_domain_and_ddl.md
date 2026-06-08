# F08.8 / 01: ドメイン配置・DDL・enum・多競技対応

> **ステータス**: 🟡 設計中
> **最終更新**: 2026-06-08
> **関連機能番号**: F08.8（試合記録・分析）／ F08.7 ／ F08.7.1
> **関連ドキュメント**:
> - [README.md](./README.md) — 機能概要・GoalNote 比較
> - [03_permissions_and_recording_modes.md](./03_permissions_and_recording_modes.md) — `recorded_by_team_id` / `owning_team_id` の権限利用
> - [05_tournament_integration.md](./05_tournament_integration.md) — `tournament_fixture_id` リンク・既存テーブルの作り替え
> - [CLAUDE.md](../../../CLAUDE.md) — DB 設計原則（原則 1〜7）

本書は **A（ドメイン配置）／ B（新規テーブル DDL）／ D（enum・多競技対応）** を具体化する。

---

## A. ドメイン配置

### A.1 新規ドメイン `com.mannschaft.app.match`

全試合の記録核として新規ドメインを新設する。tournament ドメインは「match を参照する側」へ作り替える（[05](./05_tournament_integration.md)）。

```
com.mannschaft.app.match/
├── MatchKind.java                 (enum: PRACTICE/FRIENDLY/TOURNAMENT/LEAGUE)
├── TeamSide.java                  (enum: HOME/AWAY)
├── MatchStatus.java               (enum: SCHEDULED/IN_PROGRESS/COMPLETED/CANCELLED)
├── PeriodType.java                (enum: FIRST_HALF/SECOND_HALF/EXTRA_FIRST/EXTRA_SECOND/PENALTY_SHOOTOUT/QUARTER_1.. 等)
├── MatchEventType.java            (enum: GOAL/ASSIST/SUB_IN/... サッカー基本セット)
├── Sport.java                     (enum: SOCCER/FUTSAL/...（将来拡張）)
├── entity/
│   ├── MatchEntity.java
│   ├── MatchEventEntity.java
│   └── PlayerAppearanceEntity.java
├── repository/
│   ├── MatchRepository.java                 (AbstractTenantAwareRepository 継承)
│   ├── MatchEventRepository.java
│   └── PlayerAppearanceRepository.java
├── service/
│   ├── MatchService.java                     (試合 CRUD・status 遷移)
│   ├── MatchEventService.java                (イベント記録・lineup・substitution)
│   ├── PlayingTimeCalculationService.java    (出場時間自動算出・02 §E)
│   ├── MatchAccessService.java               (権限・IDOR・03)
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

- **スコアは `matches` が正本**（`home_score` / `away_score`）。tournament 側はスコアを持たない（二重持ち解消）。
- 大会の試合は `matches.tournament_fixture_id` で fixture（旧 `tournament_matches` の縮退形）にリンクする。
- 順位表・個人ランキングは `matches` 由来のイベント／スコアから導出する（[05](./05_tournament_integration.md)）。

### A.3 ドメイン境界（FK 方針サマリ）

| 参照元 | 参照先 | ドメイン関係 | 方針 |
|--------|--------|--------------|------|
| `matches.organization_id` | organizations.id | match → organization（クロス） | ID 参照のみ（FK なし／原則 1） |
| `matches.team_id` / `opponent_team_id` | teams.id | match → team（クロス） | ID 参照のみ |
| `matches.tournament_fixture_id` | tournament_fixtures.id | match → tournament（クロス） | ID 参照のみ |
| `matches.created_by` | users.id | match → user（クロス） | ID 参照のみ |
| `match_events.match_id` | matches.id | match 内（同一ドメイン） | **FK＋ON DELETE CASCADE 可**（原則 2） |
| `match_events.player_user_id` / `related_player_user_id` | users.id | match → user（クロス） | ID 参照のみ |
| `match_events.recorded_by_team_id` | teams.id | match → team（クロス） | ID 参照のみ |
| `player_appearances.match_id` | matches.id | match 内（同一ドメイン） | **FK＋ON DELETE CASCADE 可**（原則 2） |
| `player_appearances.player_user_id` | users.id | match → user（クロス） | ID 参照のみ |
| `player_appearances.owning_team_id` | teams.id | match → team（クロス） | ID 参照のみ |

---

## B. 新規テーブル DDL

全テーブル **UUIDv7 / BINARY(16)**（原則 6・`UuidV7Entity` 継承）。同一 match ドメイン内のみ FK CASCADE 可。
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
    tournament_fixture_id BINARY(16) NULL,        -- 大会リンク（tournament ドメイン ID 参照・NULL=単独試合）
    home_away ENUM('HOME','AWAY','NEUTRAL') NOT NULL DEFAULT 'HOME', -- 主体チームのホーム/アウェイ
    opponent_team_id BIGINT NULL,                 -- 登録相手チーム（team ドメイン ID 参照・NULL 可）
    opponent_name VARCHAR(128) NULL,              -- 未登録相手名（opponent_team_id が NULL のとき使用）
    kickoff_at DATETIME NULL,                     -- キックオフ日時（予定/実績）
    venue VARCHAR(200) NULL,                      -- 会場
    duration_minutes SMALLINT UNSIGNED NULL,      -- 試合長（分・出場時間 out 既定値に使用・02 §E）
    period_format VARCHAR(32) NULL,               -- 試合形式（'HALVES_45'/'QUARTERS_10' 等・D §PeriodType と対応）
    home_score SMALLINT UNSIGNED NULL,            -- ホームスコア（正本・GOAL 集計と整合チェック・02 §E）
    away_score SMALLINT UNSIGNED NULL,            -- アウェイスコア（正本）
    status VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED/IN_PROGRESS/COMPLETED/CANCELLED
    scorekeeper_user_id BIGINT NULL,              -- 記録係ユーザー（公式戦・user ドメイン ID 参照・03 §C）
    has_scorekeeper BOOLEAN NOT NULL DEFAULT FALSE, -- 記録モード判定（TRUE=公式戦/FALSE=共同記録・03 §C）
    notes TEXT NULL,                              -- 備考
    created_by BIGINT NOT NULL,                   -- 作成者（user ドメイン ID 参照）
    version BIGINT NOT NULL DEFAULT 0,            -- 楽観ロック（@Version）
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    deleted_at DATETIME NULL,                     -- 論理削除（原則 3）
    PRIMARY KEY (id),
    INDEX idx_matches_org (organization_id, deleted_at),
    INDEX idx_matches_team (team_id, kickoff_at),
    INDEX idx_matches_fixture (tournament_fixture_id),
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
| tournament_fixture_id | BINARY(16) NULL | 大会 fixture リンク | クロス → ID 参照 |
| opponent_team_id / opponent_name | BIGINT NULL / VARCHAR(128) NULL | 相手（登録 or 未登録） | クロス → ID 参照 |
| home_score / away_score | SMALLINT UNSIGNED NULL | スコア正本 | — |
| status | VARCHAR(16) | 進行状態 | — |
| scorekeeper_user_id / has_scorekeeper | BIGINT NULL / BOOLEAN | 記録係・記録モード | クロス → ID 参照 |
| version | BIGINT | 楽観ロック | — |
| deleted_at | DATETIME NULL | 論理削除 | — |

> **enum の保持方式**: 既存 tournament ドメインは `@Enumerated(EnumType.STRING)`（`VARCHAR`）を採用している。本機能もそれに揃え `kind`/`status`/`sport` は `VARCHAR`＋`@Enumerated(STRING)` とする（MySQL `ENUM` 型はマイグレーション拡張時の `ALTER` コストが高いため、拡張が見込まれる列は VARCHAR を採る）。`home_away` のみ値が固定的なので `ENUM` でも可（実装時に統一方針へ寄せる）。

### B.2 `match_events`（時系列イベント）

```sql
-- 時系列イベント（match ドメイン内 → 親 matches へ CASCADE 可／原則 2）
CREATE TABLE match_events (
    id BINARY(16) NOT NULL,                       -- UUIDv7
    match_id BINARY(16) NOT NULL,                 -- matches(id)（同一ドメイン → FK CASCADE 可）
    minute SMALLINT UNSIGNED NULL,                -- 経過分（タイマー連動・手動訂正可・NULL=分不明）
    stoppage_minute SMALLINT UNSIGNED NULL,       -- アディショナルタイム（例 45+2 の "2"・NULL=なし）
    period VARCHAR(24) NOT NULL,                  -- PeriodType（前半/後半/延長/PK 等・D §PeriodType）
    event_type VARCHAR(24) NOT NULL,             -- MatchEventType（GOAL/ASSIST/SUB_IN/... ・D）
    team_side ENUM('HOME','AWAY') NOT NULL,       -- どちらのチームのイベントか
    player_user_id BIGINT NULL,                   -- 主体選手（user ドメイン ID 参照・未登録は NULL）
    player_name VARCHAR(128) NULL,                -- 未登録選手名（player_user_id NULL のとき）
    related_player_user_id BIGINT NULL,           -- 関連選手（アシスト者/交代相手・user ドメイン ID 参照）
    related_player_name VARCHAR(128) NULL,        -- 関連未登録選手名
    detail JSON NULL,                             -- 拡張属性（競技別の追加情報・D §多競技）
    recorded_by_team_id BIGINT NULL,             -- 記録したチーム（共同記録の権限判定・03 §C・NULL=記録係記録）
    sort_seq INT NOT NULL DEFAULT 0,             -- 同分内の表示順（タイムライン安定ソート）
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_match_events_match (match_id, period, minute, sort_seq),
    INDEX idx_match_events_player (player_user_id),
    CONSTRAINT fk_match_events_match FOREIGN KEY (match_id)
      REFERENCES matches (id) ON DELETE CASCADE   -- 同一 match ドメイン内（原則 2）
);
```

- 交代は **SUB_IN / SUB_OUT を別イベント**として記録する（同分・同 `related_player_*` で対を成す。出場時間算出は 02 §E）。
- アシストは GOAL とは別イベント（`event_type=ASSIST`・`related_player_user_id` に得点者）でも、GOAL イベントの `related_player_user_id` にアシスト者を入れる方式でもよいが、**集計の一意性のため GOAL は得点者・ASSIST は別イベント**を正とする（02 §F で確定）。
- `detail JSON` は競技別拡張（例: バスケのショット位置）に用いる予備領域。サッカー基本セットでは未使用でよい。

### B.3 `player_appearances`（出場時間）

```sql
-- 出場時間（match ドメイン内 → 親 matches へ CASCADE 可／原則 2）
CREATE TABLE player_appearances (
    id BINARY(16) NOT NULL,                       -- UUIDv7
    match_id BINARY(16) NOT NULL,                 -- matches(id)（同一ドメイン → FK CASCADE 可）
    player_user_id BIGINT NULL,                   -- 選手（user ドメイン ID 参照・未登録は NULL）
    player_name VARCHAR(128) NULL,                -- 未登録選手名
    team_side ENUM('HOME','AWAY') NOT NULL,       -- 所属サイド
    is_starter BOOLEAN NOT NULL DEFAULT FALSE,    -- 先発フラグ
    position VARCHAR(30) NULL,                     -- ポジション
    jersey_number SMALLINT UNSIGNED NULL,         -- 背番号
    in_minute SMALLINT UNSIGNED NULL,             -- 出場開始分（STARTER=0 / SUB_IN=その分・02 §E）
    out_minute SMALLINT UNSIGNED NULL,            -- 退場分（SUB_OUT/RED/2nd YELLOW=その分 / なければ duration）
    computed_minutes SMALLINT UNSIGNED NULL,      -- 自動算出出場分 = max(0, out - in)（02 §E）
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

> **UNIQUE と NULL の扱い**: MySQL の UNIQUE は NULL を重複とみなさないため、`player_user_id=NULL`（未登録選手）の行は複数許容される。登録済み選手は `UNIQUE(match_id, player_user_id)` で 1 試合 1 行（自動算出の upsert キー・02 §E）。未登録選手の同一性は `player_name`＋`owning_team_id`＋`team_side` でアプリ層が判定する（02 §E 未解決事項参照）。

### B.4 DDL まとめ

| 種別 | テーブル | 主キー | 親 FK | 原則 |
|------|---------|--------|-------|------|
| 新規 | `matches` | UUIDv7 / BINARY(16) | なし（全クロスドメインは ID 参照） | 1・3・6・7 |
| 新規 | `match_events` | UUIDv7 / BINARY(16) | `match_id` → matches CASCADE | 1・2・6 |
| 新規 | `player_appearances` | UUIDv7 / BINARY(16) | `match_id` → matches CASCADE | 1・2・6 |

---

## D. enum・多競技対応

### D.1 基本 enum（match ドメイン）

```java
public enum MatchKind { PRACTICE, FRIENDLY, TOURNAMENT, LEAGUE }

public enum TeamSide { HOME, AWAY }

public enum MatchStatus { SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED }

// 試合形式に応じてピリオドを表す。サッカーは前後半＋延長＋PK。多競技でクォーター等を追加。
public enum PeriodType {
    FIRST_HALF, SECOND_HALF,
    EXTRA_FIRST, EXTRA_SECOND,
    PENALTY_SHOOTOUT,
    // 多競技拡張（バスケ等）
    QUARTER_1, QUARTER_2, QUARTER_3, QUARTER_4, OVERTIME
}
```

> ⚠️ **tournament ドメインにも `MatchStatus` / `MatchResult` / `MatchSlot` が存在する**（`com.mannschaft.app.tournament.MatchStatus` 等）。本機能の match ドメインで同名 enum を新設するとパッケージは別だが混同しやすい。**完全修飾名で扱う**ことを実装規約とし、tournament 側 `MatchStatus`（SCHEDULED/COMPLETED 等）と match 側 `MatchStatus` は意味が近いため、[05](./05_tournament_integration.md) で「match 側へ寄せ、tournament 側 fixture は match の status を参照する」方針を採る。

### D.2 MatchEventType（サッカー基本セット）

```java
public enum MatchEventType {
    // 出場・交代
    STARTER,          // 先発（appearances 生成・in=0）
    SUB_IN,           // 交代 IN（appearances 生成・in=その分）
    SUB_OUT,          // 交代 OUT（out=その分）
    // 得点
    GOAL,             // 得点
    ASSIST,           // アシスト（GOAL とは別イベント）
    OWN_GOAL,         // オウンゴール（相手スコアに加算・02 §E）
    PENALTY_GOAL,     // PK 成功（得点に加算）
    PENALTY_MISS,     // PK 失敗
    // カード（退場は out 確定に使用・02 §E）
    YELLOW_CARD,
    RED_CARD,         // 一発退場（out=その分）
    SECOND_YELLOW,    // 2 枚目の警告＝退場（out=その分）
    // その他
    SAVE,             // GK セーブ
    INJURY,           // 負傷
    PERIOD_START,     // ピリオド開始（タイマー基準）
    PERIOD_END        // ピリオド終了
}
```

| イベント | 出場時間への影響（02 §E） | スコアへの影響（02 §E） |
|----------|--------------------------|------------------------|
| STARTER | in=0 の appearance を生成 | — |
| SUB_IN | in=minute の appearance を生成 | — |
| SUB_OUT | out=minute をセット | — |
| GOAL / PENALTY_GOAL | — | 当該 team_side のスコア +1 |
| OWN_GOAL | — | **相手** team_side のスコア +1 |
| RED_CARD / SECOND_YELLOW | out=minute（退場で出場終了） | — |
| YELLOW_CARD / SAVE / INJURY / ASSIST / PENALTY_MISS | — | — |

### D.3 多競技拡張機構

今回は**サッカーを具体実装**し、他競技は拡張余地として設計のみ行う。

- `matches.sport`（VARCHAR）で競技を識別する。
- イベント種別カタログは「競技 → 利用可能 `event_type` 集合」のマッピングで表現する。実装方式は次の 2 案。

| 案 | 内容 | 長所 | 短所 |
|----|------|------|------|
| **案 A（enum＋コード定数カタログ）** | `MatchEventType` に全競技のイベントを enum で持ち、`Map<Sport, Set<MatchEventType>>` をコード定数で定義 | DB 変更不要・型安全 | 新競技追加でデプロイ必要 |
| **案 B（DB マスタテーブル `sport_event_catalog`）** | 競技別イベントをマスタテーブルで保持（原則 6 例外＝マスタテーブルは自然キー可） | デプロイ無しで競技追加 | EAV 化・型安全性低下 |

**本設計の推奨は案 A**（サッカー実装段階では型安全・DB シンプルを優先）。将来 SYSTEM_ADMIN が競技を動的追加する要件が顕在化したら案 B（マスタテーブル）へ移行する。マスタテーブルにする場合は CLAUDE.md 原則 6 の**マスタテーブル例外**（自然キー可）に該当する旨を明記する。

```java
// 案 A: 競技別イベントカタログ（コード定数）
public final class SportEventCatalog {
    public static final Map<Sport, Set<MatchEventType>> CATALOG = Map.of(
        Sport.SOCCER, EnumSet.of(STARTER, SUB_IN, SUB_OUT, GOAL, ASSIST, OWN_GOAL,
                                 PENALTY_GOAL, PENALTY_MISS, YELLOW_CARD, RED_CARD,
                                 SECOND_YELLOW, SAVE, INJURY, PERIOD_START, PERIOD_END)
        // 将来: Sport.BASKETBALL, Sport.FUTSAL ...
    );
}
```

- イベント記録時に `event_type ∈ CATALOG.get(match.sport)` を Service で検証する（不正値は 400・症状を隠さず根治）。
- `detail JSON` 列で競技固有の追加属性（バスケのショット座標等）を保持し、コアスキーマを汚さない。

---

## 未解決事項

1. **既存 `tournament_match_player_stats`（EAV）の扱い**: 基本スタッツ（出場・先発・得点・アシスト）は `match_events` / `player_appearances` へ統合するが、大会主催者が任意定義する独自 `statKey`（例: 独自の MVP ポイント）を残すか。案: match ドメインには持ち込まず、大会固有の任意項目だけ tournament 側に `tournament_fixture_stat`（fixture×user×statKey）として残す余地を [05](./05_tournament_integration.md) で扱う。最終線引きは殿の精査待ち。
2. **多競技カタログの実装方式**: 案 A（enum＋定数）/ 案 B（マスタテーブル）の確定。サッカー単独実装段階では案 A で十分だが、要件次第で B。
3. **未登録選手（player_user_id=NULL）の同一性キー**: appearances の UNIQUE が効かないため、`player_name`＋`team_side`＋`owning_team_id` をアプリ層キーとするが、同名同チームの未登録選手が複数いる場合の扱い（背番号で識別するか）を [02](./02_playing_time_and_aggregation.md) と整合させて確定する必要がある。
4. **`home_away=NEUTRAL`（中立地）時の team_side マッピング**: 主体チームが必ず HOME 側 `team_side` になるのか、中立地でも HOME/AWAY を物理的に割り当てるのか。集計（ホーム/アウェイ別成績）に影響するため確定が必要。
