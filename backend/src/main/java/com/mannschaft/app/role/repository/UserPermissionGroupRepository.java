package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ユーザー−パーミッショングループ割当リポジトリ。
 */
public interface UserPermissionGroupRepository extends JpaRepository<UserPermissionGroupEntity, Long> {

    Optional<UserPermissionGroupEntity> findByUserIdAndGroupId(Long userId, Long groupId);

    List<UserPermissionGroupEntity> findByUserId(Long userId);

    void deleteByUserIdAndGroupIdIn(Long userId, List<Long> groupIds);

    @Query("select u.userId from UserPermissionGroupEntity u where u.groupId in :groupIds")
    List<Long> findUserIdsByGroupIdIn(@Param("groupIds") List<Long> groupIds);
}
