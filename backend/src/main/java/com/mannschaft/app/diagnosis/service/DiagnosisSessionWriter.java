package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.diagnosis.DiagnosisAxis;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.DiagnosisScoringService;
import com.mannschaft.app.diagnosis.DiagnosisStatus;
import com.mannschaft.app.diagnosis.dto.DiagnosisAnswer;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.diagnosis.dto.DiagnosisScore;
import com.mannschaft.app.diagnosis.dto.DiagnosisSessionResponse;
import com.mannschaft.app.diagnosis.dto.DiagnosisTieAnswer;
import com.mannschaft.app.diagnosis.dto.DiagnosisTieQuestion;
import com.mannschaft.app.diagnosis.entity.DiagnosisCommandEntity;
import com.mannschaft.app.diagnosis.entity.DiagnosisResultEntity;
import com.mannschaft.app.diagnosis.entity.DiagnosisSessionEntity;
import com.mannschaft.app.diagnosis.repository.DiagnosisCommandRepository;
import com.mannschaft.app.diagnosis.repository.DiagnosisResultRepository;
import com.mannschaft.app.diagnosis.repository.DiagnosisSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** ACTIVE authロックのcallback内で、診断自身の独立TXだけを使用する。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = false, propagation = Propagation.REQUIRES_NEW)
public class DiagnosisSessionWriter {
    private final DiagnosisSessionRepository sessions;
    private final DiagnosisCommandRepository commands;
    private final DiagnosisResultRepository results;
    private final DiagnosisScoringService scoring;
    private final DiagnosisQuestionnaireCatalog catalog;
    private final DiagnosisSessionSnapshotCodec codec;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DiagnosisSessionResponse start(Long userId, UUID key) {
        String hash = hash(List.of("session:start:v1"));
        var replay = replay(userId,key,hash); if (replay.isPresent()) return replay.get();
        var definition = catalog.forStart(); Instant now=now();
        var session = DiagnosisSessionEntity.builder().id(UuidV7.generate()).userId(userId)
                .status(DiagnosisStatus.STARTED).questionnaireVersion(definition.questionnaireVersion())
                .scoringVersion(definition.scoringVersion()).questionsSnapshot(codec.encodeDefinition(definition))
                .answersSnapshot(codec.encodeAnswers(new DiagnosisSessionSnapshotCodec.Answers(List.of(),Map.of())))
                .answerRevision(0).createdAt(now).updatedAt(now).build();
        sessions.saveAndFlush(session); return save(userId,key,hash,response(session));
    }
    public DiagnosisSessionResponse read(Long userId, UUID id) {
        return response(sessions.findByIdAndUserId(id,userId).orElseThrow(DiagnosisSessionWriter::notFound));
    }
    public DiagnosisSessionResponse answer(Long userId, UUID id, UUID key, long version, List<DiagnosisAnswer> submitted) {
        if (submitted == null || submitted.isEmpty() || submitted.size() > 24) throw invalid();
        String hash=hash(List.of("session:answers:v1",id,version,submitted));
        var replay=replay(userId,key,hash); if(replay.isPresent())return replay.get();
        var session=mutable(userId,id,version); var definition=codec.definition(session.getQuestionsSnapshot());
        Set<String> known=new HashSet<>(); definition.questions().forEach(q->known.add(q.id()));
        Map<String,Integer> merged=new LinkedHashMap<>();
        codec.answers(session.getAnswersSnapshot()).answers().forEach(a->merged.put(a.questionId(),a.value()));
        Set<String> seen=new HashSet<>();
        for(var answer:submitted) {
            if(answer==null || !known.contains(answer.questionId()) || !seen.add(answer.questionId()) || answer.value()<1 || answer.value()>5)throw invalid();
            merged.put(answer.questionId(),answer.value());
        }
        var ordered=definition.questions().stream().filter(q->merged.containsKey(q.id()))
                .map(q->new DiagnosisAnswer(q.id(),merged.get(q.id()))).toList();
        session.updateAnswers(codec.encodeAnswers(new DiagnosisSessionSnapshotCodec.Answers(ordered,Map.of())),now());
        sessions.flush();return save(userId,key,hash,response(session));
    }
    public DiagnosisSessionResponse complete(Long userId, UUID id, UUID key, long version, long answerRevision, List<DiagnosisTieAnswer> submitted) {
        if (submitted == null || submitted.size() > 6) throw invalid();
        String hash=hash(List.of("session:complete:v1",id,version,answerRevision,submitted));
        var replay=replay(userId,key,hash);if(replay.isPresent())return replay.get();
        var session=mutable(userId,id,version);
        if(session.getAnswerRevision()!=answerRevision)throw conflict();
        var definition=codec.definition(session.getQuestionsSnapshot());var saved=codec.answers(session.getAnswersSnapshot());
        var answers=new HashMap<String,Integer>();saved.answers().forEach(a->answers.put(a.questionId(),a.value()));
        var ties=new EnumMap<DiagnosisAxis,Integer>(DiagnosisAxis.class);ties.putAll(saved.tieAnswers());
        var seen=new HashSet<DiagnosisAxis>();
        for(var answer:submitted) {
            if(answer==null || answer.axisId()==null || !seen.add(answer.axisId()) || answer.value()<0 || answer.value()>1)throw invalid();
            ties.put(answer.axisId(),answer.value());
        }
        DiagnosisScore score;
        try {score=scoring.score(definition.questions(),answers,ties);}catch(IllegalArgumentException error){throw invalid();}
        String encoded=codec.encodeAnswers(new DiagnosisSessionSnapshotCodec.Answers(saved.answers(),ties));Instant now=now();
        if(score.typeCode()==null)session.awaitTieBreak(encoded,now);
        else {
            UUID resultId=UuidV7.generate();
            var result=new DiagnosisResultSummary(resultId,DiagnosisMethod.DIAGNOSIS,now,"diagnosis-result-v1",
                    definition.questionnaireVersion(),definition.scoringVersion(),null,null,null,score.typeCode(),score.axes(),null,
                    definition.descriptionSnapshot(),definition.axisDescriptions(),
                    DiagnosisAxisSelectionSnapshot.create(score.typeCode(),score.axes(),definition.ties()));
            results.saveAndFlush(DiagnosisResultEntity.builder().id(resultId).userId(userId).method(DiagnosisMethod.DIAGNOSIS)
                    .sourceProfileRevision(null).summarySnapshot(encode(result)).completedAt(now).createdAt(now).updatedAt(now).build());
            session.complete(encoded,resultId,now);
        }
        sessions.flush();return save(userId,key,hash,response(session));
    }
    public DiagnosisSessionResponse cancel(Long userId, UUID id, UUID key, long version) {
        String hash=hash(List.of("session:cancel:v1",id,version));
        var replay=replay(userId,key,hash);if(replay.isPresent())return replay.get();
        var session=mutable(userId,id,version);session.cancel(now());sessions.flush();
        return save(userId,key,hash,response(session));
    }
    private DiagnosisSessionEntity mutable(Long userId,UUID id,long version) {
        var session=sessions.findOwnedForUpdate(id,userId).orElseThrow(DiagnosisSessionWriter::notFound);
        catalog.requireMutationAllowed(codec.definition(session.getQuestionsSnapshot()));
        if(version<0 || session.getVersion()!=version || session.getStatus()==DiagnosisStatus.COMPLETED || session.getStatus()==DiagnosisStatus.CANCELLED)throw conflict();
        return session;
    }
    private DiagnosisSessionResponse response(DiagnosisSessionEntity session) {
        var definition=codec.definition(session.getQuestionsSnapshot());
        catalog.requireSnapshotReadable(definition);
        var saved=codec.answers(session.getAnswersSnapshot());
        List<DiagnosisTieQuestion> pending=List.of();
        if(session.getStatus()==DiagnosisStatus.TIE_BREAK_REQUIRED) {
            var values=new HashMap<String,Integer>();saved.answers().forEach(a->values.put(a.questionId(),a.value()));
            DiagnosisScore score;
            try{score=scoring.score(definition.questions(),values,saved.tieAnswers());}catch(IllegalArgumentException error){throw unavailable();}
            pending=definition.ties().stream().filter(t->score.tiedAxes().contains(t.axisId()) && !saved.tieAnswers().containsKey(t.axisId())).toList();
        }
        return new DiagnosisSessionResponse(session.getId(),session.getStatus(),Long.toString(session.getVersion()),
                Long.toString(session.getAnswerRevision()),session.getQuestionnaireVersion(),session.getScoringVersion(),
                definition.questions(),saved.answers(),pending,session.getResultId());
    }
    private Optional<DiagnosisSessionResponse> replay(Long userId,UUID key,String hash) {
        if(key==null)throw invalid();var saved=commands.findByUserIdAndCommandId(userId,key);
        if(saved.isEmpty())return Optional.empty();
        if(!MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),saved.get().getRequestHash().getBytes(StandardCharsets.US_ASCII)))
            throw new BusinessException(DiagnosisErrorCode.COMMAND_CONFLICT);
        try{return Optional.of(mapper.readValue(saved.get().getResponseSnapshot(),DiagnosisSessionResponse.class));}
        catch(JsonProcessingException error){throw unavailable();}
    }
    private DiagnosisSessionResponse save(Long userId,UUID key,String hash,DiagnosisSessionResponse response) {
        Instant now=now();commands.saveAndFlush(DiagnosisCommandEntity.builder().id(UuidV7.generate()).userId(userId)
                .commandId(key).requestHash(hash).responseSnapshot(encode(response)).createdAt(now).updatedAt(now).build());return response;
    }
    private String encode(Object value) {
        try{return mapper.writeValueAsString(value);}catch(JsonProcessingException error){throw unavailable();}
    }
    private String hash(Object value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException error){throw new IllegalStateException("命令比較方式が使用できません");}
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}

    private static BusinessException invalid(){return new BusinessException(DiagnosisErrorCode.INVALID_INPUT);}
    private static BusinessException conflict(){return new BusinessException(DiagnosisErrorCode.STATE_CONFLICT);}
    private static BusinessException notFound(){return new BusinessException(DiagnosisErrorCode.NOT_FOUND);}
    private static BusinessException unavailable(){return new BusinessException(DiagnosisErrorCode.UNAVAILABLE);}
}
