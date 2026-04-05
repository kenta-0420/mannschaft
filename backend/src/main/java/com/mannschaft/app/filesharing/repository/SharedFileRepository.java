package com.mannschaft.app.filesharing.repository;

import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 共有ファイルリポジトリ。
 */
public interface SharedFileRepository extends JpaRepository<SharedFileEntity, Long> {

    /**
     * フォルダ内のファイル一覧を取得する。
     */
    List<SharedFileEntity> findByFolderIdOrderByNameAsc(Long folderId);

    /**
     * フォルダ内のファイル一覧をページングで取得する。
     */
    Page<SharedFileEntity> findByFolderIdOrderByNameAsc(Long folderId, Pageable pageable);

    /**
     * フォルダ内のファイル数を取得する。
     */
    long countByFolderId(Long folderId);
}
