package com.mannschaft.app.social.announcement;

import org.springframework.data.jpa.domain.Specification;

/**
 * お知らせ既読の JPA Specification。代理確認を除いた本人既読のみを集計する用途で使用する。
 */
public class AnnouncementReadSpecs {

    private AnnouncementReadSpecs() {}

    /** 本人既読のみ（代理確認を除外）。既読率計算に使用する。 */
    public static Specification<AnnouncementReadStatusEntity> personReadOnly() {
        return (root, query, cb) -> cb.isFalse(root.get("isProxyConfirmed"));
    }

    /** 代理確認を含む全件。監査・管理画面用。 */
    public static Specification<AnnouncementReadStatusEntity> includingProxy() {
        return (root, query, cb) -> cb.conjunction();
    }
}
