package com.mannschaft.app.template.dto;

import java.util.List;

/** カタログ全件と未保存キーのtrue/trueを合成した応答。更新者情報は公開しない。 */
public record FeatureDisplayPoliciesResponse(List<FeatureDisplayPolicyItem> features) { }
