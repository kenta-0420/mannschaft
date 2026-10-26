package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationSlotGenerationBatchService} の単体テスト（F03.4.2 試練・F-9）。
 *
 * <p>①対象チームの列挙と生成委譲 ②チーム単位の失敗隔離（1チームの失敗が他チームを巻き込まない）
 * ③{@code @Scheduled}（日次 AM 0:15 JST）＋{@code @SchedulerLock}（多重起動防止）の宣言。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationSlotGenerationBatchService 単体テスト (F03.4.2 F-9)")
class ReservationSlotGenerationBatchServiceTest {

    @Mock
    private ReservationSlotTemplateRepository templateRepository;

    @Mock
    private ReservationSlotGenerationService generationService;

    @InjectMocks
    private ReservationSlotGenerationBatchService batchService;

    @Test
    @DisplayName("F-9: active テンプレを持つ全チームについて差分生成（generateDiffForTeam）を呼ぶ")
    void 日次バッチ_全チーム差分生成() {
        // Given
        given(templateRepository.findDistinctActiveTeamIds()).willReturn(List.of(1L, 2L, 3L));
        given(generationService.generateDiffForTeam(org.mockito.ArgumentMatchers.anyLong()))
                .willReturn(GenerateSlotsResponse.builder().build());

        // When
        batchService.generateDailyHorizon();

        // Then
        verify(generationService).generateDiffForTeam(1L);
        verify(generationService).generateDiffForTeam(2L);
        verify(generationService).generateDiffForTeam(3L);
    }

    @Test
    @DisplayName("F-9: 1チームの生成失敗は他チームを巻き込まない（チーム単位 try/catch・log.error 記録）")
    void 日次バッチ_失敗隔離() {
        // Given: チーム2 だけ失敗する
        given(templateRepository.findDistinctActiveTeamIds()).willReturn(List.of(1L, 2L, 3L));
        given(generationService.generateDiffForTeam(1L)).willReturn(GenerateSlotsResponse.builder().build());
        willThrow(new RuntimeException("DB接続断")).given(generationService).generateDiffForTeam(2L);
        given(generationService.generateDiffForTeam(3L)).willReturn(GenerateSlotsResponse.builder().build());

        // When / Then: 例外が外へ漏れず、チーム3 も処理される
        assertThatCode(() -> batchService.generateDailyHorizon()).doesNotThrowAnyException();
        verify(generationService).generateDiffForTeam(3L);
    }

    @Test
    @DisplayName("F-9③: @Scheduled(cron 0 15 0 * * * JST) と @SchedulerLock(reservationSlotGeneration) が宣言されている")
    void 日次バッチ_スケジュール宣言() throws Exception {
        // Given
        Method method = ReservationSlotGenerationBatchService.class.getMethod("generateDailyHorizon");

        // Then: 日次 AM 0:15 JST（リマインドバッチと時間帯分離）＋多重起動防止ロック
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).as("@Scheduled が宣言されていること").isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 15 0 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Tokyo");

        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        assertThat(lock).as("@SchedulerLock が宣言されていること（多重起動防止）").isNotNull();
        assertThat(lock.name()).isEqualTo("reservationSlotGeneration");
    }
}
