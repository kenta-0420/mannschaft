package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** コンテンツの実存・可視性・課金を一つの認可境界で合成する。 */
@Service
@RequiredArgsConstructor
public class ContentGateAccessService {
    private final ContentGateResolverRegistry resolverRegistry;
    private final ContentVisibilityChecker visibilityChecker;
    private final AccessControlService accessControlService;
    private final PaymentGateService paymentGateService;

    public GateCheckResponse check(String contentType, Long contentId, Long viewerUserId) {
        if (contentType == null || contentId == null
                || (!ContentGateType.POST.equals(contentType)
                    && !ContentGateType.ANNOUNCEMENT.equals(contentType))) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND);
        }
        ContentGateTarget target;
        try {
            target = resolverRegistry.resolveForAccess(contentType, contentId)
                    .filter(ContentGateTarget::hasExactlyOneScope)
                    .orElseThrow(() -> new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND, e);
        }
        ReferenceType referenceType = ContentGateType.POST.equals(contentType)
                ? ReferenceType.BLOG_POST : ReferenceType.ANNOUNCEMENT_FEED;
        final boolean visible;
        try {
            visible = visibilityChecker.canView(referenceType, contentId, viewerUserId);
        } catch (RuntimeException e) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND, e);
        }
        if (!visible) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND);
        }
        if (isStrictAdmin(viewerUserId, target)) {
            return new GateCheckResponse(true, false, java.util.List.of());
        }
        final GateCheckResponse response;
        try {
            response = paymentGateService.checkAccess(contentType, contentId, viewerUserId, target);
        } catch (RuntimeException e) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND, e);
        }
        if (response == null) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND);
        }
        if (response.isTitleHidden()) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND);
        }
        return response;
    }

    private boolean isStrictAdmin(Long userId, ContentGateTarget target) {
        if (userId == null) {
            return false;
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        Long scopeId = target.teamId() != null ? target.teamId() : target.organizationId();
        String scopeType = target.teamId() != null ? "TEAM" : "ORGANIZATION";
        return "ADMIN".equals(accessControlService.getRoleName(userId, scopeId, scopeType));
    }
}
