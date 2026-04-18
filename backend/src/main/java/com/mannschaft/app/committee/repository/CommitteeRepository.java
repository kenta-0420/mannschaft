package com.mannschaft.app.committee.repository;

import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 委員会リポジトリ。
 */
public interface CommitteeRepository extends JpaRepository<CommitteeEntity, Long> {

    /**
     * 組織の委員会一覧をページングで取得する。
     */
    Page<CommitteeEntity> findByOrganizationId(Long organizationId, Pageable pageable);

    /**
     * 組織の委員会一覧をステータスフィルタ付きでページングして取得する。
     */
    Page<CommitteeEntity> findByOrganizationIdAndStatus(Long organizationId, CommitteeStatus status, Pageable pageable);

    /**
     * 同一組織内に同名の委員会が存在するか確認する。
     */
    boolean existsByOrganizationIdAndName(Long organizationId, String name);
}
