package com.mannschaft.app.receipt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 発行者設定更新リクエストDTO（PATCH・差分更新）。
 *
 * <p>意味論（F08.4 §9.2）:</p>
 * <ul>
 *   <li>未送信・{@code null} のフィールドは<b>無変更</b></li>
 *   <li>空文字は<b>明示的なクリア</b>（サービス層で {@code NULL} に正規化する）</li>
 * </ul>
 *
 * <p>本 DTO は<b>形式（文字数・正規表現）だけ</b>を見る。必須性・組み合わせの整合といった
 * 不変条件は、既存エンティティとリクエストを<b>マージした後の状態</b>に対して
 * {@code ReceiptIssuerSettingsService} が検証する。したがって {@code issuerName} /
 * {@code isQualifiedInvoicer} に {@code @NotBlank} / {@code @NotNull} は付けない
 * （付けると 1 項目だけの差分更新が必ず 400 になる）。</p>
 */
@Getter
@RequiredArgsConstructor
public class UpdateIssuerSettingsRequest {

    @Size(max = 200)
    private final String issuerName;

    @Size(max = 10)
    private final String postalCode;

    @Size(max = 500)
    private final String address;

    @Size(max = 20)
    private final String phone;

    private final Boolean isQualifiedInvoicer;

    // 空文字は「明示クリア」として DTO を通し、サービス層のマージ後検証に委ねる。
    @Pattern(regexp = "^(|T\\d{13})$", message = "登録番号はT + 13桁の数字で入力してください")
    @Size(max = 14)
    private final String invoiceRegistrationNumber;

    private final Long defaultSealUserId;

    @Size(max = 20)
    private final String defaultSealVariant;

    private final String receiptNoteTemplate;

    @Size(max = 20)
    private final String receiptNumberPrefix;

    @Min(1)
    @Max(12)
    private final Integer fiscalYearStartMonth;

    private final Boolean autoResetNumber;

    @Size(max = 500)
    private final String customFooter;
}
