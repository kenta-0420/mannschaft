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

/**
 * F02.6 preview の認可オーケストレーション。元 Service を跨ぐ transaction と GET 業務更新は持たない。
 *
 * <p>組織告知は、閲覧者の<b>直属資格 Qd</b>（組織での役割）と<b>宛先資格 Qa</b>（宛先チームでの役割）のどちらかで読める
 * （{@link OrgAnnouncementAudienceAccess}。ALIC-1 §2.1）。元コンテンツはまず直属経路で読み、直属経路が既知の拒否で、
 * かつ Qa でも読めるなら委任経路で読み直す。委任経路の結果は sourceUrl=null・添付なし。</p>
 */
@Service
public class AnnouncementPreviewService {
    private final AnnouncementFeedRepository feedRepository;
    private final RoleResolver roleResolver;
    private final OrgAnnouncementAudienceAccess audienceAccess;
    private final AnnouncementPreviewSourceService sourceService;
    private final PaymentGateService paymentGateService;
    private final Clock clock;

    public AnnouncementPreviewService(AnnouncementFeedRepository feedRepository, RoleResolver roleResolver,
            OrgAnnouncementAudienceAccess audienceAccess, AnnouncementPreviewSourceService sourceService,
            PaymentGateService paymentGateService, @Qualifier("wallClock") Clock clock) {
        this.feedRepository = feedRepository;
        this.roleResolver = roleResolver;
        this.audienceAccess = audienceAccess;
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
        // 告知の可否（Qd / Qa）。拒否は理由によらず同一の 404 に畳む（存在オラクルを作らない）。
        boolean directAllowed;
        boolean delegatedAllowed = false;
        String delegatedRole = null;
        String role;
        if (type == AnnouncementScopeType.ORGANIZATION) {
            var access = audienceAccess.evaluate(feed, viewer);
            if (!access.allowed()) throw notFound();
            directAllowed = access.directAllowed();
            delegatedAllowed = access.delegatedAllowed();
            delegatedRole = access.delegatedRole();
            role = access.directRole().name();
        } else {
            role = roleResolver.resolveViewerRoleForPreview(viewer, type.name(), scopeId).name();
            if (!AnnouncementVisibility.isVisibleTo(feed.getVisibility(), role)) throw notFound();
            directAllowed = true;
        }
        var target = new ContentGateTarget(feedId, type == AnnouncementScopeType.TEAM ? scopeId : null,
                type == AnnouncementScopeType.ORGANIZATION ? scopeId : null);
        var gate = Objects.requireNonNull(paymentGateService.checkAccessForPreview(
                ContentGateType.ANNOUNCEMENT, feedId, viewer, target), "preview 課金結果が null です");
        if (gate.isTitleHidden()) throw notFound();
        if (feed.getSourceType() != AnnouncementSourceType.BLOG_POST
                && feed.getSourceType() != AnnouncementSourceType.BULLETIN_THREAD) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        // 告知本文の FULL は課金が満たされているか、組織（スコープ自身）での ADMIN / SYSTEM_ADMIN のときだけ。
        // 宛先チームの ADMIN は組織の課金を素通ししない（M2）。
        boolean feedFull = gate.isAccessible() || "ADMIN".equals(role) || "SYSTEM_ADMIN".equals(role);
        if (directAllowed) {
            try {
                return load(feed, viewer, feedFull, null);
            } catch (BusinessException e) {
                // 既知の認可/不在のみ秘匿する。DB・署名・課金障害を404へ丸めない。
                if (!isKnownDenial(e)) throw e;
                if (!delegatedAllowed) throw notFound();
            }
        }
        try {
            return load(feed, viewer, feedFull, delegatedRole);
        } catch (BusinessException e) {
            if (isKnownDenial(e)) throw notFound();
            throw e;
        }
    }

    /** 元コンテンツを読んでレスポンスを組み立てる。delegatedRole が null なら直属経路、非 null なら委任経路。 */
    private AnnouncementPreviewResponse load(AnnouncementFeedEntity feed, Long viewer, boolean feedFull,
                                             String delegatedRole) {
        if (delegatedRole != null) {
            if (!feedFull) {
                // 告知が課金 LOCKED でも、元コンテンツの最新の実在・認可・所有スコープの拒否は 404 のままにする（存在秘匿）
                sourceService.metadataDelegated(feed, viewer, delegatedRole);
                return AnnouncementPreviewResponse.locked(feed.getId(), feed.getScopeType(), feed.getScopeId());
            }
            // 委任経路の本文取得は、実在・所有スコープ・F00（委任役割）・strict 課金を本文取得の中で一括して評価する
            // （元ブログの課金 LOCKED なら LOCKED を返す）。metadata を別に評価して SQL を二重に発行しない。
            return sourceService.fullDelegated(feed, viewer, delegatedRole);
        }
        var metadata = sourceService.metadata(feed, viewer);
        if (!feedFull || "LOCKED".equals(metadata.accessState())) {
            return AnnouncementPreviewResponse.locked(feed.getId(), feed.getScopeType(), feed.getScopeId());
        }
        return sourceService.full(feed, viewer, metadata);
    }

    private static boolean isKnownDenial(BusinessException e) {
        String code = e.getErrorCode().getCode();
        return code.equals("VISIBILITY_001") || code.equals("VISIBILITY_004") || code.equals("CMS_001")
                || code.equals("BULLETIN_002") || code.equals("COMMON_002");
    }

    private static BusinessException notFound() { return new BusinessException(AnnouncementErrorCode.ANNOUNCE_001); }
}
