package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.service.BlogPostPreviewService;
import com.mannschaft.app.cms.visibility.BlogPostVisibilityResolver;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 外側 test TX のない Spring 呼出しで、最新認可が読取 leaf の TX の外にあることを固定する。 */
class PreviewReadTransactionBoundaryTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({BlogPostPreviewService.class, BulletinReadFacade.class, TxControl.class})
    static class Config {}

    static class TxControl {
        @Transactional public void check() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); }
    }
    static class CountingTxManager extends AbstractPlatformTransactionManager {
        int begins;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { begins++; }
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }

    @Test
    void 最新CMSと掲示板F00はactualTransactionの外で評価する() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Config.class);
            var tx = new CountingTxManager();
            context.registerBean("transactionManager", CountingTxManager.class, () -> tx);
            var repo = dependency(context, BlogPostRepository.class);
            dependency(context, CmsMapper.class);
            var blogVisibility = dependency(context, BlogPostVisibilityResolver.class);
            var gate = dependency(context, PaymentGateService.class);
            dependency(context, BlogBodyMediaResolver.class);
            dependency(context, AccessControlService.class);
            var thread = dependency(context, BulletinThreadService.class);
            dependency(context, BulletinAttachmentService.class);
            var accessGuard = dependency(context, BulletinAccessGuard.class);
            var visibility = dependency(context, ContentVisibilityChecker.class);
            dependency(context, TeamService.class);
            dependency(context, OrganizationService.class);
            context.refresh();
            context.getBean(TxControl.class).check(); // annotation interceptor の陽性対照
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(TxControl.class))).isTrue();
            assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(BlogPostPreviewService.class))).isFalse();
            assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(BulletinReadFacade.class))).isFalse();
            when(repo.findById(1L)).thenReturn(Optional.of(BlogPostEntity.builder().id(1L).teamId(10L).slug("preview").build()));
            when(gate.checkAccessForPreview(anyString(), anyLong(), anyLong(), any()))
                    .thenReturn(new GateCheckResponse(true, false, List.of()));
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return null;
            }).when(blogVisibility).assertCanViewForPreview(anyLong(), anyLong(), any());
            when(thread.getReadMetadata(2L)).thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return new BulletinThreadService.PreviewMetadata(2L, "TEAM", 10L);
            });
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return null;
            }).when(accessGuard).checkMembership(anyLong(), any(), anyLong());
            doAnswer(invocation -> {
                // 既存 common Checker 自身の readOnly TX は外側 Facade TX と区別する。
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return null;
            }).when(visibility).assertCanView(any(), anyLong(), anyLong());
            context.getBean(BlogPostPreviewService.class).getPreviewMetadata(1L, 3L);
            context.getBean(BulletinReadFacade.class).getPreviewMetadata(2L, 3L);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    private static <T> T dependency(AnnotationConfigApplicationContext context, Class<T> type) {
        T instance = mock(type);
        context.registerBean(type, () -> instance);
        return instance;
    }
}
