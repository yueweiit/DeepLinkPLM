package com.yuewei.plm.module.partner;

import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

@Service @RequiredArgsConstructor
public class PartnerSelectionTransfers {
 public static final int CHUNK_SIZE=8*1024*1024;
 private final PartnerStore store;
 private final PartnerFileWorkspace files; private final PartnerMaterialService materials;private final PartnerFileSourceAccess sources;
 private String request(Map<String,Object> input){String value=required(input.get("requestId"),"提交标识");require(value.length()<=80,"提交标识过长");return value;}
 private void authorize(long id){authorize(id,false);}
 private void authorize(long id,boolean lock){sources.read(id,lock);store.access.requirePermission("documents:write");}
 private Map<String,Object> session(long id,UUID uid,boolean lock){authorize(id,lock);var rows=store.jdbc.queryForList("select * from plm_partner_upload_session where upload_id=? and record_id=? and owner_user_id=?"+(lock?" for update":""),uid,id,store.access.actorId());require(!rows.isEmpty(),"上传会话不存在或不属于当前账号");return rows.get(0);}
 @Transactional public Map<String,Object> initialize(long id,Map<String,Object> input){
  authorize(id);sources.write(id,true);String request=request(input),name=PartnerFileWorkspace.fileName(input.get("fileName"));long size=number(input.get("fileSize"));require(size>0,"请选择非空文件");
  String purpose=text(input.getOrDefault("purpose","cooperation_cost"));require(Set.of("cooperation_cost","photo").contains(purpose),"资料用途无效");
  String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);require((purpose.equals("photo")?Set.of("png","jpg","jpeg","webp"):Set.of("xlsx","csv","xls","pdf","png","jpg","jpeg","webp","zip")).contains(extension),"此选品上传入口不支持该文件类型");
  store.jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended('partner-transfer-capacity',0))");
  String expectedHash=PartnerFileWorkspace.hash(text(input.get("sha256")));var old=store.jdbc.queryForList("select upload_id,file_name,file_size,purpose,expected_sha256 from plm_partner_upload_session where record_id=? and owner_user_id=? and request_id=?",id,store.access.actorId(),request);
  if(!old.isEmpty()){var previous=old.get(0);require(name.equals(previous.get("file_name"))&&size==number(previous.get("file_size"))&&purpose.equals(previous.get("purpose"))&&expectedHash.equals(previous.get("expected_sha256")),"重复上传标识对应了另一文件或用途");return status(id,(UUID)previous.get("upload_id"));}
  long reserved=store.jdbc.queryForObject("select coalesce(sum(file_size),0) from plm_partner_upload_session where status='uploading'",Long.class);
  try{files.capacity(Math.addExact(Math.multiplyExact(size,3),reserved));}catch(ArithmeticException e){throw invalid("文件大小超出可安全处理的存储容量");}
  UUID uid=UUID.randomUUID();long count=(size-1)/CHUNK_SIZE+1;
  store.jdbc.update("insert into plm_partner_upload_session(upload_id,record_id,owner_user_id,request_id,file_name,file_size,chunk_size,part_count,purpose,expected_sha256) values(?,?,?,?,?,?,?,?,?,?)",uid,id,store.access.actorId(),request,name,size,CHUNK_SIZE,count,purpose,PartnerFileWorkspace.hash(text(input.get("sha256"))));
  return status(id,uid);
 }
 public Map<String,Object> status(long id,UUID uid){
  var s=session(id,uid,false);var out=new LinkedHashMap<String,Object>();out.put("id",uid.toString());out.put("fileName",s.get("file_name"));out.put("fileSize",s.get("file_size"));out.put("chunkSize",CHUNK_SIZE);out.put("partCount",s.get("part_count"));out.put("status",s.get("status"));
  var parts=store.jdbc.queryForList("select part_index,checksum from plm_partner_upload_part where upload_id=? order by part_index",uid);out.put("receivedParts",parts.stream().map(p->number(p.get("part_index"))).toList());Map<String,String> hashes=new LinkedHashMap<>();for(var p:parts)hashes.put(text(p.get("part_index")),text(p.get("checksum")));out.put("partHashes",hashes);
  if(s.get("attachment_id")!=null)out.put("attachmentId",s.get("attachment_id"));if(s.get("checksum")!=null)out.put("sha256",s.get("checksum"));return out;
 }
 @Transactional public Map<String,Object> part(long id,UUID uid,long index,String expected,InputStream input){
  var s=session(id,uid,true);sources.write(id,true);require(text(s.get("status")).equals("uploading"),"上传会话已完成或取消");require(index>=0&&index<number(s.get("part_count")),"分片索引无效");expected=PartnerFileWorkspace.hash(expected);require(!expected.isEmpty(),"分片必须提供SHA-256");
  long length=Math.min(CHUNK_SIZE,number(s.get("file_size"))-index*CHUNK_SIZE);files.capacity(length);Path target=files.part(uid,index),tmp=target.resolveSibling("part-"+index+"-"+UUID.randomUUID()+".tmp");
  try{MessageDigest d=MessageDigest.getInstance("SHA-256");long written=0;byte[] buffer=new byte[64*1024];try(OutputStream output=Files.newOutputStream(tmp)){for(int n;(n=input.read(buffer))>=0;){if(n==0)continue;written+=n;require(written<=length,"分片长度超过约定值");output.write(buffer,0,n);d.update(buffer,0,n);}}
   require(written==length,"分片未完整接收，请续传此分片");String actual=HexFormat.of().formatHex(d.digest());require(actual.equals(expected),"分片SHA-256不一致，请重传");
   var old=store.jdbc.queryForList("select checksum,byte_size from plm_partner_upload_part where upload_id=? and part_index=?",uid,index);
   if(!old.isEmpty()){require(expected.equals(old.get(0).get("checksum"))&&length==number(old.get(0).get("byte_size")),"同一索引已存在内容不同的分片");require(Files.isRegularFile(target)&&expected.equals(PartnerFileWorkspace.checksum(target)),"已存分片损坏，请取消本次上传后重新开始");}
   else{Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);store.jdbc.update("insert into plm_partner_upload_part(upload_id,part_index,byte_size,checksum) values(?,?,?,?)",uid,index,length,actual);}
   store.jdbc.update("update plm_partner_upload_session set updated_at=now() where upload_id=?",uid);return status(id,uid);
  }catch(IOException|NoSuchAlgorithmException e){throw invalid("分片保存失败，已接收部分保留，可继续上传");}finally{PartnerFileWorkspace.delete(tmp);}
 }
 @Transactional public Map<String,Object> complete(long id,UUID uid,Map<String,Object> input,HttpServletRequest request){
  var s=session(id,uid,true);if(text(s.get("status")).equals("completed")){String requested=PartnerFileWorkspace.hash(text(input.get("sha256")));require(requested.isEmpty()||requested.equals(s.get("checksum")),"重复完成提供了不同校验值");return status(id,uid);}require(text(s.get("status")).equals("uploading"),"上传会话已取消");
  sources.write(id,true);var parts=store.jdbc.queryForList("select part_index,byte_size,checksum from plm_partner_upload_part where upload_id=? order by part_index",uid);require(parts.size()==number(s.get("part_count")),"仍有缺失分片，请继续上传");
  long size=number(s.get("file_size"));try{files.capacity(Math.multiplyExact(size,2));}catch(ArithmeticException e){throw invalid("处理空间无法安全预留");}
  Path combined=files.directory("transfers",uid).resolve("assembled");
  try{MessageDigest whole=MessageDigest.getInstance("SHA-256");long total=0;try(OutputStream out=Files.newOutputStream(combined)){byte[] buffer=new byte[64*1024];for(var part:parts){long index=number(part.get("part_index"));Path p=files.part(uid,index);require(Files.isRegularFile(p)&&number(part.get("byte_size"))==Files.size(p),"已上传分片缺失，请重新上传");MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(p)){for(int n;(n=in.read(buffer))>=0;)if(n>0){out.write(buffer,0,n);whole.update(buffer,0,n);digest.update(buffer,0,n);total+=n;}}require(HexFormat.of().formatHex(digest.digest()).equals(part.get("checksum")),"已上传分片校验失败，不能完成");}}
   require(total==size,"文件长度不一致，不能完成");String actual=HexFormat.of().formatHex(whole.digest()),expected=PartnerFileWorkspace.hash(text(input.get("sha256")));if(expected.isEmpty())expected=text(s.get("expected_sha256"));require(expected.isEmpty()||expected.equals(actual),"完整文件SHA-256不一致");
   String name=text(s.get("file_name")),mime=name.toLowerCase(Locale.ROOT).endsWith(".xlsx")?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":name.toLowerCase(Locale.ROOT).endsWith(".png")?"image/png":name.matches("(?i).*\\.jpe?g$")?"image/jpeg":name.toLowerCase(Locale.ROOT).endsWith(".webp")?"image/webp":"application/octet-stream";
   var attachment=materials.uploadResumable(id,new PartnerPathFile(combined,name,mime),text(s.get("purpose")),request);
   var head=sources.write(id,true);store.revision(id,head);store.save(id,head);
   store.jdbc.update("update plm_partner_upload_session set status='completed',attachment_id=?,checksum=?,updated_at=now() where upload_id=?",attachment.getAttachmentId(),actual,uid);
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){for(var part:parts)PartnerFileWorkspace.delete(files.part(uid,number(part.get("part_index"))));PartnerFileWorkspace.delete(combined);}});
   return status(id,uid);
  }catch(IOException|NoSuchAlgorithmException e){throw invalid("文件合并失败；已上传分片保留，可重试完成");}finally{PartnerFileWorkspace.delete(combined);}
 }
 @Transactional public void cancel(long id,UUID uid){var s=session(id,uid,true);sources.write(id,true);require(!text(s.get("status")).equals("completed"),"正式附件已完成，不能取消上传会话删除它");store.jdbc.update("update plm_partner_upload_session set status='cancelled',updated_at=now() where upload_id=?",uid);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){for(long i=0;i<number(s.get("part_count"));i++)PartnerFileWorkspace.delete(files.part(uid,i));PartnerFileWorkspace.delete(files.directory("transfers",uid).resolve("assembled"));}});}
}
