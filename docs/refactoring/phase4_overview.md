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
| キャッシュ値の型 | `List<TeamFriendView>` を **可変 `ArrayList`** で返す。`PageImpl` は `GenericJackson2JsonRedisSerializer` で復元できないため `Page` はキャッシュ外で組み立てる。`Stream#toList()` が返す `ImmutableCollections$ListN` も `DefaultTyping.EVERYTHING` の型 ID から復元できないため使わない（下記の注意を参照） |

> **`Stream#toList()` の戻り値型についての注意**
> `javap -c java.util.stream.Stream` を読むと `Collections.unmodifiableList(new ArrayList<>(...))` に見え、
> 戻り値は `Collections$UnmodifiableRandomAccessList` だと誤読しやすい。だがそれは**インタフェースの
> default 実装**であり、実際の Stream 実装 `ReferencePipeline` が `toList()` を override している
> （`SharedSecrets` 経由で `ImmutableCollections.ListN` を生成）。
> 本プロジェクトの JDK21（Temurin 21.0.10）で実測した実行時の型は `java.util.ImmutableCollections$ListN`。
> いずれにせよ既定コンストラクタが無く復元不能なので、`ArrayList` に集める必要性は変わらない。
| 失効 | すべて `allEntries = true`。キーが閲覧者・ページ・`publicOnly` の直積であり個別キー指定では必ず取りこぼす |

失効の網羅（キャッシュ値を変化させ得る操作の全数）:

| 操作 | 理由 |
|---|---|
| `TeamFriendsService#follow` / `#unfollow` | フレンドが増減する |
| `TeamFriendVisibilityService#setVisibility` | `isPublic` が変わる |
| `TeamService#updateTeam` | チーム名変更で `friendTeamName` が stale 化する |
| `TeamService#deleteTeam` | 削除済みチームの残留防止 |
| `TeamService#restoreTeam` | 論理削除の復元で `@SQLRestriction("deleted_at IS NULL")` の効き方が反転する。削除中に温められた `friendTeamName = null` が復元後も残るのを防ぐ |

`archiveTeam` / `unarchiveTeam` は **対象外**。`archivedAt` しか触らず、`@SQLRestriction` は
`deleted_at` のみを見るためキャッシュ値のどの項目にも影響しない（不要な全消しはヒット率を下げるだけ）。

番人テスト:

- `CacheableAuthzEnforcementGuardTest` — `@Cacheable` から例外送出型の認可ゲートに到達することを機械的に禁止。認可クラスは兄弟番人 `AuthzGateReturnValueGuardTest` のゲートクラス定義（`*AccessGuard` / `*AccessService` / `*AuthorizationService` / `AccessControlService`）に揃え、ゲート接頭辞は `check`/`require`/`assert`/`validate`/`verify`/`authorize` の 6 種。同一クラス内のヘルパーは推移的に辿る
- `CacheableAuthzEnforcementGuardConditionTest` — 上記番人の**偽陰性ゼロ証明メタテスト**。`architecture/fixtures/` の意図的違反（直接呼び・helper 経由・多段 helper）を検出できること、照会系のみの正当形を巻き込まないことを固定
- `TeamFriendListCacheEvictionCoverageTest` — 上表の失効網羅と `allEntries = true` を固定
- `TeamFriendQueryServiceCacheTest` — 実 AOP プロキシ越しにキャッシュ発火・認可の毎回実行・失効を検証。加えてサービスが生成したキャッシュ値を**本番シリアライザ**で往復させる
- `TeamFriendViewCacheSerializationTest` — `RedisConfig#redisCacheConfiguration()` から取り出した**実物の `SerializationPair`** で往復させ、`isPublic` が化けないこと・コレクション実装が復元可能なことを固定

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
