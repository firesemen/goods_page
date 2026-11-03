package ru.fridgedays.app;

import android.content.Context;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import ru.fridgedays.core.*;

/** Foreground-only, encrypted bidirectional LAN sync. Never sends an invitation/key. */
public final class NearbySync implements AutoCloseable {
    private static final String TYPE="_fridgedays._tcp.";
    private final SyncReplica store;
    private final NsdManager nsd;
    private final Consumer<String> status;
    private final Invitation invitation;
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final AtomicBoolean syncing=new AtomicBoolean();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final Map<String,InetSocketAddress> peers=new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running=true;
    private ServerSocket server;
    private WifiManager.MulticastLock multicast;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    public NearbySync(Context c,SyncReplica store,Consumer<String> status)throws Exception{
        this.store=store;this.invitation=store.invitation();this.status=status;nsd=c.getSystemService(NsdManager.class);
        WifiManager wifi=c.getApplicationContext().getSystemService(WifiManager.class);multicast=wifi.createMulticastLock("fridge-discovery");multicast.setReferenceCounted(false);multicast.acquire();
        try{server=new ServerSocket(0);workers.execute(this::listen);
            String prefix="FD-"+invitation.family.substring(0,8)+"-"+invitation.fridge.substring(0,4);
            String name=prefix+"-"+store.actorId().substring(0,8);
            NsdServiceInfo info=new NsdServiceInfo();info.setServiceName(name);info.setServiceType(TYPE);info.setPort(server.getLocalPort());
            registration=new NsdManager.RegistrationListener(){public void onServiceRegistered(NsdServiceInfo i){}public void onRegistrationFailed(NsdServiceInfo i,int e){status.accept("Не удалось объявить телефон в Wi-Fi");}public void onServiceUnregistered(NsdServiceInfo i){}public void onUnregistrationFailed(NsdServiceInfo i,int e){}};
            nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);
            discovery=new NsdManager.DiscoveryListener(){
                public void onDiscoveryStarted(String t){}public void onDiscoveryStopped(String t){}public void onServiceLost(NsdServiceInfo i){peers.remove(i.getServiceName());}
                public void onStartDiscoveryFailed(String t,int e){status.accept("Поиск рядом недоступен в этой сети");}public void onStopDiscoveryFailed(String t,int e){}
                public void onServiceFound(NsdServiceInfo i){
                    if(!running||!i.getServiceName().startsWith(prefix)||i.getServiceName().contains(store.actorId().substring(0,8))||!syncing.compareAndSet(false,true))return;
                    nsd.resolveService(i,new NsdManager.ResolveListener(){
                        public void onResolveFailed(NsdServiceInfo s,int e){syncing.set(false);}
                        public void onServiceResolved(NsdServiceInfo s){
                            if(!running){syncing.set(false);return;}
                            peers.put(i.getServiceName(),new InetSocketAddress(s.getHost(),s.getPort()));
                            try{workers.execute(()->{try{exchange(s.getHost(),s.getPort());if(running)status.accept("Синхронизировано рядом · "+java.time.LocalTime.now().withSecond(0).withNano(0));}catch(Exception e){if(running)status.accept("Обмен рядом не завершён; повторите поиск");}finally{syncing.set(false);}});}catch(RejectedExecutionException ignored){syncing.set(false);}
                        }
                    });
                }
            };nsd.discoverServices(TYPE,NsdManager.PROTOCOL_DNS_SD,discovery);
            ticker.scheduleWithFixedDelay(()->{if(running&&syncing.compareAndSet(false,true))try{for(var peer:peers.values())if(running)try{exchange(peer.getAddress(),peer.getPort());status.accept("Синхронизировано рядом");}catch(Exception ignored){}}finally{syncing.set(false);}},20,20,TimeUnit.SECONDS);
        }catch(Exception e){close();throw e;}
    }
    private void sameSpace(){if(!invitation.room().equals(store.invitation().room())||!Arrays.equals(invitation.key,store.invitation().key))throw new IllegalStateException("Семья изменилась во время обмена");}
    private Map<String,String> localPackets(){synchronized(store){sameSpace();return store.packets();}}
    private void mergePackets(Map<String,String> packets)throws Exception{synchronized(store){sameSpace();store.merge(packets);}}
    private String context(String stage){return invitation.context("nearby-"+stage);}
    private void write(DataOutputStream out,String stage,Map<String,String> data)throws Exception{
        byte[] sealed=Crypto.seal(invitation.key,context(stage),Codec.packets(data)).getBytes(StandardCharsets.US_ASCII);
        out.writeInt(sealed.length);out.write(sealed);out.flush();
    }
    private Map<String,String> read(DataInputStream in,String stage)throws Exception{
        int n=in.readInt();if(n<29||n>3_000_000)throw new IOException("Некорректный размер обмена");byte[] b=new byte[n];in.readFully(b);
        return Codec.packets(Crypto.open(invitation.key,context(stage),new String(b,StandardCharsets.US_ASCII)));
    }
    private static Map<String,String> manifest(Map<String,String> all){Map<String,String> m=new TreeMap<>();for(String id:all.keySet())m.put(id,"");return m;}
    private static Map<String,String> response(Map<String,String> local,Set<String> known){
        Map<String,String> m=manifest(local);int bytes=0,count=0;for(var e:local.entrySet())if(!known.contains(e.getKey())&&count<20&&bytes+e.getValue().length()<900_000){m.put(e.getKey(),e.getValue());bytes+=e.getValue().length();count++;}return m;
    }
    private static Map<String,String> nonempty(Map<String,String> m){Map<String,String> r=new TreeMap<>();m.forEach((k,v)->{if(!v.isEmpty())r.put(k,v);});return r;}
    private void listen(){
        while(running)try{Socket s=server.accept();if(!running||sockets.size()>=2){s.close();continue;}s.setSoTimeout(12_000);sockets.add(s);
            workers.execute(()->{try(s){
                DataInputStream in=new DataInputStream(s.getInputStream());DataOutputStream out=new DataOutputStream(s.getOutputStream());
                Map<String,String> known=read(in,"manifest");write(out,"response",response(localPackets(),known.keySet()));
                mergePackets(read(in,"push"));write(out,"ack",Map.of());if(running)status.accept("Синхронизировано рядом");
            }catch(Exception ignored){}finally{sockets.remove(s);}});
        }catch(Exception e){if(running)status.accept("Обмен рядом временно недоступен");}
    }
    private void exchange(InetAddress host,int port)throws Exception{
        for(int round=0;round<1000&&running;round++){
            boolean again;
            Socket s=new Socket();try(s){
                sockets.add(s);s.connect(new InetSocketAddress(host,port),8_000);s.setSoTimeout(12_000);
                DataOutputStream out=new DataOutputStream(s.getOutputStream());DataInputStream in=new DataInputStream(s.getInputStream());
                write(out,"manifest",manifest(localPackets()));Map<String,String> remote=read(in,"response");mergePackets(nonempty(remote));
                Map<String,String> local=localPackets();Map<String,String> push=nonempty(response(local,remote.keySet()));write(out,"push",push);read(in,"ack");
                again=remote.keySet().stream().anyMatch(id->!local.containsKey(id))||local.keySet().stream().filter(id->!remote.containsKey(id)).count()>push.size();
            }finally{sockets.remove(s);}
            if(!again)return;
        }
    }
    @Override public void close(){
        running=false;if(discovery!=null)try{nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){}
        if(registration!=null)try{nsd.unregisterService(registration);}catch(Exception ignored){}
        if(server!=null)try{server.close();}catch(IOException ignored){}
        for(Socket s:sockets)try{s.close();}catch(IOException ignored){}sockets.clear();
        if(multicast!=null&&multicast.isHeld())multicast.release();ticker.shutdownNow();workers.shutdownNow();
    }
}
