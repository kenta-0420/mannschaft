package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupCreateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableTargetType;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練（AC-31）。
 *
 * <p>軍議第8版確定稿 §3.1・§4「テンプレートと宛先グループ」AC-31 を対象とする。
 * 骨格段階では {@link ConfirmableRecipientGroupService} は
 * {@code UnsupportedOperationException} を投げるスタブのため、全テストは red。</p>
 *
 * <p>本テストは JPA/DB を必要としないコンストラクタ引数レベルの契約確認に留める
 * （実際の重複検証・永続化は Testcontainers IT で別途固定することが望ましいが、
 * 骨格・試練Aの担当範囲では未着手。出陣担当への申し送り事項とする）。</p>
 */
@DisplayName("ConfirmableRecipientGroupService 試練（AC-31）")
class ConfirmableRecipientGroupServiceTest {

    private final ConfirmableRecipientGroupService service = new ConfirmableRecipientGroupService();

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
                "配下全チーム",
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, 100L)));

        assertThatCode(() -> {
            ConfirmableRecipientGroupResponse response =
                    service.create(ScopeType.ORGANIZATION, 100L, 1L, request);
            org.assertj.core.api.Assertions.assertThat(response.getId()).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-31: 同じスコープで名前が重複すると409 GROUP_NAME_DUPLICATEになる")
    void ac31_同名グループは409になる() {
        ConfirmableRecipientGroupCreateRequest request = buildRequest(
                "配下全チーム",
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, 100L)));

        // 出陣後は1回目のcreateが成功し、2回目の同名createが409を返すはず。
        // 骨格段階ではcreate自体が未実装のため、この期待に到達できずredになる。
        service.create(ScopeType.ORGANIZATION, 100L, 1L, request);

        assertThatThrownBy(() -> service.create(ScopeType.ORGANIZATION, 100L, 1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.GROUP_NAME_DUPLICATE);
    }

    @Test
    @DisplayName("AC-31: 登録したグループの一覧が取得できる")
    void ac31_一覧取得() {
        assertThatCode(() -> service.list(ScopeType.ORGANIZATION, 100L))
                .doesNotThrowAnyException();
    }
}
