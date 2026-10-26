package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 時給（金銭情報）に対する per-scope 認可の<b>唯一の判定箇所</b>（トランザクションの外）。
 *
 * <p>時給の参照・登録（{@link ShiftHourlyRateService}）と削除（{@link ShiftHourlyRateDeletionFacade}）は
 * すべてここを通る。判定が複数箇所に分かれて片方だけ直される事故を防ぐため、判定を複製してはならない。
 * 本クラスは {@code @Transactional} を付けない（クラスにもメソッドにも）。</p>
 *
 * <p>参照・削除の判定順:</p>
 * <ol>
 *   <li>SYSTEM_ADMIN は短絡的に許可</li>
 *   <li>対象が本人 — 当該チームのメンバーであることを要求</li>
 *   <li>対象が他メンバー — 呼び出し元が当該チームの ADMIN/DEPUTY_ADMIN であること、
 *       かつ対象ユーザーも当該チームのメンバーであることを要求（対象側 BOLA 封鎖）</li>
 * </ol>
 * <p>登録・訂正は {@link #checkHourlyRateWriteAccess} で管理者限定とする。</p>
 */
@Component
@RequiredArgsConstructor
public class ShiftHourlyRateAuthorizer {

    private static final String TEAM = "TEAM";

    private final AccessControlService accessControlService;

    /**
     * @param currentUserId 操作ユーザーID
     * @param targetUserId  時給の対象ユーザーID
     * @param teamId        チームID
     * @throws com.mannschaft.app.common.BusinessException 権限がない場合（COMMON_002 / 403）
     */
    public void checkHourlyRateAccess(Long currentUserId, Long targetUserId, Long teamId) {
        if (accessControlService.isSystemAdmin(currentUserId)) {
            return;
        }
        if (currentUserId.equals(targetUserId)) {
            accessControlService.checkMembership(currentUserId, teamId, TEAM);
            return;
        }
        accessControlService.checkAdminOrAbove(currentUserId, teamId, TEAM);
        accessControlService.checkMembership(targetUserId, teamId, TEAM);
    }

    /**
     * 時給の登録・訂正を、当該チームの ADMIN/DEPUTY_ADMIN に限定する。
     * SYSTEM_ADMIN の短絡許可と対象ユーザーの所属確認は既存の認可定義に従う。
     *
     * @param currentUserId 操作ユーザーID
     * @param targetUserId  時給の対象ユーザーID
     * @param teamId        チームID
     * @throws com.mannschaft.app.common.BusinessException 権限がない場合（COMMON_002 / 403）
     */
    public void checkHourlyRateWriteAccess(Long currentUserId, Long targetUserId, Long teamId) {
        if (accessControlService.isSystemAdmin(currentUserId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(currentUserId, teamId, TEAM);
        accessControlService.checkMembership(targetUserId, teamId, TEAM);
    }
}
