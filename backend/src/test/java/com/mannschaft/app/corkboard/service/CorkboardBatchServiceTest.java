package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("CorkboardBatchService 単体テスト")
class CorkboardBatchServiceTest {

    @Mock private CorkboardCardRepository cardRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @InjectMocks private CorkboardBatchService service;

    @Test
    @DisplayName("自動アーカイブ: 対象カードの is_archived が true になり保存される")
    void 自動アーカイブ_対象あり() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 1, 10, 0);
        CorkboardCardEntity c1 = CorkboardCardEntity.builder()
                .corkboardId(1L).cardType("MEMO").createdBy(1L)
                .autoArchiveAt(now.minusHours(1)).build();
        CorkboardCardEntity c2 = CorkboardCardEntity.builder()
                .corkboardId(1L).cardType("MEMO").createdBy(1L)
                .autoArchiveAt(now.minusMinutes(5)).build();
        given(cardRepository.findByIsArchivedFalseAndAutoArchiveAtBefore(now))
                .willReturn(List.of(c1, c2));

        int marked = service.executeAutoArchive(now);

        assertThat(marked).isEqualTo(2);
        assertThat(c1.getIsArchived()).isTrue();
        assertThat(c2.getIsArchived()).isTrue();
        verify(cardRepository).saveAll(List.of(c1, c2));
    }

    @Test
    @DisplayName("自動アーカイブ: 対象なしなら save 呼ばない")
    void 自動アーカイブ_対象なし() {
        LocalDateTime now = LocalDateTime.now();
        given(cardRepository.findByIsArchivedFalseAndAutoArchiveAtBefore(now)).willReturn(List.of());

        int marked = service.executeAutoArchive(now);

        assertThat(marked).isZero();
        verify(cardRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("デッドリファレンス検知: 参照先が見つからないカードに is_ref_deleted=true を立てる")
    void デッドリファレンス_削除検知() {
        CorkboardCardEntity alive = CorkboardCardEntity.builder()
                .corkboardId(1L).cardType("REFERENCE")
                .referenceType("CHAT_MESSAGE").referenceId(101L).createdBy(1L).build();
        CorkboardCardEntity dead = CorkboardCardEntity.builder()
                .corkboardId(1L).cardType("REFERENCE")
                .referenceType("CHAT_MESSAGE").referenceId(102L).createdBy(1L).build();
        given(cardRepository.findActiveReferenceCardsByType("CHAT_MESSAGE"))
                .willReturn(List.of(alive, dead));
        // 他タイプは空
        for (String type : List.of("TIMELINE_POST", "BULLETIN_THREAD", "BLOG_POST", "FILE")) {
            given(cardRepository.findActiveReferenceCardsByType(type)).willReturn(List.of());
        }
        // 101 のみ生存
        given(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .willReturn(List.of(101L));

        int marked = service.executeDeadReferenceDetection();

        assertThat(marked).isEqualTo(1);
        assertThat(alive.getIsRefDeleted()).isFalse();
        assertThat(dead.getIsRefDeleted()).isTrue();
    }

    @Test
    @DisplayName("デッドリファレンス検知: SQL 失敗時は安全側として削除フラグを立てない")
    void デッドリファレンス_sql失敗は安全側() {
        CorkboardCardEntity card = CorkboardCardEntity.builder()
                .corkboardId(1L).cardType("REFERENCE")
                .referenceType("CHAT_MESSAGE").referenceId(101L).createdBy(1L).build();
        given(cardRepository.findActiveReferenceCardsByType("CHAT_MESSAGE")).willReturn(List.of(card));
        for (String type : List.of("TIMELINE_POST", "BULLETIN_THREAD", "BLOG_POST", "FILE")) {
            given(cardRepository.findActiveReferenceCardsByType(type)).willReturn(List.of());
        }
        given(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .willThrow(new RuntimeException("table missing"));

        int marked = service.executeDeadReferenceDetection();

        assertThat(marked).isZero();
        assertThat(card.getIsRefDeleted()).isFalse();
    }

    @Test
    @DisplayName("論理削除カード物理削除: しきい値を超えたカードを hardDeleteByIds で削除")
    void 物理削除_しきい値超() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 1, 4, 0);
        given(cardRepository.findCardIdsDeletedBefore(eq(now.minusDays(90))))
                .willReturn(List.of(1L, 2L, 3L));
        given(cardRepository.hardDeleteByIds(List.of(1L, 2L, 3L))).willReturn(3);

        int deleted = service.executePurge(now);

        assertThat(deleted).isEqualTo(3);
    }

    @Test
    @DisplayName("論理削除カード物理削除: 対象なしなら 0 を返す")
    void 物理削除_対象なし() {
        LocalDateTime now = LocalDateTime.now();
        given(cardRepository.findCardIdsDeletedBefore(any())).willReturn(List.of());

        int deleted = service.executePurge(now);

        assertThat(deleted).isZero();
        verify(cardRepository, never()).hardDeleteByIds(any());
    }
}
