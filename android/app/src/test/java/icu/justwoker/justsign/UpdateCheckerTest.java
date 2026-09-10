package icu.justwoker.justsign;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateCheckerTest {
    @Test public void normalizesTags() {
        assertEquals("1.2.3", UpdateChecker.normalize("v1.2.3"));
        assertEquals("1.2.3", UpdateChecker.normalize("1.2.3-beta"));
        assertEquals("", UpdateChecker.normalize("latest"));
    }
    @Test public void acceptsOnlyOfficialReleaseAsset() {
        assertTrue(UpdateChecker.validAssetUrl(
                "https://github.com/AI-modelsAPI/autosign/releases/download/v1.2.3/AutoSign-1.2.3-release.apk", "1.2.3"));
        assertFalse(UpdateChecker.validAssetUrl("http://github.com/evil.apk", "1.2.3"));
        assertFalse(UpdateChecker.validAssetUrl(
                "https://github.com/other/autosign/releases/download/v1.2.3/AutoSign-1.2.3-release.apk", "1.2.3"));
    }
    @Test public void comparesSemanticNumericVersions() {
        assertTrue(UpdateChecker.compare("1.0.1", "1.0.0") > 0);
        assertTrue(UpdateChecker.compare("1.10.0", "1.9.9") > 0);
        assertEquals(0, UpdateChecker.compare("1.0", "1.0.0"));
        assertTrue(UpdateChecker.compare("1.0.0", "2.0.0") < 0);
    }
}