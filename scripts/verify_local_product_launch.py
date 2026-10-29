#!/usr/bin/env python3
"""Run current launch_v1 acceptance in a disposable database, including retirement of the obsolete flow.
All fixtures, local runtime, backup, login and cleanup are owned by the existing verifier.
"""
import json,re,urllib.request,urllib.error,uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import date,timedelta
from local_research_fixture import main,sql
from local_launch_evidence import LaunchEvidence

def verify_launch(ctx):
    today=date.today().isoformat();yesterday=(date.today()-timedelta(days=1)).isoformat()
    database,base,output,owner_id,token,limited_id,limited_token=[ctx[k] for k in ('database','base','output','owner_id','token','limited_id','limited_token')]
    detail,original_act,check=ctx['detail'],ctx['act'],ctx['check']
    reject_action=ctx['reject_action']
    original_api=ctx['api']
    def api(method,path,body=None,token=None,content_type='application/json',success=True):
        if method=='POST' and isinstance(body,dict) and re.search(r'/projects/\d+/(timeline/LAUNCH_[^/]+/(confirm|return)|publish)$',path):
            pid=int(path.split('/')[2]);mode=sql(database,f"select coalesce(development_mode,'') from plm_product where product_id={pid}")
            if mode=='launch_v1':body={**body,'developmentVersion':int(sql(database,f"select coalesce(version,0) from plm_product_development_state where product_id={pid}"))}
        return original_api(method,path,body,token,content_type,success)
    def act(action,data,pid,request=None):
        result=original_act(action,data,pid,request)
        if action=='release_draft':
            result=original_act('engineering_confirm',{'stage':4,'reason':'隔离验收：核对本次已保存的正式BOM、工艺与供方分工'},pid)
        return result
    suppliers=api('GET','/product-research/options',token=token)['suppliers']
    supplier=next((row for row in suppliers if row['status']=='active' and not row.get('needsReview')),None)
    if supplier is None:
        raise AssertionError('The disposable source must contain an active authorized real supplier for the direct-purchase path')
    def catalog_ids():
        return {row['id'] for row in api('GET','/products/sales-catalog',token=token) if row['salesStatus']=='on_sale'}
    initial_catalog=catalog_ids()
    initial_market=api('GET','/market-watch/items?opportunityView=all&page=1&size=1',token=token)['content'][0]
    check('market coverage and sales catalog use the same initial product denominator',initial_market['coverageTotal']==len(initial_catalog))
    def no_launch_side_effects():
        return json.loads(sql(database,"""select json_build_object(
            'products',(select count(*) from plm_product),
            'inventory',(select count(*) from plm_inventory),
            'sets',(select count(*) from plm_collaboration_mold_set),
            'applications',(select count(*) from plm_mold_application),
            'bomItems',(select count(*) from plm_product_bom_item),
            'expenses',(select count(*) from plm_project_cost_item))::text"""))
    # A confirmed opportunity may defer the real phone selection until the development plan.
    optional=api('POST','/product-research',{'productName':'新流程：机型在计划确认','productType':'product_line','versionNo':'V1','createdBy':'fixture'},token)['productId']
    preview_input={'profile':{'productName':'可见范围验收','requirements':'','references':[]},'plan':{}}
    api('POST',f'/product-research/{optional}/design-readiness',preview_input,limited_token,success=False)
    api('POST',f'/product-research/{optional}/design-readiness',preview_input,None,success=False)
    check('design checklist retains authentication and the original project visibility scope',True)
    api('PUT',f'/product-research/{optional}',{'productName':'新流程：机型在计划确认','requirements':'先确认产品方向','model':'','phoneModelIds':[],'ownerUserId':owner_id,'version':detail(optional)['workflow']['version']},token)
    act('confirm_requirement',{},optional)
    check('design draft can defer phone selection without inventing a phone or advancing incomplete design',detail(optional)['currentStepNo']==1 and not detail(optional)['workflow']['requirement']['phoneModelIds'])
    # New launch_v1 flow: both physical routes, parallel work, immutable evidence and revision.
    phone_id=int(sql(database,"select m.id from plm_phone_model_member m join plm_phone_brand b on b.id=m.brand_id where m.status='enabled' and b.status='enabled' order by m.id limit 1"))
    evidence=LaunchEvidence(database,lambda method,path,body=None,success=True:api(method,path,body,token,success=success),detail,act,owner_id,ctx['upload'])
    references={};technical_refs={}
    def readiness(pid,profile=None,plan=None):
        current=detail(pid);workflow=current['workflow']
        return api('POST',f'/product-research/{pid}/design-readiness',{'profile':profile or workflow['profileDraft'],'plan':plan or {**workflow['planDraft'],'impactAcknowledged':True}},token)
    def stage_readiness(pid,**draft):
        before=detail(pid);stage=before['workflow'].get('revision',{}).get('stage') or before['currentStepNo']
        result=api('POST',f'/product-research/{pid}/stage-readiness',{'stage':stage,**draft},token)
        check('stage checklist is read only, including resolved links and derived yield '+str(pid)+' stage '+str(stage),detail(pid)==before)
        codes=[item['code'] for item in result['checks']]
        assert len(codes)==len(set(codes)),codes
        if stage<4:assert not any(item['code'] in ('PILOT','PILOT_REQUIREMENTS','UNIT_COST','RELEASE_DATE') for item in result['checks'])
        return result
    def launch_project(name,phone=phone_id,needs_mold=False,preliminary=False,engineering=True):
        pid=api('POST','/product-research',{'productName':name,'productType':'product_line','versionNo':'V1','createdBy':'fixture'},token)['productId']
        d=detail(pid)
        check('new project uses versioned launch flow '+str(pid),d['developmentMode']=='launch_v1')
        incomplete=readiness(pid,{'productName':'','requirements':'','model':'','ownerUserId':None,'phoneModelIds':[],'references':[]},{'phoneModelIds':[],'method':'own_mold','needsMold':True})
        missing={item['code'] for item in incomplete['checks'] if not item['complete']}
        check('read-only design preview reports all current input gaps without saving or inventing trial facts '+str(pid),not incomplete['ready'] and {'PRODUCT_NAME','REQUIREMENTS','OWNER','PHONES','MATERIAL_BASIS','DESIGN_DRAWING','DESIGN_REVIEW'}<=missing and detail(pid)==d)
        # Name alone is a real persisted draft.
        api('PUT',f'/product-research/{pid}',{'productName':name,'model':'','requirements':'','phoneModelIds':[],'ownerUserId':owner_id,'version':d['workflow']['version']},token)
        api('POST',f'/projects/{pid}/timeline/LAUNCH_DESIGN/confirm',{},token,success=False)
        api('PUT',f'/product-research/{pid}',{'productName':name,'model':'','requirements':'按键舒适、适配真实机型','phoneModelIds':[phone],'ownerUserId':owner_id,'version':detail(pid)['workflow']['version']},token)
        assignment={'mode':'internal' if needs_mold else 'supplier','internalName':'隔离工程车间' if needs_mold else '',
            'supplierCode':'' if needs_mold else supplier['supplierCode'],'role':'processing' if needs_mold else 'finished_product',
            'scope':'仅隔离验收：指定方案的模具与部件加工' if needs_mold else '仅隔离验收：已确认样品方案成品供货',
            'basis':'仅独立夹具中的显式采用方案，不代表真实业务确认'}
        plan={'phoneModelIds':[phone],'pilotDate':'','launchDate':'','method':'own_mold' if needs_mold else 'direct','needsMold':needs_mold,'references':[],'supplierAssignments':[assignment]}
        act('plan_draft',plan,pid)
        api('POST',f'/product-research/{pid}/workflow',{'action':'review_setup','version':detail(pid)['workflow']['version'],'data':{'plannedQty':10,'minYieldRate':100,'fullInspection':True}},token,success=False)
        act('confirm_design_basis',{},pid)
        references[pid]=evidence.technical(pid) if engineering else []
        if needs_mold:
            drawing=ctx['upload'](f'/projects/{pid}/attachments','qa-design.png',ctx['image'],{'fileCategory':'drawing','versionNo':'QA-D1'})
            references[pid].append({'type':'file','id':drawing['attachmentId']})
        technical_refs[pid]=list(references[pid])
        if preliminary:references[pid]=[r for r in references[pid] if r['type']=='file']
        if preliminary:
            pending=detail(pid)['workflow'];profile={k:v for k,v in pending['profileDraft'].items() if k in ('productName','model','requirements','phoneModelIds','ownerUserId','targetCost','expectedDeliveryDate','currency','costUnit','notes','pendingReason')};original_requirements=profile['requirements'];profile.update(requirements='隔离QA：尚未确认的新需求',version=pending['version'])
            api('PUT',f'/product-research/{pid}/design',{'profile':profile,'plan':pending['planDraft']},token)
            rejected=api('POST',f'/product-research/{pid}/workflow',{'action':'review_save','version':detail(pid)['workflow']['version'],'data':{'gate':'design','confirm':True,'reviewDate':today,'reviewerUserId':owner_id,'result':'passed','summary':'隔离QA','checklist':{'structure':True,'assembly':True,'manufacturability':True},'materialBasis':'PC与TPU','references':references[pid]}},token,success=False)
            check('pending business requirements block confirmation before a stale design review is committed','尚未确认' in rejected['message'] and not detail(pid)['workflow'].get('reviewRecords'))
            profile.update(requirements=original_requirements,version=detail(pid)['workflow']['version']);api('PUT',f'/product-research/{pid}/design',{'profile':profile,'plan':pending['planDraft']},token)
        evidence.review(pid,references[pid],materialBasis='隔离QA：PC背板、TPU包边，具体用料BOM待实样阶段整理' if preliminary else '')
        reviewed=detail(pid);w=reviewed['workflow']
        profile={k:v for k,v in w['profileDraft'].items() if k in ('productName','model','requirements','phoneModelIds','ownerUserId','targetCost','expectedDeliveryDate','currency','costUnit','notes','pendingReason')}
        profile.update(version=w['version'],references=references[pid])
        api('PUT',f'/product-research/{pid}/design',{'profile':profile,'plan':{**w['planDraft'],'references':references[pid]}},token)
        check('engineering documents adopted by design do not require confirming requirements or plan again '+str(pid),detail(pid)['workflow']['requirementVersion']==w['requirementVersion'] and detail(pid)['workflow']['plan']['planVersion']==w['plan']['planVersion'] and detail(pid)['reviewGates'][0]['status']=='passed')
        before_readiness=detail(pid);ready=readiness(pid)
        check('design checklist and the real passing handover agree on the current exact evidence '+str(pid),ready['ready'] and all(item['complete'] for item in ready['checks']) and detail(pid)==before_readiness)
        if preliminary:
            first=next(r['id'] for r in references[pid] if r['type']=='file')
            unreviewed=ctx['upload'](f'/attachments/{first}/versions','qa-unreviewed-design.png',ctx['image'],{'versionNo':'QA-U1','reason':'隔离QA：尚未评审的设计变更'})
            changed=[r for r in references[pid] if r['type']!='file']+[{'type':'file','id':unreviewed['attachmentId']}]
            profile.update(version=detail(pid)['workflow']['version'],references=changed)
            api('PUT',f'/product-research/{pid}/design',{'profile':profile,'plan':{**w['planDraft'],'references':changed}},token)
            changed_readiness=readiness(pid)
            check('a different selected drawing invalidates checklist readiness just like the real handover',not changed_readiness['ready'] and any(item['code']=='DESIGN_REVIEW' and not item['complete'] and item['target']=='design-review' for item in changed_readiness['checks']))
            api('POST',f'/projects/{pid}/timeline/LAUNCH_DESIGN/confirm',{},token,success=False)
            check('new technical draft still cannot bypass the approved design even though it is not a new product requirement',detail(pid)['currentStepNo']==1 and detail(pid)['workflow']['requirementVersion']==w['requirementVersion'])
            profile.update(version=detail(pid)['workflow']['version'],references=references[pid])
            api('PUT',f'/product-research/{pid}/design',{'profile':profile,'plan':{**w['planDraft'],'references':references[pid]}},token)
        api('POST',f'/projects/{pid}/timeline/LAUNCH_DESIGN/confirm',{},token)
        if preliminary:check('design hands over with a draft drawing and preliminary materials, without production BOM or trial dates',not any(r['type']=='bom' for r in detail(pid)['workflow']['stage1Basis']['design']['references']) and not detail(pid)['workflow']['plan'].get('pilotDate'))
        check('launch plan keeps exact phones and no copied sample facts '+str(pid),detail(pid)['workflow']['plan']['phoneModelIds']==[phone] and not detail(pid)['rounds'])
        return pid
    def launch_sample(pid,result='passed',previous=None,confirm=True):
        d=detail(pid);phones=d['workflow']['plan']['phoneModelIds']
        payload={'kind':'sample','recordDate':today,'title':'隔离 QA 样品','result':result,'issues':'隔离夹具：按键偏硬' if result=='failed' else '', 'changes':'隔离夹具：调整按键' if previous else '', 'retestResult':'隔离夹具：按键复验通过' if previous and result=='passed' else '', 'references':references[pid],'confirm':confirm,'details':{'phoneModelIds':phones,'makerName':'隔离 QA','designSummary':'仅隔离验收方案 D1','actualResult':'仅隔离夹具：实装、按键及支架验证通过，非真实业务事实' if result=='passed' else '', 'previousRoundId':previous},'version':d['workflow']['version'],'requestId':uuid.uuid4().hex}
        return api('POST',f'/product-research/{pid}/rounds',payload,token)
    def hand(pid,node):
        checked=stage_readiness(pid)
        check('shared checklist agrees with passing original stage handover '+str(pid)+' '+node,checked['ready'] and all(item['complete'] for item in checked['checks']))
        return api('POST',f'/projects/{pid}/timeline/{node}/confirm',{},token)
    direct=launch_project('新流程验收：直接拿样')
    launch_flow=api('GET',f'/projects/{direct}/timeline',token=token)
    check('new projects use the confirmed four-phase menus',[n['nodeName'] for n in launch_flow['nodes']]==['立项设计','模具开发','样品验证／改模','小批量试产'])
    pending=stage_readiness(direct)
    check('no-mold making checklist requires explicit current basis, without invented physical records',not pending['ready'] and any(c['code']=='NO_MOLD' and c['target']=='no-mold' and not c['complete'] for c in pending['checks']))
    for visitor in (limited_token,None):api('POST',f'/product-research/{direct}/stage-readiness',{'stage':2},visitor,success=False)
    api('POST',f'/product-research/{direct}/stage-readiness',{'stage':3},token,success=False)
    check('later-stage checklist preserves login, project scope and current-stage guards',True)
    reject_action('plan_draft',{},direct)
    api('POST',f'/projects/{direct}/timeline/LAUNCH_MOLD/confirm',{},token,success=False)
    act('no_mold',{'reason':'仅隔离夹具：采用既有方案，无新增实物模具','references':references[direct]},direct);hand(direct,'LAUNCH_MOLD')
    check('no-mold path requires an explicit design-linked basis without inventing physical rounds',detail(direct)['workflow']['stage2Basis']['noMold']['designReviewId'] and not detail(direct)['rounds'])
    pending=stage_readiness(direct)
    check('sample phase shows missing receipt, test criteria and reliability, without trial facts',not pending['ready'] and {'SAMPLE','TEST_REQUIREMENTS','RELIABILITY'}<={c['code'] for c in pending['checks'] if not c['complete']})
    before=detail(direct)
    invalid={'kind':'sample','recordDate':today,'title':'空白通过','result':'passed','references':[],'confirm':True,'details':{'phoneModelIds':[phone_id],'makerName':'内部','designSummary':'D1'},'version':before['workflow']['version']}
    api('POST',f'/product-research/{direct}/rounds',invalid,token,success=False)
    check('blank physical pass is blocked transactionally',detail(direct)==before)
    pending=launch_sample(direct,'pending',confirm=False)
    check('saving a receipt draft does not replace the adopted sample',not detail(direct)['workflow'].get('selectedRoundId'))
    api('DELETE',f'/product-research/{direct}/rounds/{pending["roundId"]}?version={detail(direct)["workflow"]["version"]}',token=token)
    fail=launch_sample(direct,'failed')
    api('POST',f'/product-research/{direct}/workflow',{'action':'select_round','version':detail(direct)['workflow']['version'],'data':{'roundId':fail['roundId']}},token,success=False)
    hand_path=f'/projects/{direct}/timeline/LAUNCH_VALIDATION/confirm'
    api('POST',hand_path,{},token,success=False)
    passed=launch_sample(direct,'passed',fail['roundId']);act('select_round',{'roundId':passed['roundId']},direct)
    check('failed sample remains immutable with actual retest linkage',any(r['roundId']==fail['roundId'] and r['result']=='failed' for r in detail(direct)['rounds']) and passed['details']['previousRoundId']==fail['roundId'])
    req={'type':'budget','name':'项目开发预算','amount':12000,'currency':'CNY'}
    budget=act('cost',req,direct);budget_id=next(e['data']['id'] for e in budget['events'] if e['kind']=='cost')
    expense=act('cost',{'type':'expense','name':'MCN 样品寄送','category':'promotion','amount':120,'currency':'CNY'},direct)
    expense_id=next(e['data']['id'] for e in expense['events'] if e['kind']=='cost')
    act('promotion',{'channel':'mcn','target':'测试合作机构','action':'准备视频素材','plannedDate':'2026-11-01','status':'pending','expenseId':expense_id},direct)
    pre=act('presale',{'channel':'电商','target':'测试客户','phoneModelId':phone_id,'quantity':30,'promisedDate':'2026-11-15','status':'reserved'},direct)
    pre_id=pre['workflow']['presales'][0]['id']
    act('presale',{**pre['workflow']['presales'][0],'quantity':20,'reason':'客户调整数量'},direct)
    updated=detail(direct)
    check('parallel records share project and preserve presale prior quantities',updated['workflow']['promotions'][0]['expenseId']==expense_id and updated['workflow']['presales'][0]['quantity']==20 and next(e['data']['before']['quantity'] for e in updated['events'] if e['kind']=='presale' and e['data'].get('before'))==30)
    # Independent directories project the same latest records, never duplicate historical versions.
    import urllib.parse
    directory=api('GET',f'/product-research/business?kind=presale&projectId={direct}&page=1&size=1',token=token)
    check('presale directory uses latest quantity and exact project relation',directory['totalElements']==1 and directory['summary']['quantity']==20 and directory['content'][0]['productId']==direct and directory['content'][0]['record']['recordVersion']==2)
    promotions=api('GET',f'/product-research/business?kind=promotion&projectId={direct}&keyword='+urllib.parse.quote('测试合作机构'),token=token)
    check('promotion directory exposes canonical related cost ID',promotions['totalElements']==1 and promotions['content'][0]['record']['expenseId']==expense_id)
    no_match=api('GET',f'/product-research/business?kind=promotion&projectId={direct}&status=done',token=token)
    check('directory filters statistics and pagination together',no_match['totalElements']==0 and no_match['summary']['recordCount']==0 and not no_match['content'])
    costs=api('GET',f'/product-research/business?kind=cost&projectId={direct}',token=token)
    check('cost directory reads original budgets and expenses',costs['totalElements']==1 and costs['content'][0]['budgetCount']==1 and costs['content'][0]['expenseCount']==1 and costs['content'][0]['budgets']['currencyGroups'][0]['budgetTotal']==12000)
    exact=api('GET',f'/development-expenses?projectId={direct}&expenseId={expense_id}',token=token)
    check('original ledger deep link filters exact selected record',exact['totalElements']==1 and exact['content'][0]['expenseId']==expense_id)
    api('GET',f'/product-research/business?kind=cost&projectId={direct}',token=limited_token,success=False)
    api('GET',f'/product-research/business?kind=presale&projectId={direct}',token=limited_token,success=False)
    check('directories retain money permissions and project scope',True)
    cancelled=act('presale',{'channel':'批发','target':'取消的预售验收','phoneModelId':phone_id,'quantity':15,'promisedDate':'2026-11-15','status':'cancelled'},direct)
    all_presales=api('GET',f'/product-research/business?kind=presale&projectId={direct}&size=1',token=token)
    next_presales=api('GET',f'/product-research/business?kind=presale&projectId={direct}&size=1&page=2',token=token)
    check('cancelled presales remain visible but are excluded from quantity and pages do not repeat',all_presales['totalElements']==2 and all_presales['summary']['quantity']==20 and all_presales['content'][0]['record']['id']!=next_presales['content'][0]['record']['id'])
    only_cancelled=api('GET',f'/product-research/business?kind=presale&projectId={direct}&status=cancelled',token=token)
    check('cancelled status uses the same zero-quantity summary and filtered records',only_cancelled['totalElements']==1 and only_cancelled['summary']['quantity']==0)
    other=launch_project('新流程验收：越权来源')
    api('POST',f'/product-research/{other}/workflow',{'action':'promotion','version':detail(other)['workflow']['version'],'data':{'channel':'mcn','target':'错项目','action':'寄样','plannedDate':'2026-11-01','status':'pending','expenseId':expense_id}},token,success=False)
    api('POST',f'/product-research/{other}/workflow',{'action':'presale','version':detail(other)['workflow']['version'],'data':{'channel':'电商','target':'错数量','phoneModelId':phone_id,'quantity':1.5,'promisedDate':'2026-11-15','status':'reserved'}},token,success=False)
    check('foreign project costs and fractional presales are rejected',not detail(other)['workflow'].get('promotions') and not detail(other)['workflow'].get('presales'))
    api('POST',f'/projects/{direct}/timeline/LAUNCH_VALIDATION/confirm',{},token,success=False)
    evidence.setup(direct)
    backdated=evidence.round_payload(direct,passed,'test');backdated['recordDate']=yesterday
    before_date=detail(direct);api('POST',f'/product-research/{direct}/rounds',backdated,token,success=False)
    check('reliability review cannot predate actual sample receipt and rejection leaves the current evidence intact',detail(direct)==before_date)
    reliability=evidence.round(direct,passed,'test');hand(direct,'LAUNCH_VALIDATION')
    check('reliability adopts the exact passed retest and direct path needs no fictional T0 mold',reliability['details']['sourceRoundId']==passed['roundId'] and not reliability['details']['t0Reviews'])
    pending=stage_readiness(direct)
    check('trial phase separately exposes its actual criteria, review, date and cost gaps',{'PILOT_REQUIREMENTS','PILOT','RELEASE_DATE','UNIT_COST'}<={c['code'] for c in pending['checks'] if not c['complete']})
    evidence.setup(direct);backdated=evidence.round_payload(direct,reliability,'pilot');backdated['recordDate']=yesterday
    before_date=detail(direct);api('POST',f'/product-research/{direct}/rounds',backdated,token,success=False)
    check('trial review cannot predate the reliability basis and cannot advance by backdating',detail(direct)==before_date)
    pilot=evidence.round(direct,reliability,'pilot');cost_id=evidence.cost(direct,next(r['id'] for r in references[direct] if r['type']=='bom'))
    act('release_draft',{'confirmationDate':today,'references':references[direct],'phoneModelIds':[phone_id],'pilotRoundId':pilot['roundId'],'unitCostVersionId':cost_id},direct);hand(direct,'LAUNCH_PILOT')
    api('POST',f'/projects/{direct}/publish',{},token,success=False)
    published_new=api('POST',f'/projects/{direct}/publish',{'riskConfirmed':True,'exceptionReason':'仅隔离夹具：报告及签样资料待补','supplementDueDate':'2026-11-01','developmentResponsibleUserId':owner_id,'developmentReferences':references[direct]},token)
    release1=detail(direct)['release'];snapshot_r1=json.dumps(release1,sort_keys=True)
    api('POST',f'/projects/{direct}/publish',{'riskConfirmed':True},token)
    check('direct route release only has applicable document gaps and deduplicates',len(detail(direct)['releases'])==1 and len(release1['missingItems'])==2 and not detail(direct)['workflow'].get('launched'))
    check('formal publication does not add a draft product to the sales catalog',catalog_ids()==initial_catalog)
    launch_data={'releaseId':release1['releaseId'],'actualDate':today,'channels':'电商测试店','supplyNote':'供货核对完成'}
    before_identity=detail(direct);before_counts=no_launch_side_effects()
    rejected=api('POST',f'/product-research/{direct}/workflow',{'action':'launch','version':before_identity['workflow']['version'],'requestId':uuid.uuid4().hex,'data':launch_data},token,success=False)
    check('missing product identity blocks launch and catalog admission in one transaction','产品特定编码' in rejected['message'] and detail(direct)==before_identity and catalog_ids()==initial_catalog and no_launch_side_effects()==before_counts)
    used=set(sql(database,"select product_specific_code from plm_product where product_specific_code is not null and deleted_flag=0").splitlines())
    product_specific=next(a+b for a in 'ZYXWVUTSRQPONMLKJIHGFEDCBA' for b in 'ABCDEFGHIJKLMNOPQRSTUVWXYZ' if a+b not in used)
    api('PUT',f'/products/{direct}',{'updatedBy':'fixture','productSpecificCode':product_specific,'productName':'不能夹带改写的正式产品名'},token,success=False)
    check('the released initial-code exception cannot smuggle changes to the formal product',detail(direct)==before_identity and catalog_ids()==initial_catalog)
    api('PUT',f'/products/{direct}',{'updatedBy':'fixture','productSpecificCode':product_specific},token)
    check('product code is first assigned through the original maintenance API and audited on the same product',detail(direct)['productSpecificCode']==product_specific and int(sql(database,f"select count(*) from plm_operation_log where action='PRODUCT_BUSINESS_IDENTITY_ASSIGN' and business_id='{direct}'"))==1)
    assigned=detail(direct);different_code=next(a+b for a in 'ZYXWVUTSRQPONMLKJIHGFEDCBA' for b in 'ABCDEFGHIJKLMNOPQRSTUVWXYZ' if a+b not in used and a+b!=product_specific)
    api('PUT',f'/products/{direct}',{'updatedBy':'fixture','productSpecificCode':different_code},token,success=False)
    check('an established product code cannot be rewritten through the initial-code repair path',detail(direct)==assigned)
    before_counts=no_launch_side_effects();request=uuid.uuid4().hex
    launch_payload={'action':'launch','version':detail(direct)['workflow']['version'],'requestId':request,'data':launch_data}
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures=[pool.submit(api,'POST',f'/product-research/{direct}/workflow',json.loads(json.dumps(launch_payload)),token) for _ in range(2)]
        concurrent_results=[future.result() for future in futures]
    check('concurrent first launch with the same request and viewed version returns the same committed result',all(row['productId']==direct for row in concurrent_results) and concurrent_results[0]['workflow']==concurrent_results[1]['workflow'])
    def launch_audits():
        return json.loads(sql(database,f"""select json_build_object(
            'events',(select count(*) from plm_product_development_event where product_id={direct} and kind='launch'),
            'workflowAudit',(select count(*) from plm_operation_log where business_id='{direct}' and action='PRODUCT_RESEARCH_LAUNCH'),
            'metadataAudit',(select count(*) from plm_operation_log where business_id='{direct}' and action='PRODUCTION_METADATA_UPDATE' and detail_json::jsonb ? 'researchLaunch'))::text"""))
    first_launch_audits=launch_audits()
    check('concurrent sales admission commits one launch event one workflow audit and one catalog audit',first_launch_audits=={'events':1,'workflowAudit':1,'metadataAudit':1})
    after_market=api('GET','/market-watch/items?opportunityView=all&page=1&size=1',token=token)['content'][0]
    after_catalog=catalog_ids();options=api('GET','/mold-applications/options',token=token)['productLines']
    check('first actual launch automatically admits the same product once and changes the opening denominator 7 to 8 when the source has seven',after_catalog==initial_catalog|{direct} and len(after_catalog)==len(initial_catalog)+1 and (len(initial_catalog)!=7 or len(after_catalog)==8) and after_market['coverageTotal']==initial_market['coverageTotal']+1)
    check('new product appears in opening application choices without inventing phone mold coverage',next(row for row in options if row['productId']==direct)['moldApplicationEligibility']['eligible'] and next(row for row in after_market['productCoverage'] if row['productLineId']==direct)['status']=='to_apply' and after_market['openedProductCount']==initial_market['openedProductCount'])
    check('sales admission does not create SKU inventory molds applications BOM lines or expenses',no_launch_side_effects()==before_counts)
    launched=detail(direct);act('launch',launch_data,direct,request)
    check('repeated launch request cannot add another product or repeat workflow audit',catalog_ids()==after_catalog and detail(direct)==launched and no_launch_side_effects()==before_counts and launch_audits()==first_launch_audits)
    act('transfer',{'releaseId':release1['releaseId'],'actualDate':today,'recipient':'测试接收方'},direct)
    transfer=next(e['eventId'] for e in detail(direct)['events'] if e['kind']=='transfer')
    act('receipt',{'releaseId':release1['releaseId'],'transferEventId':transfer,'actualDate':today,'recipient':'测试接收人','note':'已核对正式包 R1'},direct)
    check('actual launch and transfer/receipt are separate release-specific facts',detail(direct)['workflow']['launched']['releaseId']==release1['releaseId'] and any(e['kind']=='receipt' for e in detail(direct)['events']))
    act('start_revision',{'releaseId':release1['releaseId'],'reason':'文字更正','impact':'不改变产品实际方案','technical':False},direct)
    draft=detail(direct)['workflow']['releaseDraft'];draft['confirmationDate']=today;act('release_draft',draft,direct);act('revision_handover',{'stage':4},direct)
    api('POST',f'/projects/{direct}/publish',{'riskConfirmed':True,'exceptionReason':'沿用原缺口计划','supplementDueDate':'2026-11-01'},token)
    check('R2 publication retains original R1 and actual launch facts',len(detail(direct)['releases'])==2 and json.dumps(detail(direct)['releases'][1],sort_keys=True)==snapshot_r1 and detail(direct)['workflow']['launched']['releaseId']==release1['releaseId'])
    act('start_revision',{'releaseId':detail(direct)['release']['releaseId'],'reason':'修改按键结构','impact':'需要新样品实际复验','technical':True},direct)
    api('POST',f'/product-research/{direct}/workflow',{'action':'revision_handover','version':detail(direct)['workflow']['version'],'data':{'stage':1}},token,success=False)
    act('confirm_design_basis',{},direct);evidence.review(direct,references[direct]);act('revision_handover',{'stage':1},direct)
    api('POST',f'/product-research/{direct}/workflow',{'action':'revision_handover','version':detail(direct)['workflow']['version'],'data':{'stage':2}},token,success=False)
    act('no_mold',{'reason':'隔离夹具：本修订仍采用既有方案，无新模具','references':references[direct]},direct);act('revision_handover',{'stage':2},direct)
    renewed=launch_sample(direct,'passed',passed['roundId']);act('select_round',{'roundId':renewed['roundId']},direct)
    api('POST',f'/product-research/{direct}/workflow',{'action':'revision_handover','version':detail(direct)['workflow']['version'],'data':{'stage':3}},token,success=False)
    revised_test=evidence.round(direct,renewed,'test');act('revision_handover',{'stage':3},direct);revised_pilot=evidence.round(direct,revised_test,'pilot')
    act('release_draft',{'confirmationDate':today,'phoneModelIds':[phone_id],'references':references[direct],'pilotRoundId':revised_pilot['roundId'],'unitCostVersionId':cost_id},direct);act('revision_handover',{'stage':4},direct)
    api('POST',f'/projects/{direct}/publish',{'riskConfirmed':True,'exceptionReason':'实际复验通过，报告待补','supplementDueDate':'2026-11-01'},token)
    check('technical R3 requires renewed design, path, sample, reliability and pilot evidence',len(detail(direct)['releases'])==3 and detail(direct)['workflow']['stage3Basis']['sample']['roundId']==renewed['roundId'])
    api('PATCH',f'/products/{direct}/production-status',{'changes':{'salesStatus':'off_sale'},'original':{'salesStatus':'on_sale'},'reason':'仅隔离验收：停售后修改上市记录不得恢复在售'},token)
    off_sale_counts=no_launch_side_effects();act('launch',{'releaseId':detail(direct)['release']['releaseId'],'actualDate':today,'channels':'隔离修正渠道','reason':'仅更正上市历史，不恢复销售计划'},direct)
    stopped_market=api('GET','/market-watch/items?opportunityView=all&page=1&size=1',token=token)['content'][0]
    stopped_options=api('GET','/mold-applications/options',token=token)['productLines']
    check('adjusting launch history after discontinuation never restores sales or the coverage denominator',detail(direct)['salesStatus']=='off_sale' and catalog_ids()==initial_catalog and stopped_market['coverageTotal']==initial_market['coverageTotal'] and not next(row for row in stopped_options if row['productId']==direct)['moldApplicationEligibility']['eligible'] and no_launch_side_effects()==off_sale_counts)
    # Supplement remains linked to its original missing fact; uploading alone cannot close it.
    package=detail(direct)['release'];package_json=json.dumps(package,sort_keys=True)
    report=ctx['upload'](f'/projects/{direct}/attachments','launch-validation.png',ctx['image'],{'fileCategory':'testing','versionNo':'VR3'})
    check('uploading a launch report does not silently close the original package gap',json.dumps(detail(direct)['release'],sort_keys=True)==package_json)
    act('supplement',{'releaseId':package['releaseId'],'gapCode':'RELEASE_TESTING','references':[{'type':'file','id':report['attachmentId']}],'actualDate':today,'note':'补齐本轮实际验证报告'},direct)
    check('launch supplementation preserves the exact immutable package and original missing fact',json.dumps(detail(direct)['release'],sort_keys=True)==package_json and any(e['kind']=='supplement' for e in detail(direct)['events']))
    bought=launch_project('新流程验收：无生产BOM的成品采购',engineering=False)
    act('no_mold',{'reason':'隔离QA：采购现有成品，不新增模具','references':[]},bought);hand(bought,'LAUNCH_MOLD')
    bought_sample=launch_sample(bought);act('select_round',{'roundId':bought_sample['roundId']},bought)
    bought_test=evidence.round(bought,bought_sample,'test');hand(bought,'LAUNCH_VALIDATION');bought_pilot=evidence.round(bought,bought_test,'pilot')
    quoted=act('quote',{'supplierCode':supplier['supplierCode'],'amount':12,'currency':'CNY','unit':'每件','conditions':'仅隔离QA，不是真实报价'},bought)
    quote_id=next(e['eventId'] for e in quoted['events'] if e['kind']=='quote')
    rows=[{'id':g,'category':g,'name':'隔离QA '+g,'mode':'unit','unitPrice':12 if g=='material' else 0,'taxIncluded':False,'taxRate':0} for g in ('material','process','tooling','tool_transport','development')]
    bought_cost=act('unit_cost_confirm',{'title':'隔离QA 采购单件核算','basisType':'supplier_quote','quoteEventId':quote_id,'quoteUnitNote':'每件未税；无需数量及币种转换','currency':'CNY','taxBasis':'net','rows':rows},bought)['workflow']['unitCostVersions'][-1]
    act('release_draft',{'confirmationDate':today,'phoneModelIds':[phone_id],'references':bought_pilot['references'],'pilotRoundId':bought_pilot['roundId'],'unitCostVersionId':bought_cost['id']},bought);hand(bought,'LAUNCH_PILOT')
    check('finished-goods purchase can complete all four phases using the same cost sheet and existing quote history without a fictitious BOM',bought_cost['adoptedQuote']['supplierCode']==supplier['supplierCode'] and not bought_cost.get('bomCostSnapshotId') and not any(r['type']=='bom' for r in detail(bought)['materials']))
    # A real canonical mold object from the clone, selected through actual compatibility.
    compatible=sql(database,"select c.phone_model_id||','||c.mold_set_id from plm_collaboration_phone_set_compatibility c join plm_collaboration_mold_set s on s.mold_set_id=c.mold_set_id and s.deleted_flag=0 join plm_phone_model_member m on m.id=c.phone_model_id and m.status='enabled' join plm_phone_brand b on b.id=m.brand_id and b.status='enabled' where not exists(select 1 from plm_mold_application a where a.mold_set_id=s.mold_set_id and a.deleted_flag=0 and a.execution_status in ('paused','terminated')) limit 1")
    if not compatible:
        # The source currently has no per-set compatibility facts. Seed ONLY this disposable clone.
        fixture_mold=sql(database,"select s.mold_set_id||','||s.item_id from plm_collaboration_mold_set s where s.deleted_flag=0 and not exists(select 1 from plm_mold_application a where a.mold_set_id=s.mold_set_id and a.deleted_flag=0 and a.execution_status in ('paused','terminated')) limit 1")
        mid,iid=map(int,fixture_mold.split(','))
        sql(database,f"insert into plm_collaboration_phone_member(item_id,phone_model_id,mold_set_ids,pc_confirmed,tpu_confirmed,evidence,created_by,updated_by) values({iid},{phone_id},array[{mid}],false,false,'独立验收夹具，不代表真实适配','qa_fixture','qa_fixture') on conflict do nothing;insert into plm_collaboration_phone_set_compatibility(item_id,phone_model_id,mold_set_id,created_by,updated_by) values({iid},{phone_id},{mid},'qa_fixture','qa_fixture');")
        compatible=f'{phone_id},{mid}' 
    mold_phone,mold_id=map(int,compatible.split(','));made=launch_project('新流程验收：模具验证',mold_phone,True,preliminary=True)
    act('mold_link',{'moldSetId':mold_id},made)
    pending=stage_readiness(made)
    check('making checklist identifies the exact unfinished physical set',not pending['ready'] and any(c['target']==f'mold:{mold_id}' and not c['complete'] for c in pending['checks']))
    linked_sets=api('GET',f'/collaboration-plans/schedules?projectId={made}',token=token)
    check('selected mold appears in the original schedule project filter',any(r['moldSetId']==mold_id for r in linked_sets['content']))
    before=detail(made)
    api('POST',f'/product-research/{made}/workflow',{'action':'mold_node','version':before['workflow']['version'],'data':{'moldSetId':mold_id,'node':'testing','nodeVersion':0,'actualDate':today,'result':'passed','note':'绕过制作','confirm':True}},token,success=False)
    check('future mold node cannot be confirmed',detail(made)==before)
    def mold_node(node,result,note='',confirm=True):
        mold=detail(made)['molds'][0];dev=mold.get('development',{})
        return act('mold_node',{'moldSetId':mold_id,'node':node,'nodeVersion':dev.get('version',0),'actualDate':today if confirm else '', 'result':result,'note':note,'references':references[made],'confirm':confirm},made)
    mold_node('making','',confirm=False)
    check('mold draft persists without advancing current node',detail(made)['molds'][0]['development'].get('node','making')=='making')
    mold_node('making','complete');hand(made,'LAUNCH_MOLD')
    references[made]=technical_refs[made]
    sm=launch_sample(made);act('select_round',{'roundId':sm['roundId']},made)
    evidence.review(made,references[made],sm)
    check('sample-stage design refinement adopts the actual BOM without reentering project requirements',detail(made)['workflow']['requirementVersion']==1 and detail(made)['reviewGates'][0]['status']=='passed')
    evidence.review(made,references[made],sm,mold_id,result='failed',issues='隔离夹具：按键孔尺寸偏紧',checklist={'structure':True,'assembly':False,'manufacturability':True})
    mold_node('testing','failed','隔离夹具：按键孔尺寸偏紧，退回改模')
    api('POST',f'/projects/{made}/timeline/LAUNCH_VALIDATION/confirm',{},token,success=False)
    pending=stage_readiness(made)
    check('failed mold acceptance and per-set T0 remain separately actionable',not pending['ready'] and any(c['target']==f'review:t0:{mold_id}' and not c['complete'] for c in pending['checks']) and any(c['target']==f'mold:{mold_id}' and not c['complete'] for c in pending['checks']))
    check('actual mold failure blocks handover and remains in canonical history',detail(made)['molds'][0]['development']['node']=='rework')
    original_drawing=next(r['id'] for r in references[made] if r['type']=='file')
    drawing2=ctx['upload'](f'/attachments/{original_drawing}/versions','qa-design-v2.png',ctx['image'],{'versionNo':'QA-D2','reason':'隔离QA：修改按键孔尺寸'})
    references[made]=[r for r in references[made] if r['type']!='file']+[{'type':'file','id':drawing2['attachmentId']}]
    evidence.review(made,references[made])
    mold_node('rework','complete','隔离夹具：按新设计图完成改模，尚未再次收样')
    check('rework can finish against the newly approved drawing before the next real sample arrives',detail(made)['molds'][0]['development']['node']=='testing')
    sm=launch_sample(made,previous=sm['roundId']);act('select_round',{'roundId':sm['roundId']},made)
    evidence.review(made,references[made],sm,mold_id);evidence.round(made,sm,'test')
    mold_node('testing','passed','仅隔离夹具：尺寸、试装、按键通过')
    api('POST',f'/projects/{made}/timeline/LAUNCH_VALIDATION/confirm',{},token,success=False)
    pending=stage_readiness(made)
    check('passing actual acceptance still requires explicit PC and TPU coverage for every planned phone',not pending['ready'] and any(c['code']=='MOLD_COVERAGE' and not c['complete'] for c in pending['checks']))
    item_id=int(sql(database,f'select item_id from plm_collaboration_mold_set where mold_set_id={mold_id}'))
    original=api('GET',f'/collaboration-plans/items/{item_id}',token=token)['compatiblePhones']
    updated=json.loads(json.dumps(original))
    for member in updated:
        if member['phoneModelId']==mold_phone:
            for fit in member['setCompatibilities']:
                if fit['moldSetId']==mold_id:fit.update(pcConfirmed=True,tpuConfirmed=True,pcEvidence='隔离QA逐套PC试装确认，非业务结果',tpuEvidence='隔离QA逐套TPU试装确认，非业务结果')
    saved=api('POST','/collaboration-plans/batch-save',{'rows':[{'entityType':'item','id':item_id,'changes':[{'field':'compatiblePhones','before':original,'after':updated,'reason':'隔离用例明确确认逐套PC／TPU适配'}]}]},token)
    check('partial or unconfirmed part fit cannot pass, explicit per-set fit uses the original maintenance writer',saved[0]['status']=='saved')
    hand(made,'LAUNCH_VALIDATION')
    check('mold rework requires a fresh cycle T0 and reliability test before pilot',detail(made)['currentStepNo']==4 and detail(made)['workflow']['stage3Basis']['molds']['molds'][0]['development']['last']['result']=='passed')
    current_test=next(r for r in detail(made)['rounds'] if r['kind']=='test' and r['status']=='confirmed' and r['result']=='passed')
    evidence.setup(made)
    sop=ctx['upload'](f'/projects/{made}/attachments','qa-first-trial-sop.png',ctx['image'],{'fileCategory':'sop','versionNo':'QA-S1'})
    pilot_payload=evidence.round_payload(made,current_test,'pilot');pilot_payload['references'].append({'type':'file','id':sop['attachmentId']})
    made_pilot=api('POST',f'/product-research/{made}/rounds',pilot_payload,token)
    made_cost=evidence.cost(made,next(r['id'] for r in references[made] if r['type']=='bom'))
    bom_id=next(r['id'] for r in references[made] if r['type']=='bom');process_id=next(r['id'] for r in references[made] if r['type']=='process')
    check('formal engineering uses the original current-BOM confirmation and its actual locked route, never a freeze shortcut',api('GET',f'/boms/{bom_id}',token=token)['currentFormal'] and api('GET',f'/process-routes/{process_id}',token=token)['status']=='locked')
    act('release_draft',{'confirmationDate':today,'references':made_pilot['references'],'phoneModelIds':[mold_phone],'pilotRoundId':made_pilot['roundId'],'unitCostVersionId':made_cost},made)
    hand(made,'LAUNCH_PILOT')
    check('trial-first SOP stays linked to the actual trial and does not require fabricated early evidence',any(r.get('category')=='sop' for r in detail(made)['workflow']['finalized']['references']) and not detail(made)['workflow']['finalized'].get('documentationOnly'))
    # Optimistic version and deduplication, including the real finance writer.
    before=detail(made);request=uuid.uuid4().hex
    payload={'action':'presale','version':before['workflow']['version'],'requestId':request,'data':{'channel':'批发','target':'测试客户2','phoneModelId':mold_phone,'quantity':10,'promisedDate':'2026-11-20','status':'reserved'}}
    api('POST',f'/product-research/{made}/workflow',payload,token);api('POST',f'/product-research/{made}/workflow',payload,token)
    check('repeated parallel submit is idempotent',len(detail(made)['workflow']['presales'])==1)
    payload['requestId']=uuid.uuid4().hex;api('POST',f'/product-research/{made}/workflow',payload,token,success=False)
    check('stale version cannot overwrite current project data',len(detail(made)['workflow']['presales'])==1)
    sql(database,f"insert into plm_pilot_scope(user_id,entity_type,entity_id) values({limited_id},'product',{direct}),({limited_id},'product',{other}) on conflict do nothing")
    limited_launch=api('GET',f'/product-research/{direct}',token=limited_token)
    check('new project financial history follows existing finance permissions',all(e['data'].get('restricted') is True for e in limited_launch['events'] if e['kind']=='cost'))
    limited_options=api('GET','/product-research/options',token=limited_token)
    check('engineers without supplier access still load owners and never receive hidden supplier choices',bool(limited_options['owners']) and not limited_options['canSelectSupplier'] and not limited_options['suppliers'] and limited_launch['workflow']['planDraft']['suppliersRestricted'] and not limited_launch['workflow']['planDraft']['supplierAssignments'])
    before_engineering=detail(other)
    rejected=api('POST',f'/product-research/{other}/workflow',{'action':'engineering_confirm','version':before_engineering['workflow']['version'],'requestId':uuid.uuid4().hex,'data':{'stage':2}},limited_token,success=False)
    check('project access and document write alone cannot confirm an engineering scheme',rejected['code']!=0 and detail(other)==before_engineering)
    # A stale timeline confirm cannot confirm a newer draft that the caller has not reviewed.
    latest=detail(other)
    stale=urllib.request.Request(base+f'/projects/{other}/timeline/LAUNCH_MOLD/confirm',data=json.dumps({'developmentVersion':0}).encode(),method='POST',headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
    try:
        with urllib.request.urlopen(stale) as res: stale_reply=json.load(res)
    except urllib.error.HTTPError as ex: stale_reply=json.load(ex)
    check('original timeline entry also enforces the viewed development version',stale_reply.get('code')!=0 and detail(other)==latest)
    (output/'launch-fixtures.json').write_text(json.dumps({'direct':direct,'mold':made,'phone':phone_id,'moldPhone':mold_phone,'moldSetId':mold_id}))


if __name__=='__main__':
    main(verify_launch)
