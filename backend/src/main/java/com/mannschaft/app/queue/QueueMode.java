package com.mannschaft.app.queue;

/**
 * キューモード。カテゴリ内のチケット管理方式を表す。
 */
public enum QueueMode {
    /** 個別管理: カウンターごとに独立した待ち行列 */
    INDIVIDUAL,
    /** 共有管理: カテゴリ内の全カウンターで共有する待ち行列 */
    SHARED
}
