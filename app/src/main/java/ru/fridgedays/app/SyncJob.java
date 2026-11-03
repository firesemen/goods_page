package ru.fridgedays.app;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;

public final class SyncJob extends JobService {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private Future<?> task;
    public static void schedule(Context c,Store s){
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(s.invitation().relay.isEmpty()){scheduler.cancel(14);return;}
        JobInfo info=new JobInfo.Builder(14,new ComponentName(c,SyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15*60_000L).setBackoffCriteria(60_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();scheduler.schedule(info);
    }
    @Override public boolean onStartJob(JobParameters p){task=executor.submit(()->{boolean retry=false;try{CloudSync.run(Store.get(this));}catch(Exception e){retry=true;}jobFinished(p,retry);});return true;}
    @Override public boolean onStopJob(JobParameters p){if(task!=null)task.cancel(true);return true;}
    @Override public void onDestroy(){executor.shutdownNow();super.onDestroy();}
}
