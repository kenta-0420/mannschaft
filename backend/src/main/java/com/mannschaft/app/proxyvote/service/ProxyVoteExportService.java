package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.entity.ProxyVoteEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * エクスポートサービス。CSV・PDF エクスポートを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProxyVoteExportService {

    private final ProxyVoteSessionService sessionService;
    private final ProxyVoteMotionRepository motionRepository;
    private final ProxyVoteRepository voteRepository;
    private final PdfGeneratorService pdfGeneratorService;

    /**
     * 投票結果を CSV でエクスポートする。
     */
    public byte[] exportResultsCsv(Long sessionId) {
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(sessionId);
        if (session.getStatus() != SessionStatus.CLOSED && session.getStatus() != SessionStatus.FINALIZED) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_CLOSED_OR_FINALIZED);
        }

        List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(sessionId);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // BOM を付与（Excel 対応）
        try {
            baos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        } catch (Exception e) {
            // ignore
        }

        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            if (session.getIsAnonymous()) {
                // 無記名: 集計のみ
                writer.println("motion_number,title,approve_count,reject_count,abstain_count,result");
                for (ProxyVoteMotionEntity m : motions) {
                    writer.printf("%d,\"%s\",%d,%d,%d,%s%n",
                            m.getMotionNumber(), escapeCsv(m.getTitle()),
                            m.getApproveCount(), m.getRejectCount(), m.getAbstainCount(),
                            m.getResult() != null ? m.getResult().name() : "");
                }
            } else {
                // 記名: 議案別集計 + メンバー別詳細
                writer.println("motion_number,title,approve_count,reject_count,abstain_count,result");
                for (ProxyVoteMotionEntity m : motions) {
                    writer.printf("%d,\"%s\",%d,%d,%d,%s%n",
                            m.getMotionNumber(), escapeCsv(m.getTitle()),
                            m.getApproveCount(), m.getRejectCount(), m.getAbstainCount(),
                            m.getResult() != null ? m.getResult().name() : "");
                }

                // メンバー別詳細行
                writer.println();
                writer.println("motion_number,user_id,vote_type,is_proxy_vote,voted_at");
                for (ProxyVoteMotionEntity m : motions) {
                    List<ProxyVoteEntity> votes = voteRepository.findByMotionId(m.getId());
                    for (ProxyVoteEntity v : votes) {
                        writer.printf("%d,%d,%s,%s,%s%n",
                                m.getMotionNumber(),
                                v.getUserId(),
                                v.getVoteType().name(),
                                v.getIsProxyVote() ? "委任" : "本人",
                                v.getVotedAt());
                    }
                }
            }
        }

        log.info("CSV エクスポート: sessionId={}", sessionId);
        return baos.toByteArray();
    }

    /**
     * 議事録 PDF をエクスポートする。
     */
    public byte[] exportMinutesPdf(Long sessionId) {
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(sessionId);
        if (session.getStatus() != SessionStatus.FINALIZED) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_FINALIZED);
        }

        List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(sessionId);

        Map<String, Object> variables = new HashMap<>();
        variables.put("session", session);
        variables.put("title", "議事録");
        variables.put("agendas", motions);

        log.info("議事録 PDF エクスポート: sessionId={}", sessionId);
        return pdfGeneratorService.generateFromTemplate("pdf/proxy-vote-minutes", variables);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        return value.replace("\"", "\"\"");
    }
}
