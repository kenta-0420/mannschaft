package com.mannschaft.app.proxy.dto;

import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity;
import com.mannschaft.app.proxy.service.CreateProxyConsentCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 代理入力同意書登録リクエスト（F14.1）。
 */
@Getter
@NoArgsConstructor
public class CreateProxyInputConsentRequest {

    @NotNull
    private Long subjectUserId;

    @NotNull
    private Long proxyUserId;

    /** ConsentMethod enum の文字列表現。Service層でenum変換・バリデーション。 */
    @NotBlank
    private String consentMethod;

    @Size(max = 512)
    private String scannedDocumentS3Key;

    @Size(max = 512)
    private String guardianCertificateS3Key;

    private Long witnessUserId;

    @NotNull
    private LocalDate effectiveFrom;

    @NotNull
    private LocalDate effectiveUntil;

    /** FeatureScope enum の文字列表現リスト。 */
    @NotEmpty
    private List<@NotBlank String> scopes;

    /**
     * Service 入力用コマンドに変換する。
     * enum 変換失敗時は IllegalArgumentException → 400 Bad Request に変換される。
     */
    public CreateProxyConsentCommand toCommand() {
        ProxyInputConsentEntity.ConsentMethod method =
                ProxyInputConsentEntity.ConsentMethod.valueOf(consentMethod);
        List<ProxyInputConsentScopeEntity.FeatureScope> featureScopes = scopes.stream()
                .map(ProxyInputConsentScopeEntity.FeatureScope::valueOf)
                .toList();
        return new CreateProxyConsentCommand(
                subjectUserId,
                proxyUserId,
                method,
                scannedDocumentS3Key,
                guardianCertificateS3Key,
                witnessUserId,
                effectiveFrom,
                effectiveUntil,
                featureScopes
        );
    }
}
