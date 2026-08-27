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

    /**
     * クラスベース（CGLIB）プロキシを強制する版。
     *
     * <p>Spring Boot は既定で {@code spring.aop.proxy-target-class=true} であり、
     * 実運用の AOP プロキシは JDK 動的プロキシではなくクラスベースである。
     * この場合、実行される join point は実装クラス側のオーバーライドメソッドであり、
     * インターフェースにのみ付与されたアノテーションは override へ継承されない。
     * AC-13（RequireFeatureInterfaceGuardTest が禁じる形態を Aspect 単独では捕捉できない
     * ことの記録）で CGLIB 側の実測に使う（{@link #proxy(Object)} は JDK 動的プロキシ側の実測用
     * — 詳細は AC-13 のコメントブロックを参照）。</p>
     */
    private <T> T proxyClassBased(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
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
    // AC-13: RequireFeatureInterfaceGuardTest が禁じる形態（@RequireFeature の
    // インターフェース付与）が、FeatureGateAspect 単独では捕捉できないことの記録
    // （Codex 検分指摘①）
    //
    // 実測結果（本テストで固定）: Spring AOP のプロキシ経由呼び出しでは、@annotation() /
    // @within() いずれの pointcut も、内部で
    // ClassUtils.getMostSpecificMethod(method, targetClass) により「実装クラス側の
    // オーバーライドメソッド」へ解決してから注釈の有無を判定する。Java の注釈は
    // インターフェースからオーバーライドメソッドへ継承されないため、この解決結果には
    // 注釈が乗らない。これは JDK 動的プロキシ・CGLIB（クラスベース）プロキシの
    // どちらでも同じであり、以下 AC-13a〜d で両方式について実証している
    // （proxyTargetClass=false / true の双方で「拒否されず本体が実行されてしまう」ことを固定）。
    //
    // pointcut 表現をどう書き足しても（execution() 追加やアノテーション解決の
    // フォールバック追加を含め）この構造的な限界は回避できないと実測で確認済みのため、
    // FeatureGateAspect 側にはそれらのコードを持たせていない
    // （検分の裁定により、到達しない防御コードは削除した）。
    // 根治するには「@RequireFeature をインターフェースへ付与すること自体を発生させない」
    // しかなく、RequireFeatureInterfaceGuardTest（ArchUnit・CI で機械的に拒否）が
    // 本迂回に対する唯一の実効的な防御である。
    //
    // 本テストは「Aspect 単独ではこの形態を捕捉できず、番人が唯一の防御である」ことを
    // 実測で裏付ける記録として存在する。これはバグを是認するテストではない
    // （@RequireFeature のインターフェース付与自体が RequireFeatureInterfaceGuardTest に
    // より禁止形態であり、この禁止を破って書けば CI が落ちる）。意図的に固定した記録なので、
    // assertion を反転させて「直った」ことにしたり削除したりしてはならない。
    // ===============================================================

    @Test
    @DisplayName("(AC-13a/JDK・番人が禁じる形態の記録) JDK動的プロキシでは"
            + "インターフェースのメソッド付与単体だと Aspect が捕捉できず本体が実行されてしまう"
            + "（この形態自体は RequireFeatureInterfaceGuardTest が CI で禁止する）")
    void ac13a_JDKプロキシでもインターフェースメソッド付与単体ではAspectが捕捉できない() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(false);

        InterfaceMethodLevelServiceImpl target = new InterfaceMethodLevelServiceImpl();
        InterfaceMethodLevelService svc = proxy(target);

        assertThat(svc.gated())
                .as("Aspect 単独では捕捉できない形態の記録: pointcut はインターフェースのみへの"
                        + "付与を捕捉できず、フラグ無効でも本体が実行されてしまう。"
                        + "この形態自体を RequireFeatureInterfaceGuardTest が CI で禁止する")
                .isEqualTo("EXECUTED");
        assertThat(target.invocations).isEqualTo(1);
    }

    @Test
    @DisplayName("(AC-13b/JDK・番人が禁じる形態の記録) JDK動的プロキシでは"
            + "インターフェース型付与単体だと Aspect が捕捉できず本体が実行されてしまう"
            + "（この形態自体は RequireFeatureInterfaceGuardTest が CI で禁止する）")
    void ac13b_JDKプロキシでもインターフェース型付与単体ではAspectが捕捉できない() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(false);

        InterfaceTypeLevelServiceImpl target = new InterfaceTypeLevelServiceImpl();
        InterfaceTypeLevelService svc = proxy(target);

        assertThat(svc.gated())
                .as("Aspect 単独では捕捉できない形態の記録: pointcut はインターフェース型への"
                        + "付与を捕捉できず、フラグ無効でも本体が実行されてしまう。"
                        + "この形態自体を RequireFeatureInterfaceGuardTest が CI で禁止する")
                .isEqualTo("EXECUTED");
        assertThat(target.invocations).isEqualTo(1);
    }

    @Test
    @DisplayName("(AC-13c/CGLIB・番人が禁じる形態の記録) クラスベースプロキシでは"
            + "インターフェースのメソッド付与単体だと Aspect が捕捉できず本体が実行されてしまう"
            + "（この形態自体は RequireFeatureInterfaceGuardTest が CI で禁止する）")
    void ac13c_CGLIBプロキシではインターフェースメソッド付与単体ではAspectが捕捉できない() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(false);

        InterfaceMethodLevelServiceImpl target = new InterfaceMethodLevelServiceImpl();
        InterfaceMethodLevelService svc = proxyClassBased(target);

        // Spring Boot 既定（spring.aop.proxy-target-class=true）の CGLIB プロキシでも
        // 同じ理由で Aspect が捕捉できない（本体が実行されてしまう）。
        assertThat(svc.gated())
                .as("Aspect 単独では捕捉できない形態の記録: CGLIB プロキシ配下でも"
                        + "インターフェースへの付与単体では pointcut がマッチしない。"
                        + "この形態自体を RequireFeatureInterfaceGuardTest が CI で禁止する")
                .isEqualTo("EXECUTED");
        assertThat(target.invocations).isEqualTo(1);
    }

    @Test
    @DisplayName("(AC-13d/CGLIB・番人が禁じる形態の記録) クラスベースプロキシでは"
            + "インターフェース型付与単体だと Aspect が捕捉できず本体が実行されてしまう"
            + "（この形態自体は RequireFeatureInterfaceGuardTest が CI で禁止する）")
    void ac13d_CGLIBプロキシではインターフェース型付与単体ではAspectが捕捉できない() {
        when(featureFlagService.isEnabled(FLAG_A)).thenReturn(false);

        InterfaceTypeLevelServiceImpl target = new InterfaceTypeLevelServiceImpl();
        InterfaceTypeLevelService svc = proxyClassBased(target);

        assertThat(svc.gated())
                .as("Aspect 単独では捕捉できない形態の記録: CGLIB プロキシ配下でも"
                        + "インターフェース型付与単体では pointcut がマッチしない。"
                        + "この形態自体を RequireFeatureInterfaceGuardTest が CI で禁止する")
                .isEqualTo("EXECUTED");
        assertThat(target.invocations).isEqualTo(1);
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

    // ---------------------------------------------------------------
    // AC-13: インターフェースにのみ付与（実装クラスへは継承されない）
    // ---------------------------------------------------------------

    interface InterfaceMethodLevelService {
        @RequireFeature("FEATURE_SHIFT_ENABLED")
        String gated();
    }

    static class InterfaceMethodLevelServiceImpl implements InterfaceMethodLevelService {
        int invocations;

        @Override
        public String gated() {
            invocations++;
            return "EXECUTED";
        }
    }

    @RequireFeature("FEATURE_SHIFT_ENABLED")
    interface InterfaceTypeLevelService {
        String gated();
    }

    static class InterfaceTypeLevelServiceImpl implements InterfaceTypeLevelService {
        int invocations;

        @Override
        public String gated() {
            invocations++;
            return "EXECUTED";
        }
    }
}
