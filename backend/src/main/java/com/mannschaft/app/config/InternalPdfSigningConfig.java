package com.mannschaft.app.config;

import com.mannschaft.app.common.pdf.InternalPdfSigningProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * F12.1 §5.14 / F09.15 §9.4 — 内部 PDF 署名トークン Properties の有効化。
 *
 * <p>{@link InternalPdfSigningProperties} を ConfigurationProperties として登録し、
 * Spring コンテキストに Bean として公開する。値の検証（鍵未設定時の fail-fast）は
 * 利用側 {@code PdfGeneratorService#requireSigningKey} で行う（誓約 PDF 機能を使わない
 * 既存テストやプロファイルでは鍵未設定でも起動できるようにするため）。
 */
@Configuration
@EnableConfigurationProperties(InternalPdfSigningProperties.class)
public class InternalPdfSigningConfig {
}
