package com.mannschaft.app.committee.repository;

import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 委員会メンバーリポジトリ。
 */
public interface CommitteeMemberRepository extends JpaRepository<CommitteeMemberEntity, Long> {

    /**
     * 現役メンバーであるか確認する。
     */
    boolean existsByCommitteeIdAndUserIdAndLeftAtIsNull(Long committeeId, Long userId);

    /**
     * 現役メンバー一覧を取得する。
     */
    List<CommitteeMemberEntity> findByCommitteeIdAndLeftAtIsNull(Long committeeId);

    /**
     * 現役の指定ロールのメンバー数をカウントする。
     */
    long countByCommitteeIdAndRoleAndLeftAtIsNull(Long committeeId, CommitteeRole role);

    /**
     * ユーザーの現役メンバーシップを取得する。
     */
    Optional<CommitteeMemberEntity> findByCommitteeIdAndUserIdAndLeftAtIsNull(Long committeeId, Long userId);

    /**
     * ユーザーが現役として所属する委員会のID一覧を取得する。
     */
    @Query("SELECT m.committeeId FROM CommitteeMemberEntity m WHERE m.userId = :userId AND m.leftAt IS NULL")
    List<Long> findActiveCommitteeIdsByUserId(@Param("userId") Long userId);
}
