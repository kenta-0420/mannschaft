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

/** Presigned upload のサーバー採番キーと、その所有・認可境界を記録する共通台帳。 */
@Entity
@Table(name = "storage_acls", indexes = {
        @Index(name = "idx_storage_acls_file_key", columnList = "file_key", unique = true),
        @Index(name = "idx_storage_acls_owner", columnList = "owner_id"),
        @Index(name = "idx_storage_acls_scope", columnList = "scope_type,scope_id"),
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

    @Column(name = "scope_type", nullable = false, length = 32)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "acl_mode", nullable = false, length = 24)
    private StorageAclMode aclMode;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "reference_type", length = 64)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StorageAclStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
