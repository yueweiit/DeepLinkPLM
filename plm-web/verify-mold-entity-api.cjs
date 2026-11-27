// Synthetic writes are allowed only in the isolated QA service, never in 8111.
const assert=require('node:assert/strict');
(async()=>{
  const base='http://127.0.0.1:18114/api/v1';let token;
  async function api(path,body,allowFailure=false){const res=await fetch(base+path,{method:body?'POST':'GET',headers:{'content-type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body?{body:JSON.stringify(body)}:{})});const value=await res.json();if(!allowFailure)assert.equal(value.code,0,JSON.stringify({path,code:value.code,message:value.message}));return value;}
  token=(await api('/auth/login',{username:process.env.PLM_QA_USERNAME,password:process.env.PLM_QA_PASSWORD})).data.token;
  const source=(await api('/manufacturing-relations?page=1&size=100&status=active')).data.content.find(row=>row.productLineId===51&&row.phoneModelId===1393);assert.ok(source,'Isolated legacy fixture required');
  const input={productLineId:51,sourceRelationId:source.relationId||source.id,sourceRelationVersion:source.relationVersion||source.version,targetPhoneModelId:Number(process.env.PLM_QA_TARGET_PHONE),holeType:'precision',evidence:'QA隔离模拟：人工确认精孔整套共模，非真实生产事实',reason:'QA隔离模拟：验证旧码生成新映射'};
  const preview=(await api('/mold-share-mappings/entities/preview',input)).data;
  assert.equal(preview.codes.length,2);for(const row of preview.codes){assert.match(row.primaryCode,/^MBA[12]01041$/);assert.ok(row.sourceMappingCode.endsWith('J'+source.businessPhone.phoneModelCode));}
  const before=(await api('/mold-share-mappings/entities?page=1&size=100&status=all')).data;
  const saved=(await api('/mold-share-mappings/entities/confirm',input)).data;assert.equal(saved.mappings.length,2);
  const after=(await api('/mold-share-mappings/entities?page=1&size=100&status=all')).data;assert.equal(after.totalElements-before.totalElements,4);
  await api('/mold-share-mappings/entities/confirm',input);assert.equal((await api('/mold-share-mappings/entities?page=1&size=100&status=all')).data.totalElements,after.totalElements);
  for(const code of preview.codes.flatMap(row=>[row.sourceMappingCode,row.mappingCode])){const parsed=(await api('/mold-share-mappings/resolve-code?code='+code)).data;assert.equal(parsed.mold_set_id,source.moldSetId);}
  const mismatch=await api('/mold-share-mappings/entities/preview',{...input,holeType:'large'},true);assert.notEqual(mismatch.code,0);
  const target=(await api('/manufacturing-relations?page=1&size=100&status=active')).data.content.find(row=>row.phoneModelId===input.targetPhoneModelId&&row.moldSetId===source.moldSetId);assert.ok(target);
  const thirdInput={...input,sourceRelationId:target.relationId||target.id,sourceRelationVersion:target.relationVersion||target.version,targetPhoneModelId:Number(process.env.PLM_QA_THIRD_PHONE)};
  const third=(await api('/mold-share-mappings/entities/confirm',thirdInput)).data;for(const row of third.mappings)assert.equal(row.moldSetId,source.moldSetId);
  console.log(JSON.stringify({legacyPreview:true,sourceAndTargetAliasesCreated:true,duplicateReusesAliases:true,allAliasesResolveToSameMold:true,holeMismatchBlocked:true,anyGroupPhoneCanAddMember:true,codes:preview.codes},null,2));
})().catch(e=>{console.error(e.message);process.exit(1)});
