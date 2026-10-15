package com.mannschaft.app.common.storage.quota;

/**
 * F13 ストレージクォータのスコープ種別。
 *
 * <p>{@link StorageQuotaService} の引数として使用し、{@code storage_subscriptions.scope_type} と一致する。</p>
 *
 * <ul>
 *   <li>{@link #ORGANIZATION} — organizations.id を参照</li>
 *   <li>{@link #TEAM} — teams.id を参照</li>
 *   <li>{@link #PERSONAL} — users.id を参照</li>
 * </ul>
 *
 * @see <a href="../../../../../../../docs/cross-cutting/storage_quota.md">設計書</a>
 */
public enum StorageScopeType {
    ORGANIZATION,
    TEAM,
    PERSONAL
}
