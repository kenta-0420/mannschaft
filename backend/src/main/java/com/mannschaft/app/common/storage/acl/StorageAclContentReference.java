package com.mannschaft.app.common.storage.acl;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageErrorCode;

/** 親コンテンツの認可を引くための参照。添付束縛先とは別概念である。 */
public record StorageAclContentReference(String type, String key) {

    public StorageAclContentReference {
        try {
            StorageAclReferenceValidator.validate(type, key, "親コンテンツ参照");
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST, exception);
        }
    }
}
