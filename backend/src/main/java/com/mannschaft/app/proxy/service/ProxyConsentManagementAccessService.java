package com.mannschaft.app.proxy.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonConstants;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.organization.service.OrganizationQueryService;
import com.mannschaft.app.proxy.dto.ProxyInputConsentResponse;
import com.mannschaft.app.proxy.dto.ProxyInputRecordResponse;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理APIの認可窓口。親組合の確認をmutation TXの外で行い、ドメイン間TX依存を増やさない。
 * 状態判定・更新はConsentServiceが改めて取得するlocked entityだけを根拠にする。
 */
@Service
@RequiredArgsConstructor
public class ProxyConsentManagementAccessService {
    private final ProxyInputConsentRepository consents;
    private final OrganizationQueryService organizations;
    private final ScopeConcealingAccessGate accessGate;
    private final AccessControlService accessControl;
    private final ProxyInputConsentService consentService;
    private final ProxyConsentManagementQueryService queryService;

    public PagedResponse<ProxyInputConsentResponse> getConsents(Long actor, Long organizationId, int page, int size) {
        requireOrganizationAdmin(actor, organizationId);
        return queryService.getConsents(organizationId, pageable(page, size));
    }

    public PagedResponse<ProxyInputRecordResponse> getRecords(
            Long actor, Long organizationId, Long subjectUserId, int page, int size) {
        if (organizationId != null) {
            // 組合を指定した時点で管理資格を要求し、本人指定による迂回を許さない。
            requireOrganizationAdmin(actor, organizationId);
        } else {
            if (subjectUserId == null) subjectUserId = actor;
            if (!actor.equals(subjectUserId) && !accessControl.isSystemAdmin(actor)) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
        }
        return queryService.getRecords(organizationId, subjectUserId, pageable(page, size));
    }

    public ProxyInputConsentResponse approve(Long actor, Long consentId) {
        var consent = findWithLivingParent(consentId);
        accessGate.requireOrConceal(actor, consent.getOrganizationId(), "ORGANIZATION",
                () -> accessControl.hasPermission(actor, consent.getOrganizationId(), "ORGANIZATION",
                        "PROXY_CONSENT_APPROVE"), CommonErrorCode.COMMON_002, CommonErrorCode.COMMON_002);
        return ProxyInputConsentResponse.from(consentService.approveConsent(actor, consentId));
    }

    public void revoke(Long actor, Long consentId, RevokeConsentCommand command) {
        var consent = findWithLivingParent(consentId);
        accessGate.requireOwnerOrAdminOrConceal(actor, consent.getOrganizationId(), "ORGANIZATION",
                consent.getSubjectUserId(), CommonErrorCode.COMMON_002);
        consentService.revokeConsent(actor, consentId, command);
    }

    private ProxyInputConsentEntity findWithLivingParent(Long consentId) {
        var consent = consents.findById(consentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_002));
        requireLivingOrganization(consent.getOrganizationId());
        return consent;
    }

    private void requireOrganizationAdmin(Long actor, Long organizationId) {
        // proxy管理APIのSYS横断裁可。一般のscope管理者helperの意味は変えない。
        if (!accessControl.isSystemAdmin(actor)
                && !accessControl.isAdminOrAbove(actor, organizationId, "ORGANIZATION")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        requireLivingOrganization(organizationId);
    }

    private void requireLivingOrganization(Long organizationId) {
        if (organizations.findSummariesByIds(List.of(organizationId)).isEmpty()) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    private PageRequest pageable(int page, int size) {
        if (page < 0 || size < 1) throw new BusinessException(CommonErrorCode.COMMON_001);
        return PageRequest.of(page, Math.min(size, CommonConstants.MAX_PAGE_SIZE));
    }
}
