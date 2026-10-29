package com.yuewei.plm.module.partner;
import com.yuewei.plm.module.attachment.service.*;
import com.yuewei.plm.module.attachment.vo.AttachmentVO;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import static com.yuewei.plm.module.partner.PartnerValues.*;
@Service @RequiredArgsConstructor
public class PartnerMaterialService {
 private final PartnerStore store;private final DocumentService documents;private final AttachmentVersionService versions;private final PartnerFileSourceAccess sources;
 @Transactional public AttachmentVO upload(long id,String kind,MultipartFile file,String purpose,Long previous,String version,String reason,HttpServletRequest request){
  return upload(id,kind,file,purpose,previous,version,reason,request,false);
 }
 /** Called only after the catalogue service locks and validates the exact source style. */
 AttachmentVO uploadCatalogModelSheet(long id,String kind,MultipartFile file,HttpServletRequest request){
  require(Set.of("selection","supplier_quote").contains(kind),"型号原图仅属于供应商目录来源");
  store.access.requireEngineeringMaintenance();
  return upload(id,kind,file,"photo",null,"V1","国内工程补充当前款式型号表原图",request,true);
 }
 private AttachmentVO upload(long id,String kind,MultipartFile file,String purpose,Long previous,String version,String reason,HttpServletRequest request,boolean catalogModelSheet){
  if(!catalogModelSheet&&Set.of("selection","supplier_quote").contains(kind))sources.write(id,true);
  var current=store.load(id,kind,true);store.access.requirePermission(Set.of("sample","sample_shipment").contains(kind)?"samples:write":kind.equals("fulfilment")&&store.access.hasPermission("partner:delivery")?"partner:delivery":"partner:write");
  if(kind.equals("fulfilment"))require(!text(current.get("status")).equals("closed"),"执行记录已结案，不能覆盖结案依据");
  require(Set.of("material","photo","financial","cooperation_cost","factory_signature").contains(purpose),"资料用途无效");if(purpose.equals("financial"))store.access.requireFinanceWrite();
  if(purpose.equals("factory_signature")){
   store.access.requireEngineeringMaintenance();require(kind.equals("inspection")&&text(current.get("status")).equals("pending_signature"),"只能为本次已冻结的检验现场签名");require(previous==null,"工厂签名不得覆盖旧版本");
   try{require("image/png".equals(file.getContentType())&&file.getSize()<=1024*1024,"签名必须是有效PNG图片且不超过1MB");var image=javax.imageio.ImageIO.read(file.getInputStream());require(image!=null&&image.getWidth()>=200&&image.getHeight()>=60&&image.getWidth()<=1920&&image.getHeight()<=1080,"签名画布尺寸无效");int ink=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int pixel=image.getRGB(x,y);if((pixel>>>24)>50&&(pixel&0xffffff)<0xdddddd)ink++;}require(ink>=50,"签名为空，请本人重新手写");}catch(java.io.IOException e){throw invalid("签名图片无法读取，请重新签署");}
  }
  if(purpose.equals("cooperation_cost")){store.access.requireEngineeringMaintenance();store.access.requirePermission("partner:write");}
  AttachmentVO created;
  if(previous!=null){require(Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_file where record_id=? and attachment_id=?)",Boolean.class,id,previous)),"原文件不属于此记录");created=versions.upload(previous,file,required(version,"版本号"),required(reason,"修改原因"),request);}
  else created=documents.upload(file,purpose.equals("photo")?"sample_image":"other",text(version).isEmpty()?"V1":version,reason,null,List.of(new DocumentService.Reference("partner",id,"ALL")),request);
  store.jdbc.update("insert into plm_partner_file(record_id,attachment_id,purpose) values(?,?,?) on conflict(record_id,attachment_id) do update set purpose=case when plm_partner_file.purpose='financial' then 'financial' else excluded.purpose end",id,created.getAttachmentId(),purpose);
  store.event(id,"file_add",Map.of("fileId",created.getAttachmentId(),"name",created.getFileName(),"purpose",purpose,"version",text(version)),"file-"+UUID.randomUUID());return created;
 }
 public void inherit(long target,Object references){
  for(var f:maps(references)){long id=number(f.get("id"));store.access.requireFile(id);store.jdbc.queryForList("select attachment_id from plm_attachment where attachment_id=? for update",id);
   String purpose=store.jdbc.queryForObject("select coalesce((select purpose from plm_partner_file where attachment_id=? order by case purpose when 'financial' then 0 when 'photo' then 1 else 2 end limit 1),'material')",String.class,id);
   store.jdbc.update("insert into plm_partner_file(record_id,attachment_id,purpose) values(?,?,?) on conflict do nothing",target,id,purpose);
  }
 }
 /** Same document library, owner references and audit as normal upload; no second file catalogue. */
 @Transactional public AttachmentVO uploadResumable(long id,PartnerPathFile file,String purpose,HttpServletRequest request){
  sources.write(id,true);store.access.requireEngineeringMaintenance();store.access.requirePermission("partner:write");
  require(Set.of("cooperation_cost","photo").contains(purpose),"分片资料用途无效");
  var created=documents.uploadResumable(file,purpose.equals("photo")?"sample_image":"other","V1","供应商／选品资料分片文件完成",List.of(new DocumentService.Reference("partner",id,"ALL")),request);
  store.jdbc.update("insert into plm_partner_file(record_id,attachment_id,purpose) values(?,?,?) on conflict(record_id,attachment_id) do update set purpose=case when plm_partner_file.purpose='financial' then 'financial' else excluded.purpose end",id,created.getAttachmentId(),purpose);
  store.event(id,"file_add",Map.of("fileId",created.getAttachmentId(),"name",created.getFileName(),"purpose",purpose,"version","V1"),"file-"+UUID.randomUUID());
  return created;
 }
 public void checkReadIfBound(long file){if(referenced(file))store.access.requireFile(file);}
 public boolean referenced(long file){return Boolean.TRUE.equals(store.jdbc.queryForObject("select exists(select 1 from plm_partner_file where attachment_id=?)",Boolean.class,file));}
 public void lock(long file){store.jdbc.queryForList("select attachment_id from plm_attachment where attachment_id=? for update",file);}
}
