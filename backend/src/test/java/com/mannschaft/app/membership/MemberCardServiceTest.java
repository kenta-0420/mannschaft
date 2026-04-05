package com.mannschaft.app.membership;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.membership.dto.CardStatusResponse;
import com.mannschaft.app.membership.dto.CheckinHistoryResponse;
import com.mannschaft.app.membership.dto.MemberCardDetailResponse;
import com.mannschaft.app.membership.dto.MemberCardListResponse;
import com.mannschaft.app.membership.dto.MemberCardResponse;
import com.mannschaft.app.membership.dto.QrTokenResponse;
import com.mannschaft.app.membership.dto.RegenerateResponse;
import com.mannschaft.app.membership.dto.SelfCheckinRequest;
import com.mannschaft.app.membership.dto.SelfCheckinResponse;
import com.mannschaft.app.membership.dto.VerifyRequest;
import com.mannschaft.app.membership.dto.VerifyResponse;
import com.mannschaft.app.membership.entity.CheckinLocationEntity;
import com.mannschaft.app.membership.entity.MemberCardCheckinEntity;
import com.mannschaft.app.membership.entity.MemberCardEntity;
import com.mannschaft.app.membership.repository.CheckinLocationRepository;
import com.mannschaft.app.membership.repository.MemberCardCheckinRepository;
import com.mannschaft.app.membership.repository.MemberCardRepository;
import com.mannschaft.app.membership.service.MemberCardService;
import com.mannschaft.app.membership.service.QrTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

/**
 * {@link MemberCardService} の単体テスト。
 * 会員証CRUD・QR認証・チェックイン管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberCardService 単体テスト")
class MemberCardServiceTest {

    @Mock
    private MemberCardRepository memberCardRepository;

    @Mock
    private MemberCardCheckinRepository checkinRepository;

    @Mock
    private CheckinLocationRepository locationRepository;

    @Mock
    private QrTokenService qrTokenService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private MemberCardService memberCardService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_USER_ID = 2L;
    private static final Long CARD_ID = 10L;
    private static final Long SCOPE_ID = 100L;
    private static final String CARD_CODE = "card-uuid-001";
    private static final String CARD_NUMBER = "TEAM-0001";
    private static final String DISPLAY_NAME = "山田太郎";
    private static final String QR_SECRET = "qr-secret-abc";
    private static final String QR_TOKEN = CARD_CODE + ".1234567890.1234568190.signature";

    private MemberCardEntity createActiveCard() {
        return MemberCardEntity.builder()
                .userId(USER_ID)
                .scopeType(ScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .cardCode(CARD_CODE)
                .cardNumber(CARD_NUMBER)
                .displayName(DISPLAY_NAME)
                .status(CardStatus.ACTIVE)
                .issuedAt(LocalDateTime.now().minusDays(30))
                .checkinCount(5)
                .totalSpend(BigDecimal.ZERO)
                .qrSecret(QR_SECRET)
                .build();
    }

    private MemberCardEntity createSuspendedCard() {
        return MemberCardEntity.builder()
                .userId(USER_ID)
                .scopeType(ScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .cardCode(CARD_CODE)
                .cardNumber(CARD_NUMBER)
                .displayName(DISPLAY_NAME)
                .status(CardStatus.SUSPENDED)
                .issuedAt(LocalDateTime.now().minusDays(30))
                .suspendedAt(LocalDateTime.now().minusDays(1))
                .checkinCount(5)
                .totalSpend(BigDecimal.ZERO)
                .qrSecret(QR_SECRET)
                .build();
    }

    private MemberCardEntity createRevokedCard() {
        return MemberCardEntity.builder()
                .userId(USER_ID)
                .scopeType(ScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .cardCode(CARD_CODE)
                .cardNumber(CARD_NUMBER)
                .displayName(DISPLAY_NAME)
                .status(CardStatus.REVOKED)
                .issuedAt(LocalDateTime.now().minusDays(30))
                .revokedAt(LocalDateTime.now().minusDays(1))
                .checkinCount(5)
                .totalSpend(BigDecimal.ZERO)
                .qrSecret(QR_SECRET)
                .build();
    }

    // ========================================
    // getMyCards
    // ========================================

    @Nested
    @DisplayName("getMyCards")
    class GetMyCards {

        @Test
        @DisplayName("正常系: 自分の会員証一覧が返却される")
        void 取得_正常_会員証一覧返却() {
            // Given
            MemberCardEntity card = createActiveCard();
            given(memberCardRepository.findByUserIdAndDeletedAtIsNullOrderByIssuedAtAsc(USER_ID))
                    .willReturn(List.of(card));
            given(nameResolverService.resolveScopeName("TEAM", SCOPE_ID)).willReturn("テストチーム");

            // When
            ApiResponse<List<MemberCardResponse>> response = memberCardService.getMyCards(USER_ID);

            // Then
            assertThat(response.getData()).hasSize(1);
            MemberCardResponse cardResponse = response.getData().get(0);
            assertThat(cardResponse.getCardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(cardResponse.getDisplayName()).isEqualTo(DISPLAY_NAME);
            assertThat(cardResponse.getStatus()).isEqualTo("ACTIVE");
            assertThat(cardResponse.getScopeType()).isEqualTo("TEAM");
        }

        @Test
        @DisplayName("正常系: 会員証がない場合は空リスト")
        void 取得_会員証なし_空リスト() {
            // Given
            given(memberCardRepository.findByUserIdAndDeletedAtIsNullOrderByIssuedAtAsc(USER_ID))
                    .willReturn(List.of());

            // When
            ApiResponse<List<MemberCardResponse>> response = memberCardService.getMyCards(USER_ID);

            // Then
            assertThat(response.getData()).isEmpty();
        }
    }

    // ========================================
    // getCardDetail
    // ========================================

    @Nested
    @DisplayName("getCardDetail")
    class GetCardDetail {

        @Test
        @DisplayName("正常系: 会員証詳細が返却される")
        void 取得_正常_詳細返却() {
            // Given
            MemberCardEntity card = createActiveCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));
            doNothing().when(accessControlService)
                    .checkOwnerOrAdmin(USER_ID, USER_ID, SCOPE_ID, "TEAM");
            given(nameResolverService.resolveScopeName("TEAM", SCOPE_ID)).willReturn("テストチーム");

            // When
            ApiResponse<MemberCardDetailResponse> response =
                    memberCardService.getCardDetail(CARD_ID, USER_ID);

            // Then
            MemberCardDetailResponse detail = response.getData();
            assertThat(detail.getCardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(detail.getDisplayName()).isEqualTo(DISPLAY_NAME);
            assertThat(detail.getStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("異常系: 会員証不在でMEMBERSHIP_001例外")
        void 取得_会員証不在_MEMBERSHIP001例外() {
            // Given
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> memberCardService.getCardDetail(CARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_001"));
        }
    }

    // ========================================
    // getQrToken
    // ========================================

    @Nested
    @DisplayName("getQrToken")
    class GetQrToken {

        @Test
        @DisplayName("正常系: QRトークンが返却される")
        void 取得_正常_QRトークン返却() {
            // Given
            MemberCardEntity card = createActiveCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));
            given(qrTokenService.generateMemberCardQrToken(CARD_CODE, QR_SECRET)).willReturn(QR_TOKEN);
            given(qrTokenService.getExpirySeconds()).willReturn(300);
            given(nameResolverService.resolveScopeName("TEAM", SCOPE_ID)).willReturn("テストチーム");

            // When
            ApiResponse<QrTokenResponse> response = memberCardService.getQrToken(CARD_ID, USER_ID);

            // Then
            QrTokenResponse qrResponse = response.getData();
            assertThat(qrResponse.getQrToken()).isEqualTo(QR_TOKEN);
            assertThat(qrResponse.getExpiresIn()).isEqualTo(300);
            assertThat(qrResponse.getCardNumber()).isEqualTo(CARD_NUMBER);
            assertThat(qrResponse.getScopeName()).isEqualTo("テストチーム");
        }

        @Test
        @DisplayName("異常系: 他人の会員証でMEMBERSHIP_002例外")
        void 取得_他人の会員証_MEMBERSHIP002例外() {
            // Given
            MemberCardEntity card = createActiveCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));

            Long otherUserId = 999L;

            // When / Then
            assertThatThrownBy(() -> memberCardService.getQrToken(CARD_ID, otherUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_002"));
        }

        @Test
        @DisplayName("異常系: ACTIVE以外でMEMBERSHIP_003例外")
        void 取得_停止中_MEMBERSHIP003例外() {
            // Given
            MemberCardEntity card = createSuspendedCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));

            // When / Then
            assertThatThrownBy(() -> memberCardService.getQrToken(CARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_003"));
        }
    }

    // ========================================
    // regenerateQr
    // ========================================

    @Nested
    @DisplayName("regenerateQr")
    class RegenerateQr {

        @Test
        @DisplayName("正常系: QRコードが再生成される")
        void 再生成_正常_QR再生成() {
            // Given
            MemberCardEntity card = createActiveCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));
            given(accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "SUPPORTER"))
                    .willReturn(true);
            given(qrTokenService.generateSecret()).willReturn("new-secret-xyz");
            given(memberCardRepository.save(any(MemberCardEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            ApiResponse<RegenerateResponse> response = memberCardService.regenerateQr(CARD_ID, USER_ID);

            // Then
            assertThat(response.getData().getMessage()).contains("再生成");
            verify(memberCardRepository).save(any(MemberCardEntity.class));
        }

        @Test
        @DisplayName("異常系: 他人の会員証でMEMBERSHIP_002例外")
        void 再生成_他人の会員証_MEMBERSHIP002例外() {
            // Given
            MemberCardEntity card = createActiveCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));

            Long otherUserId = 999L;

            // When / Then
            assertThatThrownBy(() -> memberCardService.regenerateQr(CARD_ID, otherUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_002"));
        }

        @Test
        @DisplayName("異常系: GUEST権限でMEMBERSHIP_004例外")
        void 再生成_GUEST権限_MEMBERSHIP004例外() {
            // Given
            MemberCardEntity card = createActiveCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));
            given(accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "SUPPORTER"))
                    .willReturn(false);

            // When / Then
            assertThatThrownBy(() -> memberCardService.regenerateQr(CARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_004"));
        }

        @Test
        @DisplayName("異常系: ACTIVE以外でMEMBERSHIP_003例外")
        void 再生成_停止中_MEMBERSHIP003例外() {
            // Given
            MemberCardEntity card = createSuspendedCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));
            given(accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "SUPPORTER"))
                    .willReturn(true);

            // When / Then
            assertThatThrownBy(() -> memberCardService.regenerateQr(CARD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_003"));
        }
    }

    // ========================================
    // verify
    // ========================================

    @Nested
    @DisplayName("verify")
    class Verify {

        @Test
        @DisplayName("正常系: QRスキャン認証が成功する")
        void 認証_正常_認証成功() {
            // Given
            VerifyRequest request = new VerifyRequest(QR_TOKEN, "受付", "来店", null);
            QrTokenService.MemberCardTokenPayload payload =
                    new QrTokenService.MemberCardTokenPayload(CARD_CODE, 1234567890L, 1234568190L, "signature");

            MemberCardEntity card = createActiveCard();

            given(qrTokenService.parseMemberCardToken(QR_TOKEN)).willReturn(payload);
            given(qrTokenService.isTokenExpired(payload)).willReturn(false);
            given(memberCardRepository.findByCardCodeAndDeletedAtIsNull(CARD_CODE))
                    .willReturn(Optional.of(card));
            given(qrTokenService.verifyMemberCardSignature(payload, QR_SECRET)).willReturn(true);
            doNothing().when(accessControlService)
                    .checkAdminOrAbove(ADMIN_USER_ID, SCOPE_ID, "TEAM");
            given(checkinRepository.findTopByMemberCardIdOrderByCheckedInAtDesc(any()))
                    .willReturn(Optional.empty());
            given(checkinRepository.save(any(MemberCardCheckinEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(nameResolverService.resolveScopeName("TEAM", SCOPE_ID)).willReturn("テストチーム");

            // When
            ApiResponse<VerifyResponse> response = memberCardService.verify(request, ADMIN_USER_ID);

            // Then
            assertThat(response.getData().isVerified()).isTrue();
            verify(checkinRepository).save(any(MemberCardCheckinEntity.class));
            verify(memberCardRepository).incrementCheckinCount(any());
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("異常系: トークンパース失敗でMEMBERSHIP_021例外")
        void 認証_トークン不正_MEMBERSHIP021例外() {
            // Given
            VerifyRequest request = new VerifyRequest("invalid-token", null, null, null);
            given(qrTokenService.parseMemberCardToken("invalid-token")).willReturn(null);

            // When / Then
            assertThatThrownBy(() -> memberCardService.verify(request, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_021"));
        }

        @Test
        @DisplayName("異常系: トークン有効期限切れでEXPIRED応答")
        void 認証_期限切れ_EXPIREDレスポンス() {
            // Given
            VerifyRequest request = new VerifyRequest(QR_TOKEN, null, null, null);
            QrTokenService.MemberCardTokenPayload payload =
                    new QrTokenService.MemberCardTokenPayload(CARD_CODE, 1000000000L, 1000000300L, "sig");

            given(qrTokenService.parseMemberCardToken(QR_TOKEN)).willReturn(payload);
            given(qrTokenService.isTokenExpired(payload)).willReturn(true);

            // When
            ApiResponse<VerifyResponse> response = memberCardService.verify(request, ADMIN_USER_ID);

            // Then
            assertThat(response.getData().isVerified()).isFalse();
            assertThat(response.getData().getReason()).isEqualTo("EXPIRED");
        }

        @Test
        @DisplayName("異常系: カード不在でCARD_NOT_FOUND応答")
        void 認証_カード不在_CARD_NOT_FOUNDレスポンス() {
            // Given
            VerifyRequest request = new VerifyRequest(QR_TOKEN, null, null, null);
            QrTokenService.MemberCardTokenPayload payload =
                    new QrTokenService.MemberCardTokenPayload(CARD_CODE, 1234567890L, 1234568190L, "sig");

            given(qrTokenService.parseMemberCardToken(QR_TOKEN)).willReturn(payload);
            given(qrTokenService.isTokenExpired(payload)).willReturn(false);
            given(memberCardRepository.findByCardCodeAndDeletedAtIsNull(CARD_CODE))
                    .willReturn(Optional.empty());

            // When
            ApiResponse<VerifyResponse> response = memberCardService.verify(request, ADMIN_USER_ID);

            // Then
            assertThat(response.getData().isVerified()).isFalse();
            assertThat(response.getData().getReason()).isEqualTo("CARD_NOT_FOUND");
        }

        @Test
        @DisplayName("異常系: 署名不正でINVALID_SIGNATURE応答")
        void 認証_署名不正_INVALID_SIGNATUREレスポンス() {
            // Given
            VerifyRequest request = new VerifyRequest(QR_TOKEN, null, null, null);
            QrTokenService.MemberCardTokenPayload payload =
                    new QrTokenService.MemberCardTokenPayload(CARD_CODE, 1234567890L, 1234568190L, "bad-sig");
            MemberCardEntity card = createActiveCard();

            given(qrTokenService.parseMemberCardToken(QR_TOKEN)).willReturn(payload);
            given(qrTokenService.isTokenExpired(payload)).willReturn(false);
            given(memberCardRepository.findByCardCodeAndDeletedAtIsNull(CARD_CODE))
                    .willReturn(Optional.of(card));
            given(qrTokenService.verifyMemberCardSignature(payload, QR_SECRET)).willReturn(false);

            // When
            ApiResponse<VerifyResponse> response = memberCardService.verify(request, ADMIN_USER_ID);

            // Then
            assertThat(response.getData().isVerified()).isFalse();
            assertThat(response.getData().getReason()).isEqualTo("INVALID_SIGNATURE");
        }

        @Test
        @DisplayName("異常系: SUSPENDED会員証でSUSPENDED応答")
        void 認証_停止中_SUSPENDEDレスポンス() {
            // Given
            VerifyRequest request = new VerifyRequest(QR_TOKEN, null, null, null);
            QrTokenService.MemberCardTokenPayload payload =
                    new QrTokenService.MemberCardTokenPayload(CARD_CODE, 1234567890L, 1234568190L, "sig");
            MemberCardEntity card = createSuspendedCard();

            given(qrTokenService.parseMemberCardToken(QR_TOKEN)).willReturn(payload);
            given(qrTokenService.isTokenExpired(payload)).willReturn(false);
            given(memberCardRepository.findByCardCodeAndDeletedAtIsNull(CARD_CODE))
                    .willReturn(Optional.of(card));
            given(qrTokenService.verifyMemberCardSignature(payload, QR_SECRET)).willReturn(true);
            doNothing().when(accessControlService)
                    .checkAdminOrAbove(ADMIN_USER_ID, SCOPE_ID, "TEAM");

            // When
            ApiResponse<VerifyResponse> response = memberCardService.verify(request, ADMIN_USER_ID);

            // Then
            assertThat(response.getData().isVerified()).isFalse();
            assertThat(response.getData().getReason()).isEqualTo("SUSPENDED");
        }

        @Test
        @DisplayName("異常系: REVOKED会員証でREVOKED応答")
        void 認証_無効化済_REVOKEDレスポンス() {
            // Given
            VerifyRequest request = new VerifyRequest(QR_TOKEN, null, null, null);
            QrTokenService.MemberCardTokenPayload payload =
                    new QrTokenService.MemberCardTokenPayload(CARD_CODE, 1234567890L, 1234568190L, "sig");
            MemberCardEntity card = createRevokedCard();

            given(qrTokenService.parseMemberCardToken(QR_TOKEN)).willReturn(payload);
            given(qrTokenService.isTokenExpired(payload)).willReturn(false);
            given(memberCardRepository.findByCardCodeAndDeletedAtIsNull(CARD_CODE))
                    .willReturn(Optional.of(card));
            given(qrTokenService.verifyMemberCardSignature(payload, QR_SECRET)).willReturn(true);
            doNothing().when(accessControlService)
                    .checkAdminOrAbove(ADMIN_USER_ID, SCOPE_ID, "TEAM");

            // When
            ApiResponse<VerifyResponse> response = memberCardService.verify(request, ADMIN_USER_ID);

            // Then
            assertThat(response.getData().isVerified()).isFalse();
            assertThat(response.getData().getReason()).isEqualTo("REVOKED");
        }

        @Test
        @DisplayName("異常系: 二重スキャンでMEMBERSHIP_010例外")
        void 認証_二重スキャン_MEMBERSHIP010例外() {
            // Given
            VerifyRequest request = new VerifyRequest(QR_TOKEN, null, null, null);
            QrTokenService.MemberCardTokenPayload payload =
                    new QrTokenService.MemberCardTokenPayload(CARD_CODE, 1234567890L, 1234568190L, "sig");
            MemberCardEntity card = createActiveCard();

            MemberCardCheckinEntity recentCheckin = MemberCardCheckinEntity.builder()
                    .memberCardId(CARD_ID)
                    .checkinType(CheckinType.STAFF_SCAN)
                    .checkedInAt(LocalDateTime.now().minusMinutes(2)) // 5分以内
                    .build();

            given(qrTokenService.parseMemberCardToken(QR_TOKEN)).willReturn(payload);
            given(qrTokenService.isTokenExpired(payload)).willReturn(false);
            given(memberCardRepository.findByCardCodeAndDeletedAtIsNull(CARD_CODE))
                    .willReturn(Optional.of(card));
            given(qrTokenService.verifyMemberCardSignature(payload, QR_SECRET)).willReturn(true);
            doNothing().when(accessControlService)
                    .checkAdminOrAbove(ADMIN_USER_ID, SCOPE_ID, "TEAM");
            given(checkinRepository.findTopByMemberCardIdOrderByCheckedInAtDesc(any()))
                    .willReturn(Optional.of(recentCheckin));

            // When / Then
            assertThatThrownBy(() -> memberCardService.verify(request, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_010"));
        }
    }

    // ========================================
    // selfCheckin
    // ========================================

    @Nested
    @DisplayName("selfCheckin")
    class SelfCheckin {

        private static final String LOCATION_CODE = "loc-uuid-001";
        private static final String LOCATION_SECRET = "loc-secret-abc";
        private static final String LOCATION_QR_TOKEN = LOCATION_CODE + ".loc-signature";

        @Test
        @DisplayName("正常系: セルフチェックインが成功する")
        void チェックイン_正常_成功() {
            // Given
            SelfCheckinRequest request = new SelfCheckinRequest(LOCATION_QR_TOKEN);
            QrTokenService.LocationTokenPayload payload =
                    new QrTokenService.LocationTokenPayload(LOCATION_CODE, "loc-signature");
            CheckinLocationEntity location = CheckinLocationEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(SCOPE_ID)
                    .name("正面入口")
                    .locationCode(LOCATION_CODE)
                    .locationSecret(LOCATION_SECRET)
                    .isActive(true)
                    .autoCompleteReservation(true)
                    .build();
            MemberCardEntity card = createActiveCard();

            given(qrTokenService.parseLocationToken(LOCATION_QR_TOKEN)).willReturn(payload);
            given(locationRepository.findByLocationCodeAndDeletedAtIsNull(LOCATION_CODE))
                    .willReturn(Optional.of(location));
            given(qrTokenService.verifyLocationSignature(payload, LOCATION_SECRET)).willReturn(true);
            given(memberCardRepository.findByUserIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    USER_ID, ScopeType.TEAM, SCOPE_ID)).willReturn(Optional.of(card));
            given(checkinRepository.findTopByMemberCardIdOrderByCheckedInAtDesc(any()))
                    .willReturn(Optional.empty());
            given(checkinRepository.save(any(MemberCardCheckinEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(nameResolverService.resolveScopeName("TEAM", SCOPE_ID)).willReturn("テストチーム");

            // When
            ApiResponse<SelfCheckinResponse> response = memberCardService.selfCheckin(request, USER_ID);

            // Then
            assertThat(response.getData().isCheckedIn()).isTrue();
            assertThat(response.getData().getMessage()).contains("チェックイン");
            verify(checkinRepository).save(any(MemberCardCheckinEntity.class));
            verify(memberCardRepository).incrementCheckinCount(any());
        }

        @Test
        @DisplayName("異常系: トークンパース失敗でMEMBERSHIP_021例外")
        void チェックイン_トークン不正_MEMBERSHIP021例外() {
            // Given
            SelfCheckinRequest request = new SelfCheckinRequest("bad-token");
            given(qrTokenService.parseLocationToken("bad-token")).willReturn(null);

            // When / Then
            assertThatThrownBy(() -> memberCardService.selfCheckin(request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_021"));
        }

        @Test
        @DisplayName("異常系: 拠点不在でINVALID_LOCATION応答")
        void チェックイン_拠点不在_INVALID_LOCATIONレスポンス() {
            // Given
            SelfCheckinRequest request = new SelfCheckinRequest(LOCATION_QR_TOKEN);
            QrTokenService.LocationTokenPayload payload =
                    new QrTokenService.LocationTokenPayload(LOCATION_CODE, "sig");

            given(qrTokenService.parseLocationToken(LOCATION_QR_TOKEN)).willReturn(payload);
            given(locationRepository.findByLocationCodeAndDeletedAtIsNull(LOCATION_CODE))
                    .willReturn(Optional.empty());

            // When
            ApiResponse<SelfCheckinResponse> response = memberCardService.selfCheckin(request, USER_ID);

            // Then
            assertThat(response.getData().isCheckedIn()).isFalse();
            assertThat(response.getData().getReason()).isEqualTo("INVALID_LOCATION");
        }

        @Test
        @DisplayName("異常系: 拠点無効でLOCATION_INACTIVE応答")
        void チェックイン_拠点無効_LOCATION_INACTIVEレスポンス() {
            // Given
            SelfCheckinRequest request = new SelfCheckinRequest(LOCATION_QR_TOKEN);
            QrTokenService.LocationTokenPayload payload =
                    new QrTokenService.LocationTokenPayload(LOCATION_CODE, "sig");
            CheckinLocationEntity location = CheckinLocationEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(SCOPE_ID)
                    .name("正面入口")
                    .locationCode(LOCATION_CODE)
                    .locationSecret(LOCATION_SECRET)
                    .isActive(false)
                    .autoCompleteReservation(true)
                    .build();

            given(qrTokenService.parseLocationToken(LOCATION_QR_TOKEN)).willReturn(payload);
            given(locationRepository.findByLocationCodeAndDeletedAtIsNull(LOCATION_CODE))
                    .willReturn(Optional.of(location));
            given(qrTokenService.verifyLocationSignature(payload, LOCATION_SECRET)).willReturn(true);

            // When
            ApiResponse<SelfCheckinResponse> response = memberCardService.selfCheckin(request, USER_ID);

            // Then
            assertThat(response.getData().isCheckedIn()).isFalse();
            assertThat(response.getData().getReason()).isEqualTo("LOCATION_INACTIVE");
        }

        @Test
        @DisplayName("異常系: 非メンバーでNOT_MEMBER応答")
        void チェックイン_非メンバー_NOT_MEMBERレスポンス() {
            // Given
            SelfCheckinRequest request = new SelfCheckinRequest(LOCATION_QR_TOKEN);
            QrTokenService.LocationTokenPayload payload =
                    new QrTokenService.LocationTokenPayload(LOCATION_CODE, "sig");
            CheckinLocationEntity location = CheckinLocationEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(SCOPE_ID)
                    .name("正面入口")
                    .locationCode(LOCATION_CODE)
                    .locationSecret(LOCATION_SECRET)
                    .isActive(true)
                    .autoCompleteReservation(true)
                    .build();

            given(qrTokenService.parseLocationToken(LOCATION_QR_TOKEN)).willReturn(payload);
            given(locationRepository.findByLocationCodeAndDeletedAtIsNull(LOCATION_CODE))
                    .willReturn(Optional.of(location));
            given(qrTokenService.verifyLocationSignature(payload, LOCATION_SECRET)).willReturn(true);
            given(memberCardRepository.findByUserIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    USER_ID, ScopeType.TEAM, SCOPE_ID)).willReturn(Optional.empty());

            // When
            ApiResponse<SelfCheckinResponse> response = memberCardService.selfCheckin(request, USER_ID);

            // Then
            assertThat(response.getData().isCheckedIn()).isFalse();
            assertThat(response.getData().getReason()).isEqualTo("NOT_MEMBER");
        }

        @Test
        @DisplayName("異常系: 会員証停止中でCARD_SUSPENDED応答")
        void チェックイン_停止中_CARD_SUSPENDEDレスポンス() {
            // Given
            SelfCheckinRequest request = new SelfCheckinRequest(LOCATION_QR_TOKEN);
            QrTokenService.LocationTokenPayload payload =
                    new QrTokenService.LocationTokenPayload(LOCATION_CODE, "sig");
            CheckinLocationEntity location = CheckinLocationEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(SCOPE_ID)
                    .name("正面入口")
                    .locationCode(LOCATION_CODE)
                    .locationSecret(LOCATION_SECRET)
                    .isActive(true)
                    .autoCompleteReservation(true)
                    .build();
            MemberCardEntity card = createSuspendedCard();

            given(qrTokenService.parseLocationToken(LOCATION_QR_TOKEN)).willReturn(payload);
            given(locationRepository.findByLocationCodeAndDeletedAtIsNull(LOCATION_CODE))
                    .willReturn(Optional.of(location));
            given(qrTokenService.verifyLocationSignature(payload, LOCATION_SECRET)).willReturn(true);
            given(memberCardRepository.findByUserIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    USER_ID, ScopeType.TEAM, SCOPE_ID)).willReturn(Optional.of(card));

            // When
            ApiResponse<SelfCheckinResponse> response = memberCardService.selfCheckin(request, USER_ID);

            // Then
            assertThat(response.getData().isCheckedIn()).isFalse();
            assertThat(response.getData().getReason()).isEqualTo("CARD_SUSPENDED");
        }
    }

    // ========================================
    // getScopeMemberCards
    // ========================================

    @Nested
    @DisplayName("getScopeMemberCards")
    class GetScopeMemberCards {

        @Test
        @DisplayName("正常系: スコープの会員証一覧が返却される（検索なし）")
        void 取得_正常_一覧返却() {
            // Given
            MemberCardEntity card = createActiveCard();
            given(memberCardRepository.findByScopeAndStatus(ScopeType.TEAM, SCOPE_ID, CardStatus.ACTIVE))
                    .willReturn(List.of(card));
            given(memberCardRepository.countByScopeGroupByStatus(ScopeType.TEAM, SCOPE_ID))
                    .willReturn(List.<Object[]>of(new Object[]{CardStatus.ACTIVE, 1L}));

            // When
            Map<String, Object> result = memberCardService.getScopeMemberCards(
                    ScopeType.TEAM, SCOPE_ID, CardStatus.ACTIVE, null);

            // Then
            @SuppressWarnings("unchecked")
            List<MemberCardListResponse> data = (List<MemberCardListResponse>) result.get("data");
            assertThat(data).hasSize(1);
            assertThat(data.get(0).getCardNumber()).isEqualTo(CARD_NUMBER);
        }

        @Test
        @DisplayName("正常系: 検索クエリ付きで一覧返却")
        void 取得_検索あり_フィルタ済み一覧返却() {
            // Given
            MemberCardEntity card = createActiveCard();
            given(memberCardRepository.findByScopeAndStatusWithSearch(
                    ScopeType.TEAM, SCOPE_ID, CardStatus.ACTIVE, "山田"))
                    .willReturn(List.of(card));
            given(memberCardRepository.countByScopeGroupByStatus(ScopeType.TEAM, SCOPE_ID))
                    .willReturn(List.of());

            // When
            Map<String, Object> result = memberCardService.getScopeMemberCards(
                    ScopeType.TEAM, SCOPE_ID, CardStatus.ACTIVE, "山田");

            // Then
            @SuppressWarnings("unchecked")
            List<MemberCardListResponse> data = (List<MemberCardListResponse>) result.get("data");
            assertThat(data).hasSize(1);
        }
    }

    // ========================================
    // suspend
    // ========================================

    @Nested
    @DisplayName("suspend")
    class Suspend {

        @Test
        @DisplayName("正常系: 会員証が一時停止される")
        void 停止_正常_一時停止() {
            // Given
            MemberCardEntity card = createActiveCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));
            given(memberCardRepository.save(any(MemberCardEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            ApiResponse<CardStatusResponse> response = memberCardService.suspend(CARD_ID);

            // Then
            assertThat(response.getData().getStatus()).isEqualTo("SUSPENDED");
            verify(memberCardRepository).save(any(MemberCardEntity.class));
        }

        @Test
        @DisplayName("異常系: 既にSUSPENDEDでMEMBERSHIP_016例外")
        void 停止_既に停止済_MEMBERSHIP016例外() {
            // Given
            MemberCardEntity card = createSuspendedCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));

            // When / Then
            assertThatThrownBy(() -> memberCardService.suspend(CARD_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_016"));
        }

        @Test
        @DisplayName("異常系: REVOKEDでMEMBERSHIP_009例外")
        void 停止_無効化済_MEMBERSHIP009例外() {
            // Given
            MemberCardEntity card = createRevokedCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));

            // When / Then
            assertThatThrownBy(() -> memberCardService.suspend(CARD_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_009"));
        }
    }

    // ========================================
    // reactivate
    // ========================================

    @Nested
    @DisplayName("reactivate")
    class Reactivate {

        @Test
        @DisplayName("正常系: 一時停止が解除される")
        void 解除_正常_再有効化() {
            // Given
            MemberCardEntity card = createSuspendedCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));
            given(memberCardRepository.save(any(MemberCardEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            ApiResponse<CardStatusResponse> response = memberCardService.reactivate(CARD_ID);

            // Then
            assertThat(response.getData().getStatus()).isEqualTo("ACTIVE");
            verify(memberCardRepository).save(any(MemberCardEntity.class));
        }

        @Test
        @DisplayName("異常系: REVOKEDでMEMBERSHIP_017例外")
        void 解除_無効化済_MEMBERSHIP017例外() {
            // Given
            MemberCardEntity card = createRevokedCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));

            // When / Then
            assertThatThrownBy(() -> memberCardService.reactivate(CARD_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_017"));
        }

        @Test
        @DisplayName("異常系: ACTIVEでMEMBERSHIP_018例外")
        void 解除_有効中_MEMBERSHIP018例外() {
            // Given
            MemberCardEntity card = createActiveCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));

            // When / Then
            assertThatThrownBy(() -> memberCardService.reactivate(CARD_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_018"));
        }
    }

    // ========================================
    // getCheckinHistory
    // ========================================

    @Nested
    @DisplayName("getCheckinHistory")
    class GetCheckinHistory {

        @Test
        @DisplayName("正常系: チェックイン履歴が返却される")
        void 取得_正常_履歴返却() {
            // Given
            MemberCardEntity card = createActiveCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));
            doNothing().when(accessControlService)
                    .checkOwnerOrAdmin(USER_ID, USER_ID, SCOPE_ID, "TEAM");

            LocalDateTime from = LocalDateTime.now().minusDays(7);
            LocalDateTime to = LocalDateTime.now();

            MemberCardCheckinEntity checkin = MemberCardCheckinEntity.builder()
                    .memberCardId(CARD_ID)
                    .checkinType(CheckinType.STAFF_SCAN)
                    .checkedInBy(ADMIN_USER_ID)
                    .checkedInAt(LocalDateTime.now().minusHours(1))
                    .location("受付")
                    .build();

            given(checkinRepository.findByMemberCardIdAndCheckedInAtBetweenOrderByCheckedInAtDesc(
                    CARD_ID, from, to)).willReturn(List.of(checkin));
            given(nameResolverService.resolveUserDisplayNames(List.of(ADMIN_USER_ID)))
                    .willReturn(Map.of(ADMIN_USER_ID, "管理者"));

            // When
            ApiResponse<List<CheckinHistoryResponse>> response =
                    memberCardService.getCheckinHistory(CARD_ID, USER_ID, from, to);

            // Then
            assertThat(response.getData()).hasSize(1);
            CheckinHistoryResponse history = response.getData().get(0);
            assertThat(history.getCheckinType()).isEqualTo("STAFF_SCAN");
            assertThat(history.getLocation()).isEqualTo("受付");
            assertThat(history.getCheckedInByName()).isEqualTo("管理者");
        }

        @Test
        @DisplayName("正常系: from/toがnullの場合はデフォルト期間が適用される")
        void 取得_期間null_デフォルト期間適用() {
            // Given
            MemberCardEntity card = createActiveCard();
            given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card));
            doNothing().when(accessControlService)
                    .checkOwnerOrAdmin(USER_ID, USER_ID, SCOPE_ID, "TEAM");
            given(checkinRepository.findByMemberCardIdAndCheckedInAtBetweenOrderByCheckedInAtDesc(
                    eq(CARD_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(List.of()))
                    .willReturn(Map.of());

            // When
            ApiResponse<List<CheckinHistoryResponse>> response =
                    memberCardService.getCheckinHistory(CARD_ID, USER_ID, null, null);

            // Then
            assertThat(response.getData()).isEmpty();
        }
    }

    // ========================================
    // getScopeCheckins
    // ========================================

    @Nested
    @DisplayName("getScopeCheckins")
    class GetScopeCheckins {

        @Test
        @DisplayName("正常系: スコープ全体のチェックイン履歴が返却される")
        void 取得_正常_スコープ履歴返却() {
            // Given
            LocalDateTime from = LocalDateTime.now().minusDays(7);
            LocalDateTime to = LocalDateTime.now();

            MemberCardCheckinEntity checkin = MemberCardCheckinEntity.builder()
                    .memberCardId(CARD_ID)
                    .checkinType(CheckinType.SELF)
                    .checkedInAt(LocalDateTime.now().minusHours(2))
                    .location("正面入口")
                    .build();

            MemberCardEntity card = createActiveCard();

            given(checkinRepository.findByScopeAndPeriod(ScopeType.TEAM, SCOPE_ID, from, to))
                    .willReturn(List.of(checkin));
            given(nameResolverService.resolveUserDisplayNames(List.of()))
                    .willReturn(Map.of());
            given(memberCardRepository.findById(CARD_ID)).willReturn(Optional.of(card));

            // When
            ApiResponse<List<CheckinHistoryResponse>> response =
                    memberCardService.getScopeCheckins(ScopeType.TEAM, SCOPE_ID, from, to);

            // Then
            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getCheckinType()).isEqualTo("SELF");
            assertThat(response.getData().get(0).getCardNumber()).isEqualTo(CARD_NUMBER);
        }
    }
}
