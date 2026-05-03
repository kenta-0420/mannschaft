package com.mannschaft.app.proxy.dto;

import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.service.RevokeConsentCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 代理入力同意書撤回リクエスト（F14.1）。
 */
@Getter
@NoArgsConstructor
public class RevokeProxyInputConsentRequest {

    /** RevokeMethod enum の文字列表現。Service層でenum変換・バリデーション。 */
    @NotBlank
    private String revokeMethod;

    private Long revokeWitnessedByUserId;

    @Size(max = 255)
    private String revokeReason;

    public RevokeConsentCommand toCommand() {
        ProxyInputConsentEntity.RevokeMethod method =
                ProxyInputConsentEntity.RevokeMethod.valueOf(revokeMethod);
        return new RevokeConsentCommand(method, revokeReason, revokeWitnessedByUserId);
    }
}
