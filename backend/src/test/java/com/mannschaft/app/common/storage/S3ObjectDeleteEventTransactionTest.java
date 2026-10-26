package com.mannschaft.app.common.storage;

import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.config.SpringEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListenerMethodProcessor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.config.TransactionManagementConfigUtils;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("S3ObjectDeleteEvent のトランザクション境界")
class S3ObjectDeleteEventTransactionTest {

    @Test
    @DisplayName("commit後にだけ物理削除を配送する")
    void commit後に物理削除を配送する() {
        try (GenericApplicationContext context = newContext()) {
            DomainEventPublisher publisher = new SpringEventPublisher(context);
            StorageService storageService = context.getBean(StorageService.class);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));
            String fileKey = "attachments/committed.pdf";

            transaction.executeWithoutResult(status ->
                    publisher.publish(new S3ObjectDeleteEvent(fileKey)));

            verify(storageService).deleteAll(List.of(fileKey));
        }
    }

    @Test
    @DisplayName("rollback時は物理削除を配送しない")
    void rollback時は物理削除を配送しない() {
        try (GenericApplicationContext context = newContext()) {
            DomainEventPublisher publisher = new SpringEventPublisher(context);
            StorageService storageService = context.getBean(StorageService.class);
            TransactionTemplate transaction = new TransactionTemplate(
                    context.getBean(PlatformTransactionManager.class));

            transaction.executeWithoutResult(status -> {
                publisher.publish(new S3ObjectDeleteEvent("attachments/rolled-back.pdf"));
                status.setRollbackOnly();
            });

            verifyNoInteractions(storageService);
        }
    }

    private GenericApplicationContext newContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(StorageService.class, () -> mock(StorageService.class));
        context.registerBean(S3ObjectDeleteEventListener.class,
                () -> new S3ObjectDeleteEventListener(context.getBean(StorageService.class)));
        context.registerBean(PlatformTransactionManager.class, TestTransactionManager::new);
        context.registerBean(
                TransactionManagementConfigUtils.TRANSACTIONAL_EVENT_LISTENER_FACTORY_BEAN_NAME,
                TransactionalEventListenerFactory.class,
                TransactionalEventListenerFactory::new);
        context.registerBean(EventListenerMethodProcessor.class, EventListenerMethodProcessor::new);
        context.refresh();
        return context;
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // TransactionSynchronizationManager が管理する論理トランザクションのみを使用する。
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // 外部リソースはなく、同期コールバックの commit 境界だけを検証する。
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // 外部リソースはなく、同期コールバックの rollback 境界だけを検証する。
        }
    }
}
