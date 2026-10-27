package com.mannschaft.app.template.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** 指定キーのみ更新する要求。空配列は認可後のno-opとして扱う。 */
public record UpdateFeatureDisplayPoliciesRequest(@NotNull List<@NotNull @Valid FeatureDisplayPolicyItem> features) { }
