package com.mannschaft.app.auth.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;

/** Reuses the existing PRIMARY bean; does not create or reconfigure a pool. */
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
