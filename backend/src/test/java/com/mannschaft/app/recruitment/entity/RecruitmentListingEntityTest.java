package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RecruitmentListingEntity} 単体テスト。F22.1 市・部隊1 で追加した
 * {@code finalizeComplete()} 状態遷移と地域列を検証する。
 */
@DisplayName("RecruitmentListingEntity 単体テスト（F22.1 市）")
class RecruitmentListingEntityTest {

    private RecruitmentListingEntity.RecruitmentListingEntityBuilder baseBuilder() {
        LocalDateTime now = LocalDateTime.parse("2026-06-01T10:00:00");
        return RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(1L)
                .categoryId(1L)
                .title("練習試合募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(now.plusDays(7))
                .endAt(now.plusDays(7).plusHours(2))
                .applicationDeadline(now.plusDays(5))
                .autoCancelAt(now.plusDays(5))
                .capacity(10)
                .minCapacity(4)
                .createdBy(100L);
    }

    @Nested
    @DisplayName("finalizeComplete（FULL → COMPLETED 最終認証）")
    class FinalizeComplete {

        @Test
        @DisplayName("FULL から COMPLETED へ遷移できる")
        void FULLからCOMPLETEDへ遷移できる() {
            RecruitmentListingEntity listing = baseBuilder()
                    .status(RecruitmentListingStatus.FULL)
                    .build();

            listing.finalizeComplete();

            assertThat(listing.getStatus()).isEqualTo(RecruitmentListingStatus.COMPLETED);
        }

        @Test
        @DisplayName("OPEN からは遷移できず IllegalStateException")
        void OPENからは遷移できない() {
            RecruitmentListingEntity listing = baseBuilder()
                    .status(RecruitmentListingStatus.OPEN)
                    .build();

            assertThatThrownBy(listing::finalizeComplete)
                    .isInstanceOf(IllegalStateException.class);
            assertThat(listing.getStatus()).isEqualTo(RecruitmentListingStatus.OPEN);
        }

        @Test
        @DisplayName("既に COMPLETED の札を再度 finalize すると IllegalStateException")
        void 既にCOMPLETEDなら例外() {
            RecruitmentListingEntity listing = baseBuilder()
                    .status(RecruitmentListingStatus.COMPLETED)
                    .build();

            assertThatThrownBy(listing::finalizeComplete)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("地域列（prefecture_code / city_code）")
    class RegionColumns {

        @Test
        @DisplayName("地域列を設定して保持できる")
        void 地域列を設定して保持できる() {
            RecruitmentListingEntity listing = baseBuilder()
                    .prefectureCode("44")
                    .cityCode("44202")
                    .build();

            assertThat(listing.getPrefectureCode()).isEqualTo("44");
            assertThat(listing.getCityCode()).isEqualTo("44202");
        }

        @Test
        @DisplayName("地域列は未指定なら NULL（後方互換）")
        void 地域列は未指定ならNULL() {
            RecruitmentListingEntity listing = baseBuilder().build();

            assertThat(listing.getPrefectureCode()).isNull();
            assertThat(listing.getCityCode()).isNull();
        }
    }
}
