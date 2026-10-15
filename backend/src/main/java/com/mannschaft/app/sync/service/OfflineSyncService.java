package com.mannschaft.app.sync.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.sync.dto.SyncItem;
import com.mannschaft.app.sync.dto.SyncRequest;
import com.mannschaft.app.sync.dto.SyncResponse;
import com.mannschaft.app.sync.dto.SyncResultItem;
import com.mannschaft.app.sync.dto.SyncSummary;
import com.mannschaft.app.sync.entity.OfflineSyncConflictEntity;
import com.mannschaft.app.sync.repository.OfflineSyncConflictRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * F11.1 オフライン同期: 一括同期サービス。
 *
 * フロントエンドがオフライン中にキューイングしたリクエストを createdAt 昇順で逐次処理する。
 * 各アイテムは SyncItemProcessor チェーンで処理され、コンフリクトが発生した場合は
 * offline_sync_conflicts テーブルにレコードを作成する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OfflineSyncService {

    private final List<SyncItemProcessor> processors;
    private final OfflineSyncConflictRepository conflictRepository;
    private final ObjectMapper objectMapper;

    /**
     * オフラインキューを一括同期する。
     *
     * @param userId リクエスト元のユーザーID
     * @param request 同期リクエスト（最大50アイテム）
     * @return 各アイテムの処理結果とサマリー
     */
    @Transactional
    public SyncResponse sync(Long userId, SyncRequest request) {
        List<SyncItem> sortedItems = request.getItems().stream()
                .sorted(Comparator.comparing(SyncItem::getCreatedAt))
                .toList();

        List<SyncResultItem> results = new ArrayList<>();
        int successCount = 0;
        int conflictCount = 0;
        int failedCount = 0;

        for (SyncItem item : sortedItems) {
            SyncResultItem result = processItem(userId, item);
            results.add(result);

            switch (result.getStatus()) {
                case "SUCCESS" -> successCount++;
                case "CONFLICT" -> conflictCount++;
                case "FAILED" -> failedCount++;
                default -> failedCount++;
            }
        }

        SyncSummary summary = new SyncSummary(
                sortedItems.size(), successCount, conflictCount, failedCount);

        log.info("オフライン同期完了: userId={}, total={}, success={}, conflict={}, failed={}",
                userId, summary.getTotal(), summary.getSuccess(),
                summary.getConflict(), summary.getFailed());

        return new SyncResponse(results, summary);
    }

    /**
     * 個別アイテムを処理する。
     * SyncItemProcessor チェーンから適切なプロセッサを選択して実行する。
     * コンフリクトが発生した場合はコンフリクトレコードを作成する。
     */
    private SyncResultItem processItem(Long userId, SyncItem item) {
        try {
            SyncItemProcessor processor = findProcessor(item.getMethod(), item.getPath());
            SyncResultItem result = processor.process(userId, item);

            if ("CONFLICT".equals(result.getStatus())) {
                OfflineSyncConflictEntity conflict = createConflictRecord(userId, item);
                return SyncResultItem.conflict(
                        item.getClientId(), conflict.getId(), result.getMessage());
            }

            return result;
        } catch (Exception e) {
            log.error("同期アイテム処理失敗: clientId={}, path={}, error={}",
                    item.getClientId(), item.getPath(), e.getMessage(), e);
            return SyncResultItem.failed(item.getClientId(),
                    "処理中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * method + path に対応するプロセッサを検索する。
     * processors リストは Spring の @Order でソートされるため、
     * 最初にマッチしたプロセッサが使用される（DefaultSyncItemProcessor が最後にフォールバック）。
     */
    private SyncItemProcessor findProcessor(String method, String path) {
        return processors.stream()
                .filter(p -> p.supports(method, path))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "サポートされていない同期操作: method=" + method + ", path=" + path));
    }

    /**
     * コンフリクトレコードを作成する。
     */
    private OfflineSyncConflictEntity createConflictRecord(Long userId, SyncItem item) {
        String resourceType = extractResourceType(item.getPath());
        Long resourceId = extractResourceId(item.getPath());
        String clientDataJson = serializeBody(item.getBody());

        OfflineSyncConflictEntity conflict = OfflineSyncConflictEntity.builder()
                .userId(userId)
                .resourceType(resourceType)
                .resourceId(resourceId != null ? resourceId : 0L)
                .clientData(clientDataJson)
                .serverData("{}")
                .clientVersion(item.getVersion() != null ? item.getVersion() : 0L)
                .serverVersion(item.getVersion() != null ? item.getVersion() + 1 : 1L)
                .build();

        return conflictRepository.save(conflict);
    }

    /**
     * APIパスからリソース種別を抽出する。
     * 例: /api/v1/activities → activities, /api/v1/teams/1/members → members
     */
    private String extractResourceType(String path) {
        String[] segments = path.split("/");
        // 最後の数値でないセグメントをリソース種別とする
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isEmpty() && !segments[i].matches("\\d+")) {
                return segments[i];
            }
        }
        return "unknown";
    }

    /**
     * APIパスからリソースIDを抽出する。
     * 例: /api/v1/activities/123 → 123
     */
    private Long extractResourceId(String path) {
        String[] segments = path.split("/");
        // 最後の数値セグメントをリソースIDとする
        for (int i = segments.length - 1; i >= 0; i--) {
            if (segments[i].matches("\\d+")) {
                try {
                    return Long.parseLong(segments[i]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * リクエストボディを JSON 文字列にシリアライズする。
     */
    private String serializeBody(Object body) {
        if (body == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.warn("ボディのシリアライズに失敗: {}", e.getMessage());
            return "{}";
        }
    }
}
