package com.mannschaft.app.moderation;

/**
 * 通報理由の種別。
 */
public enum ReportReason {
    /** スパム */
    SPAM,
    /** ハラスメント */
    HARASSMENT,
    /** 不適切なコンテンツ */
    INAPPROPRIATE,
    /** 暴力的コンテンツ */
    VIOLENCE,
    /** 誤情報 */
    MISINFORMATION,
    /** 著作権侵害 */
    COPYRIGHT,
    /** その他 */
    OTHER
}
