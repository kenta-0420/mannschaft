package com.mannschaft.app.visibility.dto;

import com.mannschaft.app.visibility.VisibilityTemplateRuleType;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * 公開範囲テンプレートルールのリクエスト DTO。
 */
@Getter
@Builder
@Jacksonized
public class RuleRequest {

    /** ルール種別（必須） */
    @NotNull(message = "ruleType は必須です")
    private final VisibilityTemplateRuleType ruleType;

    /** ルール対象の数値ID（任意） */
    private final Long ruleTargetId;

    /** ルール対象のテキスト値（任意） */
    private final String ruleTargetText;
}
