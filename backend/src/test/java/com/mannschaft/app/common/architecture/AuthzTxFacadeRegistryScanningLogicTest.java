package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.architecture.AuthzTxFacadeRegistryArchTest.Entry;
import org.junit.jupiter.api.Tag;
import com.mannschaft.app.common.architecture.AuthzTxFacadeRegistryArchTest.Rules;
import com.mannschaft.app.common.architecture.AuthzTxFacadeRegistryArchTest.TxBody;
import com.mannschaft.app.common.architecture.AuthzTxFacadeRegistryArchTest.TxMode;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthzTxFacadeRegistryArchTest} の規則自体の正しさを実証する自己検証テスト。
 *
 * <p><b>番人が緑であることは、番人が守っていることの証明にならない。</b>規則が空振りしていても「違反ゼロ」と
 * 同じ緑になるためである。本クラスはテスト内の小さな合成クラス（偽の Controller・Facade・tx 本体・認可クラス）を
 * ArchUnit に読ませ、各規則について<b>陽性対照</b>（壊れた形を検出する）と<b>陰性対照</b>（正しい形を誤検出しない）を
 * 対で置く（先例: {@code VillageExistenceCheckCentralizationGuardScanningLogicTest}）。</p>
 */
@DisplayName("AuthzTxFacadeRegistryArchTest の規則（検出力＋誤検出耐性）")
@Tag(ArchUnitTestTag.ARCHUNIT)
class AuthzTxFacadeRegistryScanningLogicTest {

    // ────────────────────────────────────────────────────────────
    // 合成クラス（本番には置かない）
    // ────────────────────────────────────────────────────────────

    /** 偽の AccessControlService（throw 形 check* と boolean 形）。名前を *Service にしない（同ドメイン Service 扱いを避ける）。 */
    static class FakeAccessControl {
        void checkAdminOrAbove(Long userId) {
        }

        boolean isAdminOrAbove(Long userId) {
            return userId != null;
        }
    }

    static class FakeGate {
        void requireAdminOrConceal(Long userId) {
        }
    }

    static class GoodTxService {
        String doWork(Long id) {
            return "ok" + id;
        }
    }

    /** 認可クラスに依存する tx 本体（R4 の陽性）。 */
    static class AuthzTxService {
        private final FakeAccessControl acs = new FakeAccessControl();

        String doWork(Long id) {
            if (!acs.isAdminOrAbove(id)) {
                throw new IllegalStateException();
            }
            return "x";
        }

        String plain(Long id) {
            return "p" + id;
        }
    }

    static class GoodFacade {
        private final FakeGate gate = new FakeGate();
        private final GoodTxService tx = new GoodTxService();

        public String op(Long userId) {
            gate.requireAdminOrConceal(userId);
            return tx.doWork(userId);
        }
    }

    /** 認可を同クラスの private メソッドに置く（R3 の陰性: 深さ 1 で届く）。 */
    static class PrivateAuthzFacade {
        private final FakeGate gate = new FakeGate();
        private final GoodTxService tx = new GoodTxService();

        public String op(Long userId) {
            authorize(userId);
            return tx.doWork(userId);
        }

        private void authorize(Long userId) {
            gate.requireAdminOrConceal(userId);
        }
    }

    /** 認可をラムダの中で呼ぶ（R3 の陰性: ラムダも囲むメソッドの一部として数える）。 */
    static class LambdaAuthzFacade {
        private final FakeGate gate = new FakeGate();
        private final GoodTxService tx = new GoodTxService();

        public String op(Long userId) {
            Runnable check = () -> gate.requireAdminOrConceal(userId);
            check.run();
            return tx.doWork(userId);
        }
    }

    /** 認可へ届かない public メソッドを持つ（R3 の陽性）。 */
    static class HollowFacade {
        private final GoodTxService tx = new GoodTxService();

        public String op(Long userId) {
            return tx.doWork(userId);
        }
    }

    @Transactional
    static class ClassTxFacade {
        private final FakeGate gate = new FakeGate();
        private final GoodTxService tx = new GoodTxService();

        public String op(Long userId) {
            gate.requireAdminOrConceal(userId);
            return tx.doWork(userId);
        }
    }

    static class MethodTxFacade {
        private final FakeGate gate = new FakeGate();
        private final GoodTxService tx = new GoodTxService();

        @Transactional
        public String op(Long userId) {
            gate.requireAdminOrConceal(userId);
            return tx.doWork(userId);
        }
    }

    /** 認可クラスに依存する tx 本体を呼ぶ Facade（R4 の陽性・METHOD の陰性の素材）。 */
    static class AuthzTxCallingFacade {
        private final FakeGate gate = new FakeGate();
        private final AuthzTxService tx = new AuthzTxService();

        public String op(Long userId) {
            gate.requireAdminOrConceal(userId);
            return tx.doWork(userId);
        }

        public String plainOp(Long userId) {
            gate.requireAdminOrConceal(userId);
            return tx.plain(userId);
        }
    }

    /** 未登録の同ドメイン *Service を呼ぶ Facade（R4 の陽性: tx 本体の表の登録漏れ）。 */
    static class UnlistedServiceFacade {
        private final FakeGate gate = new FakeGate();
        private final GoodTxService tx = new GoodTxService();

        public String op(Long userId) {
            gate.requireAdminOrConceal(userId);
            return tx.doWork(userId);
        }
    }

    /** throw 形を直接呼ぶ Facade（R6 の陽性）。 */
    static class ThrowingFacade {
        private final FakeAccessControl acs = new FakeAccessControl();
        private final GoodTxService tx = new GoodTxService();

        public String op(Long userId) {
            acs.checkAdminOrAbove(userId);
            return tx.doWork(userId);
        }
    }

    /** boolean 形だけを使う Facade（R6 の陰性）。 */
    static class BooleanFacade {
        private final FakeAccessControl acs = new FakeAccessControl();
        private final GoodTxService tx = new GoodTxService();

        public String op(Long userId) {
            if (!acs.isAdminOrAbove(userId)) {
                throw new IllegalStateException();
            }
            return tx.doWork(userId);
        }
    }

    /** 認可シグナル名の Facade（R7 の陽性）。 */
    static class BadAccessService {
        private final FakeGate gate = new FakeGate();
        private final GoodTxService tx = new GoodTxService();

        public String op(Long userId) {
            gate.requireAdminOrConceal(userId);
            return tx.doWork(userId);
        }
    }

    static class GoodController {
        private final GoodFacade facade = new GoodFacade();

        public String ep(Long userId) {
            return facade.op(userId);
        }
    }

    /** Facade を呼んだうえで tx 本体も直呼びする（R1 の陽性）。 */
    static class BypassController {
        private final GoodFacade facade = new GoodFacade();
        private final GoodTxService tx = new GoodTxService();

        public String ep(Long userId) {
            facade.op(userId);
            return tx.doWork(userId);
        }
    }

    /** Facade を呼ばない（R1 の陽性）。 */
    static class NoFacadeController {
        private final GoodTxService tx = new GoodTxService();
        private final FakeGate gate = new FakeGate();

        public String ep(Long userId) {
            gate.requireAdminOrConceal(userId);
            return tx.doWork(userId);
        }
    }

    static class AnnotatedController {
        private final GoodFacade facade = new GoodFacade();

        @AuthorizedInService
        public String ep(Long userId) {
            return facade.op(userId);
        }
    }

    static class SelfScopedController {
        private final GoodFacade facade = new GoodFacade();

        @SelfScopedEndpoint("合成: 自己スコープの印（R5 の陽性対照）")
        public String ep(Long userId) {
            return facade.op(userId);
        }
    }

    /** 登録 Facade を 2 メソッドから呼ぶ（完全性の陽性: 片方を登録しない）。ラムダ経由の呼び出しも含む。 */
    static class TwoMethodController {
        private final GoodFacade facade = new GoodFacade();

        public String ep(Long userId) {
            return facade.op(userId);
        }

        public String viaLambda(Long userId) {
            java.util.function.Supplier<String> s = () -> facade.op(userId);
            return s.get();
        }
    }

    /** 各 Facade を呼ぶだけの Controller（Facade 側の規則を試すための入口）。 */
    static class FacadeEntryController {
        private final GoodFacade good = new GoodFacade();
        private final PrivateAuthzFacade privateAuthz = new PrivateAuthzFacade();
        private final LambdaAuthzFacade lambdaAuthz = new LambdaAuthzFacade();
        private final HollowFacade hollow = new HollowFacade();
        private final ClassTxFacade classTx = new ClassTxFacade();
        private final MethodTxFacade methodTx = new MethodTxFacade();
        private final AuthzTxCallingFacade authzTx = new AuthzTxCallingFacade();
        private final UnlistedServiceFacade unlisted = new UnlistedServiceFacade();
        private final ThrowingFacade throwing = new ThrowingFacade();
        private final BooleanFacade bool = new BooleanFacade();
        private final BadAccessService badName = new BadAccessService();

        public String good(Long u) {
            return good.op(u);
        }

        public String privateAuthz(Long u) {
            return privateAuthz.op(u);
        }

        public String lambdaAuthz(Long u) {
            return lambdaAuthz.op(u);
        }

        public String hollow(Long u) {
            return hollow.op(u);
        }

        public String classTx(Long u) {
            return classTx.op(u);
        }

        public String methodTx(Long u) {
            return methodTx.op(u);
        }

        public String authzTx(Long u) {
            return authzTx.op(u);
        }

        public String authzTxPlain(Long u) {
            return authzTx.plainOp(u);
        }

        public String unlisted(Long u) {
            return unlisted.op(u);
        }

        public String throwing(Long u) {
            return throwing.op(u);
        }

        public String bool(Long u) {
            return bool.op(u);
        }

        public String badName(Long u) {
            return badName.op(u);
        }
    }

    /** 長短 2 経路が合流し、合流点の先で認可へ届く tx 本体（R4 の探索順依存の陽性）。長い経路（a→b→shared）を先に辿らせる。 */
    static class MergeAuthzTxService {
        private final FakeAccessControl acs = new FakeAccessControl();

        String doWork(Long id) {
            a(id);
            shared(id);
            return "m";
        }

        private void a(Long id) {
            b(id);
        }

        private void b(Long id) {
            shared(id);
        }

        private void shared(Long id) {
            s1(id);
        }

        private void s1(Long id) {
            s2(id);
        }

        private void s2(Long id) {
            acs.isAdminOrAbove(id);
        }
    }

    /** 同じ形で認可へ届かない tx 本体（陰性）。 */
    static class MergePlainTxService {
        String doWork(Long id) {
            a(id);
            shared(id);
            return "m";
        }

        private void a(Long id) {
            b(id);
        }

        private void b(Long id) {
            shared(id);
        }

        private void shared(Long id) {
            s1(id);
        }

        private void s1(Long id) {
            s2(id);
        }

        private void s2(Long id) {
        }
    }

    static class MergeAuthzFacade {
        private final FakeGate gate = new FakeGate();
        private final MergeAuthzTxService tx = new MergeAuthzTxService();

        public String op(Long userId) {
            gate.requireAdminOrConceal(userId);
            return tx.doWork(userId);
        }
    }

    static class MergePlainFacade {
        private final FakeGate gate = new FakeGate();
        private final MergePlainTxService tx = new MergePlainTxService();

        public String op(Long userId) {
            gate.requireAdminOrConceal(userId);
            return tx.doWork(userId);
        }
    }

    /** 認可へ届かない継承入口を持つ親（R3 の継承陽性）。 */
    static class HollowParent {
        private final GoodTxService tx = new GoodTxService();

        public String inherited(Long userId) {
            return tx.doWork(userId);
        }
    }

    static class InheritHollowFacade extends HollowParent {
        private final FakeGate gate = new FakeGate();

        public String op(Long userId) {
            gate.requireAdminOrConceal(userId);
            return "x";
        }
    }

    /** 認可へ届く継承入口を持つ親（R3 の継承陰性）。 */
    static class GoodParent {
        private final FakeGate gate = new FakeGate();
        private final GoodTxService tx = new GoodTxService();

        public String inherited(Long userId) {
            gate.requireAdminOrConceal(userId);
            return tx.doWork(userId);
        }
    }

    static class InheritGoodFacade extends GoodParent {
    }

    /** 認可なしの default を持つ interface（オーバーライド済みなら R3 は見ない）。 */
    interface HollowDefault {
        default String inherited(Long userId) {
            return "d" + userId;
        }
    }

    /** 認可なしの親 inherited() を、認可付きでオーバーライドする（R3 の陰性）。 */
    static class OverrideGoodFacade extends HollowParent {
        private final FakeGate gate = new FakeGate();

        @Override
        public String inherited(Long userId) {
            gate.requireAdminOrConceal(userId);
            return "o";
        }
    }

    /** 認可なしの interface default を、認可付きでオーバーライドする（R3 の陰性）。 */
    static class InterfaceOverrideFacade implements HollowDefault {
        private final FakeGate gate = new FakeGate();

        @Override
        public String inherited(Long userId) {
            gate.requireAdminOrConceal(userId);
            return "i";
        }
    }

    /** interface default をオーバーライドしない（R3 の陽性: 認可へ届かない default が残る）。 */
    static class InterfaceHollowFacade implements HollowDefault {
    }

    static class InheritEntryController {
        private final OverrideGoodFacade overrideGood = new OverrideGoodFacade();
        private final InterfaceOverrideFacade ifaceOverride = new InterfaceOverrideFacade();
        private final InterfaceHollowFacade ifaceHollow = new InterfaceHollowFacade();
        private final InheritHollowFacade hollow = new InheritHollowFacade();
        private final InheritGoodFacade good = new InheritGoodFacade();
        private final MergeAuthzFacade mergeAuthz = new MergeAuthzFacade();
        private final MergePlainFacade mergePlain = new MergePlainFacade();

        public String overrideInherited(Long u) {
            return overrideGood.inherited(u);
        }

        public String ifaceOverrideInherited(Long u) {
            return ifaceOverride.inherited(u);
        }

        public String ifaceHollowInherited(Long u) {
            return ifaceHollow.inherited(u);
        }

        public String hollowInherited(Long u) {
            return hollow.inherited(u);
        }

        public String goodInherited(Long u) {
            return good.inherited(u);
        }

        public String mergeAuthz(Long u) {
            return mergeAuthz.op(u);
        }

        public String mergePlain(Long u) {
            return mergePlain.op(u);
        }
    }

    private final JavaClasses CLASSES = new ClassFileImporter().importClasses(
            FakeAccessControl.class, FakeGate.class, GoodTxService.class, AuthzTxService.class,
            GoodFacade.class, PrivateAuthzFacade.class, LambdaAuthzFacade.class, HollowFacade.class,
            ClassTxFacade.class, MethodTxFacade.class, AuthzTxCallingFacade.class, UnlistedServiceFacade.class,
            ThrowingFacade.class, BooleanFacade.class, BadAccessService.class,
            GoodController.class, BypassController.class, NoFacadeController.class, AnnotatedController.class,
            SelfScopedController.class, TwoMethodController.class, FacadeEntryController.class,
            MergeAuthzTxService.class, MergePlainTxService.class, MergeAuthzFacade.class, MergePlainFacade.class,
            HollowParent.class, InheritHollowFacade.class, GoodParent.class, InheritGoodFacade.class,
            HollowDefault.class, OverrideGoodFacade.class, InterfaceOverrideFacade.class,
            InterfaceHollowFacade.class, InheritEntryController.class);

    private static String n(Class<?> c) {
        return c.getName();
    }

    private static Entry entry(Class<?> controller, String method, Class<?> facade) {
        return new Entry("合成", n(controller), method, n(facade));
    }

    private static TxBody cls(Class<?> c) {
        return new TxBody(n(c), TxMode.CLASS);
    }

    private static TxBody method(Class<?> c) {
        return new TxBody(n(c), TxMode.METHOD);
    }

    private static Rules rules(List<Entry> entries, Map<String, List<TxBody>> txBodies,
            Map<String, String> allowlist, Map<String, String> exempt) {
        return new Rules(entries, txBodies, Set.of(n(FakeAccessControl.class), n(FakeGate.class)),
                n(FakeAccessControl.class), allowlist, exempt);
    }

    private static Rules single(Class<?> controller, String method, Class<?> facade, TxBody... bodies) {
        return rules(List.of(entry(controller, method, facade)), Map.of(n(facade), List.of(bodies)), Map.of(), Map.of());
    }

    // ────────────────────────────────────────────────────────────
    // 正しい形は全規則を通る（陰性対照の総括）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("陰性: 正しい形（Controller → Facade（Gate）→ 認可なし tx 本体）は全規則を通る")
    void 正しい形は全規則を通る() {
        Rules r = single(GoodController.class, "ep", GoodFacade.class, cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.registryTargetsExist(CLASSES, r)).isEmpty();
        assertThat(AuthzTxFacadeRegistryArchTest.r1ControllerCallsFacadeNotTxBody(CLASSES, r)).isEmpty();
        assertThat(AuthzTxFacadeRegistryArchTest.r2FacadeHasNoTransactional(CLASSES, r)).isEmpty();
        assertThat(AuthzTxFacadeRegistryArchTest.r3FacadePublicMethodsReachAuthz(CLASSES, r)).isEmpty();
        assertThat(AuthzTxFacadeRegistryArchTest.r4TxBodiesDoNotReachAuthz(CLASSES, r)).isEmpty();
        assertThat(AuthzTxFacadeRegistryArchTest.r5NoAuthzMarkers(CLASSES, r)).isEmpty();
        assertThat(AuthzTxFacadeRegistryArchTest.r6ThrowFormOnlyInAllowlist(CLASSES, r)).isEmpty();
        assertThat(AuthzTxFacadeRegistryArchTest.r7FacadeNaming(r)).isEmpty();
        assertThat(AuthzTxFacadeRegistryArchTest.blankReasons(r)).isEmpty();
    }

    // ────────────────────────────────────────────────────────────
    // R1
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R1 陽性: Facade を呼んでも tx 本体を直呼びしていれば赤")
    void R1_tx本体の直呼びを検出する() {
        Rules r = single(BypassController.class, "ep", GoodFacade.class, cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r1ControllerCallsFacadeNotTxBody(CLASSES, r))
                .singleElement().asString().contains("tx 本体").contains("GoodTxService#doWork");
    }

    @Test
    @DisplayName("R1 陽性: 指定の Facade を呼ばなければ赤")
    void R1_Facadeを呼ばないことを検出する() {
        Rules r = single(NoFacadeController.class, "ep", GoodFacade.class, cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r1ControllerCallsFacadeNotTxBody(CLASSES, r))
                .anyMatch(v -> v.contains("を呼んでいない"))
                .anyMatch(v -> v.contains("tx 本体"));
    }

    // ────────────────────────────────────────────────────────────
    // R2
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R2 陽性: Facade のクラス・メソッドの @Transactional をそれぞれ検出する")
    void R2_transactionalを検出する() {
        Rules classTx = single(FacadeEntryController.class, "classTx", ClassTxFacade.class, cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r2FacadeHasNoTransactional(CLASSES, classTx))
                .singleElement().asString().contains("のクラスに @Transactional");
        Rules methodTx = single(FacadeEntryController.class, "methodTx", MethodTxFacade.class, cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r2FacadeHasNoTransactional(CLASSES, methodTx))
                .singleElement().asString().contains("op(").contains("@Transactional");
    }

    // ────────────────────────────────────────────────────────────
    // R3
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R3 陽性: 認可クラスへ届かない public メソッドを検出する")
    void R3_認可へ届かないメソッドを検出する() {
        Rules r = single(FacadeEntryController.class, "hollow", HollowFacade.class, cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r3FacadePublicMethodsReachAuthz(CLASSES, r))
                .singleElement().asString().contains("HollowFacade.op(").contains("認可クラスへ届かない");
    }

    @Test
    @DisplayName("R3 陰性: 同クラスの private メソッド経由・ラムダ経由の認可は届いたと数える")
    void R3_privateとラムダ経由は通る() {
        Rules priv = single(FacadeEntryController.class, "privateAuthz", PrivateAuthzFacade.class,
                cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r3FacadePublicMethodsReachAuthz(CLASSES, priv)).isEmpty();
        Rules lambda = single(FacadeEntryController.class, "lambdaAuthz", LambdaAuthzFacade.class,
                cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r3FacadePublicMethodsReachAuthz(CLASSES, lambda)).isEmpty();
    }

    // ────────────────────────────────────────────────────────────
    // R4
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R4 陽性: CLASS の tx 本体が認可クラスに依存していれば赤")
    void R4_CLASSの認可依存を検出する() {
        Rules r = single(FacadeEntryController.class, "authzTxPlain", AuthzTxCallingFacade.class,
                cls(AuthzTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r4TxBodiesDoNotReachAuthz(CLASSES, r))
                .anyMatch(v -> v.contains("AuthzTxService → ") && v.contains("FakeAccessControl"));
    }

    @Test
    @DisplayName("R4 陽性/陰性: METHOD は呼ばれるメソッドが認可へ届けば赤、届かなければ緑（同クラスに認可が残っていても）")
    void R4_METHODは呼ばれるメソッドだけを見る() {
        Rules reaching = single(FacadeEntryController.class, "authzTx", AuthzTxCallingFacade.class,
                method(AuthzTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r4TxBodiesDoNotReachAuthz(CLASSES, reaching))
                .singleElement().asString().contains("AuthzTxService.doWork(").contains("認可クラスへ届く");
        Rules plain = single(FacadeEntryController.class, "authzTxPlain", AuthzTxCallingFacade.class,
                method(AuthzTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r4TxBodiesDoNotReachAuthz(CLASSES, plain)).isEmpty();
    }

    @Test
    @DisplayName("R4 陽性/陰性: 長短の経路が合流しても、短い経路が認可へ届けば赤・届かなければ緑（走査順に依存しない）")
    void R4_長短経路の合流で最小深さを見る() {
        Rules reaching = single(InheritEntryController.class, "mergeAuthz", MergeAuthzFacade.class,
                method(MergeAuthzTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r4TxBodiesDoNotReachAuthz(CLASSES, reaching))
                .singleElement().asString().contains("MergeAuthzTxService.doWork(").contains("認可クラスへ届く");
        Rules plain = single(InheritEntryController.class, "mergePlain", MergePlainFacade.class,
                method(MergePlainTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r4TxBodiesDoNotReachAuthz(CLASSES, plain)).isEmpty();
    }

    @Test
    @DisplayName("R3 陽性/陰性: 親クラスの継承入口が認可へ届かなければ赤、届けば緑（Controller が実際に呼ぶ継承メソッドを見る）")
    void R3_継承入口を検査する() {
        Rules hollow = single(InheritEntryController.class, "hollowInherited", InheritHollowFacade.class,
                cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r3FacadePublicMethodsReachAuthz(CLASSES, hollow))
                .singleElement().asString().contains("HollowParent.inherited(").contains("認可クラスへ届かない");
        Rules good = single(InheritEntryController.class, "goodInherited", InheritGoodFacade.class,
                cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r3FacadePublicMethodsReachAuthz(CLASSES, good)).isEmpty();
    }

    @Test
    @DisplayName("R3 陰性: 認可なしの親 inherited() / interface default を子が認可付きでオーバーライドしていれば緑（隠された親宣言は見ない）")
    void R3_オーバーライド済みの親宣言は見ない() {
        Rules overrideGood = single(InheritEntryController.class, "overrideInherited", OverrideGoodFacade.class,
                cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r3FacadePublicMethodsReachAuthz(CLASSES, overrideGood)).isEmpty();
        Rules ifaceOverride = single(InheritEntryController.class, "ifaceOverrideInherited",
                InterfaceOverrideFacade.class, cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r3FacadePublicMethodsReachAuthz(CLASSES, ifaceOverride)).isEmpty();
    }

    @Test
    @DisplayName("R3 陽性: interface default を子がオーバーライドしていなければ、認可へ届かない default を赤にする")
    void R3_interfaceのdefaultが残れば赤() {
        Rules ifaceHollow = single(InheritEntryController.class, "ifaceHollowInherited",
                InterfaceHollowFacade.class, cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r3FacadePublicMethodsReachAuthz(CLASSES, ifaceHollow))
                .singleElement().asString().contains("HollowDefault.inherited(").contains("認可クラスへ届かない");
    }

    // 探索ロジック本体を、呼び先の列挙順を明示して検証する（ArchUnit の呼び出し集合の順序に依存しない）。
    // グラフ: doWork -> [a, shared] / a -> b -> shared -> s1 -> s2（s2 が認可を呼ぶ）。
    // 長経路 doWork→a→b→shared は深さ 3、短経路 doWork→shared は深さ 1。maxDepth=3 なら s2 は短経路でのみ届く。
    private static final Map<String, List<String>> LONG_FIRST = Map.of(
            "doWork", List.of("a", "shared"), "a", List.of("b"), "b", List.of("shared"),
            "shared", List.of("s1"), "s1", List.of("s2"), "s2", List.of());
    private static final Map<String, List<String>> SHORT_FIRST = Map.of(
            "doWork", List.of("shared", "a"), "a", List.of("b"), "b", List.of("shared"),
            "shared", List.of("s1"), "s1", List.of("s2"), "s2", List.of());

    /** 旧アルゴリズム（visited 共有の深さ優先）の再現。 */
    private static boolean legacyDfsReaches(String node, int depth, int maxDepth, java.util.Set<String> visited,
            Map<String, List<String>> graph) {
        if (node.equals("s2")) {
            return true;
        }
        if (depth == maxDepth) {
            return false;
        }
        for (String callee : graph.get(node)) {
            if (visited.add(callee) && legacyDfsReaches(callee, depth + 1, maxDepth, visited, graph)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("探索 対照: 長経路先行でも短経路先行でも、短い経路が認可へ届けば到達あり。旧アルゴリズム（visited 共有 DFS）は長経路先行で誤って未到達を返す")
    void 探索は呼び先の列挙順に依存しない() {
        for (Map<String, List<String>> graph : List.of(LONG_FIRST, SHORT_FIRST)) {
            assertThat(AuthzTxFacadeRegistryArchTest.<String>bfsReaches("doWork", 3, n -> n.equals("s2"), graph::get))
                    .isTrue();
            // 深さが足りなければ（maxDepth=2）どちらの順序でも未到達
            assertThat(AuthzTxFacadeRegistryArchTest.<String>bfsReaches("doWork", 2, n -> n.equals("s2"), graph::get))
                    .isFalse();
        }
        // 対照の実効性: 旧アルゴリズムは長経路先行で shared を深さ 3 で消費し、短経路を捨てて未到達と誤判定する
        assertThat(legacyDfsReaches("doWork", 0, 3, new java.util.HashSet<>(java.util.Set.of("doWork")), LONG_FIRST))
                .isFalse();
        assertThat(legacyDfsReaches("doWork", 0, 3, new java.util.HashSet<>(java.util.Set.of("doWork")), SHORT_FIRST))
                .isTrue();
    }

    @Test
    @DisplayName("R4 陽性: Facade が tx 本体の表に無い同ドメインの *Service を呼べば赤、tx 本体を 1 本も呼ばなくても赤")
    void R4_tx本体の表の登録漏れを検出する() {
        // UnlistedServiceFacade は GoodTxService を呼ぶが、tx 本体の表には AuthzTxService しか載せない
        Rules r = single(FacadeEntryController.class, "unlisted", UnlistedServiceFacade.class,
                method(AuthzTxService.class));
        List<String> violations = AuthzTxFacadeRegistryArchTest.r4TxBodiesDoNotReachAuthz(CLASSES, r);
        assertThat(violations).anyMatch(v -> v.contains("tx 本体の表に無い同ドメインの") && v.contains("GoodTxService"));
        assertThat(violations).anyMatch(v -> v.contains("tx 本体を呼んでいない"));
    }

    // ────────────────────────────────────────────────────────────
    // R5
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R5 陽性: 登録メソッドの @AuthorizedInService・@SelfScopedEndpoint を検出する")
    void R5_認可の印を検出する() {
        Rules annotated = single(AnnotatedController.class, "ep", GoodFacade.class, cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r5NoAuthzMarkers(CLASSES, annotated))
                .singleElement().asString().contains("@AuthorizedInService");
        Rules selfScoped = single(SelfScopedController.class, "ep", GoodFacade.class, cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r5NoAuthzMarkers(CLASSES, selfScoped))
                .singleElement().asString().contains("@SelfScopedEndpoint");
    }

    // ────────────────────────────────────────────────────────────
    // R6
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R6 陽性: Facade の throw 形（check*）の直接呼び出しを検出する（許可リストが空なら赤）")
    void R6_throw形を検出する() {
        Rules r = single(FacadeEntryController.class, "throwing", ThrowingFacade.class, cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r6ThrowFormOnlyInAllowlist(CLASSES, r))
                .singleElement().asString().startsWith(n(ThrowingFacade.class) + "#op ").contains("checkAdminOrAbove");
    }

    @Test
    @DisplayName("R6 陰性: 理由付きで許可リストに載せれば緑。boolean 形だけなら許可リスト無しで緑")
    void R6_許可リストとboolean形は通る() {
        Rules allowed = single(FacadeEntryController.class, "throwing", ThrowingFacade.class, cls(GoodTxService.class))
                .withThrowFormAllowlist(Map.of(n(ThrowingFacade.class) + "#op", "合成: 理由"));
        assertThat(AuthzTxFacadeRegistryArchTest.r6ThrowFormOnlyInAllowlist(CLASSES, allowed)).isEmpty();
        Rules bool = single(FacadeEntryController.class, "bool", BooleanFacade.class, cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r6ThrowFormOnlyInAllowlist(CLASSES, bool)).isEmpty();
    }

    @Test
    @DisplayName("R6 陽性: 使われなくなった許可リストの項目（陳腐化）を検出する")
    void R6_陳腐化した許可を検出する() {
        Rules stale = single(FacadeEntryController.class, "bool", BooleanFacade.class, cls(GoodTxService.class))
                .withThrowFormAllowlist(Map.of(n(BooleanFacade.class) + "#op", "合成: 理由"));
        assertThat(AuthzTxFacadeRegistryArchTest.r6ThrowFormOnlyInAllowlist(CLASSES, stale))
                .singleElement().asString().contains("許可リストにあるが throw 形を呼んでいない");
    }

    @Test
    @DisplayName("理由の必須: 許可リスト・除外表の理由が空（空白のみ）なら赤")
    void 理由が空なら赤() {
        Rules r = rules(List.of(), Map.of(), Map.of(n(ThrowingFacade.class) + "#op", " "),
                Map.of(n(GoodController.class) + "#ep", ""));
        assertThat(AuthzTxFacadeRegistryArchTest.blankReasons(r)).hasSize(2);
    }

    // ────────────────────────────────────────────────────────────
    // R7
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R7 陽性: Facade の名前が *AccessService なら赤")
    void R7_認可シグナル名を検出する() {
        Rules r = single(FacadeEntryController.class, "badName", BadAccessService.class, cls(GoodTxService.class));
        assertThat(AuthzTxFacadeRegistryArchTest.r7FacadeNaming(r)).singleElement().asString().contains("BadAccessService");
    }

    // ────────────────────────────────────────────────────────────
    // 完全性
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("完全性 陽性: 登録 Facade を呼ぶのに登録表に無い Controller メソッド（ラムダ経由を含む）を検出する")
    void 完全性_登録漏れを検出する() {
        Rules r = rules(List.of(entry(TwoMethodController.class, "ep", GoodFacade.class)),
                Map.of(n(GoodFacade.class), List.of(cls(GoodTxService.class))), Map.of(), Map.of());
        List<String> violations = AuthzTxFacadeRegistryArchTest.completeness(CLASSES, r);
        assertThat(violations).anyMatch(v -> v.startsWith(n(TwoMethodController.class) + "#viaLambda "));
        // GoodFacade を呼ぶ他の合成 Controller も漏れとして出る（列挙が Controller 全体を見ている）
        assertThat(violations).anyMatch(v -> v.startsWith(n(GoodController.class) + "#ep "));
        assertThat(violations).noneMatch(v -> v.startsWith(n(TwoMethodController.class) + "#ep "));
    }

    @Test
    @DisplayName("完全性 陰性/陽性: 理由付きの除外表で緑になり、Facade を呼ばない除外項目は陳腐化として赤")
    void 完全性_除外表() {
        Map<String, String> exempt = new java.util.LinkedHashMap<>();
        for (String caller : AuthzTxFacadeRegistryArchTest.completeness(CLASSES, rules(List.of(),
                Map.of(n(GoodFacade.class), List.of(cls(GoodTxService.class))), Map.of(), Map.of()))) {
            exempt.put(caller.substring(0, caller.indexOf(' ')), "合成: 理由");
        }
        assertThat(exempt).isNotEmpty();
        Rules ok = rules(List.of(), Map.of(n(GoodFacade.class), List.of(cls(GoodTxService.class))), Map.of(), exempt);
        assertThat(AuthzTxFacadeRegistryArchTest.completeness(CLASSES, ok)).isEmpty();

        exempt.put(n(NoFacadeController.class) + "#ep", "合成: 理由");
        Rules stale = rules(List.of(), Map.of(n(GoodFacade.class), List.of(cls(GoodTxService.class))), Map.of(), exempt);
        assertThat(AuthzTxFacadeRegistryArchTest.completeness(CLASSES, stale))
                .singleElement().asString().contains("除外表にあるが登録 Facade を呼んでいない");
    }

    @Test
    @DisplayName("実在 陽性: 登録表のメソッド・Facade・tx 本体・許可リストの項目が実在しなければ赤")
    void 実在しない登録を検出する() {
        Rules r = rules(List.of(entry(GoodController.class, "noSuchMethod", GoodFacade.class),
                        new Entry("合成", "com.example.NoSuchController", "ep", n(GoodFacade.class))),
                Map.of(n(GoodFacade.class), List.of(new TxBody("com.example.NoSuchService", TxMode.CLASS)),
                        "com.example.NoSuchFacade", List.of()),
                Map.of(n(ThrowingFacade.class) + "#noSuch", "合成: 理由"),
                Map.of("com.example.NoSuchController#ep", "合成: 理由"));
        assertThat(AuthzTxFacadeRegistryArchTest.registryTargetsExist(CLASSES, r))
                .anyMatch(v -> v.contains("#noSuchMethod: メソッドが無い"))
                .anyMatch(v -> v.contains("NoSuchController#ep: Controller が無い"))
                .anyMatch(v -> v.contains("NoSuchFacade: Facade が無い"))
                .anyMatch(v -> v.contains("NoSuchService: tx 本体が無い"))
                .anyMatch(v -> v.contains("#noSuch: R6 の許可リストの項目が実在しない"))
                .anyMatch(v -> v.contains("除外表の項目が実在しない"));
    }
}
