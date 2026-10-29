"""Opt-in central reservation seam; no SQL allocation or automatic fallback is allowed."""
from __future__ import annotations
import json
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler, ProxyHandler

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):return None

class CentralMoldCodes:
    def __init__(self,endpoint,token_provider,opener=None):
        url=urlsplit(endpoint)
        if (url.username or url.password or url.query or url.fragment or
            url.path!='/api/v1/integrations/dingtalk/mold-code-reservations' or
            not (url.scheme=='https' or (url.scheme=='http' and url.hostname in {'127.0.0.1','localhost','plm-server'}))):
            raise ValueError('Central reservation endpoint must be trusted HTTPS or internal PLM')
        self.endpoint,self.token_provider=endpoint,token_provider
        self.open=opener or build_opener(NoRedirect(),ProxyHandler({})).open

    def reserve(self,request_id,tipo,phone_model):
        token=self.token_provider()
        if not token:raise RuntimeError('Central reservation authentication unavailable')
        body={'requestId':request_id,'tipo':tipo,'model':phone_model}
        req=Request(self.endpoint,data=json.dumps(body,ensure_ascii=False).encode(),method='POST',headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
        try:
            with self.open(req,timeout=15) as response:result=json.loads(response.read(512000))
            if result.get('code')!=0:raise RuntimeError()
            data=result['data'];code=data['phoneModelCode'];molds=data['moldCodes']
            if not isinstance(code,str) or len(code)!=4 or not code.isdigit() or not molds:raise RuntimeError()
            # Existing connector names plus the exact central response, for a guarded seam patch.
            return {'phone_code':code,'phone_model':phone_model,'final_full_code':data['generatedCodes'],'mold_codes':[m['code'] for m in molds],'central':data}
        except Exception:
            # A failure must never fall back to the old SQL allocator: retry the SAME request ID.
            raise RuntimeError('Central reservation not confirmed; retry the same request ID, do not allocate locally') from None
