<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,inject,nextTick,ref,useId,watch} from 'vue'
import {formContextKey,type InputInstance} from 'element-plus'
import {ArrowDown,Search} from '@element-plus/icons-vue'
import {onClickOutside,useEventListener} from '@vueuse/core'
import {useUserStore} from '@/stores/user'
import type {CompatiblePhone} from '@/types/molds'
import {phoneSelectionLabel,unavailablePhone,type PhoneModelMultiSelectProps} from './selection'
import {usePhoneModelSelection} from './usePhoneModelSelection'

const props=withDefaults(defineProps<PhoneModelMultiSelectProps>(),{members:()=>[],disabled:false,multiple:true,previewCount:2,placeholder:'请选择手机品牌及型号',ariaLabel:'手机型号'})
const emit=defineEmits<{'update:modelValue':[number[]];selected:[CompatiblePhone[]];confirm:[number[]];cancel:[]}>()
const form=inject(formContextKey,undefined),user=useUserStore(),isDisabled=computed(()=>props.disabled||!!form?.disabled)
const picker=usePhoneModelSelection(props,isDisabled,(ids,phones)=>{emit('update:modelValue',ids);emit('selected',phones);emit('confirm',ids)})
const {opened,draft,phones,allBrands,brandCounts,brandId,search,page,total,groups,loading,membersLoading,brandsLoading,bulkLoading,error,memberError,brandError,selectionError,externalChanged,selectedSet,allSelected,someSelected,canBulk,canConfirm,open,confirm,toggle,clear,setBrand,loadPage,loadMembers,loadBrands,bulkSelect,groupSelected,refresh,PAGE_SIZE}=picker
const trigger=ref<HTMLButtonElement>(),panel=ref<HTMLElement>(),searchInput=ref<InputInstance>(),modelList=ref<HTMLElement>()
const restoreFocus=ref(true),panelHeight=ref(620)
const panelId=`phone-model-panel-${useId()}`,selectedPage=ref(1),TAG_PAGE_SIZE=20
const confirmedIds=computed(()=>[...new Set(props.modelValue)])
const preview=computed(()=>confirmedIds.value.slice(0,Math.max(0,Math.min(5,props.previewCount))))
const selectedTags=computed(()=>draft.value.slice((selectedPage.value-1)*TAG_PAGE_SIZE,selectedPage.value*TAG_PAGE_SIZE))
const selectedPages=computed(()=>Math.max(1,Math.ceil(draft.value.length/TAG_PAGE_SIZE)))
const text=(id:number)=>{const phone=phones.value.get(id);return phone?phoneSelectionLabel(phone):`型号 #${id}（资料待加载）`}
function cancel(focus=true){restoreFocus.value=focus;picker.cancel();emit('cancel')}
function togglePanel(){if(opened.value)cancel();else void open()}
function measurePanel(){
  if(!opened.value||!trigger.value)return
  const bounds=trigger.value.getBoundingClientRect(),height=window.innerHeight
  panelHeight.value=Math.min(620,Math.max(280,bounds.top-24,height-bounds.bottom-24),height-32)
}
function focusSearch(){measurePanel();searchInput.value?.focus()}
function focusBrand(event:KeyboardEvent,direction:number){const buttons=[...(event.currentTarget as HTMLElement).parentElement!.querySelectorAll<HTMLButtonElement>('button')];const next=buttons.indexOf(event.currentTarget as HTMLButtonElement)+direction;buttons[(next+buttons.length)%buttons.length]?.focus()}
function trapFocus(event:KeyboardEvent){
  if(event.key!=='Tab'||!panel.value)return
  const elements=[...panel.value.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),[tabindex="0"]')].filter(e=>e.tabIndex>=0&&e.getClientRects().length)
  const first=elements[0],last=elements.at(-1)
  if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus()}
  else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus()}
}
onClickOutside(panel,()=>{if(opened.value)cancel(false)},{ignore:[trigger]})
useEventListener(window,'resize',measurePanel)
useEventListener(document,'scroll',measurePanel,{capture:true,passive:true})
watch(opened,async value=>{if(value){restoreFocus.value=true;selectedPage.value=1;await nextTick();focusSearch()}else{await nextTick();if(restoreFocus.value)trigger.value?.focus({preventScroll:true})}})
watch(()=>draft.value.length,()=>{selectedPage.value=Math.min(selectedPage.value,selectedPages.value)})
watch(()=>picker.rows.value,async()=>{await nextTick();if(modelList.value)modelList.value.scrollTop=0})
defineExpose({open,cancel,refresh})
</script>
<template>
  <div class="phone-model-multi-select">
    <el-popover role="dialog" :id="panelId" :title="uiText('{value0}选择面板',{value0:(ariaLabel)})" popper-class="phone-model-selector-popover" :visible="opened" placement="bottom-start" :width="720" :show-arrow="false" :persistent="false" :popper-style="{padding:'0',maxWidth:'calc(100vw - 24px)'}" :popper-options="{strategy:'fixed',modifiers:[{name:'preventOverflow',options:{padding:12,altAxis:true,tether:false}}]}" @after-enter="focusSearch">
      <template #reference>
        <button ref="trigger" type="button" class="phone-model-trigger" role="combobox" aria-haspopup="dialog" :aria-expanded="opened" :aria-controls="panelId" :aria-label="ariaLabel" :disabled="isDisabled||!user.token" @click="togglePanel" @keydown.down.prevent="open" @keydown.esc.prevent="cancel()">
          <span v-if="!modelValue.length||!user.token" class="trigger-placeholder">{{placeholder}}</span>
          <span v-else class="trigger-tags"><el-tag v-for="id in preview" :key="id" size="small" type="info">{{text(id)}}</el-tag><span v-if="confirmedIds.length>preview.length" class="trigger-more">+{{confirmedIds.length-preview.length}}{{ uiText(" 个") }}</span></span>
          <el-icon class="trigger-arrow" :class="{expanded:opened}"><ArrowDown/></el-icon>
        </button>
      </template>
      <section v-if="opened" :id="`${panelId}-content`" ref="panel" class="phone-model-panel" :style="{height:`${panelHeight}px`}" @keydown.esc.stop.prevent="cancel()" @keydown="trapFocus">
        <header class="panel-search"><el-input ref="searchInput" v-model="search" size="small" maxlength="128" clearable :prefix-icon="Search" :placeholder='uiText("搜索品牌、系列、型号或别名，如 17PM、Samsung S26")' :aria-label='uiText("搜索手机品牌及型号")'/><div v-if="$slots.tools" class="panel-tools"><slot name="tools"/></div></header>
        <div v-if="externalChanged" class="panel-error" role="alert">{{ uiText("外部选择已变化，请取消后重新打开，避免覆盖新内容。") }}</div>
        <div v-if="brandError||memberError" class="panel-error" role="alert"><span>{{brandError||memberError}}</span><el-button size="small" link @click="brandError?loadBrands():loadMembers(true)">{{ uiText("重试资料") }}</el-button></div>
        <div class="selection-body">
          <nav class="brand-nav" :aria-label='uiText("手机品牌分类")' :aria-busy="brandsLoading">
            <button v-if="!props.brandId" type="button" :class="{active:!brandId}" :aria-current="!brandId?'true':undefined" @click="setBrand(null)" @keydown.down.prevent="focusBrand($event,1)" @keydown.up.prevent="focusBrand($event,-1)"><span>{{ uiText("全部品牌") }}</span><small v-if="draft.length">{{draft.length}}</small></button>
            <button v-for="brand in allBrands.filter(b=>!props.brandId||b.brandId===props.brandId)" :key="brand.brandId" type="button" :data-brand-id="brand.brandId" :class="{active:brandId===brand.brandId}" :aria-current="brandId===brand.brandId?'true':undefined" @click="setBrand(brand.brandId)" @keydown.down.prevent="focusBrand($event,1)" @keydown.up.prevent="focusBrand($event,-1)"><span>{{brand.name}}<small v-if="brand.status==='disabled'">{{ uiText("（已停用）") }}</small></span><small v-if="brandCounts.get(brand.brandId)" :aria-label="uiText('已选 {value0} 个',{value0:(brandCounts.get(brand.brandId))})">{{brandCounts.get(brand.brandId)}}</small></button>
            <span v-if="brandsLoading" class="brand-hint">{{ uiText("品牌加载中…") }}</span><span v-else-if="!allBrands.length&&!brandError" class="brand-hint">{{ uiText("暂无品牌资料") }}</span>
          </nav>
          <div class="model-results" :aria-busy="loading||membersLoading||bulkLoading">
            <div class="results-toolbar"><el-checkbox v-if="multiple" size="small" :model-value="allSelected" :indeterminate="someSelected" :disabled="!canBulk" @change="bulkSelect()">{{ uiText("全选当前筛选结果") }}</el-checkbox><span v-else class="single-hint">{{ uiText("选择一款手机后点击完成") }}</span><span class="result-count">{{total}}{{ uiText(" 个型号") }}</span></div>
            <p v-if="multiple&&!brandId&&!search.trim()&&allowedIds===undefined" class="bulk-hint">{{ uiText("选品牌或输入搜索条件后可批量选择。") }}</p>
            <div v-if="selectionError" class="panel-error" role="alert">{{selectionError}}</div>
            <div ref="modelList" class="model-scroll">
              <p v-if="loading||membersLoading" class="result-message" role="status">{{ uiText("正在读取型号…") }}</p>
              <div v-else-if="error" class="result-message" role="alert"><p>{{error}}</p><el-button size="small" @click="loadPage()">{{ uiText("重试型号") }}</el-button></div>
              <p v-else-if="!groups.length" class="result-message" role="status">{{search.trim()?uiText('没有匹配的品牌或型号，请调整搜索条件'):uiText('暂无可选型号')}}</p>
              <template v-else><section v-for="group in groups" :key="group.key" class="model-series" :aria-label="`${group.brandName} ${group.name}`"><div class="series-heading"><strong><span v-if="!brandId">{{group.brandName}} · </span>{{group.name}}</strong><el-button v-if="multiple" size="small" link :disabled="isDisabled||bulkLoading" :aria-label="uiText('{value0}{value1} {value2}本组',{value0:(groupSelected(group.key)?'取消':'选择'),value1:(group.brandName),value2:(group.name)})" @click="bulkSelect(group)">{{groupSelected(group.key)?uiText('取消本组'):uiText('选择本组')}}</el-button></div><el-checkbox v-for="phone in group.phones" :key="phone.phoneModelId" size="small" class="model-checkbox" :model-value="selectedSet.has(phone.phoneModelId)" :disabled="isDisabled||bulkLoading||!!unavailablePhone(phone)&&!selectedSet.has(phone.phoneModelId)" @change="toggle(phone.phoneModelId)">{{phoneSelectionLabel(phone)}}</el-checkbox></section></template>
            </div>
            <div class="model-pagination"><span v-if="bulkLoading" role="status">{{ uiText("正在读取当前范围，完成后统一勾选…") }}</span><el-pagination v-else-if="total>PAGE_SIZE" size="small" :current-page="page" :page-size="PAGE_SIZE" :total="total" :pager-count="5" layout="prev,pager,next" :disabled="loading||membersLoading" @current-change="loadPage"/><span v-else>{{ uiText("每页最多 ") }}{{PAGE_SIZE}}{{ uiText(" 个型号") }}</span></div>
          </div>
        </div>
        <footer class="selection-footer">
          <div class="selected-heading"><strong>{{ uiText("已选择 ") }}{{draft.length}}{{ uiText(" 个型号") }}</strong><el-button size="small" link :disabled="!draft.length||bulkLoading||isDisabled" @click="clear">{{ uiText("清空全部") }}</el-button></div>
          <div class="selected-tags" :aria-label='uiText("本次已选型号")'><el-tag v-for="id in selectedTags" :key="id" size="small" :closable="!isDisabled&&!bulkLoading" @close="toggle(id)">{{text(id)}}</el-tag><span v-if="!draft.length" class="no-selection">{{ uiText("尚未选择型号") }}</span></div>
          <div v-if="selectedPages>1" class="selected-pagination" :aria-label='uiText("已选型号分页")'><el-button size="small" link :disabled="selectedPage===1" @click="selectedPage--">{{ uiText("上一页已选") }}</el-button><span>{{selectedPage}} / {{selectedPages}}{{ uiText(" · 每页 ") }}{{TAG_PAGE_SIZE}}{{ uiText(" 个") }}</span><el-button size="small" link :disabled="selectedPage===selectedPages" @click="selectedPage++">{{ uiText("下一页已选") }}</el-button></div>
          <div class="selection-actions"><span class="selection-note">{{multiple?uiText('可跨品牌连续选择，完成后生效'):uiText('本字段仅选择一款手机')}}</span><el-button size="small" @click="cancel()">{{ uiText("取消") }}</el-button><el-button size="small" type="primary" :disabled="!canConfirm" @click="confirm">{{ uiText("完成选择（") }}{{draft.length}}）</el-button></div>
        </footer>
      </section>
    </el-popover>
    <p v-if="memberError&&!opened" class="closed-error" role="alert">{{memberError}}<el-button size="small" link @click="loadMembers()">{{ uiText("重试") }}</el-button></p>
  </div>
</template>
<style scoped>
.phone-model-multi-select{width:100%;min-width:0;max-width:100%;font-size:12px}
.phone-model-trigger{display:flex;align-items:center;gap:6px;width:100%;min-height:28px;padding:3px 8px;border:1px solid var(--el-border-color);border-radius:var(--el-border-radius-base);background:var(--el-fill-color-blank);color:var(--el-text-color-regular);font:inherit;text-align:left;cursor:pointer}
.phone-model-trigger:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}.phone-model-trigger:hover:enabled{border-color:var(--el-color-primary)}.phone-model-trigger:disabled{cursor:not-allowed;background:var(--el-disabled-bg-color);color:var(--el-disabled-text-color)}
.trigger-placeholder{flex:1;line-height:20px;color:var(--el-text-color-placeholder)}.trigger-tags{display:flex;flex:1;flex-wrap:wrap;align-items:center;gap:4px;min-width:0}.trigger-tags :deep(.el-tag){height:auto;max-width:100%}.trigger-tags :deep(.el-tag__content){white-space:normal;overflow-wrap:anywhere;line-height:18px}.trigger-more{white-space:nowrap;color:var(--el-text-color-secondary)}.trigger-arrow{flex-shrink:0;color:var(--el-text-color-secondary)}.trigger-arrow.expanded{transform:rotate(180deg)}
.phone-model-panel{display:flex;flex-direction:column;height:min(620px,calc(100dvh - 32px));max-height:calc(100dvh - 32px);min-height:0;min-width:0;font-size:12px;color:var(--el-text-color-regular);background:var(--el-bg-color);border-radius:var(--el-border-radius-base);overflow:hidden}
.panel-search{padding:10px 12px;border-bottom:1px solid var(--el-border-color-lighter);flex-shrink:0}.panel-tools{display:flex;justify-content:flex-end;gap:8px;flex-wrap:wrap;margin-top:4px}.panel-tools :deep(.el-button){font-size:12px;margin:0}
.selection-body{display:grid;grid-template-columns:138px minmax(0,1fr);flex:1;min-height:120px;overflow:hidden}.brand-nav{padding:6px;border-right:1px solid var(--el-border-color-lighter);overflow:auto;min-width:0}.brand-nav button{display:flex;justify-content:space-between;align-items:center;gap:6px;width:100%;min-height:32px;border:0;border-radius:4px;padding:6px 8px;color:inherit;background:transparent;font:inherit;text-align:left;cursor:pointer;overflow-wrap:anywhere}.brand-nav button.active{color:var(--el-color-primary);background:var(--el-color-primary-light-9);font-weight:600}.brand-nav button:hover{background:var(--el-fill-color-light)}.brand-nav button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:-2px}.brand-nav small{font-size:11px;color:var(--el-text-color-secondary)}.brand-hint{display:block;padding:8px;line-height:18px;color:var(--el-text-color-secondary)}
.model-results{min-height:0;min-width:0;display:flex;flex-direction:column}.results-toolbar{min-height:36px;display:flex;align-items:center;justify-content:space-between;gap:8px;padding:4px 12px;flex-wrap:wrap;border-bottom:1px solid var(--el-border-color-extra-light)}.result-count{color:var(--el-text-color-secondary);white-space:nowrap}.bulk-hint{color:var(--el-text-color-secondary);font-size:12px;margin:4px 12px}.model-scroll{flex:1;min-height:0;overflow:auto;padding:0 12px}.model-series{margin-bottom:8px}.series-heading{display:flex;align-items:center;justify-content:space-between;gap:8px;min-height:30px;border-bottom:1px solid var(--el-border-color-extra-light)}.series-heading strong{font-size:12px;font-weight:500;overflow-wrap:anywhere}.series-heading .el-button{font-size:12px;flex-shrink:0}.model-checkbox{display:flex;height:auto;min-height:32px;margin-right:0;align-items:flex-start;padding:5px 0}.model-checkbox :deep(.el-checkbox__input){margin-top:2px}.model-checkbox :deep(.el-checkbox__label){font-size:12px;white-space:normal;overflow-wrap:anywhere;line-height:20px;min-width:0}.results-toolbar :deep(.el-checkbox__label){font-size:12px}.result-message{text-align:center;line-height:22px;color:var(--el-text-color-secondary);padding:24px 6px;margin:0}.model-pagination{min-height:32px;padding:4px 8px;border-top:1px solid var(--el-border-color-lighter);display:flex;align-items:center;justify-content:flex-end;color:var(--el-text-color-secondary);font-size:12px}
.selection-footer{border-top:1px solid var(--el-border-color-lighter);padding:8px 12px;flex-shrink:0}.selected-heading{display:flex;align-items:center;justify-content:space-between;min-height:24px}.selected-heading strong{font-weight:500}.selected-tags{display:flex;align-content:flex-start;gap:4px;flex-wrap:wrap;min-height:24px;max-height:86px;overflow:auto;padding:4px 0}.selected-tags :deep(.el-tag){height:auto;max-width:100%}.selected-tags :deep(.el-tag__content){white-space:normal;line-height:18px;overflow-wrap:anywhere;min-width:0}.no-selection,.selection-note{font-size:12px;color:var(--el-text-color-secondary)}.selected-pagination{display:flex;align-items:center;justify-content:flex-end;gap:6px;font-size:12px;color:var(--el-text-color-secondary)}.selection-actions{display:flex;gap:8px;align-items:center;justify-content:flex-end;margin-top:6px}.selection-actions .el-button{margin-left:0}.selection-note{margin-right:auto;line-height:18px}.phone-model-panel :deep(.el-button),.phone-model-panel :deep(.el-input__inner){font-size:12px}.phone-model-panel :deep(.el-input__wrapper){min-height:28px}.panel-error{font-size:12px;line-height:18px;padding:6px 12px;color:var(--el-color-warning-dark-2);background:var(--el-color-warning-light-9);display:flex;gap:6px;align-items:center;flex-wrap:wrap}.closed-error{margin:4px 0 0;font-size:12px;line-height:18px;color:var(--el-color-warning-dark-2)}
@media(max-height:460px){.selection-body{min-height:40px}.selected-tags{max-height:42px}}
@media(max-width:520px){.selection-body{grid-template-columns:96px minmax(0,1fr)}.brand-nav button{padding-inline:5px}.panel-search,.selection-footer{padding-inline:8px}.model-scroll{padding-inline:8px}.results-toolbar{padding-inline:8px}.selection-note{flex-basis:100%}.selection-actions{flex-wrap:wrap}.phone-model-panel{height:min(660px,calc(100dvh - 24px))}.model-pagination :deep(.el-pager li){min-width:24px}}
</style>

<style>
.phone-model-selector-popover>.el-popover__title{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap;border:0}
</style>
