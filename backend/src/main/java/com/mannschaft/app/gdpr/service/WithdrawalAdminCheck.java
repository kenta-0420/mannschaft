package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F12.3 退会時管理者チェックサービス。
 * 唯一のSYSTEM_ADMINが退会しようとする場合に例外をスローする。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalAdminCheck {

    private final AccessControlService accessControlService;
    private final UserRoleRepository userRoleRepository;

    /**
     * SYSTEM_ADMINが退会可能かどうかをチェックする。
     * 唯一のSYSTEM_ADMINが退会しようとする場合はGDPR_006例外をスロー。
     *
     * @param userId 退会対象ユーザーID
     * @throws BusinessException GDPR_006: 唯一のSYSTEM_ADMINが退会しようとしている
     */
    @Transactional(readOnly = true)
    public void check(Long userId) {
        if (!accessControlService.isSystemAdmin(userId)) {
            // SYSTEM_ADMINでないユーザーは退会可能
            return;
        }

        long count = countSystemAdmins();
        if (count <= 1) {
            throw new BusinessException(GdprErrorCode.GDPR_006);
        }
    }

    /**
     * SYSTEM_ADMINの数を取得する。
     */
    public long countSystemAdmins() {
        return userRoleRepository.findSystemAdminUserIds().size();
    }
}
