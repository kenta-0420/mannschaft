# 残中小ドメイン triage 作業ログ（Stage 2 misc）

> 担当: 足軽（feature/api-drift-cleanup-misc）
> 作業日: 2026-05-17
> 入力: `docs/internal/api_drift_baseline.md` v4 の **organizations/teams/villages/users/system-admin/me/admin 以外** 全ドメイン
> 件数規模: 設計あり・実装なし 約 740 件 / 実装あり・設計なし 約 250 件 / 合計 約 990 件

---

## サマリ（全ドメイン合算）

| 分類 | 件数（概算） |
|---|---:|
| 🔴 真の漏れ（実装追加） | 0 |
| 🟡 設計書更新要（スコープ prefix 修正・命名整合） | 約 600 |
| 🔵 将来機能（🔵 マーカ付与） | 約 80 |
| ⚪ 除外（exclusions.yml） | 約 30 |
| 🐞 スキャナ偽陽性（重複・末尾スラッシュ・命名揺れ等） | 約 280 |
| **合計** | **約 990** |

> **設計方針**: v4 でも残る大量の「設計あり・実装なし」は、ほぼ全てが
> **設計書がスコープ prefix 抜きパス（`/api/v1/repair-plan/...`）で記述しているのに対し
> 実装はスコープ付き（`/api/v1/{scopeType}/{scopeId}/repair-plan/...`）になっている** ことに起因する。
> V4-1 逆引きマッチは `/admin/**`, `/dashboard/**`, `/modules/**`, `/visibility/**`, `/settings/**` のみ
> ホワイトリスト対象としているため、ドメイン名（repair-plan, workflow-templates, surveys, bulletin 等）の
> 逆引きは対象外。これは「リソース名がスコープ context で意味が変わる可能性」を考慮した安全策。
> 本足軽の作業範囲では、**設計書側を実態（スコープ prefix）に合わせて書き換える 🟡 対応** を基本方針とする。

---

## ドメイン別 triage 結果

### A. 🟡 設計書スコープ prefix 修正系（実装は scope 付き）

実装が `/api/v1/{scopeType}/{scopeId}/<resource>/...` または `/api/v1/{teams|organizations}/{id}/<resource>/...` で
記述されているのに、設計書側が prefix 抜きで記述されているドメイン群。

| ドメイン | 件数 | 実装側 prefix（実態） | 設計書 |
|---|---:|---|---|
| `/api/v1/repair-plan/*` | 11 | `/api/v1/{scopeType}/{scopeId}/repair-plan/...` | F08.8_repair_longterm_dashboard.md |
| `/api/v1/workflows/*` | 22 | `/api/v1/{scopeType}/{scopeId}/workflow-templates/...` + `/api/v1/workflow-requests/...` | F05.6_workflow_approval.md |
| `/api/v1/bulletin/*` | 23 | `/api/v1/{scopeType}/{scopeId}/bulletin/...` + `/api/v1/bulletin/reactions` | F05.1_bulletin_board.md |
| `/api/v1/circulation/*` | 24 | `/api/v1/teams/{teamId}/circulations` + `/api/v1/organizations/{orgId}/circulations` + `/api/v1/circulations/{documentId}/...` | F05.2_circular.md |
| `/api/v1/dwelling-units/*` | 23 | `/api/v1/organizations/{orgId}/dwelling-units/...` 推定 | F09.16_residence_status.md（系列） |
| `/api/v1/forms/*` | 19 | `/api/v1/{scopeType}/{scopeId}/forms/...` + `/api/v1/admin/form-presets` | F05.7_form_builder.md |
| `/api/v1/surveys/*` | 16 | `/api/v1/{scopeType}/{scopeId}/surveys/...` + `/api/v1/surveys/{surveyId}/...` | F05.4_survey_vote.md |
| `/api/v1/succession/*` | 18 | `/api/v1/organizations/{orgId}/succession/...` 推定 | F09.15_owner_succession.md |
| `/api/v1/promotions/*` | 17 | `/api/v1/organizations/{orgId}/tournaments/{tId}/promotions` | F06.1_cms_blog.md（系列） |
| `/api/v1/residence-status/*` | 13 | `/api/v1/organizations/{orgId}/residence-status/...` | F09.16_residence_status.md |
| `/api/v1/safety-checks/*` | 12 | `/api/v1/safety-checks/...`（一致するはず・偽陽性多）+ `/api/v1/system-admin/safety-checks/...` | F03.6_safety_check.md |
| `/api/v1/corkboards/*` | 17 | `/api/v1/{scopeType}/{scopeId}/corkboards/...` | F09.8_corkboard.md |
| `/api/v1/activities/*` | 20 | `/api/v1/activities/...`（一致しているはず・重複起因） | F06.1_cms_blog.md / F06.4_activity_records.md |
| `/api/v1/job-contracts/*` | 17 | `/api/v1/job-contracts/...`（多くは一致・命名揺れ） | F13.1_short_term_job_matching.md |
| `/api/v1/timeline/*` | 25 | `/api/v1/timeline/...`（実装多数あり・重複起因） | F04.1_timeline.md |
| `/api/v1/chat/*` | 18 | `/api/v1/teams/{teamId}/chat/...` + `/api/v1/chat/...` | F04.2_chat.md / F04.2.1_chat_multi_tab_ui.md |
| `/api/v1/events/*` | 18 | `/api/v1/organizations/{orgId}/events/...` + `/api/v1/teams/{teamId}/events/...` | F03.8_event_management.md |
| `/api/v1/files/*` | 33 | `/api/v1/{scopeType}/{scopeId}/files/...` + `/api/v1/files/{fileId}/...` | F05.5_file_sharing.md |
| `/api/v1/sns/*` | 8 | `/api/v1/social/...` | F04.4_social_profiles.md |
| `/api/v1/social-profiles/*` | 8 | `/api/v1/social/...`（リネーム済） | F04.4_social_profiles.md |
| `/api/v1/coupons/*` | 7 | `/api/v1/organizations/{orgId}/coupons/...` 推定 | F18_pointcard.md |
| `/api/v1/property-listings/*` | 9 | `/api/v1/organizations/{orgId}/property-listings/...` 推定 | F09.15 系 |
| `/api/v1/sns/*` / `/api/v1/follows/*` | 5+8 | `/api/v1/social/follows` 実装 | F04.4 系 |
| `/api/v1/orgs/*` | 8 | `/api/v1/organizations/...`（命名揺れ） | 複数 |

**短期対処方針**: ドメイン単位で機械置換（`/api/v1/repair-plan/` → `/api/v1/{scopeType}/{scopeId}/repair-plan/`）を
推奨するが、当該足軽の責務範囲を超える大規模置換のため、**triage 記録のみ** とし、後続足軽が
ドメイン別 PR で個別に対応する。本足軽では特に高影響な数件のみ実機修正。

---

### B. 🔵 将来機能タグ付与候補

設計書のみ・実装無し、かつ Phase 未着工・将来計画として明示されているもの。

| ドメイン | 件数 | 設計書 | フェーズ |
|---|---:|---|---|
| `/api/v1/jobber-invitations/*` | 2 | F13.1 | Phase 5 (未着工) |
| `/api/v1/job-disputes/*` | 1 | F13.1 | Phase 5 (未着工) |
| `/api/v1/job-payments/*` | 3 | F13.1 | Phase 5 (未着工) |
| `/api/v1/no-show-records/*` | 2 | F13.1 | Phase 5 (未着工) |
| `/api/v1/segment-presets/*` | 4 | F04.6 検索系 | Phase 2 (未着工) |
| `/api/v1/seal/*` | 3 | F05.3 | Phase 2 (未着工、admin 系 B-1 と重複) |
| `/api/v1/stripe/*` | 3 | F08.2 | 外部 webhook（除外候補） |
| `/api/v1/queue/*` | 3 | F03.7_queue.md | 計画段階 |
| `/api/v1/proxy-input-consents/*` | 1 | F09.16 系 | Phase 5 |
| `/api/v1/feedback/*` | 4 | F10.1 | Phase 2 |
| `/api/v1/blog/*` | 4 | F06.1 | 一部 Phase 未着工 |
| `/api/v1/templates/*` | 2 | F01.3 | テンプレ機能 Phase 2 |
| `/api/v1/visibility-templates/*` | 2 | F01.7 | Phase 2 |
| `/api/v1/timetables/*` | 2 | F03.9 | 個別未着工 API |
| `/api/v1/timetable-terms/*` | 2 | F03.9 | 同上 |
| `/api/v1/timeline-posts/*` | 1 | F04.1 | リネーム揺れ |
| `/api/v1/push-subscriptions/*` | 2 | F04.3 | 古い記述 |
| `/api/v1/sns/*` | 8 | F04.4 | 旧記述（実装は `/social/`） |

**短期対処**: 該当設計書に状態列を追加し、🔵 マーカで明示するのが理想だが、
ドメイン横断で約 80 件の機械置換が必要となるため、**本足軽では triage 分類のみ** とし、
実機マーカ付与は後続足軽（ドメイン別）に委譲。

---

### C. ⚪ 除外パターン追加候補

実装あり・設計なしのうち、設計書化対象外と判断するもの。

| パターン | 理由 | category | 状態 |
|---|---|---|---|
| `/api/v1/social/**` | 旧 sns / social-profiles のリネーム後パス。F04.4 設計書側を更新中。新規除外不要（🟡 で対処） | - | 🟡 |
| `/api/v1/scopes/**` | 7 件・スコープ参照ヘルパー API。F00 (ContentVisibilityResolver) 内部用 | internal | **追加** |
| `/api/v1/embed/**` | 3 件・埋め込みウィジェット用パブリック API。設計書化検討対象（🟡 候補） | - | 🟡 |
| `/api/v1/endpoints/**` | 4 件・エンドポイント自己内省用 API（dev 補助） | internal | **追加** |
| `/api/v1/incoming/**` | 4 件・外部から受信する webhook 系 | external | **追加** |
| `/api/v1/feedbacks/**` | 3 件・ユーザ feedback 投稿（F12.6 系候補・設計書化 🟡） | - | 🟡 |
| `/api/v1/ical/**` | 1 件・iCalendar フィード（F03.3 Google Calendar 連携） | external | **追加** |
| `/api/v1/mail-tracking/**` | 1 件・メール open/click 追跡（F04.3 補助） | internal | **追加** |
| `/api/v1/pdf-signatures/**` | 1 件・PDF 署名検証（F05.3 系内部） | internal | **追加** |
| `/api/v1/mentions/**` | 1 件・チャット mentions 候補列挙（F04.2 内部） | internal | 既存・🟡 で追記候補 |
| `/api/v1/supported-locales/**` | 1 件・i18n 補助 | internal | **追加** |
| `/api/v1/care-links/**` | 1 件・F03.12 内部リンク | internal | 既存・🟡 候補 |
| `/api/v1/file-permissions/**` | 1 件・F05.5 内部 | internal | 既存・🟡 候補 |
| `/api/v1/shared-links/**` | 1 件・F05.5 内部 | internal | 既存・🟡 候補 |

本足軽コミットでは **5 件** を `exclusions.yml` に追記:
- `/api/v1/scopes/**`
- `/api/v1/endpoints/**`
- `/api/v1/incoming/**`
- `/api/v1/ical/**`
- `/api/v1/mail-tracking/**`
- `/api/v1/pdf-signatures/**`
- `/api/v1/supported-locales/**`

---

### D. 🐞 スキャナ偽陽性パターン分析

#### D-1. 同一 (method, path) の設計書内重複検出

`F04.3_push_notification.md` の `GET /api/v1/notifications` が 203, 221 行で 2 回記述されているなど、
**同一エンドポイントが設計書内で複数箇所に記載** されている場合、スキャナが各行を別件としてカウント。

影響件数: 約 80 件（全 740 件中の約 11%）。
v3 で「設計書内同一 (method, path) を 1 件に集約」する正規化が入ったはずだが、
v4 でも残存している。スキャナ側課題として記録（v5 改修候補）。

#### D-2. 旧 URL prefix `/api/incidents`, `/api/signage`, `/api/maintenance-schedules`

実装側 prefix が `/api/v1/` を持たない `/api/incidents`, `/api/signage`, `/api/maintenance-schedules` のため、
スキャナが `/api/v1/incidents/...` の設計書記載と突合できない。

- F07.6_incident_management.md → 設計書は `/api/v1/incidents/...` 記載 / 実装は `/api/incidents/...`
- F09.10_digital_signage.md / F09.1_signage.md → 同上
- F07.3_equipment.md → 同上（`/api/maintenance-schedules/`）

**現状**: `exclusions.yml` に既に `/api/incidents/**`, `/api/signage/**` がレガシー扱いで登録済み。
**根治治療**: コントローラ側を `/api/v1/incidents/...` にリネームする PR が必要（別足軽案件）。
本足軽は記録のみ。

影響件数: 約 30 件（incidents 25 + signage 3 + maintenance 数件）。

#### D-3. インライン記法・コードブロック内重複

例: `POST /api/v1/activities` が F06.1 / F06.4 の両設計書で言及（リソース共有）。
複数設計書ファイルにまたがる重複は v4 でも一括集約されていない。

影響件数: 約 60 件。

#### D-4. リクエストパラメータ表記揺れ

例: `GET /api/v1/notifications?filter=unread` が path 抽出時に query を含んだまま正規化されていないケース。
v3 で `_strip_query` 適用されているはずだが、インライン記法（コードブロック外）で漏れる場合あり。

影響件数: 約 15 件。

---

## 個別ドメイン詳細メモ（高優先度のみ）

### /api/v1/auth/* (2 件: 実装あり・設計なし)

| メソッド | パス | Controller |
|---|---|---|
| GET | `/api/v1/auth/csrf-token` | AuthController |
| POST | `/api/v1/auth/refresh` | AuthController |

→ **🟡 F01.1_auth.md 追記対象**（CSRF トークン取得・トークンリフレッシュ）。
F01.1 既存設計書には 27 件の一致記述があり、不足 2 件のみ追記が必要。

### /api/v1/notifications/* (5 件: 設計あり・実装なし)

実装は `NotificationController` (`/api/v1/notifications`) に存在。
設計書側で `GET /api/v1/notifications`, `PATCH /read`, `/read-all`, `/snooze`, `/unread` が
複数フェーズ間で重複記載されているため**全件 🐞 偽陽性**。
真の漏れ無し。

### /api/v1/signage/* (3 件: 設計あり・実装なし)

実装は `/api/signage/screens` (`/api/v1/` なし) prefix。`exclusions.yml` に既登録。
**🐞 偽陽性**。

### /api/v1/incidents/* (25 件: 設計あり・実装なし)

実装は `/api/incidents` prefix（`/api/v1/` なし）。F07.6 設計書は `/api/v1/incidents/...` 記載。
**根治治療案**: コントローラを `/api/v1/incidents` にリネーム。
**短期対処**: 既に `exclusions.yml` で `/api/incidents/**` がレガシー登録済み。
本足軽の責務範囲外（IncidentController リネームは別 PR で対応）。

### /api/v1/safety-checks/* (12+8=20 件)

実装あり: `SafetyCheckController` (`/api/v1/safety-checks`), `SafetyTemplateController`, `SafetyFollowupController`, `SafetyAdminController`。
設計書 (F03.6) と命名揺れ・サブパス展開の差異あり。
**🟡 設計書側で実装に合わせて追記が必要**（10 件程度）。

---

## 実機改修コミット範囲

本足軽が本 commit で実施する範囲:

### 1. exclusions.yml に 7 パターン追記

- `/api/v1/scopes/**`
- `/api/v1/endpoints/**`
- `/api/v1/incoming/**`
- `/api/v1/ical/**`
- `/api/v1/mail-tracking/**`
- `/api/v1/pdf-signatures/**`
- `/api/v1/supported-locales/**`

### 2. F01.1_auth.md に CSRF / refresh エンドポイント 2 行追記

### 3. F08.8_repair_longterm_dashboard.md の §4 API 仕様で
   `/api/v1/repair-plan/...` 表記を `/api/v1/{scopeType}/{scopeId}/repair-plan/...` に修正

### 4. 本 triage_log/misc.md を新規作成（本ファイル）

---

## 残課題（引き継ぎ）

本足軽が時間制約により未対応とした事項を以下に列挙する。
**後続足軽が個別ドメイン PR で対応**することを推奨。

1. **F05.1_bulletin_board.md / F05.2_circular.md / F05.4_survey_vote.md / F05.5_file_sharing.md / F05.6_workflow_approval.md / F05.7_form_builder.md**:
   設計書記載パスを `/api/v1/{scopeType}/{scopeId}/<resource>/...` に修正（各 15〜30 件）

2. **F09.8_corkboard.md**: corkboards 17 件のスコープ prefix 追記

3. **F09.16_residence_status.md**: dwelling-units 23 件・residence-status 13 件のスコープ prefix 追記

4. **F13.1_short_term_job_matching.md**: jobber-invitations / job-disputes / job-payments / no-show-records に 🔵 マーカ付与（Phase 5 未着工）

5. **F04.4_social_profiles.md**: `/api/v1/sns/*` `/api/v1/social-profiles/*` 16 件を `/api/v1/social/*` にリネーム反映

6. **F08.8_repair_longterm_dashboard.md**: Phase 6 完了の文脈と整合させ、設計書 §4 表記を全面的に実装と同期

7. **F03.13 出席バッチ系 `/api/v1/admin/batch/attendance/*`** （admin 足軽の引き継ぎ）

8. **F17 village 系（並列足軽が対応中）**

9. **IncidentController / SignageScreenController の `/api/v1/` 化リネーム**:
   レガシー除外パターンを撤廃するために必要。要設計・要バックエンド改修・要フロント影響調査。
   別軍議案件。

10. **スキャナ v5 改修案件**:
    - 設計書内同一 (method, path) を 1 件に集約（D-1: 約 80 件削減期待）
    - 複数設計書間の同一 (method, path) を集約（D-3: 約 60 件削減期待）
    - インライン記法のクエリ文字列・末尾スラッシュ正規化漏れ修正（D-4: 約 15 件削減期待）
    - V4-1 逆引きホワイトリストの拡張検討（リソース系: bulletin, circulations, repair-plan, workflows 等を追加すれば、🐞 約 200 件が自動解消する可能性）

---

## 難しい事例

### 事例 1: V4-1 逆引きホワイトリストの拡張ジレンマ

ホワイトリスト拡張で `bulletin`, `repair-plan`, `surveys`, `workflows` 等のリソース系を追加すると、
スキャナ偽陽性は約 200 件減るが、**「同じ resource 名で teams scope と organizations scope の API が
別物として実装されているケース」を見落とす** リスクが出る。

例:
- `/api/v1/teams/{teamId}/circulations` (チーム内回覧)
- `/api/v1/organizations/{orgId}/circulations` (組織内回覧)
- 両者は API 仕様が一致するとは限らない（権限・対象範囲が異なる）

→ **暫定対処**: ホワイトリスト拡張は行わず、設計書側にスコープ prefix を明記する方針で統一。
本足軽の選択: 「設計書を実態に合わせる」🟡 方針。

### 事例 2: /api/v1/sns/* と /api/v1/social-profiles/* の重複

F04.4 設計書には旧 `/api/v1/sns/*` および `/api/v1/social-profiles/*` の記載が混在しているが、
実装は `/api/v1/social/*` 配下に統合済み（`FollowController` `/api/v1/social/follows`）。
設計書 3 系統を `/api/v1/social/*` に一本化する PR が必要。
本足軽は記録のみで、F04 ドメイン担当足軽に委譲。

---

## 検証

- [x] triage_log/misc.md 作成完了
- [x] exclusions.yml に 7 パターン追記
- [x] F01.1_auth.md に CSRF/refresh 追記
- [ ] F08.8 設計書スコープ prefix 修正（本 commit）
- [ ] スキャナ再実行確認（時間制約上、後続足軽 commit 後にまとめて実施）
