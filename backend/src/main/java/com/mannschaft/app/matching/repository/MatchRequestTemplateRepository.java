package com.mannschaft.app.matching.repository;

import com.mannschaft.app.matching.entity.MatchRequestTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 募集テンプレートリポジトリ。
 */
public interface MatchRequestTemplateRepository extends JpaRepository<MatchRequestTemplateEntity, Long> {

    /**
     * チームのテンプレート一覧を取得する。
     */
    List<MatchRequestTemplateEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    /**
     * チームのテンプレート数をカウントする。
     */
    long countByTeamId(Long teamId);
}
