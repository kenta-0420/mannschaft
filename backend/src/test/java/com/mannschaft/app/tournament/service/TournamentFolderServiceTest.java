package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.service.SharedFolderService;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TournamentFolderService} の認可配線テスト（F08.7.1 / 04 §3 / §5）。
 *
 * <p>検分指摘で「{@code checkFolderViewAccess} / {@code checkFolderPostAccess} がどこからも呼ばれず未テスト」
 * とされた箇所を補強する。folderId → スコープ帰属検証（IDOR チェーン末端）→ 連絡スペース認可委譲が
 * 正しく行われること、他スコープの folderId を渡す IDOR が 404 で弾かれることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentFolderService 認可配線テスト")
class TournamentFolderServiceTest {

    @Mock
    private TournamentRepository tournamentRepository;
    @Mock
    private TournamentDivisionRepository divisionRepository;
    @Mock
    private SharedFolderService folderService;
    @Mock
    private TournamentContactAccessService accessService;

    @InjectMocks
    private TournamentFolderService service;

    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;
    private static final Long FOLDER_ID = 1L;
    private static final Long USER_ID = 10L;

    private TournamentEntity tournament() {
        return TournamentEntity.builder().organizationId(5L).name("テスト大会").createdBy(1L).build();
    }

    private TournamentDivisionEntity division() {
        return TournamentDivisionEntity.builder().tournamentId(TOURNAMENT_ID).name("1部").build();
    }

    private SharedFolderEntity tournamentFolder() {
        return SharedFolderEntity.builder()
                .scopeType(FileScopeType.TOURNAMENT).scopeRefId(TOURNAMENT_ID).name("大会要項").build();
    }

    private SharedFolderEntity divisionFolder() {
        return SharedFolderEntity.builder()
                .scopeType(FileScopeType.TOURNAMENT_DIVISION).scopeRefId(DIVISION_ID).name("規約").build();
    }

    @Nested
    @DisplayName("checkFolderViewAccess")
    class CheckFolderViewAccess {

        @Test
        @DisplayName("大会スコープ: フォルダ帰属確認後に連絡スペース閲覧認可へ委譲する")
        void 大会スコープ閲覧委譲() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(tournamentFolder());

            service.checkFolderViewAccess(TOURNAMENT_ID, null, FOLDER_ID, USER_ID);

            verify(accessService).checkView(
                    eq(ContactSpaceScopeType.TOURNAMENT), eq(TOURNAMENT_ID),
                    eq(ContactSpaceKind.BULLETIN), eq(USER_ID));
        }

        @Test
        @DisplayName("ディビジョンスコープ: divId→tId 帰属 + フォルダ帰属確認後に閲覧認可へ委譲する")
        void ディビジョンスコープ閲覧委譲() {
            given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                    .willReturn(Optional.of(division()));
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(divisionFolder());

            service.checkFolderViewAccess(TOURNAMENT_ID, DIVISION_ID, FOLDER_ID, USER_ID);

            verify(accessService).checkView(
                    eq(ContactSpaceScopeType.TOURNAMENT_DIVISION), eq(DIVISION_ID),
                    eq(ContactSpaceKind.BULLETIN), eq(USER_ID));
        }

        @Test
        @DisplayName("IDOR: 他スコープ（ディビジョン）の folderId を大会スコープで渡すと 404")
        void IDORは404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            // フォルダはディビジョンスコープ → 期待する大会スコープと不一致
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(divisionFolder());

            assertThatThrownBy(() -> service.checkFolderViewAccess(TOURNAMENT_ID, null, FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND);
            verify(accessService, never()).checkView(any(), any(), any(), any());
        }

        @Test
        @DisplayName("存在しない大会は 404（TOURNAMENT_NOT_FOUND）で連絡スペース認可へ進まない")
        void 大会不在は404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.checkFolderViewAccess(TOURNAMENT_ID, null, FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("checkFolderPostAccess")
    class CheckFolderPostAccess {

        @Test
        @DisplayName("大会スコープ: フォルダ帰属確認後に連絡スペース投稿認可へ委譲する")
        void 大会スコープ投稿委譲() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(tournamentFolder());

            service.checkFolderPostAccess(TOURNAMENT_ID, null, FOLDER_ID, USER_ID);

            verify(accessService).checkPost(
                    eq(ContactSpaceScopeType.TOURNAMENT), eq(TOURNAMENT_ID), eq(USER_ID));
        }

        @Test
        @DisplayName("IDOR: 他スコープの folderId を渡すと 404 で投稿認可へ進まない")
        void IDORは404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(divisionFolder());

            assertThatThrownBy(() -> service.checkFolderPostAccess(TOURNAMENT_ID, null, FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND);
            verify(accessService, never()).checkPost(any(), any(), any());
        }
    }
}
