package com.mannschaft.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4-D — {@link DataSourceContextHolder} の単体テスト。
 *
 * <p>ThreadLocal の set / get / clear が正しく動作することを確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DataSourceContextHolder 単体テスト")
class DataSourceContextHolderTest {

    @Test
    @DisplayName("setDataSourceType で REPLICA をセット後に getDataSourceType で取得できる")
    void setAndGet() {
        DataSourceContextHolder.setDataSourceType(DataSourceType.REPLICA);
        assertThat(DataSourceContextHolder.getDataSourceType()).isEqualTo(DataSourceType.REPLICA);
        DataSourceContextHolder.clear();
    }

    @Test
    @DisplayName("clear 後は null になる")
    void clearResetsToNull() {
        DataSourceContextHolder.setDataSourceType(DataSourceType.PRIMARY);
        DataSourceContextHolder.clear();
        assertThat(DataSourceContextHolder.getDataSourceType()).isNull();
    }

    @Test
    @DisplayName("PRIMARY をセットして取得できる")
    void setAndGetPrimary() {
        DataSourceContextHolder.setDataSourceType(DataSourceType.PRIMARY);
        assertThat(DataSourceContextHolder.getDataSourceType()).isEqualTo(DataSourceType.PRIMARY);
        DataSourceContextHolder.clear();
    }
}
