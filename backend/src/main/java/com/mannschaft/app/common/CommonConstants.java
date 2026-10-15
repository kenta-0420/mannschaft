package com.mannschaft.app.common;

/**
 * プロジェクト横断的なシステム定数。
 */
public final class CommonConstants {

    private CommonConstants() {
        // インスタンス化禁止
    }

    /** デフォルトのページサイズ */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 最大ページサイズ */
    public static final int MAX_PAGE_SIZE = 100;

    /** デフォルトタイムゾーン */
    public static final String DEFAULT_TIMEZONE = "Asia/Tokyo";

    /** デフォルトロケール */
    public static final String DEFAULT_LOCALE = "ja";
}
