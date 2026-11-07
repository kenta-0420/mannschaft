package com.mannschaft.modulithfixture.alpha.api;

import com.mannschaft.modulithfixture.beta.internal.BetaInternal;

/** 他モジュールの内部型を参照し、循環も作る違反検体。 */
public final class ApiType {
    private final BetaInternal dependency;

    public ApiType(BetaInternal dependency) {
        this.dependency = dependency;
    }
}
