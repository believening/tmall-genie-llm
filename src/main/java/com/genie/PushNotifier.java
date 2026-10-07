package com.genie;

import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

/**
 * 天猫精灵「消息推送」—— 让音箱主动播报（异步任务完成后用）。
 *
 * 协议（与 genie_push.py 一致，来源 CoderXGC/aligenie-demo 的官方 demo）：
 *   PUT https://openapi.aligenie.com/v1.0/iap/notifications
 *   ACS3-HMAC-SHA256 签名（签名头：x-acs-* + host + content-type）
 *   密钥是 iap.aligenie.com 技能应用平台里"应用的 AccessKey/Secret"。
 *
 * 环境变量（全部配齐才启用，缺任何一个 push 都视为未配置）：
 *   GENIE_PUSH_ACCESS_KEY    应用 AccessKey
 *   GENIE_PUSH_ACCESS_SECRET 应用 AccessSecret
 *   GENIE_PUSH_TEMPLATE_ID   审核通过的消息模板ID
 *   GENIE_PUSH_SKILL_ID      技能ID（请求里的 EncodeKey）
 *   GENIE_PUSH_ORG_ID        OrganizationId
 *   GENIE_PUSH_TARGET_ID     推送目标ID值（如 USER_OPEN_ID 对应的 openId）
 *   GENIE_PUSH_TARGET_TYPE   默认 USER_OPEN_ID（可选 USER_UNION_ID/DEVICE_OPEN_ID/DEVICE_UNION_ID）
 *   GENIE_PUSH_PLACEHOLDER   模板占位符名，默认 content（可选）
 *   GENIE_PUSH_IS_DEBUG      默认 true（技能未正式发布前必须 true，可选）
 */
public final class PushNotifier {

    private static final Logger log = LoggerFactory.getLogger(PushNotifier.class);

    private static final String HOST = "openapi.aligenie.com";
    private static final String PATH = "/v1.0/iap/notifications";

    private PushNotifier() {
    }

    /** 推送所需环境变量是否配齐 */
    public static boolean configured() {
        return !env("GENIE_PUSH_ACCESS_KEY").isEmpty()
                && !env("GENIE_PUSH_ACCESS_SECRET").isEmpty()
                && !env("GENIE_PUSH_TEMPLATE_ID").isEmpty()
                && !env("GENIE_PUSH_SKILL_ID").isEmpty()
                && !env("GENIE_PUSH_ORG_ID").isEmpty()
                && !env("GENIE_PUSH_TARGET_ID").isEmpty();
    }

    /** 推送一条播报；成功返回 null，失败返回错误描述 */
    public static String push(String text) {
        if (!configured()) {
            return "push not configured";
        }
        try {
            JSONObject unicast = new JSONObject();
            JSONObject placeholder = new JSONObject();
            placeholder.put(envOr("GENIE_PUSH_PLACEHOLDER", "content"), text);
            unicast.put("PlaceHolder", placeholder);
            unicast.put("MessageTemplateId", env("GENIE_PUSH_TEMPLATE_ID"));
            unicast.put("EncodeType", "SKILL_ID");
            unicast.put("EncodeKey", env("GENIE_PUSH_SKILL_ID"));
            JSONObject target = new JSONObject();
            target.put("TargetIdentity", env("GENIE_PUSH_TARGET_ID"));
            target.put("TargetType", envOr("GENIE_PUSH_TARGET_TYPE", "USER_OPEN_ID"));
            unicast.put("SendTarget", target);
            unicast.put("OrganizationId", env("GENIE_PUSH_ORG_ID"));
            unicast.put("IsDebug", !"false".equals(env("GENIE_PUSH_IS_DEBUG")));

            JSONObject body = new JSONObject();
            body.put("NotificationUnicastRequest", unicast);
            body.put("TenantInfo", new JSONObject());
            byte[] payload = body.toJSONString().getBytes(StandardCharsets.UTF_8);

            // ---- ACS3-HMAC-SHA256 签名（参与签名：x-acs-* + host + content-type）----
            java.util.Map<String, String> headers = new java.util.TreeMap<>();
            headers.put("content-type", "application/json");
            headers.put("host", HOST);
            headers.put("x-acs-action", "PushNotifications");
            headers.put("x-acs-version", "iap_1.0");
            headers.put("x-acs-date",
                    Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
            headers.put("x-acs-signature-nonce", UUID.randomUUID().toString());

            StringBuilder canonicalHeaders = new StringBuilder();
            StringBuilder signedNames = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                canonicalHeaders.append(e.getKey()).append(':')
                        .append(e.getValue()).append('\n');
                if (signedNames.length() > 0) {
                    signedNames.append(';');
                }
                signedNames.append(e.getKey());
            }
            String canonicalRequest = "PUT\n" + PATH + "\n\n"
                    + canonicalHeaders + "\n" + signedNames + "\n"
                    + sha256Hex(payload);
            String stringToSign = "ACS3-HMAC-SHA256\n" + sha256Hex(
                    canonicalRequest.getBytes(StandardCharsets.UTF_8));
            String signature = hmacSha256Hex(
                    env("GENIE_PUSH_ACCESS_SECRET"), stringToSign);
            headers.put("authorization", "ACS3-HMAC-SHA256 Credential="
                    + env("GENIE_PUSH_ACCESS_KEY")
                    + ", SignedHeaders=" + signedNames
                    + ", Signature=" + signature);

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://" + HOST + PATH).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                if (!"host".equals(e.getKey())) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            String resp;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8))) {
                resp = reader.lines().reduce("", (a, b) -> a + b);
            }
            if (code != 200) {
                log.error("[PUSH ERR] HTTP {} {}", code, resp);
                return "HTTP " + code + ": " + resp;
            }
            log.info("[PUSH OK] {}", resp);
            return null;
        } catch (Exception e) {
            log.error("[PUSH EX] {}", e.toString());
            return e.toString();
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256")
                .digest(data)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String hmacSha256Hex(String secret, String message)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte b : mac.doFinal(message.getBytes(StandardCharsets.UTF_8))) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String env(String name) {
        return envOr(name, "");
    }

    private static String envOr(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? def : v;
    }
}
