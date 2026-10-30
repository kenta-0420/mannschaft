package com.mannschaft.app.moduleevent.api;

import com.mannschaft.app.moduleevent.ModuleEventOperations;
import org.springframework.stereotype.Component;

/** F(c)失敗の明示計数窓口。非TXのfinal facadeから内部の別proxyへ委譲する。 */
@Component
public final class ModuleEventOperationsApi {
    private final ModuleEventOperations operations;

    public ModuleEventOperationsApi(ModuleEventOperations operations) {
        this.operations = operations;
    }

    public void recordPatternFCompletionFailure() {
        operations.recordPatternFCompletionFailure();
    }
}
