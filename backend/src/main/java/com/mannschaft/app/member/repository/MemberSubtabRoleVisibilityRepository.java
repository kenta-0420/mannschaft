package com.mannschaft.app.member.repository;

import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.member.entity.MemberSubtabRoleVisibilityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * CMP-260919-1140 Phase 1: メンバーサブタブのロール別可視性設定リポジトリ。
 */
public interface MemberSubtabRoleVisibilityRepository extends JpaRepository<MemberSubtabRoleVisibilityEntity, Long> {

    List<MemberSubtabRoleVisibilityEntity> findByScopeTypeAndScopeId(ScopeType scopeType, Long scopeId);

    Optional<MemberSubtabRoleVisibilityEntity> findByScopeTypeAndScopeIdAndSubtabKey(
            ScopeType scopeType, Long scopeId, String subtabKey);

    void deleteByScopeTypeAndScopeIdAndSubtabKey(ScopeType scopeType, Long scopeId, String subtabKey);
}
