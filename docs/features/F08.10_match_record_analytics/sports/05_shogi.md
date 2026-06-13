# F08.10 / sports / 05: 将棋競技カタログ（SHOGI）

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-13
> **位置づけ**: **F08.10 コアを継承する将棋競技カタログ**。状態モデル類型は **ターン制（TURN_BASED・コア §D.6）**。盤上競技は**記録粒度＝中間**（勝敗＋勝ち方＋総手数＋任意の局面写真/コメント。**棋譜フル（KIF）エンジンは持たない**）。個人戦＋団体戦の両対応（コア §B.6 `parent_match_id`/`board_number`）を最初に用いる競技。
> **関連機能番号**: F08.10（試合記録・分析）／ F08.7 ／ F08.7.1 ／ F19.1
> **関連ドキュメント（コア）**:
> - [../README.md](../README.md) — 機能概要・3 状態モデル類型・競技一覧・団体戦
> - [../01_domain_and_ddl.md](../01_domain_and_ddl.md) — DDL・**ターン制（`total_moves`・ピリオド無）・団体戦（`parent_match_id`/`board_number`・§B.6）・勝ち方 enum（§D.7）・局面写真（添付基盤流用・§B.7）**
> - [../03_permissions_and_recording_modes.md](../03_permissions_and_recording_modes.md) — 団体戦の親子ボード IDOR・局面写真添付の IDOR/SVG 除外/サイズ上限
> - [../04_frontend_and_ux.md](../04_frontend_and_ux.md) — ターン制の最小 UI（`useMatchTurnTracker`・タイマー無し・手数任意・結果＋写真）
> - [01_soccer.md](./01_soccer.md) — 雛形（本書は構造が大きく異なるため差分が大きい）

---

## §1 概要 — ターン制・記録粒度中間・盤上競技という根本的差分

将棋は球技（連続時間制/セット制）と根本的に異なる**ターン制（TURN_BASED・コア §D.6）**である:

- **時間（minute）・ピリオドの概念がない**（持ち時間はあるが本機能では記録しない）。試合は「手番の応酬（総手数）」で進む。
- **スコアという連続量がない**。勝敗は**勝ち（先手/後手）/負け**の二値＋**勝ち方**（投了・時間切れ・反則勝ち・千日手・持将棋）。
- **記録粒度＝中間**: 勝敗＋勝ち方＋総手数（`total_moves`）＋任意の局面写真/コメント。**棋譜フル（KIF/全手順）は記録しない**（KIF パーサ/将棋エンジンは持たない＝過剰機能・本機能の主目的は「対局結果の記録と統計」）。
- **個人戦＋団体戦の両対応**: 1 局 = 1 match を基本。団体戦は親 match（`parent_match_id=NULL`）＋子 match（各ボード・`parent_match_id` 設定・`board_number` 連番）で表現する（コア §B.6）。

`matches.sport='SHOGI'`・`matches.state_model='TURN_BASED'`（導出可）で識別する。

---

## §2 event_type カタログ（将棋）

将棋は「手の応酬」だが、本機能は手順を記録しない（記録粒度中間）。よってイベントは**結果系の少数**に限る。コア `MatchEventType` enum（器）にターン制共通値を追加（コア §D.2）。

```
// コア MatchEventType に追加（器・ターン制共通。球技は使わない）
GAME_RESULT,     // 対局結果確定（勝者サイド・勝ち方を card_reason_code 相当 or detail に）
MOVE_COUNT,      // 総手数記録（total_moves へ反映・任意）
POSITION_PHOTO,  // 局面写真添付（presign 添付・§B.7）
COMMENT          // 局面コメント（note へ自由記述）
```

```java
// SportEventCatalog の SHOGI 集合（コア §D.3）
Sport.SHOGI, EnumSet.of(GAME_RESULT, MOVE_COUNT, POSITION_PHOTO, COMMENT, OTHER)
```

> **STARTER/SUB_IN 等は使わない**: 将棋は出場交代の概念がないため出場時間系 event_type を SHOGI カタログに含めない（カタログ検証で弾く・コア §D.3）。対局者は `matches.team_id`（先手＝HOME side）/`opponent_*`（後手＝AWAY side）＋ `match_events.player_user_id` で表現する（団体戦の各ボードも 1 局 1 match なので同様）。

### §2.1 出場時間・スコアへの影響（将棋）

- **出場時間（分）概念なし**: `player_appearances.computed_minutes` は**ターン制では NULL**（コア 02 §E.1 の汎用 NULL 扱いに従い握りつぶさない）。出場時間自動算出ロジックは**ターン制では起動しない**（コア §D.6 で `state_model=TURN_BASED` のときフル再計算をスキップ＝STARTER/SUB イベントが存在しないため区間が組み立たない）。
- **スコア（連続量）なし**: `matches.home_score`/`away_score` は将棋では使わない（NULL）。**勝敗は `matches.result`（勝者 side）＋勝ち方（`win_method`・§4）で表現**する。個人戦の `result` は子ボード集計ではなく直接確定。
- 局面写真（POSITION_PHOTO）・コメント（COMMENT）は記録の付随情報でスコア・出場時間に影響しない。

---

## §3 period モデル（将棋）— ピリオド無し・総手数

- 将棋に**ピリオドはない**。`match_events.period` は NULL（コア §D.6 でターン制は period 必須を解除＝NOT NULL 制約をターン制で許容するか、`period='NONE'` 固定値を入れるかは実装時に統一。本設計は `period` をターン制で NULL 許容とする方針＝コア §B.2 の `period NOT NULL` をターン制例外として §D.6 で明記）。
- **`PERIOD_START`/`PERIOD_END` は不要**（タイマー無し・コア §D.6）。
- 進行の量的指標は**総手数 `matches.total_moves`（SMALLINT UNSIGNED NULL・コア §B.1 拡張）**で表現する。`MOVE_COUNT` イベント or 直接 `total_moves` 入力のいずれでも記録可（MVP は試合詳細で `total_moves` を直接入力＝任意）。

---

## §4 勝敗判定・勝ち方（将棋）

将棋にスコアはなく、**勝敗＝勝者 side ＋勝ち方**で確定する。

### §4.1 勝ち方カタログ（`ShogiWinMethod`）

コア §D.7 の「ターン制の勝ち方 enum（競技別カタログ）」に対する将棋の具体値。`matches.win_method`（VARCHAR・コア §B.1 拡張）or `GAME_RESULT` イベントの `card_reason_code` 相当列に保持する（実装は §D.7 で `win_method` 列に統一）。

```java
// 将棋固有: 勝ち方（日本将棋連盟の対局規定 標準）
public enum ShogiWinMethod {
    RESIGNATION,   // 投了（最も一般的）
    CHECKMATE,     // 詰み（実戦で詰みまで指す）
    TIMEOUT,       // 時間切れ（持ち時間切れ）
    FOUL_WIN,      // 反則勝ち（相手の二歩・王手放置・打ち歩詰め等の反則による）
    REPETITION,    // 千日手（同一局面 4 回＝指し直し or 規定により決着）
    IMPASSE,       // 持将棋（入玉宣言法 等・点数計算で決着 or 引き分け）
    DEFAULT_WIN    // 不戦勝（相手の不出場）
}
```

| 勝ち方 | 説明 |
|--------|------|
| RESIGNATION | 投了 |
| CHECKMATE | 詰み |
| TIMEOUT | 時間切れ |
| FOUL_WIN | 反則勝ち |
| REPETITION | 千日手 |
| IMPASSE | 持将棋 |
| DEFAULT_WIN | 不戦勝 |

> **保守方針**: 日本将棋連盟の対局規定（出典: <https://www.shogi.or.jp/>）に準拠。実装時に最新規定と照合（本記載は起草時点・唯一の正本は連盟規定）。

### §4.2 勝敗・引き分け

- 勝者 side（HOME=先手 / AWAY=後手）を `matches.result` 相当に保持。
- **千日手（REPETITION）・持将棋（IMPASSE）は引き分け（DRAW）になり得る**（指し直しをせず大会レギュレーションで引き分け扱いの場合）。W/D/L の D が将棋では発生する（球技と異なる）。
- `card_reason_code`（球技のカード理由コード）は将棋では使わず、**`win_method` で勝ち方を構造化**する（コア §D.7 で `win_method` を別列として定義）。

### §4.3 団体戦の勝敗導出（親 match）

- **団体戦の親 match（`parent_match_id=NULL`・複数ボードを束ねる）の勝敗は、子ボード（各 1 局）の勝ち星集計から導出**する（コア §B.6）。
- 例: 5 人制団体戦で 3 勝 2 敗 → 親 match の勝者 = 3 勝した側。`matches.home_score`/`away_score` には**勝ち星数**（3-2）を集計して入れる（団体戦に限りスコア列を「勝ち星数」として再利用＝セット制が獲得セット数を入れるのと同じ思想）。
- 子ボードの `result`/`win_method` を集計して親の `result` を確定。親の勝敗確定は `MatchCompletedEvent`（コア 05）で順位連携にも乗せられる（リーグ戦の団体戦）。
- IDOR: 親子ボードのテナント・所属検証はコア §C.4（団体戦の親子ボード IDOR・コア 03 で明記）に従う。

---

## §5 規律コード（将棋）

- 将棋に「カード」体系はない。反則は**勝ち方 `FOUL_WIN`（§4.1）**で表現し、`card_reason_code` は使わない（SHOGI カタログに理由コード集合を登録しない＝コードを付けると 400・コア §C.4b）。
- 反則の種別（二歩・打ち歩詰め等）の細分が必要なら `note`（自由記述）に書く（MVP は `FOUL_WIN`＋`note` で足りる・細分カタログ化は将来余地・ブロッカーではない）。

---

## §6 統計定義（将棋固有指標）

### §6.1 個人キャリア統計（`UserMatchStatsResponse`）

| 指標 | 算出元（将棋） |
|------|--------|
| totalGames | 対局数（個人戦＋団体戦の出場ボード数） |
| wins / losses / draws | result から（千日手/持将棋は draw・§4.2） |
| winRate | wins / totalGames（分母 0 は NULL） |
| winsByMethod[] | 勝ち方別内訳（投了/詰み/時間切れ/反則勝ち 等・§4.1） |
| avgMoves | 平均総手数（`total_moves` の平均・NULL は除外） |
| firstMoveWinRate | 先手（HOME side）勝率（任意・先手後手別成績） |
| monthlyTrend[] / seasonTrend[] | 月別/シーズン別の対局数・勝率（line 用） |
| totalMinutes / goalsPer90 系 | **将棋では無効**（NULL・FE 非表示・コア 04 §G.8） |

### §6.2 チーム統計（`TeamMatchStatsResponse`）

| 指標 | 説明（将棋） |
|------|------|
| wins / draws / losses | チーム（団体戦の親 match 勝敗 ＋ 個人戦の所属メンバー成績）から |
| boardWins / boardLosses | 団体戦のボード別勝ち星合計 |
| playerRankings | { userId, displayName, wins, winRate }（top-N・退会者匿名化追従） |
| winsByMethod[] | 勝ち方別内訳（チーム集計） |

---

## §7 「ポジション」概念（将棋）

- 将棋に**ポジション（守備位置）はない**。`player_appearances.position` は将棋では使わない（NULL）。
- 団体戦の**ボード順（大将/副将/…）**を表現する必要がある場合は `matches.board_number`（子 match・コア §B.6）に保持する（1=大将 等の順序・大会レギュレーション次第）。ポジション語彙としては扱わない。

---

## §8 将棋固有 UX 細部（ターン制の最小 UI）

### §8.1 対局結果記録 UI（最小・タイマー無し）

ターン制はライブのタイムライン入力（球技の 3 タップ）ではなく、**対局結果の最小入力**が主動線。

- **個人戦（1 局）**: 「対局を記録」→ 先手/後手（対戦相手）→ **勝者を選択**→ **勝ち方を選択（投了/詰み/時間切れ/反則勝ち/千日手/持将棋/不戦勝・§4.1）**→ 任意で総手数・局面写真・コメント。**タイマー・選手グリッド・タイムラインは表示しない**（球技 UI の流用なし）。
- **団体戦**: 「団体戦を記録」→ ボード数（人数）→ 各ボードの対戦カード（自チーム選手 vs 相手選手）を一覧 → 各ボードで勝者＋勝ち方を入力 → 親 match の勝敗がボード勝ち星から自動導出（§4.3）。各ボードは子 match として保存。
- **局面写真**: 任意で局面の写真を添付（§8.2）。コメント（`note`）も任意。

### §8.2 局面写真の添付（既存添付基盤の流用）

- 局面写真は**既存の添付基盤（presign 方式・bulletin 添付と同方式）を流用**する（コア §B.7・03 §C.7）。新規ストレージ機構は作らない。
- **SVG 除外・サイズ上限（10MB 等・既存基盤の制約踏襲）・IDOR 逆引き（match_id 帰属確認）** はコア 03 の局面写真添付セクションに従う（既存 bulletin 添付の IDOR/SVG 除外/サイズ上限と同じ実装パターン）。
- 写真は `POSITION_PHOTO` イベント or match 添付として保持（実装は §B.7 で match スコープの添付として定義）。

### §8.5 ターン制トラッカー（`useMatchTurnTracker`）

- **タイマー状態機械は使わない**（時間概念なし）。代わりに**`useMatchTurnTracker`（ターン制専用・コア 04 §G.16 で動的 import）**を用いる。これは状態が `WAITING → IN_PROGRESS → COMPLETED` の最小遷移のみで、**手数（任意入力）・勝者・勝ち方・写真・コメント**を管理する軽量 composable。
- 球技用の `useMatchTimerSoccer`/`useMatchTimerBasketball`/`useMatchSetTracker` とは全く異なる（時間・セット概念がないため）。

---

## §9 i18n namespace（将棋固有ラベル）

| namespace | 将棋固有の中身 |
|-----------|------------------|
| `match.event_type` | GAME_RESULT→「対局結果」・MOVE_COUNT→「総手数」・POSITION_PHOTO→「局面写真」・COMMENT→「コメント」 |
| `match.win_method`（ターン制共通・新設） | RESIGNATION→「投了」・CHECKMATE→「詰み」・TIMEOUT→「時間切れ」・FOUL_WIN→「反則勝ち」・REPETITION→「千日手」・IMPASSE→「持将棋」・DEFAULT_WIN→「不戦勝」を 6 言語表示 |
| `match.board`（団体戦共通・新設） | ボード順ラベル（「大将」「副将」等・board_number 表示） |

- `match.json` 共通ファイルに namespace 追加（コア 04 §G.6）。`match.win_method.*` はターン制競技共通でコアが新設（将棋・囲碁で共有・各競技が自分の勝ち方キーを引く）。

---

## §10 雛形準拠の確認

本書はコア [01_soccer.md](./01_soccer.md) §10 の手順に従い、`Sport.SHOGI` 追加・event_type 集合（§2・結果系少数）・period（§3・**ピリオド無し・総手数 `total_moves`**）・勝敗（§4・**勝ち方 enum `ShogiWinMethod`＋団体戦の親子ボード勝ち星集計**）・規律コード（§5・非使用）・統計（§6・勝率/勝ち方別/平均手数）・ポジション（§7・非使用）・UX（§8・**ターン制最小 UI・タイマー無し・局面写真添付・`useMatchTurnTracker`**）・i18n（§9）を定義した。**本書で初めて団体戦（`parent_match_id`/`board_number`・コア §B.6）・ターン制（`total_moves`・コア §B.1 拡張）・局面写真添付（コア §B.7）を使用**。コアのテーブル・権限・IDOR・F00 可視性・WebSocket 観戦の骨格は一切再実装しない（球技固有の出場時間算出・スコア合算ロジックはターン制では起動しない＝コア §D.6 の類型分岐）。
