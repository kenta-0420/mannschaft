package com.mannschaft.app.diagnosis.service;

import com.mannschaft.app.diagnosis.repository.DiagnosisCommandRepository;
import com.mannschaft.app.diagnosis.repository.DiagnosisResultRepository;
import com.mannschaft.app.diagnosis.repository.DiagnosisSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 最終削除通知と再試行が共用する診断自身の削除口。申請時・休止時には呼ばない。 */
@Service
@RequiredArgsConstructor
public class DiagnosisPurgeService {
    private final DiagnosisCommandRepository commands;
    private final DiagnosisSessionRepository sessions;
    private final DiagnosisResultRepository results;

    @Transactional(readOnly=false,propagation=Propagation.REQUIRES_NEW)
    public void purgeUser(Long userId) {
        if(userId==null || userId<=0)throw new IllegalArgumentException("削除対象の本人IDが不正です");
        // 冪等に本人行だけを消す。返却Entityや他domain Repositoryを公開しない。
        commands.deleteByUserId(userId);
        sessions.deleteByUserId(userId);
        results.deleteByUserId(userId);
    }
}
