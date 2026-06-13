# F08.10 / sports / 03: バスケットボール競技カタログ（BASKETBALL）

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-13
> **位置づけ**: **F08.10 コアを継承するバスケットボール競技カタログ**。状態モデル類型は **連続時間制（CONTINUOUS_TIME・4 クォーター＋OT）**。サッカー（[01_soccer.md](./01_soccer.md)）を雛形に、得点種別（2P/3P/FT）・クォーター制・ファウル体系の差分を記述する。
> **関連機能番号**: F08.10（試合記録・分析）／ F08.7 ／ F08.7.1 ／ F07.2 ／ F19.1
> **関連ドキュメント（コア）**:
> - [../README.md](../README.md) — 機能概要・3 状態モデル類型・競技一覧
> - [../01_domain_and_ddl.md](../01_domain_and_ddl.md) — DDL（汎用の器）・enum（汎用）・**拡張点 `SportEventCatalog`・`StateModel` 類型・`MatchEventType` 拡張値**（§D.2・§D.3・§D.6）
> - [../02_playing_time_and_aggregation.md](../02_playing_time_and_aggregation.md) — 出場時間自動算出・集計 API の枠組み
> - [../04_frontend_and_ux.md](../04_frontend_and_ux.md) — ライブ入力 UX 骨格・タイマー状態機械・`useMatchTimerBasketball`（クォーター＋OT）
> - [01_soccer.md](./01_soccer.md) — 雛形

---

## §1 概要 — サッカーとの関係・状態モデル類型

バスケットボールは**連続時間制（CONTINUOUS_TIME・コア §D.6）**だが、**4 クォーター＋オーバータイム**構造であり、得点が **1 点（フリースロー）/ 2 点 / 3 点**に分かれる点がサッカーと大きく異なる。コアの器（テーブル・汎用 enum）は変更せず、`MatchEventType` に**バスケ固有の得点・反則 event_type を追加**（コア §D.2 の器拡張）し、`Sport.BASKETBALL` カタログとして集合・カタログを定義する。

`matches.sport='BASKETBALL'` で識別する。

**サッカーとの主な差分**:

| 観点 | サッカー | バスケットボール |
|------|---------|------------------|
| ピリオド | 前後半 | 4 クォーター（各 10 分・FIBA）＋ OVERTIME（5 分） |
| 得点種別 | GOAL（1 点固定） | FIELD_GOAL_2（2 点）/ FIELD_GOAL_3（3 点）/ FREE_THROW（1 点） |
| 反則 | 警告/退場（黄/赤） | パーソナルファウル/テクニカルファウル/退場（5 ファウル or ディスクォリファイ） |
| 退場 | RED_CARD | 5 個目のパーソナルファウルで自動退場（FOUL_OUT）／ディスクォリファイ |
| 統計 | 得点/アシスト | 得点/アシスト/リバウンド/スティール/ブロック/ターンオーバー（§6） |

---

## §2 event_type カタログ（バスケットボール）

コア `MatchEventType` enum（器）に**バスケ固有の値を追加**する（コア §D.2・器は全競技横断で値を保持する）。追加する値:

```
// コア MatchEventType に追加（器・全競技横断。サッカーは使わない）
FIELD_GOAL_2,    // 2 点シュート成功
FIELD_GOAL_3,    // 3 点シュート成功
FREE_THROW,      // フリースロー成功（1 点）
SHOT_MISS,       // シュート失敗（任意記録・スコア非影響）
REBOUND,         // リバウンド
STEAL,           // スティール
BLOCK,           // ブロック
TURNOVER,        // ターンオーバー
PERSONAL_FOUL,   // パーソナルファウル（5 個で FOUL_OUT）
TECHNICAL_FOUL,  // テクニカルファウル
FOUL_OUT         // 5 ファウル退場 / ディスクォリファイ（out 確定）
```

```java
// SportEventCatalog の BASKETBALL 集合（コア §D.3）
Sport.BASKETBALL, EnumSet.of(STARTER, SUB_IN, SUB_OUT,
                             FIELD_GOAL_2, FIELD_GOAL_3, FREE_THROW, SHOT_MISS,
                             REBOUND, STEAL, BLOCK, TURNOVER, ASSIST,
                             PERSONAL_FOUL, TECHNICAL_FOUL, FOUL_OUT,
                             INJURY, PERIOD_START, PERIOD_END, OTHER)
```

> **注**: サッカー専用の GOAL/PENALTY_GOAL/OWN_GOAL/YELLOW_CARD/RED_CARD/SECOND_YELLOW/SAVE/PENALTY_SHOOTOUT はバスケ集合に**含めない**（カタログ検証でバスケ試合にこれらを記録すると 400・コア §D.3）。逆にバスケ固有値（FIELD_GOAL_2 等）はサッカー集合に含めない。

### §2.1 event_type の出場時間・スコアへの影響（バスケットボール）

| イベント | 出場時間への影響 | スコアへの影響 |
|----------|------------------|----------------|
| STARTER / SUB_IN / SUB_OUT | コア 02 §E.1 の汎用区間ロジック（サッカーと同一） | — |
| FIELD_GOAL_2 | — | 当該 team_side の本戦スコア **+2** |
| FIELD_GOAL_3 | — | 当該 team_side の本戦スコア **+3** |
| FREE_THROW | — | 当該 team_side の本戦スコア **+1** |
| SHOT_MISS / REBOUND / STEAL / BLOCK / TURNOVER / ASSIST | — | — |
| PERSONAL_FOUL / TECHNICAL_FOUL | — | — |
| FOUL_OUT | out=minute（退場で区間を閉じる・コア 02 §E.1） | — |
| INJURY / PERIOD_START / PERIOD_END / OTHER | — | — |

- **得点の重み付け（2P/3P/FT）がサッカーとの最大の差分**。スコア合算は「event_type ごとに加点数が異なる」（サッカーは GOAL=1 点固定）。この重み付けは §4 の合算ルールに定義する。コアの出場時間算出ロジックは event_type の種類に依らず区間ベースで成立するため**変更不要**。
- バスケに延長別カラム・PK 戦概念はない（OVERTIME 得点は本戦スコアに合算）。

---

## §3 period モデル（バスケットボール）

コア `PeriodType` enum の **`QUARTER_1`〜`QUARTER_4` / `OVERTIME`** を用いる（サッカーが使う FIRST_HALF 等は使わない）。

| period 値 | 意味 | スコア計算（§4） |
|-----------|------|------------------|
| QUARTER_1〜QUARTER_4 | 第 1〜4 クォーター（各 10 分） | 本戦スコアへ合算 |
| OVERTIME | 延長（5 分・複数回あり得る） | 本戦スコアへ**合算**（延長別カラムなし） |

- `matches.period_format` に `'QUARTERS_10'`（4×10 分・FIBA）を入れる。NBA 互換が必要なら `'QUARTERS_12'` を将来追加（period_format は自由文字列カラム）。
- `duration_minutes` は 4 クォーター 40＋OT で試合通算分（OT ありは 45 等）。
- **複数回 OVERTIME**: 同じ `OVERTIME` period 値で複数回起こり得る。区別が必要なら `match_events.sort_seq` と PERIOD_START/END で時系列を保つ（MVP は OVERTIME を 1 値で扱い、複数回 OT の細分は将来余地・ブロッカーではない。理由: アマチュア大会で複数回 OT は稀で、得点合算は period 値に依らず本戦へ加算されるため統計上は問題ない）。

---

## §4 スコア計算・勝敗判定（バスケットボール）

### §4.1 得点の重み付き合算

- 本戦スコア（`home_score`/`away_score`）への加算は **event_type ごとに加点数が異なる**: `FIELD_GOAL_2`=+2、`FIELD_GOAL_3`=+3、`FREE_THROW`=+1。
- バスケに **PK 戦（`home/away_penalty_score`）・延長別カラムはない**（OVERTIME 得点は本戦合算）。PENALTY_SHOOTOUT イベントは BASKETBALL カタログに存在しない（カタログ検証で弾く）。
- 引き分けは原則なし（同点なら OVERTIME 継続）。ただし記録上の途中保存（IN_PROGRESS）では同点があり得る。

### §4.2 本戦スコア整合チェックの突合式（バスケットボール）

- `matches.home_score`/`away_score`（本戦正本）と、`match_events` の **`FIELD_GOAL_2×2 ＋ FIELD_GOAL_3×3 ＋ FREE_THROW×1`（自サイド）** を集計した値を比較する。
- 不一致時はコアの整合チェック枠組み（コア 02 §E.5）に従い、握りつぶさず警告を返す（スコア正本は記録係確定）。
- OWN_GOAL 概念はバスケにないため符号反転処理は不要。

### §4.3 勝敗判定（W/D/L）

- `team_side` と本戦スコアで W/L を判定（バスケは引き分けなし＝OT 継続が原則）。
- 順位寄与は tournament 側の `result`/`winner_participant_id` スナップショットへ反映（コア 05 §H.2.3）。引き分け（D）は通常発生しないが、DTO の枠組みは W/D/L 共通（コア 02 §F）。

---

## §5 規律コード（バスケットボール）

**サッカーの C/S コードは流用しない**（バスケの反則体系は別物）。バスケ専用の理由コードカタログ `BasketballFoulCode` を `Map<Sport, CardReasonCatalog>` に登録する（コア §D.5・案 A）。

```java
// バスケ固有: ファウル理由コード（FIBA 競技規則 標準・PERSONAL_FOUL / TECHNICAL_FOUL / FOUL_OUT に紐づく）
public enum BasketballFoulCode {
    PF,   // パーソナルファウル（一般）
    SF,   // シューティングファウル（シュート動作中）
    OF,   // オフェンスファウル
    TF,   // テクニカルファウル
    UF,   // アンスポーツマンライクファウル
    DF    // ディスクォリファイングファウル（即退場）
}
```

| event_type | 意味 | 許容コード群 |
|------------|------|--------------|
| `PERSONAL_FOUL` | パーソナルファウル | PF / SF / OF / UF |
| `TECHNICAL_FOUL` | テクニカルファウル | TF |
| `FOUL_OUT` | 退場（5 ファウル or ディスクォリファイ） | DF（ディスクォリファイ時）／ NULL（5 ファウル累積退場時は理由コード不要） |
| 上記以外 | — | NULL のみ |

- 検証規約はコア §C.4b（「その競技カタログの列挙値かつ event_type 整合」）に従う。サッカー C/S コードをバスケ試合に付けると 400。
> **保守方針**: FIBA 競技規則（出典: <https://www.fiba.basketball/documents>）に準拠。実装時に最新 FIBA 公式規則と照合してコードを確定する（本記載は起草時点の標準・唯一の正本は FIBA 公式規則）。

---

## §6 統計定義（バスケットボール固有指標）

コアの集計 API 枠組み（コア 02 §F）に対するバスケ固有指標。

### §6.1 個人キャリア統計（`UserMatchStatsResponse`）

| 指標 | 算出元（バスケ） |
|------|--------|
| totalMatches / totalMinutes / starterRate / avgMinutes | コア共通（appearances 由来） |
| points | `FIELD_GOAL_2×2 ＋ FIELD_GOAL_3×3 ＋ FREE_THROW×1`（自分が主体） |
| assists | ASSIST |
| rebounds / steals / blocks / turnovers | 各 event_type のカウント |
| fouls | PERSONAL_FOUL ＋ TECHNICAL_FOUL |
| fieldGoalPct | (FIELD_GOAL_2＋FIELD_GOAL_3) / (成功＋SHOT_MISS)（分母 0 は NULL・コア 02 §未解決 4） |
| pointsPer game / monthlyTrend[] / seasonTrend[] / byKind[] | コア共通枠組み（指標名差し替え） |

### §6.2 チーム統計（`TeamMatchStatsResponse`）

| 指標 | 説明（バスケ） |
|------|------|
| wins / losses | team_side と本戦スコアから判定（引き分けは原則なし・§4.3） |
| totalPointsFor / totalPointsAgainst | 得点 / 失点合計 |
| pointDifference | 得失点差 |
| playerRankings | { userId, displayName, points, assists, rebounds, minutes }（top-N・退会者匿名化追従） |
| byKind[] / recentForm[] | コア共通 |

- 「goals」ではなく「points」を主指標とする（重み付き）。FE のラベルは i18n（§9）で競技別に切替。

---

## §7 ポジション語彙（バスケットボール）

| 略号 | 名称 |
|------|------|
| PG | ポイントガード |
| SG | シューティングガード |
| SF | スモールフォワード |
| PF | パワーフォワード |
| C | センター |

- 大分類 PG/SG/SF/PF/C を必須語彙とし、doughnut「ポジション傾向」はこの 5 分類で束ねる。先発 5 人を既定とする。

---

## §8 バスケットボール固有 UX 細部

### §8.1 よく使うイベント大ボタン（バスケのプリセット）

- プリセット大ボタン **`[2P]` `[3P]` `[FT]` `[リバウンド]` `[ファウル]` `[交代]` ＋ `[その他]`**（画面下部固定）。3 タップを維持。
- **得点 = 3 タップ**: [2P/3P/FT ボタン] → [得点者を選手グリッドから選択] → [任意でアシスト者選択 or スキップ]（FT はアシスト紐付けなしが通常）。
- **ファウル = 3 タップ**: [ファウルボタン] → [選手] → [PF/TF 種別]。種別確定で理由コード（§5）の選択式リストへ連続（任意・スキップ可）。5 個目の PERSONAL_FOUL は FOUL_OUT を促す（out 確定）。
- `[その他]` で自由入力（`custom_label`＋`note`・`event_type=OTHER`・スコア/出場時間非影響）。

### §8.2 連鎖（FIELD_GOAL ⇔ ASSIST）

サッカーの GOAL⇔ASSIST 連鎖（コア 04 §G.2a）と同じ機構で、FIELD_GOAL_2/3 と ASSIST を `linked_event_id` で双方向連鎖させる。集計は二重計上しない（得点＝得点者、アシスト＝アシスト者）。

### §8.3 ファウル理由コード選択 UI

警告/退場の代わりに**ファウル種別＋理由コード（§5・PF/SF/OF/TF/UF/DF）**を選択式リストで提示。構造化コード＋補足 note の併存・任意選択・公式戦推奨案内はコア 04 §G.2c と同じ。

### §8.5 タイマー状態機械・選手グリッド（バスケ）

- **タイマー状態機械（クォーター制）**:

  ```
  WAITING → QUARTER_1 → BREAK → QUARTER_2 → HALF_TIME → QUARTER_3 → BREAK → QUARTER_4 → [OVERTIME ...] → COMPLETED
  （[] = 任意。同点時のみ OVERTIME・複数回あり得る）
  ```

  | 状態 | タイマー挙動 |
  |------|-------------|
  | WAITING / BREAK / HALF_TIME | 停止 |
  | QUARTER_1〜4 / OVERTIME | 動作（PERIOD_START 基準・カウントダウン式も可・MVP は経過分で記録） |
  | COMPLETED | 停止 |

  - **FE composable は `useMatchTimerBasketball`（クォーター＋OT 専用・コア 04 §G.16）を動的 import**。クォーター遷移・複数回 OT・各ピリオド間 BREAK はサッカーの前後半とは状態遷移が異なるため、サッカー用 `useMatchTimerSoccer` とは別 composable とする（コアのタイマー状態機械骨格＝WAITING/停止状態/PERIOD_START 自動記録/minute 手動訂正に従う）。
- **選手グリッド**: §7（PG/SG/SF/PF/C）で先発 5 人を上段配置。3 段フォールバック（roster→メンバー一覧→手入力）はコア共通。

### §8.6 チャート指標（バスケ）

| チャート | バスケでの用途 |
|----------|----------------|
| radar | 得点/アシスト/リバウンド/スティール/ブロックの多軸バランス |
| line | 得点/出場時間の月別・シーズン推移 |
| doughnut | ポジション傾向（PG/SG/SF/PF/C）・kind 別割合 |
| bar | 得点分布・選手別ランキング |

---

## §9 i18n namespace（バスケ固有ラベル）

| namespace | バスケ固有の中身 |
|-----------|------------------|
| `match.event_type` | FIELD_GOAL_2→「2P」・FIELD_GOAL_3→「3P」・FREE_THROW→「フリースロー」・REBOUND→「リバウンド」・STEAL→「スティール」・BLOCK→「ブロック」・TURNOVER→「ターンオーバー」・FOUL_OUT→「退場」等 |
| `match.foul_type` | PERSONAL_FOUL→「パーソナルファウル」・TECHNICAL_FOUL→「テクニカルファウル」（カード種別 namespace のバスケ版） |
| `match.card_reason`（流用） | バスケファウル理由コード短ラベル（PF/SF/OF/TF/UF/DF）。コード記号は言語非依存・固定、説明文を 6 言語翻訳 |
| `match.position` | PG/SG/SF/PF/C を 6 言語表示 |

- `match.json` ファイルは競技共通（コア 04 §G.6）。バスケ固有ラベルは namespace の中身として追加（新規ファイル登録不要）。

---

## §10 雛形準拠の確認

本書はコア [01_soccer.md](./01_soccer.md) §10 の手順に従い、`Sport.BASKETBALL` 追加・event_type 集合（§2・器に FIELD_GOAL_2 等を追加）・period（§3・QUARTER/OVERTIME）・スコア重み付き合算（§4）・反則コード（§5・別カタログ）・統計（§6・points/rebounds 等）・ポジション（§7・PG〜C）・UX（§8・クォーター timer 別 composable）・i18n（§9）を定義した。コアのテーブル・出場時間算出・集計枠組み・権限・IDOR・F00 可視性・WebSocket 観戦の骨格は一切再実装しない（器拡張＝`MatchEventType` への値追加のみ）。
