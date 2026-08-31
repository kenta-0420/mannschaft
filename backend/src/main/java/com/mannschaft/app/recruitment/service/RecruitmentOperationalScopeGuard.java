package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;

/**
 * TEAM / ORGANIZATION 専用の既存管理経路を保護する共通ガード。
 *
 * <p>PERSONAL の公開・応募・取消・最終認証は個人対応済みの経路で扱う。一方、管理者向け
 * 参加者管理・出席記録・汎用配信設定など、組織ロールを前提にする入口ではこのガードを使い、
 * PERSONAL を存在秘匿する。</p>
 */
public final class RecruitmentOperationalScopeGuard {

    private RecruitmentOperationalScopeGuard() {
    }

    public static void requireTeamOrOrganization(RecruitmentListingEntity listing) {
        if (listing.getScopeType() != RecruitmentScopeType.TEAM
                && listing.getScopeType() != RecruitmentScopeType.ORGANIZATION) {
            throw new BusinessException(MarketErrorCode.LISTING_NOT_FOUND);
        }
    }

    /** PUBLIC 化・配信対象設定は PERSONAL の DRAFT 限定を明示して拒否する。 */
    public static void requireVisibilityConfigurable(RecruitmentListingEntity listing) {
        if (listing.getScopeType() != RecruitmentScopeType.TEAM
                && listing.getScopeType() != RecruitmentScopeType.ORGANIZATION) {
            throw new BusinessException(MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);
        }
    }
}
