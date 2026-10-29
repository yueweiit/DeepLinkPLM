package com.yuewei.plm.module.partner;

import com.yuewei.plm.common.config.AppProperties;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import static com.yuewei.plm.module.partner.PartnerValues.*;

/** Temporary parts and jobs live beneath the existing storage root, never a second file library. */
@Component @RequiredArgsConstructor
public class PartnerFileWorkspace {
 private final AppProperties properties;
 Path root(){try{Path p=Path.of(properties.getStorage().getLocalRoot()).toAbsolutePath().normalize();Files.createDirectories(p);return p;}catch(IOException e){throw invalid("文件存储目录不可用");}}
 Path directory(String category,UUID id){try{Path p=root().resolve(".partner-"+category).resolve(id.toString());Files.createDirectories(p);return p;}catch(IOException e){throw invalid("无法创建文件处理暂存目录");}}
 Path part(UUID id,long index){return directory("transfers",id).resolve("part-"+index);}
 Path job(UUID id,String name){require(name.matches("[A-Za-z0-9._-]+"),"非法任务文件名");return directory("jobs",id).resolve(name);}
 Path stored(String key){Path root=root(),p=root.resolve(key).normalize();require(p.startsWith(root)&&Files.isRegularFile(p),"原文件不可用，请重新上传");return p;}
 String key(Path path){Path root=root(),p=path.toAbsolutePath().normalize();require(p.startsWith(root),"非法存储路径");return root.relativize(p).toString().replace('\\','/');}
 void capacity(long bytes){try{require(bytes>=0&&Files.getFileStore(root()).getUsableSpace()>Math.addExact(bytes,64L*1024*1024),"存储可用空间不足，请释放空间后继续；已上传分片保留");}catch(IOException|ArithmeticException e){throw invalid("无法安全预留处理空间，请检查存储容量");}}
 static String checksum(Path path){try(InputStream in=Files.newInputStream(path)){return digest(in);}catch(IOException e){throw invalid("无法读取文件校验值");}}
 static String checksum(Path path,Runnable progress){try(InputStream in=Files.newInputStream(path)){MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[64*1024];long last=System.nanoTime();for(int n;(n=in.read(buffer))>=0;){if(n>0)digest.update(buffer,0,n);if(System.nanoTime()-last>1_000_000_000L){progress.run();last=System.nanoTime();}}progress.run();return HexFormat.of().formatHex(digest.digest());}catch(IOException|NoSuchAlgorithmException e){throw invalid("无法读取文件校验值");}}
 static String digest(InputStream in){try{MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[64*1024];for(int n;(n=in.read(buffer))>=0;)if(n>0)d.update(buffer,0,n);return HexFormat.of().formatHex(d.digest());}catch(IOException|NoSuchAlgorithmException e){throw invalid("文件校验失败");}}
 static String hash(String value){String h=text(value).toLowerCase(Locale.ROOT);require(h.isEmpty()||h.matches("[a-f0-9]{64}"),"SHA-256格式不正确");return h;}
 static String fileName(Object value){String name=required(value,"文件名");require(name.length()<=255&&!name.contains("/")&&!name.contains("\\")&&!name.contains("\0"),"文件名无效");return name;}
 static void delete(Path path){try{Files.deleteIfExists(path);}catch(IOException ignored){/* Retry/cancel may reclaim an unreferenced temporary file later. */}}
}
