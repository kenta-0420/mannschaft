package com.mannschaft.app.scopefolder.listener;

import com.mannschaft.app.membership.event.MembershipEndedEvent;
import com.mannschaft.app.organization.event.OrganizationDeletedEvent;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.scopefolder.service.MyScopeFolderService;
import com.mannschaft.app.team.event.TeamDeletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F15.3 §9.5 / §9.6 {@link MembershipEventListener} 単体テスト。
 *
 * <p>イベント受信 → folderService への委譲が行われること、
 * AFTER_COMMIT フェーズで動作する設定がアノテーションレベルで保たれていること、
 * 例外発生時もスローせず warn ログに留めることを確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipEventListener 単体テスト (F15.3)")
class MembershipEventListenerTest {

    @Mock
    private MyScopeFolderService folderService;

    @InjectMocks
    private MembershipEventListener listener;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;

    // ============================================================
    // MembershipEndedEvent
    // ============================================================

    @Nested
    @DisplayName("handleMembershipEnded")
    class HandleMembershipEnded {

        @Test
        @DisplayName("正常系: 該当ユーザー × scopeType × scopeId のアイテムを削除する")
        void membershipEnded_正常系_folderService_handleMembershipEnded_呼び出し() {
            MembershipEndedEvent event = new MembershipEndedEvent(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM, TEAM_ID);

            listener.handleMembershipEnded(event);

            verify(folderService).handleMembershipEnded(USER_ID, ScopeType.TEAM, TEAM_ID);
        }

        @Test
        @DisplayName("正常系: ORGANIZATION でも folder.ScopeType.ORGANIZATION に変換される")
        void membershipEnded_ORGANIZATION_変換() {
            MembershipEndedEvent event = new MembershipEndedEvent(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION, ORG_ID);

            listener.handleMembershipEnded(event);

            verify(folderService).handleMembershipEnded(USER_ID, ScopeType.ORGANIZATION, ORG_ID);
        }

        @Test
        @DisplayName("異常系: 内部例外は warn ログのみ、外へ伝搬しない（イベント基盤への影響回避）")
        void membershipEnded_例外を握りつぶす() {
            MembershipEndedEvent event = new MembershipEndedEvent(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM, TEAM_ID);
            doThrow(new RuntimeException("DB unavailable"))
                    .when(folderService).handleMembershipEnded(USER_ID, ScopeType.TEAM, TEAM_ID);

            // When / Then: 例外は throw されない
            listener.handleMembershipEnded(event);

            verify(folderService).handleMembershipEnded(USER_ID, ScopeType.TEAM, TEAM_ID);
        }

        @Test
        @DisplayName("AFTER_COMMIT フェーズで動作するアノテーションが付与されている")
        void membershipEnded_AFTER_COMMIT() throws NoSuchMethodException {
            Method m = MembershipEventListener.class.getMethod("handleMembershipEnded", MembershipEndedEvent.class);
            TransactionalEventListener anno = m.getAnnotation(TransactionalEventListener.class);
            assertThat(anno).isNotNull();
            assertThat(anno.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        }
    }

    // ============================================================
    // TeamDeletedEvent
    // ============================================================

    @Nested
    @DisplayName("handleTeamDeleted")
    class HandleTeamDeleted {

        @Test
        @DisplayName("正常系: 全ユーザー分の team_id を削除する")
        void teamDeleted_正常系() {
            TeamDeletedEvent event = new TeamDeletedEvent(USER_ID, TEAM_ID);

            listener.handleTeamDeleted(event);

            verify(folderService).handleScopeDeleted(ScopeType.TEAM, TEAM_ID);
            // organization 側は呼ばれない
            verify(folderService, never()).handleScopeDeleted(ScopeType.ORGANIZATION, TEAM_ID);
        }

        @Test
        @DisplayName("異常系: 内部例外は warn ログのみ、外へ伝搬しない")
        void teamDeleted_例外を握りつぶす() {
            TeamDeletedEvent event = new TeamDeletedEvent(USER_ID, TEAM_ID);
            doThrow(new RuntimeException("DB unavailable"))
                    .when(folderService).handleScopeDeleted(ScopeType.TEAM, TEAM_ID);

            listener.handleTeamDeleted(event);

            verify(folderService).handleScopeDeleted(ScopeType.TEAM, TEAM_ID);
        }

        @Test
        @DisplayName("AFTER_COMMIT フェーズで動作するアノテーションが付与されている")
        void teamDeleted_AFTER_COMMIT() throws NoSuchMethodException {
            Method m = MembershipEventListener.class.getMethod("handleTeamDeleted", TeamDeletedEvent.class);
            TransactionalEventListener anno = m.getAnnotation(TransactionalEventListener.class);
            assertThat(anno).isNotNull();
            assertThat(anno.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        }
    }

    // ============================================================
    // OrganizationDeletedEvent
    // ============================================================

    @Nested
    @DisplayName("handleOrganizationDeleted")
    class HandleOrganizationDeleted {

        @Test
        @DisplayName("正常系: 全ユーザー分の organization_id を削除する")
        void orgDeleted_正常系() {
            OrganizationDeletedEvent event = new OrganizationDeletedEvent(USER_ID, ORG_ID);

            listener.handleOrganizationDeleted(event);

            verify(folderService).handleScopeDeleted(ScopeType.ORGANIZATION, ORG_ID);
            verify(folderService, never()).handleScopeDeleted(ScopeType.TEAM, ORG_ID);
        }

        @Test
        @DisplayName("異常系: 内部例外は warn ログのみ、外へ伝搬しない")
        void orgDeleted_例外を握りつぶす() {
            OrganizationDeletedEvent event = new OrganizationDeletedEvent(USER_ID, ORG_ID);
            doThrow(new RuntimeException("DB unavailable"))
                    .when(folderService).handleScopeDeleted(ScopeType.ORGANIZATION, ORG_ID);

            listener.handleOrganizationDeleted(event);

            verify(folderService).handleScopeDeleted(ScopeType.ORGANIZATION, ORG_ID);
        }

        @Test
        @DisplayName("AFTER_COMMIT フェーズで動作するアノテーションが付与されている")
        void orgDeleted_AFTER_COMMIT() throws NoSuchMethodException {
            Method m = MembershipEventListener.class.getMethod(
                    "handleOrganizationDeleted", OrganizationDeletedEvent.class);
            TransactionalEventListener anno = m.getAnnotation(TransactionalEventListener.class);
            assertThat(anno).isNotNull();
            assertThat(anno.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        }
    }
}
