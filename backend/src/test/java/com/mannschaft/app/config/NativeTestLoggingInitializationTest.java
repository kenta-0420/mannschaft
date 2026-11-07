package com.mannschaft.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Spring 起動前の実テストクラスパスで、Logback の既定出力と個別ログ検証の契約を確認する。
 * 独立 context を使い、LevelChangePropagator が変更する JUL の共有レベルも復元する。
 */
class NativeTestLoggingInitializationTest {

    private final Map<java.util.logging.Logger, java.util.logging.Level> previousJulLevels =
            new IdentityHashMap<>();
    private final Map<java.util.logging.Logger, Handler[]> previousJulHandlers = new IdentityHashMap<>();
    private LoggerContext context;

    @BeforeEach
    void initializeNativeLogging() throws Exception {
        LogManager manager = LogManager.getLogManager();
        for (String name : Collections.list(manager.getLoggerNames())) {
            java.util.logging.Logger logger = manager.getLogger(name);
            if (logger != null) {
                previousJulLevels.put(logger, logger.getLevel());
                previousJulHandlers.put(logger, logger.getHandlers());
            }
        }
        context = new LoggerContext();
        context.setName("native-test-logging-regression");
        context.setMDCAdapter(new LogbackMDCAdapter());
        new ContextInitializer(context).autoConfig();
    }

    @AfterEach
    void restoreSharedJulState() {
        if (context != null) {
            context.stop();
        }
        LogManager manager = LogManager.getLogManager();
        // 初期化やログ出力中に新設された JUL logger も、明示レベルなしへ戻す。
        for (String name : Collections.list(manager.getLoggerNames())) {
            java.util.logging.Logger logger = manager.getLogger(name);
            if (logger != null) {
                logger.setLevel(previousJulLevels.get(logger));
            }
        }
        previousJulLevels.forEach((logger, level) -> {
            logger.setLevel(level);
            assertThat(logger.getHandlers()).containsExactly(previousJulHandlers.get(logger));
        });
    }

    @Test
    void Spring起動前も低レベル出力を抑え警告とエラーと例外スタックを残す() {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger logger = context.getLogger("com.mannschaft.logging.nativeinitialization");
        var appender = root.iteratorForAppenders().next();
        assertThat(appender).isInstanceOf(ConsoleAppender.class);
        var console = (ConsoleAppender<ILoggingEvent>) appender;
        var output = new ByteArrayOutputStream();
        // 実設定の encoder/filter/level を保持し、出力先だけをこのテストのメモリへ向ける。
        console.setOutputStream(output);

        System.out.println("native logging resource=" + getClass().getClassLoader().getResource("logback-test.xml")
                + " root=" + root.getEffectiveLevel() + " class=" + logger.getEffectiveLevel());
        context.getStatusManager().getCopyOfStatusList().stream()
                .map(status -> status.getMessage())
                .filter(message -> message.contains("logback-test.xml") || message.contains("BasicConfigurator"))
                .limit(8)
                .forEach(message -> System.out.println("native logging status=" + message));

        logger.debug("native-debug-marker");
        logger.info("native-info-marker");
        logger.warn("native-warning-marker");
        logger.error("native-error-marker", new IllegalStateException("native-stack-marker"));

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertThat(rendered).doesNotContain("native-debug-marker", "native-info-marker")
                .contains("WARN", "native-warning-marker", "ERROR", "native-error-marker",
                        "java.lang.IllegalStateException: native-stack-marker",
                        "NativeTestLoggingInitializationTest.");
        assertThat(root.getEffectiveLevel()).isEqualTo(Level.WARN);
        assertThat(logger.getEffectiveLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void 個別INFOとDEBUGを明示するListAppender検証は成立し継承状態へ戻せる() {
        Logger logger = context.getLogger("com.mannschaft.logging.explicitcapture");
        Level previousLevel = logger.getLevel();
        var capture = new ListAppender<ILoggingEvent>();
        capture.setContext(context);
        capture.start();
        logger.addAppender(capture);
        try {
            logger.setLevel(Level.INFO);
            logger.info("explicit-info-marker");
            logger.setLevel(Level.DEBUG);
            logger.debug("explicit-debug-marker");
            assertThat(capture.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("explicit-info-marker", "explicit-debug-marker");
        } finally {
            logger.detachAppender(capture);
            capture.stop();
            logger.setLevel(previousLevel);
        }
        assertThat(logger.getLevel()).isEqualTo(previousLevel);
        assertThat(logger.getEffectiveLevel()).isEqualTo(context.getLogger(Logger.ROOT_LOGGER_NAME).getEffectiveLevel());
    }
}
