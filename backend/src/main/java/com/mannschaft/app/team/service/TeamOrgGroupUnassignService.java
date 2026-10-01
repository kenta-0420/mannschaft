package com.mannschaft.app.team.service;

import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 削除されたチームグループを指す加盟行の {@code group_id} を未分類（NULL）へ戻すサービス
 * （F01.2.1 §7.3。team ドメインが自分の表を更新する）。
 *
 * <p>冪等: 何度呼んでも結果は同じ（対象が 0 件なら何もしない）。失敗して残った {@code group_id} は
 * 修復バッチ（7-A）が拾う。読み手は削除済みグループを指す行を未分類として扱うため、
 * 本処理の前後でユーザーに見える状態は変わらない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamOrgGroupUnassignService {

    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final Clock clock;

    /**
     * 指定グループを指す {@code group_id} を NULL にする。ステータス（ACTIVE・PENDING）は問わない。
     *
     * @return 更新した行数
     */
    @Transactional
    public int unassignGroup(Long organizationId, UUID groupId) {
        int updated = teamOrgMembershipRepository.clearGroupId(organizationId, groupId, Instant.now(clock));
        log.info("チームグループ削除に伴う付け替え: orgId={}, groupId={}, 更新行数={}", organizationId, groupId, updated);
        return updated;
    }
}
