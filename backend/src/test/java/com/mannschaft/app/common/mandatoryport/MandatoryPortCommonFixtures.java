package com.mannschaft.app.common.mandatoryport;

import com.mannschaft.app.resident.mandatoryport.MandatoryPortResidentFixtures;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-3P（{@code CrossDomainMandatoryPropagationArchTest}）の検体。共有パッケージ {@code common} 側（すべて陰性）。
 * 実行はしない（ArchUnit が読むだけ）。
 */
public final class MandatoryPortCommonFixtures {

    private MandatoryPortCommonFixtures() {
    }

    /** common が宣言したポート（実装は D-3P-2 の対象外）。 */
    public interface CommonLockPort {
        void lock(Long id);
    }

    /** common の MANDATORY（呼ばれても D-3P-1 の対象外）。 */
    public static class CommonMandatoryService {
        @Transactional(propagation = Propagation.MANDATORY)
        public void join() {
        }
    }

    /** common から別ドメインの MANDATORY を呼ぶ（呼ぶ側が common なので D-3P-1 の対象外）。 */
    public static class CommonCaller {
        MandatoryPortResidentFixtures.MethodMandatoryService service;

        public void call() {
            service.join();
        }
    }
}
