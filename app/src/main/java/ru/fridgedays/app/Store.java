package ru.fridgedays.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.security.keystore.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.util.*;
import java.nio.charset.StandardCharsets;
import ru.fridgedays.core.*;

/** One transaction boundary shared by UI, LAN and scheduled jobs. DB stores only ciphertext. */
public final class Store implements SyncReplica {
    private static Store instance;
    public static synchronized Store get(Context c)throws Exception{if(instance==null)instance=new Store(c.getApplicationContext());return instance;}
    private final SQLiteDatabase db;
    private final android.content.SharedPreferences prefs;
    private final SecretKey wrappingKey;
    public final String actor;
    private Invitation invite;
    private Ledger ledger;
    private boolean cloudActive;
    synchronized boolean beginCloud(){if(cloudActive)return false;cloudActive=true;return true;}
    synchronized void endCloud(){cloudActive=false;}
    private Store(Context c)throws Exception{
        prefs=c.getSharedPreferences("private-config",Context.MODE_PRIVATE);
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias("fridge-config-v1")){
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder("fridge-config-v1",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();
        }
        wrappingKey=(SecretKey)ks.getKey("fridge-config-v1",null);
        actor=prefs.getString("actor",UUID.randomUUID().toString());prefs.edit().putString("actor",actor).apply();
        String saved=prefs.getString("invitation",null);
        invite=saved==null?Invitation.create():Invitation.parse(unwrap(saved));
        if(saved==null)saveConfig();
        db=new SQLiteOpenHelper(c,"fridge.db",null,1){
            public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE packets(room TEXT NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,uploaded INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(room,id))");d.execSQL("CREATE TABLE sync(room TEXT PRIMARY KEY,cursor INTEGER NOT NULL DEFAULT 0)");}
            public void onUpgrade(SQLiteDatabase d,int old,int next){throw new IllegalStateException("Нужна миграция базы");}
        }.getWritableDatabase();
        ledger=decode(packets());
    }
    private String wrap(String value)throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,wrappingKey);
        c.updateAAD("config-v1".getBytes(StandardCharsets.UTF_8));return Crypto.encode(c.getIV())+"."+Crypto.encode(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
    private String unwrap(String value)throws Exception{
        String[] p=value.split("\\.");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,wrappingKey,new GCMParameterSpec(128,Crypto.decode(p[0])));
        c.updateAAD("config-v1".getBytes(StandardCharsets.UTF_8));return new String(c.doFinal(Crypto.decode(p[1])),StandardCharsets.UTF_8);
    }
    private void saveConfig()throws Exception{if(!prefs.edit().putString("invitation",wrap(invite.link())).commit())throw new IllegalStateException("Не удалось сохранить ключ");}
    public synchronized Invitation invitation(){return invite;}
    public String actorId(){return actor;}
    public synchronized List<Product> products(){return ledger.products();}
    public synchronized int operationCount(){return ledger.size();}
    public synchronized Map<String,String> packets(){
        Map<String,String> result=new TreeMap<>();
        try(Cursor c=db.rawQuery("SELECT id,payload FROM packets WHERE room=?",new String[]{invite.room()})){while(c.moveToNext())result.put(c.getString(0),c.getString(1));}return result;
    }
    private Ledger decode(Map<String,String> packets)throws Exception{
        Ledger l=new Ledger();List<Operation> ops=new ArrayList<>();
        for(var e:packets.entrySet()){
            Operation op=Codec.operation(Crypto.open(invite.key,invite.context(e.getKey()),e.getValue()));
            if(!op.id.equals(e.getKey()))throw new IllegalArgumentException("Несовпадение ID");ops.add(op);
        }
        l.merge(ops);return l;
    }
    public synchronized void merge(Map<String,String> incoming)throws Exception{
        Map<String,String> all=packets();
        // Validate every incoming ciphertext, including duplicate IDs, before changing anything.
        Ledger candidate=new Ledger();candidate.merge(ledger.operations());candidate.merge(decode(incoming).operations());
        db.beginTransaction();try{
            for(var e:incoming.entrySet())if(!all.containsKey(e.getKey()))insert(e.getKey(),e.getValue());
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}ledger=candidate;
    }
    private void insert(String id,String payload){ContentValues v=new ContentValues();v.put("room",invite.room());v.put("id",id);v.put("payload",payload);db.insertOrThrow("packets",null,v);}
    public synchronized void change(String product,Map<String,String> fields)throws Exception{
        Ledger candidate=new Ledger();candidate.merge(ledger.operations());Operation op=candidate.change(actor,product,fields);
        String sealed=Crypto.seal(invite.key,invite.context(op.id),Codec.operation(op));insert(op.id,sealed);ledger=candidate;
    }
    public synchronized void join(Invitation next)throws Exception{
        if(cloudActive)throw new IllegalStateException("Дождитесь завершения текущей синхронизации и повторите");
        if(!next.room().equals(invite.room())&&!packets().isEmpty())throw new IllegalArgumentException("Сначала сохраните копию. Для другой семьи используйте отдельную установку: переключение семей пока не включено.");
        if(next.room().equals(invite.room())&&!Arrays.equals(next.key,invite.key))throw new IllegalArgumentException("Ключ этой семьи не совпадает");
        Invitation previous=invite;invite=next;
        try{saveConfig();ledger=decode(packets());}catch(Exception e){invite=previous;throw e;}
        if(!previous.relay.equals(next.relay)){
            db.execSQL("UPDATE packets SET uploaded=0 WHERE room=?",new Object[]{invite.room()});db.execSQL("DELETE FROM sync WHERE room=?",new Object[]{invite.room()});
        }
    }
    public synchronized Map<String,String> pending(){
        Map<String,String> result=new LinkedHashMap<>();int bytes=0;
        try(Cursor c=db.rawQuery("SELECT id,payload FROM packets WHERE room=? AND uploaded=0 LIMIT 20",new String[]{invite.room()})){
            while(c.moveToNext()){String s=c.getString(1);if(bytes+s.length()>900_000)break;bytes+=s.length();result.put(c.getString(0),s);}
        }return result;
    }
    public synchronized void acknowledge(Collection<String> ids){db.beginTransaction();try{for(String id:ids)db.execSQL("UPDATE packets SET uploaded=1 WHERE room=? AND id=?",new Object[]{invite.room(),id});db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public synchronized long cursor(){try(Cursor c=db.rawQuery("SELECT cursor FROM sync WHERE room=?",new String[]{invite.room()})){return c.moveToFirst()?c.getLong(0):0;}}
    public synchronized void cursor(long cursor){db.execSQL("INSERT OR REPLACE INTO sync(room,cursor) VALUES (?,?)",new Object[]{invite.room(),cursor});}
}
