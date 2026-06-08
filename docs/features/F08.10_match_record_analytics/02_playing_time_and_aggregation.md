# F08.10 / 02: 出場時間自動算出ロジック・集計 API

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-08
> **関連機能番号**: F08.10（試合記録・分析）／ F07.2 パフォーマンス管理 ／ F19.1 個人プロフィール公開
> **関連ドキュメント**:
> - [01_domain_and_ddl.md](./01_domain_and_ddl.md) — `match_events` / `player_appearances` のスキーマ・enum・PK 戦スコア列
> - [04_frontend_and_ux.md](./04_frontend_and_ux.md) — チャート種別との対応・空状態
> - [03_permissions_and_recording_modes.md](./03_permissions_and_recording_modes.md) — 集計 API の認可・プライバシー
> - [sports/01_soccer.md](./sports/01_soccer.md) — **サッカー固有のスコア計算（延長合算/PK 分離・§4）・統計定義（得点/アシスト/90 分換算等・§6）**

本書は **E（出場時間自動算出ロジック）／ F（集計 API）** を具体化する。
**出場時間自動算出の枠組み・集計 API の枠組み（エンドポイント形・認可/プライバシー）は競技非依存のコア＝本書**。**競技固有のスコア計算ルール（延長得点の本戦合算・PK 戦分離・勝敗判定）と統計指標の定義（得点/アシスト/自責点/90 分あたり得点 等）は [sports/01_soccer.md](./sports/01_soccer.md)（§4・§6）** に分離する。本書の枠組み記述で現れる具体的 event_type 名（GOAL/PENALTY_GOAL 等）はサッカーカタログ（[sports/01_soccer.md](./sports/01_soccer.md) §2）の値であり、他競技では各カタログの値に読み替える。

---

## E. 出場時間自動算出ロジック

出場時間は**手入力させず、`match_events` から自動算出**する（GoalNote 上位互換の核）。算出主体は `PlayingTimeCalculationService`。

### E.1 算出ルール（複数交代・再出場対応）【要改善の根治】

1 試合・1 選手の `player_appearances` 行を、当該 match のイベント集合から決定する。**1 選手が複数回出入りする（STARTER→SUB_OUT→SUB_IN 等の再出場）ケースをサポート**するため、`computed_minutes` は**全 in/out 区間の合計**で算出する。

```
1 選手のイベントを時系列（period, minute, sort_seq）に並べ、in/out 区間を組み立てる:
  STARTER       → 区間を開始（in=0）
  SUB_IN        → 区間を開始（in=minute）。直前の区間が開いていなければ新区間
  SUB_OUT       → 開いている区間を閉じる（out=minute）
  RED_CARD / SECOND_YELLOW → 開いている区間を閉じる（out=minute・以降出場不可）
  試合終了時に開いたままの区間 → out=duration_minutes（延長込みの試合通算分）で閉じる
computed_minutes = Σ max(0, out_i - in_i)   ← 全区間の合計
first_in_minute  = 最初の区間の in（代表値・表示用）
last_out_minute  = 最後の区間の out（代表値・表示用）
is_starter       = STARTER イベントがあれば true
```

| 項目 | 決定ルール |
|------|-----------|
| `computed_minutes` | 全 in/out 区間の `Σ max(0, out - in)`（再出場を加算） |
| `first_in_minute` | 最初の出場開始分（STARTER → 0／初回 SUB_IN → その分）。代表値 |
| `last_out_minute` | 最後の退場分（SUB_OUT / RED / 2nd YELLOW → その分／無ければ `duration_minutes`）。代表値 |
| `is_starter` | STARTER イベントがあれば true、SUB_IN のみなら false |

- 退場（RED_CARD / SECOND_YELLOW）は以降ピッチに居ないため当該区間の `out` を確定させ、それ以降の区間は作らない。SUB_OUT と退場が同一区間に両方ある異常データは「より早い分」を out とする（症状を隠さず、整合チェックで警告・§E.5）。
- **延長は試合通算分で正規化**する。`duration_minutes` は前後半 90＋延長を含む試合通算（例: 延長ありなら 120）を入れ、開いた区間はこの通算分で閉じる。
- `duration_minutes` が NULL（試合長未設定）の場合、out 未確定の区間は `computed_minutes` を確定できないため `computed_minutes=NULL`（不明）とし、ゼロ埋めや握りつぶしをしない。COMPLETED 遷移時に `duration_minutes` 必須を促す（§E.3）。

### E.2 フル再計算 upsert（イベント保存時）— matches.version 非依存【楽観ロック競合回避の根治】

イベントの追加・編集・削除のたびに、**当該 match の appearances をフル再計算して upsert** する（差分計算ではなくフル再構築。整合性を担保し症状を隠さない＝根治）。

```
on MatchEvent change (create/update/delete) for matchId:
  events := match_events.findByMatchId(matchId) ordered by (period, minute, sort_seq)
  perPlayer := group events by player key
       登録選手   = player_user_id
       未登録選手 = (jersey_number, player_name, team_side)   ← 01 §D.4 同一性キー
  for each player:
     区間を組み立て（E.1）→ computed := Σ max(0, out - in)
     upsert player_appearances(match_id, player, first_in, last_out, computed, is_starter, side, ...)
  // events に現れなくなった（削除された）選手の appearance は削除する（フル同期・ただし E.5a の権限スコープ内）
  deleteAppearancesNotIn(matchId, currentPlayers, withinEditableTeamSide)
```

#### 楽観ロック競合の回避

- **フル再計算 upsert は `matches.version` に一切触れない**。`player_appearances` のみを更新する。これにより共同記録で両チームが同時にイベントを積んでも `matches` 行を奪い合わず、`matches.version` の 409 が発生しない。
- 楽観ロックの粒度は**イベント行単位（`match_events` 個別行）**を優先する（[03](./03_permissions_and_recording_modes.md) §C 共同記録）。
- **スコアキャッシュ（home/away_score）の更新は `matches.version` 非依存のアトミック増減**、または**読み取り時に GOAL 集計から導出**する方式とし、共同記録での `matches` 行奪い合いを避ける。記録係による最終スコア確定（メタ更新）は `matches.version` を用いる（[03](./03_permissions_and_recording_modes.md) §C.2）。
- この再計算は `@Transactional`（match ドメイン内に閉じる・原則 5）。
- パフォーマンス: 1 試合のイベント数は高々数十〜百件なのでフル再計算で十分。大量試合の一括取込時のみバルク再計算を別途検討（§未解決）。

### E.2a スコア計算ルール（器はコア・具体ルールは競技固有）

スコアの**器**はコアの汎用カラム（本戦 `home/away_score`・PK 戦 `home/away_penalty_score`・[01](./01_domain_and_ddl.md) §B.1）で競技非依存だが、**どの period/event_type をどのスコアに合算するか（合算ルール）は競技固有**である。

> **競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §4 参照**: サッカーの具体ルール（延長得点の本戦合算・PK 戦の `home/away_penalty_score` 分離・本戦スコア整合チェックの突合式＝`GOAL＋PENALTY_GOAL（自サイド）＋相手 OWN_GOAL`・勝敗判定）はサッカー競技カタログに集約した。
>
> - 本戦スコアと PK 戦スコアは別カラムで分離する（器はコア）。
> - 延長別カラムは持たず、延長得点は本戦スコアへ合算する（サッカーのセマンティクス・§4.1）。
> - PK 戦（`PENALTY_SHOOTOUT`）のみ本戦集計の対象外で `home/away_penalty_score` にのみ加算し、個人キャリアの `goals` にも含めない（[sports/01_soccer.md](./sports/01_soccer.md) §4・§6）。
>
> 整合チェックの**枠組み**（握りつぶさず乖離を可視化・スコア正本は記録係確定）は §E.5 のコアに残し、サッカーの具体突合式は [sports/01_soccer.md](./sports/01_soccer.md) §4.2 を参照する。

### E.3 COMPLETED 遷移時の確定再計算

- `status` を `COMPLETED` にする際、`duration_minutes` を必須化（未設定なら 400・症状を隠さない）し、out 未確定の全区間を `out=duration_minutes` で確定再計算する。
- COMPLETED 後にイベントを訂正した場合も再計算を再走させる（締切ロックは tournament fixture 側の roster_deadline とは別概念。COMPLETED 後の訂正可否は 03 §C の権限に従う）。
- POSTPONED（延期）/ CANCELLED（中止）は順位導出・確定再計算の対象外（01 §B.1.1）。

### E.4 アディショナルタイム（stoppage）の算入

- `match_events.stoppage_minute` は「45+2」の "2" を保持する。
- **出場時間算出では `minute` を主、`stoppage_minute` は表示用の補助**とし、`computed_minutes` には原則 `minute` ベースで算入する（45+2 で交代しても in/out は 45 を採用）。
- 理由: アディショナルタイムは公式記録でも分計上が曖昧なため、二重カウントや過大計上を避ける。`stoppage_minute` は**イベントのタイムライン表示順とラベル**（"45+2'"）にのみ用いる。
- 将来「アディショナルを出場分に算入する」要件が出たら、`in/out` を `minute + stoppage_minute` で計算するモードをチーム/大会設定で切替可能にする（§未解決）。

### E.5 スコア整合チェック（握りつぶさない）— 枠組み（コア）

- **枠組み（競技非依存）**: `matches.home_score`/`away_score`（本戦正本キャッシュ）と、`match_events` から導出した得点集計値を比較し、不一致時は**例外で握りつぶさず警告を返す**（集計 API レスポンス・ライブ記録 UI に「スコア(2) とイベント得点集計(1) が不一致」を表示）。
- スコアの正本はあくまで `home_score`（記録係が最終確定）。イベントは抜け漏れがあり得るため、**自動で書き換えず**乖離を可視化して人が判断する（根治治療＝症状を隠さない）。
- **競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §4.2 参照**: 「イベントから導出した得点集計値」の**具体突合式**（サッカー＝`GOAL＋PENALTY_GOAL（自サイド・本戦＝延長含む）＋相手 OWN_GOAL`・PK 戦 `PENALTY_SHOOTOUT` は対象外・OWN_GOAL は相手サイドへ加算）は競技固有のため、サッカー競技カタログに集約した。他競技では各カタログの得点合算ルールに従う。

### E.5a 再計算の破壊耐性（変更権限スコープ内に限定）【セキュリティ要改善の根治】

- フル再計算の「events に現れなくなった選手の appearance を削除する」フル同期（§E.2）は、**変更権限のあるチーム（`team_side`）分の appearance に限定**する。
- すなわち、自チーム（記録した `recorded_by_team_id` / `owning_team_id`）のイベント変更が、**相手チームの appearance を巻き添えで削除しないようにする**。削除対象を `withinEditableTeamSide`（操作者が編集権限を持つ side）でフィルタする。
- 共同記録で両チームが各々自サイドを記録する際、片方の再計算がもう片方のデータを破壊しないことを保証する（IDOR/破壊の根治・[03](./03_permissions_and_recording_modes.md) §C）。

### E.6 算出ロジックの単体テスト方針（抜粋）

| ケース | 期待 |
|--------|------|
| フル出場（STARTER・交代なし・duration=90） | first_in=0/last_out=90/computed=90/starter=true |
| 後半 60 分から出場（SUB_IN@60） | first_in=60/last_out=90/computed=30/starter=false |
| 70 分で交代 OUT（STARTER＋SUB_OUT@70） | first_in=0/last_out=70/computed=70 |
| **再交代/再出場（STARTER→SUB_OUT@30→SUB_IN@60、duration=90）** | computed=30+30=60（区間合計・first_in=0/last_out=90） |
| **延長出場（STARTER・交代なし・duration=120）** | computed=120（試合通算分で正規化） |
| 80 分で一発退場（STARTER＋RED@80） | first_in=0/last_out=80/computed=80 |
| SUB_OUT@70 と RED@65 が両方（異常） | out=65（より早い分）＋整合警告 |
| **OWN_GOAL（HOME の OWN_GOAL）** | AWAY の本戦スコア +1（符号反転・§E.5） |
| **PK 戦（PENALTY_SHOOTOUT ×5）** | home/away_penalty_score にのみ加算・本戦スコア/個人 goals は不変 |
| **duration 未設定（out 未確定区間あり）** | 該当選手 computed=NULL（不明・ゼロ埋めしない） |
| duration 未設定で COMPLETED 遷移 | 400（必須化） |
| GOAL 2 件だがスコア 1 | computed は正常・整合警告フラグ true |

---

## F. 集計 API（チャート用）

集計は `MatchStatsAggregationService` が `match_events` / `player_appearances` / `matches` から導出する。認可・プライバシーは [03](./03_permissions_and_recording_modes.md) に従う。**API パスはテナント文脈を持つ**（既存 tournament 同様、認証主体の現在 org コンテキストでテナント絞り込みを基底で強制する・[03](./03_permissions_and_recording_modes.md) §C.4）。

### F.1 個人キャリア統計【認可・プライバシー確定】

```
GET /api/v1/users/{userId}/match-stats?from=&to=&kind=&sport=          -- 本人のみ（チーム横断）
GET /api/v1/users/{userId}/teams/{teamId}/match-stats?from=&to=&kind=&sport=  -- 他者閲覧（teamId 必須）
```

| クエリ | 説明 |
|--------|------|
| from / to | 集計期間（kickoff_at 基準・ISO 日付） |
| kind | PRACTICE/FRIENDLY/TOURNAMENT/LEAGUE 絞り込み（任意） |
| sport | 競技絞り込み（任意・既定 SOCCER） |

> **タイムゾーン方針**: `kickoff_at` 基準の `from`/`to` 絞り込み・`monthlyTrend` の月境界・シーズン境界（§F.1 末尾の `seasonTrend[]`・§未解決 5）は、**プロジェクトの既存 TZ 方針（`matches.kickoff_at` は `LocalDateTime` ＝サーバー TZ）に従う**。アカウント別 TZ 表示は FE 表示層（`useDatetime`）で行い、集計の境界判定はサーバー TZ で一貫させる（タイムゾーン根治シリーズの方針を踏襲）。

#### 認可・プライバシー（殿裁可）

- **デフォルトは本人のみ**（`userId == self`）。`GET /users/{userId}/match-stats`（teamId 無し＝チーム横断集計）は**本人限定**。
- **他者閲覧は teamId 必須パスパラメータ化**（`/users/{userId}/teams/{teamId}/match-stats`）し、次の**二重検証**を通す:
  1. `AccessControlService.isAdminOrAbove(viewer, teamId, "TEAM")`（閲覧者が当該チームの ADMIN/DEPUTY 以上）
  2. 対象 `userId` が当該 `teamId` に所属している（`accessControlService.isMember(userId, teamId, "TEAM")`）
- **個人統計の公開可否は F19.1 プロフィール公開設定を正本に連動**する。F19.1 で「統計を公開」設定の対象ユーザーは、上記 ADMIN 条件を満たさない閲覧者にも当該 team スコープで開示し得る（F19.1 の可視性レベルに従う）。F19.1 非公開なら ADMIN/本人のみ。
- 未登録選手（`player_user_id=NULL`）は `userId` が無いため本 API の対象外（01 §D.4・キャリア横断は登録ユーザーのみ）。
- ページング: timeline（F.2）はページング、playerRankings（F.3）は top-N 上限。

**レスポンス DTO（`UserMatchStatsResponse`）の指標**:

- **枠組み（競技非依存・コア）**: 個人キャリア統計のレスポンス DTO（`UserMatchStatsResponse`）として、出場系（totalMatches/totalMinutes/starterRate/avgMinutes）・トレンド系（monthlyTrend[]/seasonTrend[]/byKind[]）の**構造**を返す。`goalsPer90` 等の 90 分換算・分母ゼロ NULL（§未解決 4）も枠組みとして共通。
- **競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §6.1 参照**: 各指標の**具体的な算出定義**（goals=GOAL＋PENALTY_GOAL〔本戦のみ・PK 戦除外〕／assists=ASSIST／ownGoals=OWN_GOAL〔自責点〕／yellowCards・redCards／goalsPer90 等）はサッカー固有のため、サッカー競技カタログの統計定義に集約した。他競技では各カタログの指標定義に従う（バスケ＝リバウンド/スティール等）。

### F.2 個人タイムライン

```
GET /api/v1/users/{userId}/match-stats/timeline?from=&to=&page=&size=    -- 本人（チーム横断）
GET /api/v1/users/{userId}/teams/{teamId}/match-stats/timeline?...        -- 他者（teamId 必須・F.1 と同じ認可）
```

- 出場した試合を時系列で返す（試合ごとの { matchId, kickoffAt, opponent, computedMinutes, goals, assists, cards, result(W/D/L) }）。
- 個人分析画面の試合履歴リスト・ライン推移の元データ。**ページング必須**（大量試合での N+1/肥大を回避）。

### F.3 チーム統計

```
GET /api/v1/teams/{teamId}/match-stats?from=&to=&kind=&sport=
```

- 認可: 当該 `teamId` のメンバー以上。`playerRankings`（選手別ランキング）の閲覧は **min_role=MEMBER 以上（SUPPORTER 除外）** を確定し、F02.2.1 min_role 正本に登録する（[04](./04_frontend_and_ux.md) §G.9・[06](./06_implementation_plan.md) §I.4）。
- `home_away=NEUTRAL`（中立地）は HOME/AWAY 別成績に混入させず別カテゴリで扱う（01 §未解決 4）。

**レスポンス DTO（`TeamMatchStatsResponse`）**:

- **枠組み（競技非依存・コア）**: チーム統計のレスポンス DTO（`TeamMatchStatsResponse`）として、勝敗系（wins/draws/losses・recentForm[]）・選手別ランキング（playerRankings・top-N 上限・displayName は退会者匿名化追従・原則 4）・kind 別内訳（byKind[]）の**構造**を返す。
- **競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §6.2 参照**: 各指標の**具体的な算出定義**（W/D/L 判定＝team_side と本戦スコア／totalGoalsFor・totalGoalsAgainst／goalDifference〔得失点差〕／playerRankings の goals/assists 定義）はサッカー固有のため、サッカー競技カタログの統計定義に集約した。勝敗判定の具体（PK 戦勝敗を含む）は [sports/01_soccer.md](./sports/01_soccer.md) §4.3 を参照。

> **N+1 / 大量試合**: `playerRankings` は top-N 上限で返す。試合数が膨大なチーム/大会では将来サマリテーブル（事前集計）を設ける余地を残す（§未解決）。

### F.4 試合内 API

```
GET /api/v1/matches/{matchId}/events       -- タイムライン（period/minute/sort_seq ソート）
GET /api/v1/matches/{matchId}/appearances  -- 出場時間一覧（両サイド・computed_minutes 込み）
```

- いずれも `matchId` → org/team 帰属確認の IDOR チェーン（親 matches をテナント取得 → 子は match_id スコープ・01 §A.4）を通す（[03](./03_permissions_and_recording_modes.md)）。
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
- チャート種別との対応は**枠組み（競技非依存）**。各チャートに差し込むサッカー固有の指標（radar の守備軸＝SAVE 等・doughnut のポジション傾向＝GK/DF/MF/FW 等）は**競技固有 → [sports/01_soccer.md](./sports/01_soccer.md) §8.6 参照**。

---

## 未解決事項（全項目解決済み／MVP外の先送り決定を含む）

1. **未登録選手のキャリア集計** — 解決済み（殿裁可）: `player_user_id=NULL`（手入力選手名）は **キャリア横断統計（F.1）の対象外**（userId が無い）。チーム統計（F.3）・タイムラインでは `(jersey_number, player_name, team_side)`（01 §D.4）ベースでその試合内に限り集計する。アプリ登録への誘導 UX で緩和する。
2. **アディショナルタイム算入モード**: §E.4 で `minute` ベースを既定としたが、チーム/大会単位で「stoppage 算入」を切替可能にするかは要件確定待ち（**MVP は `minute` ベース固定で確定**・拡張可否のみ後段判断する**先送り決定＝ブロッカーではない**）。
3. **大量試合一括取込時の再計算性能**: §E.2 のフル再計算は 1 試合単位では十分だが、CSV 一括取込（旧 GoalNote データ移行等）ではバルク再計算 API の要否を後段判断（**MVP は 1 試合単位フル再計算で確定**・**先送り決定＝ブロッカーではない**）。
4. **goalsPer90 の分母ゼロ** — 解決済み（殿裁可）: totalMinutes=0 のとき goalsPer90 は **NULL（未定義）**として返す（0 除算を握りつぶさない）。FE 表示は「—」（[04](./04_frontend_and_ux.md) §G.8）。
5. **シーズン境界の定義**: `seasonTrend[]` のシーズン区切り（年度 4 月始まり / 暦年 / 大会シーズン）。チーム設定 or 組織設定のどれを正本にするか後段判断（**MVP は暦年で暫定確定**・正本選定のみ後段の**先送り決定＝ブロッカーではない**）。
