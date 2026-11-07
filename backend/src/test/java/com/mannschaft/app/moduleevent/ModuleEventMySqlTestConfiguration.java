package com.mannschaft.app.moduleevent;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 本番の組立に既存の購読検体だけを足し、M の実 MySQL 試練で同じ構成を使う。 */
@TestConfiguration(proxyBeanMethods = false)
public class ModuleEventMySqlTestConfiguration {
    @Bean
    ModuleEventRegistryIT.Listeners moduleRegistryTestListeners() {
        return new ModuleEventRegistryIT.Listeners();
    }

    @Bean
    ModuleEventReplayTestFixture.Listeners moduleReplayTestListeners() {
        return new ModuleEventReplayTestFixture.Listeners();
    }
}
