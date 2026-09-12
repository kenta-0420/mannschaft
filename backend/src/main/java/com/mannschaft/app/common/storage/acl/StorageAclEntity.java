package com.mannschaft.app.common.storage.acl;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/** Presigned upload の所有境界、親認可参照、添付束縛先を分離して記録する ACL 台帳。 */
@Entity
@Table(name = "storage_acls", indexes = {
        @Index(name = "idx_storage_acls_file_key", columnList = "file_key", unique = true),
        @Index(name = "idx_storage_acls_owner", columnList = "owner_id"),
        @Index(name = "idx_storage_acls_scope_key", columnList = "scope_type,scope_key"),
        @Index(name = "idx_storage_acls_expires", columnList = "expires_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class StorageAclEntity extends UuidV7Entity {

    @Column(name = "file_key", nullable = false, length = 500, unique = true)
    private String fileKey;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 32)
    private StorageAclScopeType scopeType;

    @Column(name = "scope_key", nullable = false, length = 64)
    private String scopeKey;

    /** V192 互換の数値scope。新typed ACLでは使用しない。 */
    @Column(name = "scope_id")
    private Long legacyScopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "acl_mode", nullable = false, length = 24)
    private StorageAclMode aclMode;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    /** V192 互換の監査参照。新typed ACLでは使用しない。 */
    @Column(name = "reference_type", length = 64)
    private String legacyReferenceType;

    /** V192 互換の監査参照。新typed ACLでは使用しない。 */
    @Column(name = "reference_id")
    private Long legacyReferenceId;

    @Column(name = "parent_content_reference_type", length = 64)
    private String parentContentReferenceType;

    @Column(name = "parent_content_reference_key", length = 64)
    private String parentContentReferenceKey;

    @Column(name = "attachment_binding_type", length = 64)
    private String attachmentBindingType;

    @Column(name = "attachment_binding_key", length = 64)
    private String attachmentBindingKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StorageAclStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
