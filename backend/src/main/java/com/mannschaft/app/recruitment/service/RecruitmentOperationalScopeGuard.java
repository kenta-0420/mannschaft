package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;

/**
 * 主体別管理市 Phase 2 の運用経路を TEAM / ORGANIZATION に閉じる共通ガード。
 *
 * <p>PERSONAL の札は現在 DRAFT 専用であり、個人専用の作成・編集・取消以外の既存募集運用
 * （応募、参加者管理、最終認証、決済関連）へ到達させない。未知の scope も
 * fail-closed で存在秘匿する。</p>
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
