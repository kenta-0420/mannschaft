package com.mannschaft.app.role;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ユーザー−パーミッショングループ割当リポジトリ。
 */
public interface UserPermissionGroupRepository extends JpaRepository<UserPermissionGroupEntity, Long> {

    Optional<UserPermissionGroupEntity> findByUserIdAndGroupId(Long userId, Long groupId);

    List<UserPermissionGroupEntity> findByUserId(Long userId);

    void deleteByUserIdAndGroupIdIn(Long userId, List<Long> groupIds);
}
