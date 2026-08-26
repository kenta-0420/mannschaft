package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.dto.BlogSeriesResponse;
import com.mannschaft.app.cms.dto.CreateSeriesRequest;
import com.mannschaft.app.cms.dto.UpdateSeriesRequest;
import com.mannschaft.app.cms.entity.BlogPostSeriesEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostSeriesRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ブログシリーズサービス。連載シリーズのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogSeriesService {

    private final BlogPostSeriesRepository seriesRepository;
    private final BlogPostRepository postRepository;
    private final AccessControlService accessControlService;

    /**
     * シリーズ一覧を取得する。
     *
     * <p>認可根治戦役 Wave7: 兄弟の {@link #createSeries} と同じ
     * {@link AccessControlService#checkMembership} をスコープに敷き、非メンバーが
     * 他チーム/組織のシリーズ構成を閲覧できないようにする。</p>
     */
    public List<BlogSeriesResponse> listSeries(Long userId, Long teamId, Long organizationId) {
        List<BlogPostSeriesEntity> entities;
        if (teamId != null) {
            accessControlService.checkMembership(userId, teamId, "TEAM");
            entities = seriesRepository.findByTeamIdOrderByCreatedAtDesc(teamId);
        } else if (organizationId != null) {
            accessControlService.checkMembership(userId, organizationId, "ORGANIZATION");
            entities = seriesRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
        } else {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return entities.stream()
                .map(e -> new BlogSeriesResponse(
                        e.getId(), e.getTeamId(), e.getOrganizationId(),
                        e.getName(), e.getDescription(), e.getCreatedBy(),
                        postRepository.countBySeriesId(e.getId()),
                        e.getCreatedAt(), e.getUpdatedAt()))
                .collect(Collectors.toList());
    }

    /**
     * シリーズを作成する。
     *
     * <p>認可根治戦役 Wave3-B7: 従来は認可判定が皆無だったため、非メンバーでも任意のチーム/組織に
     * シリーズを作成できた。{@code teamId}/{@code organizationId} に対する
     * {@link AccessControlService#checkMembership}（メンバーであれば可、ADMIN限定ではない）を要求する。</p>
     */
    @Transactional
    public BlogSeriesResponse createSeries(Long userId, CreateSeriesRequest request) {
        if (request.getTeamId() != null) {
            accessControlService.checkMembership(userId, request.getTeamId(), "TEAM");
        } else if (request.getOrganizationId() != null) {
            accessControlService.checkMembership(userId, request.getOrganizationId(), "ORGANIZATION");
        } else {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        BlogPostSeriesEntity entity = BlogPostSeriesEntity.builder()
                .teamId(request.getTeamId())
                .organizationId(request.getOrganizationId())
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(userId)
                .build();

        BlogPostSeriesEntity saved = seriesRepository.save(entity);
        log.info("シリーズ作成: seriesId={}, name={}", saved.getId(), saved.getName());
        return new BlogSeriesResponse(saved.getId(), saved.getTeamId(), saved.getOrganizationId(),
                saved.getName(), saved.getDescription(), saved.getCreatedBy(),
                0L, saved.getCreatedAt(), saved.getUpdatedAt());
    }

    /**
     * シリーズを更新する。
     *
     * <p>認可根治戦役 Wave3-B7: 従来は認可判定が皆無だったため、非メンバーでも任意のシリーズを
     * 改題できた（BOLA）。シリーズが所属するスコープ（teamId優先→organizationId）の
     * ADMIN/DEPUTY_ADMIN のみ許可する。</p>
     */
    @Transactional
    public BlogSeriesResponse updateSeries(Long id, Long userId, UpdateSeriesRequest request) {
        BlogPostSeriesEntity entity = seriesRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.SERIES_NOT_FOUND));
        checkScopeAdmin(userId, entity.getTeamId(), entity.getOrganizationId());
        entity.update(request.getName(), request.getDescription());
        BlogPostSeriesEntity saved = seriesRepository.save(entity);
        log.info("シリーズ更新: seriesId={}", id);
        return new BlogSeriesResponse(saved.getId(), saved.getTeamId(), saved.getOrganizationId(),
                saved.getName(), saved.getDescription(), saved.getCreatedBy(),
                postRepository.countBySeriesId(saved.getId()),
                saved.getCreatedAt(), saved.getUpdatedAt());
    }

    /**
     * シリーズを削除する。
     *
     * <p>認可根治戦役 Wave3-B7: {@link #updateSeries} と同一の認可方式（スコープADMIN限定）。</p>
     */
    @Transactional
    public void deleteSeries(Long id, Long userId) {
        BlogPostSeriesEntity entity = seriesRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.SERIES_NOT_FOUND));
        checkScopeAdmin(userId, entity.getTeamId(), entity.getOrganizationId());
        seriesRepository.delete(entity);
        log.info("シリーズ削除: seriesId={}", id);
    }

    /**
     * スコープ（teamId優先→organizationId）の ADMIN/DEPUTY_ADMIN であることを検証する。
     * いずれも null の異常系は fail-closed で 403（COMMON_002）とする。
     */
    private void checkScopeAdmin(Long userId, Long teamId, Long organizationId) {
        if (teamId != null) {
            accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        } else if (organizationId != null) {
            accessControlService.checkAdminOrAbove(userId, organizationId, "ORGANIZATION");
        } else {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }
}
