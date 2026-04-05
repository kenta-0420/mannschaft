package com.mannschaft.app.search.repository;

import com.mannschaft.app.search.entity.SearchSavedQueryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 保存済み検索クエリリポジトリ。
 */
public interface SearchSavedQueryRepository extends JpaRepository<SearchSavedQueryEntity, Long> {

    /**
     * ユーザーの保存済みクエリを作成日時の降順で取得する。
     */
    List<SearchSavedQueryEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * ユーザーIDと保存済みクエリIDで取得する。
     */
    Optional<SearchSavedQueryEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * ユーザーの保存済みクエリ件数を取得する。
     */
    long countByUserId(Long userId);
}
