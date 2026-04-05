package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.BlogTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ブログタグリポジトリ。
 */
public interface BlogTagRepository extends JpaRepository<BlogTagEntity, Long> {

    List<BlogTagEntity> findByTeamIdOrderBySortOrderAsc(Long teamId);

    List<BlogTagEntity> findByOrganizationIdOrderBySortOrderAsc(Long organizationId);

    Optional<BlogTagEntity> findByTeamIdAndName(Long teamId, String name);

    Optional<BlogTagEntity> findByOrganizationIdAndName(Long organizationId, String name);
}
