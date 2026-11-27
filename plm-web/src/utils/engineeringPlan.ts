import type {EngineeringPlan,EngineeringSpecification,MoldApplication} from '@/api/modules/moldApplications'
import {t as uiText} from '@/locales'
export const holeTypeNames={large:'大孔',precision:'精孔',mixed:'混合',other:'其他'} as const
const holeTypeValues={large:['large','大孔','大孔Gran agujero'],precision:['precision','精孔','精孔Agujero fino'],mixed:['mixed','混合','混合Mixto'],other:['other','其他']} as const
export function recognizedHoleType(value:string){return (Object.keys(holeTypeValues) as (keyof typeof holeTypeValues)[]).find(type=>(holeTypeValues[type] as readonly string[]).includes(value))||''}
export function mappedHoleTypeValue(type:keyof typeof holeTypeValues,options:string[]){return options.find(value=>recognizedHoleType(value)===type)||holeTypeNames[type]}
export function emptyEngineeringSpecification(key=crypto.randomUUID()):EngineeringSpecification{return {key,holeType:'',structureDescription:'',phoneModelIds:[]}}
export function copyEngineeringPlan(plan:EngineeringPlan):EngineeringPlan{return {...plan,specifications:plan.specifications.map(row=>({...row,phoneModelIds:[...row.phoneModelIds],...(row.moldSharing?{moldSharing:{...row.moldSharing,mappings:row.moldSharing.mappings.map(mapping=>({...mapping}))}}:{})}))}}
export function applicationScopePlan(plan:EngineeringPlan):EngineeringPlan{
 if(plan.codeRuleVersion===2)return copyEngineeringPlan(plan)
 return {handling:'pending',specifications:plan.specifications.map(({key,holeType,structureDescription,phoneModelIds})=>({key,holeType,structureDescription,phoneModelIds:[...phoneModelIds]}))}
}
export function initialEngineeringPlan(row?:Partial<Omit<MoldApplication,'applicationId'>>&{applicationId:number|null}):EngineeringPlan{
 if(row?.engineeringPlan)return copyEngineeringPlan(row.engineeringPlan)
 const original=row?.form?.holeType||'',holeType=recognizedHoleType(original)
 return {handling:row?.applicationId?row.executionIntent||'pending':'pending',...(!row?.applicationId?{codeRuleVersion:2,identityMode:'mold_only'}:{}),specifications:[{key:'spec-1',holeType,structureDescription:'',phoneModelIds:row?.applicationId&&row.phoneModelId?[row.phoneModelId]:[]}],...(row?.applicationId&&row.phoneModelId?{codePhoneModelId:row.phoneModelId}:{})}
}
export function engineeringPlanIssues(plan:EngineeringPlan,requireHoleType=plan.codeRuleVersion===2):string[]{
 const issues:string[]=[]
 if(!plan.specifications.length)issues.push(uiText('请填写本次适用规格'))
 if(plan.identityMode==='mold_only'&&plan.handling==='reuse'&&plan.specifications.length!==1)issues.push(uiText('直接采用请逐个自身机型办理'))
 plan.specifications.forEach((row,index)=>{if(requireHoleType&&!row.holeType)issues.push(uiText('规格{number}：请明确孔型',{number:index+1}));if(row.structureDescription.length>1000)issues.push(uiText('规格{number}：结构说明最多1000字',{number:index+1}));if(row.holeType==='other'&&!row.structureDescription.trim())issues.push(uiText('规格{number}：其他孔型须填写结构说明',{number:index+1}));if(['reuse','new_set','shared_existing'].includes(plan.handling)&&(row.phoneModelIds.length!==1||(plan.identityMode==='mold_only'?!row.moldSharing?.mappings.length:!row.manufacturingRelationId||!row.manufacturingRelationVersion)))issues.push(uiText(plan.identityMode==='mold_only'?'请为每款自身手机选择同孔型共模映射':'规格{number}：请为一款手机选择已确认的生产与模具关系',{number:index+1}));if(plan.codeRuleVersion===2&&row.holeType==='other')issues.push(uiText('新版编码请选择大孔、精孔或混合；其他不能自动视为混合'))})
 return issues
}
