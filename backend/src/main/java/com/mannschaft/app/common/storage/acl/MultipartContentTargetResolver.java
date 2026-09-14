package com.mannschaft.app.common.storage.acl;

import java.util.Optional;

/** 各ドメインの保存済みメディアと親の認可から multipart の束縛を復元する。 */
public interface MultipartContentTargetResolver {
    Optional<MultipartContentTarget> resolveMultipartTarget(String fileKey, Long uploaderId);
}
