export type SampleDomain = 'model' | 'research' | 'partner' | 'unclassified'
export type SampleKind = 'phone' | 'dummy' | 'pc' | 'tpu' | 'case' | 'prototype' | 'supplier' | 'unspecified'
export interface ResearchSampleProject {id:number;code:string;name:string;projectKind?:string;modelProductId?:number;phoneModels?:import('@/components/PhoneObjectSelect/types').ObjectPhone[]}
export interface ResearchSampleChoices {
  canReadSets?:boolean
  canReadProjects?:boolean
  products?:{id:number;name:string;code?:string;salesStatus?:string}[]
  projects?:ResearchSampleProject[]
  project?:ResearchSampleProject
  sets?:{id:number;itemId:number;moldSetId:number;setNo:number;name:string;moldName?:string;engravedCode?:string;phoneModels?:import('@/components/PhoneObjectSelect/types').ObjectPhone[]}[]
  rounds?:{id:number;no:number;title:string;result:string;date:string}[]
}
