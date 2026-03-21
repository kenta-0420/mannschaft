package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.cms.dto.BlogTagResponse;
import com.mannschaft.app.cms.dto.CreateTagRequest;
import com.mannschaft.app.cms.dto.UpdateTagRequest;
import com.mannschaft.app.cms.entity.BlogTagEntity;
import com.mannschaft.app.cms.repository.BlogTagRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ブログタグサービス。タグのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogTagService {

    private final BlogTagRepository tagRepository;
    private final CmsMapper cmsMapper;

    /**
     * タグ一覧を取得する。
     */
    public List<BlogTagResponse> listTags(Long teamId, Long organizationId) {
        List<BlogTagEntity> tags;
        if (teamId != null) {
            tags = tagRepository.findByTeamIdOrderBySortOrderAsc(teamId);
        } else {
            tags = tagRepository.findByOrganizationIdOrderBySortOrderAsc(organizationId);
        }
        return cmsMapper.toBlogTagResponseList(tags);
    }

    /**
     * タグを作成する。
     */
    @Transactional
    public BlogTagResponse createTag(CreateTagRequest request) {
        // 重複チェック
        if (request.getTeamId() != null) {
            tagRepository.findByTeamIdAndName(request.getTeamId(), request.getName())
                    .ifPresent(t -> { throw new BusinessException(CmsErrorCode.DUPLICATE_TAG_NAME); });
        } else {
            tagRepository.findByOrganizationIdAndName(request.getOrganizationId(), request.getName())
                    .ifPresent(t -> { throw new BusinessException(CmsErrorCode.DUPLICATE_TAG_NAME); });
        }

        BlogTagEntity entity = BlogTagEntity.builder()
                .teamId(request.getTeamId())
                .organizationId(request.getOrganizationId())
                .name(request.getName())
                .color(request.getColor() != null ? request.getColor() : "#6B7280")
                .build();

        BlogTagEntity saved = tagRepository.save(entity);
        log.info("タグ作成: tagId={}, name={}", saved.getId(), saved.getName());
        return cmsMapper.toBlogTagResponse(saved);
    }

    /**
     * タグを更新する。
     */
    @Transactional
    public BlogTagResponse updateTag(Long id, UpdateTagRequest request) {
        BlogTagEntity entity = tagRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.TAG_NOT_FOUND));
        entity.update(
                request.getName(),
                request.getColor() != null ? request.getColor() : entity.getColor(),
                request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder());
        BlogTagEntity saved = tagRepository.save(entity);
        log.info("タグ更新: tagId={}", id);
        return cmsMapper.toBlogTagResponse(saved);
    }

    /**
     * タグを削除する（物理削除）。
     */
    @Transactional
    public void deleteTag(Long id) {
        BlogTagEntity entity = tagRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.TAG_NOT_FOUND));
        tagRepository.delete(entity);
        log.info("タグ削除: tagId={}", id);
    }
}
