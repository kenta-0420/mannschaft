package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.quickmemo.QuickMemoErrorCode;
import com.mannschaft.app.quickmemo.dto.CreateTagRequest;
import com.mannschaft.app.quickmemo.dto.TagResponse;
import com.mannschaft.app.quickmemo.dto.UpdateTagRequest;
import com.mannschaft.app.quickmemo.entity.TagEntity;
import com.mannschaft.app.quickmemo.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 汎用タグサービス。PERSONAL / TEAM / ORGANIZATION スコープのタグ CRUD を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService {

    private static final int TAG_LIMIT_PER_SCOPE = 50;

    private final TagRepository tagRepository;
    private final AuditLogService auditLogService;

    /**
     * スコープ内のタグ一覧を使用頻度降順で取得する。
     */
    public PagedResponse<TagResponse> listTags(String scopeType, Long scopeId, int page, int size) {
        Page<TagEntity> pageResult = tagRepository
                .findByScopeTypeAndScopeIdOrderByUsageCountDesc(scopeType, scopeId,
                        PageRequest.of(page - 1, size));
        List<TagResponse> responses = pageResult.getContent().stream()
                .map(TagResponse::from)
                .toList();
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                pageResult.getTotalElements(), page, size, pageResult.getTotalPages());
        return PagedResponse.of(responses, meta);
    }

    /**
     * タグを作成する。スコープ内50個上限・同名重複チェックあり。
     */
    @Transactional
    public TagResponse createTag(String scopeType, Long scopeId, CreateTagRequest req) {
        long currentCount = tagRepository.countByScopeTypeAndScopeId(scopeType, scopeId);
        if (currentCount >= TAG_LIMIT_PER_SCOPE) {
            throw new BusinessException(QuickMemoErrorCode.TAG_LIMIT_EXCEEDED);
        }

        String trimmedName = req.name().strip();
        if (tagRepository.existsByScopeTypeAndScopeIdAndName(scopeType, scopeId, trimmedName)) {
            throw new BusinessException(QuickMemoErrorCode.TAG_NAME_DUPLICATE);
        }

        Long currentUserId = SecurityUtils.getCurrentUserId();
        TagEntity tag = TagEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(trimmedName)
                .color(req.color())
                .createdBy(currentUserId)
                .build();

        TagEntity saved = tagRepository.save(tag);

        auditLogService.record("TAG_CREATED", currentUserId, null, null, null,
                null, null, null,
                "{\"tagId\":" + saved.getId() + ",\"scopeType\":\"" + scopeType + "\"}");

        log.info("タグ作成: tagId={}, scope={}/{}", saved.getId(), scopeType, scopeId);
        return TagResponse.from(saved);
    }

    /**
     * タグを更新する。
     */
    @Retryable(retryFor = {DataAccessException.class}, maxAttempts = 3)
    @Transactional
    public TagResponse updateTag(String scopeType, Long scopeId, Long tagId, UpdateTagRequest req) {
        TagEntity tag = tagRepository.findByIdAndScopeTypeAndScopeId(tagId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.TAG_NOT_FOUND));

        if (req.name() != null) {
            String trimmedName = req.name().strip();
            if (tagRepository.existsByScopeTypeAndScopeIdAndNameAndIdNot(scopeType, scopeId, trimmedName, tagId)) {
                throw new BusinessException(QuickMemoErrorCode.TAG_NAME_DUPLICATE);
            }
            tag.rename(trimmedName);
        }
        if (req.color() != null) {
            tag.changeColor(req.color());
        }

        TagEntity saved = tagRepository.save(tag);

        auditLogService.record("TAG_UPDATED", SecurityUtils.getCurrentUserId(), null, null, null,
                null, null, null,
                "{\"tagId\":" + tagId + "}");

        return TagResponse.from(saved);
    }

    /**
     * タグを削除する。使用中（usage_count > 0）は削除不可。
     */
    @Transactional
    public void deleteTag(String scopeType, Long scopeId, Long tagId) {
        TagEntity tag = tagRepository.findByIdAndScopeTypeAndScopeId(tagId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.TAG_NOT_FOUND));

        if (tag.getUsageCount() > 0) {
            throw new BusinessException(QuickMemoErrorCode.TAG_IN_USE);
        }

        tagRepository.delete(tag);

        auditLogService.record("TAG_DELETED", SecurityUtils.getCurrentUserId(), null, null, null,
                null, null, null,
                "{\"tagId\":" + tagId + ",\"name\":\"" + tag.getName() + "\"}");

        log.info("タグ削除: tagId={}", tagId);
    }
}
