package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.dto.WebAuthnLoginCompleteRequest;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;

/**
 * 実際のES256鍵でWebAuthn assertionを生成する試験用認証器。
 * 検証器をモックせず、署名対象のrpId・UP/UV・カウンタ・clientDataを独立に変更する。
 */
final class PasskeyAssertionFixture {

    private final KeyPair keyPair;
    private final String credentialId;

    PasskeyAssertionFixture() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
        credentialId = encode(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }

    String credentialId() {
        return credentialId;
    }

    String publicKey() {
        return encode(new ObjectConverter().getCborConverter().writeValueAsBytes(
                EC2COSEKey.create((ECPublicKey) keyPair.getPublic(), COSEAlgorithmIdentifier.ES256)));
    }

    WebAuthnLoginCompleteRequest assertion(String challenge, String origin, String rpId,
                                           int flags, long signedCount, long reportedCount) throws Exception {
        byte[] clientData = ("{\"type\":\"webauthn.get\",\"challenge\":\"" + challenge
                + "\",\"origin\":\"" + origin + "\",\"crossOrigin\":false}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] authenticatorData = ByteBuffer.allocate(37)
                .put(sha256(rpId.getBytes(StandardCharsets.UTF_8)))
                .put((byte) flags)
                .putInt((int) signedCount)
                .array();
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(authenticatorData);
        signer.update(sha256(clientData));
        return new WebAuthnLoginCompleteRequest(credentialId, encode(authenticatorData),
                encode(clientData), encode(signer.sign()), reportedCount);
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String encode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
