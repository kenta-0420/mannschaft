# AccountPurge §9.6 最後の ADMIN 退会時の組織オーナー継承 陣立て書（addendum）

> 起票日: 2026-05-18
> 担当: 家老（Plan agent）
> ステータス: 🟡 設計段階（実コード変更なし／マスター御裁可待ち）
> 親設計書: [`account_purge_cross_domain_refactor.md`](./account_purge_cross_domain_refactor.md) §9.6 の専用深堀
> 親設計書ステータス: main マージ済（commit 896c5f739 / 2026-05-18）
> 範囲: 本軍議は §9.6 のみ。他の §9 項目（9.7 監視・9.8 event-pool 等）には立ち入らない

---

## §1. 背景と問題定義

### 1.1 親設計書 §9.6 の論点要約

親設計書 `account_purge_cross_domain_refactor.md` §9.6 で提起された論点を引用整理する:

- `RoleService#removeMember`（[`RoleService.java:137-150`](../../backend/src/main/java/com/mannschaft/app/role/service/RoleService.java)）の `checkLastAdmin` ガード（同 §141-142）が「最後の ADMIN を削除しようとすると `BusinessException(ROLE_004)` を投げる」設計
- 親設計書 Phase B-1 で新設予定の `RolePurgeEventListener` が、退会者を `removeMember(scopeId, scopeType, userId)` で除名しようとしたとき、退会者が最後の ADMIN だった組織で例外発生 → `AccountPurgedEvent` の listener が落ち、purge が部分失敗する
- 親設計書の推奨は「B（UX で後任 ADMIN 指名 / arch化を選択）+ A（暫定の `removeMemberWithoutAdminCheck` を安全弁として実装）の併用」
- ただし B が UX 開発・法務調整を要するため間に合うか不明であり、「A の暫定実装と段階移行をどう設計するか」が本軍議の中心論点

### 1.2 現状の宙ぶらりん状態（重要 — 既に潜在矛盾あり）

実コード調査の結果、**本リファクタ着手前の現状でも、最後の ADMIN が退会した組織に矛盾が発生している**:

| フェーズ | 何が起きる | 矛盾の有無 |
|---|---|---|
| 退会受付（即時匿名化） | `UserService#withdrawUser`（[`UserService.java:478-500`](../../backend/src/main/java/com/mannschaft/app/auth/service/UserService.java)）が SYSTEM_ADMIN のみブロック（`checkNotLastSystemAdmin`、`UserService.java:508`）。**組織 ADMIN / チーム ADMIN は退会できる**。`user_roles` 行はこの時点では削除されない（即時匿名化フェーズは `user.anonymize() + softDelete()` のみ）| 🟡 user は匿名化済だが ADMIN ロールは残存。組織画面では「@deleted_user_xxx (ADMIN)」のような表示になる |
| 退会から 30 日後（物理削除バッチ） | `AccountPurgeService#purgeUser`（[`AccountPurgeService.java:111-222`](../../backend/src/main/java/com/mannschaft/app/gdpr/service/AccountPurgeService.java)）が `userRoleRepository.deleteAllByUserId(userId)`（同 §164）で **`checkLastAdmin` をバイパスして直接 DELETE**。残った組織は **ADMIN が 0 人** になる | 🔴 既に静かに発生中。ROLE_004 例外は飛ばないが、組織が ADMIN 不在で取り残される |
| F15.4 Phase 4 (`teams.member_count`) | `MembershipChangedEvent(REMOVED)` 未発火で 24h まで集計ズレ | 🟡 親設計書 §3.5 で根治予定 |

**ポイント:** 親設計書 Phase B-1（`RolePurgeEventListener` が `RoleService#removeMember` を呼ぶ）に移行した瞬間、現状静かに発生していた「ADMIN 不在で残る組織」問題が **`BusinessException(ROLE_004)` で顕在化** する。つまり本問題は親設計書が掘り起こす技術的負債ではなく、**親設計書の正しい設計によって既存の潜在不整合が明示化される構造**。

CLAUDE.md「障害対応の原則」§2「症状を隠さない」の精神に従えば、現状の `deleteAllByUserId` バイパスは技術的負債そのものであり、本軍議で根治する必要がある。

### 1.3 村ドメイン（F17.1）の先行事例 — 後夜次バッチによる承継

`village` ドメインでは既に同種問題への対応が実装済（[`VillageHeadmanSuccessionBatchService.java`](../../backend/src/main/java/com/mannschaft/app/village/batch/VillageHeadmanSuccessionBatchService.java)、F17.1 Phase 1 B11）:

- 毎日 UTC 03:00 に全村巡回
- HEADMAN がユーザー退会済（`users.deleted_at NOT NULL`）の村について
  1. 最古参 ELDER → HEADMAN
  2. ELDER 不在なら最古参 VILLAGER → HEADMAN
  3. 誰もいなければ村を archive

**示唆:** organization / team でも「夜次バッチで承継させる」案（C 案系）の先行事例がある。村は HEADMAN 単独の自治体メタファだが、組織/チームの ADMIN は複数許容で性質が異なる点に注意。

### 1.4 既存資産 — `transferOwnership` API は既に実装済

実コード調査で判明:

| エンドポイント | 場所 | 内容 |
|---|---|---|
| `POST /api/v1/teams/{id}/transfer-ownership` | [`TeamController.java:389-395`](../../backend/src/main/java/com/mannschaft/app/team/controller/TeamController.java) | 現オーナー → 対象ユーザーに ADMIN 譲渡 |
| `POST /api/v1/organizations/{id}/transfer-ownership` | [`OrganizationController.java:390-395`](../../backend/src/main/java/com/mannschaft/app/organization/controller/OrganizationController.java) | 同上、ORG 用 |
| `RoleService#transferOwnership(scopeId, scopeType, currentUserId, targetUserId)` | [`RoleService.java:242-291`](../../backend/src/main/java/com/mannschaft/app/role/service/RoleService.java) | 現オーナーを MEMBER に降格 + 対象ユーザーを ADMIN に昇格 + `MembershipChangedEvent(CHANGED)`×2 |

**示唆:** 案 B（UX で後任 ADMIN 指名）の **バックエンドは既に完成しており、追加実装は退会フロー画面の組み込みのみ**。これは推奨案の実装コストを大きく押し下げる発見。

---

## §2. 現状把握サマリ

### 2.1 退会フロー全体像

| フェーズ | 入口 | API/コード | 副作用 |
|---|---|---|---|
| 退会画面 | [`frontend/app/pages/settings/account.vue`](../../frontend/app/pages/settings/account.vue)（行 273）→ [`SettingsDeleteAccountSection.vue`](../../frontend/app/components/settings/SettingsDeleteAccountSection.vue) → [`SettingsDeletionPreviewDialog.vue`](../../frontend/app/components/settings/SettingsDeletionPreviewDialog.vue) | `getDeletionPreview()` で `GET /api/v1/account/deletion-preview`（[`GdprController.java:92-99`](../../backend/src/main/java/com/mannschaft/app/gdpr/controller/GdprController.java)）| 削除されるデータ件数を表示。**現状は ADMIN 不在になる組織の警告は出ない** |
| 退会 API 呼び出し | `handleDeleteAccount` 内 `DELETE /api/v1/users/me`（`account.vue:12`）| [`UserController.java:122-125`](../../backend/src/main/java/com/mannschaft/app/auth/controller/UserController.java) | 内部で `requestWithdrawal()` を呼ぶ（**`withdrawUser()` ではない**）|
| バックエンド受付 | `UserService#requestWithdrawal`（[`UserService.java:408-438`](../../backend/src/main/java/com/mannschaft/app/auth/service/UserService.java)）| SYSTEM_ADMIN のみブロック / Password 確認 / `user.requestDeletion()` で `deleted_at` セット + Refresh Token 失効 + `WithdrawalRequestedEvent` 発火 | **組織 ADMIN は退会できる**。`user_roles` は残存。30日以内に `/api/v1/account/cancel-withdrawal` で取消可能 |
| 即時匿名化 | `UserService#withdrawUser`（同 §478）| 🔴 **休眠コード（殿の検分で確定 2026-05-18）**。`withdrawUser()` を呼ぶ呼出元はリポジトリ内に**一切存在しない**（`WithdrawalRequestedEvent` 購読者は `WithdrawalStripeHandler` と `AuditLogEventListener` のみで、いずれも `withdrawUser` を呼ばない）。`UserAnonymizedEvent` 発火点は `UserService.java:497` の **1 箇所のみ** = `withdrawUser` 内 = 発火実績なし | 結果として 9 ドメインの `*AnonymizationEventListener`（auth / favorite / notification / schedule / social / village / weather / scopefolder / chart）は**全休眠中**。退会受付から 30 日間、個人情報は丸見えのまま **🔴 重大な既存隠れバグ**（§10.10 で別軍議起票）|
| 30 日後物理削除 | `AccountPurgeService#purgeUser`（[`AccountPurgeService.java:111`](../../backend/src/main/java/com/mannschaft/app/gdpr/service/AccountPurgeService.java)）| `user_roles.deleteAllByUserId` で全 ADMIN ロール強制 DELETE → `users.delete` 物理削除 | 本軍議の対象。親設計書 Phase B-1 で `AccountPurgedEvent` → `RolePurgeEventListener` 経由に置き換える計画 |

### 2.2 `checkLastAdmin` ガードの実装範囲

[`RoleService.java:341-349`](../../backend/src/main/java/com/mannschaft/app/role/service/RoleService.java):

```java
private void checkLastAdmin(Long scopeId, String scopeType, UserRoleEntity current) {
    RoleEntity currentRole = roleRepository.findById(current.getRoleId()).orElse(null);
    if (currentRole != null && "ADMIN".equals(currentRole.getName())) {
        long adminCount = countByRoleInScope(scopeId, scopeType, current.getRoleId());
        if (adminCount <= 1) {
            throw new BusinessException(RoleErrorCode.ROLE_004);
        }
    }
}
```

| 観点 | 実装内容 |
|---|---|
| 対象ロール名 | `"ADMIN"` 文字列ハードコード（`DEPUTY_ADMIN` や `OWNER` は対象外）|
| 対象スコープ | `TEAM` と `ORGANIZATION` の 2 種（`SYSTEM_ADMIN` のプラットフォームスコープは別ガード `checkNotLastSystemAdmin` で対応）|
| 呼び出し元 | `changeRole`（§95-100）/ `removeMember`（§142）/ `leaveScope`（§162）の 3 箇所 |
| `assignRole` | チェックなし（追加だから OK）|
| `transferOwnership` | **自前で「現ユーザーが ADMIN かどうか」と「現ユーザー ≠ 対象ユーザー」のみチェックし、`checkLastAdmin` は呼ばない**。譲渡完了後は新 ADMIN が存在するため正当 |
| エラーコード | `ROLE_004 = "最後の管理者を除名・変更できません"`（Severity.WARN）|

### 2.3 ADMIN 相当ロールの整理

| スコープ | ADMIN 相当 | 最小要件 | 退会ガード |
|---|---|---|---|
| プラットフォーム | `SYSTEM_ADMIN` | 1 名以上必須（[`UserRoleRepository.java:countSystemAdmins`](../../backend/src/main/java/com/mannschaft/app/role/repository/UserRoleRepository.java)）| `UserService#checkNotLastSystemAdmin`（`UserService.java:508`）で退会 API がブロック |
| 組織 | `ADMIN` のみ（`DEPUTY_ADMIN` は副）| 1 名以上推奨（仕様上は 0 でも DB 制約に反しない）| **退会 API でブロックなし**。`RoleService#removeMember/leaveScope` のみ ROLE_004 で拒否 |
| チーム | `ADMIN` のみ | 同上 | 同上 |
| 村（F17.1）| `HEADMAN` | 1 名以上（HEADMAN 不在は archive 対象）| `VillageHeadmanSuccessionBatchService` で夜次自動承継 |

**重要な発見:** **退会 API（`requestWithdrawal`）は組織/チームの最後の ADMIN をブロックしていない**。一方で「管理画面から自分を除名」する操作（`removeMember`）と「自主退会」（`leaveScope`）はガードされる。これは設計上の非対称であり、§1.2 の「ADMIN 不在組織が静かに発生」の直接原因。

### 2.4 影響を受ける組織の規模感（推定）

ユーザー実態データへの直接アクセスは行わない（agent ポリシー）が、設計判断に必要な範囲で推定:

- 1 ユーザーあたり所属組織数: 個人ユーザー（家族チーム + 自分の組織 1〜2）から自治会・管理組合 ADMIN まで様々。**1〜10 件のレンジが大半**
- 「最後の ADMIN かつ他メンバー 1 人以上」のケース頻度: 自治会理事退任など「組織は残すが自分は抜けたい」シナリオは現実的に発生する。**中規模リリース後で月数件〜数十件オーダ** と想定
- 「最後の ADMIN かつ他メンバー 0 人」: 個人組織の総撤退ケース。**月十数件〜数十件オーダ** と想定（個人ユーザーの正常退会フローの大半）

→ 「他メンバー 0 人」ケースは「組織ごと archive」で機械処理して問題ない。「他メンバー 1 人以上」ケースは UX 介入が必要。

---

## §3. 設計案比較

親設計書 §9.6 提示の A〜D 案に加え、家老裁量で E〜G を追加。

### 案 A: `removeMemberWithoutAdminCheck` の安全弁実装

**UX 流れ:** ユーザー視点では現状と変わらない（退会画面でそのまま退会できる）。最後の ADMIN だった組織は ADMIN 不在で残る。

**バックエンド処理:**
1. `RoleService` に `removeMemberWithoutAdminCheck(scopeId, scopeType, userId)` を新設（既存 `removeMember` から `checkLastAdmin` 呼び出しのみ削除した版）
2. 親設計書 Phase B-1 の `RolePurgeEventListener` がこれを呼ぶ
3. 各組織で ADMIN が 0 人になったら運用通知（PagerDuty / Slack）
4. ADMIN 不在組織は手動で「他メンバーを ADMIN 昇格」または「組織 archive」する運用

**失敗時のフォールバック:** ADMIN 不在組織が放置されないよう、夜次バッチで `users.purged_at` 起点に検出し運用通知を発火する。

**法務・運用観点:** ADMIN 不在組織が長期間放置されると「組織責任者不在」状態となり、自治会・管理組合の場合は法的責任の所在が曖昧化する。運用負荷が線形に積み上がる。

**実装コスト:** 1 PR（`removeMemberWithoutAdminCheck` 追加 + UT 2 件 + Listener 側修正）。**最小コスト**。

**B 並走前提:** B-1〜B-3（後述）の前に先行リリースして安全弁とできる。B 完成後も「他メンバー 0 人ケース」では引き続き有効。

### 案 B: 退会フロー画面で「後任 ADMIN 指名 / 組織 archive」を選択させる UX

**UX 流れ:**
1. ユーザーが `settings/account.vue` → 「アカウント削除」ボタンを押下
2. `DELETE /api/v1/account/deletion-preview` のレスポンスを `SettingsDeletionPreviewDialog` で表示
3. **追加**: レスポンスに「あなたが最後の ADMIN になっている組織/チーム一覧」を含める（`lastAdminScopes: [{scopeType, scopeId, scopeName, otherMembersCount}]`）
4. 1 件以上ある場合、Dialog の「削除する」ボタンを無効化し、組織ごとに「後任 ADMIN を指名する」/「組織を archive する」のラジオを提示
5. 「後任 ADMIN を指名」選択時はメンバー検索コンポーネントで対象選択 → 内部で `POST /api/v1/{scope}/{id}/transfer-ownership` を呼ぶ（**既存 API**）
6. 「組織 archive」選択時は内部で **`PATCH /api/v1/{scope}/{id}/archive`** を呼ぶ（**既存 API 確認済 / 偵察 2026-05-18**）
7. すべての lastAdmin 組織を処理完了したら「削除する」ボタンが活性化し、`DELETE /api/v1/users/me` を呼べる

**バックエンド処理:**
1. `GdprController#buildDeletionPreview` を拡張し、`UserRoleRepository.findLastAdminScopes(userId)` を追加（新規メソッド: 「自分が ADMIN かつスコープ内 ADMIN 数 = 1」のスコープを返す）
2. `UserService#requestWithdrawal` の冒頭に「lastAdmin スコープが残っていないか」のサーバ側ガードを追加（フロント素通り防止）。残っていれば新エラーコード `AUTH_xxx = "未処理の最後 ADMIN 組織があります"` を返す
3. **ORG/TEAM の archive API は既存（偵察確認済 2026-05-18）**:
   - `PATCH /api/v1/organizations/{id}/archive`（`OrganizationController.java:159` / `OrganizationService#archiveOrganization` 行 171）
   - `PATCH /api/v1/organizations/{id}/unarchive`（同 167 / 184）
   - `PATCH /api/v1/teams/{id}/archive`（`TeamController.java:158` / `TeamService#archiveTeam` 行 199）
   - `PATCH /api/v1/teams/{id}/unarchive`（同 166 / 216）
   - Entity 側に `archive() / unarchive()` メソッド、`organizations.archived_at` / `teams.archived_at` カラム既存
4. 既存 `transferOwnership` API はそのまま再利用
5. **指名先承諾フローは入れない**（既存 `transferOwnership` も同意なしの即時昇格設計）→ §10.11 議論事項
6. **archive 後の不可逆性**: 残メンバー 0 で archive → 全員退会済になると `unarchive` を呼べる人が不在に → SYSTEM_ADMIN 介入手順を運用整備（§10.12）

**失敗時のフォールバック:** UX 内で対応完了させる前提のため、フォールバックはサーバ側ガードのみ。それでも素通りした場合は案 A の安全弁が拾う。

**法務・運用観点:** ユーザーに明示的に組織責任の引継ぎを選ばせるため、自治会・管理組合の組織責任が明確化する。GDPR 削除権との整合: 「削除を阻害」と見える可能性があるが、「30日後に物理削除」の保証は変わらないため法的問題なし。

**実装コスト:** **4 PR**（β-4 archive API 新規追加が偵察確認で不要となり 1 PR 削減）。
- B-1: バックエンド `findLastAdminScopes` + `buildDeletionPreview` 拡張 + UT
- B-2: `UserService#requestWithdrawal` の lastAdmin ガード追加 + 新エラーコード
- B-3: フロント `SettingsDeletionPreviewDialog` 拡張 + 組織別承継 UI コンポーネント新規 + 既存 Dialog の i18n 化（現状日本語直書きのため、Phase β-3 でついでに i18n 化）+ 6 言語投入（初版は ja 値）
- B-4 ~~ORG/TEAM archive API 追加~~ → **削除（既存実装あり）**。代わりに「他メンバー 0 人ケース」の自動 archive バッチ `OrganizationHeadlessArchiveBatchService` / `TeamHeadlessArchiveBatchService`（F17.1 `VillageHeadmanSuccessionBatchService` を 1:1 流用、各約 200 行）を Phase β-4 として実装
- B-5: E2E テスト追加（lastAdmin あり / なし / 全件処理して削除成功）

### 案 C: 自動承継（最古参メンバーを ADMIN 昇格）

**UX 流れ:** ユーザー視点では現状と変わらない（退会画面でそのまま退会できる）。**プレビュー画面で「組織 X の ADMIN は自動的に Y さん（最古参メンバー）に承継されます」を表示**。

**バックエンド処理:**
1. `UserService#requestWithdrawal` の冒頭で `findLastAdminScopes(userId)` を呼び、各スコープで「最古参の non-ADMIN メンバー」を選定
2. 同一トランザクション内で `transferOwnership(scopeId, scopeType, userId, oldestMember.userId)` を呼ぶ
3. 他メンバー 0 人なら組織 archive
4. 退会監査ログに「承継先 user_id」を記録
5. 承継された側にプッシュ通知/メール「あなたが組織 X の ADMIN に自動承継されました」

**失敗時のフォールバック:** ない。完全自動。

**法務・運用観点:** **法的に最大の懸念**。本人同意なく ADMIN 権限を付与する設計は、特に管理組合・自治会で「望まない法的責任」を負わせる可能性。マスター御裁可必須事項。

**実装コスト:** 3 PR（バックエンド自動承継ロジック + プレビュー表示 + 通知）。

### 案 D: 「最後の ADMIN は退会できない」を規約化（退会拒否）

**UX 流れ:** プレビュー画面で「あなたは N 件の組織の最後の ADMIN です。退会前に組織を整理してください」と表示し、削除ボタンを無効化。ユーザーが手動で組織画面に行き `transferOwnership` または `archive` を実行 → プレビューを再読込 → 削除可能になる。

**バックエンド処理:** `UserService#requestWithdrawal` の冒頭で `findLastAdminScopes(userId)` をチェックし、1 件以上あれば即座に新エラーコードで 409 Conflict。

**失敗時のフォールバック:** ユーザーは退会できない。組織整理を完了するまで永続的に退会不可。

**法務・運用観点:** GDPR 削除権との緊張関係。「削除を阻害している」と解釈される可能性。Art. 17 (3)(b) 「法的義務の遵守」を根拠に正当化可能だが、解釈論争のリスクあり。

**実装コスト:** 1〜2 PR（最も実装が軽い）。

### 案 E（新規）: 「保留型退会」— 後任指名猶予を 7 日与える

**UX 流れ:**
1. 退会受付時に最後 ADMIN 組織があれば「退会受付完了。7 日以内に後任を指名してください。未指名のままだと組織は自動 archive されます」と通知
2. 7 日間は「退会受付中・組織未処理」状態となり、ユーザーは引き続きログイン可能（または ADMIN 操作のみ可能）
3. 7 日経過時点で `LastAdminDeadlineBatchService` が走り、後任指名済なら通常退会フローへ、未指名なら自動 archive + 退会フロー継続

**バックエンド処理:** 新テーブル `pending_admin_succession`（userId, scopeType, scopeId, deadlineAt, succeededByUserId NULL, status）+ バッチ。

**失敗時のフォールバック:** 7 日経過時点の強制 archive が最終防衛線。

**法務・運用観点:** 案 B と案 C のハイブリッドで、ユーザーに選択肢を与えつつ最終的には機械処理。GDPR との整合性も良好（30 日物理削除タイムリミット内に収まる）。

**実装コスト:** 6〜8 PR。新テーブル DDL + Entity + Batch + バックエンドガード + フロント UI + 通知 + E2E。**最大コスト**。

### 案 F（新規）: スコープ別方針分割

**UX 流れ:** ORG は厳格に B（明示的後任指名必須）、TEAM は緩く A（安全弁のみ、ADMIN 不在許容）、VILLAGE は F17.1 既存夜次バッチ任せ。

**バックエンド処理:** スコープ別に分岐するロジックを `RolePurgeEventListener` 内に集約。

**法務・運用観点:** ORG（自治会・管理組合）は法的責任が重いため厳格に、TEAM（家族・友達グループ）は軽く、というメリハリは現実的。

**実装コスト:** 案 A + 案 B の合算 + スコープ別分岐ロジック。5〜7 PR。

### 案 G（新規）: 規約変更で「最後の ADMIN は退会できない」を明示化（案 D の規約強化版）

実装は案 D と同等。規約面で「ADMIN 引退時は事前に後任指名を完了する責任がある」と明文化して法的責任を利用者側に寄せる。

### 比較マトリクス

| 案 | UX 摩擦 | バックエンド実装コスト | 法務リスク | GDPR 整合性 | 運用負荷 | ADMIN 不在許容 | 既存資産活用 |
|---|---|---|---|---|---|---|---|
| A | 低（ユーザー無自覚）| **最小**（1 PR）| 中（ADMIN 不在組織放置）| ◎ | 高（手動是正多発）| 許容 | ─ |
| B | 中（追加選択 UI）| 中（4〜5 PR）| **小** | ◎ | **低** | 不許容 | `transferOwnership` 既存活用 ◎ |
| C | 低（自動）| 中（3 PR）| **大**（本人同意なき権限譲渡）| ◎ | 低 | 不許容 | `transferOwnership` 既存活用 |
| D | **大**（退会できない）| 小（1〜2 PR）| 中（GDPR 阻害論争）| △ | 低 | 不許容 | ─ |
| E | 中（時限通知）| **大**（6〜8 PR + 新テーブル）| 小 | ○（7 日内なので OK）| 中 | 不許容 | ─ |
| F | 案 A + B 混在 | 大（5〜7 PR）| 小 | ◎ | 中 | TEAM のみ許容 | `transferOwnership` 既存活用 |
| G | 案 D 同等 | 案 D 同等 | 中 | △ | 低 | 不許容 | ─ |

---

## §4. 推奨案と理由

### 4.1 推奨: 案 B + 案 A の併用（親設計書 §9.6 推奨と一致 + 詳細化）

**理由:**

1. **既存 API `transferOwnership` がそのまま使えるため、案 B のバックエンド実装コストが当初想定より大幅に低い**。フロント側の組織選択 UI と「lastAdmin スコープ検出」の `UserRoleRepository` 追加メソッドのみで完結
2. **既存 `SettingsDeletionPreviewDialog` への拡張で UX フローが自然**。新規モーダル追加ではなく、既存プレビュー Dialog に「警告ブロック」「未処理スコープ一覧」「個別承継アクション」を追記する形で実装可能
3. **法的に最も安全**。本人の意思で承継先を選ぶため、自治会・管理組合の組織責任の引継ぎが明確になる
4. **案 A の `removeMemberWithoutAdminCheck` を先行リリースして安全弁にすれば、案 B の UX 実装が間に合わなくても親設計書 Phase B-1 をブロックしない**
5. **「他メンバー 0 人ケース」は案 B でも UX を出す必要がない**（自動 archive 一択）ので、UI は「他メンバー 1 人以上ケース」のみに絞れる

### 4.2 不採用とした他案の理由

| 不採用案 | 理由 |
|---|---|
| C | 本人同意なき ADMIN 権限譲渡は法的責任の押し付け。自治会・管理組合では特に危険 |
| D | GDPR Art.17 削除権との緊張。「永続的に退会できない」状態の発生は避けたい |
| E | 実装コスト過大。30 日物理削除タイムリミットがあるため猶予期間設計は冗長 |
| F | スコープ別方針分割は概念的に複雑で説明コストが高い。実害ベースで段階移行する方が良い |
| G | 規約だけで実装は案 D と同等のため別途採用する利点がない |

### 4.3 Phase 分け（推奨）

```
Phase α（暫定安全弁・親設計書 Phase B-1 と同時）:
  ├─ 案 A の removeMemberWithoutAdminCheck を実装
  └─ RolePurgeEventListener がこれを呼ぶ
  → この時点でも ADMIN 不在組織は発生し得るが、現状（直接 deleteAllByUserId）と同等の挙動なので退行なし

Phase β（UX 本実装・別軍議で実装着手）:
  ├─ findLastAdminScopes Repository メソッド追加
  ├─ deletion-preview API 拡張
  ├─ requestWithdrawal にサーバ側ガード追加
  ├─ SettingsDeletionPreviewDialog UI 拡張
  ├─ ORG/TEAM archive API（未実装なら追加）
  ├─ i18n 6 言語追加
  └─ E2E テスト

Phase γ（最終整理）:
  ├─ removeMemberWithoutAdminCheck の利用箇所監視（PagerDuty メトリクス）
  ├─ 「他メンバー 0 人」ケースを自動 archive に統一
  └─ Phase β リリース 30 日後、安全弁の発火頻度が 0 件相当なら removeMemberWithoutAdminCheck を「他メンバー 0 人かつ archive 失敗時のみ」用に縮退
```

---

## §5. 推奨案の詳細設計

### 5.1 退会フロー画面の Mock（テキスト記述）

```
┌─────────────────────────────────────────────────────────────────┐
│ アカウント削除の確認                                              │
├─────────────────────────────────────────────────────────────────┤
│ ⚠️ この操作は取り消せません。本当にアカウントを削除しますか？      │
│                                                                  │
│ ┌─────────────────────────────────────────────────────────┐     │
│ │ 🔴 退会前に処理が必要な組織が 2 件あります                │     │
│ │                                                          │     │
│ │ あなたが最後の管理者になっている組織は、退会前に          │     │
│ │ 後任の管理者を指名するか、組織を凍結する必要があります。  │     │
│ └─────────────────────────────────────────────────────────┘     │
│                                                                  │
│ ┌─────────────────────────────────────────────────────────┐     │
│ │ 組織「○○マンション管理組合」（他メンバー 12 名）         │     │
│ │ ○ 後任の管理者を指名する     [選択: 山田太郎 ▼]          │     │
│ │ ○ 組織を凍結する                                          │     │
│ │ [処理する]                                                │     │
│ └─────────────────────────────────────────────────────────┘     │
│                                                                  │
│ ┌─────────────────────────────────────────────────────────┐     │
│ │ チーム「家族グループ」（他メンバー 0 名）                  │     │
│ │ ⓘ 他にメンバーがいないため、自動で凍結されます            │     │
│ │ [自動凍結する]                                            │     │
│ └─────────────────────────────────────────────────────────┘     │
│                                                                  │
│ ──── すべての組織を処理すると下記が活性化します ────              │
│                                                                  │
│ 削除されるデータ:                                                │
│   charts: 5 件、chatMessages: 142 件...                          │
│                                                                  │
│ パスワード: [        ]                                            │
│                                                                  │
│ [キャンセル]                       [削除する（無効化中）]         │
└─────────────────────────────────────────────────────────────────┘
```

すべての lastAdmin スコープを処理完了後、削除ボタンが活性化し、Dialog が再読込されて通常の削除確認画面に遷移する。

### 5.2 バックエンド処理シーケンス（テキスト）

```
[退会画面オープン]
  Frontend → GET /api/v1/account/deletion-preview
  GdprController#getDeletionPreview
    → buildDeletionPreview(userId)
        ├─ chartCount / chatCount / paymentCount（既存）
        └─ lastAdminScopes = userRoleRepository.findLastAdminScopes(userId)  ★ 新規
            ↳ ネイティブ SQL: 「user_id=:uid が ADMIN かつ scope 内 ADMIN 数=1」のスコープを返す
    → DeletionPreviewResponse に lastAdminScopes 追加
  Frontend ← { dataSummary, lastAdminScopes: [...], warnings: [...] }

[ユーザーが「後任を指名」を選択 → 「処理する」]
  Frontend → POST /api/v1/organizations/{id}/transfer-ownership  ★ 既存 API
    body: { targetUserId }
  RoleService#transferOwnership で承継完了（既存ロジック）

[ユーザーが「組織を凍結」を選択]
  Frontend → POST /api/v1/organizations/{id}/archive  ★ 未実装なら追加
  ※ ORG/TEAM の archive API 有無は Phase β 着手時に再確認

[全 lastAdmin 処理完了後、削除ボタン活性化]
  Frontend → DELETE /api/v1/users/me
  UserController#requestWithdrawal
    → UserService#requestWithdrawal
        ├─ checkNotLastSystemAdmin(userId)              （既存）
        ├─ checkNoLastAdminScopes(userId)               ★ 新規ガード
        │   ↳ findLastAdminScopes(userId).isEmpty() でなければ
        │      throw new BusinessException(AUTH_xxx)
        ├─ Password 確認                                 （既存）
        ├─ user.requestDeletion() + softDelete           （既存）
        └─ eventPublisher.publish(WithdrawalRequestedEvent)
  → 30 日後 AccountPurgeService が purge
       ↳ 親設計書 Phase B-1 完成後は AccountPurgedEvent
            → RolePurgeEventListener
                 → RoleService#removeMember（チェックは通る、既に ADMIN 譲渡済のため）

  ※ もしユーザーが上記ガードを素通りした場合（API 直叩き等）:
       → 案 A の安全弁 removeMemberWithoutAdminCheck が拾う
       → ADMIN 不在組織として運用通知発火
```

### 5.3 既存テーブル変更要否

**変更不要**。Phase α/β/γ いずれも新規テーブルなし、既存スキーマ変更なし。

ただし Phase β で `users.purged_at` インデックスは親設計書 §9.10 で別途追加予定。

### 5.4 新規 API / メソッド

| 種別 | シグネチャ | 場所 |
|---|---|---|
| Repository メソッド | `List<LastAdminScope> findLastAdminScopes(Long userId)` | `UserRoleRepository` 拡張 |
| DTO | `LastAdminScope { scopeType, scopeId, scopeName, otherMembersCount }` | `role/dto/` |
| DeletionPreviewResponse 拡張 | `List<LastAdminScope> lastAdminScopes` フィールド追加 | `gdpr/dto/DeletionPreviewResponse` |
| RoleService メソッド | `void removeMemberWithoutAdminCheck(Long scopeId, String scopeType, Long userId)` | `RoleService` 新設 |
| UserService 内部ヘルパー | `private void checkNoLastAdminScopes(Long userId)` | `UserService` 新設 |
| エラーコード | `AUTH_xxx = "未処理の最後 ADMIN 組織があります"` | `AuthErrorCode` 新設（番号は Phase β 着手時に未使用を確定）|

ORG/TEAM の archive API は事前調査必要（未実装なら追加 PR が独立して発生）。

### 5.5 i18n 文言案

すべて `frontend/app/locales/{ja,en,zh,ko,es,de}/common.json`（または新規 `account.json`）に追加。

| キー | ja | en |
|---|---|---|
| `account.delete.lastAdmin.heading` | 退会前に処理が必要な組織が {count} 件あります | {count} organization(s) need action before withdrawal |
| `account.delete.lastAdmin.description` | あなたが最後の管理者になっている組織は、退会前に後任の管理者を指名するか、組織を凍結する必要があります。 | You are the last admin of these organizations. Please assign a successor or archive each one before withdrawing. |
| `account.delete.lastAdmin.action.transfer` | 後任の管理者を指名する | Assign successor |
| `account.delete.lastAdmin.action.archive` | 組織を凍結する | Archive organization |
| `account.delete.lastAdmin.action.autoArchive` | 他にメンバーがいないため、自動で凍結されます | No other members — will be archived automatically |
| `account.delete.lastAdmin.proceedBlocked` | 上記すべての組織を処理してから削除に進んでください | Please process all organizations above before proceeding to delete |

zh / ko / es / de は Phase β 着手時に翻訳。初版はすべて ja と同一値で投入してから順次更新（CLAUDE.md i18n ルール準拠）。

### 5.6 既存退会処理への割り込みポイント

| 割り込み箇所 | 変更内容 |
|---|---|
| `GdprController#buildDeletionPreview`（[`GdprController.java:124-160`](../../backend/src/main/java/com/mannschaft/app/gdpr/controller/GdprController.java)）| `lastAdminScopes` フィールドを追加 |
| `UserService#requestWithdrawal`（[`UserService.java:408`](../../backend/src/main/java/com/mannschaft/app/auth/service/UserService.java)）| 冒頭に `checkNoLastAdminScopes(userId)` 追加（`checkNotLastSystemAdmin` の直後）|
| `SettingsDeletionPreviewDialog.vue`（[`account.vue:273` 経由](../../frontend/app/components/settings/SettingsDeletionPreviewDialog.vue)）| lastAdminScopes ブロック追加 + 各スコープごとの承継アクション UI |
| `RoleService`（[`RoleService.java:137`](../../backend/src/main/java/com/mannschaft/app/role/service/RoleService.java)）| `removeMemberWithoutAdminCheck` メソッド新設（パッケージプライベートまたは public）|
| 親設計書 Phase B-1 で新設予定 `RolePurgeEventListener` | `removeMember` ではなく `removeMemberWithoutAdminCheck` を呼ぶ |

---

## §6. Phase 分けと PR 粒度

| Phase | PR | 内容 | 工数目安 | 親設計書との依存 |
|---|---|---|---|---|
| α-1 | 1 | `removeMemberWithoutAdminCheck` 追加 + UT 2 件 | 0.5 日 | **親設計書 Phase B-1 着手と同時または直前** |
| α-2 | 1 | `RolePurgeEventListener`（親設計書 Phase B-1）で `removeMemberWithoutAdminCheck` を呼ぶよう統合 | （親設計書 B-1 内で実施）| 親 B-1 と一体 |
| α-3 | 1 | 運用通知整備（ADMIN 不在組織検出 → PagerDuty / Slack）| 1 日 | 親 §9.7 と同時着手推奨 |
| β-1 | 1 | `findLastAdminScopes` Repository メソッド + LastAdminScope DTO + UT | 0.5 日 | 独立 |
| β-2 | 1 | `GdprController#buildDeletionPreview` 拡張 + `DeletionPreviewResponse` 拡張 + 統合テスト | 0.5 日 | β-1 依存 |
| β-3 | 1 | `UserService#requestWithdrawal` に `checkNoLastAdminScopes` 追加 + 新エラーコード + UT | 0.5 日 | β-1 依存 |
| β-4 | 1 | ORG/TEAM archive API 追加（既存有無確認後）| 1〜2 日 | 独立 |
| β-5 | 1 | `SettingsDeletionPreviewDialog.vue` 拡張 + 新規承継アクションコンポ + i18n 6 言語 | 2〜3 日 | β-2, β-4 依存 |
| β-6 | 1 | E2E テスト（Playwright）| 1 日 | β-5 依存 |
| γ-1 | 1 | 安全弁発火メトリクス監視 + 縮退判断 | （30 日運用後）| Phase β 完了後 |

**全体 PR 数:** Phase α = 2〜3 PR、Phase β = 6 PR、Phase γ = 1 PR、合計 **9〜10 PR**。

---

## §7. 法務・運用観点

### 7.1 GDPR Art. 17 削除権との整合

- 案 B は「ユーザーに追加の手続きを要求する」が、これは「削除権の阻害」ではなく「組織責任の引継ぎのための合理的な準備期間」として正当化可能（GDPR Recital 65 「他者の権利との均衡」）
- 30 日後物理削除タイムリミットは案 B でも変わらない（ユーザーが UX を完了した時点で `requestDeletion()` がセットされ、その時点から 30 日カウント開始）
- 案 D / G は「永続的に退会できない」状態を生むため Art. 17 (1) との緊張が大きく、本軍議では不採用

### 7.2 自治会・管理組合の組織責任

- 区分所有法・地方自治法上、管理組合・自治会の代表者（理事長・会長）は退任時に後任を明示的に引継ぐ義務がある
- 案 B はこの法的義務を UX 上で支援する設計のため、F09.15「区分所有者承継支援」シリーズとも親和性が高い
- 案 C（自動承継）は「本人同意なき法的責任の付与」を生むため、特に管理組合・自治会では避けるべき

### 7.3 残された組織メンバーへの通知

| シナリオ | 通知タイミング | 通知先 | 内容 |
|---|---|---|---|
| 後任指名済 | `transferOwnership` 完了時 | 新 ADMIN ユーザー | 「あなたが組織 X の管理者に承継されました」（既存通知に「先任者の退会に伴う承継」フラグ追加）|
| 組織 archive | `archive` 完了時 | 全メンバー | 「組織 X は管理者退会により凍結されました」|
| 安全弁発火（案 A 発動）| 30 日後 purge 完了時 | システム管理者（SYSTEM_ADMIN）| 「組織 X が ADMIN 不在状態になりました。手動対応してください」|

### 7.4 法務レビューのタイミング

- Phase α は「現状追認 + 安全弁」のため法務レビュー不要
- Phase β リリース前に**マスター御裁可 + 任意で法務レビュー**（特に自治会・管理組合シナリオの UX 文言）
- Phase γ（縮退判断）は法務不要

---

## §8. テスト計画

### 8.1 ユニットテスト

| 対象 | テストケース |
|---|---|
| `RoleService#removeMemberWithoutAdminCheck` | 正常系（ADMIN 1 名のみでも DELETE 成功）/ 存在しない userRole で ROLE_001 |
| `UserRoleRepository#findLastAdminScopes` | ADMIN 1 名のスコープを返す / ADMIN 2 名以上のスコープは返さない / MEMBER のみ所属のスコープは返さない |
| `UserService#checkNoLastAdminScopes` | lastAdminScopes 空なら例外なし / 1 件以上で AUTH_xxx |
| `GdprController#buildDeletionPreview` 拡張 | `lastAdminScopes` フィールドが正しく入る |

### 8.2 統合テスト（`@SpringBootTest`）

| シナリオ | 検証内容 |
|---|---|
| 通常退会 | lastAdmin なし → `DELETE /users/me` 成功 |
| 最後 ADMIN ガード | lastAdmin あり → `DELETE /users/me` が 409 で AUTH_xxx |
| 承継経由退会 | lastAdmin に対し `transferOwnership` → 再度 `deletion-preview` で lastAdmin が消えている → `DELETE /users/me` 成功 |
| archive 経由退会 | lastAdmin に対し `archive` → 同上 |
| 安全弁発火 | `removeMemberWithoutAdminCheck` を直接呼んで ADMIN 0 になる組織を作成し、運用通知バッチで検出される |

### 8.3 E2E テスト（Playwright）

| シナリオ | 検証内容 |
|---|---|
| LAS-001 | 個人ユーザー（lastAdmin 0 件）が退会画面で削除ボタンを押せる |
| LAS-002 | 組織 ADMIN（lastAdmin 1 件）が退会画面を開くと警告ブロックが表示される |
| LAS-003 | 「後任を指名」を選択 → メンバー検索 → 「処理する」で承継完了、削除ボタン活性化 |
| LAS-004 | 「組織を凍結」を選択 → 「処理する」で archive 完了、削除ボタン活性化 |
| LAS-005 | 他メンバー 0 人のスコープは自動凍結表示のみで、ワンクリックで処理可能 |
| LAS-006 | すべての lastAdmin を処理完了したら削除ボタンが活性化し、`DELETE /users/me` まで完走する |

### 8.4 GDPR シナリオテスト

- 案 B 採用後も「退会受付完了から 30 日以内に物理削除される」ことを既存 `AccountPurgeServiceTest` シナリオで保証
- 法務レビュー時に「承継 UX 中はユーザーが退会できない」期間の合理性を文書化

### 8.5 既存テストへの影響

- `SettingsDeletionPreviewDialogTest`（Vitest, 存在すれば）: lastAdminScopes 描画ロジック追加
- `UserServiceTest#requestWithdrawal_xxx` 系: lastAdmin ガードを通過するように mock 追加
- `GdprControllerTest#getDeletionPreview_xxx`: lastAdminScopes フィールド追加

---

## §9. 親設計書 §9.6 への反映方針

本軍議の御裁可成立後、親設計書 `account_purge_cross_domain_refactor.md` §9.6 を以下の通り更新する PR を別途立てる:

1. §9.6 本文を 1〜2 段落の要約に短縮
2. 詳細は本設計書（`account_purge_last_admin_succession.md`）にリンク委譲
3. 「推奨案」を「案 B + 案 A 併用（詳細別書）」と確定表記に変更
4. 親 Phase B-1 着手前提として「α-1 PR（`removeMemberWithoutAdminCheck` 追加）」をブロッカーとして明記
5. 親設計書「変更履歴」表に本軍議への分岐を追記
6. 🔴 **エラーコード誤記訂正**: 親 §9.6 本文中の `BusinessException(ROLE_001)` → **`BusinessException(ROLE_004)`** に訂正（実コード `RoleErrorCode.ROLE_004` が正、本設計書 §2.2 で確認済）

**更新 PR 番号予定:** 本設計書の御裁可後、出陣命令と同時に発番される PR 番号を記入する欄を親側に新設（現時点では `_TBD_`）。

---

## §10. 未解決事項（マスター御裁可待ち → **2026-05-18 一括裁定済**）

> **🏯 マスター裁定（2026-05-18・「よきにはからえ」一括承認）**
>
> 下表の論点はすべて家老推奨案を採用とする。論点 10.11〜10.13 のうち家老推奨が明確でなかった項目については殿が安全側に倒した裁定を補記：
>
> - **§10.11（指名先承諾欠如）**: ~~Phase β β-3 では既存 `transferOwnership`（事後通知のみ）で進める。指名先事前承諾フローは Phase β-2 リリース後の運用フェーズで利用者の声を見て**別軍議で判断**。~~ → **【解消・2026-07-18 マスター御裁可】** オーナー委譲を承諾型（オファー→承諾）に統一することを決定。指名相手のみが承諾でき（宛先照合 = IDOR 防止）、承諾があって初めて委譲を実行する。設計は [`F01.2/03_business_logic.md`「オーナー委譲 承諾フロー」](../features/F01.2_org_team_member_role/03_business_logic.md) ／ [`F01.2/02_api_design.md`](../features/F01.2_org_team_member_role/02_api_design.md)（`transfer-ownership-offers`）／新テーブル [`ownership_transfer_offers`](../features/F01.2_org_team_member_role/01_db_design.md) を参照。案 C（自動承継）との責任構造の違い（ユーザーが能動的に指名する点）は従来整理どおり。
>
>   **⚠️ 退会 purge 経路は承諾型化の対象外（承諾スキップの強制委譲）**: 承諾型2段は「通常のオーナー委譲」に適用する。**退会（アカウント purge）に伴う最後の ADMIN 承継は、承諾を待つと退会が詰まり（承継先が 2FA 未設定なら承諾不能＝退会不能）GDPR Art.17 の 30 日タイムリミットに抵触する**ため、**purge 経路に限り従来どおりシステム強制の即時委譲（承諾スキップ・2FA チェックなし）を残す**。強制委譲であることを audit に `forced=true` で記録する。本節の案 B（退会プレビューでの手動指名承継）が呼ぶのは通常の承諾型ではなく、この **強制委譲経路**（本人不在で即時完結）である。したがって「承諾待ちで `deletion-preview` が詰まる」ことはない。実装は承諾型 `acceptOffer` と purge 用 `forceTransferForPurge` を**別メソッドに分離**する（詳細: F01.2 03_business_logic「退会 purge 経由の承継は『承諾スキップの強制委譲』」）。
> - **§10.12（archive 不可逆性）**: Phase β-4 で **SYSTEM_ADMIN 用 force-unarchive エンドポイント** を新設（`POST /api/v1/system-admin/{scope}/{id}/force-unarchive`、監査ログ必須）。残メンバー 0 archive 組織でも SYSTEM_ADMIN 介入で救済可能とする。
> - **§10.13（Phase α 上限期間）**: 「Phase α 開始から **6 ヶ月以内** に Phase β 着手必着」をサーキットブレーカーとし、auto memory に運用ルールを記録。
> - **§10.7（法務レビュー）**: 任意ではなく **Phase β リリース前に必須化**。
> - **§10.10**: PR #793 で根治治療着手済（家老推奨 案 ε）。
>
> 残論点なし。本設計書は出陣可能状態。


| # | 論点 | 推奨 | 御裁可必要事項 |
|---|---|---|---|
| 10.1 | 案 B + 案 A 併用方針の承認 | ─ | **本軍議の根幹**。承認 / 修正 / 他案再検討の判断 |
| 10.2 | ~~Phase β-4「ORG/TEAM archive API」の既存有無事前調査~~ | ✅ **解決済（偵察 2026-05-18）**。`PATCH /api/v1/organizations/{id}/archive`、`PATCH /api/v1/teams/{id}/archive` 既存。F17.1 `VillageHeadmanSuccessionBatchService` パターン流用可能 | **裁定不要**。β-4 スコープは「自動 archive バッチ新設（各約 200 行）」に縮減 |
| 10.3 | Phase γ-1「安全弁縮退判断」のメトリクス閾値 | 「Phase β リリース後 30 日連続で `removeMemberWithoutAdminCheck` 発火 0 件」を縮退条件とする | 数値の妥当性確認 |
| 10.4 | 案 B の「他メンバー 0 人ケース」自動 archive の挙動 | UX 上「自動凍結」ボタンを表示し、ユーザーがワンクリックで実行 | 「完全自動で archive される」vs 「ユーザー確認必須」の判断 |
| 10.5 | F09.15「区分所有者承継支援」との UX 統合 | 別軍議で要件整理 | F09.15 既存 UX フローと本軍議の UX を統合するか、別フローのままにするか |
| 10.6 | `DEPUTY_ADMIN` ロールの扱い | 本軍議スコープ外（`checkLastAdmin` も `DEPUTY_ADMIN` を対象外としている）| DEPUTY のみの組織で ADMIN 退会した場合は ADMIN 不在として案 B 対象に含めるか |
| 10.7 | 法務レビューのタイミング | Phase β リリース前に任意レビュー | 法務レビュー実施判断 |
| 10.8 | 親設計書 §9.6 の反映 PR タイミング | 本軍議の御裁可後ただちに立てる | OK ならそのまま、別タイミングが良ければ指示 |
| 10.9 | i18n 翻訳（zh / ko / es / de）の調達 | 初版はすべて ja 値で投入し、Phase β リリース後の運用フェーズで順次翻訳 | 翻訳業者調達 or AI 翻訳の判断 |
| **10.10** | 🔴 ~~`withdrawUser()` 休眠コード問題の別軍議起票要否~~ | ✅ **解決済 — PR #793 で根治治療着手**（2026-05-18）。退会受付と即時匿名化が接続されていない既存隠れバグは `withdrawal_flow_immediate_anonymization_fix.md`（636 行）で扱う。家老推奨は **案 ε（二段匿名化モデル）** = Day 0 弱匿名化 + Day 30 強匿名化。本軍議の Phase B-1（role ドメイン）への影響は無し（独立並行可能と判定）|
| **10.11** | `transferOwnership` の指名先承諾プロセス欠如（家老検分 2026-05-18） | 案 B では既存 `transferOwnership` をそのまま使うため、指名先には**事後通知のみ** | 案 C 不採用理由「本人同意なき権限譲渡」と論理矛盾しないか、指名先承諾フロー（事前通知 + 異議申立期間）を入れるか |
| **10.12** | archive 後の不可逆性と組織完全消失リスク（家老検分 2026-05-18） | SYSTEM_ADMIN 介入手順を運用整備（GdprController に SYSTEM_ADMIN 用 force-unarchive エンドポイント新設？）| 残メンバー 0 で archive 後の救済経路を整備するか、archive を「論理削除と同等」とみなし放置するか |
| **10.13** | Phase α 単独運用の上限期間サーキットブレーカー（家老検分 2026-05-18） | 「Phase α 開始から N ヶ月以内に Phase β 必着」を memory にリマインダ記録 | 「3 ヶ月」「6 ヶ月」等の上限期間の確定 |
| **10.14** | 退会バースト時の通知バックエンド枯渇対策（家老検分 2026-05-18） | 親 §9.8 `event-pool` 議論と統合。「退会バースト 10,000 件 → 新 ADMIN 通知 + 全メンバー通知 + SYSTEM_ADMIN 通知が一斉発火」シナリオへの `purge-pool` 分離適用 | 退会経路の通知も `purge-pool` に分離するかの判断 |
| **10.15** | `findLastAdminScopes(userId)` の必要インデックス明示と EXPLAIN 確認（家老検分 2026-05-18） | `user_roles.user_id` インデックス + Phase α-2 統合テストで EXPLAIN 確認 | クエリ計画を §5.4 に追記するか、α-2 着手時に確認するか |

---

## 関連ドキュメント

| パス | 内容 |
|---|---|
| `docs/architecture/account_purge_cross_domain_refactor.md` | **親設計書**（本軍議の起点）|
| `docs/architecture/db_scalability.md` | 1000 万ユーザー耐久 DB 再構築（親設計書の帰属親）|
| `backend/src/main/java/com/mannschaft/app/role/service/RoleService.java` | `checkLastAdmin` ガード本体（§142）、`transferOwnership`（§242）|
| `backend/src/main/java/com/mannschaft/app/auth/service/UserService.java` | `requestWithdrawal`（§408）、`withdrawUser`（§478）、`checkNotLastSystemAdmin`（§508）|
| `backend/src/main/java/com/mannschaft/app/gdpr/service/AccountPurgeService.java` | 30 日後物理削除バッチ（§111-222）|
| `backend/src/main/java/com/mannschaft/app/gdpr/controller/GdprController.java` | `deletion-preview` API（§92-160）|
| `backend/src/main/java/com/mannschaft/app/village/batch/VillageHeadmanSuccessionBatchService.java` | F17.1 村長承継バッチ（先行事例）|
| `frontend/app/pages/settings/account.vue` | 退会画面 |
| `frontend/app/components/settings/SettingsDeletionPreviewDialog.vue` | 削除プレビュー Dialog（Phase β-5 で拡張）|
| `docs/features/F09.15_resident_succession_support.md` | 区分所有者承継支援（UX 統合の検討対象）|
| `docs/features/F17.1_village_community.md` | 村ドメインの先行事例 |

---

## 変更履歴

| 日付 | 内容 | 担当 |
|---|---|---|
| 2026-05-18 | 初版作成（陣立て書 / 案 B+A 推奨 / Phase α-β-γ 計画 / マスター御裁可待ち 9 項目）| 家老（Plan agent）|
| 2026-05-18 | 検分修正反映 #1: ORG/TEAM archive API 既存実装あり（偵察確定） → §3 案 B / §5.2 / §6 β-4 / §10.2 を全面改訂（POST→PATCH、新規開発→既存活用、β-4 をバッチ実装に縮減で 1 PR 削減） | 殿（家老検分 + 偵察反映）|
| 2026-05-18 | 検分修正反映 #2: 🔴 重大発見 — `withdrawUser()` は呼出ゼロ・`UserAnonymizedEvent` 発火実績ゼロ・9 リスナー全休眠中 → §2.1 表に 🔴 明示、§10.10 別軍議起票事項として新設 | 殿（殿検分 verify）|
| 2026-05-18 | 検分修正反映 #3: §10 に 5 件追記（10.11 指名先承諾欠如 / 10.12 archive 不可逆性 / 10.13 Phase α 上限期間 / 10.14 退会バースト時通知枯渇 / 10.15 findLastAdminScopes EXPLAIN）+ §9 親設計書反映 PR に `ROLE_001→ROLE_004` 訂正項目追加 | 殿（家老検分反映）|
| 2026-05-18 | マスター「よきにはからえ」一括裁定反映: §10.10 を「PR #793 で根治治療」として解決済化。§10 全項目について家老推奨案採用 + 殿の安全側裁定を §10 ヘッダに明記（β リリース前法務レビュー必須化 / Phase α 上限 6 ヶ月 / SYSTEM_ADMIN force-unarchive 新設）。残論点ゼロ・出陣可能状態 | 殿（マスター御裁可反映）|
| 2026-05-18 | **Phase α-1 実装 PR `_TBD_` で `RoleService#removeMemberWithoutAdminCheck` 安全弁メソッド追加**（`checkLastAdmin` バイパス版、`MembershipChangedEvent(REMOVED)` 発火、UT 3 件）。呼出元（`RolePurgeEventListener`）は Phase B-1 / α-2 で配線予定 | 足軽（Phase α-1）|
