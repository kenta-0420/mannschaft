package com.mannschaft.app.common.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F00 共通可視性判定の Resolver 共通テンプレート（テンプレートメソッドパターン）。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 完全一致。
 * §5.1 / §7.5 / §11.6 / §15 D-13/D-14/D-15/D-16 の規約を Resolver 個別実装に分散させず
 * 本基底クラスで強制する。</p>
 *
 * <p>サブクラスは以下の最小契約のみ実装すればよく、Resolver 本体は約 25〜30 行で完結する：</p>
 * <ul>
 *   <li>{@link #referenceType()}（{@link ContentVisibilityResolver} の必須）</li>
 *   <li>{@link #loadProjections(Collection)} — 機能 Repository から実存 Projection を 1 SQL で取得</li>
 *   <li>{@link #toStandard(Enum)} — 機能側 visibility enum → {@link StandardVisibility} 正規化</li>
 *   <li>{@link #toContentStatus(VisibilityProjection)} — 任意（既定: PUBLISHED）</li>
 *   <li>{@link #evaluateCustom(VisibilityProjection, Long, UserScopeRoleSnapshot)} — 任意（既定: false）</li>
 * </ul>
 *
 * <p><strong>本基底が強制する判定パイプライン</strong>:</p>
 * <ol>
 *   <li>実存確認込み射影取得（SQL 1）</li>
 *   <li>必要スコープ集合の集計（direct / orgWide）</li>
 *   <li>{@link MembershipBatchQueryService} で snapshot 構築（最大 SQL 5）</li>
 *   <li>{@link #visibleByStatus(VisibilityProjection, Long, UserScopeRoleSnapshot)} —
 *       §7.5 status × visibility 合成のうち status 軸ガード（DELETED / ARCHIVED / DRAFT / SCHEDULED）</li>
 *   <li>{@link #visibleByVisibility(VisibilityProjection, Long, UserScopeRoleSnapshot)} —
 *       SystemAdmin 高速パス（§15 D-13）→ §11.6 親 ORG 連鎖ガード →
 *       {@link StandardVisibility} 9 値 × {@link UserScopeRoleSnapshot} 評価</li>
 * </ol>
 *
 * <p><strong>SystemAdmin 高速パス（§15 D-13）</strong>: 本ファサードでは扱わず、Resolver 内で
 * 「実存確認後に systemAdmin なら status 評価のみ実行し visibility 評価をスキップ」する。これにより
 * 「存在しない id を accessible として返す」セマンティクスを排除し、IDOR 防止 §11.3 と整合する。</p>
 *
 * <p><strong>監査ログ連携（§11.4 / マスター裁可 C-1）</strong>: {@link #decide(Long, Long)} 経由で
 * センシティブな {@link StandardVisibility}（PRIVATE / CUSTOM_TEMPLATE / ADMINS_ONLY）の
 * deny / allow を {@link ResolverAuditPolicy} 判定の上 {@link AuditLogService} へ非同期記録する。</p>
 *
 * <p><strong>メトリクス（§9.4）</strong>: {@link VisibilityMetrics#recordCustomDispatch} を CUSTOM
 * 経路で必ず呼ぶ。レイテンシ系は呼び出し元 {@link ContentVisibilityChecker} 側で計測される。</p>
 *
 * <p><strong>循環参照防止（§15 D-16）</strong>: 本基底は {@link ContentVisibilityResolver} 型の
 * フィールドを持たない。コンテナ型コンテンツ（コルクボード等）で他 type 判定が必要な Resolver は
 * {@link ContentVisibilityChecker} を inject し、{@link #checker()} 経由で参照する。</p>
 *
 * @param <V> 機能固有 visibility enum 型
 * @param <P> 機能側 Projection 型（{@link VisibilityProjection} を実装）
 */
@Slf4j
public abstract class AbstractContentVisibilityResolver<V extends Enum<V>, P extends VisibilityProjection>
        implements ContentVisibilityResolver<V> {

    /** §10.2 メンバーシップ・ロール情報のバルク照会サービス（必須）。 */
    protected final MembershipBatchQueryService membershipBatchQueryService;

    /** §5.1 CUSTOM_TEMPLATE 評価サービス（必須、未利用 Resolver でも null 不可）。 */
    protected final VisibilityTemplateEvaluator templateEvaluator;

    /** §9.4 メトリクス（必須、CUSTOM 細分種別の集計に利用）。 */
    protected final VisibilityMetrics visibilityMetrics;

    /**
     * §4.6 任意依存。FOLLOWERS_ONLY を扱う Resolver のみ Bean 配線が必要。
     * Spring 側で Bean 不在の場合は {@code null} 注入される。
     */
    protected final FollowBatchService followBatchService;

    /**
     * §11.4 マスター裁可 C-1。センシティブ visibility の allow/deny 監査ログ記録に用いる。
     * テスト構成で Bean 未配線の場合は {@code null}。
     */
    protected final AuditLogService auditLogService;

    /**
     * Resolver 共通テンプレートの依存を全部受け取るコンストラクタ。
     *
     * <p>サブクラスは {@code @RequiredArgsConstructor} と機能 Repository を併用し、
     * 本コンストラクタを {@code super(...)} 呼び出し（Lombok の場合は明示）で連鎖させる。</p>
     *
     * @param membershipBatchQueryService メンバーシップ照会サービス（必須）
     * @param templateEvaluator           CUSTOM_TEMPLATE 評価サービス（必須）
     * @param visibilityMetrics           Micrometer メトリクスヘルパ（必須）
     * @param followBatchService          フォロー判定 SPI（任意、{@code null} 可）
     * @param auditLogService             監査ログサービス（任意、{@code null} 可）
     */
    protected AbstractContentVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService) {
        this.membershipBatchQueryService = Objects.requireNonNull(
                membershipBatchQueryService, "membershipBatchQueryService must not be null");
        this.templateEvaluator = Objects.requireNonNull(
                templateEvaluator, "templateEvaluator must not be null");
        this.visibilityMetrics = Objects.requireNonNull(
                visibilityMetrics, "visibilityMetrics must not be null");
        this.followBatchService = followBatchService;
        this.auditLogService = auditLogService;
    }

    // ========================================================================
    // サブクラス契約
    // ========================================================================

    /**
     * 機能側 Repository から、実存する Projection を 1 SQL で取得する。
     *
     * <p>「IDOR 防止 §11.3」の DB lookup ベース原則のため、ここで返ってこない ID は
     * 「不存在 or 論理削除」として扱う（false / NOT_FOUND）。</p>
     *
     * @param ids 取得対象 ID 集合（空でない、{@code null} ではない）
     * @return 実存する Projection の List（空でも null 不可）
     */
    protected abstract List<P> loadProjections(Collection<Long> ids);

    /**
     * 機能側 visibility enum 値を {@link StandardVisibility} に正規化する。
     *
     * <p>各機能の {@code mapping/*VisibilityMapper} を呼び出すラッパとして実装するのが定石。</p>
     *
     * @param visibility 機能側 enum 値（{@code null} 不可、null は呼び出し前にガードされる）
     * @return 対応する {@link StandardVisibility}
     */
    protected abstract StandardVisibility toStandard(V visibility);

    /**
     * 機能側 status enum 値を {@link ContentStatus} に正規化する（任意）。
     *
     * <p>既定実装は {@link ContentStatus#PUBLISHED}（status 軸を持たない機能用）。</p>
     *
     * @param row 判定対象の Projection
     * @return 対応する {@link ContentStatus}
     */
    protected ContentStatus toContentStatus(P row) {
        return ContentStatus.PUBLISHED;
    }

    /**
     * {@link StandardVisibility#CUSTOM} 値の機能個別判定（任意）。
     *
     * <p>既定実装は fail-closed の {@code false}。CUSTOM 経路を持つ機能（Survey / Committee 等）は
     * 必ずオーバーライドして「機能独自セマンティクス」を実装すること。</p>
     *
     * @param row          判定対象の Projection
     * @param viewerUserId 閲覧者 user_id（{@code null} 可、未認証）
     * @param snapshot     メンバーシップスナップショット
     * @return 閲覧可能なら {@code true}
     */
    protected boolean evaluateCustom(P row, Long viewerUserId, UserScopeRoleSnapshot snapshot) {
        return false;
    }

    /**
     * {@link StandardVisibility#CUSTOM} の細分種別文字列（任意）。
     *
     * <p>{@link VisibilityMetrics#recordCustomDispatch} のタグに用いる。
     * 既定は機能側 enum の名前（例: {@code AFTER_RESPONSE}）。
     * 細分種別を持たない機能は {@code "DEFAULT"} を返すなど任意に設計可能。</p>
     *
     * @param row 判定対象の Projection
     * @return CUSTOM 細分種別タグ
     */
    protected String customSubType(P row) {
        Object v = row.visibility();
        return v == null ? "UNKNOWN" : v.toString();
    }

    /**
     * 将来のコンテナ型 Resolver（コルクボード等）が他 type 判定を行う際に
     * {@link ContentVisibilityChecker} を取り出すフック（任意）。
     *
     * <p>§15 D-16 により、Resolver は他 Resolver を直接 inject せず本フックでアクセスする。
     * Phase 1 では使用されない。</p>
     *
     * @return 注入された {@link ContentVisibilityChecker}（既定 {@code null}）
     */
    protected ContentVisibilityChecker checker() {
        return null;
    }

    // ========================================================================
    // テンプレート（finalize して挙動を固定する）
    // ========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>テンプレートメソッド: 内部で {@link #filterAccessible(Collection, Long)} を呼び、
     * 結果に対象 ID が含まれるかで判定する。SystemAdmin 高速パスや status / visibility
     * ガードはすべて {@link #filterAccessible} 側で実装される。</p>
     */
    @Override
    public final boolean canView(Long contentId, Long viewerUserId) {
        if (contentId == null) {
            return false;
        }
        return filterAccessible(List.of(contentId), viewerUserId).contains(contentId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>テンプレートメソッド: 設計書 §4.6 のパイプラインを忠実に実装する。
     * 例外は内部で握り潰さず、SQL 例外等はそのまま伝播させる（fail-closed の判定は
     * 各ガードメソッドで明示的に false を返すことで表現する）。</p>
     */
    @Override
    public final Set<Long> filterAccessible(Collection<Long> contentIds, Long viewerUserId) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Set.of();
        }

        // 1) 実存確認込み射影取得（SQL 1）。null Projection は除外（fail-closed）。
        List<P> rows = loadProjections(contentIds);
        if (rows == null || rows.isEmpty()) {
            return Set.of();
        }

        // 2) 必要スコープを集計（直接所属判定 / ORGANIZATION_WIDE 親解決）。
        Set<ScopeKey> directScopes = collectDirectScopes(rows);
        Set<ScopeKey> orgWideScopes = collectOrgWideScopes(rows);

        // 3) snapshot 構築（最大 SQL 5、SystemAdmin なら SQL 1）。
        UserScopeRoleSnapshot snapshot = membershipBatchQueryService
                .snapshotForUser(viewerUserId, directScopes, orgWideScopes);

        // 4) 各 row の判定（DB アクセスなし、純メモリ）。
        Set<Long> result = new HashSet<>();
        for (P row : rows) {
            if (row == null || row.id() == null) {
                continue;
            }
            if (!visibleByStatus(row, viewerUserId, snapshot)) {
                continue;
            }
            if (!visibleByVisibility(row, viewerUserId, snapshot)) {
                continue;
            }
            result.add(row.id());
        }
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link ContentVisibilityResolver#decide} の既定実装をオーバーライドし、
     * 実存確認結果と解決した {@link StandardVisibility} を {@link VisibilityDecision#resolvedLevel}
     * に載せて返す。これにより {@link ResolverAuditPolicy} がセンシティブ判定を行える。</p>
     *
     * <p>実存しない場合は {@link DenyReason#NOT_FOUND}、status / visibility ガードで弾かれた
     * 場合は適切な {@link DenyReason} を分類して返す。allow / deny どちらでもセンシティブ条件
     * 該当時は {@link AuditLogService} に非同期記録する。</p>
     */
    @Override
    public final VisibilityDecision decide(Long contentId, Long viewerUserId) {
        if (contentId == null) {
            return VisibilityDecision.deny(referenceType(), null, DenyReason.NOT_FOUND,
                    "contentId is null");
        }

        // 1) 実存確認（SQL 1）
        List<P> rows = loadProjections(List.of(contentId));
        if (rows == null || rows.isEmpty()) {
            return VisibilityDecision.deny(referenceType(), contentId, DenyReason.NOT_FOUND);
        }
        P row = rows.get(0);
        if (row == null || row.id() == null) {
            return VisibilityDecision.deny(referenceType(), contentId, DenyReason.NOT_FOUND);
        }

        // 2) snapshot 構築
        Set<ScopeKey> directScopes = collectDirectScopes(rows);
        Set<ScopeKey> orgWideScopes = collectOrgWideScopes(rows);
        UserScopeRoleSnapshot snapshot = membershipBatchQueryService
                .snapshotForUser(viewerUserId, directScopes, orgWideScopes);

        // 3) status ガード
        if (!visibleByStatus(row, viewerUserId, snapshot)) {
            ContentStatus status = toContentStatus(row);
            DenyReason reason = switch (status) {
                case DELETED -> DenyReason.NOT_FOUND;
                case DRAFT, SCHEDULED, ARCHIVED -> DenyReason.NOT_OWNER;
                default -> DenyReason.UNSPECIFIED;
            };
            String detail = "status=" + status;
            VisibilityDecision decision = decisionWithLevel(false, contentId, reason, null, detail);
            recordAudit(decision, viewerUserId);
            return decision;
        }

        // 4) visibility 解決
        StandardVisibility level = resolveLevelSafely(row);
        if (level == null) {
            // visibility 値が不正 or null → fail-closed
            VisibilityDecision decision = decisionWithLevel(false, contentId,
                    DenyReason.UNSPECIFIED, null, "visibility resolution failed");
            recordAudit(decision, viewerUserId);
            return decision;
        }

        // CUSTOM 経路は分岐前にメトリクス記録（評価結果に関わらず利用ログとして残す）
        if (level == StandardVisibility.CUSTOM) {
            visibilityMetrics.recordCustomDispatch(referenceType(), customSubType(row));
        }

        // 5) visibility ガード
        boolean allowed = visibleByVisibility(row, viewerUserId, snapshot);
        DenyReason denyReason = allowed ? null : classifyDenyReason(level, row, viewerUserId, snapshot);
        VisibilityDecision decision = decisionWithLevel(allowed, contentId, denyReason, level, null);
        recordAudit(decision, viewerUserId);
        return decision;
    }

    // ========================================================================
    // 内部実装
    // ========================================================================

    /**
     * §7.5 status × visibility 合成における status 軸ガード。
     *
     * <ul>
     *   <li>{@link ContentStatus#DELETED} → 誰も不可視（SystemAdmin も含む）</li>
     *   <li>{@link ContentStatus#ARCHIVED} → SystemAdmin のみ可視</li>
     *   <li>{@link ContentStatus#DRAFT} / {@link ContentStatus#SCHEDULED} → SystemAdmin or 作成者本人</li>
     *   <li>{@link ContentStatus#PUBLISHED} → visibility 評価へ</li>
     * </ul>
     */
    private boolean visibleByStatus(P row, Long viewerUserId, UserScopeRoleSnapshot snapshot) {
        ContentStatus status = toContentStatus(row);
        if (status == null) {
            // fail-closed: status 取得失敗
            log.warn("toContentStatus returned null: referenceType={} id={}",
                    referenceType(), row.id());
            return false;
        }
        return switch (status) {
            case DELETED -> false;
            case ARCHIVED -> snapshot.isSystemAdmin();
            case DRAFT, SCHEDULED -> snapshot.isSystemAdmin()
                    || (viewerUserId != null && Objects.equals(viewerUserId, row.authorUserId()));
            case PUBLISHED -> true;
        };
    }

    /**
     * §11.6 親 ORG 連鎖ガード付き visibility 評価。
     *
     * <p>SystemAdmin 高速パス（§15 D-13）はここで処理する：systemAdmin なら status ガード通過後
     * 即 true を返し、9 値の評価をスキップする。</p>
     */
    private boolean visibleByVisibility(P row, Long viewerUserId, UserScopeRoleSnapshot snapshot) {
        // §15 D-13: SystemAdmin 高速パス（実存確認済の row に対して短絡）
        if (snapshot.isSystemAdmin()) {
            return true;
        }

        // §11.6 親 ORG 非アクティブなら即不可視
        ScopeKey scope = scopeOf(row);
        if (scope != null && snapshot.isParentOrgInactive(scope)) {
            return false;
        }

        StandardVisibility level = resolveLevelSafely(row);
        if (level == null) {
            return false;
        }

        return switch (level) {
            case PUBLIC -> true;
            case MEMBERS_ONLY -> scope != null && snapshot.isMemberOf(scope);
            case SUPPORTERS_AND_ABOVE -> scope != null
                    && snapshot.hasRoleOrAbove(scope, "SUPPORTER");
            case ADMINS_ONLY -> scope != null
                    && snapshot.hasRoleOrAbove(scope, "ADMIN");
            case ORGANIZATION_WIDE -> scope != null && snapshot.isMemberOfParentOrg(scope);
            case PRIVATE -> viewerUserId != null
                    && Objects.equals(viewerUserId, row.authorUserId());
            case CUSTOM_TEMPLATE -> row.visibilityTemplateId() != null
                    && templateEvaluator.canView(viewerUserId, row.visibilityTemplateId(),
                            row.authorUserId());
            case FOLLOWERS_ONLY -> followBatchService != null
                    && viewerUserId != null
                    && followBatchService.isFollower(viewerUserId, row.authorUserId());
            case CUSTOM -> {
                // filterAccessible 経路では recordCustomDispatch がここで行われる。
                // decide 経路ではすでに記録済みのため二重記録を避けるための分岐は持たない
                // （Counter は単調増加で許容、ホットパスではないため）。
                visibilityMetrics.recordCustomDispatch(referenceType(), customSubType(row));
                yield evaluateCustom(row, viewerUserId, snapshot);
            }
        };
    }

    /**
     * deny 時の {@link DenyReason} を可能な限り分類する。
     */
    private DenyReason classifyDenyReason(
            StandardVisibility level, P row, Long viewerUserId, UserScopeRoleSnapshot snapshot) {
        if (level == null) {
            return DenyReason.UNSPECIFIED;
        }
        ScopeKey scope = scopeOf(row);
        return switch (level) {
            case PRIVATE -> DenyReason.NOT_OWNER;
            case CUSTOM_TEMPLATE -> DenyReason.TEMPLATE_RULE_NO_MATCH;
            case MEMBERS_ONLY, ORGANIZATION_WIDE ->
                    scope != null && snapshot.roleByScope().containsKey(scope)
                            ? DenyReason.INSUFFICIENT_ROLE : DenyReason.NOT_A_MEMBER;
            case SUPPORTERS_AND_ABOVE, ADMINS_ONLY ->
                    scope != null && snapshot.roleByScope().containsKey(scope)
                            ? DenyReason.INSUFFICIENT_ROLE : DenyReason.NOT_A_MEMBER;
            case FOLLOWERS_ONLY, CUSTOM, PUBLIC -> DenyReason.UNSPECIFIED;
        };
    }

    /**
     * decide 用の {@link VisibilityDecision} 生成（resolvedLevel を埋め込む）。
     *
     * <p>{@link VisibilityDecision} の static factory は resolvedLevel を null 固定で生成するため、
     * 本ヘルパで直接コンストラクタを呼び埋め込む。</p>
     */
    private VisibilityDecision decisionWithLevel(
            boolean allowed, Long contentId, DenyReason denyReason,
            StandardVisibility resolvedLevel, String detail) {
        if (allowed) {
            // インバリアント: allowed=true なら denyReason は null。
            return new VisibilityDecision(referenceType(), contentId, true, null,
                    resolvedLevel, detail);
        }
        DenyReason reason = denyReason != null ? denyReason : DenyReason.UNSPECIFIED;
        return new VisibilityDecision(referenceType(), contentId, false, reason,
                resolvedLevel, detail);
    }

    /**
     * §11.4 マスター裁可 C-1: センシティブ visibility の allow/deny 監査ログ記録。
     *
     * <p>{@link AuditLogService} 未配線時は何もしない（テスト構成での null 安全）。</p>
     */
    private void recordAudit(VisibilityDecision decision, Long viewerUserId) {
        if (auditLogService == null || decision == null) {
            return;
        }
        StandardVisibility level = decision.resolvedLevel();
        boolean shouldRecord = decision.allowed()
                ? ResolverAuditPolicy.shouldAuditAllow(level)
                : ResolverAuditPolicy.shouldAuditDeny(level);
        if (!shouldRecord) {
            return;
        }
        String eventType = decision.allowed()
                ? "VISIBILITY_GRANTED_SENSITIVE"
                : "VISIBILITY_DENIED";
        String metadata = String.format(
                "{\"source\":\"VISIBILITY\",\"referenceType\":\"%s\",\"contentId\":%s,"
                        + "\"resolvedLevel\":\"%s\",\"denyReason\":%s}",
                decision.referenceType().name(),
                decision.contentId() != null ? decision.contentId().toString() : "null",
                level.name(),
                decision.denyReason() != null ? "\"" + decision.denyReason().name() + "\"" : "null");
        try {
            auditLogService.record(
                    eventType,
                    viewerUserId,
                    null,   // targetUserId
                    null,   // teamId
                    null,   // organizationId
                    null,   // ipAddress
                    null,   // userAgent
                    null,   // sessionHash
                    metadata);
        } catch (RuntimeException e) {
            // 監査ログ失敗で本処理を止めない（AuditLogService 内部でも握り潰しているが二重防御）。
            log.warn("AuditLog 書き込み失敗: eventType={} referenceType={} contentId={}",
                    eventType, decision.referenceType(), decision.contentId(), e);
        }
    }

    /**
     * Projection から {@link ScopeKey} を生成する。scopeType / scopeId が null なら null を返す。
     */
    private ScopeKey scopeOf(P row) {
        if (row.scopeType() == null || row.scopeId() == null) {
            return null;
        }
        return new ScopeKey(row.scopeType(), row.scopeId());
    }

    /**
     * Projection の visibility を {@link StandardVisibility} に解決する。
     * 値が null や型不一致の場合は null を返す（呼び出し側で fail-closed する）。
     */
    @SuppressWarnings("unchecked")
    private StandardVisibility resolveLevelSafely(P row) {
        Object raw = row.visibility();
        if (raw == null) {
            return null;
        }
        try {
            return toStandard((V) raw);
        } catch (ClassCastException e) {
            log.warn("toStandard 用の enum キャスト失敗: referenceType={} id={} actualType={}",
                    referenceType(), row.id(), raw.getClass().getName(), e);
            return null;
        }
    }

    /** filterAccessible / decide の SQL 集計ヘルパ: 直接所属判定が必要なスコープ集合。 */
    private Set<ScopeKey> collectDirectScopes(List<P> rows) {
        Set<ScopeKey> scopes = new HashSet<>();
        for (P row : rows) {
            ScopeKey scope = scopeOf(row);
            if (scope != null) {
                scopes.add(scope);
            }
        }
        return scopes;
    }

    /**
     * filterAccessible / decide の SQL 集計ヘルパ: ORGANIZATION_WIDE 用の親解決対象スコープ集合。
     * 解決失敗 row はスキップ（fail-closed は visibleByVisibility 側で扱う）。
     */
    private Set<ScopeKey> collectOrgWideScopes(List<P> rows) {
        Set<ScopeKey> scopes = new HashSet<>();
        for (P row : rows) {
            StandardVisibility level = resolveLevelSafely(row);
            if (level != StandardVisibility.ORGANIZATION_WIDE) {
                continue;
            }
            ScopeKey scope = scopeOf(row);
            if (scope != null) {
                scopes.add(scope);
            }
        }
        return scopes;
    }

    // 静的解析向けの未使用警告抑止（Stream API を使う代替実装の参考実装として保持）。
    @SuppressWarnings("unused")
    private Set<Long> filterAccessibleStream(List<P> rows, Long viewerUserId,
                                             UserScopeRoleSnapshot snapshot) {
        return rows.stream()
                .filter(Objects::nonNull)
                .filter(row -> visibleByStatus(row, viewerUserId, snapshot))
                .filter(row -> visibleByVisibility(row, viewerUserId, snapshot))
                .map(VisibilityProjection::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }
}
