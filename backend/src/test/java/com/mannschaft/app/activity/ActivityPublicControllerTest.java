package com.mannschaft.app.activity;

import com.mannschaft.app.activity.controller.ActivityPublicController;
import com.mannschaft.app.activity.service.PublicActivityQueryService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.publicview.dto.PublicActivityDetail;
import com.mannschaft.app.publicview.dto.PublicScopeRef;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * {@link ActivityPublicController} の単体テスト。
 * F06.4 SNS シェア用 ID 直引きエンドポイント {@code GET /api/v1/public/activities/{id}} を検証する。
 *
 * <p><b>匿名公開の安全化に伴う書き換え</b>: 本 Controller は
 * {@code ActivityRecordResponse}（認証済み API 用 DTO。{@code createdBy} /
 * {@code fieldValues} / {@code location} 等を含む）を返さなくなり、公開専用 DTO
 * {@link PublicActivityDetail}（御裁可済み 8 項目のみ）を返す。あわせて可視性判定・
 * 親スコープ検証・404 正規化は {@link PublicActivityQueryService} へ移譲されたため、
 * 本テストは <b>Controller が Query Service へ正しく委譲し、その結果／例外をそのまま通す</b>
 * ことに責務を絞る。</p>
 *
 * <p>公開契約そのもの（漏洩禁止項目・DRAFT 除外・親スコープ・スコープ詐称・404 の
 * 区別不能性）は実 Security フィルタチェーンを通す契約テスト
 * {@code ActivityPublicContractIT} が正準として検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityPublicController 単体テスト")
class ActivityPublicControllerTest {

    @Mock
    private PublicActivityQueryService publicActivityQueryService;

    @InjectMocks
    private ActivityPublicController controller;

    private static final Long ACTIVITY_ID = 42L;
    private static final Long TEAM_ID = 7L;

    private static PublicActivityDetail sampleDetail() {
        return new PublicActivityDetail(
                ACTIVITY_ID,
                "公開練習記録",
                LocalDate.of(2026, 5, 1),
                LocalTime.of(10, 0),
                LocalTime.of(12, 0),
                "公開してよい説明文",
                PublicScopeRef.ofTeam(TEAM_ID, "公開チーム"),
                LocalDateTime.of(2026, 5, 1, 9, 0));
    }

    @Nested
    @DisplayName("getPublicActivityById — GET /api/v1/public/activities/{id}")
    class GetPublicActivityById {

        @Test
        @DisplayName("正常系: 公開対象の記録は 200 と公開専用 DTO を返す")
        void 公開記録_200返却() {
            PublicActivityDetail detail = sampleDetail();
            given(publicActivityQueryService.getPublicActivityById(ACTIVITY_ID))
                    .willReturn(detail);

            ResponseEntity<ApiResponse<PublicActivityDetail>> response =
                    controller.getPublicActivityById(ACTIVITY_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getData()).isEqualTo(detail);
        }

        @Test
        @DisplayName("異常系: 非公開の記録は PUBLIC_013（404 相当）が伝播する")
        void 非公開記録_404相当を伝播() {
            willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_013))
                    .given(publicActivityQueryService).getPublicActivityById(ACTIVITY_ID);

            assertThatThrownBy(() -> controller.getPublicActivityById(ACTIVITY_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                    .isEqualTo("PUBLIC_013");
        }

        @Test
        @DisplayName("異常系: 存在しない ID も非公開と同じ PUBLIC_013（存在秘匿）")
        void 存在しないId_非公開と同一コード() {
            willThrow(new BusinessException(PublicViewErrorCode.PUBLIC_013))
                    .given(publicActivityQueryService).getPublicActivityById(9999L);

            assertThatThrownBy(() -> controller.getPublicActivityById(9999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode().getCode())
                    .isEqualTo("PUBLIC_013");
        }
    }

    @Nested
    @DisplayName("listTeamPublicActivities — GET /api/v1/public/teams/{teamId}/activities")
    class ListTeamPublicActivities {

        @Test
        @DisplayName("limit をそのまま Query Service へ渡す（丸めは Query Service の責務）")
        void limitを委譲する() {
            given(publicActivityQueryService.listPublicTeamActivities(TEAM_ID, 100000))
                    .willReturn(java.util.List.of());

            var response = controller.listTeamPublicActivities(TEAM_ID, 100000);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getData()).isEmpty();
        }
    }
}
