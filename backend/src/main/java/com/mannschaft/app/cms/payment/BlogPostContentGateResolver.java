package com.mannschaft.app.cms.payment;

import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.spi.ContentGateResolver;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ブログ記事の課金ゲート用スコープResolver。
 */
@Component
@RequiredArgsConstructor
public class BlogPostContentGateResolver implements ContentGateResolver {

    private final BlogPostRepository blogPostRepository;

    @Override
    public String contentType() {
        return ContentGateType.POST;
    }

    @Override
    public Optional<ContentGateTarget> resolveForAccess(Long contentId) {
        return blogPostRepository.findById(contentId)
                .filter(post -> post.getDeletedAt() == null)
                .map(post -> new ContentGateTarget(post.getId(), post.getTeamId(), post.getOrganizationId()));
    }

    /** 未削除の記事だけを SQL 1 本で解決する（team_id / organization_id は生値のまま渡し、scope の不整合は判定側に任せる）。 */
    @Override
    public Map<Long, ContentGateTarget> resolveForAccessBatch(Collection<Long> contentIds) {
        List<Long> ids = contentIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ContentGateTarget> targets = new HashMap<>();
        for (Object[] row : blogPostRepository.findGateTargetRowsByIdIn(ids)) {
            Long id = (Long) row[0];
            targets.put(id, new ContentGateTarget(id, (Long) row[1], (Long) row[2]));
        }
        return targets;
    }

    @Override
    public boolean existsInScope(Long contentId, Long teamId, Long organizationId) {
        if (teamId != null) {
            return blogPostRepository.existsByIdAndTeamId(contentId, teamId);
        }
        return organizationId != null
                && blogPostRepository.existsByIdAndOrganizationId(contentId, organizationId);
    }
}
