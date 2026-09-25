package icu.justwoker.justsign;

/** AnyRouter 已验证的 Cookie 会话协议：先self验身份，再且仅一次sign_in。 */
public final class AnyRouterSignInJs {
    private AnyRouterSignInJs() {}
    public static String render(String expectedId) {
        String uid = expectedId != null && expectedId.matches("[1-9][0-9]{0,14}") ? expectedId : "";
        return TEMPLATE.replace("__UID__", uid);
    }
    private static final String TEMPLATE = """
        (async function(){
          if(location.origin!=='https://anyrouter.top')return;
          if(window.__justsignSignInBusy)return;
          window.__justsignSignInBusy=true;
          var uid='__UID__',sent=false;
          function result(o){if(sent)return;sent=true;
            if(location.origin==='https://anyrouter.top')window.JustSign.onResult(JSON.stringify(o));}
          function failure(message){result({ok:false,already:false,reward:0,rewardKnown:false,message:message});}
          if(!uid){failure('缺少站内用户ID，请先重新授权此账号');return;}
          var headers={'Accept':'application/json','New-Api-User':uid};
          async function request(path,method){
            var ac=new AbortController(),timer=setTimeout(function(){ac.abort();},15000);
            try{
              var r=await fetch(path,{method:method||'GET',headers:headers,credentials:'include',
                cache:'no-store',redirect:'error',signal:ac.signal});
              if(r.redirected)throw new Error('redirect');
              var body=await r.text();
              if(body.length>262144)throw new Error('large');
              var j=JSON.parse(body);return {http:r.status,body:j};
            }finally{clearTimeout(timer);}
          }
          try{
            var self=await request('/api/user/self');
            if(self.http!==200||!self.body||self.body.success!==true){
              failure('当前会话无效或站点拒绝访问，请刷新后重试');return;}
            if(!self.body.data||String(self.body.data.id)!==uid){
              failure('网页会话与当前账号不一致，已停止签到');return;}
            if(!window.JustSign.claimSignIn()){
              failure('本轮签到已提交，不重复发送；请刷新确认状态');return;}
            var r=await request('/api/user/sign_in','POST'),j=r.body||{};
            var already=typeof j.message==='string'&&/^(今日已签到|今天已签到|今日已经签到|already checked in today)[。！.!]?$/i.test(j.message.trim());
            if(r.http===200&&(j.success===true||already)){
              result({ok:true,already:already,reward:0,rewardKnown:false,
                message:already?'今日已签到（站点未返回奖励金额）':'签到请求成功（站点未返回奖励金额）'});
            }else failure('签到未成功（HTTP '+r.http+'），未确认任何奖励到账');
          }catch(e){failure(e&&e.name==='AbortError'?'签到请求超时，未确认结果；不会自动重复提交':'网络或响应异常，未确认签到结果；不会自动重复提交');}
        })();
        """;
}
