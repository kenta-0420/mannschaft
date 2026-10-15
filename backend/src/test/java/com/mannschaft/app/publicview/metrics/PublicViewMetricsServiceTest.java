package com.mannschaft.app.publicview.metrics;

import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.team.repository.TeamRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PublicViewMetricsService} の単体テスト。
 *
 * <p>F19.1 Phase 5: Counter 記録ロジックおよびメトリクス名・タグを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicViewMetricsService 単体テスト")
class PublicViewMetricsServiceTest {

    /** テスト用インメモリ MeterRegistry。実際の Prometheus 依存なしで Counter を検証できる。 */
    private MeterRegistry meterRegistry;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    private PublicViewMetricsService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new PublicViewMetricsService(meterRegistry, teamRepository, organizationRepository);
        // Gauge 登録
        service.registerGauges();
    }

    @Test
    @DisplayName("DISPLAY_NAME → REAL_NAME の変更: Counter が 1 増加すること")
    void recordModeChange_displaysToReal_incrementsCounter() {
        // act
        service.recordModeChange(
                NameDisclosureMode.DISPLAY_NAME,
                NameDisclosureMode.REAL_NAME,
                "TEAM"
        );

        // assert: Counter 名・タグが正しいこと
        Counter counter = meterRegistry.find("public.supporter_name_disclosure.mode_changes")
                .tag("old_mode", "DISPLAY_NAME")
                .tag("new_mode", "REAL_NAME")
                .tag("scope_type", "TEAM")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("REAL_NAME → DISPLAY_NAME の変更: Counter が 1 増加すること")
    void recordModeChange_realToDisplay_incrementsCounter() {
        // act
        service.recordModeChange(
                NameDisclosureMode.REAL_NAME,
                NameDisclosureMode.DISPLAY_NAME,
                "ORGANIZATION"
        );

        // assert
        Counter counter = meterRegistry.find("public.supporter_name_disclosure.mode_changes")
                .tag("old_mode", "REAL_NAME")
                .tag("new_mode", "DISPLAY_NAME")
                .tag("scope_type", "ORGANIZATION")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("同じモード変更を 2 回呼ぶと Counter が 2 になること")
    void recordModeChange_calledTwice_counterIsTwo() {
        // act
        service.recordModeChange(
                NameDisclosureMode.DISPLAY_NAME,
                NameDisclosureMode.REAL_NAME,
                "TEAM"
        );
        service.recordModeChange(
                NameDisclosureMode.DISPLAY_NAME,
                NameDisclosureMode.REAL_NAME,
                "TEAM"
        );

        // assert
        Counter counter = meterRegistry.find("public.supporter_name_disclosure.mode_changes")
                .tag("old_mode", "DISPLAY_NAME")
                .tag("new_mode", "REAL_NAME")
                .tag("scope_type", "TEAM")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Gauge が MeterRegistry に登録されていること")
    void registerGauges_bothScopeTypeGaugesRegistered() {
        // assert: TEAM Gauge が登録されていること
        assertThat(meterRegistry.find("public.supporter_name_disclosure.real_name_enabled_rate")
                .tag("scope_type", "TEAM")
                .gauge()).isNotNull();

        // assert: ORGANIZATION Gauge が登録されていること
        assertThat(meterRegistry.find("public.supporter_name_disclosure.real_name_enabled_rate")
                .tag("scope_type", "ORGANIZATION")
                .gauge()).isNotNull();
    }
}
