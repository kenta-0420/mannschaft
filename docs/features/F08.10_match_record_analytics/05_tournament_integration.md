# F08.10 / 05: tournament 統合・既存コード作り替え・Match*→Fixture* 改称

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-08
> **関連機能番号**: F08.10（試合記録・分析）／ F08.7（大会・リーグ管理）／ F08.7.1
> **関連ドキュメント**:
> - [01_domain_and_ddl.md](./01_domain_and_ddl.md) — `matches.tournament_fixture_id`（BIGINT）リンク・fixture BIGINT 据え置き
> - [02_playing_time_and_aggregation.md](./02_playing_time_and_aggregation.md) — スコア正本・イベント集計・PK 戦分離
> - [F08.7_tournament_league.md](../F08.7_tournament_league.md) — 既存 `tournament_matches` / 順位表 / 個人ランキング
> - 既存実装: `TournamentMatchEntity` / `MatchController`（org スコープ）/ `MatchService` / `StandingsCalculationService` / `RankingsCalculationService` / `TournamentMatchSetEntity`
> - [sports/01_soccer.md](./sports/01_soccer.md) — サッカー固有のスコア計算・勝敗判定（§4・延長合算/PK 分離/PK 勝敗）

本書は **H（tournament 統合・既存コード作り替え）** を具体化する。**グリーンフィールド**（未デプロイ・運用データ無し）のため、後方互換を考えず最も綺麗な形へ作り替える。
**tournament 統合の枠組み（fixture 化・スコア正本化・順位導出のイベント駆動・スナップショット・participant⇔team_side 変換）は競技非依存のコア＝本書**。スコアの**合算ルール・勝敗判定の具体（延長得点の本戦合算・PK 戦勝敗）はサッカー固有**であり [sports/01_soccer.md](./sports/01_soccer.md) §4 を参照する。

---

## H.0 入口① 第一陣 BE の採用方針 — 中道（既存 tournament 非破壊・改称は後続フェーズ）【マスター御裁可・実装済】

> **状態**: 入口①の第一陣 BE（順位連携リスナー）を本方針で実装済み。Phase 5 の full Fixture 改称（H.1）は**後続フェーズに延期**。

入口①（大会の対戦表からの記録合流）の**順位連携**は、以下の **中道** を採用する（H.1〜H.4 の full Fixture 改称＝物理改称・スコア正本移管は**後続フェーズ Phase 5 へ延期**）。

- **既存 `tournament_matches` を物理改称しない**（`tournament_fixtures` への rename・スコア列削除・スナップショット列追加は行わない）。スコア正本の移管（H.2.3）も後続。
- 入口① は match ドメインの `MatchCompletedEvent`（COMPLETED 遷移時に発火・受信ゼロだった）を、**tournament 側に新設したリスナー `MatchScoreFixtureListener` が `@TransactionalEventListener(phase=AFTER_COMMIT)` で受信**する。
- リスナーは fixtureId（= 既存 `tournament_matches.id`・BIGINT）で fixture を引当て、**既存 `tournament.service.MatchService.updateScore` を再利用**してスコアを反映する。これにより:
  - `updateScore` 内で `determineResult` / `winnerParticipantId` 確定 / `match.updateScore()`（status=COMPLETED 自動化）/ 既存 `StandingsRecalculationEvent` 発火 がそのまま走る。
  - **既存 `StandingsCalculationService` の `@Async @EventListener` 順位再計算（冪等・全消し再計算）は切り替えない**（二重発火・既存テスト破壊のリスク回避）。新リスナーは「`MatchCompletedEvent`(AFTER_COMMIT) を受けて既存 `updateScore`＋既存 StandingsRecalc 経路を起動するだけ」の薄い橋渡しに徹する。
- **participant ⇔ side は home participant = HOME 固定**（H.1.2）。リスナーはイベントの `homeScore` を fixture の HOME 側（`home_participant_id`）へ、`awayScore` を AWAY 側へ入替えずに渡す。
- **延長 PK 値整合**: イベントの `homeScore`/`awayScore` は**本戦合算済み**（延長得点は本戦に合算・sports/01 §4.1）であり、リスナーは延長別スコア（`homeExtraScore`/`awayExtraScore`）を**使わない（null を渡す）**。PK 戦は `homePenaltyScore`/`awayPenaltyScore` を**分離値のまま**渡す（合算 home_score ＋ PK 分離）。
- **AFTER_COMMIT**: match 側トランザクションがコミット済みのスコアに対してのみ反映する（未コミットのスコアで順位を誤更新しない）。リスナーは新規 `@Transactional` を張る（AFTER_COMMIT は元トランザクション外で走るため）。発火元 match ドメインの `@Transactional` を跨がない（原則 5・H.5）。
- **冪等**: COMPLETED 後の訂正による再発火でも `updateScore` は全列上書き（加算ではなく置換）＝冪等。
- **fixture 引当不能の許容**: fixtureId で引けない / tournamentId 解決不能の場合は**例外を投げず警告ログのみ**でスキップ（match 側は既コミット・tournament を越境で壊さない・H.2 (b)）。
- **div/tournament の順引き**: 既存 `updateScore` が要求する `tournamentId`/`divisionId` 発火経路に合わせるため、fixture の `matchday → division` を ID 順引きして `tournamentId` を得る。

なぜ中道か（根治の範囲を限定）: H.1 の full Fixture 改称は既存 F08.7（tournament）全体への最も侵襲的な作り替え（Entity/Controller/Service/enum/DTO 改称・スコア列移管・既存テスト大量追従）であり、入口①の順位連携という単一の価値提供には不要。**疎結合イベント駆動（`MatchCompletedEvent` 購読）だけで順位連携を成立させ**、改称は Phase 5 として独立に判断する。下記 **H.1〜H.4 は full Fixture 改称（Phase 5）前提の記述**であり、※ 印で「中道採用・改称は後続」を注記する。

---

## H.1 中核思想 — `tournament_matches` を fixture へ縮退（BIGINT 据え置き）

> ※ **中道採用・改称は後続（H.0）**: 以下 H.1〜H.4 の物理改称（`tournament_matches`→`tournament_fixtures`）・スコア正本移管は **Phase 5（後続フェーズ）に延期**。入口①第一陣は H.0 の中道（既存 `tournament_matches`＋`updateScore` 再利用・リスナーで順位連携）で実装済み。

既存 `tournament_matches` は実体が「**対戦カード(fixture)**」である。スコア（`homeScore`/`awayScore`/`homeExtraScore`/`awayExtraScore`/`homePenaltyScore`/`awayPenaltyScore`/`winnerParticipantId`/`result`）を持つが、これは新 `matches` が正本化すべき情報である（二重持ち解消）。延長別スコア（`homeExtraScore`/`awayExtraScore`）は本戦スコアへ合算し延長別列は廃止する（H.1 移行表・01 §B.1）。

**作り替え方針**: `tournament_matches` を **`tournament_fixtures`** へ改称・縮退し、fixture は「matches を参照し、節(matchday)・部(division)・参加チーム(participant)・順位寄与のみを持つ」ものとする。

> **【重要・致命的指摘の根治】fixture は BIGINT 据え置き**: tournament ドメインは全テーブルが `BaseEntity`（BIGINT AUTO_INCREMENT）で構成されており、CLAUDE.md 原則 6 は「**既存テーブルの BIGINT ID は変更しない**」と定める。よって `tournament_matches`→`tournament_fixtures` の縮退でも **PK は BIGINT のまま**とし、tournament を UUID 全面移行しない。`matches.tournament_fixture_id` は **BIGINT NULL** で fixture を ID 参照する（01 §B.1）。

| 旧 `tournament_matches` の列 | 移行先 |
|------------------------------|--------|
| `matchdayId` / `homeParticipantId` / `awayParticipantId` / `matchNumber` / `leg` | **fixture が保持**（対戦カード構造） |
| `nextMatchId` / `nextMatchSlot` | **fixture が保持**（トーナメント進行） |
| `scheduledDatetime` / `venue` / `scheduleId` | **matches へ移管**（試合の実体情報・`matches.kickoff_at`/`venue`/`schedule_id`・01 §B.1） |
| `homeScore`/`awayScore`/`homePenaltyScore`/`awayPenaltyScore`/`winnerParticipantId`/`result` | **matches へ移管（スコア正本化・二重持ち解消）**。`matches.home_score`/`away_score`（本戦）・`home_penalty_score`/`away_penalty_score`（PK 戦）へ。順位は matches 由来で導出。ただし**順位計算の高速化のためスナップショットを fixture へコピー**（H.2.3） |
| `homeExtraScore`/`awayExtraScore`（延長別スコア） | **home/away_score へ合算（延長別列は廃止）**。新 `matches` は延長別カラムを持たず、延長得点は本戦スコアに合算する（01 §B.1 延長戦スコアの扱い・02 §E.2a）。**この合算ルールはサッカー固有 → [sports/01_soccer.md](./sports/01_soccer.md) §4.1 参照**（最終スコア「延長の末 3-2」は 3-2 が正） |
| `status` | matches の status を正とし、fixture は参照（01 §B.1.1 照合表・MatchStatus は POSTPONED 含む 5 値で一致） |
| `rosterDeadline` | F08.7.1/05 の roster 機能に紐づく。fixture 側に残す（roster は tournament スコープ） |

### H.1.1 fixture と match のリンク

- `matches.tournament_fixture_id`（**BIGINT NULL**）で fixture を ID 参照（FK なし・原則 1）。
- 大会の対戦カード作成時に fixture を作り、試合実施時（または同時）に対応する `matches` を `kind=TOURNAMENT/LEAGUE`・`tournament_fixture_id=fixture.id` で生成する。
- 単独試合（練習/親善）は `tournament_fixture_id=NULL`。

### H.1.2 participant ⇔ team_side マッピング【致命的指摘の根治】

fixture は「**参加チーム(participant)**」を `home_participant_id` / `away_participant_id`（BIGINT）で保持する。一方 match は HOME/AWAY の `team_side` でイベント/スコアを持つ。両者の対応を固定ルールで明文化する。

- fixture に **`home_participant_id` / `away_participant_id` を残す**。
- match 生成時、**「fixture の home participant = match の HOME team_side」を固定ルール**とする（生成時に決定し以後不変）。
- 集計（順位・得失点）時の **participant ⇔ team_side 変換ロジック**を順位導出シーケンス（H.2）に組み込む: `matches.home_score` → home_participant の得点、`away_score` → away_participant の得点。
- **同一 team が複数 participant になり得る**（同一チームが複数枠でエントリーする大会等）ため、**team_id 単独で participant を逆引きすることは禁止**。必ず `participant_id`（fixture が保持）経由で対応付ける。

---

## H.2 順位表・個人ランキングの導出元を matches へ移す

> ※ **中道採用・改称は後続（H.0）**: H.2.1〜H.2.3 のスコア正本移管・fixture スナップショット・既存 `@Async @EventListener` の `MatchCompletedEvent` 受信への切替は **Phase 5 に延期**。入口①第一陣では既存 `StandingsCalculationService`（`@Async @EventListener`・`tournament_matches` 由来の全消し再計算）を**切り替えず**、新リスナー `MatchScoreFixtureListener` が `MatchCompletedEvent`(AFTER_COMMIT) を受けて既存 `updateScore` → 既存 `StandingsRecalculationEvent` 発火に乗せるのみ（H.0）。下記の (a) AFTER_COMMIT・(b) リスナー例外許容・(d) 冪等は中道でも遵守。

### H.2.1 StandingsCalculationService

- 現状: `StandingsRecalculationEvent` を受けて `tournament_matches` のスコア（`homeScore`/`awayScore`/`result`）から勝点・順位を計算（`@Async @EventListener`）。
- 作り替え（※ Phase 5）: **スコアの源泉を `matches` へ変更**。トリガーは match の COMPLETED 遷移（`MatchCompletedEvent`・match ドメイン発火）を tournament ドメインが受信 → 当該 fixture の division の順位を再計算（原則 5: ドメインをまたぐ更新はイベント駆動で分離）。
- **入口①第一陣（中道・実装済）**: `StandingsCalculationService` は不変。`MatchScoreFixtureListener` が `MatchCompletedEvent`(AFTER_COMMIT) を受けて既存 `tournament.service.MatchService.updateScore(tournamentId, fixtureId, ...)` を呼ぶ → 既存 `StandingsRecalculationEvent`(divisionId, tournamentId) が発火し既存 `@Async` 再計算（冪等）が走る。

```
[match ドメイン]  match.status=COMPLETED  →  publish MatchCompletedEvent(matchId, fixtureId, homeScore, awayScore, homePenaltyScore, awayPenaltyScore, result, winnerParticipantId, status, ...)
        │（イベント越境・原則 5 で @Transactional は跨がない）
        ▼
[tournament ドメイン] @TransactionalEventListener(phase=AFTER_COMMIT) StandingsCalculationService
        fixture := findByMatch(fixtureId)
        // スナップショットを fixture へコピー（H.2.3）→ 以後はクロスドメイン JOIN 不要
        fixture.home_score/away_score/home_penalty_score/away_penalty_score/status/result/winner_participant_id := event のスナップショット
        participant 対応 := fixture.home_participant_id / away_participant_id（H.1.2）
        → 勝点・タイブレーク・順位を再計算（既存ロジック流用・fixture スナップショット参照）
```

#### イベント駆動の失敗リカバリ・冪等性（フェイルセーフ）【要改善の根治】

順位スナップショットは matches を正本とする**派生キャッシュ**であり、整合崩れが**順位表に直結**するため、失敗時のリカバリ経路を明示する。

- **(a) AFTER_COMMIT 発火**: `@TransactionalEventListener(phase=AFTER_COMMIT)` でリスナーを起動し、**match 側のトランザクションがコミット済みのデータに対してのみ**スナップショットコピー・順位再計算を実行する（未コミットのスコアで順位を誤更新しない）。発火元 match ドメインの `@Transactional` を跨がない（原則 5・H.5）。
- **(b) リスナー例外時の許容**: リスナー内で例外が出た場合は**ログに記録し（症状を隠さない）**、fixture スナップショット未更新を一時的に許容する。リスナー例外で match 側トランザクション（既コミット）を巻き戻さない（AFTER_COMMIT のため不可かつ不要）。
- **(c) 明示再同期（フェイルセーフ）**: スナップショット欠落・順位ズレが疑われる場合に、**順位再計算 API（既存 `recalculateStandings` 相当・手動キック）でクロスドメインに matches から fixture スナップショットを再取得・順位を明示再同期できる経路**を残す。これが派生キャッシュ整合崩れの最終的な回復手段（根治）。
- **(d) 冪等設計**: 同一 fixture への複数 `MatchCompletedEvent`（COMPLETED 後の訂正による再 COMPLETED）は、**常に最新値で上書き**する（加算ではなく置換）。スナップショットは最新の matches 値で冪等に再構築されるため、イベント重複配信・再発火でも順位が二重計上されない。

### H.2.2 RankingsCalculationService（個人ランキング）

- 現状: `tournament_match_player_stats`（EAV statKey）を集計して得点王等を算出。
- 作り替え: 基本スタッツ（得点・アシスト）は **`match_events`（GOAL/ASSIST・本戦のみ・PK 戦除外）から集計**する。大会固有の任意 statKey（独自項目）が必要な場合のみ tournament 側に残す（H.3・H.6）。
- 個人ランキングの「得点王」= 当該大会に紐づく matches（`tournament_fixture_id IN fixtures of tournament`）のイベント集計。

### H.2.3 順位導出の実体化ビュー化（スナップショットコピー）【致命的指摘の根治】

> 起草時の「fixture はスコアを一切持たない」は**撤回**する。クロスドメイン JOIN（原則 1 違反）と N+1 を避けるため、**fixture にスコアのスナップショットをイベント駆動でコピー**する（実体化ビュー）。

- `MatchCompletedEvent` 受信時、tournament ドメインが **fixture へ次の列のスナップショットをコピー**する: `status` / `home_score` / `away_score` / `home_penalty_score` / `away_penalty_score` / `result`（勝敗） / `winner_participant_id`。クロスドメイン JOIN（原則 1 違反）を完全に回避するため、**勝敗確定値（result / winner_participant_id）も fixture へコピー**し、順位計算が fixture 自ドメイン内で完結するようにする（「必要なら」の曖昧運用を排除し、上記列を必須コピー対象として確定）。
- これにより tournament は**自ドメイン内テーブル（fixture）だけで順位計算でき**、毎回 matches へクロスドメイン JOIN する必要がなくなる（原則 1・N+1 回避）。
- スナップショットは matches を正本とする**派生キャッシュ**であり、COMPLETED 後の訂正（再 COMPLETED）でも `MatchCompletedEvent` の再発火でコピーが更新される。
- 既存 `findByDivisionIdAndStatus` 等のクエリは、**fixture スナップショット（fixture.status / fixture.home_score）参照に書き換える**（matches を直接見ない）。

---

## H.3 既存 `tournament_match_rosters` / `tournament_match_player_stats` の統合

| 既存テーブル | 作り替え方針 |
|--------------|-------------|
| `tournament_match_rosters`（出場メンバー表・先発/背番号/ポジション/登録番号/ユニフォーム） | **roster は「試合前のエントリー表」**として tournament スコープに残す（F08.7.1/05 の機能）。一方で**実際の出場（appearances）は match ドメインの `player_appearances`** が正本。roster → appearances への初期取込（先発リストを STARTER イベントとして生成）を Service で橋渡しする（H.3.1） |
| `tournament_match_player_stats`（EAV statKey） | 基本スタッツ（出場・先発・得点・アシスト・カード）は `match_events`/`player_appearances` へ**統合（廃止）**。大会主催者が任意定義する独自 statKey（例: 独自 MVP ポイント）**だけ** tournament 側に `tournament_fixture_stat`（fixture×user×statKey・EAV）として残す（H.6） |

### H.3.1 roster → appearances 橋渡し（STARTER イベント経由に一本化）【要改善の根治】

- メンバー表（`tournament_match_rosters`・先発フラグ/背番号/ポジション）は試合**前**の予定。
- 試合開始時に roster の `is_starter=true` の選手を、**`player_appearances` を直接作らず `match_events` の STARTER イベントとして一括生成**する。
  - 理由: フル再計算の**単一ソースを `match_events` に統一**する（02 §E.2）。appearances を直接作る経路と events 由来の経路が併存すると再計算で不整合になる。**appearances は常に events から導出される**ようにする。
- **冪等化**: roster 取込は **fixture 単位の「1 回取込フラグ」**で冪等にする（二重取込で STARTER イベントが重複生成されない）。
- 以降の交代・得点はライブ記録（04 §G.2）で `match_events` に積む → appearances 自動再計算（02 §E）。

---

## H.4 既存コードの影響範囲（Match*→Fixture* 改称・名前衝突回避）

物理改称（H.1）を採るため、以下の作り替えが発生する。**tournament 側の `Match*` を `Fixture*` へ改称**し、新 match ドメインの同名クラスとの**名前衝突を回避**する。

### バックエンド

| 対象 | 影響 |
|------|------|
| `TournamentMatchEntity` → `TournamentFixtureEntity` | 改称＋スコア列削除（matches へ移管・H.2.3 のスナップショット列は残す）。`updateScore`/`status` の責務を match へ移す |
| `MatchController`（`com.mannschaft.app.tournament.controller`／org スコープ `/organizations/{orgId}/tournaments/{tId}`） | **名前衝突回避**: 新 match ドメインに同名 `MatchController` を作るため、tournament 側を **`FixtureController` へ改称**。スコア入力 API（PATCH score）は match ドメインへ移設。**既存は @PreAuthorize 無し・org 絞り込み無し（IDOR 温床）なので改称ついでに認可を付与**（03 §C.3.1） |
| `MatchService`（tournament） | **`FixtureService` へ改称**。スコア更新ロジックを match ドメインへ移設 |
| `MatchStatus`/`MatchResult`/`MatchSlot`（tournament enum） | status は match 側へ寄せる（POSTPONED 含む 5 値で一致・01 §B.1.1）。`MatchResult` → `FixtureResult`、`MatchSlot`（トーナメント進行）→ `FixtureSlot` へ改称し fixture 側に残す |
| `StandingsCalculationService` / `RankingsCalculationService` | スコア源泉を matches/fixture スナップショットへ（H.2）。`MatchCompletedEvent` 受信に切替 |
| `MatchSetRepository` / `TournamentMatchSetEntity`（セット制スコア） | セットスコアは MVP ではスカラ home/away_score＋PK score に縮退。バレー等のセット制は将来 `match_periods`/`match_sets`（match ドメイン子テーブル）で吸収する余地を残す（§未解決 3）。既存 `TournamentMatchSetEntity` は当面 fixture 側に残置 or `detail JSON` 退避を実装時に選定 |
| DTO（`MatchResponse`/`ScoreUpdateRequest`/`BatchScoreRequest` 等） | スコア系は match ドメイン DTO へ移管（`FixtureResponse` と名前衝突回避）。fixture DTO は対戦カード構造＋スナップショットのみ |
| `TournamentMapper` | fixture ↔ match のマッピング追加 |
| `matches.schedule_id` | fixture の `scheduleId`（F03.1 連携）を matches へ移管（H.1 表・01 §B.1） |

### フロントエンド

| 対象 | 影響 |
|------|------|
| tournament 配下の対戦カード・スコア入力ページ | スコア入力を match ドメイン API（ライブ記録/結果入力）へ向ける |
| `composables/tournament/useTournamentBracket.ts` | fixture（対戦構造）取得はそのまま。スコアは fixture スナップショット or matches から取得 |
| `components/widgets/WidgetTeamTournamentRecord.vue` | 大会成績の表示元を matches 由来（fixture スナップショット）へ |
| 新規 `useMatchApi`/`useMatchEventApi`/`useMatchAnalytics`（04 §G.14・`composables/match/`） | tournament スコア入力の置換先 |

### H.5 ドメイン越境の原則順守

- match → tournament は**イベント駆動**（`MatchCompletedEvent`）で疎結合（原則 5）。fixture へのスナップショットコピーは tournament 側のイベントリスナー内で完結（match ドメインの @Transactional を跨がない）。
- tournament → match はリポジトリ越境せず、match ドメインの Service メソッド呼び出し（fixtureId → match 取得）経由（ドメイン境界の原則）。
- 双方向 FK は張らない（`matches.tournament_fixture_id`（BIGINT）も `tournament_fixtures` 側の match 参照も ID のみ・原則 1）。

### H.6 大会固有 statKey の残置方針【殿裁可】

- 基本スタッツ（出場/先発/得点/アシスト/カード）は match（events/appearances）が正本。tournament には**持ち込まない**。
- 大会主催者が任意定義する独自 statKey（例: 独自 MVP ポイント・敢闘賞ポイント等）**のみ** tournament 側に `tournament_fixture_stat`（fixture×user×statKey・EAV）として残す。
- `tournament_match_player_stats` は基本スタッツ部分を廃止し、独自 statKey 部分のみ `tournament_fixture_stat` へ縮退する。

---

## 未解決事項（全項目解決済み／MVP外の先送り決定を含む）

1. **物理改称の可否** — 解決済み（殿裁可）: グリーンフィールドゆえ `tournament_matches` → `tournament_fixtures` の物理リネーム（縮退・スコア列削除＋スナップショット列追加）を採用。`Match*`→`Fixture*` 改称で名前衝突を回避（H.1・H.4）。
2. **大会固有の任意 statKey の残置** — 解決済み（殿裁可）: 基本スタッツは match へ統合。大会固有の独自 statKey のみ tournament 側 `tournament_fixture_stat`（EAV）に残す（H.6・01 §未解決 1）。
3. **セット制スコア（バレー等）の表現**: MVP はスカラ home/away_score＋PK score に縮退で確定。将来 `match_periods`/`match_sets`（match ドメイン子テーブル）で吸収する余地（多競技 01 §D.3 と整合・**多競技拡張時に判断する先送り決定＝ブロッカーではない**）。延長別スコアも同じ `match_periods` で将来吸収する（01 §B.1 延長戦スコアの扱い）。
4. **scheduleId（カレンダー連携）の移管** — 解決済み（殿裁可）: 試合実体は matches なので **`matches.schedule_id`（BIGINT NULL）へ移管**（H.1 表・01 §B.1）。
