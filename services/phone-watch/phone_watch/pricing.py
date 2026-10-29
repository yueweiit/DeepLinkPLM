"""Full-price evidence tied to a product identity; never scan arbitrary page numbers."""
from __future__ import annotations
import json
import re
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from urllib.parse import unquote, urljoin, urlparse

from .cleaning import has_non_phone_context


def price_brand(value):
    prefixes = {'SAMSUNG': r'samsung|galaxy|sm|三星', 'APPLE': r'apple|iphone|ip|苹果',
                'XIAOMI': r'xiaomi|redmi|poco|xm|小米|红米', 'OPPO': r'oppo|op',
                'HONOR': r'honor|hr|荣耀', 'MOTOROLA': r'motorola|moto|mt|摩托罗拉',
                'VIVO': r'vivo|iqoo|vo', 'HUAWEI': r'huawei|hw|华为', 'ZTE': r'zte|zt|nubia|redmagic|中兴'}
    return next((brand for brand, pattern in prefixes.items() if re.match(r'^(?:' + pattern + r')(?![a-z])', str(value).strip(), re.I)), None)


def model_matches(expected: str, actual: str) -> bool:
    want_brand, actual_brand = price_brand(expected), price_brand(actual)
    if want_brand and actual_brand and want_brand != actual_brand:
        return False
    for edition in ('焕新版', '青春版', '活力版', '至尊版', '探索版', '竞速版', '专业影像套装'):
        if edition in actual and edition not in expected:
            return False
    def tokens(value):
        value = unquote(value).lower().replace('lte', '4g')
        value = re.sub(r'(\d+\s*(?:gb|tb))\s*\+\s*(?=\d)', r'\1 ', value)
        value = value.replace('+', ' plus ')
        value = re.sub(r'\b(?:samsung|galaxy|sm|motorola|moto|mt|oppo|op|honor|hr|xiaomi|xm|zte|zt|vivo|vo|huawei|hw|apple|iphone|ip)\b', ' ', value)
        return re.findall(r'[a-z]+|\d+', value)
    want, got = tokens(expected), tokens(actual)
    if not want or not got:
        return False
    # Product titles may include colours/capacities; never drop phone variants or explicit network.
    pattern = r'^' + r'[\s_-]*'.join(re.escape(w) for w in want) + r'(?![a-z0-9])'
    normalized = ' '.join(got)
    if not re.search(pattern, normalized):
        return False
    tail = got[len(want):]
    if tail and (len(tail[0]) == 1 and tail[0].isalpha() or tail[0].isdigit() and (len(tail) < 2 or tail[1] not in {'gb', 'tb', 'g'})):
        return False
    variants = {'pro', 'max', 'ultra', 'plus', 'lite', 'fe', 'fusion', 'power', 'fold', 'flip', 'edge', 'turbo', 'gt', 'neo'}
    if (set(got) & variants) != (set(want) & variants):
        return False
    expected_network = set(re.findall(r'\b[45]g\b|\blte\b', expected.lower().replace('-', ' ')))
    actual_network = set(re.findall(r'\b[45]g\b|\blte\b', actual.lower().replace('-', ' ')))
    expected_network = {'4g' if n == 'lte' else n for n in expected_network}
    actual_network = {'4g' if n == 'lte' else n for n in actual_network}
    return not expected_network or expected_network == actual_network


def full_amount(value):
    raw = str(value or '').strip()
    if not re.fullmatch(r'\d+(?:,\d{3})*(?:\.\d{1,2})?', raw):
        return None
    try:
        amount = Decimal(raw.replace(',', ''))
        return float(amount) if amount > 0 else None
    except InvalidOperation:
        return None


def path_model(url: str) -> str:
    for segment in reversed(unquote(urlparse(url).path).split('/')):
        if re.search(r'(?:galaxy|redmi|reno|moto|blade|iphone|xiaomi|poco)', segment, re.I) and re.search(r'\d', segment):
            return segment.replace('-', ' ')
    return ''


def _page_matches(url: str, model: str) -> bool:
    path = path_model(url)
    if not path:
        return True
    if model_matches(model, path):
        return True
    # Samsung explicitly sells S/S+ variants in one ProductGroup purchase page.
    host = urlparse(url).hostname or ''
    if host in {'www.samsung.com', 'www.samsung.com.cn'} and not re.search(r'\b(?:plus|ultra|pro|max|fe)\b', path, re.I):
        base = re.sub(r'\b(?:plus|ultra|pro|max|fe)\b|\+', '', model, flags=re.I)
        return model_matches(base, path)
    return False


def _quote(name, value, currency, url, model, basis, *, kind='retail', availability=None, sku=None, page_identity_verified=False):
    amount = full_amount(value)
    currency = str(currency or '').upper()
    if amount is None or currency not in {'MXN', 'USD', 'CNY', 'KRW', 'EUR', 'GBP'}:
        return None
    if has_non_phone_context(name) or not model_matches(model, name) or (not page_identity_verified and not _page_matches(url, model)):
        return None
    if not url.startswith(('http://', 'https://')):
        return None
    if any(word in name for word in ('专业影像套装', '摄影套装', '影像套装')):
        return None
    spec = re.search(r'\b\d{1,4}\s*(?:GB|TB)\b', name, re.I)
    capacities = re.findall(r'\b\d{1,4}\s*(?:GB|TB)\b', unquote(urlparse(url).path), re.I)
    specs = name if spec else name + ('（' + ' / '.join(capacities) + '，报价页规格）' if capacities else '（容量未注明）')
    if availability and any(x in str(availability).lower() for x in ('outofstock', 'discontinued', 'soldout')):
        kind = 'listed'
    return {'verified': True, 'amount': amount, 'currency': currency, 'specs': specs,
            'url': url, 'model': model, 'kind': kind, 'basis': basis,
            'availability': availability, 'sku': sku,
            'capturedAt': datetime.now(timezone.utc).isoformat(timespec='seconds')}


def _official_catalog_quotes(parser, url, model, country):
    """A single official product card can supply an explicitly labelled starting/list price."""
    host = urlparse(url).hostname or ''
    chinese = country == 'CN' and host in {'www.mi.com', 'www.oppo.com', 'www.opposhop.cn', 'www.vivo.com.cn', 'shop.vivo.com.cn', 'www.honor.com', 'www.vmall.com'}
    if not chinese:
        return []
    quotes = []
    for link in parser.links:
        name = (link.text + ' ' + link.title).strip()
        match = re.search(r'(?:[¥￥]|RMB\s*|CNY\s*)(\d[\d,]*(?:\.\d{1,2})?)|(\d[\d,]*(?:\.\d{1,2})?)\s*元', name, re.I)
        if not match:
            continue
        target = urljoin(url, link.href)
        if urlparse(target).hostname != host:
            continue
        price_name = name[:match.start()].strip()
        q = _quote(price_name, match.group(1) or match.group(2), 'CNY', target, model,
                   'Official product card: exact model + explicit RMB amount',
                   kind='starting' if '起' in name else 'listed')
        if q:
            quotes.append(q)
    return quotes


def xiaomi_store_quote(payload, url, model):
    """The CN store's public product/view response, also used by its purchase page."""
    if not isinstance(payload, dict) or payload.get('code') != 200 or not isinstance(payload.get('data'), dict):
        raise ValueError('小米商城未返回有效在售商品资料')
    data = payload['data']
    family = str((data.get('product_info') or {}).get('name') or '')
    chinese = lambda value: re.findall(r'[\u4e00-\u9fff]+', re.sub(r'^(?:小米|红米)\s*', '', value))
    if not model_matches(model, family) or chinese(model) != chinese(family):
        raise ValueError('小米商城商品系列与来源型号不一致：' + family)
    quotes = []
    for row in data.get('goods_list') or []:
        goods = row.get('goods_info') or {}
        enabled = (data.get('product_info') or {}).get('is_enable')
        selling = enabled and goods.get('is_sale') and goods.get('is_stock')
        q = _quote(str(goods.get('name') or ''), goods.get('price') or goods.get('market_price'),
                   'CNY', url, model, 'Xiaomi CN product/view: exact goods_info + yuan full price',
                   kind='retail' if selling else 'listed', sku=str(goods.get('commodity_id') or goods.get('sku') or ''))
        if q:
            quotes.append(q)
    if not quotes:
        raise ValueError('小米商城没有该准确型号和规格的有效商品价格')
    return min(quotes, key=lambda q: (q['kind'] != 'retail', q['amount'], q['specs']))


def verified_quote(parser, url: str, model: str, brand: str, country: str):
    from .crawler import decode_json_ld
    if any(text in parser.title.lower() for text in ('verifica tu identidad', 'access denied', 'captcha')):
        raise ValueError('来源要求验证身份，自动采集不可用；已保留上次有效报价及原始值')
    quotes = []
    for script in parser.scripts:
        for product in decode_json_ld(script):
            types = product.get('@type', [])
            if isinstance(types, str):
                types = [types]
            if not {'product', 'mobilephone', 'phone'} & {str(t).lower() for t in types}:
                continue
            name = str(product.get('name') or product.get('model') or '')
            declared = product.get('brand') or ''
            if isinstance(declared, dict):
                declared = declared.get('name', '')
            actual_brand = price_brand(declared) or price_brand(name)
            if price_brand(brand) and actual_brand and price_brand(brand) != actual_brand:
                continue
            product_url = urljoin(url, str(product.get('url') or url))
            if not _page_matches(url, model):
                continue
            offers = product.get('offers', [])
            if isinstance(offers, dict):
                offers = [offers]
            for offer in offers:
                if not isinstance(offer, dict) or 'price' not in offer:
                    continue
                # lowPrice/AggregateOffer can span capacities and is not an exact-spec quote.
                if str(offer.get('@type', '')).lower() == 'aggregateoffer':
                    continue
                text = json.dumps(offer, ensure_ascii=False).lower()
                if any(word in text for word in ('installment', 'monthly', 'mensual', 'quincenal', 'por mes', 'paymentplan')):
                    continue
                offer_url = urljoin(product_url, str(offer.get('url') or product_url))
                q = _quote(name, offer.get('price'), offer.get('priceCurrency'), offer_url, model,
                           'Product/Offer + exact model/network', availability=offer.get('availability'), sku=product.get('sku'))
                if q:
                    quotes.append(q)
        # Apple publishes exact part/capacity full prices in its own buy-flow metrics JSON.
        if (urlparse(url).hostname or '') in {'www.apple.com', 'www.apple.com.cn'} and '/shop/buy-iphone/' in urlparse(url).path:
            try:
                data = json.loads(script).get('data', {})
            except (ValueError, AttributeError):
                data = {}
            for product in data.get('products', []) if isinstance(data, dict) else []:
                if not isinstance(product, dict) or product.get('category') != 'iphone':
                    continue
                q = _quote(str(product.get('name') or ''), (product.get('price') or {}).get('fullPrice'),
                           data.get('currency'), url, model, 'Apple buy-flow: exact part/capacity fullPrice', sku=product.get('partNumber'))
                if q:
                    quotes.append(q)
    if not quotes:
        amount = full_amount(parser.meta.get('product:price:amount') or parser.meta.get('og:price:amount'))
        currency = str(parser.meta.get('product:price:currency') or parser.meta.get('og:price:currency') or '').upper()
        title = parser.meta.get('og:title') or parser.title
        q = _quote(title, amount, currency, url, model, 'product price meta + exact title/network/path')
        if q:
            quotes.append(q)
    if not quotes:
        quotes = _official_catalog_quotes(parser, url, model, country)
    if not quotes:
        raise ValueError('未找到与型号、网络和规格对应的完整商品报价（可能为分期、错链或动态报价）')
    return min(quotes, key=lambda q: (q['kind'] != 'retail', q['amount'], q['specs']))
