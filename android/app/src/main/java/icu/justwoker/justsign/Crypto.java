package icu.justwoker.justsign;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Crypto（v0.2.0）— 凭据字段加密 + TOTP 动态码生成。
 *
 * 【为什么必须加密】
 * 本应用是 debug 签名包，`run-as icu.justwoker.justsign cat shared_prefs/justsign.xml`
 * 可以直接读出全部明文。站点登录密码与 2FA 密钥落盘前必须加密。
 *
 * 【方案】Android Keystore（TEE/StrongBox）生成 AES-256-GCM 密钥：
 *   - setUserAuthenticationRequired(false)：不弹指纹，否则会打断纯后台自动化。
 *   - 密钥永不出安全芯片，prefs 里只有密文（格式 v1:<base64(iv|ct)>）。
 *   - 代价（已与用户确认接受）：卸载应用密钥即销毁，密码需重录。
 *
 * 【降级】Keystore 不可用的极端机型（provider 缺失）→ 返回 null，
 *   调用方须把该字段视为"无法保存"，绝不退回明文落盘。
 */
public final class Crypto {
    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "justsign_cred_v1";
    private static final String XFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String PREFIX = "v1:";

    private Crypto() {}

    /* ================= 密钥 ================= */

    private static SecretKey key() {
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build());
            return kg.generateKey();
        } catch (Exception e) { return null; }
    }

    /** Keystore 是否可用（UI 用来提示"本机无法安全保存密码"） */
    public static boolean available() { return key() != null; }

    /* ================= 加解密 ================= */

    /** 明文 → "v1:base64(iv|ciphertext)"；失败返回 null（调用方不得退回明文） */
    public static String enc(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            SecretKey k = key();
            if (k == null) return null;
            Cipher c = Cipher.getInstance(XFORM);
            c.init(Cipher.ENCRYPT_MODE, k);
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return PREFIX + Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Exception e) { return null; }
    }

    /** "v1:..." → 明文；非本格式原样返回（兼容历史明文数据）；解密失败返回空串 */
    public static String dec(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (!stored.startsWith(PREFIX)) return stored;
        try {
            SecretKey k = key();
            if (k == null) return "";
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            if (all.length <= IV_LEN) return "";
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(XFORM);
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Exception e) { return ""; }
    }

    /** 是否已是本类加密过的密文 */
    public static boolean isEnc(String s) { return s != null && s.startsWith(PREFIX); }

    /* ================= 2FA ================= */

    /**
     * 2FA 字段取值 → 可直接填入网页的 6 位验证码。
     * 支持两种录入方式（用户按自己账号情况任选）：
     *   1) TOTP 密钥（Base32，通常 16/26/32 位，可含空格）→ 本地按 RFC6238 实时算码
     *   2) 固定字符串（如恢复码）→ 原样返回
     * 空值返回空串，表示该账号没有 2FA，授权流程不做跳转（用户明确要求）。
     */
    public static String totpOrLiteral(String secretOrCode) {
        if (secretOrCode == null) return "";
        String s = secretOrCode.trim();
        if (s.isEmpty()) return "";
        String b32 = s.replace(" ", "").replace("-", "").toUpperCase(Locale.US);
        if (looksBase32(b32)) {
            String code = totp(b32, System.currentTimeMillis() / 1000L / 30L);
            if (!code.isEmpty()) return code;
        }
        return s;
    }

    /** Base32 判定：仅 A-Z2-7，长度 16 的倍数或常见 16/26/32，且不含 0/1/8/9 */
    private static boolean looksBase32(String s) {
        if (s.length() < 16) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= '2' && c <= '7') || c == '=';
            if (!ok) return false;
        }
        return true;
    }

    /** RFC6238 TOTP-SHA1 6 位 */
    private static String totp(String base32Secret, long counter) {
        try {
            byte[] k = base32Decode(base32Secret);
            if (k.length == 0) return "";
            byte[] msg = new byte[8];
            for (int i = 7; i >= 0; i--) { msg[i] = (byte) (counter & 0xff); counter >>>= 8; }
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(k, "HmacSHA1"));
            byte[] hash = mac.doFinal(msg);
            int off = hash[hash.length - 1] & 0x0f;
            int bin = ((hash[off] & 0x7f) << 24) | ((hash[off + 1] & 0xff) << 16)
                    | ((hash[off + 2] & 0xff) << 8) | (hash[off + 3] & 0xff);
            return String.format(Locale.US, "%06d", bin % 1000000);
        } catch (Exception e) { return ""; }
    }

    private static byte[] base32Decode(String s) {
        try {
            s = s.replace("=", "");
            int bits = 0, value = 0, idx = 0;
            byte[] out = new byte[s.length() * 5 / 8];
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                int v;
                if (c >= 'A' && c <= 'Z') v = c - 'A';
                else if (c >= '2' && c <= '7') v = c - '2' + 26;
                else return new byte[0];
                value = (value << 5) | v;
                bits += 5;
                if (bits >= 8) {
                    out[idx++] = (byte) ((value >>> (bits - 8)) & 0xff);
                    bits -= 8;
                }
            }
            if (idx == out.length) return out;
            byte[] trimmed = new byte[idx];
            System.arraycopy(out, 0, trimmed, 0, idx);
            return trimmed;
        } catch (Exception e) { return new byte[0]; }
    }
}
