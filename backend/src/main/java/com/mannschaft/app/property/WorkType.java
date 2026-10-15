package com.mannschaft.app.property;

/**
 * 物件履歴パッケージの工事種別。
 * F09.13 設計書 §3 property_work_packages.work_type に対応。
 */
public enum WorkType {

    /** 改修工事 */
    RENOVATION,

    /** 修繕 */
    REPAIR,

    /** 事故対応 */
    INCIDENT,

    /** 点検 */
    INSPECTION,

    /** 災害 */
    DISASTER,

    /** 打合せ */
    MEETING,

    /** その他 */
    OTHER
}
