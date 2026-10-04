package com.mannschaft.app.auth.service;

import javax.sql.DataSource;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Two-connection auth operations enter this singleton before starting a transaction. */
public final class UserOperationAdmission {
    private final DataSource primaryDataSource;
    private final int configuredMaximum;

    public UserOperationAdmission(DataSource primaryDataSource, int configuredMaximum) {
        this.primaryDataSource = primaryDataSource;
        this.configuredMaximum = configuredMaximum;
    }

    public <T> T execute(Supplier<T> operation) {
        throw new UnsupportedOperationException("Admission boundary is not implemented");
    }

    public static final class Rejected extends RejectedExecutionException {
        private Rejected() { super("User operation admission rejected"); }
    }
}
