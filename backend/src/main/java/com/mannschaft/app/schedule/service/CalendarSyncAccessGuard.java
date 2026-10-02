package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.schedule.GoogleCalendarErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * カレンダー同期設定（Google Calendar 連携）のスコープ認可を一元化するガード。
 *
 * <p>チーム／組織スコープの同期トグルは、<b>呼び出しユーザーが当該スコープの
 * アクティブメンバーである場合にのみ</b>実行できる。判定は F00.5 メンバーシップ基盤
 * （{@link MembershipService#isActiveMember}）で行い、サポータを含む全 RoleKind の
 * アクティブメンバーシップを所属とみなす。</p>
 *
 * <p>拒否時は存在秘匿のため {@link GoogleCalendarErrorCode#CALENDAR_SYNC_SCOPE_NOT_FOUND}
 * （→404）へ写像する。403 と 404 を撃ち分けると、スコープ ID の実在有無が応答から読み取れる
 * ため、権限不足と不存在を同一の応答へ畳む。</p>
 */
@Service
@RequiredArgsConstructor
public class CalendarSyncAccessGuard {

    private final MembershipService membershipService;

    /**
     * 呼び出しユーザーが当該スコープのアクティブメンバーであることを保証する。
     *
     * @param userId    呼び出しユーザー ID
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープ ID
     * @throws BusinessException アクティブメンバーでない場合
     *                           （{@link GoogleCalendarErrorCode#CALENDAR_SYNC_SCOPE_NOT_FOUND}）
     */
    public void requireActiveScopeMember(Long userId, ScopeType scopeType, Long scopeId) {
        if (!membershipService.isActiveMember(userId, scopeType, scopeId)) {
            throw new BusinessException(GoogleCalendarErrorCode.CALENDAR_SYNC_SCOPE_NOT_FOUND);
        }
    }
}
