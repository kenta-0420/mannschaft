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
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        return metadata(find(id), viewerId, null);
    }

    /**
     * {@link #getPreviewMetadata} の委任版。記事の所有スコープでの閲覧者の役割を委任役割（"MEMBER" / "SUPPORTER"）に差し替えて F00 を評価する
     * （組織告知の宛先チームのメンバーへの本文プレビュー委任。ALIC-1 §2.3）。課金・状態・所有スコープ照合は通常版と共通。
     * 呼び出しは {@code AnnouncementPreviewSourceService} に限る。
     */
    public PreviewMetadata getPreviewMetadataDelegated(Long id, Long viewerId, String delegatedRole) {
        return metadata(find(id), viewerId, requireDelegatedRole(delegatedRole));
    }

    /**
     * 組織告知の一覧用の一括版。{@link #getPreviewMetadata} が 404 にならない組織ブログの ID だけを返す
     * （strict 課金で HIDDEN は管理者・システム管理者でも除外し、F00 は strict 課金結果を使って一括評価する。
     * 課金・F00 とも SQL 本数は記事の数に比例しない）。
     */
    public Set<Long> filterPreviewReadable(Collection<Long> ids, Long viewerId, Long organizationId) {
        return filterReadable(ids, viewerId, organizationId, null);
    }

    /** {@link #filterPreviewReadable} の委任版（{@link #getPreviewMetadataDelegated} の一括版）。 */
    public Set<Long> filterPreviewReadableDelegated(Collection<Long> ids, Long viewerId, Long organizationId,
                                                    String delegatedRole) {
        return filterReadable(ids, viewerId, organizationId, requireDelegatedRole(delegatedRole));
    }

    private Set<Long> filterReadable(Collection<Long> ids, Long viewerId, Long organizationId, String delegatedRole) {
        if (ids == null || ids.isEmpty()) return Set.of();
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, ContentGateTarget> targets = new LinkedHashMap<>();
        distinct.forEach(id -> targets.put(id, new ContentGateTarget(id, null, organizationId)));
        Map<Long, GateCheckResponse> gates = paymentGateService.checkAccessBatchForPreview(
                ContentGateType.POST, distinct, viewerId, targets);
        if (gates == null) return Set.of();
        // 課金 HIDDEN（タイトルも隠す）は管理者でも除外する（プレビューは管理者バイパスより先に 404）。結果に無い記事も除外。
        List<Long> titleVisible = distinct.stream().filter(id -> {
            GateCheckResponse gate = gates.get(id);
            return gate != null && !gate.isTitleHidden();
        }).toList();
        if (titleVisible.isEmpty()) return Set.of();
        return delegatedRole == null
                ? visibilityResolver.filterAccessibleForPreview(titleVisible, viewerId, gates)
                : visibilityResolver.filterAccessibleForPreviewDelegated(titleVisible, viewerId, gates, delegatedRole);
    }

    /** FULL の時だけ本文を取得し、認可済み実 scope に束縛して署名する。 */
    public BlogPostResponse getPreviewById(Long id, Long viewerId, String scopeType, Long scopeId) {
        return previewById(id, viewerId, scopeType, scopeId, null);
    }

    /** {@link #getPreviewById} の委任版（{@link #getPreviewMetadataDelegated} と同じ委任役割で評価する）。 */
    public BlogPostResponse getPreviewByIdDelegated(Long id, Long viewerId, String scopeType, Long scopeId,
                                                    String delegatedRole) {
        return previewById(id, viewerId, scopeType, scopeId, requireDelegatedRole(delegatedRole));
    }

    private BlogPostResponse previewById(Long id, Long viewerId, String scopeType, Long scopeId,
                                         String delegatedRole) {
        BlogPostEntity entity = find(id);
        PreviewMetadata metadata = metadata(entity, viewerId, delegatedRole);
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

    private static String requireDelegatedRole(String delegatedRole) {
        if (delegatedRole == null || delegatedRole.isBlank()) {
            throw new IllegalArgumentException("delegatedRole must not be blank");
        }
        return delegatedRole;
    }

    private PreviewMetadata metadata(BlogPostEntity entity, Long viewerId, String delegatedRole) {
        if ((entity.getTeamId() == null) == (entity.getOrganizationId() == null) || entity.getUserId() != null) throw notFound();
        var gate = Objects.requireNonNull(paymentGateService.checkAccessForPreview(ContentGateType.POST, entity.getId(),
                viewerId, new ContentGateTarget(entity.getId(), entity.getTeamId(), entity.getOrganizationId())),
                "preview 課金結果が null です");
        if (gate.isTitleHidden()) throw notFound();
        if (delegatedRole == null) {
            visibilityResolver.assertCanViewForPreview(entity.getId(), viewerId, gate);
        } else {
            visibilityResolver.assertCanViewForPreviewDelegated(entity.getId(), viewerId, gate, delegatedRole);
        }
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
