# F08.10: 多競技ライブ記録・分析（Match Record & Analytics — GoalNote 上位互換）

> **ステータス**: 🟢 設計完了（未解決ブロッカー ゼロ）
> **最終更新**: 2026-06-13（多競技 6 種＝SOCCER/FUTSAL/BASKETBALL/VOLLEYBALL/SHOGI/GO・3 状態モデル類型・球技セット制・盤上ターン制・団体戦・WebSocket ライブ観戦を確定。先送り事項を決着。**二重検分の指摘 12 件を反映＝ターン制勝敗格納を home/away_score に統一（result/winner_side 列なし）・盤上個人戦の本人記録権限・sport/state_model 事後変更不可・団体戦引分け畳み込み・ボード記録分担/進捗一覧・完全オフライン明示・バスケ §8.4 ファウルコード表示・バレー period 正本統一・ReferenceType.MATCH 実装済追従・SimpleBroker 後追い切断の限界明記・win_method VARCHAR(32)・観戦 read-only バッジ**）
> **モジュール種別**: 選択式モジュール #14（大会・リーグ管理）の中核拡張 ／ 全試合の記録核
> **関連機能番号**: F08.7（大会・リーグ管理）／ F08.7.1（トーナメント機能拡張）／ F07.2（パフォーマンス管理）／ F06.4 活動記録 ／ F02.2 ダッシュボード ／ F00（コンテンツ可視性・ロール基盤）／ F19.1（個人プロフィール公開）／ F02.2.1（ウィジェット min_role）

> **※ 機能番号について**: F08.8 は既存の「修繕計画（[F08.8_repair_longterm_dashboard.md](../F08.8_repair_longterm_dashboard.md)）」が使用済みのため衝突回避として **F08.10** を採番した。
> また **tournament ドメインに既存の `Match*` クラス**（`MatchController` / `MatchService` / `MatchStatus` / `MatchResult` / `MatchSlot`）があり名前が衝突する。本機能は新規 `com.mannschaft.app.match` ドメインで同名 enum/クラスを使うため、**tournament 側を `Fixture*` へ改称**して衝突を回避する（方針詳細は [05_tournament_integration.md](./05_tournament_integration.md) §H.4・§H.6 参照）。

---

## 1. 概要

**全種別の試合（練習試合・親善試合・大会・リーグ）を単一の真実として記録し、個人キャリア統計・チーム統計をチャートで可視化する**機能。
スマホ片手で会場の記録係が時系列イベント（得点・アシスト・交代・カード）を 3 タップで入力でき、保存と同時に各選手の出場時間・スタッツが自動算出され、個人/チームの分析画面に蓄積される。

サッカーを具体実装の起点とし、`sport` カラム＋競技別カタログ＋**3 つの状態モデル類型への抽象化**により、**球技（連続時間制・セット制）と盤上競技（ターン制）にまたがる多競技**を、コアを再実装せず競技固有の差分だけで拡張できる構造を採る。記録者の入力は **WebSocket ライブ観戦**で可視性を持つ観戦者へリアルタイム配信される（正本は HTTP）。

### 1.0 コア（競技非依存）＋ sports/（競技固有）の二層構造

本設計書は **「テーブル（器）は競技非依存で共通・カタログ（中身）だけ競技別」** という方針で二層に分かれる（競技ごとに「記録すべき内容」が異なるため）。

- **F08.10 コア（01〜07）** = **競技非依存の土台**（`matches`/`match_events`/`player_appearances`/`match_sets` の汎用カラム・汎用 enum・出場時間算出の枠組み・集計 API の枠組み・記録モード/権限/IDOR/F00 可視性/入力検証の枠組み・ライブ入力 UX の骨格・**WebSocket ライブ観戦**・tournament 統合・実装計画）＋**拡張点 `SportEventCatalog`／`StateModel` 類型／勝ち方カタログの定義**（01 §D.3・§D.6・§D.7）。
- **[sports/01_soccer.md](./sports/01_soccer.md)〜[sports/06_go.md](./sports/06_go.md)** = **各競技固有のカタログ**（event_type 具体値・period／状態進行・スコア/勝敗計算・規律コード/勝ち方・統計定義・ポジション語彙・競技固有 UX 細部・i18n namespace）。
- 新競技は **[sports/01_soccer.md](./sports/01_soccer.md) を雛形に複製・差分記述**して追加できる（§10 新競技の追加手順・01 §D.3）。コアのテーブル（器）は一切変更しない（器拡張＝`MatchEventType` enum への値追加のみ）。

### 1.0a 多競技対応（MVP 6 競技）と 3 状態モデル類型

**MVP 対象競技（マスター御裁可）**: SOCCER（実装済）＋ **FUTSAL / BASKETBALL / VOLLEYBALL / SHOGI（将棋）/ GO（囲碁）**。将来競技は `Sport` enum＋カタログ追加のみで足りる。

競技は試合進行のしかたで **3 つの状態モデル類型（`StateModel`・01 §D.6）** に抽象化し、コアの分岐（タイマー/出場時間/COMPLETED バリデーション/FE composable）を**類型単位**で行う。

| 状態モデル類型 | 対象競技 | 進行 | スコア表現 | FE composable（動的 import・04 §G.16） |
|----------------|----------|------|------------|------------------------------------------|
| **連続時間制（CONTINUOUS_TIME）** | SOCCER / FUTSAL（前後半）・BASKETBALL（4 クォーター＋OT） | タイマー＋ピリオド | スカラ `home/away_score`（＋PK） | `useMatchTimerSoccer` / `useMatchTimerBasketball` |
| **セット制（SET_BASED）** | VOLLEYBALL（best-of-5・ラリーポイント・デュース・25 点〔最終 15 点〕） | セット進行 | `match_sets`＋獲得セット数（01 §B.5） | `useMatchSetTracker` |
| **ターン制（TURN_BASED）** | SHOGI / GO（手数・ピリオド無） | 手番の応酬（総手数） | スコア無・勝敗＋勝ち方（01 §D.7） | `useMatchTurnTracker` |

- **盤上（将棋/囲碁）の記録粒度＝中間**: 勝敗＋勝ち方（投了/時間切れ/中押し/反則勝ち等・競技別カタログ）＋総手数（`total_moves`）＋任意の局面写真/コメント。**棋譜フル（KIF/SGF）エンジンは持たない**（過剰機能・記録が主目的）。局面写真は既存添付基盤（presign・SVG 除外・サイズ上限・IDOR 逆引き）を流用（01 §B.7）。
- **個人戦＋団体戦の両対応**: 1 局 = 1 match を基本。団体戦は `matches.parent_match_id`（自己参照・同一ドメイン FK＋CASCADE 可）＋`board_number` で表現し、親の勝敗は子ボードの勝ち星集計から導出（01 §B.6）。
- **共通シェル＋競技別モジュール**: FE は薄い共通シェル（`live.vue`）＋競技別 composable を**動的 import（lazy-load）**で遅延読込しバンドル肥大化を防ぐ（04 §G.16）。

### 1.0b WebSocket ライブ観戦（MVP・正本は HTTP）

記録者の入力（イベント・スコア）を、可視性を持つ観戦者へ STOMP トピック `/topic/matches/{matchId}/live` でリアルタイム配信する（[07_realtime_spectator.md](./07_realtime_spectator.md)）。

- **正本は依然 HTTP**: 記録者は HTTP で POST → BE が永続化（出場時間再計算・認可）→ **AFTER_COMMIT** で `SimpMessagingTemplate` 配信。観戦者は購読のみ（read-only・書き込み経路にしない）。
- **セキュリティ最重要**: 購読時に **F00 可視性（`MatchVisibilityResolver`・03 §C.8）を検証する STOMP 購読インターセプタ**を新設。可視性の無い者の購読は拒否。大会公式戦は F08.7 の可視性 6 レベルと整合。
- 再接続/初期スナップショットは HTTP で取得し topic 差分で追従（07 §J.4）。WebSocket が落ちても HTTP で閲覧継続（グレースフルデグレード）。

### 1.1 本機能が解決する課題

- 大会の対戦カード（`tournament_matches`）はスコアしか持たず、**時系列イベント・出場時間・個人スタッツの蓄積基盤が無い**。
- 練習試合・親善試合は記録する場所そのものが無く、個人のキャリア統計が「大会の試合」に限定される。
- 既存の `tournament_match_player_stats` は EAV（statKey 任意）で柔軟だが、**出場時間の自動算出も交代の時系列も持たない**。GoalNote のような「タイムライン入力 → 自動集計 → 分析」の体験が成立しない。

---

## 2. GoalNote との比較（何を上位互換とするか）

GoalNote（サッカー個人記録アプリ）は「個人が自分の出場・得点を手入力してキャリアを蓄積する」体験を提供する。本機能はそれを以下の点で上位互換とする。

| 観点 | GoalNote（一般的な個人記録アプリ） | F08.10（本機能） |
|------|-----------------------------------|------------------|
| 記録単位 | 個人が自分の試合を自己申告 | **1 試合 = 1 レコード**を両チーム・全選手で共有。二重入力なし |
| 記録者 | 本人のみ | **会場の記録係**（公式戦）／**両チーム共同記録**（練習・親善）の 2 モード |
| 出場時間 | 手入力 | 交代イベントから**自動算出**（in/out/computed_minutes・複数交代/再出場対応） |
| スコア整合 | スコアと得点者が別管理 | GOAL イベント集計とスコアキャッシュを**整合チェック**（不一致は警告） |
| 大会連携 | なし | 大会（fixture）に直結し、**順位表・個人ランキングが試合記録から導出** |
| 多競技 | サッカー専用 | `sport` ＋競技別カタログ＋3 状態モデル類型で **6 競技対応**（球技＋盤上・§1.0a） |
| ライブ観戦 | なし | **WebSocket リアルタイム配信**（可視性検証付き購読・正本は HTTP・§1.0b） |
| 分析 | 個人グラフ中心 | 個人キャリア＋**チーム統計**（勝敗・得失点・選手別ランキング）を両軸で提供 |
| 権限 | 個人所有 | 自チーム分の訂正権限・相手分は記録係へ依頼の**権限分界**（IDOR 対策込み） |
| 共有 | 個人内 | 1 試合として両チーム・各選手にスタッツ共有（**DB 所有はユーザー不可視**） |

**上位互換の核**は (a) 1 試合単一レコードの共同記録、(b) 出場時間の自動算出、(c) 大会との双方向連携、(d) 3 タップのライブ入力 UX、の 4 点である。

---

## 3. 機能ステータス表

| 機能 | 説明 | ステータス |
|------|------|-----------|
| 汎用試合 `matches` 基盤（競技非依存・器） | 全種別試合の単一レコード（DDL/Entity/Repo/汎用 enum・拡張点 `SportEventCatalog`） | ✅ 実装完了（Phase 1〜入口①） |
| 競技カタログ機構（多競技拡張） | `Sport`＋`SportEventCatalog`（案 A）で競技別カタログを差し込む拡張点（01 §D.3） | ✅ 実装完了（機構） |
| **状態モデル類型抽象化**（多競技の中核） | `StateModel`（連続時間制/セット制/ターン制）でコア分岐を類型単位化（01 §D.6） | 🟢 設計完了 |
| **サッカー競技カタログ**（最初の競技） | event_type/period/スコア計算/規律コード C/S/統計/ポジション/UX/i18n（sports/01_soccer.md） | ✅ 実装完了 |
| **フットサル競技カタログ** | 連続時間制・前後半 20 分・サッカー差分小（sports/02_futsal.md） | 🟢 設計完了 |
| **バスケットボール競技カタログ** | 連続時間制・4 クォーター＋OT・得点 2P/3P/FT・ファウル体系（sports/03_basketball.md） | 🟢 設計完了 |
| **バレーボール競技カタログ** | セット制・ラリーポイント・デュース・`match_sets` 子表（sports/04_volleyball.md） | 🟢 設計完了 |
| **将棋競技カタログ** | ターン制・勝ち方/総手数/局面写真・団体戦（sports/05_shogi.md） | 🟢 設計完了 |
| **囲碁競技カタログ** | ターン制・中押し/目数差勝ち・団体戦（sports/06_go.md） | 🟢 設計完了 |
| **セット制スコア `match_sets`（器）** | バレー等のセット内得点子表（先送り解決・実装へ昇格・01 §B.5） | 🟢 設計完了 |
| **団体戦（`parent_match_id`/`board_number`）** | 個人戦＋団体戦・親の勝敗は子ボード勝ち星集計（先送り解決・01 §B.6） | 🟢 設計完了 |
| **WebSocket ライブ観戦** | STOMP 配信（AFTER_COMMIT・正本 HTTP）＋購読認可（F00 可視性）＋観戦者ビュー（07） | 🟢 設計完了 |
| 時系列イベント `match_events`（器） | 得点・アシスト・交代・カード等のタイムライン（カードは標準理由コードを選択式で付与可・**サッカーの C1〜C8/S1〜S6/CS は sports/01_soccer.md §5**） | 🟢 設計完了 |
| 出場時間 `player_appearances` | 交代から自動算出する出場分（複数交代/再出場対応） | 🟢 設計完了 |
| 出場時間自動算出ロジック | イベント保存時のフル再計算 upsert | 🟢 設計完了 |
| 集計 API（個人/チーム） | キャリア統計・チーム統計・チャート用 DTO | 🟢 設計完了 |
| 記録モード・権限 | 公式戦/共同記録・自チーム訂正権限・IDOR・F00 可視性 | 🟢 設計完了 |
| FE 単独試合 CRUD | 一覧/作成画面・導線 | 🟢 設計完了 |
| FE ライブ記録 UI | タイムライン 3 タップ・タイマー状態機械・WakeLock・オフラインキュー | 🟢 設計完了 |
| FE 個人/チーム分析チャート | radar/line/doughnut/bar（chart.js 流用） | 🟢 設計完了 |
| ダッシュボードウィジェット | `WidgetTeamMatchSummary`（min_role 確定） | 🟢 設計完了 |
| tournament 統合（fixture 化） | スコア二重持ち解消・順位表導出・Match*→Fixture* 改称 | 🟢 設計完了 |

凡例: 🟡 設計中 ／ 🟢 設計完了 ／ ✅ 実装完了

---

## 4. 本ディレクトリ内インデックス

**コア（競技非依存・01〜07）**:

| ファイル | 内容 |
|----------|------|
| [README.md](./README.md) | 本書。概要・3 状態モデル類型・コア+sports 二層構造・GoalNote 比較・ステータス表・インデックス・横断未解決事項 |
| [01_domain_and_ddl.md](./01_domain_and_ddl.md) | ドメイン配置（A）／新規テーブル DDL（B・汎用の器・`match_sets`/団体戦/局面写真）／汎用 enum・**拡張点 `SportEventCatalog`・`StateModel` 類型・勝ち方カタログの定義**（D） |
| [02_playing_time_and_aggregation.md](./02_playing_time_and_aggregation.md) | 出場時間自動算出の枠組み（E）／集計 API の枠組み（F） |
| [03_permissions_and_recording_modes.md](./03_permissions_and_recording_modes.md) | 記録モード・編集権限・セキュリティ・IDOR・F00 可視性・入力検証・**団体戦親子 IDOR・局面写真添付・WebSocket 購読認可**（C） |
| [04_frontend_and_ux.md](./04_frontend_and_ux.md) | 画面・導線・ライブ入力 UX の骨格・チャート枠組み・**競技別 composable 動的 import・観戦者ビュー**・i18n 機構（G） |
| [05_tournament_integration.md](./05_tournament_integration.md) | tournament 統合・既存コード作り替え・Match*→Fixture* 改称（H） |
| [06_implementation_plan.md](./06_implementation_plan.md) | 段階実装計画・部隊割り・テスト方針・**多競技 Phase 6・WebSocket 観戦 Phase 7**（I） |
| [07_realtime_spectator.md](./07_realtime_spectator.md) | **WebSocket ライブ観戦**（J）: トピック設計・AFTER_COMMIT 配信フロー・購読認可インターセプタ（F00）・再接続/スナップショット・スケール・テスト |

**競技固有カタログ（sports/）**:

| ファイル | 内容 | 状態モデル類型 |
|----------|------|----------------|
| [sports/01_soccer.md](./sports/01_soccer.md) | **サッカー競技カタログ**（雛形）: event_type/period/スコア/規律 C・S/統計/ポジション/UX/i18n/**新競技追加手順（§10）** | 連続時間制 |
| [sports/02_futsal.md](./sports/02_futsal.md) | フットサル（前後半 20 分・サッカー差分小） | 連続時間制 |
| [sports/03_basketball.md](./sports/03_basketball.md) | バスケットボール（4 クォーター＋OT・2P/3P/FT・ファウル） | 連続時間制 |
| [sports/04_volleyball.md](./sports/04_volleyball.md) | バレーボール（セット制・デュース・`match_sets`） | セット制 |
| [sports/05_shogi.md](./sports/05_shogi.md) | 将棋（勝ち方/総手数/局面写真・団体戦） | ターン制 |
| [sports/06_go.md](./sports/06_go.md) | 囲碁（中押し/目数差勝ち・団体戦） | ターン制 |

---

## 5. アーキテクチャ要点（CLAUDE.md DB 設計原則準拠）

本機能は**グリーンフィールド**（未デプロイ・運用データ無し）であり、後方互換の足枷が無いため最も綺麗な形を採る。

- **新規ドメイン** `com.mannschaft.app.match` を全試合の記録核として新設（[01](./01_domain_and_ddl.md) §A）。
- 新規テーブル（`matches` / `match_events` / `player_appearances` / `match_sets`）は全て **UUIDv7 / BINARY(16)**（原則 6）。
- 他ドメイン（user / team / organization / tournament）への参照は**FK を張らず ID 参照のみ**（原則 1）。
- **団体戦の `matches.parent_match_id` は matches → matches の自己参照**（同一ドメイン）なので **FK＋CASCADE 可**（原則 2・[01](./01_domain_and_ddl.md) §B.6）。クロスドメイン FK ではない。
- `match_sets`（セット制）も match ドメイン内子表で親 matches へ CASCADE（原則 2・子は org_id/deleted_at を持たず親で分離・[01](./01_domain_and_ddl.md) §B.5）。
- **WebSocket 配信は HTTP 永続化の AFTER_COMMIT**（match ドメインの @Transactional を跨がない・原則 5・[07](./07_realtime_spectator.md) §J.2）。局面写真は既存添付基盤を流用し新規ストレージを作らない（[01](./01_domain_and_ddl.md) §B.7）。
- **tournament の fixture は BIGINT 据え置き**（原則 6「既存テーブルの BIGINT ID は変更しない」）。`matches.tournament_fixture_id` は **BIGINT NULL** で fixture を ID 参照する（[01](./01_domain_and_ddl.md) §B.1・[05](./05_tournament_integration.md) §H.1）。
- `match_events` / `player_appearances` の親 `matches` への参照は**同一 match ドメイン内**なので CASCADE 可（原則 2）。**子テーブルは organization_id / deleted_at を持たない**（テナント分離は親 matches で行い、子は match_id スコープでのみアクセスする二段アクセスを Service 基底で強制・[01](./01_domain_and_ddl.md) §A.4）。
- `matches.organization_id` を持ち、**`matches` のリポジトリのみ** `AbstractTenantAwareRepository` を継承（原則 7）。子テーブルのリポジトリはテナント絞り込みを持たない（親経由でのみアクセス）。
- `matches` は `deleted_at` で論理削除（原則 3）。退会ユーザーの統計は ID 保持・表示名のみ匿名化（原則 4）。
- `@Transactional` は match ドメイン内に閉じる。順位表導出は tournament ドメインがイベント受信で行う（原則 5・[05](./05_tournament_integration.md)）。

---

## 6. 関連設計書リンク

- [F08.7 大会・リーグ管理](../F08.7_tournament_league.md) — `tournament_matches` / `tournament_participants` / 順位表・個人ランキング（**作り替え対象**）
- [F08.7.1 トーナメント機能拡張](../F08.7.1_tournament_extensions/README.md) — 試合メンバー表（`tournament_match_rosters`）/ エントリーテンプレ
- [F08.7.1 / 05 試合メンバー表](../F08.7.1_tournament_extensions/05_match_roster.md) — roster の出場メンバー定義（appearances への統合検討元）
- [F19.1 個人プロフィール公開](../F19.1_public_pages_identity_disclosure.md) — 個人統計の公開可否の正本（本機能の他者統計閲覧が連動）
- [F02.2 マイダッシュボード](../F02.2_dashboard.md) — ウィジェット母体
- [docs/security/03_role_authority_model.md](../../security/03_role_authority_model.md) — ロール・権限モデル（AccessControlService / @accessGuard SpEL）
- [docs/features/F00_content_visibility_resolver.md](../F00_content_visibility_resolver.md) — コンテンツ可視性 Resolver 基盤（`MatchVisibilityResolver` の正準・WebSocket 購読認可も委譲）
- 既存 WebSocket 基盤: `com.mannschaft.app.config.WebSocketConfig`（SimpleBroker `/topic` `/queue`・`/ws` SockJS）／ `WebSocketAuthChannelInterceptor`（STOMP CONNECT 時 JWT 検証）— ライブ観戦（[07](./07_realtime_spectator.md)）の配信・購読認可の流用元
- [CLAUDE.md](../../../CLAUDE.md) — DB 設計原則（原則 1〜7）

---

## 7. 横断未解決事項（全項目解決済み／MVP外の先送り決定を含む）

各文書末尾の「未解決事項（全項目解決済み／MVP外の先送り決定を含む）」を集約する。検分 3 本（不備/保守性・セキュリティ・UX）の全指摘に殿が決定を下し、**ブロッカーとなる未解決はゼロ**である。下記 §7.1 は殿裁可で解決済み、§7.2 は「MVP 外・後段 Phase で決定する先送り事項（根拠付き）」であり、いずれも実装着手を妨げるものではない。各サブ文書からの「§未解決」参照は、当該文書末尾の「未解決事項（全項目解決済み／MVP外の先送り決定を含む）」節を指す。

### 7.1 解決済み（殿裁可）

- **既存 `tournament_match_player_stats`（EAV）の線引き** — 解決済み（殿裁可）: 基本スタッツ（出場/先発/得点/アシスト/カード）は match（events/appearances）へ統合。大会主催者が任意定義する独自 statKey（EAV）**のみ** tournament 側に残す（[01](./01_domain_and_ddl.md) §未解決・[05](./05_tournament_integration.md) §H.3・§H.6）。
- **多競技カタログの実装方式** — 解決済み（殿裁可）: **案 A（enum＋コード定数カタログ）で確定**。DB マスタ化は将来余地（マスタテーブル例外として自然キー可・[01](./01_domain_and_ddl.md) §D.3）。
- **tournament fixture の ID 型** — 解決済み（殿裁可）: tournament は全テーブル BIGINT（BaseEntity）のため**原則 6 に従い fixture も BIGINT 据え置き**。`tournament_matches`→`tournament_fixtures` の縮退でも PK は BIGINT のまま。`matches.tournament_fixture_id` は **BIGINT NULL**（[01](./01_domain_and_ddl.md) §B.1・[05](./05_tournament_integration.md) §H.1）。
- **fixture の物理改称（縮退）の可否** — 解決済み（殿裁可）: グリーンフィールドゆえ `tournament_matches`→`tournament_fixtures` への縮退（スコア列削除）を採用。Match*→Fixture* 改称で名前衝突を回避（[05](./05_tournament_integration.md) §H.1・§H.4）。
- **共同記録モードの編集競合** — 解決済み（殿裁可）: 楽観ロック粒度は**イベント行単位**を優先。スコアキャッシュは matches.version 非依存のアトミック増減 or 読取時 GOAL 集計導出。フル再計算は matches.version に触れず appearances のみ更新（[02](./02_playing_time_and_aggregation.md) §E.2・[03](./03_permissions_and_recording_modes.md) §未解決・[04](./04_frontend_and_ux.md) §G.2 409 UX）。
- **個人統計の他者閲覧プライバシー** — 解決済み（殿裁可）: 他者閲覧は **teamId 必須パスパラメータ + `AccessControlService.isAdminOrAbove(viewer, teamId, "TEAM")` + 対象 user の当該 team 所属** の二重検証。公開可否は **F19.1 プロフィール公開設定を正本**に連動。チーム横断集計は本人限定（[02](./02_playing_time_and_aggregation.md) §F.1・[03](./03_permissions_and_recording_modes.md) §C.4・[04](./04_frontend_and_ux.md) §G.9）。
- **@EnableMethodSecurity の有効状態** — 解決済み（事実訂正）: 「現状無効」は誤り。**Phase 3（#1266）で既に有効化済**。ただし per-scope ロールは JWT 非搭載のため `hasRole('ADMIN')` は使えず、**AccessControlService / @accessGuard SpEL** を使う（[03](./03_permissions_and_recording_modes.md) §C.3）。
- **F00 可視性の具体実装** — 解決済み（殿裁可・一部実装済）: `MatchVisibilityResolver implements ContentVisibilityResolver` を新設（本機能で実装）。`ReferenceType.MATCH`(idKind=UUID_V7) は **既に現行 main の `ReferenceType.java` に配線済**（`idKind()` の switch に `MATCH -> UUID_V7` あり）。独自 visibility 述語は書かず F00 正準経由（[03](./03_permissions_and_recording_modes.md) §C.3）。
- **ライブ入力のオフライン対応 Phase** — 解決済み（殿裁可）: 屋外会場前提のため **MVP でも最低限のローカルキュー＋再送（dexie 軽量版）・入力データ一時保持**を組み込む。フル同期は後段 Phase（[04](./04_frontend_and_ux.md) §G.11・[06](./06_implementation_plan.md) §I.1）。殿よりマスターへリスク提示済。
- **WidgetTeamMatchSummary の min_role** — 解決済み（殿裁可）: **MEMBER 以上（SUPPORTER 除外）**で確定し F02.2.1 min_role 正本に登録（[02](./02_playing_time_and_aggregation.md) §F.1・[04](./04_frontend_and_ux.md) §G.9・§G.1 画面一覧 min_role 欄・[06](./06_implementation_plan.md) §I.4）。
- **scheduleId（カレンダー連携）の移管** — 解決済み（殿裁可）: 試合実体は matches なので `matches.schedule_id`（BIGINT NULL）へ移管（既存 `TournamentMatchEntity.scheduleId` 由来・[01](./01_domain_and_ddl.md) §B.1・[05](./05_tournament_integration.md) §H.4）。
- **未登録選手（player_user_id=NULL）の同一性キー** — 解決済み（殿裁可）: `(jersey_number, player_name, team_side)` をアプリ層キーとしフル再計算 upsert の決定性を担保。**キャリア横断集計は登録ユーザーのみ**、NULL 選手はその試合内集計に限る（[01](./01_domain_and_ddl.md) §未解決・[02](./02_playing_time_and_aggregation.md) §未解決）。
- **多競技範囲（MVP）** — 解決済み（マスター御裁可・本設計）: SOCCER に加え **FUTSAL/BASKETBALL/VOLLEYBALL/SHOGI/GO を MVP に含める**。3 状態モデル類型（CONTINUOUS_TIME/SET_BASED/TURN_BASED・[01](./01_domain_and_ddl.md) §D.6）に抽象化し、新競技は `Sport` enum＋カタログ＋composable 追加のみで足りる拡張機構を確定（§1.0a・[sports/01_soccer.md](./sports/01_soccer.md) §10）。
- **セット制スコア（バレー等）の表現** — 解決済み（マスター御裁可・本設計）: VOLLEYBALL を MVP 競技化したため **`match_sets` 子表を確定版 DDL へ昇格**（[01](./01_domain_and_ddl.md) §B.5・[sports/04_volleyball.md](./sports/04_volleyball.md)）。`matches.home_score`/`away_score` に獲得セット数、セット内得点は `match_sets`。（旧 §7.2 先送りから昇格・ブロッカー無し＝バレーを MVP に入れる判断のみが前提だった）。
- **ターン制（盤上競技）の記録モデル** — 解決済み（マスター御裁可・本設計）: SHOGI/GO を**ターン制（TURN_BASED・[01](./01_domain_and_ddl.md) §D.6）**として確定。記録粒度＝中間（勝敗＋勝ち方＋総手数＋局面写真・**棋譜フルエンジンは持たない**）。`total_moves`/`win_method` 列・勝ち方カタログ（§D.7）・ターン制最小 UI（[04](./04_frontend_and_ux.md) §G.16a）を確定（[sports/05_shogi.md](./sports/05_shogi.md)・[sports/06_go.md](./sports/06_go.md)）。
- **団体戦対応** — 解決済み（マスター御裁可・本設計）: 個人戦＋団体戦の両対応。団体戦は `matches.parent_match_id`（自己参照・同一ドメイン FK＋CASCADE）＋`board_number` で表現し、親の勝敗は子ボードの勝ち星集計から導出（[01](./01_domain_and_ddl.md) §B.6）。親子ボードの IDOR・テナント検証は [03](./03_permissions_and_recording_modes.md) §C.4。
- **WebSocket ライブ観戦** — 解決済み（マスター御裁可・本設計）: STOMP トピック `/topic/matches/{id}/live` で記録者入力を **AFTER_COMMIT 配信**（正本は HTTP・観戦者は read-only）。**購読時に F00 可視性を検証する STOMP 購読インターセプタ**を新設し可視性なしは購読拒否（大会は F08.7 6 レベルと整合・[03](./03_permissions_and_recording_modes.md) §C.8・[07](./07_realtime_spectator.md)）。

#### 二重検分（不備/保守性・セキュリティ・UX）の追加指摘 12 件 — 全て解決済（殿裁可）

- **ターン制個人戦の勝敗格納** — 解決済み: 個人戦（TURN_BASED かつ `parent_match_id=NULL`）も `home_score`/`away_score` に勝敗を格納（勝ち=1-0/0-1・引分=0-0）。既存 `MatchStatsAggregationService.resolveResult()` を全競技で再利用。**`result`/`winner_side` 列は追加しない**。勝ち方は `win_method` が保持（[01](./01_domain_and_ddl.md) §B.1.2・§D.7・[sports/05_shogi.md](./sports/05_shogi.md) §2.1/§4.2・[sports/06_go.md](./sports/06_go.md) §4.2）。
- **盤上個人戦の記録権限** — 解決済み: TURN_BASED 個人戦は**対局者本人**が自分の対局結果を記録・訂正可（team ADMIN/DEPUTY・記録係に加え本人を許可）。`canRecordTimeline` に類型分岐を追加・team 中心権限表と接続（[03](./03_permissions_and_recording_modes.md) §C.2a）。
- **sport/state_model の事後変更可否** — 解決済み: `match_events` 0 件時のみ変更可、記録開始後は変更不可（誤選択は削除→再作成）。記録後の変更要求は 409（[03](./03_permissions_and_recording_modes.md) §C.2b）。
- **団体戦の引分け畳み込み** — 解決済み: 子ボード DRAW（千日手/持将棋/持碁）は大会レギュレーション準拠（既定=両者 0.5 勝ずつ・整数スケール格納）。親の勝ち星同数→親 DRAW（[01](./01_domain_and_ddl.md) §B.6・[sports/05_shogi.md](./sports/05_shogi.md) §4.3）。
- **団体戦のボード記録分担・進捗一覧** — 解決済み: ボード進捗一覧（n/N 確定状況）＋記録担当（親作成者 / 各ボード担当 team ADMIN / 個人戦ボードは対局者本人）の導線（[04](./04_frontend_and_ux.md) §G.16a）。
- **完全オフライン観戦の明示** — 解決済み: HTTP 不通時は最後のスナップショットを表示し「オフライン・最新でない可能性」を明示（[04](./04_frontend_and_ux.md) §G.17）。
- **バスケのタイムライン ファウル理由コード表示** — 解決済み: color 非依存・形状/アイコン併用でファウルコード（PF/SF/OF/TF/UF/DF）を選手とともに表示（コア §G.12 準拠・[sports/03_basketball.md](./sports/03_basketball.md) §8.4）。
- **バレーの period 正本** — 解決済み: `match_sets.set_number` を正本・`period` には SET_1..5 を補助格納（表示用）に統一（[sports/04_volleyball.md](./sports/04_volleyball.md) §3）。
- **`ReferenceType.MATCH`** — 解決済み（実装済追従）: `ReferenceType.java` に `MATCH=UUID_V7` が**配線済**（[03](./03_permissions_and_recording_modes.md) §C.3.2）。
- **SimpleBroker の後追い切断ギャップ** — 解決済み（限界明記）: 購読後の可視性降格は次回再接続まで差分配信が届きうるが、ペイロード最小化（機微情報を元々含まない）の二重防御で実害を限定（[07](./07_realtime_spectator.md) §J.3.3）。
- **`win_method` 列長** — 解決済み: VARCHAR(24)→**VARCHAR(32)**（将来 competition の長い enum 名の余地・[01](./01_domain_and_ddl.md) §B.1）。
- **観戦者の read-only バッジ** — 解決済み: 「観戦のみ・記録権限なし」バッジ表示（[04](./04_frontend_and_ux.md) §G.17）。

### 7.2 先送り決定（MVP 外・後段 Phase で決定／ブロッカーではない）

> 以下は「未解決のブロッカー」ではなく、**MVP では既定値で確定済み・拡張可否や正本選定のみを後段 Phase で判断する先送り決定（根拠付き）**である。MVP 実装はこれらの既定値で成立する。各項目に「**なぜ今やらないか**」を明記する。

- **アディショナルタイム（stoppage）算入モード**: §E.4 で `minute` ベースを既定。チーム/大会単位で「stoppage 算入」を切替可能にするかは要件確定待ち。**MVP は `minute` ベース固定で確定**。**なぜ今やらないか**: 公式記録でもアディショナルの分計上は曖昧で二重計上を避けるため。算入要件が顕在化したら in/out を `minute+stoppage` で計算するモードを設定で切替（[02](./02_playing_time_and_aggregation.md) §E.4・§未解決 2）。
- **シーズン境界の定義**: `seasonTrend[]` のシーズン区切り（年度 4 月始まり / 暦年 / 大会シーズン）の正本（チーム設定 or 組織設定）。**MVP は暦年で暫定確定**。**なぜ今やらないか**: 暦年なら追加設定なしで成立し、後でチーム/組織設定を正本化しても集計関数の境界定義差し替えで吸収可能（侵襲が小さい・[02](./02_playing_time_and_aggregation.md) §未解決 5）。
- **大量試合一括取込時のバルク再計算**: CSV 一括取込（旧 GoalNote データ移行等）時のバルク再計算 API の要否。**MVP は 1 試合単位フル再計算で確定**。**なぜ今やらないか**: 1 試合のイベント数は高々数十〜百件でフル再計算が十分高速。一括取込は旧データ移行が要件化したときの後段課題（[02](./02_playing_time_and_aggregation.md) §E.2・§未解決 3）。
- **大会連携（tournament 作り替え・Phase 5）**: fixture 化・`Match*→Fixture*` 物理改称・順位導出スナップショット。**MVP では入口①の中道（既存 tournament 非破壊・イベント駆動順位連携）まで実装済**で、full 改称は **Phase 5 として分離**。**なぜ今やらないか**: full 改称は既存 F08.7 全体への最も侵襲的な作り替え（Entity/Controller/Service 改称・大量テスト追従）で、単独試合記録＋多競技＋観戦の MVP には不要。御裁可を経て分離着手（[05](./05_tournament_integration.md) §H.0・[06](./06_implementation_plan.md) §I.1）。
- **WS マルチインスタンスブローカー**: SimpleBroker（インメモリ）はインスタンスローカル。本番スケール時の Valkey/外部ブローカー切替。**MVP は既存 SimpleBroker で確定**。**なぜ今やらないか**: WS スケールは F08.10 固有でなく全 WS 機能（chat/lobby/通知）共通の基盤課題であり、F08.10 単独で先行決定すると基盤と乖離する。本機能は `SimpMessagingTemplate` 抽象に閉じておりブローカー差し替えに非依存（WS 基盤別軍議で判断・[07](./07_realtime_spectator.md) §J.5）。
- **3 類型外の競技（採点競技等）**: 体操/フィギュア等の採点競技は 3 状態モデル類型に当てはまらない。**MVP の 6 競技は 3 類型で網羅**。**なぜ今やらないか**: 要件が顕在化したら新類型（SCORED 等）を `StateModel` に追加して対応する余地を残す（コアの類型分岐に 1 類型追加・[01](./01_domain_and_ddl.md) §D.6）。

> **横断未解決のブロッカーは存在しない**: 上記はいずれも MVP 既定値で成立し、後段 Phase での拡張可否/正本選定のみが残る。実装着手を妨げる未解決はゼロ。
