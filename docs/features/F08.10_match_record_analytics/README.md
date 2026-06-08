# F08.10: 試合記録・分析（Match Record & Analytics — GoalNote 上位互換）

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-08
> **モジュール種別**: 選択式モジュール #14（大会・リーグ管理）の中核拡張 ／ 全試合の記録核
> **関連機能番号**: F08.7（大会・リーグ管理）／ F08.7.1（トーナメント機能拡張）／ F07.2（パフォーマンス管理）／ F06.4 活動記録 ／ F02.2 ダッシュボード ／ F00（コンテンツ可視性・ロール基盤）／ F19.1（個人プロフィール公開）／ F02.2.1（ウィジェット min_role）

> **※ 機能番号について**: F08.8 は既存の「修繕計画（[F08.8_repair_longterm_dashboard.md](../F08.8_repair_longterm_dashboard.md)）」が使用済みのため衝突回避として **F08.10** を採番した。
> また **tournament ドメインに既存の `Match*` クラス**（`MatchController` / `MatchService` / `MatchStatus` / `MatchResult` / `MatchSlot`）があり名前が衝突する。本機能は新規 `com.mannschaft.app.match` ドメインで同名 enum/クラスを使うため、**tournament 側を `Fixture*` へ改称**して衝突を回避する（方針詳細は [05_tournament_integration.md](./05_tournament_integration.md) §H.4・§H.6 参照）。

---

## 1. 概要

**全種別の試合（練習試合・親善試合・大会・リーグ）を単一の真実として記録し、個人キャリア統計・チーム統計をチャートで可視化する**機能。
スマホ片手で会場の記録係が時系列イベント（得点・アシスト・交代・カード）を 3 タップで入力でき、保存と同時に各選手の出場時間・スタッツが自動算出され、個人/チームの分析画面に蓄積される。

サッカーを具体実装の起点とし、`sport` カラム＋競技別イベントカタログにより**多競技拡張**（将来バスケット等）に開かれた構造を採る。

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
| 多競技 | サッカー専用 | `sport` ＋競技別イベントカタログで**拡張可能** |
| 分析 | 個人グラフ中心 | 個人キャリア＋**チーム統計**（勝敗・得失点・選手別ランキング）を両軸で提供 |
| 権限 | 個人所有 | 自チーム分の訂正権限・相手分は記録係へ依頼の**権限分界**（IDOR 対策込み） |
| 共有 | 個人内 | 1 試合として両チーム・各選手にスタッツ共有（**DB 所有はユーザー不可視**） |

**上位互換の核**は (a) 1 試合単一レコードの共同記録、(b) 出場時間の自動算出、(c) 大会との双方向連携、(d) 3 タップのライブ入力 UX、の 4 点である。

---

## 3. 機能ステータス表

| 機能 | 説明 | ステータス |
|------|------|-----------|
| 汎用試合 `matches` 基盤 | 全種別試合の単一レコード（DDL/Entity/Repo/enum） | 🟢 設計完了 |
| 時系列イベント `match_events` | 得点・アシスト・交代・カード等のタイムライン | 🟢 設計完了 |
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

| ファイル | 内容 |
|----------|------|
| [README.md](./README.md) | 本書。概要・GoalNote 比較・ステータス表・インデックス・横断未解決事項 |
| [01_domain_and_ddl.md](./01_domain_and_ddl.md) | ドメイン配置（A）／新規テーブル DDL（B）／enum・多競技対応（D） |
| [02_playing_time_and_aggregation.md](./02_playing_time_and_aggregation.md) | 出場時間自動算出ロジック（E）／集計 API（F） |
| [03_permissions_and_recording_modes.md](./03_permissions_and_recording_modes.md) | 記録モード（公式戦/共同記録）・編集権限・セキュリティ・IDOR・F00 可視性（C） |
| [04_frontend_and_ux.md](./04_frontend_and_ux.md) | 画面・導線・ライブ入力 UX・チャート・composable・i18n（G） |
| [05_tournament_integration.md](./05_tournament_integration.md) | tournament 統合・既存コード作り替え・Match*→Fixture* 改称（H） |
| [06_implementation_plan.md](./06_implementation_plan.md) | 段階実装計画・部隊割り・テスト方針（I） |

---

## 5. アーキテクチャ要点（CLAUDE.md DB 設計原則準拠）

本機能は**グリーンフィールド**（未デプロイ・運用データ無し）であり、後方互換の足枷が無いため最も綺麗な形を採る。

- **新規ドメイン** `com.mannschaft.app.match` を全試合の記録核として新設（[01](./01_domain_and_ddl.md) §A）。
- 新規テーブル（`matches` / `match_events` / `player_appearances`）は全て **UUIDv7 / BINARY(16)**（原則 6）。
- 他ドメイン（user / team / organization / tournament）への参照は**FK を張らず ID 参照のみ**（原則 1）。
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
- [docs/features/F00_content_visibility_resolver.md](../F00_content_visibility_resolver.md) — コンテンツ可視性 Resolver 基盤（`MatchVisibilityResolver` の正準）
- [CLAUDE.md](../../../CLAUDE.md) — DB 設計原則（原則 1〜7）

---

## 7. 横断未解決事項（殿裁可の反映状況）

各文書末尾の「未解決事項」を集約する。検分 3 本（不備/保守性・セキュリティ・UX）の全指摘に殿が決定を下し、以下のとおり解決した。

### 7.1 解決済み（殿裁可）

- **既存 `tournament_match_player_stats`（EAV）の線引き** — 解決済み（殿裁可）: 基本スタッツ（出場/先発/得点/アシスト/カード）は match（events/appearances）へ統合。大会主催者が任意定義する独自 statKey（EAV）**のみ** tournament 側に残す（[01](./01_domain_and_ddl.md) §未解決・[05](./05_tournament_integration.md) §H.3・§H.6）。
- **多競技カタログの実装方式** — 解決済み（殿裁可）: **案 A（enum＋コード定数カタログ）で確定**。DB マスタ化は将来余地（マスタテーブル例外として自然キー可・[01](./01_domain_and_ddl.md) §D.3）。
- **tournament fixture の ID 型** — 解決済み（殿裁可）: tournament は全テーブル BIGINT（BaseEntity）のため**原則 6 に従い fixture も BIGINT 据え置き**。`tournament_matches`→`tournament_fixtures` の縮退でも PK は BIGINT のまま。`matches.tournament_fixture_id` は **BIGINT NULL**（[01](./01_domain_and_ddl.md) §B.1・[05](./05_tournament_integration.md) §H.1）。
- **fixture の物理改称（縮退）の可否** — 解決済み（殿裁可）: グリーンフィールドゆえ `tournament_matches`→`tournament_fixtures` への縮退（スコア列削除）を採用。Match*→Fixture* 改称で名前衝突を回避（[05](./05_tournament_integration.md) §H.1・§H.4）。
- **共同記録モードの編集競合** — 解決済み（殿裁可）: 楽観ロック粒度は**イベント行単位**を優先。スコアキャッシュは matches.version 非依存のアトミック増減 or 読取時 GOAL 集計導出。フル再計算は matches.version に触れず appearances のみ更新（[02](./02_playing_time_and_aggregation.md) §E.2・[03](./03_permissions_and_recording_modes.md) §未解決・[04](./04_frontend_and_ux.md) §G.2 409 UX）。
- **個人統計の他者閲覧プライバシー** — 解決済み（殿裁可）: 他者閲覧は **teamId 必須パスパラメータ + `AccessControlService.isAdminOrAbove(viewer, teamId, "TEAM")` + 対象 user の当該 team 所属** の二重検証。公開可否は **F19.1 プロフィール公開設定を正本**に連動。チーム横断集計は本人限定（[02](./02_playing_time_and_aggregation.md) §F.1・[03](./03_permissions_and_recording_modes.md) §C.4・[04](./04_frontend_and_ux.md) §G.9）。
- **@EnableMethodSecurity の有効状態** — 解決済み（事実訂正）: 「現状無効」は誤り。**Phase 3（#1266）で既に有効化済**。ただし per-scope ロールは JWT 非搭載のため `hasRole('ADMIN')` は使えず、**AccessControlService / @accessGuard SpEL** を使う（[03](./03_permissions_and_recording_modes.md) §C.3）。
- **F00 可視性の具体実装** — 解決済み（殿裁可）: `MatchVisibilityResolver implements ContentVisibilityResolver` を新設し `ReferenceType.MATCH`(idKind=UUID_V7) を追加。独自 visibility 述語は書かず F00 正準経由（[03](./03_permissions_and_recording_modes.md) §C.3）。
- **ライブ入力のオフライン対応 Phase** — 解決済み（殿裁可）: 屋外会場前提のため **MVP でも最低限のローカルキュー＋再送（dexie 軽量版）・入力データ一時保持**を組み込む。フル同期は後段 Phase（[04](./04_frontend_and_ux.md) §G.2・[06](./06_implementation_plan.md) §I.1）。殿よりマスターへリスク提示済。
- **WidgetTeamMatchSummary の min_role** — 解決済み（殿裁可）: **MEMBER 以上（SUPPORTER 除外）**で確定し F02.2.1 min_role 正本に登録（[02](./02_playing_time_and_aggregation.md) §F.1・[04](./04_frontend_and_ux.md) §未解決・[06](./06_implementation_plan.md) §I.4）。
- **scheduleId（カレンダー連携）の移管** — 解決済み（殿裁可）: 試合実体は matches なので `matches.schedule_id`（BIGINT NULL）へ移管（既存 `TournamentMatchEntity.scheduleId` 由来・[01](./01_domain_and_ddl.md) §B.1・[05](./05_tournament_integration.md) §H.4）。
- **未登録選手（player_user_id=NULL）の同一性キー** — 解決済み（殿裁可）: `(jersey_number, player_name, team_side)` をアプリ層キーとしフル再計算 upsert の決定性を担保。**キャリア横断集計は登録ユーザーのみ**、NULL 選手はその試合内集計に限る（[01](./01_domain_and_ddl.md) §未解決・[02](./02_playing_time_and_aggregation.md) §未解決）。

### 7.2 残る真の未解決（MVP 外・後段判断）

- **アディショナルタイム（stoppage）算入モード**: §E.4 で `minute` ベースを既定としたが、チーム/大会単位で「stoppage 算入」を切替可能にするかは要件確定待ち（MVP は `minute` ベース固定・[02](./02_playing_time_and_aggregation.md) §未解決）。
- **シーズン境界の定義**: `seasonTrend[]` のシーズン区切り（年度 4 月始まり / 暦年 / 大会シーズン）の正本（チーム設定 or 組織設定）。MVP は暦年で暫定（[02](./02_playing_time_and_aggregation.md) §未解決）。
- **セット制スコア（バレー等）の表現**: 多競技で将来 `match_periods` / `match_sets` 子テーブルで吸収する余地。MVP はスカラ home/away_score＋PK score（[05](./05_tournament_integration.md) §H.4・[01](./01_domain_and_ddl.md) §D.3）。
- **大量試合一括取込時のバルク再計算**: 旧データ移行（CSV 一括取込）時のバルク再計算 API の要否。MVP は 1 試合単位フル再計算（[02](./02_playing_time_and_aggregation.md) §未解決）。
