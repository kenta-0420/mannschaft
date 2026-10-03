package com.mannschaft.app.reservation;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link EmergencyClosureBroadcastListener} の純 Mockito UT。
 *
 * <p>お手本は {@code MatchLiveBroadcastListenerTest}。{@code @TransactionalEventListener} は結合テストでは
 * TX 発火しないため、リスナーメソッドを<b>直接呼ぶ</b>。</p>
 *
 * <p>検証項目:
 * <ul>
 *   <li>イベント受領 → {@code convertAndSend("/topic/teams/{teamId}/emergency-closures/{closureId}/confirmations", payload)}
 *       が正しい宛先・ペイロードで呼ばれる</li>
 *   <li>確認サマリ（confirmedCount/totalCount）がカウントクエリ結果から算出される</li>
 *   <li>ユーザー氏名が NameResolverService 経由「姓 + ' ' + 名」で取得される（不在時は "不明なユーザー"）</li>
 *   <li>convertAndSend が例外 → 確認経路へ伝播させずログのみ（巻き戻さない・症状を隠さない）</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} かつ {@code @Transactional} を付けていない
 *       （配信のみで DB 書き込みなしゆえ・素の REQUIRED は ApplicationContext 全滅）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmergencyClosureBroadcastListener 単体テスト")
class EmergencyClosureBroadcastListenerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private EmergencyClosureConfirmationRepository confirmationRepository;

    @Mock
    private NameResolverService nameResolverService;

    @InjectMocks
    private EmergencyClosureBroadcastListener listener;

    private static final Long CLOSURE_ID = 500L;
    private static final Long TEAM_ID = 7L;
    private static final Long USER_ID = 42L;

    private EmergencyClosureConfirmedEvent event() {
        return EmergencyClosureConfirmedEvent.builder()
                .closureId(CLOSURE_ID)
                .teamId(TEAM_ID)
                .userId(USER_ID)
                .confirmedAt(LocalDateTime.of(2026, 4, 8, 9, 30))
                .build();
    }

    // ============================================================
    // 配信宛先・ペイロード・サマリ算出
    // ============================================================

    @Nested
    @DisplayName("配信宛先・ペイロード・確認サマリ")
    class Broadcast {

        @Test
        @DisplayName("確認イベントを /topic/teams/{teamId}/emergency-closures/{closureId}/confirmations へ正しいペイロードで配信する")
        void 配信宛先とペイロード() {
            given(confirmationRepository.countByEmergencyClosureId(CLOSURE_ID)).willReturn(5L);
            given(confirmationRepository.countByEmergencyClosureIdAndConfirmedAtIsNotNull(CLOSURE_ID))
                    .willReturn(3L);
            given(nameResolverService.resolveUserFullName(USER_ID)).willReturn("山田 太郎");

            listener.onEmergencyClosureConfirmed(event());

            ArgumentCaptor<EmergencyClosureConfirmationUpdatePayload> captor =
                    ArgumentCaptor.forClass(EmergencyClosureConfirmationUpdatePayload.class);
            verify(messagingTemplate).convertAndSend(
                    eq("/topic/teams/" + TEAM_ID + "/emergency-closures/" + CLOSURE_ID + "/confirmations"),
                    captor.capture());

            EmergencyClosureConfirmationUpdatePayload payload = captor.getValue();
            assertThat(payload.getConfirmedCount()).isEqualTo(3L);
            assertThat(payload.getTotalCount()).isEqualTo(5L);
            assertThat(payload.getUserId()).isEqualTo(USER_ID);
            assertThat(payload.getUserFullName()).isEqualTo("山田 太郎");
            assertThat(payload.getConfirmedAt()).isEqualTo(LocalDateTime.of(2026, 4, 8, 9, 30));
        }

        @Test
        @DisplayName("ユーザーが見つからない場合は NameResolverService のフォールバック氏名で配信する（配信は継続）")
        void ユーザー不在時はフォールバック氏名() {
            given(confirmationRepository.countByEmergencyClosureId(CLOSURE_ID)).willReturn(1L);
            given(confirmationRepository.countByEmergencyClosureIdAndConfirmedAtIsNotNull(CLOSURE_ID))
                    .willReturn(1L);
            given(nameResolverService.resolveUserFullName(USER_ID)).willReturn("不明なユーザー");

            listener.onEmergencyClosureConfirmed(event());

            ArgumentCaptor<EmergencyClosureConfirmationUpdatePayload> captor =
                    ArgumentCaptor.forClass(EmergencyClosureConfirmationUpdatePayload.class);
            verify(messagingTemplate).convertAndSend(any(String.class), captor.capture());
            assertThat(captor.getValue().getUserFullName()).isEqualTo("不明なユーザー");
        }
    }

    // ============================================================
    // 配信失敗は確認へ伝播させない（best-effort・症状を隠さずログ）
    // ============================================================

    @Nested
    @DisplayName("配信失敗の取り扱い（best-effort）")
    class FailureHandling {

        @Test
        @DisplayName("convertAndSend が例外を投げても確認経路へ再スローしない（巻き戻さない・WARN ログのみ）")
        void 配信例外_再スローしない() {
            given(confirmationRepository.countByEmergencyClosureId(CLOSURE_ID)).willReturn(1L);
            given(confirmationRepository.countByEmergencyClosureIdAndConfirmedAtIsNotNull(CLOSURE_ID))
                    .willReturn(1L);
            given(nameResolverService.resolveUserFullName(USER_ID)).willReturn("不明なユーザー");
            doThrow(new org.springframework.messaging.MessagingException("broker down"))
                    .when(messagingTemplate).convertAndSend(any(String.class), any(Object.class));

            assertThatCode(() -> listener.onEmergencyClosureConfirmed(event()))
                    .doesNotThrowAnyException();
        }
    }

    // ============================================================
    // トランザクション注釈（@Transactional 不要の確証・地雷の逆ケース）
    // ============================================================

    @Nested
    @DisplayName("トランザクション注釈（@Transactional 不要の確証）")
    class TransactionAnnotation {

        @Test
        @DisplayName("onEmergencyClosureConfirmed は @TransactionalEventListener(AFTER_COMMIT) である")
        void afterCommitフェーズ() throws NoSuchMethodException {
            Method m = EmergencyClosureBroadcastListener.class.getMethod(
                    "onEmergencyClosureConfirmed", EmergencyClosureConfirmedEvent.class);
            TransactionalEventListener anno = m.getAnnotation(TransactionalEventListener.class);
            assertThat(anno).isNotNull();
            assertThat(anno.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        }

        @Test
        @DisplayName("配信のみで DB 書き込みが無いため @Transactional を付けていない（素の AFTER_COMMIT で context は壊れない）")
        void Transactional注釈なし() throws NoSuchMethodException {
            // 素の @Transactional(REQUIRED) を AFTER_COMMIT リスナーに付けると起動時バリデで ApplicationContext 全滅。
            // 本リスナーは配信のみゆえ @Transactional を付けないのが正道。
            Method m = EmergencyClosureBroadcastListener.class.getMethod(
                    "onEmergencyClosureConfirmed", EmergencyClosureConfirmedEvent.class);
            assertThat(m.getAnnotation(Transactional.class))
                    .as("配信専用リスナーに @Transactional は不要")
                    .isNull();
            assertThat(EmergencyClosureBroadcastListener.class.getAnnotation(Transactional.class))
                    .as("クラスレベルにも @Transactional を付けない")
                    .isNull();
        }
    }
}
