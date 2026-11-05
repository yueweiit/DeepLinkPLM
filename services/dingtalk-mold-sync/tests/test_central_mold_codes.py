import io
import json
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('central', Path(__file__).resolve().parents[1] / 'central_mold_codes.py')
central = importlib.util.module_from_spec(spec)
spec.loader.exec_module(central)


class CentralCodeTests(unittest.TestCase):
    def client(self, response, seen=None):
        def open(request, timeout):
            if seen is not None:
                seen.append(json.loads(request.data))
            return io.BytesIO(json.dumps({'code': 0, 'data': response}).encode())
        return central.CentralMoldCodes('https://plm.example/api/v1/integrations/dingtalk/mold-code-reservations', lambda: 'test-token', open)

    def response(self, status='reuse'):
        return {'resolutionStatus': status, 'businessPhone': {'phoneModelId': 18, 'phoneModelCode': '1218'},
                'moldPhoneCode': '1145', 'manufacturingPhoneCode': '1145', 'manufacturingProductId': 100,
                'materialResolutionStatus': 'ready', 'relationId': 7, 'relationVersion': 3,
                'generatedCodes': 'MBA101145 / MBA201145',
                'moldCodes': [{'code': 'MBA101145'}, {'code': 'MBA201145'}]}

    def reserve(self, client, **changes):
        args = dict(intent='reuse', product_line_id=55, phone_model_id=18, hole_type='large', relation_id=7, relation_version=3)
        args.update(changes)
        return client.reserve('same-id-12345', '亮甲', 'Apple iPhone 18 Pro Max', **args)

    def test_legacy_output_retains_original_mold_segment_and_stable_request_without_rewriting_business_phone(self):
        seen = []
        result = self.reserve(self.client(self.response(), seen))
        self.assertEqual(result['phone_code'], '1145')
        self.assertEqual(result['business_phone_code'], '1218')
        self.assertEqual(result['mold_codes'], ['MBA101145', 'MBA201145'])
        self.assertEqual(seen[0], {'requestId': 'same-id-12345', 'tipo': '亮甲', 'model': 'Apple iPhone 18 Pro Max',
                                  'intent': 'reuse', 'productLineId': 55, 'phoneModelId': 18, 'holeType': 'large',
                                  'structureDescription': '', 'relationId': 7, 'relationVersion': 3})

    def test_errors_never_fall_back_to_sql_allocation(self):
        def fail(*args, **kwargs):
            raise OSError('transport detail must not be exposed')
        client = central.CentralMoldCodes('https://plm.example/api/v1/integrations/dingtalk/mold-code-reservations', lambda: 'test', fail)
        with self.assertRaisesRegex(RuntimeError, 'same request ID'):
            self.reserve(client)

    def test_untrusted_plain_http_and_credentials_in_urls_are_rejected(self):
        for url in ['http://example.com/api/v1/integrations/dingtalk/mold-code-reservations',
                    'https://user:secret@example.com/api/v1/integrations/dingtalk/mold-code-reservations',
                    'https://example.com/api/v1/integrations/dingtalk/mold-code-reservations?token=secret']:
            with self.assertRaises(ValueError):
                central.CentralMoldCodes(url, lambda: 'test')

    def test_unknown_or_old_unqualified_requests_stop_before_transport(self):
        seen = []
        client = self.client(self.response(), seen)
        with self.assertRaisesRegex(ValueError, 'explicitly'):
            client.reserve('old-id', '亮甲', 'iPhone 18 Pro Max')
        with self.assertRaisesRegex(ValueError, 'explicitly'):
            self.reserve(client, intent='pending')
        self.assertEqual(seen, [])

    def test_mold_only_result_never_pretends_to_authorize_a_manufacturing_material(self):
        response = self.response()
        response.update(manufacturingPhoneCode=None, manufacturingProductId=None, materialResolutionStatus='pending')
        result = self.reserve(self.client(response))
        self.assertEqual(result['mold_phone_code'], '1145')
        self.assertIsNone(result['manufacturing_phone_code'])
        self.assertEqual(result['material_resolution_status'], 'pending')

    def test_scope_or_relation_version_change_does_not_silently_accept_other_codes(self):
        response = self.response()
        response['relationVersion'] = 4
        with self.assertRaisesRegex(RuntimeError, 'same request ID'):
            self.reserve(self.client(response))
        response = self.response()
        response['businessPhone']['phoneModelId'] = 17
        with self.assertRaises(RuntimeError):
            self.reserve(self.client(response))

    def test_explicit_new_precision_request_has_no_reuse_relation(self):
        seen = []
        response = self.response('new')
        response.update(moldPhoneCode='1218', manufacturingPhoneCode='1218', relationId=None, relationVersion=None)
        result = self.reserve(self.client(response, seen), intent='new', hole_type='precision', relation_id=None, relation_version=None)
        self.assertEqual(result['phone_code'], '1218')
        self.assertNotIn('relationId', seen[0])


if __name__ == '__main__':
    unittest.main()
