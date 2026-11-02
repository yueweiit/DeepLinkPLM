import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import ElementPlus from 'element-plus'
import {afterEach,beforeEach,describe,expect,it} from 'vitest'
import PasteDialog from '../selection/SelectionPasteDialog.vue'
import {newSelectionRow} from '@/utils/partnerSelections'
import type {SelectionRow} from '@/types/partnerSelections'

let wrapper:VueWrapper
const row=(index:number)=>({...newSelectionRow(),rowId:`row-${index}`,name:`款式${index}`,material:'原材质',color:'原色',modelText:'原型号',sampleQty:3})
async function start(rows:SelectionRow[]=[row(1),row(2),row(3)]){wrapper=mount(PasteDialog,{props:{rows,busy:false,saveError:'',fieldLabel:field=>field,canEdit:row=>row.canReviseSelection!==false},global:{plugins:[ElementPlus],stubs:{Teleport:true}},attachTo:document.body});await flushPromises()}
const cell=(row:number,column:number)=>wrapper.get(`textarea[data-row="${row}"][data-column="${column}"]`)
async function paste(row:number,column:number,text:string){await cell(row,column).trigger('paste',{clipboardData:{getData:()=>text}});await flushPromises()}
async function click(text:string){await wrapper.findAll('button').find(button=>button.text()===text)!.trigger('click');await flushPromises()}
beforeEach(()=>{document.body.innerHTML=''})
afterEach(()=>{wrapper?.unmount();document.body.innerHTML=''})

describe('existing-selection spreadsheet paste',()=>{
 it('shows labelled rows and cells, applies a two-dimensional range and preserves blanks, embedded newlines and zero quantities',async()=>{
  await start();expect(wrapper.get('table').attributes('aria-label')).toBe('选品矩形粘贴表格');expect(wrapper.findAll('thead th')).toHaveLength(12)
  await paste(0,2,'"PC\n透明"\t\tHonor 600 Lite 5G\t黑色\nTPU\t加厚\t\t白色')
  expect((cell(0,2).element as HTMLTextAreaElement).value).toBe('PC\n透明');expect((cell(0,4).element as HTMLTextAreaElement).value).toBe('Honor 600 Lite 5G');expect((cell(1,4).element as HTMLTextAreaElement).value).toBe('原型号')
  await paste(0,9,'0\n2');await click('核对粘贴');await click('应用本次内容')
  expect(wrapper.emitted('apply')?.[0][0]).toEqual([{rowId:'row-1',changes:{material:'PC\n透明',modelText:'Honor 600 Lite 5G',color:'黑色',sampleQty:0}},{rowId:'row-2',changes:{material:'TPU',specification:'加厚',color:'白色',sampleQty:2}}])
 })
 it('keeps long multi-model values intact in the full-width active-cell editor and applies edits to only that field',async()=>{
  const original=Array.from({length:39},(_,index)=>`QA 手机型号 ${index+1} Pro Max`).join(' / ');await start([{...row(1),modelText:original,notes:'来源说明'.repeat(160)},row(2)]);await cell(0,4).trigger('focus');await flushPromises();const editor=wrapper.get('textarea.current-cell-editor');expect((editor.element as HTMLTextAreaElement).value).toBe(original);await editor.setValue(original+' / 新开发型号');await click('核对粘贴');await click('应用本次内容');expect(wrapper.emitted('apply')?.[0][0]).toEqual([{rowId:'row-1',changes:{modelText:original+' / 新开发型号'}}]);expect(wrapper.findAll('.paste-grid tbody tr')).toHaveLength(2)
 })
 it('keeps an empty Excel row in place rather than shifting a later style upward',async()=>{
  await start();await paste(0,2,'PC\n\nTPU\n');expect((cell(1,2).element as HTMLTextAreaElement).value).toBe('原材质');expect((cell(2,2).element as HTMLTextAreaElement).value).toBe('TPU');await click('核对粘贴');await click('应用本次内容');expect(wrapper.emitted('apply')?.[0][0]).toEqual([{rowId:'row-1',changes:{material:'PC'}},{rowId:'row-3',changes:{material:'TPU'}}])
 })
 it('rejects out-of-page, out-of-column and fractional-quantity ranges without partially editing their cells',async()=>{
  await start();await paste(2,2,'PC\nTPU');expect(wrapper.get('[role="alert"]').text()).toContain('超出本页已有行');expect((cell(2,2).element as HTMLTextAreaElement).value).toBe('原材质')
  await paste(0,9,'1\t2');expect(wrapper.get('[role="alert"]').text()).toContain('超出当前粘贴字段');expect((cell(0,9).element as HTMLTextAreaElement).value).toBe('3')
  await paste(0,9,'2.5');expect(wrapper.get('[role="alert"]').text()).toContain('非负整数');expect((cell(0,9).element as HTMLTextAreaElement).value).toBe('3');expect(wrapper.emitted('apply')).toBeUndefined()
 })
 it('rejects a rectangle crossing an immutable row without skipping it or changing another style',async()=>{
  await start([row(1),{...row(2),canReviseSelection:false},row(3)]);await paste(0,2,'PC\nTPU\nPC');expect(wrapper.get('[role="alert"]').text()).toContain('不可修改');expect((cell(0,2).element as HTMLTextAreaElement).value).toBe('原材质');expect((cell(2,2).element as HTMLTextAreaElement).value).toBe('原材质');expect(wrapper.find('textarea[data-row="1"]').exists()).toBe(false);await wrapper.get('.locked-row td:nth-of-type(5) span').trigger('focus');await flushPromises();expect(wrapper.get('textarea.current-cell-editor').attributes('readonly')).toBeDefined();expect((wrapper.get('textarea.current-cell-editor').element as HTMLTextAreaElement).value).toBe('原型号')
 })
 it('invalidates a checked preview after direct edits and cancels without applying changes',async()=>{
  await start();await cell(0,5).setValue('黑色');await click('核对粘贴');let apply=wrapper.findAll('button').find(button=>button.text()==='应用本次内容')!;expect(apply.attributes('disabled')).toBeUndefined()
  await cell(0,5).setValue('白色');apply=wrapper.findAll('button').find(button=>button.text()==='应用本次内容')!;expect(apply.attributes('disabled')).toBeDefined();await click('取消');expect(wrapper.emitted('close')).toHaveLength(1);expect(wrapper.emitted('apply')).toBeUndefined()
 })
 it('restores an erased original cell even when no nonblank changes are pending',async()=>{await start();await cell(0,2).setValue('');await click('重置本次填写');expect((cell(0,2).element as HTMLTextAreaElement).value).toBe('原材质');expect(wrapper.emitted('apply')).toBeUndefined()})
 it('checks directly typed quantities and keeps the original record when an entered cell is blank',async()=>{
  await start();await cell(0,9).setValue('-1');await click('核对粘贴');expect(wrapper.get('[role="alert"]').text()).toContain('非负整数');expect(wrapper.emitted('apply')).toBeUndefined();await cell(0,9).setValue('');await cell(0,5).setValue('白色');await click('核对粘贴');await click('应用本次内容');expect(wrapper.emitted('apply')?.[0][0]).toEqual([{rowId:'row-1',changes:{color:'白色'}}])
 })
})
