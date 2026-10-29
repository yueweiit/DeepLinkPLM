import request, { unwrapPage, unwrapResponse } from '../request'
import type { ProductionStatus } from '@/types/production'

export interface MasterDataStatus {enabled:boolean;ready:boolean;administrator:boolean;batch?:{sourceHash:string;committedAt:string}}
export interface CurrentSku {
  masterDataStatus?:'active'|'inactive';
  sourceId:string;objectId:number;objectType:'product'|'partner';phoneModelId:number;itemCode:string;seriesCode:string;seriesName:string;
  productName:string;brandId:string;brandName:string;modelName:string;modelCode:string;colorShortCode:string;colorNameZh:string;
  colorNameEn?:string;colorNameEs?:string;commercialVersion?:string;listingStatus:'listed'|'unlisted';sourceStatus:string;
  productionStatus:ProductionStatus;phoneProductionStatus:ProductionStatus;salesStatus:string;developmentStatus:string;observedAt:string;
  purchased:boolean;sameItemCount:number;basePrice?:string;currency?:null;
}
export interface CatalogSeries {masterDataStatus?:'active'|'inactive';code:string;name:string;objectId:number;objectType:'product'|'partner';skuCount:number;mes?:{product_name_cn:string;product_name_en?:string;product_name_es?:string;product_type:string;status:string;description_zh?:string;description_en?:string;description_es?:string}}
export interface CatalogOptions {series:CatalogSeries[];brands:{id:string;name:string}[];total:number;listed:number;purchased:number}
export interface CatalogQuery {page:number;size:number;keyword?:string;seriesCode?:string;brandId?:string;listingStatus?:string;productionStatus?:string}
export interface RouteNode {id:number;nodeCode:string;localNodeCode?:string;name:string;stepNo:number;operationCode:string;x?:number;y?:number;config?:Record<string,unknown>}
export interface RouteEdge {id:number;fromNodeId:number;toNodeId:number;edgeNo:number;relationType:string;label?:string;condition?:unknown}
export interface ScopedMaterial {id:number;lineNo:number;inventoryId:number;childCode:string;childName:string;quantity:string;unit:string;unitRaw?:string;unitCn?:string;unitEs?:string;componentRowId?:string;nodeId:number;nodeCode:string;nodeName:string;operationCode:string;articleSpecific:boolean;unitPrice?:string;currency?:string;stockUnit?:string;mesEffectiveUnit?:string;unitProjection?:unknown}
export interface ScopedRecipe {bomId:number;projectId:number;bomCode:string;internalBomCode:string;outputQty:string;parentCode:string;parentName:string;specification?:string;sourceVersion:string;versionNo:string;status:string;confirmedBy?:string;confirmedAt?:string;bomRouteId:number;processId:number;routeCode:string;routeName:string;items:ScopedMaterial[];graph:{nodes:RouteNode[];edges:RouteEdge[]}}
export interface CurrentSkuDetail {source:Record<string,unknown>;objectType:'product'|'partner';objectId:number;manufacturing:ScopedRecipe[]}
export async function getMasterDataStatus(){return unwrapResponse<MasterDataStatus>(await request.get('/master-data/status'))}
export async function getCurrentCatalog(query:CatalogQuery){return unwrapPage<CurrentSku>(await request.get('/products/current-catalog',{params:query}))}
export async function getCurrentCatalogOptions(){return unwrapResponse<CatalogOptions>(await request.get('/products/current-catalog/options'))}
export async function getCurrentSku(id:string){return unwrapResponse<CurrentSkuDetail>(await request.get(`/products/current-catalog/${encodeURIComponent(id)}`))}
export async function getLocalSnapshots(){return unwrapResponse<Array<{name:string;hash:string;capturedAt:string}>>(await request.get('/master-data/snapshots'))}
export interface ImportPreview {importId:string;sourceHash:string;originalHash:string;seriesCodes:string[];skuCount:number;listedCount:number;unlistedCount:number;purchasedCount:number;warnings:unknown[];gaps:unknown[];remoteBusinessWrites:number}
export async function previewLocalSnapshot(snapshotName:string){return unwrapResponse<ImportPreview>(await request.post('/master-data/preview',{snapshotName}))}
export async function commitLocalSnapshot(preview:ImportPreview,reason:string){return unwrapResponse<unknown>(await request.post('/master-data/commit',{importId:preview.importId,sourceHash:preview.sourceHash,originalHash:preview.originalHash,reason},{timeout:180000}))}
