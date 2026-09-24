package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDeliveryStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
import com.mannschaft.app.support.perf.CountingDataSource;
import com.mannschaft.app.support.perf.SqlStatementCounter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260920-1040 試練B（殿の訂正指示を反映）: {@link ConfirmableFanoutChunkSink} の
 * 1 チャンクあたり SQL 文数を、受信者行・notifications・メール outbox の<b>表ごと</b>に実測する（AC-34）。
 *
 * <p>「例外を投げずに完了する」だけの検証では弱いという指摘を受け、{@code NotificationFanoutJobRedIT} と
 * 同じ金型（{@link CountingDataSource} で実 DataSource をラップし、データソース層で発行された
 * PreparedStatement の execute* 呼び出しを 1 文として数える）に差し替えた。JdbcTemplate 経由の
 * 多値 INSERT も Hibernate の SessionFactory を通らないため Hibernate Statistics には現れず、
 * データソース層の計測が必須（{@link SqlStatementCounter} の javadoc参照）。</p>
 */
@DisplayName("ConfirmableFanoutChunkSink 1チャンクあたりのSQL文数（表ごと実測・AC-34・試練B）")
@Import(ConfirmableFanoutChunkSinkInsertCountTest.CountingDsConfig.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ConfirmableFanoutChunkSinkInsertCountTest extends AbstractMySqlIntegrationTest {

    private static final String EMAIL_PREFIX_BASE = "cfx-sqlcount";

    private static final String RECIPIENT_INSERT = "recipient_insert";
    private static final String NOTIFICATION_INSERT = "notification_insert";
    private static final String OUTBOX_INSERT = "outbox_insert";

    @Autowired
    private ConfirmableFanoutChunkSink sink;

    @Autowired
    private ConfirmableNotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SqlStatementCounter statementCounter;

    @PersistenceContext
    private EntityManager em;

    private String emailPrefix;
    private Long notificationId;

    @AfterEach
    void cleanUp() {
        if (notificationId != null) {
            jdbc.update("DELETE FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                    notificationId);
            jdbc.update("DELETE FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND source_id = ?",
                    notificationId);
            jdbc.update("DELETE FROM email_outbox WHERE source_domain = 'CONFIRMABLE_NOTIFICATION' "
                    + "AND source_event_id = ?", String.valueOf(notificationId));
            jdbc.update("DELETE FROM confirmable_notifications WHERE id = ?", notificationId);
        }
        if (emailPrefix != null) {
            ConfirmableFanoutFixture.deleteUsers(em, emailPrefix);
        }
    }

    @Test
    @DisplayName("AC-34: 500人チャンクでも受信者行・notifications・メールoutboxのINSERT文は"
            + "それぞれ高々数文（受信者数=500に比例しない）")
    void chunkOf500EmitsBoundedInsertsPerTable() {
        emailPrefix = EMAIL_PREFIX_BASE + "-" + UUID.randomUUID();
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(em, 500, emailPrefix);

        ConfirmableNotificationEntity notification = notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(1L)
                .title("AC-34 SQL文数実測")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(ConfirmableNotificationStatus.ACTIVE)
                .deliveryStatus(ConfirmableNotificationDeliveryStatus.QUEUED)
                .totalRecipientCount(0)
                .unconfirmedCount(0)
                .build());
        notificationId = notification.getId();

        statementCounter.reset();
        sink.processChunk(UUID.randomUUID(), notificationId, userIds);

        long recipientInserts = statementCounter.count(RECIPIENT_INSERT);
        long notificationInserts = statementCounter.count(NOTIFICATION_INSERT);
        long outboxInserts = statementCounter.count(OUTBOX_INSERT);

        // 受信者数(500)に比例しない上限として「10文以内」を閾値にする（多値INSERT1文なら1、
        // 万一バッチ分割されても数文程度のはず。500件の逐次INSERTなら500文発行され必ず超過する）。
        assertThat(recipientInserts)
                .as("AC-34: confirmable_notification_recipients へのINSERT文数は受信者数(500)に比例しない")
                .isPositive()
                .isLessThan(10L);
        assertThat(notificationInserts)
                .as("AC-34: notifications へのINSERT文数は受信者数(500)に比例しない")
                .isPositive()
                .isLessThan(10L);
        assertThat(outboxInserts)
                .as("AC-34: email_outbox へのINSERT文数は受信者数(500)に比例しない")
                .isPositive()
                .isLessThan(10L);
    }

    /** {@link NotificationFanoutJobRedIT} と同じ BeanPostProcessor 方式で DataSource を計測用にラップする。 */
    @TestConfiguration
    static class CountingDsConfig {
        static final SqlStatementCounter COUNTER = new SqlStatementCounter();

        static {
            COUNTER.register(RECIPIENT_INSERT, sql -> sql.contains("insert")
                    && sql.contains("confirmable_notification_recipients"));
            COUNTER.register(NOTIFICATION_INSERT, sql -> sql.contains("insert")
                    && sql.contains("into notifications"));
            COUNTER.register(OUTBOX_INSERT, sql -> sql.contains("insert")
                    && sql.contains("email_outbox"));
        }

        @Bean
        SqlStatementCounter sqlStatementCounter() {
            return COUNTER;
        }

        @Bean
        static BeanPostProcessor confirmableFanoutCountingDataSourceWrapper() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource ds && !(bean instanceof CountingDataSource)) {
                        return new CountingDataSource(ds, COUNTER);
                    }
                    return bean;
                }
            };
        }
    }
}
