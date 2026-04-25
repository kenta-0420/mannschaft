package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.dto.MemberWorkConstraintRequest;
import com.mannschaft.app.shift.dto.MemberWorkConstraintResponse;
import com.mannschaft.app.shift.entity.MemberWorkConstraintEntity;
import com.mannschaft.app.shift.repository.MemberWorkConstraintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link MemberWorkConstraintService} の単体テスト。
 *
 * <p>F03.5 v2 勤務制約 CRUD の権限境界・解決順序・全NULL拒否を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberWorkConstraintService 単体テスト")
class MemberWorkConstraintServiceTest {

    @Mock
    private MemberWorkConstraintRepository constraintRepository;

    @Mock
    private ShiftMapper shiftMapper;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private MemberWorkConstraintService service;

    // ========================================
    // テスト用定数・ヘルパ
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long ADMIN_USER_ID = 10L;
    private static final Long MEMBER_USER_ID = 20L;
    private static final Long OTHER_MEMBER_USER_ID = 30L;
    private static final String SCOPE_TEAM = "TEAM";

    private MemberWorkConstraintEntity buildIndividual() {
        return MemberWorkConstraintEntity.builder()
                .teamId(TEAM_ID)
                .userId(MEMBER_USER_ID)
                .maxMonthlyHours(new BigDecimal("80.00"))
                .note("個別制約")
                .build();
    }

    private MemberWorkConstraintEntity buildTeamDefault() {
        return MemberWorkConstraintEntity.builder()
                .teamId(TEAM_ID)
                .userId(null)
                .maxMonthlyHours(new BigDecimal("160.00"))
                .maxMonthlyDays(23)
                .note("チームデフォルト")
                .build();
    }

    private MemberWorkConstraintRequest buildValidRequest() {
        return new MemberWorkConstraintRequest(
                new BigDecimal("100.00"),
                22,
                5,
                8,
                new BigDecimal("11.00"),
                "テストメモ");
    }

    private MemberWorkConstraintRequest buildAllNullRequest() {
        return new MemberWorkConstraintRequest(null, null, null, null, null, "メモのみ");
    }

    // ========================================
    // 共通セットアップ: MapStruct mock が NullPointer 返しでテストを落とすのを避ける
    // ========================================

    @BeforeEach
    void setupDefaultMapperResponses() {
        // 明示的に given(...).willReturn(...) するテストは @Nested 内で上書きする
    }

    // ========================================
    // upsertConstraint
    // ========================================

    @Nested
    @DisplayName("upsertConstraint")
    class UpsertConstraint {

        @Test
        @DisplayName("個別制約_新規作成_ADMIN_成功")
        void 個別制約_新規作成_ADMIN_成功() {
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, SCOPE_TEAM))
                    .willReturn(true);
            given(constraintRepository.findByUserIdAndTeamId(MEMBER_USER_ID, TEAM_ID))
                    .willReturn(Optional.empty());
            MemberWorkConstraintEntity saved = buildIndividual();
            given(constraintRepository.save(any(MemberWorkConstraintEntity.class)))
                    .willReturn(saved);
            MemberWorkConstraintResponse dto = new MemberWorkConstraintResponse(
                    null, TEAM_ID, MEMBER_USER_ID, null, null, null, null, null, null);
            given(shiftMapper.toWorkConstraintResponse(saved)).willReturn(dto);

            MemberWorkConstraintResponse result = service.upsertConstraint(
                    TEAM_ID, MEMBER_USER_ID, buildValidRequest(), ADMIN_USER_ID);

            assertThat(result).isNotNull();
            verify(constraintRepository).save(any(MemberWorkConstraintEntity.class));
        }

        @Test
        @DisplayName("個別制約_既存更新_ADMIN_成功")
        void 個別制約_既存更新_ADMIN_成功() {
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, SCOPE_TEAM))
                    .willReturn(true);
            MemberWorkConstraintEntity existing = buildIndividual();
            given(constraintRepository.findByUserIdAndTeamId(MEMBER_USER_ID, TEAM_ID))
                    .willReturn(Optional.of(existing));
            given(constraintRepository.save(existing)).willReturn(existing);
            given(shiftMapper.toWorkConstraintResponse(existing))
                    .willReturn(new MemberWorkConstraintResponse(
                            null, TEAM_ID, MEMBER_USER_ID, null, null, null, null, null, null));

            MemberWorkConstraintRequest req = buildValidRequest();
            service.upsertConstraint(TEAM_ID, MEMBER_USER_ID, req, ADMIN_USER_ID);

            // 既存エンティティが updateConstraints で更新された結果を検証
            assertThat(existing.getMaxMonthlyHours()).isEqualByComparingTo(req.getMaxMonthlyHours());
            assertThat(existing.getNote()).isEqualTo(req.getNote());
        }

        @Test
        @DisplayName("個別制約_MEMBER権限_403")
        void 個別制約_MEMBER権限_403() {
            given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, TEAM_ID, SCOPE_TEAM))
                    .willReturn(false);

            assertThatThrownBy(() -> service.upsertConstraint(
                    TEAM_ID, MEMBER_USER_ID, buildValidRequest(), MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.WORK_CONSTRAINT_FORBIDDEN));
            verifyNoInteractions(constraintRepository);
        }

        @Test
        @DisplayName("個別制約_全項目NULL_400")
        void 個別制約_全項目NULL_400() {
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, SCOPE_TEAM))
                    .willReturn(true);

            assertThatThrownBy(() -> service.upsertConstraint(
                    TEAM_ID, MEMBER_USER_ID, buildAllNullRequest(), ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.WORK_CONSTRAINT_ALL_NULL));
        }
    }

    // ========================================
    // upsertTeamDefault
    // ========================================

    @Nested
    @DisplayName("upsertTeamDefault")
    class UpsertTeamDefault {

        @Test
        @DisplayName("チームデフォルト_新規_ADMIN_成功")
        void チームデフォルト_新規_ADMIN_成功() {
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, SCOPE_TEAM))
                    .willReturn(true);
            given(constraintRepository.findTeamDefault(TEAM_ID))
                    .willReturn(Optional.empty());
            MemberWorkConstraintEntity saved = buildTeamDefault();
            given(constraintRepository.save(any(MemberWorkConstraintEntity.class)))
                    .willReturn(saved);
            given(shiftMapper.toWorkConstraintResponse(saved))
                    .willReturn(new MemberWorkConstraintResponse(
                            null, TEAM_ID, null, null, null, null, null, null, null));

            MemberWorkConstraintResponse result = service.upsertTeamDefault(
                    TEAM_ID, buildValidRequest(), ADMIN_USER_ID);

            assertThat(result.getUserId()).isNull();
        }

        @Test
        @DisplayName("チームデフォルト_MEMBER権限_403")
        void チームデフォルト_MEMBER権限_403() {
            given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, TEAM_ID, SCOPE_TEAM))
                    .willReturn(false);

            assertThatThrownBy(() -> service.upsertTeamDefault(
                    TEAM_ID, buildValidRequest(), MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getConstraint - 解決順序
    // ========================================

    @Nested
    @DisplayName("getConstraint 解決順序")
    class GetConstraintResolution {

        @Test
        @DisplayName("本人閲覧_個別レコード存在_個別を返却")
        void 本人閲覧_個別レコード存在_個別を返却() {
            // 本人なので isAdminOrAbove は呼ばれない
            MemberWorkConstraintEntity individual = buildIndividual();
            given(constraintRepository.findByUserIdAndTeamId(MEMBER_USER_ID, TEAM_ID))
                    .willReturn(Optional.of(individual));
            MemberWorkConstraintResponse dto = new MemberWorkConstraintResponse(
                    null, TEAM_ID, MEMBER_USER_ID,
                    new BigDecimal("80.00"), null, null, null, null, "個別制約");
            given(shiftMapper.toWorkConstraintResponse(individual)).willReturn(dto);

            MemberWorkConstraintResponse result = service.getConstraint(
                    TEAM_ID, MEMBER_USER_ID, MEMBER_USER_ID);

            assertThat(result.getUserId()).isEqualTo(MEMBER_USER_ID);
            assertThat(result.getMaxMonthlyHours()).isEqualByComparingTo("80.00");
        }

        @Test
        @DisplayName("本人閲覧_個別なしデフォルトあり_デフォルトを返却")
        void 本人閲覧_個別なしデフォルトあり_デフォルトを返却() {
            given(constraintRepository.findByUserIdAndTeamId(MEMBER_USER_ID, TEAM_ID))
                    .willReturn(Optional.empty());
            MemberWorkConstraintEntity teamDefault = buildTeamDefault();
            given(constraintRepository.findTeamDefault(TEAM_ID))
                    .willReturn(Optional.of(teamDefault));
            MemberWorkConstraintResponse dto = new MemberWorkConstraintResponse(
                    null, TEAM_ID, null,
                    new BigDecimal("160.00"), 23, null, null, null, "チームデフォルト");
            given(shiftMapper.toWorkConstraintResponse(teamDefault)).willReturn(dto);

            MemberWorkConstraintResponse result = service.getConstraint(
                    TEAM_ID, MEMBER_USER_ID, MEMBER_USER_ID);

            assertThat(result.getUserId()).isNull(); // デフォルトなので userId = null
            assertThat(result.getMaxMonthlyHours()).isEqualByComparingTo("160.00");
        }

        @Test
        @DisplayName("個別なしデフォルトなし_404")
        void 個別なしデフォルトなし_404() {
            given(constraintRepository.findByUserIdAndTeamId(MEMBER_USER_ID, TEAM_ID))
                    .willReturn(Optional.empty());
            given(constraintRepository.findTeamDefault(TEAM_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getConstraint(
                    TEAM_ID, MEMBER_USER_ID, MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.WORK_CONSTRAINT_NOT_FOUND));
        }

        @Test
        @DisplayName("他人の制約閲覧_MEMBER権限_403")
        void 他人の制約閲覧_MEMBER権限_403() {
            // currentUser = MEMBER_USER_ID、閲覧対象 = OTHER_MEMBER_USER_ID、権限なし
            given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, TEAM_ID, SCOPE_TEAM))
                    .willReturn(false);

            assertThatThrownBy(() -> service.getConstraint(
                    TEAM_ID, OTHER_MEMBER_USER_ID, MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.WORK_CONSTRAINT_FORBIDDEN));
        }
    }

    // ========================================
    // listConstraintsByTeam
    // ========================================

    @Nested
    @DisplayName("listConstraintsByTeam")
    class ListConstraintsByTeam {

        @Test
        @DisplayName("ADMIN_一覧取得_成功")
        void ADMIN_一覧取得_成功() {
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, SCOPE_TEAM))
                    .willReturn(true);
            List<MemberWorkConstraintEntity> entities = List.of(buildIndividual(), buildTeamDefault());
            given(constraintRepository.findByTeamId(TEAM_ID)).willReturn(entities);
            given(shiftMapper.toWorkConstraintResponseList(entities))
                    .willReturn(List.of(
                            new MemberWorkConstraintResponse(null, TEAM_ID, MEMBER_USER_ID,
                                    null, null, null, null, null, null),
                            new MemberWorkConstraintResponse(null, TEAM_ID, null,
                                    null, null, null, null, null, null)));

            List<MemberWorkConstraintResponse> result = service
                    .listConstraintsByTeam(TEAM_ID, ADMIN_USER_ID);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("MEMBER権限_一覧取得_403")
        void MEMBER権限_一覧取得_403() {
            given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, TEAM_ID, SCOPE_TEAM))
                    .willReturn(false);

            assertThatThrownBy(() -> service.listConstraintsByTeam(TEAM_ID, MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.WORK_CONSTRAINT_FORBIDDEN));
        }
    }

    // ========================================
    // deleteConstraint
    // ========================================

    @Nested
    @DisplayName("deleteConstraint")
    class DeleteConstraint {

        @Test
        @DisplayName("ADMIN_削除_成功")
        void ADMIN_削除_成功() {
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, SCOPE_TEAM))
                    .willReturn(true);
            MemberWorkConstraintEntity entity = buildIndividual();
            given(constraintRepository.findByUserIdAndTeamId(MEMBER_USER_ID, TEAM_ID))
                    .willReturn(Optional.of(entity));

            service.deleteConstraint(TEAM_ID, MEMBER_USER_ID, ADMIN_USER_ID);

            verify(constraintRepository).delete(entity);
        }

        @Test
        @DisplayName("ADMIN_削除対象なし_404")
        void ADMIN_削除対象なし_404() {
            given(accessControlService.isAdminOrAbove(ADMIN_USER_ID, TEAM_ID, SCOPE_TEAM))
                    .willReturn(true);
            given(constraintRepository.findByUserIdAndTeamId(MEMBER_USER_ID, TEAM_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteConstraint(
                    TEAM_ID, MEMBER_USER_ID, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.WORK_CONSTRAINT_NOT_FOUND));
        }

        @Test
        @DisplayName("MEMBER権限_削除_403")
        void MEMBER権限_削除_403() {
            given(accessControlService.isAdminOrAbove(MEMBER_USER_ID, TEAM_ID, SCOPE_TEAM))
                    .willReturn(false);

            assertThatThrownBy(() -> service.deleteConstraint(
                    TEAM_ID, MEMBER_USER_ID, MEMBER_USER_ID))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(constraintRepository);
        }
    }
}
