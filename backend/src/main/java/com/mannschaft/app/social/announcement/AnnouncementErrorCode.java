package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F02.6 お知らせウィジェットのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum AnnouncementErrorCode implements ErrorCode {

    /** お知らせが見つからない（404） */
    ANNOUNCE_001("ANNOUNCE_001", "お知らせが見つかりません", Severity.WARN),

    /** この操作を行う権限がありません（403） */
    ANNOUNCE_002("ANNOUNCE_002", "この操作を行う権限がありません", Severity.WARN),

    /** 同じコンテンツは既にお知らせ登録済みです（409） */
    ANNOUNCE_003("ANNOUNCE_003", "同じコンテンツは既にお知らせ登録済みです", Severity.WARN),

    /** ピン留め上限（5件）に達しています（409） */
    ANNOUNCE_004("ANNOUNCE_004", "ピン留め上限（5件）に達しています", Severity.WARN),

    /** 元コンテンツが対象スコープに属していません（400） */
    ANNOUNCE_005("ANNOUNCE_005", "元コンテンツが対象スコープに属していません", Severity.WARN),

    /** 対象コンテンツが見つかりません（404） */
    ANNOUNCE_006("ANNOUNCE_006", "対象コンテンツが見つかりません", Severity.WARN),

    /** 個人ブログ・ソーシャルプロフィールはお知らせ化できません（400） */
    ANNOUNCE_007("ANNOUNCE_007", "個人ブログ・ソーシャルプロフィールはお知らせ化できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
