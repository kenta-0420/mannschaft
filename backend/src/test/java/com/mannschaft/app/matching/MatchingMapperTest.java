package com.mannschaft.app.matching;

import com.mannschaft.app.matching.dto.*;
import com.mannschaft.app.matching.entity.*;
import com.mannschaft.app.matching.mapper.MatchingMapperImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MatchingMapperImpl} の単体テスト。MapStruct 生成クラスを直接インスタンス化してテストする。
 */
@DisplayName("MatchingMapper 単体テスト")
class MatchingMapperTest {

    private final MatchingMapperImpl mapper = new MatchingMapperImpl();

    // ===================== Prefecture =====================

    @Nested
    @DisplayName("toPrefectureResponse")
    class ToPrefectureResponse {

        @Test
        @DisplayName("正常系: 都道府県が変換される")
        void 都道府県_変換() throws Exception {
            PrefectureEntity entity = createPrefecture("13", "東京都");

            PrefectureResponse result = mapper.toPrefectureResponse(entity);

            assertThat(result.getCode()).isEqualTo("13");
            assertThat(result.getName()).isEqualTo("東京都");
        }

        @Test
        @DisplayName("正常系: 都道府県リスト変換")
        void 都道府県リスト_変換() throws Exception {
            PrefectureEntity e1 = createPrefecture("13", "東京都");
            PrefectureEntity e2 = createPrefecture("14", "神奈川県");

            List<PrefectureResponse> result = mapper.toPrefectureResponseList(List.of(e1, e2));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getCode()).isEqualTo("13");
            assertThat(result.get(1).getCode()).isEqualTo("14");
        }
    }

    // ===================== City =====================

    @Nested
    @DisplayName("toCityResponse")
    class ToCityResponse {

        @Test
        @DisplayName("正常系: 市区町村が変換される")
        void 市区町村_変換() throws Exception {
            CityEntity entity = createCity("13101", "13", "千代田区");

            CityResponse result = mapper.toCityResponse(entity);

            assertThat(result.getCode()).isEqualTo("13101");
            assertThat(result.getName()).isEqualTo("千代田区");
        }

        @Test
        @DisplayName("正常系: 市区町村リスト変換")
        void 市区町村リスト_変換() throws Exception {
            CityEntity e = createCity("13101", "13", "千代田区");

            List<CityResponse> result = mapper.toCityResponseList(List.of(e));

            assertThat(result).hasSize(1);
        }
    }

    // ===================== ProposedDate =====================

    @Nested
    @DisplayName("toProposedDateResponse")
    class ToProposedDateResponse {

        @Test
        @DisplayName("正常系: 日程候補が変換される")
        void 日程候補_変換() {
            MatchProposalDateEntity entity = MatchProposalDateEntity.builder()
                    .proposalId(1L)
                    .proposedDate(LocalDate.of(2025, 6, 1))
                    .proposedTimeFrom(LocalTime.of(10, 0))
                    .proposedTimeTo(LocalTime.of(12, 0))
                    .isSelected(true)
                    .build();

            ProposedDateResponse result = mapper.toProposedDateResponse(entity);

            assertThat(result.getProposedDate()).isEqualTo(LocalDate.of(2025, 6, 1));
            assertThat(result.getProposedTimeFrom()).isEqualTo(LocalTime.of(10, 0));
            assertThat(result.getIsSelected()).isTrue();
        }

        @Test
        @DisplayName("正常系: 日程候補リスト変換")
        void 日程候補リスト_変換() {
            MatchProposalDateEntity e = MatchProposalDateEntity.builder()
                    .proposalId(1L)
                    .proposedDate(LocalDate.of(2025, 7, 15))
                    .isSelected(false)
                    .build();

            List<ProposedDateResponse> result = mapper.toProposedDateResponseList(List.of(e));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getIsSelected()).isFalse();
        }
    }

    // ===================== Review =====================

    @Nested
    @DisplayName("toReviewResponse")
    class ToReviewResponse {

        @Test
        @DisplayName("正常系: isPublic=trueの場合コメントが含まれる")
        void レビュー_公開_コメントあり() {
            MatchReviewEntity entity = MatchReviewEntity.builder()
                    .proposalId(1L).reviewerTeamId(10L).revieweeTeamId(20L)
                    .rating((short) 4).comment("良いチームでした").isPublic(true)
                    .build();
            // idは生成されないため手動設定は不要

            ReviewResponse result = mapper.toReviewResponse(entity);

            assertThat(result.getProposalId()).isEqualTo(1L);
            assertThat(result.getRating()).isEqualTo((short) 4);
            assertThat(result.getComment()).isEqualTo("良いチームでした");
            assertThat(result.getIsPublic()).isTrue();
        }

        @Test
        @DisplayName("正常系: isPublic=falseの場合コメントがnull")
        void レビュー_非公開_コメントなし() {
            MatchReviewEntity entity = MatchReviewEntity.builder()
                    .proposalId(1L).reviewerTeamId(10L).revieweeTeamId(20L)
                    .rating((short) 2).comment("非公開コメント").isPublic(false)
                    .build();

            ReviewResponse result = mapper.toReviewResponse(entity);

            assertThat(result.getComment()).isNull();
            assertThat(result.getIsPublic()).isFalse();
        }

        @Test
        @DisplayName("正常系: レビューリスト変換")
        void レビューリスト_変換() {
            MatchReviewEntity e = MatchReviewEntity.builder()
                    .proposalId(1L).reviewerTeamId(10L).revieweeTeamId(20L)
                    .rating((short) 5).isPublic(true)
                    .build();

            List<ReviewResponse> result = mapper.toReviewResponseList(List.of(e));

            assertThat(result).hasSize(1);
        }
    }

    // ===================== NgTeam =====================

    @Nested
    @DisplayName("toNgTeamResponse")
    class ToNgTeamResponse {

        @Test
        @DisplayName("正常系: NGチームが変換される")
        void NGチーム_変換() {
            NgTeamEntity entity = NgTeamEntity.builder()
                    .teamId(1L).blockedTeamId(99L).reason("マナーが悪い")
                    .build();

            NgTeamResponse result = mapper.toNgTeamResponse(entity);

            assertThat(result.getBlockedTeamId()).isEqualTo(99L);
        }

        @Test
        @DisplayName("正常系: NGチームリスト変換")
        void NGチームリスト_変換() {
            NgTeamEntity e = NgTeamEntity.builder()
                    .teamId(1L).blockedTeamId(50L).build();

            List<NgTeamResponse> result = mapper.toNgTeamResponseList(List.of(e));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getBlockedTeamId()).isEqualTo(50L);
        }
    }

    // ===================== Template =====================

    @Nested
    @DisplayName("toTemplateResponse")
    class ToTemplateResponse {

        @Test
        @DisplayName("正常系: テンプレートが変換される")
        void テンプレート_変換() {
            MatchRequestTemplateEntity entity = MatchRequestTemplateEntity.builder()
                    .teamId(1L).name("サッカー募集テンプレ")
                    .templateJson("{\"activityType\":\"SOCCER\"}")
                    .build();

            TemplateResponse result = mapper.toTemplateResponse(entity);

            assertThat(result.getName()).isEqualTo("サッカー募集テンプレ");
            assertThat(result.getTemplateJson()).isEqualTo("{\"activityType\":\"SOCCER\"}");
        }

        @Test
        @DisplayName("正常系: テンプレートリスト変換")
        void テンプレートリスト_変換() {
            MatchRequestTemplateEntity e = MatchRequestTemplateEntity.builder()
                    .teamId(1L).name("テンプレ1").templateJson("{}")
                    .build();

            List<TemplateResponse> result = mapper.toTemplateResponseList(List.of(e));

            assertThat(result).hasSize(1);
        }
    }

    // ===================== NotificationPreference =====================

    @Nested
    @DisplayName("toNotificationPreferenceResponse")
    class ToNotificationPreferenceResponse {

        @Test
        @DisplayName("正常系: activityType・categoryが変換される")
        void 通知設定_フィールドあり_変換() {
            MatchNotificationPreferenceEntity entity = MatchNotificationPreferenceEntity.builder()
                    .teamId(1L).prefectureCode("13").cityCode("13101")
                    .activityType(ActivityType.COMPETITION)
                    .category(MatchCategory.ADULT)
                    .isEnabled(true).build();

            NotificationPreferenceResponse result = mapper.toNotificationPreferenceResponse(entity);

            assertThat(result.getPrefectureCode()).isEqualTo("13");
            assertThat(result.getActivityType()).isEqualTo("COMPETITION");
            assertThat(result.getCategory()).isEqualTo("ADULT");
            assertThat(result.getIsEnabled()).isTrue();
        }

        @Test
        @DisplayName("正常系: activityType・categoryがnullの場合nullを返す")
        void 通知設定_フィールドNull_変換() {
            MatchNotificationPreferenceEntity entity = MatchNotificationPreferenceEntity.builder()
                    .teamId(1L).isEnabled(false).build();

            NotificationPreferenceResponse result = mapper.toNotificationPreferenceResponse(entity);

            assertThat(result.getActivityType()).isNull();
            assertThat(result.getCategory()).isNull();
            assertThat(result.getIsEnabled()).isFalse();
        }
    }

    // ===================== Private helpers =====================

    private PrefectureEntity createPrefecture(String code, String name) throws Exception {
        var constructor = PrefectureEntity.class.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(code, name);
    }

    private CityEntity createCity(String code, String prefCode, String name) throws Exception {
        var constructor = CityEntity.class.getDeclaredConstructor(String.class, String.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(code, prefCode, name);
    }
}
