package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.repository.BirthProfileCommandRepository;
import com.mannschaft.app.auth.repository.BirthProfileConfirmationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 最終削除時にauth自身の確認証跡と成功命令を消す。ACTIVE限定Guardへは入らない。 */
@Service
@RequiredArgsConstructor
public class BirthProfilePurgeService {
    private final BirthProfileCommandRepository commands;
    private final BirthProfileConfirmationRepository confirmations;

    @Transactional(readOnly=false,propagation=Propagation.REQUIRES_NEW)
    public void purgeUser(Long userId) {
        deleteOwnedHistory(userId);
    }

    /** 既存auth完全削除TXに参加し、auth完了を独立listenerと競争させない。 */
    @Transactional(readOnly=false,propagation=Propagation.MANDATORY)
    public void purgeInAuthTransaction(Long userId) {
        deleteOwnedHistory(userId);
    }

    private void deleteOwnedHistory(Long userId) {
        if(userId==null || userId<=0)throw new IllegalArgumentException("削除対象の本人IDが不正です");
        commands.deleteByUserId(userId);
        confirmations.deleteByUserId(userId);
    }
}
