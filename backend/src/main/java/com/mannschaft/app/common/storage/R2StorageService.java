package com.mannschaft.app.common.storage;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Cloudflare R2 を使用したストレージサービス実装。
 * R2 は S3 互換 API を使用するため、AWS S3 SDK でアクセスする。
 * 一時ファイルは "tmp/" プレフィックスで単一バケット内に配置する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class R2StorageService implements StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties storageProperties;

    @Override
    public PresignedUploadResult generateUploadUrl(String r2Key, String contentType, Duration ttl) {
        try {
            PutObjectRequest objectRequest = PutObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(r2Key)
                    .contentType(contentType)
                    .cacheControl(CachePolicy.resolve(r2Key))
                    .build();

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .putObjectRequest(objectRequest)
                    .build();

            String url = s3Presigner.presignPutObject(presignRequest).url().toString();
            log.debug("R2 Pre-signed upload URL生成: key={}", r2Key);
            return new PresignedUploadResult(url, r2Key, ttl.toSeconds());
        } catch (Exception e) {
            log.error("R2 Pre-signed upload URL生成失敗: key={}", r2Key, e);
            throw new BusinessException(StorageErrorCode.PRESIGNED_URL_FAILED, e);
        }
    }

    @Override
    public String generateDownloadUrl(String r2Key, Duration ttl) {
        try {
            GetObjectRequest objectRequest = GetObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(r2Key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(objectRequest)
                    .build();

            String url = s3Presigner.presignGetObject(presignRequest).url().toString();
            log.debug("R2 Pre-signed download URL生成: key={}", r2Key);
            return url;
        } catch (Exception e) {
            log.error("R2 Pre-signed download URL生成失敗: key={}", r2Key, e);
            throw new BusinessException(StorageErrorCode.PRESIGNED_URL_FAILED, e);
        }
    }

    @Override
    public void upload(String r2Key, byte[] data, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(r2Key)
                    .contentType(contentType)
                    .cacheControl(CachePolicy.resolve(r2Key))
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(data));
            log.debug("R2アップロード完了: key={}, size={}", r2Key, data.length);
        } catch (Exception e) {
            log.error("R2アップロード失敗: key={}", r2Key, e);
            throw new BusinessException(StorageErrorCode.UPLOAD_FAILED, e);
        }
    }

    @Override
    public void upload(String r2Key, InputStream data, long contentLength, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(r2Key)
                    .contentType(contentType)
                    .contentLength(contentLength)
                    .cacheControl(CachePolicy.resolve(r2Key))
                    .build();

            s3Client.putObject(request, RequestBody.fromInputStream(data, contentLength));
            log.debug("R2ストリームアップロード完了: key={}, size={}", r2Key, contentLength);
        } catch (Exception e) {
            log.error("R2ストリームアップロード失敗: key={}", r2Key, e);
            throw new BusinessException(StorageErrorCode.UPLOAD_FAILED, e);
        }
    }

    @Override
    public void delete(String r2Key) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(r2Key)
                    .build();

            s3Client.deleteObject(request);
            log.debug("R2オブジェクト削除: key={}", r2Key);
        } catch (Exception e) {
            log.error("R2オブジェクト削除失敗: key={}", r2Key, e);
            throw new BusinessException(StorageErrorCode.DELETE_FAILED, e);
        }
    }

    @Override
    public void deleteAll(List<String> r2Keys) {
        if (r2Keys == null || r2Keys.isEmpty()) {
            return;
        }
        try {
            List<ObjectIdentifier> identifiers = r2Keys.stream()
                    .map(key -> ObjectIdentifier.builder().key(key).build())
                    .toList();

            DeleteObjectsRequest request = DeleteObjectsRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .delete(Delete.builder().objects(identifiers).build())
                    .build();

            s3Client.deleteObjects(request);
            log.debug("R2オブジェクト一括削除: count={}", r2Keys.size());
        } catch (Exception e) {
            log.error("R2オブジェクト一括削除失敗: keys={}", r2Keys, e);
            throw new BusinessException(StorageErrorCode.DELETE_FAILED, e);
        }
    }

    @Override
    public byte[] download(String r2Key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(r2Key)
                    .build();

            byte[] data = s3Client.getObjectAsBytes(request).asByteArray();
            log.debug("R2ダウンロード完了: key={}, size={}", r2Key, data.length);
            return data;
        } catch (Exception e) {
            log.error("R2ダウンロード失敗: key={}", r2Key, e);
            throw new BusinessException(StorageErrorCode.DOWNLOAD_FAILED, e);
        }
    }

    /**
     * R2 オブジェクトのサイズをバイト単位で返す（HEAD リクエスト）。
     *
     * @param r2Key オブジェクトキー
     * @return ファイルサイズ（バイト）
     */
    public long getObjectSize(String r2Key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(r2Key)
                    .build();
            long size = s3Client.headObject(request).contentLength();
            log.debug("R2オブジェクトサイズ取得: key={}, size={}", r2Key, size);
            return size;
        } catch (Exception e) {
            log.error("R2オブジェクトサイズ取得失敗: key={}", r2Key, e);
            throw new BusinessException(StorageErrorCode.DOWNLOAD_FAILED, e);
        }
    }

    /**
     * R2 オブジェクトの先頭 n バイトを返す（レンジ GET）。
     * マジックバイト検証に使用する。
     *
     * @param r2Key    オブジェクトキー
     * @param numBytes 取得バイト数
     * @return 先頭バイト列
     */
    public byte[] readFirstBytes(String r2Key, int numBytes) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(r2Key)
                    .range("bytes=0-" + (numBytes - 1))
                    .build();
            byte[] data = s3Client.getObjectAsBytes(request).asByteArray();
            log.debug("R2先頭バイト取得: key={}, bytes={}", r2Key, data.length);
            return Arrays.copyOf(data, numBytes);
        } catch (Exception e) {
            log.error("R2先頭バイト取得失敗: key={}", r2Key, e);
            throw new BusinessException(StorageErrorCode.DOWNLOAD_FAILED, e);
        }
    }

    /**
     * R2 オブジェクトの存在確認。
     *
     * @param r2Key オブジェクトキー
     * @return 存在する場合 true
     */
    public boolean objectExists(String r2Key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(r2Key)
                    .build();
            s3Client.headObject(request);
            return true;
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.error("R2オブジェクト存在確認失敗: key={}", r2Key, e);
            throw new BusinessException(StorageErrorCode.DOWNLOAD_FAILED, e);
        }
    }

    /**
     * R2 画像の縦横サイズを返す（フルダウンロード + ImageIO）。
     * 添付ファイル登録時のメタデータ取得に使用する。
     *
     * @param r2Key オブジェクトキー
     * @return [width, height]。取得失敗時は空配列
     */
    public int[] getImageDimensions(String r2Key) {
        try {
            byte[] data = download(r2Key);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null) return new int[0];
            return new int[]{image.getWidth(), image.getHeight()};
        } catch (Exception e) {
            log.warn("R2画像サイズ取得失敗（ピクセル値はNULLで保存）: key={}", r2Key, e);
            return new int[0];
        }
    }
}
