package com.mannschaft.app.admin.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * セキュリティスキャン状態ウィジェット用設定クラス。
 *
 * <p>GitHub Actions API への HTTP クライアント（RestTemplate）と
 * {@link GitHubProperties} を Spring 管理下に置く。</p>
 */
@Configuration
@EnableConfigurationProperties(GitHubProperties.class)
public class SecurityScanConfig {

    /**
     * GitHub Actions API 呼び出し専用の RestTemplate。
     *
     * <p>名前付き（"gitHubRestTemplate"）で登録し、他の Bean の RestTemplate と
     * 干渉しないようにする。タイムアウトはデフォルト（無制限）ではなく
     * SimpleClientHttpRequestFactory で 5 秒を設定する。</p>
     */
    @Bean("gitHubRestTemplate")
    public RestTemplate gitHubRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }
}
