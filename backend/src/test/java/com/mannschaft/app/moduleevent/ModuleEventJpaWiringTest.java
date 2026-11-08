package com.mannschaft.app.moduleevent;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.core.EventPublicationRepository;

import static org.assertj.core.api.Assertions.assertThat;

/** DBを除外した最小Boot構成へMのJPA永続化を混入させない起動回帰試練。 */
class ModuleEventJpaWiringTest {
    @Test
    void JPAを除外した最小構成はEntityManagerなしで起動する() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=test")
                .withUserConfiguration(MinimalConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EntityManager.class);
                    assertThat(context).doesNotHaveBean(EventPublicationRepository.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
    static class MinimalConfiguration { }
}
