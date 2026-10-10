package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.ranchsource.api.SourceRewardLinkProvider;
import com.mannschaft.app.common.ranchsource.api.SourceRewardReference;
import com.mannschaft.app.ranch.dto.RanchRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Ranch読取TX終了後、源所有の現在閲覧判定に技術IDだけを渡す。 */
@Service
@RequiredArgsConstructor
public class RanchRecordSourceLinkResolver {
    private final List<SourceRewardLinkProvider> providers;

    public CursorPagedResponse<RanchRecord> resolve(Long userId, RanchRecordQueryReader.ReadPage read) {
        List<RanchRecord> data = read.page().getData().stream().map(record -> {
            RanchRecordSourceRef ref = read.sourceRefs().get(record.id());
            if (ref == null) return record;
            List<SourceRewardLinkProvider> matching = providers.stream()
                    .filter(provider -> provider.sourceType() == ref.sourceType()).toList();
            // 未対応・重複登録の源は一意な認可境界がないため、元台帳だけを表示する。
            if (matching.size() != 1) return record;
            SourceRewardReference reference;
            try {
                reference = new SourceRewardReference(ref.sourceType(), ref.idType(), ref.sourceId());
            } catch (IllegalArgumentException invalidSavedIdentity) {
                // 古い/破損した正準IDを源へ渡さず、記録のポイント履歴は維持する。
                return record;
            }
            return matching.getFirst().resolve(userId, reference).map(link -> new RanchRecord(
                    record.id(), record.kind(), record.sourceType(), record.deltaPoints(), record.deltaXp(),
                    record.occurredAt(), new RanchRecord.SourceLink(link.kind().name(), link.id(), link.url())))
                    .orElse(record);
        }).toList();
        return CursorPagedResponse.of(data, read.page().getMeta());
    }
}
