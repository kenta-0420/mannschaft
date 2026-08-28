package com.mannschaft.app.safetycheck;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.safetycheck.dto.BulkRespondRequest;
import com.mannschaft.app.safetycheck.dto.RespondRequest;
import com.mannschaft.app.safetycheck.dto.SafetyResponseResponse;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseFollowupEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseFollowupRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseRepository;
import com.mannschaft.app.safetycheck.service.SafetyResponseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SafetyResponseService} の単体テスト。
 * 安否確認回答の登録・一括回答を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SafetyResponseService 単体テスト")
class SafetyResponseServiceTest {

    @Mock
    private SafetyCheckRepository safetyCheckRepository;

    @Mock
    private SafetyResponseRepository responseRepository;

    @Mock
    private SafetyResponseFollowupRepository followupRepository;

    @Mock
    private SafetyCheckMapper mapper;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private SafetyResponseService safetyResponseService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SAFETY_CHECK_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final Long RESPONSE_ID = 200L;
    private static final Long OPERATOR_ID = 5L;

    private SafetyCheckEntity createActiveCheck() {
        return SafetyCheckEntity.builder()
                .scopeType(SafetyCheckScopeType.TEAM)
                .scopeId(1L)
                .title("地震発生")
                .status(SafetyCheckStatus.ACTIVE)
                .totalTargetCount(10)
                .createdBy(1L)
                .build();
    }

    private SafetyCheckEntity createClosedCheck() {
        SafetyCheckEntity entity = createActiveCheck();
        entity.close(1L);
        return entity;
    }

    private SafetyResponseEntity createResponseEntity() {
        SafetyResponseEntity entity = SafetyResponseEntity.builder()
                .safetyCheckId(SAFETY_CHECK_ID)
                .userId(USER_ID)
                .status(SafetyResponseStatus.SAFE)
                .message("無事です")
                .respondedAt(LocalDateTime.now())
                .build();
        callOnCreate(entity);
        return entity;
    }

    private SafetyResponseResponse createResponseDto() {
        return new SafetyResponseResponse(
                RESPONSE_ID, SAFETY_CHECK_ID, USER_ID, "SAFE",
                "無事です", null, false, null, null,
                LocalDateTime.now());
    }

    private void callOnCreate(Object entity) {
        try {
            Method method = entity.getClass().getSuperclass().getDeclaredMethod("onCreate");
            method.setAccessible(true);
            method.invoke(entity);
        } catch (Exception ignored) {
        }
    }

    /**
     * 認可根治戦役 Wave6 ロットC で追加したスコープメンバーシップ確認のスタブ。
     * {@code respond}/{@code bulkRespond} は {@code findCheckOrThrow} 直後にこの判定を通るため、
     * クローズ済み・重複回答等の既存分岐に到達させたい既存テストにも共通して必要になる。
     */
    private void stubMember(SafetyCheckEntity check, Long userId, boolean isMember) {
        given(accessControlService.isMember(userId, check.getScopeId(), check.getScopeType().name()))
                .willReturn(isMember);
    }

    // ========================================
    // respond
    // ========================================

    @Nested
    @DisplayName("respond")
    class Respond {

        @Test
        @DisplayName("安否回答_正常_SAFE_レスポンス返却")
        void 安否回答_正常_SAFE_レスポンス返却() {
            // Given
            RespondRequest req = new RespondRequest("SAFE", "無事です", null, null, null, null);
            SafetyCheckEntity check = createActiveCheck();
            SafetyResponseEntity savedEntity = createResponseEntity();
            SafetyResponseResponse responseDto = createResponseDto();

            stubMember(check, USER_ID, true);
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));
            given(responseRepository.findBySafetyCheckIdAndUserId(SAFETY_CHECK_ID, USER_ID))
                    .willReturn(Optional.empty());
            given(responseRepository.save(any(SafetyResponseEntity.class))).willReturn(savedEntity);
            given(mapper.toSafetyResponseResponse(savedEntity)).willReturn(responseDto);

            // When
            SafetyResponseResponse result = safetyResponseService.respond(SAFETY_CHECK_ID, req, USER_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("SAFE");
            verify(followupRepository, never()).save(any(SafetyResponseFollowupEntity.class));
        }

        @Test
        @DisplayName("安否回答_NEED_SUPPORT_フォローアップ自動作成")
        void 安否回答_NEED_SUPPORT_フォローアップ自動作成() {
            // Given
            RespondRequest req = new RespondRequest("NEED_SUPPORT", "助けが必要です", "CUSTOM",
                    true, new BigDecimal("35.6812"), new BigDecimal("139.7671"));
            SafetyCheckEntity check = createActiveCheck();
            SafetyResponseEntity savedEntity = SafetyResponseEntity.builder()
                    .safetyCheckId(SAFETY_CHECK_ID)
                    .userId(USER_ID)
                    .status(SafetyResponseStatus.NEED_SUPPORT)
                    .message("助けが必要です")
                    .respondedAt(LocalDateTime.now())
                    .build();
            callOnCreate(savedEntity);
            SafetyResponseResponse responseDto = createResponseDto();

            stubMember(check, USER_ID, true);
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));
            given(responseRepository.findBySafetyCheckIdAndUserId(SAFETY_CHECK_ID, USER_ID))
                    .willReturn(Optional.empty());
            given(responseRepository.save(any(SafetyResponseEntity.class))).willReturn(savedEntity);
            given(mapper.toSafetyResponseResponse(savedEntity)).willReturn(responseDto);

            // When
            safetyResponseService.respond(SAFETY_CHECK_ID, req, USER_ID);

            // Then
            verify(followupRepository).save(any(SafetyResponseFollowupEntity.class));
        }

        @Test
        @DisplayName("安否回答_既に回答済み_BusinessException")
        void 安否回答_既に回答済み_BusinessException() {
            // Given
            RespondRequest req = new RespondRequest("SAFE", "無事です", null, null, null, null);
            SafetyCheckEntity check = createActiveCheck();
            SafetyResponseEntity existing = createResponseEntity();

            stubMember(check, USER_ID, true);
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));
            given(responseRepository.findBySafetyCheckIdAndUserId(SAFETY_CHECK_ID, USER_ID))
                    .willReturn(Optional.of(existing));

            // When & Then
            assertThatThrownBy(() -> safetyResponseService.respond(SAFETY_CHECK_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.ALREADY_RESPONDED));
        }

        @Test
        @DisplayName("安否回答_クローズ済み_BusinessException")
        void 安否回答_クローズ済み_BusinessException() {
            // Given
            RespondRequest req = new RespondRequest("SAFE", "無事です", null, null, null, null);
            SafetyCheckEntity check = createClosedCheck();

            stubMember(check, USER_ID, true);
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));

            // When & Then
            assertThatThrownBy(() -> safetyResponseService.respond(SAFETY_CHECK_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.SAFETY_CHECK_ALREADY_CLOSED));
        }

        @Test
        @DisplayName("安否回答_存在しない安否確認_BusinessException")
        void 安否回答_存在しない安否確認_BusinessException() {
            // Given
            RespondRequest req = new RespondRequest("SAFE", null, null, null, null, null);
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> safetyResponseService.respond(SAFETY_CHECK_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        /**
         * 認可根治戦役 Wave6 ロットC の根治対象: 旧実装は対象安否確認のスコープ（チーム/組織）に
         * 呼び出し元が所属しているかを一切検証していなかった（兄弟メソッド
         * {@code SafetyCheckService#getSafetyCheck} は {@code isMember} を使うが respond は素通し）。
         * 非所属者が他組織・他チームの安否確認へ回答を書き込めない（結果集計の汚染を根治）ことを固定する。
         */
        @Test
        @DisplayName("安否回答_対象スコープの非所属者_BusinessException")
        void 安否回答_対象スコープの非所属者_BusinessException() {
            // Given
            RespondRequest req = new RespondRequest("SAFE", "無事です", null, null, null, null);
            SafetyCheckEntity check = createActiveCheck();

            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));
            stubMember(check, USER_ID, false);

            // When & Then
            assertThatThrownBy(() -> safetyResponseService.respond(SAFETY_CHECK_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.SAFETY_CHECK_NOT_FOUND));

            verify(responseRepository, never()).save(any());
        }

        @Test
        @DisplayName("安否回答_不正な回答ステータス_BusinessException")
        void 安否回答_不正な回答ステータス_BusinessException() {
            // Given
            RespondRequest req = new RespondRequest("INVALID_STATUS", null, null, null, null, null);
            SafetyCheckEntity check = createActiveCheck();

            stubMember(check, USER_ID, true);
            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));
            given(responseRepository.findBySafetyCheckIdAndUserId(SAFETY_CHECK_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> safetyResponseService.respond(SAFETY_CHECK_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.INVALID_RESPONSE_STATUS));
        }
    }

    // ========================================
    // bulkRespond
    // ========================================

    @Nested
    @DisplayName("bulkRespond")
    class BulkRespond {

        @Test
        @DisplayName("一括回答_正常_回答リスト返却")
        void 一括回答_正常_回答リスト返却() {
            // Given
            BulkRespondRequest.BulkRespondItem item = new BulkRespondRequest.BulkRespondItem(
                    20L, "SAFE", "無事です");
            BulkRespondRequest req = new BulkRespondRequest(List.of(item));
            SafetyCheckEntity check = createActiveCheck();
            SafetyResponseEntity savedEntity = createResponseEntity();
            SafetyResponseResponse responseDto = createResponseDto();

            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));
            given(responseRepository.findBySafetyCheckIdAndUserId(SAFETY_CHECK_ID, 20L))
                    .willReturn(Optional.empty());
            given(responseRepository.save(any(SafetyResponseEntity.class))).willReturn(savedEntity);
            given(mapper.toSafetyResponseResponse(savedEntity)).willReturn(responseDto);

            // When
            List<SafetyResponseResponse> result =
                    safetyResponseService.bulkRespond(SAFETY_CHECK_ID, req, OPERATOR_ID);

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("一括回答_既存回答スキップ_新規のみ登録")
        void 一括回答_既存回答スキップ_新規のみ登録() {
            // Given
            BulkRespondRequest.BulkRespondItem existingItem = new BulkRespondRequest.BulkRespondItem(
                    20L, "SAFE", "無事です");
            BulkRespondRequest.BulkRespondItem newItem = new BulkRespondRequest.BulkRespondItem(
                    30L, "SAFE", "無事です");
            BulkRespondRequest req = new BulkRespondRequest(List.of(existingItem, newItem));
            SafetyCheckEntity check = createActiveCheck();
            SafetyResponseEntity savedEntity = createResponseEntity();
            SafetyResponseResponse responseDto = createResponseDto();

            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));
            given(responseRepository.findBySafetyCheckIdAndUserId(SAFETY_CHECK_ID, 20L))
                    .willReturn(Optional.of(createResponseEntity())); // 既存
            given(responseRepository.findBySafetyCheckIdAndUserId(SAFETY_CHECK_ID, 30L))
                    .willReturn(Optional.empty()); // 新規
            given(responseRepository.save(any(SafetyResponseEntity.class))).willReturn(savedEntity);
            given(mapper.toSafetyResponseResponse(savedEntity)).willReturn(responseDto);

            // When
            List<SafetyResponseResponse> result =
                    safetyResponseService.bulkRespond(SAFETY_CHECK_ID, req, OPERATOR_ID);

            // Then
            assertThat(result).hasSize(1); // 新規の1件のみ
        }

        @Test
        @DisplayName("一括回答_上限超過_BusinessException")
        void 一括回答_上限超過_BusinessException() {
            // Given
            List<BulkRespondRequest.BulkRespondItem> items = new ArrayList<>();
            for (int i = 0; i < 101; i++) {
                items.add(new BulkRespondRequest.BulkRespondItem((long) i, "SAFE", null));
            }
            BulkRespondRequest req = new BulkRespondRequest(items);
            SafetyCheckEntity check = createActiveCheck();

            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));

            // When & Then
            assertThatThrownBy(() -> safetyResponseService.bulkRespond(SAFETY_CHECK_ID, req, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.BULK_RESPOND_LIMIT_EXCEEDED));
        }

        @Test
        @DisplayName("一括回答_クローズ済み_BusinessException")
        void 一括回答_クローズ済み_BusinessException() {
            // Given
            BulkRespondRequest.BulkRespondItem item = new BulkRespondRequest.BulkRespondItem(
                    20L, "SAFE", null);
            BulkRespondRequest req = new BulkRespondRequest(List.of(item));
            SafetyCheckEntity check = createClosedCheck();

            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));

            // When & Then
            assertThatThrownBy(() -> safetyResponseService.bulkRespond(SAFETY_CHECK_ID, req, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SafetyCheckErrorCode.SAFETY_CHECK_ALREADY_CLOSED));
        }

        @Test
        @DisplayName("一括回答_NEED_SUPPORTあり_フォローアップ自動作成")
        void 一括回答_NEED_SUPPORTあり_フォローアップ自動作成() {
            // Given
            BulkRespondRequest.BulkRespondItem item = new BulkRespondRequest.BulkRespondItem(
                    20L, "NEED_SUPPORT", "助けが必要");
            BulkRespondRequest req = new BulkRespondRequest(List.of(item));
            SafetyCheckEntity check = createActiveCheck();
            SafetyResponseEntity savedEntity = SafetyResponseEntity.builder()
                    .safetyCheckId(SAFETY_CHECK_ID)
                    .userId(20L)
                    .status(SafetyResponseStatus.NEED_SUPPORT)
                    .respondedAt(LocalDateTime.now())
                    .build();
            callOnCreate(savedEntity);
            SafetyResponseResponse responseDto = createResponseDto();

            given(safetyCheckRepository.findById(SAFETY_CHECK_ID)).willReturn(Optional.of(check));
            given(responseRepository.findBySafetyCheckIdAndUserId(SAFETY_CHECK_ID, 20L))
                    .willReturn(Optional.empty());
            given(responseRepository.save(any(SafetyResponseEntity.class))).willReturn(savedEntity);
            given(mapper.toSafetyResponseResponse(savedEntity)).willReturn(responseDto);

            // When
            safetyResponseService.bulkRespond(SAFETY_CHECK_ID, req, OPERATOR_ID);

            // Then
            verify(followupRepository).save(any(SafetyResponseFollowupEntity.class));
        }
    }
}
