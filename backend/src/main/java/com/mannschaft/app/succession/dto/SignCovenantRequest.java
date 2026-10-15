package com.mannschaft.app.succession.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 入居時誓約の署名リクエスト DTO（F09.15 §6 / §7.1）。
 *
 * <p>本 DTO は「PDF 生成 + 内部署名トークン付与 + テーブル INSERT」の一括処理を
 * トリガーする。署名は ADMIN がテンプレートを準備した上で本人（区分所有者）が
 * 同意項目を確認して呼ぶ。
 *
 * <p>同意項目（{@code confirmedItems}）はダークパターン回避のための必須チェックで、
 * Service 側で {@code covenantType} ごとに必要な項目キーが含まれているか検証する。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignCovenantRequest {

    /**
     * 誓約区分。SUCCESSION_PRE_REGISTRATION / PRIVACY_CONSENT / MONITORING_CONSENT のいずれか。
     */
    @NotBlank(message = "誓約区分は必須です")
    @Pattern(
            regexp = "SUCCESSION_PRE_REGISTRATION|PRIVACY_CONSENT|MONITORING_CONSENT",
            message = "誓約区分が不正です")
    @JsonAlias("covenant_type")
    private String covenantType;

    /**
     * 居住者台帳 ID（{@code resident_registry.id}）。本人の台帳 ID を必須で受け取り、
     * クロスドメイン参照は INDEX のみ・FK なしのため Service 側で実存確認を行う。
     */
    @NotNull(message = "居住者台帳IDは必須です")
    @JsonAlias("resident_registry_id")
    private Long residentRegistryId;

    /**
     * 誓約テンプレートのバージョン（例: "v1.0.0"）。
     * 法令改正等で再同意が必要になった場合、本フィールドで世代管理する。
     */
    @NotBlank(message = "誓約バージョンは必須です")
    @Size(max = 20, message = "誓約バージョンは20文字以内です")
    @JsonAlias("covenant_version")
    private String covenantVersion;

    /**
     * 同意項目のキー一覧（ダークパターン回避用）。
     * 例: ["agree_succession_pre_registration", "agree_data_retention_10y"]。
     * Service 側で covenantType ごとに必須キーが含まれているか検証する。
     */
    @NotNull(message = "同意項目は必須です")
    @JsonAlias("confirmed_items")
    private List<String> confirmedItems;
}
