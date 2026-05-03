package com.mannschaft.app.survey.repository;

import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import org.springframework.data.jpa.domain.Specification;

/** F14.1 代理入力: SurveyResponse 集計分離用 Specification */
public final class SurveyResponseSpecs {

    private SurveyResponseSpecs() {}

    /** 本人入力のみ（is_proxy_input = false）— 回答率・定足数の正規集計に使用 */
    public static Specification<SurveyResponseEntity> byPersonOnly() {
        return (root, query, cb) -> cb.isFalse(root.get("isProxyInput"));
    }

    /** 代理入力を含む全件（is_proxy_input IN (true, false)）— 運営の参考値に使用 */
    public static Specification<SurveyResponseEntity> includingProxy() {
        return (root, query, cb) -> cb.conjunction();
    }
}
