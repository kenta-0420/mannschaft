# F08.8 / 06: 段階実装計画・部隊割り・テスト方針

> **ステータス**: 🟡 設計中
> **最終更新**: 2026-06-08
> **関連機能番号**: F08.8（試合記録・分析）
> **関連ドキュメント**:
> - [01_domain_and_ddl.md](./01_domain_and_ddl.md) 〜 [05_tournament_integration.md](./05_tournament_integration.md)
> - [TEST_CONVENTION.md](../../../TEST_CONVENTION.md) — テスト規約
> - 方針: BE/API はテスト先行（test-first）／FE・E2E は後（feedback_test_first_be_api）

本書は **I（段階実装計画）** を具体化する。

---

## I. 段階実装計画

### I.1 Phase 概要と依存順

| Phase | 内容 | 依存 |
|-------|------|------|
| **Phase 1** | match 基盤（Flyway 3 テーブル／Entity／Repository／enum／多競技カタログ） | なし |
| **Phase 2** | Service（出場時間自動算出／集計／権限・IDOR） | Phase 1 |
| **Phase 3** | FE 単独試合 CRUD ＋ ライブ記録 UI | Phase 2（API 確定後） |
| **Phase 4** | 個人/チーム分析チャート ＋ ダッシュボードウィジェット | Phase 2・3 |
| **Phase 5** | tournament 移行（fixture 化／順位表・個人ランキングの matches 由来導出） | Phase 1〜4＋御裁可（05 §未解決 1） |

- Phase 1→2 は BE が直列依存（Entity/Repo が無いと Service が書けない）。
- Phase 3/4 は API 契約（Phase 2 の Controller/DTO）が確定してから着手。FE は API モックでなく実 BE で実機 E2E まで踏む（feedback_e2e_real_full_crud）。
- Phase 5 は最も侵襲的（既存 F08.7 作り替え）。グリーンフィールドだが御裁可を経てから着手する。

### I.2 部隊割り（Phase ごとの足軽分担）

依存が密な BE は「コミットを先・長時間ビルドを後」「前陣ブランチに reset --hard する逐次チェーン」で進める（feedback_subagent_commit_before_long_build）。

#### Phase 1（match 基盤・test-first）

| 隊 | 担当 | 成果物 |
|----|------|--------|
| 1-A | Flyway／DDL | `V9.YYYYMMDDHHMMSS__create_matches.sql` ほか 3 ファイル（採番はマージ直前に origin/main 最大番号を再確認） |
| 1-B | Entity／enum | `MatchEntity`/`MatchEventEntity`/`PlayerAppearanceEntity`（UuidV7Entity 継承）・`MatchKind`/`MatchEventType` 等 enum・`SportEventCatalog` |
| 1-C | Repository | `MatchRepository`（AbstractTenantAwareRepository 継承）/`MatchEventRepository`/`PlayerAppearanceRepository` |
| 1-T | テスト | Flyway from-scratch 適用テスト（Docker・FK/CASCADE 成立確認）・Entity 永続化 IT |

#### Phase 2（Service・test-first）

| 隊 | 担当 | 成果物 |
|----|------|--------|
| 2-A | 出場時間算出 | `PlayingTimeCalculationService`（フル再計算 upsert・02 §E） |
| 2-B | 集計 | `MatchStatsAggregationService`（個人/チーム・02 §F）＋ Response DTO（@Builder） |
| 2-C | 権限/IDOR | `MatchAccessService`（03 §C）＋ `MatchEventService`/`MatchService` |
| 2-D | Controller/DTO | `MatchController`/`MatchEventController`/`MatchStatsController` ＋ Request DTO |
| 2-T | テスト | 出場時間算出 UT（02 §E.6 ケース表）・集計 UT・Controller 統合テスト・認可テスト |

#### Phase 3（FE 試合 CRUD＋ライブ記録）

| 隊 | 担当 | 成果物 |
|----|------|--------|
| 3-A | composable/型 | `useMatchApi`/`useMatchEventApi`・`types/match.ts`（any 禁止） |
| 3-B | 試合一覧/作成 | `pages/teams/[id]/matches/index.vue`・`new.vue` |
| 3-C | ライブ記録 | `pages/teams/[id]/matches/[matchId]/live.vue`（3 タップ UX・タイマー・WakeLock・undo） |
| 3-D | i18n | `match.json` 6 言語＋`nuxt.config.ts` files 登録 |
| 3-T | テスト | E2E（試合作成→ライブ記録→COMPLETED の一気通貫・実 BE） |

#### Phase 4（分析チャート＋ウィジェット）

| 隊 | 担当 | 成果物 |
|----|------|--------|
| 4-A | 共通チャート | `components/charts/BaseChart.vue`（chart.js register 追加・ClientOnly・destroy） |
| 4-B | 個人/チーム分析 | `pages/me/match-analytics.vue`・`teams/[id]/match-analytics.vue`・`members/[userId]/...`・`useMatchAnalytics` |
| 4-C | ウィジェット | `WidgetTeamMatchSummary.vue`＋F02.2.1 min_role 登録（CI 双方向検証） |
| 4-T | テスト | チャート描画スモーク・集計表示 E2E |

#### Phase 5（tournament 移行）

| 隊 | 担当 | 成果物 |
|----|------|--------|
| 5-A | fixture 化 DDL/Entity | `tournament_matches`→`tournament_fixtures`（スコア列削除）・改称（05 §H.4） |
| 5-B | 順位導出 | `StandingsCalculationService`/`RankingsCalculationService` を matches 由来・`MatchCompletedEvent` 受信へ |
| 5-C | Controller/Service 改称 | tournament `MatchController`→`FixtureController` 等・スコア API を match へ移設 |
| 5-D | FE 追従 | tournament スコア入力 → match ライブ記録/結果入力へ・ウィジェット表示元変更 |
| 5-T | テスト | 順位導出 IT（match スコア → 順位）・既存 tournament テスト全面追従・Flyway 往復 |

### I.3 テスト方針

| 種別 | 対象 | 規約 |
|------|------|------|
| **出場時間算出 UT** | `PlayingTimeCalculationService`（02 §E.6 の全ケース） | test-first。フル出場/途中交代/退場/異常データ/整合警告を網羅 |
| **集計 UT** | `MatchStatsAggregationService`（個人/チーム・90 分あたり・0 除算 NULL） | test-first。期間/kind/sport フィルタ・退会者 displayName |
| **Controller 統合テスト** | 各 Controller（@WebMvcTest or full context） | 認可（403/404）・IDOR チェーン・楽観ロック 409 |
| **認可テスト** | `MatchAccessService`（公式戦/共同記録・自チーム分のみ・相手分 403） | 03 §C の権限マトリクスを網羅 |
| **Flyway 適用テスト** | from-scratch（Docker・Testcontainers tmpfs） | FK/CASCADE 成立・採番衝突回避・BINARY(16) PK |
| **FE E2E** | 試合作成→ライブ記録→COMPLETED→分析表示の一気通貫 | 実 BE・認証付き CRUD（read-only/モック禁止・feedback_e2e_real_full_crud） |

- BE ドメイン UT ＋ API 契約テストは**実装より前に設計書から書く**（test-first）。FE/E2E は後（feedback_test_first_be_api）。
- Flyway 採番は**マージ時に origin/main 最大番号を再確認**しリネーム（並行 PR との衝突回避・feedback_migration_version_collision）。
- `@WebMvcTest` ＋ `@EnableMethodSecurity` の incompatible に注意（既存教訓）。認可テストは方式を確認のうえ選定。

### I.4 ドキュメント同期

実装時に以下を更新（CLAUDE.md ドキュメント更新ルール）。

- 本ディレクトリ各文書のステータス（🟡 設計中 → 🟢 設計完了 → ✅ 実装完了）。
- `README.md`（プロジェクトルート）・`docs/openapi.json`（API 追加後 `npm run generate:types` 再生成）。
- F08.7 / F08.7.1 設計書（fixture 化に伴う記述追従・Phase 5）。
- F02.2.1（ウィジェット min_role 追加・Phase 4）。

---

## 未解決事項

1. **Phase 5 着手条件**: 05 §未解決 1（物理改称の可否）の御裁可が前提。御裁可前は Phase 1〜4（単独試合の記録・分析）だけで MVP として成立する設計なので、Phase 5 を切り離して先行リリース可能か（大会連携なしの単独試合記録を先に出す）を判断する。
2. **test-first の適用度**: 出場時間算出・集計のような純ロジックは test-first が有効だが、tournament 作り替え（Phase 5）は既存テストの大量追従が伴う。Phase 5 のみ従来順（実装→テスト追従）に戻すか。
3. **MVP スコープ**: ライブ記録のオフライン対応（dexie・04 §未解決 2）・多競技（サッカー以外）・大会固有 statKey 残置（05 §未解決 2）は MVP 外として後段 Phase に回す前提でよいか。
