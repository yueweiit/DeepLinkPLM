import json
import unittest
from unittest.mock import patch
from phone_watch.crawler import parse_html, best_price_from_page, enrich_missing_prices
from phone_watch.pricing import verified_quote, model_matches, xiaomi_store_quote
from phone_watch import db

class VerifiedPricesTest(unittest.TestCase):
    def page(self,name='Samsung Galaxy A17 LTE 128GB',price='3999.00',currency='MXN',**offer):
        return parse_html('<script type="application/ld+json">'+json.dumps({'@type':'Product','name':name,'offers':{'@type':'Offer','price':price,'priceCurrency':currency,**offer}})+'</script>')
    def test_exact_full_price_and_network_specs(self):
        q=verified_quote(self.page(),'https://www.samsung.com/mx/galaxy-a17-lte/','Galaxy A17 4G','SAMSUNG','MX')
        self.assertEqual((q['amount'],q['currency']),(3999.0,'MXN'));self.assertIn('128GB',q['specs'])
    def test_no_page_number_or_description_fallback(self):
        with patch('phone_watch.crawler.fetch_html',return_value='<h1>Galaxy S26 Ultra</h1><p>24 pagos de $395 Pantalla 6.7 128GB</p>'):
            with self.assertRaises(ValueError):best_price_from_page('https://samsung.com/mx/galaxy-s26-ultra/',model='Galaxy S26 Ultra')
    def test_installments_wrong_page_and_missing_currency_rejected(self):
        for parser,url in [(self.page(description='24 mensualidades'),'https://test.mx/galaxy-a17-lte/'),(self.page(),'https://test.mx/galaxy-z-flip8/'),(self.page(currency=''),'https://test.mx/galaxy-a17-lte/')]:
            with self.assertRaises(ValueError):verified_quote(parser,url,'Galaxy A17 4G','SAMSUNG','MX')
    def test_pro_max_ultra_and_network_are_not_merged(self):
        for expected,actual in [('Reno 15','Reno 15 Pro 128GB'),('Redmi Note 15 4G','Redmi Note 15 5G 256GB'),('iPhone 17 Pro','iPhone 17 Pro Max'),('Xiaomi 17','REDMI 17 5G'),('Xiaomi 17','Xiaomi 17T'),('Redmi Note 17','Redmi Note 17S'),('vivo Y600','vivo Y600 Turbo 256GB'),('OPPO K15','OPPO K15 GT 256GB')]:self.assertFalse(model_matches(expected,actual))
        self.assertFalse(model_matches('SM Galaxy A17', 'OPPO A17 128GB'))
        self.assertTrue(model_matches('VO Y04 4G', 'vivo Y04 4G 128GB'))
        self.assertTrue(model_matches('IP 17', 'iPhone 17 256GB'))
        self.assertFalse(model_matches('IP 17', 'iPad 17'))
    def test_incomplete_number_and_aggregate_offers_rejected(self):
        for p in [self.page(price='355,'),self.page(**{'@type':'AggregateOffer'})]:
            with self.assertRaises(ValueError):verified_quote(p,'https://test.mx/galaxy-a17-lte/','Galaxy A17 4G','SAMSUNG','MX')
    def test_plain_dollar_requires_mexico_context(self):
        self.assertEqual(db.parse_price_for_filter('$3,999','MX'),('MXN',3999.0));self.assertEqual(db.parse_price_for_filter('$3,999','GLOBAL'),(None,None));self.assertEqual(db.parse_price_for_filter('USD 399','MX'),('USD',399.0))
    def test_accessory_quote_is_rejected_and_capacity_can_come_from_offer_url(self):
        with self.assertRaises(ValueError):
            verified_quote(self.page(name='Funda Samsung Galaxy A17 LTE 128GB'), 'https://test.mx/galaxy-a17-lte/', 'Galaxy A17 4G', 'SAMSUNG', 'MX')
        quote = verified_quote(self.page(name='Samsung Galaxy A17 LTE', url='/galaxy-a17-lte-128gb/'), 'https://test.mx/galaxy-a17-lte/', 'Galaxy A17 4G', 'SAMSUNG', 'MX')
        self.assertIn('128gb', quote['specs'])
    def test_apple_exact_capacity_full_price_is_read_in_both_currencies(self):
        for country, host, currency, amount in [('CN','www.apple.com.cn','CNY',6799),('MX','www.apple.com','MXN',21999)]:
            url=f'https://{host}/'+('mx/' if country=='MX' else '')+'shop/buy-iphone/iphone-17'
            data={'data':{'currency':currency,'products':[{'name':'iPhone 17 256GB Black','category':'iphone','partNumber':'exact-part','price':{'fullPrice':amount}}]}}
            p=parse_html('<script type="application/json">'+json.dumps(data)+'</script><p>24 monthly payments 199</p>')
            q=verified_quote(p,url,'iPhone 17','APPLE',country)
            self.assertEqual((q['amount'],q['currency'],q['sku']),(amount,currency,'exact-part'));self.assertIn('256GB',q['specs'])
    def test_shared_samsung_purchase_page_preserves_each_exact_variant(self):
        items=[{'@type':'Product','name':name+' 256GB','offers':{'@type':'Offer','price':price,'priceCurrency':'CNY'}} for name,price in [('Galaxy S26',7799),('Galaxy S26+',7999),('Galaxy S26 Ultra',10999)]]
        p=parse_html('<script type="application/ld+json">'+json.dumps({'@type':'ProductGroup','hasVariant':items})+'</script>')
        for model,price in [('Galaxy S26',7799),('Galaxy S26+',7999)]:
            q=verified_quote(p,'https://www.samsung.com.cn/smartphones/galaxy-s26/buy/',model,'SAMSUNG','CN');self.assertEqual(q['amount'],price)
    def test_official_card_starting_price_is_explicit_and_not_an_unrelated_page_number(self):
        p=parse_html('<a href="/shop/buy?product_id=17">Xiaomi 17 4299元起</a><a href="/shop/buy?product_id=18">Xiaomi 17 Pro 4999元起</a><div>补贴 3000元</div>')
        q=verified_quote(p,'https://www.mi.com/shop/search?keyword=17','Xiaomi 17','XIAOMI','CN')
        self.assertEqual((q['amount'],q['currency'],q['kind']),(4299,'CNY','starting'));self.assertIn('product_id=17',q['url'])
        with self.assertRaises(ValueError):verified_quote(p,'https://untrusted.test/shop/search','Xiaomi 17','XIAOMI','CN')
    def test_sold_out_offer_is_labelled_as_listed_reference(self):
        q=verified_quote(self.page(availability='https://schema.org/OutOfStock'),'https://test.mx/galaxy-a17-lte/','Galaxy A17 4G','SAMSUNG','MX');self.assertEqual(q['kind'],'listed')
    def test_wrong_source_can_follow_its_official_exact_phone_purchase_link(self):
        first='<title>Galaxy Z Flip8</title><a href="/mx/smartphones/galaxy-s26/buy/">Comprar Galaxy S26</a>'
        second='<script type="application/ld+json">'+json.dumps({'@type':'Product','name':'Galaxy S26 256GB','offers':{'@type':'Offer','price':'19999','priceCurrency':'MXN'}})+'</script>'
        with patch('phone_watch.crawler.fetch_html',side_effect=[first,second]):
            q=best_price_from_page('https://www.samsung.com/mx/smartphones/galaxy-z-flip8/buy/',model='Galaxy S26',brand='SAMSUNG',country='MX')
        self.assertEqual(q['amount'],19999);self.assertIn('galaxy-s26',q['url'])
    def test_special_edition_and_imaging_kit_do_not_supply_normal_phone_price(self):
        self.assertFalse(model_matches('Redmi Note 17 Pro','Redmi Note 17 Pro 焕新版'))
        with self.assertRaises(ValueError):verified_quote(self.page(name='Xiaomi 17 Ultra 专业影像套装'),'https://www.mi.com/shop/buy?product_id=1','Xiaomi 17 Ultra','XIAOMI','CN')
    def test_xiaomi_public_store_uses_full_exact_goods_price_instead_of_nav_price(self):
        data={'code':200,'data':{'product_info':{'is_enable':True,'name':'Xiaomi 17 Pro'},'goods_list':[{'goods_info':{'name':'Xiaomi 17 Pro 12GB+256GB 黑色','price':'5699','market_price':'5999','commodity_id':123,'is_sale':True,'is_stock':True}},{'goods_info':{'name':'Xiaomi 17 Pro Max 12GB+512GB','price':'6999','is_sale':True,'is_stock':True}}]}}
        q=xiaomi_store_quote(data,'https://www.mi.com/shop/buy?product_id=123','Xiaomi 17 Pro');self.assertEqual((q['amount'],q['currency'],q['kind']),(5699,'CNY','retail'));self.assertIn('12GB+256GB',q['specs'])
        with patch('phone_watch.crawler.fetch_html',return_value=json.dumps(data)) as fetch:
            live=best_price_from_page('https://www.mi.com/shop/buy?product_id=123',model='Xiaomi 17 Pro',country='CN');self.assertEqual(live['amount'],5699);self.assertIn('api2.order.mi.com',fetch.call_args.args[0])
    def test_xiaomi_out_of_stock_stays_reference_and_wrong_products_stay_rejected(self):
        data={'code':200,'data':{'product_info':{'is_enable':True,'name':'Redmi Note 17'},'goods_list':[{'goods_info':{'name':'Redmi Note 17 8GB+128GB','price':'1399','is_sale':True,'is_stock':False}}]}}
        self.assertEqual(xiaomi_store_quote(data,'https://www.mi.com/shop/buy?product_id=17','Redmi Note 17')['kind'],'listed')
        with self.assertRaises(ValueError):xiaomi_store_quote(data,'https://www.mi.com/shop/buy?product_id=17','Xiaomi 17')

    def price_connection(self):
        import sqlite3
        conn=sqlite3.connect(':memory:');conn.row_factory=sqlite3.Row
        for table,region in [('phones','country'),('candidates','source_country')]:
            conn.execute(f'create table {table}(id integer,source_url text,model text,brand text,{region} text,price text,price_evidence text,price_checked_at text,price_error text,deleted_at text)')
        return conn

    def test_native_mexico_source_is_kept_before_trying_a_yuan_catalog_fallback(self):
        conn=self.price_connection()
        conn.execute('insert into phones(id,source_url,model,brand,country) values(1,?,?,?,?)',('https://www.samsung.com/mx/galaxy-a17-lte/','Galaxy A17 4G','SAMSUNG','MX'))
        q={'verified':True,'model':'Galaxy A17 4G','specs':'Galaxy A17 LTE 128GB','currency':'MXN','amount':3999,'url':'https://www.samsung.com/mx/galaxy-a17-lte/','kind':'retail'}
        with patch('phone_watch.crawler.best_price_from_page',return_value=q),patch('phone_watch.store_prices.StorePriceLookup.lookup')as catalogs:
            self.assertEqual(enrich_missing_prices(conn,force=True),1);catalogs.assert_not_called()
        self.assertEqual(json.loads(conn.execute('select price_evidence from phones').fetchone()[0])['currency'],'MXN')

    def test_wrong_edition_cached_quote_is_invalidated_without_losing_original_evidence(self):
        conn=self.price_connection()
        evidence={'verified':True,'amount':4199,'currency':'CNY','specs':'REDMI K90 至尊版 12GB+512GB','capturedAt':'2026-10-15T00:00:00Z'}
        conn.execute('insert into phones(id,source_url,model,brand,country,price,price_evidence) values(1,?,?,?,?,?,?)',('https://www.mi.com/shop/buy?product_id=1','Redmi K90','XIAOMI','CN','CNY 4199',json.dumps(evidence)))
        with patch('phone_watch.store_prices.StorePriceLookup.lookup',side_effect=ValueError('目录未匹配')),patch('phone_watch.crawler.best_price_from_page',side_effect=ValueError('商品不匹配')):self.assertEqual(enrich_missing_prices(conn,force=True),0)
        row=conn.execute('select * from phones').fetchone();cached=json.loads(row['price_evidence']);self.assertFalse(cached['verified']);self.assertEqual(cached['amount'],4199);self.assertEqual(row['price'],'CNY 4199');self.assertIn('不同型号',row['price_error'])
