package com.mannschaft.app.filesharing.payment;

import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.spi.ContentGateResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 共有ファイルの課金ゲート用スコープResolver。
 */
@Component
@RequiredArgsConstructor
public class SharedFileContentGateResolver implements ContentGateResolver {

    private final SharedFileRepository sharedFileRepository;

    @Override
    public String contentType() {
        return ContentGateType.FILE;
    }

    @Override
    public boolean existsInScope(Long contentId, Long teamId, Long organizationId) {
        if (teamId != null) {
            return sharedFileRepository.existsInTeamScope(contentId, teamId);
        }
        return organizationId != null
                && sharedFileRepository.existsInOrganizationScope(contentId, organizationId);
    }
}
