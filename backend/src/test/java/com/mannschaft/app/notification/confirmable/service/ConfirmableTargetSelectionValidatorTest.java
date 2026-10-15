package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableTargetType;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練（AC-8〜11）。
 *
 * <p>軍議第8版確定稿 §3.3・§4「入力の意味（null / 省略 / 空配列）」を対象とする。
 * 骨格段階では {@link ConfirmableTargetSelectionValidator#resolve} は常に
 * {@code UnsupportedOperationException} を投げるスタブのため、本クラスの全テストは
 * 出陣（green化）まで red のまま失敗する（期待どおりの例外／非例外にならないため）。</p>
 */
@DisplayName("ConfirmableTargetSelectionValidator 試練（AC-8〜11）")
class ConfirmableTargetSelectionValidatorTest {

    private final ConfirmableTargetSelectionValidator validator = new ConfirmableTargetSelectionValidator();

    @Test
    @DisplayName("AC-8: targetsを省略（null）した場合は既定の宛先に解決され、例外を投げない")
    void ac8_targetsNull_既定の宛先に解決される() {
        // 出陣後は例外を投げず、既定宛先（AC-1/AC-6）に解決されるはず。
        // 骨格段階は UnsupportedOperationException を投げるため red。
        assertThatCode(() -> validator.resolve(null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-9: targets=[]（空配列）は400 TARGETS_EMPTYになる")
    void ac9_targetsEmpty_TARGETS_EMPTYになる() {
        assertThatThrownBy(() -> validator.resolve(List.of(), null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.TARGETS_EMPTY);
    }

    @Test
    @DisplayName("AC-10: targetsとrecipientGroupIdを両方指定すると400 TARGETS_AND_GROUP_BOTH_SPECIFIEDになる")
    void ac10_targetsとrecipientGroupIdを両方指定_400になる() {
        List<ConfirmableTargetSpec> targets = List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, 1L));
        UUID groupId = UUID.randomUUID();

        assertThatThrownBy(() -> validator.resolve(targets, groupId, null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.TARGETS_AND_GROUP_BOTH_SPECIFIED);
    }

    @Test
    @DisplayName("AC-11: 公開APIのリクエストにrecipientUserIdsを含めても受信者には反映されない（無視される）")
    void ac11_recipientUserIdsを含めても無視される() {
        List<Long> ignoredRecipientUserIds = List.of(999L);

        // 出陣後は targets/recipientGroupId が両方 null であれば既定宛先に解決されるはず
        // （recipientUserIds の有無で結果が変わらない）。骨格段階は例外を投げるため red。
        assertThatCode(() -> validator.resolve(null, null, ignoredRecipientUserIds))
                .doesNotThrowAnyException();
    }
}
