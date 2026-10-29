"""Shared explicit review fixtures, permitted only in disposable LOCAL QA databases.

These are synthetic acceptance facts, not production approvals or physical results.
All domain writes go through the existing PLM APIs and retain normal gate checks.
"""
from datetime import date
import base64
import json
import uuid
from local_research_fixture import sql


class LaunchEvidence:
    def __init__(self, database, api, detail, action, reviewer_id, upload):
        if not database.startswith(('plm_alignment_', 'plm_research_verify_')):
            raise ValueError('Review fixtures must never run in the normal local database')
        self.database, self.api, self.detail, self.action, self.reviewer = database, api, detail, action, reviewer_id
        self.upload = upload
        self.today = date.today().isoformat()

    def setup(self, pid):
        d=self.detail(pid);stage=d['workflow'].get('revision',{}).get('stage') or d['currentStepNo']
        if stage==3:
            values={'tests':[{'code':'QA-FIT','name':'隔离夹具适配检查','requirement':'仅验收：结构及装配记录完整','minValue':1}]}
        elif stage==4:
            values={'plannedQty':10,'minYieldRate':100,'fullInspection':True}
        else:
            raise ValueError('Testing criteria belong to sample validation; trial criteria belong to the trial stage')
        return self.action('review_setup',{**values,'reason':'隔离验收：在所属阶段维护当前标准'},pid)

    def review(self, pid, refs, sample=None, mold=None, **overrides):
        payload = {'gate': 't0' if mold else 'design', 'reviewDate': self.today,
            'reviewerUserId': self.reviewer, 'result': 'passed', 'confirm': True,
            'summary': '仅隔离验收夹具：核对具体技术版本，不代表真实业务评审',
            'checklist': {'structure': True, 'assembly': True, 'manufacturability': True}, 'references': refs}
        if sample:
            payload['sampleRoundId'] = sample['roundId']
        if mold:
            payload['moldSetId'] = mold
        return self.action('review_save', {**payload, **overrides}, pid)

    def round_payload(self, pid, source, kind, **overrides):
        facts = {'phoneModelIds': source['details']['phoneModelIds'], 'sourceRoundId': source['roundId'],
            'reviewerUserId': self.reviewer, 'actualResult': '仅隔离验收夹具，不代表实际生产或测试结果'}
        if kind == 'test':
            facts.update(testContent='隔离夹具：结构、装配及版本追溯', tests=[{
                'code': 'QA-FIT', 'result': 'passed', 'measuredValue': 1,
                'actual': '仅隔离验收夹具：结构及装配记录完整', 'method': '隔离用例',
                'tester': '隔离 QA', 'sampleQty': 1, 'date': self.today}])
        else:
            facts.update(producedQty=10, inspectedQty=10, defectQty=0,
                inspectionMethod='隔离夹具全检', defectSummary='仅隔离验收夹具：0 不良，无真实生产指令')
        facts.update(overrides)
        return {'kind': kind, 'recordDate': self.today, 'title': '隔离 QA：' + kind,
            'result': 'passed', 'references': [{'type': r['type'], 'id': r['id']} for r in source['references']],
            'confirm': True, 'details': facts, 'version': self.detail(pid)['workflow']['version'],
            'requestId': uuid.uuid4().hex}

    def round(self, pid, source, kind):
        self.setup(pid)
        payload = self.round_payload(pid, source, kind)
        if kind == 'test':
            image = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==')
            report = self.upload(f'/projects/{pid}/attachments', 'qa-only-testing-' + uuid.uuid4().hex + '.png', image, {'fileCategory': 'testing', 'versionNo': 'QA-T1'})
            payload['references'].append({'type': 'file', 'id': report['attachmentId']})
        return self.api('POST', f'/product-research/{pid}/rounds', payload)

    def cost(self, pid, bom_id):
        workbench = self.api('GET', f'/boms/{bom_id}/workbench')
        currency = self.api('GET', f'/boms/{bom_id}').get('currencyCode') or 'USD'
        rows = [{'id': group, 'category': group, 'name': '隔离 QA：' + group,
            'mode': 'unit', 'unitPrice': 1 if group in ('material', 'process') else 0,
            'taxIncluded': False, 'taxRate': 0,
            'remark': '显式隔离夹具金额；0 表示本用例无此费用，不是来源缺项补零'}
            for group in ('material', 'process', 'tooling', 'tool_transport', 'development')]
        result = self.action('unit_cost_confirm', {'title': '隔离 QA 单件核算', 'currency': currency,
            'taxBasis': 'net', 'bomId': bom_id, 'routeId': workbench['routes'][0]['productBomRouteId'],
            'references': [{'type': 'bom', 'id': bom_id}], 'rows': rows,
            'reason': '隔离验收具体BOM成本版本', 'notes': '仅隔离夹具，非来源价格、实际报价或付款事实'}, pid)
        return result['workflow']['unitCostVersions'][-1]['id']

    def technical(self, pid):
        """Use a real MES graph/material identity; create a separate marked QA recipe."""
        route_id = int(sql(self.database, "select min(object_id) from plm_master_data_source where source_system='mes' and source_kind='process_flows'"))
        template = self.api('GET', f'/process-routes/{route_id}')
        keys = {n['processId']: n['nodeCode'] for n in template['operations']}
        edges = [{'fromNodeCode': keys[e['fromNodeId']], 'toNodeCode': keys[e['toNodeId']],
            'edgeNo': e['edgeNo'], 'relationType': e['relationType'], 'label': e.get('label'),
            'condition': e.get('condition')} for e in template['graph']['edges']]
        graph = self.api('POST', f'/projects/{pid}/process-routes', {'processName': '隔离 QA：MES 原工艺图',
            'versionNo': 'QA-G1', 'sourceProcessId': route_id, 'sourceRouteCode': template['sourceRouteCode'],
            'operations': template['operations'], 'edges': edges})
        bom = self.api('POST', f'/projects/{pid}/boms', {'bomName': '隔离 QA 配方（非业务）', 'bomType': 'mbom',
            'versionNo': 'QA-B1', 'processId': graph['processId'], 'parentCode': f'QA-{pid}',
            'parentName': '隔离 QA 母件', 'outputQty': '1.0000'})
        material = json.loads(sql(self.database, "select to_json(i)::text from plm_inventory i join plm_master_data_source s on s.object_id=i.inventory_id and s.source_kind='material_items' where i.deleted_flag=0 order by i.inventory_id limit 1"))
        color = json.loads(sql(self.database, "select to_json(c)::text from plm_code_item c where code_type='color' and status='enabled' and deleted_flag=0 order by code_value limit 1"))
        workbench = self.api('GET', f'/boms/{bom["productBomId"]}/workbench')
        routes = workbench['routes']
        routes[0]['colors'] = [color['code_value']]
        routes[0]['colorItems'] = [{'codeItemId': color['code_item_id'], 'codeValue': color['code_value'], 'codeName': color['code_name']}]
        routes[0]['items'] = [{'lineNo': 1, 'inventoryId': material['inventory_id'], 'itemCode': material['inventory_code'],
            'itemName': material['inventory_name'], 'quantity': '1.00000000', 'unit': material['stock_uom'],
            'nodeId': graph['operations'][0]['processId'], 'remark': '仅隔离夹具，不是来源配方变更'}]
        self.api('PUT', f'/boms/{bom["productBomId"]}/routes', routes)
        self.api('POST', f'/boms/{bom["productBomId"]}/submit-review')
        self.api('POST', f'/boms/{bom["productBomId"]}/confirm-current-version')
        return [{'type': 'bom', 'id': bom['productBomId']}, {'type': 'process', 'id': graph['processId']}]
