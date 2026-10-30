import type { DevelopmentMaterial } from '@/api/modules/productResearch'
import { ENGINEERING_UPLOAD_ACCEPT } from '@/utils/file'

export type Product3dKind = 'source' | 'render'
const sourceExtensions = ['step','stp','stl','igs','iges','sldprt','sldasm','x_t','x_b','3dm','obj','3mf','prt']
const renderExtensions = ['png','jpg','jpeg','webp','pdf']
export const product3dUploads = {
  source: {category:'drawing',title:'上传3D源文件',accept:sourceExtensions.map(ext=>'.'+ext).filter(ext=>ENGINEERING_UPLOAD_ACCEPT.split(',').includes(ext)).join(',')},
  render: {category:'render_3d',title:'上传3D效果图',accept:renderExtensions.map(ext=>'.'+ext).join(',')}
} as const
export function product3dKind(material:DevelopmentMaterial):Product3dKind|null {
  if(material.type!=='file'||!material.attachment)return null
  const ext=(material.attachment.fileExt||'').replace(/^\./,'').toLowerCase()
  if(material.category==='drawing'&&sourceExtensions.includes(ext))return 'source'
  if(material.category==='render_3d'&&renderExtensions.includes(ext))return 'render'
  return null
}
