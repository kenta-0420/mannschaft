package com.mannschaft.app.resident.mandatoryport;

import com.mannschaft.app.common.mandatoryport.MandatoryPortCommonFixtures;
import com.mannschaft.app.membership.mandatoryport.MandatoryPortMembershipFixtures;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-3P（{@code CrossDomainMandatoryPropagationArchTest}）の検体。ドメイン {@code resident} 側。
 *
 * <p>呼ばれる側（MANDATORY の宣言の仕方を変えたもの）と、逆向きポートの実装（D-3P-2 の陽性・陰性）を置く。
 * 実行はしない（ArchUnit が読むだけ）。</p>
 */
public final class MandatoryPortResidentFixtures {

    private MandatoryPortResidentFixtures() {
    }

    // =====================================================================
    // D-3P-1 の呼ばれる側
    // =====================================================================

    /** メソッドに MANDATORY を宣言。REQUIRED と無印のメソッドも持つ。 */
    public static class MethodMandatoryService {
        @Transactional(propagation = Propagation.MANDATORY)
        public void join() {
        }

        @Transactional
        public void required() {
        }

        public void plain() {
        }
    }

    /** クラスに MANDATORY を宣言。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public static class ClassMandatoryService {
        public void join() {
        }
    }

    /** 親クラスに MANDATORY を宣言（子は宣言を継承する）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public static class MandatoryBase {
        public void inheritedJoin() {
        }
    }

    /** 宣言を継承するだけの子。 */
    public static class InheritingService extends MandatoryBase {
    }

    /** interface のメソッドに MANDATORY を宣言。 */
    public interface DeclaredMandatoryPort {
        @Transactional(propagation = Propagation.MANDATORY)
        void ifaceJoin();
    }

    /** interface の宣言を継承する実装（実装自身は無印）。 */
    public static class DeclaredMandatoryPortImpl implements DeclaredMandatoryPort {
        @Override
        public void ifaceJoin() {
        }
    }

    /** interface のメソッドに MANDATORY を宣言（実装クラスの REQUIRED と競合させる検体用）。 */
    public interface ConflictMandatoryPort {
        @Transactional(propagation = Propagation.MANDATORY)
        void conflictJoin();
    }

    /**
     * 実装メソッドは無印、実装クラスは REQUIRED、interface のメソッドは MANDATORY。Spring は実装メソッドの階層
     * （interface のメソッドを含む）をクラスより先に探すので、実効値は MANDATORY。
     */
    @Transactional
    public static class InterfaceMandatoryOverClassRequiredService implements ConflictMandatoryPort {
        @Override
        public void conflictJoin() {
        }
    }

    /** 同じドメインからの呼び出し（D-3P-1 の陰性）。 */
    public static class SameDomainCaller {
        MethodMandatoryService service;

        public void call() {
            service.join();
        }
    }

    // =====================================================================
    // D-3P-2 の実装側
    // =====================================================================

    /** 陽性: membership のポートをメソッドの MANDATORY で実装。 */
    public static class MethodLevelReversePortAdapter implements MandatoryPortMembershipFixtures.MembershipLockPort {
        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public void lock(Long id) {
        }
    }

    /** 陽性: membership のポートをクラスの MANDATORY で実装。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public static class ClassLevelReversePortAdapter implements MandatoryPortMembershipFixtures.MembershipLockPort {
        @Override
        public void lock(Long id) {
        }
    }

    /** 親クラスが MANDATORY の {@code lock} を持つ。 */
    public static class MandatoryLockBase {
        @Transactional(propagation = Propagation.MANDATORY)
        public void lock(Long id) {
        }
    }

    /** 陽性: 親の MANDATORY 実装を継承して membership のポートを満たす。 */
    public static class InheritedReversePortAdapter extends MandatoryLockBase
            implements MandatoryPortMembershipFixtures.MembershipLockPort {
    }

    /**
     * 陽性: membership のポートのメソッドが MANDATORY、実装クラスが REQUIRED、実装メソッドは無印。
     * Spring はポートのメソッドの宣言をクラスの宣言より先に拾うので、実効値は MANDATORY。
     */
    @Transactional
    public static class InterfaceMandatoryOverClassRequiredAdapter
            implements MandatoryPortMembershipFixtures.MembershipMandatoryLockPort {
        @Override
        public void lock(Long id) {
        }
    }

    /** 陰性: ポートのメソッドは MANDATORY だが、実装メソッドに REQUIRED を明示（実装メソッドが最初に当たる）。 */
    public static class MethodRequiredOverInterfaceMandatoryAdapter
            implements MandatoryPortMembershipFixtures.MembershipMandatoryLockPort {
        @Override
        @Transactional
        public void lock(Long id) {
        }
    }

    /** 陰性: REQUIRED で実装。 */
    public static class RequiredReversePortAdapter implements MandatoryPortMembershipFixtures.MembershipLockPort {
        @Override
        @Transactional
        public void lock(Long id) {
        }
    }

    /** 陰性: common のポートを MANDATORY で実装。 */
    public static class CommonPortAdapter implements MandatoryPortCommonFixtures.CommonLockPort {
        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public void lock(Long id) {
        }
    }

    /** 同じドメインのポート。 */
    public interface ResidentLockPort {
        void lock(Long id);
    }

    /** 陰性: 同じドメインのポートを MANDATORY で実装。 */
    public static class SameDomainPortAdapter implements ResidentLockPort {
        @Override
        @Transactional(propagation = Propagation.MANDATORY)
        public void lock(Long id) {
        }
    }
}
