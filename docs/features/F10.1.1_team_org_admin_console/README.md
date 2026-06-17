# F10.1.1: チーム/組織 管理者専用ダッシュボード・管理コンソール

> **ステータス**: 🟢 設計確定
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
3. **既存資産を流用する** — 認可は `AccessControlService` / `StandardVisibility(ADMINS_AND_ABOVE)` / `AbstractTenantAwareRepository` / `ContentVisibilityResolver`、ハブ UI は既存の `/organizations/[slug]/admin/point-cards/`（F18 Phase2）の作法を踏襲する。新規の可視性述語・独自ロールゲートは作らない。

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

| カテゴリ | 機能 | 主な操作 | データソース（既存流用 / 新規） |
|---------|------|---------|------------------------------|
| **予約** | 予約確認 | confirm / cancel / complete / no-show / reschedule / admin-note / 承認待ち予約一覧 | **既存流用**: `Team/OrgReservationController`（§01.4.1） |
| **予算** | 予算管理 | 会計年度・配分・カテゴリ・取引・サマリ・超過アラート・レポート・CSV | **既存流用**: `budget/controller/*`（§01.4.2） |
| **承認待ち** | 横断承認待ち | 予約承認待ち・シフトリクエスト・マッチング申込・支払承認・入会/入村申請の横断集約と各一覧への導線 | **新規**: `admin-action-required` 集約 API（[03](./03_admin_action_required_api.md)）。各ドメインの承認実行は既存流用 |
| **メンバー** | メンバー管理/統計 | ロール変更・招待・メンバー一覧・会員証/プロフィール/フィールド/情報・メンバー数統計 | **既存流用**: `admin/dashboard/users`・`member-cards/profiles/fields/info` 系（§01.4.3） |
| **設定** | 設定集約 | shift / faq / public / care-overrides / todo-status-labels / notification-credits / モジュール ON-OFF | **既存流用**: 各 `settings/*` ルート・`admin/modules`（§01.4.4） |
| **アラート** | 管理者向けアラート | 新規予約・承認待ち・未読問い合わせの件数バッジと通知 | **既存流用**: `WidgetAdminBusinessAlert` 系サマリ + 承認待ち集約 API（[02](./02_admin_lens_widgets.md) §3） |

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
│  point-cards/index.vue（F18 Phase2）の作法を踏襲                       │
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
| **P1** | 横断「承認待ち」集約 API（BE）＋契約テスト（[03](./03_admin_action_required_api.md)） | 各ドメイン既存 Service |
| **P2** | L2/L3 ハブのルート骨格＋ミドルウェア（FE）＋既存 settings/member ルートの導線統一（[01](./01_console_routes.md)） | P1（承認待ちセクション） |
| **P3** | L1 管理者レンズトグル＋`DashboardAdminWidgetGrid`（FE）（[02](./02_admin_lens_widgets.md)） | P1・F22.1 既存パネル |
| **P4** | 既存ルート（settings/*・member-*）のハブ配下リダイレクト整理＋E2E（[01](./01_console_routes.md) §6） | P2 |

> 新規テーブルは作らない（[03](./03_admin_action_required_api.md) §5）。本機能は既存テーブルの集計・既存 API のハブ集約・FE 再編が主体であり、Flyway マイグレーションを伴わない。
