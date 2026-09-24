package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.cms.media.BlogMediaScope;
import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.StorageErrorCode;
import com.mannschaft.app.common.storage.acl.StorageAccessService;
import com.mannschaft.app.common.storage.acl.StorageAclService;
import com.mannschaft.app.common.storage.quota.StorageFeatureType;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** 記事複製でメディア実体と台帳を分離し、元記事の binding を共有しない。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogMediaCopyService {
    private final BlogBodyMediaResolver bodyResolver;
    private final BlogMediaUploadRepository mediaRepository;
    private final StorageAccessService accessService;
    private final StorageAclService aclService;
    private final R2StorageService storageService;
    private final StorageQuotaService quotaService;
    private final BlogMediaCopyCleanupService cleanupService;

    /** 呼び出し元は元記事の書込権限を検証し、同一スコープの複製先記事を保存済みであること。 */
    @Transactional
    void copyMedia(BlogPostEntity original, BlogPostEntity copy, Long actorId) {
        var keys = new LinkedHashSet<>(bodyResolver.extractR2Keys(original.getBody()));
        if (original.getCoverImageUrl() != null && original.getCoverImageUrl().startsWith("blog/")) {
            keys.add(original.getCoverImageUrl());
        }
        if (keys.isEmpty()) {
            return;
        }
        BlogMediaScope scope = BlogMediaScope.of(copy.getTeamId(), copy.getOrganizationId(), copy.getUserId());
        if (scope == null || !scope.equals(BlogMediaScope.of(original.getTeamId(),
                original.getOrganizationId(), original.getUserId())) || copy.getId() == null) {
            throw new BusinessException(StorageErrorCode.ACL_NOT_FOUND);
        }
        var originals = mediaRepository.findForPostBinding(keys).stream()
                .collect(Collectors.toMap(BlogMediaUploadEntity::getS3Key, media -> media));
        long totalSize = 0;
        for (String key : keys) {
            BlogMediaUploadEntity media = originals.get(key);
            if (media == null || !Objects.equals(original.getId(), media.getBlogPostId())
                    || !scope.equals(BlogMediaAclService.storedScope(media))) {
                throw new BusinessException(StorageErrorCode.ACL_NOT_FOUND);
            }
            var target = BlogMediaAclService.targetOf(media);
            // 元記事の認可に加え、PENDING・失効・別親/binding を複製元に使わせない。
            accessService.generateDownloadUrl(key, target.scope(), target.parent(), target.binding(),
                    Duration.ofMinutes(1));
            totalSize = Math.addExact(totalSize, media.getFileSize());
        }
        quotaService.checkQuota(scope.scopeType(), scope.scopeId(), totalSize);
        var copiedKeys = new ArrayList<String>();
        AtomicBoolean compensated = new AtomicBoolean();
        Runnable compensate = () -> {
            if (compensated.compareAndSet(false, true)) {
                for (String key : copiedKeys) {
                    try {
                        cleanupService.cleanup(key, scope);
                    } catch (RuntimeException failure) {
                        log.error("ブログ複製の補償削除を記録できませんでした: key={}", key, failure);
                    }
                }
            }
        };
        boolean synchronizedTransaction = TransactionSynchronizationManager.isSynchronizationActive();
        if (synchronizedTransaction) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        compensate.run();
                    }
                }
            });
        }
        try {
            Map<String, String> replacements = new LinkedHashMap<>();
            for (String key : keys) {
                BlogMediaUploadEntity source = originals.get(key);
                String newKey = "blog/" + scope.scopeType().name() + "/" + scope.scopeId() + "/" + UUID.randomUUID();
                BlogMediaUploadEntity saved = mediaRepository.save(BlogMediaUploadEntity.builder()
                        .blogPostId(copy.getId()).uploaderId(actorId).scopeType(scope.scopeType().name())
                        .scopeId(scope.scopeId()).s3Key(newKey).mediaType(source.getMediaType())
                        .contentType(source.getContentType()).fileSize(source.getFileSize())
                        .processingStatus(source.getProcessingStatus()).build());
                var target = BlogMediaAclService.targetOf(saved);
                aclService.registerPending(newKey, actorId, target.scope(), saved.getContentType(),
                        Duration.ofHours(1), target.parent());
                copiedKeys.add(newKey);
                storageService.copyObject(key, newKey);
                aclService.claimPending(newKey, actorId, target.scope(), target.parent(), target.binding());
                quotaService.recordUpload(scope.scopeType(), scope.scopeId(), saved.getFileSize(),
                        StorageFeatureType.CMS, BlogMediaService.REFERENCE_TYPE, saved.getId(), actorId);
                replacements.put(key, newKey);
            }
            String body = original.getBody() == null ? null : bodyResolver.replaceKeys(original.getBody(), replacements);
            String cover = replacements.getOrDefault(original.getCoverImageUrl(), original.getCoverImageUrl());
            copy.update(copy.getTitle(), copy.getSlug(), body, copy.getExcerpt(), cover, copy.getVisibility(),
                    copy.getPriority(), copy.getReadingTimeMinutes());
        } catch (RuntimeException failure) {
            if (!synchronizedTransaction) {
                compensate.run();
            }
            throw failure;
        }
    }
}
