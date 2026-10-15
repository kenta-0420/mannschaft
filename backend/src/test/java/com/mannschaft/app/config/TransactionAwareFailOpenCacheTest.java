package com.mannschaft.app.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TransactionAwareFailOpenCacheTest {

    private final Cache delegate = mock(Cache.class);
    private final CacheErrorHandler errorHandler = mock(CacheErrorHandler.class);
    private final Cache cache = new TransactionAwareCacheDecorator(
            new FailOpenWriteCache(delegate, errorHandler));

    @BeforeEach
    void startTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void putはcommit前に実行されず_commit後のシリアライズ相当例外をfailOpenにする() {
        RuntimeException failure = new IllegalStateException("serialize failed");
        doThrow(failure).when(delegate).put("key", "value");

        cache.put("key", "value");

        verify(delegate, never()).put("key", "value");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(errorHandler).handleCachePutError(failure, delegate, "key", "value");
    }

    @Test
    void rollbackではevictを実行しない() {
        cache.evict("key");

        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(delegate, never()).evict("key");
    }

    @Test
    void evictIfPresentはafterCommit内でも二重遅延せず即時実行する() {
        cache.evictIfPresent("key");

        verify(delegate).evictIfPresent("key");
        org.assertj.core.api.Assertions.assertThat(
                TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }
}
