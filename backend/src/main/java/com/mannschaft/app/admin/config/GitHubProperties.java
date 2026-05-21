package com.mannschaft.app.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * GitHub API アクセス設定プロパティ。
 *
 * <p>application.yml の {@code app.github.*} を読み込む。
 * OWASP Dependency-Check スキャン状態の取得（システム管理画面用）に使用する。</p>
 */
@ConfigurationProperties(prefix = "app.github")
public record GitHubProperties(
        @DefaultValue("kenta-0420") String owner,
        @DefaultValue("mannschaft") String repo,
        @DefaultValue("") String token
) {}
