package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryEntity;
import com.mannschaft.app.cms.media.BlogMediaScope;
import com.mannschaft.app.cms.repository.BlogMediaR2DeleteRetryRepository;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.util.SessionHashUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 複製トランザクションが失敗した際の外部オブジェクト削除を独立して記録する。 */
@Service
@RequiredArgsConstructor
public class BlogMediaCopyCleanupService {
    private final R2StorageService storageService;
    private final BlogMediaR2DeleteRetryRepository retryRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cleanup(String fileKey, BlogMediaScope scope) {
        try {
            storageService.delete(fileKey);
        } catch (RuntimeException failure) {
            String hash = SessionHashUtil.hash(fileKey);
            if (retryRepository.findByObjectKeyHash(hash).isPresent()) {
                return;
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            // 使用量加算もロールバック済みなので、再試行で既存使用量を減算してはいけない。
            retryRepository.save(BlogMediaR2DeleteRetryEntity.builder()
                    .objectKey(fileKey).objectKeyHash(hash).fileSize(0L)
                    .scopeType(scope.scopeType().name()).scopeId(String.valueOf(scope.scopeId()))
                    .nextAttemptAt(now).createdAt(now).updatedAt(now)
                    .lastError("複製失敗後のオブジェクト削除に失敗しました").build());
        }
    }
}
