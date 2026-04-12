package com.mannschaft.app.notification.confirmable.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F04.9 トークン経由の確認レスポンスDTO（認証不要エンドポイント用）。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicConfirmationResponse {

    /** 確認が成功したかどうか */
    private boolean success;

    /** ユーザー向けメッセージ */
    private String message;
}
