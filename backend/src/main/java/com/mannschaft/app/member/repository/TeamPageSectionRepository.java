package com.mannschaft.app.member.repository;

import com.mannschaft.app.member.entity.TeamPageSectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ページセクションリポジトリ。
 */
public interface TeamPageSectionRepository extends JpaRepository<TeamPageSectionEntity, Long> {

    /**
     * ページ内セクションを表示順で取得する。
     */
    List<TeamPageSectionEntity> findByTeamPageIdOrderBySortOrder(Long teamPageId);

    /**
     * ページIDで全セクションを削除する。
     */
    void deleteByTeamPageId(Long teamPageId);
}
