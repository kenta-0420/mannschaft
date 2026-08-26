# F08.10 / sports / 02: フットサル競技カタログ（FUTSAL）

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-13
> **位置づけ**: **F08.10 コアを継承するフットサル競技カタログ**。状態モデル類型は **連続時間制（CONTINUOUS_TIME）**。サッカー（[01_soccer.md](./01_soccer.md)）と最も近く、差分は小さい。本書はサッカー雛形を複製し、フットサル固有の差分のみを記述する（同一の章立ては「サッカーと同一」と明記して重複を避ける）。
> **関連機能番号**: F08.10（試合記録・分析）／ F08.7 ／ F08.7.1 ／ F07.2 ／ F19.1
> **関連ドキュメント（コア）**:
> - [../README.md](../README.md) — 機能概要・3 状態モデル類型・競技一覧
> - [../01_domain_and_ddl.md](../01_domain_and_ddl.md) — ドメイン配置・DDL（汎用の器）・enum（汎用）・**拡張点 `SportEventCatalog`・`StateModel` 類型**（§D.3・§D.6）
> - [../02_playing_time_and_aggregation.md](../02_playing_time_and_aggregation.md) — 出場時間自動算出の枠組み・集計 API の枠組み
> - [../04_frontend_and_ux.md](../04_frontend_and_ux.md) — ライブ入力 UX の骨格・タイマー状態機械・competition 別 composable 動的 import
> - [01_soccer.md](./01_soccer.md) — **雛形**（本書はサッカーとの差分のみを記述）

---

## §1 概要 — サッカーとの関係・状態モデル類型

フットサルは**サッカーと同じ連続時間制（CONTINUOUS_TIME・[../01_domain_and_ddl.md](../01_domain_and_ddl.md) §D.6）**であり、出場時間・スコア計算・カード体系・統計のほとんどがサッカーと共通である。コアの器（`matches`/`match_events`/`player_appearances`・汎用 enum）は一切変更せず、`Sport.FUTSAL` カタログとして差分だけを定義する。

`matches.sport='FUTSAL'` で識別し、Service は `event_type ∈ SportEventCatalog.CATALOG.get(Sport.FUTSAL)` を検証する（コア §D.3）。

**サッカーとの主な差分（要点）**:

| 観点 | サッカー | フットサル |
|------|---------|-----------|
| ピリオド | 前後半 45 分 | 前後半 20 分（プレーイングタイム＝MVP はランニングタイム近似） |
| 累積ファウル | なし | チーム単位の累積ファウル（5 個目以降 = 第 2PK）を**任意記録**（MVP は記録のみ・自動 PK 付与判定はしない） |
| 退場後の復帰 | 退場した選手は復帰不可・人数欠けたまま | 退場後 2 分経過または失点で**別選手が復帰可**（人数回復）→ 出場時間算出に影響（§2.1） |
| タイムアウト | なし | 各チーム前後半 1 回（MVP は `OTHER` イベントで任意記録・出場時間/スコア非影響） |
| ポジション語彙 | GK/DF/MF/FW | GK/フィクソ/アラ/ピヴォ（§7） |

---

## §2 event_type カタログ（フットサル）

サッカーと**同一の `MatchEventType` 集合**を用いる（コア `MatchEventType` enum・[01_soccer.md](./01_soccer.md) §2 と同じ）。フットサル固有の新規 event_type は追加しない（累積ファウル・タイムアウトは `OTHER`＋`custom_label` で記録）。

```java
// SportEventCatalog の FUTSAL 集合（サッカーと同一・コア §D.3）
Sport.FUTSAL, EnumSet.of(STARTER, SUB_IN, SUB_OUT, GOAL, ASSIST, OWN_GOAL,
                         PENALTY_GOAL, PENALTY_MISS, PENALTY_SHOOTOUT,
                         YELLOW_CARD, RED_CARD, SECOND_YELLOW,
                         SAVE, INJURY, PERIOD_START, PERIOD_END, OTHER)
```

### §2.1 event_type の出場時間・スコアへの影響（フットサル）

**スコア影響はサッカーと完全に同一**（GOAL/PENALTY_GOAL=自サイド本戦 +1・OWN_GOAL=相手本戦 +1・PENALTY_SHOOTOUT=PK 戦・[01_soccer.md](./01_soccer.md) §2.1 と同じ）。

**出場時間への差分**: フットサルは退場（RED_CARD/SECOND_YELLOW）後、2 分経過または失点で**別選手が復帰**してチーム人数が回復する。これは退場した本人の出場時間には影響しない（退場者の区間は `out=minute` で確定・サッカーと同じ）。復帰する別選手は通常の `SUB_IN`（区間開始）として記録する。よって**コアの出場時間算出ロジック（区間合計・コア 02 §E.1）をそのまま適用でき、フットサル固有の特別処理は不要**（退場＝当該選手の区間を閉じる／復帰選手＝SUB_IN で新区間、という汎用ルールで表現できる）。

---

## §3 period モデル（フットサル）

サッカーと同じ `PeriodType` 値を用いる（前後半・延長・PK）。クォーター制は使わない。

| period 値 | 意味 | スコア計算（§4） |
|-----------|------|------------------|
| FIRST_HALF | 前半（20 分） | 本戦スコアへ合算 |
| SECOND_HALF | 後半（20 分） | 本戦スコアへ合算 |
| EXTRA_FIRST / EXTRA_SECOND | 延長（大会レギュレーション次第） | 本戦スコアへ**合算** |
| PENALTY_SHOOTOUT | PK 戦 | `home/away_penalty_score` にのみ加算 |

- `matches.period_format` にフットサルは `'HALVES_20'`（前後半 20 分）を入れる。
- `duration_minutes` は前後半 40＋延長で試合通算分を表す（延長あり大会は 50 等）。

---

## §4 スコア計算・勝敗判定（フットサル）

**サッカーと完全に同一**（[01_soccer.md](./01_soccer.md) §4 を参照）。本戦スコアと PK 戦スコアの分離・延長得点の本戦合算・整合チェック突合式（GOAL＋PENALTY_GOAL〔自サイド本戦〕＋相手 OWN_GOAL）・W/D/L 判定はサッカーと同じルールで成立する。フットサル固有のスコア重み付けは存在しない（1 ゴール＝1 点）。

---

## §5 規律コード（フットサル）

MVP では**サッカーの規律コード（`CautionCode` C1〜C8 / `SendingOffCode` S1〜S6・CS）を流用**する（フットサル競技規則の警告・退場理由はサッカーとほぼ同体系であり、MVP では共通カタログで足りる）。

- 検証規約はコア [../03_permissions_and_recording_modes.md](../03_permissions_and_recording_modes.md) §C.4b（「その競技カタログの列挙値かつ event_type 整合」）に従う。FUTSAL カタログはサッカーと同じ `CardReasonCatalog` を参照する形で `Map<Sport, CardReasonCatalog>` に登録する（実装は SOCCER の定数を共有してよい）。
- **将来差分（MVP 外・ブロッカーではない）**: フットサル独自の累積ファウル（第 2PK 制度）の理由分類を別コードにするかは要件顕在化時に判断する。MVP は累積ファウルを `OTHER`＋`custom_label`（例「チーム累積ファウル 5」）で任意記録するに留め、自動 PK 付与判定はしない（記録のみ）。理由: 累積ファウル管理は審判の管轄であり、本機能は「記録」が主目的のため自動判定は過剰機能。

---

## §6 統計定義（フットサル固有指標）

**サッカーと同一の指標**（[01_soccer.md](./01_soccer.md) §6.1・§6.2）を用いる。goals=GOAL＋PENALTY_GOAL（本戦のみ・PK 戦除外）・assists=ASSIST・W/D/L・得失点差・goalsPer90 等すべて共通。フットサル固有の追加指標は MVP では設けない（GK のセーブ数＝SAVE 集計はサッカーと同じく radar の守備軸に流用可）。

---

## §7 ポジション語彙（フットサル）

`player_appearances.position`（汎用カラム）に入れる、フットサルのポジション語彙。

| 略号 | 名称 |
|------|------|
| GK | ゴレイロ（ゴールキーパー） |
| FIXO | フィクソ（守備） |
| ALA | アラ（サイド） |
| PIVO | ピヴォ（前線） |

- 大分類（GK/FIXO/ALA/PIVO）を必須語彙とし、細分は任意で `position` に自由文字列で入れてよい（doughnut「ポジション傾向」は大分類で束ねる）。
- 選手グリッド（§8）の先発配置は GK/FIXO/ALA/PIVO の並びを既定とする。

---

## §8 フットサル固有 UX 細部

コアのライブ入力 UX 骨格（コア [../04_frontend_and_ux.md](../04_frontend_and_ux.md) §G）に対するフットサルの差分。**プリセットボタン・連鎖・理由コード選択 UI はサッカーと同一**（[01_soccer.md](./01_soccer.md) §8.1〜§8.4 を参照）。差分のみ以下。

### §8.5 タイマー状態機械・選手グリッド（フットサル）

- **タイマー状態機械はサッカーと同一の構造**（連続時間制）:

  ```
  WAITING → FIRST_HALF → HALF_TIME → SECOND_HALF → [EXTRA_FIRST → EXTRA_SECOND] → [PENALTY_SHOOTOUT] → COMPLETED
  ```

  ピリオド長が 20 分である点だけがサッカー（45 分）と異なる。タイマー挙動表は [01_soccer.md](./01_soccer.md) §8.5 と同じ。**FE composable は `useMatchTimerSoccer` を再利用可**（前後半の連続時間制は共通・コア 04 §G.16）。フットサル専用に分ける必要が出た場合（累積ファウル表示等）のみ `useMatchTimerFutsal` を派生する余地を残す（MVP では `useMatchTimerSoccer` 流用で確定）。
- **選手グリッド**: §7 のポジション語彙（GK/FIXO/ALA/PIVO）で先発を上段・控えを下段。5 人制（GK＋4 フィールド）を既定の先発人数とする（roster→メンバー一覧→手入力の 3 段フォールバックはコア 04 §G.1c と同じ）。

### §8.6 チャート指標（フットサル）

サッカーと同一（radar=得点/アシスト/出場/守備、doughnut=ポジション傾向 GK/FIXO/ALA/PIVO、line=推移、bar=ランキング）。

---

## §9 i18n namespace（フットサル固有ラベル）

`match.json`（競技共通ファイル・コア 04 §G.6）内の namespace に、フットサル固有のラベル差分を追加する。

| namespace | フットサル固有の中身 |
|-----------|--------------------|
| `match.event_type` | サッカーと同一ラベル群を流用（GOAL→「得点」等） |
| `match.card_type` | サッカーと同一（警告/退場） |
| `match.card_reason` | サッカーと同一（C1〜C8 / S1〜S6 / CS の短ラベル流用） |
| `match.position` | **フットサル固有**: GK→「ゴレイロ」・FIXO→「フィクソ」・ALA→「アラ」・PIVO→「ピヴォ」を 6 言語表示 |

- `match.position` のみフットサル独自語彙のため、競技を判別して表示するキー設計（例 `match.position.futsal.PIVO`）または `match.sport='FUTSAL'` 時にフットサル語彙を引くマッピングを FE で行う（コアの namespace 機構に従い、ファイルは `match.json` 共通）。

---

## §10 雛形準拠の確認

本書はコア [01_soccer.md](./01_soccer.md) §10 の新競技追加手順に従い、`Sport.FUTSAL` を追加し、event_type 集合（§2・サッカーと同一）・period（§3）・スコア（§4・同一）・規律コード（§5・流用）・統計（§6・同一）・ポジション（§7・差分）・UX（§8・差分小）・i18n（§9・position のみ差分）を定義した。コアのテーブル（器）・出場時間算出・集計・権限・IDOR・F00 可視性・WebSocket 観戦の骨格は一切再実装しない。
