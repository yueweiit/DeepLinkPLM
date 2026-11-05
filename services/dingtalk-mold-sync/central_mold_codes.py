"""Opt-in PLM mold reservation adapter; never allocate or substitute a phone code locally."""
from __future__ import annotations
import json
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler, ProxyHandler


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class CentralMoldCodes:
    def __init__(self, endpoint, token_provider, opener=None):
        url = urlsplit(endpoint)
        if (url.username or url.password or url.query or url.fragment or
                url.path != '/api/v1/integrations/dingtalk/mold-code-reservations' or
                not (url.scheme == 'https' or (url.scheme == 'http' and url.hostname in {'127.0.0.1', 'localhost', 'plm-server'}))):
            raise ValueError('Central reservation endpoint must be trusted HTTPS or internal PLM')
        self.endpoint, self.token_provider = endpoint, token_provider
        self.open = opener or build_opener(NoRedirect(), ProxyHandler({})).open

    def reserve(self, request_id, tipo, phone_model, *, intent=None, product_line_id=None,
                phone_model_id=None, hole_type=None, structure_description='', relation_id=None,
                relation_version=None, phone_code_item_id=None):
        body = self._command(request_id, tipo, phone_model, intent, product_line_id, phone_model_id,
                             hole_type, structure_description, relation_id, relation_version, phone_code_item_id)
        token = self.token_provider()
        if not token:
            raise RuntimeError('Central reservation authentication unavailable')
        req = Request(self.endpoint, data=json.dumps(body, ensure_ascii=False).encode(), method='POST',
                      headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
        try:
            with self.open(req, timeout=15) as response:
                result = json.loads(response.read(512000))
            if result.get('code') != 0:
                raise ValueError('PLM did not confirm the reservation')
            data = result['data']
            if data['resolutionStatus'] != intent or data['businessPhone']['phoneModelId'] != phone_model_id:
                raise ValueError('PLM returned another business scope')
            code, molds = data['moldPhoneCode'], data['moldCodes']
            if not isinstance(code, str) or len(code) != 4 or not code.isdigit() or not molds or not data['generatedCodes']:
                raise ValueError('PLM did not return confirmed original mold codes')
            if intent == 'reuse' and (data.get('relationId') != relation_id or data.get('relationVersion') != relation_version):
                raise ValueError('PLM returned another relation version')
            # Legacy phone_code is the M-code segment only; it never authorizes N/SKU generation.
            return {'phone_code': code, 'mold_phone_code': code, 'phone_model': phone_model,
                    'business_phone_code': data['businessPhone'].get('phoneModelCode'),
                    'manufacturing_phone_code': data.get('manufacturingPhoneCode'),
                    'manufacturing_product_id': data.get('manufacturingProductId'),
                    'material_resolution_status': data.get('materialResolutionStatus', 'pending'),
                    'final_full_code': data['generatedCodes'], 'mold_codes': [m['code'] for m in molds],
                    'central': data}
        except Exception:
            raise RuntimeError('Central reservation not confirmed; retry the same request ID, do not allocate locally') from None

    @staticmethod
    def _command(request_id, tipo, model, intent, product, phone, hole, structure, relation, version, phone_code):
        if not isinstance(request_id, str) or not request_id.strip():
            raise ValueError('A stable request ID is required')
        if intent not in {'new', 'reuse'}:
            raise ValueError('Engineering intent must explicitly be new or reuse; pending cannot allocate codes')
        if type(product) is not int or product < 1 or type(phone) is not int or phone < 1:
            raise ValueError('Actual PLM product and business phone IDs are required')
        if hole not in {'large', 'precision', 'other'} or not isinstance(structure, str) or (hole == 'other' and not structure.strip()):
            raise ValueError('An explicit hole type and other-hole description are required')
        if intent == 'reuse' and (type(relation) is not int or relation < 1 or type(version) is not int or version < 1):
            raise ValueError('Reuse requires a confirmed relation ID and version')
        if intent == 'new' and (relation is not None or version is not None):
            raise ValueError('New mold intent cannot carry an existing reuse relation')
        body = {'requestId': request_id, 'tipo': tipo, 'model': model, 'intent': intent,
                'productLineId': product, 'phoneModelId': phone, 'holeType': hole, 'structureDescription': structure}
        if relation is not None:
            body.update(relationId=relation, relationVersion=version)
        if phone_code is not None:
            body['phoneCodeItemId'] = phone_code
        return body
