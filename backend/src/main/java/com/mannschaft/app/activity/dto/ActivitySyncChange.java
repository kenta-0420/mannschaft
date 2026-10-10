package com.mannschaft.app.activity.dto;

/** 基本項目一つの変更。0・false・nullを文字列化せず保持する。 */
public record ActivitySyncChange(String field, Object currentValue, Object scheduleValue,
                                 boolean automatic) {
}
