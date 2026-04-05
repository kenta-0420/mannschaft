package com.mannschaft.app.proxyvote.repository;

import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionCommentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 議案コメントリポジトリ。
 */
public interface ProxyVoteMotionCommentRepository extends JpaRepository<ProxyVoteMotionCommentEntity, Long> {

    Page<ProxyVoteMotionCommentEntity> findByMotionIdOrderByCreatedAtAsc(Long motionId, Pageable pageable);
}
