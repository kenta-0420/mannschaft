package com.mannschaft.app.sync.service;

import com.mannschaft.app.sync.dto.SyncItem;
import com.mannschaft.app.sync.dto.SyncResultItem;

/**
 * オフライン同期アイテムの処理インターフェース。
 * 各機能モジュールがこのインターフェースを実装することで、同期 API からの振り分けに対応する。
 * 例: 活動記録の SyncItemProcessor 実装、チャットの SyncItemProcessor 実装 など。
 */
public interface SyncItemProcessor {

    /**
     * このプロセッサが指定された method + path の組み合わせをサポートするか判定する。
     *
     * @param method HTTPメソッド (POST, PATCH, PUT, DELETE)
     * @param path APIパス (例: /api/v1/activities)
     * @return サポートする場合 true
     */
    boolean supports(String method, String path);

    /**
     * 同期アイテムを処理し、結果を返す。
     *
     * @param userId リクエスト元のユーザーID
     * @param item 同期アイテム
     * @return 処理結果
     */
    SyncResultItem process(Long userId, SyncItem item);
}
