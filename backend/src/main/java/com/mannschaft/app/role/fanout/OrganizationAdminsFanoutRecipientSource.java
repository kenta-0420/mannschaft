package com.mannschaft.app.role.fanout;

import com.mannschaft.app.notification.fanout.FanoutPageRequest;
import com.mannschaft.app.notification.fanout.FanoutRecipient;
import com.mannschaft.app.notification.fanout.FanoutRecipientSource;
import com.mannschaft.app.notification.fanout.FanoutRecipientRowMapper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F01.2.1 §6.7: 組織 ADMIN 全員（在籍中・ACTIVE のユーザー）を受信者とする受信者ソース。
 *
 * <p>{@code scope_ref} は組織 ID の文字列。受信者は数名のため {@code shard_count=1} 固定で使う。
 * 受信者解決は role ドメインの Repository を引くため role ドメインに置く（{@link OrgFanoutRecipientSource} と同型）。</p>
 *
 * <p>受信者は数名のため単一シャード前提で、シャード分割（{@code shardCount > 1}）は行わない。</p>
 */
@Component
@RequiredArgsConstructor
public class OrganizationAdminsFanoutRecipientSource implements FanoutRecipientSource {

    /** レジストリ解決キー（{@code notification_fanout_jobs.scope_type} VARCHAR(20) に収まる19文字）。 */
    public static final String SCOPE_TYPE = "ORGANIZATION_ADMINS";

    private final UserRoleRepository userRoleRepository;

    @Override
    public String scopeType() {
        return SCOPE_TYPE;
    }

    @Override
    public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
        long scopeId = Long.parseLong(request.scopeRef());
        return FanoutRecipientRowMapper.toRecipients(
                userRoleRepository.findOrganizationAdminUserIdsKeyset(scopeId, request.cursorSubjectId(), request.limit()));
    }
}
