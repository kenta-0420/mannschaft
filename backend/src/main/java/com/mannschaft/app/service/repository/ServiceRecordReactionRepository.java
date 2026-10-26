package com.mannschaft.app.service.repository;

import com.mannschaft.app.service.entity.ServiceRecordReactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * リアクションリポジトリ。
 */
public interface ServiceRecordReactionRepository extends JpaRepository<ServiceRecordReactionEntity, Long> {

    Optional<ServiceRecordReactionEntity> findByServiceRecordIdAndUserId(Long serviceRecordId, Long userId);

    List<ServiceRecordReactionEntity> findByServiceRecordIdIn(List<Long> serviceRecordIds);

    void deleteByServiceRecordIdAndUserId(Long serviceRecordId, Long userId);
}
