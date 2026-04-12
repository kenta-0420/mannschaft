package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.recruitment.DisputeResolution;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

/**
 * {@link RecruitmentNoShowService} の単体テスト。
 * §5.8 NO_SHOW フローの主要パスを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentNoShowService 単体テスト")
class RecruitmentNoShowServiceTest {

    @Mock
    private RecruitmentParticipantRepository participantRepository;

    @Mock
    private RecruitmentNoShowRecordRepository noShowRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private RecruitmentNoShowService service;

    private static final Long ADMIN_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final Long PARTICIPANT_ID = 100L;
    private static final Long RECORD_ID = 200L;
    private static final RecruitmentScopeType SCOPE_TYPE = RecruitmentScopeType.TEAM;

    // ========================================
    // resolveDispute - §5.8 異議申立解決
    // ========================================

    @Nested
    @DisplayName("resolveDispute - §5.8 異議申立解決")
    class ResolveDispute {

        @Test
        @DisplayName("認可チェック失敗 → BusinessException が伝播")
        void resolveDispute_unauthorizedAdmin_throws() {
            // checkAdminOrAbove は権限なし時に COMMON_002 を投げる
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(ADMIN_ID, SCOPE_ID, SCOPE_TYPE.name());

            assertThatThrownBy(() -> service.resolveDispute(
                    RECORD_ID, ADMIN_ID, SCOPE_TYPE, SCOPE_ID, DisputeResolution.UPHELD))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("NO_SHOW 記録が存在しない → NO_SHOW_RECORD_NOT_FOUND")
        void resolveDispute_recordNotFound_throws() {
            given(noShowRepository.findById(RECORD_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveDispute(
                    RECORD_ID, ADMIN_ID, SCOPE_TYPE, SCOPE_ID, DisputeResolution.UPHELD))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND);
        }

        @Test
        @DisplayName("異議申立中でない記録 → INVALID_STATE_TRANSITION")
        void resolveDispute_notDisputedRecord_throws() throws Exception {
            RecruitmentNoShowRecordEntity record = buildRecord(false);
            given(noShowRepository.findById(RECORD_ID)).willReturn(Optional.of(record));

            assertThatThrownBy(() -> service.resolveDispute(
                    RECORD_ID, ADMIN_ID, SCOPE_TYPE, SCOPE_ID, DisputeResolution.UPHELD))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    // ========================================
    // markNoShow - §5.8 NO_SHOW マーク
    // ========================================

    @Nested
    @DisplayName("markNoShow - §5.8 NO_SHOW マーク")
    class MarkNoShow {

        @Test
        @DisplayName("対象参加者が存在しない → LISTING_NOT_FOUND")
        void markNoShow_participantNotFound_throws() {
            given(participantRepository.findById(PARTICIPANT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.markNoShow(PARTICIPANT_ID, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private RecruitmentNoShowRecordEntity buildRecord(boolean disputed) throws Exception {
        RecruitmentNoShowRecordEntity record = RecruitmentNoShowRecordEntity.builder()
                .participantId(PARTICIPANT_ID)
                .listingId(1L)
                .userId(999L)
                .build();
        // isDisputed フィールドをリフレクションで設定
        Field f = RecruitmentNoShowRecordEntity.class.getDeclaredField("disputed");
        f.setAccessible(true);
        f.set(record, disputed);
        return record;
    }
}
