package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.entity.RanchPointLedgerEntity;
import com.mannschaft.app.ranch.entity.RanchRewardDecisionEntity;
import com.mannschaft.app.ranch.repository.RanchPointLedgerRepository;
import com.mannschaft.app.ranch.repository.RanchRewardDecisionRepository;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AC26: 本人台帳の表示は保持し、本人の報酬決定だけを源の再認可候補にする。 */
class RanchRecordQueryReaderTest {
    @Test
    void ownedRewardDecisionSuppliesTypeAndRefWithoutSourceContentOrLink() {
        UUID ownerId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        var row = ledger(ledgerId, ownerId, decisionId, "REWARD");
        var decision = decision(decisionId, ownerId, 21L);
        var reader = reader(List.of(row), List.of(decision));

        var result = reader.readPage(21L, null, 20);

        assertThat(result.page().getData()).hasSize(1);
        var record = result.page().getData().getFirst();
        assertThat(record.sourceType()).isEqualTo("BLOG_FIRST_PUBLISH");
        assertThat(record.sourceLink()).isNull();
        assertThat(record.deltaPoints()).isEqualTo("4");
        assertThat(result.sourceRefs()).containsEntry(ledgerId, new RanchRecordSourceRef(
                RanchRewardSourceType.BLOG_FIRST_PUBLISH,
                RanchRewardEnvelope.IdType.LONG, "37"));
    }

    @Test
    void foreignUserForeignOwnerAndCareDecisionNeverExposeSourceIdentity() {
        UUID ownerId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        var reader = reader(List.of(
                ledger(UUID.randomUUID(), ownerId, first, "REWARD"),
                ledger(UUID.randomUUID(), ownerId, second, "REWARD"),
                ledger(UUID.randomUUID(), ownerId, third, "CARE")), List.of(
                decision(first, ownerId, 22L),
                decision(second, UUID.randomUUID(), 21L),
                decision(third, ownerId, 21L)));

        var result = reader.readPage(21L, null, 20);

        assertThat(result.page().getData()).hasSize(3).allSatisfy(record -> {
            assertThat(record.sourceType()).isNull();
            assertThat(record.sourceLink()).isNull();
        });
        assertThat(result.sourceRefs()).isEmpty();
    }

    private static RanchRecordQueryReader reader(List<RanchPointLedgerEntity> rows,
                                                 List<RanchRewardDecisionEntity> decisions) {
        var ledger = mock(RanchPointLedgerRepository.class);
        var rewards = mock(RanchRewardDecisionRepository.class);
        when(ledger.pageForUser(eq(21L), isNull(), isNull(), any(Pageable.class))).thenReturn(rows);
        when(rewards.findAllById(any())).thenReturn(decisions);
        return new RanchRecordQueryReader(ledger, rewards, mock(RanchRecordCursorCodec.class));
    }

    private static RanchPointLedgerEntity ledger(UUID id, UUID ownerId, UUID decisionId, String kind) {
        return RanchPointLedgerEntity.builder().id(id).userId(21L).ownerId(ownerId)
                .decisionId(decisionId).entryKind(kind).deltaPoints(4).deltaXp(0)
                .occurredAt(Instant.parse("2026-10-05T00:00:00Z")).build();
    }

    private static RanchRewardDecisionEntity decision(UUID id, UUID ownerId, Long userId) {
        return RanchRewardDecisionEntity.builder().id(id).ownerId(ownerId).userId(userId)
                .sourceType(RanchRewardSourceType.BLOG_FIRST_PUBLISH)
                .canonicalKey(("BLOG_FIRST_PUBLISH:LONG:37:USER:" + userId)
                        .getBytes(StandardCharsets.US_ASCII)).build();
    }
}
