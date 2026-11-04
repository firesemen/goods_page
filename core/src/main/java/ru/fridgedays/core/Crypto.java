package ru.fridgedays.core;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Crypto {
    private static final SecureRandom RANDOM=new SecureRandom();
    public static byte[] newKey(){byte[] key=new byte[32];RANDOM.nextBytes(key);return key;}
    public static String encode(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public static byte[] decode(String s){return Base64.getUrlDecoder().decode(s);}
    public static String sha256(String value)throws GeneralSecurityException{
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
    }
    public static String seal(byte[] key,String context,byte[] plaintext)throws GeneralSecurityException{
        byte[] nonce=new byte[12];RANDOM.nextBytes(nonce);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext=cipher.doFinal(plaintext), all=new byte[nonce.length+ciphertext.length+1];all[0]=1;
        System.arraycopy(nonce,0,all,1,12);System.arraycopy(ciphertext,0,all,13,ciphertext.length);return encode(all);
    }
    public static byte[] open(byte[] key,String context,String sealed)throws GeneralSecurityException{
        byte[] all;try{all=decode(sealed);}catch(IllegalArgumentException e){throw new GeneralSecurityException("Некорректное шифрование",e);}
        if(all.length<29||all[0]!=1)throw new GeneralSecurityException("Неизвестный пакет");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,all,1,12));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));return cipher.doFinal(all,13,all.length-13);
    }
    /** A separate relay capability; knowing it never gives the AES key. */
    public static String capability(byte[] key,String room)throws GeneralSecurityException{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return encode(mac.doFinal(("relay-v1:"+room).getBytes(StandardCharsets.UTF_8)));
    }
    public static String photoCapability(byte[] key,String room)throws GeneralSecurityException{
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return encode(mac.doFinal(("photo-v1:"+room).getBytes(StandardCharsets.UTF_8)));
    }
    public static String passwordSeal(char[] password,byte[] data)throws GeneralSecurityException{
        byte[] salt=new byte[16];RANDOM.nextBytes(salt);byte[] derived=derive(password,salt);
        try{return "FDB1."+encode(salt)+"."+seal(derived,"backup-v1",data);}finally{Arrays.fill(derived,(byte)0);}
    }
    public static byte[] passwordOpen(char[] password,String text)throws GeneralSecurityException{
        try{String[] p=text.split("\\.",-1);if(p.length!=3||!p[0].equals("FDB1"))throw new GeneralSecurityException("Неизвестная резервная копия");
            byte[] salt=decode(p[1]);if(salt.length!=16)throw new GeneralSecurityException("Некорректная соль");byte[] key=derive(password,salt);
            try{return open(key,"backup-v1",p[2]);}finally{Arrays.fill(key,(byte)0);}
        }catch(IllegalArgumentException e){throw new GeneralSecurityException(e);}
    }
    private static byte[] derive(char[] password,byte[] salt)throws GeneralSecurityException{
        PBEKeySpec spec=new PBEKeySpec(password,salt,210_000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}finally{spec.clearPassword();}
    }
}
