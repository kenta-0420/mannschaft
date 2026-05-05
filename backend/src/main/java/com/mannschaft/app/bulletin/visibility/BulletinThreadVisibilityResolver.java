package com.mannschaft.app.bulletin.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F00 Phase C — {@link ReferenceType#BULLETIN_THREAD} 用
 * {@link AbstractContentVisibilityResolver} 実装（最小実装 / MEMBERS_ONLY 固定）。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
 * §4.6 / §5.1 / §7.5 / §11.6 / §12.3.1 / §15 D-13/D-14/D-16。</p>
 *
 * <p><strong>visibility 概念新設機能の最小実装</strong>（§12.3.1）:
 * 掲示板スレッドは現状 {@code visibility} カラムを持たない所属固定機能のため、
 * {@link #toStandard(StandardVisibility)} は引数値に関わらず常に
 * {@link StandardVisibility#MEMBERS_ONLY} を返す。
 * scope メンバー判定は基底クラスの MEMBERS_ONLY 経路がそのまま適用され、
 * 「所属メンバーのみ可視」が実現される。</p>
 *
 * <p>後日 {@code visibility} カラムを追加する場合は別軍議で機能仕様策定の上、
 * 本クラスを Mapper 経由の正規化に切り替える PR を分離する。</p>
 *
 * <p>SystemAdmin 高速パス（§15 D-13）／親 ORG 連鎖ガード（§11.6）／
 * 監査ログ（§11.4）／メトリクス（§9.4）の責務はすべて
 * {@link AbstractContentVisibilityResolver} に委譲される。</p>
 */
@Component
public class BulletinThreadVisibilityResolver
        extends AbstractContentVisibilityResolver<StandardVisibility, BulletinThreadVisibilityProjection> {

    private final BulletinThreadRepository bulletinThreadRepository;

    public BulletinThreadVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityMetrics visibilityMetrics,
            VisibilityTemplateEvaluator templateEvaluator,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            BulletinThreadRepository bulletinThreadRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.bulletinThreadRepository = bulletinThreadRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.BULLETIN_THREAD;
    }

    @Override
    protected List<BulletinThreadVisibilityProjection> loadProjections(Collection<Long> ids) {
        return bulletinThreadRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(StandardVisibility visibility) {
        // §12.3.1 最小実装: visibility 概念無し → 常に MEMBERS_ONLY 固定。
        return StandardVisibility.MEMBERS_ONLY;
    }
}
