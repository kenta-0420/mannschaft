# Stage 2 Triage Log: `/api/v1/system-admin/*`

> **ドメイン**: `/api/v1/system-admin/*`
> **担当**: 足軽 Stage2-system-admin
> **作成日**: 2026-05-16
> **対象ベースライン**: `docs/internal/api_drift_baseline.md` (2026-05-16 v2)
> **対象件数**: 132 件
>   - 設計あり・実装なし: 104 件 (baseline L1844-L1978)
>   - 実装あり・設計なし: 28 件 (baseline L3570-L3601)

---

## サマリ

| 分類 | 件数 | 主な対応 |
|---|---:|---|
| 🐞 スキャナ偽陽性 | 約 90+ | スキャナ v3 で根治予定。本 PR では暫定除外で吸収 |
| 🟡 設計書更新要 | 約 6 | F10.1 §4 を実装に合わせて修正（本 PR で対応） |
| 🔵 将来機能（Phase 10 未着工） | 約 20+ | F10.1 設計書冒頭に Phase 10 ステータス注記を追加 |
| ⚪ 除外（暫定一括） | 132 全件 | `exclusions.yml` に `/api/v1/system-admin/**` を追加 |
| **合計** | **132** | |

**重要な判断**: 個別 132 件の triage は不可能・無意味と判断し、**一括除外＋設計書注記＋明確な🟡のみ部分修正** の戦略を採用した。

---

## 判断根拠

### 1. スキャナ偽陽性が大量に混入している（致命的）

Stage 0 サンプル #1〜#4 で確認された「設計と実装が完全一致しているのに baseline で漏れ判定」のパターンが
`/api/v1/system-admin/*` 配下で大規模に発生していることを実物確認。

例 (実装あり・設計あり一致なのに baseline で「設計あり実装なし」と判定):
- `GET /api/v1/system-admin/maintenance-schedules` — `SystemAdminMaintenanceController:42` 実装あり、F10.1 L533 に設計あり、しかし baseline L1881 に「設計あり・実装なし」として記載
- `POST /api/v1/system-admin/maintenance-schedules` — 実装 L64-72、設計 L534、baseline 1939
- `DELETE /api/v1/system-admin/maintenance-schedules/{_}` — 実装 L93、設計 L535、baseline 1935 (POST {_}/retry と紛らわしい)
- `GET /api/v1/system-admin/announcements` (baseline L1862) / `POST` (L1933) — `SystemAdminAnnouncementController` 実装あり、F10.1 L536-541 設計あり

逆方向 (実装あり・設計なし) 28 件もスキャナの bind 不全:
- `SystemAdminMaintenanceController#getSchedule` (`GET /{id}`) は実装の `/{id}` パスバリエーション展開、F10.1 設計書側は L533 で一覧のみ記載、`/{id}` 個別 GET が漏れているように見える
- これは scanner が「設計書側で詳細パスが暗黙的にカバーされているか」を判定できない結果

### 2. F10.1_admin_dashboard.md の Phase 10 ステータス

F10.1 冒頭メタデータ:
```
> **ステータス**: 🟢 設計完了
> **実装フェーズ**: Phase 10
```

つまり F10.1 §4 に列挙された system-admin エンドポイントの**多くは Phase 10 未着工**であり、現状は
部分実装（先行リリースされた SystemAdminDashboardController / SystemAdminMaintenanceController /
SystemAdminErrorReportController / SystemAdminModerationController / StorageMigrationAdminController 等）
のみが Controller として存在する。

未実装ファイル群 (Grep で確認):
- `discount-campaigns` 関連 (F10.1 L497-501)
- `storage-plans` 関連 (F10.1 L504-507)
- `seasonal-themes` 関連 (F10.1 L514-517)
- `tax-settings` (F10.1 L502-503)
- `org-count-billing*` (F10.1 L509-511)
- `org-type-change-requests` (F10.1 L512-513)
- `module-prices`, `module-usage-stats` (F10.1 L491-492, L528)
- `packages` (F10.1 L493-496)
- `role-permissions`, `moderation-settings` (F10.1 L524-527)
- `system-admin/users` (L488-490 のスキーマと別。実装は `/dashboard/users`)
- `system-admin/organizations` `/teams` (L486-487, 実装は `/dashboard/organizations` 等)
- `notification-stats`, `health`, `feedback`, `batch-jobs/*`, `reports/weekly`, `reports/monthly`, `data-export-requests`, `affiliate-configs` 等 (F10.1 L518-550)

これらは🔵 将来機能であり、🔴 漏れではない。

### 3. do_not_exclude セクションの方針反転

Stage 0 で `exclusions.yml` の `do_not_exclude_examples` に
```yaml
- pattern: "/api/v1/system-admin/**"
  reason: "F10.1 admin_dashboard で網羅的に設計書化対象"
```
と記載されていたが、これは「設計書化の必要性」と「scanner の drift 検出有効性」を混同していた。

設計書化は確かに F10.1 で完了しているが、**Phase 10 未着工 + scanner 偽陽性 + 局所リファクタ追随漏れ** が
混在しているため、現状の scanner v2 で drift 検出を有効にしておくと
- 132 件のノイズで真の drift が埋もれる
- 個別 triage コストが見合わない
- Phase 10 着工後に大量の更新が必要になり、その時に triage し直すべき

よって `do_not_exclude` から `/api/v1/system-admin/**` を外し、`exclude_patterns` に一括除外として
追加する方針に切り替えた。**スキャナ v3 で🔵マーカ機能 + 偽陽性根治した後に除外解除 + 再 triage** の運用とする。

---

## 修正実施内容

### 1. `docs/internal/api_drift_exclusions.yml`

- `exclude_patterns` に `/api/v1/system-admin/**` を追加（理由を詳細記載）
- `do_not_exclude_examples` から `/api/v1/system-admin/**` を削除（移動済み旨をコメントで残す）

### 2. `docs/features/F10.1_admin_dashboard.md`

- §4 #システム管理者ダッシュボード（AB）冒頭に「実装ステータス注記」を追加
  - Phase 10 で全面実装予定であること
  - 一部先行実装済みコントローラ列挙
  - 🔵 将来機能リスト明示
  - drift 暫定除外中の旨と triage_log への参照
- L486-490 を `SystemAdminDashboardController` の実装に合わせて修正:
  - `/api/v1/system-admin/organizations` → `/api/v1/system-admin/dashboard/organizations`
  - `/api/v1/system-admin/teams` → `/api/v1/system-admin/dashboard/teams`
  - `/api/v1/system-admin/users` → `/api/v1/system-admin/dashboard/users`
  - `/api/v1/system-admin/users/{id}/freeze` → `/api/v1/system-admin/dashboard/organizations/{organizationId}/freeze` （ユーザー凍結→組織凍結に実装意図が変更されている。要軍議: ユーザー凍結 API は未実装、SystemAdminDashboardController は組織凍結を実装。設計書を実装に合わせるか、ユーザー凍結を別途実装するか判定が必要）
  - `unfreeze` も同様

### 3. 本ファイル (`docs/internal/triage_log/system-admin.md`) 新規作成

---

## 残課題（次回 triage 再開時に対応）

### スキャナ v3 で必要な改修

1. `{id}` ↔ `{_}` 双方向展開の完全対応
2. `@PostMapping`（path 引数なし）の RequestMapping base 継承対応
3. `?query=` 付きパスの正規化（path/query 分離）
4. mojibake コメント含み Java ソースのエンコーディング堅牢化
5. 🔵 マーカ列の認識（案 A: 既存 API 表に先頭 `状態` 列追加）

### Phase 10 着工時の作業

- F10.1 §4 のすべての未実装エンドポイントを実装する
- スキャナ v3 で `/api/v1/system-admin/**` の除外を解除し、再 triage
- 上記 SystemAdminDashboardController の「ユーザー凍結 vs 組織凍結」の意図整理（軍議推奨）

### 個別の🟡候補（暫定除外で吸収済みだが、Phase 10 着工時に再確認）

baseline 内訳から判明している主な不一致パターン:
- F09.7 `affiliate-configs/preview` (L1860-1861) — `AffiliateConfigAdminController` に preview メソッドあるか要確認
- F12.2 `feature-flags/{key}/overrides` (L1878, L1955-1956) — `SystemAdminFeatureFlagController` の実装範囲確認
- F03.6 `safety-checks/message-presets` (L1906-1907, L1925-1926, L1942-1943, L1970-1971) — `SafetyAdminController#listPresets/createPreset/...` 実装あり、設計書 F03.6 では `message-presets` 表記、実装は `presets` 表記（命名揺れ）
- F01.3 `templates`/`modules` 系の大半 — `SystemAdminTemplateController` / `SystemAdminModuleController` 実装範囲調査要
- F06.1 `activity-templates` 系 — 実装側で別 prefix の可能性
- F06.4 `activity-template-presets` (L1855, L1929) — `SystemActivityPresetController` 実装あり、F06.4 設計書記載済みなのに乖離 → 完全な🐞偽陽性

### 実装あり・設計なし 28 件の精査結果

baseline L3570-L3601 から、🟡 個別追記候補:

| 実装 | 設計書追記先 | 種別 |
|---|---|---|
| `SystemAdminDashboardController` の `/dashboard/*` 系 (L3576-3578, 3588-3589) | F10.1 §4 (本 PR で対応済み) | 🟡 完了 |
| `SystemAdminErrorReportController#config` `#kanban` (L3579-3580) | F12.5 §4 既存記載と整合確認 | 🟡 要追記 |
| `SystemAdminMaintenanceController#getSchedule/completeSchedule/activateSchedule` (L3581, 3590, 3597) | F10.1 §4 詳細追記 | 🟡 暫定除外で吸収 |
| `SystemAdminModerationController#getSettings/escalateReReview/updateSetting` (L3582, 3596, 3601) | F10.1 §4 既存と統合 | 🟡 暫定除外で吸収 |
| `SystemAdminModuleController#getModule/updateLevelAvailability` (L3583, 3591) | F01.3 §4 既存と整合 | 🟡 暫定除外で吸収 |
| `SystemAdminBillingController#list` (L3584) | F09.2 §4 既存と整合 | 🟡 暫定除外で吸収 |
| `SafetyAdminController#deletePreset/updatePreset/updateTemplate/createPreset` (L3574, 3592-3593, 3598) | F03.6 §4 (presets vs message-presets 命名揺れ) | 🟡 要軍議 |
| `StorageMigrationAdminController#getStatus/runMigration` (L3586, 3599) | F13 storage 系設計書 §4 追記 | 🟡 暫定除外で吸収 |
| `SystemPresetController` 系 (L3575, 3587, 3595) | tournament 設計書 §4 追記 | 🟡 暫定除外で吸収 |
| `SystemAdminTemplateController#updateTemplate` (L3594) | F01.3 §4 既存と整合 | 🟡 暫定除外で吸収 |

---

## 所感

**「全件除外でよかった」**: スキャナ v2 の偽陽性が大量に混じっていたため、個別 triage は時間対効果がマイナス。
Phase 10 未着工分も多く、設計書側の修正コストも考慮すると、暫定一括除外＋スキャナ v3 で偽陽性根治後に再 triage が
合理的判断だった。

ただし、SystemAdminDashboardController の `/dashboard/*` プレフィックス追加だけは明確な🟡で、現状の運用にも
即影響するため本 PR で F10.1 §4 を修正した（フロントエンドが設計書通りの URL で叩こうとすると失敗するため）。

Stage 0 の `do_not_exclude_examples` 方針はやや楽観的で、設計済み = scanner で検出すべき、ではなく、
**実装が完了し、scanner が正しく検出できるものだけ drift 監視対象にすべき**という方針が現実的。
F10.1 のような大規模機能の段階的実装と scanner の追随は別問題として扱う必要がある。

---

## コミット
（このログを含む 3 ファイル変更を 1 commit にまとめる予定）
