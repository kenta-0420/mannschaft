package com.mannschaft.app.common.featuregate;

import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FeatureGateAspect} の単体テスト（Gate 基盤工事③・試練 / 受け入れ条件 AC-1〜AC-6・AC-10）。
 *
 * <p><b>金型</b>: {@code RepairPlanModuleGuardAspectTest}
 * （{@link AspectJProxyFactory} で手動プロキシを構築し、Spring コンテキストを起動しない）。</p>
 *
 * <p>実 HTTP ステータス（AC-7）・未認証 401（AC-8）・キャッシュ挙動（AC-11/AC-12）は
 * {@code FeatureGateAspectIT}、アノテーション引数の静的検証（AC-9）は
 * {@code FeatureGateAnnotationKeyGuardTest} が受け持つ。</p>
 */
@DisplayName("FeatureGateAspect 単体テスト（Gate基盤工事③）")
class FeatureGateAspectTest {

    private static final String FLAG_A = "FEATURE_SHIFT_ENABLED";
    private static final String FLAG_B = "FEATURE_MARKET_ENABLED";
    private static final String UNKNOWN_FLAG = "FEATURE_NO_SUCH_ROW_ENABLED";

    private FeatureFlagService featureFlagService;

    @BeforeEach
    void setUp() {
        featureFlagService = mock(FeatureFlagService.class);
    }

    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new FeatureGateAspect(featureFlagService));
        return factory.getProxy();
    }

    // ===============================================================
    // AC-1: フラグ有効時は本体が実行される
    // ===============================================================

    @Test
    @DisplayName("(AC-1) フラグ有効時は @RequireFeature 付与メソッドの本体が実行される")
    void ac1_フラグ有効時は本体が実行される() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(true);

        SampleService svc = proxy(new SampleServiceImpl());

        assertThat(svc.singleFlag()).isEqualTo("EXECUTED");
        verify(featureFlagService).isEnabled(FLAG_A);
    }

    // ===============================================================
    // AC-2: フラグ無効時は本体が実行されず FEATURE_GATE_001 が飛ぶ
    // ===============================================================

    @Test
    @DisplayName("(AC-2) フラグ無効時は本体が実行されず FEATURE_GATE_001 の BusinessException が飛ぶ")
    void ac2_フラグ無効時はFEATURE_GATE_001で拒否される() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(false);

        SampleServiceImpl target = new SampleServiceImpl();
        SampleService svc = proxy(target);

        assertThatThrownBy(svc::singleFlag)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(FeatureGateErrorCode.FEATURE_GATE_001);

        assertThat(target.invocations)
                .as("拒否されたのだからメソッド本体は1度も実行されていないこと")
                .isZero();
    }

    // ===============================================================
    // AC-3: feature_flags に行が無い未知キーは拒否（フェイルクローズ）
    // ===============================================================

    @Test
    @DisplayName("(AC-3) 行が存在しない未知キーは拒否される（フェイルクローズ）")
    void ac3_未知キーはフェイルクローズで拒否される() {
        // FeatureFlagService.isEnabled は行が無ければ false を返す（orElse(false)）。
        when(featureFlagService.isEnabled(UNKNOWN_FLAG)).thenReturn(false);

        UnknownFlagServiceImpl target = new UnknownFlagServiceImpl();
        UnknownFlagService svc = proxy(target);

        assertThatThrownBy(svc::unknownFlag)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(FeatureGateErrorCode.FEATURE_GATE_001);

        assertThat(target.invocations).isZero();
    }

    // ===============================================================
    // AC-4: クラスレベル付与が全 public メソッドに効く / メソッドレベルが優先される
    // ===============================================================

    @Test
    @DisplayName("(AC-4a) クラスレベル付与が全 public メソッドに効く")
    void ac4a_クラスレベル付与が全publicメソッドに効く() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(false);

        ClassLevelServiceImpl target = new ClassLevelServiceImpl();
        ClassLevelService svc = proxy(target);

        assertThatThrownBy(svc::first)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(FeatureGateErrorCode.FEATURE_GATE_001);
        assertThatThrownBy(svc::second)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(FeatureGateErrorCode.FEATURE_GATE_001);

        assertThat(target.invocations).as("2メソッドとも本体未実行").isZero();
    }

    @Test
    @DisplayName("(AC-4b) メソッドレベル付与がクラスレベル付与より優先される")
    void ac4b_メソッドレベルがクラスレベルより優先される() {
        // クラスレベル = FLAG_A（無効）、メソッドレベル = FLAG_B（有効）。
        // メソッドレベルが優先されるなら本体は実行され、FLAG_A は評価されない。
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(false);
        when(featureFlagService.isEnabled(FLAG_B)).thenReturn(true);

        ClassLevelService svc = proxy(new ClassLevelServiceImpl());

        assertThat(svc.overridden()).isEqualTo("EXECUTED");
        verify(featureFlagService).isEnabled(FLAG_B);
        verify(featureFlagService, never()).isEnabled(FLAG_A);
    }

    // ===============================================================
    // AC-5: 未付与メソッドでは Aspect が動かない
    // ===============================================================

    @Test
    @DisplayName("(AC-5) @RequireFeature 未付与メソッドでは isEnabled を1度も呼ばない")
    void ac5_未付与メソッドではAspectが動かない() {
        SampleService svc = proxy(new SampleServiceImpl());

        assertThat(svc.notGated()).isEqualTo("NOT_GATED");
        verify(featureFlagService, never()).isEnabled(anyString());
    }

    // ===============================================================
    // AC-6: @Order(10) で @Transactional より先に走る
    // ===============================================================

    /**
     * (AC-6 前半) Aspect の優先度宣言そのものを固定する。
     *
     * <p>Spring の {@code @Transactional} アドバイザは既定で
     * {@code Ordered.LOWEST_PRECEDENCE}（{@link Integer#MAX_VALUE}）である。
     * 本 Aspect の order がそれより小さい＝先に走ることで、
     * 拒否時にトランザクションが開始されない（＝DB 書き込みが起きない）。
     * 実際に書き込みが起きないことは {@code FeatureGateAspectIT} で実 DB を使って裏取りする。</p>
     */
    @Test
    @DisplayName("(AC-6) FeatureGateAspect は @Order(10) で @Transactional より先に走る")
    void ac6_Orderは10でトランザクションより先に走る() {
        Order order = FeatureGateAspect.class.getAnnotation(Order.class);

        assertThat(order)
                .as("@Order が付いていないと @Transactional との前後関係が不定になり、"
                        + "拒否時にトランザクションが開始されうる")
                .isNotNull();
        assertThat(order.value()).isEqualTo(10);
        assertThat(order.value())
                .as("@Transactional アドバイザ既定（Ordered.LOWEST_PRECEDENCE）より先であること")
                .isLessThan(Integer.MAX_VALUE);
    }

    // ===============================================================
    // AC-10: 複数キー指定は AND
    // ===============================================================

    @Test
    @DisplayName("(AC-10a) 複数キー指定で全て有効なら本体が実行される")
    void ac10a_複数キーが全て有効なら実行される() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(true);
        when(featureFlagService.isEnabled(FLAG_B)).thenReturn(true);

        MultiFlagService svc = proxy(new MultiFlagServiceImpl());

        assertThat(svc.multi()).isEqualTo("EXECUTED");
        verify(featureFlagService).isEnabled(FLAG_A);
        verify(featureFlagService).isEnabled(FLAG_B);
    }

    @Test
    @DisplayName("(AC-10b) 複数キー指定で1つでも無効なら拒否される（AND判定）")
    void ac10b_複数キーの1つが無効なら拒否される() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(true);
        when(featureFlagService.isEnabled(FLAG_B)).thenReturn(false);

        MultiFlagServiceImpl target = new MultiFlagServiceImpl();
        MultiFlagService svc = proxy(target);

        assertThatThrownBy(svc::multi)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(FeatureGateErrorCode.FEATURE_GATE_001);

        assertThat(target.invocations).isZero();
    }

    // ===============================================================
    // AOP 検証用のサンプル（試練の骨格。出陣では変更不要）
    // ===============================================================

    interface SampleService {
        String singleFlag();

        String notGated();
    }

    static class SampleServiceImpl implements SampleService {
        int invocations;

        @Override
        @RequireFeature("FEATURE_SHIFT_ENABLED")
        public String singleFlag() {
            invocations++;
            return "EXECUTED";
        }

        @Override
        public String notGated() {
            return "NOT_GATED";
        }
    }

    interface UnknownFlagService {
        String unknownFlag();
    }

    static class UnknownFlagServiceImpl implements UnknownFlagService {
        int invocations;

        @Override
        @RequireFeature("FEATURE_NO_SUCH_ROW_ENABLED")
        public String unknownFlag() {
            invocations++;
            return "EXECUTED";
        }
    }

    interface ClassLevelService {
        String first();

        String second();

        String overridden();
    }

    @RequireFeature("FEATURE_SHIFT_ENABLED")
    static class ClassLevelServiceImpl implements ClassLevelService {
        int invocations;

        @Override
        public String first() {
            invocations++;
            return "EXECUTED";
        }

        @Override
        public String second() {
            invocations++;
            return "EXECUTED";
        }

        @Override
        @RequireFeature("FEATURE_MARKET_ENABLED")
        public String overridden() {
            invocations++;
            return "EXECUTED";
        }
    }

    interface MultiFlagService {
        String multi();
    }

    static class MultiFlagServiceImpl implements MultiFlagService {
        int invocations;

        @Override
        @RequireFeature({"FEATURE_SHIFT_ENABLED", "FEATURE_MARKET_ENABLED"})
        public String multi() {
            invocations++;
            return "EXECUTED";
        }
    }
}
