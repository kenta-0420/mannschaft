package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import com.mannschaft.app.social.announcement.dto.AnnouncementPreviewResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

/** F02.6 preview の認可オーケストレーション。元 Service を跨ぐ transaction と GET 業務更新は持たない。 */
@Service
public class AnnouncementPreviewService {
    private final AnnouncementFeedRepository feedRepository;
    private final RoleResolver roleResolver;
    private final AnnouncementPreviewAudienceGuard audienceGuard;
    private final AnnouncementPreviewSourceService sourceService;
    private final PaymentGateService paymentGateService;
    private final Clock clock;

    public AnnouncementPreviewService(AnnouncementFeedRepository feedRepository, RoleResolver roleResolver,
            AnnouncementPreviewAudienceGuard audienceGuard, AnnouncementPreviewSourceService sourceService,
            PaymentGateService paymentGateService, @Qualifier("wallClock") Clock clock) {
        this.feedRepository = feedRepository;
        this.roleResolver = roleResolver;
        this.audienceGuard = audienceGuard;
        this.sourceService = sourceService;
        this.paymentGateService = paymentGateService;
        this.clock = clock;
    }

    public AnnouncementPreviewResponse preview(AnnouncementScopeType type, Long scopeId, Long feedId, Long viewer) {
        var feed = feedRepository.findById(feedId).orElseThrow(AnnouncementPreviewService::notFound);
        LocalDateTime now = LocalDateTime.now(clock);
        if (feed.getScopeType() != type || !Objects.equals(feed.getScopeId(), scopeId)
                || feed.getSourceDeletedAt() != null
                || (feed.getStartsAt() != null && feed.getStartsAt().isAfter(now))
                || (feed.getExpiresAt() != null && !feed.getExpiresAt().isAfter(now))) throw notFound();
        String role = roleResolver.resolveViewerRoleForPreview(viewer, type.name(), scopeId).name();
        if (!AnnouncementVisibility.isVisibleTo(feed.getVisibility(), role)) throw notFound();
        audienceGuard.assertIncluded(feed, viewer, role);
        var target = new ContentGateTarget(feedId, type == AnnouncementScopeType.TEAM ? scopeId : null,
                type == AnnouncementScopeType.ORGANIZATION ? scopeId : null);
        var gate = Objects.requireNonNull(paymentGateService.checkAccessForPreview(
                ContentGateType.ANNOUNCEMENT, feedId, viewer, target), "preview 課金結果が null です");
        if (gate.isTitleHidden()) throw notFound();
        if (feed.getSourceType() != AnnouncementSourceType.BLOG_POST
                && feed.getSourceType() != AnnouncementSourceType.BULLETIN_THREAD) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        try {
            var metadata = sourceService.metadata(feed, viewer);
            boolean feedFull = gate.isAccessible() || "ADMIN".equals(role) || "SYSTEM_ADMIN".equals(role);
            if (!feedFull || "LOCKED".equals(metadata.accessState())) {
                return AnnouncementPreviewResponse.locked(feedId, type, scopeId);
            }
            return sourceService.full(feed, viewer, metadata);
        } catch (BusinessException e) {
            // 既知の認可/不在のみ秘匿する。DB・署名・課金障害を404へ丸めない。
            String code = e.getErrorCode().getCode();
            if (code.equals("VISIBILITY_001") || code.equals("VISIBILITY_004") || code.equals("CMS_001")
                    || code.equals("BULLETIN_002") || code.equals("COMMON_002")) throw notFound();
            throw e;
        }
    }
    private static BusinessException notFound() { return new BusinessException(AnnouncementErrorCode.ANNOUNCE_001); }
}
