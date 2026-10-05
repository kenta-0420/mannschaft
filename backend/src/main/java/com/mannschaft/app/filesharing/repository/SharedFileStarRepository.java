package com.mannschaft.app.filesharing.repository;

import com.mannschaft.app.filesharing.entity.SharedFileStarEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** 強匿名化専用。対象利用者の行を論理削除済みも含めて物理削除する。 */
    @Modifying
    @Query(value = "DELETE FROM shared_file_stars WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") Long userId);
}
