package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.dto.BlogMediaUploadUrlRequest;
import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.cms.media.BlogMediaScope;
import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.storage.StorageErrorCode;
import com.mannschaft.app.common.storage.acl.MultipartContentTarget;
import com.mannschaft.app.common.storage.acl.MultipartContentTargetResolver;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** ブログメディアの保存スコープ・親記事認可・記事への一度限りの紐付けを担う。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogMediaAclService implements MultipartContentTargetResolver {
    private final BlogMediaUploadRepository mediaRepository;
    private final BlogPostRepository postRepository;
    private final AccessControlService accessControlService;
    private final BlogBodyMediaResolver bodyMediaResolver;

    /** 記事があれば保存スコープを正本とし、未保存なら要求スコープの投稿権限を確認する。 */
    public BlogMediaScope resolveUploadScope(Long uploaderId, BlogMediaUploadUrlRequest request) {
        BlogMediaScope requested;
        try {
            requested = new BlogMediaScope(StorageScopeType.valueOf(request.getScopeType().toUpperCase()),
                    request.getScopeId());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST, exception);
        }
        if (request.getBlogPostId() != null) {
            BlogPostEntity post = findPost(request.getBlogPostId());
            checkWriteAccess(post, uploaderId);
            BlogMediaScope actual = scopeOf(post);
            if (!actual.equals(requested)) {
                throw new BusinessException(StorageErrorCode.ACL_NOT_FOUND);
            }
            checkScopeMembership(actual, uploaderId);
            return actual;
        }
        checkScopeMembership(requested, uploaderId);
        return requested;
    }

    @Override
    public Optional<MultipartContentTarget> resolveMultipartTarget(String fileKey, Long uploaderId) {
        if (!fileKey.startsWith("blog/")) {
            return Optional.empty();
        }
        return mediaRepository.findByS3Key(fileKey).map(media -> {
            if (!fileKey.equals(media.getS3Key()) || !Objects.equals(uploaderId, media.getUploaderId())) {
                throw new BusinessException(StorageErrorCode.ACL_NOT_FOUND);
            }
            BlogMediaScope scope = storedScope(media);
            checkScopeMembership(scope, uploaderId);
            if (media.getBlogPostId() != null) {
                BlogPostEntity post = findPost(media.getBlogPostId());
                checkWriteAccess(post, uploaderId);
                if (!scope.equals(scopeOf(post))) {
                    throw new BusinessException(StorageErrorCode.ACL_NOT_FOUND);
                }
            }
            return targetOf(media);
        });
    }

    /** 記事本文のキーを同じスコープの保存台帳へ結び、別記事からの付け替えを拒否する。 */
    @Transactional
    void bindBodyMedia(BlogPostEntity post, Long actorId) {
        var keys = bodyMediaResolver.extractR2Keys(post.getBody());
        if (keys.isEmpty()) {
            return;
        }
        var media = mediaRepository.findForPostBinding(keys);
        var byKey = media.stream().collect(java.util.stream.Collectors.toMap(
                BlogMediaUploadEntity::getS3Key, entry -> entry));
        for (String key : keys) {
            BlogMediaUploadEntity entry = byKey.get(key);
            if (entry == null || !scopeOf(post).equals(storedScope(entry))
                    || (entry.getBlogPostId() != null && !post.getId().equals(entry.getBlogPostId()))
                    || (entry.getBlogPostId() == null && !Objects.equals(actorId, entry.getUploaderId()))) {
                throw new BusinessException(StorageErrorCode.ACL_NOT_FOUND);
            }
            entry.linkToPost(post.getId());
            mediaRepository.save(entry);
        }
    }

    static MultipartContentTarget targetOf(BlogMediaUploadEntity media) {
        return com.mannschaft.app.cms.media.BlogMediaAclTarget.from(media);
    }

    static BlogMediaScope storedScope(BlogMediaUploadEntity media) {
        if (media.getScopeType() == null || media.getScopeId() == null || media.getId() == null) {
            throw new BusinessException(StorageErrorCode.ACL_NOT_FOUND);
        }
        return new BlogMediaScope(StorageScopeType.valueOf(media.getScopeType()), media.getScopeId());
    }

    private BlogMediaScope scopeOf(BlogPostEntity post) {
        BlogMediaScope scope = BlogMediaScope.of(post.getTeamId(), post.getOrganizationId(), post.getUserId());
        if (scope == null) {
            throw new BusinessException(StorageErrorCode.ACL_NOT_FOUND);
        }
        return scope;
    }

    private BlogPostEntity findPost(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(StorageErrorCode.ACL_NOT_FOUND));
    }

    private void checkWriteAccess(BlogPostEntity post, Long actorId) {
        if (Objects.equals(actorId, post.getAuthorId())) {
            return;
        }
        BlogMediaScope scope = scopeOf(post);
        if (scope.scopeType() == StorageScopeType.PERSONAL) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        accessControlService.checkAdminOrAbove(actorId, scope.scopeId(), scope.scopeType().name());
    }

    private void checkScopeMembership(BlogMediaScope scope, Long actorId) {
        if (scope.scopeType() == StorageScopeType.PERSONAL) {
            if (!Objects.equals(actorId, scope.scopeId())) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
        } else if (scope.scopeType() == StorageScopeType.TEAM
                || scope.scopeType() == StorageScopeType.ORGANIZATION) {
            accessControlService.checkMembership(actorId, scope.scopeId(), scope.scopeType().name());
        } else {
            throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST);
        }
    }
}
