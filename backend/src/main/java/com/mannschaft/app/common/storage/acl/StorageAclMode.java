package com.mannschaft.app.common.storage.acl;

/** オブジェクトの認可境界。Phase 3 の既定値は親コンテンツ継承。 */
public enum StorageAclMode {
    CONTENT_BOUND,
    PERSONAL,
    PUBLIC
}
