package com.mannschaft.app.organization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 組織リポジトリ。
 */
public interface OrganizationRepository extends JpaRepository<OrganizationEntity, Long> {

    List<OrganizationEntity> findByVisibility(OrganizationEntity.Visibility visibility);

    boolean existsByName(String name);

    @Query("SELECT o FROM OrganizationEntity o WHERE o.name LIKE %:keyword% OR o.nameKana LIKE %:keyword%")
    Page<OrganizationEntity> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
