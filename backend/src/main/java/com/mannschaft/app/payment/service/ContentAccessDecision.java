package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.dto.GateCheckResponse;

/** 可視性判定と会費ゲートを独立軸として合成する純粋関数。 */
public final class ContentAccessDecision {

    private ContentAccessDecision() {
    }

    /**
     * 可視性を先に評価し、課金ゲートとAND合成する。
     * 不可視は常にHIDDEN、未払いで未充足ゲートのtitleHiddenだけがHIDDENを決める。
     */
    public static ContentAccessState resolve(boolean visibilityAllowed, GateCheckResponse gate) {
        if (!visibilityAllowed || gate == null) {
            return ContentAccessState.HIDDEN;
        }
        if (gate.isAccessible()) {
            return ContentAccessState.FULL;
        }
        return gate.isTitleHidden() ? ContentAccessState.HIDDEN : ContentAccessState.LOCKED;
    }
}
