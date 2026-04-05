package com.mannschaft.app.moderation;

/**
 * 異議申立てステータス。
 */
public enum AppealStatus {
    /** 招待済み（トークン送信済み） */
    INVITED,
    /** 申立て理由送信済み */
    PENDING,
    /** 承認 */
    ACCEPTED,
    /** 却下 */
    REJECTED
}
