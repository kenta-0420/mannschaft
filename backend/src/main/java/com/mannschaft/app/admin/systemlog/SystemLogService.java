package com.mannschaft.app.admin.systemlog;

import com.mannschaft.app.common.storage.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * システムログ（スロークエリログ・SSR エラーログ）を R2 に保存・管理するサービス。
 * R2 操作はトランザクション外で行うため @Transactional は不要。
 */
@Slf4j
@Service
public class SystemLogService {

    /** Presigned ダウンロード URL の有効期限 */
    private static final Duration DOWNLOAD_URL_TTL = Duration.ofMinutes(15);

    /** スロークエリログの R2 プレフィックス */
    private static final String SLOW_QUERY_PREFIX = "logs/slow-query/";

    /** SSR エラーログの R2 プレフィックス */
    private static final String SSR_ERROR_PREFIX = "logs/ssr-error/";

    /** スレッドセーフな SSR エラーバッファ */
    private final Queue<String> ssrErrorBuffer = new ConcurrentLinkedQueue<>();

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties storageProperties;
    private final SystemLogPiiMasker piiMasker;
    private final String slowQueryLogPath;

    public SystemLogService(
            S3Client s3Client,
            S3Presigner s3Presigner,
            StorageProperties storageProperties,
            SystemLogPiiMasker piiMasker,
            @Value("${mannschaft.system-log.slow-query-log-path:./logs/mysql-slow/mannschaft-slow.log}")
            String slowQueryLogPath) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.storageProperties = storageProperties;
        this.piiMasker = piiMasker;
        this.slowQueryLogPath = slowQueryLogPath;
    }

    /**
     * 指定日付のスロークエリログを R2 にアップロードする。
     * ログファイルが存在しない場合、または対象日付のエントリが 0 件の場合はスキップする。
     *
     * @param date アップロード対象の日付
     */
    public void uploadSlowQueryLog(LocalDate date) {
        Path logPath = Path.of(slowQueryLogPath);
        if (!Files.exists(logPath)) {
            log.info("スロークエリログファイルが存在しないためスキップ: path={}", slowQueryLogPath);
            return;
        }

        String dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        List<String> entries;
        try {
            String content = Files.readString(logPath, StandardCharsets.UTF_8);
            entries = extractEntriesForDate(content, dateStr);
        } catch (IOException e) {
            log.error("スロークエリログ読み込み失敗: path={}", slowQueryLogPath, e);
            return;
        }

        if (entries.isEmpty()) {
            log.info("対象日付のスロークエリログエントリなしのためスキップ: date={}", dateStr);
            return;
        }

        // PII マスキングを適用してから結合
        String maskedContent = entries.stream()
                .map(piiMasker::mask)
                .collect(Collectors.joining("\n"));

        String r2Key = SLOW_QUERY_PREFIX + dateStr + ".log";
        uploadToR2(r2Key, maskedContent.getBytes(StandardCharsets.UTF_8), "text/plain");
        log.info("スロークエリログアップロード完了: date={}, entries={}, key={}", dateStr, entries.size(), r2Key);
    }

    /**
     * スロークエリログから指定日付のエントリを抽出する。
     * {@code # Time: YYYY-MM-DD} 行でエントリを分割する。
     *
     * @param content  ログファイルの全内容
     * @param dateStr  対象日付（YYYY-MM-DD 形式）
     * @return 対象日付のエントリリスト
     */
    private List<String> extractEntriesForDate(String content, String dateStr) {
        List<String> result = new ArrayList<>();
        // # Time: 行でエントリを分割
        String[] lines = content.split("\n");
        StringBuilder currentEntry = new StringBuilder();
        boolean isTargetDate = false;

        for (String line : lines) {
            if (line.startsWith("# Time:")) {
                // 前のエントリを確定
                if (isTargetDate && !currentEntry.isEmpty()) {
                    result.add(currentEntry.toString().trim());
                }
                currentEntry = new StringBuilder(line).append("\n");
                // 対象日付かチェック
                isTargetDate = line.contains(dateStr);
            } else {
                if (!currentEntry.isEmpty() || isTargetDate) {
                    currentEntry.append(line).append("\n");
                }
            }
        }
        // 最後のエントリ
        if (isTargetDate && !currentEntry.isEmpty()) {
            result.add(currentEntry.toString().trim());
        }
        return result;
    }

    /**
     * SSR エラー JSONL 行をバッファに追加する（スレッドセーフ）。
     *
     * @param maskedJsonLine PII マスキング済みの JSONL 行
     */
    public void appendSsrError(String maskedJsonLine) {
        ssrErrorBuffer.offer(maskedJsonLine);
    }

    /**
     * バッファに蓄積された SSR エラーを R2 にフラッシュする。
     * バッファが空の場合はスキップする。既存ファイルがある場合は追記する。
     *
     * @param date フラッシュ対象の日付
     */
    public void flushSsrErrors(LocalDate date) {
        // バッファからドレイン
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = ssrErrorBuffer.poll()) != null) {
            lines.add(line);
        }

        if (lines.isEmpty()) {
            log.debug("SSR エラーバッファが空のためスキップ: date={}", date);
            return;
        }

        String dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String r2Key = SSR_ERROR_PREFIX + dateStr + ".jsonl";

        // 既存ファイルとのマージ
        String newContent;
        if (objectExists(r2Key)) {
            // 既存内容をダウンロードして新規行と結合
            byte[] existing = downloadFromR2(r2Key);
            String existingContent = new String(existing, StandardCharsets.UTF_8);
            newContent = existingContent.endsWith("\n")
                    ? existingContent + String.join("\n", lines)
                    : existingContent + "\n" + String.join("\n", lines);
        } else {
            newContent = String.join("\n", lines);
        }

        uploadToR2(r2Key, newContent.getBytes(StandardCharsets.UTF_8), "text/plain");
        log.info("SSR エラーフラッシュ完了: date={}, lines={}, key={}", dateStr, lines.size(), r2Key);
    }

    /**
     * R2 上のシステムログファイル一覧を返す。Presigned ダウンロード URL 付き。
     *
     * @param type ログ種別（"slow-query" | "ssr-error" | null で両方）
     * @param date 日付フィルタ（指定日付のファイルのみ。null で全件）
     * @return ログファイルレスポンスのリスト
     */
    public List<SystemLogFileResponse> listLogFiles(String type, LocalDate date) {
        List<String> prefixes = resolvePrefixes(type);
        String dateStr = date != null ? date.format(DateTimeFormatter.ISO_LOCAL_DATE) : null;

        List<SystemLogFileResponse> result = new ArrayList<>();
        for (String prefix : prefixes) {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(storageProperties.getBucket())
                    .prefix(prefix)
                    .build();

            s3Client.listObjectsV2(request).contents().stream()
                    .filter(obj -> dateStr == null || obj.key().contains(dateStr))
                    .forEach(obj -> result.add(toResponse(obj, prefix)));
        }
        return result;
    }

    /**
     * ログ種別からR2プレフィックスのリストを返す。
     *
     * @param type ログ種別（"slow-query" | "ssr-error" | null）
     * @return プレフィックスのリスト
     */
    private List<String> resolvePrefixes(String type) {
        if ("slow-query".equals(type)) {
            return List.of(SLOW_QUERY_PREFIX);
        } else if ("ssr-error".equals(type)) {
            return List.of(SSR_ERROR_PREFIX);
        } else {
            return List.of(SLOW_QUERY_PREFIX, SSR_ERROR_PREFIX);
        }
    }

    /**
     * S3Object をレスポンス DTO に変換する。Presigned URL を生成して付与する。
     *
     * @param obj    S3 オブジェクト情報
     * @param prefix 対応するプレフィックス
     * @return レスポンス DTO
     */
    private SystemLogFileResponse toResponse(S3Object obj, String prefix) {
        String key = obj.key();
        String logType = SLOW_QUERY_PREFIX.equals(prefix) ? "slow-query" : "ssr-error";
        // キーからファイル名と日付を抽出
        String fileName = key.substring(prefix.length());
        // ファイル名から日付部分を抽出（拡張子を除く）
        String date = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String downloadUrl = generatePresignedDownloadUrl(key);

        return new SystemLogFileResponse(logType, date, fileName, obj.size(), downloadUrl);
    }

    /**
     * R2 オブジェクトの存在確認（HEAD リクエスト）。
     *
     * @param r2Key R2 オブジェクトキー
     * @return 存在する場合 true
     */
    private boolean objectExists(String r2Key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(storageProperties.getBucket())
                    .key(r2Key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /**
     * R2 からコンテンツをダウンロードする。
     *
     * @param r2Key R2 オブジェクトキー
     * @return バイト列
     */
    private byte[] downloadFromR2(String r2Key) {
        return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(r2Key)
                .build()).asByteArray();
    }

    /**
     * R2 にコンテンツをアップロードする。
     *
     * @param r2Key       R2 オブジェクトキー
     * @param data        アップロードするバイト列
     * @param contentType コンテンツタイプ
     */
    private void uploadToR2(String r2Key, byte[] data, String contentType) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(storageProperties.getBucket())
                        .key(r2Key)
                        .contentType(contentType)
                        .contentLength((long) data.length)
                        .build(),
                RequestBody.fromBytes(data));
    }

    /**
     * Presigned ダウンロード URL を生成する（有効期限 15 分）。
     *
     * @param r2Key R2 オブジェクトキー
     * @return Presigned URL 文字列
     */
    private String generatePresignedDownloadUrl(String r2Key) {
        GetObjectRequest objectRequest = GetObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(r2Key)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(DOWNLOAD_URL_TTL)
                .getObjectRequest(objectRequest)
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }
}
