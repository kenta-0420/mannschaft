package com.mannschaft.app.common.storage.acl;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** ドメイン固有の復元処理を集約し、未登録キーと複数ドメインへの二重登録を拒否する。 */
@Service
@RequiredArgsConstructor
public class MultipartContentTargetRegistry {
    private final List<MultipartContentTargetResolver> resolvers;

    public MultipartContentTarget resolve(String fileKey, Long uploaderId) {
        List<MultipartContentTarget> matches = resolvers.stream()
                .flatMap(resolver -> resolver.resolveMultipartTarget(fileKey, uploaderId).stream())
                .toList();
        if (matches.size() != 1) {
            throw new BusinessException(StorageErrorCode.ACL_NOT_FOUND);
        }
        return matches.getFirst();
    }
}
