package com.mannschaft.app.search.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.search.SearchErrorCode;
import com.mannschaft.app.search.SearchMapper;
import com.mannschaft.app.search.dto.SaveQueryRequest;
import com.mannschaft.app.search.dto.SavedQueryResponse;
import com.mannschaft.app.search.dto.SearchHistoryResponse;
import com.mannschaft.app.search.entity.SearchHistoryEntity;
import com.mannschaft.app.search.entity.SearchSavedQueryEntity;
import com.mannschaft.app.search.repository.SearchHistoryRepository;
import com.mannschaft.app.search.repository.SearchSavedQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 検索履歴・保存済みクエリサービス。検索履歴のCRUDおよび保存済みクエリの管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchHistoryService {

    private static final int MAX_SAVED_QUERIES = 20;

    private final SearchHistoryRepository searchHistoryRepository;
    private final SearchSavedQueryRepository searchSavedQueryRepository;
    private final SearchMapper searchMapper;

    /**
     * 検索履歴を記録する。同一クエリが存在する場合は検索日時を更新する。
     *
     * @param userId ユーザーID
     * @param query  検索クエリ
     */
    @Transactional
    public void recordHistory(Long userId, String query) {
        searchHistoryRepository.findByUserIdAndQuery(userId, query)
                .ifPresentOrElse(
                        SearchHistoryEntity::refreshSearchedAt,
                        () -> {
                            SearchHistoryEntity entity = SearchHistoryEntity.builder()
                                    .userId(userId)
                                    .query(query)
                                    .build();
                            searchHistoryRepository.save(entity);
                        }
                );
        log.debug("検索履歴記録: userId={}, query='{}'", userId, query);
    }

    /**
     * ユーザーの検索履歴一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 検索履歴レスポンスリスト
     */
    public List<SearchHistoryResponse> listHistory(Long userId) {
        List<SearchHistoryEntity> entities = searchHistoryRepository.findByUserIdOrderBySearchedAtDesc(userId);
        return searchMapper.toHistoryResponseList(entities);
    }

    /**
     * ユーザーの検索履歴を全削除する。
     *
     * @param userId ユーザーID
     */
    @Transactional
    public void deleteAllHistory(Long userId) {
        searchHistoryRepository.deleteByUserId(userId);
        log.info("検索履歴全削除: userId={}", userId);
    }

    /**
     * 検索履歴を個別削除する。
     *
     * @param userId    ユーザーID
     * @param historyId 検索履歴ID
     */
    @Transactional
    public void deleteHistory(Long userId, Long historyId) {
        SearchHistoryEntity entity = searchHistoryRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(SearchErrorCode.HISTORY_NOT_FOUND));
        searchHistoryRepository.delete(entity);
        log.info("検索履歴削除: userId={}, historyId={}", userId, historyId);
    }

    /**
     * 保存済みクエリ一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 保存済みクエリレスポンスリスト
     */
    public List<SavedQueryResponse> listSavedQueries(Long userId) {
        List<SearchSavedQueryEntity> entities = searchSavedQueryRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return searchMapper.toSavedQueryResponseList(entities);
    }

    /**
     * 検索クエリを保存する。上限チェックを行い、超過時は例外をスローする。
     *
     * @param userId  ユーザーID
     * @param request 保存リクエスト
     * @return 保存済みクエリレスポンス
     */
    @Transactional
    public SavedQueryResponse saveQuery(Long userId, SaveQueryRequest request) {
        long count = searchSavedQueryRepository.countByUserId(userId);
        if (count >= MAX_SAVED_QUERIES) {
            throw new BusinessException(SearchErrorCode.MAX_SAVED_QUERIES_EXCEEDED);
        }

        SearchSavedQueryEntity entity = SearchSavedQueryEntity.builder()
                .userId(userId)
                .name(request.getName())
                .queryParams(request.getQueryParams())
                .build();

        SearchSavedQueryEntity saved = searchSavedQueryRepository.save(entity);
        log.info("検索クエリ保存: userId={}, savedQueryId={}", userId, saved.getId());
        return searchMapper.toSavedQueryResponse(saved);
    }
}
