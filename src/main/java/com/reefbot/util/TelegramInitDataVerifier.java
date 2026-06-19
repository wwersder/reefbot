package com.reefbot.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Verifies Telegram WebApp initData using HMAC-SHA256.
 *
 * <p>Algorithm (per Telegram docs):
 * <ol>
 *   <li>Parse key=value pairs from URL-encoded initData</li>
 *   <li>Extract {@code hash} field</li>
 *   <li>Sort remaining pairs by key alphabetically</li>
 *   <li>Join as {@code key=value\n}</li>
 *   <li>Compute secret = HMAC_SHA256(key="WebAppData", data=botToken)</li>
 *   <li>Compare HMAC_SHA256(key=secret, data=dataCheckString) with extracted hash</li>
 * </ol>
 */
public class TelegramInitDataVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final long MAX_AGE_SECONDS = 86_400; // 24 hours

    private TelegramInitDataVerifier() {}

    /**
     * Verifies initData and returns parsed fields (excluding "hash").
     *
     * @throws SecurityException if verification fails or data is too old
     */
    public static Map<String, String> verify(String rawInitData, String botToken) {
        Map<String, String> params = parseParams(rawInitData);
        String receivedHash = params.remove("hash");
        if (receivedHash == null) {
            throw new SecurityException("Missing hash in initData");
        }

        // Build data-check string
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            entries.add(e.getKey() + "=" + e.getValue());
        }
        Collections.sort(entries);
        String dataCheckString = String.join("\n", entries);

        // Compute expected hash
        try {
            byte[] secretKey = hmac(botToken, "WebAppData".getBytes(StandardCharsets.UTF_8));
            byte[] expectedHashBytes = hmac(dataCheckString, secretKey);
            String expectedHash = bytesToHex(expectedHashBytes);

            if (!expectedHash.equalsIgnoreCase(receivedHash)) {
                throw new SecurityException("initData signature mismatch");
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("HMAC computation failed: " + e.getMessage(), e);
        }

        // Check freshness
        String authDate = params.get("auth_date");
        if (authDate != null) {
            long ts = Long.parseLong(authDate);
            long age = System.currentTimeMillis() / 1000 - ts;
            if (age > MAX_AGE_SECONDS) {
                throw new SecurityException("initData expired (age=" + age + "s)");
            }
        }

        return params;
    }

    /**
     * Extracts the Telegram user ID from already-verified initData params.
     * The "user" field is a JSON object like {"id":12345,...}.
     */
    public static Long extractTelegramId(Map<String, String> params) {
        String userJson = params.get("user");
        if (userJson == null) throw new SecurityException("No user field in initData");
        // Quick JSON extraction without a full parser
        int idIdx = userJson.indexOf("\"id\":");
        if (idIdx < 0) throw new SecurityException("No id in user JSON");
        int start = idIdx + 5;
        // skip whitespace
        while (start < userJson.length() && userJson.charAt(start) == ' ') start++;
        int end = start;
        while (end < userJson.length() && Character.isDigit(userJson.charAt(end))) end++;
        return Long.parseLong(userJson.substring(start, end));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Map<String, String> parseParams(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String key   = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            map.put(key, value);
        }
        return map;
    }

    private static byte[] hmac(String data, byte[] key) throws Exception {
        return hmac(data.getBytes(StandardCharsets.UTF_8), key);
    }

    private static byte[] hmac(byte[] data, byte[] key) throws Exception {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(key, HMAC_SHA256));
        return mac.doFinal(data);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
