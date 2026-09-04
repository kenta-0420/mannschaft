package com.mannschaft.app.common.duplicatename;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * CMP-260901-1538 柱③-A: 組織・チーム名称の同名確認フローに関するエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum DuplicateNameErrorCode implements ErrorCode {

    /** 同名候補が存在するため確認が必要（409）。confirmDuplicate 未指定、または fingerprint 不一致（再出現）。 */
    DUPNAME_001("DUPNAME_001", "同名の候補が見つかりました。内容を確認のうえ再送信してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
