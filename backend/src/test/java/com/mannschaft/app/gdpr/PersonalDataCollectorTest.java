package com.mannschaft.app.gdpr;

import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalDataCollector 単体テスト")
class PersonalDataCollectorTest {

    private PersonalDataCollector collector;

    @BeforeEach
    void setUp() {
        collector = new PersonalDataCollector();
    }

    @Nested
    @DisplayName("collect")
    class Collect {

        @Test
        @DisplayName("正常系: nullカテゴリで全カテゴリが収集される")
        void 正常_nullカテゴリ_全カテゴリ収集() {
            List<PersonalDataCollector.CategoryData> result = collector.collect(1L, null);

            assertThat(result).hasSize(10);
            assertThat(result).extracting(PersonalDataCollector.CategoryData::category)
                    .containsExactlyInAnyOrder(
                            "account", "payments", "activity", "schedule", "chart",
                            "cms", "filesharing", "todo", "moderation", "audit_log"
                    );
        }

        @Test
        @DisplayName("正常系: [account, payments]指定で2ファイルのみ返る")
        void 正常_部分カテゴリ_2件返却() {
            List<PersonalDataCollector.CategoryData> result = collector.collect(1L, List.of("account", "payments"));

            assertThat(result).hasSize(2);
            assertThat(result).extracting(PersonalDataCollector.CategoryData::category)
                    .containsExactlyInAnyOrder("account", "payments");
        }

        @Test
        @DisplayName("異常系: カテゴリ収集失敗時は空JSON([])でスキップされる")
        void 異常_収集失敗時スキップ() {
            // 失敗するコレクターを持つPersonalDataCollectorを作成（パッケージプライベートコンストラクタ使用）
            Map<String, Function<Long, String>> failingCollectors = Map.of(
                    "account", userId -> { throw new RuntimeException("DB error"); },
                    "payments", userId -> "[{\"amount\":100}]"
            );
            PersonalDataCollector collectorWithFail = new PersonalDataCollector(failingCollectors);

            List<PersonalDataCollector.CategoryData> result = collectorWithFail.collect(1L, null);

            assertThat(result).hasSize(2);

            PersonalDataCollector.CategoryData accountData = result.stream()
                    .filter(d -> "account".equals(d.category()))
                    .findFirst().orElseThrow();
            assertThat(accountData.json()).isEqualTo("[]");

            PersonalDataCollector.CategoryData paymentData = result.stream()
                    .filter(d -> "payments".equals(d.category()))
                    .findFirst().orElseThrow();
            assertThat(paymentData.json()).isEqualTo("[{\"amount\":100}]");
        }
    }

    @Nested
    @DisplayName("getCategoryKeys")
    class GetCategoryKeys {

        @Test
        @DisplayName("正常系: 10カテゴリキーが返る")
        void 正常_10カテゴリキー返却() {
            List<String> keys = collector.getCategoryKeys();

            assertThat(keys).hasSize(10);
            assertThat(keys).containsExactlyInAnyOrder(
                    "account", "payments", "activity", "schedule", "chart",
                    "cms", "filesharing", "todo", "moderation", "audit_log"
            );
        }
    }
}
