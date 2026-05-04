package com.mannschaft.app.circulation.repository;

import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import org.springframework.data.jpa.domain.Specification;

/**
 * 回覧受信者の JPA Specification。代理確認除外の集計に使用する。
 */
public class CirculationRecipientSpecs {

    private CirculationRecipientSpecs() {}

    /** 本人押印のみ（代理確認を除外）。既読率計算に使用する。 */
    public static Specification<CirculationRecipientEntity> byPersonOnly() {
        return (root, query, cb) -> cb.isFalse(root.get("isProxyConfirmed"));
    }

    /** 代理確認を含む全件。監査・管理画面用。 */
    public static Specification<CirculationRecipientEntity> includingProxy() {
        return (root, query, cb) -> cb.conjunction();
    }
}
