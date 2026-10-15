package icu.justwoker.justsign;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * v1.3.5：非 New API 站（fengwind 系）的额度读取映射。
 *
 * 为什么必须测：这类站的响应是 {code:0,message:"success",data:{...}}，
 * 与 New API 的 {success:true,data:{...}} 不同，金额字段名也各不相同。
 * 映射写错的表现是「卡片三个额度全空」——运行时才暴露，编译期完全看不出来。
 *
 * 直接调 Engine.mapForeignSelf（真实实现），不复制逻辑：复制出来的副本会在
 * 实现改动后依旧全绿，等于没测。
 *
 * 真机实证（三套子域 /api/user/self 全 404）：
 *   api.fengwind.com          → /api/v1/auth/me
 *   api-donation.fengwind.com → /api/me
 *   api-welfalre.fengwind.com → /api/me（用量在 /api/level 的 profile 下）
 */
public class ForeignSelfMappingTest {

    /** 站点的真实信封：{code,message,data:{...}}，外层再套 http。 */
    private static JSONObject envelope(String userJson) throws Exception {
        return new JSONObject().put("http", 200).put("data",
                new JSONObject().put("code", 0).put("message", "success")
                        .put("data", new JSONObject(userJson)));
    }

    /** 归一化结果里的 New API 形状用户对象。 */
    private static JSONObject user(JSONObject mapped) {
        assertNotNull(mapped);
        JSONObject inner = mapped.optJSONObject("data");
        assertNotNull(inner);
        JSONObject u = inner.optJSONObject("data");
        assertNotNull(u);
        return u;
    }

    /** 主站形态：balance 即可用额（美元）。 */
    @Test public void mainSiteBalance() throws Exception {
        JSONObject u = user(Engine.mapForeignSelf(envelope("{\"id\":1,\"balance\":34.98}"), 500000));
        assertEquals(34.98 * 500000, u.getDouble("quota"), 0.01);
        assertEquals("1", u.getString("id"));
    }

    /** 福利站/捐赠站形态：balance + sub2api_user_id；用量常缺。 */
    @Test public void welfareSiteBalance() throws Exception {
        JSONObject u = user(Engine.mapForeignSelf(
                envelope("{\"sub2api_user_id\":777,\"balance\":12.5}"), 500000));
        assertEquals(12.5 * 500000, u.getDouble("quota"), 0.01);
        assertEquals("777", u.getString("id"));
    }

    /** data.user 内层（New API 变体）也要能取到。 */
    @Test public void nestedUserObject() throws Exception {
        JSONObject u = user(Engine.mapForeignSelf(envelope("{\"user\":{\"balance\":7.25}}"), 1));
        assertEquals(7.25, u.getDouble("quota"), 0.001);
    }

    /** 用量字段存在时要原样采用，不得丢。 */
    @Test public void usedAmountPickedUp() throws Exception {
        JSONObject u = user(Engine.mapForeignSelf(
                envelope("{\"balance\":10,\"usage_total_cost\":4.5}"), 1));
        assertEquals(10.0, u.getDouble("quota"), 0.001);
        assertEquals(4.5, u.getDouble("used_quota"), 0.001);
    }

    /** 字段全不认识 → null（保留上次额度），绝不编造 0。 */
    @Test public void unknownSchemaReturnsNull() throws Exception {
        assertNull(Engine.mapForeignSelf(envelope("{\"mystery\":\"x\"}"), 1));
        assertNull(Engine.mapForeignSelf(new JSONObject().put("http", 200), 1));
        assertNull(Engine.mapForeignSelf(null, 1));
    }

    /** 字符串型数字（JSON 里常见）也要认。 */
    @Test public void numericStringsAccepted() throws Exception {
        JSONObject u = user(Engine.mapForeignSelf(envelope("{\"balance\":\"3.5\"}"), 2));
        assertEquals(7.0, u.getDouble("quota"), 0.001);
    }

    /** 非有限值（NaN/Infinity）不得当成有效额度。 */
    @Test public void nonFiniteRejected() throws Exception {
        assertNull(Engine.mapForeignSelf(envelope("{\"balance\":\"NaN\"}"), 1));
        assertNull(Engine.mapForeignSelf(envelope("{\"balance\":\"Infinity\"}"), 1));
    }

    /** 结果必须带 success=true —— 下游 validSelf 靠它判断业务有效。 */
    @Test public void normalizedEnvelopeIsBusinessValid() throws Exception {
        JSONObject mapped = Engine.mapForeignSelf(envelope("{\"id\":9,\"balance\":1.0}"), 1);
        assertNotNull(mapped);
        assertTrue(mapped.getJSONObject("data").getBoolean("success"));
        assertEquals(200, mapped.getInt("http"));
        assertTrue(SiteProtocol.validSelf(mapped, "9"));
    }
}
