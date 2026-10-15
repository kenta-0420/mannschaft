package com.mannschaft.app.social.announcement.dto;

import com.mannschaft.app.social.announcement.AnnouncementChannel;
import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BroadcastRequestDto} のバリデーション単体テスト。
 * Jakarta Validation を使って必須フィールドのバリデーションを検証する。
 */
@DisplayName("BroadcastRequestDto バリデーションテスト")
class BroadcastRequestDtoTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 正常系: バリデーションエラーなし
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("正常系 — 全フィールドが有効値の場合")
    class ValidDto {

        @Test
        @DisplayName("全フィールドが正常値ならバリデーションエラーが発生しないこと")
        void noViolationsWhenAllFieldsAreValid() {
            // given
            BroadcastRequestDto dto = BroadcastRequestDto.builder()
                    .channel(AnnouncementChannel.BULLETIN_THREAD)
                    .targetRole("MEMBERS_AND_ABOVE")
                    .content(AnnouncementContentRequest.builder()
                            .title("告知タイトル")
                            .build())
                    .build();

            // when
            Set<ConstraintViolation<BroadcastRequestDto>> violations = validator.validate(dto);

            // then
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("オプションフィールド（priority, expiresAt, templateId, targetTeamIds）が null でもエラーなし")
        void noViolationsWhenOptionalFieldsAreNull() {
            // given
            BroadcastRequestDto dto = BroadcastRequestDto.builder()
                    .channel(AnnouncementChannel.SURVEY)
                    .targetRole("PUBLIC")
                    .content(AnnouncementContentRequest.builder()
                            .title("必須フィールドのみ")
                            .build())
                    // templateId, expiresAt, targetTeamIds は省略
                    .build();

            // when
            Set<ConstraintViolation<BroadcastRequestDto>> violations = validator.validate(dto);

            // then
            assertThat(violations).isEmpty();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 異常系: 必須フィールド欠如
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("異常系 — 必須フィールドが null の場合")
    class RequiredFieldViolations {

        @Test
        @DisplayName("channel が null のときバリデーションエラーが発生すること")
        void violationWhenChannelIsNull() {
            // given
            BroadcastRequestDto dto = BroadcastRequestDto.builder()
                    .channel(null)
                    .targetRole("MEMBERS_AND_ABOVE")
                    .content(AnnouncementContentRequest.builder()
                            .title("タイトルあり")
                            .build())
                    .build();

            // when
            Set<ConstraintViolation<BroadcastRequestDto>> violations = validator.validate(dto);

            // then
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("channel"));
        }

        @Test
        @DisplayName("targetRole が null のときバリデーションエラーが発生すること")
        void violationWhenTargetRoleIsNull() {
            // given
            BroadcastRequestDto dto = BroadcastRequestDto.builder()
                    .channel(AnnouncementChannel.BULLETIN_THREAD)
                    .targetRole(null)
                    .content(AnnouncementContentRequest.builder()
                            .title("タイトルあり")
                            .build())
                    .build();

            // when
            Set<ConstraintViolation<BroadcastRequestDto>> violations = validator.validate(dto);

            // then
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("targetRole"));
        }

        @Test
        @DisplayName("content が null のときバリデーションエラーが発生すること")
        void violationWhenContentIsNull() {
            // given
            BroadcastRequestDto dto = BroadcastRequestDto.builder()
                    .channel(AnnouncementChannel.SCHEDULE)
                    .targetRole("MEMBERS_AND_ABOVE")
                    .content(null)
                    .build();

            // when
            Set<ConstraintViolation<BroadcastRequestDto>> violations = validator.validate(dto);

            // then
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("content"));
        }

        @Test
        @DisplayName("channel・targetRole・content が全て null のとき3件のバリデーションエラーが発生すること")
        void allRequiredFieldsNullGivesThreeViolations() {
            // given
            BroadcastRequestDto dto = BroadcastRequestDto.builder()
                    .channel(null)
                    .targetRole(null)
                    .content(null)
                    .build();

            // when
            Set<ConstraintViolation<BroadcastRequestDto>> violations = validator.validate(dto);

            // then: channel, targetRole, content の3フィールドでエラー
            assertThat(violations).hasSize(3);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 境界値: targetRole の長さ制限
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("targetRole の長さ制限")
    class TargetRoleSizeLimit {

        @Test
        @DisplayName("targetRole が30文字以内ならエラーなし")
        void noViolationWhenTargetRoleIsWithinLimit() {
            // given
            String targetRole = "A".repeat(30); // ちょうど30文字
            BroadcastRequestDto dto = BroadcastRequestDto.builder()
                    .channel(AnnouncementChannel.BULLETIN_THREAD)
                    .targetRole(targetRole)
                    .content(AnnouncementContentRequest.builder()
                            .title("タイトル")
                            .build())
                    .build();

            // when
            Set<ConstraintViolation<BroadcastRequestDto>> violations = validator.validate(dto);

            // then
            assertThat(violations).isEmpty();
        }

        @Test
        @DisplayName("targetRole が31文字以上のときバリデーションエラーが発生すること")
        void violationWhenTargetRoleExceedsMaxLength() {
            // given
            String targetRole = "A".repeat(31); // 31文字（上限超過）
            BroadcastRequestDto dto = BroadcastRequestDto.builder()
                    .channel(AnnouncementChannel.BULLETIN_THREAD)
                    .targetRole(targetRole)
                    .content(AnnouncementContentRequest.builder()
                            .title("タイトル")
                            .build())
                    .build();

            // when
            Set<ConstraintViolation<BroadcastRequestDto>> violations = validator.validate(dto);

            // then
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("targetRole"));
        }
    }
}
