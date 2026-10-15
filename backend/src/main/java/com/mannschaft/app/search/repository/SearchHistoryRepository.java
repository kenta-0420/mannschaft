package com.mannschaft.app.search.repository;

import com.mannschaft.app.search.entity.SearchHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 検索履歴リポジトリ。
 */
public interface SearchHistoryRepository extends JpaRepository<SearchHistoryEntity, Long> {

    /**
     * ユーザーの検索履歴を検索日時の降順で取得する。
     */
    List<SearchHistoryEntity> findByUserIdOrderBySearchedAtDesc(Long userId);

    /**
     * ユーザーIDと検索クエリで既存の履歴を取得する。
     */
    Optional<SearchHistoryEntity> findByUserIdAndQuery(Long userId, String query);

    /**
     * ユーザーの検索履歴を全削除する。
     */
    void deleteByUserId(Long userId);

    /**
     * ユーザーIDと履歴IDで検索履歴を取得する。
     */
    Optional<SearchHistoryEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * ユーザーの検索履歴件数を取得する。
     */
    long countByUserId(Long userId);
}
