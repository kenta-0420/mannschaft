package com.mannschaft.app.common.storage;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * S3ストレージの設定値を保持するコンポーネント。
 */
@Getter
@Component
public class StorageProperties {

    private final String bucket;
    private final String tmpBucket;
    private final int presignedUploadTtl;
    private final int presignedDownloadTtl;

    public StorageProperties(
            @Value("${mannschaft.storage.bucket}") String bucket,
            @Value("${mannschaft.storage.tmp-bucket}") String tmpBucket,
            @Value("${mannschaft.storage.presigned-upload-ttl}") int presignedUploadTtl,
            @Value("${mannschaft.storage.presigned-download-ttl}") int presignedDownloadTtl) {
        this.bucket = bucket;
        this.tmpBucket = tmpBucket;
        this.presignedUploadTtl = presignedUploadTtl;
        this.presignedDownloadTtl = presignedDownloadTtl;
    }
}
