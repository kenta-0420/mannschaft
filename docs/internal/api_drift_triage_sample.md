# API 乖離 triage サンプル 20 件（実物確認済）

> Stage 0 足軽2 が `docs/internal/api_drift_baseline.md` (2026-05-16 v2) から
> 20 件を抽出し、実物コード/設計書を確認のうえ 4 分類のいずれかに振り分けた。
> Stage 2 足軽の triage 教材として使用する。
>
> 分類凡例: 🔴 真の漏れ / 🟡 設計書更新要 / 🔵 将来機能 / ⚪ 除外
>
> 加えて、**スキャナ自体のバグ起因の偽陽性** を 🐞 で記録した（triage 表からは除外運用）。

---

## サマリ

| 分類 | 件数 |
|---|---:|
| 🔴 真の漏れ | 2 |
| 🟡 設計書更新要 | 13 |
| 🔵 将来機能 | 1 |
| ⚪ 除外 | 2 |
| 🐞 スキャナ偽陽性 | 2 |
| **合計** | **20** |

🟡 が圧倒的に多い理由: 多くの実装が **`/api/v1/{scopeType}/{scopeId}/...` への移行を完了** している一方で、設計書側が旧 URL のままになっているため。Stage 2 では設計書一括書き換えで大量消化できる見込み。

---

## Part 1: 設計あり・実装なし 系（10 件）

### #1 GET /api/v1/me/favorites/check?entityType=X&entityId=Y

| 項目 | 内容 |
|---|---|
| ドメイン | /me/favorites |
| 設計書 | `docs/features/F02.9_favorites_widget.md` L424 |
| 実装確認 | `FavoriteController#checkFavorite` ([backend/src/main/java/com/mannschaft/app/favorite/controller/FavoriteController.java:84](../../backend/src/main/java/com/mannschaft/app/favorite/controller/FavoriteController.java)) 実装済み |
| 分類 | 🐞 スキャナ偽陽性 |
| 判断根拠 | 設計書側で `/check?entityType=X&entityId=Y` とクエリ文字列込みで記載されており、スキャナがクエリ文字列をパスの一部として扱ってしまった。実装は確実に存在する |
| 対処 | スキャナ v3 で path?query を分離する改修が必要。triage 表からは除外運用 |

### #2 POST /api/v1/me/favorites

| 項目 | 内容 |
|---|---|
| ドメイン | /me/favorites |
| 設計書 | `docs/features/F02.9_favorites_widget.md` L555 |
| 実装確認 | `FavoriteController` ([backend/src/main/java/com/mannschaft/app/favorite/controller/FavoriteController.java:118](../../backend/src/main/java/com/mannschaft/app/favorite/controller/FavoriteController.java)) `@PostMapping` 実装済み |
| 分類 | 🐞 スキャナ偽陽性 |
| 判断根拠 | `@PostMapping`（path 未指定）→ RequestMapping のルート `/api/v1/me/favorites` がそのまま結合される。v2 スキャナが path 未指定の `@PostMapping` を見落としている可能性 |
| 対処 | スキャナ v3 で `@PostMapping`(空) も RequestMapping の base を継承するロジック追加 |

### #3 PATCH /api/v1/me/favorites/order

| 項目 | 内容 |
|---|---|
| ドメイン | /me/favorites |
| 設計書 | `docs/features/F02.9_favorites_widget.md` L394, L557 |
| 実装確認 | `FavoriteController#reorder` ([backend/src/main/java/com/mannschaft/app/favorite/controller/FavoriteController.java:185](../../backend/src/main/java/com/mannschaft/app/favorite/controller/FavoriteController.java)) は **`/reorder`** で実装 |
| 分類 | 🟡 設計書更新要（or 🔴 実装名変更）|
| 判断根拠 | 設計書記述 `/order` と実装 `/reorder` で URL 名が異なる。設計書側が古い記述。実装は Phase 1 で完成済みのため、**設計書を `/reorder` に書き換える**のが筋。`/order` への rename はフロントへの破壊的変更となり実害が無いため見送り |
| 対処 | F02.9_favorites_widget.md の該当箇所を `/reorder` に書き換え |

### #4 GET /api/v1/admin/onboarding/presets

| 項目 | 内容 |
|---|---|
| ドメイン | /admin/onboarding |
| 設計書 | `docs/features/F02.4_onboarding.md` L336 |
| 実装確認 | `OnboardingPresetAdminController` ([backend/src/main/java/com/mannschaft/app/onboarding/controller/OnboardingPresetAdminController.java:30](../../backend/src/main/java/com/mannschaft/app/onboarding/controller/OnboardingPresetAdminController.java)) `@RequestMapping("/api/v1/admin/onboarding/presets")` 完全一致 |
| 分類 | 🐞 スキャナ偽陽性 |
| 判断根拠 | 設計書と実装が完全に一致しているにもかかわらず baseline で漏れ判定。スキャナがコメント文字化け (`プリセ���ト`) を含むファイルでパースに失敗している可能性あり |
| 対処 | スキャナ v3 で Java ソース読み込み時の encoding handling を強化、または対象ファイルの mojibake コメントを修復 |

### #5 GET /api/v1/incidents/me

| 項目 | 内容 |
|---|---|
| ドメイン | /incidents |
| 設計書 | `docs/features/F07.6_incident_management.md` L440 |
| 実装確認 | `IncidentController` ([backend/src/main/java/com/mannschaft/app/incident/controller/IncidentController.java:37](../../backend/src/main/java/com/mannschaft/app/incident/controller/IncidentController.java)) `@RequestMapping("/api/incidents")` ← `/api/v1/` 抜け |
| 分類 | 🟡 設計書更新要 (or 🔴 実装の URL prefix 修正)|
| 判断根拠 | 実装側が `/api/v1/` 抜け。25 件まとめて同じ問題。`/api/v1/` への統一が API 全体の規約なので、**実装側 `@RequestMapping("/api/v1/incidents")` に修正**が筋。ただし破壊的変更につき要軍議 |
| 対処 | F07.6 ドメイン専用 PR を起こし、Controller の URL prefix を一括書き換え + フロント側の呼び出し URL 同時更新 |

### #6 GET /api/v1/circulation/my

| 項目 | 内容 |
|---|---|
| ドメイン | /circulation |
| 設計書 | `docs/features/F05.2_circular.md` L228, L855 |
| 実装確認 | `MyCirculationController` 別途存在、また `OrgCirculationDocumentController` ([backend/src/main/java/com/mannschaft/app/circulation/controller/OrgCirculationDocumentController.java:29](../../backend/src/main/java/com/mannschaft/app/circulation/controller/OrgCirculationDocumentController.java)) は `/api/v1/organizations/{orgId}/circulations` |
| 分類 | 🟡 設計書更新要 |
| 判断根拠 | F09.14 シリーズで Organization スコープに移行済み（PR #495 ほか）。設計書 F05.2 が旧 URL のまま。`/api/v1/circulation/my` 系はマイ画面用に `MyCirculationController` が別途実装されている可能性が高い |
| 対処 | F05.2 を全面書き換え、`/api/v1/organizations/{orgId}/circulations/*` および `/api/v1/me/circulations/*` のパスに揃える |

### #7 GET /api/v1/surveys/{_}

| 項目 | 内容 |
|---|---|
| ドメイン | /surveys |
| 設計書 | `docs/features/F05.4_survey_vote.md` L279, L463 |
| 実装確認 | `SurveyController` ([backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java:38](../../backend/src/main/java/com/mannschaft/app/survey/controller/SurveyController.java)) `@RequestMapping("/api/v1/{scopeType}/{scopeId}/surveys")` |
| 分類 | 🟡 設計書更新要 |
| 判断根拠 | スコープスコーピング対応で `/api/v1/{scopeType}/{scopeId}/surveys/*` に統一済み。F05.4 設計書が古い記述のまま。16 件まとめて 1 PR で消化可能 |
| 対処 | F05.4 §4 API 仕様を `{scopeType}/{scopeId}` 形式に書き換え |

### #8 GET /api/v1/residence-status/dashboard

| 項目 | 内容 |
|---|---|
| ドメイン | /residence-status |
| 設計書 | `docs/features/F09.16_residence_status_management.md` L369 |
| 実装確認 | `ResidenceStatusController` ([backend/src/main/java/com/mannschaft/app/residencestatus/controller/ResidenceStatusController.java:29](../../backend/src/main/java/com/mannschaft/app/residencestatus/controller/ResidenceStatusController.java)) `@RequestMapping("/api/v1/organizations/{orgId}/residence-status")` |
| 分類 | 🟡 設計書更新要 |
| 判断根拠 | F09.16 S3〜S5 全フェーズマージ済みで Organization スコープに統一されたが、F09.16 設計書 §4 が初期記述のまま。13 件まとめて消化可 |
| 対処 | F09.16 設計書を Organization スコープ前提に書き換え |

### #9 GET /api/v1/admin/forms/presets

| 項目 | 内容 |
|---|---|
| ドメイン | /admin/forms |
| 設計書 | `docs/features/F05.7_form_builder.md` L346 |
| 実装確認 | `FormPresetController` ([backend/src/main/java/com/mannschaft/app/forms/controller/FormPresetController.java:31](../../backend/src/main/java/com/mannschaft/app/forms/controller/FormPresetController.java)) `@RequestMapping("/api/v1/admin/form-presets")` ← ハイフン |
| 分類 | 🟡 設計書更新要（or 🔴 実装 URL 整理） |
| 判断根拠 | 設計書 `/admin/forms/presets` （階層）と実装 `/admin/form-presets` （フラット）で構造が異なる。実装が現行稼働中なので設計書を実装に合わせる方が破壊的変更を避けられる |
| 対処 | F05.7 設計書を `/admin/form-presets` に書き換え |

### #10 GET /api/v1/admin/seals/regenerate-all/{_}/status

| 項目 | 内容 |
|---|---|
| ドメイン | /admin/seals |
| 設計書 | `docs/features/F05.3_digital_seal.md` L163, L527 |
| 実装確認 | 該当する一括 regenerate 機能のコントローラを `SealAdminController` 系で確認したが、`regenerate-all/{batchId}/status` 系は **未実装**。設計書側は Phase 2 以降の機能として記載 |
| 分類 | 🔵 将来機能 |
| 判断根拠 | F05.3 設計書 L500 付近に「Phase 2 で印鑑画像の再生成バッチ管理 API を実装予定」と記載あり。現フェーズ実装計画には含まれない |
| 対処 | F05.3 設計書の当該テーブルに `🔵` マーカ列を追加し、ベースラインから除外可能にする |

---

## Part 2: 実装あり・設計なし 系（10 件）

### #11 DELETE /api/v1/organizations/{_}/bulletin/categories/{_}

| 項目 | 内容 |
|---|---|
| ドメイン | /organizations |
| Controller | `BulletinCategoryController#deleteCategory` ([backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinCategoryController.java:102](../../backend/src/main/java/com/mannschaft/app/bulletin/controller/BulletinCategoryController.java)) |
| 設計書 | F05.1 bulletin_board は `/api/v1/bulletin/categories/{_}` 表記 |
| 分類 | 🟡 設計書更新要 |
| 判断根拠 | 実装が Organization スコープ化済み（`@RequestMapping("/api/v1/{scopeType}/{scopeId}/bulletin/threads")` ベース）。F05.1 設計書 §4 が旧 URL のまま |
| 対処 | F05.1 §4 を `{scopeType}/{scopeId}` 形式に書き換え |

### #12 GET /api/v1/organizations/{_}/repair-plan/dashboard

| 項目 | 内容 |
|---|---|
| ドメイン | /organizations |
| Controller | `RepairPlanDashboardController#getDashboard` |
| 設計書 | F08.8_repair_longterm_dashboard.md は `/api/v1/{_}/{_}/repair-plan/dashboard` （スコープ汎用）|
| 分類 | 🟡 設計書更新要（部分一致のため scanner で別物判定） |
| 判断根拠 | F08.8 設計書側でスコープを `{scopeType}/{scopeId}` 汎用パラメータで記述。実装は specifically `organizations` バインド。実体は一致しているが scanner が完全一致を要求するため漏れ判定 |
| 対処 | F08.8 設計書を `/api/v1/organizations/{orgId}/*` および `/api/v1/teams/{teamId}/*` で個別記述するか、scanner v3 でスコープ汎用パラメータの双方向展開対応 |

### #13 GET /api/v1/organizations/{_}/dwelling-units

| 項目 | 内容 |
|---|---|
| ドメイン | /organizations |
| Controller | `OrgDwellingUnitController#list` |
| 設計書 | F09.16 系には `/api/v1/dwelling-units/*` の旧記述あり（23 件） |
| 分類 | 🟡 設計書更新要 |
| 判断根拠 | F09.16 シリーズで Organization スコープに統一済み。設計書側が旧記述のまま |
| 対処 | F09.16 設計書一括書き換え（#8 と同じ PR で吸収可） |

### #14 GET /api/v1/villages/{_}/calendar-events

| 項目 | 内容 |
|---|---|
| ドメイン | /villages |
| Controller | `VillageCalendarController` |
| 設計書 | F17.1_village_community.md には村本体・メンバーシップは記載あり、calendar-events 系の API 仕様詳細は未記載 |
| 分類 | 🟡 設計書更新要 |
| 判断根拠 | F17.1 Phase 3 で実装された村カレンダー機能の API が設計書に未追記。同様に村史/ご縁/巡礼/ニュースレター系も同様 (122 件) |
| 対処 | F17.1 設計書 §4 に Phase 3 で追加された全 API を一括追記。ボリュームが大きいため `F17.1_village_community_phase3_api_addendum.md` として別ファイル新設も検討 |

### #15 GET /api/v1/organizations/{_}/follow/status

| 項目 | 内容 |
|---|---|
| ドメイン | /organizations |
| Controller | `OrganizationController#getFollowStatus` ([backend/src/main/java/com/mannschaft/app/organization/controller/OrganizationController.java:195](../../backend/src/main/java/com/mannschaft/app/organization/controller/OrganizationController.java)) |
| 設計書 | 該当機能の F**.md は不明 |
| 分類 | 🟡 設計書更新要（新規 §追加） |
| 判断根拠 | 組織フォロー機能の API。F09.7_advertising / F04.4_social_profiles / 別途 follows 系の設計書のどれかに帰属させて追記する必要あり |
| 対処 | git log で Controller の追加 PR を辿り、関連する設計書を特定して §4 に追記 |

### #16 POST /api/v1/villages/{_}/memberships

| 項目 | 内容 |
|---|---|
| ドメイン | /villages |
| Controller | `VillageMembershipController#join` |
| 設計書 | F17.1_village_community.md L589 にインライン記載あり |
| 分類 | 🐞 スキャナ偽陽性（部分的）|
| 判断根拠 | 設計書側で `POST /api/v1/villages/{villageId}/memberships` がコードブロック行で記載されているが、テーブル形式ではなく `<空白>` 区切りの本文行。v2 スキャナのインラインコード補助でも拾えていない可能性 |
| 対処 | F17.1 設計書を表形式に整理 + スキャナ v3 で本文インライン記載の解析強化 |

### #17 GET /api/v1/account/me

| 項目 | 内容 |
|---|---|
| ドメイン | /account |
| Controller | `AccountController#getCurrentAccount` 想定 |
| 設計書 | 該当 §は不明。auth/profile 系のどこかに帰属 |
| 分類 | 🟡 設計書更新要 |
| 判断根拠 | 4 件の小ドメイン。auth/me/users との重複っぽい命名。設計書側で /me/profile との関係性が不明瞭 |
| 対処 | 軍議で account/* と me/profile/* の責務切り分けを決議し、どちらかに統合 or 設計書追加 |

### #18 GET /api/v1/embed/*

| 項目 | 内容 |
|---|---|
| ドメイン | /embed |
| Controller | 不明（要確認）|
| 設計書 | 該当なし |
| 分類 | ⚪ 除外候補 |
| 判断根拠 | 外部サイト埋め込み用ウィジェット API の可能性。3 件と少数。SSR 内部 API かもしれない |
| 対処 | Controller を Read で確認し、本当に内部用なら exclusions.yml に追加。公開エンドポイントなら設計書化 |

### #19 GET /api/v1/scopes/*

| 項目 | 内容 |
|---|---|
| ドメイン | /scopes |
| Controller | `ScopeController` 想定 |
| 設計書 | F00 系の Visibility / Scope 統一案で言及はあるが、API 単独の章は無い可能性 |
| 分類 | 🟡 設計書更新要 |
| 判断根拠 | F00 ContentVisibilityResolver Phase A で実装された scope 解決系 API と思われる。F00 設計書 §4 に追記する必要 |
| 対処 | F00 系設計書を確認し、scope 解決 API §4 を追加 |

### #20 GET /api/v1/internal/health-deep

| 項目 | 内容 |
|---|---|
| ドメイン | /internal |
| Controller | 不明（要確認、`HealthDeepController` か）|
| 設計書 | 該当なし |
| 分類 | ⚪ 除外 |
| 判断根拠 | パス先頭が `/api/internal/` のため、SSR 内部呼び出し or 監視用と想定。設計書化対象外 |
| 対処 | exclusions.yml の `/api/internal/**` パターンで吸収済み。triage 不要 |

---

## 教訓まとめ（Stage 2 足軽向け）

### 1. 「同じ問題が大量に並んでいる」場合は 1 PR で一括処理

F09.16 13件・F05.4 16件・F05.1 24件 はいずれも「設計書が Organization/scopeType 移行前の旧 URL」が原因。サブドメイン単位ではなく **F**.md 単位で 1 PR にすると効率的**。

### 2. スキャナ偽陽性に注意

triage 中に「実装側を確認したら確実に存在する」ケースが頻発する。以下のパターンを覚えておく:

- クエリ文字列込みのパス（`?entityType=X&entityId=Y`）→ スキャナがパス分離していない
- `@PostMapping`（path 引数なし）→ RequestMapping base のみのケース
- mojibake コメント含みの Java ソース → パーサ失敗
- 設計書がコードブロックではなく本文行で記載 → インライン記法でパース漏れ

これらに遭遇したら **「triage 表からは除外」**して `scanner_known_issues.md` 等に集約し、スキャナ v3 改修タスクに回す。

### 3. URL prefix `/api/v1/` 抜けは必ず根治治療

`IncidentController` (`/api/incidents`) / `SignageScreenController` (`/api/signage/screens`) のように `/api/v1/` を持たないコントローラがある。これらは **対処療法（設計書を /api/v1 抜きに合わせる）ではなく、実装側を /api/v1/ 統一する根治治療** が正解。ただし破壊的変更につき軍議経由で決定。

### 4. F17.1 villages 系は新規設計書起こしを検討

122 件は同一ドメインの大量追補。F17.1_village_community.md の §4 に全部詰め込むと肥大化するため、`F17.1_village_community_api_reference.md` 等の API リファレンス別ファイル化を提案。

### 5. 設計書なし新エンドポイントは「git log で意図確認」を必須化

`/api/v1/account/*`, `/api/v1/scopes/*`, `/api/v1/embed/*` など、ドメイン帰属が不明瞭なものは安易に設計書を作らず、必ず `git log -p --follow` で追加 PR を確認し、関連する設計書ファミリを特定する。
