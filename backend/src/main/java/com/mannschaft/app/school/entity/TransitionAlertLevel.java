package com.mannschaft.app.school.entity;

/** 「前にいたのに今いない」検知アラートの緊急度。 */
public enum TransitionAlertLevel {
    /** 通常（連絡確認待ち程度） */
    NORMAL,
    /** 緊急（2時限連続欠席等、明確な失踪リスク） */
    URGENT
}
