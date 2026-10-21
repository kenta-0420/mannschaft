package com.mannschaft.app.webhook;

import com.mannschaft.app.webhook.service.ApiKeyService;
import com.mannschaft.app.webhook.service.WebhookEndpointService;
import com.mannschaft.app.webhook.service.IncomingWebhookService;
import com.mannschaft.app.webhook.service.WebhookDeliveryService;
import com.mannschaft.app.webhook.repository.ApiKeyRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

/** 単独モジュール起動の可否を測る検体。既存SpringBootTest基底は継承しない。 */
@ApplicationModuleTest(verifyAutomatically = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebhookSpikeIT {
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("webhook_spike").withUsername("test").withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"));
    static { MYSQL.start(); }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", MYSQL::getJdbcUrl);
        values.add("spring.datasource.username", MYSQL::getUsername);
        values.add("spring.datasource.password", MYSQL::getPassword);
        values.add("logging.level.org.springframework.test.context.cache", () -> "DEBUG");
    }
    @MockitoBean org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired ApplicationContext context;

    @Test
    void webhookの実ServiceとRepositoryを単独ロードする() {
        assertThat(context.getBeansOfType(ApiKeyService.class)).hasSize(1);
        assertThat(context.getBeansOfType(WebhookEndpointService.class)).hasSize(1);
        assertThat(context.getBeansOfType(IncomingWebhookService.class)).hasSize(1);
        assertThat(context.getBeansOfType(WebhookDeliveryService.class)).hasSize(1);
        assertThat(context.getBeansOfType(ApiKeyRepository.class)).hasSize(1);
    }
}