package com.mannschaft.app.auth.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;

/** 既存PRIMARY Beanを再利用し、接続プールの新設・再設定を行わない。 */
@Configuration
public class UserOperationAdmissionConfiguration {
    @Bean
    public UserOperationAdmission userOperationAdmission(
            DataSource dataSource,
            @Qualifier("primaryDataSource") ObjectProvider<DataSource> primary,
            @Value("${app.auth.operation-guard.max-concurrent:4}") int maximum) {
        return new UserOperationAdmission(primary.getIfAvailable(() -> dataSource), maximum);
    }
}
