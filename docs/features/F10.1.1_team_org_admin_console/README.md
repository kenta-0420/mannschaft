# F10.1.1: チーム/組織 管理者専用ダッシュボード・管理コンソール

> **ステータス**: 🟢 設計完了
> **最終更新**: 2026-06-17
> **モジュール種別**: F10.1（管理者ダッシュボード）のチーム/組織管理者向け拡張
> **対象ロール**: ADMIN / DEPUTY_ADMIN（チーム/組織スコープ）
> **関連ドキュメント**:
> - [F10.1_admin_dashboard.md](../F10.1_admin_dashboard.md) — 母体（スコープ整理・システム管理は別軍議）
> - [01_console_routes.md](./01_console_routes.md) — L2/L3 ハブのルート設計・既存ルート再編マッピング
> - [02_admin_lens_widgets.md](./02_admin_lens_widgets.md) — L1 管理者レンズのウィジェット一覧
> - [03_admin_action_required_api.md](./03_admin_action_required_api.md) — 横断「承認待ち」集約 API 設計
> - [04_security_authorization.md](./04_security_authorization.md) — 認可・セキュリティ設計
> - [05_decisions.md](./05_decisions.md) — 論点と決定
> - [F22.1_swipe_scope_dashboard/README.md](../F22.1_swipe_scope_dashboard/README.md) — 横スワイプダッシュボード（L1 の母体 UI）
> - [F02.2.1_dashboard_widget_role_visibility.md](../F02.2.1_dashboard_widget_role_visibility.md) — ウィジェット可視性ゲート
> - [F00_content_visibility_resolver.md](../F00_content_visibility_resolver.md) — 可視性正準ラダー
> - [docs/security/01_authorization_baseline.md](../../security/01_authorization_baseline.md) — 認可基盤

---

## 1. 概要

チーム/組織の管理者（ADMIN / DEPUTY_ADMIN）は、現状メンバーとほぼ同一のダッシュボードしか閲覧できず、運営に必要な「予約確認」「予算管理」「承認待ち処理」「メンバー管理」「設定変更」といった管理機能が、ナビゲーション上に散在する `settings/*`・`member-*`・`admin/point-cards/*` 等の個別ルートに分散している。本ドキュメントは、これらを**管理者専用の3層ビュー**として体系化する設計を定義する。

```
L1: 管理者レンズ（軽量グランス）
    横スワイプダッシュボード（F22.1）のチーム/組織パネルに「管理者表示」トグルを追加。
    トグル ON で管理者向けウィジェット群（サマリ＋要対応バッジ＋導線のみ）に差し替える。

L2: 管理コンソール ハブ
    /teams/[slug]/admin ・ /organizations/[slug]/admin
    管理機能をカテゴリ別カードで集約したハブページ。

L3: 管理コンソール セクション（各機能の実画面）
    /teams/[slug]/admin/reservations ・ /admin/budget ・ /admin/approvals ・
    /admin/members ・ /admin/settings/* 等。既存ルートを取り込み/導線統一する。
```

L1 はグランス（一目把握）専用で、深い操作は L2/L3 へ遷移する。L1 と L2/L3 は同じ管理機能を**異なる粒度**で見せる関係であり、データソース API は共有する。

### 1.1 設計の3原則

1. **軸を混ぜない** — 横スワイプの軸は「スコープ（個人/チーム/組織）」専用のまま温存する。第4タブ『管理』は新設しない。管理者ビューは各スコープパネル内の**レンズ切替（トグル）**で表現する。
2. **認可はサーバが最終判断** — FE の `useRoleAccess.isAdminOrDeputy` は表示制御のみ。可視・実行可否の最終判断は必ず BE の `AccessControlService.isAdminOrAbove` / `checkAdminOrAbove` / 権限グループ判定で行う。
3. **既存資産を流用する** — 認可は `AccessControlService`（`checkAdminOrAbove` / `isAdminOrAbove`〔集合判定・DEPUTY 包含〕/ `resolveEffectiveRoleName`）・`StandardVisibility(ADMINS_AND_ABOVE)`〔ADMIN+DEPUTY 包含〕・`ContentVisibilityResolver` を流用する。新規の可視性述語・独自ロールゲートは作らない。ハブ UI のアクセス制御は**新規標準作法 `admin-console`**（ルートミドルウェア＋`useRoleAccess`〔第2引数 slug〕）として定義する（既存 point-cards は共通作法を持たないため「踏襲」しない・[05](./05_decisions.md) §6）。

> **偵察に基づく事実確定（2026-06-17）**: 本シリーズは検分指摘を受け、先行版が「既存流用」を実コード未確認で断言した箇所を全て実コード偵察で確定し、断定に書き換えた。主な訂正: (a) team/org への入会申請ドメインは**存在しない**（招待のみ・村の `VillageJoinRequestService` のみ実在）、(b) 予約は team 専用（組織スコープ無し）、(c) 承認待ち集約はスコープ別動的ドメイン（team=予約/シフト/マッチング・org=支払）、(d) point-cards は共通認可作法を持たない（インライン `myOrganizations.role`＋amber 表示）、(e) `BUDGET_VIEW` は scope=ORGANIZATION のみ実在（team は seed 追加が要る）、(f) 管理者ウィジェット可視性は `min_role`（3値）ではなく `StandardVisibility.ADMINS_AND_ABOVE` コード固定。詳細は各分冊と [05_decisions.md](./05_decisions.md)。

---

## 2. 本ドキュメント群のスコープ

| 区分 | 対象ロール | 本ドキュメントでの扱い | 担当ドキュメント |
|------|-----------|----------------------|----------------|
| **A: チーム/組織 管理コンソール** | ADMIN / DEPUTY_ADMIN | **本ドキュメント群で新設** | F10.1.1（本群） |
| **B: システム管理（プラットフォーム）** | SYSTEM_ADMIN | **スコープ外（別軍議）**。F10.1 母体の既存システム管理章（AB）を温存 | F10.1 §4「システム管理者ダッシュボード（AB）」 |

> **棲み分けの根拠**: A は「テナント内（1チーム/1組織）の運営者」が使う機能で、認可は `scope_type + scope_id` による所属＋ロール判定で閉じる。B は「プラットフォーム全体」を横断管理する機能で、`isSystemAdmin(userId)` 判定とテナント横断の権限を要する。両者は認可境界・対象データ・UI 配置（`/admin` vs `/system-admin`）がすべて異なるため、設計ドキュメントを分離する。F10.1 母体の冒頭に本棲み分けを追記する（同 §1 改訂）。

---

## 3. 機能カタログ（管理コンソールに集約する機能）

L2/L3 ハブが束ねる管理機能を以下に列挙する。各機能のデータソース API・認可・ルートは [01_console_routes.md](./01_console_routes.md) を、L1 レンズに載せるサマリは [02_admin_lens_widgets.md](./02_admin_lens_widgets.md) を参照する。

| カテゴリ | 機能 | スコープ | 主な操作 | データソース（既存流用 / 新規） |
|---------|------|---------|---------|------------------------------|
| **予約** | 予約確認 | team のみ | confirm / cancel / complete / no-show / reschedule / admin-note / 承認待ち予約一覧 | **既存流用**: `TeamReservationController`（実在・組織版は無し・§01.3.1） |
| **予算** | 予算管理 | team / org | 会計年度・配分・カテゴリ・取引・サマリ・超過アラート・レポート・CSV | **既存流用**: budget ドメイン Controller 群（既存 `budget.vue` を正本・§01.3.2） |
| **承認待ち** | 横断承認待ち | team=予約/シフト/マッチング・org=支払 | 各ドメインの承認待ち件数の横断集約と各一覧への導線（スコープ別動的ドメイン） | **新規**: `admin-action-required` 集約 API（[03](./03_admin_action_required_api.md)）。各ドメインの承認実行は既存流用 |
| **支払** | 未収請求 | org のみ | 組織が発行した未完了請求の処理状況追跡 | **既存流用**: `PaymentRequestService.findForOrg`（実在・支払承認ワークフローは無し・[03](./03_admin_action_required_api.md) §3.4） |
| **メンバー** | メンバー管理/統計 | team / org | ロール変更・**招待**（入会申請ドメインは team/org に無し）・メンバー一覧・会員証/プロフィール/フィールド/情報・メンバー数統計 | **既存流用**: `admin/dashboard/users`・`member-cards/profiles/fields/info` 系（§01.3.4） |
| **設定** | 設定集約 | team / org | shift / faq / public / care-overrides / todo-status-labels / notification-credits / モジュール ON-OFF | **既存流用**: 各 `settings/*` ルート・`admin/modules`（§01.3.5） |
| **アラート** | 管理者向けアラート | team / org | 新規予約・未読問い合わせの件数バッジ（承認待ちは ③ に一本化・二重計上回避） | **既存流用**: `WidgetAdminBusinessAlert` 系サマリ（[02](./02_admin_lens_widgets.md) §3） |

---

## 4. 全体アーキテクチャ図

```
┌─ 横スワイプダッシュボード（F22.1・軸=スコープ） ──────────────────────┐
│  [個人] ← → [チーム] ← → [組織]    （循環スワイプ。軸は不変）          │
│                  │                                                     │
│   DashboardTeamPanel / DashboardOrgPanel                               │
│   ┌────────────────────────────────────────────────┐                  │
│   │  タグ行  #チームA #チームB …      [👤メンバー｜🛡管理者] ← レンズ │ ← L1 トグル
│   │  ────────────────────────────────────────────── │                  │
│   │  メンバーレンズ: DashboardSwipeWidgetGrid（厳選8）│                  │
│   │  管理者レンズ : DashboardAdminWidgetGrid（管理8）  │ ← 新規コンポーネント│
│   │     ├ 予約サマリ      → /teams/[slug]/admin/reservations           │
│   │     ├ 予算サマリ      → /teams/[slug]/admin/budget                 │
│   │     ├ 承認待ちバッジ  → /teams/[slug]/admin/approvals  ← 集約APIを使用│
│   │     ├ メンバー統計    → /teams/[slug]/admin/members               │
│   │     └ 「管理コンソールを開く」 → /teams/[slug]/admin（ハブ）        │
│   └────────────────────────────────────────────────┘                  │
└──────────────────────────────────────────────────────────────────────┘
                                   │ 「管理コンソールを開く」
                                   ▼
┌─ L2 ハブ /teams/[slug]/admin ─────────────────────────────────────────┐
│  [予約] [予算] [承認待ち] [メンバー] [設定]   ← カテゴリ別アクションカード  │
│  新規標準作法 admin-console（middleware + useRoleAccess〔slug〕）        │
│  ※組織ハブは [予算][支払][承認待ち][メンバー][設定][ポイントカード]      │
└──────────────────────────────────────────────────────────────────────┘
                                   │ カード押下
                                   ▼
┌─ L3 セクション（実画面） ──────────────────────────────────────────────┐
│  /admin/reservations  /admin/budget  /admin/approvals                  │
│  /admin/members       /admin/settings/*                                 │
│  ※既存 settings/*・member-* ルートを取込/リダイレクト                   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 5. 対象レベル

- [x] 組織 (Organization) — `/organizations/[slug]/admin`
- [x] チーム (Team) — `/teams/[slug]/admin`
- [ ] 個人 (Personal) — 対象外（個人スコープに管理者概念はない）

---

## 6. 実装フェーズ提案

| フェーズ | 内容 | 依存 |
|---------|------|------|
| **P1** | 横断「承認待ち」集約 API（BE）＋各ドメインの読み取り専用 Query Service 新設（[05](./05_decisions.md) §12）＋契約テスト（[03](./03_admin_action_required_api.md)）。**TEAM スコープ予算権限 seed は P1 から除外**（`permissions.name` 単独 UNIQUE によりスキーマ上不可。P3 で方針確定・[05](./05_decisions.md) §13 参照） | 各ドメイン既存 Service |
| **P2a** | L2/L3 ハブのルート骨格＋`admin-console` ミドルウェア（FE）。**承認待ちバッジは含めない**（ハブ骨格のみ・各フェーズ単独リリース可能にする） | P1（API 自体は無くてもハブ骨格は出せる） |
| **P2b** | ハブの承認待ちバッジ／カードを点火（承認待ち集約 API を消費） | P1・P2a |
| **P3** | L1 管理者レンズトグル＋`DashboardAdminWidgetGrid`（FE）（[02](./02_admin_lens_widgets.md)） | P1・F22.1 既存パネル |
| **P4** | 既存ルート（トップ直下 reservations/budget/payments/matching/shifts・member-*）のハブ配下リダイレクト整理＋settings 導線＋E2E（[01](./01_console_routes.md) §6） | P2a |

> **P2/P1 依存の分割（各フェーズ単独リリース可能の成立）**: P2 を「P2a=ハブ骨格（承認待ちバッジ抜き）」と「P2b=承認待ちカード点火（P1 後）」に分割する。P2a はバッジ未取得でも非表示フォールバックで成立し、P1 完了前でもリリースできる。
>
> **新規テーブルは作らない**。**Flyway マイグレーションについて**: TEAM スコープの `BUDGET_VIEW`/`BUDGET_MANAGE` 権限 seed は `permissions.name` 単独 UNIQUE（`V2.002`）によりスキーマ上不可であることが実装軍議で判明したため、**P1 スコープから除外**した（先行版の「Flyway マイグレーション1本伴う」記述を訂正）。TEAM 予算権限の方針は P3 軍議で確定（[05](./05_decisions.md) §13）。本機能の主体は既存テーブルの集計・既存 API のハブ集約・読み取り Query Service の新設・FE 再編。

---

## 7. 凡例・未解決事項

- **ステータス凡例**: 🟢 = 設計完了（実装可能）。本シリーズの全分冊は 🟢。🟡（設計未確定・要検討）は使用しない。
- **未解決事項: なし**。検分二隊の致命5件・要修正は全て実コード偵察に基づく断定へ書き換え済み。残る他ドメインへの依存作業は宙ぶらりんにせず [05](./05_decisions.md) §12 の依存タスク表に起票先付きで明示した（前提・後回しにしない）。
