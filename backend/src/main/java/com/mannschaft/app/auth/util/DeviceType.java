package com.mannschaft.app.auth.util;

/**
 * デバイス種別。User-Agent パース結果からセッション一覧のアイコン出し分けに使用する。
 */
public enum DeviceType {
    /** PC（Windows, macOS, Linux） */
    DESKTOP,
    /** スマートフォン（iPhone, Android） */
    MOBILE,
    /** タブレット（iPad, Android Tablet） */
    TABLET,
    /** 判定不能 */
    UNKNOWN
}
