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
