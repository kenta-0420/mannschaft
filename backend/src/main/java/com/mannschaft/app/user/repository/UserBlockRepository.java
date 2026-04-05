package com.mannschaft.app.user.repository;

import com.mannschaft.app.user.entity.UserBlockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
}
