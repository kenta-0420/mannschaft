package com.mannschaft.app.proxyvote.repository;

import com.mannschaft.app.proxyvote.AttachmentTargetType;
import com.mannschaft.app.proxyvote.entity.ProxyVoteAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 添付ファイルリポジトリ。
 */
public interface ProxyVoteAttachmentRepository extends JpaRepository<ProxyVoteAttachmentEntity, Long> {

    List<ProxyVoteAttachmentEntity> findByTargetTypeAndTargetIdOrderBySortOrderAsc(
            AttachmentTargetType targetType, Long targetId);

    long countByTargetTypeAndTargetId(AttachmentTargetType targetType, Long targetId);
}
