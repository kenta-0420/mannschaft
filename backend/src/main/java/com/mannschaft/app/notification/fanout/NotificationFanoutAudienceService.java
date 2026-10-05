package com.mannschaft.app.notification.fanout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * fan-out の宛先集合（{@code ORGANIZATION_TEAMS} 戦略用。F01.2.1 §5.6・§8.5.3）の登録サービス。
 *
 * <p>他ドメイン（告知ウィザード）から宛先集合の表を直接触らせないための、通知ドメインの窓口。
 * 呼び出し側のトランザクションに参加して（{@link Propagation#MANDATORY}）見出しと宛先チームを書くため、
 * 呼び出し側（告知）がロールバックすれば宛先集合も残らない（transactional outbox 相当）。</p>
 *
 * <p>主キー {@code audience_snapshot_id} は呼び出し側が決定的に導いた UUID を渡す。
 * 同じキーの二重登録は何もしない（見出しがあれば宛先チームの不足分だけを足す）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationFanoutAudienceService {

    private final NotificationFanoutAudienceRepository audienceRepository;
    private final NotificationFanoutAudienceTeamRepository audienceTeamRepository;

    /**
     * 宛先集合（見出し＋宛先チーム）を冪等に登録する。宛先チームは 0 件でもよい（直属メンバーだけに届く告知）。
     *
     * @param audienceSnapshotId 宛先集合のキー（決定的に導いた UUID。ジョブ行の {@code scope_ref} に入る）
     * @param organizationId     宛先の組織 ID（直属メンバーの解決と加盟の再確認に使う）
     * @param teamIds            宛先チーム ID（重複は除く）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registerAudience(UUID audienceSnapshotId, Long organizationId, Collection<Long> teamIds) {
        if (!audienceRepository.existsById(audienceSnapshotId)) {
            NotificationFanoutAudienceEntity header = NotificationFanoutAudienceEntity.builder()
                    .organizationId(organizationId)
                    .build();
            header.setId(audienceSnapshotId);
            audienceRepository.saveAndFlush(header);
        }
        Set<Long> wanted = new LinkedHashSet<>(teamIds);
        Set<Long> existing = new LinkedHashSet<>(
                audienceTeamRepository.findTeamIdsByAudienceSnapshotId(audienceSnapshotId));
        List<NotificationFanoutAudienceTeamEntity> rows = wanted.stream()
                .filter(teamId -> !existing.contains(teamId))
                .map(teamId -> NotificationFanoutAudienceTeamEntity.builder()
                        .audienceSnapshotId(audienceSnapshotId)
                        .teamId(teamId)
                        .build())
                .toList();
        audienceTeamRepository.saveAll(rows);
        audienceTeamRepository.flush();
        log.debug("fan-out 宛先集合を登録: audienceSnapshotId={} organizationId={} teams={}",
                audienceSnapshotId, organizationId, wanted.size());
    }
}
