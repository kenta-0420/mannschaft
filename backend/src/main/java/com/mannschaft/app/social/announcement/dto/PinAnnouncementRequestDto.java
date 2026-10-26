package com.mannschaft.app.social.announcement.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ピン留めリクエスト DTO（F02.6）。
 *
 * <p>
 * {@code PATCH /api/v1/teams/{teamId}/announcements/{id}/pin} のリクエストボディ。
 * 設計書 §4 では {@code { "pinned": true }} 形式だが、
 * Service 層では TOGGLE 方式（現状の反転）で実装しているため、
 * このフィールドはフロントエンドとの契約維持用として保持する。
 * </p>
 *
 * <p>
 * Service の {@code togglePin} は引数に「希望値」を取らずトグルするため、
 * Controller は本 DTO を受け取るが {@code pinned} の値は Service に渡さない。
 * </p>
 */
@Getter
@NoArgsConstructor
public class PinAnnouncementRequestDto {

    /**
     * ピン留め希望値（true = ピン留め / false = 解除）。
     * Service 層ではトグル方式を採用するため実際の制御には使用しない。
     */
    private Boolean pinned;
}
