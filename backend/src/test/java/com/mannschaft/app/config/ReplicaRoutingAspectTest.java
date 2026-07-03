package com.mannschaft.app.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ReplicaRoutingAspect} の単体テスト（Phase 4-D 根治）。
 *
 * <p>本テストは「readOnly 外側 → 書き込み内側（{@code REQUIRES_NEW}）」のネストで、
 * 書き込みメソッド進入中に {@link DataSourceType#PRIMARY} が明示され、
 * 復帰時に外側の {@link DataSourceType#REPLICA} 指定が<b>復元</b>されることを検証する。
 * 従来の実装（書き込みで何もしない／finally で無条件 clear）ではこれらが赤になる。</p>
 */
@DisplayName("ReplicaRoutingAspect 単体テスト")
class ReplicaRoutingAspectTest {

    private final ReplicaRoutingAspect aspect = new ReplicaRoutingAspect();

    @AfterEach
    void tearDown() {
        DataSourceContextHolder.clear();
    }

    /** アノテーション付きメソッドを持つサンプル（実 Method / Transactional の取得元）。 */
    @SuppressWarnings("unused")
    static class Sample {
        @Transactional(readOnly = true)
        public void readMethod() {
        }

        @Transactional
        public void writeMethod() {
        }
    }

    /**
     * 指定メソッドで aspect を実行する。proceed 実行中の ThreadLocal 値を capture に記録する。
     */
    private void invoke(String methodName, AtomicReference<DataSourceType> captured) throws Throwable {
        Method method = Sample.class.getMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.proceed()).thenAnswer(inv -> {
            captured.set(DataSourceContextHolder.getDataSourceType());
            return null;
        });
        aspect.routeDataSource(pjp);
    }

    @Nested
    @DisplayName("トップレベル（進入前 null）")
    class TopLevel {

        @Test
        @DisplayName("readOnly メソッドは進入中 REPLICA・復帰後は null")
        void readOnlyRoutesReplica() throws Throwable {
            AtomicReference<DataSourceType> captured = new AtomicReference<>();
            invoke("readMethod", captured);
            assertThat(captured.get()).isEqualTo(DataSourceType.REPLICA);
            assertThat(DataSourceContextHolder.getDataSourceType()).isNull();
        }

        @Test
        @DisplayName("書き込みメソッドは進入中 PRIMARY を明示・復帰後は null")
        void writeRoutesPrimary() throws Throwable {
            AtomicReference<DataSourceType> captured = new AtomicReference<>();
            invoke("writeMethod", captured);
            assertThat(captured.get()).isEqualTo(DataSourceType.PRIMARY);
            assertThat(DataSourceContextHolder.getDataSourceType()).isNull();
        }
    }

    @Nested
    @DisplayName("ネスト（進入前に外側指定あり）")
    class Nested_ {

        @Test
        @DisplayName("【根治】readOnly(REPLICA) の内側で書き込み → 進入中 PRIMARY・復帰後は REPLICA へ復元")
        void writeInsideReadOnlyRoutesPrimaryThenRestoresReplica() throws Throwable {
            // 外側 readOnly が既に REPLICA をセットしている状態を再現
            DataSourceContextHolder.setDataSourceType(DataSourceType.REPLICA);

            AtomicReference<DataSourceType> captured = new AtomicReference<>();
            invoke("writeMethod", captured);

            // REQUIRES_NEW の新規コネクションはプライマリへ向く
            assertThat(captured.get()).isEqualTo(DataSourceType.PRIMARY);
            // 外側の REPLICA 指定は失われず復元される（clear ではなく restore）
            assertThat(DataSourceContextHolder.getDataSourceType()).isEqualTo(DataSourceType.REPLICA);
        }

        @Test
        @DisplayName("書き込み(PRIMARY) の内側で readOnly → 進入中 REPLICA・復帰後は PRIMARY へ復元")
        void readInsideWriteRoutesReplicaThenRestoresPrimary() throws Throwable {
            DataSourceContextHolder.setDataSourceType(DataSourceType.PRIMARY);

            AtomicReference<DataSourceType> captured = new AtomicReference<>();
            invoke("readMethod", captured);

            assertThat(captured.get()).isEqualTo(DataSourceType.REPLICA);
            assertThat(DataSourceContextHolder.getDataSourceType()).isEqualTo(DataSourceType.PRIMARY);
        }
    }

    @Test
    @DisplayName("proceed が例外を投げても進入前の指定を復元する")
    void restoresPreviousOnException() throws Throwable {
        DataSourceContextHolder.setDataSourceType(DataSourceType.REPLICA);

        Method method = Sample.class.getMethod("writeMethod");
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        RuntimeException boom = new RuntimeException("boom");
        when(pjp.proceed()).thenThrow(boom);

        assertThatThrownBy(() -> aspect.routeDataSource(pjp)).isSameAs(boom);
        assertThat(DataSourceContextHolder.getDataSourceType()).isEqualTo(DataSourceType.REPLICA);
    }
}
