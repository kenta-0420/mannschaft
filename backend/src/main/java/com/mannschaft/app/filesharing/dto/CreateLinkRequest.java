package com.mannschaft.app.filesharing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 共有リンク作成リクエストDTO。
 *
 * <p>PR-D マスター確定仕様:</p>
 * <ul>
 *   <li>{@code expiresAt} は必須・最大30日先まで（無期限リンク不可）。未指定 / 過去 / 30日超は
 *       Service 層で {@code LINK_EXPIRY_INVALID}（400）。</li>
 *   <li>{@code downloadAllowed} は既定 {@code false}（＝閲覧のみ）。DL を許すリンクは明示 {@code true}。</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public class CreateLinkRequest {

    @Schema(description = "リンクの有効期限（必須・最大30日先）", example = "2026-07-30T00:00:00")
    private final LocalDateTime expiresAt;

    @Size(max = 255)
    @Schema(description = "任意の閲覧パスワード（設定するとアクセス時に照合）")
    private final String password;

    @Schema(description = "このリンクでのダウンロード許可。既定 false（閲覧のみ）。"
            + "true でもファイル/フォルダの download_disabled が優先されDL不可", example = "false")
    private final Boolean downloadAllowed;
}
