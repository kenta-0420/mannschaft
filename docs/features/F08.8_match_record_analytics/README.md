# F08.8: 試合記録・分析（GoalNote 上位互換）

> **ステータス**: 🟡 設計中
> **最終更新**: 2026-06-08
> **モジュール種別**: 選択式モジュール #14（大会・リーグ管理）の中核拡張 ／ 全試合の記録核
> **関連機能番号**: F08.7（大会・リーグ管理）／ F08.7.1（トーナメント機能拡張）／ F07.2（パフォーマンス管理）／ F06.4 活動記録 ／ F02.2 ダッシュボード ／ F00（コンテンツ可視性・ロール基盤）

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

| 観点 | GoalNote（一般的な個人記録アプリ） | F08.8（本機能） |
|------|-----------------------------------|------------------|
| 記録単位 | 個人が自分の試合を自己申告 | **1 試合 = 1 レコード**を両チーム・全選手で共有。二重入力なし |
| 記録者 | 本人のみ | **会場の記録係**（公式戦）／**両チーム共同記録**（練習・親善）の 2 モード |
| 出場時間 | 手入力 | 交代イベントから**自動算出**（in/out/computed_minutes） |
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
| 汎用試合 `matches` 基盤 | 全種別試合の単一レコード（DDL/Entity/Repo/enum） | 🟡 設計中 |
| 時系列イベント `match_events` | 得点・アシスト・交代・カード等のタイムライン | 🟡 設計中 |
| 出場時間 `player_appearances` | 交代から自動算出する出場分 | 🟡 設計中 |
| 出場時間自動算出ロジック | イベント保存時のフル再計算 upsert | 🟡 設計中 |
| 集計 API（個人/チーム） | キャリア統計・チーム統計・チャート用 DTO | 🟡 設計中 |
| 記録モード・権限 | 公式戦/共同記録・自チーム訂正権限・IDOR | 🟡 設計中 |
| FE 単独試合 CRUD | 一覧/作成画面 | 🟡 設計中 |
| FE ライブ記録 UI | タイムライン 3 タップ・タイマー・WakeLock | 🟡 設計中 |
| FE 個人/チーム分析チャート | radar/line/doughnut/bar（chart.js 流用） | 🟡 設計中 |
| ダッシュボードウィジェット | `WidgetTeamMatchSummary` | 🟡 設計中 |
| tournament 統合（fixture 化） | スコア二重持ち解消・順位表導出 | 🟡 設計中 |

凡例: 🟡 設計中 ／ 🟢 設計完了 ／ ✅ 実装完了

---

## 4. 本ディレクトリ内インデックス

| ファイル | 内容 |
|----------|------|
| [README.md](./README.md) | 本書。概要・GoalNote 比較・ステータス表・インデックス |
| [01_domain_and_ddl.md](./01_domain_and_ddl.md) | ドメイン配置（A）／新規テーブル DDL（B）／enum・多競技対応（D） |
| [02_playing_time_and_aggregation.md](./02_playing_time_and_aggregation.md) | 出場時間自動算出ロジック（E）／集計 API（F） |
| [03_permissions_and_recording_modes.md](./03_permissions_and_recording_modes.md) | 記録モード（公式戦/共同記録）・編集権限・セキュリティ・IDOR（C） |
| [04_frontend_and_ux.md](./04_frontend_and_ux.md) | 画面・ライブ入力 UX・チャート・composable・i18n（G） |
| [05_tournament_integration.md](./05_tournament_integration.md) | tournament 統合・既存コード作り替え（H） |
| [06_implementation_plan.md](./06_implementation_plan.md) | 段階実装計画・部隊割り・テスト方針（I） |

---

## 5. アーキテクチャ要点（CLAUDE.md DB 設計原則準拠）

本機能は**グリーンフィールド**（未デプロイ・運用データ無し）であり、後方互換の足枷が無いため最も綺麗な形を採る。

- **新規ドメイン** `com.mannschaft.app.match` を全試合の記録核として新設（[01](./01_domain_and_ddl.md) §A）。
- 新規テーブル（`matches` / `match_events` / `player_appearances`）は全て **UUIDv7 / BINARY(16)**（原則 6）。
- 他ドメイン（user / team / organization / tournament）への参照は**FK を張らず ID 参照のみ**（原則 1）。
- `match_events` / `player_appearances` の親 `matches` への参照は**同一 match ドメイン内**なので CASCADE 可（原則 2）。
- `matches.organization_id` を持ち、リポジトリは `AbstractTenantAwareRepository` を継承（原則 7）。
- `matches` は `deleted_at` で論理削除（原則 3）。退会ユーザーの統計は ID 保持・表示名のみ匿名化（原則 4）。
- `@Transactional` は match ドメイン内に閉じる。順位表導出は tournament ドメインがイベント受信で行う（原則 5・[05](./05_tournament_integration.md)）。

---

## 6. 関連設計書リンク

- [F08.7 大会・リーグ管理](../F08.7_tournament_league.md) — `tournament_matches` / `tournament_participants` / 順位表・個人ランキング（**作り替え対象**）
- [F08.7.1 トーナメント機能拡張](../F08.7.1_tournament_extensions/README.md) — 試合メンバー表（`tournament_match_rosters`）/ エントリーテンプレ
- [F08.7.1 / 05 試合メンバー表](../F08.7.1_tournament_extensions/05_match_roster.md) — roster の出場メンバー定義（appearances への統合検討元）
- [F02.2 マイダッシュボード](../F02.2_dashboard.md) — ウィジェット母体
- [docs/security/03_role_authority_model.md](../../security/03_role_authority_model.md) — ロール・権限モデル
- [CLAUDE.md](../../../CLAUDE.md) — DB 設計原則（原則 1〜7）

---

## 7. 未解決事項（ディレクトリ横断・殿の精査用）

各文書末尾の「未解決事項」を集約する。

- 既存 `tournament_match_player_stats` の EAV 任意項目を match ドメインへ完全移管するか、大会固有項目だけ tournament 側へ残すかの最終線引き（[01](./01_domain_and_ddl.md) §未解決・[05](./05_tournament_integration.md) §未解決）。
- 多競技カタログを DB テーブル（マスタ）にするか enum＋コード定数にするかの確定（[01](./01_domain_and_ddl.md) §未解決）。
- グリーンフィールド前提で `tournament_matches` を物理的に fixture へ改称・縮退してよいか（既存 F08.7 実装の作り替え範囲・[05](./05_tournament_integration.md) §未解決）。
- 共同記録モードでの編集競合（両チームが同時にタイムライン入力）の解決方式（楽観ロック粒度・[03](./03_permissions_and_recording_modes.md) §未解決）。
