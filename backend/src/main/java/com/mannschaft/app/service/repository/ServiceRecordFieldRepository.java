package com.mannschaft.app.service.repository;

import com.mannschaft.app.service.entity.ServiceRecordFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * カスタムフィールド定義リポジトリ。
 */
public interface ServiceRecordFieldRepository extends JpaRepository<ServiceRecordFieldEntity, Long> {

    List<ServiceRecordFieldEntity> findByTeamIdOrderBySortOrder(Long teamId);

    List<ServiceRecordFieldEntity> findByTeamIdAndIsActiveTrue(Long teamId);

    Optional<ServiceRecordFieldEntity> findByIdAndTeamId(Long id, Long teamId);

    long countByTeamIdAndIsActiveTrue(Long teamId);
}
