import io,json,importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('central',Path(__file__).resolve().parents[1]/'central_mold_codes.py');central=importlib.util.module_from_spec(spec);spec.loader.exec_module(central)
class CentralCodeTests(unittest.TestCase):
    def test_legacy_shape_retains_central_codes_and_stable_request_id(self):
        seen=[]
        def open(req,timeout):
            seen.append(json.loads(req.data));return io.BytesIO(json.dumps({'code':0,'data':{'phoneModelCode':'1294','generatedCodes':'MFA101294 / MFA201294','moldCodes':[{'code':'MFA101294'},{'code':'MFA201294'}]}}).encode())
        client=central.CentralMoldCodes('https://plm.example/api/v1/integrations/dingtalk/mold-code-reservations',lambda:'test-token',open)
        r=client.reserve('same-id-12345','幻影Fantasía Case','OP RENO 16F 5G');self.assertEqual(r['phone_code'],'1294');self.assertEqual(r['mold_codes'],['MFA101294','MFA201294']);self.assertEqual(seen[0]['requestId'],'same-id-12345')
    def test_errors_never_fall_back_to_sql_allocation(self):
        def fail(*args,**kwargs):raise OSError('must not disclose transport detail')
        client=central.CentralMoldCodes('https://plm.example/api/v1/integrations/dingtalk/mold-code-reservations',lambda:'test',fail)
        with self.assertRaisesRegex(RuntimeError,'same request ID'):client.reserve('stable-id','Tipo','Phone')
    def test_untrusted_plain_http_and_credentials_in_urls_are_rejected(self):
        for url in ['http://example.com/api/v1/integrations/dingtalk/mold-code-reservations','https://user:secret@example.com/api/v1/integrations/dingtalk/mold-code-reservations','https://example.com/api/v1/integrations/dingtalk/mold-code-reservations?token=secret']:
            with self.assertRaises(ValueError):central.CentralMoldCodes(url,lambda:'test')
if __name__=='__main__':unittest.main()
