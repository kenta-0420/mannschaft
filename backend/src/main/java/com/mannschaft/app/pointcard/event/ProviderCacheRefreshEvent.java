package com.mannschaft.app.pointcard.event;

/**
 * ポイントカードプロバイダーマスタの変更を通知するイベント。
 * SYSTEM_ADMIN がプロバイダー追加・編集・無効化した際に発火する。
 * リスナー: ProviderMatchService（第二陣 2B で実装）
 */
public record ProviderCacheRefreshEvent() {
}
