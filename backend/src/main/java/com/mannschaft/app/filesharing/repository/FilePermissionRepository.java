package com.mannschaft.app.filesharing.repository;

import com.mannschaft.app.filesharing.entity.FilePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ファイル権限リポジトリ。
 */
public interface FilePermissionRepository extends JpaRepository<FilePermissionEntity, Long> {

    /**
     * 対象（ファイルまたはフォルダ）の権限一覧を取得する。
     */
    List<FilePermissionEntity> findByTargetTypeAndTargetId(String targetType, Long targetId);

    /**
     * 対象の権限を削除する。
     */
    void deleteByTargetTypeAndTargetId(String targetType, Long targetId);

    /**
     * 特定の権限付与対象の権限を取得する。
     */
    List<FilePermissionEntity> findByPermissionTargetTypeAndPermissionTargetId(
            com.mannschaft.app.filesharing.PermissionTargetType permissionTargetType, Long permissionTargetId);
}
