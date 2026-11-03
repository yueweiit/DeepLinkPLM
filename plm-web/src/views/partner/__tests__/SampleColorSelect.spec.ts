import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it } from 'vitest'
import { defineComponent, ref } from 'vue'
import ElementPlus from 'element-plus'
import SampleColorSelect from '../selection/SampleColorSelect.vue'

let wrapper: ReturnType<typeof mount>
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

it('adds a newly typed colour with Enter while retaining colours already selected', async () => {
  const Harness = defineComponent({
    components: { SampleColorSelect },
    setup() { return { colours: ref(['黑色', '白色']) } },
    template: '<SampleColorSelect v-model="colours" :options="[\'黑色\',\'白色\']" label="选样颜色"/>',
  })
  wrapper = mount(Harness, { attachTo: document.body, global: { plugins: [ElementPlus] } })
  const input = wrapper.get('input')
  await input.trigger('focus')
  await input.setValue('定制蓝')
  await flushPromises()
  await input.trigger('keydown', { key: 'Enter', code: 'Enter' })
  await flushPromises()
  expect(wrapper.getComponent(SampleColorSelect).props('modelValue')).toEqual(['黑色', '白色', '定制蓝'])
})
