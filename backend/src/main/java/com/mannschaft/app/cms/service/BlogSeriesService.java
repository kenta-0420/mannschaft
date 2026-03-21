package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.cms.dto.BlogSeriesResponse;
import com.mannschaft.app.cms.dto.CreateSeriesRequest;
import com.mannschaft.app.cms.dto.UpdateSeriesRequest;
import com.mannschaft.app.cms.entity.BlogPostSeriesEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostSeriesRepository;
import com.mannschaft.app.common.BusinessException;
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
    private final CmsMapper cmsMapper;

    /**
     * シリーズ一覧を取得する。
     */
    public List<BlogSeriesResponse> listSeries(Long teamId, Long organizationId) {
        List<BlogPostSeriesEntity> entities;
        if (teamId != null) {
            entities = seriesRepository.findByTeamIdOrderByCreatedAtDesc(teamId);
        } else {
            entities = seriesRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
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
     */
    @Transactional
    public BlogSeriesResponse createSeries(Long userId, CreateSeriesRequest request) {
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
     */
    @Transactional
    public BlogSeriesResponse updateSeries(Long id, UpdateSeriesRequest request) {
        BlogPostSeriesEntity entity = seriesRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.SERIES_NOT_FOUND));
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
     */
    @Transactional
    public void deleteSeries(Long id) {
        BlogPostSeriesEntity entity = seriesRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.SERIES_NOT_FOUND));
        seriesRepository.delete(entity);
        log.info("シリーズ削除: seriesId={}", id);
    }
}
