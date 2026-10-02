package com.mannschaft.app.support.perf;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * 実 {@link DataSource} をラップし、発行された SQL ステートメント数を {@link SqlStatementCounter} で数える
 * 軽量プロキシ（測定専用・テスト支援）。datasource-proxy / p6spy などの外部ライブラリを追加せずに、
 * 標準 JDK の {@link Proxy}（動的プロキシ）だけで {@link Connection} / {@link Statement} 系をラップする。
 *
 * <h2>設計（AC-9 の物差し）</h2>
 * <ul>
 *   <li>{@link #getConnection()} が返す {@link Connection} を動的プロキシで包む。</li>
 *   <li>プロキシ Connection の {@code prepareStatement/prepareCall}（第 1 引数が SQL 文字列）は、
 *       返る {@link PreparedStatement} をさらにプロキシで包み、<b>prepare 時の SQL</b> を保持させる。</li>
 *   <li>{@code createStatement} が返す {@link Statement} も包み、{@code execute(sql)} の第 1 引数で SQL を得る。</li>
 *   <li>ステートメントの {@code execute*}（execute / executeUpdate / executeQuery / executeBatch /
 *       executeLargeUpdate / executeLargeBatch）呼び出しごとに {@link SqlStatementCounter#onExecute} を呼ぶ。</li>
 * </ul>
 *
 * <p>これにより、Hibernate（JPA）経由の INSERT も、将来 P1 出陣で導入される {@code JdbcTemplate} の
 * 多値 INSERT も、<b>同じ層で同じ単位</b>で数えられる。JPA 由来か JdbcTemplate 由来かに依存しない。</p>
 *
 * <p>スレッド安全性: {@code @Async} 配信スレッドや還流スレッドが別コネクションで INSERT/SELECT しても、
 * それらは本 DataSource から借りたコネクション上で実行されるため漏れなく数える（カウンタ側が thread-safe）。</p>
 */
public final class CountingDataSource implements DataSource {

    private final DataSource delegate;
    private final SqlStatementCounter counter;

    public CountingDataSource(DataSource delegate, SqlStatementCounter counter) {
        this.delegate = delegate;
        this.counter = counter;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrapConnection(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrapConnection(delegate.getConnection(username, password));
    }

    // ---- DataSource の残りは素通し ----
    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() {
        try {
            return delegate.getParentLogger();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return (T) this;
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    private Connection wrapConnection(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                CountingDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new ConnectionHandler(real, counter));
    }

    // =====================================================================
    // 動的プロキシ InvocationHandler 群
    // =====================================================================

    /** Connection をラップし、prepare/create 系の返り値を計測用ステートメントプロキシに差し替える。 */
    private static final class ConnectionHandler implements InvocationHandler {
        private final Connection real;
        private final SqlStatementCounter counter;

        ConnectionHandler(Connection real, SqlStatementCounter counter) {
            this.real = real;
            this.counter = counter;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Object result = invokeReal(method, args);
            // prepareStatement(String, ...) / prepareCall(String, ...) は第 1 引数が SQL。
            if ((name.equals("prepareStatement") || name.equals("prepareCall"))
                    && args != null && args.length > 0 && args[0] instanceof String sql) {
                Class<?> iface = (result instanceof CallableStatement)
                        ? CallableStatement.class : PreparedStatement.class;
                return wrapStatement(result, iface, sql, counter);
            }
            // createStatement(...) は SQL を持たない（execute(sql) 時に第 1 引数で得る）。
            if (name.equals("createStatement")) {
                return wrapStatement(result, Statement.class, null, counter);
            }
            return result;
        }

        private Object invokeReal(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /** Statement / PreparedStatement / CallableStatement をラップし、execute* を数える。 */
    private static final class StatementHandler implements InvocationHandler {
        private final Object real;
        private final String preparedSql; // PreparedStatement の場合は prepare 時 SQL、Statement は null
        private final SqlStatementCounter counter;

        StatementHandler(Object real, String preparedSql, SqlStatementCounter counter) {
            this.real = real;
            this.preparedSql = preparedSql;
            this.counter = counter;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.startsWith("execute")) {
                // Statement.execute(String sql) 系は第 1 引数が SQL、PreparedStatement は prepare 時 SQL。
                String sql = preparedSql;
                if (sql == null && args != null && args.length > 0 && args[0] instanceof String s) {
                    sql = s;
                }
                counter.onExecute(sql);
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static Object wrapStatement(Object real, Class<?> iface, String sql, SqlStatementCounter counter) {
        return Proxy.newProxyInstance(
                CountingDataSource.class.getClassLoader(),
                new Class<?>[] {iface},
                new StatementHandler(real, sql, counter));
    }
}
