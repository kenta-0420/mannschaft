package com.mannschaft.app.role;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.role.service.MembershipInviteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** G09/G20/G22: 未配送期間のカード・管理一覧・参加を実DBで直接検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class InviteGenerationReadPathsIT extends InviteGenerationItSupport {
    @Autowired private InviteService invites;
    @Autowired private MembershipInviteService membershipInvites;
    @Autowired private MembershipRepository memberships;

    @ParameterizedTest
    @CsvSource({"TEAM,true", "TEAM,false", "ORGANIZATION,true", "ORGANIZATION,false"})
    @DisplayName("G09: 未加入者の旧世代カードは閉鎖/解除後ともREVOKED")
    void oldCardIsRevokedBeforePhysicalCleanup(String type, boolean archived) {
        Scope scope = scope(type);
        Long target = user();
        var old = token(scope, 0, null, 1, 0, target, null);
        assertThat(membershipInvites.resolveInviteCardData(old.getId(), target).status()).isEqualTo("PENDING");
        jdbc.update("UPDATE " + scope.table() + " SET closure_generation = 1, archived_at = "
                + (archived ? "UTC_TIMESTAMP()" : "NULL") + " WHERE id = ?", scope.id());

        assertThat(membershipInvites.resolveInviteCardData(old.getId(), target).status()).isEqualTo("REVOKED");
        assertThat(tokens.findById(old.getId()).orElseThrow().getRevokedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G09/G18: 既加入者は旧世代でもJOINEDを優先し、在籍を変えない")
    void existingMemberCardKeepsJoinedPriority(String type) {
        Scope scope = scope(type);
        Long target = user();
        var old = token(scope, 0, null, 1, 0, target, null);
        Long membershipId = inTx(() -> memberships.saveAndFlush(MembershipEntity.builder()
                .userId(target).scopeType(ScopeType.valueOf(type)).scopeId(scope.id())
                .roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build()).getId());
        jdbc.update("UPDATE " + scope.table() + " SET closure_generation = 1, archived_at = UTC_TIMESTAMP() WHERE id = ?", scope.id());

        assertThat(membershipInvites.resolveInviteCardData(old.getId(), target).status()).isEqualTo("JOINED");
        assertThat(memberships.findById(membershipId).orElseThrow().getLeftAt()).isNull();
        assertThat(tokens.findById(old.getId()).orElseThrow().getUsedCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("管理一覧は旧世代を除外し、現世代の期限切れ/上限到達の従来表示は維持する")
    void managementListDoesNotAdvertiseOldGenerationAsActive(String type) {
        Scope scope = scope(type);
        Long actor = admin(scope);
        var old = token(scope, 0);
        jdbc.update("UPDATE " + scope.table() + " SET closure_generation = 1 WHERE id = ?", scope.id());
        var current = token(scope, 1);
        var expired = token(scope, 1, LocalDateTime.of(2020, 1, 1, 0, 0), null, 0, null, null);
        var exhausted = token(scope, 1, null, 1, 1, null, null);

        assertThat(invites.getInviteTokens(scope.id(), type, actor)).extracting(response -> response.getId())
                .containsExactlyInAnyOrder(current.getId(), expired.getId(), exhausted.getId())
                .doesNotContain(old.getId());
    }

    @ParameterizedTest
    @CsvSource({"TEAM,FUTURE", "TEAM,DELETED", "TEAM,MISSING", "TEAM,PROVISIONED",
            "ORGANIZATION,FUTURE", "ORGANIZATION,DELETED", "ORGANIZATION,MISSING", "ORGANIZATION,PROVISIONED"})
    @DisplayName("G22: 未来世代・削除・親欠落・PROVISIONEDはpreview/joinとも拒否する")
    void unsafeParentOrGenerationCannotBeUsed(String type, String invalidity) {
        Scope scope = scope(type);
        Long target = user();
        var token = token(scope, "FUTURE".equals(invalidity) ? 1 : 0);
        switch (invalidity) {
            case "DELETED" -> jdbc.update("UPDATE " + scope.table() + " SET deleted_at = UTC_TIMESTAMP() WHERE id = ?", scope.id());
            case "MISSING" -> jdbc.update("DELETE FROM " + scope.table() + " WHERE id = ?", scope.id());
            case "PROVISIONED" -> jdbc.update("UPDATE " + scope.table() + " SET lifecycle_status = 'PROVISIONED' WHERE id = ?", scope.id());
            default -> { }
        }

        assertThat(invites.previewInvite(token.getToken()).getData().isValid()).isFalse();
        assertThatThrownBy(() -> invites.joinByInvite(token.getToken(), target)).isInstanceOf(BusinessException.class);
        assertThat(tokens.findById(token.getId()).orElseThrow().getUsedCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id = ?", Long.class, target)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G20: 解除後の現世代tokenは通常の参加ができる")
    void reopenedScopeAcceptsCurrentGeneration(String type) {
        Scope scope = scope(type);
        Long target = user();
        jdbc.update("UPDATE " + scope.table() + " SET closure_generation = 1 WHERE id = ?", scope.id());
        var current = token(scope, 1);

        assertThat(invites.previewInvite(current.getToken()).getData().isValid()).isTrue();
        invites.joinByInvite(current.getToken(), target);
        assertThat(tokens.findById(current.getId()).orElseThrow().getUsedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memberships WHERE user_id = ? AND left_at IS NULL", Long.class, target)).isEqualTo(1);
    }
}
