package com.mannschaft.app.filesharing.repository;

import com.mannschaft.app.filesharing.entity.SharedFileTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ファイルタグリポジトリ。
 */
public interface SharedFileTagRepository extends JpaRepository<SharedFileTagEntity, Long> {

    /**
     * ファイルのタグ一覧を取得する。
     */
    List<SharedFileTagEntity> findByFileIdOrderByTagNameAsc(Long fileId);

    /**
     * ファイルIDとタグ名とユーザーIDでタグを取得する。
     */
    Optional<SharedFileTagEntity> findByFileIdAndTagNameAndUserId(Long fileId, String tagName, Long userId);

    /**
     * ファイルIDとタグ名とユーザーIDでタグが存在するか確認する。
     */
    boolean existsByFileIdAndTagNameAndUserId(Long fileId, String tagName, Long userId);

    /**
     * ユーザーのタグ一覧を取得する。
     */
    List<SharedFileTagEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
}
