# F08.10 / 06: 段階実装計画・部隊割り・テスト方針

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-13（多競技 Phase 6・WebSocket 観戦 Phase 7 を MVP として追加）
> **関連機能番号**: F08.10（試合記録・分析）
> **関連ドキュメント**:
> - [01_domain_and_ddl.md](./01_domain_and_ddl.md) 〜 [05_tournament_integration.md](./05_tournament_integration.md)
> - [sports/01_soccer.md](./sports/01_soccer.md) — **最初の競技カタログ（サッカー）**。競技カタログ実装は 1 コンポーネントとして本計画に含む（Phase 1-B・§10 新競技追加手順）
> - [TEST_CONVENTION.md](../../../TEST_CONVENTION.md) — テスト規約
> - 方針: BE/API はテスト先行（test-first）／FE・E2E は後（feedback_test_first_be_api）

本書は **I（段階実装計画）** を具体化する。
**競技非依存のコア（テーブル・Service・FE 骨格）と、競技カタログ（最初の競技＝サッカー）を 1 コンポーネントとして区別**して実装する。サッカーカタログ（event_type/period/規律コード/統計/ポジション/UX/i18n の具体）は [sports/01_soccer.md](./sports/01_soccer.md) を正準とし、2 競技目以降は同文書 §10 の手順で雛形複製・差分追加する。

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
| **Phase 6（多競技）** | 状態モデル類型抽象化（`StateModel`・01 §D.6）＋競技別カタログ・composable（FUTSAL/BASKETBALL/VOLLEYBALL/SHOGI/GO）＋セット制 DDL（`match_sets`）＋ターン制・団体戦 | Phase 1〜4 | ✅ MVP（マスター御裁可で多競技を MVP 化・サブフェーズ 6-①〜④で段階） |
| **Phase 7（WebSocket 観戦）** | STOMP ライブ観戦（`/topic/matches/{id}/live`・AFTER_COMMIT 配信・購読認可インターセプタ・観戦者ビュー・07） | Phase 2（記録経路）・Phase 3 | ✅ MVP（マスター御裁可で観戦を MVP 化） |

> **【MVP 範囲の明記】 Phase 1〜4 で単独試合記録＋個人/チーム分析の MVP が成立**する。**マスター御裁可により多競技（Phase 6）と WebSocket 観戦（Phase 7）も MVP に含める**（サッカー以外の 5 競技＋ライブ観戦）。大会連携（Phase 5＝tournament 作り替え）は最も侵襲的（既存 F08.7 改称）なので御裁可を経て分離着手する MVP 外。多競技（Phase 6）は Phase 5 に依存しない（単独試合での多競技記録が先行可能）。

> **多競技（Phase 6）のサブフェーズ**:
> - **6-①（状態モデル抽象化＆Sport 拡張）**: `Sport` enum 拡張（6 競技）・`StateModel` 類型（01 §D.6）・`Sport→StateModel` マッピング・出場時間算出/COMPLETED バリデーションの類型分岐。`period` NULL 許容（ターン制）。
> - **6-②（競技別カタログ＆composable）**: 各競技の `SportEventCatalog` 集合・規律/勝ち方カタログ（catalog パッケージ）＋FE 競技別 composable（動的 import・04 §G.16）＋カタログ駆動イベント入力シート＋i18n namespace。FUTSAL/BASKETBALL（連続時間制）先行。
> - **6-③（セット制 DDL＆UI）**: `match_sets`（01 §B.5・Flyway 追加）＋`useMatchSetTracker`＋簡易/詳細記録 UI（VOLLEYBALL・[sports/04_volleyball.md](./sports/04_volleyball.md)）。
> - **6-④（ターン制＆団体戦）**: `total_moves`/`win_method`/`parent_match_id`/`board_number` 列追加（01 §B.1・Flyway 追加）＋`useMatchTurnTracker`＋局面写真添付（既存基盤流用）＋団体戦の親子ボード（SHOGI/GO・[sports/05_shogi.md](./sports/05_shogi.md)・[sports/06_go.md](./sports/06_go.md)）。

- Phase 1→2 は BE が直列依存（Entity/Repo が無いと Service が書けない）。
- Phase 3/4 は API 契約（Phase 2 の Controller/DTO）が確定してから着手。FE は API モックでなく実 BE で実機 E2E まで踏む（feedback_e2e_real_full_crud）。
- **オフライン最低限（dexie 軽量版のローカルキュー＋再送・入力データ一時保持）は Phase 3 の MVP に含める**（04 §G.11・殿裁可）。フル同期は後段 Phase。
- Phase 5 はグリーンフィールドだが御裁可を経てから着手する。

### I.2 部隊割り（Phase ごとの足軽分担）

依存が密な BE は「コミットを先・長時間ビルドを後」「前陣ブランチに reset --hard する逐次チェーン」で進める（feedback_subagent_commit_before_long_build）。

#### Phase 1（match 基盤・test-first）

| 隊 | 担当 | 成果物 |
|----|------|--------|
| 1-A | Flyway／DDL | `V76.001__create_matches.sql` ほか 3 ファイル（matches=UUIDv7・子=UUIDv7・`tournament_fixture_id`/`schedule_id` は BIGINT。**採番は origin/main の全体最大バージョンの次の major を採る**＝Phase 1 では V76 系を採用済。マージ直前に origin/main 最大番号を再確認しリネーム） |
| 1-B | Entity／enum（器＝コア） | `MatchEntity`/`MatchEventEntity`（**note/custom_label/linked_event_id/card_reason_code 列含む・自己参照 FK**）/`PlayerAppearanceEntity`（UuidV7Entity 継承・子は org_id/deleted_at 無し）・`MatchKind`/`MatchStatus`(POSTPONED 含む)/`MatchEventType`(全競技の値を保持する器・PENALTY_SHOOTOUT・**OTHER** 含む)/`PeriodType`/`Sport` 等 enum・**拡張点 `SportEventCatalog`（案 A の機構そのもの）** |
| 1-S | **競技カタログ（サッカー＝最初の競技）** | `Sport.SOCCER` のカタログ実体（[sports/01_soccer.md](./sports/01_soccer.md) 正準）: `SportEventCatalog.CATALOG` の SOCCER 集合（§2）・**理由コードカタログ `CautionCode`(C1〜C8)/`SendingOffCode`(S1〜S6, CS)（JFA 競技規則 標準・サッカー固有・Sport.SOCCER 紐づけ・実装時に最新 JFA 公式競技規則と照合）**・ポジション語彙（§7）。**この隊が「競技カタログ実装」コンポーネント**。2 競技目は §10 手順で複製 |
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

#### Phase 6（多競技・MVP）

| 隊 | 担当 | 成果物 |
|----|------|--------|
| 6-① | 状態モデル抽象化 | `Sport`（6 値）・`StateModel`（01 §D.6）・`Map<Sport,StateModel>`・`PeriodType` 拡張（QUARTER/SET）・出場時間算出/COMPLETED バリデーションの類型分岐（CONTINUOUS_TIME/SET_BASED/TURN_BASED）・`period` NULL 許容（ターン制・Flyway ALTER）。**Flyway 採番は全体最大 major の次（マージ直前再確認）** |
| 6-② | 競技カタログ＋FE composable | `catalog/`（FutsalCatalog/BasketballCatalog/VolleyballCatalog/ShogiCatalog/GoCatalog・規律 BasketballFoulCode・勝ち方 ShogiWinMethod/GoWinMethod）＋FE `useMatchTimerBasketball`/カタログ駆動入力シート（動的 import・04 §G.16）＋i18n（競技別 namespace・6 言語） |
| 6-③ | セット制 | `match_sets`（01 §B.5・Flyway CREATE）＋`MatchSetEntity`/Repository＋`useMatchSetTracker`＋簡易/詳細記録 UI（[sports/04_volleyball.md](./sports/04_volleyball.md)） |
| 6-④ | ターン制＋団体戦 | `matches` 列追加（total_moves/win_method/parent_match_id/board_number・Flyway ALTER）＋自己参照 FK＋`useMatchTurnTracker`＋局面写真添付（既存 presign 基盤流用・SVG 除外/サイズ上限/IDOR・03 §C.7a）＋団体戦の親子ボード勝ち星集計（[sports/05_shogi.md](./sports/05_shogi.md) §4.3） |
| 6-T | テスト | 競技別カタログ検証 UT（各競技の event_type 集合・他競技値の 400・規律/勝ち方カタログ）・セット制スコア/デュース UT・ターン制勝敗/団体戦勝ち星集計 UT・出場時間算出の類型分岐 UT（連続時間制のみ算出/ターン制スキップ）・Flyway from-scratch（追加列・match_sets・自己参照 FK CASCADE）・各競技の記録 E2E（実 BE） |

#### Phase 7（WebSocket ライブ観戦・MVP）

| 隊 | 担当 | 成果物 |
|----|------|--------|
| 7-A | 配信 | `MatchLiveUpdateEvent`＋`@TransactionalEventListener(AFTER_COMMIT)` 配信リスナー（`SimpMessagingTemplate.convertAndSend("/topic/matches/{id}/live", payload)`・07 §J.2）＋差分ペイロード DTO（serverSeq・機微情報除外・07 §J.2.1/J.3.3）。`MatchEventService` の記録経路に publish 1 行追加 |
| 7-B | 購読認可 | **STOMP SUBSCRIBE 認可インターセプタ**（match live 宛先のみ・`MatchAccessService.canView`→`MatchVisibilityResolver` 委譲・他テナント/可視性なしは購読拒否・未認証は PUBLIC のみ・07 §J.3・03 §C.8）。既存 `WebSocketAuthChannelInterceptor`（CONNECT）は不変 |
| 7-C | FE 観戦ビュー | 観戦者ビュー（read-only・STOMP 購読・初期スナップショット HTTP＋差分追従・再接続スナップショット再取得・接続状態インジケーター・04 §G.17）。`live.vue` の権限分岐（記録 UI / 観戦ビュー） |
| 7-T | テスト | 購読認可 UT（canView true/false・他テナント・未認証 PUBLIC）・配信リスナー UT（純 Mockito・convertAndSend 検証・機微情報除外）・実 WS E2E（記録→配信→観戦・可視性なし購読拒否・再接続スナップショット復帰・07 §J.6） |

#### 入口①（大会の対戦表からの記録合流）の段階出陣【中道・05 §H.0】

入口①は full Fixture 改称（Phase 5）に依存させず、**既存 tournament 非破壊の中道（05 §H.0）**で段階出陣する。

| 陣 | 範囲 | 成果物 | 状態 |
|----|------|--------|------|
| **第一陣（BE）** | by-fixture 解決＋順位連携リスナー | `MatchRepository.findFirstByOrganizationIdAndTeamIdAndTournamentFixtureIdOrderByKickoffAtDescIdDesc`／`MatchService.resolveByFixtureId`／`MatchRecordController` `GET .../matches/by-fixture/{fixtureId}`（入口④ by-schedule と完全対称）＋ **`tournament/listener/MatchScoreFixtureListener`**（`MatchCompletedEvent` を AFTER_COMMIT 受信 → fixture 引当 → 既存 `tournament.service.MatchService.updateScore` 再利用 → 既存 `StandingsRecalculationEvent` 発火に乗る。participant⇔side=HOME 固定／延長は本戦合算済みで extra=null／PK 分離／冪等／~~既存 `@Async` 順位計算は切替えない~~ **【第三陣で訂正＝AFTER_COMMIT へ切替・05 §H.0.1】**）。契約/Service/リスナー UT 付き | ✅ 実装済 |
| **第二陣（FE）** | 大会の対戦表ページ導線 | 対戦表ページ（節（matchday）/会場・日付グルーピング）の「記録」ボタン → by-fixture 解決 → 既存なら live 復帰・無ければ `tournament_fixtureId` 引き継ぎ作成 → `live.vue` 合流（04 §G.1a-2）。fixture の participant → team 解決、記録時 `tournament_fixtureId` 必須 | ✅ 実装済（#1439） |
| **第三陣（連携 E2E）** | 一気通貫 | 対戦表からカード押下 → ライブ記録 → COMPLETED → 順位表反映の実 BE E2E（feedback_e2e_real_full_crud）。`frontend/tests/e2e/real/f0810-entry1-fixture-record.spec.ts`（FIX-000〜FIX-010）。**実機 E2E で順位自動反映のレース条件を発見**（`StandingsCalculationService.onStandingsRecalculation` が `@Async @EventListener`＝発火元 REQUIRES_NEW TX のコミット前に未コミットスコアを読む → `played=0`・手動再計算でしか反映されない）→ **AFTER_COMMIT へ切替えて根治（05 §H.0.1）**。FIX-009 は手動 recalculate を除去し、最大 ~10 秒ポーリングで自動反映（`played=1`/勝点 3）をアサートするよう是正 | ✅ 根治・自動反映実証済 |

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
- Flyway 採番は**全体最大バージョンの次の major を採る**（major 数値比較でソートされるため。例: 全体最大 V75→V76 系。`V9.timestamp` 式は major=9 で V10〜V75 より前にソートされ from-scratch で死ぬ＝誤り・feedback_flyway_version_sort_after_global_max）。**マージ時に origin/main 最大番号を再確認**しリネーム（並行 PR との衝突回避・feedback_migration_version_collision）。Phase 1 は V76.001-003 を採用済。
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
3. **MVP スコープ** — 解決済み（マスター御裁可・本設計で更新）: ライブ記録のオフラインは**最低限を MVP に含む**（04 §G.11）。**多競技（FUTSAL/BASKETBALL/VOLLEYBALL/SHOGI/GO・Phase 6）・WebSocket ライブ観戦（Phase 7）も MVP に含める**（マスター御裁可）。セット制スコア（`match_sets`）・ターン制・団体戦は Phase 6 で MVP 化。**MVP 外として残るのは**: 大会固有 statKey 残置（05 §H.6・Phase 5 内）・オフラインフル同期・大会連携（Phase 5＝tournament 改称・最も侵襲的なので分離）・3 類型外の競技（採点競技等）・WS マルチインスタンスブローカー（07 §J.5・WS 基盤別軍議）。これらは**ブロッカーではない**（理由は各文書の §未解決に明記）。
