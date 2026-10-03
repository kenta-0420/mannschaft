package com.mannschaft.app.proxy;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;

import java.time.LocalDate;
import java.util.UUID;

/** 代理同意管理の契約テストで使う、Repository保存によるDBフィクスチャ。 */
record ProxyConsentManagementTestFixture(
        UserRepository users, OrganizationRepository organizations,
        ProxyInputConsentRepository consents, ProxyInputRecordRepository records) {

    Long account() {
        return users.save(UserEntity.builder().email(UUID.randomUUID() + "@example.com")
                .lastName("契約").firstName("住民").displayName("契約住民").isSearchable(true)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
    }

    Long organization() {
        return organizations.save(OrganizationEntity.builder()
                .slug("proxy-" + UUID.randomUUID().toString().substring(0, 12)).name("代理管理組合")
                .orgType(OrganizationEntity.OrgType.COMMUNITY).visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false)
                .build()).getId();
    }

    ProxyInputConsentEntity consent(Long organizationId, Long subjectId, Long proxyId) {
        return consents.save(ProxyInputConsentEntity.create(subjectId, proxyId, organizationId,
                ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED, "consent.pdf", null, null,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30)));
    }

    Long record(Long consentId, Long subjectId, Long proxyId, ProxyInputRecordEntity.InputSource source) {
        return records.save(ProxyInputRecordEntity.create(consentId, subjectId, proxyId,
                "SURVEY", "SURVEY_RESPONSE", 42L, source, "組合事務所")).getId();
    }
}
