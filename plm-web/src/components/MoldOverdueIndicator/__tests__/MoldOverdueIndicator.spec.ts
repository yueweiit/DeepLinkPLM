import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { CollaborationRow, MoldScheduleTask } from '@/types/collaboration'
import { moldDateClass, moldOverdueCell } from '@/utils/moldPresentation'
import MoldOverdueIndicator from '../index.vue'

describe('saved mold delay presentation',()=>{
  it('uses saved task metadata, not a past date or draft, and clears after the server confirms completion',async()=>{
    const row={moldSetId:42,assetNumber:'MS-000042',plannedMoldDeliveryDate:'2000-01-01'} as CollaborationRow
    const w=mount(MoldOverdueIndicator,{props:{row},global:{stubs:{ElPopover:{template:'<div><slot name="reference" /><slot /></div>'}}}})
    expect(w.find('button').exists()).toBe(false)
    expect(moldOverdueCell({row,column:{property:'plannedMoldDeliveryDate'}})).toBe('')
    const task:MoldScheduleTask={moldSetId:42,assetNumber:'MS-000042',styleName:'薇武士',compatibleModels:'Apple iPhone',taskKey:'ship',title:'填写实际发模',group:'logistics',ownerUserId:1,dueDate:'2026-10-10',dueField:'plannedMoldDeliveryDate',overdue:true,overdueDays:3}
    await w.setProps({row:{...row,overdueTasks:[task]}})
    expect(w.get('button').attributes('aria-label')).toContain('MS-000042')
    expect(w.text()).toContain('填写实际发模');expect(w.text()).toContain('逾期 3 天');expect(w.text()).toContain('草稿保存后更新')
    expect(moldOverdueCell({row:{...row,overdueTasks:[task]},column:{property:'plannedMoldDeliveryDate'}})).toBe('is-overdue')
    expect(moldOverdueCell({row:{...row,overdueTasks:[task]},column:{property:'actualMexicoArrivalDate'}})).toBe('')
    expect(moldOverdueCell({row:{...row,overdueTasks:[{...task,overdue:false}]},column:{property:'plannedMoldDeliveryDate'}})).toBe('')
    await w.setProps({row:{...row,overdueTasks:[]}});expect(w.find('button').exists()).toBe(false);w.unmount()
  })
  it('tints only the three confirmed actual-date columns and leaves planned dates neutral',()=>{
    expect(['openedDate','actualShipDate','actualMexicoArrivalDate','plannedMoldDeliveryDate'].map(moldDateClass)).toEqual(['milestone-opening','milestone-shipping','milestone-mexico',''])
  })
})
