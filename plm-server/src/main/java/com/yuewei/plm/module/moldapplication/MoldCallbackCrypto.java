package com.yuewei.plm.module.moldapplication;

import com.yuewei.plm.common.exception.BusinessException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** DingTalk enterprise callback protocol; constant-time signature/owner and strict padding checks. */
public final class MoldCallbackCrypto {
    private final String token,owner;private final byte[] key;private final SecureRandom random=new SecureRandom();
    public MoldCallbackCrypto(String token,String encodingKey,String owner){try{if(token.isBlank()||owner.isBlank()||encodingKey.length()!=43)throw new IllegalArgumentException();this.token=token;this.owner=owner;this.key=Base64.getDecoder().decode(encodingKey+"=");if(key.length!=32)throw new IllegalArgumentException();}catch(Exception e){throw denied();}}
    private static BusinessException denied(){return new BusinessException(40101,"钉钉回调验证失败");}
    String signature(String timestamp,String nonce,String encrypted)throws Exception {var values=new ArrayList<>(List.of(token,timestamp,nonce,encrypted));Collections.sort(values);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(String.join("",values).getBytes(StandardCharsets.UTF_8)));}
    public String decrypt(String sign,String timestamp,String nonce,String encrypted) {
        try {
            if(sign==null||timestamp==null||nonce==null||encrypted==null||encrypted.length()>400_000||nonce.length()>128)throw denied();
            if(!timestamp.matches("[0-9]{1,16}"))throw denied();long rawTime=Long.parseLong(timestamp);long seconds=rawTime>100_000_000_000L?rawTime/1000:rawTime;
            long now=Instant.now().getEpochSecond();if(seconds<now-300||seconds>now+300||!MessageDigest.isEqual(signature(timestamp,nonce,encrypted).getBytes(StandardCharsets.US_ASCII),sign.getBytes(StandardCharsets.US_ASCII)))throw denied();
            Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(Arrays.copyOf(key,16)));byte[] plain=cipher.doFinal(Base64.getDecoder().decode(encrypted));
            int pad=plain[plain.length-1]&255;if(pad<1||pad>32||plain.length-pad<20)throw denied();for(int i=plain.length-pad;i<plain.length;i++)if((plain[i]&255)!=pad)throw denied();
            int length=ByteBuffer.wrap(plain,16,4).getInt();if(length<0||length>plain.length-pad-20)throw denied();
            byte[] recipient=Arrays.copyOfRange(plain,20+length,plain.length-pad);if(!MessageDigest.isEqual(recipient,owner.getBytes(StandardCharsets.UTF_8)))throw denied();
            return new String(plain,20,length,StandardCharsets.UTF_8);
        }catch(Exception e){throw denied();}
    }
    public Map<String,String> encrypt(String message) {
        try {
            byte[] msg=message.getBytes(StandardCharsets.UTF_8),recipient=owner.getBytes(StandardCharsets.UTF_8),salt=new byte[16];random.nextBytes(salt);
            var buffer=ByteBuffer.allocate(20+msg.length+recipient.length);buffer.put(salt).putInt(msg.length).put(msg).put(recipient);byte[] raw=buffer.array();int pad=32-raw.length%32;byte[] plain=Arrays.copyOf(raw,raw.length+pad);Arrays.fill(plain,raw.length,plain.length,(byte)pad);
            var cipher=Cipher.getInstance("AES/CBC/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(Arrays.copyOf(key,16)));String encrypted=Base64.getEncoder().encodeToString(cipher.doFinal(plain));
            String timestamp=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();return Map.of("encrypt",encrypted,"timeStamp",timestamp,"nonce",nonce,"msg_signature",signature(timestamp,nonce,encrypted));
        }catch(Exception e){throw denied();}
    }
}
