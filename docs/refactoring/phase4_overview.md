# リファクタリング第4弾 概要

## 対象ファイル

| 対象 | 変更前行数 | 課題 |
|------|-----------|------|
| `backend/.../disclosure/service/DisclosureExportService.java` | 922行 | バリデーション・PDF/Excel生成・R2保存・export記録・presigned URL の5責務が混在 |
| `backend/.../social/service/TeamFriendsService.java` | 724行 | フォロー操作・相互検知・フレンド一覧取得・可視性設定が1クラスに集中 |
| `frontend/app/pages/teams/[id]/signage.vue` | 804行 | organizations 版（800行）と完全に同じ構造が重複 |
| `frontend/app/pages/organizations/[id]/signage.vue` | 800行 | 同上 |

---

## 1. DisclosureExportService 分割方針

### 分割後の構成（4クラス）

```
backend/.../disclosure/service/
├── DisclosureExportService.java        （~280行: ファサード・export 記録 + presigned URL）
│   export() / list() / getDownloadUrl()
│
├── DisclosureExportValidationService.java （バージョン整合・form_schema 検証・引用パッケージ検証）
│   validateDraftVersion / validateFormSchema / validateReferencedPackages
│
├── DisclosureExportFileService.java   （PDF/Excel 生成 + SHA-256 算出）
│   generatePdf / generateExcel / computeSha256
│
└── DisclosureExportStorageService.java （R2 直接保存 + SharedFile DB 登録）
    storeToR2 / registerSharedFile / issuePresignedUrl
```

### 設計上の注意点

- バリデーション系（DISCLOSURE_004 / 006 / 007 / 008）は `DisclosureExportValidationService` に集約
- `SealStampService` 連携（StampVerifyResponse）は `DisclosureExportService` ファサードに残す
- 各クラスに `@Service` + `@Transactional(readOnly = true)` クラスレベル設定
- 更新系メソッド（export 記録作成・status 遷移）は `DisclosureExportService` に集約し `@Transactional` で個別上書き

---

## 2. TeamFriendsService 分割方針

### 分割後の構成（3クラス）

```
backend/.../social/service/
├── TeamFriendsService.java         （~250行: ファサード + follow/unfollow + 相互検知）
│   followTeam / unfollowTeam / detectMutual
│
├── TeamFriendQueryService.java     （フレンド一覧・ページネーション・キャッシュ）
│   listFriends / countFriends / getFriendView
│
└── TeamFriendVisibilityService.java （公開設定・可視性管理）
    updateVisibility / getVisibility
```

### 設計上の注意点

- `@Cacheable`（`teamFriendList`）は `TeamFriendQueryService` に集約。`@CacheEvict` は更新操作を持つ側（`TeamFriendsService` / `TeamFriendVisibilityService` / `TeamService`）に置く
- REPEATABLE_READ 分離（相互フォロー検知）は `TeamFriendsService.followTeam` 内に残す
- `PessimisticLockingFailureException` 等のロック競合ハンドリング（NOWAIT_RETRY_AFTER_SECONDS）はファサードに維持
- 監査ログ（`AuditLogService`）連携はファサード経由

### `teamFriendList` キャッシュの構造（issue #2496 で根治）

本分割の直後から、`teamFriendList` キャッシュは **一度も発火していなかった**。
`TeamFriendQueryService.listFriendsResponse` が同一 Bean 内の `this.listFriends()` を呼んでおり、
Spring のキャッシュ AOP（プロキシ方式）が自己呼び出しでは作用しなかったためである。
さらに認可（`checkMembership`）が `@Cacheable` メソッドの**内側**にあり、
「自己呼び出しを解消した瞬間にキャッシュヒットが認可を飛ばす」構造だった。

現在の構造は以下のとおり:

| 論点 | 方針 |
|---|---|
| 認可の位置 | `listFriends`（キャッシュの**外**）で `checkMembership` を実行し、通過後にキャッシュ層を呼ぶ |
| 自己呼び出し | `@Lazy` 自己注入した `self()` 経由で `listFriendViews` を呼ぶ（`WidgetVisibilityResolver` と同型） |
| キャッシュキー | `teamId:userId:page:size:publicOnly`。返却内容は閲覧者個人に依存しないが、認可がキャッシュ内側へ再混入した場合の多層防御として `userId` を含める |
| キャッシュ値の型 | `List<TeamFriendView>`。`PageImpl` は `GenericJackson2JsonRedisSerializer` で復元できないため `Page` はキャッシュ外で組み立てる |
| 失効 | すべて `allEntries = true`。キーが閲覧者・ページ・`publicOnly` の直積であり個別キー指定では必ず取りこぼす |
| 失効の網羅 | `follow` / `unfollow` / `setVisibility` / `TeamService.updateTeam`（`friendTeamName` の stale 防止）/ `TeamService.deleteTeam` |

番人テスト:

- `CacheableAuthzEnforcementGuardTest` — `@Cacheable` の内側で例外送出型の認可ゲートを呼ぶことを機械的に禁止
- `TeamFriendListCacheEvictionCoverageTest` — 上表の失効網羅と `allEntries = true` を固定
- `TeamFriendQueryServiceCacheTest` — 実 AOP プロキシ越しにキャッシュ発火・認可の毎回実行・失効を検証
- `TeamFriendViewCacheSerializationTest` — JSON 往復で `isPublic` が化けないことを固定

---

## 3. signage.vue 汎用化方針

### 対応方針: scope props によるコンポーネント化

teams 版（804行）と organizations 版（800行）は import / state / dialog / 関数 / template がほぼ完全に同一。Phase 1〜2 の webhook 汎用化と同パターンで分割する。

```
変更前:
- teams/[id]/signage.vue（804行）
- organizations/[id]/signage.vue（800行）         ← 同じロジックが重複

変更後:
- components/signage/SignageManager.vue（~750行: 本体ロジック、scopeType/scopeId props）
- teams/[id]/signage.vue         → ~30行（scope-type="TEAM" を渡すのみ）
- organizations/[id]/signage.vue → ~30行（scope-type="ORGANIZATION" + layout="organization" を渡すのみ）
```

### 設計上の注意点

- `useSignageApi()` の各関数は `scopeType` を引数に取る既存形式があれば踏襲、無ければ wrapper で吸収
- `definePageMeta` 内の `layout` は organizations 側でのみ `'organization'` を指定（teams 側は default）
- 両ページで同一コンポーネントを共有するため、片方の変更が両方に反映される

---

## 実施時期

| フェーズ | 対象 | 実施時期 |
|---------|------|---------|
| 第1弾 | ActionMemoService / useParkingApi / webhooks.vue(org) | 2026-05-16 |
| 第2弾 | ErrorReportService / useVillageApi / webhooks.vue(teams汎用化) | 2026-05-16 |
| 第3弾 | useShiftApi / TodoService / AnnouncementFeedService | 2026-05-17 |
| 第4弾 | DisclosureExportService / TeamFriendsService / signage.vue(汎用化) | 2026-05-17 |

## 参考

- `docs/refactoring/phase1_overview.md` — 第1弾の概要
- `docs/refactoring/phase2_overview.md` — 第2弾の概要
