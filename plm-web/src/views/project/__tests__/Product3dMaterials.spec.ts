import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import ElementPlus from 'element-plus'
import {afterEach,expect,it} from 'vitest'
import Product3dMaterials from '../components/Product3dMaterials.vue'
import type {DevelopmentMaterial} from '@/api/modules/productResearch'
const material=(id:number,category:string,fileExt:string):DevelopmentMaterial=>({type:'file',id,name:`文件${id}.${fileExt}`,versionNo:`D${id}`,status:'draft',category,attachment:{fileExt,attachmentId:id} as any})
const source=material(21,'drawing','step'),render=material(22,'render_3d','png'),ordinary=material(23,'drawing','png'),unclassified=material(24,'other','step')
let wrapper:VueWrapper<any>
function button(text:string){return wrapper.findAll('button').find(b=>b.text()===text)!}
function start(props:Record<string,unknown>={}){wrapper=mount(Product3dMaterials,{props:{materials:[source,render,ordinary,unclassified],selected:[],savedReferences:[],uploadable:true,selectable:true,busy:false,...props},global:{plugins:[ElementPlus],stubs:{ElDialog:{props:['modelValue'],template:'<section v-if="modelValue" class="selection-dialog"><slot/><slot name="footer"/></section>'}}}})}
afterEach(()=>wrapper?.unmount())
it('separates source and engineer-designated render versions and shows saved, pending and unadopted states without classifying ordinary images',async()=>{
 start({materials:[source,render,ordinary,unclassified,material(25,'drawing','x_t')],selected:['file:21'],savedReferences:[{type:'file',id:22}]});expect(wrapper.get('[data-3d-kind=source]').text()).toContain('文件21.step · D21');expect(wrapper.get('[data-3d-kind=source]').text()).toContain('本次待保存采用');expect(wrapper.get('[data-3d-kind=source]').text()).toContain('文件25.x_t');expect(wrapper.get('[data-3d-kind=render]').text()).toContain('本次待保存取消');expect(wrapper.text()).not.toContain('文件23.png');expect(wrapper.text()).not.toContain('文件24.step');await wrapper.setProps({savedReferences:[{type:'file',id:21}]});expect(wrapper.get('[data-file="21"]').text()).toContain('已保存采用');expect(wrapper.get('[data-file="22"]').text()).toContain('已上传未采用');
})
it('keeps temporary checks on cancel, confirms only original qualified IDs and preserves other existing references',async()=>{
 start({selected:['bom:70','file:24']});await button('选择已有3D资料').trigger('click');await wrapper.get('.selection-dialog input[value="file:21"]').setValue(true);await button('取消').trigger('click');expect(wrapper.emitted('update:selected')).toBeUndefined();await button('选择已有3D资料').trigger('click');expect((wrapper.get('.selection-dialog input[value="file:21"]').element as HTMLInputElement).checked).toBe(false);await wrapper.get('.selection-dialog input[value="file:22"]').setValue(true);await button('确认本次选择').trigger('click');expect(wrapper.emitted('update:selected')).toEqual([[['bom:70','file:24','file:22']]]);expect(wrapper.props('savedReferences')).toEqual([]);
})
it('uses explicit upload kinds and keeps readonly records viewable without changing their adopted versions',async()=>{
 start();await button('上传3D源文件').trigger('click');await button('上传3D效果图').trigger('click');expect(wrapper.emitted('upload')).toEqual([['source'],['render']]);await wrapper.setProps({uploadable:false,selectable:false,savedReferences:[{type:'file',id:21}],selected:['file:21']});expect(wrapper.find('[data-readiness-focus=product3d-upload]').exists()).toBe(false);expect(button('选择已有3D资料').attributes('disabled')).toBeDefined();await wrapper.get('[data-file="21"] button').trigger('click');expect(wrapper.emitted('view')).toEqual([[source]]);expect(wrapper.get('[data-file="21"]').text()).toContain('已保存采用');expect(wrapper.emitted('update:selected')).toBeUndefined();
})
