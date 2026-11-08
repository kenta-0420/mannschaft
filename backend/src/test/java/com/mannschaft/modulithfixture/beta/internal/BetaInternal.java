package com.mannschaft.modulithfixture.beta.internal;

import com.mannschaft.modulithfixture.alpha.api.ApiType;

/** 公開窓口を参照し返す向きの依存で循環を完成する検体。 */
public final class BetaInternal {
    private final ApiType dependency;

    public BetaInternal(ApiType dependency) {
        this.dependency = dependency;
    }
}
