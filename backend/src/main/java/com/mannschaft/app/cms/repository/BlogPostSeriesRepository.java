package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.entity.BlogPostSeriesEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ブログ連載シリーズリポジトリ。
 */
public interface BlogPostSeriesRepository extends JpaRepository<BlogPostSeriesEntity, Long> {

    List<BlogPostSeriesEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    List<BlogPostSeriesEntity> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);
}
