package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.dto.CreateReactionRequest;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinReactionRepository;
import com.mannschaft.app.bulletin.repository.BulletinReplyRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.service.TournamentContactAccessService;
import com.mannschaft.app.village.service.PostingIdentityService;
import com.mannschaft.app.village.service.VillageBulletinAccessService;
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
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F08.7.1 連絡機能: 掲示板サブサービス（返信・リアクション・添付）の大会/ディビジョンスコープ認可配線テスト。
 *
 * <p>検分指摘 B1/B2 の根治を固定する。要点:</p>
 * <ul>
 *   <li>TOURNAMENT / TOURNAMENT_DIVISION スコープで {@link BulletinAccessGuard#checkMembership} に
 *       <b>到達しない</b>こと（membership.domain.ScopeType に TOURNAMENT が無く 500 になる穴を塞ぐ）。</li>
 *   <li>閲覧系は {@link TournamentContactAccessService#checkView}、投稿/モデレーション系は
 *       {@link TournamentContactAccessService#checkPost} へ委譲すること。</li>
 *   <li>非権限者（canView/canPost が例外）は弾かれること（情報漏洩・権限昇格防止）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F08.7.1 掲示板 大会スコープ認可配線テスト")
class BulletinTournamentScopeAuthorizationTest {

    private static final Long SCOPE_ID = 500L;
    private static final Long THREAD_ID = 100L;
    private static final Long REPLY_ID = 200L;
    private static final Long USER_ID = 10L;

    private BulletinThreadEntity tournamentThread() {
        return BulletinThreadEntity.builder()
                .scopeType(ScopeType.TOURNAMENT).scopeId(SCOPE_ID)
                .authorId(USER_ID).title("大会連絡").body("本文").build();
    }

    private BulletinThreadEntity divisionThread() {
        return BulletinThreadEntity.builder()
                .scopeType(ScopeType.TOURNAMENT_DIVISION).scopeId(SCOPE_ID)
                .authorId(USER_ID).title("ディビジョン連絡").body("本文").build();
    }

    // ========================================================================
    // B1: BulletinReplyService（global 経路）
    // ========================================================================
    @Nested
    @DisplayName("B1 BulletinReplyService（global）")
    class ReplyService {

        @Mock private BulletinReplyRepository replyRepository;
        @Mock private BulletinThreadRepository threadRepository;
        @Mock private BulletinThreadService threadService;
        @Mock private BulletinMapper bulletinMapper;
        @Mock private BulletinAccessGuard accessGuard;
        @Mock private AuditLogService auditLogService;
        @Mock private VillageBulletinAccessService villageBulletinAccessService;
        @Mock private PostingIdentityService postingIdentityService;
        @Mock private TournamentContactAccessService tournamentContactAccessService;
        @InjectMocks private BulletinReplyService service;

        @Test
        @DisplayName("listRepliesGlobal: TOURNAMENT は canView を呼び checkMembership に落ちない")
        void listReplies大会はcanView() {
            BulletinThreadEntity thread = tournamentThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
            given(replyRepository.findByThreadIdAndParentIdIsNullOrderByCreatedAtAsc(eq(THREAD_ID), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));

            service.listRepliesGlobal(THREAD_ID, USER_ID, PageRequest.of(0, 20));

            verify(tournamentContactAccessService).checkView(
                    eq(ContactSpaceScopeType.TOURNAMENT), eq(SCOPE_ID), eq(ContactSpaceKind.BULLETIN), eq(USER_ID));
            verify(accessGuard, never()).checkMembership(any(), any(), any());
        }

        @Test
        @DisplayName("listRepliesGlobal: 非権限者（canView 例外）は閲覧不可")
        void listReplies非権限者は不可() {
            BulletinThreadEntity thread = tournamentThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
            doThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN))
                    .when(tournamentContactAccessService)
                    .checkView(any(), any(), any(), any());

            assertThatThrownBy(() -> service.listRepliesGlobal(THREAD_ID, USER_ID, PageRequest.of(0, 20)))
                    .isInstanceOf(BusinessException.class);
            verify(accessGuard, never()).checkMembership(any(), any(), any());
        }

        @Test
        @DisplayName("createReplyGlobal: TOURNAMENT_DIVISION は canPost を呼び checkMembership に落ちない")
        void createReplyディビジョンはcanPost() {
            BulletinThreadEntity thread = divisionThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
            given(replyRepository.save(any(BulletinReplyEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.createReplyGlobal(THREAD_ID, null, USER_ID, "返信本文");

            verify(tournamentContactAccessService).checkPost(
                    eq(ContactSpaceScopeType.TOURNAMENT_DIVISION), eq(SCOPE_ID), eq(USER_ID));
            verify(accessGuard, never()).checkMembership(any(), any(), any());
            verify(accessGuard, never()).requireManageContent(any(), any(), any());
        }

        @Test
        @DisplayName("createReplyGlobal: canPost 無し（例外）は投稿できない")
        void createReply非投稿権限者は不可() {
            BulletinThreadEntity thread = tournamentThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
            doThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN))
                    .when(tournamentContactAccessService).checkPost(any(), any(), any());

            assertThatThrownBy(() -> service.createReplyGlobal(THREAD_ID, null, USER_ID, "本文"))
                    .isInstanceOf(BusinessException.class);
            verify(replyRepository, never()).save(any());
            verify(accessGuard, never()).checkMembership(any(), any(), any());
        }

        @Test
        @DisplayName("deleteReplyGlobal: 他者投稿は canPost を要求し checkMembership に落ちない")
        void deleteReply他者はcanPost() {
            BulletinThreadEntity thread = tournamentThread();
            BulletinReplyEntity reply = BulletinReplyEntity.builder()
                    .threadId(THREAD_ID).authorId(999L).body("他者の返信").build();
            given(replyRepository.findById(REPLY_ID)).willReturn(Optional.of(reply));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));

            service.deleteReplyGlobal(REPLY_ID, USER_ID);

            verify(tournamentContactAccessService).checkPost(
                    eq(ContactSpaceScopeType.TOURNAMENT), eq(SCOPE_ID), eq(USER_ID));
            verify(accessGuard, never()).checkMembership(any(), any(), any());
            verify(accessGuard, never()).requireManageContent(any(), any(), any());
        }
    }

    // ========================================================================
    // B2: BulletinReactionService
    // ========================================================================
    @Nested
    @DisplayName("B2 BulletinReactionService")
    class ReactionService {

        @Mock private BulletinReactionRepository reactionRepository;
        @Mock private BulletinThreadRepository threadRepository;
        @Mock private BulletinReplyRepository replyRepository;
        @Mock private BulletinMapper bulletinMapper;
        @Mock private BulletinAccessGuard accessGuard;
        @Mock private TournamentContactAccessService tournamentContactAccessService;
        @InjectMocks private BulletinReactionService service;

        @Test
        @DisplayName("addReaction(THREAD): TOURNAMENT は canView を呼び checkMembership に落ちない")
        void addReaction大会はcanView() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(tournamentThread()));
            given(reactionRepository.existsByTargetTypeAndTargetIdAndUserIdAndEmoji(any(), any(), any(), any()))
                    .willReturn(false);
            given(reactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.addReaction(USER_ID, new CreateReactionRequest(TargetType.THREAD.name(), THREAD_ID, "👍"));

            verify(tournamentContactAccessService).checkView(
                    eq(ContactSpaceScopeType.TOURNAMENT), eq(SCOPE_ID), eq(ContactSpaceKind.BULLETIN), eq(USER_ID));
            verify(accessGuard, never()).checkMembership(any(), any(), any());
        }

        @Test
        @DisplayName("addReaction: 非権限者（canView 例外）はリアクションできない")
        void addReaction非権限者は不可() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(tournamentThread()));
            doThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN))
                    .when(tournamentContactAccessService).checkView(any(), any(), any(), any());

            assertThatThrownBy(() -> service.addReaction(
                    USER_ID, new CreateReactionRequest(TargetType.THREAD.name(), THREAD_ID, "👍")))
                    .isInstanceOf(BusinessException.class);
            verify(reactionRepository, never()).save(any());
            verify(accessGuard, never()).checkMembership(any(), any(), any());
        }
    }
}
