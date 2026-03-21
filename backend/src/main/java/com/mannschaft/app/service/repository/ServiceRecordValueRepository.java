package com.mannschaft.app.service.repository;

import com.mannschaft.app.service.entity.ServiceRecordValueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * カスタムフィールド値リポジトリ。
 */
public interface ServiceRecordValueRepository extends JpaRepository<ServiceRecordValueEntity, Long> {

    List<ServiceRecordValueEntity> findByServiceRecordId(Long serviceRecordId);

    List<ServiceRecordValueEntity> findByServiceRecordIdIn(List<Long> serviceRecordIds);

    void deleteByServiceRecordId(Long serviceRecordId);
}
