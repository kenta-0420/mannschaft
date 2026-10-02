package com.mannschaft.app.schedule.entity;

/**
 * Google カレンダー同期の方向を表す enum。
 * <ul>
 *   <li>PUSH_ONLY — Mannschaft → Google カレンダーへの一方向同期（Phase 1〜3）</li>
 *   <li>BIDIRECTIONAL — Mannschaft ↔ Google カレンダーの双方向同期（Phase 4〜）</li>
 * </ul>
 */
public enum SyncDirection {
    PUSH_ONLY,
    BIDIRECTIONAL
}
