package com.mannschaft.app.visibility.dto;

import com.mannschaft.app.visibility.VisibilityTemplateRuleType;
import lombok.Builder;
import lombok.Getter;

/**
 * 公開範囲テンプレートルールのレスポンス DTO。
 */
@Getter
@Builder
public class RuleResponse {

    /** ルール ID */
    private final Long id;

    /** ルール種別 */
    private final VisibilityTemplateRuleType ruleType;

    /** ルール対象の数値ID */
    private final Long ruleTargetId;

    /** ルール対象のテキスト値 */
    private final String ruleTargetText;

    /** 表示順序 */
    private final int sortOrder;
}
