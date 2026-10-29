import json
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

from phone_watch.store_prices import StorePriceLookup, flight_data, honor_quote, liverpool_quotes, nuxt_data, oppo_quote, vivo_quote


class StorePricesTest(unittest.TestCase):
    def test_nuxt_literals_and_shared_button_data_are_read_without_running_javascript(self):
        html = '<script>window.__NUXT__=(function(a,b,c){c.disabled=b;return {data:[{name:a,mainButton:c}]}}("OPPO Find N6",false,{}));</script>'
        self.assertEqual(nuxt_data(html)['data'][0], {'name': 'OPPO Find N6', 'mainButton': {'disabled': False}})
        self.assertEqual(nuxt_data('<script>window.__NUXT__=(function(a){process.exit();return a}({}));</script>'), {})

    def test_react_chunks_are_joined_before_decoding_sku_data(self):
        text = '11:' + json.dumps({'productId': '22', 'title': 'Galaxy A37'}) + '\n'
        html = ''.join('<script>self.__next_f.push(' + json.dumps([1, part]) + ')</script>' for part in (text[:19], text[19:]))
        self.assertEqual(flight_data(html), [{'productId': '22', 'title': 'Galaxy A37'}])

    def oppo(self, **changes):
        product = {'goodsTopCategoryName': '手机', 'goodsSkuId': 39272, 'name': 'OPPO Find N6 16GB+512GB 金橙', 'priceTag': {'price': '10999', 'buyPrice': '10989'}, 'price': 10989, **changes}
        return {'data': [{'resData': product, 'mainButton': {'disabled': False}}]}

    def test_oppo_reads_the_exact_phone_price_excluding_coupon_amounts_and_accessories(self):
        q = oppo_quote(self.oppo(), 'https://www.opposhop.cn/cn/web/products/39272.html', 'OPPO Find N6')
        self.assertEqual((q['amount'], q['currency'], q['sku']), (10999, 'CNY', '39272'))
        for changes in ({'goodsTopCategoryName': '配件'}, {'name': 'OPPO Find N6 Ultra 512GB'}, {'priceTag': {'buyPrice': 500}}):
            with self.assertRaises(ValueError):
                oppo_quote(self.oppo(**changes), q['url'], 'OPPO Find N6')

    def test_unavailable_oppo_sku_is_only_a_listed_reference(self):
        data = self.oppo(); data['data'][0]['mainButton']['disabled'] = True
        self.assertEqual(oppo_quote(data, 'https://www.opposhop.cn/cn/web/products/39272.html', 'OPPO Find N6')['kind'], 'listed')

    def test_vivo_uses_commodity_sku_price_not_coupon_or_installment(self):
        info = {'commoditySpu': {'spuName': 'vivo Y6'}}
        detail = {'143778': {'id': 143778, 'skuName': 'vivo Y6 12GB+256GB 凤迎金', 'salePrice': 2699, 'discountPrice': 2299, 'marketable': 1, 'installment': {'perPay': 100}}}
        q = vivo_quote(info, detail, 'https://shop.vivo.com.cn/product/10011947?skuId=143778', 'vivo Y6')
        self.assertEqual((q['amount'], q['currency'], q['sku']), (2699, 'CNY', '143778'))
        self.assertIn('skuId=143778', q['url'])
        with self.assertRaises(ValueError):
            vivo_quote({'commoditySpu': {'spuName': 'vivo Y600 Turbo'}}, detail, q['url'], 'vivo Y600')

    def test_honor_joins_exact_sbom_codes_and_ignores_cheaper_recommendations(self):
        product = {'resultCode': 0, 'data': {'name': 'HONOR X7d', 'sbomList': [{'sbomCode': 'phone'}]}}
        detail = {'data': {'detailDispInfos': [{'skuPriceInfo': {'sbomCode': code, 'sbomName': name, 'salePrice': amount, 'unitPrice': 7999, 'sbomStatus': '2'}} for code, name, amount in [('gift', 'HONOR X7d case', 49), ('phone', 'HONOR X7d 8GB+256GB', 5999)]]}}
        q = honor_quote(product, detail, 'https://www.honor.com/mx/phones/honor-x7d/buy/', 'HONOR X7d')
        self.assertEqual((q['amount'], q['currency'], q['sku']), (5999, 'MXN', 'phone'))

    def liverpool(self):
        return [{'productId': '22', 'brand': 'SAMSUNG', 'title': 'Galaxy A37 Super AMOLED 6.7 pulgadas', 'productAttributes': [{'details': [{'name': 'Modelo comercial', 'value': 'Galaxy A37'}, {'name': 'Red', 'value': '5G'}]}], 'priceInfo': {'promoPrice': {'minPrice': 200}}, 'variants': [{'skuId': '23', 'inventoryStatus': True, 'attributes': {'Size': {'name': '128 GB'}, 'Color': {'name': 'Negro'}}, 'priceInfo': {'salePrice': 6499, 'promoPrice': {'price': 5524.15}}}]}]

    def test_liverpool_uses_in_stock_capacity_price_not_parent_range_or_conditional_promo(self):
        q = liverpool_quotes(self.liverpool(), 'https://www.liverpool.com.mx/tienda/pdp/celular-galaxy-a37-super-amoled-plus/22', 'Galaxy A37 5G', 'SAMSUNG')[0]
        self.assertEqual((q['amount'], q['currency'], q['sku']), (6499, 'MXN', '23'))
        self.assertIn('128 GB', q['specs']); self.assertIn('skuid=23', q['url'])

    def test_xiaomi_jsonp_discovers_real_product_ids_then_verifies_the_full_sku(self):
        lookup = StorePriceLookup()
        search = {'code': 200, 'data': {'pc_list': [{'commodity_list': [{'name': 'Xiaomi 17 Ultra 12GB+512GB', 'commodity_id': 5810}, {'name': 'Xiaomi 17 Ultra 专业影像套装', 'commodity_id': 5853}]}]}}
        product = {'code': 200, 'data': {'product_info': {'name': 'Xiaomi 17 Ultra', 'is_enable': True}, 'goods_list': [{'goods_info': {'commodity_id': 5810, 'name': 'Xiaomi 17 Ultra 12GB+512GB 冷烟紫', 'price': '7999', 'is_sale': True, 'is_stock': True}}]}}
        with patch.object(lookup, 'html', return_value='__jp0(' + json.dumps(search) + ');'), patch.object(lookup, 'json', return_value=product) as detail:
            q = lookup.xiaomi('Xiaomi 17 Ultra')
            self.assertEqual((q['amount'], q['currency']), (7999, 'CNY')); self.assertIn('product_id=5810', q['url'])
            self.assertEqual(detail.call_count, 1)
        with patch.object(lookup, 'html', return_value='evil(' + json.dumps(search) + ');'):
            with self.assertRaises(ValueError): lookup.xiaomi('Xiaomi 17 Ultra')

    def test_liverpool_rejects_missing_network_brand_mismatch_recommendations_and_used_goods(self):
        url = 'https://www.liverpool.com.mx/tienda/pdp/phone/22'
        for field, value in [('productId', 'different'), ('brand', 'OPPO'), ('title', 'Galaxy A37 Reacondicionado'), ('productAttributes', [{'details': [{'name': 'Modelo comercial', 'value': 'Galaxy A37'}]}])]:
            data = self.liverpool(); data[0][field] = value
            self.assertEqual(liverpool_quotes(data, url, 'Galaxy A37 5G', 'SAMSUNG'), [])

    def test_reviewed_manufacturer_alias_is_reused_only_for_its_store_product(self):
        data = self.liverpool(); data[0]['productAttributes'][0]['details'] = [{'name':'Modelo comercial','value':'Galaxy A37'}]
        proof = {'reviewed':True,'brand':'SAMSUNG','productId':'22','model':'Galaxy A37 5G','storeModel':'Galaxy A37','reason':'厂家发布资料同文简称，已核对商品身份','officialUrl':'https://news.samsung.com/mx/official'}
        url='https://www.liverpool.com.mx/tienda/pdp/phone/22'
        self.assertEqual(liverpool_quotes(data,url,'Galaxy A37 5G','SAMSUNG'),[])
        quote=liverpool_quotes(data,url,'Galaxy A37 5G','SAMSUNG',proof)[0]
        self.assertIn('5G',quote['specs']);self.assertEqual(quote['identityEvidence'],proof)
        lookup=StorePriceLookup()
        catalog={'productId':'22','brand':'SAMSUNG','title':'Galaxy A37','priceInfo':{'salePrice':6499}}
        render=lambda obj:'<script>self.__next_f.push('+json.dumps([1,'0:'+json.dumps(obj)+'\n'])+')</script>'
        search=render(catalog)+'<a href="/tienda/pdp/phone/22">Galaxy A37</a>'
        with patch.object(lookup,'html',side_effect=lambda u:search if '?s=' in u else render(data)):
            refreshed=lookup.lookup('Galaxy A37 5G','SAMSUNG','https://www.samsung.com/mx/','MX',{'verified':True,'identityEvidence':proof})
            self.assertEqual(refreshed['identityEvidence'],proof);self.assertEqual(refreshed['amount'],6499)
        self.assertEqual(liverpool_quotes(data,url,'Galaxy A37 5G','SAMSUNG',{**proof,'productId':'other'}),[])
        data[0]['productAttributes'][0]['details'].append({'name':'Red','value':'4G'})
        self.assertEqual(liverpool_quotes(data,url,'Galaxy A37 5G','SAMSUNG',proof),[])
        for field, value in [('inventoryStatus', False), ('attributes', {})]:
            data = self.liverpool(); data[0]['variants'][0][field] = value
            self.assertEqual(liverpool_quotes(data, url, 'Galaxy A37 5G', 'SAMSUNG'), [])

    def test_second_mexico_store_reuses_exact_offer_validation_and_keeps_sold_out_quotes_as_references(self):
        lookup = StorePriceLookup()
        search = '<a href="https://www.teledata.com.mx/productos/zte-blade-l130/">ZTE Blade L130 8GB $810 $499</a>'
        product = '<script type="application/ld+json">' + json.dumps({'@type':'Product','@id':'https://www.teledata.com.mx/productos/zte-blade-l130/','brand':{'name':'ZTE'},'name':'ZTE Blade L130 8GB','sku':'phone','offers':{'@type':'Offer','price':810,'priceCurrency':'MXN','availability':'https://schema.org/OutOfStock'}}) + '</script><script>LS.country="MX";LS.product={id:1};LS.variants='+json.dumps([{'product_id':1,'price_number':499,'price_long':'$499 MXN','sku':'phone','available':False,'stock':0}])+';</script>'
        with patch.object(lookup, 'html', side_effect=[search, product]):
            q = lookup.teledata('ZT Blade L130', 'ZTE')
            self.assertEqual((q['amount'], q['currency'], q['kind']), (499, 'MXN', 'listed'))

    def test_price_save_preserves_a_verified_mxn_quote_and_checks_for_concurrent_changes(self):
        import sqlite3
        from phone_watch.crawler import save_price_result
        conn=sqlite3.connect(':memory:');conn.row_factory=sqlite3.Row
        conn.execute('create table phones(id integer,model text,price text,price_evidence text,price_checked_at text,price_error text,deleted_at text)')
        mx={'verified':True,'model':'OPPO A6','specs':'OPPO A6 8GB+256GB','currency':'MXN','amount':5999,'capturedAt':'2026-10-01T00:00:00Z'}
        cn={**mx,'currency':'CNY','amount':1899,'capturedAt':'2026-10-15T00:00:00Z'}
        conn.execute('insert into phones(id,model,price,price_evidence) values(1,?,?,?)',('OPPO A6','MXN 5999',json.dumps(mx)))
        row={**dict(conn.execute('select * from phones').fetchone()),'table_name':'phones'}
        self.assertEqual(save_price_result(conn,row,cn),1)
        saved=json.loads(conn.execute('select price_evidence from phones').fetchone()[0]);self.assertEqual(saved['currency'],'MXN');self.assertEqual(saved['capturedAt'],mx['capturedAt']);self.assertEqual(saved['alternativeQuote']['currency'],'CNY')
        self.assertEqual(save_price_result(conn,row,cn),0)
        with self.assertRaises(ValueError):save_price_result(conn,row,{**cn,'specs':'OPPO A6 Pro 256GB'})

    def test_discovery_shares_success_and_failure_downloads_across_source_rows(self):
        lookup = StorePriceLookup()
        with patch('phone_watch.crawler.fetch_html', return_value='one') as fetch, ThreadPoolExecutor(max_workers=6) as pool:
            self.assertEqual(list(pool.map(lookup.html, ['https://shop.test/p'] * 8)), ['one'] * 8)
            self.assertEqual(fetch.call_count, 1)
        with patch('phone_watch.crawler.fetch_html', side_effect=ValueError('HTTP 503')) as fetch:
            for _ in range(2):
                with self.assertRaises(ValueError): lookup.html('https://shop.test/failed')
            self.assertEqual(fetch.call_count, 1)

    def test_mexico_is_checked_first_and_yuan_fallback_keeps_provider_and_country(self):
        lookup = StorePriceLookup()
        with patch.object(lookup, 'liverpool', return_value={'amount': 5999, 'currency': 'MXN'}) as mx, patch.object(lookup, 'oppo') as cn:
            self.assertEqual(lookup.lookup('OPPO A6', 'OPPO', 'https://www.oppo.com/cn/a6', 'CN')['currency'], 'MXN')
            mx.assert_called_once(); cn.assert_not_called()
        lookup = StorePriceLookup()
        with patch.object(lookup, 'liverpool', side_effect=ValueError('HTTP 503')), patch.object(lookup, 'teledata', side_effect=ValueError('未取得有效报价')), patch.object(lookup, 'oppo', return_value={'amount': 1899, 'currency': 'CNY', 'country': 'CN', 'source': 'OPPO 中国官方商城'}):
            q = lookup.lookup('OPPO A6', 'OPPO', '', 'MX')
            self.assertEqual((q['currency'], q['source'], q['country']), ('CNY', 'OPPO 中国官方商城', 'CN'))
            self.assertIn('HTTP 503', q['checkedProviders'][0])

    def test_same_bare_model_from_different_brands_does_not_share_a_quote(self):
        lookup = StorePriceLookup()
        with patch.object(lookup, 'liverpool', side_effect=lambda model, brand: {'amount': 100 if brand == 'SAMSUNG' else 200, 'currency': 'MXN'}):
            self.assertEqual(lookup.lookup('A17', 'SAMSUNG', '', 'MX')['amount'], 100)
            self.assertEqual(lookup.lookup('A17', 'OPPO', '', 'MX')['amount'], 200)

    def test_china_vivo_dot_com_and_escaped_links_find_the_exact_model_after_other_nav_products(self):
        lookup = StorePriceLookup()
        html = '<script>' + json.dumps(['https://shop.vivo.com.cn/product/1', 'https://shop.vivo.com.cn/wap/product/2']).replace('/', '\\u002f') + '</script>'
        wrong = {'data': {'commoditySpu': {'spuName': 'vivo X500 Pro'}, 'defaultSkuId': 11}}
        exact = {'data': {'commoditySpu': {'spuName': 'vivo X500'}, 'defaultSkuId': 22}}
        detail = {'data': {'22': {'id': 22, 'skuName': 'vivo X500 12GB+256GB', 'salePrice': 5499, 'marketable': 1}}}
        with patch.object(lookup, 'liverpool', side_effect=ValueError('未取得 MXN')), patch.object(lookup, 'teledata', side_effect=ValueError('未取得 MXN')), patch.object(lookup, 'html', return_value=html), patch.object(lookup, 'json', side_effect=[wrong, exact, detail]):
            quote = lookup.lookup('vivo X500', 'VIVO', 'https://www.vivo.com/vivo/x500/', 'CN')
            self.assertEqual((quote['currency'], quote['amount']), ('CNY', 5499))
            self.assertIn('/product/2', quote['url'])
