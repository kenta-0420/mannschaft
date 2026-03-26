package com.mannschaft.app.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF生成用フォント設定。
 * NotoSansJP / NotoSerifJP フォントファイルのリソースパスを管理し、
 * PdfGeneratorService からフォント登録時に参照される。
 */
@Slf4j
@Configuration
public class PdfFontConfig {

    private final List<FontEntry> registeredFonts = new ArrayList<>();

    @PostConstruct
    public void init() {
        registerFont("fonts/NotoSansJP-Regular.ttf", "NotoSansJP", true);
        registerFont("fonts/NotoSerifJP-Regular.ttf", "NotoSerifJP", true);
    }

    private void registerFont(String classpathLocation, String familyName, boolean optional) {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            if (optional) {
                log.info("任意フォントが見つかりません（スキップ）: {}", classpathLocation);
                return;
            }
            log.error("必須フォントが見つかりません: {}", classpathLocation);
            throw new IllegalStateException("必須フォントが見つかりません: " + classpathLocation);
        }
        registeredFonts.add(new FontEntry(classpathLocation, familyName));
        log.info("フォント登録準備完了: {} ({})", familyName, classpathLocation);
    }

    public List<FontEntry> getRegisteredFonts() {
        return List.copyOf(registeredFonts);
    }

    public record FontEntry(String classpathLocation, String familyName) {}
}
