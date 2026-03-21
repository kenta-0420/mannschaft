package com.mannschaft.app.service.repository;

import com.mannschaft.app.service.entity.ServiceRecordTemplateValueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * テンプレート値リポジトリ。
 */
public interface ServiceRecordTemplateValueRepository extends JpaRepository<ServiceRecordTemplateValueEntity, Long> {

    List<ServiceRecordTemplateValueEntity> findByTemplateId(Long templateId);

    void deleteByTemplateId(Long templateId);
}
