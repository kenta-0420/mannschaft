package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationTargetRepository;
import com.mannschaft.app.notification.fanout.FanoutChunkSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * CMP-260920-1040 試練B: {@link ConfirmableFanoutChunkSink} の 1 チャンクあたり SQL 文数の上限
 * （軍議第8版確定稿 §12・AC-34）。
 *
 * <p>受信者行・notifications・メール outbox は、それぞれ<b>多値 INSERT 1 文</b>で確定する契約であり、
 * 受信者数（本テストでは 500 人）に比例した文数を発行してはならない。骨格
 * （{@link UnsupportedOperationException}）の段階ではこの契約を満たせないため red になる。
 * 実装後（出陣）は本テストの {@code assertThatCode(...).doesNotThrowAnyException()} が通り、
 * かつ発行された SQL 文数がチャンクサイズに比例しないことを、JdbcTemplate の呼び出し回数の実測で
 * 確かめる（Mockito が投げる {@code MockitoException} は本テストの関心事ではないため、
 * ここでは「例外を投げずに完了する」ことだけを先に固定し、文数の厳密な検証は出陣後に強化する）。</p>
 */
@DisplayName("ConfirmableFanoutChunkSink 1チャンクあたりのSQL文数（AC-34・試練B）")
class ConfirmableFanoutChunkSinkInsertCountTest {

    @Test
    @DisplayName("AC-34: 500人チャンクの処理は例外を投げずに完了する（受信者数に比例したSQL文を発行しない契約の前提）")
    void processChunkOfFiveHundredCompletesWithoutException() {
        ConfirmableNotificationRepository notificationRepository = mock(ConfirmableNotificationRepository.class);
        ConfirmableNotificationRecipientRepository recipientRepository =
                mock(ConfirmableNotificationRecipientRepository.class);
        ConfirmableNotificationTargetRepository targetRepository = mock(ConfirmableNotificationTargetRepository.class);
        EmailOutboxService emailOutboxService = mock(EmailOutboxService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        ConfirmableFanoutChunkSink sink = new ConfirmableFanoutChunkSink(
                notificationRepository, recipientRepository, targetRepository, emailOutboxService, jdbcTemplate);

        UUID jobId = UUID.randomUUID();
        Long notificationId = 1L;
        List<Long> userIds = LongStream.rangeClosed(1, 500).boxed().toList();

        // 骨格段階では UnsupportedOperationException を投げるため red。
        // 出陣後はここが例外を投げずに完了し、かつ jdbcTemplate.update / emailOutboxService.enqueueAll が
        // それぞれ「受信者数に比例しない回数」（チャンクにつき高々数回）しか呼ばれないことを検証すること
        // （このテストは AC-34 の契約の入口だけを固定する。詳細な呼び出し回数の番人は出陣で追加する）。
        assertThatCode(() -> sink.processChunk(jobId, notificationId, userIds))
                .as("AC-34: 500人チャンクの処理は（DB接続不要のユニット的検証として）例外を投げずに完了するべき")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-34: finish も例外を投げずに完了する")
    void finishCompletesWithoutException() {
        ConfirmableNotificationRepository notificationRepository = mock(ConfirmableNotificationRepository.class);
        ConfirmableNotificationRecipientRepository recipientRepository =
                mock(ConfirmableNotificationRecipientRepository.class);
        ConfirmableNotificationTargetRepository targetRepository = mock(ConfirmableNotificationTargetRepository.class);
        EmailOutboxService emailOutboxService = mock(EmailOutboxService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        ConfirmableFanoutChunkSink sink = new ConfirmableFanoutChunkSink(
                notificationRepository, recipientRepository, targetRepository, emailOutboxService, jdbcTemplate);

        assertThatCode(() -> sink.finish(UUID.randomUUID(), 1L))
                .doesNotThrowAnyException();
    }

    /** {@link FanoutChunkSink} を経由してもキーが確定していることの回帰番人。 */
    @Test
    @DisplayName("notificationType() は固定キーを返す")
    void notificationTypeIsFixed() {
        ConfirmableNotificationRepository notificationRepository = mock(ConfirmableNotificationRepository.class);
        ConfirmableNotificationRecipientRepository recipientRepository =
                mock(ConfirmableNotificationRecipientRepository.class);
        ConfirmableNotificationTargetRepository targetRepository = mock(ConfirmableNotificationTargetRepository.class);
        EmailOutboxService emailOutboxService = mock(EmailOutboxService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        FanoutChunkSink sink = new ConfirmableFanoutChunkSink(
                notificationRepository, recipientRepository, targetRepository, emailOutboxService, jdbcTemplate);

        org.assertj.core.api.Assertions.assertThat(sink.notificationType())
                .isEqualTo(ConfirmableFanoutChunkSink.NOTIFICATION_TYPE);
    }
}
