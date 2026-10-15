package com.mannschaft.app.knowledgebase.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.knowledgebase.PageAccessLevel;
import com.mannschaft.app.knowledgebase.entity.KbPageEntity;
import com.mannschaft.app.knowledgebase.repository.KbPageQueryRepository;
import com.mannschaft.app.knowledgebase.repository.KbPageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * ナレッジベース全文検索サービス。
 * キーワードによるページ検索とアクセスレベルフィルタリングを担当する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class KbSearchService {

    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "DEPUTY_ADMIN");

    private final KbPageRepository pageRepository;
    private final KbPageQueryRepository pageQueryRepository;

    /**
     * キーワードでページを全文検索する。
     * アクセスレベルに応じてフィルタリングを行う。
     */
    public ApiResponse<List<KbPageEntity>> search(String keyword, String scopeType,
                                                   Long scopeId, String userRole) {
        boolean isAdmin = ADMIN_ROLES.contains(userRole);

        // 管理者でない場合は ADMIN_ONLY を除外するためのフィルターを渡さない
        // （ADMIN_ONLYを非表示にしたい場合 → searchFullText内でACCESS_LEVELを指定する）
        // 非管理者: ADMIN_ONLYを除いた検索（access_levelフィルターなし → 後段で除外）
        List<Long> pageIds = pageQueryRepository.searchFullText(keyword, scopeType, scopeId, null);

        if (pageIds.isEmpty()) {
            return ApiResponse.of(List.of());
        }

        List<KbPageEntity> pages = pageRepository.findAllById(pageIds);

        // アクセスレベルフィルタ
        List<KbPageEntity> filtered = pages.stream()
                .filter(p -> p.getDeletedAt() == null)
                .filter(p -> isAdmin || p.getAccessLevel() != PageAccessLevel.ADMIN_ONLY)
                .toList();

        log.debug("KB全文検索: keyword={}, 件数={}", keyword, filtered.size());
        return ApiResponse.of(filtered);
    }
}
