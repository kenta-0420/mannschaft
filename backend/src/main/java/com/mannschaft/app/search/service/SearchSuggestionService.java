package com.mannschaft.app.search.service;

import com.mannschaft.app.search.dto.SearchSuggestionResponse;
import com.mannschaft.app.search.entity.SearchHistoryEntity;
import com.mannschaft.app.search.repository.SearchHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 検索サジェストサービス。入力補完候補と最近の検索履歴からの候補を提供する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchSuggestionService {

    private static final int MAX_SUGGESTIONS = 10;
    private static final int MAX_RECENT_QUERIES = 5;

    private final SearchHistoryRepository searchHistoryRepository;

    /**
     * 検索サジェストを取得する。
     *
     * <p>ユーザーの検索履歴から前方一致でキーワード候補を抽出し、
     * 最近の検索履歴からも候補を返す。</p>
     *
     * @param query  入力中のクエリ（前方一致フィルタ用）
     * @param userId ユーザーID
     * @return サジェストレスポンス
     */
    public SearchSuggestionResponse suggest(String query, Long userId) {
        List<SearchHistoryEntity> allHistory =
                searchHistoryRepository.findByUserIdOrderBySearchedAtDesc(userId);

        List<String> suggestions = allHistory.stream()
                .map(SearchHistoryEntity::getQuery)
                .filter(q -> q.toLowerCase().startsWith(query.toLowerCase()))
                .distinct()
                .limit(MAX_SUGGESTIONS)
                .toList();

        List<String> recentQueries = allHistory.stream()
                .map(SearchHistoryEntity::getQuery)
                .distinct()
                .limit(MAX_RECENT_QUERIES)
                .toList();

        return new SearchSuggestionResponse(suggestions, recentQueries);
    }
}
