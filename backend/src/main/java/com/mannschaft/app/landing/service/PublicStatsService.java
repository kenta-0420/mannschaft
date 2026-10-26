package com.mannschaft.app.landing.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.landing.dto.PublicStatsResponse;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ランディングページ公開統計サービス。
 */
@Service
@RequiredArgsConstructor
public class PublicStatsService {

    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

    @Transactional(readOnly = true)
    @Cacheable("public-stats")
    public PublicStatsResponse getPublicStats() {
        long totalUsers = userRepository.countByStatus(UserEntity.UserStatus.ACTIVE);
        long totalTeams = teamRepository.count();
        long totalOrganizations = organizationRepository.count();

        return PublicStatsResponse.builder()
                .totalUsers(totalUsers)
                .totalTeams(totalTeams)
                .totalOrganizations(totalOrganizations)
                .countryBreakdown(null)
                .build();
    }
}
