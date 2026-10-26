package com.mannschaft.app.common.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;

/**
 * テナント（organization_id）スコープを意識したリポジトリ基底インターフェース。
 * 将来のシャーディングで organization_id をルーティングキーとして使う布石。
 *
 * @param <T>  エンティティ型
 * @param <ID> ID型
 */
@NoRepositoryBean
public interface AbstractTenantAwareRepository<T, ID> extends JpaRepository<T, ID> {

    List<T> findByOrganizationIdAndDeletedAtIsNull(Long organizationId);

    Page<T> findByOrganizationIdAndDeletedAtIsNull(Long organizationId, Pageable pageable);

    Optional<T> findByIdAndOrganizationIdAndDeletedAtIsNull(ID id, Long organizationId);

    long countByOrganizationIdAndDeletedAtIsNull(Long organizationId);
}
