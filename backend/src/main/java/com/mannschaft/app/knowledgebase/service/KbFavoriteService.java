package com.mannschaft.app.knowledgebase.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.knowledgebase.KnowledgeBaseErrorCode;
import com.mannschaft.app.knowledgebase.entity.KbPageEntity;
import com.mannschaft.app.knowledgebase.entity.KbPageFavoriteEntity;
import com.mannschaft.app.knowledgebase.repository.KbPageFavoriteRepository;
import com.mannschaft.app.knowledgebase.repository.KbPageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ナレッジベースお気に入りサービス。
 * お気に入りの取得・追加・削除を担当する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class KbFavoriteService {

    /** お気に入り上限件数 */
    private static final int MAX_FAVORITES = 50;

    private final KbPageRepository pageRepository;
    private final KbPageFavoriteRepository favoriteRepository;

    /**
     * ユーザーのお気に入りページ一覧を取得する。
     */
    public ApiResponse<List<KbPageEntity>> getFavorites(Long userId, String scopeType, Long scopeId) {
        List<KbPageFavoriteEntity> favorites =
                favoriteRepository.findByUserIdOrderByCreatedAtDesc(userId);

        List<Long> pageIds = favorites.stream()
                .map(KbPageFavoriteEntity::getKbPageId)
                .toList();

        List<KbPageEntity> pages = pageRepository.findAllById(pageIds).stream()
                .filter(p -> p.getScopeType().equals(scopeType) && p.getScopeId().equals(scopeId))
                .filter(p -> p.getDeletedAt() == null)
                .toList();

        return ApiResponse.of(pages);
    }

    /**
     * お気に入りを追加する。
     * ページ存在確認・重複確認・上限確認を行う。
     */
    @Transactional
    public void addFavorite(Long pageId, Long userId) {
        // ページ存在確認
        pageRepository.findByIdAndDeletedAtIsNull(pageId)
                .orElseThrow(() -> new BusinessException(KnowledgeBaseErrorCode.KB_001));

        // 重複確認
        if (favoriteRepository.findByKbPageIdAndUserId(pageId, userId).isPresent()) {
            return; // 既にお気に入り登録済みの場合は冪等に処理
        }

        // 上限確認 (KB_008)
        int currentCount = favoriteRepository.countByUserId(userId);
        if (currentCount >= MAX_FAVORITES) {
            throw new BusinessException(KnowledgeBaseErrorCode.KB_008);
        }

        KbPageFavoriteEntity favorite = KbPageFavoriteEntity.builder()
                .kbPageId(pageId)
                .userId(userId)
                .build();
        favoriteRepository.save(favorite);

        log.info("お気に入りを追加しました: pageId={}, userId={}", pageId, userId);
    }

    /**
     * お気に入りを削除する（物理削除）。
     */
    @Transactional
    public void removeFavorite(Long pageId, Long userId) {
        favoriteRepository.findByKbPageIdAndUserId(pageId, userId)
                .ifPresent(favorite -> {
                    favoriteRepository.delete(favorite);
                    log.info("お気に入りを削除しました: pageId={}, userId={}", pageId, userId);
                });
    }
}
