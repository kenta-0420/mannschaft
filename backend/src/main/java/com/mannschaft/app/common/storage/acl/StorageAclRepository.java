package com.mannschaft.app.common.storage.acl;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Storage ACL 台帳の検索と、条件付き claim 更新を提供するリポジトリ。 */
public interface StorageAclRepository extends JpaRepository<StorageAclEntity, UUID> {

    Optional<StorageAclEntity> findByFileKey(String fileKey);

    /**
     * 一覧表示の ACL 照合用。呼び出し側は入力順の復元と認可外項目の除外を担う。
     */
    List<StorageAclEntity> findByFileKeyIn(Collection<String> fileKeys);

    /**
     * PENDING かつ未期限切れの行だけを一度だけ添付先へ束縛する。
     * read-then-write を避け、競合する claim の勝者を DB 条件で一意にする。
     */
    @Modifying
    @Query(value = """
            UPDATE storage_acls
               SET status = 'CLAIMED', attachment_binding_type = :bindingType,
                   attachment_binding_key = :bindingKey, updated_at = UTC_TIMESTAMP()
             WHERE file_key = :fileKey
               AND owner_id = :ownerId
               AND scope_type = :scopeType
               AND scope_key = :scopeKey
               AND acl_mode = 'CONTENT_BOUND'
               AND parent_content_reference_type = :parentType
               AND parent_content_reference_key = :parentKey
               AND status = 'PENDING'
               AND expires_at > UTC_TIMESTAMP()
               AND parent_content_reference_type IS NOT NULL
               AND parent_content_reference_key IS NOT NULL
               AND attachment_binding_type IS NULL
               AND attachment_binding_key IS NULL
            """, nativeQuery = true)
    int claimPending(@Param("fileKey") String fileKey,
                     @Param("ownerId") Long ownerId,
                     @Param("scopeType") String scopeType,
                     @Param("scopeKey") String scopeKey,
                     @Param("parentType") String parentType,
                     @Param("parentKey") String parentKey,
                     @Param("bindingType") String bindingType,
                     @Param("bindingKey") String bindingKey);

    /** 添付そのものに束縛された ACL だけを失効させる。親やスコープ単位では解放しない。 */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE storage_acls
               SET status = 'REVOKED', updated_at = UTC_TIMESTAMP()
             WHERE BINARY file_key = BINARY :fileKey
               AND acl_mode = 'CONTENT_BOUND'
               AND status = 'CLAIMED'
               AND BINARY attachment_binding_type = BINARY :bindingType
               AND BINARY attachment_binding_key = BINARY :bindingKey
            """, nativeQuery = true)
    int releaseClaimed(@Param("fileKey") String fileKey,
                       @Param("bindingType") String bindingType,
                       @Param("bindingKey") String bindingKey);

    /**
     * 解放の再送判定は current read で行う。JPA の管理済み entity や RR の過去 snapshot を使わない。
     */
    @Query(value = """
            SELECT file_key FROM storage_acls
             WHERE BINARY file_key = BINARY :fileKey
               AND acl_mode = 'CONTENT_BOUND'
               AND status = 'REVOKED'
               AND BINARY attachment_binding_type = BINARY :bindingType
               AND BINARY attachment_binding_key = BINARY :bindingKey
             FOR UPDATE
            """, nativeQuery = true)
    Optional<String> findReleasedFileKey(@Param("fileKey") String fileKey,
                                         @Param("bindingType") String bindingType,
                                         @Param("bindingKey") String bindingKey);
}
