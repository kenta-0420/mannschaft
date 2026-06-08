# F08.10 / 06: 段階実装計画・部隊割り・テスト方針

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-08
> **関連機能番号**: F08.10（試合記録・分析）
> **関連ドキュメント**:
> - [01_domain_and_ddl.md](./01_domain_and_ddl.md) 〜 [05_tournament_integration.md](./05_tournament_integration.md)
> - [TEST_CONVENTION.md](../../../TEST_CONVENTION.md) — テスト規約
> - 方針: BE/API はテスト先行（test-first）／FE・E2E は後（feedback_test_first_be_api）

本書は **I（段階実装計画）** を具体化する。

---

## I. 段階実装計画

### I.1 Phase 概要と依存順（MVP 範囲の明記）

| Phase | 内容 | 依存 | MVP |
|-------|------|------|-----|
| **Phase 1** | match 基盤（Flyway 3 テーブル／Entity／Repository／enum／多競技カタログ案 A） | なし | ✅ MVP |
| **Phase 2** | Service（出場時間自動算出／集計／権限・IDOR・**F00 MatchVisibilityResolver / MatchAccessService**） | Phase 1 | ✅ MVP |
| **Phase 3** | FE 単独試合 CRUD ＋ ライブ記録 UI（導線・クイックスタート・タイマー状態機械・**オフライン最低限**） | Phase 2（API 確定後） | ✅ MVP |
| **Phase 4** | 個人/チーム分析チャート ＋ ダッシュボードウィジェット（min_role 登録） | Phase 2・3 | ✅ MVP |
| **Phase 5** | tournament 移行（fixture 化・BIGINT 据え置き／Match*→Fixture* 改称／順位導出スナップショット） | Phase 1〜4＋御裁可 | ⏸ MVP 外（分離リリース可） |

> **【MVP 範囲の明記・致命的指摘の根治】 Phase 1〜4 で MVP が成立する**（単独試合の記録＋個人/チーム分析）。大会連携（Phase 5）は MVP に含めず、**Phase 1〜4 を先行リリース可能**とする。Phase 5（tournament 作り替え）は最も侵襲的（既存 F08.7 改称）なので、御裁可を経てから分離して着手する。

- Phase 1→2 は BE が直列依存（Entity/Repo が無いと Service が書けない）。
- Phase 3/4 は API 契約（Phase 2 の Controller/DTO）が確定してから着手。FE は API モックでなく実 BE で実機 E2E まで踏む（feedback_e2e_real_full_crud）。
- **オフライン最低限（dexie 軽量版のローカルキュー＋再送・入力データ一時保持）は Phase 3 の MVP に含める**（04 §G.11・殿裁可）。フル同期は後段 Phase。
- Phase 5 はグリーンフィールドだが御裁可を経てから着手する。

### I.2 部隊割り（Phase ごとの足軽分担）

依存が密な BE は「コミットを先・長時間ビルドを後」「前陣ブランチに reset --hard する逐次チェーン」で進める（feedback_subagent_commit_before_long_build）。

#### Phase 1（match 基盤・test-first）

| 隊 | 担当 | 成果物 |
|----|------|--------|
| 1-A | Flyway／DDL | `V9.YYYYMMDDHHMMSS__create_matches.sql` ほか 3 ファイル（matches=UUIDv7・子=UUIDv7・`tournament_fixture_id`/`schedule_id` は BIGINT。採番はマージ直前に origin/main 最大番号を再確認） |
| 1-B | Entity／enum | `MatchEntity`/`MatchEventEntity`（**note/custom_label/linked_event_id/card_reason_code 列含む・自己参照 FK**）/`PlayerAppearanceEntity`（UuidV7Entity 継承・子は org_id/deleted_at 無し）・`MatchKind`/`MatchStatus`(POSTPONED 含む)/`MatchEventType`(PENALTY_SHOOTOUT・**OTHER** 含む) 等 enum・`SportEventCatalog`（案 A・OTHER 含む）・**理由コードカタログ `CautionCode`(C1〜C8)/`SendingOffCode`(S1〜S6, CS)（JFA 競技規則 標準・サッカー固有・Sport.SOCCER 紐づけ・01 §D.5。実装時に最新 JFA 公式競技規則と照合）** |
| 1-C | Repository | `MatchRepository`（AbstractTenantAwareRepository 継承）/`MatchEventRepository`/`PlayerAppearanceRepository`（後 2 者はテナント絞り込み無し・match_id スコープ専用・二段アクセス・01 §A.4） |
| 1-T | テスト | Flyway from-scratch 適用テスト（Docker・FK/CASCADE 成立・**子に org_id/deleted_at が無いこと**確認）・Entity 永続化 IT |

#### Phase 2（Service・test-first）

| 隊 | 担当 | 成果物 |
|----|------|--------|
| 2-A | 出場時間算出 | `PlayingTimeCalculationService`（フル再計算 upsert・**再出場/区間合計**・matches.version 非依存・破壊耐性 02 §E.5a） |
| 2-B | 集計 | `MatchStatsAggregationService`（個人/チーム・本戦/PK 分離・02 §F）＋ Response DTO（@Builder） |
| 2-C | 権限/IDOR/可視性 | `MatchAccessService`（03 §C・二段アクセス）＋ **`MatchVisibilityResolver`（F00・`ReferenceType.MATCH` 追加・`ContentVisibilityChecker` 登録）** ＋ `MatchEventService`/`MatchService` |
| 2-D | Controller/DTO | `MatchController`/`MatchEventController`/`MatchStatsController` ＋ Request DTO（jakarta.validation・owning/recorded_by はサーバー導出・**note(@Size 255)/custom_label(@Size 64) の最大長＋制御文字除去＋HTML 不可検証・linked_event_id の同一 match 帰属検証・card_reason_code のカタログ列挙値検証＋event_type 整合検証（警告→C 系/退場→S 系/CS・不整合は 400）**・03 §C.4a/C.4b） |
| 2-T | テスト | 出場時間算出 UT（02 §E.6 ケース表）・集計 UT・Controller 統合テスト・**認可/IDOR/テナント越境/F00 可視性テスト**・**card_reason_code 検証テスト（カタログ列挙値・event_type 整合＝警告→C 系/退場→S 系/CS・非対象 event_type への付与は 400・03 §C.4b）** |

#### Phase 3（FE 試合 CRUD＋ライブ記録）

| 隊 | 担当 | 成果物 |
|----|------|--------|
| 3-A | composable/型 | `composables/match/useMatchApi`/`useMatchEventApi`・`types/match.ts`（any 禁止・PENALTY_SHOOTOUT/POSTPONED 含む） |
| 3-B | 試合一覧/作成＋入口導線 | `pages/teams/[id]/matches/index.vue`（進行中バッジ・**「＋試合を記録」FAB＝入口 2**）・`new.vue`（クイックスタート・必須は kind＋相手）・**ダッシュボードのクイックアクション「試合を記録」（入口 3）**・**カレンダー（F03.1）予定からの「この試合を記録」起票（入口 4・`matches.schedule_id` 引き継ぎ＝日時/相手/会場を予定から事前充填）**（4 入口はすべて `live.vue` に合流・04 §G.1a-2） |
| 3-C | ライブ記録 | `live.vue`（3 タップ UX・タイマー状態機械・WakeLock・undo・**オフライン最低限（dexie 軽量）**・選手グリッド 3 段フォールバック・**イベント種別選択 UI（得点/アシスト/警告/交代＋その他）・その他自由入力（custom_label＋note）・理由メモ枠（note）・警告/退場の理由コード選択 UI（C1〜C8 / S1〜S6・CS の選択式＋短ラベル・補足 note 併記・タイムラインで「🟨 C2 ラフプレー（7番）」表示・04 §G.2c/G.2d）・GOAL⇔ASSIST 双方向連鎖（linked_event_id）＋連鎖の束ね表示（04 §G.2/G.2a/G.2b）**・**付加機能 (a) 前回先発コピー・(b) スコアボード常時表示（04 §G.15 (a)/(b)）**） |
| 3-D | i18n | `match.json` 6 言語（**`match.card_reason.C1`…`C8`/`S1`…`S6`/`CS` の短ラベル翻訳含む・04 §G.6**）＋`nuxt.config.ts` files 登録 |
| 3-T | テスト | E2E（試合作成→ライブ記録→COMPLETED の一気通貫・実 BE） |

#### Phase 4（分析チャート＋ウィジェット）

| 隊 | 担当 | 成果物 |
|----|------|--------|
| 4-A | 共通チャート | `components/charts/BaseChart.vue`（chart.js register 追加・ClientOnly・destroy・空状態・色覚配慮） |
| 4-B | 個人/チーム分析 | `pages/me/match-analytics.vue`（マイページタブ）・`teams/[id]/match-analytics.vue`・`members/[userId]/...`（teamId 認可・F19.1 連動）・`composables/match/useMatchAnalytics`・**付加機能 (c) 出場時間タイムバー可視化・(d) 個人「自己ベスト」ハイライト（04 §G.15 (c)/(d)）** |
| 4-C | ウィジェット | `WidgetTeamMatchSummary.vue`＋**F02.2.1 min_role=MEMBER 登録（CI 双方向検証）** |
| 4-T | テスト | チャート描画スモーク・集計表示 E2E |

#### Phase 5（tournament 移行・MVP 外）

| 隊 | 担当 | 成果物 |
|----|------|--------|
| 5-A | fixture 化 DDL/Entity | `tournament_matches`→`tournament_fixtures`（**BIGINT 据え置き**・スコア列削除＋スナップショット列追加・05 §H.1/H.4） |
| 5-B | 順位導出 | `StandingsCalculationService`/`RankingsCalculationService` を fixture スナップショット由来・`MatchCompletedEvent` 受信へ（participant⇔team_side 変換・05 §H.2） |
| 5-C | Controller/Service 改称 | tournament `MatchController`→`FixtureController`・`MatchService`→`FixtureService`・`MatchResult`/`MatchSlot`→`Fixture*` 等・スコア API を match へ移設・**認可付与** |
| 5-D | FE 追従 | tournament スコア入力 → match ライブ記録/結果入力へ・ウィジェット表示元変更 |
| 5-T | テスト | 順位導出 IT（match スコア → スナップショット → 順位）・既存 tournament テスト全面追従・Flyway 往復 |

### I.3 テスト方針

| 種別 | 対象 | 規約 |
|------|------|------|
| **出場時間算出 UT** | `PlayingTimeCalculationService`（02 §E.6 の全ケース） | test-first。フル出場/途中交代/**再交代・再出場**/**延長**/退場/**OWN_GOAL 符号反転**/**PK 戦**/**duration 未設定**/異常データ/整合警告を網羅 |
| **集計 UT** | `MatchStatsAggregationService`（個人/チーム・90 分あたり・0 除算 NULL・本戦/PK 分離） | test-first。期間/kind/sport フィルタ・退会者 displayName・未登録選手の試合内限定 |
| **Controller 統合テスト** | 各 Controller（@WebMvcTest or full context） | 認可（403/404）・**IDOR チェーン（子 ID 直引き禁止・親子不一致 404）**・**テナント分離**・**編集権限境界（自チーム分のみ）**・**共同記録競合（イベント行単位 409）** |
| **認可テスト** | `MatchAccessService`（公式戦/共同記録・自チーム分のみ・相手分 403）・`MatchVisibilityResolver`（F00 可視性） | 03 §C の権限マトリクスを網羅 |
| **Flyway 適用テスト** | from-scratch（Docker・Testcontainers tmpfs） | FK/CASCADE 成立・採番衝突回避・matches=BINARY(16) PK・fixture/schedule は BIGINT・子に org_id/deleted_at 無し |
| **FE E2E** | 試合作成→ライブ記録→COMPLETED→分析表示の一気通貫 | 実 BE・認証付き CRUD（read-only/モック禁止・feedback_e2e_real_full_crud） |

- BE ドメイン UT ＋ API 契約テストは**実装より前に設計書から書く**（test-first）。FE/E2E は後（feedback_test_first_be_api）。
- Flyway 採番は**マージ時に origin/main 最大番号を再確認**しリネーム（並行 PR との衝突回避・feedback_migration_version_collision）。
- `@WebMvcTest` ＋ `@EnableMethodSecurity`（**既に有効・03 §C.3.1**）の incompatible に注意（既存教訓）。認可テストは方式を確認のうえ選定（full context or @accessGuard モック）。

### I.4 ドキュメント同期

実装時に以下を更新（CLAUDE.md ドキュメント更新ルール）。

- 本ディレクトリ各文書のステータス（🟢 設計完了 → ✅ 実装完了）。
- `README.md`（プロジェクトルート）・`docs/openapi.json`（API 追加後 `npm run generate:types` 再生成）。
- F08.7 / F08.7.1 設計書（fixture 化・Match*→Fixture* 改称に伴う記述追従・Phase 5）。
- **F02.2.1（ウィジェット `WidgetTeamMatchSummary` min_role=MEMBER 追加・Phase 4・CI 双方向検証）**。
- **F19.1（個人統計の公開設定連動・参照リンク）**。

---

## 未解決事項（全項目解決済み／MVP外の先送り決定を含む）

1. **Phase 5 着手条件** — 解決済み（殿裁可）: 05 §H.1（物理改称・BIGINT 据え置き）の御裁可済。**Phase 1〜4（単独試合の記録・分析）だけで MVP として成立**し、Phase 5 を切り離して先行リリース可能（§I.1）。Phase 5 着手は別途タイミング判断。
2. **test-first の適用度** — 解決済み（殿裁可）: 出場時間算出・集計の純ロジックは test-first。tournament 作り替え（Phase 5）は既存テストの大量追従を伴うため Phase 5 のみ従来順（実装→テスト追従）に戻してよい。
3. **MVP スコープ** — 解決済み（殿裁可）: ライブ記録のオフラインは**最低限を MVP に含む**（04 §G.11）。多競技（サッカー以外・案 A 拡張）・大会固有 statKey 残置（05 §H.6）・フル同期・セット制スコアは MVP 外として後段 Phase。
