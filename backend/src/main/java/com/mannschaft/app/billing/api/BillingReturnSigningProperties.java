package com.mannschaft.app.billing.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * PR4 署名付き return state（BC-16 / BC-28）の HMAC 鍵設定。
 *
 * <p>{@code mannschaft.billing.return-state} 配下にバインドされる。金型は
 * {@code com.mannschaft.app.jobmatching.config.QrSigningProperties}（kid / secret / active の
 * リストで鍵ローテーションを表現する）。</p>
 *
 * <p><b>secret は必ず環境変数から与えること</b>（{@code MANNSCHAFT_BILLING_RETURN_SIGNING_SECRET}）。
 * リポジトリへ平文を置いてはならない。未設定時の挙動は
 * {@link BillingReturnSigningKeyProviderImpl} の javadoc を参照。</p>
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "mannschaft.billing.return-state")
public class BillingReturnSigningProperties {

    /** HMAC-SHA256 署名鍵リスト。空でも起動は妨げない（fail-closed は provider 側が担う）。 */
    @Valid
    private List<SigningKey> signingKeys = new ArrayList<>();

    /** HMAC 署名鍵エントリ。{@code active=true} の最後の鍵で新規発行し、検証は全鍵から kid で解決する。 */
    @Getter
    @Setter
    public static class SigningKey {

        /** 鍵 ID（token の先頭セグメント）。 */
        @NotBlank
        private String kid;

        /** HMAC-SHA256 用 secret。UTF-8 バイト列で 32 bytes 以上必須。 */
        private String secret;

        /** アクティブフラグ。新規発行に使う鍵は {@code true}、旧鍵は {@code false} で検証のみ。 */
        @NotNull
        private Boolean active = Boolean.TRUE;
    }
}
