package icu.justwoker.justsign;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;

/**
 * 离屏/可见 WebView 代理挂载的公共核心（item4 收敛）。
 *
 * 各站点原本各写一份完全相同的：构建 {@link ProxyConfig}（addProxyRule + addDirect）→
 * {@link ProxyController#setProxyOverride} → 异常时直连回退。此处只抽取这段逐字相同的核心，
 * 调用方保留各自的可达性探测、线程调度、host/port 解析与 scheme 拼接逻辑（它们彼此有差异）。
 *
 * 行为与各处内联实现严格等价：
 *   - executor 固定 {@code Runnable::run}（所有收敛点原本一致）；
 *   - 成功回调 onApplied 由调用方传入（AuthActivity 带 showTip，其余直接续跑）；
 *   - 任何异常都执行 onFail 回退（各处原本都是回退到直连续跑）。
 *
 * 注意：AnyRouterCheckin 的实现与此不同（main::post executor、无 addDirect、异常不回退而是报错），
 *   故不走此 helper，保持原样。
 */
final class ProxyMount {
    private ProxyMount() {}

    /**
     * @param address   完整代理地址（如 "socks5://127.0.0.1:10808"，或 OffscreenLogout 的 "host:port"）
     * @param onApplied 代理挂载成功后的续跑回调
     * @param onFail    构建/挂载抛异常时的回退回调（通常是直连续跑）
     */
    static void apply(String address, Runnable onApplied, Runnable onFail) {
        try {
            ProxyConfig pc = new ProxyConfig.Builder()
                    .addProxyRule(address)
                    .addDirect()
                    .build();
            ProxyController.getInstance().setProxyOverride(pc, Runnable::run, onApplied);
        } catch (Exception e) { onFail.run(); }
    }
}
