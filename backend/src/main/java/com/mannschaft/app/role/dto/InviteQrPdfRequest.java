package com.mannschaft.app.role.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 招待QRコードPDFの生成リクエスト。
 *
 * @param token 発行時に受け取った平文の招待トークン（DB にはハッシュしか無いため、画面が保持する値を渡す）
 */
public record InviteQrPdfRequest(@NotBlank String token) {
}
