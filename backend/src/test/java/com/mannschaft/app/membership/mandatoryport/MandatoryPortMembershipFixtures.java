package com.mannschaft.app.membership.mandatoryport;

import com.mannschaft.app.common.mandatoryport.MandatoryPortCommonFixtures;
import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.outbox.NotificationOutboxIngestService;
import com.mannschaft.app.resident.mandatoryport.MandatoryPortResidentFixtures;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-3P（{@code CrossDomainMandatoryPropagationArchTest}）の検体。ドメイン {@code membership} 側。
 *
 * <p>別ドメイン（resident）の MANDATORY を呼ぶ側（D-3P-1）、逆向きポートの宣言（D-3P-2）、
 * 通知ドメインの tx 参加型の入口への依存（D-3P-3）を置く。実行はしない（ArchUnit が読むだけ）。</p>
 */
public final class MandatoryPortMembershipFixtures {

    private MandatoryPortMembershipFixtures() {
    }

    /** membership が宣言したポート（resident が実装すると逆向き）。 */
    public interface MembershipLockPort {
        void lock(Long id);
    }

    /** D-3P-1: resident の各種メソッドを呼ぶ。 */
    public static class CrossDomainCaller {
        MandatoryPortResidentFixtures.MethodMandatoryService methodService;
        MandatoryPortResidentFixtures.ClassMandatoryService classService;
        MandatoryPortResidentFixtures.InheritingService inheritingService;
        MandatoryPortResidentFixtures.DeclaredMandatoryPortImpl declaredPortImpl;
        MandatoryPortCommonFixtures.CommonMandatoryService commonService;
        ApplicationEventPublisher publisher;

        /** 陽性: メソッドの MANDATORY。 */
        public void callMethodLevel() {
            methodService.join();
        }

        /** 陽性: クラスの MANDATORY。 */
        public void callClassLevel() {
            classService.join();
        }

        /** 陽性: 親クラスから継承した MANDATORY。 */
        public void callInherited() {
            inheritingService.inheritedJoin();
        }

        /** 陽性: interface のメソッドで宣言された MANDATORY。 */
        public void callInterfaceDeclared() {
            declaredPortImpl.ifaceJoin();
        }

        /** 陰性: REQUIRED。 */
        public void callRequired() {
            methodService.required();
        }

        /** 陰性: 無印。 */
        public void callPlain() {
            methodService.plain();
        }

        /** 陰性: common の MANDATORY。 */
        public void callCommon() {
            commonService.join();
        }

        /** 陰性: イベント経由（直接の呼び出しではない）。 */
        public void publishEvent() {
            publisher.publishEvent(new Object());
        }
    }

    /** D-3P-3: 通知ドメインの fan-out 登録口を呼ぶ。 */
    public static class FanoutEnqueuer {
        NotificationFanoutJobService fanoutJobService;

        /** 陽性: 呼び出し側 tx に参加する登録。 */
        public void enqueueInCallerTransaction(FanoutEnqueueCommand command) {
            fanoutJobService.enqueueInCurrentTransaction(command);
        }

        /** 陽性: コミット後に通知ドメイン tx で登録（outbox を経ない登録）。 */
        public void enqueueInOwn(FanoutEnqueueCommand command) {
            fanoutJobService.enqueueInOwnTransaction(command);
        }

        /** 陰性: 独立コミット（REQUIRES_NEW）の既存の enqueue。 */
        public void enqueueIsolated() {
            fanoutJobService.enqueue(null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /** D-3P-3 陽性: 取り込みサービスへの依存。 */
    public static class IngestDependent {
        NotificationOutboxIngestService ingestService;
    }
}
