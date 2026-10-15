package com.mannschaft.app.property.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkPackageVisibility;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.repository.PropertyWorkPackageRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F09.13 物件履歴台帳 — {@link ReferenceType#PROPERTY_WORK_PACKAGE} 用
 * {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §7.5。
 * 機能側設計書: {@code docs/features/F09.13_property_history.md} §5.5 / §6.1。</p>
 *
 * <p><strong>visibility 正規化ポリシー</strong>:</p>
 * <ul>
 *   <li>{@link WorkPackageVisibility#ADMINS_ONLY} → {@link StandardVisibility#ADMINS_AND_ABOVE}</li>
 *   <li>{@link WorkPackageVisibility#MEMBERS_ONLY} → {@link StandardVisibility#MEMBERS_AND_ABOVE}
 *       （W2: 内輪＝応援者除外。{@link com.mannschaft.app.property.service.PropertyWorkPackageMaskingService}
 *       が SUPPORTER を不可視と明記＝内輪確証あり。機能 enum 名・DB 値は据え置き＝④A）</li>
 *   <li>{@link WorkPackageVisibility#MEMBERS_MASKED} → {@link StandardVisibility#MEMBERS_AND_ABOVE}
 *       （MASKED は「閲覧自体は MEMBER まで可。金額のみマスク」の意味であり可視性は MEMBER 範囲）</li>
 *   <li>{@link WorkPackageVisibility#PUBLIC_MASKED} → {@link StandardVisibility#SUPPORTERS_AND_ABOVE}
 *       （PUBLIC ではあるが匿名ユーザーは想定外で SUPPORTER 以上が閲覧可。金額のみマスク）</li>
 * </ul>
 *
 * <p><strong>金額マスキングは別管理</strong>: F00 Resolver は「閲覧可/不可」のみを判定する
 * 責務に徹し、金額マスクの有無は {@link com.mannschaft.app.property.service.PropertyWorkPackageMaskingService}
 * で別途処理する（責務分離）。</p>
 *
 * <p><strong>status 正規化</strong>: 設計書 §3 の 5 値を {@link ContentStatus} に写像。
 * 論理削除（{@code deleted_at IS NOT NULL}）はエンティティ側 {@code @SQLRestriction} で
 * 既に除外されるため、Projection 段階で {@link ContentStatus#DELETED} を扱う必要は無い。</p>
 *
 * <p><strong>Projection 取得方針</strong>: 本フェーズ（1-β）では Repository に Projection 用
 * クエリを追加せず、Entity を {@code findAllById} で取得して詰め替える。1 SQL で完結し、
 * {@link AbstractContentVisibilityResolver} の N+1 防止規約と整合する。後続フェーズで
 * パフォーマンス最適化が必要になった場合のみ Repository 拡張で 1 SQL 投影に置換する。</p>
 *
 * <p>{@code @Transactional} は付与しない（{@link AbstractContentVisibilityResolver} 規約 +
 * ArchUnit ルール）。</p>
 */
@Component
public class PropertyWorkPackageVisibilityResolver
        extends AbstractContentVisibilityResolver<WorkPackageVisibility,
                PropertyWorkPackageVisibilityProjection> {

    private final PropertyWorkPackageRepository packageRepository;

    public PropertyWorkPackageVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            PropertyWorkPackageRepository packageRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.packageRepository = packageRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.PROPERTY_WORK_PACKAGE;
    }

    @Override
    protected List<PropertyWorkPackageVisibilityProjection> loadProjections(Collection<Long> ids) {
        // 1-β 段階では Entity 取得→詰め替え。@SQLRestriction("deleted_at IS NULL") で論理削除済は除外される。
        List<PropertyWorkPackageEntity> entities = packageRepository.findAllById(ids);
        return entities.stream()
                .map(e -> new PropertyWorkPackageVisibilityProjection(
                        e.getId(),
                        e.getScopeType(),
                        e.getScopeId(),
                        e.getCreatedBy(),
                        e.getStatus(),
                        e.getVisibility()))
                .toList();
    }

    @Override
    protected StandardVisibility toStandard(WorkPackageVisibility visibility) {
        if (visibility == null) {
            // fail-closed: visibility 値が null なら最も制限的な ADMIN 限定扱い
            // 挙動不変・名称正準化（W4）: ADMINS_AND_ABOVE = hasRoleOrAbove("ADMIN") = 旧 ADMINS_ONLY と同一判定。
            return StandardVisibility.ADMINS_AND_ABOVE;
        }
        return switch (visibility) {
            // 挙動不変・名称正準化（W4）: 機能enum/DB据置、出力 Std 値のみ ADMINS_AND_ABOVE（= 旧 ADMINS_ONLY 同一判定）。
            case ADMINS_ONLY -> StandardVisibility.ADMINS_AND_ABOVE;
            // W2: MEMBERS_ONLY/MEMBERS_MASKED は「応援者に見せない内輪」（MaskingService.isVisible が
            // SUPPORTER を不可視と明記＝内輪(i)確証あり）。出力先を正準ラダー MEMBERS_AND_ABOVE
            // （hasRoleOrAbove(MEMBER) / SUPPORTER・GUEST 除外）へ変更。機能 enum 名・DB 値は据え置き（④A）。
            // MASKED 系は閲覧範囲としては MEMBERS_AND_ABOVE と同じ（金額マスクは MaskingService 側で処理）。
            case MEMBERS_ONLY, MEMBERS_MASKED -> StandardVisibility.MEMBERS_AND_ABOVE;
            // PUBLIC_MASKED は SUPPORTER 以上が閲覧可（匿名閲覧は想定外）
            case PUBLIC_MASKED -> StandardVisibility.SUPPORTERS_AND_ABOVE;
        };
    }

    @Override
    protected ContentStatus toContentStatus(PropertyWorkPackageVisibilityProjection row) {
        return mapStatus(row.status());
    }

    /**
     * {@link WorkPackageStatus} → {@link ContentStatus} の写像（§7.5）。
     *
     * <ul>
     *   <li>{@code PLANNED} → {@link ContentStatus#PUBLISHED}（visibility 評価へ）</li>
     *   <li>{@code IN_PROGRESS} → {@link ContentStatus#PUBLISHED}</li>
     *   <li>{@code COMPLETED} → {@link ContentStatus#PUBLISHED}</li>
     *   <li>{@code CLOSED} → {@link ContentStatus#PUBLISHED}（クローズ後も閲覧自体は visibility に従う）</li>
     *   <li>{@code CANCELLED} → {@link ContentStatus#ARCHIVED}（SystemAdmin のみ可視）</li>
     * </ul>
     *
     * <p>本機能は {@code DRAFT}/{@code SCHEDULED} に該当するステータスを持たない（パッケージは
     * 作成段階から {@code PLANNED} で公開状態であり、内部下書きの概念がない）。
     * 論理削除は {@code @SQLRestriction} で射影段階で除外されるため、{@link ContentStatus#DELETED}
     * への写像は不要。</p>
     */
    private static ContentStatus mapStatus(WorkPackageStatus status) {
        if (status == null) {
            // fail-closed: status 不明は ARCHIVED 扱い（SystemAdmin のみ可視）
            return ContentStatus.ARCHIVED;
        }
        return switch (status) {
            case PLANNED, IN_PROGRESS, COMPLETED, CLOSED -> ContentStatus.PUBLISHED;
            case CANCELLED -> ContentStatus.ARCHIVED;
        };
    }
}
