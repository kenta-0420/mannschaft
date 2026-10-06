package com.mannschaft.app.dashboard.repository;

import com.mannschaft.app.dashboard.entity.ChatContactFolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * チャット・連絡先カスタムフォルダのリポジトリ。
 */
public interface ChatContactFolderRepository extends JpaRepository<ChatContactFolderEntity, Long> {

    /**
     * 指定ユーザーのフォルダ一覧を並び順で取得する。
     */
    List<ChatContactFolderEntity> findByUserIdOrderBySortOrder(Long userId);

    /**
     * 指定ユーザーのフォルダ数を返す。
     */
    long countByUserId(Long userId);

    /**
     * 指定ユーザー × フォルダ名で存在チェックする。
     */
    boolean existsByUserIdAndName(Long userId, String name);

    /**
     * 指定ユーザー × フォルダ名で存在チェックする（自身を除外、更新時の重複チェック用）。
     */
    boolean existsByUserIdAndNameAndIdNot(Long userId, String name, Long id);

    /**
     * フォルダIDとユーザーIDで取得する（所有者検証用）。
     */
    Optional<ChatContactFolderEntity> findByIdAndUserId(Long id, Long userId);

    /** 強匿名化専用。対象利用者の行を論理削除済みも含めて物理削除する。 */
    @Modifying
    @Query(value = "DELETE FROM chat_contact_folders WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") Long userId);
}
