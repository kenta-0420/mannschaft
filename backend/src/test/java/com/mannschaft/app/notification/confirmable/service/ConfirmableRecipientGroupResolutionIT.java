package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableRecipientGroupEntity;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableRecipientGroupRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練A補完（AC-15 宛先グループの404）。
 *
 * <p>軍議第8版確定稿 §4「認可」AC-15「他スコープの recipientGroupId を指定すると404。
 * 論理削除済みのグループも404」を対象とする。{@link ConfirmableRecipientGroupService#resolveForSend}
 * は骨格段階では {@code UnsupportedOperationException} を投げるスタブのため、
 * 本クラスの全テストは出陣（green化）まで red のまま失敗する。</p>
 */
@DisplayName("ConfirmableRecipientGroupService#resolveForSend 試練（AC-15 宛先グループの404）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ConfirmableRecipientGroupResolutionIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ConfirmableRecipientGroupService service;
    @Autowired
    private ConfirmableRecipientGroupRepository groupRepository;

    @Test
    @DisplayName("AC-15: 他スコープ（別組織）のrecipientGroupIdを指定すると404 RECIPIENT_GROUP_NOT_FOUND")
    void ac15_otherScopeGroupIsNotFound() {
        ConfirmableRecipientGroupEntity group = groupRepository.save(ConfirmableRecipientGroupEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(501L)
                .name("組織501の配下チーム")
                .build());

        assertThatThrownBy(() -> service.resolveForSend(ScopeType.ORGANIZATION, 999L, group.getId()))
                .as("AC-15: 自スコープ以外が保有するグループは404（存在秘匿）")
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.RECIPIENT_GROUP_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-15: 論理削除済みのrecipientGroupIdを指定すると404 RECIPIENT_GROUP_NOT_FOUND")
    void ac15_deletedGroupIsNotFound() {
        ConfirmableRecipientGroupEntity group = groupRepository.save(ConfirmableRecipientGroupEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(502L)
                .name("削除予定グループ")
                .deletedAt(Instant.now())
                .build());

        assertThatThrownBy(() -> service.resolveForSend(ScopeType.ORGANIZATION, 502L, group.getId()))
                .as("AC-15: 論理削除済みのグループは404（存在秘匿。既定＝配下すべてに戻す判断はController/FE側）")
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.RECIPIENT_GROUP_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-15: 存在しないrecipientGroupIdを指定すると404 RECIPIENT_GROUP_NOT_FOUND")
    void ac15_nonexistentGroupIsNotFound() {
        assertThatThrownBy(() -> service.resolveForSend(ScopeType.ORGANIZATION, 503L, UUID.randomUUID()))
                .as("AC-15: 存在しないIDも404")
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.RECIPIENT_GROUP_NOT_FOUND);
    }
}
