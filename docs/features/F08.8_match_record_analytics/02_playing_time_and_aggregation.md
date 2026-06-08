# F08.8 / 02: 出場時間自動算出ロジック・集計 API

> **ステータス**: 🟡 設計中
> **最終更新**: 2026-06-08
> **関連機能番号**: F08.8（試合記録・分析）／ F07.2 パフォーマンス管理
> **関連ドキュメント**:
> - [01_domain_and_ddl.md](./01_domain_and_ddl.md) — `match_events` / `player_appearances` のスキーマ・enum
> - [04_frontend_and_ux.md](./04_frontend_and_ux.md) — チャート種別との対応
> - [03_permissions_and_recording_modes.md](./03_permissions_and_recording_modes.md) — 集計 API の認可

本書は **E（出場時間自動算出ロジック）／ F（集計 API）** を具体化する。

---

## E. 出場時間自動算出ロジック

出場時間は**手入力させず、`match_events` から自動算出**する（GoalNote 上位互換の核）。算出主体は `PlayingTimeCalculationService`。

### E.1 算出ルール

1 試合・1 選手の `player_appearances` 行を、当該 match のイベント集合から次のルールで決定する。

| 項目 | 決定ルール |
|------|-----------|
| `in_minute` | STARTER → `0`／SUB_IN → そのイベントの `minute`（+ stoppage は §E.4） |
| `out_minute` | SUB_OUT / RED_CARD / SECOND_YELLOW → そのイベントの `minute`／いずれも無ければ `matches.duration_minutes`（延長込みの総試合長） |
| `computed_minutes` | `max(0, out_minute - in_minute)` |
| `is_starter` | STARTER イベントがあれば true、SUB_IN のみなら false |

- 退場（RED_CARD / SECOND_YELLOW）は以降ピッチに居ないため `out_minute` を確定させる。SUB_OUT と退場が両方ある異常データは「より早い分」を out とする（症状を隠さず、整合チェックで警告・§E.5）。
- `duration_minutes` が NULL（試合長未設定）の場合、out 未確定の選手は `computed_minutes=NULL`（不明）とし、ゼロ埋めや握りつぶしをしない。COMPLETED 遷移時に `duration_minutes` 必須を促す（§E.3）。

### E.2 フル再計算 upsert（イベント保存時）

イベントの追加・編集・削除のたびに、**当該 match の appearances をフル再計算して upsert** する（差分計算ではなくフル再構築。整合性を担保し症状を隠さない＝根治）。

```
on MatchEvent change (create/update/delete) for matchId:
  events := match_events.findByMatchId(matchId) ordered by (period, minute, sort_seq)
  perPlayer := group events by (player_user_id or player_name+team_side+owning_team_id)
  for each player:
     in  := STARTER?0 : SUB_IN.minute
     out := earliest(SUB_OUT.minute, RED.minute, SECOND_YELLOW.minute) ?? duration_minutes
     computed := max(0, out - in)
     upsert player_appearances(match_id, player, in, out, computed, is_starter, side, ...)
  // events に現れなくなった（削除された）選手の appearance は削除する（フル同期）
  deleteAppearancesNotIn(matchId, currentPlayers)
```

- upsert キーは登録選手 = `UNIQUE(match_id, player_user_id)`、未登録選手 = アプリ層キー（`player_name`＋`team_side`＋`owning_team_id`／01 §未解決 3）。
- この再計算は `@Transactional`（match ドメイン内に閉じる・原則 5）。
- パフォーマンス: 1 試合のイベント数は高々数十〜百件なのでフル再計算で十分。大量試合の一括取込時のみバルク再計算を別途検討（§未解決）。

### E.3 COMPLETED 遷移時の確定再計算

- `status` を `COMPLETED` にする際、`duration_minutes` を必須化（未設定なら 400・症状を隠さない）し、out 未確定の全選手の `out_minute=duration_minutes` で確定再計算する。
- COMPLETED 後にイベントを訂正した場合も再計算を再走させる（締切ロックは tournament fixture 側の roster_deadline とは別概念。COMPLETED 後の訂正可否は 03 §C の権限に従う）。

### E.4 アディショナルタイム（stoppage）の算入

- `match_events.stoppage_minute` は「45+2」の "2" を保持する。
- **出場時間算出では `minute` を主、`stoppage_minute` は表示用の補助**とし、`computed_minutes` には原則 `minute` ベースで算入する（45+2 で交代しても in/out は 45 を採用）。
- 理由: アディショナルタイムは公式記録でも分計上が曖昧なため、二重カウントや過大計上を避ける。`stoppage_minute` は**イベントのタイムライン表示順とラベル**（"45+2'"）にのみ用いる。
- 将来「アディショナルを出場分に算入する」要件が出たら、`in/out` を `minute + stoppage_minute` で計算するモードをチーム/大会設定で切替可能にする（§未解決）。

### E.5 スコア整合チェック（握りつぶさない）

- `matches.home_score` / `away_score`（正本キャッシュ）と、`match_events` の `GOAL`＋`PENALTY_GOAL`（自サイド）＋相手の `OWN_GOAL` を集計した値を比較する。
- 不一致時は**例外で握りつぶさず警告を返す**: 集計 API レスポンス・ライブ記録 UI に「スコア(2) とイベント得点集計(1) が不一致」を表示する。
- スコアの正本はあくまで `home_score`（記録係が最終確定）。イベントは抜け漏れがあり得るため、**自動で書き換えず**乖離を可視化して人が判断する（根治治療＝症状を隠さない）。
- OWN_GOAL は**相手サイドのスコアに加算**して集計する（01 §D.2 表）。

### E.6 算出ロジックの単体テスト方針（抜粋）

| ケース | 期待 |
|--------|------|
| フル出場（STARTER・交代なし・duration=90） | in=0/out=90/computed=90/starter=true |
| 後半 60 分から出場（SUB_IN@60） | in=60/out=90/computed=30/starter=false |
| 70 分で交代 OUT（STARTER＋SUB_OUT@70） | in=0/out=70/computed=70 |
| 80 分で一発退場（STARTER＋RED@80） | in=0/out=80/computed=80 |
| SUB_OUT@70 と RED@65 が両方（異常） | out=65（より早い分）＋整合警告 |
| duration 未設定で COMPLETED 遷移 | 400（必須化） |
| GOAL 2 件だがスコア 1 | computed は正常・整合警告フラグ true |

---

## F. 集計 API（チャート用）

集計は `MatchStatsAggregationService` が `match_events` / `player_appearances` / `matches` から導出する。認可は [03](./03_permissions_and_recording_modes.md) に従う。

### F.1 個人キャリア統計

```
GET /api/v1/users/{userId}/match-stats?from=&to=&teamId=&kind=&sport=
```

| クエリ | 説明 |
|--------|------|
| from / to | 集計期間（kickoff_at 基準・ISO 日付） |
| teamId | 特定チーム所属時の絞り込み（任意） |
| kind | PRACTICE/FRIENDLY/TOURNAMENT/LEAGUE 絞り込み（任意） |
| sport | 競技絞り込み（任意・既定 SOCCER） |

**レスポンス DTO（`UserMatchStatsResponse`）の指標**:

| 指標 | 算出元 |
|------|--------|
| totalMatches | 出場した（appearance がある）試合数 |
| totalMinutes | Σ computed_minutes |
| goals | GOAL＋PENALTY_GOAL（自分が主体） |
| assists | ASSIST（自分が主体） |
| ownGoals | OWN_GOAL（自分が主体・自責点） |
| yellowCards / redCards | YELLOW_CARD ＋SECOND_YELLOW / RED_CARD |
| starterRate | starter 試合数 / totalMatches |
| avgMinutes | totalMinutes / totalMatches |
| goalsPer90 | goals / (totalMinutes / 90) |
| monthlyTrend[] | 月別 { month, matches, minutes, goals, assists }（ライン用） |
| seasonTrend[] | シーズン別配列（同上・期間粒度違い） |
| byKind[] | kind 別内訳 { kind, matches, goals, ... }（doughnut/bar 用） |

### F.2 個人タイムライン

```
GET /api/v1/users/{userId}/match-stats/timeline?from=&to=
```

- 出場した試合を時系列で返す（試合ごとの { matchId, kickoffAt, opponent, computedMinutes, goals, assists, cards, result(W/D/L) }）。
- 個人分析画面の試合履歴リスト・ライン推移の元データ。

### F.3 チーム統計

```
GET /api/v1/teams/{teamId}/match-stats?from=&to=&kind=&sport=
```

**レスポンス DTO（`TeamMatchStatsResponse`）**:

| 指標 | 説明 |
|------|------|
| wins / draws / losses | team_side と home_score/away_score から判定（W/D/L） |
| totalGoalsFor / totalGoalsAgainst | 得点 / 失点合計 |
| goalDifference | 得失点差 |
| playerRankings | 選手別ランキング { userId, displayName, goals, assists, minutes }（bar 用・displayName は退会者匿名化追従・原則 4） |
| byKind[] | kind 別内訳（勝敗・得失点） |
| recentForm[] | 直近 N 試合の結果配列（W/D/L・ライン/フォーム表示） |

### F.4 試合内 API

```
GET /api/v1/matches/{matchId}/events       -- タイムライン（period/minute/sort_seq ソート）
GET /api/v1/matches/{matchId}/appearances  -- 出場時間一覧（両サイド・computed_minutes 込み）
```

- いずれも `matchId` → org/team 帰属確認の IDOR チェーンを通す（[03](./03_permissions_and_recording_modes.md)）。
- レスポンスに E.5 のスコア整合警告フラグ（`scoreMismatch: boolean`）を含める。

### F.5 レスポンス指標とチャート種別の対応

| チャート種別（chart.js） | 用途 | データ源 |
|--------------------------|------|----------|
| **radar** | 個人スタッツ分布（得点・アシスト・出場・守備等の多軸バランス） | F.1 個人統計の主要指標を正規化 |
| **line** | 得点/出場時間の月別・シーズン別推移 | F.1 `monthlyTrend[]` / `seasonTrend[]`・F.2 timeline |
| **doughnut** | ポジション傾向・kind 別出場割合 | F.1 `byKind[]`・position 集計 |
| **bar** | 得点分布・選手別ランキング | F.3 `playerRankings`・F.1 `byKind[]` |

- DTO は集計済みの**チャートが直接描ける形**（labels 配列＋values 配列に変換しやすい構造）で返す。FE 側で再集計しないことで any 排除と型安全を担保（[04](./04_frontend_and_ux.md)）。
- 全 DTO は `@Builder`（Response DTO の規約）で構築する。

---

## 未解決事項

1. **未登録選手のキャリア集計**: `player_user_id=NULL`（手入力選手名）は個人キャリア統計（F.1）に集計できない（userId が無い）。チーム統計（F.3）では `player_name` ベースで集計するが、同名別人の混在リスクがある。アプリ登録への誘導 UX で緩和する想定だが、集計上の扱いを確定する必要がある。
2. **アディショナルタイム算入モード**: §E.4 で `minute` ベースを既定としたが、チーム/大会単位で「stoppage 算入」を切替可能にするか。要件確定待ち。
3. **大量試合一括取込時の再計算性能**: §E.2 のフル再計算は 1 試合単位では十分だが、CSV 一括取込（旧 GoalNote データ移行等）では試合ごとに走らせるとコストが嵩む。バルク再計算 API の要否。
4. **goalsPer90 の分母ゼロ**: totalMinutes=0 のとき goalsPer90 は NULL（未定義）として返す（0 除算を握りつぶさない）。FE 表示は「—」。この扱いで確定してよいか。
5. **シーズン境界の定義**: `seasonTrend[]` のシーズン区切り（年度 4 月始まり / 暦年 / 大会シーズン）。チーム設定 or 組織設定のどれを正本にするか。
