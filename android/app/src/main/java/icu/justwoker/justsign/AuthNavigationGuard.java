package icu.justwoker.justsign;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One count per onPageStarted (never per shouldOverride/onPageFinished callback).
 * Trips only on a real reload loop, not on a long linear chain of distinct redirects:
 *   - SAME_PATH_LIMIT: the same path started back-to-back (classic WAF reload loop).
 *   - REVISIT_LIMIT: any single path re-entered this many times overall (catches
 *     alternating A/B/A/B loops that never repeat consecutively).
 *   - TOTAL_LIMIT: a high backstop against runaway navigation of ever-changing URLs.
 * A normal OAuth hop chain (login → provider → callback → console) visits each path
 * a handful of times and stays well under every limit.
 */
public final class AuthNavigationGuard {
    static final int SAME_PATH_LIMIT = 8;
    static final int REVISIT_LIMIT = 12;
    static final int TOTAL_LIMIT = 80;
    private final Map<String,Integer> visits = new HashMap<>();
    private String lastPath = "";
    private int consecutive, total;
    private boolean tripped;

    public boolean onPageStarted(String url) {
        if (tripped) return true;
        String path;
        try {
            URI u=URI.create(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) && !"http".equalsIgnoreCase(u.getScheme())) return false;
            if (u.getHost()==null) return false;
            path=u.getScheme().toLowerCase(Locale.ROOT)+"://"+u.getHost().toLowerCase(Locale.ROOT)
                    +":"+u.getPort()+(u.getPath()==null?"/":u.getPath());
        } catch(Exception e) {return false;}
        consecutive=path.equals(lastPath)?consecutive+1:1;
        lastPath=path;
        total++;
        int seen=visits.getOrDefault(path,0)+1;
        visits.put(path,seen);
        tripped=consecutive>=SAME_PATH_LIMIT || seen>=REVISIT_LIMIT || total>=TOTAL_LIMIT;
        return tripped;
    }
    public int totalStarts() { return total; }
    public void reset() { visits.clear(); lastPath=""; consecutive=0; total=0; tripped=false; }
}
