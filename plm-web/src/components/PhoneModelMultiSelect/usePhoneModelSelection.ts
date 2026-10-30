import {computed,ref,shallowRef,watch,onBeforeUnmount,type ComputedRef} from 'vue'
import * as api from '@/api/modules/phoneReference'
import {useUserStore} from '@/stores/user'
import {comparePhoneCodes} from '@/utils/productionStatus'
import type {CompatiblePhone,PhoneBrand,ReferenceQuery} from '@/types/molds'
import {matchesPhone,phoneSeries,selectionSearchTerm,unavailablePhone,type PhoneModelMultiSelectProps} from './selection'

const PAGE_SIZE=50, BULK_PAGE_SIZE=100, MAX_BULK=10000
const unique=(ids:number[])=>[...new Set(ids)].sort((a,b)=>a-b)
const same=(a:number[],b:number[])=>unique(a).join(',')===unique(b).join(',')
export function usePhoneModelSelection(props:PhoneModelMultiSelectProps,disabled:ComputedRef<boolean>,commit:(ids:number[],phones:CompatiblePhone[])=>void) {
  const user=useUserStore(),opened=ref(false),draft=ref<number[]>([]),openingIds=ref<number[]>([])
  const phones=shallowRef(new Map<number,CompatiblePhone>()),brands=ref<PhoneBrand[]>([])
  const brandId=ref<number|null>(props.brandId || null),search=ref(''),page=ref(1),total=ref(0),rows=ref<CompatiblePhone[]>([])
  const loading=ref(false),membersLoading=ref(false),brandsLoading=ref(false),bulkLoading=ref(false)
  const error=ref(''),memberError=ref(''),brandError=ref(''),selectionError=ref(''),externalChanged=ref(false)
  const fullScope=shallowRef<CompatiblePhone[]|null>(null),groupScopes=shallowRef(new Map<string,CompatiblePhone[]>())
  let initializing=false
  let queryEpoch=0,memberEpoch=0,brandEpoch=0,bulkEpoch=0,openEpoch=0,timer:ReturnType<typeof setTimeout>|undefined
  const scoped=computed(()=>props.allowedIds!==undefined),allowed=computed(()=>new Set(props.allowedIds || []))
  const selectedSet=computed(()=>new Set(draft.value))
  function merge(values:CompatiblePhone[]) {const next=new Map(phones.value);values.forEach(p=>next.set(p.phoneModelId,p));phones.value=next}
  const allBrands=computed(()=>{
    const dictionary=new Map<number,PhoneBrand>(scoped.value?[]:brands.value.map(b=>[b.brandId,b]))
    for(const p of phones.value.values())if(!scoped.value||allowed.value.has(p.phoneModelId)||draft.value.includes(p.phoneModelId)) {
      if(!dictionary.has(p.brandId))dictionary.set(p.brandId,{id:p.brandId,brandId:p.brandId,name:p.brandName,status:p.brandStatus || 'enabled'})
    }
    return [...dictionary.values()].sort((a,b)=>a.name.localeCompare(b.name))
  })
  const brandCounts=computed(()=>{const counts=new Map<number,number>();for(const id of draft.value){const p=phones.value.get(id);if(p)counts.set(p.brandId,(counts.get(p.brandId)||0)+1)}return counts})
  const localMatches=computed(()=>[...phones.value.values()].filter(p=>allowed.value.has(p.phoneModelId)&&(!brandId.value||p.brandId===brandId.value)&&matchesPhone(p,search.value)).sort(comparePhoneCodes))
  const groups=computed(()=>{
    const values=new Map<string,{key:string;name:string;brandId:number;brandName:string;phones:CompatiblePhone[]}>()
    for(const phone of rows.value){const series=phoneSeries(phone);if(!values.has(series.key))values.set(series.key,{...series,brandId:phone.brandId,brandName:phone.brandName,phones:[]});values.get(series.key)!.phones.push(phone)}
    return [...values.values()]
  })
  const knownScope=computed(()=>scoped.value ? localMatches.value : fullScope.value)
  const selectableScope=computed(()=>knownScope.value?.filter(p=>!unavailablePhone(p)))
  const allSelected=computed(()=>!!selectableScope.value?.length && selectableScope.value.every(p=>selectedSet.value.has(p.phoneModelId)))
  const someSelected=computed(()=>!allSelected.value && (knownScope.value || rows.value).some(p=>selectedSet.value.has(p.phoneModelId)))
  const canBulk=computed(()=>props.multiple!==false && !disabled.value && !loading.value && !membersLoading.value && !bulkLoading.value && !error.value && !memberError.value && !!total.value && (scoped.value || !!brandId.value || /[\p{L}\p{N}]/u.test(selectionSearchTerm(search.value))))
  const canConfirm=computed(()=>!disabled.value && !bulkLoading.value && !membersLoading.value && !externalChanged.value && !!user.token && (props.multiple!==false||draft.value.length<=1) && draft.value.every(id=>phones.value.has(id)))
  async function loadBrands() {
    if(!user.token||scoped.value)return
    const ticket=++brandEpoch,auth=user.token;brandsLoading.value=true;brandError.value=''
    try {const result=await api.getPhoneSelectionBrands();if(!Array.isArray(result))throw new Error('invalid');if(ticket===brandEpoch&&auth===user.token)brands.value=result}
    catch {if(ticket===brandEpoch&&auth===user.token)brandError.value='品牌读取失败，请重试'}
    finally {if(ticket===brandEpoch)brandsLoading.value=false}
  }
  async function loadMembers(all=false) {
    const ticket=++memberEpoch,auth=user.token
    if(!auth)return
    merge(props.members || []);memberError.value=''
    const ids=unique(all?[...props.modelValue,...draft.value,...props.allowedIds || []]:props.modelValue.slice(0,Math.max(0,Math.min(5,props.previewCount ?? 2)))).filter(id=>!phones.value.has(id)||(all&&scoped.value&&!phones.value.get(id)?.searchTexts))
    if(!ids.length){membersLoading.value=false;return}
    membersLoading.value=true
    try {
      for(let start=0;start<ids.length;start+=100){const values=await api.getPhoneModelOptions(ids.slice(start,start+100));if(ticket!==memberEpoch||auth!==user.token)return;merge(values)}
      if(all&&ids.some(id=>!phones.value.has(id)))memberError.value='部分型号资料缺失，原 ID 保留；请重试资料或移除缺失项'
    } catch {if(ticket===memberEpoch&&auth===user.token)memberError.value='型号资料读取失败；已选 ID 保留，请重试'}
    finally {if(ticket===memberEpoch)membersLoading.value=false}
  }
  function query(brand=brandId.value,currentPage=page.value,size=PAGE_SIZE):ReferenceQuery {
    return {page:currentPage,size,keyword:selectionSearchTerm(search.value),selectionSearch:true,...brand?{brandId:brand}:{},...(props.unassignedCode?{unassignedCode:true}:{})}
  }
  async function loadPage(next=page.value) {
    clearTimeout(timer);const ticket=++queryEpoch,auth=user.token
    if(!opened.value||!auth)return
    bulkEpoch++;bulkLoading.value=false;selectionError.value='';error.value='';page.value=next;loading.value=true
    if(scoped.value){total.value=localMatches.value.length;rows.value=localMatches.value.slice((next-1)*PAGE_SIZE,next*PAGE_SIZE);loading.value=false;return}
    try {const result=await api.getPhoneModels(query());if(ticket!==queryEpoch||auth!==user.token||!opened.value)return;merge(result.content);rows.value=result.content;total.value=result.totalElements ?? result.content.length;if(page.value===1&&result.content.length===total.value)fullScope.value=result.content}
    catch {if(ticket===queryEpoch&&auth===user.token){rows.value=[];error.value='型号读取失败，请重试'}}
    finally {if(ticket===queryEpoch)loading.value=false}
  }
  function filterChanged(debounce=false) {
    if(initializing)return
    clearTimeout(timer);queryEpoch++;bulkEpoch++;bulkLoading.value=false;fullScope.value=null;groupScopes.value=new Map();rows.value=[];total.value=0;page.value=1;error.value='';selectionError.value=''
    if(!opened.value)return
    loading.value=true
    if(debounce)timer=setTimeout(()=>void loadPage(1),250);else void loadPage(1)
  }
  function setBrand(value:number|null){if(disabled.value||props.brandId)return;brandId.value=value}
  function toggle(id:number) {
    if(disabled.value||bulkLoading.value||!user.token)return
    if(selectedSet.value.has(id)){draft.value=draft.value.filter(v=>v!==id);return}
    const phone=phones.value.get(id)
    if(!phone||unavailablePhone(phone)||(scoped.value&&!allowed.value.has(id))||(props.brandId&&phone.brandId!==props.brandId))return
    draft.value=props.multiple===false?[id]:[...draft.value,id];selectionError.value=''
  }
  function clear(){if(!disabled.value&&!bulkLoading.value)draft.value=[]}
  function groupSelected(key:string) {
    const values=groupScopes.value.get(key) || selectableScope.value?.filter(p=>phoneSeries(p).key===key)
    return !!values?.length&&values.every(p=>selectedSet.value.has(p.phoneModelId))
  }
  async function bulkSelect(group?:{key:string;brandId:number}) {
    if(disabled.value||!opened.value||!user.token||props.multiple===false||bulkLoading.value||loading.value||memberError.value)return
    if(!group&&!canBulk.value)return
    const ticket=++bulkEpoch,filterTicket=queryEpoch,auth=user.token
    const current=()=>ticket===bulkEpoch&&filterTicket===queryEpoch&&auth===user.token&&opened.value&&!disabled.value
    bulkLoading.value=true;selectionError.value=''
    try {
      let values:CompatiblePhone[]
      if(scoped.value)values=localMatches.value
      else if(fullScope.value)values=fullScope.value
      else {
        values=[];let expected=-1
        for(let next=1;;next++){
          const result=await api.getPhoneModels(query(group?.brandId || brandId.value,next,BULK_PAGE_SIZE));if(!current())return
          const count=result.totalElements ?? result.content.length
          if(expected<0){expected=count;if(!group&&count!==total.value)throw new Error('结果数量已变化，请刷新结果后重选')}
          if(count!==expected)throw new Error('结果在读取中发生变化，请刷新后重试')
          if(expected>MAX_BULK)throw new Error('当前结果超过 10000 个，请缩小品牌或搜索范围后选择')
          values.push(...result.content)
          if(values.length>=expected)break
          if(!result.content.length)throw new Error('型号结果不完整，请重试')
        }
        if(new Set(values.map(p=>p.phoneModelId)).size!==expected)throw new Error('型号结果已变化，请刷新后重试')
        if(!group||group.brandId===brandId.value)fullScope.value=values
      }
      if(!current())return
      values=values.filter(p=>(!group||phoneSeries(p).key===group.key)&&!unavailablePhone(p)&&(!props.brandId||p.brandId===props.brandId)&&(!scoped.value||allowed.value.has(p.phoneModelId)))
      merge(values)
      if(group)groupScopes.value=new Map([...groupScopes.value,[group.key,values]])
      const ids=new Set(values.map(p=>p.phoneModelId)),remove=values.length>0&&values.every(p=>selectedSet.value.has(p.phoneModelId))
      draft.value=remove?draft.value.filter(id=>!ids.has(id)):unique([...draft.value,...ids])
    } catch(failure){if(current())selectionError.value=failure instanceof Error?failure.message:'批量读取失败，请重试'}
    finally {if(ticket===bulkEpoch)bulkLoading.value=false}
  }
  async function open() {
    if(disabled.value||!user.token)return
    const openTicket=++openEpoch,auth=user.token
    initializing=true;loading.value=true;openingIds.value=unique(props.modelValue);draft.value=[...openingIds.value];externalChanged.value=false;selectionError.value=''
    brandId.value=props.brandId || null;search.value='';page.value=1;fullScope.value=null;opened.value=true
    void loadBrands();await loadMembers(true);if(openTicket!==openEpoch||auth!==user.token||!opened.value)return;initializing=false;await loadPage(1)
  }
  function cancel(){openEpoch++;initializing=false;opened.value=false;clearTimeout(timer);queryEpoch++;bulkEpoch++;bulkLoading.value=false;loading.value=false;draft.value=[];selectionError.value=''}
  function confirm() {
    if(!canConfirm.value)return
    const added=draft.value.filter(id=>!openingIds.value.includes(id))
    if(added.some(id=>{const p=phones.value.get(id);return !p||!!unavailablePhone(p)||(scoped.value&&!allowed.value.has(id))||!!props.brandId&&p.brandId!==props.brandId})){selectionError.value='部分新增型号已不可用或不在当前允许范围，请移除后重选';return}
    const ids=unique(draft.value);commit(ids,ids.map(id=>phones.value.get(id)).filter((p):p is CompatiblePhone=>!!p));cancel()
  }
  async function refresh(){phones.value=new Map();merge(props.members || []);await Promise.all([loadMembers(opened.value),loadBrands()]);if(opened.value)filterChanged()}
  watch(search,()=>filterChanged(true));watch(brandId,()=>filterChanged())
  watch(()=>[props.modelValue,props.members,props.allowedIds] as const,()=>{if(opened.value&&!same(props.modelValue,openingIds.value))externalChanged.value=true;void loadMembers(opened.value)},{deep:true})
  watch(localMatches,()=>{if(opened.value&&scoped.value)void loadPage(Math.min(page.value,Math.max(1,Math.ceil(localMatches.value.length/PAGE_SIZE))))})
  watch(()=>props.brandId,value=>{brandId.value=value || null})
  watch(disabled,value=>{if(value)cancel()})
  watch(()=>user.token,()=>{cancel();memberEpoch++;brandEpoch++;phones.value=new Map();brands.value=[];rows.value=[];error.value='';memberError.value='';brandError.value='';membersLoading.value=false;brandsLoading.value=false;if(user.token)void loadMembers()},{immediate:true})
  onBeforeUnmount(()=>{cancel();memberEpoch++;brandEpoch++})
  return {opened,draft,phones,allBrands,brandCounts,brandId,search,page,total,rows,groups,loading,membersLoading,brandsLoading,bulkLoading,error,memberError,brandError,selectionError,externalChanged,selectedSet,allSelected,someSelected,canBulk,canConfirm,scoped,open,cancel,confirm,toggle,clear,setBrand,loadPage,loadMembers,loadBrands,bulkSelect,groupSelected,refresh,PAGE_SIZE}
}
