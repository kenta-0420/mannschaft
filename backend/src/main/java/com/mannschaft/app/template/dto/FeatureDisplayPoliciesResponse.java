package com.mannschaft.app.template.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/** カタログ全件と未保存キーのtrue/trueを合成した応答。更新者情報は公開しない。 */
public record FeatureDisplayPoliciesResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FeatureDisplayPolicyItem> features) { }
