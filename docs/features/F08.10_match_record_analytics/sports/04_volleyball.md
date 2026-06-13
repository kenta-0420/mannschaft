# F08.10 / sports / 04: バレーボール競技カタログ（VOLLEYBALL）

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-13
> **位置づけ**: **F08.10 コアを継承するバレーボール競技カタログ**。状態モデル類型は **セット制（SET_BASED・コア §D.6）**。本書で初めて **`match_sets` 子表（コア §B.5 で確定）** を用いる。サッカー（連続時間制）とはスコア表現が根本的に異なるため、差分が大きい。
> **関連機能番号**: F08.10（試合記録・分析）／ F08.7 ／ F08.7.1 ／ F07.2 ／ F19.1
> **関連ドキュメント（コア）**:
> - [../README.md](../README.md) — 機能概要・3 状態モデル類型・競技一覧
> - [../01_domain_and_ddl.md](../01_domain_and_ddl.md) — DDL・**`match_sets` 子表（§B.5）・`StateModel` 類型（§D.6）・`MatchEventType` セット制拡張値**
> - [../02_playing_time_and_aggregation.md](../02_playing_time_and_aggregation.md) — 出場時間/集計の枠組み（セット制の出場概念は §2.1）
> - [../04_frontend_and_ux.md](../04_frontend_and_ux.md) — ライブ入力 UX 骨格・`useMatchSetTracker`（セット制トラッカー）
> - [01_soccer.md](./01_soccer.md) — 雛形

---

## §1 概要 — セット制という根本的差分・状態モデル類型

バレーボールは**セット制（SET_BASED・コア §D.6）**であり、サッカー/バスケの「連続時間制」とは試合構造が根本的に異なる:

- 試合は**複数セット（best-of-5）**で構成され、**先に 3 セット先取したチームが勝者**。
- 各セットは**ラリーポイント制**で、**25 点先取（ただし 2 点差が必要＝デュース）**、**最終第 5 セットのみ 15 点先取**。
- **時間（minute）の概念が希薄**で、スコアは「セットごとの得点」で表現される。

このため、サッカーの「スカラ `home_score`/`away_score`（本戦合算）」では表現できない。**コア §B.5 で確定した `match_sets` 子表**（match ドメイン内・親 matches へ CASCADE）にセットごとのスコアを保持し、`matches.home_score`/`away_score` には**獲得セット数**（例 3-1）を格納する（セット制での「本戦スコア」の意味づけ）。

`matches.sport='VOLLEYBALL'`・`matches.state_model='SET_BASED'`（導出可・コア §D.6）で識別する。

---

## §2 event_type カタログ（バレーボール）

バレーは「ラリーごとの得点」が中心で、サッカーのような得点者一意の GOAL とは異なる。コア `MatchEventType` enum（器）にバレー固有値を追加する（コア §D.2）。

```
// コア MatchEventType に追加（器・全競技横断。サッカー/バスケは使わない）
SET_START,       // セット開始（match_sets 行を起こす・set_number 連動）
SET_END,         // セット終了（勝者サイド確定・match_sets を閉じる）
POINT,           // 得点（ラリー獲得・得点サイド・任意で得点種別を detail に）
SERVE_ACE,       // サービスエース（POINT の特殊・得点も計上）
BLOCK_POINT,     // ブロック得点
ATTACK_POINT,    // アタック決定
SERVE_ERROR,     // サーブミス（相手得点・任意記録）
SUBSTITUTION     // 交代（バレーは交代制限あり・MVP は SUB_IN/SUB_OUT 流用でも可）
```

```java
// SportEventCatalog の VOLLEYBALL 集合（コア §D.3）
Sport.VOLLEYBALL, EnumSet.of(STARTER, SUB_IN, SUB_OUT,
                             SET_START, SET_END,
                             POINT, SERVE_ACE, BLOCK_POINT, ATTACK_POINT, SERVE_ERROR,
                             INJURY, OTHER)
```

> **MVP の記録粒度（明示）**: バレーは「全ラリーを 1 件ずつ記録する」と入力負荷が極めて高い（1 セットで 50 ラリー超）。**MVP の既定記録粒度は「セットごとの最終スコアを直接入力」**とし、ラリー単位の `POINT` イベントは**任意の詳細記録モード**に留める（記録者が細かく記録したい場合のみ）。これにより `match_sets` の `home_points`/`away_points` を直接編集する軽量 UI を主動線とし、ラリー逐次記録は副動線（§8.1）。理由: アマチュア大会で全ラリー記録は非現実的・入力摩擦最大。

### §2.1 出場時間・スコアへの影響（バレーボール）

- **「出場時間（分）」概念はバレーに馴染まない**。バレーの出場は「どのセットに出場したか」で表現する。`player_appearances.computed_minutes` は**セット制では NULL**（分概念なし・コア 02 §E.1 の duration 未設定と同じ扱い＝ゼロ埋めしない）とし、代わりに**出場セット数**を `detail` または集計側で `match_sets`×appearance から導出する（MVP は「出場したか否か（is_starter＋SUB 記録）」のみを保持し、セット別出場の精緻化は将来余地・ブロッカーではない）。
- **スコアは `match_sets` が正本**: `POINT`/`SERVE_ACE`/`BLOCK_POINT`/`ATTACK_POINT` は当該セット（`match_sets` の最新オープン行）の得点サイドに +1。`matches.home_score`/`away_score` には**獲得セット数**を集計して反映（SET_END 確定時）。
- ローテーション・サーブ順の精緻記録は**MVP 範囲外**（ブロッカーではない・理由: 記録負荷が高く、勝敗・セットスコア・主要スタッツには不要）。必要なら将来 `detail JSON` に保持する余地を残す。

---

## §3 period モデル（バレーボール）— `match_sets` 子表

バレーは `PeriodType` の前後半/クォーターを使わない。**セットを `match_sets` 子表（コア §B.5）の `set_number`（1〜5）で表現**する。

| 表現 | 保持先 |
|------|--------|
| セット番号（1〜5） | `match_sets.set_number` |
| セットごとの得点 | `match_sets.home_points` / `away_points` |
| セット勝者 | `match_sets.winner_side`（HOME/AWAY・SET_END で確定） |
| 獲得セット数（試合の本戦スコア） | `matches.home_score`/`away_score`（= 勝ちセット数の集計） |

- `match_events.period` には便宜上 `SET_1`〜`SET_5` を入れる（コア `PeriodType` にセット制拡張値 `SET_1`〜`SET_5` を追加・コア §D.1）。または `period` は NULL とし `match_sets.set_number` を正本にする（実装時にどちらかへ統一・MVP は `match_sets.set_number` を正本とし `period` は補助）。
- `matches.period_format` に `'BEST_OF_5'`（5 セットマッチ）を入れる。3 セットマッチは `'BEST_OF_3'`。
- `duration_minutes` はバレーでは意味を持たない（NULL 可）。COMPLETED 遷移時の `duration_minutes` 必須化（コア 02 §E.3）は**セット制では適用しない**（コアの COMPLETED バリデーションは `state_model` 別に分岐＝セット制は「全セット確定」を必須条件にする・コア 02 §E.3 を §D.6 で類型別に拡張）。

---

## §4 スコア計算・勝敗判定（バレーボール）

### §4.1 セットスコアと試合スコア（2 層構造）

- **セット内スコア**: 各 `match_sets` 行の `home_points`/`away_points`。**25 点先取（2 点差必須＝デュース）**、**第 5 セットのみ 15 点先取（2 点差必須）**。
- **試合スコア（本戦）**: `matches.home_score`/`away_score` = 勝ちセット数（例 3-1）。**先に 3 セット先取で試合終了**（best-of-5）。best-of-3 は 2 セット先取。
- **PK 戦・延長別カラムは使わない**（バレーにそれらの概念はない）。

### §4.2 セット確定ルール（デュース）

- セット勝利条件: `max(home_points, away_points) >= setTarget` **かつ** `abs(home_points - away_points) >= 2`。`setTarget` = 通常セット 25 / 第 5 セット 15。
- デュース（24-24 や 14-14）は 2 点差がつくまで継続（27-25・16-14 等）。SET_END はこの条件を満たしたときのみ確定可能とし、満たさない SET_END は **400**（症状を隠さない・記録ミスを弾く）。
- 整合チェック: `match_sets` の勝ちセット数集計と `matches.home_score`/`away_score`（獲得セット数）の一致を検証（コア 02 §E.5 の枠組みに従い、不一致は握りつぶさず警告）。

### §4.3 勝敗判定（W/D/L）

- W/L は獲得セット数（`matches.home_score`/`away_score`）で判定。**バレーに引き分け（D）はない**（必ず 3 セット先取で決着）。
- 順位寄与は tournament 側の `result`/`winner_participant_id` スナップショット（コア 05 §H.2.3）。バレーの順位はセット率・得点率をタイブレークに使う大会があるため、`match_sets` のセット得点合計を fixture スナップショットに含める余地を残す（MVP は勝敗のみ・セット率タイブレークは将来余地・ブロッカーではない）。

---

## §5 規律コード（バレーボール）

バレーの反則・ペナルティ体系（遅延警告・ペナルティ・退場）はサッカー/バスケと別。**MVP では規律コードを記録対象としない**（バレーのカード＝遅延警告/ペナルティは稀で、記録の主目的＝セットスコア・スタッツに不要）。

- `card_reason_code` はバレー試合では NULL のみ許容（コア §C.4b の検証規約に従い、VOLLEYBALL カタログに理由コード集合を登録しない＝コードを付けると 400）。
- **将来差分（MVP 外・ブロッカーではない）**: バレー独自の警告/ペナルティ/退場（イエロー/レッド/レッド＋イエロー）を記録する要件が出たら、`VolleyballSanctionCode` を別カタログとして追加する（コア §D.5 の機構で拡張）。理由: アマチュア記録では反則の理由分類より勝敗・得点記録が優先。

---

## §6 統計定義（バレーボール固有指標）

### §6.1 個人キャリア統計（`UserMatchStatsResponse`）

| 指標 | 算出元（バレー） |
|------|--------|
| totalMatches | 出場した試合数（appearance あり） |
| setsPlayed | 出場セット数（§2.1・MVP は概算） |
| points | POINT＋SERVE_ACE＋BLOCK_POINT＋ATTACK_POINT（自分が主体・詳細記録モード時のみ精緻） |
| aces | SERVE_ACE |
| blocks | BLOCK_POINT |
| attacks | ATTACK_POINT |
| totalMinutes / goalsPer90 系 | **バレーでは無効**（分概念なし・NULL）。FE は当該指標を非表示にする（コア 04 §G.8 null フォールバック） |
| monthlyTrend[] / seasonTrend[] / byKind[] | コア共通枠組み（指標名差し替え・points 等） |

> **詳細記録モード非使用時**: セットスコアのみ記録した試合では個人スタッツ（points/aces 等）は 0 件になる（イベント未記録）。この場合 totalMatches・setsPlayed・勝敗は集計できるが個人得点は「未記録」表示とする（0 と未記録を区別＝コア 02 §F のスコア整合警告と同じ思想で握りつぶさない）。

### §6.2 チーム統計（`TeamMatchStatsResponse`）

| 指標 | 説明（バレー） |
|------|------|
| wins / losses | 獲得セット数で判定（引き分けなし・§4.3） |
| setsWon / setsLost | 勝ちセット・負けセット合計 |
| setRatio | setsWon / setsLost（タイブレーク用・分母 0 は NULL） |
| pointsFor / pointsAgainst | 全セット通算得点 / 失点（`match_sets` 由来） |
| playerRankings | { userId, displayName, points, aces, blocks }（詳細記録時のみ・top-N・退会者匿名化追従） |

---

## §7 ポジション語彙（バレーボール）

| 略号 | 名称 |
|------|------|
| OH | アウトサイドヒッター（レフト） |
| OP | オポジット（ライト） |
| MB | ミドルブロッカー（センター） |
| S | セッター |
| L | リベロ |

- 大分類 OH/OP/MB/S/L を必須語彙。先発 6 人＋リベロを既定とする。doughnut「ポジション傾向」はこの分類で束ねる。

---

## §8 バレーボール固有 UX 細部

### §8.1 入力動線（セットスコア直接入力＝主動線 / ラリー逐次＝副動線）

- **主動線（MVP 既定）**: セットごとに **`home_points`/`away_points` を数値ステッパーで直接入力**する軽量 UI。SET_END でデュース条件（§4.2）を満たすと「セット確定」ボタンが有効化。全セット確定で COMPLETED 可。
- **副動線（詳細記録モード・任意）**: ラリーごとに `[得点（自/相手）]` `[エース]` `[ブロック]` `[アタック]` のプリセットで `POINT` 系イベントを 1 件ずつ記録（得点者を選手グリッドから選択）。この場合 `match_sets` の得点はイベント集計から自動算出。
- モード切替（簡易/詳細）は試合作成時 or 試合中に選べる。**ADHD 配慮で既定は簡易（セットスコア直接入力）**＝入力摩擦最小。

### §8.5 セットトラッカー（`useMatchSetTracker`）・選手グリッド

- **タイマー状態機械の代わりに「セットトラッカー」**を用いる（連続時間制のタイマーは不使用）:

  ```
  WAITING → SET_1 → SET_2 → SET_3 → [SET_4 → SET_5] → COMPLETED
  （[] = 任意。3 セット先取で COMPLETED・残セットは消化されない）
  ```

  - 各セットは「進行中（得点入力可）→ デュース条件達成で確定可能 → 確定（SET_END・次セットへ）」の状態を持つ。
  - **FE composable は `useMatchSetTracker`（セット制専用・コア 04 §G.16 で動的 import）**。サッカー/バスケのタイマー（時間ベース）とは状態モデルが全く異なるため別 composable。セット番号・各セットスコア・デュース判定・勝ちセット数・試合終了判定（3 セット先取）を管理する。
- **選手グリッド**: §7（OH/OP/MB/S/L）で先発 6 人＋リベロを配置。3 段フォールバック（roster→メンバー一覧→手入力）はコア共通。

### §8.6 チャート指標（バレー）

| チャート | バレーでの用途 |
|----------|----------------|
| radar | 得点/エース/ブロック/アタックの多軸バランス（詳細記録時） |
| line | セット率・得点率の月別推移 |
| doughnut | ポジション傾向（OH/OP/MB/S/L） |
| bar | 選手別得点ランキング・セット別得点推移 |

- スコアボード常時表示（コア 04 §G.15(b)）はバレーでは**セットスコア（3-1）＋現セット得点（24-22）**を表示する。

---

## §9 i18n namespace（バレー固有ラベル）

| namespace | バレー固有の中身 |
|-----------|------------------|
| `match.event_type` | POINT→「得点」・SERVE_ACE→「サービスエース」・BLOCK_POINT→「ブロック」・ATTACK_POINT→「アタック決定」・SET_START→「セット開始」・SET_END→「セット終了」等 |
| `match.set`（新設・セット制共通） | セット番号ラベル（「第 1 セット」等）・デュース・「セット確定」・獲得セット数表示 |
| `match.position` | OH/OP/MB/S/L を 6 言語表示 |

- `match.json` 共通ファイルに namespace 追加（コア 04 §G.6）。セット制共通の `match.set.*` はコアが新設する（セット制競技で共有）。

---

## §10 雛形準拠の確認

本書はコア [01_soccer.md](./01_soccer.md) §10 の手順に従い、`Sport.VOLLEYBALL` 追加・event_type 集合（§2・SET_START/POINT 等を器に追加）・period（§3・**`match_sets` 子表＋`SET_1`〜`SET_5`**）・スコア 2 層構造（§4・セット内スコア＋獲得セット数）・規律コード（§5・MVP 非対象）・統計（§6・points/aces 等＋分概念無効化）・ポジション（§7）・UX（§8・`useMatchSetTracker`・簡易/詳細記録モード）・i18n（§9）を定義した。**本書で初めて `match_sets` 子表を使用**（コア §B.5 で確定済の子表設計に整合）。コアの権限・IDOR・F00 可視性・WebSocket 観戦の骨格は一切再実装しない。
