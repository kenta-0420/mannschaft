package com.mannschaft.app.common.storage.acl;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageErrorCode;
import com.mannschaft.app.common.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * CONTENT_BOUND ACL を唯一の入口として保護ダウンロード URL を発行するサービス。
 *
 * <p>ドメイン側の閲覧認可を通過した後で、file key・期待スコープ・親コンテンツ参照・
 * attachment binding の全てを台帳と照合する。未知キー、PENDING、別テナントの scope、
 * 親または binding の不一致は同じ {@link StorageErrorCode#ACL_NOT_FOUND} に正規化し、
 * 存在有無を開示しない。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StorageAccessService {

    private final StorageAclRepository repository;
    private final StorageService storageService;

    /**
     * 単一 attachment 向けの厳格 API。照合に失敗した場合は必ず 404 相当で失敗する。
     */
    public String generateDownloadUrl(String fileKey, StorageAclScope expectedScope,
                                      StorageAclContentReference expectedParent,
                                      StorageAclAttachmentBinding expectedBinding,
                                      Duration requestedTtl) {
        StorageAclDownloadRequest request = new StorageAclDownloadRequest(
                fileKey, expectedScope, expectedParent, expectedBinding);
        if (!hasCompleteRequest(request)) {
            throw notFound();
        }
        StorageAclEntity acl = repository.findByFileKey(fileKey).orElseThrow(this::notFound);
        if (!matchesClaimedContentBoundAcl(acl, request)) {
            throw notFound();
        }
        return storageService.generateDownloadUrl(fileKey, requestedTtl);
    }

    /**
     * 一覧向けの明示的な寛容 API。
     *
     * <p>ACL 台帳は IN クエリで一度だけ取得する。欠落・未 claim・照合不一致の項目は
     * URL を返さず省略するため、一覧の一件が失効・削除されても他の表示を妨げない。
     * 署名発行自体の失敗は運用障害なので隠蔽せず呼び出し元へ伝播する。</p>
     *
     * <p>同一 file key に異なるスコープ・親参照・binding の要求が混在した場合、その key は
     * URL を一切返さない。Map の値を別行へ誤流用できないよう、曖昧な要求を fail-closed にする。</p>
     */
    public Map<String, String> generateDownloadUrlsForList(
            Collection<StorageAclDownloadRequest> requests, Duration requestedTtl) {
        if (requests == null || requests.isEmpty()) {
            return Map.of();
        }

        Map<String, AccessTuple> tupleByFileKey = new HashMap<>();
        Set<String> ambiguousFileKeys = new HashSet<>();
        for (StorageAclDownloadRequest request : requests) {
            if (!hasFileKey(request)) {
                continue;
            }
            if (!hasCompleteRequest(request)) {
                ambiguousFileKeys.add(request.fileKey());
                continue;
            }
            AccessTuple requestedTuple = AccessTuple.from(request);
            AccessTuple previousTuple = tupleByFileKey.putIfAbsent(request.fileKey(), requestedTuple);
            if (previousTuple != null && !previousTuple.equals(requestedTuple)) {
                ambiguousFileKeys.add(request.fileKey());
            }
        }
        Set<String> fileKeys = new LinkedHashSet<>(tupleByFileKey.keySet());
        fileKeys.removeAll(ambiguousFileKeys);
        if (fileKeys.isEmpty()) {
            return Map.of();
        }

        Map<String, StorageAclEntity> aclByFileKey = new HashMap<>();
        for (StorageAclEntity acl : repository.findByFileKeyIn(fileKeys)) {
            if (acl != null && acl.getFileKey() != null) {
                aclByFileKey.put(acl.getFileKey(), acl);
            }
        }

        Map<String, String> urls = new LinkedHashMap<>();
        for (StorageAclDownloadRequest request : requests) {
            if (!hasCompleteRequest(request) || ambiguousFileKeys.contains(request.fileKey())
                    || urls.containsKey(request.fileKey())) {
                continue;
            }
            StorageAclEntity acl = aclByFileKey.get(request.fileKey());
            if (matchesClaimedContentBoundAcl(acl, request)) {
                urls.put(request.fileKey(), storageService.generateDownloadUrl(
                        request.fileKey(), requestedTtl));
            }
        }
        return urls;
    }

    private boolean matchesClaimedContentBoundAcl(StorageAclEntity acl, StorageAclDownloadRequest request) {
        return hasCompleteRequest(request)
                && acl != null
                && acl.getStatus() == StorageAclStatus.CLAIMED
                && acl.getAclMode() == StorageAclMode.CONTENT_BOUND
                && request.scope().type() == acl.getScopeType()
                && request.scope().scopeKey().equals(acl.getScopeKey())
                && request.parentContentReference().type().equals(acl.getParentContentReferenceType())
                && request.parentContentReference().key().equals(acl.getParentContentReferenceKey())
                && request.attachmentBinding().type().equals(acl.getAttachmentBindingType())
                && request.attachmentBinding().key().equals(acl.getAttachmentBindingKey());
    }

    private boolean hasFileKey(StorageAclDownloadRequest request) {
        return request != null && request.fileKey() != null && !request.fileKey().isBlank();
    }

    private boolean hasCompleteRequest(StorageAclDownloadRequest request) {
        return hasFileKey(request)
                && request.scope() != null
                && request.parentContentReference() != null
                && request.attachmentBinding() != null;
    }

    private BusinessException notFound() {
        return new BusinessException(StorageErrorCode.ACL_NOT_FOUND);
    }

    /** 同一 file key の batch 要求が同一コンテンツを指すことを保証する照合 tuple。 */
    private record AccessTuple(StorageAclScope scope, StorageAclContentReference parent,
                               StorageAclAttachmentBinding binding) {
        private static AccessTuple from(StorageAclDownloadRequest request) {
            return new AccessTuple(request.scope(), request.parentContentReference(), request.attachmentBinding());
        }
    }
}
