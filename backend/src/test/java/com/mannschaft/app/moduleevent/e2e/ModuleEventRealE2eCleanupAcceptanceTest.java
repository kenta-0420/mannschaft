package com.mannschaft.app.moduleevent.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** JDK-only と同じ M21 acceptance 試練を通常 JUnit 経路でも実行する。 */
class ModuleEventRealE2eCleanupAcceptanceTest {
    @Test
    @Timeout(30)
    void M21後始末の有限受け入れ条件を満たす() throws Throwable {
        ModuleEventRealE2eCleanupTest.main(new String[0]);
    }
}
