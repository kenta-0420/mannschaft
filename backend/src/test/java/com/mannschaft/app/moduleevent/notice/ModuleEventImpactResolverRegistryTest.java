package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.moduleevent.api.spi.ModuleEventImpactResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** resolver 構成と初回識別の契約試練。DB・Spring context・通知配送を使わない。 */
class ModuleEventImpactResolverRegistryTest {
    private static final String KEY = "fixture.notice";
    private static final UUID OPERATION = UUID.fromString("019a0000-0000-7000-8000-000000000001");
    private static final UUID EPISODE = UUID.fromString("019a0000-0000-7000-8000-000000000002");
    private static final ReliableEvent EVENT = new ProbeEvent();

    @Test
    void provider0は未接続としてempty() {
        assertTrue(new ModuleEventImpactResolverRegistry(List.of()).identify(EVENT).isEmpty());
    }

    @Test
    void nullのprovider集合を起動時に拒否() {
        assertThrows(IllegalArgumentException.class, () -> new ModuleEventImpactResolverRegistry(null));
    }

    @Test
    void nullのproviderを起動時に拒否() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleEventImpactResolverRegistry(Arrays.asList((ModuleEventImpactResolver) null)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", " upper", "Upper", "x_y", "x/y", "x y"})
    void 安定keyのnull空白形式不正を拒否(String key) {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleEventImpactResolverRegistry(List.of(provider(key, Optional.empty()))));
    }

    @Test
    void key101文字を拒否() {
        assertThrows(IllegalArgumentException.class,
                () -> new ModuleEventImpactResolverRegistry(List.of(provider("a".repeat(101), Optional.empty()))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "domain.event-v1"})
    void 正規keyを登録Beanからだけ解決(String key) {
        ModuleEventImpactResolver source = provider(key, Optional.empty());
        assertSame(source, new ModuleEventImpactResolverRegistry(List.of(source)).find(key).orElse(null));
    }

    @Test
    void key100文字を登録可能() {
        String key = "a".repeat(100);
        assertTrue(new ModuleEventImpactResolverRegistry(List.of(provider(key, Optional.empty()))).find(key).isPresent());
    }

    @Test
    void 同じkeyのBeanは非対象でも拒否() {
        assertThrows(IllegalArgumentException.class, () -> new ModuleEventImpactResolverRegistry(List.of(
                provider(KEY, Optional.empty()), provider(KEY, Optional.empty()))));
    }

    @Test
    void 非対象providerはempty() {
        assertTrue(new ModuleEventImpactResolverRegistry(List.of(provider(KEY, Optional.empty()))).identify(EVENT).isEmpty());
    }

    @Test
    void 一つだけ対象なら同一bindingを返す() {
        ModuleEventImpactBinding binding = binding(KEY, ModuleEventNoticeScope.ORGANIZATION, 7L, 7L);
        assertSame(binding, registry(Optional.of(binding)).identify(EVENT).orElse(null));
    }

    @Test
    void TEAM単独のorganizationIdNullを許す() {
        ModuleEventImpactBinding binding = binding(KEY, ModuleEventNoticeScope.TEAM, 7L, null);
        assertSame(binding, registry(Optional.of(binding)).identify(EVENT).orElse(null));
    }

    @Test
    void 複数providerが同じeventへmatchしたら拒否() {
        assertThrows(IllegalStateException.class, () -> new ModuleEventImpactResolverRegistry(List.of(
                provider(KEY, Optional.of(binding(KEY, ModuleEventNoticeScope.TEAM, 7L, null))),
                provider("other.notice", Optional.of(binding("other.notice", ModuleEventNoticeScope.TEAM, 7L, null)))))
                .identify(EVENT));
    }

    @Test
    void nullOptionalは未接続に偽装せず拒否() {
        assertThrows(IllegalStateException.class, () -> registry(null).identify(EVENT));
    }

    @Test
    void providerと異なるbindingkeyを拒否() {
        assertThrows(IllegalStateException.class,
                () -> registry(Optional.of(binding("other.notice", ModuleEventNoticeScope.TEAM, 7L, null))).identify(EVENT));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 正数でないscopeIdを拒否(long scopeId) {
        assertThrows(IllegalStateException.class,
                () -> registry(Optional.of(binding(KEY, ModuleEventNoticeScope.TEAM, scopeId, null))).identify(EVENT));
    }

    @Test
    void ORGANIZATIONの不一致を拒否() {
        assertThrows(IllegalStateException.class,
                () -> registry(Optional.of(binding(KEY, ModuleEventNoticeScope.ORGANIZATION, 7L, 8L))).identify(EVENT));
    }

    @Test
    void ORGANIZATIONのorganizationIdNullを拒否() {
        assertThrows(IllegalStateException.class,
                () -> registry(Optional.of(binding(KEY, ModuleEventNoticeScope.ORGANIZATION, 7L, null))).identify(EVENT));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void TEAMでも指定したorganizationIdは正数(long organizationId) {
        assertThrows(IllegalStateException.class,
                () -> registry(Optional.of(binding(KEY, ModuleEventNoticeScope.TEAM, 7L, organizationId))).identify(EVENT));
    }

    @Test
    void 必須UUIDnullをtransport構築時から拒否() {
        assertThrows(NullPointerException.class, () -> new ModuleEventImpactBinding(KEY, null, EPISODE,
                ModuleEventNoticeScope.TEAM, 7L, null));
        assertThrows(NullPointerException.class, () -> new ModuleEventImpactBinding(KEY, OPERATION, null,
                ModuleEventNoticeScope.TEAM, 7L, null));
    }

    @Test
    void nullEventを拒否() {
        assertThrows(IllegalArgumentException.class, () -> registry(Optional.empty()).identify(null));
    }

    private static ModuleEventImpactResolverRegistry registry(Optional<ModuleEventImpactBinding> result) {
        return new ModuleEventImpactResolverRegistry(List.of(provider(KEY, result)));
    }

    private static ModuleEventImpactBinding binding(String key, ModuleEventNoticeScope scope, Long id, Long org) {
        return new ModuleEventImpactBinding(key, OPERATION, EPISODE, scope, id, org);
    }

    private static ModuleEventImpactResolver provider(String key, Optional<ModuleEventImpactBinding> result) {
        return new ModuleEventImpactResolver() {
            public String resolverKey() { return key; }
            public Optional<ModuleEventImpactBinding> identify(ReliableEvent event) { return result; }
            public ModuleEventRecoveryState recoveryState(ModuleEventImpactBinding value) {
                fail("初回識別は業務復旧評価を呼んではならない"); return ModuleEventRecoveryState.UNKNOWN;
            }
            public List<ModuleEventNoticeRecipient> nextRecipients(ModuleEventNoticeDescriptor n, long after, int limit) {
                fail("初回識別は受信者評価を呼んではならない"); return List.of();
            }
            public List<ModuleEventNoticeRecipient> eligibleRecipients(ModuleEventNoticeDescriptor n, List<Long> ids) {
                fail("初回識別は受信者評価を呼んではならない"); return List.of();
            }
        };
    }

    private record ProbeEvent() implements ReliableEvent {
        public LocalDateTime occurredAt() { return LocalDateTime.of(2026, 10, 29, 0, 0); }
    }
}
