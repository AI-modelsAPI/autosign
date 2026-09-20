package icu.justwoker.justsign;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.TimeUnit;

/** WorkManager非精准闹钟，系统可能延后；每次执行仍由08:00时间门禁兜底。 */
public final class AnyRouterWorker extends Worker {
    private static final String NAME="justsign-anyrouter-eight-am-v1";
    public AnyRouterWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}
    @NonNull @Override public Result doWork(){
        Store store=new Store(getApplicationContext());
        org.json.JSONObject schedule=store.config().optJSONObject("schedule");
        if(schedule!=null&&!schedule.optBoolean("enabled",true))return Result.success();
        new Engine(getApplicationContext()).runAnyRouterOnce();
        return Result.success();
    }
    public static void sync(Context context){
        Store store=new Store(context);
        WorkManager manager=WorkManager.getInstance(context);
        org.json.JSONObject schedule=store.config().optJSONObject("schedule");
        if(schedule!=null&&!schedule.optBoolean("enabled",true)){manager.cancelUniqueWork(NAME);return;}
        long now=System.currentTimeMillis();
        long delay=Math.max(0,SiteProtocol.nextAnyRouterRun(now,true)-now);
        PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(AnyRouterWorker.class,24,TimeUnit.HOURS)
                .setInitialDelay(delay,TimeUnit.MILLISECONDS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build();
        // KEEP避免每次打开/刷新界面把下一次执行继续向后推。
        manager.enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.KEEP,request);
    }
}
