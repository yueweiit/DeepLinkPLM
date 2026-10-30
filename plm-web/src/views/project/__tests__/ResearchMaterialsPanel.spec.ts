import { mount,type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import {afterEach,expect,it,vi} from 'vitest';
import ResearchMaterialsPanel from '../components/ResearchMaterialsPanel.vue';
import {type DevelopmentMaterial} from '@/api/modules/productResearch';
import {product3dKind} from '@/utils/product3dMaterials';
let wrapper:VueWrapper;
afterEach(()=>wrapper?.unmount());
const file=(id:number,status='draft'):DevelopmentMaterial=>({id,type:'file',name:`完整工程图片-000${id}.png`,versionNo:'D'+id,status,category:'sample_image',attachment:{attachmentId:id,fileExt:'png',fileSize:1024,originalFileName:`完整工程图片-000${id}.png`} as any});
function start(editable=true){wrapper=mount(ResearchMaterialsPanel,{props:{materials:[file(1),file(2,'released'),{type:'bom',id:7,name:'具体用料BOM',versionNo:'B1',status:'released'}],selected:['file:1'],editable,selectable:editable,busy:false},global:{plugins:[ElementPlus],stubs:{DocumentPreview:{props:['file','compact'],template:'<div class="test-preview">{{file.attachmentId}}</div>'}}}});}
it('keeps image previews and every original file operation directly visible with exact version identities',async()=>{
 start();expect(wrapper.text()).toContain('图片与附件');expect(wrapper.findAll('.test-preview')).toHaveLength(2);expect(wrapper.findAll('.material-row')).toHaveLength(3);expect(wrapper.find('.el-collapse').exists()).toBe(false);
 const rowUpload=wrapper.findAll('.material-row')[0]!.findAll('button').find(b=>b.text()==='上传新版')!;await rowUpload.trigger('click');expect(wrapper.emitted('upload')).toEqual([[file(1)]]);
 await wrapper.get('button[aria-label="查看大图 完整工程图片-0001.png D1"]').trigger('click');expect(wrapper.emitted('view')?.[0]?.[0]).toEqual(file(1));
 const row=wrapper.findAll('.material-row')[0]!;await row.findAll('button').find(b=>b.text()==='下载')!.trigger('click');expect(wrapper.emitted('download')?.[0]?.[0]).toEqual(file(1));
 expect(wrapper.findAll('.material-row')[1]!.text()).not.toContain('删除草稿');expect(row.text()).toContain('删除草稿');
});
it('keeps all selected reference IDs when a different existing version is adopted',async()=>{
 start();await wrapper.findAll('.material-row')[1]!.get('input[type=checkbox]').setValue(true);expect(wrapper.emitted('update:selected')).toEqual([[['file:1','file:2']]]);
});
it('keeps reading and download available while original write and reference selection permissions are absent',()=>{
 start(false);expect(wrapper.find('input[type=file]').exists()).toBe(false);expect(wrapper.find('.material-drop-zone').exists()).toBe(false);expect(wrapper.text()).not.toContain('上传新版');expect(wrapper.text()).not.toContain('删除草稿');expect(wrapper.text()).toContain('下载');expect(wrapper.text()).toContain('查看 BOM');expect(wrapper.findAll('input[type=checkbox]').every(e=>(e.element as HTMLInputElement).disabled)).toBe(true);
});

it('shows missing images openly and immediately previews images returned by the existing upload refresh',async()=>{
 start();await wrapper.setProps({materials:[]});expect(wrapper.get('.image-placeholder').text()).toBe('暂无图片');expect(wrapper.findAll('.material-drop-zone')).toHaveLength(1);expect(wrapper.get('.material-drop-zone').text()).toContain('将图片或附件拖到这里');
 await wrapper.setProps({materials:[file(3)]});expect(wrapper.find('.image-gallery').exists()).toBe(true);expect(wrapper.find('.material-drop-zone').exists()).toBe(true);expect(wrapper.findAll('.test-preview')).toHaveLength(1);expect(wrapper.text()).toContain('完整工程图片-0003.png');
 expect(wrapper.emitted('update:selected')).toBeUndefined();
});


it('stages a mixed OS file drop once, accepts native selection again, and preserves saved reference IDs',async()=>{
 start();const image=new File(['image'],'设计图.png',{type:'image/png'}),document=new File(['plan'],'方案.docx');
 const transfer={types:['Files'],files:[image,document],dropEffect:'none'};
 await wrapper.get('aside').trigger('dragenter',{dataTransfer:transfer});expect(wrapper.classes()).toContain('is-dragging');
 await wrapper.get('aside').trigger('dragover',{dataTransfer:transfer});expect(transfer.dropEffect).toBe('copy');
 await wrapper.get('aside').trigger('drop',{dataTransfer:transfer});expect(wrapper.classes()).not.toContain('is-dragging');expect(wrapper.emitted('files')).toEqual([[[image,document]]]);
 const input=wrapper.get('input[type=file]');Object.defineProperty(input.element,'files',{value:[image],configurable:true});
 await input.trigger('change');await input.trigger('change');expect(wrapper.emitted('files')).toEqual([[[image,document]],[[image]],[[image]]]);expect(wrapper.emitted('update:selected')).toBeUndefined();
 const click=vi.spyOn(input.element as HTMLInputElement,'click').mockImplementation(()=>{});
 await wrapper.findAll('button').find(b=>b.text()==='从本地上传')!.trigger('click');await wrapper.get('.material-drop-zone').trigger('keydown',{key:'Enter'});expect(click).toHaveBeenCalledTimes(2);click.mockRestore();
});
it('prevents file navigation and uploading on a readonly or busy panel while offering the actual stage',async()=>{
 start(false);await wrapper.setProps({uploadStageName:'立项设计'});const dropped=new File(['image'],'设计图.png');
 await wrapper.get('aside').trigger('drop',{dataTransfer:{types:['Files'],files:[dropped]}});expect(wrapper.emitted('files')).toBeUndefined();
 await wrapper.findAll('button').find(b=>b.text()==='前往立项设计上传')!.trigger('click');expect(wrapper.emitted('currentStage')).toEqual([[]]);
 await wrapper.setProps({editable:true,busy:true});await wrapper.get('aside').trigger('drop',{dataTransfer:{types:['Files'],files:[dropped]}});expect(wrapper.emitted('files')).toBeUndefined();expect(wrapper.find('.is-dragging').exists()).toBe(false);
});


it('retains full long file identities and original write restrictions in the unified project panel',async()=>{
 start();const item={...file(1),name:'供应商确认的完整设计名称_不得截断_包含代表型号及变更原因.png'};await wrapper.setProps({materials:[item],editable:false,selectable:false});expect(wrapper.get('.material-row').text()).toContain(item.name+' · D1');expect(wrapper.get('.image-caption').text()).toContain(item.name);expect(wrapper.find('.material-drop-zone').exists()).toBe(false);expect(wrapper.text()).toContain('下载');expect(wrapper.text()).not.toContain('上传新版');expect(wrapper.text()).not.toContain('删除草稿');
});

it('keeps a legacy attachment with null extension visible as an ordinary file without guessing 3D use from its filename',async()=>{
 const legacy:DevelopmentMaterial={...file(9),name:'历史产品图.step',category:'drawing',attachment:{...file(9).attachment,fileExt:null} as any};expect(product3dKind(legacy)).toBeNull();start();await wrapper.setProps({materials:[legacy]});expect(wrapper.get('.material-row').text()).toContain('历史产品图.step · D9');expect(wrapper.find('.image-gallery').exists()).toBe(false);expect(wrapper.get('.image-placeholder').text()).toBe('暂无图片');await wrapper.get('.material-row').findAll('button').find(button=>button.text()==='下载')!.trigger('click');expect(wrapper.emitted('download')).toEqual([[legacy]]);expect(wrapper.emitted('update:selected')).toBeUndefined();
});
