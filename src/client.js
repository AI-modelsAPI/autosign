import axios from 'axios';
import { SocksProxyAgent } from 'socks-proxy-agent';

export class SiteClient {
  constructor(site, account, cfg) {
    this.site = site;
    this.baseUrl = (site.baseUrl || '').replace(/\/+$/, '');
    this.cfg = cfg;
    const token = account?.token || account?.accessToken;
    this.headers = { 'User-Agent': cfg.UA, 'Accept': 'application/json' };
    if (token) this.headers['Authorization'] = 'Bearer ' + token;
    if (account?.cookie) this.headers['Cookie'] = account.cookie;

    let agent = null;
    if (cfg.proxy?.enabled) {
      const { type, host, port } = cfg.proxy;
      agent = new SocksProxyAgent(`${type}://${host}:${port}`);
    }
    this.axios = axios.create({
      baseURL: this.baseUrl,
      headers: this.headers,
      httpsAgent: agent,
      httpAgent: agent,
      timeout: 15000,
      validateStatus: () => true
    });
  }

  async call(method, path, body) {
    try {
      const r = await this.axios[method.toLowerCase()](path, body);
      return { ok: true, status: r.status, data: r.data };
    } catch (e) {
      const st = e.response?.status;
      const d = e.response?.data;
      return { ok: false, status: st || 0, error: e.message, data: d };
    }
  }

  async status()      { return this.call('get', '/api/status'); }
  async self()        { return this.call('get', '/api/user/self'); }
  async statusSelf()  { return this.call('get', '/api/user/status'); }

  async usageLog(opts = {}) {
    const q = new URLSearchParams();
    if (opts.category) q.set('category', opts.category);
    if (opts.page)     q.set('page', opts.page);
    if (opts.limit)    q.set('limit', opts.limit);
    return this.call('get', '/api/log/self?' + q.toString());
  }

  async checkin() { return this.call('post', '/api/user/checkin'); }
}
export default SiteClient;
