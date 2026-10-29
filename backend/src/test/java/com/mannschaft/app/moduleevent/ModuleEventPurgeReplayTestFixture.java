package com.mannschaft.app.moduleevent;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.event.ModuleEventListener;
import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.quickmemo.dto.UpdateSettingsRequest;
import com.mannschaft.app.quickmemo.service.UserQuickMemoSettingsService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** M16の検体購読。C/Fの実業務への接続を表すものではない。 */
@TestConfiguration(proxyBeanMethods = false)
public class ModuleEventPurgeReplayTestFixture {
    public static final String LISTENER_ID = "module-purge-replay-it";

    @Bean
    Listeners moduleEventPurgeReplayListeners(UserService users, UserQuickMemoSettingsService settings) {
        return new Listeners(users, settings);
    }

    public record PurgeReplayEvent(UUID id, Long userId, LocalDateTime occurredAt) implements ReliableEvent { }

    public static final class Probe {
        public final AtomicInteger skipped = new AtomicInteger();
        public final AtomicInteger written = new AtomicInteger();
    }

    public static class Listeners {
        private final UserService users;
        private final UserQuickMemoSettingsService settings;
        private final ConcurrentMap<UUID, Probe> probes = new ConcurrentHashMap<>();

        Listeners(UserService users, UserQuickMemoSettingsService settings) {
            this.users = users;
            this.settings = settings;
        }

        public Probe register(UUID id) {
            Probe probe = new Probe();
            if (probes.putIfAbsent(id, probe) != null) throw new IllegalArgumentException("検体ID重複");
            return probe;
        }

        public void remove(UUID id) { probes.remove(id); }

        // 外側TXを付けず、実現在判定と実Serviceの書込TXを通す。
        @ModuleEventListener(id = LISTENER_ID)
        public void receive(PurgeReplayEvent event) {
            Probe probe = probes.get(event.id());
            if (probe == null) throw new IllegalStateException("未登録の検体");
            var state = users.getActiveDisplayStates(List.of(event.userId())).get(event.userId());
            if (state == null || !state.active()) {
                probe.skipped.incrementAndGet();
                return;
            }
            settings.updateSettings(event.userId(),
                    new UpdateSettingsRequest(true, null, null, null, null, null, null), "NEW_ONLY");
            probe.written.incrementAndGet();
        }
    }
}
