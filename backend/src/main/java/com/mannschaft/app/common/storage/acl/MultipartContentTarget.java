package com.mannschaft.app.common.storage.acl;

/** 保存済みメディアから復元した multipart の所有スコープと添付束縛。 */
public record MultipartContentTarget(StorageAclScope scope, StorageAclContentReference parent,
                                     StorageAclAttachmentBinding binding) {
}
