package com.mannschaft.app.health;

import com.mannschaft.app.errorreport.service.ErrorReportNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F10.5 Phase 10-β-2 — {@link HealthStatusListener} の単体テスト。
 *
 * <p>HealthEndpoint をモック化し、UP → DOWN 遷移検知時のみ
 * {@link ErrorReportNotifier#notifyHealthDown} が呼ばれることを検証する。
 * {@code @Scheduled} の時間進行はテストせず、{@code doPoll()} を直接呼ぶ。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HealthStatusListener 単体テスト (F10.5 Phase 10-β-2)")
class HealthStatusListenerTest {

    @Mock
    private HealthEndpoint healthEndpoint;
    @Mock
    private ErrorReportNotifier errorReportNotifier;

    private HealthStatusListener listener;

    @BeforeEach
    void setUp() {
        listener = new HealthStatusListener(healthEndpoint, errorReportNotifier, true);
    }

    /**
     * components = {db: status, redis: status} の CompositeHealth をリフレクションで生成する。
     *
     * <p>{@link CompositeHealth} のコンストラクタおよび {@link CompositeHealth#getComponents()} は
     * package-private / final のため Mockito で stub できない。テスト用にリフレクションで実体を作る。</p>
     */
    private CompositeHealth composite(Map<String, Status> components) {
        Map<String, HealthComponent> mapped = new LinkedHashMap<>();
        Status overall = Status.UP;
        for (Map.Entry<String, Status> entry : components.entrySet()) {
            mapped.put(entry.getKey(), Health.status(entry.getValue()).build());
            if (Status.DOWN.equals(entry.getValue())) {
                overall = Status.DOWN;
            }
        }
        try {
            java.lang.reflect.Constructor<CompositeHealth> ctor = CompositeHealth.class
                    .getDeclaredConstructor(
                            org.springframework.boot.actuate.endpoint.ApiVersion.class,
                            Status.class, Map.class);
            ctor.setAccessible(true);
            return ctor.newInstance(org.springframework.boot.actuate.endpoint.ApiVersion.V3, overall, mapped);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("CompositeHealth インスタンスの生成に失敗", e);
        }
    }

    @Test
    @DisplayName("初回ポーリング: 全 component が UP/DOWN いずれでも通知しない（記録のみ）")
    void firstPoll_recordsButDoesNotNotify() {
        given(healthEndpoint.health())
                .willReturn(composite(Map.of("db", Status.UP, "redis", Status.DOWN)));

        listener.doPoll();

        verify(errorReportNotifier, never()).notifyHealthDown(any(), any());
    }

    @Test
    @DisplayName("UP → DOWN 遷移: 該当 component について notifyHealthDown が呼ばれる")
    void upToDown_triggersNotification() {
        // 1 周目: 全部 UP（記録のみ）
        given(healthEndpoint.health())
                .willReturn(composite(Map.of("db", Status.UP, "redis", Status.UP)));
        listener.doPoll();
        // 2 周目: db が DOWN になる
        given(healthEndpoint.health())
                .willReturn(composite(Map.of("db", Status.DOWN, "redis", Status.UP)));
        listener.doPoll();

        verify(errorReportNotifier, times(1)).notifyHealthDown(eq("db"), any());
        verify(errorReportNotifier, never()).notifyHealthDown(eq("redis"), any());
    }

    @Test
    @DisplayName("DOWN → UP 復旧時は通知しない（本フェーズではログ記録のみ）")
    void downToUp_doesNotNotify() {
        // 1 周目: db DOWN（記録のみ）
        given(healthEndpoint.health())
                .willReturn(composite(Map.of("db", Status.DOWN, "redis", Status.UP)));
        listener.doPoll();
        // 2 周目: db UP に復旧
        given(healthEndpoint.health())
                .willReturn(composite(Map.of("db", Status.UP, "redis", Status.UP)));
        listener.doPoll();

        verify(errorReportNotifier, never()).notifyHealthDown(any(), any());
    }

    @Test
    @DisplayName("2 component 同時 DOWN: それぞれについて notifyHealthDown が呼ばれる")
    void multipleComponentsDown_triggersMultipleNotifications() {
        // 1 周目: 全 UP
        given(healthEndpoint.health())
                .willReturn(composite(Map.of("db", Status.UP, "redis", Status.UP)));
        listener.doPoll();
        // 2 周目: 両方 DOWN
        given(healthEndpoint.health())
                .willReturn(composite(Map.of("db", Status.DOWN, "redis", Status.DOWN)));
        listener.doPoll();

        verify(errorReportNotifier).notifyHealthDown(eq("db"), any());
        verify(errorReportNotifier).notifyHealthDown(eq("redis"), any());
    }

    @Test
    @DisplayName("DOWN 継続（DOWN→DOWN）は新たに通知しない（クールダウンは Notifier 側で管理）")
    void persistentDown_notifiesOnlyOnTransition() {
        // 1 周目: db UP（記録のみ）
        given(healthEndpoint.health())
                .willReturn(composite(Map.of("db", Status.UP)));
        listener.doPoll();
        // 2 周目: db DOWN（遷移検知 → 通知）
        given(healthEndpoint.health())
                .willReturn(composite(Map.of("db", Status.DOWN)));
        listener.doPoll();
        // 3 周目: db DOWN 継続（遷移なし → 通知しない）
        given(healthEndpoint.health())
                .willReturn(composite(Map.of("db", Status.DOWN)));
        listener.doPoll();

        verify(errorReportNotifier, times(1)).notifyHealthDown(eq("db"), any());
    }

    @Test
    @DisplayName("enabled=false の場合は HealthEndpoint も呼ばない")
    void disabled_doesNothing() {
        HealthStatusListener disabled = new HealthStatusListener(healthEndpoint, errorReportNotifier, false);
        disabled.pollHealthStatus();

        verify(healthEndpoint, never()).health();
        verify(errorReportNotifier, never()).notifyHealthDown(any(), any());
    }

    @Test
    @DisplayName("HealthEndpoint が例外を投げても pollHealthStatus は伝搬させない")
    void exceptionInEndpoint_isSwallowed() {
        given(healthEndpoint.health()).willThrow(new RuntimeException("kaboom"));

        // 例外が伝搬しないこと（次回のスケジュールが止まるのを防ぐ）
        listener.pollHealthStatus();

        verify(errorReportNotifier, never()).notifyHealthDown(any(), any());
    }
}
