package com.mannschaft.app.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * F08.9 P3c-2 自立移行の引き継ぎ開始リクエスト
 * （{@code POST /api/v1/me/guardianship/children/{childUserId}/handover/initiate}・02_api_design §2.3）。
 *
 * <p>子がメール未登録の場合のみ、保護者が子のメールアドレスを指定できる。
 * 子に既存メールがある場合は {@code childEmail} の指定は不要であり、指定しても受理しない
 * （既存メールの上書きはメール変更フローの迂回になるため 400 で拒否・03_security §3.2 の精神）。
 * 子（受益者）のユーザーIDはパスから取得するため body には含めない（IDOR 防止）。</p>
 *
 * @param childEmail 子のメールアドレス（子がメール未登録の場合のみ指定可・任意）
 */
public record GuardianshipHandoverInitiateRequest(
        @Email @Size(max = 255) String childEmail) {
}
