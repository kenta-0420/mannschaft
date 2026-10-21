package com.mannschaft.spike;

import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** 公式delete設定の実DB効果だけを公開Registry APIで確認する。非同期完了は別試験で検証する。 */
@SpringBootTest(classes = RegistrySpikeIT.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.modulith.events.completion-mode=delete")
class RegistryDeleteSpikeIT {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry values) { RegistrySpikeIT.database(values); }
    @Autowired RegistrySpikeIT.RestrictedRegistry registry;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    @Test
    void delete設定では完了時に実publication行が削除される() {
        jdbc.update("delete from event_publication");
        var event = new RegistrySpikeIT.SuccessEvent(1L, 2L, Instant.now());
        var target = PublicationTargetIdentifier.of("module-success");
        new TransactionTemplate(transactions).executeWithoutResult(status -> registry.store(event, Stream.of(target)));
        assertThat(jdbc.queryForObject("select count(*) from event_publication where completion_date is null", Long.class)).isEqualTo(1L);
        registry.markCompleted(event, target);
        assertThat(jdbc.queryForObject("select count(*) from event_publication", Long.class)).isZero();
    }
}
