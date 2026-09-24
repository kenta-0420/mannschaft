package com.mannschaft.app.common.storage.acl;

/**
 * 認可済み親コンテンツが、自身に属する attachment のダウンロード URL を求める際の照合値。
 *
 * <p>fileKey だけを入力にできないよう、スコープ・親コンテンツ・attachment binding を常に
 * 一組で受け渡す。これらの値は HTTP リクエストから直接受け取らず、各ドメインが取得済みの
 * エンティティから組み立てる。</p>
 */
public record StorageAclDownloadRequest(
        String fileKey,
        StorageAclScope scope,
        StorageAclContentReference parentContentReference,
        StorageAclAttachmentBinding attachmentBinding) {
}
