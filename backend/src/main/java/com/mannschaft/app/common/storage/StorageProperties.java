package com.mannschaft.app.common.storage;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cloudflare R2 ストレージの設定値を保持するコンポーネント。
 * 一時ファイルは tmp-bucket を廃止し、単一バケット内の "tmp/" プレフィックスで管理する。
 */
@Getter
@Component
public class StorageProperties {

    private final String bucket;
    private final int presignedUploadTtl;
    private final int presignedDownloadTtl;

    public StorageProperties(
            @Value("${mannschaft.storage.bucket}") String bucket,
            @Value("${mannschaft.storage.presigned-upload-ttl}") int presignedUploadTtl,
            @Value("${mannschaft.storage.presigned-download-ttl}") int presignedDownloadTtl) {
        this.bucket = bucket;
        this.presignedUploadTtl = presignedUploadTtl;
        this.presignedDownloadTtl = presignedDownloadTtl;
    }
}
