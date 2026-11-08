package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC0: 実MySQL接続の分離レベルを変え、小contextのrefreshで起動拒否を観測する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ErrorReportIsolationGuardR1IT {
    @Test
    void AC0_REPEATABLE_READ接続なら起動できる() throws Exception {
        assertThatCode(() -> startWithIsolation(Connection.TRANSACTION_REPEATABLE_READ)).doesNotThrowAnyException();
    }

    @Test
    void AC0_READ_COMMITTED接続なら起動を拒否する() {
        assertThatThrownBy(() -> startWithIsolation(Connection.TRANSACTION_READ_COMMITTED))
                .as("RCの実接続を受けた起動ゲートはrefreshを失敗させる")
                .isInstanceOf(org.springframework.beans.BeansException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    /** 共通singleton MySQLだけを再利用し、不要な全アプリcontextは起動しない。 */
    private static class Database extends AbstractMySqlIntegrationTest {
        static Connection openConnection() throws java.sql.SQLException {
            return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        }
    }

    private void startWithIsolation(int isolation) throws Exception {
        try (var connection = Database.openConnection();
             var context = new AnnotationConfigApplicationContext()) {
            connection.setTransactionIsolation(isolation);
            var dataSource = new SingleConnectionDataSource(connection, true);
            context.registerBean(DataSource.class, () -> dataSource);
            context.register(ErrorReportIsolationGuard.class);
            context.refresh();
        }
    }
}
