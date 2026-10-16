package com.mannschaft.app.loyalty.dto;

import io.swagger.v3.oas.annotations.media.Schema;


/** チームポイントの業務契約。 */
@Schema(name = "LoyaltySelfAwardBootstrap")
public record SelfAwardBootstrap(
        SelfAwardPreview preview,
        AccountDetail account) {
}
