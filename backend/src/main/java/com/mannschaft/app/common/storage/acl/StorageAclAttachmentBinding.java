package com.mannschaft.app.common.storage.acl;

/** 一意な添付先を表す束縛。claim の冪等性はこの値の完全一致で判定する。 */
public record StorageAclAttachmentBinding(String type, String key) {

    public StorageAclAttachmentBinding {
        StorageAclReferenceValidator.validate(type, key, "添付束縛先");
    }
}
