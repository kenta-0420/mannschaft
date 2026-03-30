package com.mannschaft.app.skill.repository;

import com.mannschaft.app.skill.entity.MemberSkillEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * メンバースキル・資格リポジトリ。
 */
public interface MemberSkillRepository extends JpaRepository<MemberSkillEntity, Long> {

    /**
     * ユーザーIDとスコープに紐づく資格一覧（未削除）を取得する。
     */
    List<MemberSkillEntity> findByUserIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(Long userId, String scopeType, Long scopeId);

    /**
     * IDで資格を取得する（未削除）。
     */
    Optional<MemberSkillEntity> findByIdAndDeletedAtIsNull(Long id);
}
