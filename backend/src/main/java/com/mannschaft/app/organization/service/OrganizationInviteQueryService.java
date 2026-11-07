package com.mannschaft.app.organization.service;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationBlockRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 招待の承諾・プレビューが organization ドメインへ問い合わせる読み取り窓口（CMP-260826-1457）。
 *
 * <p>role ドメインのトランザクションから organization の Repository を直接触らずに済むよう、結果だけを
 * プリミティブ（{@code boolean} / {@code String}）で返す。Entity は外に出さない。例外は投げない
 * （エラーの優先順位は呼び出し側の claim が決める）。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrganizationInviteQueryService {

    private final OrganizationBlockRepository organizationBlockRepository;
    private final OrganizationRepository organizationRepository;

    /** 当該ユーザーが組織からブロックされているか。 */
    public boolean isBlocked(Long organizationId, Long userId) {
        return organizationBlockRepository.existsByOrganizationIdAndUserId(organizationId, userId);
    }

    /** 組織名。存在しなければ null。 */
    public String findName(Long organizationId) {
        return organizationRepository.findById(organizationId).map(OrganizationEntity::getName).orElse(null);
    }
}
