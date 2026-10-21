package com.mannschaft.app.errorreport.service;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

/**
 * エラー集約が必要とする実プール接続の分離レベルを確認する起動ゲート。
 * 試練先行の最小骨格。READ COMMITTED の拒否は後続の出陣で実装する。
 */
@Component
public class ErrorReportIsolationGuard implements InitializingBean {
    private final DataSource dataSource;

    public ErrorReportIsolationGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws Exception { }
}
