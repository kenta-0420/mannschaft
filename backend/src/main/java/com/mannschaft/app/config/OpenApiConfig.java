package com.mannschaft.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * springdoc-openapi グローバル設定。
 *
 * <p>ネストされた record / enum など springdoc が自動で named component として
 * 登録しないスキーマを明示的に {@code components/schemas} に追加する。</p>
 */
@Configuration
public class OpenApiConfig {

    /**
     * {@link com.mannschaft.app.reflection.RecallDirection} を named component として登録する。
     *
     * <p>springdoc は ネストされた record フィールドの enum を inline 展開するため、
     * {@code @Schema(ref = ...)} を使ってもフィールドに {@code $ref} は付くが
     * スキーマ本体が {@code components/schemas} に存在しない問題が生じる（PR #1917 根治）。
     * このカスタマイザーで手動登録することで常に named component として出力される。</p>
     */
    @Bean
    public OpenApiCustomizer recallDirectionSchemaCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.components(new Components());
            }
            if (openApi.getComponents().getSchemas() == null
                    || !openApi.getComponents().getSchemas().containsKey("RecallDirection")) {
                @SuppressWarnings("rawtypes")
                Schema<String> schema = new StringSchema();
                schema.setName("RecallDirection");
                schema.setDescription(
                        "暗記カード（TERM_CARD）の出題方向（§13-B）。"
                                + "到来済み想起予定日数のパリティで決定論的に算出する。");
                schema.setEnum(List.of("MEANING_TO_TERM", "TERM_TO_MEANING"));
                openApi.getComponents().addSchemas("RecallDirection", schema);
            }
        };
    }
}
