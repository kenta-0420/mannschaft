package com.mannschaft.app.knowledgebase.repository;

import com.mannschaft.app.knowledgebase.entity.KbPageFavoriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ナレッジベースページお気に入りリポジトリ。
 */
public interface KbPageFavoriteRepository extends JpaRepository<KbPageFavoriteEntity, Long> {

    /**
     * ユーザーIDでお気に入りを作成日時の降順で取得する。
     */
    List<KbPageFavoriteEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * ページIDとユーザーIDでお気に入りを取得する。
     */
    Optional<KbPageFavoriteEntity> findByKbPageIdAndUserId(Long kbPageId, Long userId);

    /**
     * ユーザーIDでお気に入り件数をカウントする。
     */
    int countByUserId(Long userId);

    /** 強匿名化専用。対象利用者の行を論理削除済みも含めて物理削除する。 */
    @Modifying
    @Query(value = "DELETE FROM kb_page_favorites WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") Long userId);
}
