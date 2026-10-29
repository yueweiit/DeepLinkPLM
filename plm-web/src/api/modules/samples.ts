import request,{unwrapResponse} from '../request'
import type {ResearchSampleChoices} from '@/types/samples'

/** Source choices use the sample API and its existing action/object permissions. */
export async function researchSampleSources(domain:'model'|'research',projectId?:number,context?:{modelProductId?:number;phoneModelId?:number}):Promise<ResearchSampleChoices>{
  return unwrapResponse(await request.get('/samples/research-sources',{params:{domain,projectId,...context}}))
}
