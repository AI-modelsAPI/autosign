package icu.justwoker.justsign;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Linux DO 可见授权路由回归：旧站点 Cookie 不得把 /login 卡在首页。 */
public class AuthLoopRecoveryTest {

    private static final String BASE = "https://agentrouter.org";

    @Test public void loginAndSigninRoutesAreRecognized() {
        assertTrue(AuthLoopRecovery.isLoginRoute(BASE + "/login", BASE));
        assertTrue(AuthLoopRecovery.isLoginRoute(BASE + "/signin?next=/", BASE));
        assertFalse(AuthLoopRecovery.isLoginRoute(BASE + "/", BASE));
        assertFalse(AuthLoopRecovery.isLoginRoute("https://connect.linux.do/login", BASE));
    }

    @Test public void staleSessionHomeForcesOneLoginReload() {
        assertTrue(AuthLoopRecovery.shouldForceLoginRoute(
                BASE + "/", BASE, 401, false, false, false));
        assertTrue(AuthLoopRecovery.shouldForceLoginRoute(
                BASE + "/console", BASE, 200, false, false, false));
        assertFalse(AuthLoopRecovery.shouldForceLoginRoute(
                BASE + "/", BASE, 503, false, false, false));
        assertFalse(AuthLoopRecovery.shouldForceLoginRoute(
                BASE + "/", BASE, 401, false, true, false));
        assertFalse(AuthLoopRecovery.shouldForceLoginRoute(
                BASE + "/", BASE, 401, false, false, true));
    }

    @Test public void liveSessionHomeIsNotDestroyed() {
        assertFalse(AuthLoopRecovery.shouldForceLoginRoute(
                BASE + "/", BASE, 200, true, false, false));
        assertFalse(AuthLoopRecovery.shouldForceLoginRoute(
                BASE + "/login", BASE, 401, false, false, false));
    }
}
