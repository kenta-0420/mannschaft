package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * M専用遷移のterminal保持を実Service/Repoで検証する先行試練。
 * 公開ユーザー削除API、C/F実業務接続を証明するものではない。
 */
@ModuleEventMySqlTest
@Import(ModuleEventNoticeNotificationFixtures.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventNoticeJobTransitionsIT extends ModuleEventNoticeNotificationTestSupport {
    @Autowired NotificationFanoutJobService service;
    @Autowired MeterRegistry meters;

    @Test void DONEのM開始は拒否して全列を保持する() { rejectStart(NotificationFanoutJobStatus.DONE); }
    @Test void DEAD_LETTERのM開始は拒否して全列を保持する() { rejectStart(NotificationFanoutJobStatus.DEAD_LETTER); }
    @Test void DONEのMcursor前進は全列を保持する() { rejectCursor(NotificationFanoutJobStatus.DONE); }
    @Test void DEAD_LETTERのMcursor前進は全列を保持する() { rejectCursor(NotificationFanoutJobStatus.DEAD_LETTER); }
    @Test void DONEのM失敗記録は全列とcounterを保持する() { rejectFailure(NotificationFanoutJobStatus.DONE); }
    @Test void DEAD_LETTERのM失敗記録は全列とcounterを保持する() { rejectFailure(NotificationFanoutJobStatus.DEAD_LETTER); }

    private void rejectStart(NotificationFanoutJobStatus terminal) {
        UUID id = terminalJob(terminal);
        var before = snapshot(id);
        boolean started = service.tryMarkModuleEventRunning(id);
        var after = snapshot(id);
        assertSoftly(softly -> {
            softly.assertThat(started).as("terminalの開始拒否").isFalse();
            softly.assertThat(after).usingRecursiveComparison().isEqualTo(before);
        });
    }

    private void rejectCursor(NotificationFanoutJobStatus terminal) {
        UUID id = terminalJob(terminal);
        var before = snapshot(id);
        service.advanceModuleEventCursor(id, USER_A, 7L);
        assertThat(snapshot(id)).usingRecursiveComparison().isEqualTo(before);
    }

    private void rejectFailure(NotificationFanoutJobStatus terminal) {
        UUID id = terminalJob(terminal);
        var before = snapshot(id);
        double retry = counter("mannschaft.notification.fanout.job.retry");
        double dead = counter("mannschaft.notification.fanout.job.dead_letter");
        // 2つのterminalで通常失敗と即DEAD_LETTERの両指定を通す。
        service.recordModuleEventFailure(id, "旧Mworkerの自己検体失敗", 10,
                terminal == NotificationFanoutJobStatus.DEAD_LETTER);
        var after = snapshot(id);
        assertSoftly(softly -> {
            softly.assertThat(after).usingRecursiveComparison().isEqualTo(before);
            softly.assertThat(counter("mannschaft.notification.fanout.job.retry")).isEqualTo(retry);
            softly.assertThat(counter("mannschaft.notification.fanout.job.dead_letter")).isEqualTo(dead);
        });
    }

    private UUID terminalJob(NotificationFanoutJobStatus status) {
        var row = notice("DELAYED");
        UUID id = job(row);
        // 既存金型のtyped Repo投入と既存Serviceだけで自己jobをterminalへ置く。
        if (status == NotificationFanoutJobStatus.DONE) service.markDone(id);
        else service.recordFailure(id, "terminalの自己検体", 10, true);
        assertThat(snapshot(id).get("status")).isEqualTo(status.name());
        return id;
    }

    private Map<String,Object> snapshot(UUID id) {
        return jdbc.queryForMap("SELECT * FROM notification_fanout_jobs WHERE id = ?", bytes(id));
    }

    private double counter(String name) {
        var counter = meters.find(name).counter();
        return counter == null ? 0D : counter.count();
    }
}
