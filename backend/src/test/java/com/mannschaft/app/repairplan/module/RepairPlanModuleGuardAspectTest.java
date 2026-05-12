package com.mannschaft.app.repairplan.module;

import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link RepairPlanModuleGuardAspect} の単体テスト。
 *
 * <p>Spring の {@link AspectJProxyFactory} で手動プロキシを構築し、
 * {@link RequireRepairPlanModule} 付きメソッドの呼び出し前に
 * {@link RepairPlanModuleGuard#requireEnabled(String, Long)} が
 * 期待通り呼ばれることを検証する。</p>
 *
 * <p>本テストはコンパイル時の {@code -parameters} オプションが有効であることを前提とする
 * （build.gradle で標準有効化されている）。仮にビルド環境で無効化されていた場合は
 * パラメータ名解決が失敗し本テストは失敗する。</p>
 */
@DisplayName("RepairPlanModuleGuardAspect AOP 経由テスト")
class RepairPlanModuleGuardAspectTest {

    private RepairPlanModuleGuard guardMock;
    private SampleService proxy;

    @BeforeEach
    void setUp() {
        guardMock = mock(RepairPlanModuleGuard.class);
        RepairPlanModuleGuardAspect aspect = new RepairPlanModuleGuardAspect(guardMock);

        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleServiceImpl());
        factory.addAspect(aspect);
        proxy = factory.getProxy();
    }

    @Test
    @DisplayName("@RequireRepairPlanModule 付与メソッドで guard.requireEnabled が呼ばれる")
    void aspect_invokes_guard_before_method() {
        doNothing().when(guardMock).requireEnabled(anyString(), anyLong());

        String result = proxy.guarded("TEAM", 42L);

        assertThatCode(() -> proxy.guarded("TEAM", 42L)).doesNotThrowAnyException();
        verify(guardMock, times(2)).requireEnabled("TEAM", 42L);
        // メソッドが実行されたことを返り値で確認
        org.assertj.core.api.Assertions.assertThat(result).isEqualTo("OK:TEAM:42");
    }

    @Test
    @DisplayName("guard.requireEnabled が BusinessException を投げた場合、メソッド本体は実行されない")
    void aspect_propagates_guard_failure() {
        doThrow(new BusinessException(RepairPlanModuleErrorCode.REPAIR_PLAN_013))
                .when(guardMock).requireEnabled(anyString(), any());

        assertThatThrownBy(() -> proxy.guarded("TEAM", 42L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RepairPlanModuleErrorCode.REPAIR_PLAN_013);
    }

    @Test
    @DisplayName("@RequireRepairPlanModule 未付与メソッドでは Aspect が動作しない")
    void aspect_skips_unannotated_method() {
        String result = proxy.unguarded();

        org.assertj.core.api.Assertions.assertThat(result).isEqualTo("UNGUARDED");
        verify(guardMock, times(0)).requireEnabled(anyString(), any());
    }

    /**
     * AOP 検証用のサンプル Service インターフェース。
     */
    interface SampleService {
        String guarded(String scopeType, Long scopeId);

        String unguarded();
    }

    /**
     * AOP 検証用のサンプル Service 実装。
     */
    static class SampleServiceImpl implements SampleService {

        @Override
        @RequireRepairPlanModule
        public String guarded(String scopeType, Long scopeId) {
            return "OK:" + scopeType + ":" + scopeId;
        }

        @Override
        public String unguarded() {
            return "UNGUARDED";
        }
    }

}
