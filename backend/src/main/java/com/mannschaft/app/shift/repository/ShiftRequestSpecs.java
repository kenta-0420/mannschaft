package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import org.springframework.data.jpa.domain.Specification;

/**
 * シフト希望の JPA Specification。代理入力集計汚染防止のため、
 * リポジトリ操作は必ずこのクラス経由で行う。
 */
public class ShiftRequestSpecs {

    private ShiftRequestSpecs() {}

    /** 本人入力のみ（代理入力を除外）。統計・集計に使用する。 */
    public static Specification<ShiftRequestEntity> byPersonOnly() {
        return (root, query, cb) -> cb.equal(root.get("isProxyInput"), false);
    }

    /** 代理入力を含む全件。監査・管理画面用。 */
    public static Specification<ShiftRequestEntity> includingProxy() {
        return (root, query, cb) -> cb.conjunction();
    }
}
