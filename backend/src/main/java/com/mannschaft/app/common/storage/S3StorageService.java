package com.mannschaft.app.common.storage;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * AWS S3 を使用したストレージサービス実装。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "storage", name = "provider", havingValue = "s3")
@RequiredArgsConstructor
public class S3StorageService implements StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties storageProperties;

    @Override
    public PresignedUploadResult generateUploadUrl(String s3Key, String contentType, Duration ttl) {
        try {
            PutObjectRequest objectRequest = PutObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(s3Key)
                    .contentType(contentType)
                    .cacheControl(CachePolicy.resolve(s3Key))
                    .build();

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .putObjectRequest(objectRequest)
                    .build();

            String url = s3Presigner.presignPutObject(presignRequest).url().toString();
            log.debug("Pre-signed upload URL生成: key={}", s3Key);
            return new PresignedUploadResult(url, s3Key, ttl.toSeconds());
        } catch (Exception e) {
            log.error("Pre-signed upload URL生成失敗: key={}", s3Key, e);
            throw new BusinessException(StorageErrorCode.PRESIGNED_URL_FAILED, e);
        }
    }

    @Override
    public String generateDownloadUrl(String s3Key, Duration ttl) {
        try {
            GetObjectRequest objectRequest = GetObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(s3Key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(objectRequest)
                    .build();

            String url = s3Presigner.presignGetObject(presignRequest).url().toString();
            log.debug("Pre-signed download URL生成: key={}", s3Key);
            return url;
        } catch (Exception e) {
            log.error("Pre-signed download URL生成失敗: key={}", s3Key, e);
            throw new BusinessException(StorageErrorCode.PRESIGNED_URL_FAILED, e);
        }
    }

    @Override
    public void upload(String s3Key, byte[] data, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(s3Key)
                    .contentType(contentType)
                    .cacheControl(CachePolicy.resolve(s3Key))
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(data));
            log.debug("S3アップロード完了: key={}, size={}", s3Key, data.length);
        } catch (Exception e) {
            log.error("S3アップロード失敗: key={}", s3Key, e);
            throw new BusinessException(StorageErrorCode.UPLOAD_FAILED, e);
        }
    }

    @Override
    public void upload(String s3Key, InputStream data, long contentLength, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(s3Key)
                    .contentType(contentType)
                    .contentLength(contentLength)
                    .cacheControl(CachePolicy.resolve(s3Key))
                    .build();

            s3Client.putObject(request, RequestBody.fromInputStream(data, contentLength));
            log.debug("S3ストリームアップロード完了: key={}, size={}", s3Key, contentLength);
        } catch (Exception e) {
            log.error("S3ストリームアップロード失敗: key={}", s3Key, e);
            throw new BusinessException(StorageErrorCode.UPLOAD_FAILED, e);
        }
    }

    @Override
    public void delete(String s3Key) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(s3Key)
                    .build();

            s3Client.deleteObject(request);
            log.debug("S3オブジェクト削除: key={}", s3Key);
        } catch (Exception e) {
            log.error("S3オブジェクト削除失敗: key={}", s3Key, e);
            throw new BusinessException(StorageErrorCode.DELETE_FAILED, e);
        }
    }

    @Override
    public void deleteAll(List<String> s3Keys) {
        if (s3Keys == null || s3Keys.isEmpty()) {
            return;
        }
        try {
            List<ObjectIdentifier> identifiers = s3Keys.stream()
                    .map(key -> ObjectIdentifier.builder().key(key).build())
                    .toList();

            DeleteObjectsRequest request = DeleteObjectsRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .delete(Delete.builder().objects(identifiers).build())
                    .build();

            s3Client.deleteObjects(request);
            log.debug("S3オブジェクト一括削除: count={}", s3Keys.size());
        } catch (Exception e) {
            log.error("S3オブジェクト一括削除失敗: keys={}", s3Keys, e);
            throw new BusinessException(StorageErrorCode.DELETE_FAILED, e);
        }
    }

    @Override
    public byte[] download(String s3Key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(s3Key)
                    .build();

            byte[] data = s3Client.getObjectAsBytes(request).asByteArray();
            log.debug("S3ダウンロード完了: key={}, size={}", s3Key, data.length);
            return data;
        } catch (Exception e) {
            log.error("S3ダウンロード失敗: key={}", s3Key, e);
            throw new BusinessException(StorageErrorCode.DOWNLOAD_FAILED, e);
        }
    }

    /**
     * S3 オブジェクトのサイズをバイト単位で返す（HEAD リクエスト）。
     *
     * @param s3Key オブジェクトキー
     * @return ファイルサイズ（バイト）
     */
    public long getObjectSize(String s3Key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(s3Key)
                    .build();
            long size = s3Client.headObject(request).contentLength();
            log.debug("S3オブジェクトサイズ取得: key={}, size={}", s3Key, size);
            return size;
        } catch (Exception e) {
            log.error("S3オブジェクトサイズ取得失敗: key={}", s3Key, e);
            throw new BusinessException(StorageErrorCode.DOWNLOAD_FAILED, e);
        }
    }

    /**
     * S3 オブジェクトの先頭 n バイトを返す（レンジ GET）。
     * マジックバイト検証に使用する。
     *
     * @param s3Key    オブジェクトキー
     * @param numBytes 取得バイト数
     * @return 先頭バイト列
     */
    public byte[] readFirstBytes(String s3Key, int numBytes) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(s3Key)
                    .range("bytes=0-" + (numBytes - 1))
                    .build();
            byte[] data = s3Client.getObjectAsBytes(request).asByteArray();
            log.debug("S3先頭バイト取得: key={}, bytes={}", s3Key, data.length);
            return Arrays.copyOf(data, numBytes);
        } catch (Exception e) {
            log.error("S3先頭バイト取得失敗: key={}", s3Key, e);
            throw new BusinessException(StorageErrorCode.DOWNLOAD_FAILED, e);
        }
    }

    /**
     * S3 画像の縦横サイズを返す（フルダウンロード + ImageIO）。
     * 添付ファイル登録時のメタデータ取得に使用する。
     *
     * @param s3Key オブジェクトキー
     * @return [width, height]。取得失敗時は空配列
     */
    public int[] getImageDimensions(String s3Key) {
        try {
            byte[] data = download(s3Key);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) return new int[0];
            return new int[]{image.getWidth(), image.getHeight()};
        } catch (Exception e) {
            log.warn("S3画像サイズ取得失敗（ピクセル値はNULLで保存）: key={}", s3Key, e);
            return new int[0];
        }
    }
}
