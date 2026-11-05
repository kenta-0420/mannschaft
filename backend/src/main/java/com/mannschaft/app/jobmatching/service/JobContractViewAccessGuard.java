package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.policy.JobPolicy;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 求人契約の書込前に通す「閲覧可否の門」（CMP-260821-1027 存在オラクルの本文正規化）。
 *
 * <h2>保証する内容</h2>
 * <p>契約の当事者（Requester または Worker 本人。詳細取得 {@link JobContractService#findById} と同じ
 * {@link JobPolicy#isContractParticipant}）でない者には、不在の契約IDと同一の
 * {@link JobmatchingErrorCode#JOB_CONTRACT_NOT_FOUND}（404・同一本文）を返して実在を秘匿する。
 * 当事者が自分の権限外の操作（Requester の完了報告、Worker の承認・差戻し・QR 発行等）を行った場合は、
 * 従来どおり Service / Controller 側の {@code JOB_PERMISSION_DENIED}（404 固定の写像）で返す。</p>
 *
 * <p>取引を持たない部品として Controller から Service より先に呼ぶ（{@code JobPostingViewAccessGuard} と同じ型）。</p>
 */
@Component
@RequiredArgsConstructor
public class JobContractViewAccessGuard {

    private final JobContractRepository contractRepository;
    private final JobPolicy jobPolicy;

    /**
     * 呼出者が契約の当事者であることを確かめる。
     *
     * @param contractId 契約ID（パス由来）
     * @param userId     操作ユーザーID
     * @throws BusinessException 不在・当事者でない者（{@link JobmatchingErrorCode#JOB_CONTRACT_NOT_FOUND} / 404）
     */
    public void requireVisible(Long contractId, Long userId) {
        JobContractEntity contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_CONTRACT_NOT_FOUND));
        if (!jobPolicy.isContractParticipant(userId, contract)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_CONTRACT_NOT_FOUND);
        }
    }
}
