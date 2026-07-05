package com.mannschaft.app.proxy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.mannschaft.app.proxy.dto.ProxyActionView;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link ProxyInputQueryService} の単体テスト（F14.1 代理入力の取得系）。
 *
 * <p>他ドメインへ Entity を漏らさず {@link ProxyActionView}（プリミティブ DTO）で返すことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyInputQueryService テスト")
class ProxyInputQueryServiceTest {

    private static final Long SUBJECT_ID = 11L;

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @InjectMocks
    private ProxyInputQueryService service;

    @Test
    @DisplayName("subject で引き、Entity を View（プリミティブ）へ写像する")
    void getActionsBySubject_mapsToView() {
        ProxyInputRecordEntity record = ProxyInputRecordEntity.builder()
                .subjectUserId(SUBJECT_ID)
                .proxyUserId(100L)
                .featureScope("SCHEDULE_ATTENDANCE")
                .targetEntityType("SCHEDULE_ATTENDANCE")
                .targetEntityId(999L)
                .inputSource(ProxyInputRecordEntity.InputSource.GUARDIANSHIP_SWITCH)
                .originalStorageLocation("N/A")
                .build();
        ReflectionTestUtils.setField(record, "id", 77L);
        ReflectionTestUtils.setField(record, "createdAt", LocalDateTime.parse("2026-07-04T09:00:00"));
        given(proxyInputRecordRepository.findBySubjectUserIdOrderByCreatedAtDesc(SUBJECT_ID))
                .willReturn(List.of(record));

        List<ProxyActionView> result = service.getActionsBySubject(SUBJECT_ID);

        assertThat(result).hasSize(1);
        ProxyActionView v = result.get(0);
        assertThat(v.id()).isEqualTo(77L);
        assertThat(v.subjectUserId()).isEqualTo(SUBJECT_ID);
        assertThat(v.proxyUserId()).isEqualTo(100L);
        assertThat(v.featureScope()).isEqualTo("SCHEDULE_ATTENDANCE");
        assertThat(v.targetEntityId()).isEqualTo(999L);
        assertThat(v.inputSource()).isEqualTo("GUARDIANSHIP_SWITCH");
        assertThat(v.createdAt()).isEqualTo(LocalDateTime.parse("2026-07-04T09:00:00"));
    }

    @Test
    @DisplayName("該当なし → 空リスト")
    void getActionsBySubject_empty() {
        given(proxyInputRecordRepository.findBySubjectUserIdOrderByCreatedAtDesc(SUBJECT_ID))
                .willReturn(List.of());

        assertThat(service.getActionsBySubject(SUBJECT_ID)).isEmpty();
    }
}
