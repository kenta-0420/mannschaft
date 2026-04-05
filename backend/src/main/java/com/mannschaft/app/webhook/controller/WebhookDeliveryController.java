package com.mannschaft.app.webhook.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.webhook.service.WebhookDeliveryService;
import com.mannschaft.app.webhook.service.WebhookDeliveryService.DeliveryLogResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Webhook配信ログ管理コントローラー。
 * Outgoing Webhook の配信ログ照会・リトライAPIを提供する。
 */
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Validated
public class WebhookDeliveryController {

    private final WebhookDeliveryService webhookDeliveryService;

    /**
     * エンドポイントに紐づく配信ログ一覧をページネーション付きで取得する。
     * 認可: ADMIN / DEPUTY_ADMIN（MANAGE_INTEGRATION権限）
     *
     * @param endpointId 対象エンドポイントID
     * @param page       ページ番号（0始まり、省略時0）
     * @param size       1ページあたりの件数（省略時20）
     */
    @GetMapping("/endpoints/{endpointId}/deliveries")
    public PagedResponse<DeliveryLogResponse> listDeliveryLogs(
            @PathVariable Long endpointId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // 配信ログ全件取得
        List<DeliveryLogResponse> allLogs = webhookDeliveryService.listDeliveryLogs(endpointId);

        // ページネーション計算
        long total = allLogs.size();
        int totalPages = (int) Math.ceil((double) total / size);
        int fromIndex = Math.min(page * size, (int) total);
        int toIndex = Math.min(fromIndex + size, (int) total);
        List<DeliveryLogResponse> pageData = allLogs.subList(fromIndex, toIndex);

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(total, page, size, totalPages);
        return PagedResponse.of(pageData, meta);
    }

    /**
     * 指定配信ログのWebhookを再送する。
     * 認可: ADMIN
     *
     * @param id 再送対象の配信ログID
     */
    @PostMapping("/deliveries/{id}/retry")
    public ApiResponse<Void> retryDelivery(
            @PathVariable Long id) {
        webhookDeliveryService.retryDelivery(id);
        return ApiResponse.of(null);
    }
}
