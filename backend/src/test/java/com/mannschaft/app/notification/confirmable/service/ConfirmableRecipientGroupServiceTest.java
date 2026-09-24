package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupCreateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableTargetType;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練（AC-31）。
 *
 * <p>軍議第8版確定稿 §3.1・§4「テンプレートと宛先グループ」AC-31（グループの作成・一覧・
 * 名前重複409）を対象とする。{@link ConfirmableRecipientGroupService} は実データベースへ
 * 永続化するため、Testcontainers の MySQL を使う IT として実データを検証する
 * （是正: コンストラクタ引数レベルの契約確認のみに留めていた軽量ユニットテストから昇格）。</p>
 */
@DisplayName("ConfirmableRecipientGroupService 試練（AC-31）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ConfirmableRecipientGroupServiceTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ConfirmableRecipientGroupService service;

    private ConfirmableRecipientGroupCreateRequest buildRequest(String name, List<ConfirmableTargetSpec> targets) {
        try {
            ConfirmableRecipientGroupCreateRequest req = new ConfirmableRecipientGroupCreateRequest();
            Field nameField = ConfirmableRecipientGroupCreateRequest.class.getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(req, name);
            Field targetsField = ConfirmableRecipientGroupCreateRequest.class.getDeclaredField("targets");
            targetsField.setAccessible(true);
            targetsField.set(req, targets);
            return req;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("AC-31: グループを作成できる（正常系）")
    void ac31_グループを作成できる() {
        ConfirmableRecipientGroupCreateRequest request = buildRequest(
                "配下全チーム-" + System.nanoTime(),
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, 100L)));

        ConfirmableRecipientGroupResponse response =
                service.create(ScopeType.ORGANIZATION, 100L, 1L, request);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getTargets()).hasSize(1);
    }

    @Test
    @DisplayName("AC-31: 同じスコープで名前が重複すると409 GROUP_NAME_DUPLICATEになる")
    void ac31_同名グループは409になる() {
        String name = "同名グループ-" + System.nanoTime();
        ConfirmableRecipientGroupCreateRequest request = buildRequest(
                name, List.of(new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, 200L)));

        service.create(ScopeType.ORGANIZATION, 200L, 1L, request);

        assertThatThrownBy(() -> service.create(ScopeType.ORGANIZATION, 200L, 1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.GROUP_NAME_DUPLICATE);
    }

    @Test
    @DisplayName("AC-31: 登録したグループの一覧が取得できる")
    void ac31_一覧取得() {
        String name = "一覧確認グループ-" + System.nanoTime();
        ConfirmableRecipientGroupCreateRequest request = buildRequest(
                name, List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, 300L)));
        service.create(ScopeType.ORGANIZATION, 300L, 1L, request);

        List<ConfirmableRecipientGroupResponse> groups = service.list(ScopeType.ORGANIZATION, 300L);

        assertThat(groups).extracting(ConfirmableRecipientGroupResponse::getName).contains(name);
    }
}
