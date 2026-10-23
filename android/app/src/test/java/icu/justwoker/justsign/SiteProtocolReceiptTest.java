package icu.justwoker.justsign;

import org.json.JSONObject;
import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 签到回执必须代表奖励到账，不能把普通登录时间当成回执。 */
public class SiteProtocolReceiptTest {

    private static long utc(String value) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(value).getTime();
    }

    private static JSONObject account(String date, long time, String source) throws Exception {
        JSONObject receipt = new JSONObject().put("date", date).put("time", time);
        if (source != null) receipt.put("source", source);
        return new JSONObject().put("lastCheckin", receipt);
    }

    @Test public void lastLoginAloneDoesNotCountAsReceipt() throws Exception {
        long now = utc("2026-10-21 12:00:00");
        long epoch = SiteProtocol.rewardEpochMs(now);
        JSONObject account = new JSONObject().put("lastLogin", epoch + 1);
        assertFalse(SiteProtocol.hasSignInReceipt(account, now));
    }

    @Test public void explicitAndLegacyReceiptsCount() throws Exception {
        long now = utc("2026-10-21 12:00:00");
        String date = SiteProtocol.rewardDate(now);
        long time = SiteProtocol.rewardEpochMs(now) + 1;
        assertTrue(SiteProtocol.hasSignInReceipt(account(date, time, "api_sign_in"), now));
        assertTrue(SiteProtocol.hasSignInReceipt(account(date, time, "relogin"), now));
        assertTrue(SiteProtocol.hasSignInReceipt(account(date, time, null), now));
    }

    @Test public void receiptMustBeCurrentAndNotFuture() throws Exception {
        long now = utc("2026-10-21 12:00:00");
        String date = SiteProtocol.rewardDate(now);
        long epoch = SiteProtocol.rewardEpochMs(now);
        assertTrue(SiteProtocol.hasSignInReceipt(account(date, epoch, "relogin"), now));
        assertFalse(SiteProtocol.hasSignInReceipt(account(date, now + 1, "relogin"), now));
        assertFalse(SiteProtocol.hasSignInReceipt(account(
                SiteProtocol.rewardDate(epoch - 1), epoch - 1, "relogin"), now));
        assertFalse(SiteProtocol.hasSignInReceipt(account(date, epoch + 1, "manual"), now));
    }
}
