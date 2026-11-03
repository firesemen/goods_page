package ru.fridgedays.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import ru.fridgedays.core.*;

public final class ReminderReceiver extends BroadcastReceiver {
    public static final String REMIND="ru.fridgedays.REMIND";
    static ZoneId zone(Context c){return ZoneId.of(c.getSharedPreferences("settings",0).getString("zone","Europe/Moscow"));}
    static LocalDate today(Context c){return LocalDate.now(zone(c));}
    static void sendIfDue(Context c,Store store)throws Exception{if(ZonedDateTime.now(zone(c)).getHour()>=c.getSharedPreferences("settings",0).getInt("hour",9))send(c,store,false);}
    public static void schedule(Context c){
        int hour=c.getSharedPreferences("settings",0).getInt("hour",9);
        ZonedDateTime now=ZonedDateTime.now(zone(c)),next=now.withHour(hour).withMinute(0).withSecond(0).withNano(0);
        if(!next.isAfter(now))next=next.plusDays(1);
        PendingIntent p=PendingIntent.getBroadcast(c,12,new Intent(c,ReminderReceiver.class).setAction(REMIND),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        c.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.toInstant().toEpochMilli(),p);
        NotificationManager nm=c.getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("expiry","Сроки годности",NotificationManager.IMPORTANCE_DEFAULT));
    }
    @Override public void onReceive(Context c,Intent intent){
        String action=intent==null?null:intent.getAction();
        if(!REMIND.equals(action)&&!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_TIME_CHANGED.equals(action)&&!Intent.ACTION_TIMEZONE_CHANGED.equals(action)&&!Intent.ACTION_DATE_CHANGED.equals(action))return;
        schedule(c);
        PendingResult pending=goAsync();ExecutorService worker=Executors.newSingleThreadExecutor();
        worker.execute(()->{try{sendIfDue(c,Store.get(c));}catch(Exception ignored){}finally{pending.finish();worker.shutdown();}});
    }
    public static void send(Context c,Store store,boolean test)throws Exception{
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        if((Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)||!nm.areNotificationsEnabled())throw new IllegalStateException("Разрешите уведомления в настройках Android");
        LocalDate date=today(c);String dailyKey="sent:"+store.invitation().room();var prefs=c.getSharedPreferences("settings",0);
        if(!test&&date.toString().equals(prefs.getString(dailyKey,"")))return;
        List<String> lines=new ArrayList<>();int tomorrow=0,today=0,expired=0;
        for(Product p:store.products())if(!p.deleted&&p.quantity>0&&p.daysLeft(date)<=1){long days=p.daysLeft(date);if(days<0)expired++;else if(days==0)today++;else tomorrow++;if(lines.size()<8)lines.add(p.name+" · "+p.status(date));}
        if(lines.isEmpty()&&!test)return;
        String title=test&&lines.isEmpty()?"Уведомления работают":"Сегодня: "+today+" · завтра: "+tomorrow+" · просрочено: "+expired;
        String body=lines.isEmpty()?"Список пока не требует внимания.":String.join("\n",lines);
        PendingIntent open=PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        nm.notify(test?19:18,new Notification.Builder(c,"expiry").setSmallIcon(ru.fridgedays.app.R.drawable.ic_fridge).setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build());
        if(!test)prefs.edit().putString(dailyKey,date.toString()).apply();
    }
}
