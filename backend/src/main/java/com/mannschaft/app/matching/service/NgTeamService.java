package com.mannschaft.app.matching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.MatchingErrorCode;
import com.mannschaft.app.matching.dto.CreateNgTeamRequest;
import com.mannschaft.app.matching.dto.NgTeamResponse;
import com.mannschaft.app.matching.entity.NgTeamEntity;
import com.mannschaft.app.matching.mapper.MatchingMapper;
import com.mannschaft.app.matching.repository.NgTeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * NGチームサービス。NGチームの追加・削除・一覧を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NgTeamService {

    private final NgTeamRepository ngTeamRepository;
    private final MatchingMapper matchingMapper;

    /**
     * 自チームのNGリストを取得する。
     */
    public List<NgTeamResponse> listNgTeams(Long teamId) {
        List<NgTeamEntity> entities = ngTeamRepository.findByTeamIdOrderByCreatedAtDesc(teamId);
        return matchingMapper.toNgTeamResponseList(entities);
    }

    /**
     * NGチームを追加する。
     */
    @Transactional
    public NgTeamResponse addNgTeam(Long teamId, CreateNgTeamRequest request) {
        // 自チームチェック
        if (teamId.equals(request.getBlockedTeamId())) {
            throw new BusinessException(MatchingErrorCode.SELF_NG_NOT_ALLOWED);
        }

        // 重複チェック
        if (ngTeamRepository.existsByTeamIdAndBlockedTeamId(teamId, request.getBlockedTeamId())) {
            throw new BusinessException(MatchingErrorCode.DUPLICATE_NG_TEAM);
        }

        NgTeamEntity entity = NgTeamEntity.builder()
                .teamId(teamId)
                .blockedTeamId(request.getBlockedTeamId())
                .reason(request.getReason())
                .build();

        NgTeamEntity saved = ngTeamRepository.save(entity);
        log.info("NGチーム追加: teamId={}, blockedTeamId={}", teamId, request.getBlockedTeamId());
        return matchingMapper.toNgTeamResponse(saved);
    }

    /**
     * NGチームを解除する。
     */
    @Transactional
    public void removeNgTeam(Long teamId, Long blockedTeamId) {
        NgTeamEntity entity = ngTeamRepository.findByTeamIdAndBlockedTeamId(teamId, blockedTeamId)
                .orElseThrow(() -> new BusinessException(MatchingErrorCode.NG_TEAM_NOT_FOUND));

        ngTeamRepository.delete(entity);
        log.info("NGチーム解除: teamId={}, blockedTeamId={}", teamId, blockedTeamId);
    }
}
