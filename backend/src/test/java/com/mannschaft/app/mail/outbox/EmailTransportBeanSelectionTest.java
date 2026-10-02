package com.mannschaft.app.mail.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.18: AC1 EmailTransport Bean 選択テスト。
 *
 * <p>{@code mannschaft.email.simulate=true} のとき {@link LoggingEmailTransport} が選択され、
 * {@code false} または未設定のとき {@link SesEmailTransport} が選択されることを検証する。
 * ApplicationContextRunner を使い、Spring の @ConditionalOnProperty ロジックのみを
 * 軽量に確認する（Testcontainers / DB 不要）。</p>
 */
@DisplayName("AC1: EmailTransport Bean 選択テスト（simulate フラグ）")
class EmailTransportBeanSelectionTest {

    /**
     * テスト対象のクラスのみを登録して ApplicationContext を構築するベースランナー。
     * SesV2Client など外部依存は個別テストで必要に応じてモックを登録する。
     */
    private final ApplicationContextRunner baseRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    SesEmailTransport.class,
                    LoggingEmailTransport.class
            );

    @Nested
    @DisplayName("simulate=true のとき")
    class WhenSimulateTrue {

        @Test
        @DisplayName("LoggingEmailTransport が DI コンテナに存在し SesEmailTransport は存在しない")
        void loggingTransport_isPresent_sesTransport_isAbsent() {
            baseRunner
                    .withPropertyValues("mannschaft.email.simulate=true")
                    .run(context -> {
                        assertThat(context)
                                .as("simulate=true のとき LoggingEmailTransport が Bean として存在すること")
                                .hasSingleBean(LoggingEmailTransport.class);

                        assertThat(context)
                                .as("simulate=true のとき SesEmailTransport は存在しないこと")
                                .doesNotHaveBean(SesEmailTransport.class);

                        assertThat(context.getBean(EmailTransport.class))
                                .as("EmailTransport インターフェースで取得した Bean が LoggingEmailTransport であること")
                                .isInstanceOf(LoggingEmailTransport.class);
                    });
        }
    }

    @Nested
    @DisplayName("simulate=false のとき")
    class WhenSimulateFalse {

        @Test
        @DisplayName("SesEmailTransport が DI コンテナに存在し LoggingEmailTransport は存在しない")
        void sesTransport_isPresent_loggingTransport_isAbsent() {
            // SesEmailTransport は SesV2Client を必要とするのでモックを登録する
            baseRunner
                    .withPropertyValues("mannschaft.email.simulate=false")
                    .withBean("sesV2Client", software.amazon.awssdk.services.sesv2.SesV2Client.class,
                            () -> org.mockito.Mockito.mock(software.amazon.awssdk.services.sesv2.SesV2Client.class))
                    .run(context -> {
                        assertThat(context)
                                .as("simulate=false のとき SesEmailTransport が Bean として存在すること")
                                .hasSingleBean(SesEmailTransport.class);

                        assertThat(context)
                                .as("simulate=false のとき LoggingEmailTransport は存在しないこと")
                                .doesNotHaveBean(LoggingEmailTransport.class);

                        assertThat(context.getBean(EmailTransport.class))
                                .as("EmailTransport インターフェースで取得した Bean が SesEmailTransport であること")
                                .isInstanceOf(SesEmailTransport.class);
                    });
        }
    }

    @Nested
    @DisplayName("simulate プロパティ未設定のとき（既定: false = 実SES）")
    class WhenSimulateNotSet {

        @Test
        @DisplayName("SesEmailTransport が選択される（matchIfMissing=true でデフォルト実SES）")
        void sesTransport_isDefault_whenPropertyNotSet() {
            baseRunner
                    // simulate を設定しない (matchIfMissing = true → SesEmailTransport が有効)
                    .withBean("sesV2Client", software.amazon.awssdk.services.sesv2.SesV2Client.class,
                            () -> org.mockito.Mockito.mock(software.amazon.awssdk.services.sesv2.SesV2Client.class))
                    .run(context -> {
                        assertThat(context)
                                .as("simulate 未設定のとき SesEmailTransport がデフォルトで選択されること")
                                .hasSingleBean(SesEmailTransport.class);

                        assertThat(context)
                                .as("simulate 未設定のとき LoggingEmailTransport は存在しないこと")
                                .doesNotHaveBean(LoggingEmailTransport.class);
                    });
        }
    }
}
