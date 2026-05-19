package com.mannschaft.app.publicview.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.dto.PublicOrganizationResponse;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F19.1 公開組織ページ用クエリサービス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.1 / §7.3</p>
 *
 * <p><strong>IDOR 対策</strong>: PRIVATE / archived / 削除済 / 不在を区別せず
 * 一律 {@link PublicViewErrorCode#PUBLIC_001}（404 へ正規化）を返す。
 * リポジトリ層の {@link OrganizationRepository#findPublicOrganizationById} が
 * これら全条件を満たした行のみ返すため、本サービスは結果の有無のみ判定する。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PublicOrganizationQueryService {

    private final OrganizationRepository organizationRepository;

    /**
     * 公開組織の詳細を取得する。
     *
     * @param organizationId 組織 ID
     * @return 抑制版 DTO
     * @throws BusinessException PRIVATE / archived / 削除済 / 不在の場合
     *                           （{@link PublicViewErrorCode#PUBLIC_001}、404 へ正規化）
     */
    public PublicOrganizationResponse getPublicOrganization(Long organizationId) {
        OrganizationEntity org = organizationRepository.findPublicOrganizationById(organizationId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));
        boolean philosophyVisible = org.getProfileVisibility() != null
                && org.getProfileVisibility().isPhilosophyVisible();
        return PublicOrganizationResponse.from(org, philosophyVisible);
    }
}
