package com.mannschaft.app.moduleevent;

import com.mannschaft.app.common.event.ModuleEventListener;
import com.mannschaft.app.common.event.ReliableEvent;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 再配信試練の実購読。観測対象は試練ごとの一意な識別子で分離する。 */
public final class ModuleEventReplayTestFixture {
    public static final String LISTENER_ID = "module-replay-it";

    private ModuleEventReplayTestFixture() { }

    public record ReplayEvent(UUID targetId, Long organizationId, LocalDateTime occurredAt)
            implements ReliableEvent { }

    public static final class Probe {
        public final AtomicInteger invocations = new AtomicInteger();
        public final AtomicInteger finished = new AtomicInteger();
        public volatile boolean fail = true;
    }

    public static class Listeners {
        private final ConcurrentMap<UUID, Probe> probes = new ConcurrentHashMap<>();

        public Probe register(UUID targetId) {
            Probe probe = new Probe();
            if (probes.putIfAbsent(targetId, probe) != null) {
                throw new IllegalArgumentException("検体の識別子が重複しています");
            }
            return probe;
        }

        public void remove(UUID targetId) {
            probes.remove(targetId);
        }

        @ModuleEventListener(id = LISTENER_ID)
        public void receive(ReplayEvent event) {
            Probe probe = probes.get(event.targetId());
            if (probe == null) {
                throw new IllegalStateException("試練の購読観測が登録されていません");
            }
            probe.invocations.incrementAndGet();
            try {
                if (probe.fail) {
                    throw new IllegalStateException("再配信試練の意図した失敗");
                }
            } finally {
                probe.finished.incrementAndGet();
            }
        }
    }
}
