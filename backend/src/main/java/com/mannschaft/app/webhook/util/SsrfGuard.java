package com.mannschaft.app.webhook.util;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.webhook.WebhookErrorCode;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;

/**
 * SSRF（Server-Side Request Forgery）対策ガード。
 * Outgoing Webhook URL の検証を行い、プライベートIPへのアクセスを防ぐ。
 */
@Component
public class SsrfGuard {

    /**
     * WebhookエンドポイントURLを検証する。
     * <ul>
     *   <li>HTTPS のみ許可</li>
     *   <li>プライベート・ループバックIPアドレスへのアクセスを拒否</li>
     * </ul>
     *
     * @param url 検証対象のURL文字列
     * @throws BusinessException HTTPS以外またはプライベートIPの場合
     * @throws UnknownHostException ホスト名が解決できない場合（スロースルー）
     */
    public void validate(String url) throws UnknownHostException {
        // HTTPSのみ許可（URLパースエラーはWEBHOOK_002として扱う）
        URL parsed;
        try {
            parsed = new URL(url);
        } catch (MalformedURLException e) {
            throw new BusinessException(WebhookErrorCode.WEBHOOK_002);
        }

        if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
            // HTTP等のHTTPS以外は拒否
            throw new BusinessException(WebhookErrorCode.WEBHOOK_002);
        }

        String host = parsed.getHost();

        // localhostは直接ブロック
        if ("localhost".equalsIgnoreCase(host)) {
            throw new BusinessException(WebhookErrorCode.WEBHOOK_003);
        }

        // IPアドレス解決してプライベートIP・ループバックチェック
        InetAddress addr = InetAddress.getByName(host);
        if (isPrivateOrLoopback(addr)) {
            throw new BusinessException(WebhookErrorCode.WEBHOOK_003);
        }
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * プライベートIPアドレスまたはループバックアドレスかどうかを判定する。
     * 対象: 10.x.x.x, 172.16-31.x.x, 192.168.x.x, 127.x.x.x, ::1
     *
     * @param addr 検証対象のInetAddress
     * @return プライベート/ループバックの場合は true
     */
    private boolean isPrivateOrLoopback(InetAddress addr) {
        // java.net の標準メソッドによるループバック・サイトローカル判定
        if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
            return true;
        }

        // 追加チェック: 172.16.0.0/12 (172.16.x.x ～ 172.31.x.x)
        // isSiteLocalAddress は 172.16/12 を含む場合もあるが、明示的にチェック
        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            // IPv4の場合
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;

            // 10.0.0.0/8
            if (first == 10) {
                return true;
            }
            // 172.16.0.0/12
            if (first == 172 && second >= 16 && second <= 31) {
                return true;
            }
            // 192.168.0.0/16
            if (first == 192 && second == 168) {
                return true;
            }
            // 127.0.0.0/8 (isLoopbackAddressでもカバーされるが念のため)
            if (first == 127) {
                return true;
            }
        } else if (raw.length == 16) {
            // IPv6の ::1 はisLoopbackAddressでカバー済み
            // IPv4マップドアドレス (::ffff:10.x.x.x等) のチェック
            if (isIpv4MappedPrivate(raw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * IPv4マップドIPv6アドレスのプライベートIPチェック。
     * (::ffff:0:0/96 形式の内部アドレスを検出する)
     */
    private boolean isIpv4MappedPrivate(byte[] raw) {
        // IPv4マップドアドレス: 先頭10バイトが0、続く2バイトが0xFF
        boolean isMapped = true;
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                isMapped = false;
                break;
            }
        }
        if (!isMapped || (raw[10] & 0xFF) != 0xFF || (raw[11] & 0xFF) != 0xFF) {
            return false;
        }
        // 後半4バイトをIPv4アドレスとして評価
        int first = raw[12] & 0xFF;
        int second = raw[13] & 0xFF;
        if (first == 10) return true;
        if (first == 172 && second >= 16 && second <= 31) return true;
        if (first == 192 && second == 168) return true;
        if (first == 127) return true;
        return false;
    }
}
