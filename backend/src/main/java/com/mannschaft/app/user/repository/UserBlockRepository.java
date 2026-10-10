package com.mannschaft.app.user.repository;

import com.mannschaft.app.user.entity.UserBlockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ユーザーブロックリポジトリ。
 */
public interface UserBlockRepository extends JpaRepository<UserBlockEntity, Long> {

    /**
     * ブロック関係が存在するか確認する。
     */
    boolean existsByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    /**
     * ブロッカーが作成したブロック一覧を取得する。
     */
    List<UserBlockEntity> findByBlockerId(Long blockerId);

    /**
     * ブロック関係を削除する。
     */
    @Modifying
    @Transactional
    void deleteByBlockerIdAndBlockedId(Long blockerId, Long blockedId);

    /**
     * 逆方向のブロック関係が存在するか確認する（blockedId 視点）。
     */
    boolean existsByBlockedIdAndBlockerId(Long blockedId, Long blockerId);

    /** 強匿名化専用。対象利用者の行を論理削除済みも含めて物理削除する。 */
    @Modifying
    @Query(value = "DELETE FROM user_blocks WHERE blocker_id = :userId", nativeQuery = true)
    int deleteByBlockerId(@Param("userId") Long userId);

    /** 強匿名化専用。対象利用者の行を論理削除済みも含めて物理削除する。 */
    @Modifying
    @Query(value = "DELETE FROM user_blocks WHERE blocked_id = :userId", nativeQuery = true)
    int deleteByBlockedId(@Param("userId") Long userId);
}
