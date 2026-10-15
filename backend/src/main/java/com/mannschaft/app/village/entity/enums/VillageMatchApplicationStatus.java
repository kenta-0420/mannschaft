package com.mannschaft.app.village.entity.enums;

/**
 * 村練習試合募集への応募状態（F17.1 Phase 2）。
 *
 * <ul>
 *   <li>{@link #PENDING}   — 審査待ち</li>
 *   <li>{@link #ACCEPTED}  — 承認</li>
 *   <li>{@link #REJECTED}  — 却下</li>
 *   <li>{@link #WITHDRAWN} — 自主取下げ</li>
 * </ul>
 *
 * <p>UNIQUE(recruit_id, applicant_user_id, status) により PENDING 二重応募は DB 層で防がれる。
 * 履歴は status 違いで複数並存可能。</p>
 */
public enum VillageMatchApplicationStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    WITHDRAWN
}
