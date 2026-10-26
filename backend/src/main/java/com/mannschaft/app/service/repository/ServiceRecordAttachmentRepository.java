package com.mannschaft.app.service.repository;

import com.mannschaft.app.service.entity.ServiceRecordAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 添付ファイルリポジトリ。
 */
public interface ServiceRecordAttachmentRepository extends JpaRepository<ServiceRecordAttachmentEntity, Long> {

    List<ServiceRecordAttachmentEntity> findByServiceRecordIdOrderBySortOrder(Long serviceRecordId);

    List<ServiceRecordAttachmentEntity> findByServiceRecordIdIn(List<Long> serviceRecordIds);

    Optional<ServiceRecordAttachmentEntity> findByIdAndServiceRecordId(Long id, Long serviceRecordId);

    long countByServiceRecordId(Long serviceRecordId);
}
