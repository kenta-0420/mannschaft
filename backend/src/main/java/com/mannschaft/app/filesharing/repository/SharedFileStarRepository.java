package com.mannschaft.app.filesharing.repository;

import com.mannschaft.app.filesharing.entity.SharedFileStarEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ファイルスターリポジトリ。
 */
public interface SharedFileStarRepository extends JpaRepository<SharedFileStarEntity, Long> {

    /**
     * ファイルIDとユーザーIDでスターを取得する。
     */
    Optional<SharedFileStarEntity> findByFileIdAndUserId(Long fileId, Long userId);

    /**
     * ファイルIDとユーザーIDでスターが存在するか確認する。
     */
    boolean existsByFileIdAndUserId(Long fileId, Long userId);

    /**
     * ユーザーのスター一覧を取得する。
     */
    List<SharedFileStarEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * ファイルのスター数を取得する。
     */
    long countByFileId(Long fileId);
}
