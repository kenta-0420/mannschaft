package com.mannschaft.app.parking.repository;

import com.mannschaft.app.parking.entity.ParkingApplicationEntity;
import org.springframework.data.jpa.domain.Specification;

/**
 * 駐車場申請の JPA Specification。代理入力集計汚染防止のため、
 * リポジトリ操作は必ずこのクラス経由で行う。
 */
public class ParkingApplicationSpecs {

    private ParkingApplicationSpecs() {}

    /** 本人入力のみ（代理入力を除外）。統計・集計に使用する。 */
    public static Specification<ParkingApplicationEntity> byPersonOnly() {
        return (root, query, cb) -> cb.isFalse(root.get("isProxyInput"));
    }

    /** 代理入力を含む全件。監査・管理画面用。 */
    public static Specification<ParkingApplicationEntity> includingProxy() {
        return (root, query, cb) -> cb.conjunction();
    }
}
