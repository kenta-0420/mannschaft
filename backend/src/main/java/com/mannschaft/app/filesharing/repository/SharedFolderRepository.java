package com.mannschaft.app.filesharing.repository;

import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 共有フォルダリポジトリ。
 */
public interface SharedFolderRepository extends JpaRepository<SharedFolderEntity, Long> {

    /**
     * チームのルートフォルダ一覧を取得する。
     */
    List<SharedFolderEntity> findByTeamIdAndParentIdIsNullOrderByNameAsc(Long teamId);

    /**
     * 組織のルートフォルダ一覧を取得する。
     */
    List<SharedFolderEntity> findByOrganizationIdAndParentIdIsNullOrderByNameAsc(Long organizationId);

    /**
     * ユーザーのルートフォルダ一覧を取得する。
     */
    List<SharedFolderEntity> findByUserIdAndScopeTypeAndParentIdIsNullOrderByNameAsc(Long userId, FileScopeType scopeType);

    /**
     * 子フォルダ一覧を取得する。
     */
    List<SharedFolderEntity> findByParentIdOrderByNameAsc(Long parentId);

    /**
     * 同一親配下の同名フォルダが存在するか確認する。
     */
    boolean existsByParentIdAndName(Long parentId, String name);

    /**
     * チームIDとフォルダIDで取得する。
     */
    Optional<SharedFolderEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * 組織IDとフォルダIDで取得する。
     */
    Optional<SharedFolderEntity> findByIdAndOrganizationId(Long id, Long organizationId);
}
