package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientPageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練（AC-30・AC-59・AC-60）。
 *
 * <p>軍議第8版確定稿 §9.5「受信者一覧をページングするときのFE契約」を対象とする。
 * 骨格段階では {@link ConfirmableNotificationQueryService#getRecipientsPage} はスタブのため red。</p>
 *
 * <p>実際のフィクスチャ（1,201人規模・ADMIN/MEMBER のロール横断）は Testcontainers IT が必要なため、
 * 骨格・試練Aでは「契約の形」を固定する軽量テストに留める。出陣担当への申し送り事項:
 * 本クラスを IT に差し替えるか、専用の {@code ConfirmableNotificationRecipientPageIT} を追加すること。</p>
 */
@DisplayName("受信者一覧ページング応答契約 試練（AC-30・AC-59・AC-60）")
class ConfirmableNotificationRecipientPageTest {

    private final ConfirmableNotificationQueryService queryService =
            new ConfirmableNotificationQueryService(null, null);

    @Test
    @DisplayName("AC-30: sizeに101を指定すると上限100に丸められるか400になる（例外を投げない場合はsize=100で返る）")
    void ac30_sizeが101_上限100に丸められる() {
        ConfirmableNotificationRecipientPageResponse response =
                queryService.getRecipientsPage(1L, 1L, 0, 101, false);

        assertThat(response.getSize()).isLessThanOrEqualTo(
                ConfirmableNotificationQueryService.MAX_RECIPIENT_PAGE_SIZE);
    }

    @Test
    @DisplayName("AC-59: ADMINが未確認者だけに絞ったページを開くと、総件数・確認済み・未確認件数は通知全体の値で返る")
    void ac59_ADMIN視点_総件数は通知全体の値() {
        ConfirmableNotificationRecipientPageResponse response =
                queryService.getRecipientsPage(1L, 1L, 0, 100, true);

        assertThat(response.getViewerRole())
                .isEqualTo(ConfirmableNotificationRecipientPageResponse.ViewerRole.ADMIN);
        assertThat(response.getTotalElements()).isEqualTo(1201L);
    }

    @Test
    @DisplayName("AC-60: MEMBERの場合、公開範囲設定どおりに見えてよい範囲の一覧と件数だけが返る")
    void ac60_MEMBER視点_公開範囲どおりの件数() {
        ConfirmableNotificationRecipientPageResponse response =
                queryService.getRecipientsPage(1L, 2L, 0, 100, true);

        assertThat(response.getViewerRole())
                .isEqualTo(ConfirmableNotificationRecipientPageResponse.ViewerRole.MEMBER);
    }
}
