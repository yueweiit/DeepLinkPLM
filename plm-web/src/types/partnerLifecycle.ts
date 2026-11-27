import type { PartnerFile, PartnerRow } from './partner'

export interface CatalogListingPair { phoneModelId:number;colorId:number;colorName:string;specName:string;catalogSpecKey:string;eligible:boolean;reason?:string;inspectionId?:number;sampleId?:number }
export interface CatalogListingOptions { catalogStyleId:number;catalogStyleCode:string;catalogStyleLabel?:string;name:string;supplierCode:string;selectionId:number;selectionRowId:string;selectionVersion:number;selectionRowVersion:number;photos:Pick<PartnerFile,'id'|'name'|'version'>[];pairs:CatalogListingPair[];externalListingConnected:boolean }
export interface CatalogListingRequest { selectionId:number;selectionRowId:string;selectionVersion:number;selectionRowVersion:number;selectedPairs:{phoneModelId:number;colorId:number}[];productLinkMode:'create'|'existing';existingProductId?:number;existingProductVersion?:number;specLinks?:{phoneModelId:number;colorId:number;specId?:number;configurationConfirmed?:boolean;createNew?:boolean}[];name:string;introduction:string;refs:{id:number;type:string;name:string;version:string}[];requestId:string }
export interface ListedCatalogRow extends PartnerRow { productId?:number;listingId?:number;legacyProduct?:boolean;catalogStyleCode?:string;catalogStyleLabel?:string;phones?:{modelName?:string;phoneModelCode?:string}[];color?:string;photoId?:number;integrationStatus?:string;channel?:string }

export interface CrmListingCapabilities { enabled:boolean;adapterReady:boolean;authorized:boolean;canSubmit:boolean;reason:string }
export interface CrmListingSku { specId:number;specName:string;internalSkuId:number;skuCode?:string;skuName?:string;phoneModelName?:string;phoneModelCode?:string;color?:string;colorCode?:string;supplierCode:string;eligible:boolean;reason:string }
export type CrmListingSyncStatus = 'not_submitted'|'pending'|'processing'|'partial'|'synced'|'failed'
export interface CrmListingSync { listingId:number;taskId?:number;status:CrmListingSyncStatus;crmProductId?:string;errorCode?:string;errorMessage?:string;skus:{internalSkuId:number;status:string;listingStatus:string;crmSkuId?:string;errorMessage?:string}[] }
export interface CrmListingPushOptions extends CrmListingCapabilities { listingId:number;snapshotVersion:string;product:{productId:number;productCode:string;name:string;catalogStyleCode:string;introduction:string;photos:{id:number;version:string}[]};reasons:string[];skus:CrmListingSku[];sync:CrmListingSync }
export interface CrmListingPushItem { listingId:number;snapshotVersion:string;internalSkuIds:number[] }
