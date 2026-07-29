"""Bounded article-link discovery built on the existing web-ingest safety gates."""

from __future__ import annotations

import hashlib
import io
import json
import re
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
ARTICLE_TYPES = {"Article", "NewsArticle", "ReportageNewsArticle", "BlogPosting"}


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


class _SectionParser(HTMLParser):
    def __init__(self, base_url: str) -> None:
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.links: list[tuple[str, str]] = []
        self.json_ld: list[object] = []
        self._href = ""
        self._text: list[str] = []
        self._json_ld = False
        self._script: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
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

    def handle_endtag(self, tag: str) -> None:
        if tag == "a" and self._href:
            self.links.append((self._href, re.sub(r"\s+", " ", "".join(self._text)).strip()))
            self._href = ""
            self._text = []
        if tag == "script" and self._json_ld:
            try:
                self.json_ld.append(json.loads("".join(self._script)))
            except (ValueError, TypeError):
                pass
            self._json_ld = False
            self._script = []


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
    for url, title in parser.links:
        try:
            if not title or url.startswith(("mailto:", "tel:", "javascript:")):
                continue
            result.candidates.append(_candidate(source, url, title, None, "SECTION", page_url))
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
            raise ValueError("请求地址不属于已启用白名单来源")
        await _assert_public_host(current)
        await _rate_limit(source.origin_host, source.rate_limit_seconds)
        request = _client().build_request("GET", current, headers={"User-Agent": USER_AGENT})
        response = await _client().send(request, stream=True, follow_redirects=False)
        try:
            if response.status_code in {301, 302, 303, 307, 308}:
                if redirect_count >= MAX_REDIRECTS:
                    raise ValueError("重定向次数超过限制")
                location = response.headers.get("location")
                if not location:
                    raise ValueError("重定向缺少目标地址")
                current = normalize_url(location, current)
                continue
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
    raise ValueError("重定向次数超过限制")


async def discover_articles(source: DiscoverySource, entry_url: str, method: str) -> DiscoveryResult:
    normalized = normalize_url(entry_url)
    if not _same_origin(normalized, source):
        raise ValueError("发现入口不属于已启用白名单来源")
    allowed, robots_status = await _robots(normalized, source.rate_limit_seconds)
    if not allowed:
        raise PermissionError(f"robots.txt 不允许发现：{robots_status}")
    content, final_url, content_type = await _bounded_fetch(normalized, source)
    selected = method.upper()
    if selected in {"RSS", "ATOM"}:
        return parse_feed(content, final_url, source)
    if selected == "SITEMAP":
        result, children = parse_sitemap(content, final_url, source)
        for child in children:
            try:
                child_content, child_url, _ = await _bounded_fetch(child, source)
                child_result, _ = parse_sitemap(child_content, child_url, source)
                result.candidates.extend(child_result.candidates)
                result.errors.extend(child_result.errors)
            except (httpx.HTTPError, ValueError) as exc:
                result.errors.append(str(exc))
        return _deduplicate(result)
    if selected in {"JSON_LD", "SECTION", "MIXED"} or "html" in content_type.lower():
        return parse_html(content, final_url, source)
    raise ValueError("发现方式与响应内容不匹配")
