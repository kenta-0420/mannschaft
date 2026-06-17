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

    /**
     * ユーザーの保存済みクエリを全削除する。
     *
     * <p>退会フロー（GDPR Art.17）の30日後物理削除時に、
     * {@code SearchAnonymizationEventListener#onAccountPurged} から
     * {@link com.mannschaft.app.gdpr.event.AccountPurgedEvent} 購読経由で呼ばれる。
     * 保存済みクエリは「個人設定・復元価値」を持つため即時削除ではなく30日猶予側に置く。</p>
     */
    void deleteByUserId(Long userId);
}
