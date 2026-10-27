package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.dashboard.ViewerRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 実 Spring Cache proxy を通し、一覧キャッシュと本文の最新認可を分離する。 */
class RoleResolverPreviewCacheTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableCaching(proxyTargetClass = true)
    static class Config {
        @Bean CacheManager cacheManager() { return new ConcurrentMapCacheManager("dashboard:viewer-role"); }
        @Bean AccessControlService accessControlService() { return mock(AccessControlService.class); }
        @Bean MembershipBatchQueryService membershipBatchQueryService() {
            return mock(MembershipBatchQueryService.class);
        }
        @Bean RoleResolver roleResolver(AccessControlService service, MembershipBatchQueryService batch) {
            return new RoleResolver(service, batch);
        }
    }

    @Test
    @DisplayName("PREVIEW-06 一覧のADMINキャッシュが残っても本文では最新PUBLIC")
    void previewは役割解除後の最新ロールを取得する() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var access = context.getBean(AccessControlService.class);
            var resolver = context.getBean(RoleResolver.class);
            when(access.getRoleName(1L, 2L, "TEAM")).thenReturn("ADMIN");
            assertThat(resolver.resolveViewerRole(1L, "TEAM", 2L)).isEqualTo(ViewerRole.ADMIN);
            when(access.getRoleName(1L, 2L, "TEAM")).thenReturn(null);
            assertThat(resolver.resolveViewerRole(1L, "TEAM", 2L)).isEqualTo(ViewerRole.ADMIN);
            assertThat(resolver.resolveViewerRoleForPreview(1L, "TEAM", 2L)).isEqualTo(ViewerRole.PUBLIC);
        }
    }

    @Test
    @DisplayName("DashboardのADMINキャッシュが残ってもInbox batchは最新PUBLIC")
    void inboxBatchBypassesDashboardCache() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var access = context.getBean(AccessControlService.class);
            var batch = context.getBean(MembershipBatchQueryService.class);
            var resolver = context.getBean(RoleResolver.class);
            var scope = new ScopeKey("TEAM", 2L);
            when(access.getRoleName(1L, 2L, "TEAM")).thenReturn("ADMIN");
            assertThat(resolver.resolveViewerRole(1L, "TEAM", 2L)).isEqualTo(ViewerRole.ADMIN);
            when(batch.snapshotForUser(1L, Set.of(scope), Set.of()))
                    .thenReturn(UserScopeRoleSnapshot.empty());
            when(access.isActiveUser(1L)).thenReturn(true);
            assertThat(resolver.resolveViewerRolesFresh(1L, Set.of(scope)).get(scope))
                    .isEqualTo(ViewerRole.PUBLIC);
            assertThat(resolver.resolveViewerRole(1L, "TEAM", 2L)).isEqualTo(ViewerRole.ADMIN);
        }
    }
}
