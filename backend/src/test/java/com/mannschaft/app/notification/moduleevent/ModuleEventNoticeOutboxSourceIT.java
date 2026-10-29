package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.mannschaft.app.moduleevent.notice.ModuleEventNoticeOutboxEntity;
import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;
import com.mannschaft.app.notification.fanout.FanoutMessageKind;
import com.mannschaft.app.notification.fanout.FanoutMessageRenderer;
import com.mannschaft.app.notification.outbox.NotificationOutboxMessage;
import com.mannschaft.app.notification.outbox.NotificationOutboxPayloadCodec;
import com.mannschaft.app.notification.outbox.NotificationOutboxIngestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 公開 M API・既存 codec/ingest・実 MySQL を通す source の先行試練。retention は未完。 */
@ModuleEventMySqlTest
@Import(ModuleEventNoticeNotificationFixtures.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventNoticeOutboxSourceIT extends ModuleEventNoticeNotificationTestSupport {
    @Autowired ModuleEventNoticeOutboxSource source;
    @Autowired NotificationOutboxPayloadCodec codec;
    @Autowired NotificationOutboxIngestService ingest;
    @Autowired FanoutMessageRenderer renderer;

    @ParameterizedTest
    @ValueSource(strings = {"DELAYED", "QUARANTINED", "RECOVERED"})
    void 三phaseを正規aliasと固定文面のFANOUTへ変換する(String phase) {
        ModuleEventNoticeOutboxEntity row = notice(phase);
        NotificationOutboxMessage message = one();
        FanoutEnqueueCommand command = codec.decode(message.payloadVersion(), message.payloadJson()).fanout();
        assertThat(message.id()).isEqualTo(row.getId());
        assertThat(message.claimToken()).isNotNull();
        assertThat(message.kind()).isEqualTo("FANOUT");
        assertThat(command.scopeType()).isEqualTo("MODULE_EVENT_NOTICE");
        assertThat(command.notificationType()).isEqualTo("MODULE_EVENT_NOTICE");
        assertThat(command.sourceType()).isEqualTo("MODULE_EVENT_NOTICE");
        assertThat(command.scopeRef()).isEqualTo(row.getNoticeRef().toString());
        assertThat(command.sourceId()).isEqualTo(row.getNoticeRef());
        assertThat(command.idempotencyKey()).isEqualTo(row.getNoticeKey());
        assertThat(command.shardMode()).isEqualTo(FanoutEnqueueCommand.ShardMode.FIXED_SINGLE);
        assertThat(command.messageKind()).isEqualTo(FanoutMessageKind.valueOf("MODULE_EVENT_" + phase));
        assertThat(command.messageArgs()).isEmpty();
        assertThat(command.actorId()).isNull();
        assertThat(command.actionUrl()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 999})
    void 未知版を解釈せず元の版をrelayへ渡して失敗回数を増やさず先送りする(int version) {
        ModuleEventNoticeOutboxEntity row = notice("FUTURE", "PENDING", version, "FUTURE");
        NotificationOutboxMessage message = one();
        assertThat(message.payloadVersion()).isEqualTo(version);
        assertThat(codec.supports(message.payloadVersion())).isFalse();
        assertThat(source.deferUnsupportedVersion(message.id(), message.claimToken(), FUTURE.plusSeconds(60), FUTURE))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM module_event_notice_outbox WHERE notice_ref = ?",
                Integer.class, row.getNoticeRef())).isZero();
    }

    @Test
    void 既知版破損は固定不正payloadで有界失敗へ渡し後続正常claimも返す() {
        ModuleEventNoticeOutboxEntity broken = notice("DELAYED", "PENDING", 1, "FUTURE");
        ModuleEventNoticeOutboxEntity normal = notice("DELAYED");
        List<NotificationOutboxMessage> messages = source.claim(100, FUTURE);
        assertThat(messages).hasSize(2);
        NotificationOutboxMessage invalid = messages.stream().filter(m -> m.id().equals(broken.getId())).findFirst().orElseThrow();
        assertThat(invalid.payloadVersion()).isEqualTo(1);
        assertThatThrownBy(() -> codec.decode(invalid.payloadVersion(), invalid.payloadJson()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(messages).anyMatch(m -> m.id().equals(normal.getId()));
    }

    @Test
    void ingest後印付け前のcrash再受付は同じjobへ収束する() {
        ModuleEventNoticeOutboxEntity row = notice("DELAYED");
        NotificationOutboxMessage first = one();
        ingest.ingest(codec.decode(first.payloadVersion(), first.payloadJson()));
        assertThat(source.recoverStuck(FUTURE.plusSeconds(1), FUTURE.plusSeconds(2))).isEqualTo(1);
        NotificationOutboxMessage again = source.claim(100, FUTURE.plusSeconds(2)).getFirst();
        ingest.ingest(codec.decode(again.payloadVersion(), again.payloadJson()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_fanout_jobs WHERE source_type = ? AND source_id = ?",
                Long.class, "MODULE_EVENT_NOTICE", row.getNoticeRef())).isEqualTo(1);
        jdbc.query("SELECT id FROM notification_fanout_jobs WHERE source_type = ? AND source_id = ?",
                (rs, n) -> { byte[] b = rs.getBytes(1); java.nio.ByteBuffer v = java.nio.ByteBuffer.wrap(b);
                    jobs.add(new UUID(v.getLong(), v.getLong())); return 0; }, "MODULE_EVENT_NOTICE", row.getNoticeRef());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELAYED", "QUARANTINED", "RECOVERED"})
    void 固定文面は引数0で六言語すべて描画できる(String phase) {
        FanoutMessageKind kind = FanoutMessageKind.valueOf("MODULE_EVENT_" + phase);
        assertThat(kind.argCount()).isZero();
        var messages = renderer.renderAllLocales(kind);
        assertThat(messages).containsOnlyKeys("ja", "en", "zh", "ko", "es", "de");
        messages.values().forEach(m -> { assertThat(m.title()).isNotBlank(); assertThat(m.body()).isNotBlank(); });
    }

    @Test
    void organizationのtenantをdescriptorから保存し偽scopeIdへ変換しない() {
        var row = notice("DELAYED");
        jdbc.update("UPDATE module_event_notice_outbox SET scope_type = 'ORGANIZATION', organization_id = scope_id "
                + "WHERE notice_ref = ?", row.getNoticeRef());
        var message = one();
        var command = codec.decode(message.payloadVersion(), message.payloadJson()).fanout();
        assertThat(command.organizationId()).isEqualTo(8201L);
        assertThat(command.scopeRef()).isEqualTo(row.getNoticeRef().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RELAYED", "FAILED", "DEAD", "DEFER"})
    void 世代付き印付けは旧tokenを拒否し現在tokenだけをMへ委譲する(String operation) {
        var row = notice("DELAYED");
        var message = one();
        assertThat(mark(operation, message, UUID.randomUUID())).isFalse();
        assertThat(mark(operation, message, message.claimToken())).isTrue();
        String expected = operation.equals("RELAYED") ? "RELAYED" : operation.equals("DEAD") ? "DEAD" : "PENDING";
        assertThat(jdbc.queryForObject("SELECT status FROM module_event_notice_outbox WHERE notice_ref = ?",
                String.class, row.getNoticeRef())).isEqualTo(expected);
        if (operation.equals("FAILED") || operation.equals("DEAD")) {
            assertThat(jdbc.queryForObject("SELECT last_error_code FROM module_event_notice_outbox WHERE notice_ref = ?",
                    String.class, row.getNoticeRef())).isEqualTo("RELAY_FAILED");
        }
    }

    @Test
    void oldestを未接続の空にせず実MのPENDING時刻へ委譲する() {
        notice("DELAYED");
        assertThat(source.oldestPendingCreatedAt()).contains(FUTURE);
    }

    private boolean mark(String operation, NotificationOutboxMessage message, UUID token) {
        return switch (operation) {
            case "RELAYED" -> source.markRelayed(message.id(), token, FUTURE);
            case "FAILED", "DEAD" -> source.markFailed(message.id(), token, "秘密を含む自由文をMへ渡さない",
                    FUTURE.plusSeconds(30), operation.equals("DEAD"), FUTURE);
            case "DEFER" -> source.deferUnsupportedVersion(message.id(), token, FUTURE.plusSeconds(60), FUTURE);
            default -> throw new IllegalArgumentException("試練操作が不正");
        };
    }

    private NotificationOutboxMessage one() {
        List<NotificationOutboxMessage> messages = source.claim(100, FUTURE);
        assertThat(messages).as("source の実装が必要").hasSize(1);
        return messages.getFirst();
    }
}
