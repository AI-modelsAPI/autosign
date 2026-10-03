package icu.justwoker.justsign;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.json.JSONException;

import java.util.List;

import static org.junit.Assert.*;

/**
 * v1.3.2：「添加账号」必须为每个站点列出所有身份的所有账号。
 * 此前 providers() 按站点声明的 oauthProviders 裁剪，只有 AgentRouter/AnyRouter 声明了双身份，
 * 于是 SeekAI/KKtoken 等站点的 L 站账号在列表里根本不出现（真机实测：SeekAI 只列出 GitHub 账号）。
 */
public class SiteProtocolAccountOptionsTest {

    private JSONObject site(String name, String primaryProvider) throws JSONException {
        JSONObject s = new JSONObject();
        return s.put("name", name).put("oauthProvider", primaryProvider);
    }

    /** 无论站点声明什么，两种身份都在候选里。 */
    @Test public void everySiteOffersBothProviders() throws JSONException {
        assertEquals(2, SiteProtocol.providers(site("SeekAI", "github")).length);
        assertEquals("github", SiteProtocol.providers(site("SeekAI", "github"))[0]);
        assertEquals("linuxdo", SiteProtocol.providers(site("SeekAI", "github"))[1]);
        // AnyRouter 主身份是 linuxdo，顺序反过来，但两个都在
        String[] any = SiteProtocol.providers(site("AnyRouter", "linuxdo"));
        assertEquals("linuxdo", any[0]);
        assertEquals("github", any[1]);
        // oauthProviders 字段不再影响结果（旧站点配置里可能残留）
        JSONObject legacy = site("AgentRouter", "github").put("oauthProviders", "github");
        assertEquals(2, SiteProtocol.providers(legacy).length);
    }

    /** 只有 GitHub 名的凭据 → 只出 GitHub 一项；有 L 站名的 → 两项。 */
    @Test public void optionsFollowCredentialFields() throws JSONException {
        JSONArray creds = new JSONArray()
                .put(new JSONObject().put("id", "cred_1").put("alias", "账号1").put("githubUser", "AI-modelsAPI"))
                .put(new JSONObject().put("id", "cred_2").put("alias", "L站-1").put("siteAccount", "zgj"));

        List<String[]> seekAi = SiteProtocol.accountOptions(site("SeekAI", "github"), creds);
        assertEquals(2, seekAi.size());
        assertEquals("cred_1", seekAi.get(0)[0]);
        assertEquals("github", seekAi.get(0)[1]);
        assertEquals("cred_2", seekAi.get(1)[0]);
        assertEquals("linuxdo", seekAi.get(1)[1]);

        // 同一凭据两种身份都填了 → 同一站点下相邻出现
        JSONArray both = new JSONArray().put(new JSONObject()
                .put("id", "cred_3").put("githubUser", "u").put("siteAccount", "l"));
        List<String[]> opts = SiteProtocol.accountOptions(site("AnyRouter", "linuxdo"), both);
        assertEquals(2, opts.size());
        assertEquals("linuxdo", opts.get(0)[1]);
        assertEquals("github", opts.get(1)[1]);
    }

    /** 用户可见的身份名不能一处「Linux DO」一处「L站」。 */
    @Test public void providerLabelsAreConsistent() {
        assertEquals("GitHub", SiteProtocol.providerLabel("github"));
        assertEquals("Linux DO", SiteProtocol.providerLabel("linuxdo"));
    }
}