package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.dto.MuteResponse;
import com.mannschaft.app.timeline.entity.UserMuteEntity;
import com.mannschaft.app.timeline.repository.UserMuteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * タイムラインミュートサービス。ユーザー・チーム等のミュート追加・解除・一覧取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelineMuteService {

    /** 1 ユーザーあたりのミュート件数上限。 */
    private static final long MAX_MUTES_PER_USER = 200L;

    private final UserMuteRepository muteRepository;
    private final TimelineMapper timelineMapper;

    /**
     * ミュートを追加する。
     *
     * @param mutedType ミュート対象種別
     * @param mutedId   ミュート対象ID
     * @param userId    ユーザーID
     * @return 作成されたミュート
     */
    @Transactional
    public MuteResponse addMute(String mutedType, Long mutedId, Long userId) {
        if (muteRepository.existsByUserIdAndMutedTypeAndMutedId(userId, mutedType, mutedId)) {
            throw new BusinessException(TimelineErrorCode.MUTE_ALREADY_EXISTS);
        }
        // ミュートはフィードクエリの NOT IN に展開されるため、無制限に増えるとクエリが肥大する。
        // 重複判定の後に置くことで、既存ミュートの再登録が上限エラーに化けないようにしている。
        if (muteRepository.countByUserId(userId) >= MAX_MUTES_PER_USER) {
            throw new BusinessException(TimelineErrorCode.MAX_MUTES_EXCEEDED);
        }

        UserMuteEntity mute = UserMuteEntity.builder()
                .userId(userId)
                .mutedType(mutedType)
                .mutedId(mutedId)
                .build();
        mute = muteRepository.save(mute);

        log.info("ミュート追加: mutedType={}, mutedId={}, userId={}", mutedType, mutedId, userId);
        return timelineMapper.toMuteResponse(mute);
    }

    /**
     * ミュートを解除する。
     *
     * @param mutedType ミュート対象種別
     * @param mutedId   ミュート対象ID
     * @param userId    ユーザーID
     */
    @Transactional
    public void removeMute(String mutedType, Long mutedId, Long userId) {
        UserMuteEntity mute = muteRepository.findByUserIdAndMutedTypeAndMutedId(userId, mutedType, mutedId)
                .orElseThrow(() -> new BusinessException(TimelineErrorCode.MUTE_NOT_FOUND));

        muteRepository.delete(mute);

        log.info("ミュート解除: mutedType={}, mutedId={}, userId={}", mutedType, mutedId, userId);
    }

    /**
     * ユーザーのミュート一覧を取得する。
     *
     * @param userId ユーザーID
     * @return ミュート一覧
     */
    public List<MuteResponse> getMutes(Long userId) {
        return timelineMapper.toMuteResponseList(muteRepository.findByUserId(userId));
    }
}
