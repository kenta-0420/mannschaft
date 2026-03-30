package com.mannschaft.app.knowledgebase.repository;

import com.mannschaft.app.knowledgebase.entity.KbPageFavoriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
