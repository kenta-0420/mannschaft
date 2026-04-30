package com.mannschaft.app.school.entity;

/** 保護者連絡の処理状態（DTO用の派生ステータス）。 */
public enum FamilyNoticeStatus {
    /** 未確認（担任がまだ見ていない） */
    PENDING,
    /** 確認済み（担任が確認したが出欠未反映） */
    ACKNOWLEDGED,
    /** 出欠反映済み */
    APPLIED
}
