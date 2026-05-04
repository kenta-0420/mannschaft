# F00: ContentVisibilityResolver 共通基盤

> **ステータス**: 🟢 **設計完了**（v1.0 — 第 1 回・第 2 回精査全件反映、マスター裁可済）
> **最終更新**: 2026-05-04
> **マスター裁可済**: §17 Q1 確定（未認証ユーザーは PUBLIC のみ閲覧可、それ以外は fail-closed）
> **モジュール種別**: 横断基盤（権限管理・共通部品）
> **対象パッケージ**: `com.mannschaft.app.common.visibility`
> **関連機能**:
> - F09.8.1 コルクボード・ピン止めダッシュボード（直近のクライアント、§5.2 が本基盤前提）
> - F02.2.1 ダッシュボード・ウィジェット可視性制御（先例 Resolver パターン）
> - F01.7 カスタム公開範囲テンプレート（既存 `VisibilityTemplateEvaluator` を本基盤に統合）
> - F01.2 チーム・組織・ロール（`AccessControlService` の責務範囲）

---

## 目次

1. [目的と現状の負債](#1-目的と現状の負債)
2. [設計目標](#2-設計目標)
3. [スコープ](#3-スコープ)
4. [アーキテクチャ](#4-アーキテクチャ)
5. [StandardVisibility と既存 enum の対応](#5-standardvisibility-と既存-enum-の対応)
6. [ReferenceType と Resolver の対応](#6-referencetype-と-resolver-の対応)
7. [API 設計](#7-api-設計)
8. [パッケージ・クラス構成](#8-パッケージクラス構成)
9. [キャッシング戦略](#9-キャッシング戦略)
10. [既存 AccessControlService / VisibilityTemplateEvaluator との統合](#10-既存-accesscontrolservice--visibilitytemplateevaluator-との統合)
11. [セキュリティ考慮事項](#11-セキュリティ考慮事項)
12. [段階的移行計画](#12-段階的移行計画)
13. [テスト戦略](#13-テスト戦略)
14. [パフォーマンス目標と検証](#14-パフォーマンス目標と検証)
15. [設計判断ログ](#15-設計判断ログ)
16. [見送り事項 (Out of Scope)](#16-見送り事項-out-of-scope)
17. [未解決問題 (Open Questions)](#17-未解決問題-open-questions)
18. [精査履歴](#18-精査履歴)
19. [関連ファイル一覧](#19-関連ファイル一覧)

---

## 1. 目的と現状の負債

### 1.1 目的

「ユーザー X はコンテンツ Y を閲覧できるか?」という判定ロジックを **単一の共通基盤** に集約し、以下を実現する:

- **横断的な利用**: コルクボード・通知・検索・ダッシュボード等、複数機能を跨ぐ参照解決で type ごとの権限判定を一括実行できる
- **N+1 防止**: `Set<Long> filterAccessible(refType, ids, userId)` のバッチ判定 API を提供
- **セキュリティ漏れの根絶**: 「Mention 配信に visibility チェックなし」のようなチェック忘れを設計レベルで防ぐ
- **保守性**: 共通の `StandardVisibility` 概念で各機能の判定ロジックを統一し、コピペ実装を撤廃

### 1.2 現状の技術的負債（実測値）

2026-05-04 時点の偵察結果:

| 指標 | 実測値 | 出典 |
|---|---|---|
| Visibility 系 enum の総数 | **20** (19 enum + 1 Record) | `grep -r "Visibility" backend/src/main/java/com/mannschaft/app/` |
| 完全に同値セットの enum グループ | **2 グループ** | グループA: `{PUBLIC, MEMBERS_ONLY}` 系 4 個 / グループB: `{HIDDEN, CREATOR_AND_ADMIN, ALL_MEMBERS}` 系 2 個 |
| `AccessControlService` を経由しない可視性判定 | **8+ モジュール** (~60%) | activity / cms / event / contact / gallery / survey / matching / member 等 |
| `if (visibility == X)` の散在ファイル数 | **20+** | TeamExtendedProfileService と OrganizationExtendedProfileService がほぼ同一実装 |
| visibility チェック未実装の Service | **5** | Gallery / Survey / Schedule / Recruitment(Phase2 留保) / Matching |

### 1.3 重複している判定パターンの代表例

```java
// TeamExtendedProfileService.java:76（同パターンが Organization 側にコピペ存在）
if (team.getVisibility() == TeamEntity.Visibility.PRIVATE && !isMember) {
    throw new BusinessException(TeamErrorCode.TEAM_048);
}
```

このパターンが約 6 モジュールに散在し、引数も判定基準も微妙に異なる。

### 1.4 セキュリティ上の懸念

偵察で判明した可視性チェックの**漏れ疑い箇所**:

| 箇所 | リスク | 影響度 |
|---|---|---|
| `NotificationDispatchService` Mention 配信 | PRIVATE コンテンツに @mention されたユーザーが通知文経由で本文を覗ける | 高 |
| `CirculationDocumentService.distribute()` | スコープ外メンバーへの誤配信 | 中 |
| `Gallery.listAlbums()` | visibility パラメータを受け取るが未使用 | 中 |
| `Survey` 結果一覧 | ResultsVisibility 設定はあるが Service が読まない | 中 |
| `Schedule` 一覧 | visibility フィルタ自体が無い | 中 |

本基盤の導入により、Mention / 通知配信は **必ず ContentVisibilityChecker.filterAccessible() を経由する** よう規約化する（§11）。

---

## 2. 設計目標

### 2.1 機能目標

- **F-1**: 任意の `(referenceType, contentId, userId)` 組について、O(1) 単発判定 API を提供
- **F-2**: 任意の `(referenceType, [contentId...], userId)` についてバッチ判定 API を提供（SQL 数 ≦ 2 を保証）
- **F-3**: 複数 referenceType を一括判定する `filterAccessibleByType()` を提供（コルクボード等のミックス参照向け）
- **F-4**: 新規 referenceType の追加が **既存コードに触らず Strategy 追加のみで完結** すること
- **F-5**: 未対応 referenceType に対するフォールバック動作を統一（`is_accessible=false` で返す、例外スローしない）

### 2.2 非機能目標

- **NF-1 性能**: コルクボード 1 ボード（≦ 50 ピン）の `filterAccessibleByType()` で **p95 ≦ 100ms / SQL 数 ≦ 8**
- **NF-2 後方互換**: 既存 `AccessControlService` の API は一切変更しない（追加のみ）。既存 Service の挙動は変えない
- **NF-3 拡張性**: Strategy パターンで新 referenceType を追加する手順が ≦ 30 行のコード追加で完結
- **NF-4 テスト容易性**: 個別 Resolver は SUT 単体で完結（他モジュールの Service をモックせず Repository のみモック）
- **NF-5 観測性**: 判定漏れ・キャッシュヒット率・遅延を Micrometer メトリクスで可視化

### 2.3 非目標（後続フェーズで扱う）

- 全 20 個の visibility enum を `StandardVisibility` 1 つに完全統一すること（Phase E で段階対応）
- 個別機能 Service を全部削除して Resolver 経由のみにすること（既存パスは保持）
- ロール・権限定義そのものの再設計（`AccessControlService` / `RoleService` の責務維持）

---

## 3. スコープ

### 3.1 含むもの

- 共通基盤クラス群（`ContentVisibilityResolver<V>` 抽象、`ContentVisibilityChecker` ファサード、`StandardVisibility` enum、`ReferenceType` enum、`VisibilityDecision` 値オブジェクト）
- 既存 visibility enum → StandardVisibility への Mapper クラス群（20 個分の対応表）
- 個別機能向け Resolver 実装（Phase B〜D で段階的に）
- 単体テスト・統合テスト・性能テストの規約とテンプレート
- 通知配信・Mention 配信での Resolver 必須利用ガイドライン
- 移行ガイド（既存 Service を Resolver に切り替える手順書）

### 3.2 含まないもの (Out of Scope)

§16 参照。主に以下:

- 個別機能の visibility 値追加（例: CMS に新値を増やす）
- Visibility enum 自体の DB 保存形式変更（VARCHAR(30) のまま）
- フロントエンドの可視性 UI 統一（次フェーズ）

### 3.3 対象 reference_type の確定リスト

F09.8.1 §4.3 の 11 種を起点に、本基盤としては以下 17 種を Phase B〜D で順次サポート:

| # | ReferenceType | 既存 enum | visibility 戦略 | 担当 Phase |
|---|---|---|---|---|
| 1 | `BLOG_POST` | `cms.Visibility` | 既存enum | B |
| 2 | `EVENT` | `event.entity.EventVisibility` | 既存enum | B |
| 3 | `ACTIVITY_RESULT` | `activity.ActivityVisibility` | 既存enum | B |
| 4 | `SCHEDULE` | `schedule.ScheduleVisibility` | 既存enum | B |
| 5 | `TIMELINE_POST` | (visibility 概念なし、所属で判定) | 所属固定 | B |
| 6 | `CHAT_MESSAGE` | (visibility 概念なし、チャネル所属で判定) | 所属固定 | B |
| 7 | `BULLETIN_THREAD` | (visibility 概念なし、所属で判定) | 所属固定 | C |
| 8 | `TOURNAMENT` | `tournament.TournamentVisibility` | 既存enum | C |
| 9 | `RECRUITMENT_LISTING` | `recruitment.RecruitmentVisibility` | 既存enum | C |
| 10 | `JOB_POSTING` | `jobmatching.enums.VisibilityScope` | 既存enum | C |
| 11 | `SURVEY` | `survey.ResultsVisibility` | 既存enum (CUSTOM 多) | C |
| 12 | `CIRCULATION_DOCUMENT` | (回覧板配信先 ACL で判定) | ACL固定 | C |
| 13 | `COMMENT` | (親コンテンツの可視性に従属) | 親従属 | C |
| 14 | `PHOTO_ALBUM` | `gallery.AlbumVisibility` | 既存enum | D |
| 15 | `FILE_ATTACHMENT` | (添付元コンテンツの可視性に従属) | 親従属 | D |
| 16 | `TEAM` | `TeamEntity.Visibility` | 既存enum | D |
| 17 | `ORGANIZATION` | `OrganizationEntity.Visibility` | 既存enum | D |

**visibility 戦略の凡例**:
- **既存enum**: 機能側に visibility カラムあり、Mapper 経由で StandardVisibility に正規化
- **所属固定**: 機能側に visibility 概念がない（Phase 1 では実質 MEMBERS_ONLY 固定）。将来機能拡張で visibility を追加するときは別軍議で機能仕様策定が必要
- **ACL固定**: 配信先テーブル（distribution_target 等）に直接 user_id を持つタイプ。Resolver 内でその ACL テーブルを参照
- **親従属**: 親コンテンツの可視性に従う（COMMENT は対象 BlogPost / Event 等の visibility を継承）。Resolver 内で親 type の `ContentVisibilityChecker.canView()` を呼ぶ（§D-16 循環参照対策）

**Phase 2 予約**（Phase 1 では fail-closed、ReferenceType enum には先行定義する）:
- `PERSONAL_TIMETABLE` — F03.15 個人時間割が Mention 配信されるユースケース対応
- `FOLLOW_LIST` — フォロー一覧自体を corkboard カードとして引用するユースケース対応

これらは v0.2 時点では Resolver 未実装のため `ContentVisibilityChecker.canView(...)` を呼ぶことを ArchUnit ルールで禁止する（§13.5）。

**意図的に対象外** とする visibility:
- `OnlineVisibility`（オンライン状態。コンテンツではなくユーザー属性）
- `OrganizationProfileVisibility`（フィールド別フラグの Record。設計が異質）
- `FollowListVisibility`（リスト全体の公開可否で個別エンティティ単位ではない）
- `PersonalTimetableVisibility`（FAMILY_SHARED は family_team メンバー判定で別経路）

これらは §16.2 で別建ての扱いを示す。

---

## 4. アーキテクチャ

### 4.1 全体図

```
┌─────────────────────────────────────────────────────────────────┐
│                  Caller (Controller / Service)                 │
│  例: CorkboardCardService, NotificationDispatchService          │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  ContentVisibilityChecker (Facade)        @Service             │
│  ─────────────────────────────────────────────                 │
│  + canView(refType, contentId, userId)         : boolean        │
│  + filterAccessible(refType, ids, userId)      : Set<Long>      │
│  + filterAccessibleByType(map, userId)         : Map<RT,Set>    │
│  + decide(refType, contentId, userId)          : VisibilityDecision │
└──────────────────────────┬──────────────────────────────────────┘
                           │ Map<ReferenceType, Resolver> でディスパッチ
                ┌──────────┼──────────┬──────────┬──────────┐
                ▼          ▼          ▼          ▼          ▼
       ┌──────────────┐ ┌──────────┐ ┌─────────┐ ┌─────────┐
       │ BlogPost     │ │ Event    │ │ Schedule│ │ ...     │
       │ VisibilityRe │ │ Visibili │ │ Visibili│ │         │
       │ solver       │ │ tyResolv │ │ tyResolv│ │         │
       │              │ │ er       │ │ er      │ │         │
       └──┬───────────┘ └─┬────────┘ └─┬───────┘ └─────────┘
          │               │            │
          └───────┬───────┴────────────┘
                  │ 各 Resolver は以下を組み合わせる
        ┌─────────┼─────────────┐
        ▼         ▼             ▼
┌───────────┐ ┌─────────────┐ ┌──────────────────────────┐
│ AccessCtrl│ │ Visibility  │ │ 機能固有 Repository       │
│ Service   │ │ TemplateEval│ │ (BlogPostRepository 等)  │
│ (既存)    │ │ uator (既存)│ │                          │
└───────────┘ └─────────────┘ └──────────────────────────┘
```

### 4.2 Strategy + Registry パターンの採用根拠

**選択肢の比較**:

| パターン | 利点 | 欠点 |
|---|---|---|
| (A) 単一巨大 if-else | シンプル | 機能追加で本体改修・モジュール境界破壊 |
| (B) `instanceof` チェーン | enum 不要 | 型階層に縛られる・テスト困難 |
| (C) **Strategy + Registry** | モジュール独立・追加容易・テスト独立 | 初期構築コスト |
| (D) Spring `ApplicationContext.getBean(...)` | 動的解決可能 | 名前解決ミスを実行時まで検出できない |

→ **(C) を採用**。Spring の `Map<ReferenceType, ContentVisibilityResolver<?>>` を `@Autowired` で組み立て可能（DI コンテナ標準機能）。

### 4.3 中核インターフェース定義

```java
package com.mannschaft.app.common.visibility;

/**
 * 個別 reference_type 用の可視性判定 Strategy。
 * 1 reference_type につき 1 つの実装クラスを置く。
 *
 * @param <V> 機能固有の visibility 型（StandardVisibility に正規化される前の値）
 */
public interface ContentVisibilityResolver<V> {

    /** この Resolver が担当する reference_type を返す。 */
    ReferenceType referenceType();

    /**
     * 単発判定。N+1 を避けるため、複数 ID の判定では batch を使うこと。
     */
    boolean canView(Long contentId, Long viewerUserId);

    /**
     * バッチ判定。実装は SQL 数 ≦ 2 で完結すべき
     * (1 回の SELECT で必要なメタデータを一括取得し、メモリ上で判定)。
     *
     * @return アクセス可能な contentId の Set。要素順は保証しない。
     */
    Set<Long> filterAccessible(Collection<Long> contentIds, Long viewerUserId);

    /**
     * 詳細判定理由を返すデバッグ・監査用 API（任意実装）。
     * デフォルト実装は canView の結果を VisibilityDecision にラップして返す。
     */
    default VisibilityDecision decide(Long contentId, Long viewerUserId) {
        boolean ok = canView(contentId, viewerUserId);
        return ok
            ? VisibilityDecision.allow(referenceType(), contentId)
            : VisibilityDecision.deny(referenceType(), contentId, DenyReason.UNSPECIFIED);
    }
}
```

### 4.4 ファサード `ContentVisibilityChecker`

```java
package com.mannschaft.app.common.visibility;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentVisibilityChecker {

    private final Map<ReferenceType, ContentVisibilityResolver<?>> resolverMap;
    private final MeterRegistry meterRegistry;

    /**
     * Spring が ContentVisibilityResolver の全 Bean を集めて Map<String, Bean> を渡す。
     * Constructor で referenceType() をキーに変換する。
     */
    public ContentVisibilityChecker(
            List<ContentVisibilityResolver<?>> resolvers,
            MeterRegistry meterRegistry) {
        this.resolverMap = resolvers.stream()
            .collect(Collectors.toUnmodifiableMap(
                ContentVisibilityResolver::referenceType,
                Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException(
                        "duplicate resolver for " + a.referenceType());
                }));
        this.meterRegistry = meterRegistry;
    }

    public boolean canView(ReferenceType type, Long contentId, Long userId) {
        Resolver<?> resolver = resolverMap.get(type);
        if (resolver == null) {
            // §11.2 フォールバック: 未対応 type は閲覧不可とみなす（fail-closed）
            recordUnsupported(type);
            return false;
        }
        return timed(type, "canView", () -> resolver.canView(contentId, userId));
    }

    public Set<Long> filterAccessible(
            ReferenceType type, Collection<Long> ids, Long userId) {
        Resolver<?> resolver = resolverMap.get(type);
        if (resolver == null) {
            recordUnsupported(type);
            return Set.of(); // fail-closed
        }
        return timed(type, "filterAccessible",
            () -> resolver.filterAccessible(ids, userId));
    }

    public Map<ReferenceType, Set<Long>> filterAccessibleByType(
            Map<ReferenceType, ? extends Collection<Long>> idsByType,
            Long userId) {
        Map<ReferenceType, Set<Long>> result = new EnumMap<>(ReferenceType.class);
        idsByType.forEach((type, ids) -> {
            result.put(type, filterAccessible(type, ids, userId));
        });
        return result;
    }

    /**
     * 未対応 type の記録。tag cardinality 爆発防止のため最大 100 種類で打ち切る。
     * 100 を超えた場合は tag を "OVERFLOW" に集約。
     * DB 由来の不明 type は別経路で WARN ログ + Slack 通知（VisibilityMetrics 側で処理）。
     */
    private void recordUnsupported(ReferenceType type) {
        // VisibilityMetrics.recordUnsupported(type) に委譲（cardinality 制御）
        log.debug("Unsupported referenceType={} caller={}",
            type, Thread.currentThread().getStackTrace()[3]);
        visibilityMetrics.recordUnsupported(type);
    }
}
```

**重要**: SystemAdmin 高速パスを **このファサードでは持たない**。理由は §15 D-13 参照。各 Resolver が `existingIds()` で実存確認した後に SystemAdmin 判定を行い、「実在する ID だけを返す」セマンティクスを保つ（IDOR 矛盾回避）。

### 4.5 個別 Resolver の典型実装

```java
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogPostVisibilityResolver
        implements ContentVisibilityResolver<com.mannschaft.app.cms.Visibility> {

    private final BlogPostRepository blogPostRepository;
    private final AccessControlService accessControlService;
    private final VisibilityTemplateEvaluator templateEvaluator;

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.BLOG_POST;
    }

    @Override
    public boolean canView(Long contentId, Long viewerUserId) {
        return filterAccessible(List.of(contentId), viewerUserId)
            .contains(contentId);
    }

    @Override
    public Set<Long> filterAccessible(
            Collection<Long> ids, Long viewerUserId) {
        if (ids.isEmpty()) return Set.of();

        // 1) 実存確認込みの一括取得（SQL 1 回）
        //    存在しない id は rows に含まれないので IDOR セマンティクス OK
        List<BlogPostVisibilityProjection> rows =
            blogPostRepository.findVisibilityProjectionsByIdIn(ids);
        if (rows.isEmpty()) return Set.of();

        // 2) status × visibility の合成 (§7.5)
        //    DELETED/ARCHIVED は SystemAdmin 以外不可視
        //    DRAFT/SCHEDULED は author のみ可視
        boolean isSysAdmin = accessControlService.isSystemAdmin(viewerUserId);

        // 3) MEMBERS_ONLY/SUPPORTERS_AND_ABOVE/ORGANIZATION_WIDE 用に
        //    必要なスコープ集合を算出 → ロール情報を 1 SQL でバルク取得
        Set<ScopeKey> requiredScopes = collectMemberScopeKeys(rows);
        Set<ScopeKey> orgScopes = collectOrgScopeKeys(rows);

        UserScopeRoleSnapshot snapshot = membershipBatchService
            .snapshotForUser(viewerUserId, requiredScopes, orgScopes);
        // ↑ SQL 2-3 回で完結:
        //   ① user_roles WHERE user_id=? AND (team_id IN ... OR organization_id IN ...)
        //   ② teams WHERE id IN ... の親 organization_id JOIN（必要なら）
        //   ③ system_admin フラグ（既に snapshot に集約）

        // 4) 各 row を判定（DB アクセスなし、純メモリ）
        return rows.stream()
            .filter(row -> visibleByStatus(row, viewerUserId, isSysAdmin))
            .filter(row -> evaluate(row, viewerUserId, snapshot))
            .map(BlogPostVisibilityProjection::id)
            .collect(Collectors.toSet());
    }

    /** §7.5 status × visibility 合成のフィルタ */
    private boolean visibleByStatus(
            BlogPostVisibilityProjection row,
            Long viewerUserId, boolean isSysAdmin) {
        ContentStatus s = BlogPostStatusMapper.toStandard(row.status());
        return switch (s) {
            case DELETED -> false;                 // 誰でも不可視
            case ARCHIVED -> isSysAdmin;           // SystemAdmin のみ
            case DRAFT, SCHEDULED ->
                Objects.equals(viewerUserId, row.authorUserId()) || isSysAdmin;
            case PUBLISHED -> true;                // visibility 評価へ進む
        };
    }

    /** §5 StandardVisibility 評価（DB アクセスなし、snapshot のみ参照） */
    private boolean evaluate(
            BlogPostVisibilityProjection row,
            Long viewerUserId,
            UserScopeRoleSnapshot snapshot) {
        if (snapshot.isSystemAdmin()) return true;

        StandardVisibility std = CmsVisibilityMapper.toStandard(row.visibility());
        ScopeKey scope = new ScopeKey(row.scopeType(), row.scopeId());
        return switch (std) {
            case PUBLIC -> true;
            case MEMBERS_ONLY -> snapshot.isMemberOf(scope);
            case SUPPORTERS_AND_ABOVE ->
                snapshot.hasRoleOrAbove(scope, "SUPPORTER");
            case ADMINS_ONLY -> snapshot.hasRoleOrAbove(scope, "ADMIN");
            case ORGANIZATION_WIDE -> snapshot.isMemberOfParentOrg(scope);
            case PRIVATE -> Objects.equals(viewerUserId, row.authorUserId());
            case CUSTOM_TEMPLATE -> templateEvaluator.canView(
                viewerUserId, row.visibilityTemplateId(), row.authorUserId());
            case FOLLOWERS_ONLY -> followBatchService
                .isFollower(viewerUserId, row.authorUserId());
            case CUSTOM, FOLLOWERS_ONLY -> false; // 機能側で個別 override
        };
    }
}
```

**重要ポイント**:
- `MembershipBatchQueryService.snapshotForUser()` を **新規共通基盤として §10.2 で追加** する。これにより複数 scope に跨る判定が SQL ≦ 3 回で完結する。
- SystemAdmin 判定は `snapshot` 構築時に 1 回引いて使い回す（ファサードでは事前判定しない、§15 D-13）。
- `snapshot` は `@RequestScope` ではなく Resolver 呼び出しごとのローカル値（再入可能性のため）。同 Tx 内でのメモ化は §9.3 `MembershipQueryCache` が補完する。

### 4.6 AbstractContentVisibilityResolver — Resolver 共通テンプレート

§4.5 のサンプルを 13 個コピペすると ~1300 行の重複が生まれる。これを避けるため、共通テンプレートメソッドパターンの抽象基底クラスを **Phase A 成果物として必ず実装する**:

```java
package com.mannschaft.app.common.visibility;

/**
 * Resolver 共通骨格。各機能側は loadProjections / toStandard / 任意の
 * evaluateCustom のみ実装する。
 *
 * @param <V> 機能固有 visibility 型
 * @param <P> Visibility Projection 型 (status, visibility, scopeId/Type, authorUserId 等を含む)
 */
@RequiredArgsConstructor
public abstract class AbstractContentVisibilityResolver<V extends Enum<V>, P extends VisibilityProjection>
        implements ContentVisibilityResolver<V> {

    protected final MembershipBatchQueryService membershipBatchQueryService;
    protected final VisibilityTemplateEvaluator templateEvaluator;
    protected final FollowBatchService followBatchService; // 任意（FOLLOWERS_ONLY 不使用なら null 可）

    /** 機能側 Repository から実存 Projection を一括取得 (SQL 1) */
    protected abstract List<P> loadProjections(Collection<Long> ids);

    /** 機能側 Visibility 値を StandardVisibility に正規化 */
    protected abstract StandardVisibility toStandard(V visibility);

    /** 機能側 status を ContentStatus に正規化 (デフォルト: PUBLISHED 固定) */
    protected ContentStatus toContentStatus(P row) {
        return ContentStatus.PUBLISHED;
    }

    /**
     * StandardVisibility が CUSTOM のとき、または独自セマンティクスの値で
     * 機能ごとの個別判定が必要な場合のみオーバーライド。
     * デフォルトは fail-closed (false)。
     */
    protected boolean evaluateCustom(
            P row, Long viewerUserId, UserScopeRoleSnapshot snapshot) {
        return false;
    }

    /** Phase 1 では呼ばれないが、将来コンテナ型 Resolver 用に提供 */
    protected ContentVisibilityChecker checker() { return null; }

    @Override
    public final boolean canView(Long contentId, Long viewerUserId) {
        return filterAccessible(List.of(contentId), viewerUserId).contains(contentId);
    }

    @Override
    public final Set<Long> filterAccessible(
            Collection<Long> ids, Long viewerUserId) {
        if (ids == null || ids.isEmpty()) return Set.of();

        // 1) 実存確認込み射影取得（SQL 1）
        List<P> rows = loadProjections(ids);
        if (rows.isEmpty()) return Set.of();

        // 2) 必要スコープを集計
        Set<ScopeKey> directScopes = collectDirectScopes(rows);
        Set<ScopeKey> orgWideScopes = collectOrgWideScopes(rows);

        // 3) snapshot 構築 (SQL 1-3)
        UserScopeRoleSnapshot snapshot = membershipBatchQueryService
            .snapshotForUser(viewerUserId, directScopes, orgWideScopes);

        // 4) 各 row の判定（DB アクセスなし、純メモリ）
        return rows.stream()
            .filter(row -> visibleByStatus(row, viewerUserId, snapshot))
            .filter(row -> visibleByVisibility(row, viewerUserId, snapshot))
            .map(VisibilityProjection::id)
            .collect(Collectors.toSet());
    }

    /** §7.5 status × visibility 合成のフィルタ */
    private boolean visibleByStatus(
            P row, Long viewerUserId, UserScopeRoleSnapshot snapshot) {
        ContentStatus s = toContentStatus(row);
        return switch (s) {
            case DELETED -> false;
            case ARCHIVED -> snapshot.isSystemAdmin();
            case DRAFT, SCHEDULED -> snapshot.isSystemAdmin()
                || Objects.equals(viewerUserId, row.authorUserId());
            case PUBLISHED -> true;
        };
    }

    /** §11.6 親 ORG 連鎖チェック付き visibility 判定 */
    private boolean visibleByVisibility(
            P row, Long viewerUserId, UserScopeRoleSnapshot snapshot) {
        if (snapshot.isSystemAdmin()) return true;

        // §11.6 親 ORG 非アクティブなら即不可視
        ScopeKey scope = new ScopeKey(row.scopeType(), row.scopeId());
        if (snapshot.isParentOrgInactive(scope)) return false;

        StandardVisibility std = toStandard((V) row.visibility());
        return switch (std) {
            case PUBLIC -> true;
            case MEMBERS_ONLY -> snapshot.isMemberOf(scope);
            case SUPPORTERS_AND_ABOVE -> snapshot.hasRoleOrAbove(scope, "SUPPORTER");
            case ADMINS_ONLY -> snapshot.hasRoleOrAbove(scope, "ADMIN");
            case ORGANIZATION_WIDE -> snapshot.isMemberOfParentOrg(scope);
            case PRIVATE -> Objects.equals(viewerUserId, row.authorUserId());
            case CUSTOM_TEMPLATE -> templateEvaluator.canView(
                viewerUserId, row.visibilityTemplateId(), row.authorUserId());
            case FOLLOWERS_ONLY -> followBatchService != null
                && followBatchService.isFollower(viewerUserId, row.authorUserId());
            case CUSTOM -> evaluateCustom(row, viewerUserId, snapshot);
        };
    }

    private Set<ScopeKey> collectDirectScopes(List<P> rows) {
        return rows.stream()
            .map(r -> new ScopeKey(r.scopeType(), r.scopeId()))
            .collect(Collectors.toSet());
    }

    private Set<ScopeKey> collectOrgWideScopes(List<P> rows) {
        // ORGANIZATION_WIDE が選ばれている row のみ親解決対象
        return rows.stream()
            .filter(r -> toStandard((V) r.visibility()) == StandardVisibility.ORGANIZATION_WIDE)
            .map(r -> new ScopeKey(r.scopeType(), r.scopeId()))
            .collect(Collectors.toSet());
    }
}
```

#### VisibilityProjection 共通インターフェース

```java
public interface VisibilityProjection {
    Long id();
    String scopeType();          // "TEAM" / "ORGANIZATION"
    Long scopeId();
    Long authorUserId();         // null 可
    Long visibilityTemplateId(); // CUSTOM_TEMPLATE 時のみ非 null
    Object visibility();         // 機能側 enum 値
}
```

各機能の Projection (例: `BlogPostVisibilityProjection`) は VisibilityProjection を実装する。

#### 抽象基底クラス採用後の Resolver 実装サマリ

```java
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogPostVisibilityResolver
        extends AbstractContentVisibilityResolver<
            com.mannschaft.app.cms.Visibility, BlogPostVisibilityProjection> {

    private final BlogPostRepository blogPostRepository;

    @Override public ReferenceType referenceType() { return ReferenceType.BLOG_POST; }

    @Override
    protected List<BlogPostVisibilityProjection> loadProjections(Collection<Long> ids) {
        return blogPostRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(com.mannschaft.app.cms.Visibility v) {
        return CmsVisibilityMapper.toStandard(v);
    }

    @Override
    protected ContentStatus toContentStatus(BlogPostVisibilityProjection row) {
        return BlogPostStatusMapper.toStandard(row.status());
    }
    // CUSTOM 値なし、evaluateCustom はオーバーライド不要
}
```

→ Resolver 本体クラスは **約 25 行** で完結。NF-3 「≦ 30 行」の真の達成は本抽象基底クラスの導入が前提。

---

## 5. StandardVisibility と既存 enum の対応

### 5.1 StandardVisibility 定義

```java
package com.mannschaft.app.common.visibility;

/**
 * 機能横断で扱う標準可視性レベル。
 * 機能固有 enum は Mapper でこの enum に正規化される。
 */
public enum StandardVisibility {
    /** 誰でも閲覧可能。**未認証ユーザー (userId=null) も閲覧可** (§17.Q1 マスター裁可済) */
    PUBLIC,

    /** スコープ (TEAM/ORGANIZATION) の所属メンバーのみ
     *  包含: ADMIN/DEPUTY_ADMIN/MEMBER/SUPPORTER/GUEST のうちロール保有者すべて */
    MEMBERS_ONLY,

    /** SUPPORTER 以上のロール保有者
     *  包含: ADMIN/DEPUTY_ADMIN/MEMBER/SUPPORTER (= GUEST 以外の全認証メンバー)
     *  AccessControlService.hasRoleOrAbove(..., "SUPPORTER") と同等 */
    SUPPORTERS_AND_ABOVE,

    /** ADMIN ロールのみ
     *  包含: ADMIN/DEPUTY_ADMIN
     *  AccessControlService.isAdminOrAbove(...) と同等 */
    ADMINS_ONLY,

    /** 作成者本人のみ */
    PRIVATE,

    /** SNS のフォロワー関係に基づく公開 (社会機能 F04.x 専用) */
    FOLLOWERS_ONLY,

    /** F01.7 カスタムテンプレートによる公開 (visibility_template_id 必須) */
    CUSTOM_TEMPLATE,

    /**
     * スコープ全体の組織メンバーへ公開
     * TEAM スコープのコンテンツでも親 ORG の所属メンバーまで広げる。
     * 親スコープ解決規約は §5.1.1 を参照。
     */
    ORGANIZATION_WIDE,

    /**
     * 上記いずれにも該当しない機能独自のセマンティクス。
     * Resolver 内で個別ハンドリングが必要。
     * 例: Survey の AFTER_RESPONSE (時間軸条件)、Committee の NAME_ONLY (部分公開)
     */
    CUSTOM
}
```

### 5.1.1 親スコープ解決規約

`ORGANIZATION_WIDE` および将来の親スコープ参照のために、以下を共通基盤に置く:

```java
package com.mannschaft.app.common.visibility;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScopeAncestorResolver {

    private final TeamRepository teamRepository;

    /**
     * TEAM スコープ集合に対する親 organization_id を 1 SQL でバルク解決。
     * ORGANIZATION スコープはそのまま返す。
     */
    public Map<ScopeKey, Long> resolveParentOrgIds(Set<ScopeKey> scopes) {
        Set<Long> teamIds = scopes.stream()
            .filter(s -> "TEAM".equals(s.scopeType()))
            .map(ScopeKey::scopeId)
            .collect(Collectors.toSet());
        if (teamIds.isEmpty()) return Map.of();

        // SQL: SELECT id, organization_id FROM teams WHERE id IN (?) AND deleted_at IS NULL
        Map<Long, Long> teamToOrg = teamRepository
            .findOrganizationIdByTeamIdIn(teamIds);

        Map<ScopeKey, Long> result = new HashMap<>();
        scopes.forEach(s -> {
            if ("TEAM".equals(s.scopeType())) {
                Long orgId = teamToOrg.get(s.scopeId());
                if (orgId != null) result.put(s, orgId);
            } else if ("ORGANIZATION".equals(s.scopeType())) {
                result.put(s, s.scopeId());
            }
        });
        return result;
    }
}
```

このリゾルバを `MembershipBatchQueryService` 内で使い、`snapshot.isMemberOfParentOrg(scope)` を実装する。

### 5.1.2 親スコープ非公開時の連鎖ルール

§11.6 を参照。要旨: 親 ORG が `DELETED/SUSPENDED` 状態のとき、配下 TEAM コンテンツは fail-closed で SystemAdmin 以外不可視。

### 5.1.3 StandardVisibility 値追加時のコスト

将来 StandardVisibility に新値を追加する場合の影響範囲:

| 影響先 | 触れる規模 | 自動検出 |
|---|---|---|
| Mapper 17 個 | 各 1 case 追加 (5 行 × 17 = ~85 行) | ✅ exhaustive switch でコンパイルエラー |
| `AbstractContentVisibilityResolver` の `visibleByVisibility` switch | 1 case 追加 (5 行) | ✅ コンパイルエラー |
| `UserScopeRoleSnapshot` のメソッド追加 | 必要なら 1 メソッド (10 行) | ❌ 手動検出 |
| 統合テスト網羅 | 13 Resolver × 2 ケース = 26 ケース | ❌ 手動検出 |
| 設計書 §5.1 / §5.2 / §15 D-2 改訂 | 数十行 | ❌ 手動検出 |

→ 値追加は **本設計書の改訂 (v0.x → v1.x マイナー or メジャー) を伴う作業** と位置付ける。軽率な追加は禁止。

### 5.1.4 CUSTOM 値の運用規約

`CUSTOM` は escape hatch だが乱用すると共通基盤の意味が薄れる。以下を運用ルールとする:

- **比率上限**: 全機能 enum 値のうち CUSTOM に流れる比率が **30% を超えたら** StandardVisibility 値追加の議題化を必須化
  - v0.3 時点: 全機能値 ~50 個中 CUSTOM 行き 8 個 = 16% (許容内)
- **新規機能の CUSTOM 禁止**: 新機能設計時に初手から CUSTOM を選ぶことは禁止。必ず StandardVisibility での表現を試みる
- **Resolver 内の CUSTOM 分岐コード上限**: 1 値あたり 30 行以下。超える場合は別 ReferenceType への分割を検討
- **メトリクス可視化**: `content_visibility.custom_dispatch_count{referenceType, customSubType}` を §9.4 に追加し、CUSTOM 利用の偏りを可視化

これらは `BACKEND_CODING_CONVENTION.md` にも反映する (§19.4)。

### 5.2 既存 enum → StandardVisibility 対応表

| 既存 enum | 値 | → StandardVisibility | 備考 |
|---|---|---|---|
| `cms.Visibility` | PUBLIC | PUBLIC | |
| | MEMBERS_ONLY | MEMBERS_ONLY | |
| | SUPPORTERS_AND_ABOVE | SUPPORTERS_AND_ABOVE | |
| | FOLLOWERS_ONLY | FOLLOWERS_ONLY | |
| | PRIVATE | PRIVATE | |
| | CUSTOM_TEMPLATE | CUSTOM_TEMPLATE | |
| `event.entity.EventVisibility` | PUBLIC | PUBLIC | |
| | MEMBERS_ONLY | MEMBERS_ONLY | |
| | SUPPORTERS_AND_ABOVE | SUPPORTERS_AND_ABOVE | |
| `activity.ActivityVisibility` | PUBLIC | PUBLIC | |
| | MEMBERS_ONLY | MEMBERS_ONLY | |
| `tournament.TournamentVisibility` | PUBLIC | PUBLIC | |
| | MEMBERS_ONLY | MEMBERS_ONLY | |
| `timetable.TimetableVisibility` | PUBLIC | PUBLIC | |
| | MEMBERS_ONLY | MEMBERS_ONLY | |
| `member.PageVisibility` | PUBLIC | PUBLIC | |
| | MEMBERS_ONLY | MEMBERS_ONLY | |
| `todo.ProjectVisibility` | PRIVATE | PRIVATE | |
| | MEMBERS_ONLY | MEMBERS_ONLY | |
| | PUBLIC | PUBLIC | |
| `schedule.ScheduleVisibility` | MEMBERS_ONLY | MEMBERS_ONLY | |
| | ORGANIZATION | ORGANIZATION_WIDE | |
| | CUSTOM_TEMPLATE | CUSTOM_TEMPLATE | |
| `recruitment.RecruitmentVisibility` | PUBLIC | PUBLIC | |
| | SCOPE_ONLY | MEMBERS_ONLY | semantically equivalent |
| | SUPPORTERS_ONLY | SUPPORTERS_AND_ABOVE | |
| | CUSTOM_TEMPLATE | CUSTOM_TEMPLATE | |
| `jobmatching.enums.VisibilityScope` | TEAM_MEMBERS | MEMBERS_ONLY | |
| | TEAM_MEMBERS_SUPPORTERS | SUPPORTERS_AND_ABOVE | |
| | JOBBER_INTERNAL | CUSTOM | JOBBER ロール限定（Resolver 内で個別処理） |
| | JOBBER_PUBLIC_BOARD | PUBLIC | |
| | ORGANIZATION_SCOPE | ORGANIZATION_WIDE | |
| | CUSTOM_TEMPLATE | CUSTOM_TEMPLATE | |
| `gallery.AlbumVisibility` | ALL_MEMBERS | MEMBERS_ONLY | |
| | SUPPORTERS_AND_ABOVE | SUPPORTERS_AND_ABOVE | |
| | ADMIN_ONLY | ADMINS_ONLY | |
| `actionmemo.enums.OrgVisibility` | TEAM_ONLY | MEMBERS_ONLY | |
| | ORG_WIDE | ORGANIZATION_WIDE | |
| `committee.entity.CommitteeVisibility` | HIDDEN | PRIVATE | "見えない" 扱いを PRIVATE 相当に |
| | NAME_ONLY | CUSTOM | 部分公開（名前のみ）= Resolver 個別 |
| | NAME_AND_PURPOSE | CUSTOM | 部分公開 = Resolver 個別 |
| `survey.ResultsVisibility` | AFTER_RESPONSE | CUSTOM | 時間軸条件、Resolver 個別 |
| | AFTER_CLOSE | CUSTOM | 時間軸条件、Resolver 個別 |
| | ADMINS_ONLY | ADMINS_ONLY | |
| | VIEWERS_ONLY | CUSTOM | 限定リスト、Resolver 個別 |
| `survey.UnrespondedVisibility` | HIDDEN | PRIVATE | |
| | CREATOR_AND_ADMIN | CUSTOM | 作成者または ADMIN |
| | ALL_MEMBERS | MEMBERS_ONLY | |
| `notification.confirmable.entity.UnconfirmedVisibility` | HIDDEN | PRIVATE | |
| | CREATOR_AND_ADMIN | CUSTOM | 同上 |
| | ALL_MEMBERS | MEMBERS_ONLY | |
| `matching.MatchVisibility` | PLATFORM | PUBLIC | プラットフォーム全体 |
| | ORGANIZATION | ORGANIZATION_WIDE | |
| `social.FollowListVisibility` | (本基盤対象外) | - | §16.2 |
| `contact.OnlineVisibility` | (本基盤対象外) | - | §16.2 |
| `timetable.personal.PersonalTimetableVisibility` | (本基盤対象外) | - | §16.2 |
| `organization.ProfileVisibility` | (Record、本基盤対象外) | - | §16.2 |

### 5.3 Mapper クラスの配置

```
backend/src/main/java/com/mannschaft/app/common/visibility/mapping/
├── CmsVisibilityMapper.java        # cms.Visibility ⇄ StandardVisibility
├── EventVisibilityMapper.java
├── ActivityVisibilityMapper.java
├── ScheduleVisibilityMapper.java
├── ...（13 個）
└── package-info.java               # 「機能側 enum を共通基盤に正規化する責務」
```

各 Mapper は static メソッドのみ:

```java
public final class CmsVisibilityMapper {
    private CmsVisibilityMapper() {}

    public static StandardVisibility toStandard(com.mannschaft.app.cms.Visibility v) {
        return switch (v) {
            case PUBLIC -> StandardVisibility.PUBLIC;
            case MEMBERS_ONLY -> StandardVisibility.MEMBERS_ONLY;
            case SUPPORTERS_AND_ABOVE -> StandardVisibility.SUPPORTERS_AND_ABOVE;
            case FOLLOWERS_ONLY -> StandardVisibility.FOLLOWERS_ONLY;
            case PRIVATE -> StandardVisibility.PRIVATE;
            case CUSTOM_TEMPLATE -> StandardVisibility.CUSTOM_TEMPLATE;
        };
    }
}
```

→ Mapper は片方向のみ（StandardVisibility → 機能側は不要、UI から保存する値は機能側 enum をそのまま使う）。

### 5.4 機能側 enum 改廃時の手順

機能チームが `cms.Visibility` 等に値を追加する際の標準フロー（共通基盤との整合維持のため必須）:

1. 機能側 enum に値追加 (例: `cms.Visibility.NEW_VALUE`)
2. **コンパイラがエラー指摘** → 該当 Mapper の switch 修正
3. StandardVisibility で表せるなら既存値にマップ、表せないなら `CUSTOM` に流す
4. CUSTOM の場合、対応 Resolver の `evaluateCustom()` に case 追加
5. ガード単体テスト追加（新値のパターンが各 viewer ロールに対して期待通りの可視性になるか）
6. CUSTOM 比率が §5.1.4 の閾値を超えたら StandardVisibility 値追加軍議を起票
7. PR 作成時、本設計書 §5.2 対応表にも 1 行追加

この手順は `backend/.claudecode.md` および `BACKEND_CODING_CONVENTION.md` にも転記する (§19.4)。

---

## 6. ReferenceType と Resolver の対応

### 6.1 ReferenceType enum

```java
package com.mannschaft.app.common.visibility;

/**
 * コルクボード・通知・検索など、
 * 横断的に参照されるコンテンツの種別。
 *
 * 既存の corkboard_card_reference テーブルの reference_type カラム
 * (VARCHAR(30), F09.8.1) と値を一致させること。
 */
public enum ReferenceType {
    BLOG_POST,
    EVENT,
    ACTIVITY_RESULT,
    SCHEDULE,
    TIMELINE_POST,
    BULLETIN_THREAD,
    TOURNAMENT,
    RECRUITMENT_LISTING,
    JOB_POSTING,
    SURVEY,
    PHOTO_ALBUM,
    TEAM,
    ORGANIZATION,

    // 将来追加候補（Resolver が無い間は fail-closed)
    CHAT_MESSAGE,
    FILE,
    DOCUMENT,
    URL
}
```

### 6.2 Resolver 実装の配置原則

各 Resolver は **その reference_type を所有する機能パッケージ配下** に置く。共通基盤側に集めない。

```
backend/src/main/java/com/mannschaft/app/cms/visibility/BlogPostVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/event/visibility/EventVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/schedule/visibility/ScheduleVisibilityResolver.java
...
```

理由:
- 機能の凝集度を保つ（Visibility ロジックも機能境界の内側）
- パッケージ依存方向: `feature → common` を維持（逆向き禁止）
- 機能削除時に Resolver も同梱削除可能

### 6.3 新 referenceType 追加手順 (Strategy 拡張)

#### 6.3.1 最小手順 (Resolver 本体クラスのみ)

`AbstractContentVisibilityResolver` を継承する場合、本体クラスは **約 25-30 行** で完結:

1. `ReferenceType` enum に値追加（1 行）
2. 機能パッケージに `XyzVisibilityResolver extends AbstractContentVisibilityResolver<XyzVisibility, XyzVisibilityProjection>` を作成
3. `@Component` を付与（Spring が自動で `ContentVisibilityChecker` に登録）
4. `loadProjections` / `toStandard` / `toContentStatus` を override

→ ContentVisibilityChecker 本体は **無改修** で済む。

#### 6.3.2 完全手順 (テスト・規約・統合まで含む)

実際の PR で必要な全作業（推定 200〜400 行、実装工数 0.5〜1 日）:

| # | 作業 | 規模 | 担当 |
|---|---|---|---|
| 1 | `ReferenceType` enum に値追加 | 1 行 | 設計者 |
| 2 | `XyzVisibilityResolver` 本体（最小手順） | ~25 行 | 機能担当 |
| 3 | `XyzVisibilityProjection` Record 追加 | 5〜10 行 | 機能担当 |
| 4 | `XyzVisibilityMapper` static class 追加（既存 enum あれば） | 15〜30 行 | 機能担当 |
| 5 | `XyzStatusMapper` 追加（status 軸あれば） | 15〜30 行 | 機能担当 |
| 6 | 機能 Repository に `findVisibilityProjectionsByIdIn` + JPQL | 10〜20 行 | 機能担当 |
| 7 | `XyzVisibilityResolverTest`（単体・SUT + Mock） | 80〜150 行 | 機能担当 |
| 8 | `XyzVisibilityResolverIntegrationTest`（実 DB） | 50〜100 行 | 機能担当 |
| 9 | Mapper の param 化テスト | 10〜20 行 | 機能担当 |
| 10 | 通知発行する機能なら `*VisibilityGuardTest`（§13.5） | 30〜50 行 | 機能担当 |
| 11 | ArchUnit テストが新 type を認識する構成確認 | 確認のみ | 設計者 |
| 12 | `corkboard_card_reference.reference_type` の DB 制約に値追加（必要なら DDL） | 1 行 | 設計者 |
| 13 | F09.8.1 設計書の reference_type 表に追記 | 1 行 | 設計者 |
| 14 | 本設計書 §3.3 表に追記 | 1 行 | 設計者 |

---

## 7. API 設計

### 7.1 公開 API サマリ

| API | 用途 | 想定 SQL 数 |
|---|---|---|
| `boolean canView(ReferenceType, Long contentId, Long userId)` | 単発判定 (詳細画面など) | ≦ 2 |
| `Set<Long> filterAccessible(ReferenceType, Collection<Long>, Long userId)` | 同 type バッチ判定 (一覧画面) | ≦ 2 |
| `Map<ReferenceType, Set<Long>> filterAccessibleByType(Map<RT, Coll<Long>>, Long userId)` | 複数 type ミックス判定 (コルクボード) | ≦ 2 × type 数 |
| `VisibilityDecision decide(ReferenceType, Long contentId, Long userId)` | 監査・デバッグ用詳細判定 | 任意 |
| `void assertCanView(ReferenceType, Long contentId, Long userId)` | 例外スロー版 (Controller 入口など) | ≦ 2 |

### 7.2 VisibilityDecision

```java
public record VisibilityDecision(
    ReferenceType referenceType,
    Long contentId,
    boolean allowed,
    DenyReason denyReason,           // allowed=true なら null
    StandardVisibility resolvedLevel, // null 可（CUSTOM 等）
    String detail                    // 任意の説明（監査ログ用）
) {
    public static VisibilityDecision allow(ReferenceType t, Long id) { ... }
    public static VisibilityDecision deny(
        ReferenceType t, Long id, DenyReason r) { ... }
}

public enum DenyReason {
    NOT_FOUND,                  // コンテンツ存在しない
    NOT_A_MEMBER,
    INSUFFICIENT_ROLE,
    NOT_OWNER,
    TEMPLATE_RULE_NO_MATCH,
    UNSUPPORTED_REFERENCE_TYPE,
    UNSPECIFIED
}
```

### 7.3 例外と返却値の方針

- **見つからないコンテンツ**: `decide()` は `DenyReason.NOT_FOUND` で返す（例外なし）。`canView()` は `false`、`filterAccessible()` は当該 ID を Set に入れない。
- **権限不足**: `canView()` は `false`、`assertCanView()` は `BusinessException(VisibilityErrorCode.VISIBILITY_001)`。
- **未対応 ReferenceType**: fail-closed (false / 空 Set)。Micrometer メトリクスに記録（§9.4）。

### 7.4 エラーコード

| Code | 状況 | HTTP | ja メッセージ案 |
|---|---|---|---|
| `VISIBILITY_001` | 認可拒否（権限不足） | 403 | 「このコンテンツを閲覧する権限がありません」 |
| `VISIBILITY_002` | 不正な reference_type | 400 | 「指定された参照種別が無効です」 |
| `VISIBILITY_003` | 内部 Resolver エラー | 500 | 「閲覧可否の判定中にエラーが発生しました」 |
| `VISIBILITY_004` | 対象コンテンツ不在 (DenyReason.NOT_FOUND) | 404 | 「指定のコンテンツが見つかりません」 |

#### 7.4.1 i18n メッセージ要件

CLAUDE.md i18n ルールに従い、6 言語対応必須:
- 登録先: `backend/src/main/resources/messages_{ja,en,zh,ko,es,de}.properties`
- 各エラーコード × 6 言語 = 24 メッセージを Phase A で登録（A-6 工程）
- 翻訳が間に合わない場合は ja の値を流用（後続軍議で翻訳）
- 404 マスク時（§17.Q2 で 403 と 404 を統一する場合）は VISIBILITY_001/004 とも同じメッセージ「指定のコンテンツが見つかりません」を返却

定義先: `backend/src/main/java/com/mannschaft/app/common/visibility/VisibilityErrorCode.java`

`assertCanView()` の例外スロー方針:
- 内部で `decide()` を呼ぶ
- `DenyReason.NOT_FOUND` → `VISIBILITY_004` (404)
- それ以外の deny → `VISIBILITY_001` (403)
- これにより 404/403 が DenyReason 単位で分岐する。最終的に外部レスポンスで統一 (404 マスク) するかは Controller 責務。

メソッド命名: `assertCanView` のみを提供する（v0.3 整理、§15 D-5 参照）。AccessControlService 慣行 (`checkXxx` = void throw) との整合は本メソッドで取る。エイリアス `checkCanView` は混乱回避のため作らない。

### 7.5 status と visibility の合成

Visibility だけでは「下書き / アーカイブ / 削除済み」のような状態軸を表せない。本基盤は status 軸も標準化し、`canView` を「visibility の許可 ∧ status の公開状態」の AND と定義する。

#### ContentStatus 標準カテゴリ

```java
package com.mannschaft.app.common.visibility;

/**
 * コンテンツの公開状態。各機能の status enum は Mapper でこの enum に正規化される。
 */
public enum ContentStatus {
    /** 作成中・未公開。author と SystemAdmin のみ可視 */
    DRAFT,

    /** 公開予約中 (scheduled_publish_at が未来)。author と SystemAdmin のみ可視 */
    SCHEDULED,

    /** 公開中。visibility 評価に進む */
    PUBLISHED,

    /** アーカイブ済み (利用者から非表示)。SystemAdmin のみ可視 */
    ARCHIVED,

    /** 論理削除済み。誰でも不可視 (fail-closed) */
    DELETED
}
```

#### 各機能 status の正規化

| 機能 status | → ContentStatus |
|---|---|
| BlogPost: `DRAFT` | DRAFT |
| BlogPost: `PUBLISHED` | PUBLISHED |
| BlogPost: `ARCHIVED` | ARCHIVED |
| BlogPost: `deleted_at IS NOT NULL` | DELETED |
| Event: `PUBLISHED`, `published_at < now` | PUBLISHED |
| Event: `PUBLISHED`, `published_at > now` | SCHEDULED |
| Event: `CANCELLED` | ARCHIVED |
| Schedule: `DRAFT` | DRAFT |
| Schedule: `CONFIRMED` | PUBLISHED |
| Survey: `OPEN` | PUBLISHED |
| Survey: `CLOSED` | PUBLISHED （Survey は閉じても結果閲覧の文脈では PUBLISHED 扱い） |
| Survey: `DELETED` | DELETED |

各機能は `*StatusMapper`（mapping パッケージ配下）を実装する。

#### 合成判定ロジック

```java
// 各 Resolver の filterAccessible 内で呼ぶ
private boolean visibleByStatus(
        ContentMetadata meta, Long viewerUserId, boolean isSysAdmin) {
    return switch (meta.status()) {
        case DELETED -> false;
        case ARCHIVED -> isSysAdmin;
        case DRAFT, SCHEDULED ->
            isSysAdmin || Objects.equals(viewerUserId, meta.authorUserId());
        case PUBLISHED -> true; // visibility 評価に進む
    };
}
```

#### Resolver での実装規約

各 Resolver は `Projection` に `status` カラムを必ず含め、`visibleByStatus()` を `evaluate()` の前段ガードとして実行する。これにより status 判定の分散実装を防ぐ。

---

## 8. パッケージ・クラス構成

### 8.1 パッケージレイアウト

```
backend/src/main/java/com/mannschaft/app/common/visibility/
├── ContentVisibilityChecker.java                  # ファサード (@Service)
├── ContentVisibilityResolver.java                 # Strategy インターフェース
├── ReferenceType.java                             # 共通 enum
├── StandardVisibility.java                        # 共通 enum
├── VisibilityDecision.java                        # 値オブジェクト
├── DenyReason.java                                # enum
├── VisibilityErrorCode.java                       # ErrorCode 実装
├── VisibilityMetrics.java                         # Micrometer ヘルパ
├── projection/
│   └── BlogPostVisibilityProjection.java         # 型安全な軽量射影
│       (各機能 Repository の射影 IF はこのパッケージに集約しない、機能側に置く)
├── mapping/
│   ├── package-info.java                          # 規約説明
│   ├── CmsVisibilityMapper.java
│   ├── EventVisibilityMapper.java
│   ├── ActivityVisibilityMapper.java
│   └── ...                                        # 13 個
└── package-info.java                              # 基盤の責務範囲

backend/src/main/java/com/mannschaft/app/cms/visibility/
└── BlogPostVisibilityResolver.java                # Phase B で追加

backend/src/main/java/com/mannschaft/app/event/visibility/
└── EventVisibilityResolver.java                   # Phase B で追加

... (機能ごと)
```

### 8.2 クラス責務の境界

| クラス | 責務 | 依存先 |
|---|---|---|
| `ContentVisibilityChecker` | ディスパッチ・メトリクス・例外集約 | Resolver 群、MeterRegistry |
| `ContentVisibilityResolver<V>` | 個別 type の判定ロジック | AccessControlService, VisibilityTemplateEvaluator, 機能 Repository |
| `*VisibilityMapper` | 機能 enum → StandardVisibility 変換 | (依存なし、純粋関数) |
| `StandardVisibility` | 値型のみ | (依存なし) |
| `VisibilityDecision` | 値型のみ | (依存なし) |

### 8.3 依存方向の強制

`backend/build.gradle` に ArchUnit テストを追加し、以下を CI で強制:

- `common.visibility` パッケージは個別機能 (cms, event 等) に依存してはならない
- 個別機能の `*VisibilityResolver` は `common.visibility` に依存してよい
- Mapper クラスは Spring Bean ではない（純粋関数として保つ）

---

## 9. キャッシング戦略

### 9.1 基本方針

可視性判定の結果は **キャッシュしない** ことを原則とする。理由:

- メンバーシップ変更（ロール変更・退会）の即時反映が必要
- キャッシュ無効化のコストが計算コストを上回るケースが多い
- 既存 `AccessControlService` 自体がキャッシュなしで稼働中（300+ 呼び出し点で問題発生していない）

### 9.2 例外: テンプレート評価のキャッシュ

`VisibilityTemplateEvaluator.canView()` は既に `@Cacheable("visibilityTemplate")` でキャッシュ済み。本基盤も**そのキャッシュを継承**するだけで追加キャッシュは持たない。

### 9.3 例外: メンバーシップの 1 リクエスト内メモ化

ファサード `ContentVisibilityChecker` の同一トランザクション内で同一 `(userId, scopeId, scopeType)` のメンバーシップを複数回引く可能性が高いため、`RequestScoped` のメモ化バッファを設置する:

```java
@Component
@RequestScope
public class MembershipQueryCache {
    private final Map<MembershipKey, Boolean> cache = new HashMap<>();

    public boolean isMember(Long userId, Long scopeId, String scopeType,
                            AccessControlService delegate) {
        return cache.computeIfAbsent(
            new MembershipKey(userId, scopeId, scopeType),
            k -> delegate.isMember(k.userId(), k.scopeId(), k.scopeType()));
    }
}
```

各 Resolver 内では `accessControlService` の代わりにこのキャッシュを経由する（任意）。

### 9.4 観測性 (Micrometer メトリクス)

| メトリック名 | タグ | 説明 |
|---|---|---|
| `content_visibility.check.latency` | referenceType, op | 単発・バッチ判定のレイテンシ (Timer) |
| `content_visibility.check.batch_size` | referenceType | バッチ判定の入力件数 (Distribution) |
| `content_visibility.check.access_ratio` | referenceType | バッチ判定の許可率 (Gauge, Histogram) |
| `content_visibility.check.denied` | referenceType, denyReason | deny の発生数 (Counter) |
| `content_visibility.unsupported_reference_type` | referenceType (max 100 + OVERFLOW) | 未対応 type の検出回数 (Counter) |
| `content_visibility.template_eval.latency` | rule_count | テンプレート評価のレイテンシ |
| `content_visibility.custom_dispatch_count` | referenceType, customSubType | CUSTOM 値経由の判定回数 (§5.1.4 偏り検出) |

ダッシュボード設置先: 既存 Grafana の `Backend / Visibility` パネル（新設）。

---

## 10. 既存 AccessControlService / VisibilityTemplateEvaluator との統合

### 10.1 役割分担

| 基盤 | 責務 | 入力単位 | 出力単位 |
|---|---|---|---|
| `AccessControlService` (既存) | 粗粒度: スコープ/ロール/メンバーシップ判定 | (userId, scopeId, scopeType) | boolean / void(throw) / String(roleName) |
| `VisibilityTemplateEvaluator` (既存) | 細粒度: F01.7 カスタムテンプレート評価 | (userId, templateId, ownerId) | boolean |
| `ContentVisibilityChecker` (新設) | 集約・ディスパッチ | (refType, contentId, userId) | boolean / Set / Map |
| `*VisibilityResolver` (新設, 13 個) | type 別の判定オーケストレーション | (contentId, userId) | boolean / Set |

### 10.2 既存 API への影響と新規追加 API

#### 既存 API
- `AccessControlService` の **既存 12 メソッドは変更なし**（シグネチャ・挙動とも）
- `VisibilityTemplateEvaluator` も **インターフェース変更なし**
- 既存呼び出し元のコードは Phase A 時点で**無改修**

#### 新規追加 API（Phase A）
本基盤の実装上、以下の追加が必要。配置先は **新クラス** `MembershipBatchQueryService` を共通基盤側に新設する（`AccessControlService` の責務肥大化を避けるため、§17 Q9 で確定）:

```java
package com.mannschaft.app.common.visibility;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipBatchQueryService {

    private final UserRoleRepository userRoleRepository;
    private final ScopeAncestorResolver scopeAncestorResolver;
    private final FollowRepository followRepository;

    /**
     * ユーザー × 複数スコープのメンバーシップ・ロール情報を 1 SQL でバルク取得。
     * MEMBERS_ONLY/SUPPORTERS_AND_ABOVE/ADMINS_ONLY/ORGANIZATION_WIDE を含む
     * 一括判定の前に一度だけ呼ぶ。
     */
    public UserScopeRoleSnapshot snapshotForUser(
            Long userId,
            Set<ScopeKey> directScopes,
            Set<ScopeKey> orgWideScopes) {
        if (userId == null) return UserScopeRoleSnapshot.empty();

        boolean sysAdmin = userRoleRepository.existsSystemAdminByUserId(userId);
        if (sysAdmin) return UserScopeRoleSnapshot.systemAdmin();

        // SQL 1: user_roles WHERE user_id=? AND ((team_id IN ?) OR (organization_id IN ?))
        List<UserRoleProjection> direct = userRoleRepository
            .findByUserIdAndScopes(userId, directScopes);

        // SQL 2 (orgWideScopes が空でない時のみ): TEAM → 親 ORG 解決
        Map<ScopeKey, Long> parentOrgs = orgWideScopes.isEmpty()
            ? Map.of()
            : scopeAncestorResolver.resolveParentOrgIds(orgWideScopes);

        // SQL 3 (parentOrgs が空でない時のみ): 親 ORG メンバーシップ
        List<UserRoleProjection> orgMembers = parentOrgs.isEmpty()
            ? List.of()
            : userRoleRepository.findByUserIdAndOrganizationIdIn(
                userId, Set.copyOf(parentOrgs.values()));

        return UserScopeRoleSnapshot.of(direct, parentOrgs, orgMembers);
    }

    /** FOLLOWERS_ONLY 用バッチフォロー判定 */
    public Set<Long> findFollowedUserIds(Long viewerUserId, Set<Long> targetUserIds) {
        if (viewerUserId == null || targetUserIds.isEmpty()) return Set.of();
        return followRepository.findFollowedTargetIds(viewerUserId, targetUserIds);
    }
}
```

`UserScopeRoleSnapshot` は値オブジェクト:

```java
public record UserScopeRoleSnapshot(
    boolean systemAdmin,
    Map<ScopeKey, String> roleByScope,        // direct メンバーシップ + ロール名
    Map<ScopeKey, Long> parentOrgByScope,     // TEAM → 親 ORG
    Set<ScopeKey> orgMemberOf                 // 親 ORG での所属
) {
    public boolean isSystemAdmin() { return systemAdmin; }
    public boolean isMemberOf(ScopeKey s) {
        return systemAdmin || roleByScope.containsKey(s);
    }
    public boolean hasRoleOrAbove(ScopeKey s, String required) {
        if (systemAdmin) return true;
        String role = roleByScope.get(s);
        return role != null && RolePriority.priority(role)
            <= RolePriority.priority(required);
    }
    public boolean isMemberOfParentOrg(ScopeKey s) {
        if (systemAdmin) return true;
        Long parentOrg = parentOrgByScope.get(s);
        return parentOrg != null
            && orgMemberOf.contains(new ScopeKey("ORGANIZATION", parentOrg));
    }
    public static UserScopeRoleSnapshot empty() { /* ... */ }
    public static UserScopeRoleSnapshot systemAdmin() { /* ... */ }
}
```

#### UserRoleRepository への追加

```java
public interface UserRoleRepository extends JpaRepository<UserRoleEntity, Long> {
    // 既存メソッドそのまま

    // 新規（Phase A）
    @Query("...") // バルク所属取得
    List<UserRoleProjection> findByUserIdAndScopes(
        @Param("userId") Long userId,
        @Param("scopes") Set<ScopeKey> scopes);

    Map<Long, Long> findOrganizationIdByTeamIdIn(Set<Long> teamIds);

    List<UserRoleProjection> findByUserIdAndOrganizationIdIn(
        Long userId, Set<Long> organizationIds);
}
```

### 10.3 新基盤への移行パス（既存 Service ごと）

各機能 Service のパターン:

**Before (Phase A 前):**
```java
public List<BlogPostDto> listVisiblePosts(Long userId) {
    return blogPostRepository.findAll().stream()
        .filter(p -> isVisibleTo(p, userId))  // モジュール内ローカル判定
        .map(this::toDto)
        .toList();
}
private boolean isVisibleTo(BlogPostEntity p, Long userId) { /* if ... */ }
```

**After (Phase B 移行後):**
```java
private final ContentVisibilityChecker visibilityChecker;

public List<BlogPostDto> listVisiblePosts(Long userId) {
    List<BlogPostEntity> all = blogPostRepository.findAll();
    Set<Long> accessibleIds = visibilityChecker.filterAccessible(
        ReferenceType.BLOG_POST,
        all.stream().map(BlogPostEntity::getId).toList(),
        userId);
    return all.stream()
        .filter(p -> accessibleIds.contains(p.getId()))
        .map(this::toDto)
        .toList();
}
// isVisibleTo() 削除（ロジックは BlogPostVisibilityResolver に移管済み）
```

### 10.4 既存判定ロジックの段階的削除（Phase E）

Phase B〜D で各 Service の判定ロジックを Resolver 経由に切り替えた後、Phase E で:

- 重複した private な `isVisibleTo()` / `canViewXxx()` を削除
- 同じ enum 値を持つ複数 Service の重複コードを削減
- Repository 側の固定値フィルタ（`findByVisibility(PUBLIC)` 等）を必要に応じて動的に書き直し

---

## 11. セキュリティ考慮事項

### 11.1 Mention・通知配信での Resolver 必須利用

**規約**: コンテンツ参照を含む通知発行 Service では「配信先ユーザーリスト確定後、必ず `filterAccessible()` を通す」ことを義務化。

#### 既知の対象 Service（v0.2 暫定リスト、Phase A 着手前に grep 棚卸しで確定）

| Service | 適用箇所 | 命名パターン |
|---|---|---|
| `NotificationDispatchService.dispatch*()` | mention 由来の通知発行直前 | `*DispatchService` |
| `MentionDetectionService` | mention 抽出後、通知作成前 | `*MentionService` |
| `CirculationDocumentService.distribute()` | 配信先決定後 | `*DistributeService` |
| `TimelinePostService.notifyMentioned()` | mention 通知送信前 | `*PostService` |
| `BulletinReplyService.notifyParticipants()` | 返信通知送信前 | `*ReplyService` |
| `ChatMessageNotifier.notifyMentions()` | チャットメッセージ通知 | `*Notifier` |
| `CommentNotificationService` | コメント通知（ブログ・イベント・チーム） | `*NotificationService` |
| `ReactionNotificationService` | リアクション通知 | `*NotificationService` |
| `FileSharingNotificationService` | ファイル添付通知 | `*NotificationService` |
| `ScheduledNotificationBatch` | 通知バッチ送信 | `*Batch` |

→ 命名パターンが多様なため、ArchUnit は **「`NotificationRepository.save*()` を呼ぶクラスは `ContentVisibilityChecker` に依存しなければならない」** という依存ベース検査に変更（§13.5 改訂）。

#### 網羅戦略

Phase A 着手前のチェックリスト:
1. `grep -r "notificationRepository.save"` で全呼び出し点を棚卸し
2. 各呼び出し直前に `visibilityChecker.canView` または `filterAccessible` が走っているか目視確認
3. 漏れているクラスを §11.1 表に追加し v0.3 で確定
4. `NotificationDispatchVisibilityGuardTest`（§13.5）でランタイム検証

#### 実装パターン

```java
// 例: ChatMessageNotifier
public void notifyMentions(Long messageId, List<Long> mentionedUserIds) {
    Set<Long> allowed = visibilityChecker.filterAccessible(
        ReferenceType.CHAT_MESSAGE,
        Set.of(messageId),
        mentionedUserIds  // ← 各 userId について判定が必要
    );
    // ↑ シグネチャが「複数 userId」なので Resolver 側 API も拡張するか、
    //   呼び出し側でループする（後者が現状の §7.1 API）。
    //   §17 Q11 として API 拡張を検討。
}
```

### 11.2 fail-closed 原則

- 未対応 ReferenceType: `false` を返す（漏らさない）
- Resolver 内で例外発生: `false` を返してログ記録（漏らさない）
- 未認証 (userId=null): **PUBLIC コンテンツの PUBLISHED 状態のみ true**、それ以外は false（§17.Q1 マスター裁可済）

#### DB 由来の不明 ReferenceType の扱い

`corkboard_card_reference.reference_type` 等に **DB 直接挿入や旧バージョン由来の文字列** が残るリスクへの対処:

- アプリ起動時に `ReferenceType.values()` と DB 上の distinct 値を比較するヘルスチェック (`ReferenceTypeIntegrityCheck`) を `@PostConstruct` で実行
- 不一致が検出されたら `WARN` ログ + 起動継続（fail-open 起動、機能はリクエスト時 fail-closed）
- `ReferenceType` enum からは **値の削除を禁止する** (§15 D-12)。deprecated 化のみ
- カウンタの cardinality 爆発防止: `recordUnsupported` の tag は最大 100 種類で打ち切り、超過時は `tag=OVERFLOW` に集約

### 11.3 IDOR 防止

- `canView` の引数 `contentId` は呼び出し元から信頼しない（DB lookup で実存確認）
- `filterAccessible` も同様に DB lookup ベース
- 「見つからない」と「権限なし」は外部レスポンスでは区別しない（404 と 403 を統一して 404 で返す方針は §17.Q2）

### 11.4 監査ログ

#### deny 時（必ず記録）

```java
log.warn("Visibility denied: refType={}, contentId={}, userId={}, reason={}, "
       + "clientIp={}, sessionId={}, requestId={}, triggeredFrom={}",
    type, contentId, userId, denyReason,
    MDC.get("clientIp"), MDC.get("sessionId"), MDC.get("requestId"),
    callerFqn);
```

`MDC` フィールドは `RequestLoggingFilter` で挿入する前提（既存実装が無ければ Phase A で追加）。
`triggeredFrom` は呼び出し元 Service の FQN（`Thread.currentThread().getStackTrace()` から取得、コスト低）。

#### allow 時（条件付きで記録）

通常は記録しない（性能・ログ量）。ただし以下のセンシティブ visibility への成功アクセスは INFO ログ:
- `PRIVATE` への閲覧成功（作成者本人以外が見えた場合は異常 → これは事実上発生しないはずなので記録）
- `CUSTOM_TEMPLATE` 経由の閲覧成功（マッパー誤りや評価ロジック誤りの事後トリアージ用）
- `ARCHIVED`/`DELETED` への SystemAdmin 閲覧

実装は Resolver 側の `auditAllow` フラグで opt-in:

```java
public final class ResolverAuditPolicy {
    public static boolean shouldAuditAllow(StandardVisibility v) {
        return v == PRIVATE || v == CUSTOM_TEMPLATE;
    }
}
```

#### AuditLogService との連携

既存 `AuditLogService` への永続化は Phase A 着手時に判断（既存基盤の API・テーブル設計を確認の上）:
- 候補 1: deny 時のみ DB 永続化（攻撃調査用）
- 候補 2: ログ出力のみで永続化はせず、Loki 等の集約基盤に流す
- §17 Q12 で確定

#### Slack 通知

以下は Grafana Alerts で Slack に流す:
- `content_visibility.check.denied` rate > 100/min for 5 min（攻撃疑い）
- `content_visibility.unsupported_reference_type` count > 0（コード不整合）
- 同一 userId からの deny 100件/分超 → セキュリティ通知

### 11.5 既存の漏れ修正 (Phase F)

§1.4 で挙げた漏れ疑い箇所を Phase F として独立に修正:
- Gallery / Survey / Schedule / Recruitment / Matching の visibility 未実装の Service を Resolver 経由に移行
- 各々 PR 分割（1 機能 1 PR、Hotfix 級として優先）

### 11.6 親スコープ非公開時の連鎖ルール

親 ORG が `DELETED` または `SUSPENDED` 状態のとき、配下 TEAM コンテンツの可視性は以下の通り:

| 親 ORG 状態 | 配下 TEAM コンテンツの可視性 |
|---|---|
| ACTIVE | 通常の visibility 評価 |
| SUSPENDED | SystemAdmin 以外不可視 |
| DELETED | SystemAdmin 以外不可視 |

`MembershipBatchQueryService.snapshotForUser()` 内で親 ORG の状態を引き、`UserScopeRoleSnapshot` に反映する:

```java
public record UserScopeRoleSnapshot(
    boolean systemAdmin,
    Map<ScopeKey, String> roleByScope,
    Map<ScopeKey, Long> parentOrgByScope,
    Set<ScopeKey> orgMemberOf,
    Set<Long> suspendedOrgIds  // 追加
) {
    public boolean isParentOrgInactive(ScopeKey scope) {
        Long parent = parentOrgByScope.get(scope);
        return parent != null && suspendedOrgIds.contains(parent);
    }
}
```

各 Resolver の `evaluate()` の最初に:

```java
if (!snapshot.isSystemAdmin() && snapshot.isParentOrgInactive(scope)) {
    return false;
}
```

→ 連鎖ルールを Resolver 個別実装に分散させず、共通基盤で強制する。

---

## 12. 段階的移行計画

### 12.1 Phase 概観

v0.3 改訂で第 2 回精査の指摘 5（Phase A 過小見積もり）を反映し、上方修正:

| Phase | 内容 | 想定工数 | 依存 |
|---|---|---|---|
| **A** | 基盤クラス・StandardVisibility・全 Mapper・抽象基底・MembershipBatchQueryService・ScopeAncestorResolver・ContentStatus・テスト基盤・性能・ArchUnit・ヘルスチェック・i18n メッセージ・テスト用ユーティリティ | **2〜3 週** | なし |
| **B** | priority 1 機能の Resolver 実装 (BLOG_POST, EVENT, ACTIVITY_RESULT, SCHEDULE, TIMELINE_POST, CHAT_MESSAGE) | 1〜1.5 週 | A |
| **C** | priority 2 機能の Resolver 実装 (BULLETIN_THREAD, TOURNAMENT, RECRUITMENT_LISTING, JOB_POSTING, SURVEY, CIRCULATION_DOCUMENT, COMMENT) | 1.5 週 | A |
| **D** | priority 3 機能の Resolver 実装 (PHOTO_ALBUM, FILE_ATTACHMENT, TEAM, ORGANIZATION) | 1 週 | A |
| **E** | 重複コード削除・既存 Service の Resolver 経由化（リスク評価マトリクス §12.6.1 参照） | 1〜1.5 週 | B〜D 各機能完了次第 |
| **F** | セキュリティ漏れ修正 (Mention 配信ガード等) | 3〜5 日 | A |
| 全体 | | **約 6〜8 週** | |

#### Phase A 内訳 (2〜3 週)

- A-1: 基盤クラス（Checker, Resolver IF, AbstractResolver, ReferenceType, StandardVisibility, ContentStatus, VisibilityDecision, DenyReason 等）— 4 日
- A-2: Mapper 群 17 個 + Mapper の param 化テスト 17 個 — 2.5 日
- A-3: MembershipBatchQueryService + ScopeAncestorResolver + UserRoleRepository 拡張 + UserScopeRoleSnapshot — 3 日
- A-4: ArchUnit テスト + ガードテスト基盤 + 性能テスト基盤 + Hibernate Stats ヘルパ + SqlIntentCounter — 3 日
- A-5: ReferenceTypeIntegrityCheck ヘルスチェック + Micrometer メトリクス + Grafana ダッシュボード — 2 日
- A-6: VisibilityErrorCode + i18n メッセージ 6 言語 + AuditLogService 連携確認 — 2 日
- A-7: VisibilityCheckerTestSupport テスト用ユーティリティ + TEST_CONVENTION.md 規約改訂 — 1.5 日
- A-8: 設計書 v1.0 確定 + `.claudecode.md` §31 追記 + BACKEND_CODING_CONVENTION 追記 — 1.5 日
- 余裕バッファ: 2 日

### 12.2 Phase A: 基盤構築

**成果物**:
- `common/visibility/` パッケージ全クラス
- 13 個の Mapper クラス（既存 enum をすべて網羅）
- `ContentVisibilityCheckerTest` (Resolver なしで Bean 構築できることの確認、未対応 type の fail-closed 動作確認)
- `*VisibilityMapperTest` (各 Mapper の網羅性確認、Mapper の switch が exhaustive であることを保証する param 化テスト)
- ArchUnit テスト (`VisibilityArchitectureTest`) で依存方向強制
- `VisibilityErrorCode` を `BusinessException` 仕組みに登録

**完了条件**:
- 全テストグリーン
- `ContentVisibilityChecker` を `@Autowired` した SmokeTest が起動できる（Resolver 0 個でも）
- ArchUnit ルール pass
- Phase A の PR を main にマージ

**触れるファイル概算**: 新規 25 ファイル、既存改修 0 ファイル

### 12.3 Phase B: priority 1 機能

**対象**: BLOG_POST / EVENT / ACTIVITY_RESULT / SCHEDULE / TIMELINE_POST / CHAT_MESSAGE

#### 12.3.1 visibility 概念新設機能の扱い

§3.3 表で `所属固定` と分類した機能 (TIMELINE_POST / CHAT_MESSAGE / BULLETIN_THREAD / CIRCULATION_DOCUMENT 等) は、機能側に visibility 概念がそもそも存在しない。本基盤導入時の扱い:

1. Phase B〜D で **最小実装の Resolver** を作成（実質 MEMBERS_ONLY 固定相当）
   - `loadProjections` で機能 Entity を引き、`scopeType/scopeId` を取得
   - `toStandard` は固定値 `MEMBERS_ONLY` を返す
   - これにより corkboard 等から `canView` 経由でアクセス判定が可能になる
2. 後日 `visibility` カラムを機能に追加する場合は **別軍議** で機能仕様策定（DDL 追加・UI 追加・既存データ初期値設定）
3. 別軍議で visibility 追加後、本基盤の Resolver の `toStandard` を Mapper 経由に切り替える PR を分離

→ Phase B〜D 内では「最小実装」までを行う。本格 visibility 機能化は本基盤のスコープ外。

各機能で:
1. `*VisibilityResolver` 実装 (Strategy + 既存 Repository への射影クエリ追加)
2. `*VisibilityResolverTest` (単体、SUT + Repository モック)
3. `*VisibilityResolverIntegrationTest` (実 DB、各 StandardVisibility パターンを網羅)
4. 既存 Service のうち重要 1 メソッドを Resolver 経由に切り替え（残りは Phase E）

**リグレッション防止**:
- 既存 Controller の E2E は変更前後で通ること
- 既存 Service の Resolver 経由切り替えは「追加経路 + フィーチャーフラグ」ではなく**直接置換**でよい（テストが網羅できているので）

**触れるファイル概算**: 各機能 5 ファイル × 5 機能 = **25 ファイル新規 + 5 ファイル改修**

### 12.4 Phase C: priority 2 機能

**対象**: BULLETIN_THREAD / TOURNAMENT / RECRUITMENT_LISTING / JOB_POSTING / SURVEY

Phase B と同手順。Survey は ResultsVisibility の時間軸条件 (AFTER_RESPONSE/AFTER_CLOSE) を Resolver 内でハンドリングする必要があり、テストパターン多め。

**触れるファイル概算**: 各機能 5 ファイル × 5 機能 = **25 ファイル新規 + 5 ファイル改修**

### 12.5 Phase D: priority 3 機能

**対象**: PHOTO_ALBUM / TEAM / ORGANIZATION

Team / Organization は既存の Profile 系 Service と整合性をとる必要があるので注意。

**触れるファイル概算**: 各機能 5 ファイル × 3 機能 = **15 ファイル新規 + 3 ファイル改修**

### 12.6 Phase E: 重複コード削除

**対象**:
- `TeamExtendedProfileService.java:76,172,391` の重複 if 文 → `visibilityChecker` に置換
- `OrganizationExtendedProfileService.java:76` 同上
- 各機能の `private boolean isVisibleTo()` メソッドを削除
- Repository 側の固定値フィルタを動的化（必要箇所のみ）

**注意**: Phase E は機能拡張ではなく純粋なリファクタなので、PR を細かく分割（1 機能 1 PR、相互レビュー必須）。

**触れるファイル概算**: 削除 ~15 ファイル、改修 ~30 ファイル

#### 12.6.1 機能別リスク評価マトリクス

機能ごとに Phase E の実施リスクを事前評価する:

| 機能 | リスク | 既存テスト網羅 | 推奨着手順 | フィーチャーフラグ要否 | 備考 |
|---|---|---|---|---|---|
| Activity | 低 | 中 | 1 | 不要 | enum 単純、参照少ない |
| Tournament | 低 | 中 | 2 | 不要 | enum 単純 |
| Project (Todo) | 低 | 中 | 3 | 不要 | enum 単純 |
| BlogPost (CMS) | 中 | 高 (E2E あり) | 4 | 不要 | 既存実装明確、移行範囲明確 |
| Event | 中 | 高 (E2E あり) | 5 | 不要 | 既存実装明確 |
| Schedule | 中 | 中 | 6 | 不要 | visibility 未実装ゆえ慎重に |
| Recruitment | 中 | 中 | 7 | 不要 | Phase 2 留保コード実装含む |
| JobPosting | 中 | 中 | 8 | 不要 | JOBBER_INTERNAL の CUSTOM 注意 |
| Timetable | 低 | 低 | 9 | 不要 | 単純 |
| PhotoAlbum (Gallery) | 中 | 低 | 10 | **要** | visibility 未実装なので新機能扱い |
| Survey | 高 | 中 | 11 | **要** | 時間軸条件 (AFTER_RESPONSE/AFTER_CLOSE) で独自セマンティクス |
| Team (ExtendedProfile) | **高** | 高 | 12 | **要** | 他機能から多数参照、UI 直結 |
| Organization (ExtendedProfile) | **高** | 高 | 13 | **要** | 同上 |

**運用方針**:
- 低リスク機能から順に Phase E を回し、慣れた段階で中→高に進む
- 高リスク機能は Phase B〜D で Resolver 経由実装まで完了 → 1 sprint 寝かせて旧コード削除
- フィーチャーフラグ「要」の機能では `application.yml` に `feature.visibility-resolver.{module}: true|false` を仕込み、問題発生時の即時切り戻しを可能にする
- フラグ削除は 2 sprint 安定稼働後

### 12.7 Phase F: セキュリティ漏れ修正

**対象**: §11.5 の 5 件

各々 Hotfix 級として優先度を上げ、Phase A 完了次第着手可能。

**触れるファイル概算**: 5 機能 × 2 ファイル = **10 ファイル改修**

### 12.8 Phase 間の並列度

- A: 必ず最初・直列
- B / C / D: A 完了後は機能ごとに独立 PR で並列可
- E: B〜D 各機能が完了次第その機能だけ E を回せる（部分並列）
- F: A 完了後すぐ着手可（B〜D と完全並列）

---

## 13. テスト戦略

### 13.1 テスト階層

| 階層 | 対象 | フレームワーク |
|---|---|---|
| 単体 | 各 `*VisibilityResolver` | JUnit 5 + Mockito |
| 単体 | 各 `*VisibilityMapper` | JUnit 5 (param 化) |
| 単体 | `ContentVisibilityChecker` ファサード | JUnit 5 + Mockito |
| 統合 | 各 Resolver の DB 整合 | `@SpringBootTest` + `@Transactional` |
| アーキテクチャ | パッケージ依存・命名規約 | ArchUnit |
| 性能 | バッチ判定の SQL 数 | `@DataJpaTest` + Hibernate Statistics |
| E2E | 既存 Controller のリグレッション | Playwright (frontend) |

### 13.2 Resolver 単体テストのテンプレート

```java
@ExtendWith(MockitoExtension.class)
class BlogPostVisibilityResolverTest {

    @Mock private BlogPostRepository repository;
    @Mock private AccessControlService accessControl;
    @Mock private VisibilityTemplateEvaluator templateEvaluator;

    @InjectMocks private BlogPostVisibilityResolver resolver;

    @Test
    void public_post_visible_to_anyone() {
        when(repository.findVisibilityProjectionsByIdIn(List.of(1L)))
            .thenReturn(List.of(new BlogPostVisibilityProjection(
                1L, "PUBLIC", null, null, 99L)));

        assertThat(resolver.canView(1L, 42L)).isTrue();
        verifyNoInteractions(accessControl);
    }

    @Test
    void members_only_post_invisible_to_non_member() { ... }

    @Test
    void members_only_post_visible_to_member() { ... }

    // 各 StandardVisibility 値 × member/non-member の網羅
}
```

### 13.3 Mapper の exhaustive テスト

```java
@ParameterizedTest
@EnumSource(com.mannschaft.app.cms.Visibility.class)
void every_value_maps_to_some_standard(com.mannschaft.app.cms.Visibility v) {
    assertThat(CmsVisibilityMapper.toStandard(v)).isNotNull();
}
```

→ enum に値が追加された瞬間にコンパイル or テスト失敗するので、Mapper の網羅性を CI で保証。

### 13.4 性能テスト

`backend/src/test/java/com/mannschaft/app/common/visibility/perf/`:

```java
@DataJpaTest
@Testcontainers // MySQL 同等 DB で実施 (H2 では Statistics が異挙動)
class VisibilityCheckerPerformanceTest {

    @Autowired private ContentVisibilityChecker checker;
    @Autowired private SqlIntentCounter sqlCounter;

    @Test
    void filter_accessible_homogeneous_scopes_uses_at_most_3_sql() {
        // 同 scopeId に揃った 50 件
        sqlCounter.reset();

        checker.filterAccessible(
            ReferenceType.BLOG_POST, longIdsOf(1..50), 42L);

        // ① blog_posts WHERE id IN ?
        // ② user_roles WHERE user_id=? AND (team_id IN ?)
        // ③ system_admin チェック (snapshot 内)
        assertThat(sqlCounter.intentCount("blog_posts")).isEqualTo(1);
        assertThat(sqlCounter.intentCount("user_roles")).isLessThanOrEqualTo(2);
        assertThat(sqlCounter.totalCount()).isLessThanOrEqualTo(4); // バッファ +1
    }

    @Test
    void filter_accessible_heterogeneous_scopes_uses_at_most_4_sql() {
        // 3 つの異なる team / 2 つの異なる org にまたがる 50 件
        // 第1回精査 指摘 3 のシナリオ
        sqlCounter.reset();

        checker.filterAccessible(
            ReferenceType.BLOG_POST, longIdsOf(1..50, 4_distinct_teams), 42L);

        // 同 user_roles クエリ 1 回で 4 team を IN 句にバルク取得できる
        assertThat(sqlCounter.totalCount()).isLessThanOrEqualTo(4);
    }

    @Test
    void filter_by_type_corkboard_pattern_uses_at_most_8_sql() {
        // F09.8.1 §10.4 の SQL 数 ≦ 8 目標を本基盤側で担保
        Map<ReferenceType, List<Long>> mix = Map.of(
            ReferenceType.BLOG_POST, List.of(1L, 2L, 3L),
            ReferenceType.EVENT, List.of(4L, 5L),
            ReferenceType.SCHEDULE, List.of(6L, 7L),
            ReferenceType.TIMELINE_POST, List.of(8L, 9L, 10L));

        sqlCounter.reset();
        checker.filterAccessibleByType(mix, 42L);

        // 4 type × (projection + membership) ≦ 8
        assertThat(sqlCounter.totalCount()).isLessThanOrEqualTo(8);
    }
}
```

#### `SqlIntentCounter` ヘルパ

`getPrepareStatementCount()` は Hibernate 内部の生 SQL 数なので JPQL 最適化挙動の変更で false positive が出やすい。テーブル名ベースで「クエリ意図単位」をカウントするヘルパを Phase A で導入:

```java
@Component
public class SqlIntentCounter {
    public void reset() { /* StatementInspector を介して記録クリア */ }
    public int intentCount(String tableHint) { /* table 名を含む SQL の件数 */ }
    public int totalCount() { /* 総 SQL 数 */ }
}
```

これにより Hibernate 6 へのアップグレードや IN バッチ分割の挙動変更でテストが false positive にならない。

### 13.5 ArchUnit ルール + ガードテスト

#### ArchUnit（静的依存検査）

```java
@AnalyzeClasses(packages = "com.mannschaft.app")
class VisibilityArchitectureTest {

    @ArchTest
    static final ArchRule mappers_have_no_dependencies =
        classes().that().resideInAPackage("..common.visibility.mapping..")
            .should().onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "..common.visibility..", "..cms..", /* enum 元 */);

    @ArchTest
    static final ArchRule common_does_not_depend_on_features =
        noClasses().that().resideInAPackage("..common.visibility..")
            .should().dependOnClassesThat()
                .resideInAnyPackage("..cms.service..", "..event.service..", /* etc */);

    /** 通知発行 = NotificationRepository.save* を呼ぶクラスは VisibilityChecker 必須 */
    @ArchTest
    static final ArchRule notification_save_callers_must_depend_on_checker =
        classes().that().callMethodWhere(target ->
            target.getOwner().getName().endsWith("NotificationRepository")
            && target.getName().startsWith("save"))
        .should().dependOnClassesThat()
            .haveSimpleName("ContentVisibilityChecker");

    /** Phase 2 予約済みの ReferenceType を Phase 1 で呼ぶことを禁止 */
    @ArchTest
    static final ArchRule phase2_reserved_types_unused = noClasses()
        .should().dependOnClassesThat().haveFullyQualifiedName(
            "com.mannschaft.app.common.visibility.ReferenceType")
        .andShould(/* 詳細: PERSONAL_TIMETABLE / FOLLOW_LIST 参照を検出 */);

    /** Resolver は他 Resolver を直接 inject してはならない（循環防止） */
    @ArchTest
    static final ArchRule resolvers_dont_inject_other_resolvers =
        noClasses().that().implement(ContentVisibilityResolver.class)
            .should().dependOnClassesThat().implement(ContentVisibilityResolver.class);
}
```

#### Mockito ガードテスト（呼び忘れ検出）

ArchUnit は「依存があるか」しか見ないので、メソッド本体での呼び忘れ防止用に Mockito ベースのガードテストを置く:

```java
@SpringBootTest
class NotificationDispatchVisibilityGuardTest {

    @MockBean private NotificationRepository notificationRepository;
    @MockBean private ContentVisibilityChecker visibilityChecker;
    @Autowired private NotificationDispatchService service;

    @Test
    void dispatchMention_calls_visibility_checker_before_repository_save() {
        when(visibilityChecker.canView(any(), any(), any())).thenReturn(true);

        service.dispatchMention(123L, ReferenceType.BLOG_POST, List.of(1L, 2L, 3L));

        InOrder order = inOrder(visibilityChecker, notificationRepository);
        order.verify(visibilityChecker, atLeastOnce())
            .canView(eq(ReferenceType.BLOG_POST), eq(123L), any());
        order.verify(notificationRepository, atLeastOnce()).save(any());
    }

    @Test
    void dispatchMention_filters_out_non_accessible_users() {
        when(visibilityChecker.canView(any(), any(), eq(2L))).thenReturn(false);
        when(visibilityChecker.canView(any(), any(), or(eq(1L), eq(3L)))).thenReturn(true);

        service.dispatchMention(123L, ReferenceType.BLOG_POST, List.of(1L, 2L, 3L));

        ArgumentCaptor<NotificationEntity> captor = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
            .extracting(NotificationEntity::getRecipientUserId)
            .containsExactlyInAnyOrder(1L, 3L);
    }
}
```

各通知発行 Service ごとに同様のガードテストを Phase F で配置する。

### 13.6 Phase B〜D の各機能テストカバレッジ目標

- Resolver 単体: line coverage ≧ 90%、branch coverage ≧ 85%
- Mapper: 100% (enum 全値)
- 統合テスト: 各 StandardVisibility に対して member / non-member / owner / admin の最低 4 ケース

### 13.7 テスト総量と工数見積もり

| カテゴリ | 想定件数 | 想定工数 |
|---|---|---|
| Resolver 単体テスト | 17 個 × 平均 15 ケース = 255 ケース | 17 × 0.5d = 8.5 日 |
| Mapper param 化テスト | 17 個 × 平均 5 値 = 85 ケース | 17 × 0.1d = 1.7 日 |
| 統合テスト（実 DB） | 17 個 × 平均 4 ケース = 68 ケース | 17 × 0.4d = 6.8 日 |
| ArchUnit ルール | 4 ルール | 0.5 日 |
| ガードテスト（通知発行 Service） | 10 個 × 2 ケース = 20 ケース | 10 × 0.3d = 3 日 |
| 性能テスト | 5 シナリオ | 1 日 |
| ヘルスチェックテスト | 1 シナリオ | 0.3 日 |
| **合計** | 約 433 テストケース | **約 22 日（≒ 4 週）** |

→ Phase A〜F の 6〜8 週工数のうち、テスト工数は約半分を占める。実装:テスト = **1:1.0〜1.2** 程度。Phase A 内訳 (§12.1) で「A-2 Mapper 群 + テスト 2.5 日」「A-4 テスト基盤 3 日」に既に含まれているのは 5.5 日のみで、残り 16.5 日は Phase B〜D に分散される。

### 13.8 テスト用ユーティリティ `VisibilityCheckerTestSupport`

機能側既存テストで `ContentVisibilityChecker` を `@MockBean` する場合、毎回 `when(...).thenReturn(...)` を書くと API 拡張時に数百テストが一斉に壊れる。これを防ぐため、Phase A 成果物として以下を提供:

```java
package com.mannschaft.app.common.visibility.testsupport;

@TestConfiguration
public class VisibilityCheckerTestSupport {

    /** 全 type / 全 contentId / 全 userId に対して allow（最も多いユースケース） */
    public static void allowAll(ContentVisibilityChecker checker) {
        when(checker.canView(any(), any(), any())).thenReturn(true);
        when(checker.filterAccessible(any(), anyCollection(), any()))
            .thenAnswer(inv -> Set.copyOf(inv.getArgument(1)));
        when(checker.filterAccessibleByType(anyMap(), any()))
            .thenAnswer(inv -> {
                Map<ReferenceType, Collection<Long>> input = inv.getArgument(0);
                return input.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
            });
    }

    /** 全て deny */
    public static void denyAll(ContentVisibilityChecker checker) { /* ... */ }

    /** 特定 type の特定 contentId のみ allow */
    public static void allowFor(ContentVisibilityChecker checker,
                                 ReferenceType type, Set<Long> ids) { /* ... */ }

    /** 特定 userId についてのみ allow（他は deny） */
    public static void allowForUser(ContentVisibilityChecker checker, Long userId) { /* ... */ }

    /** decide() でカスタム DenyReason を返す */
    public static void denyWithReason(ContentVisibilityChecker checker, DenyReason reason) { /* ... */ }
}
```

`TEST_CONVENTION.md` に以下を規約化:
- `ContentVisibilityChecker` を mock するときは必ず `VisibilityCheckerTestSupport` 経由
- 直接 `when()` でスタブする場合は理由をテストクラスのコメントで明記

これにより本基盤 API 進化（例: tenantId 追加）に対する既存テストの脆弱性を低減。

---

## 14. パフォーマンス目標と検証

### 14.1 目標値

| 指標 | 目標 | 測定方法 |
|---|---|---|
| `canView` 単発 | p95 ≦ 30ms | Grafana メトリクス `content_visibility.check.latency{op=canView}` |
| `filterAccessible` (50件) | p95 ≦ 60ms | 同 `{op=filterAccessible}` |
| `filterAccessibleByType` (4 type × 5 件) | p95 ≦ 100ms | F09.8.1 §10.4 と整合 |
| 1 リクエストの SQL 数 (filterAccessible 50件) | ≦ 2 | DataJpaTest + Hibernate Statistics |
| キャッシュヒット率 (テンプレート評価) | ≧ 80% | 既存 `visibilityTemplate` キャッシュ |

### 14.2 ベンチマーク

Phase A 完了時にベースライン測定、Phase B 完了時に目標達成確認、Phase E 完了時に旧ロジック撤去後の劣化なし確認。

ベンチマーク用シナリオ:
- 1000 件 BlogPost / 100 件 Event / 100 件 Schedule のテストデータ
- 100 並列クライアント × 1 分間
- Spring Boot Profile `perf-bench` で実行

### 14.3 性能劣化アラート

Grafana に以下のアラートを追加:
- `content_visibility.check.latency p95 > 200ms` for 5 min → Slack 通知
- `content_visibility.unsupported_reference_type` count > 0 → Slack 通知（コード変更必要のサイン）

---

## 15. 設計判断ログ

### D-1: Strategy + Registry を採用 (✅)

代替案: 単一巨大 if-else / instanceof チェーン / Spring `getBean(...)` 動的解決

**判断**: Strategy + Registry。理由は §4.2 の比較表のとおり。`@Component` 自動収集と `Map<Key, Bean>` パターンが Spring 標準で type-safe。

### D-2: StandardVisibility は 9 値で打ち止め (✅)

候補値の検討:
- `PUBLIC` ✅
- `MEMBERS_ONLY` ✅
- `SUPPORTERS_AND_ABOVE` ✅
- `ADMINS_ONLY` ✅
- `PRIVATE` ✅
- `FOLLOWERS_ONLY` ✅
- `CUSTOM_TEMPLATE` ✅
- `ORGANIZATION_WIDE` ✅
- `CUSTOM` ✅ (escape hatch)
- ❌ `HIDDEN` (PRIVATE と機能的に等価、Mapper で PRIVATE に寄せる)
- ❌ `NAME_ONLY` (Committee 固有、CUSTOM 扱い)
- ❌ `AFTER_CLOSE` (Survey 固有時間軸、CUSTOM 扱い)

**判断**: 9 値で確定。`CUSTOM` は escape hatch として残し、Resolver 内で個別判定。値の追加・削除は本設計書の改訂を伴うこと。

### D-3: 既存 enum は当面そのまま残す (✅)

代替案: Phase A で全 enum を `StandardVisibility` 1 つに統一して既存テーブルもマイグレーション。

**判断**: 既存 enum は残す。理由:
- 機能固有の意味（例: Recruitment の SCOPE_ONLY, JobMatching の JOBBER_INTERNAL）を完全に StandardVisibility だけで表せない
- DB マイグレーションのリスクが大きい（19 テーブルの値書き換え）
- Mapper だけで実用上の集約は達成できる
- enum 統廃合は将来のリファクタリング機会で個別判断

### D-4: ContentVisibilityChecker は @Service、Resolver は @Component (✅)

**判断**: ファサードは「業務的振る舞い」なので `@Service`、Strategy は技術的構成要素なので `@Component`。両者とも DI コンテナ管理は同じ。

### D-5: メソッド名は canView / filterAccessible / decide / assertCanView (✅, v0.3 整理)

代替案: `isVisible` / `accessibleIds` / `permitted` / `checkCanView` など。

**判断**:
- `canView()` boolean: AccessControlService の既存メソッド名（`checkMembership` 等）と被らない、F09.8.1 §5.2 で既に使われている表現に揃える
- `filterAccessible()` Set 返し: 「見えるものを残す」意味が直感的、`is_accessible` カラム命名と整合 (F09.8.1)
- `decide()` VisibilityDecision 返し: 監査・デバッグ用詳細判定
- `assertCanView()` void throw: NOT_FOUND は VISIBILITY_004 (404)、それ以外の deny は VISIBILITY_001 (403) を分岐スロー

**v0.3 整理**: v0.2 で「`checkCanView` を `assertCanView` のエイリアスとして併設」と書いたが、第 2 回精査 指摘 10 で「3 種類は混乱を招く」と指摘されたため、**`checkCanView` エイリアスは作らない**。AccessControlService 慣行 (`checkXxx` = void throw) との整合は `assertCanView` 1 つで十分とする。`assertCanView` はメソッド命名の動詞として不自然（`assert` はテストフレームワーク連想）と感じられる場合のみ将来 `requireView` 等への改名軍議を立てる。

§7.4 の API 表も `checkCanView` を削除して整合させる。

### D-6: 未対応 ReferenceType は fail-closed (false 返却) (✅)

代替案: 例外スロー / `Optional<Boolean>` 返却。

**判断**: fail-closed (false)。理由:
- セキュリティ第一（漏らさない）
- 例外スローは呼び出し側に過剰負担、Optional は型安全だが冗長
- メトリクスで検出できるので運用上の見落としは防げる

### D-7: キャッシュは持たず、テンプレート評価キャッシュのみ継承 (✅)

代替案: 全 Resolver に `@Cacheable` を付ける / Redis にキャッシュレイヤを設ける。

**判断**: キャッシュなし。理由:
- メンバーシップ変更の即時反映が必要
- 既存 AccessControlService がキャッシュなしで稼働中
- リクエストスコープのメモ化（§9.3）で同一 Tx 内重複を防げば十分
- 性能問題が顕在化したら Phase 2 で再検討

### D-8: ReferenceType と corkboard_card_reference.reference_type は値一致 (✅)

**判断**: F09.8.1 の DB カラムと値を一致させる。値の同期保証として ArchUnit テストで「Java enum 名 = DB スキーマの定数」を確認。

### D-9: 個別 Resolver は機能パッケージ配下に置く (✅)

代替案: `common/visibility/resolver/` に全 Resolver を集約。

**判断**: 機能パッケージ配下。理由:
- パッケージ依存方向 `feature → common` を維持できる
- 機能凝集度を保つ
- 機能削除時に Resolver も同梱削除できる
- `common` 側に依存逆転が発生しない

### D-10: Mapper は static (Spring Bean ではない) (✅)

**判断**: 純粋関数なので static。`@Component` にすると DI 経由でしか使えず、テスト・パターンマッチが煩雑になる。

### D-11: 通知配信での Resolver 利用は ArchUnit + ガードテストで強制 (✅, v0.2 改訂)

**判断**: 規約だけに任せるとまた漏れる。ArchUnit は静的依存検査しかできないので:
- ArchUnit: `NotificationRepository.save*()` を呼ぶクラスは `ContentVisibilityChecker` に依存する
- Mockito ガードテスト (`*VisibilityGuardTest`): 各通知発行 Service について「save の前に canView が呼ばれている」を `InOrder` で検証
- 両方を併用することで「依存はあるが呼び忘れ」のケースまでカバー

### D-12: ReferenceType の値削除を禁止 (✅, v0.2 追加)

**判断**: enum 値を削除すると DB に残る旧 type 文字列が起動時 valueOf 失敗 → 過去データ全部 fail-closed で利用者実害。値の削除は禁止し、deprecated 化のみ許可:

```java
public enum ReferenceType {
    @Deprecated(since = "v2.0", forRemoval = false)
    OLD_TYPE_NAME,  // 値は残す
    NEW_TYPE_NAME,
    ...
}
```

ヘルスチェック `ReferenceTypeIntegrityCheck` を `@PostConstruct` で実行し、DB の distinct 値と enum の差分を起動時に WARN ログ。

### D-13: SystemAdmin 高速パスをファサードでは持たない (✅, v0.2 追加)

**判断**: 当初案 (v0.1) ではファサード `ContentVisibilityChecker.filterAccessible` で `isSystemAdmin` 早期 return を行い、入力 `ids` をそのまま返す案だった。しかしこれは「存在しない id も accessible として返す」セマンティクスとなり、IDOR 防止 §11.3 の「DB lookup ベース」原則と矛盾し、後段の 404 と整合しない。

代替案: 各 Resolver の `filterAccessible` 内で `findVisibilityProjectionsByIdIn` を実行 → 実存 row のみを対象に、`isSystemAdmin=true` の場合は `evaluate()` を全 true 返却に短絡。これにより SystemAdmin でも実存 ID 集合のみ返却される。コスト差は SQL 1 回分のみで体感差なし。

### D-14: AccessControlService は拡張せず MembershipBatchQueryService を新設 (✅, v0.2 追加)

**判断**: 本基盤に必要なバルク判定 API は `AccessControlService` に直追加せず、新クラス `MembershipBatchQueryService` を `common/visibility/` に置く。理由:
- AccessControlService の責務肥大化を避ける (既に 12 メソッド)
- バルク判定は本基盤専用なので、単発判定とは抽象度が違う
- 既存呼び出し点 (96 ファイル・316 呼び出し) への影響ゼロを保つ

ただし `MembershipBatchQueryService` は `UserRoleRepository` の新規メソッド (`findByUserIdAndScopes`, `findOrganizationIdByTeamIdIn`, `findByUserIdAndOrganizationIdIn`) を追加する。Repository 拡張は不可避。

### D-15: status と visibility の合成を共通基盤で標準化 (✅, v0.2 追加)

**判断**: visibility だけでは「DRAFT/ARCHIVED/DELETED」を表せず、Resolver 各実装者が独自実装するとコピペが status 軸に移動するだけ。`ContentStatus` 標準 enum (DRAFT/SCHEDULED/PUBLISHED/ARCHIVED/DELETED) を導入し、各 Resolver で `visibleByStatus()` を `evaluate()` の前段ガードとして必ず実行する規約を §7.5 に明記。

### D-16: Resolver は他 Resolver を直接 inject せず ContentVisibilityChecker 経由 (✅, v0.2 追加)

**判断**: コルクボードカードのように「コンテナ + 複数 reference」型のコンテンツの Resolver は、内部で他 type の判定を呼ぶ必要がある。Spring の循環依存と評価ループを避けるため:
- Resolver は `ContentVisibilityResolver` 型のフィールドを持たない（ArchUnit で強制）
- 必要な場合は `ContentVisibilityChecker` を inject
- 再帰呼び出しの深度上限 3 で `IllegalStateException`

---

## 16. 見送り事項 (Out of Scope)

### 16.1 本基盤に含めない設計判断

| 項目 | 理由 | 将来扱い |
|---|---|---|
| Visibility enum の DB レベル統一 | リスク大、既存テーブル 19 個の書き換え | 別軍議で個別検討 |
| フロントエンドの可視性 UI 統一 | 機能ごとの UI コンセプトが多様 | F02.x の延長で別途 |
| ロール定義そのものの見直し | F01.2 の責務、本基盤と独立 | 必要に応じて別軍議 |
| 監査ログの保存先一元化 | 既存 AuditLogService がある | F04.x の延長で別途 |
| 横断検索 API の権限フィルタ統一 | 検索基盤側の課題 | 検索リファクタ時に本基盤を使う |
| **多テナント isolation** | Mannschaft が単一テナント前提のため | §17 Q14 で道筋のみ示す。実施時は v2.0 メジャー改訂相当 |

### 16.2 本基盤の Phase 1 対象としない visibility enum

| Enum | Phase 1 対象外の理由 | 将来扱い |
|---|---|---|
| `OnlineVisibility` | コンテンツではなくユーザー属性 | 永久対象外。既存 ContactService 内のまま |
| `OrganizationProfileVisibility` (Record) | フィールド別フラグ、構造が異質 | 永久対象外。既存 OrganizationExtendedProfileService 内のまま |
| `DashboardWidgetMinRole` | コンテンツではなく UI ウィジェット | 永久対象外。F02.2.1 の `RoleResolver` のまま |
| `FollowListVisibility` | リスト全体可否でコンテンツ単位ではない | **Phase 2 予約**: `FOLLOW_LIST` を ReferenceType に追加（フォロー一覧を corkboard で引用するユースケース対応）|
| `PersonalTimetableVisibility` | FAMILY_SHARED が特殊（family_team メンバー判定） | **Phase 2 予約**: `PERSONAL_TIMETABLE` を ReferenceType に追加（F03.15 個人時間割の Mention 配信対応） |

「Phase 2 予約」の項目は v0.2 時点で:
- `ReferenceType` enum には先行定義（§3.3）
- Resolver 未実装のため `canView` 呼び出しは fail-closed
- §13.5 ArchUnit `phase2_reserved_types_unused` ルールで「Phase 1 では呼び出し禁止」を強制

### 16.3 Phase 1 では実装しない API

| API | 理由 |
|---|---|
| `Set<Long> filterEditable(...)` | 編集権限は別概念（Phase 2 候補） |
| `boolean canCreate(...)` | 作成権限は別概念 |
| `Stream<Long> filterAccessibleStream(...)` | 必要性が見えてから |
| 非同期 API (`Mono<Set<Long>>`) | プロジェクトが Reactive ではない |

---

## 17. 未解決問題 (Open Questions)

### Q1: 未認証ユーザー (userId=null) の扱い（マスター裁可済）

**論点**: PUBLIC コンテンツは未認証でも見せるべきか？

**選択肢**:
- (A) `userId=null` → 常に false (fail-closed 一貫)
- (B) `userId=null` → PUBLIC のみ true、他は false
- (C) `userId=null` → IllegalArgumentException

**マスター裁可 (2026-05-04)**: **(B) を採用**。Mannschaft は未認証（アカウント無し・未ログイン）の閲覧者にも PUBLIC コンテンツは公開する方針。

#### 実装規約

`AbstractContentVisibilityResolver.visibleByVisibility` の冒頭に:

```java
if (viewerUserId == null) {
    // 未認証: PUBLIC のみ通す。それ以外は fail-closed
    StandardVisibility std = toStandard((V) row.visibility());
    return std == StandardVisibility.PUBLIC
        && toContentStatus(row) == ContentStatus.PUBLISHED;
}
```

`MembershipBatchQueryService.snapshotForUser` も `userId=null` の場合は `UserScopeRoleSnapshot.empty()` を即返却（DB アクセスなし）。

#### セキュリティ補足

- `PRIVATE` / `MEMBERS_ONLY` / `SUPPORTERS_AND_ABOVE` / `ADMINS_ONLY` / `CUSTOM_TEMPLATE` / `FOLLOWERS_ONLY` / `ORGANIZATION_WIDE` はすべて未認証では false（fail-closed）
- `status != PUBLISHED` のコンテンツは未認証では一律不可視（DRAFT/SCHEDULED は author 必須、author=null=未認証 では一致不能）
- 監査ログ §11.4 の `userId` フィールドは未認証時 `"anonymous"` 文字列で記録（NPE 回避）

**決着**: ✅ 確定（v1.0、マスター裁可済）。

### Q2: 「権限なし」と「存在しない」の HTTP レスポンス区別

**論点**: 既存 Mannschaft の慣行はどちら？

**選択肢**:
- (A) 統一して 404 (情報漏洩防止、セキュアだが UX 悪い)
- (B) 区別して 403 / 404 (UX 良いが、リソース存在情報が漏れる)

**現状方針 (v0.1)**: 既存 `BusinessException` の慣行に合わせる（要調査）。`ContentVisibilityChecker` は判定結果のみ返し、HTTP コード変換は呼び出し側 Controller の責務。

**決着**: ▶ Phase A 着手前に既存 ErrorCode 一覧を確認して確定。

### Q3: VisibilityTemplateEvaluator の REGION_MATCH 未実装

**論点**: 既存 `VisibilityTemplateEvaluator` の `REGION_MATCH` ルール型が未実装（コメント記載）。本基盤は REGION_MATCH 含むテンプレートをどう扱う？

**現状方針 (v0.1)**: REGION_MATCH を含むテンプレートは現状 false 評価のまま（現行と同挙動）。本基盤の責務外として F01.7 側で対応。

**決着**: ▶ 既知の現状追認、Phase A 影響なし。

### Q4: バッチ判定の上限件数

**論点**: `filterAccessible(refType, ids, userId)` の `ids.size()` に上限を設けるか？

**選択肢**:
- (A) 上限なし（呼び出し側責務）
- (B) 1000 件で警告ログ
- (C) 1000 件超過は IllegalArgumentException

**現状方針 (v0.1)**: (B) 1000 件で WARN ログ + Micrometer で記録。それ以上の制限なし。

**決着**: ▶ Phase A 着手で確定。

### Q5: Phase E のリスク管理

**論点**: 既存 Service の重複コードを削除する Phase E は、テストカバレッジが薄い機能で予期しない挙動変更を起こすリスクがある。

**現状方針 (v0.1)**:
- Phase E は機能ごとに独立 PR 化
- 各 PR でリグレッションテスト（既存 E2E + 機能網羅 unit）必須
- 不安が残る機能は Phase E をスキップして Resolver の追加経路だけ残す（重複コードを残してでも安全策）

**決着**: ▶ Phase E 着手時に各機能の判断、設計時点では方針のみ確定。

### Q6: `Map<ReferenceType, Set<Long>>` の戻り値で「存在しない ID」を区別するか

**論点**: 入力 ID が DB に存在しない場合、戻り値の Set に含まれない。これと「存在するが権限なし」が区別できない。

**現状方針 (v0.1)**: 区別しない（fail-closed と同じ理由）。デバッグが必要なケースは `decide()` で個別取得する。

**決着**: ▶ 確定。

### Q7: `userId` が System Admin の場合の高速パス（v0.2 で確定）

**論点**: System Admin はすべて見えるので、Resolver 内で都度判定するのは無駄。

**v0.2 確定**: ファサードでは早期 return しない。各 Resolver の `filterAccessible` 内で `findVisibilityProjectionsByIdIn` を実行 → 実存 row を取得 → `snapshot.isSystemAdmin()` で短絡。これにより:
- 「存在しない ID は返さない」セマンティクスを維持（IDOR 防止 §11.3 と整合）
- コスト差は SQL 1 回分のみで体感差なし
- §15 D-13 にも記録

**決着**: ✅ 確定（v0.2）。

### Q8: status × visibility の合成方針（v0.2 で確定）

**論点**: 削除済み・下書き・公開予約中・アーカイブ済みコンテンツの可視性をどう扱うか。

**v0.2 確定**: `ContentStatus { DRAFT, SCHEDULED, PUBLISHED, ARCHIVED, DELETED }` を共通基盤で標準化。各 Resolver は `visibleByStatus()` を `evaluate()` の前段ガードとして必ず実行 (§7.5)。判定ルール:
- `DELETED`: 誰でも不可視（fail-closed）
- `ARCHIVED`: SystemAdmin のみ可視
- `DRAFT/SCHEDULED`: SystemAdmin or author のみ可視
- `PUBLISHED`: visibility 評価へ進む

**決着**: ✅ 確定（v0.2）。§7.5、§15 D-15 参照。

### Q9: AccessControlService 拡張先の方針（v0.2 で確定）

**論点**: 本基盤に必要なバルク判定 API を AccessControlService に直接追加するか、新クラスを置くか。

**v0.2 確定**: 新クラス `MembershipBatchQueryService` を `common/visibility/` に置く。AccessControlService 既存メソッドは触らない。`UserRoleRepository` には新規 3 メソッド追加。

**決着**: ✅ 確定（v0.2）。§10.2、§15 D-14 参照。

### Q10: Resolver 同士の循環参照リスク（v0.2 で方針確定）

**論点**: コルクボードカード Resolver のような「コンテナ型」が他 Resolver を呼ぶとき、循環依存・評価ループ・スタック深度のリスクがある。

**v0.2 方針**:
- Resolver は他 Resolver を直接 inject しない（ArchUnit `resolvers_dont_inject_other_resolvers` で強制）
- 必要時は `ContentVisibilityChecker` をフィールドで保持（Spring lazy init で循環回避）
- 再帰呼び出しの深度上限 3、超過で `IllegalStateException`
- `RequestScoped` の `RecursionDepthCounter` Bean で深度管理

**決着**: ✅ 方針確定（v0.2）。実装詳細は Phase A 着手時に微調整。§15 D-16 参照。

### Q11: バッチ判定の API シグネチャ拡張

**論点**: 現状 `filterAccessible(ReferenceType, ids, userId)` は「複数 contentId × 1 userId」だが、Mention 配信では「1 contentId × 複数 userId」が必要。逆向き API を追加するか?

**現状方針 (v0.2)**: Phase A は現行 API のみ。Mention 配信側で `userIds.stream().filter(uid -> canView(...))` と書く。これが N+1 を起こすか性能テストで確認し、深刻なら Phase 2 で `filterAccessibleViewers(ReferenceType, contentId, userIds)` API を追加。

**決着**: ▶ Phase A 完了後の性能ベンチマーク結果で判断。

### Q12: AuditLogService への永続化方針

**論点**: visibility deny 時の監査ログを既存 AuditLogService に永続化するか、ログ出力のみで集約基盤に流すか。

**現状方針 (v0.2)**: Phase A 着手時に AuditLogService の API・テーブル設計を確認。候補:
- (A) 全 deny を AuditLogService に永続化（攻撃調査・コンプライアンス）
- (B) deny は WARN ログ + Loki 集約のみ、AuditLogService への永続化はしない
- (C) センシティブな visibility (PRIVATE/CUSTOM_TEMPLATE) のみ永続化

**決着**: ▶ Phase A 着手前に既存 AuditLogService 確認後に確定。

### Q13: SUPPORTERS_AND_ABOVE 等の機能別意図確認

**論点**: §5.2 対応表で `SUPPORTERS_ONLY → SUPPORTERS_AND_ABOVE` 等のマッピングが、各機能の本来意図（例: 「SUPPORTER のみ単独」を意図していたか）と一致するか不明。

**現状方針 (v0.3)**: Phase A 着手前に各機能オーナーへヒアリングを実施し、意図確認:
- Recruitment: `SUPPORTERS_ONLY` の本来意図は SUPPORTER 単独か、MEMBER 含むか
- JobMatching: `TEAM_MEMBERS_SUPPORTERS` の SUPPORTER 含意
- CMS: `SUPPORTERS_AND_ABOVE` で MEMBER を含む意図かどうか

意図が「SUPPORTER 単独」の機能があれば、`StandardVisibility.SUPPORTERS_EXCLUSIVE` の追加または `CUSTOM` 個別処理を検討（§5.1.3 値追加コスト評価込み）。

**決着**: ▶ Phase A 着手前のヒアリング後に確定。

### Q14: 多テナント isolation への対応道筋（道筋のみ示す）

**論点**: 将来 Mannschaft が複数テナント (法人別 isolation) に拡張された場合、本基盤はどう対応するか。

**v0.3 道筋**: Phase 1 では対応しない。将来対応する場合の改修範囲:
- `ScopeKey` を `TenantScopeKey(tenantId, scopeType, scopeId)` に拡張
- 全 17 Mapper の Projection IF 改修
- 全 13 Resolver の loadProjections / Repository クエリ全変更（tenantId 条件追加）
- `MembershipBatchQueryService` のシグネチャ拡張
- `UserScopeRoleSnapshot` に tenantId 軸追加

これは本基盤の根本構造を変えるレベル。実施時は v2.0 メジャー改訂相当として、**専用軍議の起票・既存 PR の凍結・全 Resolver 同時改修** が必要。

早期に「対応していない」を明文化することで、後から「多テナント化したい」軍議が立つ際の手戻りリスクを可視化。

**決着**: ✅ 道筋確定（v0.3）。Phase 1 対象外。

### Q15: 通知・アラート閾値の現実妥当性

**論点**: §9.4 / §11.4 / §14.3 のメトリクス・Slack アラート閾値が現実的か（false positive、アラート疲れ）。

**現状方針 (v0.3)**:
- Phase A 完了時にベースライン測定（1 sprint）
- ベースラインを元に閾値を確定し §11.4 / §14.3 を改訂
- `unsupported_reference_type > 0` のアラートは Phase A 完了後 1 週間 disable（DB 整合性確認後 enable）

**決着**: ▶ Phase A 完了後 1 sprint で確定。

### Q1〜Q6 (v0.1 から継続)

§17 (v0.2) では Q1〜Q6 を以下のように更新:
- **Q1** 未認証ユーザー: 現状 fail-closed (A)、PUBLIC ページ未認証アクセスのユースケース確認待ち → ▶ マスター裁可
- **Q2** 「権限なし」と「存在しない」の HTTP 区別: §7.4 で 403/404 を `DenyReason` 単位で分岐スローする方針に確定。Controller 側で 404 マスクするかは個別判断 → ✅ 方針確定
- **Q3** REGION_MATCH 未実装: 現行追認 → ✅ 確定
- **Q4** バッチ件数上限: 1000件 WARN ログ → ✅ 確定
- **Q5** Phase E のリスク: 機能ごと独立 PR、不安なら Phase E スキップ → ✅ 方針確定
- **Q6** 「存在しない ID」の区別: しない（fail-closed と同じ） → ✅ 確定

---

## 18. 精査履歴

| 回 | 観点 | 指摘数 | 反映状況 |
|---|---|---|---|
| 第 1 回 | セキュリティ・見落とし観点 | **14 件** (Critical 4 / High 8 / Medium 1 / Low 1) | **全件反映済み (v0.2)** |
| 第 2 回 | 保守性・拡張性・運用観点 | **17 件** (Critical 0 / High 6 / Medium 9 / Low 2) | **全件反映済み (v0.3)** |

### 第 1 回精査結果と反映状況（2026-05-04 実施）

| # | 観点 | 指摘 | 反映先 | 重要度 |
|---|---|---|---|---|
| 1 | セキュリティ/IDOR | SystemAdmin 高速パスが contentId 実存検証をスキップ | §4.5 書き換え、§15 D-13、§17 Q7 確定 | Critical |
| 2 | セキュリティ/網羅 | 通知配信の Resolver 必須利用リストに ChatMessage / Comment / Reaction / FileSharing 系が漏れ | §11.1 表拡張、§13.5 ArchUnit を依存ベース検査に変更、Phase A 棚卸し義務付け | Critical |
| 3 | N+1 | MEMBERS_ONLY の評価が異 scopeId 混在で N+1 (filterAccessible 内) | §4.5 を `MembershipBatchQueryService.snapshotForUser` ベースに書き換え、§13.4 性能テストに混在シナリオ追加 | Critical |
| 4 | セマンティクス | status × visibility の交差が完全に未定義 | §7.5 新章追加、`ContentStatus` 標準化、§15 D-15、§17 Q8 確定 | Critical |
| 5 | セマンティクス | 親スコープ（ORG）連鎖ルール未定義 | §5.1.1 親スコープ解決規約、§11.6 連鎖ルール、`ScopeAncestorResolver` 新設 | High |
| 6 | セマンティクス | SUPPORTERS_AND_ABOVE の包含順位がコードと文書で矛盾の可能性 | §5.1 包含定義を明示、各機能の意図確認を §17 Q8 で継続 | High |
| 7 | セキュリティ | 未対応 ReferenceType の DB 由来データへの脆弱性、cardinality 爆発 | §11.2 ヘルスチェック追加、§15 D-12 値削除禁止 | High |
| 8 | 後方互換 | AccessControlService「メソッド追加なし」が現実的に守れない | §10.2 を新規 `MembershipBatchQueryService` 設置に書き換え、§15 D-14、§17 Q9 確定 | High |
| 9 | マッピング | PersonalTimetable/FollowList を Phase 1 対象外にすると Mention 漏れ温床 | §16.2 で Phase 2 予約に格上げ、§3.3 ReferenceType に先行定義、§13.5 ArchUnit で Phase 1 利用禁止 | High |
| 10 | マッピング | ChatMessage/Comment/Document/FileAttachment が ReferenceType に欠 | §3.3 で 13→17 種に拡張、Phase 割当を再考 | High |
| 11 | API | assertCanView と canView/filterAccessible の「存在しない vs 権限なし」区別不一致 | §7.4 に `VISIBILITY_004 (404)` 追加、`DenyReason` 単位で分岐、`checkCanView` エイリアス追加、§17 Q2 確定 | High |
| 12 | テスト | ArchUnit が依存しか見ず「呼び忘れ」を検出不可 | §13.5 に Mockito ガードテスト `*VisibilityGuardTest` 追加、`InOrder` で save 前 canView を検証 | High |
| 13 | セキュリティ | 監査ログが攻撃検知に不十分（IP/UA/sessionId/requestId なし） | §11.4 を MDC ベースに書き換え、PRIVATE/CUSTOM_TEMPLATE 成功時の opt-in 記録、Slack アラート、§17 Q12 | High |
| 14 | アーキ | Resolver 同士の循環参照リスクが未解決 | §17 Q10 確定、§15 D-16、§13.5 ArchUnit で他 Resolver 直接 inject 禁止 | Medium |

### 第 2 回精査結果と反映状況（2026-05-04 実施）

| # | 観点 | 指摘 | 反映先 | 重要度 |
|---|---|---|---|---|
| 1 | 保守性 | §4.5 サンプルが「30 行」と矛盾、抽象基底クラスの不在 | §4.6 `AbstractContentVisibilityResolver` 新設、Resolver 本体 25 行で完結 | High |
| 2 | 保守性 | §6.3「30 行で完結」が付随作業を隠蔽 | §6.3.1 最小手順 / §6.3.2 完全手順 (12 ステップ) に分割 | High |
| 3 | 拡張性 | StandardVisibility 値追加コストの記述欠落 | §5.1.3 値追加コスト表新設 | Medium |
| 4 | 保守性 | 機能側 enum 改廃時のフロー文書化欠落 | §5.4 機能側 enum 改廃時の手順 新設 (7 ステップ) | High |
| 5 | 段階移行 | Phase A 工数 1 週が著しく過小 | §12.1 全体 4〜5週→6〜8週、Phase A 1週→2〜3週、A-1〜A-8 内訳追加 | High |
| 6 | 段階移行 | visibility 概念新設機能の扱い未文書化 | §3.3 表に「visibility 戦略」列追加、§12.3.1 新設 | High |
| 7 | 段階移行 | Phase E の機能別リスク評価がフラット | §12.6.1 機能別リスク評価マトリクス（13 機能 × リスク・着手順・FF 要否） | Medium |
| 8 | テスト | テスト総量見積もり欠落 | §13.7 約 433 ケース・約 22 日（≒ 4 週）の内訳表 | Medium |
| 9 | テスト | DataJpaTest + Hibernate Statistics の脆弱性 | §13.4 `SqlIntentCounter` 導入、Testcontainers 化、許容バッファ +1、異 scopeId シナリオ追加 | Medium |
| 10 | 命名 | `checkCanView` エイリアスが規約矛盾 | §15 D-5 改訂で `assertCanView` のみ提供、`checkCanView` は作らない、§7.4 整合修正 | Medium |
| 11 | 拡張性 | 多テナント拡張時の API 拡張余地未検討 | §16.1 に「多テナント Out of Scope」追加、§17 Q14 で道筋明文化 | Low |
| 12 | 拡張性 | CUSTOM 値の運用上限規約欠落 | §5.1.4 CUSTOM 利用の運用規約（30% 上限・新規禁止・コード上限・メトリクス） | Medium |
| 13 | 運用 | VISIBILITY_001〜004 の i18n 規約欠落 | §7.4 表に ja メッセージ案追加、§7.4.1 i18n 要件（6 言語必須）新設 | Medium |
| 14 | テスト | テスト用ユーティリティ `VisibilityCheckerTestSupport` の不在 | §13.8 新設、Phase A 成果物に追加、TEST_CONVENTION.md 規約化 | High |
| 15 | ドキュメント | `.claudecode.md` 章番号確認欠落 | Phase A タスク (A-8) に「`.claudecode.md` の現行章番号確認 → 空き番号確定」を含む旨追記 | Low |
| 16 | 運用 | メトリクス・アラート閾値の整合性・現実性 | §9.4 表に `denied` / `custom_dispatch_count` 追加、§17 Q15 で Phase A 完了後ベースライン測定 | Medium |
| 17 | ドキュメント | 付録 A シーケンス図が v0.2 改訂を反映していない | A.1 / A.2 を v0.3 完全反映に書き直し（snapshotForUser 追加、SystemAdmin 短絡 Resolver 内、status × visibility ガード、Phase 2 性能改善コメント） | Medium |

**v0.3 反映完了。第 2 回精査全件反映済み。設計書 🟢 設計完了 確定可。**

### 残 Open Questions の決着状況（v0.3 時点）

| Q | 内容 | 状態 |
|---|---|---|
| Q1 | 未認証ユーザー扱い | ✅ 確定（マスター裁可、PUBLIC のみ閲覧可、status=PUBLISHED 限定） |
| Q2 | 「権限なし」と「存在しない」の HTTP 区別 | ✅ 確定（§7.4: VISIBILITY_004 追加で 403/404 分岐スロー） |
| Q3 | REGION_MATCH 未実装 | ✅ 確定（現状追認） |
| Q4 | バッチ件数上限 | ✅ 確定（1000 件 WARN ログ） |
| Q5 | Phase E のリスク管理 | ✅ 方針確定（§12.6.1 機能別マトリクス） |
| Q6 | 「存在しない ID」の区別 | ✅ 確定（区別しない） |
| Q7 | SystemAdmin 高速パス | ✅ 確定（v0.2 / §15 D-13） |
| Q8 | status × visibility 合成 | ✅ 確定（v0.2 / §7.5） |
| Q9 | AccessControlService 拡張先 | ✅ 確定（v0.2 / 新クラス MembershipBatchQueryService） |
| Q10 | Resolver 同士の循環参照 | ✅ 方針確定（§D-16） |
| Q11 | バッチ判定 API シグネチャ拡張 | ▶ Phase A 完了後の性能ベンチマーク結果で判断 |
| Q12 | AuditLogService 永続化方針 | ▶ Phase A 着手前に既存 AuditLogService 確認後確定 |
| Q13 | SUPPORTERS_AND_ABOVE 等の機能別意図確認 | ▶ Phase A 着手前に機能オーナーヒアリング |
| Q14 | 多テナント isolation 道筋 | ✅ 道筋確定（v0.3 / Phase 1 対象外） |
| Q15 | 通知・アラート閾値の妥当性 | ▶ Phase A 完了後 1 sprint で確定 |

**マスター裁可待ち項目**: なし（Q1 確定済）。Q11/Q12/Q13/Q15 は Phase A 着手前後の調査・確認で確定可（ヒアリング対象が明確）。

---

## 19. 関連ファイル一覧

### 19.1 新規作成

#### Phase A
```
backend/src/main/java/com/mannschaft/app/common/visibility/
├── ContentVisibilityChecker.java
├── ContentVisibilityResolver.java               # Strategy interface
├── AbstractContentVisibilityResolver.java       # 共通テンプレート (§4.6)
├── ReferenceType.java
├── StandardVisibility.java
├── ContentStatus.java                           # §7.5 status 標準化
├── VisibilityProjection.java                    # 共通 IF
├── VisibilityDecision.java
├── DenyReason.java
├── ScopeKey.java
├── UserScopeRoleSnapshot.java
├── MembershipBatchQueryService.java             # §10.2 新設
├── ScopeAncestorResolver.java                   # §5.1.1 親 ORG 解決
├── FollowBatchService.java                      # FOLLOWERS_ONLY 用
├── VisibilityErrorCode.java
├── VisibilityMetrics.java
├── MembershipQueryCache.java                    # @RequestScope
├── ReferenceTypeIntegrityCheck.java             # @PostConstruct ヘルスチェック
├── RecursionDepthCounter.java                   # @RequestScope (§D-16)
├── package-info.java
├── testsupport/
│   └── VisibilityCheckerTestSupport.java       # §13.8 テスト用ユーティリティ
└── mapping/
    ├── package-info.java
    ├── CmsVisibilityMapper.java
    ├── EventVisibilityMapper.java
    ├── ActivityVisibilityMapper.java
    ├── TournamentVisibilityMapper.java
    ├── TimetableVisibilityMapper.java
    ├── MemberPageVisibilityMapper.java
    ├── ProjectVisibilityMapper.java
    ├── ScheduleVisibilityMapper.java
    ├── RecruitmentVisibilityMapper.java
    ├── JobMatchingVisibilityMapper.java
    ├── AlbumVisibilityMapper.java
    ├── OrgVisibilityMapper.java
    ├── CommitteeVisibilityMapper.java
    ├── SurveyResultsVisibilityMapper.java
    ├── SurveyUnrespondedVisibilityMapper.java
    ├── ConfirmableUnconfirmedVisibilityMapper.java
    └── MatchingVisibilityMapper.java

backend/src/test/java/com/mannschaft/app/common/visibility/
├── ContentVisibilityCheckerTest.java
├── VisibilityArchitectureTest.java              # ArchUnit
├── perf/
│   └── VisibilityCheckerPerformanceTest.java
└── mapping/
    └── (各 Mapper の param 化テスト 17 個)
```

#### Phase B〜D
```
backend/src/main/java/com/mannschaft/app/cms/visibility/BlogPostVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/event/visibility/EventVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/activity/visibility/ActivityResultVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/schedule/visibility/ScheduleVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/social/visibility/TimelinePostVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/social/visibility/BulletinThreadVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/tournament/visibility/TournamentVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/recruitment/visibility/RecruitmentListingVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/jobmatching/visibility/JobPostingVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/survey/visibility/SurveyVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/gallery/visibility/PhotoAlbumVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/team/visibility/TeamVisibilityResolver.java
backend/src/main/java/com/mannschaft/app/organization/visibility/OrganizationVisibilityResolver.java

(各々の test, projection, integration test も同パッケージに配置)
```

### 19.2 改修（Phase E）

| ファイル | 変更内容 |
|---|---|
| `cms/service/BlogPostService.java` | `isVisibleTo` 削除、`visibilityChecker` 経由に置換 |
| `event/service/EventService.java` | 同上 |
| `team/service/TeamExtendedProfileService.java` | 重複 if 削除 |
| `organization/service/OrganizationExtendedProfileService.java` | 同上 |
| `gallery/service/PhotoAlbumService.java` | 未実装の visibility フィルタ実装 |
| `survey/service/SurveyService.java` | ResultsVisibility/UnrespondedVisibility の判定実装 |
| `schedule/service/ScheduleService.java` | visibility フィルタ追加 |
| `recruitment/service/RecruitmentListingService.java` | Phase 2 留保コードを実装 |
| (他 Phase B〜D 対象機能の Service) | Resolver 経由に切り替え |

### 19.3 改修（Phase F: セキュリティ漏れ修正）

| ファイル | 変更内容 |
|---|---|
| `notification/service/NotificationDispatchService.java` | mention 配信前に `canView` チェック |
| `social/mention/MentionDetectionService.java` | mention 抽出後に `filterAccessible` でフィルタ |
| `circulation/service/CirculationDocumentService.java` | 配信先決定後に visibility チェック |
| `social/timeline/TimelinePostService.java` | mention 通知送信前にチェック |
| `social/bulletin/BulletinReplyService.java` | 返信通知送信前にチェック |

### 19.4 ドキュメント追記

| ファイル | 追記内容 |
|---|---|
| `backend/.claudecode.md` | **章番号確認の上**、空き番号として「ContentVisibilityResolver の使用ルール」を追記（v0.3 時点で §29 想定だが Phase A の A-8 工程で現行最終章を確認後に確定）。CUSTOM 利用規約 (§5.1.4)・機能側 enum 改廃手順 (§5.4) も含む |
| `backend/BACKEND_CODING_CONVENTION.md` | 新規 Resolver 実装手順、Mapper の規約 |
| `TEST_CONVENTION.md` | Resolver テスト規約、ArchUnit ルール |
| `README.md` | アーキテクチャ図に共通基盤として追加 |
| `docs/features/F09.8.1_corkboard_pin_dashboard.md` | §5.2 を本基盤前提に書き換え（Phase D 完了後） |
| `~/.claude/projects/.../memory/project_visibility_enum_unification_pending.md` | 「F00 で本格対応」に状態更新 |

---

## 付録 A: 主要シーケンス図

### A.1 コルクボードカード一覧取得 (filterAccessibleByType) — v0.3 改訂

```
Frontend          CorkboardController       CorkboardCardService
  │                     │                         │
  ├─ GET /cards ─▶      │                         │
  │                     ├─ listCards(boardId, userId) ─▶│
  │                     │                         ├─ findCardsByBoardId(boardId)
  │                     │                         │
  │                     │                         ├─ groupReferencesByType(cards)
  │                     │                         │  → Map<RefType, List<Long>>
  │                     │                         │
  │                     │                         ├─ visibilityChecker.filterAccessibleByType(map, userId)
  │                     │                         │   │
  │                     │                         │   for each (type, ids):
  │                     │                         │   │
  │                     │                         │   ├─ resolver.loadProjections(ids)  ── SQL #1 (e.g. blog_posts)
  │                     │                         │   │   ◀ List<P>（実存 row のみ、IDOR safe）
  │                     │                         │   │
  │                     │                         │   ├─ membershipBatchQueryService.snapshotForUser(userId, scopes, orgWideScopes)
  │                     │                         │   │   ├─ existsSystemAdminByUserId(userId) ── SQL #2
  │                     │                         │   │   ├─ findByUserIdAndScopes(...)         ── SQL #3
  │                     │                         │   │   └─ scopeAncestorResolver.resolveParentOrgIds(orgWideScopes)
  │                     │                         │   │       └─ findOrganizationIdByTeamIdIn ── SQL #4 (条件付き)
  │                     │                         │   │   ◀ UserScopeRoleSnapshot (含 sysAdmin / 親ORG非アクティブ)
  │                     │                         │   │
  │                     │                         │   ├─ rows.filter(visibleByStatus)   ── §7.5 status × visibility
  │                     │                         │   ├─ rows.filter(visibleByVisibility) ── §11.6 親ORG連鎖
  │                     │                         │   │
  │                     │                         │   ◀ Set<Long> accessible
  │                     │                         │
  │                     │                         ├─ assemble DTOs with is_accessible flag
  │                     │ ◀── List<CardDto> ──────│
  │ ◀───────────────────│                         │
```

**性能**: 4 type × 2-3 SQL = SQL 数 ≦ 12（F09.8.1 §10.4 目標 ≦ 8 は将来 caller 側で複数 type をまとめて 1 SQL にする最適化で達成）。MembershipBatchQueryService の snapshot 構築を全 type で **1 回だけ**呼ぶ最適化を Phase 2 で検討。

### A.2 Mention 通知配信 (canView による配信先フィルタ) — v0.3 改訂

```
TimelinePostService    MentionDetectionService     ContentVisibilityChecker
  │                          │                            │
  ├─ createPost(content, userId) ─▶                       │
  │                          ├─ extractMentions(content)
  │                          │  → List<Long> mentionedIds (例 50 件)
  │                          │
  │                          ├─ for each mentionedId:                  ⚠️ Phase 2 性能改善候補 (§17 Q11)
  │                          │     visibilityChecker.canView(            現状 50 回ループ → 内部 SQL は
  │                          │       TIMELINE_POST, postId, mid) ────▶ │ membershipQueryCache(@RequestScope) で
  │                          │                            │           │ 同一 userId は 2 回目以降キャッシュヒット
  │                          │ ◀─── boolean ──────────────│           │
  │                          │
  │                          ├─ filter out non-accessible
  │ ◀── allowedMentionedIds ─│
  │
  ├─ NotificationDispatchService.dispatch(allowedMentionedIds, ...)
  │   └─ ↑ ArchUnit + Mockito ガードテストで save 前 canView を強制 (§13.5)
```

**Phase 2 改善案**: 「1 contentId × N userIds」のシグネチャ `filterAccessibleViewers(refType, contentId, userIds)` を追加し、Resolver 内で 1 回の snapshot 構築で N userId 判定を実行することで N+1 を完全解消（§17.Q11）。

---

## 付録 B: 用語集

| 用語 | 定義 |
|---|---|
| ContentVisibilityResolver | reference_type 1 つを担当する Strategy 実装 |
| ContentVisibilityChecker | 全 Resolver を集約するファサード Bean |
| StandardVisibility | 機能横断で扱う標準可視性 enum |
| ReferenceType | コンテンツ種別 enum (BLOG_POST, EVENT, ...) |
| VisibilityDecision | 判定結果の値オブジェクト (allow/deny + 理由) |
| Mapper | 機能固有 enum を StandardVisibility に変換する static class |
| fail-closed | 不明・例外時に「閲覧不可」を返すセキュリティ原則 |
| 粗粒度権限 | スコープ・ロール・メンバーシップ単位の権限 (AccessControlService の責務) |
| 細粒度可視性 | コンテンツ・テンプレート単位の可視性 (本基盤の責務) |

---

**v1.0 設計完了**

履歴:
- v0.1 (2026-05-04): 初稿ドラフト 1,548 行 / 19 章 / 付録 2
- v0.2 (2026-05-04): 第 1 回精査（セキュリティ・見落とし観点）14 件全件反映
- v1.0 (2026-05-04): 第 2 回精査（保守性・拡張性・運用観点）17 件全件反映、🟢 設計完了

次フェーズ: マスター上奏 → 御裁可後に Phase A 着手（2〜3 週見込み）→ Phase B〜F へ展開（全体 6〜8 週見込み）。
