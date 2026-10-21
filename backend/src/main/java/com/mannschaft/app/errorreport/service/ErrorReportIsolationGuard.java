package com.mannschaft.app.errorreport.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import javax.sql.DataSource;
import java.sql.Connection;

/**
 * エラー集約が必要とする実プール接続の分離レベルを確認する起動ゲート。
 * 設定値ではなく、実際に借りた pooled session を検査する。
 */
@Component
@Profile("!openapi-gen")
public class ErrorReportIsolationGuard implements InitializingBean {
    private final DataSource dataSource;

    public ErrorReportIsolationGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
                throw new IllegalStateException("エラー集約には REPEATABLE READ の実プール接続が必要です");
            }
        }
    }
}
