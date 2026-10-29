export const PRODUCT_RESEARCH_STAGES = [
  {step:1,key:'initiation',name:'立项',icon:'EditPen'},
  {step:2,key:'drawing',name:'画3D产品图',icon:'Document'},
  {step:3,key:'prototype',name:'3D打样',icon:'Box'},
  {step:4,key:'sample-confirm',name:'样品确认',icon:'DocumentChecked'},
  {step:5,key:'mold-design',name:'模具设计',icon:'SetUp'},
  {step:6,key:'mold',name:'开模',icon:'SetUp'},
  {step:7,key:'pilot',name:'试产',icon:'Finished'},
  {step:8,key:'ship',name:'发模',icon:'Van'}
] as const
export function launchStage(value:unknown){return PRODUCT_RESEARCH_STAGES.find(s=>s.key===String(value)||String(s.step)===String(value))||PRODUCT_RESEARCH_STAGES[0]}
export function legacyResearchStep(value:unknown){return ({1:1,2:5,3:6,4:7} as Record<string,number>)[String(value)]||1}
