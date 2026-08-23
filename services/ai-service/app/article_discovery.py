"""Bounded article-link discovery built on the existing web-ingest safety gates."""

from __future__ import annotations

import asyncio
import hashlib
import io
import json
import re
import random
import socket
import ssl
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime, timezone
from html.parser import HTMLParser
from urllib.parse import urljoin, urlparse, urlunparse

import httpx

from app.web_ingest import USER_AGENT, _assert_public_host, _client, _rate_limit, _robots

MAX_DISCOVERY_BYTES = 1024 * 1024
MAX_XML_NODES = 10_000
MAX_XML_DEPTH = 32
MAX_CANDIDATES = 100
MAX_SITEMAP_CHILDREN = 20
MAX_SECTION_LINKS = 200
MAX_REDIRECTS = 5
RETRY_DELAY_RANGES = ((2.0, 5.0), (10.0, 20.0), (30.0, 60.0))
ARTICLE_TYPES = {"Article", "NewsArticle", "ReportageNewsArticle", "BlogPosting"}
ARTICLE_PATH_PATTERN = re.compile(
    r"(?:^|[-_/])(article|detail|content|news|notice|view|story|post)(?:[-_/.]|$)", re.IGNORECASE
)
ARTICLE_QUERY_KEYS = {
    "articleid", "article_id", "contentid", "content_id", "docid", "doc_id", "infoid", "newsid", "postid"
}
DIRECTORY_PATH_PATTERN = re.compile(
    r"(?:^|[-_/])(category|categories|channel|directory|guide|index|list|nav|search|sitemap)(?:[-_/.]|$)",
    re.IGNORECASE,
)
DIRECTORY_QUERY_KEYS = {"cate", "category", "channel", "column", "dept", "department", "menu", "type"}
NAVIGATION_TITLES = {
    "首页", "返回首页", "政务公开", "信息公开", "信息公开目录", "按部门分类", "按主题分类",
    "上一页", "下一页", "更多", "更多信息", "网站地图", "联系我们", "登录", "注册",
    "home", "back", "previous", "next", "more", "menu", "navigation",
}
DATE_PATTERN = re.compile(r"(?<!\d)(20\d{2})[-年/.](0?[1-9]|1[0-2])[-月/.](0?[1-9]|[12]\d|3[01])日?(?!\d)")


@dataclass(frozen=True)
class DiscoverySource:
    source_id: int
    source_url: str
    rate_limit_seconds: int = 3

    @property
    def origin_host(self) -> str:
        return (urlparse(self.source_url).hostname or "").lower()


@dataclass(frozen=True)
class ArticleCandidate:
    source_id: int
    discovered_url: str
    canonical_url: str
    title: str
    published_time: str | None
    discovery_method: str
    discovery_page: str
    content_kind_candidate: str
    discovered_at: str
    dedup_key: str


@dataclass
class DiscoveryResult:
    candidates: list[ArticleCandidate] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    filtered_navigation_count: int = 0


class DiscoveryFailure(RuntimeError):
    """Safe, stable failure contract shared with the operations UI."""

    def __init__(self, code: str, message: str, *, retryable: bool, status_code: int = 502) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
        self.status_code = status_code

    def detail(self) -> dict[str, object]:
        return {
            "error_code": self.code,
            "message": self.message,
            "retryable": self.retryable,
            "stage": "DISCOVERY",
        }


def _contains_cause(exc: BaseException, expected: type[BaseException]) -> bool:
    current: BaseException | None = exc
    seen: set[int] = set()
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        if isinstance(current, expected):
            return True
        current = current.__cause__ or current.__context__
    return False


def _network_failure(exc: BaseException) -> DiscoveryFailure:
    if isinstance(exc, httpx.ConnectTimeout):
        return DiscoveryFailure("CONNECT_TIMEOUT", "官网连接超时", retryable=True)
    if isinstance(exc, httpx.ReadTimeout):
        return DiscoveryFailure("READ_TIMEOUT", "官网响应读取超时", retryable=True)
    if isinstance(exc, httpx.TooManyRedirects):
        return DiscoveryFailure("REDIRECT_LIMIT", "官网重定向次数过多", retryable=False, status_code=400)
    if _contains_cause(exc, socket.gaierror):
        return DiscoveryFailure("DNS_FAILED", "官网域名暂时无法解析", retryable=True)
    if _contains_cause(exc, ssl.SSLError):
        return DiscoveryFailure("TLS_FAILED", "官网安全连接验证失败", retryable=True)
    if isinstance(exc, httpx.ConnectError):
        return DiscoveryFailure("CONNECT_TIMEOUT", "暂时无法连接官网", retryable=True)
    return DiscoveryFailure("READ_TIMEOUT", "读取官网内容时连接中断", retryable=True)


async def _retry_wait(attempt: int) -> None:
    low, high = RETRY_DELAY_RANGES[min(attempt, len(RETRY_DELAY_RANGES) - 1)]
    await asyncio.sleep(random.uniform(low, high))


async def _send_with_retry(request: httpx.Request) -> httpx.Response:
    last_failure: DiscoveryFailure | None = None
    for attempt in range(len(RETRY_DELAY_RANGES) + 1):
        try:
            response = await _client().send(request, stream=True, follow_redirects=False)
        except httpx.TransportError as exc:
            last_failure = _network_failure(exc)
            if not last_failure.retryable or attempt >= len(RETRY_DELAY_RANGES):
                raise last_failure from exc
            await _retry_wait(attempt)
            continue
        if response.status_code == 429 or response.status_code >= 500:
            if attempt < len(RETRY_DELAY_RANGES):
                await response.aclose()
                await _retry_wait(attempt)
                continue
        return response
    raise last_failure or DiscoveryFailure("READ_TIMEOUT", "读取官网内容时连接中断", retryable=True)


async def _read_robots_with_retry(url: str, rate_limit_seconds: int) -> tuple[bool, str]:
    last_transport: BaseException | None = None
    for attempt in range(len(RETRY_DELAY_RANGES) + 1):
        try:
            allowed, status = await _robots(url, rate_limit_seconds)
            if allowed or status.startswith(("DENIED", "DISALLOWED")):
                return allowed, status
            if not status.startswith("UNAVAILABLE") or attempt >= len(RETRY_DELAY_RANGES):
                return allowed, status
        except (httpx.TransportError, socket.gaierror) as exc:
            last_transport = exc
            if attempt >= len(RETRY_DELAY_RANGES):
                raise
        await _retry_wait(attempt)
    if last_transport:
        raise last_transport
    return False, "UNAVAILABLE"


class _SectionParser(HTMLParser):
    def __init__(self, base_url: str) -> None:
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.links: list[tuple[str, str, str | None]] = []
        self.json_ld: list[object] = []
        self._href = ""
        self._text: list[str] = []
        self._json_ld = False
        self._script: list[str] = []
        self._row_depth = 0
        self._row_links: list[tuple[str, str]] = []
        self._row_text: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if tag == "tr":
            self._row_depth += 1
            if self._row_depth == 1:
                self._row_links = []
                self._row_text = []
        if tag == "a" and values.get("href") and len(self.links) < MAX_SECTION_LINKS:
            self._href = urljoin(self.base_url, values["href"] or "")
            self._text = []
        if tag == "script" and "ld+json" in (values.get("type") or "").lower():
            self._json_ld = True
            self._script = []

    def handle_data(self, data: str) -> None:
        if self._href:
            self._text.append(data)
        if self._json_ld:
            self._script.append(data)
        if self._row_depth:
            self._row_text.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag == "a" and self._href:
            link = (self._href, re.sub(r"\s+", " ", "".join(self._text)).strip())
            if self._row_depth:
                self._row_links.append(link)
            else:
                self.links.append((*link, None))
            self._href = ""
            self._text = []
        if tag == "script" and self._json_ld:
            try:
                self.json_ld.append(json.loads("".join(self._script)))
            except (ValueError, TypeError):
                pass
            self._json_ld = False
            self._script = []
        if tag == "tr" and self._row_depth:
            if self._row_depth == 1:
                row_text = re.sub(r"\s+", " ", "".join(self._row_text)).strip()
                match = DATE_PATTERN.search(row_text)
                published = "-".join(match.groups()) if match else None
                self.links.extend((url, title, published) for url, title in self._row_links)
                self._row_links = []
                self._row_text = []
            self._row_depth -= 1


def _article_link_score(url: str, title: str, page_url: str) -> int:
    """Rank generic article-like links without depending on a specific website."""
    parsed = urlparse(url)
    page = urlparse(page_url)
    path = parsed.path.lower()
    query_keys = {item.split("=", 1)[0].lower() for item in parsed.query.split("&") if item}
    normalized_title = re.sub(r"\s+", " ", title).strip()
    lowered_title = normalized_title.lower()
    score = 0
    if ARTICLE_PATH_PATTERN.search(path):
        score += 60
    if query_keys & ARTICLE_QUERY_KEYS:
        score += 50
    if re.search(r"/20\d{2}/(?:0?[1-9]|1[0-2])(?:/|$)", path):
        score += 30
    if path.endswith((".html", ".htm", ".shtml", ".pdf")):
        score += 8
    if len(normalized_title) >= 8:
        score += 10
    if path.startswith("/service") and len(normalized_title) >= 6:
        score += 15
    if lowered_title in NAVIGATION_TITLES or len(normalized_title) <= 2:
        score -= 80
    if DIRECTORY_PATH_PATTERN.search(path):
        score -= 55
    if query_keys & DIRECTORY_QUERY_KEYS and not (query_keys & ARTICLE_QUERY_KEYS):
        score -= 55
    if parsed.path == page.path and parsed.query == page.query:
        score -= 100
    return score


def normalize_url(raw_url: str, base_url: str | None = None) -> str:
    value = urljoin(base_url or raw_url, raw_url).strip()
    parsed = urlparse(value)
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname or parsed.username:
        raise ValueError("只允许不含账号信息的 HTTP/HTTPS 公开网址")
    host = parsed.hostname.lower()
    port = parsed.port
    netloc = host if port is None or (parsed.scheme.lower() == "http" and port == 80) or (
        parsed.scheme.lower() == "https" and port == 443
    ) else f"{host}:{port}"
    path = re.sub(r"/{2,}", "/", parsed.path or "/")
    return urlunparse((parsed.scheme.lower(), netloc, path, "", parsed.query, ""))


def _same_origin(url: str, source: DiscoverySource) -> bool:
    return (urlparse(url).hostname or "").lower() == source.origin_host


def _candidate(source: DiscoverySource, url: str, title: str, published: str | None,
               method: str, page: str, kind: str = "UNKNOWN") -> ArticleCandidate:
    canonical = normalize_url(url, page)
    if not _same_origin(canonical, source):
        raise ValueError("候选链接不属于已启用白名单来源")
    return ArticleCandidate(
        source_id=source.source_id,
        discovered_url=normalize_url(url, page),
        canonical_url=canonical,
        title=title.strip()[:300],
        published_time=published.strip() if published else None,
        discovery_method=method,
        discovery_page=normalize_url(page),
        content_kind_candidate=kind,
        discovered_at=datetime.now(timezone.utc).isoformat(),
        dedup_key=hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
    )


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].lower()


def _child_text(element: ET.Element, name: str) -> str:
    for child in element:
        if _local(child.tag) == name and child.text:
            return child.text.strip()
    return ""


def _validate_xml(root: ET.Element) -> None:
    count = 0
    stack = [(root, 1)]
    while stack:
        node, depth = stack.pop()
        count += 1
        if count > MAX_XML_NODES:
            raise ValueError("XML 节点数量超过限制")
        if depth > MAX_XML_DEPTH:
            raise ValueError("XML 嵌套深度超过限制")
        stack.extend((child, depth + 1) for child in node)


def _xml_root(content: bytes) -> ET.Element:
    if len(content) > MAX_DISCOVERY_BYTES:
        raise ValueError("发现文件超过大小限制")
    if b"<!DOCTYPE" in content.upper() or b"<!ENTITY" in content.upper():
        raise ValueError("不允许包含 DTD 或实体的 XML")
    try:
        root = ET.parse(io.BytesIO(content)).getroot()
    except ET.ParseError as exc:
        raise ValueError("XML 格式错误") from exc
    _validate_xml(root)
    return root


def parse_feed(content: bytes, page_url: str, source: DiscoverySource) -> DiscoveryResult:
    result = DiscoveryResult()
    root = _xml_root(content)
    root_name = _local(root.tag)
    method = "RSS" if root_name == "rss" else "ATOM" if root_name == "feed" else ""
    if not method:
        raise ValueError("不是支持的 RSS 2.0 或 Atom 文档")
    entries = [node for node in root.iter() if _local(node.tag) in ({"item"} if method == "RSS" else {"entry"})]
    for entry in entries:
        try:
            if method == "RSS":
                url = _child_text(entry, "link") or _child_text(entry, "guid")
                title = _child_text(entry, "title")
                published = _child_text(entry, "pubdate") or None
            else:
                url = ""
                for child in entry:
                    if _local(child.tag) == "link" and child.attrib.get("href") and child.attrib.get("rel", "alternate") == "alternate":
                        url = child.attrib["href"]
                        break
                title = _child_text(entry, "title")
                published = _child_text(entry, "published") or _child_text(entry, "updated") or None
            if not url:
                raise ValueError("条目缺少文章地址")
            result.candidates.append(_candidate(source, url, title, published, method, page_url))
        except (ValueError, TypeError) as exc:
            result.errors.append(str(exc))
        if len(result.candidates) >= MAX_CANDIDATES:
            break
    return _deduplicate(result)


def parse_sitemap(content: bytes, page_url: str, source: DiscoverySource) -> tuple[DiscoveryResult, list[str]]:
    result = DiscoveryResult()
    root = _xml_root(content)
    root_name = _local(root.tag)
    if root_name == "sitemapindex":
        children: list[str] = []
        for node in root:
            try:
                url = normalize_url(_child_text(node, "loc"), page_url)
                if not _same_origin(url, source):
                    raise ValueError("Sitemap 子文件不属于已启用白名单来源")
                children.append(url)
            except ValueError as exc:
                result.errors.append(str(exc))
            if len(children) >= MAX_SITEMAP_CHILDREN:
                break
        return result, list(dict.fromkeys(children))
    if root_name != "urlset":
        raise ValueError("不是支持的 Sitemap 文档")
    for node in root:
        try:
            url = _child_text(node, "loc")
            if not url:
                raise ValueError("Sitemap 条目缺少地址")
            result.candidates.append(_candidate(
                source, url, "", _child_text(node, "lastmod") or None, "SITEMAP", page_url
            ))
        except ValueError as exc:
            result.errors.append(str(exc))
        if len(result.candidates) >= MAX_CANDIDATES:
            break
    return _deduplicate(result), []


def _json_ld_articles(value: object) -> list[dict[str, object]]:
    if isinstance(value, list):
        return [item for child in value for item in _json_ld_articles(child)]
    if not isinstance(value, dict):
        return []
    graph = value.get("@graph")
    items = _json_ld_articles(graph) if graph is not None else []
    raw_type = value.get("@type")
    types = set(raw_type if isinstance(raw_type, list) else [raw_type])
    if types & ARTICLE_TYPES:
        items.append(value)
    return items


def parse_html(content: bytes, page_url: str, source: DiscoverySource) -> DiscoveryResult:
    if len(content) > MAX_DISCOVERY_BYTES:
        raise ValueError("发现页面超过大小限制")
    parser = _SectionParser(page_url)
    parser.feed(content.decode("utf-8", errors="replace"))
    result = DiscoveryResult()
    for document in parser.json_ld:
        for article in _json_ld_articles(document):
            try:
                raw_url = article.get("url") or article.get("mainEntityOfPage") or ""
                if isinstance(raw_url, dict):
                    raw_url = raw_url.get("@id") or raw_url.get("url") or ""
                result.candidates.append(_candidate(
                    source, str(raw_url), str(article.get("headline") or article.get("name") or ""),
                    str(article.get("datePublished")) if article.get("datePublished") else None,
                    "JSON_LD", page_url, "ARTICLE"
                ))
            except (ValueError, TypeError) as exc:
                result.errors.append(str(exc))
    for url, title, published in parser.links:
        try:
            if not title or url.startswith(("mailto:", "tel:", "javascript:")):
                continue
            if _article_link_score(url, title, page_url) < 10:
                result.filtered_navigation_count += 1
                continue
            result.candidates.append(_candidate(source, url, title, published, "SECTION", page_url))
        except ValueError as exc:
            result.errors.append(str(exc))
        if len(result.candidates) >= MAX_CANDIDATES:
            break
    return _deduplicate(result)


def _deduplicate(result: DiscoveryResult) -> DiscoveryResult:
    unique: dict[str, ArticleCandidate] = {}
    for candidate in result.candidates:
        existing = unique.get(candidate.dedup_key)
        if existing is None or (existing.discovery_method == "SECTION" and candidate.discovery_method == "JSON_LD"):
            unique[candidate.dedup_key] = candidate
    result.candidates = list(unique.values())[:MAX_CANDIDATES]
    result.errors = result.errors[:MAX_CANDIDATES]
    return result


async def _bounded_fetch(url: str, source: DiscoverySource) -> tuple[bytes, str, str]:
    current = normalize_url(url)
    for redirect_count in range(MAX_REDIRECTS + 1):
        if not _same_origin(current, source):
            raise DiscoveryFailure(
                "CROSS_DOMAIN_BLOCKED", "官网跳转到了未登记域名，已停止访问",
                retryable=False, status_code=400,
            )
        try:
            await _assert_public_host(current)
        except socket.gaierror as exc:
            raise DiscoveryFailure("DNS_FAILED", "官网域名暂时无法解析", retryable=True) from exc
        await _rate_limit(source.origin_host, source.rate_limit_seconds)
        request = _client().build_request("GET", current, headers={"User-Agent": USER_AGENT})
        response = await _send_with_retry(request)
        try:
            if response.status_code in {301, 302, 303, 307, 308}:
                if redirect_count >= MAX_REDIRECTS:
                    raise DiscoveryFailure(
                        "REDIRECT_LIMIT", "官网重定向次数过多", retryable=False, status_code=400,
                    )
                location = response.headers.get("location")
                if not location:
                    raise ValueError("重定向缺少目标地址")
                current = normalize_url(location, current)
                continue
            status = response.status_code
            if status == 403:
                raise DiscoveryFailure("HTTP_403", "官网拒绝了本次访问", retryable=False, status_code=403)
            if status == 404:
                raise DiscoveryFailure("HTTP_404", "配置的官网入口不存在", retryable=False, status_code=404)
            if status == 429:
                raise DiscoveryFailure("HTTP_429", "官网访问频率受限，系统将稍后重试", retryable=True, status_code=429)
            if status >= 500:
                raise DiscoveryFailure("HTTP_5XX", "官网服务暂时异常", retryable=True)
            response.raise_for_status()
            declared = int(response.headers.get("content-length", "0") or 0)
            if declared > MAX_DISCOVERY_BYTES:
                raise ValueError("响应体超过大小限制")
            chunks: list[bytes] = []
            size = 0
            async for chunk in response.aiter_bytes():
                size += len(chunk)
                if size > MAX_DISCOVERY_BYTES:
                    raise ValueError("响应体超过大小限制")
                chunks.append(chunk)
            return b"".join(chunks), current, response.headers.get("content-type", "")
        finally:
            await response.aclose()
    raise DiscoveryFailure("REDIRECT_LIMIT", "官网重定向次数过多", retryable=False, status_code=400)


async def discover_articles(source: DiscoverySource, entry_url: str, method: str) -> DiscoveryResult:
    normalized = normalize_url(entry_url)
    if not _same_origin(normalized, source):
        raise ValueError("发现入口不属于已启用白名单来源")
    try:
        allowed, robots_status = await _read_robots_with_retry(normalized, source.rate_limit_seconds)
    except httpx.TransportError as exc:
        failure = _network_failure(exc)
        raise DiscoveryFailure(
            "ROBOTS_UNAVAILABLE", "官网访问规则暂时无法读取，系统将稍后重试",
            retryable=True,
        ) from failure
    except socket.gaierror as exc:
        raise DiscoveryFailure("DNS_FAILED", "官网域名暂时无法解析", retryable=True) from exc
    if not allowed:
        code = "ROBOTS_DENIED" if robots_status.startswith(("DENIED", "DISALLOWED")) else "ROBOTS_UNAVAILABLE"
        raise DiscoveryFailure(
            code,
            "官网公开访问规则不允许采集" if code == "ROBOTS_DENIED" else "官网访问规则暂时不可用",
            retryable=code == "ROBOTS_UNAVAILABLE",
            status_code=403 if code == "ROBOTS_DENIED" else 502,
        )
    content, final_url, content_type = await _bounded_fetch(normalized, source)
    if not content.strip():
        raise DiscoveryFailure("EMPTY_HTML", "官网返回了空页面", retryable=True)
    selected = method.upper()
    try:
        if selected in {"RSS", "ATOM"}:
            result = parse_feed(content, final_url, source)
        elif selected == "SITEMAP":
            result, children = parse_sitemap(content, final_url, source)
            for child in children:
                try:
                    child_content, child_url, _ = await _bounded_fetch(child, source)
                    child_result, _ = parse_sitemap(child_content, child_url, source)
                    result.candidates.extend(child_result.candidates)
                    result.errors.extend(child_result.errors)
                    result.filtered_navigation_count += child_result.filtered_navigation_count
                except (DiscoveryFailure, ValueError) as exc:
                    result.errors.append(str(exc))
            result = _deduplicate(result)
        elif selected in {"JSON_LD", "SECTION", "MIXED"} or "html" in content_type.lower():
            result = parse_html(content, final_url, source)
        else:
            raise DiscoveryFailure(
                "PARSER_UNSUPPORTED", "当前入口格式暂不支持自动识别",
                retryable=False, status_code=400,
            )
    except DiscoveryFailure:
        raise
    except ValueError as exc:
        raise DiscoveryFailure(
            "PARSER_UNSUPPORTED", "官网内容格式暂时无法识别",
            retryable=False, status_code=400,
        ) from exc
    if not result.candidates:
        dynamic = b"__NEXT_DATA__" in content or b"webpack" in content.lower() or b"window.__" in content
        raise DiscoveryFailure(
            "DYNAMIC_PAGE_SUSPECTED" if dynamic else "NO_ARTICLE_LINKS",
            "页面可能需要浏览器执行脚本后才能显示文章" if dynamic else "本次没有识别到文章链接",
            retryable=False, status_code=422,
        )
    return result
