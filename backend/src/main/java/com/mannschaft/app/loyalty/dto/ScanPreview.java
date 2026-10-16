package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;


/** チームポイントの業務契約。 */
@Schema(name = "LoyaltyScanPreview")
public record ScanPreview(
        String displayName,
        int balance,
        RuleResponse rule,
        long programVersion,
        boolean isEligibleForAward) {
}
