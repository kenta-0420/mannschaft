package com.mannschaft.app.safetycheck;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.safetycheck.dto.CreateSafetyCheckRequest;
import com.mannschaft.app.safetycheck.dto.SafetyCheckResponse;
import com.mannschaft.app.safetycheck.dto.SafetyCheckResultsResponse;
import com.mannschaft.app.safetycheck.dto.SafetyResponseResponse;
import com.mannschaft.app.safetycheck.dto.UnrespondedUserResponse;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.safetycheck.entity.SafetyCheckTemplateEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.safetycheck.repository.SafetyCheckTemplateRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseRepository;
import com.mannschaft.app.safetycheck.service.SafetyCheckService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SafetyCheckService} の単体テスト。
 * 安否確認の発信・クローズ・結果集計・リマインドを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SafetyCheckService 単体テスト")
class SafetyCheckServiceTest {

    @Mock
    private SafetyCheckRepository safetyCheckRepository;

    @Mock
    private SafetyResponseRepository safetyResponseRepository;

    @Mock
    private SafetyCheckTemplateRepository templateRepository;

    @Mock
    private SafetyCheckMapper mapper;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private NotificationHelper notificationHelper;

    @InjectMocks
    private SafetyCheckService safetyCheckService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SAFETY_CHECK_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long TEMPLATE_ID = 50L;

    private SafetyCheckEntity createActiveCheck() {
        SafetyCheckEntity entity = SafetyCheckEntity.builder()
                .scopeType(SafetyCheckScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .title("地震発生")
                .message("安否を報告してください")
                .isDrill(false)
                .status(SafetyCheckStatus.ACTIVE)
                .reminderIntervalMinutes(30)
                .totalTargetCount(10)
                .createdBy(USER_ID)
                .build();
        return entity;
    }

    private SafetyCheckEntity createClosedCheck() {
        SafetyCheckEntity entity = createActiveCheck();
        entity.close(USER_ID);
        return entity;
    }

    private SafetyCheckResponse createCheckResponse() {
        return new SafetyCheckResponse(
                SAFETY_CHECK_ID, "TEAM", SCOPE_ID, "地震発生",
                "安否を報告してください", false, "ACTIVE", 30,
                10, USER_ID, null, null, LocalDateTime.now());
    }

    private SafetyCheckTemplateEntity createTemplateEntity() {
        return SafetyCheckTemplateEntity.builder()
                .scopeType(SafetyCheckScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .templateName("地震テンプレート")
                .title("地震発生テンプレ")
                .message("テンプレートメッセージ")
                .reminderIntervalMinutes(15)
                .build();
    }

    // ========================================
    // createSafetyCheck
    // ========================================

    @Nested
    @DisplayName("createSafetyCheck")
    class CreateSafetyCheck {

        @Test
        @DisplayName("安否確認発信_正常_レスポンス返却")
        void 安否確認発信_正常_レスポンス返却() {
            // Given
            CreateSafetyCheckRequest req = new CreateSafetyCheckRequest(
                    "地震発生", "安否を報告してください", "TEAM", SCOPE_ID,
                    false, 30, null);
            SafetyCheckEntity savedEntity = createActiveCheck();
            SafetyCheckResponse response = createCheckResponse();

            given(safetyCheckRepository.save(any(SafetyCheckEntity.class))).willReturn(savedEntity);
            given(userRoleRepository.countByTeamId(SCOPE_ID)).willReturn(10L);
            given(mapper.toSafetyCheckResponse(savedEntity)).willReturn(response);

            // When
            SafetyCheckResponse result = safetyCheckService.createSafetyCheck(req, USER_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTitle()).isEqualTo("地震発生");
        }

        @Test
        @DisplayName("安否確認発信_テンプレートからデフォルト値適用")
        void 安否確認発信_テンプレートからデフォルト値適用() {
            // Given
            CreateSafetyCheckRequest req = new CreateSafetyCheckRequest(
                    null, null, "TEAM", SCOPE_ID,
                    false, null, TEMPLATE_ID);
            SafetyCheckTemplateEntity template = createTemplateEntity();
            SafetyCheckEntity savedEntity = createActiveCheck();
            SafetyCheckResponse response = createCheckResponse();

            given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(template));
            given(safetyCheckRepository.save(any(SafetyCheckEntity.class))).willReturn(savedEntity);
            given(userRoleRepository.countByTeamId(SCOPE_ID)).willReturn(10L);
            given(mapper.toSafetyCheckResponse(savedEntity)).willReturn(response);

            // When
            SafetyCheckResponse result = safetyCheckService.createSafetyCheck(req, USER_ID);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("安否確認発信_テンプレート存在しない_BusinessException")
        void 安否確認発信_テンプレート存在しない_BusinessException() {
            // Given
            CreateSafetyCheckRequest req = new CreateSafetyCheckRequest(
                    "タイトル", "メッセージ", "TEAM", SCOPE_ID,
                    false, null, 999L);
            given(templateRepository.findById(999L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> safetyCheckService.createSafetyCheck(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.TEMPLATE_NOT_FOUND));
        }

        @Test
        @DisplayName("安否確認発信_不正なスコープ種別_BusinessException")
        void 安否確認発信_不正なスコープ種別_BusinessException() {
            // Given
            CreateSafetyCheckRequest req = new CreateSafetyCheckRequest(
                    "タイトル", "メッセージ", "INVALID_SCOPE", SCOPE_ID,
                    false, null, null);

            // When & Then
            assertThatThrownBy(() -> safetyCheckService.createSafetyCheck(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.INVALID_SCOPE_TYPE));
        }

        @Test
        @DisplayName("安否確認発信_ORGANIZATIONスコープ_正常")
        void 安否確認発信_ORGANIZATIONスコープ_正常() {
            // Given
            CreateSafetyCheckRequest req = new CreateSafetyCheckRequest(
                    "地震発生", "安否を報告してください", "ORGANIZATION", SCOPE_ID,
                    false, 30, null);
            SafetyCheckEntity savedEntity = SafetyCheckEntity.builder()
                    .scopeType(SafetyCheckScopeType.ORGANIZATION)
                    .scopeId(SCOPE_ID)
                    .title("地震発生")
                    .status(SafetyCheckStatus.ACTIVE)
                    .totalTargetCount(50)
                    .createdBy(USER_ID)
                    .build();
            SafetyCheckResponse response = createCheckResponse();

            given(safetyCheckRepository.save(any(SafetyCheckEntity.class))).willReturn(savedEntity);
            given(userRoleRepository.countByOrganizationId(SCOPE_ID)).willReturn(50L);
            given(mapper.toSafetyCheckResponse(savedEntity)).willReturn(response);

            // When
            SafetyCheckResponse result = safetyCheckService.createSafetyCheck(req, USER_ID);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // listSafetyChecks
    // ========================================

    @Nested
    @DisplayName("listSafetyChecks")
    class ListSafetyChecks {

        @Test
        @DisplayName("安否確認一覧取得_ステータス指定_フィルタ結果返却")
        void 安否確認一覧取得_ステータス指定_フィルタ結果返却() {
            // Given
            SafetyCheckEntity entity = createActiveCheck();
            SafetyCheckResponse response = createCheckResponse();
            Page<SafetyCheckEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
            given(safetyCheckRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    SafetyCheckScopeType.TEAM, SCOPE_ID, SafetyCheckStatus.ACTIVE, PageRequest.of(0, 10)))
                    .willReturn(page);
            given(mapper.toSafetyCheckResponse(entity)).willReturn(response);

            // When
            Page<SafetyCheckResponse> result = safetyCheckService.listSafetyChecks("TEAM", SCOPE_ID, "ACTIVE", 0, 10);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("安否確認一覧取得_ステータス未指定_全件返却")
        void 安否確認一覧取得_ステータス未指定_全件返却() {
            // Given
            SafetyCheckEntity entity = createActiveCheck();
            SafetyCheckResponse response = createCheckResponse();
            Page<SafetyCheckEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
            given(safetyCheckRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    SafetyCheckScopeType.TEAM, SCOPE_ID, PageRequest.of(0, 10)))
                    .willReturn(page);
            given(mapper.toSafetyCheckResponse(entity)).willReturn(response);

            // When
            Page<SafetyCheckResponse> result = safetyCheckService.listSafetyChecks("TEAM", SCOPE_ID, null, 0, 10);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // getSafetyCheck
    // ========================================

    @Nested
    @DisplayName("getSafetyCheck")
    class GetSafetyCheck {

        @Test
        @DisplayName("安否確認詳細取得_正常_レスポンス返却")
        void 安否確認詳細取得_正常_レスポンス返却() {
            // Given
            SafetyCheckEntity entity = createActiveCheck();
            SafetyCheckResponse response = createCheckResponse();
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(entity));
            given(mapper.toSafetyCheckResponse(entity)).willReturn(response);

            // When
            SafetyCheckResponse result = safetyCheckService.getSafetyCheck(SAFETY_CHECK_ID);

            // Then
            assertThat(result.getTitle()).isEqualTo("地震発生");
        }

        @Test
        @DisplayName("安否確認詳細取得_存在しない_BusinessException")
        void 安否確認詳細取得_存在しない_BusinessException() {
            // Given
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> safetyCheckService.getSafetyCheck(SAFETY_CHECK_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.SAFETY_CHECK_NOT_FOUND));
        }
    }

    // ========================================
    // closeSafetyCheck
    // ========================================

    @Nested
    @DisplayName("closeSafetyCheck")
    class CloseSafetyCheck {

        @Test
        @DisplayName("安否確認クローズ_正常_ステータスCLOSED")
        void 安否確認クローズ_正常_ステータスCLOSED() {
            // Given
            SafetyCheckEntity entity = createActiveCheck();
            SafetyCheckResponse response = createCheckResponse();
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(entity));
            given(safetyCheckRepository.save(entity)).willReturn(entity);
            given(mapper.toSafetyCheckResponse(entity)).willReturn(response);

            // When
            safetyCheckService.closeSafetyCheck(SAFETY_CHECK_ID, USER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(SafetyCheckStatus.CLOSED);
            assertThat(entity.getClosedBy()).isEqualTo(USER_ID);
            assertThat(entity.getClosedAt()).isNotNull();
        }

        @Test
        @DisplayName("安否確認クローズ_既にクローズ済み_BusinessException")
        void 安否確認クローズ_既にクローズ済み_BusinessException() {
            // Given
            SafetyCheckEntity entity = createClosedCheck();
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> safetyCheckService.closeSafetyCheck(SAFETY_CHECK_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.SAFETY_CHECK_ALREADY_CLOSED));
        }

        @Test
        @DisplayName("安否確認クローズ_存在しない_BusinessException")
        void 安否確認クローズ_存在しない_BusinessException() {
            // Given
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> safetyCheckService.closeSafetyCheck(SAFETY_CHECK_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getResults
    // ========================================

    @Nested
    @DisplayName("getResults")
    class GetResults {

        @Test
        @DisplayName("結果集計取得_正常_カウント正確")
        void 結果集計取得_正常_カウント正確() {
            // Given
            SafetyCheckEntity check = createActiveCheck();
            SafetyResponseEntity responseEntity = SafetyResponseEntity.builder()
                    .safetyCheckId(SAFETY_CHECK_ID)
                    .userId(1L)
                    .status(SafetyResponseStatus.SAFE)
                    .build();
            SafetyResponseResponse responseDto = new SafetyResponseResponse(
                    1L, SAFETY_CHECK_ID, 1L, "SAFE", null, null,
                    false, null, null, LocalDateTime.now());

            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));
            given(safetyResponseRepository.findBySafetyCheckIdOrderByRespondedAtAsc(SAFETY_CHECK_ID))
                    .willReturn(List.of(responseEntity));
            given(mapper.toSafetyResponseResponseList(List.of(responseEntity)))
                    .willReturn(List.of(responseDto));
            given(safetyResponseRepository.countBySafetyCheckIdAndStatus(SAFETY_CHECK_ID, SafetyResponseStatus.SAFE))
                    .willReturn(7L);
            given(safetyResponseRepository.countBySafetyCheckIdAndStatus(SAFETY_CHECK_ID, SafetyResponseStatus.NEED_SUPPORT))
                    .willReturn(2L);
            given(safetyResponseRepository.countBySafetyCheckIdAndStatus(SAFETY_CHECK_ID, SafetyResponseStatus.OTHER))
                    .willReturn(1L);

            // When
            SafetyCheckResultsResponse result = safetyCheckService.getResults(SAFETY_CHECK_ID);

            // Then
            assertThat(result.getSafetyCheckId()).isEqualTo(SAFETY_CHECK_ID);
            assertThat(result.getTotalTargetCount()).isEqualTo(10);
            assertThat(result.getRespondedCount()).isEqualTo(1L);
            assertThat(result.getSafeCount()).isEqualTo(7L);
            assertThat(result.getNeedSupportCount()).isEqualTo(2L);
            assertThat(result.getOtherCount()).isEqualTo(1L);
            assertThat(result.getUnrespondedCount()).isEqualTo(9L);
        }
    }

    // ========================================
    // getUnrespondedUsers
    // ========================================

    @Nested
    @DisplayName("getUnrespondedUsers")
    class GetUnrespondedUsers {

        @Test
        @DisplayName("未回答ユーザー一覧取得_正常_空リスト返却")
        void 未回答ユーザー一覧取得_正常_空リスト返却() {
            // Given
            SafetyCheckEntity entity = createActiveCheck();
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(entity));
            given(safetyResponseRepository.findRespondedUserIdsBySafetyCheckId(SAFETY_CHECK_ID))
                    .willReturn(List.of(1L, 2L));

            // When
            List<UnrespondedUserResponse> result = safetyCheckService.getUnrespondedUsers(SAFETY_CHECK_ID);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("未回答ユーザー一覧取得_存在しない_BusinessException")
        void 未回答ユーザー一覧取得_存在しない_BusinessException() {
            // Given
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> safetyCheckService.getUnrespondedUsers(SAFETY_CHECK_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getHistory
    // ========================================

    @Nested
    @DisplayName("getHistory")
    class GetHistory {

        @Test
        @DisplayName("履歴取得_正常_ページ返却")
        void 履歴取得_正常_ページ返却() {
            // Given
            SafetyCheckEntity entity = createClosedCheck();
            SafetyCheckResponse response = createCheckResponse();
            Page<SafetyCheckEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
            given(safetyCheckRepository.findClosedByScopeOrderByClosedAtDesc(
                    SafetyCheckScopeType.TEAM, SCOPE_ID, PageRequest.of(0, 10)))
                    .willReturn(page);
            given(mapper.toSafetyCheckResponse(entity)).willReturn(response);

            // When
            Page<SafetyCheckResponse> result = safetyCheckService.getHistory("TEAM", SCOPE_ID, 0, 10);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // sendReminder
    // ========================================

    @Nested
    @DisplayName("sendReminder")
    class SendReminder {

        @Test
        @DisplayName("リマインド送信_正常_lastReminderAt更新")
        void リマインド送信_正常_lastReminderAt更新() {
            // Given
            SafetyCheckEntity entity = createActiveCheck();
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(entity));
            given(safetyCheckRepository.save(entity)).willReturn(entity);

            // When
            safetyCheckService.sendReminder(SAFETY_CHECK_ID, USER_ID);

            // Then
            assertThat(entity.getLastReminderAt()).isNotNull();
            verify(safetyCheckRepository).save(entity);
        }

        @Test
        @DisplayName("リマインド送信_クローズ済み_BusinessException")
        void リマインド送信_クローズ済み_BusinessException() {
            // Given
            SafetyCheckEntity entity = createClosedCheck();
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> safetyCheckService.sendReminder(SAFETY_CHECK_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.SAFETY_CHECK_ALREADY_CLOSED));
        }

        @Test
        @DisplayName("リマインド送信_間隔短すぎ_BusinessException")
        void リマインド送信_間隔短すぎ_BusinessException() {
            // Given
            SafetyCheckEntity entity = createActiveCheck();
            entity.updateLastReminderAt(); // 直前にリマインド送信済み
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> safetyCheckService.sendReminder(SAFETY_CHECK_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.REMIND_TOO_FREQUENT));
        }
    }
}
