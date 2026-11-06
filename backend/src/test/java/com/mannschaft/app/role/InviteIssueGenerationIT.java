package com.mannschaft.app.role;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.event.OrganizationInviteTokenCreatedEvent;
import com.mannschaft.app.role.dto.CreateInviteTokenRequest;
import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.team.event.TeamInviteTokenCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G04/G05/G14 共有招待の発行世代・休止拒否・commit後の閉鎖検出。
 *
 * <p>競合同期は既存の実発行イベントにテスト側listenerを一時登録し、実TXのafterCommitから
 * 別TXで親を閉鎖する。production hook/APIやBean動作の置換は追加しない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("個人招待の発行世代とcommit後再確認")
class InviteIssueGenerationIT extends InviteGenerationItSupport {
    @Autowired private InviteService invites;
    @Autowired private ConfigurableApplicationContext context;

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G04: 発行時に招待先自身の現在閉鎖世代を保存する")
    void issuanceRecordsCurrentScopeGeneration(String type) {
        Scope scope = scope(type);
        Long actor = admin(scope);
        jdbc.update("UPDATE " + scope.table() + " SET closure_generation = 3 WHERE id = ?", scope.id());

        var response = invites.createInviteToken(scope.id(), type,
                new CreateInviteTokenRequest(scope.memberRoleId(), null, null), actor).getData();

        assertThat(tokens.findById(response.getId()).orElseThrow().getScopeClosureGeneration()).isEqualTo(3);
        assertThat(response.getRevokedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G05: 事前に閉鎖済みなら発行を拒否しtokenを作らない")
    void alreadyArchivedScopeRejectsIssuanceBeforeWritingToken(String type) {
        Scope scope = scope(type);
        Long actor = admin(scope);
        jdbc.update("UPDATE " + scope.table()
                + " SET archived_at = UTC_TIMESTAMP(), closure_generation = 1 WHERE id = ?", scope.id());

        assertThatThrownBy(() -> invites.createInviteToken(scope.id(), type,
                new CreateInviteTokenRequest(scope.memberRoleId(), null, null), actor)).isInstanceOf(BusinessException.class);
        assertThat(tokenCount(scope, false)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"TEAM,true", "TEAM,false", "ORGANIZATION,true", "ORGANIZATION,false"})
    @DisplayName("G14: 発行commit後に閉鎖/解除を挟んでも拒否し、監査可能な失効token行だけを残す")
    void closureAfterIssuanceCommitIsDetectedAndCompensated(String type, boolean remainsArchived) {
        Scope scope = scope(type);
        Long actor = admin(scope);
        AtomicLong issuedTokenId = new AtomicLong();
        ApplicationListener<PayloadApplicationEvent<?>> listener = event -> {
            Object payload = event.getPayload();
            Long tokenId = null;
            if (payload instanceof TeamInviteTokenCreatedEvent created
                    && type.equals("TEAM") && created.getTeamId().equals(scope.id())) {
                tokenId = created.getTokenId();
            } else if (payload instanceof OrganizationInviteTokenCreatedEvent created
                    && type.equals("ORGANIZATION") && created.getOrganizationId().equals(scope.id())) {
                tokenId = created.getTokenId();
            }
            if (tokenId == null) {
                return;
            }
            issuedTokenId.set(tokenId);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("発行イベントはrole書込TXの中で発行する").isTrue();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    TransactionTemplate close = new TransactionTemplate(transactionManager);
                    close.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    close.executeWithoutResult(status -> jdbc.update("UPDATE " + scope.table()
                            + " SET archived_at = " + (remainsArchived ? "UTC_TIMESTAMP()" : "NULL")
                            + ", closure_generation = 1 WHERE id = ?", scope.id()));
                }
            });
        };
        ApplicationEventMulticaster multicaster = context.getBean("applicationEventMulticaster", ApplicationEventMulticaster.class);
        multicaster.addApplicationListener(listener);
        try {
            assertThatThrownBy(() -> invites.createInviteToken(scope.id(), type,
                    new CreateInviteTokenRequest(scope.memberRoleId(), null, null), actor)).isInstanceOf(BusinessException.class);
        } finally {
            multicaster.removeApplicationListener(listener);
        }
        assertThat(issuedTokenId.get()).isPositive();
        assertThat(tokenCount(scope, false)).as("補償済みtokenは監査行として保持する").isEqualTo(1);
        assertThat(tokenCount(scope, true)).as("未失効tokenを作り逃がさない").isZero();
        assertThat(tokens.findById(issuedTokenId.get()).orElseThrow().getRevokedAt()).isNotNull();
    }

    private Long tokenCount(Scope scope, boolean unrevokedOnly) {
        String scopeColumn = "TEAM".equals(scope.type()) ? "team_id" : "organization_id";
        return jdbc.queryForObject("SELECT COUNT(*) FROM invite_tokens WHERE " + scopeColumn + " = ?"
                + (unrevokedOnly ? " AND revoked_at IS NULL" : ""), Long.class, scope.id());
    }
}
