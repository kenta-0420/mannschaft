package com.mannschaft.app.common.storage.acl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Presign 時点の ACL 台帳登録を担う共通サービス。保存処理での認可判定は後続 Phase で行う。 */
@Service
@RequiredArgsConstructor
public class StorageAclService {

    private final StorageAclRepository repository;
    @org.springframework.beans.factory.annotation.Qualifier("utcClock")
    private final Clock clock;

    /**
     * サーバー採番キーを PENDING として登録する。
     * ACL モードを省略した場合は CONTENT_BOUND とする。
     */
    @Transactional
    public void registerPending(String fileKey, Long ownerId, String scopeType, Long scopeId,
                                            String contentType, Duration ttl,
                                            String referenceType, Long referenceId) {
        if (fileKey == null || fileKey.isBlank() || ownerId == null || scopeType == null || scopeType.isBlank()
                || scopeId == null || contentType == null || contentType.isBlank() || ttl == null
                || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ACL登録に必要な値が不足しています");
        }
        if (repository.findByFileKey(fileKey).isPresent()) {
            throw new IllegalStateException("ストレージキーは既にACL台帳へ登録されています");
        }
        StorageAclEntity entity = StorageAclEntity.builder()
                .fileKey(fileKey)
                .ownerId(ownerId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .aclMode(StorageAclMode.CONTENT_BOUND)
                .contentType(contentType)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .status(StorageAclStatus.PENDING)
                .expiresAt(Instant.now(clock).plus(ttl))
                .build();
        repository.save(entity);
    }
}
