package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.repository.AnalyticsMonthlyCohortRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MonthlyCohortBatchService} の IN 句チャンク分割単体テスト。
 *
 * <p>コホートユーザー数が数万〜数十万件に達すると、IN 句 1 本にまとめて問い合わせると
 * プレースホルダ上限 / max_allowed_packet 超過で例外が飛ぶ（機能停止）。チャンク分割後、
 * 合算値が分割前と一致することを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
class MonthlyCohortBatchServiceChunkingTest {

    @Mock
    private AnalyticsMonthlyCohortRepository cohortRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MemberPaymentRepository memberPaymentRepository;

    @InjectMocks
    private MonthlyCohortBatchService service;

    @Test
    @DisplayName("ユーザーID件数がチャンクサイズを超える場合、複数回に分割して呼び出し合算する（countActive）")
    void countActiveByUserIdsChunked_splitsAndSums() {
        // チャンクサイズ(1000)の2.5倍 = 2500件 → 3チャンク(1000, 1000, 500)に分かれる想定
        List<Long> userIds = LongStream.rangeClosed(1, 2500).boxed().toList();

        when(userRepository.countActiveByUserIds(anyList()))
                .thenAnswer(inv -> {
                    List<?> chunk = inv.getArgument(0);
                    return chunk.size(); // チャンクサイズをそのままアクティブ数として返す
                });

        int result = service.countActiveByUserIdsChunked(userIds);

        // 合算 = 1000 + 1000 + 500 = 2500
        assertThat(result).isEqualTo(2500);

        ArgumentCaptor<List<Long>> chunkCaptor = ArgumentCaptor.forClass(List.class);
        verify(userRepository, times(3)).countActiveByUserIds(chunkCaptor.capture());
        List<List<Long>> chunks = chunkCaptor.getAllValues();
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(1000);
        assertThat(chunks.get(1)).hasSize(1000);
        assertThat(chunks.get(2)).hasSize(500);

        // 全チャンクを合わせた要素が元のリストと一致する（欠落・重複が無い）
        List<Long> allIds = new ArrayList<>();
        chunks.forEach(allIds::addAll);
        assertThat(allIds).containsExactlyElementsOf(userIds);
    }

    @Test
    @DisplayName("ユーザーID件数がチャンクサイズと同じ・以下の場合は1回だけ呼び出す")
    void countActiveByUserIdsChunked_singleChunkWhenAtOrBelowLimit() {
        List<Long> userIds = LongStream.rangeClosed(1, 1000).boxed().toList();
        when(userRepository.countActiveByUserIds(anyList())).thenReturn(1000);

        int result = service.countActiveByUserIdsChunked(userIds);

        assertThat(result).isEqualTo(1000);
        verify(userRepository, times(1)).countActiveByUserIds(any());
    }

    @Test
    @DisplayName("収益合算もチャンク分割され、null 返却チャンクは0として扱われる")
    void sumPaidAmountByUserIdsAndMonthChunked_splitsAndSums() {
        List<Long> userIds = LongStream.rangeClosed(1, 1500).boxed().toList();
        LocalDate monthStart = LocalDate.of(2026, 4, 1);
        LocalDate monthEnd = LocalDate.of(2026, 4, 30);

        when(memberPaymentRepository.sumPaidAmountByUserIdsAndMonth(anyList(), any(), any()))
                .thenReturn(BigDecimal.valueOf(1000))
                .thenReturn(null); // 2チャンク目は支払い無しでnull（COALESCEされない集計もあり得るため防御）

        BigDecimal result = service.sumPaidAmountByUserIdsAndMonthChunked(userIds, monthStart, monthEnd);

        assertThat(result).isEqualByComparingTo(BigDecimal.valueOf(1000));
        verify(memberPaymentRepository, times(2))
                .sumPaidAmountByUserIdsAndMonth(anyList(), any(), any());
    }
}
