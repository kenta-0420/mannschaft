package com.mannschaft.app.notification.confirmable.mapper;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationTemplateResponse;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationTemplateEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableRecipientGroupEntity;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationTemplateRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableRecipientGroupRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練A補完（AC-32 テンプレートの既定の宛先グループ・BE部分）。
 *
 * <p>軍議第8版確定稿 §3.1・§4「テンプレートと宛先グループ」AC-32 を対象とする。
 * 「テンプレートに default_recipient_group_id を保存でき、取得 API の応答に含まれる。
 * 削除済みグループを指すテンプレートは、応答でそのグループを null として返す」を検証する。</p>
 *
 * <p>骨格段階の {@link ConfirmableNotificationMapper} は MapStruct の既定マッピング
 * （フィールド名一致でそのままコピー）のため、論理削除済みグループを NULL に落とす判定を
 * 行っていない。したがって「削除済みグループは応答で null」のテストは出陣（green化）まで
 * red のまま失敗する。</p>
 */
@DisplayName("ConfirmableNotificationMapper 試練（AC-32 テンプレートの既定の宛先グループ）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ConfirmableNotificationTemplateDefaultGroupIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ConfirmableNotificationMapper mapper;
    @Autowired
    private ConfirmableNotificationTemplateRepository templateRepository;
    @Autowired
    private ConfirmableRecipientGroupRepository groupRepository;

    @Test
    @DisplayName("AC-32: 有効な既定グループを保存すると、取得応答にそのグループIDが含まれる")
    void ac32_validDefaultGroupIsIncludedInResponse() {
        ConfirmableRecipientGroupEntity group = groupRepository.save(ConfirmableRecipientGroupEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(601L)
                .name("AC-32用有効グループ")
                .build());

        ConfirmableNotificationTemplateEntity template = templateRepository.save(
                ConfirmableNotificationTemplateEntity.builder()
                        .scopeType(ScopeType.ORGANIZATION)
                        .scopeId(601L)
                        .name("AC-32テンプレート")
                        .title("タイトル")
                        .defaultPriority(ConfirmableNotificationPriority.NORMAL)
                        .defaultRecipientGroupId(group.getId())
                        .build());

        ConfirmableNotificationTemplateResponse response = mapper.toTemplateResponse(template);

        assertThat(response.getDefaultRecipientGroupId())
                .as("AC-32: 保存した既定グループIDが取得応答に含まれる")
                .isEqualTo(group.getId());
    }

    @Test
    @DisplayName("AC-32: 論理削除済みグループを指すテンプレートは、応答でそのグループをnullとして返す")
    void ac32_deletedDefaultGroupIsNullInResponse() {
        ConfirmableRecipientGroupEntity deletedGroup = groupRepository.save(ConfirmableRecipientGroupEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(602L)
                .name("AC-32用削除済みグループ")
                .deletedAt(LocalDateTime.now())
                .build());

        ConfirmableNotificationTemplateEntity template = templateRepository.save(
                ConfirmableNotificationTemplateEntity.builder()
                        .scopeType(ScopeType.ORGANIZATION)
                        .scopeId(602L)
                        .name("AC-32テンプレート（削除済みグループ参照）")
                        .title("タイトル")
                        .defaultPriority(ConfirmableNotificationPriority.NORMAL)
                        .defaultRecipientGroupId(deletedGroup.getId())
                        .build());

        ConfirmableNotificationTemplateResponse response = mapper.toTemplateResponse(template);

        assertThat(response.getDefaultRecipientGroupId())
                .as("AC-32: 削除済みグループを指す場合は応答でnull（既定＝配下すべてに戻す）")
                .isNull();
    }

    @Test
    @DisplayName("AC-32: 既定グループ未設定（null）のテンプレートは、応答でもnull")
    void ac32_noDefaultGroupIsNullInResponse() {
        ConfirmableNotificationTemplateEntity template = templateRepository.save(
                ConfirmableNotificationTemplateEntity.builder()
                        .scopeType(ScopeType.ORGANIZATION)
                        .scopeId(603L)
                        .name("AC-32テンプレート（既定グループなし）")
                        .title("タイトル")
                        .defaultPriority(ConfirmableNotificationPriority.NORMAL)
                        .build());

        ConfirmableNotificationTemplateResponse response = mapper.toTemplateResponse(template);

        assertThat(response.getDefaultRecipientGroupId()).isNull();
        // このアサーション自体はスタブの素通しマッピングでも成立するため、UUID.randomUUID() を
        // 使わないダミー変数で試練Aが本クラスをコンパイル可能な形に保つことのみを保証する（境界確認）。
        assertThat(UUID.randomUUID()).isNotEqualTo(response.getDefaultRecipientGroupId());
    }
}
