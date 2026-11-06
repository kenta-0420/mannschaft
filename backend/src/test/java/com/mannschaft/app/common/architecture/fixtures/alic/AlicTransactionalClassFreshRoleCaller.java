package com.mannschaft.app.common.architecture.fixtures.alic;

import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.dashboard.service.RoleResolver;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * fixture（CMP-261011-0035 AC-F04 負例）: クラス全体 {@code @Transactional} の中から
 * キャッシュなし役割解決を呼ぶダミー。規則は違反として検出しなければならない。Spring Bean にはしない。
 */
@Transactional(readOnly = true)
public class AlicTransactionalClassFreshRoleCaller {

    private final RoleResolver roleResolver;

    public AlicTransactionalClassFreshRoleCaller(RoleResolver roleResolver) {
        this.roleResolver = roleResolver;
    }

    public Object resolve(Long userId) {
        return roleResolver.resolveViewerRolesFresh(userId, Set.of(new ScopeKey("TEAM", 1L)));
    }
}
