package com.mannschaft.app.knowledgebase.repository;

import com.mannschaft.app.knowledgebase.entity.KbPageRevisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ナレッジベースページリビジョンリポジトリ。
 */
public interface KbPageRevisionRepository extends JpaRepository<KbPageRevisionEntity, Long> {

    /**
     * ページIDでリビジョンをリビジョン番号の降順で取得する。
     */
    List<KbPageRevisionEntity> findByKbPageIdOrderByRevisionNumberDesc(Long kbPageId);

    /**
     * リビジョンIDとページIDでリビジョンを取得する。
     */
    Optional<KbPageRevisionEntity> findByIdAndKbPageId(Long id, Long kbPageId);

    /**
     * ページの最古リビジョンを取得する（FIFO削除用）。
     */
    Optional<KbPageRevisionEntity> findFirstByKbPageIdOrderByRevisionNumberAsc(Long kbPageId);

    /**
     * ページIDでリビジョン数をカウントする。
     */
    int countByKbPageId(Long kbPageId);
}
