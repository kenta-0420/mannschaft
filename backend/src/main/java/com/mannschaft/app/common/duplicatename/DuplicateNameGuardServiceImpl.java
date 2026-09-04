package com.mannschaft.app.common.duplicatename;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

/**
 * CMP-260901-1538 柱③-A: {@link DuplicateNameGuardService} の実装。
 *
 * <p><b>骨格段階（試練）</b>: 出陣（実装）フェーズで以下を実装する。</p>
 * <ul>
 *   <li>{@code candidateSupplier} を呼び候補が空なら即座に return（重複なし）</li>
 *   <li>候補がある場合、{@code confirmDuplicate=false} なら
 *       {@link DuplicateNameFingerprintService#issue} で fingerprint を発行し
 *       {@link DuplicateNameConfirmationRequiredException} を投げる</li>
 *   <li>{@code confirmDuplicate=true} なら {@link DuplicateNameFingerprintService#verify} で
 *       候補 ID 集合の完全一致を確認し、不一致なら新規 fingerprint を発行して再度
 *       {@link DuplicateNameConfirmationRequiredException} を投げる（確認後に新たな同名が出現したケース）</li>
 * </ul>
 *
 * <p>現時点では未実装のため {@link UnsupportedOperationException} を投げ、呼び出す試練テストを
 * red にする。</p>
 */
@Service
@RequiredArgsConstructor
public class DuplicateNameGuardServiceImpl implements DuplicateNameGuardService {

    private final DuplicateNameFingerprintService fingerprintService;

    @Override
    public void checkForCreate(DuplicateNameScopeKind scopeKind, String rawName, Long actorUserId,
            boolean confirmDuplicate, String suppliedFingerprint,
            Supplier<List<DuplicateNameCandidate>> candidateSupplier) {
        throw new UnsupportedOperationException(
                "DuplicateNameGuardServiceImpl#checkForCreate は柱③-A 出陣（実装）フェーズで実装予定");
    }
}
