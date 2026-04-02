package com.mannschaft.app.gdpr;

import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalDataCoverageValidator 単体テスト")
class PersonalDataCoverageValidatorTest {

    @Mock
    private PersonalDataCollector personalDataCollector;

    @InjectMocks
    private PersonalDataCoverageValidator validator;

    @Mock
    private ApplicationArguments applicationArguments;

    @Nested
    @DisplayName("run")
    class Run {

        @Test
        @DisplayName("正常系: 全カテゴリ登録済みの場合、例外なく完了する")
        void 正常_全カテゴリ登録済み_正常完了() throws Exception {
            given(personalDataCollector.getCategoryKeys())
                    .willReturn(List.of("account", "payments", "activity", "schedule", "chart",
                            "cms", "filesharing", "todo", "moderation", "audit_log"));

            // @PersonalDataアノテーションがついたクラスはClassPathScanningで検索されるが
            // テスト環境では実際にスキャンするため例外が出ないことを確認
            assertThatCode(() -> validator.run(applicationArguments))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("正常系: カテゴリが少ない場合でも例外をスローしない（ERRORログのみ）")
        void 正常_カテゴリ少ない_例外なし() throws Exception {
            // 登録済みカテゴリが少ない場合、ERRORログは出るが例外はスローしない
            given(personalDataCollector.getCategoryKeys())
                    .willReturn(List.of("account"));

            assertThatCode(() -> validator.run(applicationArguments))
                    .doesNotThrowAnyException();
        }
    }
}
