package com.mannschaft.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArchiveDataSourceConfig 単体テスト")
class ArchiveDataSourceConfigTest {

    @Test
    @DisplayName("ArchiveDataSourceConfig は @ConditionalOnProperty アノテーションを持つ")
    void hasConditionalOnProperty() {
        var annotation = ArchiveDataSourceConfig.class.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).contains("app.archive.db.enabled");
        assertThat(annotation.havingValue()).isEqualTo("true");
    }
}
