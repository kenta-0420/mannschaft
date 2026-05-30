# F08.7.1: トーナメント機能拡張（連絡・成績ウィジェット・リーグ昇降格移籍）

> **ステータス**: 🟢 設計完了
> **実装フェーズ**: Phase 8（F08.7 の後続拡張）
> **最終更新**: 2026-05-31
> **モジュール種別**: 選択式モジュール #14（大会・リーグ管理）の機能拡張
> **関連ドキュメント**:
> - [F08.7_tournament_league.md](../F08.7_tournament_league.md) — 大会・リーグ管理（**母体**。大会 CRUD / ディビジョン / 参加者 / 順位表 / 昇降格枠は実装済み）
> - [F05.1_bulletin_board.md](../F05.1_bulletin_board.md) — 掲示板（連絡スペースの掲示板実体・`scope_type` 拡張先）
> - [F04.2_chat.md](../F04.2_chat.md) — チャット（連絡スペースのチャット実体・`channel_type` / `source_type` 拡張先）
> - [F02.2_dashboard.md](../F02.2_dashboard.md) — マイダッシュボード（成績ウィジェットの母体・widget_key 一覧の正本）
> - [F02.2.1_dashboard_widget_role_visibility.md](../F02.2.1_dashboard_widget_role_visibility.md) — ウィジェット ロール別可視性（min_role 表の正本・CI 双方向検証）
> - [F22.1_swipe_scope_dashboard/04_widgets.md](../F22.1_swipe_scope_dashboard/04_widgets.md) — 横スワイプダッシュボード（名前空間混同防止のための相互参照）
> - [docs/security/03_role_authority_model.md](../../security/03_role_authority_model.md) — ロール・権限モデル（新スコープ認可方針・PUBLIC 露出方針）

---

## 1. 概要

F08.7 大会・リーグ管理は **バックエンドの大会 CRUD・ディビジョン・参加者・順位表・昇降格枠が🟢実装完了**しているが、(a) 参加チーム間の連絡手段、(b) ダッシュボードでの成績可視化、(c) 組織をまたぐリーグ間移籍（昇降格）の導線が未整備である。本書はこの 3 領域を**実装可能レベルまで具体化**する設計書である。

設計領域は 3 つに分かれ、それぞれ連番ファイルに詳述する:

1. **連絡機能**（[01_communication.md](./01_communication.md)）— 大会全体＋各ディビジョンの二段で掲示板・チャットの連絡スペースを自動付帯。read/write 分離認可、公開トグル、自動生成フック、削除・退会の取り扱い。
2. **成績ウィジェット**（[02_dashboard_widgets.md](./02_dashboard_widgets.md)）— 自チーム成績／主催大会サマリ／順位表の 3 ウィジェットを F02.2 系の詳細ダッシュボードに追加。既存可視性インフラ（`dashboard_widget_role_visibility` / `dashboard_widget_settings`）に enum 追加だけで乗る。
3. **リーグ・ピラミッド＋昇降格移籍**（[03_league_pyramid_and_transfer.md](./03_league_pyramid_and_transfer.md)）— 組織階層（`organizations.parent_organization_id`）からリーグ・ピラミッドを導出し、組織をまたぐ昇格（プル型招待）と降格（プッシュ型送り出し）を `league_transfer` テーブルで実現。通算成績・テンプレート・identity・順位履歴は team_id 串刺しで自動的に付いてくる。

### 1.1 背景と判明事実（偵察結果）

- 大分県サッカー協会の「1部〜4部」は **4 大会ではなく、1 大会＋4 ディビジョン**（`tournament_divisions.level`）で表現する。昇降格枠 `promotion_slots` / `relegation_slots` も実装済み。
- 大会作成導線は **組織サイドバー → トーナメント** のみ実装済み。チームは「参加する側」。チームの参加履歴ページ `/teams/{id}/tournaments` は順位・勝点・通算成績を**既に表示**している。
- 連絡機能（掲示板・チャット）は F08.7 §5.9 に**計画のみ存在し未実装**（bulletin `ScopeType` / chat `ChannelType` に該当値なし）。
- ダッシュボードに大会成績ウィジェットは**存在しない**。可視性インフラ（`dashboard_widget_role_visibility` ＝admin 設定 / `dashboard_widget_settings` ＝個人）は既存で、enum 追加だけで各団体の表示 ON/OFF 設定に自動で乗る。
- 通算成績（`tournament-stats`）・エントリーテンプレート（`tournament_entry_templates.team_id`）・チーム identity・順位履歴（`tournament-history`）は **すべて team_id スコープで蓄積**されており、組織をまたいでも team_id 串刺しで集計される＝**データ持ち運びの土台は既にある**。
- **組織階層は実装済み**：`organizations.parent_organization_id` ＋ `OrganizationHierarchyService`（祖先探索・サイクル検出・最大深度 5）。`org_type` に `ASSOCIATION`（協会・連盟）あり → 九州協会 ⊃ 大分県協会が即実現可能。
- **昇降格の枠・確定ロジックも実装済み**：`tournament_divisions.promotion_slots/relegation_slots`、`PromotionService.getPromotionPreview/executePromotions`、`tournament_promotion_records`（PROMOTION/RELEGATION/PLAYOFF_*）。ただし**同一大会内の部間移動のみ**。
- チーム→所属県協会は `team_org_memberships`（status=ACTIVE）で引ける（`findFirstByTeamIdAndStatus` / `findOrganizationIdByTeamIdIn`）。
- **新規実装が必要なのは「組織をまたぐ昇降格（県協会↔九州協会のリーグ間異動）」のみ**。

### 1.2 F08.7（母体）との関係

| 観点 | F08.7（母体） | 本書 F08.7.1（拡張） |
|------|--------------|---------------------|
| 大会 / ディビジョン / 参加者 / 順位表 | 🟢 実装済み（CRUD・順位計算） | 流用（変更なし） |
| 同一大会内の昇降格 | 🟢 `PromotionService`（部間移動） | 流用（再実装しない） |
| ディビジョン別ターゲティング（§5.9） | 計画のみ（掲示板/通知/DM の配信フィルタ） | §5.9 を本書 01 で「連絡スペースとして具体化」 |
| 連絡スペース（掲示板・チャット） | 未実装 | **新規**（01_communication.md） |
| 成績ウィジェット | 未実装 | **新規**（02_dashboard_widgets.md） |
| 組織をまたぐリーグ間移籍 | 未実装 | **新規**（03_league_pyramid_and_transfer.md） |

> F08.7 本体の肥大化を避けるため、本拡張は新規ディレクトリ方式（先例：F22.1）で `F08.7.1`（先例：F02.2.1 の親.子採番）とする。F08.7 §5.9 は本書 01 へのリンクに置き換える。

---

## 2. 確定要件 → 設計章番号 トレーサビリティ対応表

マスター裁可済みの要件①〜⑨が、本書のどの章で実装可能レベルに具体化されているかの対応表。

| # | 確定要件 | 反映先 |
|---|---------|--------|
| ① | 連絡単位＝**大会全体＋各ディビジョンの二段**（掲示板・チャット両方） | 01 §2（データモデル `tournament_contact_space`）/ §3（自動生成フック） |
| ② | 閲覧＝参加チーム全メンバー＋**公開設定 ON のスペースは PUBLIC も可**（各スペースに公開トグル） | 01 §4.1（`canView`）/ §5（公開トグル） |
| ③ | 投稿＝**主催組織 ADMIN ＋各チームの代表（ADMIN）・副代表（DEPUTY_ADMIN）** | 01 §4.2（`canPost`） |
| ④ | スペースは**大会／ディビジョン作成時に自動付帯** | 01 §3（自動生成フック・`createTournament`/`createDivision`/`continueTournament`） |
| ⑤ | 成績ウィジェット 3 種（チーム=自チーム成績／組織=主催大会サマリ／順位表）、表示 ON/OFF は各団体が設定 | 02 §2（ウィジェット定義表）/ §4（編集箇所）/ §6（可視性インフラ連携） |
| ⑥ | リーグ移籍の起点＝**上位リーグ主催者が検索して招待（プル型）** | 03 §4（昇格フロー・プル型）/ §6（API）/ §7（認可） |
| ⑦ | リーグ連結＝**リーグ・ピラミッドを組織階層から導出**（県→地域→全国を `parent_organization_id` で表現） | 03 §2（中核思想・組織階層からの導出） |
| ⑧ | 移籍で持ち運ぶデータ＝**通算成績／エントリーテンプレート／チーム identity／順位履歴（全部）** | 03 §3（データ持ち運びの土台）/ §8（集計クエリの org 非依存検証） |
| ⑨ | **降格も対称に考慮**：昇格＝プル／降格＝**プッシュ（上位リーグ主催者が送り出し＋出身県協会 org に通知）** | 03 §5（降格フロー・プッシュ型）/ §6（API）/ §7（認可） |

---

## 3. DDL / Flyway まとめ（横断）

| 種別 | 内容 | 原則 |
|------|------|------|
| 新規テーブル | `tournament_contact_space`（01 §2）/ `league_transfer`（03 §3.1） | UUIDv7（原則 6）・クロスドメイン FK なし（原則 1） |
| enum 文字列追加（DDL 不要・VARCHAR / ENUM 値追加） | bulletin `ScopeType` に `TOURNAMENT` / `TOURNAMENT_DIVISION`、chat `ChannelType` に `TOURNAMENT_CHAT` / `TOURNAMENT_DIVISION_CHAT` | — |
| WidgetKey enum 追加（DDL 不要・`widget_key VARCHAR(50)`） | `TEAM_TOURNAMENT_RECORD` / `ORG_TOURNAMENT_SUMMARY` / `TEAM_DIVISION_STANDINGS`（02 §2） | — |
| index 追加（既存なければ） | `chat_channels(source_type, source_id)`（既存 UNIQUE あり）/ `bulletin_threads(scope_type, scope_id)`（既存あり）/ `league_transfer(team_id, season, direction)` UNIQUE / `tournament_contact_space(scope_type, scope_id, space_kind)` UNIQUE | — |
| 不要 | `league_series` 等のピラミッド専用テーブル（組織階層から導出するため） | — |

> **付番**: `V9.YYYYMMDDHHMMSS` 系を踏襲する。マージ直前に `git fetch origin main` し V9 系 / V70 系の最大番号を再確認して衝突を回避する（memory `feedback_migration_version_collision`）。

---

## 4. 設計原則の準拠（横断）

| 原則 | 本書での遵守 |
|------|------------|
| 原則 1（クロスドメイン FK 禁止） | `tournament_contact_space` / `league_transfer` は team_id・division_id・organization_id を ID 参照のみで保持。bulletin/chat への参照も ref_id 値のみ |
| 原則 2（CASCADE は同一ドメイン内のみ） | 大会・ディビジョン削除時もクロスドメイン CASCADE を作らず、スペースは soft delete / archive で残す（01 §6） |
| 原則 5（@Transactional はドメイン内） | 自動生成フックがチャット/掲示板ドメインを呼ぶ箇所は越境 TODO を明記（01 §3）。集計・移籍は読み取りトランザクションを各ドメイン内に閉じる |
| 原則 6（新規テーブルは UUIDv7） | `tournament_contact_space` / `league_transfer` ともに `id BINARY(16)`（UuidV7Entity 継承） |
| 原則 7（テナントスコープ Repository） | `league_transfer` は organization_id でも引くが「両 org をまたぐ移籍記録」のため単一 org スコープに収まらない。`AbstractTenantAwareRepository` は適用せず、from/to の双方向 index で対応（03 §3.1） |

---

## 5. 精査ログ

本設計書は作成後に 2 周の自己精査を実施する想定である（1 周目＝家老 `/検分`、2 周目＝殿の独立確認）。各観点（不備 / セキュリティ / ユーザビリティ / 見落とし / 保守性）のチェック結果は各連番ファイル末尾の精査ログに記載する。

---

## 6. 未解決事項

**現時点でなし。**

マスター裁可済みプランの「未解決点の解消」表に挙がった全論点は、以下のとおり各章本文で解決済み:

| 論点 | 解消先 |
|------|--------|
| REGISTERED チームの閲覧/投稿 | 01 §4.1（REGISTERED＋ACTIVE を含める。WITHDRAWN/DISQUALIFIED 除外） |
| 大会全体チャンネルの N+1 | 01 §4.3（exists 単発クエリ新設） |
| continueTournament の払い出し漏れ | 01 §3.3（フック追加＋テスト） |
| source 複合 index 有無 | 01 §3.4（実装時 grep、無ければ移行追加） |
| 払い出し競合 | 01 §3.4（UNIQUE＋例外 catch 再取得） |
| 大会/ディビジョン削除時のスペース孤児化 | 01 §6（soft delete / archive で残す） |
| bulletin カテゴリ自動生成 | 01 §3.2（provisioning でデフォルト 2 件生成） |
| 通算成績の組織絞り込み | 03 §8（集計クエリ検証、org 絞りがあれば撤廃） |
| chat 公開の妥当性 | 01 §5 / docs/security §（既定 OFF・PUBLIC は read-only） |
| 降格先の次シーズン未作成 | 03 §5（DISPATCHED→PLACED の二段で吸収） |
| 出身県協会が複数/不明 | 03 §5.2（子孫 ASSOCIATION に限定、0 件なら保留して ADMIN へ警告） |
| 同一大会内 vs 組織またぎ昇降格の責務分離 | 03 §2 / §9（既存 PromotionService と新 league_transfer の線引き） |
