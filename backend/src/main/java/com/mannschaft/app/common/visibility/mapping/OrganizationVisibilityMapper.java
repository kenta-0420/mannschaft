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
 *       {@link StandardVisibility#SCOPE_AFFILIATED}
 *       （外部非公開・組織メンバーは閲覧可。非メンバーには非公開となる）</li>
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
            // PRIVATE は「外部非公開・組織メンバーは閲覧可」を意味する。
            // SCOPE_AFFILIATED にマッピングすることで、メンバーは自組織を閲覧でき、非メンバーには非公開となる。
            // 挙動不変・名称正準化（W3）: SCOPE_AFFILIATED = isMemberOf = 旧 MEMBERS_ONLY と同一判定。
            case PRIVATE -> StandardVisibility.SCOPE_AFFILIATED;
        };
    }
}
