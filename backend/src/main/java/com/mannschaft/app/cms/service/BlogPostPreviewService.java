package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.visibility.BlogPostVisibilityResolver;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** preview 専用の非トランザクション読取。F00/課金/署名を記事の更新 TX に含めない。 */
@Service
@RequiredArgsConstructor
public class BlogPostPreviewService {
    private final BlogPostRepository postRepository;
    private final CmsMapper cmsMapper;
    private final BlogPostVisibilityResolver visibilityResolver;
    private final PaymentGateService paymentGateService;
    private final BlogBodyMediaResolver bodyMediaResolver;
    private final AccessControlService accessControlService;

    /** 本文や署名を作らず最新実在・実 scope・F00・strict 課金を確認する。 */
    public PreviewMetadata getPreviewMetadata(Long id, Long viewerId) {
        return metadata(find(id), viewerId);
    }

    /** FULL の時だけ本文を取得し、認可済み実 scope に束縛して署名する。 */
    public BlogPostResponse getPreviewById(Long id, Long viewerId, String scopeType, Long scopeId) {
        BlogPostEntity entity = find(id);
        PreviewMetadata metadata = metadata(entity, viewerId);
        if (!metadata.scopeType().equals(scopeType) || !Objects.equals(metadata.scopeId(), scopeId)) throw notFound();
        BlogPostResponse dto = cmsMapper.toBlogPostResponse(entity).withAccessState(metadata.accessState());
        var content = dto.getContent();
        if ("LOCKED".equals(metadata.accessState())) {
            return content == null ? dto : dto.toBuilder().content(new BlogPostResponse.BlogPostContentDto(
                    content.title(), content.slug(), null, null, null)).build();
        }
        if (content == null || content.body() == null) return dto;
        String body = bodyMediaResolver.resolveBodyForPreview(content.body(), StorageScopeType.valueOf(scopeType),
                scopeId, id);
        if (body == null || body.equals(content.body())) return dto;
        return dto.toBuilder().content(new BlogPostResponse.BlogPostContentDto(
                content.title(), content.slug(), body, content.excerpt(), content.coverImageUrl())).build();
    }

    private PreviewMetadata metadata(BlogPostEntity entity, Long viewerId) {
        if ((entity.getTeamId() == null) == (entity.getOrganizationId() == null) || entity.getUserId() != null) throw notFound();
        var gate = Objects.requireNonNull(paymentGateService.checkAccessForPreview(ContentGateType.POST, entity.getId(),
                viewerId, new ContentGateTarget(entity.getId(), entity.getTeamId(), entity.getOrganizationId())),
                "preview 課金結果が null です");
        if (gate.isTitleHidden()) throw notFound();
        visibilityResolver.assertCanViewForPreview(entity.getId(), viewerId, gate);
        boolean full = gate.isAccessible() || accessControlService.isSystemAdmin(viewerId);
        return new PreviewMetadata(entity.getTeamId() != null ? "TEAM" : "ORGANIZATION",
                entity.getTeamId() != null ? entity.getTeamId() : entity.getOrganizationId(), entity.getSlug(),
                full ? "FULL" : "LOCKED");
    }

    private BlogPostEntity find(Long id) {
        return postRepository.findById(id).orElseThrow(BlogPostPreviewService::notFound);
    }
    private static BusinessException notFound() {
        return new BusinessException(CmsErrorCode.POST_NOT_FOUND);
    }
    public record PreviewMetadata(String scopeType, Long scopeId, String slug, String accessState) {}
}
