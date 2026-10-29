import { describe, expect, it } from 'vitest'
import type { CollaborationRow, PlanField, RowResult } from '@/types/collaboration'
import { archiveModelName, collaborationFieldEditable, CollaborationDrafts, collaborationRedirect, equalCell, parseCell, parseClipboard, rowKey, suggestedArrivalDate, validateMoldRow } from '../collaboration'

const row = (id = 1): CollaborationRow => ({ itemId:id,productLineId:null,styleName:'薇武士',brand:'HONOR',compatibleModels:'Honor Test',phoneModelCode:'0001',phonePrice:null,priceCurrency:null,salesPlatform:null,referenceUrl:null,notes:null,pcTotal:null,tpuTotal:null,finishedTotal:null,productionNotes:null,projectCount:3,projects:[],moldSetCount:2,status:'linked',updatedAt:'2026-10-09' })
const field = (kind:string): PlanField => ({ key:'pcTotal',label:'累计数量',group:'production',kind,maxLength:0 })
const result = (id:number,status:RowResult['status']): RowResult => ({ entityType:'item',id,status,message:status,conflicts:[],row:null })

describe('collaboration cells and Excel clipboard', () => {
  it('redirects old module links without losing archive, mold or task positioning', () => {
    expect(collaborationRedirect({view:'market',itemId:'738',productLineId:'55'})).toEqual({path:'/market-watch',query:{itemId:'738',productLineId:'55',panel:'market-data'}})
    expect(collaborationRedirect({view:'production',itemId:'738'})).toEqual({path:'/production-totals',query:{itemId:'738'}})
    expect(collaborationRedirect({view:'delivery',moldSetId:'86',taskKey:'ship'})).toEqual({path:'/mold-schedules',query:{moldSetId:'86',taskKey:'ship',tab:'tasks'}})
  })
  it('keeps unknown distinct from zero and never reads quantities from remarks', () => {
    expect(parseCell(field('count'),'')).toBeNull();expect(parseCell(field('count'),'0')).toBe(0)
    expect(parseCell(field('count'),'5,400')).toBe(5400)
    for (const text of ['OK 5400','-1','1.5','9007199254740992']) expect(() => parseCell(field('count'),text)).toThrow()
    expect(parseCell(field('text'),'OK 5400')).toBe('OK 5400')
  })
  it('preserves leading zeros and validates real dates in supported formats', () => {
    expect(parseCell(field('code'),'0001')).toBe('0001');expect(() => parseCell(field('code'),'1')).toThrow()
    expect(parseCell(field('date'),'21/08/2026')).toBe('2026-08-21')
    expect(parseCell(field('date'),'2026/8/21')).toBe('2026-08-21')
    expect(parseCell(field('date'),'2026年8月21日')).toBe('2026-08-21')
    expect(() => parseCell(field('date'),'2026-02-30')).toThrow()
  })
  it('supports rectangular TSV including quoted multiline and trailing blank cells', () => {
    expect(parseClipboard('0\t12\t\r\n1\t2\t3\r\n')).toEqual([['0','12',''],['1','2','3']])
    expect(parseClipboard('"市场\n备注"\t"含""引号"\n')).toEqual([['市场\n备注','含"引号']])
    expect(() => parseClipboard('"未结束')).toThrow()
  })
  it('only offers a suggestion and crosses month/year boundaries correctly', () => {
    expect(suggestedArrivalDate(null)).toBeNull();expect(suggestedArrivalDate('2026-12-28')).toBe('2027-01-04')
  })
  it('allows market and engineering to choose a canonical brand without granting raw-text or other engineering edits',()=>{
    const brand:PlanField={key:'brandId',label:'品牌资料',group:'engineering',kind:'id',maxLength:0}
    expect(collaborationFieldEditable(brand,['market'])).toBe(true);expect(collaborationFieldEditable(brand,['engineering'])).toBe(true);expect(collaborationFieldEditable(brand,['production'])).toBe(false)
    expect(collaborationFieldEditable({...brand,key:'moldName'},['market'])).toBe(false);expect(collaborationFieldEditable({...brand,key:'brand',group:'market'},['market','engineering'])).toBe(false)
  })
})
describe('colour-neutral drafts and optimistic saves', () => {
  it('compares compatible members as a set while keeping confirmation boolean distinct from text', () => {
    expect(equalCell([17,18],[18,17])).toBe(true);expect(equalCell([17],[17,18])).toBe(false)
    expect(equalCell(true,'true')).toBe(false)
    const members=[17,18], drafts=new CollaborationDrafts(), master={...row(),compatiblePhoneModelIds:[17]}
    drafts.set(master,'compatiblePhoneModelIds',members);members.push(19)
    expect(drafts.patches()[0].changes[0]).toMatchObject({before:[17],after:[17,18]})
    expect(archiveModelName({...row(),currentCompatibleModels:'17 Pro / 18 Pro'})).toBe('17 Pro / 18 Pro')
  })
  it('captures related quote originals and protects each phone relation as one immutable optimistic list', () => {
    const old=[{phoneModelId:17,setIds:[3,4],pcConfirmed:true,tpuConfirmed:true,evidence:'17两套均核对'}], next=[...old,{phoneModelId:18,setIds:[3],pcConfirmed:true,tpuConfirmed:true,evidence:'18仅第1套核对'}]
    const drafts=new CollaborationDrafts(), master={...row(),compatiblePhones:old}, set={...row(),moldSetId:3,quoteConfirmed:false,quoteAmount:0,quoteCurrency:'USD',quoteBasis:'报价单'}
    drafts.set(master,'compatiblePhones',next);drafts.set(master,'pcTotal',50);drafts.set(set,'quoteConfirmed',true);next[0].setIds.push(5)
    expect(drafts.patches({entity:'item',fields:['compatiblePhones']})[0]).toMatchObject({changes:[{field:'compatiblePhones',before:[{phoneModelId:17,setIds:[3,4]}],after:[{phoneModelId:17,setIds:[3,4]},{phoneModelId:18,setIds:[3]}]}]})
    expect(equalCell([{phoneModelId:17,setIds:[4,3],pcConfirmed:true,tpuConfirmed:true,evidence:'同证据'}],[{phoneModelId:17,setIds:[3,4],pcConfirmed:true,tpuConfirmed:true,evidence:'同证据'}])).toBe(true)
    expect(drafts.patches({entity:'mold_set',fields:['quoteConfirmed']})[0].expectedValues).toEqual({quoteAmount:0,quoteCurrency:'USD',quoteBasis:'报价单'})
  })
  it('normalises explicit locations and acceptance without treating unknown as arrived',()=>{
    expect(parseCell(field('location'),'未知')).toBeNull();expect(parseCell(field('location'),'墨西哥工厂')).toBe('mexico_factory');expect(parseCell(field('acceptance'),'验收通过')).toBe('passed');expect(()=>parseCell(field('acceptance'),'')).toThrow();expect(()=>parseCell(field('location'),'快递已签收')).toThrow()
  })
  it('does not use Mexico arrival to satisfy part acceptance, and requires dates and failure notes',()=>{
    const mold:CollaborationRow={...row(),moldSetId:3,actualMexicoArrivalDate:'2026-08-01',pcAcceptanceStatus:'passed'}
    expect(validateMoldRow(mold,key=>(mold[key] as string)??null)).toContain('实际到厂日期')
    const valid:CollaborationRow={...mold,pcFactoryArrivalDate:'2026-08-03',pcAcceptanceDate:'2026-08-03'};expect(validateMoldRow(valid,key=>(valid[key] as string)??null)).toBeNull()
    const failed:CollaborationRow={...valid,tpuAcceptanceStatus:'failed'};expect(validateMoldRow(failed,key=>(failed[key] as string)??null)).toContain('问题说明')
  })
  it('stores only changed fields and keeps the original value when refreshed rows arrive', () => {
    const drafts=new CollaborationDrafts();drafts.set(row(),'pcTotal',0)
    drafts.set({...row(),pcTotal:20},'pcTotal',30)
    expect(drafts.patches()[0].changes).toEqual([{field:'pcTotal',before:null,after:30,reason:undefined}])
    expect(drafts.value({...row(),pcTotal:20},'pcTotal')).toBe(30)
    drafts.set(row(),'pcTotal',null);expect(drafts.rows.size).toBe(0)
  })
  it('keeps failed/conflicting rows while successful rows clear independently', () => {
    const drafts=new CollaborationDrafts();drafts.set(row(1),'pcTotal',1);drafts.set(row(2),'pcTotal',2)
    drafts.apply(result(1,'saved'));drafts.apply(result(2,'forbidden'))
    expect(drafts.rows.size).toBe(1);expect(drafts.rows.get('item:2')?.result?.status).toBe('forbidden')
  })
  it('requires explicit conflict resolution and updates the baseline to current, including null', () => {
    const drafts=new CollaborationDrafts();drafts.set({...row(),pcTotal:100},'pcTotal',90)
    drafts.apply({...result(1,'conflict'),conflicts:[{field:'pcTotal',label:'PC',before:100,current:null,attempted:90}]})
    drafts.resolve('item:1','pcTotal',true);drafts.set(row(),'pcTotal',80)
    expect(drafts.patches()[0].changes[0].before).toBeNull()
    drafts.apply({...result(1,'conflict'),conflicts:[{field:'pcTotal',label:'PC',before:null,current:120,attempted:80}]})
    drafts.resolve('item:1','pcTotal',false);expect(drafts.rows.size).toBe(0)
  })
  it('preserves edits made during an in-flight save and rebases only the saved field', () => {
    const drafts=new CollaborationDrafts();drafts.set(row(),'pcTotal',100)
    const submitted=drafts.patches()[0];drafts.set(row(),'pcTotal',120);drafts.set(row(),'tpuTotal',90)
    drafts.apply({...result(1,'saved'),row:{...row(),pcTotal:100}},submitted)
    expect(drafts.patches()[0].changes).toEqual([{field:'pcTotal',before:100,after:120,reason:undefined},{field:'tpuTotal',before:null,after:90,reason:undefined}])
    const next={...drafts.patches()[0],changes:drafts.patches()[0].changes.filter(c=>c.field==='pcTotal')}
    drafts.set(row(),'pcTotal',0);drafts.apply({...result(1,'saved'),row:{...row(),pcTotal:null}},next)
    expect(drafts.patches()[0].changes).toEqual([{field:'pcTotal',before:null,after:0,reason:undefined},{field:'tpuTotal',before:null,after:90,reason:undefined}])
    const zero={...drafts.patches()[0],changes:drafts.patches()[0].changes.filter(c=>c.field==='pcTotal')}
    drafts.set(row(),'pcTotal',5);drafts.apply({...result(1,'saved'),row:{...row(),pcTotal:0}},zero)
    expect(drafts.patches()[0].changes[0]).toMatchObject({before:0,after:5})
  })
  it('distinguishes mold copies but never creates one production row per color or copy', () => {
    expect(rowKey(row())).toBe('item:1');expect(rowKey({...row(),moldSetId:3})).toBe('mold_set:3')
    const drafts=new CollaborationDrafts();drafts.set(row(),'pcTotal',0);drafts.set({...row(),projectCount:7,moldSetCount:3},'tpuTotal',25)
    expect(drafts.patches()).toHaveLength(1)
  })
  it('retains an explicit unchanged-date confirmation for the server basis audit', () => {
    const mold={...row(),moldSetId:3,plannedFactoryArrivalDate:'2026-08-21'}
    const drafts=new CollaborationDrafts();drafts.set(mold,'plannedFactoryArrivalDate','2026-08-21',true)
    expect(drafts.patches()[0].changes[0]).toMatchObject({before:'2026-08-21',after:'2026-08-21'})
    const submitted=drafts.patches()[0];drafts.set(mold,'plannedFactoryArrivalDate','2026-08-22',true)
    drafts.apply({entityType:'mold_set',id:3,status:'saved',message:'saved',conflicts:[],row:mold},submitted)
    expect(drafts.rows.get('mold_set:3')?.changes.get('plannedFactoryArrivalDate')).toMatchObject({before:'2026-08-21',after:'2026-08-22',confirm:true})
    drafts.clear();expect(drafts.patches()).toHaveLength(0)
  })
  it('saves and discards only the active module or task fields, preserving hidden drafts', () => {
    const drafts=new CollaborationDrafts(), item=row(), mold={...item,moldSetId:3}
    drafts.set(item,'brand','Apple');drafts.set(item,'pcTotal',0);drafts.set(mold,'actualShipDate','2026-10-13');drafts.set(mold,'pcAcceptanceIssues','待核对')
    const market={entity:'item' as const,fields:['brand']}, task={entity:'mold_set' as const,id:3,fields:['actualShipDate']}
    expect(drafts.patches(market)[0].changes.map(c=>c.field)).toEqual(['brand'])
    const submitted=drafts.patches(task)[0];drafts.apply({...result(3,'saved'),entityType:'mold_set',row:{...mold,actualShipDate:'2026-10-13'}},submitted)
    expect(drafts.patches(task)).toEqual([]);expect(drafts.patches()).toHaveLength(2)
    drafts.clear(market);expect(drafts.patches()[0].changes.map(c=>c.field)).toEqual(['pcTotal'])
    expect(drafts.patches()[1].changes.map(c=>c.field)).toEqual(['pcAcceptanceIssues'])
  })
})
