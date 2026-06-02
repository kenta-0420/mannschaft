package com.mannschaft.app.tournament.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.dto.ContactSpaceResponse;
import com.mannschaft.app.tournament.entity.TournamentContactSpaceEntity;
import com.mannschaft.app.tournament.repository.TournamentContactSpaceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TournamentContactSpaceService} の単体テスト（F08.7.1 §5 公開トグル）。
 *
 * <p>公開トグルが主催組織 ADMIN / SYSTEM_ADMIN 限定であること、スコープ不一致は 404、
 * 監査ログが記録されることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentContactSpaceService 単体テスト")
class TournamentContactSpaceServiceTest {

    @Mock
    private TournamentContactSpaceRepository contactSpaceRepository;
    @Mock
    private TournamentContactAccessService tournamentContactAccessService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TournamentContactSpaceService service;

    private static final Long TOURNAMENT_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final UUID SPACE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private TournamentContactSpaceEntity space(boolean isPublic) {
        return TournamentContactSpaceEntity.builder()
                .scopeType(ContactSpaceScopeType.TOURNAMENT)
                .scopeId(TOURNAMENT_ID)
                .spaceKind(ContactSpaceKind.BULLETIN)
                .refId(999L)
                .isPublic(isPublic)
                .build();
    }

    @Nested
    @DisplayName("updateVisibility")
    class UpdateVisibility {

        @Test
        @DisplayName("主催者が公開トグルON_保存され監査ログを記録する")
        void toggleOn_ok() {
            given(contactSpaceRepository.findById(SPACE_ID)).willReturn(Optional.of(space(false)));
            given(contactSpaceRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            ContactSpaceResponse result = service.updateVisibility(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, SPACE_ID, true, USER_ID);

            verify(tournamentContactAccessService)
                    .checkVisibilityManage(ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, USER_ID);
            verify(contactSpaceRepository).save(any(TournamentContactSpaceEntity.class));
            verify(auditLogService).record(anyString(), any(), any(), any(), any(), any(), any(), any(), anyString());
            assertThat(result.isPublic()).isTrue();
        }

        @Test
        @DisplayName("認可なし_403で保存されない")
        void forbidden_403() {
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_VISIBILITY_FORBIDDEN))
                    .given(tournamentContactAccessService)
                    .checkVisibilityManage(ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, USER_ID);

            assertThatThrownBy(() -> service.updateVisibility(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, SPACE_ID, true, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TournamentErrorCode.CONTACT_SPACE_VISIBILITY_FORBIDDEN));

            verify(contactSpaceRepository, never()).save(any());
        }

        @Test
        @DisplayName("スペースが別スコープに属する_404（IDOR）")
        void wrongScope_404() {
            TournamentContactSpaceEntity other = TournamentContactSpaceEntity.builder()
                    .scopeType(ContactSpaceScopeType.TOURNAMENT)
                    .scopeId(999L) // 別の大会
                    .spaceKind(ContactSpaceKind.BULLETIN)
                    .refId(1L)
                    .isPublic(false)
                    .build();
            given(contactSpaceRepository.findById(SPACE_ID)).willReturn(Optional.of(other));

            assertThatThrownBy(() -> service.updateVisibility(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, SPACE_ID, true, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));

            verify(contactSpaceRepository, never()).save(any());
        }

        @Test
        @DisplayName("スペースが存在しない_404")
        void missing_404() {
            given(contactSpaceRepository.findById(SPACE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateVisibility(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, SPACE_ID, true, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));
        }
    }
}
