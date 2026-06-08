# F08.10 / sports / 01: サッカー競技カタログ（SOCCER）

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-08
> **位置づけ**: **F08.10 コアを継承するサッカー競技カタログ**。コア（競技非依存の土台＋拡張点 `SportEventCatalog`）の上に、サッカー固有の「記録すべき内容」（イベント種別・ピリオド・スコア計算・規律コード・統計定義・ポジション語彙・画面細部・i18n）を定義する。
> **関連機能番号**: F08.10（試合記録・分析）／ F08.7 ／ F08.7.1 ／ F07.2 ／ F19.1
> **関連ドキュメント（コア）**:
> - [../README.md](../README.md) — 機能概要・GoalNote 比較・機能ステータス表・インデックス
> - [../01_domain_and_ddl.md](../01_domain_and_ddl.md) — ドメイン配置・DDL（汎用の器）・enum（汎用）・**拡張点 `SportEventCatalog` の機構**（§D.3）
> - [../02_playing_time_and_aggregation.md](../02_playing_time_and_aggregation.md) — 出場時間自動算出の枠組み・集計 API の枠組み
> - [../03_permissions_and_recording_modes.md](../03_permissions_and_recording_modes.md) — 記録モード・編集権限・IDOR・F00 可視性・入力検証の枠組み
> - [../04_frontend_and_ux.md](../04_frontend_and_ux.md) — ライブ入力 UX の骨格・チャート枠組み・composable 配置
> - [../05_tournament_integration.md](../05_tournament_integration.md) — tournament 統合・順位導出・勝敗判定の枠組み

---

## §1 概要 — コアとの関係・`SportEventCatalog` の実体

F08.10 は**競技ごとに「記録すべき内容」が異なる**（サッカー／バスケ／バレー／野球…）が、**テーブル（器）は競技非依存で共通**、**カタログ（中身）だけ競技別**という構造を採る（コア [../README.md](../README.md) §1・[../01_domain_and_ddl.md](../01_domain_and_ddl.md) §D.3）。

- **コア（F08.10）** = 競技非依存の土台（`matches`/`match_events`/`player_appearances` の汎用カラム・汎用 enum・出場時間算出の枠組み・集計 API の枠組み・記録モード/権限/IDOR/F00 可視性/入力検証の枠組み・ライブ入力 UX の骨格）＋**拡張点 `SportEventCatalog` の定義**。
- **本書（sports/01_soccer.md）** = サッカー固有のカタログ（コアの拡張点に差し込む具体値）。

本書はコアの **拡張点 `SportEventCatalog`（[../01_domain_and_ddl.md](../01_domain_and_ddl.md) §D.3・案 A＝enum＋コード定数カタログ）の `Sport.SOCCER` 実体**である。サッカーを F08.10 の最初の具体実装（MVP の対象競技）とし、本書を雛形に他競技（02_basketball.md 等）を複製・差分記述することで多競技拡張する（§10 新競技の追加手順）。

`matches.sport`（VARCHAR・既定 `'SOCCER'`）で競技を識別し、Service は `event_type ∈ SportEventCatalog.CATALOG.get(match.sport)` を検証する（コア [../01_domain_and_ddl.md](../01_domain_and_ddl.md) §D.3）。本書で定義する event_type／period／規律コードは、すべてこの `Sport.SOCCER` カタログに紐づく。

---

## §2 event_type カタログ（サッカー）

コアの `matches.match_events.event_type`（VARCHAR・汎用の器）に対し、**サッカーで利用可能な `MatchEventType` の具体集合**を定義する（コア [../01_domain_and_ddl.md](../01_domain_and_ddl.md) §D.2 から抽出）。

```java
// サッカー固有の event_type 集合（コア MatchEventType enum のうち SOCCER カタログが許容する値）
public enum MatchEventType {
    // 出場・交代
    STARTER,          // 先発（appearances 生成・in=0）
    SUB_IN,           // 交代 IN（appearances 生成・in=その分。再出場も同じ）
    SUB_OUT,          // 交代 OUT（out=その分）
    // 得点
    GOAL,             // 得点（本戦）
    ASSIST,           // アシスト（GOAL とは別イベント）
    OWN_GOAL,         // オウンゴール（相手スコアに加算・§4・コア 02 §E）
    PENALTY_GOAL,     // PK 成功（本戦得点に加算）
    PENALTY_MISS,     // PK 失敗（本戦）
    PENALTY_SHOOTOUT, // PK 戦の 1 本（home/away_penalty_score へ・本戦集計対象外・§4・コア 02 §E.2）
    // カード（退場は out 確定に使用・コア 02 §E）
    YELLOW_CARD,
    RED_CARD,         // 一発退場（out=その分）
    SECOND_YELLOW,    // 2 枚目の警告＝退場（out=その分）
    // その他
    SAVE,             // GK セーブ
    INJURY,           // 負傷
    PERIOD_START,     // ピリオド開始（タイマー基準）
    PERIOD_END,       // ピリオド終了
    OTHER             // その他（プリセット外のイベント・custom_label に自由ラベル名・note に理由メモ）
}
```

```java
// 案 A（コア §D.3）の SOCCER カタログ実体（コード定数）
public final class SportEventCatalog {
    public static final Map<Sport, Set<MatchEventType>> CATALOG = Map.of(
        Sport.SOCCER, EnumSet.of(STARTER, SUB_IN, SUB_OUT, GOAL, ASSIST, OWN_GOAL,
                                 PENALTY_GOAL, PENALTY_MISS, PENALTY_SHOOTOUT,
                                 YELLOW_CARD, RED_CARD, SECOND_YELLOW,
                                 SAVE, INJURY, PERIOD_START, PERIOD_END, OTHER)
        // 将来: Sport.BASKETBALL, Sport.FUTSAL ...（§10 新競技の追加手順）
    );
}
```

### §2.1 event_type の出場時間・スコアへの影響（サッカー）

出場時間自動算出の枠組み（コア [../02_playing_time_and_aggregation.md](../02_playing_time_and_aggregation.md) §E）・スコア計算（§4）に対する、**サッカー event_type ごとの具体的影響**。

| イベント | 出場時間への影響（コア 02 §E） | スコアへの影響（§4・コア 02 §E） |
|----------|--------------------------|------------------------|
| STARTER | in=0 の appearance を生成 | — |
| SUB_IN | in=minute の出場区間を開始（再出場も区間を追加） | — |
| SUB_OUT | out=minute をセット（区間を閉じる） | — |
| GOAL / PENALTY_GOAL | — | 当該 team_side の**本戦**スコア +1 |
| OWN_GOAL | — | **相手** team_side の**本戦**スコア +1 |
| PENALTY_SHOOTOUT | — | 当該 team_side の **PK 戦**スコア +1（本戦集計対象外・§4・コア 02 §E.2） |
| RED_CARD / SECOND_YELLOW | out=minute（退場で出場区間を閉じる） | — |
| YELLOW_CARD / SAVE / INJURY / ASSIST / PENALTY_MISS | — | — |
| OTHER | — | — （`custom_label`＋`note` で内容を記述・スコア/出場時間に影響しない） |

- 交代は **SUB_IN / SUB_OUT を別イベント**として記録し、同分・同 `related_player_*` で対を成す。**複数交代・再出場**（一度 OUT した選手が再び IN する）にも対応する（コア 02 §E.1）。
- アシストは **GOAL とは独立した固有イベント**であり、GOAL と ASSIST の時系列対応は `linked_event_id`（自己参照）で双方向に連鎖させる。集計は各イベント単体（GOAL=得点者、ASSIST=アシスト者）をカウントし二重計上しない（コア [../01_domain_and_ddl.md](../01_domain_and_ddl.md) §B.2・[../02_playing_time_and_aggregation.md](../02_playing_time_and_aggregation.md) §F）。
- 退場（RED_CARD / SECOND_YELLOW）は以降ピッチに居ないため当該区間の `out` を確定させる（コア 02 §E.1）。

---

## §3 period モデル（サッカー）

コアの `match_events.period`（VARCHAR・汎用の器）と汎用 `PeriodType` enum に対し、**サッカーで用いるピリオドの具体値**を定義する（コア [../01_domain_and_ddl.md](../01_domain_and_ddl.md) §D.1 PeriodType・[../04_frontend_and_ux.md](../04_frontend_and_ux.md) タイマー状態機械から抽出）。

```java
// サッカーで用いる PeriodType の具体値（コア PeriodType enum のうち SOCCER が使う値）
//   FIRST_HALF, SECOND_HALF      — 前半・後半
//   EXTRA_FIRST, EXTRA_SECOND    — 延長前半・延長後半
//   PENALTY_SHOOTOUT             — PK 戦（分概念なし）
// （HALF_TIME は UI タイマー状態機械のハーフタイム停止状態として扱う・§8 タイマー状態機械）
```

| period 値 | 意味 | スコア計算（§4） |
|-----------|------|------------------|
| FIRST_HALF | 前半 | 本戦スコアへ合算 |
| SECOND_HALF | 後半 | 本戦スコアへ合算 |
| EXTRA_FIRST | 延長前半 | 本戦スコアへ**合算**（延長別カラムは持たない・§4） |
| EXTRA_SECOND | 延長後半 | 本戦スコアへ**合算** |
| PENALTY_SHOOTOUT | PK 戦 | `home/away_penalty_score` にのみ加算（本戦対象外・§4） |

- **試合形式**: `matches.period_format`（汎用カラム）にサッカーは `'HALVES_45'`（前後半 45 分）等を入れる（コア [../01_domain_and_ddl.md](../01_domain_and_ddl.md) §B.1）。延長ありの試合は `duration_minutes=120`（前後半 90＋延長 30）等で試合通算分を表す（コア 02 §E.1）。
- 多競技拡張（バスケ等）の `QUARTER_1..4` / `OVERTIME` はコア `PeriodType` enum に値として存在するが**サッカーでは使わない**（各競技カタログがどの period を使うかを定義する）。

---

## §4 スコア計算・勝敗判定（サッカー）

コアの汎用スコアカラム（`home_score`/`away_score`/`home_penalty_score`/`away_penalty_score`・[../01_domain_and_ddl.md](../01_domain_and_ddl.md) §B.1）に対する、**サッカーのスコア計算ルール**（コア 01 §B.1 延長戦スコアの扱い・[../02_playing_time_and_aggregation.md](../02_playing_time_and_aggregation.md) §E.2a・[../05_tournament_integration.md](../05_tournament_integration.md) 勝敗判定から抽出）。

### §4.1 本戦スコアと PK 戦スコアの分離

- **本戦スコア（`home_score`/`away_score`）と PK 戦スコア（`home_penalty_score`/`away_penalty_score`）は別**（コア 01 §B.1）。
- **延長別カラムは持たない**。延長中の `GOAL`/`PENALTY_GOAL` は本戦スコア（`home_score`/`away_score`）に**合算**する（`period`＝`EXTRA_FIRST`/`EXTRA_SECOND` に関わらず）。サッカーの最終スコア「延長の末 3-2」は 3-2 が正＝合算が正しいセマンティクス（コア 01 §B.1 延長戦スコアの扱い・02 §E.2a）。
- **PK 戦（`PENALTY_SHOOTOUT` イベント）のみ本戦スコア集計の対象外**であり、`home/away_penalty_score` にのみ加算する。個人キャリアの `goals` にも PK 戦は含めない（本戦得点＝延長得点を含む・§6）。
- 延長別の内訳が必要になった場合は将来 `match_periods`（ピリオド別スコア子テーブル）で吸収する余地を残す（コア [../05_tournament_integration.md](../05_tournament_integration.md) §未解決 3）。

### §4.2 本戦スコア整合チェックの突合式（サッカー）

コアの「スコア整合チェック（握りつぶさない）」の枠組み（コア [../02_playing_time_and_aggregation.md](../02_playing_time_and_aggregation.md) §E.5）に対する、サッカーの突合式。

- `matches.home_score`/`away_score`（本戦正本キャッシュ）と、`match_events` の **GOAL ＋ PENALTY_GOAL（自サイド・本戦＝延長を含む）＋ 相手の OWN_GOAL** を集計した値を比較する（**PK 戦 `PENALTY_SHOOTOUT` は対象外**）。
- OWN_GOAL は**相手サイドの本戦スコアに加算**して集計する（§2.1 表）。
- 不一致時は例外で握りつぶさず警告を返す（スコアの正本は `home_score`＝記録係が最終確定。イベントは抜け漏れがあり得るため自動で書き換えず乖離を可視化・コア 02 §E.5）。

### §4.3 勝敗判定（W/D/L）

- チーム統計（コア 02 §F.3）・順位導出（コア [../05_tournament_integration.md](../05_tournament_integration.md) §H.2）の勝敗（W/D/L）は、`team_side` と本戦スコア（`home_score`/`away_score`）から判定する。
- 本戦同点で PK 戦が行われた場合の最終的な勝敗（PK 勝ち/負け）は、`home_penalty_score`/`away_penalty_score` で判定する（大会のレギュレーションに従う）。順位寄与は tournament 側の `result`/`winner_participant_id` スナップショットへ反映される（コア 05 §H.2.3）。
- `home_away=NEUTRAL`（中立地）は HOME/AWAY 別成績に混入させず別カテゴリで扱う（コア [../01_domain_and_ddl.md](../01_domain_and_ddl.md) §未解決 4）。

---

## §5 規律コード（サッカー・JFA 競技規則 標準）

警告（Caution）・退場（Sending-off）の理由を**選択式（構造化）の標準コード**で記録する。コードはコアの汎用カラム `match_events.card_reason_code`（VARCHAR(8)・[../01_domain_and_ddl.md](../01_domain_and_ddl.md) §B.2）に保持し、補足の自由記述（`note`）と**両方を併せ持てる**（コア 01 §D.5 から抽出）。

**これはサッカー固有のカタログ**であり、競技別カタログ（`SportEventCatalog`・コア §D.3）の一部として `Sport.SOCCER` に紐づく。**多競技拡張時は競技ごとに別カタログ（理由コード集合）を持つ**こと（バスケのテクニカルファウル等は別体系・§10）。

> ⚠️ **保守方針（記憶ではなく公式準拠）**: 本カタログは **JFA 競技規則（出典: <https://www.jfa.jp/laws/>）の標準コード**に基づく。競技規則は毎年改定されうるため、**JFA 公式競技規則の改定に追従して保守**すること。実装時には**最新の JFA 公式競技規則と必ず照合**してから列挙値を確定する（本設計書の記載は起草時点の標準であり、唯一の正本は JFA 公式競技規則である）。

### §5.1 警告（Caution）— `CautionCode` C1〜C8

```java
// サッカー固有: 警告の理由コード（JFA 競技規則 標準・YELLOW_CARD / SECOND_YELLOW に紐づく）
public enum CautionCode {
    C1, // 反スポーツ的行為
    C2, // ラフプレー
    C3, // 異議（言葉・行動による）
    C4, // 繰り返しの違反
    C5, // 遅延行為
    C6, // 距離不足（コーナーキック/フリーキック/スローインの規定距離を守らない）
    C7, // 無許可入（主審の承認を得ずにフィールドへ入る・復帰する）
    C8  // 無許可去（主審の承認を得ずにフィールドから離れる）
}
```

| コード | 理由（短ラベル） |
|--------|------------------|
| C1 | 反スポーツ的行為 |
| C2 | ラフプレー |
| C3 | 異議 |
| C4 | 繰り返しの違反 |
| C5 | 遅延行為 |
| C6 | 距離不足 |
| C7 | 無許可入 |
| C8 | 無許可去 |

### §5.2 退場（Sending-off）— `SendingOffCode` S1〜S6・CS

```java
// サッカー固有: 退場の理由コード（JFA 競技規則 標準・RED_CARD / SECOND_YELLOW に紐づく）
public enum SendingOffCode {
    S1, // 著しく不正なプレー
    S2, // 乱暴な行為
    S3, // つば（人に唾を吐く）
    S4, // 得点機会阻止（意図的なハンドリングによる）
    S5, // 得点機会阻止（その他のファウルによる）
    S6, // 侮辱（攻撃的・侮辱的・下品な発言や身振り）
    CS  // 警告 2 回（2 枚目の警告による退場＝SECOND_YELLOW に対応）
}
```

| コード | 理由（短ラベル） |
|--------|------------------|
| S1 | 著しく不正なプレー |
| S2 | 乱暴な行為 |
| S3 | つば吐き |
| S4 | 得点機会阻止（手） |
| S5 | 得点機会阻止（その他） |
| S6 | 侮辱 |
| CS | 警告 2 回（2 枚目の警告による退場） |

### §5.3 event_type とコード群の対応

`card_reason_code` に許容されるコード集合は `event_type` で決まる（サーバー検証の枠組みはコア [../03_permissions_and_recording_modes.md](../03_permissions_and_recording_modes.md) §C.4b・**「その競技カタログの列挙値かつ event_type 整合」**という汎用規約に対し、サッカーの具体対応を本表で定義する）。

| event_type | 意味 | 許容コード群 |
|------------|------|--------------|
| `YELLOW_CARD` | 警告 | `CautionCode`（C1〜C8） |
| `RED_CARD` | 退場（一発） | `SendingOffCode` のうち **S1〜S6**（CS は除く＝一発退場は警告 2 回ではない） |
| `SECOND_YELLOW` | 2 枚目の警告による退場 | **CS**（＝警告 2 回による退場。`SECOND_YELLOW` は意味上 CS に対応） |
| 上記以外 | — | NULL のみ（理由コード非対象） |

- いずれのコードも**任意（NULL 可）**: 後から補完できる（公式戦では記録を推奨・§8）。
- `card_reason_code` は構造化（集計・絞り込み用）、`note` は補足の自由記述。両者は**併存**し、既存の `custom_label`（OTHER 用）・`linked_event_id`（連鎖）とも独立して共存する。

> **カタログの実装配置**: `CautionCode`/`SendingOffCode` は match ドメイン（`com.mannschaft.app.match`）のサッカー固有カタログとして配置し、`SportEventCatalog`（コア §D.3・案 A）と同じく**コード定数で `Sport.SOCCER` に紐づける**（例: `Map<Sport, CardReasonCatalog>`）。DB マスタ化は将来余地（コア §D.3 案 B と同方針）。

---

## §6 統計定義（サッカー固有指標）

コアの集計 API の枠組み（個人/チーム/試合内・コア [../02_playing_time_and_aggregation.md](../02_playing_time_and_aggregation.md) §F）に対する、**サッカー固有の統計指標の定義**（コア 02 §F の競技固有部分から抽出）。指標の枠組み・エンドポイント形・認可/プライバシーはコアに従う。

### §6.1 個人キャリア統計の指標（`UserMatchStatsResponse`）

| 指標 | 算出元（サッカー） |
|------|--------|
| totalMatches | 出場した（appearance がある）試合数 |
| totalMinutes | Σ computed_minutes |
| goals | GOAL＋PENALTY_GOAL（自分が主体・**本戦のみ**・PK 戦除外・§4） |
| assists | ASSIST（自分が主体） |
| ownGoals | OWN_GOAL（自分が主体・自責点） |
| yellowCards / redCards | YELLOW_CARD ＋SECOND_YELLOW / RED_CARD |
| starterRate | starter 試合数 / totalMatches |
| avgMinutes | totalMinutes / totalMatches |
| goalsPer90 | goals / (totalMinutes / 90)（totalMinutes=0 は NULL・コア 02 §未解決 4） |
| monthlyTrend[] | 月別 { month, matches, minutes, goals, assists }（ライン用） |
| seasonTrend[] | シーズン別配列（同上・期間粒度違い） |
| byKind[] | kind 別内訳 { kind, matches, goals, ... }（doughnut/bar 用） |

### §6.2 チーム統計の指標（`TeamMatchStatsResponse`）

| 指標 | 説明（サッカー） |
|------|------|
| wins / draws / losses | team_side と home_score/away_score（本戦）から判定（W/D/L・§4.3） |
| totalGoalsFor / totalGoalsAgainst | 得点 / 失点合計（本戦） |
| goalDifference | 得失点差 |
| playerRankings | 選手別ランキング { userId, displayName, goals, assists, minutes }（bar 用・top-N 上限・displayName は退会者匿名化追従・原則 4） |
| byKind[] | kind 別内訳（勝敗・得失点） |
| recentForm[] | 直近 N 試合の結果配列（W/D/L・ライン/フォーム表示） |

- **得点（goals）の定義**: GOAL＋PENALTY_GOAL（本戦のみ・PK 戦除外）。OWN_GOAL は得点者の goals に含めない（自責点 `ownGoals` で別計上）。
- **個人ランキング（得点王）**: 当該大会に紐づく matches のイベント集計（GOAL/ASSIST・本戦のみ・PK 戦除外・コア [../05_tournament_integration.md](../05_tournament_integration.md) §H.2.2）。
- 未登録選手（`player_user_id=NULL`）はキャリア横断集計の対象外（コア 01 §D.4・02 §F.1）。

---

## §7 ポジション語彙（サッカー）

コアの汎用カラム `player_appearances.position`（VARCHAR(30)・[../01_domain_and_ddl.md](../01_domain_and_ddl.md) §B.3）に入れる、**サッカーのポジション語彙の具体値**。

| ポジション略号 | 名称 |
|----------------|------|
| GK | ゴールキーパー |
| DF | ディフェンダー（CB/SB を含む大分類） |
| MF | ミッドフィルダー（DMF/CMF/OMF/SH を含む大分類） |
| FW | フォワード（CF/WG を含む大分類） |

- 大分類（GK/DF/MF/FW）を必須語彙とし、細分（CB/SB/DMF/OMF/WG 等）は任意で `position` に入れてよい（自由文字列。集計の doughnut「ポジション傾向」は大分類で束ねる・コア 02 §F.5）。
- 選手グリッド（§8 選手グリッド）の先発配置は GK/DF/MF/FW の並びを既定とする。
- 多競技拡張時は競技ごとに別のポジション語彙を持つ（バスケ PG/SG/SF/PF/C 等・§10）。

---

## §8 サッカー固有 UX 細部

コアのライブ入力 UX の骨格（4 入口・3 タップ・タイマー状態機械・イベント連鎖・undo・409・チャート枠組み・オフライン・a11y・composable 配置・コア [../04_frontend_and_ux.md](../04_frontend_and_ux.md) §G）に対する、**サッカー固有の具体ボタン/コード/ポジション/チャート指標**（コア 04 §G の競技固有部分から抽出）。

### §8.1 よく使うイベント大ボタン（サッカーのプリセット）

- イベント種別はプリセット大ボタン **`[得点]` `[アシスト]` `[警告]` `[交代]` の 4 プリセット ＋ `[その他]`**（画面下部固定）。3 タップの速さを維持する（コア骨格＝コア 04 §G.2 イベント種別の選択 UI）。
- **得点 = 3 タップ**: [得点ボタン] → [得点者を選手グリッドから選択] → [任意でアシスト者選択 or スキップ]。
- **交代 = 3 タップ**: [交代ボタン] → [OUT 選手] → [IN 選手]。SUB_OUT/SUB_IN の 2 イベントを 1 操作で生成。
- **カード = 3 タップ**: [カードボタン] → [選手] → [黄/赤]（赤・2 枚目黄は out 確定・§2.1）。黄/赤を確定するとそのまま理由コード（§5）の選択式リストに繋がる（任意・スキップ可・§8.3）。
- `[その他]` を選ぶと自由入力（`custom_label`＝ラベル名 ＋ `note`＝理由メモ）を受け付ける（`event_type=OTHER`・コア 01 §D.2・スコア/出場時間に影響しない）。

### §8.2 GOAL ⇔ ASSIST 双方向連鎖（サッカー）

得点とアシストは**それぞれ固有の選手・背番号・理由（`note`）を持つ 2 つの独立イベント**であり、`linked_event_id`（コア 01 §B.2 自己参照）で双方向に連鎖させる（コア 04 §G.2a/G.2b の枠組みに対するサッカーの具体）。

- **速い道（得点起点）**: `[得点]` → 得点者選択 → **「＋アシストを紐付け」** → アシスト者＋理由（`note`）。GOAL と ASSIST の 2 イベントを生成し相互に `linked_event_id` で連鎖。
- **物語る道（アシスト起点）**: `[アシスト]` → 選手＋理由（`note`）→ **「→ 得点へつなぐ」** → 得点者。逆順でも同じ連鎖。
- タイムライン上で連鎖を視覚的に束ねて表示（例: `7番 アシスト（コーナーキックから）⤵ / 9番 得点`）。連鎖の一方を削除しても他方は残る（`ON DELETE SET NULL`・コア 01 §B.2）。
- 集計は二重計上しない（GOAL=得点者の goals、ASSIST=アシスト者の assists・§6・コア 02 §F）。

### §8.3 警告・退場の理由コード選択 UI（JFA 標準・選択式＋補足メモ）

警告/退場を選んだら、**標準理由コード（§5）を選択式リスト**で提示する。構造化コード＋補足の自由記述（`note`）の**両方**を 1 イベントに付けられる（コア 04 §G.2c のサッカー具体）。

- **警告（黄・2 枚目黄）**→ `CautionCode` C1〜C8 を選択式リストで提示（**各コード＋日本語短ラベル**: 例「C2 ラフプレー」）。`SECOND_YELLOW`（2 枚目の警告による退場）は退場でもあるため **CS（警告 2 回）**も併せ提示する（§5.3）。
- **退場（赤・一発）**→ `SendingOffCode` S1〜S6 を選択式リストで提示（例「S2 乱暴な行為」）。一発退場では CS は提示しない（§5.3）。
- **さらに補足の自由記述（`note`）枠を併記**する（例「背後からのチャージ」）。
- **最短操作（ADHD 配慮）**: 「コードをタップ → 確定」で完了。3 タップ（カード→選手→黄/赤）の速さは維持し、理由コード選択は任意ステップ（スキップ可）。
- **デフォルトは未選択可**（後で補完できる）。**公式戦（記録係あり・コア 03 §C.1）では理由コードの記録を推奨**する旨を UI で案内（必須にはしない）。
- コード記号（C1 等）は言語非依存・固定。短ラベルは i18n（`match.card_reason.*`・§9）で 6 言語表示する。

### §8.4 タイムライン表示での理由コード表示（サッカー）

- タイムライン各行で、警告/退場イベントは **カードアイコン（色＋形状）＋理由コード＋短ラベル＋選手**を束ねて表示（例: **「🟨 C2 ラフプレー（7 番）」**）。
- 理由コード未選択のカードは従来どおりカード＋選手のみ表示（例「🟨（7 番）」）。
- 補足の `note` があれば併記（例「🟨 C2 ラフプレー（7 番・背後からのチャージ）」）。
- 色覚配慮どおり色だけに依存せず、形状・テキストラベル（コード＋短ラベル）を併用する（コア 04 §G.12）。

### §8.5 サッカーのタイマー状態機械・選手グリッド

- **タイマー状態機械（サッカーのピリオド遷移）**:

  ```
  WAITING → FIRST_HALF → HALF_TIME → SECOND_HALF → [EXTRA_FIRST → EXTRA_SECOND] → [PENALTY_SHOOTOUT] → COMPLETED
  （[] = 任意ピリオド。延長・PK 戦は試合により省略される）
  ```

  | 状態 | タイマー挙動 | 手動訂正 |
  |------|-------------|---------|
  | WAITING | 停止（00:00） | 不可 |
  | FIRST_HALF | 動作（PERIOD_START 基準で minute 自動補完） | minute・stoppage を手動訂正可 |
  | HALF_TIME | 停止 | 可 |
  | SECOND_HALF | 動作 | 可 |
  | EXTRA_FIRST/SECOND | 動作 | 可 |
  | PENALTY_SHOOTOUT | 停止（PK は分概念なし） | — |
  | COMPLETED | 停止 | イベント訂正は権限に従う（コア 03 §C） |

  - 状態機械の骨格（WAITING/HALF_TIME/COMPLETED の停止・PERIOD_START/PERIOD_END 自動記録・minute 手動訂正）はコア 04 §G.2 のタイマー枠組みに従い、**ピリオドの具体（前後半・延長・PK）がサッカー固有**。
- **選手グリッドの先発配置**: §7 のポジション語彙（GK/DF/MF/FW）に沿って先発を上段・控えを下段に配置（コア 04 §G.2 選手グリッド・取得源は roster→メンバー一覧→手入力の 3 段フォールバック＝コア 04 §G.1c）。

### §8.6 サッカー向けチャート指標

コアのチャート枠組み（chart.js・radar/line/doughnut/bar・`BaseChart.vue`・コア [../04_frontend_and_ux.md](../04_frontend_and_ux.md) §G.3）に差し込む、サッカーの具体指標。

| チャート種別 | サッカーでの用途 | データ源（§6・コア 02 §F） |
|--------------|------------------|----------------------------|
| **radar** | 個人スタッツ分布（得点・アシスト・出場・守備（SAVE 等）の多軸バランス） | §6.1 個人指標を正規化 |
| **line** | 得点/出場時間の月別・シーズン別推移 | §6.1 `monthlyTrend[]`/`seasonTrend[]` |
| **doughnut** | **ポジション傾向（GK/DF/MF/FW・§7）**・kind 別出場割合 | §6.1 `byKind[]`・position 集計 |
| **bar** | 得点分布・選手別ランキング | §6.2 `playerRankings`・§6.1 `byKind[]` |

---

## §9 i18n namespace（サッカー固有ラベル）

UI 文字列の i18n はコアの枠組み（`app/locales/{ja,en,zh,ko,es,de}/match.json` を新設・`nuxt.config.ts` の `files` 配列へ登録・直書き禁止・コア [../04_frontend_and_ux.md](../04_frontend_and_ux.md) §G.6）に従う。**`match.json` ファイル自体は競技共通**（コアが新設・nuxt.config 登録もコア側で実施）であり、本書は**サッカー固有ラベル群（namespace の中身）の所在を定義**する。

| namespace | サッカー固有の中身 |
|-----------|--------------------|
| `match.event_type` | サッカー event_type ラベル（GOAL→「得点」・ASSIST→「アシスト」・OWN_GOAL→「オウンゴール」・PENALTY_GOAL→「PK（成功）」・SAVE→「セーブ」・**OTHER→「その他」** 等・§2） |
| `match.card_type` | カード種別ラベル（YELLOW→「警告」・RED→「退場」等・形状ラベル併用・§8.3） |
| `match.card_reason` | **警告/退場の理由コード短ラベル（`C1`…`C8` / `S1`…`S6` / `CS`・§5）**。コード記号は言語非依存・固定、説明文（短ラベル）を 6 言語翻訳。例: `match.card_reason.C2`→ja「ラフプレー」 |
| `match.position` | **サッカーのポジション語彙ラベル（GK/DF/MF/FW・§7）**を 6 言語表示 |

- 理由コード短ラベルは `match.json` 内の `match.card_reason.*` namespace に含める（**理由コード用に新規ファイル登録は不要**・match.json に内包・コア 04 §G.6）。
- 多競技拡張時は競技ごとに `match.event_type.*` / `match.card_reason.*` / `match.position.*` のラベル集合が異なる（各競技カタログ文書が自競技のラベル群を定義する）。

---

## §10 新競技の追加手順（本書を雛形に）

`SportEventCatalog`（コア [../01_domain_and_ddl.md](../01_domain_and_ddl.md) §D.3・案 A）の拡張機構により、**新競技は本書（01_soccer.md）を雛形に複製し、差分を書く**ことで追加できる（コアのテーブル＝器は不変・カタログ＝中身だけ競技別）。

新競技 `sports/0N_xxx.md`（例: `02_basketball.md`）の作成手順:

1. **`Sport` enum に競技を追加**（コア §D.1・例 `Sport.BASKETBALL`）。
2. **§2 event_type カタログ**: その競技の `event_type` 集合を `SportEventCatalog.CATALOG` に追加（コア `MatchEventType` enum に不足する値があれば enum へ追加。汎用の器 `match_events.event_type` VARCHAR は不変）。
3. **§3 period モデル**: その競技のピリオド（バスケ＝`QUARTER_1..4`/`OVERTIME` 等）を定義（コア `PeriodType` enum の該当値を使う／不足すれば追加）。
4. **§4 スコア計算・勝敗判定**: その競技のスコア合算ルール（バスケ＝得点の重み 1/2/3 点等）を定義。セット制（バレー等）は将来 `match_periods`/`match_sets` で吸収（コア 05 §未解決 3）。
5. **§5 規律コード**: その競技の反則体系（バスケ＝テクニカルファウル等）を別カタログ（`Map<Sport, CardReasonCatalog>`）として定義。サッカー用 C/S コードは流用しない。
6. **§6 統計定義**: その競技固有の指標（バスケ＝リバウンド/アシスト/スティール等）を定義。
7. **§7 ポジション語彙**: その競技のポジション（バスケ＝PG/SG/SF/PF/C 等）を定義。
8. **§8 競技固有 UX**: プリセットボタン・選手グリッド配置・チャート指標を差し替え。
9. **§9 i18n**: `match.event_type.*` / `match.card_reason.*` / `match.position.*` のラベル群を追加（`match.json` ファイルは共通・namespace の中身が競技別）。

これにより、**コアの枠組み（出場時間算出・集計 API・記録モード・権限・IDOR・F00 可視性・ライブ入力 UX の骨格）を一切再実装せず**、競技固有の中身だけを差分で追加できる。
