package com.mannschaft.app.cms.payment;

import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.spi.ContentGateResolver;
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
    public boolean existsInScope(Long contentId, Long teamId, Long organizationId) {
        if (teamId != null) {
            return blogPostRepository.existsByIdAndTeamId(contentId, teamId);
        }
        return organizationId != null
                && blogPostRepository.existsByIdAndOrganizationId(contentId, organizationId);
    }
}
