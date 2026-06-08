# F08.8 / 05: tournament 統合・既存コード作り替え

> **ステータス**: 🟡 設計中
> **最終更新**: 2026-06-08
> **関連機能番号**: F08.8（試合記録・分析）／ F08.7（大会・リーグ管理）／ F08.7.1
> **関連ドキュメント**:
> - [01_domain_and_ddl.md](./01_domain_and_ddl.md) — `matches.tournament_fixture_id` リンク
> - [02_playing_time_and_aggregation.md](./02_playing_time_and_aggregation.md) — スコア正本・イベント集計
> - [F08.7_tournament_league.md](../F08.7_tournament_league.md) — 既存 `tournament_matches` / 順位表 / 個人ランキング
> - 既存実装: `TournamentMatchEntity` / `MatchController`（org スコープ）/ `MatchService` / `StandingsCalculationService` / `RankingsCalculationService`

本書は **H（tournament 統合・既存コード作り替え）** を具体化する。**グリーンフィールド**（未デプロイ・運用データ無し）のため、後方互換を考えず最も綺麗な形へ作り替える。

---

## H.1 中核思想 — `tournament_matches` を fixture へ縮退

既存 `tournament_matches` は実体が「**対戦カード(fixture)**」である。スコア（`home_score`/`away_score`/`homeExtraScore`/`homePenaltyScore`/`winnerParticipantId`/`result`）を持つが、これは新 `matches` が正本化すべき情報である（二重持ち解消）。

**作り替え方針**: `tournament_matches` を **`tournament_fixtures`** へ改称・縮退し、fixture は「matches を参照し、節(matchday)・部(division)・参加チーム(participant)・順位寄与のみを持つ」ものとする。

| 旧 `tournament_matches` の列 | 移行先 |
|------------------------------|--------|
| `matchdayId` / `homeParticipantId` / `awayParticipantId` / `matchNumber` / `leg` | **fixture が保持**（対戦カード構造） |
| `nextMatchId` / `nextMatchSlot` | **fixture が保持**（トーナメント進行） |
| `scheduledDatetime` / `venue` / `scheduleId` | **matches へ移管**（試合の実体情報） |
| `homeScore`/`awayScore`/`homeExtraScore`/`awayExtraScore`/`homePenaltyScore`/`awayPenaltyScore`/`winnerParticipantId`/`result` | **matches へ移管（スコア正本化・二重持ち解消）**。順位は matches 由来で導出 |
| `status` | matches の status を正とし、fixture は参照（01 §D.1 ⚠️） |
| `rosterDeadline` | F08.7.1/05 の roster 機能に紐づく。fixture 側に残す（roster は tournament スコープ） |

### H.1.1 fixture と match のリンク

- `matches.tournament_fixture_id`（BINARY(16) NULL）で双方向リンク。
- 大会の対戦カード作成時に fixture を作り、試合実施時（または同時）に対応する `matches` を `kind=TOURNAMENT/LEAGUE`・`tournament_fixture_id=fixture.id` で生成する。
- 単独試合（練習/親善）は `tournament_fixture_id=NULL`。

> **改称 vs 新設の判断（グリーンフィールド前提）**: 運用データが無いため、`tournament_matches` を物理的に `tournament_fixtures` へリネーム（テーブル DROP＋CREATE で再構築）してよい。Flyway は新規 `V9.YYYYMMDDHHMMSS__rename_tournament_matches_to_fixtures.sql`（実態は drop/create で綺麗に作り直し）で対応する。既存 Entity/Service/Controller/FE の参照を全面書き換える（H.4）。**ただし F08.7 / F08.7.1 は「設計完了・一部実装完了」状態であり、作り替えの影響範囲が広い**。物理改称を行うか論理的に fixture 概念だけ被せるかは最終的に殿の御裁可を要する（§未解決 1）。

---

## H.2 順位表・個人ランキングの導出元を matches へ移す

### H.2.1 StandingsCalculationService

- 現状: `StandingsRecalculationEvent` を受けて `tournament_matches` のスコア（`homeScore`/`awayScore`/`result`）から勝点・順位を計算（`@Async @EventListener`）。
- 作り替え: **スコアの源泉を `matches` へ変更**。fixture（旧 tournament_matches）はスコアを持たないため、`fixture.id` → `matches.tournament_fixture_id` で対応 match を引き、`matches.home_score`/`away_score` を用いる。
- トリガー: match の COMPLETED 遷移（`MatchCompletedEvent`・match ドメイン発火）を tournament ドメインが受信 → 当該 fixture の division の順位を再計算（原則 5: ドメインをまたぐ更新はイベント駆動で分離）。

```
[match ドメイン]  match.status=COMPLETED  →  publish MatchCompletedEvent(matchId, fixtureId)
        │（イベント越境・原則 5 で @Transactional は跨がない）
        ▼
[tournament ドメイン] @EventListener StandingsCalculationService
        fixture := findByMatch(fixtureId)
        score := matches.home_score / away_score（正本）
        → 勝点・タイブレーク・順位を再計算（既存ロジック流用）
```

### H.2.2 RankingsCalculationService（個人ランキング）

- 現状: `tournament_match_player_stats`（EAV statKey）を集計して得点王等を算出。
- 作り替え: 基本スタッツ（得点・アシスト）は **`match_events`（GOAL/ASSIST）から集計**する。大会固有の任意 statKey（独自項目）が必要な場合のみ tournament 側に残す（H.3）。
- 個人ランキングの「得点王」= 当該大会に紐づく matches（`tournament_fixture_id IN fixtures of tournament`）のイベント集計。

---

## H.3 既存 `tournament_match_rosters` / `tournament_match_player_stats` の統合

| 既存テーブル | 作り替え方針 |
|--------------|-------------|
| `tournament_match_rosters`（出場メンバー表・先発/背番号/ポジション/登録番号/ユニフォーム） | **roster は「試合前のエントリー表」**として tournament スコープに残す（F08.7.1/05 の機能）。一方で**実際の出場（appearances）は match ドメインの `player_appearances`** が正本。roster → appearances への初期取込（先発リストを appearances の STARTER として生成）を Service で橋渡しする |
| `tournament_match_player_stats`（EAV statKey） | 基本スタッツ（出場・先発・得点・アシスト等）は `match_events`/`player_appearances` へ**統合（廃止）**。大会主催者が任意定義する独自 statKey（例: 独自 MVP ポイント）**だけ** tournament 側に `tournament_fixture_stat`（fixture×user×statKey）として残す余地を検討（§未解決 2） |

### H.3.1 roster → appearances 橋渡し

- メンバー表（`tournament_match_rosters`・先発フラグ/背番号/ポジション）は試合**前**の予定。
- 試合開始時に roster の `is_starter=true` の選手を `player_appearances` の STARTER（in=0）として生成する初期化処理を `MatchEventService` に持つ。
- 以降の交代・得点はライブ記録（04 §G.2）で `match_events` に積む → appearances 自動再計算（02 §E）。
- これにより「エントリー表（tournament）」と「実出場（match）」を分離しつつ連携する。

---

## H.4 既存コードの影響範囲

物理改称（H.1）または論理被せのいずれを採っても、以下の作り替えが発生する。

### バックエンド

| 対象 | 影響 |
|------|------|
| `TournamentMatchEntity` → `TournamentFixtureEntity` | 改称＋スコア列削除（matches へ移管）。`updateScore`/`status` の責務を match へ移す |
| `MatchController`（`com.mannschaft.app.tournament.controller`／org スコープ `/organizations/{orgId}/tournaments/{tId}`） | **名前衝突回避**: 新 match ドメインに同名 `MatchController` を作るため、tournament 側を `FixtureController` へ改称。スコア入力 API（PATCH score）は match ドメインへ移設 |
| `MatchService`（tournament） | `FixtureService` へ改称。スコア更新ロジックを match ドメインへ移設 |
| `StandingsCalculationService` / `RankingsCalculationService` | スコア源泉を matches へ（H.2）。`MatchCompletedEvent` 受信に切替 |
| `MatchSetRepository` / `TournamentMatchSetEntity`（セット制スコア） | バレー等のセットスコアは matches/match_events 側でどう表現するか要検討（§未解決 3） |
| DTO（`MatchResponse`/`ScoreUpdateRequest`/`BatchScoreRequest` 等） | スコア系は match ドメイン DTO へ移管。fixture DTO は対戦カード構造のみ |
| `TournamentMapper` | fixture ↔ match のマッピング追加 |
| 既存 `tournament.MatchStatus`/`MatchResult`/`MatchSlot` | status は match 側へ寄せる。MatchSlot（トーナメント進行）は fixture 側に残す |

### フロントエンド

| 対象 | 影響 |
|------|------|
| tournament 配下の対戦カード・スコア入力ページ | スコア入力を match ドメイン API（ライブ記録/結果入力）へ向ける |
| `composables/tournament/useTournamentBracket.ts` | fixture（対戦構造）取得はそのまま。スコアは matches から取得 |
| `components/widgets/WidgetTeamTournamentRecord.vue` | 大会成績の表示元を matches 由来へ（順位・得失点） |
| 新規 `useMatchApi`/`useMatchEventApi`/`useMatchAnalytics`（04 §G.4） | tournament スコア入力の置換先 |

### H.5 ドメイン越境の原則順守

- match → tournament は**イベント駆動**（`MatchCompletedEvent`）で疎結合（原則 5）。
- tournament → match はリポジトリ越境せず、match ドメインの Service メソッド呼び出し（fixtureId → match 取得）経由（ドメイン境界の原則）。
- 双方向 FK は張らない（`matches.tournament_fixture_id` も `tournament_fixtures` 側の match 参照も ID のみ・原則 1）。

---

## 未解決事項

1. **物理改称の可否**: グリーンフィールドゆえ `tournament_matches` → `tournament_fixtures` の物理リネーム（drop/create 再構築）が技術的に可能だが、F08.7 / F08.7.1 が「設計完了・一部実装完了」状態であり作り替えが広範。物理改称するか、`tournament_matches` を残しつつ matches を正本に被せる（fixture 概念を論理導入）かのいずれを採るか、殿＋マスターの御裁可が必要。
2. **大会固有の任意 statKey の残置**: `tournament_match_player_stats` を完全廃止して match へ統合するか、大会主催者の独自項目用に tournament 側へ `tournament_fixture_stat` を残すか（01 §未解決 1 と連動）。
3. **セット制スコア（バレー等）の表現**: 既存 `TournamentMatchSetEntity`（セットごとの得点）を matches/match_events でどう表すか。`detail JSON` でセットスコアを保持するか、`match_sets`（match ドメイン子テーブル）を新設するか。多競技対応（01 §D.3）と整合させて確定する。
4. **scheduleId（カレンダー連携）の移管**: fixture の `scheduleId`（F03.1 連携）を matches へ移すか fixture に残すか。試合実体は matches なので matches 推奨だが、既存 F03.1 連携の参照方向を確認のうえ確定。
