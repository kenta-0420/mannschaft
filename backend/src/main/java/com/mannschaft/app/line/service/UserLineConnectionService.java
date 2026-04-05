package com.mannschaft.app.line.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.line.LineErrorCode;
import com.mannschaft.app.line.LineMapper;
import com.mannschaft.app.line.dto.LinkLineRequest;
import com.mannschaft.app.line.dto.UserLineStatusResponse;
import com.mannschaft.app.line.entity.UserLineConnectionEntity;
import com.mannschaft.app.line.repository.UserLineConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * ユーザーLINE連携サービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserLineConnectionService {

    private final UserLineConnectionRepository userLineConnectionRepository;
    private final LineMapper lineMapper;

    /**
     * LINE連携状態を取得する。
     */
    public UserLineStatusResponse getStatus(Long userId) {
        Optional<UserLineConnectionEntity> connection =
                userLineConnectionRepository.findByUserId(userId);

        if (connection.isEmpty()) {
            return new UserLineStatusResponse(false, null, null, null, null, null, null);
        }

        return lineMapper.toUserLineStatusResponse(connection.get());
    }

    /**
     * LINEアカウントをリンクする。
     */
    @Transactional
    public UserLineStatusResponse link(Long userId, LinkLineRequest request) {
        if (userLineConnectionRepository.existsByUserId(userId)) {
            throw new BusinessException(LineErrorCode.LINE_006);
        }
        if (userLineConnectionRepository.existsByLineUserId(request.getLineUserId())) {
            throw new BusinessException(LineErrorCode.LINE_006);
        }

        UserLineConnectionEntity entity = UserLineConnectionEntity.builder()
                .userId(userId)
                .lineUserId(request.getLineUserId())
                .displayName(request.getDisplayName())
                .pictureUrl(request.getPictureUrl())
                .statusMessage(request.getStatusMessage())
                .linkedAt(LocalDateTime.now())
                .build();

        UserLineConnectionEntity saved = userLineConnectionRepository.save(entity);
        return lineMapper.toUserLineStatusResponse(saved);
    }

    /**
     * LINEアカウントリンクを解除する。
     */
    @Transactional
    public void unlink(Long userId) {
        if (!userLineConnectionRepository.existsByUserId(userId)) {
            throw new BusinessException(LineErrorCode.LINE_005);
        }
        userLineConnectionRepository.deleteByUserId(userId);
    }
}
