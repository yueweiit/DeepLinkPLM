#!/usr/bin/env python3
"""Exercise draft deletion using only a disposable local PostgreSQL clone."""
from concurrent.futures import ThreadPoolExecutor
import json
import uuid
from local_research_fixture import main, sql, quote


def verify(ctx):
    api,check,token,db,account=(ctx[k] for k in ['api','check','token','database','account'])
    key=lambda:uuid.uuid4().hex
    def post(path,body,auth=token): return api('POST',path,body,auth)
    def get(a,auth=token):return api('GET',f'/mold-applications/{a}',token=auth)
    def delete(a,v,auth=token,success=True):return api('DELETE',f'/mold-applications/{a}?version={v}',token=auth,success=success)
    brand=post('/molds/reference-data/brands',{'name':'草稿删除验收 '+key()[:8]})
    def draft(label,auth=token):
        phone=post('/molds/reference-data/phone-models',{'brandId':brand['brandId'],'modelName':label+' '+key()[:6]})
        return post('/mold-applications/from-phone',{'phoneModelId':phone['phoneModelId'],'requestId':key()},auth)
    def deleted(a):return sql(db,f'select deleted_flag from plm_mold_application where application_id={a};')=='1'
    def audits(a):return int(sql(db,f"select count(*) from plm_operation_log where action='MOLD_APPLICATION_DRAFT_DELETE' and business_id='{a}';"))
    def mutable(a,changes):sql(db,f'update plm_mold_application set {changes} where application_id={a};')
    def totals():return api('GET','/mold-applications/summary',token=token)
    first=draft('可删除草稿');a=first['applicationId'];v=first['version'];phone=first['phoneModelId']
    before_phone=sql(db,f'select row_to_json(p) from plm_phone_model_member p where id={phone};')
    before=totals();check('own local draft has delete capability',get(a)['canDeleteDraft'])
    result=delete(a,v)
    check('delete succeeds and tombstones the draft',result=={'applicationId':a,'deleted':True} and deleted(a))
    after=totals()
    check('summary and draft count each decrease by one',sum(x['count'] for x in before['counts'])-sum(x['count'] for x in after['counts'])==1 and next(x['count'] for x in before['businessCounts'] if x['businessStatus']=='draft')-next((x['count'] for x in after['businessCounts'] if x['businessStatus']=='draft'),0)==1)
    check('list excludes deleted draft',not any(x['applicationId']==a for x in api('GET','/mold-applications?status=draft&size=100',token=token)['content']))
    api('GET',f'/mold-applications/{a}',token=token,success=False)
    delete(a,v,success=False)
    check('repeat delete produces one audit and preserves phone master',audits(a)==1 and before_phone==sql(db,f'select row_to_json(p) from plm_phone_model_member p where id={phone};'))
    fresh=post('/mold-applications/from-phone',{'phoneModelId':phone,'requestId':key()})
    check('new application after deletion does not resurrect old draft',fresh['applicationId']!=a and deleted(a))

    stale=draft('版本冲突');a=stale['applicationId'];mutable(a,'version_no=version_no+1')
    rejected=delete(a,stale['version'],success=False)
    check('stale version is rejected without deletion or audit',rejected['code']==40901 and not deleted(a) and audits(a)==0)

    for label,changes in [
        ('已提交',"status='submitted'"),('提交待核对',"status='submission_unknown'"),
        ('钉钉来源',"source_system='dingtalk'"),('已关联审批',"external_instance_id='qa-"+key()+"'"),
        ('已获审批',"approval_granted=true"),('已转项目',f"project_id={ctx['old_id']}")]:
        d=draft(label);a=d['applicationId'];mutable(a,changes)
        check(label+' has no delete action',get(a)['canDeleteDraft'] is False)
        delete(a,d['version'],success=False)
        check(label+' cannot be removed through direct API',not deleted(a) and audits(a)==0)

    _,reader=account('mold-delete-reader','domestic',['collaboration:view'],True)
    _,other=account('mold-delete-maintainer','domestic',['collaboration:view','mold-application:create'],True)
    external_id,external=account('mold-delete-external','supplier',['collaboration:view','mold-application:create'],False)
    d=draft('权限检查');a=d['applicationId']
    for label,auth in [('read only',reader['token']),('other maintainer',other['token']),('external supplier',external['token'])]:
        if label=='external supplier':mutable(a,f'applicant_id={external_id}')
        check(label+' sees no deletion capability',not get(a,auth)['canDeleteDraft'])
        delete(a,d['version'],auth,False)
        check(label+' direct deletion leaves draft and audit intact',not deleted(a) and audits(a)==0)
    owned=draft('本人维护人',other['token']);a=owned['applicationId']
    check('authorized non-admin owner can delete their own draft',get(a,other['token'])['canDeleteDraft'])
    delete(a,owned['version'],other['token'])
    check('owner deletion succeeds',deleted(a))

    d=draft('研发关联');a=d['applicationId'];p=ctx['legacy']['productId']
    sql(db,f"insert into plm_product_development_state(product_id,data) values({p},jsonb_build_object('moldLinks',jsonb_build_array(jsonb_build_object('applicationId',{a})))) on conflict(product_id) do update set data=excluded.data;")
    rejected=delete(a,d['version'],success=False)
    check('development source references block deletion',not deleted(a) and '新品研发' in rejected['message'])
    sql(db,f'delete from plm_product_development_state where product_id={p};')
    delete(a,d['version']);check('unlinked unconfirmed draft can then be deleted',deleted(a))

    d=draft('确认样品引用');a=d['applicationId']
    sql(db,f"insert into plm_product_development_round(product_id,round_no,kind,record_date,title,result,status,confirmed_at,confirmed_by,details_json,created_by,updated_by) values({p},1,'sample',current_date,'独立验收来源','passed','confirmed',now(),'qa',jsonb_build_object('moldApplication',jsonb_build_object('application_id',{a})),'qa','qa');")
    delete(a,d['version'],success=False)
    check('confirmed sample history preserves its original application source',not deleted(a) and audits(a)==0)

    d=draft('审计原子性');a=d['applicationId']
    sql(db,"create function qa_reject_draft_delete_audit() returns trigger language plpgsql as $$ begin if new.action='MOLD_APPLICATION_DRAFT_DELETE' then raise exception 'isolated QA audit failure'; end if; return new; end $$; create trigger qa_delete_audit before insert on plm_operation_log for each row execute function qa_reject_draft_delete_audit();")
    try:
        delete(a,d['version'],success=False)
        check('audit failure rolls the deletion and version back',not deleted(a) and get(a)['version']==d['version'] and audits(a)==0)
    finally:sql(db,'drop trigger qa_delete_audit on plm_operation_log; drop function qa_reject_draft_delete_audit();')
    delete(a,d['version']);check('retry after audit recovery succeeds once',deleted(a) and audits(a)==1)

    original=draft('撤回原单');o=original['applicationId'];mutable(o,"status='terminated',remote_status='TERMINATED',external_instance_id='qa-"+key()+"'")
    revision=draft('撤回修订草稿');a=revision['applicationId'];mutable(a,f'previous_application_id={o}')
    check('withdrawn original is recognized before draft deletion',get(o)['businessStatus']=='withdrawn')
    delete(a,revision['version'])
    check('deleting revision preserves original withdrawn status and evidence',get(o)['businessStatus']=='withdrawn' and not deleted(o))

    concurrent=draft('并发重复删除');a=concurrent['applicationId']
    def request(_):
        try:return delete(a,concurrent['version'])
        except AssertionError:return None
    with ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(request,range(2)))
    check('concurrent duplicate delete commits exactly once',sum(r is not None for r in results)==1 and deleted(a) and audits(a)==1)
    browser=[draft('浏览器删除验收'),draft('浏览器详情删除验收')]
    (ctx['output']/'mold-delete-fixtures.json').write_text(json.dumps({'drafts':browser,'brand':brand},ensure_ascii=False,indent=2))

if __name__=='__main__':main(verify)
