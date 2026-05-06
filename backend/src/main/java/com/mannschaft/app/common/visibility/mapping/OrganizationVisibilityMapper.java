package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.organization.entity.OrganizationEntity;

/**
 * {@link OrganizationEntity.Visibility} を {@link StandardVisibility} に正規化する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表完全一致。
 *
 * <p><strong>マッピング方針</strong> (Phase D-δ 設計決定):</p>
 * <ul>
 *   <li>{@link OrganizationEntity.Visibility#PUBLIC} →
 *       {@link StandardVisibility#PUBLIC}（誰でも閲覧可）</li>
 *   <li>{@link OrganizationEntity.Visibility#PRIVATE} →
 *       {@link StandardVisibility#ADMINS_ONLY}
 *       （組織は PUBLIC/PRIVATE の 2 値のみで MEMBERS_ONLY 相当の中間概念を持たないため、
 *       PRIVATE は最も制限的な ADMINS_ONLY にマッピングし誤公開リスクを最小化する）</li>
 * </ul>
 */
public final class OrganizationVisibilityMapper {

    private OrganizationVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link OrganizationEntity.Visibility} を共通の {@link StandardVisibility} に写像する。
     *
     * @param v 機能側 enum (non-null)
     * @return 対応する StandardVisibility 値
     */
    public static StandardVisibility toStandard(OrganizationEntity.Visibility v) {
        return switch (v) {
            case PUBLIC -> StandardVisibility.PUBLIC;
            // 組織は PUBLIC/PRIVATE の 2 値のみで中間概念を持たない。
            // PRIVATE は保守的に ADMINS_ONLY にマッピングし、誤公開リスクを最小化する。
            case PRIVATE -> StandardVisibility.ADMINS_ONLY;
        };
    }
}
