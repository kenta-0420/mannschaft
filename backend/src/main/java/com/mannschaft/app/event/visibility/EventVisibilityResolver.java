package com.mannschaft.app.event.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.mapping.EventStatusMapper;
import com.mannschaft.app.common.visibility.mapping.EventVisibilityMapper;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F00 Phase B — {@link ReferenceType#EVENT} 用 {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.5 / §4.6 / §6.2 / §15 D-9。
 *
 * <p>機能 enum {@link EventVisibility} は CUSTOM / FOLLOWERS_ONLY / CUSTOM_TEMPLATE を持たず、
 * {@code PUBLIC / MEMBERS_ONLY / SUPPORTERS_AND_ABOVE} の 3 値を {@link EventVisibilityMapper}
 * 経由で {@link StandardVisibility} に正規化するのみ。status 軸は {@link EventStatusMapper}
 * で {@link ContentStatus} に正規化される。
 *
 * <p>本クラスは抽象基底のテンプレートメソッドを差し替えるだけで完結し、
 * {@code canView} / {@code filterAccessible} / {@code decide} の各パイプラインや
 * SystemAdmin 高速パス（§15 D-13）／親 ORG 連鎖ガード（§11.6）／監査ログ（§11.4）／
 * メトリクス（§9.4）の責務は {@link AbstractContentVisibilityResolver} に委譲される。
 */
@Component
public class EventVisibilityResolver
        extends AbstractContentVisibilityResolver<EventVisibility, EventVisibilityProjection> {

    private final EventRepository eventRepository;

    public EventVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityMetrics visibilityMetrics,
            com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator templateEvaluator,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            EventRepository eventRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.eventRepository = eventRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.EVENT;
    }

    @Override
    protected List<EventVisibilityProjection> loadProjections(Collection<Long> ids) {
        return eventRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(EventVisibility visibility) {
        return EventVisibilityMapper.toStandard(visibility);
    }

    @Override
    protected ContentStatus toContentStatus(EventVisibilityProjection row) {
        return EventStatusMapper.toStandard(row.status());
    }
}
