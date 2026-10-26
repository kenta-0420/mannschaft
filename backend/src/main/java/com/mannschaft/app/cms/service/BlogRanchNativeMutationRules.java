package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EnumInputParser;
import java.time.LocalDateTime;

/** 既存公開処理と本人保護付き公開処理で同じ業務規則を使用する。 */
final class BlogRanchNativeMutationRules {
    private BlogRanchNativeMutationRules() { }

    static PostStatus changeStatus(BlogPostEntity entity, PublishRequest request, LocalDateTime baseTime) {
        PostStatus status = EnumInputParser.parse(PostStatus.class, request.getStatus(), "status");
        if (status == PostStatus.REJECTED
                && (request.getRejectionReason() == null || request.getRejectionReason().isBlank())) {
            throw new BusinessException(CmsErrorCode.REJECTION_REASON_REQUIRED);
        }
        switch (status) {
            case PUBLISHED -> entity.publish(request.getPublishedAt(), baseTime);
            case REJECTED -> entity.reject(request.getRejectionReason());
            default -> entity.changeStatus(status, baseTime);
        }
        return status;
    }

    static void selfReview(BlogPostEntity entity, SelfReviewRequest request, LocalDateTime baseTime) {
        if (entity.getStatus() != PostStatus.PENDING_SELF_REVIEW) {
            throw new BusinessException(CmsErrorCode.INVALID_STATUS_TRANSITION);
        }
        switch (request.getAction().toUpperCase()) {
            case "PUBLISH" -> entity.publish(entity.getPublishedAt(), baseTime);
            case "DRAFT" -> entity.changeStatus(PostStatus.DRAFT, baseTime);
            case "DELETE" -> entity.softDelete();
            default -> throw new BusinessException(CmsErrorCode.INVALID_STATUS_TRANSITION);
        }
    }
}
