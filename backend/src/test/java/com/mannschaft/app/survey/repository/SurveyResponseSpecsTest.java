package com.mannschaft.app.survey.repository;

import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SurveyResponseSpecs} の単体テスト。
 * DB なし・軽量テスト（Mockito で CriteriaBuilder/Root/CriteriaQuery をモック）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyResponseSpecs テスト")
class SurveyResponseSpecsTest {

    @Mock
    private Root<SurveyResponseEntity> root;

    @Mock
    private CriteriaQuery<?> query;

    @Mock
    private CriteriaBuilder cb;

    @Mock
    private Path<Boolean> isProxyInputPath;

    @Mock
    private Predicate isFalsePredicate;

    @Mock
    private Predicate conjunctionPredicate;

    // ─────────────────────────────────────────────────────────────
    // byPersonOnly
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("byPersonOnly — 本人入力のみ")
    class ByPersonOnly {

        @Test
        @DisplayName("null でない Specification を返すこと")
        void shouldReturnNonNullSpecification() {
            // When
            Specification<SurveyResponseEntity> spec = SurveyResponseSpecs.byPersonOnly();

            // Then
            assertThat(spec).isNotNull();
        }

        @Test
        @DisplayName("toPredicate が CriteriaBuilder.isFalse() を呼び出すこと")
        void shouldCallIsFalseOnCriteriaBuilder() {
            // Given
            given(root.<Boolean>get("isProxyInput")).willReturn(isProxyInputPath);
            given(cb.isFalse(any(Expression.class))).willReturn(isFalsePredicate);

            // When
            Specification<SurveyResponseEntity> spec = SurveyResponseSpecs.byPersonOnly();
            Predicate predicate = spec.toPredicate(root, query, cb);

            // Then
            verify(cb).isFalse(isProxyInputPath);
            assertThat(predicate).isEqualTo(isFalsePredicate);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // includingProxy
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("includingProxy — 代理入力含む全件")
    class IncludingProxy {

        @Test
        @DisplayName("null でない Specification を返すこと")
        void shouldReturnNonNullSpecification() {
            // When
            Specification<SurveyResponseEntity> spec = SurveyResponseSpecs.includingProxy();

            // Then
            assertThat(spec).isNotNull();
        }

        @Test
        @DisplayName("toPredicate が CriteriaBuilder.conjunction() を呼び出すこと")
        void shouldCallConjunctionOnCriteriaBuilder() {
            // Given
            given(cb.conjunction()).willReturn(conjunctionPredicate);

            // When
            Specification<SurveyResponseEntity> spec = SurveyResponseSpecs.includingProxy();
            Predicate predicate = spec.toPredicate(root, query, cb);

            // Then
            verify(cb).conjunction();
            assertThat(predicate).isEqualTo(conjunctionPredicate);
        }
    }
}
