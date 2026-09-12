package com.mannschaft.app.common.storage.acl;

/** 親コンテンツの認可を引くための参照。添付束縛先とは別概念である。 */
public record StorageAclContentReference(String type, String key) {

    public StorageAclContentReference {
        StorageAclReferenceValidator.validate(type, key, "親コンテンツ参照");
    }
}
