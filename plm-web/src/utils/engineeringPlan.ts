import type {EngineeringPlan,EngineeringSpecification,MoldApplication} from '@/api/modules/moldApplications'
import {t as uiText} from '@/locales'
export const holeTypeNames={large:'大孔',precision:'精孔',other:'其他'} as const
const holeTypeValues={large:['large','大孔','大孔Gran agujero'],precision:['precision','精孔','精孔Agujero fino'],other:['other','其他']} as const
export function recognizedHoleType(value:string){return (Object.keys(holeTypeValues) as (keyof typeof holeTypeValues)[]).find(type=>(holeTypeValues[type] as readonly string[]).includes(value))||''}
export function mappedHoleTypeValue(type:keyof typeof holeTypeValues,options:string[]){return options.find(value=>recognizedHoleType(value)===type)||holeTypeNames[type]}
export function emptyEngineeringSpecification(key=crypto.randomUUID()):EngineeringSpecification{return {key,holeType:'',structureDescription:'',phoneModelIds:[]}}
export function copyEngineeringPlan(plan:EngineeringPlan):EngineeringPlan{return {...plan,specifications:plan.specifications.map(row=>({...row,phoneModelIds:[...row.phoneModelIds]}))}}
export function applicationScopePlan(plan:EngineeringPlan):EngineeringPlan{
 return {handling:'pending',specifications:plan.specifications.map(({key,holeType,structureDescription,phoneModelIds})=>({key,holeType,structureDescription,phoneModelIds:[...phoneModelIds]}))}
}
export function initialEngineeringPlan(row?:Partial<Omit<MoldApplication,'applicationId'>>&{applicationId:number|null}):EngineeringPlan{
 if(row?.engineeringPlan)return copyEngineeringPlan(row.engineeringPlan)
 const original=row?.form?.holeType||'',holeType=recognizedHoleType(original)
 return {handling:row?.applicationId?row.executionIntent||'pending':'pending',specifications:[{key:'spec-1',holeType,structureDescription:'',phoneModelIds:row?.applicationId&&row.phoneModelId?[row.phoneModelId]:[]}],...(row?.applicationId&&row.phoneModelId?{codePhoneModelId:row.phoneModelId}:{})}
}
export function engineeringPlanIssues(plan:EngineeringPlan):string[]{
 const issues:string[]=[]
 if(!plan.specifications.length)issues.push(uiText('请填写本次适用规格'))
 plan.specifications.forEach((row,index)=>{if(!row.holeType)issues.push(uiText('规格{number}：请明确孔型',{number:index+1}));if(row.structureDescription.length>1000)issues.push(uiText('规格{number}：结构说明最多1000字',{number:index+1}));if(row.holeType==='other'&&!row.structureDescription.trim())issues.push(uiText('规格{number}：其他孔型须填写结构说明',{number:index+1}));if((plan.handling==='reuse'||plan.handling==='new_set')&&(row.phoneModelIds.length!==1||!row.manufacturingRelationId||!row.manufacturingRelationVersion))issues.push(uiText('规格{number}：请为一款手机选择已确认的生产与模具关系',{number:index+1}))})
 return issues
}
