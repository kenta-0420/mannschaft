package com.mannschaft.app.filesharing.repository;

import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ファイルバージョンリポジトリ。
 */
public interface SharedFileVersionRepository extends JpaRepository<SharedFileVersionEntity, Long> {

    /**
     * ファイルの全バージョンを降順で取得する。
     */
    List<SharedFileVersionEntity> findByFileIdOrderByVersionNumberDesc(Long fileId);

    /**
     * ファイルの特定バージョンを取得する。
     */
    Optional<SharedFileVersionEntity> findByFileIdAndVersionNumber(Long fileId, Integer versionNumber);

    /**
     * ファイルの最新バージョン番号を取得する。
     */
    Optional<SharedFileVersionEntity> findTopByFileIdOrderByVersionNumberDesc(Long fileId);
}
