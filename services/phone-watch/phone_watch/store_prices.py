"""Read-only store discovery. Catalogs locate products; only SKU details supply prices."""
from __future__ import annotations

import json
import re
from collections import OrderedDict
from concurrent.futures import Future
from threading import Lock
from urllib.parse import parse_qs, urlencode, urljoin, urlparse

from .pricing import _quote, model_matches


class _Literal:
    """Decode Nuxt's literal/alias subset without executing downloaded JavaScript."""
    token = re.compile(r'\s*("(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|[A-Za-z_$][\w$]*|[{}\[\]:,])')

    def __init__(self, text, aliases=None):
        self.text, self.pos, self.aliases = text, 0, aliases or {}

    def take(self):
        match = self.token.match(self.text, self.pos)
        if not match:
            raise ValueError('unsupported store data literal')
        self.pos = match.end()
        return match[1]

    def value(self, depth=0):
        if depth > 80:
            raise ValueError('store data nesting limit')
        token = self.take()
        if token in ('{', '['):
            result = {} if token == '{' else []
            end = '}' if token == '{' else ']'
            mark = self.pos
            if self.take() == end:
                return result
            self.pos = mark
            while True:
                if token == '{':
                    key = self.take()
                    key = json.loads(key) if key.startswith('"') else key
                    if self.take() != ':':
                        raise ValueError('store data key separator')
                    result[key] = self.value(depth + 1)
                else:
                    result.append(self.value(depth + 1))
                delimiter = self.take()
                if delimiter == end:
                    return result
                if delimiter != ',':
                    raise ValueError('store data value separator')
        if token.startswith('"') or re.match(r'-?\d', token):
            return json.loads(token)
        if token in ('true', 'false', 'null'):
            return json.loads(token)
        if token == 'void' and self.take() == '0':
            return None
        if token == 'undefined':
            return None
        if token not in self.aliases:
            raise ValueError('unsupported store data expression')
        return self.aliases[token]


def nuxt_data(html):
    match = re.search(r'window\.__NUXT__\s*=\s*\(function\(([^)]*)\)\{(.*?)return (.*?)\}\((.*?)\)\);?\s*</script>', html, re.S)
    if not match:
        return {}
    try:
        args = _Literal('[' + match[4] + ']').value()
        aliases = dict(zip(match[1].split(','), args))
        for statement in filter(None, match[2].split(';')):
            assignment = re.fullmatch(r'([\w$]+)\.([\w$]+)=(.*)', statement)
            if not assignment or not isinstance(aliases.get(assignment[1]), dict):
                return {}
            aliases[assignment[1]][assignment[2]] = _Literal(assignment[3], aliases).value()
        return _Literal(match[3], aliases).value()
    except (ValueError, RecursionError):
        return {}


def flight_data(html):
    """Join React server component chunks before parsing their JSON records."""
    chunks, result = [], []
    for match in re.finditer(r'self\.__next_f\.push\((\[.*?)\)</script>', html, re.S):
        try:
            chunk = json.loads(match[1])
            if chunk[0] == 1 and isinstance(chunk[1], str):
                chunks.append(chunk[1])
        except (ValueError, IndexError):
            pass
    for line in ''.join(chunks).splitlines():
        try:
            result.append(json.loads(line.split(':', 1)[1]))
        except (ValueError, IndexError):
            pass
    return result


def _identity(title):
    # Remove retailer presentation words, not phone variants. Screen descriptions often contain "plus".
    title = re.split(r'\b(?:super\s+amoled|dynamic\s+amoled|amoled|poled|oled|lcd|ips|tft|\d(?:\.\d+)?\s*pulgadas)\b', title, maxsplit=1, flags=re.I)[0]
    return re.sub(r'^(?:(?:celular|smartphone|tel[eé]fono|bundle)\s+)+', '', title, flags=re.I).strip()


def _family_matches(model, title):
    """Discovery can omit a network; final evidence still requires explicit matching."""
    base = re.sub(r'\b[45]G\b', '', model, flags=re.I)
    return model_matches(base, _identity(title))


def oppo_quote(data, url, model):
    item = (data.get('data') or [{}])[0]
    product = item.get('resData') or {}
    if product.get('goodsTopCategoryName') != '手机' or not product.get('goodsSkuId'):
        raise ValueError('OPPO 商城未返回有效手机 SKU')
    # buyPrice includes coupons; priceTag.price is the unconditional full commodity price.
    price = (product.get('priceTag') or {}).get('price')
    button = item.get('mainButton') or {}
    q = _quote(product.get('name', ''), price, 'CNY', url, model,
               'OPPO official SKU resData.name + priceTag.price (excluding conditional coupons)',
               kind='listed' if button.get('disabled') else 'retail', sku=str(product['goodsSkuId']))
    if not q:
        raise ValueError('OPPO 商品型号、版本或完整售价不匹配')
    return {**q, 'source': 'OPPO 中国官方商城', 'country': 'CN'}


def vivo_quote(info, details, url, model):
    if not model_matches(model, (info.get('commoditySpu') or {}).get('spuName', '')):
        raise ValueError('vivo 商品系列不匹配')
    quotes = []
    for sku in details.values():
        if not isinstance(sku, dict) or not sku.get('id'):
            continue
        # discountPrice may require a coupon/subsidy; retain the unconditional salePrice.
        target = url.split('?', 1)[0].replace('/wap/product/', '/product/') + '?' + urlencode({'skuId': sku['id']})
        q = _quote(sku.get('skuName', ''), sku.get('salePrice'), 'CNY', target, model,
                   'vivo official getInfo/getDetail: exact SKU salePrice, excluding coupons',
                   kind='retail' if sku.get('marketable') == 1 else 'listed', sku=str(sku.get('id', '')))
        if q:
            quotes.append({**q, 'source': 'vivo 中国官方商城', 'country': 'CN'})
    if not quotes:
        raise ValueError('vivo 未提供准确 SKU 完整售价')
    return min(quotes, key=lambda q: (q['kind'] != 'retail', q['amount']))


def honor_quote(product, detail, url, model):
    data = product.get('data') or {}
    if product.get('resultCode') != 0 or not model_matches(model, data.get('name') or data.get('briefName') or ''):
        raise ValueError('荣耀商城商品系列不匹配')
    skus = {sku['sbomCode'] for sku in data.get('sbomList', [])}
    quotes = []
    for row in (detail.get('data') or {}).get('detailDispInfos', []):
        sku = row.get('skuPriceInfo') or {}
        if sku.get('sbomCode') not in skus:
            continue  # never use a recommended accessory, gift, or installment
        q = _quote(sku.get('sbomName', ''), sku.get('salePrice'), 'MXN', url, model,
                   'HONOR MX queryPrdDisplayDetailInfo + matching sbomCode salePrice',
                   kind='retail' if sku.get('sbomStatus') == '2' else 'listed', sku=sku.get('sbomCode'))
        if q:
            quotes.append({**q, 'source': '荣耀墨西哥官方商城', 'country': 'MX'})
    if not quotes:
        raise ValueError('荣耀商城未返回准确 SKU 完整售价')
    return min(quotes, key=lambda q: (q['kind'] != 'retail', q['amount']))


def liverpool_quotes(payload, url, model, brand, identity_evidence=None):
    from .crawler import iter_json_objects
    quotes = []
    # The product ID in the URL must agree; recommendations on a PDP are not evidence.
    product_id = urlparse(url).path.rstrip('/').split('/')[-1]
    for product in iter_json_objects(payload):
        if str(product.get('productId')) != product_id or not product.get('variants') or not product.get('productAttributes'):
            continue
        if str(product.get('brand', '')).casefold() != brand.casefold():
            continue
        title = str(product.get('title') or '')
        if re.search(r'reacondicionad|renewed|reconditioned|usado|bundle|\bkit\b', title, re.I):
            continue
        attributes = {d.get('name'): d.get('value', '') for g in product['productAttributes'] for d in g.get('details', [])}
        name = str(attributes.get('Modelo comercial') or _identity(title))
        if not _family_matches(model, name):
            name = _identity(title)
        network = re.findall(r'\b[45]G\b', ' '.join(str(v) for k, v in attributes.items() if k in ('Red', 'Tecnología', 'Red celular', 'Tecnología de red')), re.I)
        identity = name + (' ' + ' '.join(set(network)) if network else '')
        reviewed = identity_evidence or {}
        alias_confirmed = (reviewed.get('reviewed') is True and reviewed.get('brand') == brand.upper()
                           and str(reviewed.get('productId')) == product_id and model_matches(model, str(reviewed.get('model') or ''))
                           and reviewed.get('storeModel', '').casefold() == name.casefold()
                           and _family_matches(model, name) and reviewed.get('reason') and reviewed.get('officialUrl', '').startswith('https://'))
        # A reviewed manufacturer alias is scoped to one store product; explicit contradictory networks never qualify.
        if alias_confirmed and not network and not re.search(r'\b[45]G\b', name + ' ' + title, re.I):
            identity = model
        for sku in product['variants']:
            if not sku.get('inventoryStatus'):
                continue
            capacity = ((sku.get('attributes') or {}).get('Size') or {}).get('name', '')
            if not re.search(r'\d+\s*(?:GB|TB)', capacity, re.I):
                continue  # no parent/range price masquerading as one capacity
            color = ((sku.get('attributes') or {}).get('Color') or {}).get('name', '')
            target = url.split('?', 1)[0] + '?' + urlencode({'skuid': sku['skuId']})
            q = _quote(identity + ' ' + capacity + ' ' + color, (sku.get('priceInfo') or {}).get('salePrice'), 'MXN', target, model,
                       'Liverpool PDP: exact productId, in-stock skuId + capacity salePrice (excluding ranges/promotions)', sku=str(sku['skuId']), page_identity_verified=True)
            if q:
                seller = sku.get('seller') or product.get('seller')
                quote = {**q, 'source': 'Liverpool México', 'country': 'MX', 'seller': seller}
                if alias_confirmed:
                    quote['identityEvidence'] = reviewed
                quotes.append(quote)
    return quotes


def teledata_quote(html, url, model, brand):
    from .crawler import parse_html, decode_json_ld
    parser = parse_html(html)
    parent = re.search(r'LS\.product\s*=\s*\{\s*id\s*:\s*(\d+)', html)
    variant_data = re.search(r'LS\.variants\s*=\s*', html)
    if not parent or not variant_data or not re.search(r'LS\.country\s*=\s*["\x27]MX["\x27]', html):
        raise ValueError('Teledata 未提供可核对的墨西哥商品及 SKU 资料')
    variants = json.JSONDecoder().raw_decode(html[variant_data.end():])[0]
    products = [p for script in parser.scripts for p in decode_json_ld(script) if p.get('@type') == 'Product' and p.get('@id', '').rstrip('/') == url.split('?', 1)[0].rstrip('/')]
    quotes = []
    for product in products:
        if (product.get('brand') or {}).get('name', '').casefold() != brand.casefold():
            continue
        name = str(product.get('name') or '')
        for sku in variants if isinstance(variants, list) else []:
            if str(sku.get('product_id')) != parent[1] or 'MXN' not in str(sku.get('price_long')):
                continue
            options = ' '.join(str(sku.get('option' + str(i)) or '') for i in range(3)).strip()
            capacities = set(re.findall(r'\d+\s*(?:GB|TB)', name.upper()))
            selected = set(re.findall(r'\d+\s*(?:GB|TB)', options.upper()))
            if selected and capacities and not selected.issubset(capacities):
                continue
            # JSON-LD can lag the actual SKU price. Read the same complete amount used by the selector.
            q = _quote(name + ' ' + options, sku.get('price_number'), 'MXN', url, model,
                       'Teledata LS.product ID + exact variant price_number; excludes old JSON-LD, installments and payment discounts',
                       kind='retail' if sku.get('available') and int(sku.get('stock') or 0) > 0 else 'listed', sku=str(sku.get('sku') or sku.get('id') or ''))
            if q:
                quotes.append({**q, 'source': 'Teledata México · Telcel 经销商', 'country': 'MX'})
    if not quotes:
        raise ValueError('Teledata 商品、SKU 或网络版本不一致')
    return min(quotes, key=lambda q: (q['kind'] != 'retail', q['amount']))


class StorePriceLookup:
    """One collection run shares downloads and failures, including between duplicate sources."""
    def __init__(self, timeout=15):
        self.timeout, self.cache, self.lock = timeout, OrderedDict(), Lock()

    def once(self, key, work):
        with self.lock:
            future = self.cache.get(key)
            owner = future is None
            if owner:
                future = self.cache[key] = Future()
            self.cache.move_to_end(key)
        if owner:
            try:
                future.set_result(work())
            except Exception as exc:
                future.set_exception(exc)
            # Product HTML can be megabytes. Keep recent downloads, plus small quote results.
            with self.lock:
                completed = [k for k, f in self.cache.items() if isinstance(k, str) and f.done()]
                for old in completed[:-40]:
                    self.cache.pop(old, None)
        return future.result()

    def html(self, url):
        from .crawler import fetch_html
        headers = {'Accept': 'application/json,text/html,*/*', 'Referer': 'https://' + (urlparse(url).hostname or '') + '/'}
        if urlparse(url).hostname == 'api2.order.mi.com':
            headers.update(Origin='https://www.mi.com', Referer='https://www.mi.com/')
        return self.once(url, lambda: fetch_html(url, timeout=self.timeout, headers=headers))

    def json(self, url):
        return json.loads(self.html(url))

    def liverpool(self, model, brand, identity_evidence=None):
        from .crawler import parse_html, iter_json_objects
        query = 'celular ' + brand
        search = 'https://www.liverpool.com.mx/tienda?' + urlencode({'s': query})
        html = self.html(search)
        payload = flight_data(html)
        ids = {str(o['productId']) for o in iter_json_objects(payload) if o.get('brand', '').casefold() == brand.casefold() and o.get('title') and _family_matches(model, o['title']) and 'priceInfo' in o}
        targets = list(dict.fromkeys(urljoin(search, a.href) for a in parse_html(html).links if '/pdp/' in a.href and any('/' + i in a.href for i in ids)))
        # If the brand catalog's first page lacks this model, search the exact family once.
        if not targets:
            search = 'https://www.liverpool.com.mx/tienda?' + urlencode({'s': re.sub(r'^(?:SM|XM|HR|OP|VO|MT|ZT)\s+', '', model, flags=re.I)})
            html = self.html(search)
            ids = {str(o['productId']) for o in iter_json_objects(flight_data(html)) if o.get('brand', '').casefold() == brand.casefold() and o.get('title') and _family_matches(model, o['title']) and 'priceInfo' in o}
            targets = list(dict.fromkeys(urljoin(search, a.href) for a in parse_html(html).links if '/pdp/' in a.href and any('/' + i in a.href for i in ids)))
        quotes = []
        for url in targets[:3]:
            quotes.extend(liverpool_quotes(flight_data(self.html(url)), url, model, brand, identity_evidence))
        if not quotes:
            raise ValueError('已查墨西哥商品目录，未取得该型号、网络及容量一致的在售 SKU 售价')
        return min(quotes, key=lambda q: q['amount'])

    def oppo(self, model):
        from .crawler import parse_html, iter_json_objects
        catalog = 'https://www.opposhop.cn/cn/web/topic/700100.html'
        html = self.html(catalog)
        skus = {str(p['skuId']) for p in iter_json_objects(nuxt_data(html)) if p.get('skuId') and p.get('title') and _family_matches(model, p['title'])}
        targets = list(dict.fromkeys(urljoin(catalog, a.href.strip()) for a in parse_html(html).links if any('/products/' + i + '.html' in a.href for i in skus)))
        # The official catalog publishes numeric SKU IDs; resolve those via its existing SKU route.
        targets.extend('https://www.opposhop.cn/cn/web/products/' + sku + '.html' for sku in sorted(skus) if not any('/products/' + sku + '.html' in u for u in targets))
        for url in targets[:3]:
            try:
                return oppo_quote(nuxt_data(self.html(url)), url, model)
            except ValueError:
                pass
        raise ValueError('已查 OPPO 中国商城目录，未找到该准确型号和版本的 SKU 售价')

    def teledata(self, model, brand):
        from .crawler import parse_html
        search = 'https://www.teledata.com.mx/search/?' + urlencode({'q': brand})
        html = self.html(search)
        targets = list(dict.fromkeys(urljoin(search, a.href) for a in parse_html(html).links if '/productos/' in a.href and _family_matches(model, a.text.split('$', 1)[0])))
        if not targets:
            search = 'https://www.teledata.com.mx/search/?' + urlencode({'q': re.sub(r'^(?:SM|XM|HR|OP|VO|MT|ZT)\s+', '', model, flags=re.I)})
            html = self.html(search)
            targets = list(dict.fromkeys(urljoin(search, a.href) for a in parse_html(html).links if '/productos/' in a.href and _family_matches(model, a.text.split('$', 1)[0])))
        quotes = []
        for url in targets[:3]:
            if urlparse(url).hostname != 'www.teledata.com.mx':
                continue
            try:
                quotes.append(teledata_quote(self.html(url), url, model, brand))
            except ValueError:
                pass
        if not quotes:
            raise ValueError('已查 Teledata 墨西哥目录，未取得型号、网络一致的完整商品报价')
        return min(quotes, key=lambda q: (q['kind'] != 'retail', q['amount']))

    def xiaomi(self, model):
        from .crawler import iter_json_objects
        from .pricing import xiaomi_store_quote
        query = re.sub(r'^(?:XM|Xiaomi)\s+(?=(?:Redmi|POCO)\b)', '', model, flags=re.I)
        endpoint = 'https://api2.order.mi.com/search/index?' + urlencode({'query': query, 'page_index': 1, 'page_size': 40, 'filter_tag': 0, 'main_sort': 0, 'province_id': '', 'city_id': '', 'callback': '__jp0'})
        # The site's search script returns JSONP, not JSON. Decode only the requested callback's literal argument.
        response = re.fullmatch(r'\s*__jp0\((.*)\);?\s*', self.html(endpoint), re.S)
        if not response:
            raise ValueError('小米搜索未返回有效商品数据')
        payload = json.loads(response[1])
        ids = list(dict.fromkeys(str(p.get('commodity_id') or p.get('product_id')) for p in iter_json_objects((payload.get('data') or {}).get('pc_list')) if p.get('name') and model_matches(model, p['name']) and (p.get('commodity_id') or p.get('product_id'))))
        for product_id in ids[:3]:
            url = 'https://www.mi.com/shop/buy?' + urlencode({'product_id': product_id})
            detail_url = 'https://api2.order.mi.com/product/view?' + urlencode({'product_id': product_id, 'version': 2})
            try:
                quote = xiaomi_store_quote(self.json(detail_url), url, model)
                return {**quote, 'source': '小米中国官方商城', 'country': 'CN', 'evidenceUrl': detail_url, 'discoveryUrl': endpoint}
            except ValueError:
                pass
        raise ValueError('已查小米中国商品目录，未取得准确型号、网络及版本的手机 SKU 报价')

    def honor(self, model, source):
        from .crawler import parse_html
        targets = []
        for catalog in ('https://www.honor.com/mx/shop/', source):
            if urlparse(catalog).hostname != 'www.honor.com':
                continue
            for link in parse_html(self.html(catalog)).links:
                url = urljoin(catalog, link.href)
                if '/mx/phones/' in url and '/buy' in url and (_family_matches(model, link.text) or _family_matches(model, urlparse(url).path.split('/')[-3].replace('-', ' '))):
                    targets.append(url)
        if '/mx/phones/' in source:
            # This buy link must be observed on the page, never generated from a model name.
            targets = [u for u in targets if urlparse(u).hostname == 'www.honor.com']
        base = 'https://selfservice-sg.hihonor.com/ccpcmd/services/dispatch/secured/CCPC/EN/eCommerce/'
        for url in list(dict.fromkeys(targets))[:2]:
            page = self.html(url)
            match = re.search(r'id="productId"[^>]*>\s*(\d+)', page)
            if not match:
                continue
            product = self.json(base + 'queryPrdDisplayDetailInfo/1000?' + urlencode({'productId': match[1], 'siteCode': 'MX', 'loginFrom': 1}))
            codes = [s['sbomCode'] for s in (product.get('data') or {}).get('sbomList', [])]
            if codes:
                detail = self.json(base + 'querySkuDetailDispInfo/1000?' + urlencode({'skuCodes': ','.join(codes), 'groupFlag': 'true', 'siteCode': 'MX', 'loginFrom': 1}))
                return honor_quote(product, detail, url, model)
        raise ValueError('已查荣耀墨西哥商城，未取得准确型号 SKU 报价')

    def honor_cn(self, model):
        from .crawler import parse_html
        from .pricing import verified_quote
        url = 'https://www.honor.com/cn/shop/'
        quote = verified_quote(parse_html(self.html(url)), url, model, 'HONOR', 'CN')
        return {**quote, 'source': '荣耀中国官方商城', 'country': 'CN'}

    def vivo(self, model, source):
        # Product links are published in the portal's structured page data.
        from .crawler import parse_html
        html = self.html(source)
        normalized = re.sub(r'\\u002[fF]', '/', html).replace('\\/', '/')
        urls = [a.href for a in parse_html(html).links if a.href.startswith(('https://shop.vivo.com.cn/product/','https://shop.vivo.com.cn/wap/product/')) and _family_matches(model, a.text)]
        urls += re.findall(r'https://shop\.vivo\.com\.cn/(?:wap/)?product/\d+(?:\?skuId=\d+)?', normalized)
        if urlparse(source).hostname == 'shop.vivo.com.cn':
            urls.insert(0, source)
        for url in list(dict.fromkeys(urls))[:40]:
            spu = urlparse(url).path.rstrip('/').split('/')[-1]
            base = 'https://shop.vivo.com.cn/api/v1/product/'
            info = self.json(base + 'getInfo?' + urlencode({'spuId': spu})).get('data') or {}
            if not model_matches(model, (info.get('commoditySpu') or {}).get('spuName', '')):
                continue
            sku = parse_qs(urlparse(url).query).get('skuId', [info.get('defaultSkuId')])[0]
            detail = self.json(base + 'getDetail?' + urlencode({'spuId': spu, 'typeId': 1, 'skuId': sku, 'needSurfRecord': 'false'})).get('data') or {}
            return vivo_quote(info, detail, url, model)
        raise ValueError('已查 vivo 官方商品链接，未取得准确型号的 SKU 售价')

    def motorola(self, model):
        from .crawler import iter_json_objects
        site = 'https://www.motorola.com/mx/es/'
        html = self.html(site + 'familia-g') + self.html(site)
        codes = sorted(set(re.findall(r'\bPMIPM[A-Z0-9]+\b', html, re.I)))
        if not codes:
            raise ValueError('摩托罗拉目录未返回商品系列 ID')
        base = 'https://api.motorola.com/mx/es/'
        response = self.json(base + 'online/moto/getMotoPictureSwitch?' + urlencode({'subseriesCodes': ','.join(codes)}))
        products = [p for p in iter_json_objects(response.get('data')) if p.get('productCode') and p.get('productType') == 0 and model_matches(model, p.get('productName', '')) and p.get('storage')]
        if not products:
            raise ValueError('摩托罗拉当前目录无准确型号及网络的商品')
        data = self.json(base + 'detail/price/batch/get?' + urlencode({'preSelect': 1, 'mcode': ','.join(p['productCode'] for p in products), 'configId': '', 'enteredCode': ''})).get('data') or {}
        quotes = []
        for product in products:
            price = data.get(product['productCode']) or []
            if len(price) < 5 or price[0] != 0 or price[2] != product['productCode'] or price[3] != 'MXN':
                continue
            name = ' '.join(str(product.get(k) or '') for k in ('productName', 'storage', 'colorName'))
            url = urljoin(site, site + str(product.get('url') or '').lstrip('/'))
            q = _quote(name, price[4], price[3], url, model,
                       'Motorola MX exact productCode + storage + batch full price (empty coupon code)',
                       kind='retail' if product.get('marketingStatus') == 'Available' else 'listed', sku=product['productCode'])
            if q:
                quotes.append({**q, 'source': 'Motorola 墨西哥官方商城', 'country': 'MX'})
        if not quotes:
            raise ValueError('摩托罗拉商品未提供完整原币价格')
        return min(quotes, key=lambda q: (q['kind'] != 'retail', q['amount']))

    def lookup(self, model, brand, source, country, price_evidence=None):
        brand = brand.upper()
        if brand not in {'SAMSUNG', 'XIAOMI', 'OPPO', 'HONOR', 'MOTOROLA', 'VIVO', 'ZTE', 'APPLE'}:
            raise ValueError('该品牌尚无已核实商品目录')
        errors = []
        cached = price_evidence or {}
        try:
            if isinstance(cached, str):
                cached = json.loads(cached)
        except ValueError:
            cached = {}
        proof = cached.get('identityEvidence') if isinstance(cached, dict) and cached.get('verified') else None
        providers = [('MX · Liverpool', lambda: self.liverpool(model, brand, proof) if proof else self.liverpool(model, brand)), ('MX · Teledata', lambda: self.teledata(model, brand))]
        if brand == 'HONOR':
            providers.insert(0, ('MX · HONOR 官方商城', lambda: self.honor(model, source)))
            providers.append(('CN · HONOR 官方商城', lambda: self.honor_cn(model)))
        if brand == 'MOTOROLA':
            providers.insert(0, ('MX · Motorola 官方商城', lambda: self.motorola(model)))
        if brand == 'OPPO':
            providers.append(('CN · OPPO 官方商城', lambda: self.oppo(model)))
        if brand == 'XIAOMI':
            providers.append(('CN · 小米官方商城', lambda: self.xiaomi(model)))
        if brand == 'VIVO' and (urlparse(source).hostname in {'www.vivo.com.cn', 'shop.vivo.com.cn', 'www.iqoo.com'} or country == 'CN' and urlparse(source).hostname == 'www.vivo.com'):
            providers.append(('CN · vivo 官方商城', lambda: self.vivo(model, source)))
        for label, work in providers:
            try:
                quote = self.once(('quote', label, brand, model, source if 'vivo' in label else '', json.dumps(proof, sort_keys=True) if proof else ''), work)
                quote = dict(quote)
                quote['checkedProviders'] = errors + [label + '：已取得准确 SKU 报价']
                return quote
            except Exception as exc:
                errors.append(label + '：' + (str(exc)[:160] or '查询失败'))
        raise ValueError('；'.join(errors))
