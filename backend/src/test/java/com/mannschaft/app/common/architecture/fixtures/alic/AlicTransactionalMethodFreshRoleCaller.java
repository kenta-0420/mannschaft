package com.mannschaft.app.common.architecture.fixtures.alic;

import com.mannschaft.app.dashboard.service.RoleResolver;
import org.springframework.transaction.annotation.Transactional;

/**
 * fixture（AC-F04 負例）: メソッド単位 {@code @Transactional} の中から
 * {@code resolveViewerRoleForPreview} を呼ぶダミー。tx のない {@link #outsideTx} は違反にしない（対照）。
 * Spring Bean にはしない。
 */
public class AlicTransactionalMethodFreshRoleCaller {

    private final RoleResolver roleResolver;

    public AlicTransactionalMethodFreshRoleCaller(RoleResolver roleResolver) {
        this.roleResolver = roleResolver;
    }

    @Transactional
    public Object insideTx(Long userId) {
        return roleResolver.resolveViewerRoleForPreview(userId, "TEAM", 1L);
    }

    public Object outsideTx(Long userId) {
        return roleResolver.resolveViewerRoleForPreview(userId, "ORGANIZATION", 2L);
    }
}
