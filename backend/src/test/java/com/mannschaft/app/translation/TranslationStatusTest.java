package com.mannschaft.app.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TranslationStatus 単体テスト")
class TranslationStatusTest {

    @Nested
    @DisplayName("canTransitionTo")
    class CanTransitionTo {

        @Nested
        @DisplayName("DRAFT からの遷移")
        class FromDraft {

            @ParameterizedTest(name = "DRAFT → {0} は許可される")
            @EnumSource(value = TranslationStatus.class, names = {"IN_REVIEW", "PUBLISHED"})
            @DisplayName("許可される遷移")
            void allowedTransitions(TranslationStatus target) {
                assertThat(TranslationStatus.DRAFT.canTransitionTo(target)).isTrue();
            }

            @ParameterizedTest(name = "DRAFT → {0} は拒否される")
            @EnumSource(value = TranslationStatus.class, names = {"NEEDS_UPDATE"})
            @DisplayName("許可されない遷移")
            void disallowedTransitions(TranslationStatus target) {
                assertThat(TranslationStatus.DRAFT.canTransitionTo(target)).isFalse();
            }

            @Test
            @DisplayName("自己遷移 DRAFT → DRAFT は拒否される")
            void selfTransition() {
                assertThat(TranslationStatus.DRAFT.canTransitionTo(TranslationStatus.DRAFT)).isFalse();
            }
        }

        @Nested
        @DisplayName("IN_REVIEW からの遷移")
        class FromInReview {

            @ParameterizedTest(name = "IN_REVIEW → {0} は許可される")
            @EnumSource(value = TranslationStatus.class, names = {"PUBLISHED", "DRAFT"})
            @DisplayName("許可される遷移")
            void allowedTransitions(TranslationStatus target) {
                assertThat(TranslationStatus.IN_REVIEW.canTransitionTo(target)).isTrue();
            }

            @ParameterizedTest(name = "IN_REVIEW → {0} は拒否される")
            @EnumSource(value = TranslationStatus.class, names = {"NEEDS_UPDATE"})
            @DisplayName("許可されない遷移")
            void disallowedTransitions(TranslationStatus target) {
                assertThat(TranslationStatus.IN_REVIEW.canTransitionTo(target)).isFalse();
            }

            @Test
            @DisplayName("自己遷移 IN_REVIEW → IN_REVIEW は拒否される")
            void selfTransition() {
                assertThat(TranslationStatus.IN_REVIEW.canTransitionTo(TranslationStatus.IN_REVIEW)).isFalse();
            }
        }

        @Nested
        @DisplayName("PUBLISHED からの遷移")
        class FromPublished {

            @Test
            @DisplayName("PUBLISHED → DRAFT は許可される")
            void allowedTransitionToDraft() {
                assertThat(TranslationStatus.PUBLISHED.canTransitionTo(TranslationStatus.DRAFT)).isTrue();
            }

            @ParameterizedTest(name = "PUBLISHED → {0} は拒否される")
            @EnumSource(value = TranslationStatus.class, names = {"IN_REVIEW", "NEEDS_UPDATE"})
            @DisplayName("許可されない遷移")
            void disallowedTransitions(TranslationStatus target) {
                assertThat(TranslationStatus.PUBLISHED.canTransitionTo(target)).isFalse();
            }

            @Test
            @DisplayName("自己遷移 PUBLISHED → PUBLISHED は拒否される")
            void selfTransition() {
                assertThat(TranslationStatus.PUBLISHED.canTransitionTo(TranslationStatus.PUBLISHED)).isFalse();
            }
        }

        @Nested
        @DisplayName("NEEDS_UPDATE からの遷移")
        class FromNeedsUpdate {

            @ParameterizedTest(name = "NEEDS_UPDATE → {0} は許可される")
            @EnumSource(value = TranslationStatus.class, names = {"DRAFT", "PUBLISHED"})
            @DisplayName("許可される遷移")
            void allowedTransitions(TranslationStatus target) {
                assertThat(TranslationStatus.NEEDS_UPDATE.canTransitionTo(target)).isTrue();
            }

            @ParameterizedTest(name = "NEEDS_UPDATE → {0} は拒否される")
            @EnumSource(value = TranslationStatus.class, names = {"IN_REVIEW"})
            @DisplayName("許可されない遷移")
            void disallowedTransitions(TranslationStatus target) {
                assertThat(TranslationStatus.NEEDS_UPDATE.canTransitionTo(target)).isFalse();
            }

            @Test
            @DisplayName("自己遷移 NEEDS_UPDATE → NEEDS_UPDATE は拒否される")
            void selfTransition() {
                assertThat(TranslationStatus.NEEDS_UPDATE.canTransitionTo(TranslationStatus.NEEDS_UPDATE)).isFalse();
            }
        }
    }
}
