package com.mannschaft.app.auth.service;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 二接続を使う本人操作を、authトランザクション開始前に制限する。 */
public final class UserOperationAdmission {
    private final DataSource primaryDataSource;
    private final int configuredMaximum;
    private final AtomicReference<Semaphore> permits = new AtomicReference<>();
    private final ThreadLocal<Boolean> entered = new ThreadLocal<>();

    public UserOperationAdmission(DataSource primaryDataSource, int configuredMaximum) {
        this.primaryDataSource = primaryDataSource;
        this.configuredMaximum = configuredMaximum;
    }

    public <T> T execute(Supplier<T> operation) {
        if (operation == null) throw new IllegalArgumentException("本人操作が指定されていません");
        if (Boolean.TRUE.equals(entered.get())
                || TransactionSynchronizationManager.isActualTransactionActive()) throw new Rejected();
        Semaphore available = capacity();
        if (!available.tryAcquire()) throw new Rejected();
        entered.set(true);
        try {
            return operation.get();
        } finally {
            // Runnerプロキシがcommitまたはrollbackを終えて戻った後に解放する。
            entered.remove();
            available.release();
        }
    }

    private Semaphore capacity() {
        Semaphore existing = permits.get();
        if (existing != null) return existing;
        if (configuredMaximum <= 0 || !(primaryDataSource instanceof HikariDataSource hikari)
                || hikari.isClosed()) throw new Rejected();
        int maximum = hikari.getMaximumPoolSize();
        if (maximum < 2) throw new Rejected();
        // P=2/3では予備接続を予約できない。他経路の既存timeoutは変更しない。
        int limit = Math.min(configuredMaximum, Math.max(1, (maximum - 2) / 2));
        Semaphore candidate = new Semaphore(limit);
        return permits.compareAndSet(null, candidate) ? candidate : permits.get();
    }

    public static final class Rejected extends RejectedExecutionException {
        private Rejected() { super("本人操作の受付を拒否しました"); }
    }
}
