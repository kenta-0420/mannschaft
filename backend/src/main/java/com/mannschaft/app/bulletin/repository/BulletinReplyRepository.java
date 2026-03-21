package com.mannschaft.app.bulletin.repository;

import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 掲示板返信リポジトリ。
 */
public interface BulletinReplyRepository extends JpaRepository<BulletinReplyEntity, Long> {

    /**
     * スレッドの返信をページング取得する（作成日時昇順）。
     */
    Page<BulletinReplyEntity> findByThreadIdAndParentIdIsNullOrderByCreatedAtAsc(
            Long threadId, Pageable pageable);

    /**
     * 親返信に対する子返信を取得する。
     */
    List<BulletinReplyEntity> findByParentIdOrderByCreatedAtAsc(Long parentId);

    /**
     * IDとスレッドIDで返信を取得する。
     */
    Optional<BulletinReplyEntity> findByIdAndThreadId(Long id, Long threadId);

    /**
     * スレッドの返信数を取得する。
     */
    long countByThreadId(Long threadId);
}
