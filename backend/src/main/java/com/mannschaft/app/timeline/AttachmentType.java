package com.mannschaft.app.timeline;

/**
 * タイムライン投稿添付ファイルの種別。
 */
public enum AttachmentType {
    /** 画像ファイル（R2直アップロード） */
    IMAGE,
    /** 動画ファイル（R2直アップロード・VIDEO_FILE） */
    VIDEO_FILE,
    /** 動画リンク（YouTube等の外部リンク） */
    VIDEO_LINK,
    /** リンクプレビュー（OGP） */
    LINK_PREVIEW
}
