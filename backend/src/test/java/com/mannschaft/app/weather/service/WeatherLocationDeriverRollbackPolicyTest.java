package com.mannschaft.app.weather.service;

import com.mannschaft.app.weather.exception.WeatherLocationDeriveException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WeatherLocationDeriver#deriveAndPersist(Long)} のトランザクション・ロールバック方針検証。
 *
 * <p>背景: 郵便番号更新の非同期リスナー
 * {@code WeatherLocationEventListener.handlePostalCodeUpdated}
 * （{@code @Async} + {@code @Transactional(REQUIRES_NEW)} + {@code @TransactionalEventListener(AFTER_COMMIT)}）が
 * {@code deriveAndPersist} を呼ぶ。{@code deriveAndPersist} がマスタ未ヒット等で
 * {@link WeatherLocationDeriveException} を投げると、共有トランザクションが
 * rollback-only にマークされ、リスナーが例外を catch しても最終 commit で
 * {@code UnexpectedRollbackException} が発生する。</p>
 *
 * <p>{@code WeatherLocationDeriveException} は「業務上想定される結果（マスタ未ヒット等）」であり
 * ロールバック不要のため、{@code @Transactional(noRollbackFor = WeatherLocationDeriveException.class)} で
 * rollback-only マークを防ぐ。本テストはこの設定がメソッドに付与されていることを担保する。</p>
 */
@DisplayName("WeatherLocationDeriver ロールバック方針 単体テスト")
class WeatherLocationDeriverRollbackPolicyTest {

    @Test
    @DisplayName("deriveAndPersist の @Transactional は WeatherLocationDeriveException を noRollbackFor に含む")
    void deriveAndPersist_hasNoRollbackForDeriveException() throws NoSuchMethodException {
        Method method = WeatherLocationDeriver.class.getDeclaredMethod("deriveAndPersist", Long.class);
        Transactional tx = method.getAnnotation(Transactional.class);

        assertThat(tx)
                .as("deriveAndPersist には @Transactional が付与されているべき")
                .isNotNull();
        assertThat(tx.noRollbackFor())
                .as("WeatherLocationDeriveException で rollback-only マークされないこと")
                .contains(WeatherLocationDeriveException.class);
    }
}
