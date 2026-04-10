package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.dto.ActionMemoTagResponse;
import com.mannschaft.app.actionmemo.dto.CreateActionMemoTagRequest;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoTagRequest;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.entity.ActionMemoTagEntity;
import com.mannschaft.app.actionmemo.entity.ActionMemoTagLinkEntity;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagLinkRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F02.5 行動メモタグサービス（Phase 4）。
 *
 * <p>設計書 §4 §6 §11 に従い、以下を保証する:</p>
 * <ul>
 *   <li>所有者一致検証: タグもメモも {@code userId} 一致を検証。不一致は 404（IDOR 対策）</li>
 *   <li>タグ上限: 1ユーザー100件（論理削除済みは含めない）</li>
 *   <li>1メモあたりタグ上限: 10個</li>
 *   <li>論理削除: 復活機能なし（§11 #9）。中間テーブルは残す</li>
 *   <li>ログ: content は含めず、tagId / userId / name のみ</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionMemoTagService {

    /** 設計書 §3: 1ユーザーあたりのタグ上限 */
    private static final int TAG_LIMIT_PER_USER = 100;

    /** 設計書 §3: 1メモあたりのタグ数上限 */
    private static final int TAG_LIMIT_PER_MEMO = 10;

    private final ActionMemoTagRepository tagRepository;
    private final ActionMemoTagLinkRepository tagLinkRepository;
    private final ActionMemoRepository memoRepository;

    // ==================================================================
    // タグ一覧取得
    // ==================================================================

    /**
     * ユーザーのタグ一覧を取得する（論理削除済みは含めない。サジェスト候補用）。
     *
     * @param userId 現在のユーザー
     * @return タグ一覧（sortOrder 昇順）
     */
    public List<ActionMemoTagResponse> getTags(Long userId) {
        List<ActionMemoTagEntity> tags = tagRepository.findByUserIdOrderBySortOrderAsc(userId);
        return tags.stream()
                .map(this::toResponse)
                .toList();
    }

    // ==================================================================
    // タグ1件取得
    // ==================================================================

    /**
     * タグ1件を取得する。所有者不一致 / 存在しない / 論理削除済みは 404。
     *
     * @param tagId  タグ ID
     * @param userId 現在のユーザー
     * @return タグレスポンス
     */
    public ActionMemoTagResponse getTag(Long tagId, Long userId) {
        ActionMemoTagEntity tag = findOwnTagOrThrow(tagId, userId);
        return toResponse(tag);
    }

    // ==================================================================
    // タグ作成
    // ==================================================================

    /**
     * タグを作成する。1ユーザー100件上限チェック付き。
     *
     * @param request 作成リクエスト（name 必須、color 任意）
     * @param userId  現在のユーザー
     * @return 作成されたタグレスポンス
     */
    @Transactional
    public ActionMemoTagResponse createTag(CreateActionMemoTagRequest request, Long userId) {
        // 100件上限チェック（論理削除済みは @SQLRestriction で除外されるため countByUserId でOK）
        long currentCount = tagRepository.countByUserId(userId);
        if (currentCount >= TAG_LIMIT_PER_USER) {
            log.warn("タグ上限到達: userId={}, currentCount={}", userId, currentCount);
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TAG_LIMIT_EXCEEDED);
        }

        ActionMemoTagEntity entity = ActionMemoTagEntity.builder()
                .userId(userId)
                .name(request.getName())
                .color(request.getColor())
                .sortOrder(0)
                .build();

        ActionMemoTagEntity saved = tagRepository.save(entity);

        log.info("tag_created: tagId={} userId={} name={}", saved.getId(), userId, saved.getName());

        return toResponse(saved);
    }

    // ==================================================================
    // タグ更新
    // ==================================================================

    /**
     * タグを更新する（名前・色）。所有者一致検証付き。
     *
     * @param tagId   タグ ID
     * @param request 更新リクエスト（全フィールド任意）
     * @param userId  現在のユーザー
     * @return 更新後のタグレスポンス
     */
    @Transactional
    public ActionMemoTagResponse updateTag(Long tagId, UpdateActionMemoTagRequest request, Long userId) {
        ActionMemoTagEntity tag = findOwnTagOrThrow(tagId, userId);

        if (request.getName() != null && !request.getName().isBlank()) {
            tag.setName(request.getName());
        }
        if (request.getColor() != null) {
            tag.setColor(request.getColor());
        }

        ActionMemoTagEntity saved = tagRepository.save(tag);

        log.info("tag_updated: tagId={} userId={} name={}", saved.getId(), userId, saved.getName());

        return toResponse(saved);
    }

    // ==================================================================
    // タグ論理削除
    // ==================================================================

    /**
     * タグを論理削除する。復活機能なし（§11 #9）。
     * 中間テーブル（action_memo_tag_links）は残す。
     *
     * @param tagId  タグ ID
     * @param userId 現在のユーザー
     */
    @Transactional
    public void deleteTag(Long tagId, Long userId) {
        ActionMemoTagEntity tag = findOwnTagOrThrow(tagId, userId);
        tag.softDelete();
        tagRepository.save(tag);

        log.info("tag_deleted: tagId={} userId={} name={}", tagId, userId, tag.getName());
    }

    // ==================================================================
    // メモにタグを追加
    // ==================================================================

    /**
     * メモにタグを追加する。1メモ10個上限チェック付き。
     *
     * <p>既に紐付け済みのタグはスキップする（冪等性）。
     * 追加後に10個を超える場合はエラー。</p>
     *
     * @param memoId メモ ID
     * @param tagIds 追加するタグ ID リスト
     * @param userId 現在のユーザー
     */
    @Transactional
    public void addTagsToMemo(Long memoId, List<Long> tagIds, Long userId) {
        // メモの所有者検証
        ActionMemoEntity memo = memoRepository.findByIdAndUserId(memoId, userId)
                .orElseThrow(() -> new BusinessException(ActionMemoErrorCode.ACTION_MEMO_NOT_FOUND));

        // タグの所有者検証（論理削除済みタグは @SQLRestriction で除外される）
        List<Long> distinctIds = tagIds.stream().distinct().toList();
        List<ActionMemoTagEntity> tags = tagRepository.findByIdInAndUserId(distinctIds, userId);
        if (tags.size() != distinctIds.size()) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TAG_NOT_FOUND);
        }

        // 既存タグ件数 + 追加分（重複は除外）の上限チェック
        long existingCount = tagLinkRepository.countByMemoId(memoId);
        long newTagCount = 0;
        for (ActionMemoTagEntity tag : tags) {
            if (!tagLinkRepository.existsByMemoIdAndTagId(memoId, tag.getId())) {
                newTagCount++;
            }
        }
        if (existingCount + newTagCount > TAG_LIMIT_PER_MEMO) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TAG_PER_MEMO_LIMIT_EXCEEDED);
        }

        // 紐付け保存（重複はスキップ）
        for (ActionMemoTagEntity tag : tags) {
            if (!tagLinkRepository.existsByMemoIdAndTagId(memoId, tag.getId())) {
                tagLinkRepository.save(ActionMemoTagLinkEntity.builder()
                        .memoId(memoId)
                        .tagId(tag.getId())
                        .build());
            }
        }

        log.info("tags_added_to_memo: memoId={} userId={} tagIds={}", memoId, userId, distinctIds);
    }

    // ==================================================================
    // メモからタグを除去
    // ==================================================================

    /**
     * メモからタグを除去する。
     *
     * @param memoId メモ ID
     * @param tagId  タグ ID
     * @param userId 現在のユーザー
     */
    @Transactional
    public void removeTagFromMemo(Long memoId, Long tagId, Long userId) {
        // メモの所有者検証
        memoRepository.findByIdAndUserId(memoId, userId)
                .orElseThrow(() -> new BusinessException(ActionMemoErrorCode.ACTION_MEMO_NOT_FOUND));

        // 中間レコードの存在確認・削除
        ActionMemoTagLinkEntity link = tagLinkRepository.findByMemoIdAndTagId(memoId, tagId)
                .orElseThrow(() -> new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TAG_NOT_FOUND));
        tagLinkRepository.delete(link);

        log.info("tag_removed_from_memo: memoId={} tagId={} userId={}", memoId, tagId, userId);
    }

    // ==================================================================
    // プライベートヘルパー
    // ==================================================================

    /**
     * 所有者一致検証付きのタグ取得。不一致・存在しない・論理削除済みは全て 404。
     */
    private ActionMemoTagEntity findOwnTagOrThrow(Long tagId, Long userId) {
        return tagRepository.findByIdAndUserId(tagId, userId)
                .orElseThrow(() -> new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TAG_NOT_FOUND));
    }

    /**
     * Entity → Response マッピング。
     */
    private ActionMemoTagResponse toResponse(ActionMemoTagEntity entity) {
        return ActionMemoTagResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .color(entity.getColor())
                .sortOrder(entity.getSortOrder())
                .deleted(false)
                .build();
    }
}
