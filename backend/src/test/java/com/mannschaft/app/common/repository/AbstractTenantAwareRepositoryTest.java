package com.mannschaft.app.common.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AbstractTenantAwareRepository} のコンパイル検証テスト。
 * {@code @NoRepositoryBean} インターフェースは直接インスタンス化できないため、
 * リフレクションを用いてインターフェース定義の正確性を確認する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractTenantAwareRepository コンパイル検証")
class AbstractTenantAwareRepositoryTest {

    @Test
    @DisplayName("インターフェース定義が正しく存在する")
    void インターフェース定義が正しく存在する() {
        // インターフェースが @NoRepositoryBean を持つことを確認
        assertThat(AbstractTenantAwareRepository.class.isInterface()).isTrue();
        assertThat(AbstractTenantAwareRepository.class
                .isAnnotationPresent(
                    org.springframework.data.repository.NoRepositoryBean.class))
                .isTrue();
    }
}
