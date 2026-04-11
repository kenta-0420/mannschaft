package com.mannschaft.app.sync.service;

import com.mannschaft.app.sync.dto.SyncItem;
import com.mannschaft.app.sync.dto.SyncResultItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * デフォルトの同期アイテムプロセッサ。
 * 他のプロセッサがサポートしないパスパターンに対してフォールバックとして動作する。
 *
 * <p>POST リクエストにはスタブとして SUCCESS を返し、
 * PATCH/PUT リクエストで version が指定されている場合は CONFLICT を模擬する。
 * 将来的に各機能モジュールが専用の SyncItemProcessor を実装すれば、
 * このデフォルト実装は使われなくなる。</p>
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DefaultSyncItemProcessor implements SyncItemProcessor {

    /**
     * フォールバックとして全ての method + path をサポートする。
     */
    @Override
    public boolean supports(String method, String path) {
        return true;
    }

    @Override
    public SyncResultItem process(Long userId, SyncItem item) {
        String method = item.getMethod().toUpperCase();
        log.info("DefaultSyncItemProcessor: userId={}, method={}, path={}, clientId={}",
                userId, method, item.getPath(), item.getClientId());

        return switch (method) {
            case "POST" -> SyncResultItem.success(item.getClientId(), 0L);
            case "PATCH", "PUT" -> {
                if (item.getVersion() != null && item.getVersion() > 0) {
                    // version 不一致を模擬（スタブ実装）
                    yield SyncResultItem.conflict(item.getClientId(), null,
                            "バージョン不一致: スタブ実装のためコンフリクトを模擬");
                }
                yield SyncResultItem.success(item.getClientId(), 0L);
            }
            case "DELETE" -> SyncResultItem.success(item.getClientId(), null);
            default -> SyncResultItem.failed(item.getClientId(),
                    "未サポートのHTTPメソッド: " + method);
        };
    }
}
