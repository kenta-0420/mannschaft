package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.cms.dto.BlogTagResponse;
import com.mannschaft.app.cms.dto.CreateTagRequest;
import com.mannschaft.app.cms.dto.UpdateTagRequest;
import com.mannschaft.app.cms.entity.BlogTagEntity;
import com.mannschaft.app.cms.repository.BlogTagRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
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
    private final AccessControlService accessControlService;

    /**
     * タグ一覧を取得する。
     *
     * <p>認可根治戦役 Wave7: 兄弟の {@link #createTag} と同じ
     * {@link AccessControlService#checkMembership} をスコープに敷き、非メンバーが
     * 他チーム/組織のタグ構成を閲覧できないようにする。</p>
     */
    public List<BlogTagResponse> listTags(Long userId, Long teamId, Long organizationId) {
        List<BlogTagEntity> tags;
        if (teamId != null) {
            accessControlService.checkMembership(userId, teamId, "TEAM");
            tags = tagRepository.findByTeamIdOrderBySortOrderAsc(teamId);
        } else if (organizationId != null) {
            accessControlService.checkMembership(userId, organizationId, "ORGANIZATION");
            tags = tagRepository.findByOrganizationIdOrderBySortOrderAsc(organizationId);
        } else {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return cmsMapper.toBlogTagResponseList(tags);
    }

    /**
     * タグを作成する。
     *
     * <p>認可根治戦役 Wave3-B7: 従来は認可判定が皆無だったため、非メンバーでも任意のチーム/組織に
     * タグを作成できた。{@code teamId}/{@code organizationId} に対する
     * {@link AccessControlService#checkMembership}（メンバーであれば可、ADMIN限定ではない）を要求する。</p>
     */
    @Transactional
    public BlogTagResponse createTag(Long userId, CreateTagRequest request) {
        if (request.getTeamId() != null) {
            accessControlService.checkMembership(userId, request.getTeamId(), "TEAM");
        } else if (request.getOrganizationId() != null) {
            accessControlService.checkMembership(userId, request.getOrganizationId(), "ORGANIZATION");
        } else {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

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
     *
     * <p>認可根治戦役 Wave3-B7: 従来は認可判定が皆無だったため、非メンバーでも任意のタグを
     * 改名できた（BOLA）。タグが所属するスコープ（teamId優先→organizationId）の
     * ADMIN/DEPUTY_ADMIN のみ許可する。</p>
     */
    @Transactional
    public BlogTagResponse updateTag(Long id, Long userId, UpdateTagRequest request) {
        BlogTagEntity entity = tagRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.TAG_NOT_FOUND));
        checkScopeAdmin(userId, entity.getTeamId(), entity.getOrganizationId());
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
     *
     * <p>認可根治戦役 Wave3-B7: {@link #updateTag} と同一の認可方式（スコープADMIN限定）。</p>
     */
    @Transactional
    public void deleteTag(Long id, Long userId) {
        BlogTagEntity entity = tagRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.TAG_NOT_FOUND));
        checkScopeAdmin(userId, entity.getTeamId(), entity.getOrganizationId());
        tagRepository.delete(entity);
        log.info("タグ削除: tagId={}", id);
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
