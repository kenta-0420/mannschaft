package com.mannschaft.app.common.storage.acl;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/** Presigned upload の ACL 登録と、添付先への原子的 claim を担う共通サービス。 */
@Service
@RequiredArgsConstructor
public class StorageAclService {

    private final StorageAclRepository repository;
    @org.springframework.beans.factory.annotation.Qualifier("utcClock")
    private final Clock clock;

    /** CONTENT_BOUND の PENDING ACL を登録する。親コンテンツ参照は添付束縛先に使わない。 */
    @Transactional
    public void registerPending(String fileKey, Long ownerId, StorageAclScope scope, String contentType,
                                Duration ttl, StorageAclContentReference parentContentReference) {
        validatePendingArguments(fileKey, ownerId, scope, contentType, ttl);
        if (parentContentReference == null) {
            throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST);
        }
        if (repository.findByFileKey(fileKey).isPresent()) {
            throw new BusinessException(StorageErrorCode.ACL_CLAIM_CONFLICT);
        }
        repository.save(StorageAclEntity.builder()
                .fileKey(fileKey)
                .ownerId(ownerId)
                .scopeType(scope.type())
                .scopeKey(scope.scopeKey())
                .aclMode(StorageAclMode.CONTENT_BOUND)
                .contentType(contentType)
                .parentContentReferenceType(parentContentReference == null ? null : parentContentReference.type())
                .parentContentReferenceKey(parentContentReference == null ? null : parentContentReference.key())
                .status(StorageAclStatus.PENDING)
                .expiresAt(LocalDateTime.now(clock).plus(ttl))
                .build());
    }

    /** 既存 producer の互換入口。課金スコープではなく ACL 所有スコープとして変換する。 */
    @Transactional
    public void registerPending(String fileKey, Long ownerId, String scopeType, Long scopeId,
                                String contentType, Duration ttl, String referenceType, Long referenceId) {
        StorageAclScope scope = legacyScope(scopeType, scopeId, ownerId);
        validatePendingArguments(fileKey, ownerId, scope, contentType, ttl);
        if (repository.findByFileKey(fileKey).isPresent()) {
            throw new BusinessException(StorageErrorCode.ACL_CLAIM_CONFLICT);
        }
        repository.save(StorageAclEntity.builder()
                .fileKey(fileKey)
                .ownerId(ownerId)
                .scopeType(scope.type())
                .scopeKey(scope.scopeKey())
                .legacyScopeId(scopeId)
                .aclMode(StorageAclMode.CONTENT_BOUND)
                .contentType(contentType)
                .legacyReferenceType(referenceType)
                .legacyReferenceId(referenceId)
                .status(StorageAclStatus.PENDING)
                .expiresAt(LocalDateTime.now(clock).plus(ttl))
                .build());
    }

    /**
     * 添付束縛を DB 条件付き更新で確定する。同一束縛だけは post-condition で冪等成功とする。
     */
    @Transactional
    public void claimPending(String fileKey, Long ownerId, StorageAclScope scope, StorageAclAttachmentBinding binding) {
        validateClaimArguments(fileKey, ownerId, scope, binding);
        LocalDateTime now = LocalDateTime.now(clock);
        if (repository.claimPending(fileKey, ownerId, scope.type().name(), scope.scopeKey(), binding.type(), binding.key(), now) == 1) {
            return;
        }
        StorageAclEntity acl = repository.findByFileKey(fileKey)
                .orElseThrow(() -> new BusinessException(StorageErrorCode.ACL_NOT_FOUND));
        if (!ownerId.equals(acl.getOwnerId()) || scope.type() != acl.getScopeType()
                || !scope.scopeKey().equals(acl.getScopeKey())) {
            throw new BusinessException(StorageErrorCode.ACL_FORBIDDEN);
        }
        if (!acl.getExpiresAt().isAfter(now)) {
            throw new BusinessException(StorageErrorCode.ACL_CLAIM_CONFLICT);
        }
        if (isSameClaim(acl, binding)) {
            return;
        }
        throw new BusinessException(StorageErrorCode.ACL_CLAIM_CONFLICT);
    }

    private boolean isSameClaim(StorageAclEntity acl, StorageAclAttachmentBinding binding) {
        return acl.getParentContentReferenceType() != null
                && acl.getParentContentReferenceKey() != null
                && acl.getStatus() == StorageAclStatus.CLAIMED
                && binding.type().equals(acl.getAttachmentBindingType())
                && binding.key().equals(acl.getAttachmentBindingKey());
    }

    private void validatePendingArguments(String fileKey, Long ownerId, StorageAclScope scope, String contentType,
                                          Duration ttl) {
        if (fileKey == null || fileKey.isBlank() || ownerId == null || ownerId <= 0 || scope == null
                || contentType == null || contentType.isBlank() || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST);
        }
        if ((scope.type() == StorageAclScopeType.PERSONAL || scope.type() == StorageAclScopeType.PUBLIC)
                && !scope.scopeKey().equals(String.valueOf(ownerId))) {
            throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST);
        }
    }

    private void validateClaimArguments(String fileKey, Long ownerId, StorageAclScope scope,
                                        StorageAclAttachmentBinding binding) {
        if (fileKey == null || fileKey.isBlank() || ownerId == null || ownerId <= 0 || scope == null || binding == null) {
            throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST);
        }
        if ((scope.type() == StorageAclScopeType.PERSONAL || scope.type() == StorageAclScopeType.PUBLIC)
                && !scope.scopeKey().equals(String.valueOf(ownerId))) {
            throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST);
        }
    }

    private StorageAclScope legacyScope(String scopeType, Long scopeId, Long ownerId) {
        if (scopeType == null || scopeId == null) {
            throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST);
        }
        try {
            return switch (StorageAclScopeType.valueOf(scopeType)) {
                case TEAM -> StorageAclScope.team(scopeId);
                case ORGANIZATION -> StorageAclScope.organization(scopeId);
                case PERSONAL -> StorageAclScope.personal(ownerId);
                case PUBLIC -> StorageAclScope.publicFor(ownerId);
                case VILLAGE -> throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST);
            };
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST, exception);
        }
    }
}
