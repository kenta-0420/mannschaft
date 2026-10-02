package com.mannschaft.app.provisioning.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.provisioning.ProvisioningErrorCode;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 柱②-3: 販促プロビジョニングゲート。
 *
 * <p>PROVISIONED（承諾前の事前作成状態）スコープへの、招待発行・サポーター申請・課金などの
 * 「通常のメンバー参加/管理系導線」を一律で遮断する（AC11）。承諾（accept）そのものと、
 * SYSTEM_ADMIN のプロビジョニング管理系 API（{@code ProvisioningService} /
 * {@code ProvisioningAcceptanceService} 自身）はこのゲートの対象外
 * （意図的に PROVISIONED 行を読み書きする経路のため）。</p>
 *
 * <p>不在ではなく「ロック中」を意味するため 423 (Locked) を既定とする
 * （不在との違いを呼び出し元が判別できてよい導線のため、村招待の存在秘匿とは性質が異なる）。</p>
 */
@Component
@RequiredArgsConstructor
public class ProvisioningGate {

    private static final String SCOPE_TEAM = "TEAM";

    private final TeamService teamService;
    private final OrganizationService organizationService;

    /**
     * 指定スコープが PROVISIONED でないこと（＝通常導線を使ってよいこと）を要求する。
     *
     * @param scopeId   スコープ ID
     * @param scopeType {@code TEAM} または {@code ORGANIZATION}
     * @throws BusinessException {@code PROV_008}（423） PROVISIONED の場合
     */
    @Transactional(readOnly = true)
    public void requireActive(Long scopeId, String scopeType) {
        boolean provisioned = SCOPE_TEAM.equals(scopeType)
                ? teamService.isProvisioned(scopeId)
                : organizationService.isProvisioned(scopeId);
        if (provisioned) {
            throw new BusinessException(ProvisioningErrorCode.PROV_008);
        }
    }
}
