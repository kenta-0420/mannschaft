package com.mannschaft.app.property;

/**
 * 業者（vendors）の分類。
 * F09.13 設計書 §3 vendors.category に対応。
 */
public enum VendorCategory {

    /** 建設・施工 */
    CONSTRUCTION,

    /** 点検 */
    INSPECTION,

    /** コンサルティング */
    CONSULTING,

    /** 清掃 */
    CLEANING,

    /** 警備 */
    SECURITY,

    /** その他 */
    OTHER
}
