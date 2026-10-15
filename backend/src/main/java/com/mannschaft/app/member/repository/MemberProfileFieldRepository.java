package com.mannschaft.app.member.repository;

import com.mannschaft.app.member.entity.MemberProfileFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * プロフィールフィールド定義リポジトリ。
 */
public interface MemberProfileFieldRepository extends JpaRepository<MemberProfileFieldEntity, Long> {

    /**
     * チーム別フィールド一覧を表示順で取得する。
     */
    List<MemberProfileFieldEntity> findByTeamIdOrderBySortOrder(Long teamId);

    /**
     * 組織別フィールド一覧を表示順で取得する。
     */
    List<MemberProfileFieldEntity> findByOrganizationIdOrderBySortOrder(Long organizationId);

    /**
     * チーム別の有効なフィールド一覧を取得する。
     */
    List<MemberProfileFieldEntity> findByTeamIdAndIsActiveTrueOrderBySortOrder(Long teamId);

    /**
     * 組織別の有効なフィールド一覧を取得する。
     */
    List<MemberProfileFieldEntity> findByOrganizationIdAndIsActiveTrueOrderBySortOrder(Long organizationId);
}
