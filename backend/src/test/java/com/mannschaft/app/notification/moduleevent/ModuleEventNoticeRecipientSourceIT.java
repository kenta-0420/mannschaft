package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.moduleevent.ModuleEventMySqlTest;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.notification.fanout.FanoutPageRequest;
import com.mannschaft.app.notification.fanout.FanoutRecipient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実 M API を通し、未接続と正常空終端を区別する受信者 adapter 試練。 */
@ModuleEventMySqlTest
@Import(ModuleEventNoticeNotificationFixtures.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ModuleEventNoticeRecipientSourceIT extends ModuleEventNoticeNotificationTestSupport {
    @Autowired ModuleEventNoticeRecipientSource source;

    @Test
    void keyset上限とlocaleを現在許可された実SPIから写し全件化しない() {
        var row = notice("DELAYED");
        state(row).recipients = LongStream.rangeClosed(1, 501)
                .mapToObj(id -> new ModuleEventNoticeRecipient(id, id == 501 ? "en_US" : "ja")).toList();
        List<FanoutRecipient> first = source.nextPage(request(row.getNoticeRef(), 0, 500));
        List<FanoutRecipient> last = source.nextPage(request(row.getNoticeRef(), 500, 500));
        assertThat(first).hasSize(500);
        assertThat(first.getLast().userId()).isEqualTo(500);
        assertThat(last).containsExactly(new FanoutRecipient(501, "en"));
        assertThat(source.nextPage(request(row.getNoticeRef(), 501, 500))).isEmpty();
        assertThat(state(row).observedOutsideTransaction).isTrue();
    }

    @Test
    void 未接続を正常空へ潰さずretry可能な例外にする() {
        var row = notice("DELAYED");
        state(row).available = false;
        assertThatThrownBy(() -> source.nextPage(request(row.getNoticeRef(), 0, 10)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 接続済みで現在適格0件だけは正常空終端とする() {
        var row = notice("DELAYED");
        assertThat(source.nextPage(request(row.getNoticeRef(), 0, 10))).isEmpty();
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"));
        assertThat(source.nextPage(request(row.getNoticeRef(), 0, 10))).hasSize(1);
        state(row).recipients = List.of();
        assertThat(source.nextPage(request(row.getNoticeRef(), 0, 10))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING_RECOVERY", "DEAD", "FUTURE"})
    void 不適格statusを正常空やDONEへ変換しない(String status) {
        var row = notice("RECOVERED", status, 1, "TEAM");
        state(row).recovery = ModuleEventRecoveryState.CLOSED_COMPLETE;
        assertThatThrownBy(() -> source.nextPage(request(row.getNoticeRef(), 0, 10)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 未close復旧を終端にせず実close後だけ受信者を返す() {
        var row = notice("RECOVERED");
        state(row).recipients = List.of(new ModuleEventNoticeRecipient(USER_A, "ja"));
        assertThatThrownBy(() -> source.nextPage(request(row.getNoticeRef(), 0, 10)))
                .isInstanceOf(IllegalStateException.class);
        state(row).recovery = ModuleEventRecoveryState.CLOSED_COMPLETE;
        assertThat(source.nextPage(request(row.getNoticeRef(), 0, 10))).hasSize(1);
    }

    @Test
    void 単一shard以外を拒否する() {
        var row = notice("DELAYED");
        assertThatThrownBy(() -> source.nextPage(new FanoutPageRequest(row.getNoticeRef().toString(), 0, 10, false, 1, 2)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 501})
    void 不正上限を正常空へ変換しない(int limit) {
        var row = notice("DELAYED");
        assertThatThrownBy(() -> source.nextPage(request(row.getNoticeRef(), 0, limit)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 不正cursorと非正規aliasを拒否する() {
        assertThatThrownBy(() -> source.nextPage(request(1L, -1, 10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.nextPage(new FanoutPageRequest("01", 0, 10, false, 0, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FanoutPageRequest request(Long ref, long cursor, int limit) {
        return new FanoutPageRequest(ref.toString(), cursor, limit, false, 0, 1);
    }
}
