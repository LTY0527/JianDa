"""Compliant, whitelist-gated webpage extraction for public information articles."""

from __future__ import annotations

import asyncio
import hashlib
import ipaddress
import json
import os
import re
import socket
import time
from dataclasses import dataclass, field
from datetime import datetime
from email.utils import parsedate_to_datetime
from html import unescape
from html.parser import HTMLParser
from urllib.parse import urljoin, urlparse
from urllib.robotparser import RobotFileParser

import httpx

from app.models import WebArticleImage, WebArticlePreview


USER_AGENT = "JianDaContentBot/1.0 (+public-service accessibility research)"
REQUEST_TIMEOUT = httpx.Timeout(20.0, connect=8.0)
MAX_HTML_BYTES = 5 * 1024 * 1024
MAX_IMAGE_BYTES = 8 * 1024 * 1024
_CLIENT: httpx.AsyncClient | None = None
_DOMAIN_LOCKS: dict[str, asyncio.Lock] = {}
_LAST_REQUEST_AT: dict[str, float] = {}
_CACHE: dict[str, tuple[float, WebArticlePreview]] = {}


def _client() -> httpx.AsyncClient:
    global _CLIENT
    if _CLIENT is None or _CLIENT.is_closed:
        _CLIENT = httpx.AsyncClient(
            timeout=REQUEST_TIMEOUT,
            follow_redirects=True,
            limits=httpx.Limits(max_connections=8, max_keepalive_connections=4),
            headers={"User-Agent": USER_AGENT, "Accept": "text/html,application/xhtml+xml"},
        )
    return _CLIENT


async def _assert_public_host(url: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username:
        raise ValueError("只允许不含账号信息的 HTTP/HTTPS 公开网址")
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    records = await asyncio.to_thread(socket.getaddrinfo, parsed.hostname, port)
    allow_fake_ip_dns = os.getenv(
        "JIANDA_ALLOW_FAKE_IP_DNS", ""
    ).strip().lower() in {"1", "true", "yes"}
    fake_ip_network = ipaddress.ip_network("198.18.0.0/15")
    for record in records:
        address = ipaddress.ip_address(record[4][0])
        if allow_fake_ip_dns and address in fake_ip_network:
            continue
        if not address.is_global:
            raise ValueError("不允许访问本机、局域网或保留地址")


async def _rate_limit(domain: str, interval_seconds: int) -> None:
    lock = _DOMAIN_LOCKS.setdefault(domain, asyncio.Lock())
    async with lock:
        remaining = interval_seconds - (time.monotonic() - _LAST_REQUEST_AT.get(domain, 0))
        if remaining > 0:
            await asyncio.sleep(remaining)
        _LAST_REQUEST_AT[domain] = time.monotonic()


async def _robots(url: str, interval_seconds: int) -> tuple[bool, str]:
    parsed = urlparse(url)
    robots_url = f"{parsed.scheme}://{parsed.netloc}/robots.txt"
    await _rate_limit(parsed.hostname or "", interval_seconds)
    response = await _client().get(robots_url)
    if response.status_code in {401, 403}:
        return False, f"DENIED_HTTP_{response.status_code}"
    if response.status_code == 404:
        return True, "NOT_FOUND_ALLOW"
    if response.status_code >= 400:
        return False, f"UNAVAILABLE_HTTP_{response.status_code}"
    parser = RobotFileParser()
    parser.set_url(robots_url)
    parser.parse(response.text.splitlines())
    allowed = parser.can_fetch(USER_AGENT, url)
    return allowed, "ALLOWED" if allowed else "DISALLOWED"


@dataclass
class _ParsedPage:
    title: str = ""
    canonical_url: str = ""
    author: str = ""
    source_name: str = ""
    wechat_account_name: str = ""
    account_subject: str = ""
    wechat_biz: str = ""
    published_at: str = ""
    cover_image_url: str = ""
    cover_image_type: str = "CATEGORY_DEFAULT"
    json_ld_image_url: str = ""
    blocks: list[str] = field(default_factory=list)
    preferred_blocks: list[str] = field(default_factory=list)
    images: list[WebArticleImage] = field(default_factory=list)
    json_ld: list[dict[str, object]] = field(default_factory=list)


class _ArticleParser(HTMLParser):
    BLOCK_TAGS = {"p", "h1", "h2", "h3", "h4", "li", "blockquote", "figcaption"}
    SKIP_TAGS = {"script", "style", "noscript", "svg", "nav", "footer", "form", "button"}

    def __init__(self, base_url: str) -> None:
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.page = _ParsedPage()
        self._stack: list[str] = []
        self._skip_depth = 0
        self._preferred_depth = 0
        self._block_tag = ""
        self._block_text: list[str] = []
        self._title_text: list[str] = []
        self._json_ld_text: list[str] = []
        self._in_json_ld = False
        self._last_block_text = ""
        self._pending_image_indexes: list[int] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attr = {key.lower(): value or "" for key, value in attrs}
        self._stack.append(tag)
        if tag in self.SKIP_TAGS:
            self._skip_depth += 1
        if tag in {"article", "main"} or "article" in attr.get("class", "").lower():
            self._preferred_depth += 1
        if tag == "title":
            self._title_text = []
        if tag == "script" and "ld+json" in attr.get("type", "").lower():
            self._in_json_ld = True
            self._json_ld_text = []
        if tag == "meta":
            self._meta(attr)
        elif tag == "link" and "canonical" in attr.get("rel", "").lower():
            self.page.canonical_url = urljoin(self.base_url, attr.get("href", ""))
        elif tag in {"img", "source", "video"}:
            self._image_candidates(tag, attr)
        if tag in self.BLOCK_TAGS and not self._skip_depth:
            self._block_tag = tag
            self._block_text = []

    def handle_endtag(self, tag: str) -> None:
        if tag == "title" and not self.page.title:
            self.page.title = _clean("".join(self._title_text))
        if tag == "script" and self._in_json_ld:
            self._parse_json_ld("".join(self._json_ld_text))
            self._in_json_ld = False
        if tag == self._block_tag:
            text = _clean("".join(self._block_text))
            if len(text) >= 8 and not _looks_like_chrome(text):
                self.page.blocks.append(text)
                if self._preferred_depth:
                    self.page.preferred_blocks.append(text)
                for index in self._pending_image_indexes:
                    image = self.page.images[index]
                    image.context_text = _clean(f"{image.context_text} {text}")[:500]
                self._pending_image_indexes = []
                self._last_block_text = text
            self._block_tag = ""
            self._block_text = []
        if tag in {"article", "main"} and self._preferred_depth:
            self._preferred_depth -= 1
        if tag in self.SKIP_TAGS and self._skip_depth:
            self._skip_depth -= 1
        if self._stack:
            self._stack.pop()

    def handle_data(self, data: str) -> None:
        if self._in_json_ld:
            self._json_ld_text.append(data)
        if self._stack and self._stack[-1] == "title":
            self._title_text.append(data)
        if self._block_tag and not self._skip_depth:
            self._block_text.append(data)

    def _meta(self, attr: dict[str, str]) -> None:
        key = (
            attr.get("property")
            or attr.get("name")
            or attr.get("itemprop")
            or ""
        ).lower()
        value = unescape(attr.get("content", "")).strip()
        if not value:
            return
        if key in {"og:title", "twitter:title"}:
            self.page.title = value
        elif key == "og:image":
            self.page.cover_image_url = urljoin(self.base_url, value)
            self.page.cover_image_type = "ORIGINAL_COVER"
        elif key in {"author", "article:author"}:
            self.page.author = value
        elif key in {"article:published_time", "pubdate", "publishdate", "datepublished"}:
            self.page.published_at = value
        elif key in {"og:site_name", "source"}:
            self.page.source_name = value
        elif key in {"profile_nickname", "wechat_account_name"}:
            self.page.wechat_account_name = value
            self.page.source_name = self.page.source_name or value
        elif key in {"account_subject", "publisher"}:
            self.page.account_subject = value

    def _image_candidates(self, tag: str, attr: dict[str, str]) -> None:
        sources: list[str] = []
        if tag == "video":
            sources.append(attr.get("poster", ""))
        else:
            for key in (
                "src",
                "data-src",
                "data-original",
                "data-lazy-src",
                "data-echo",
            ):
                sources.append(attr.get(key, ""))
            for key in ("srcset", "data-srcset"):
                sources.extend(_srcset_urls(attr.get(key, "")))
        width = int(attr.get("width", "0")) if attr.get("width", "").isdigit() else None
        height = int(attr.get("height", "0")) if attr.get("height", "").isdigit() else None
        caption = (attr.get("alt") or attr.get("title") or "").strip()
        dom_hint = " ".join(filter(None, (
            " ".join(self._stack[-5:]),
            attr.get("class", ""),
            attr.get("id", ""),
            attr.get("role", ""),
        )))
        context_text = _clean(f"{self._last_block_text} {caption} {dom_hint}")[:500]
        known = {image.url for image in self.page.images}
        for source in sources:
            source = source.strip()
            if not source or source.startswith(("data:", "javascript:")):
                continue
            resolved = urljoin(self.base_url, source)
            if resolved in known:
                continue
            known.add(resolved)
            image_index = len(self.page.images)
            self.page.images.append(
                WebArticleImage(
                    url=resolved,
                    caption=caption,
                    context_text=context_text,
                    discovery_method="ARTICLE_IMAGE",
                    width=width,
                    height=height,
                )
            )
            self._pending_image_indexes.append(image_index)

    def _parse_json_ld(self, value: str) -> None:
        try:
            parsed = json.loads(value)
        except (json.JSONDecodeError, TypeError):
            return
        candidates = parsed if isinstance(parsed, list) else [parsed]
        for candidate in candidates:
            if isinstance(candidate, dict) and isinstance(candidate.get("@graph"), list):
                candidates.extend(candidate["@graph"])
            if isinstance(candidate, dict):
                self.page.json_ld.append(candidate)


def _clean(value: str) -> str:
    return re.sub(r"\s+", " ", unescape(value)).strip()


def _srcset_urls(value: str) -> list[str]:
    """Return URLs without treating srcset density/width hints as addresses."""
    result: list[str] = []
    for candidate in value.split(","):
        url = candidate.strip().split(maxsplit=1)[0] if candidate.strip() else ""
        if url:
            result.append(url)
    return result


def _looks_like_chrome(value: str) -> bool:
    normalized = value.replace(" ", "")
    chrome = ("首页", "登录", "注册", "客户端下载", "相关推荐", "责任编辑", "版权所有", "网站地图")
    return len(normalized) < 80 and sum(item in normalized for item in chrome) >= 2


def _topic_tokens(value: str) -> set[str]:
    """Build small language-neutral tokens without a site-specific dictionary."""
    normalized = _clean(value).lower()
    tokens = set(re.findall(r"[a-z0-9]{3,}", normalized))
    for run in re.findall(r"[\u3400-\u9fff]{2,}", normalized):
        tokens.update(run[index:index + 2] for index in range(len(run) - 1))
    return tokens


def _image_relevance(
    page: _ParsedPage, image: WebArticleImage, discovery_method: str,
) -> int:
    base = {"OPEN_GRAPH": 80, "JSON_LD": 70, "ARTICLE_IMAGE": 10}[discovery_method]
    evidence = _clean(f"{image.caption} {image.context_text}")
    overlap = _topic_tokens(page.title).intersection(_topic_tokens(evidence))
    score = base + min(24, len(overlap) * 4)
    context = evidence.lower()
    if "article" in context or "main" in context:
        score += 15
    unrelated = (
        "header", "footer", "navbar", "navigation", "sidebar", "download-app",
        "copyright", "login", "register", "页头", "页脚", "导航", "登录", "版权",
    )
    score -= sum(20 for word in unrelated if word in context)
    return max(0, min(100, score))


def _json_ld_metadata(page: _ParsedPage) -> None:
    for item in page.json_ld:
        kind = str(item.get("@type", "")).lower()
        if not any(word in kind for word in ("article", "news", "report")):
            continue
        page.title = page.title or _clean(str(item.get("headline", "")))
        page.author = page.author or _nested_name(item.get("author"))
        page.source_name = page.source_name or _nested_name(item.get("publisher"))
        page.published_at = page.published_at or str(item.get("datePublished", ""))
        image = item.get("image")
        if isinstance(image, str):
            page.json_ld_image_url = urljoin(page.canonical_url, image)
        elif isinstance(image, list) and image:
            page.json_ld_image_url = urljoin(page.canonical_url, str(image[0]))
        elif isinstance(image, dict):
            image_url = image.get("url") or image.get("contentUrl")
            if image_url:
                page.json_ld_image_url = urljoin(page.canonical_url, str(image_url))


def _wechat_metadata(page: _ParsedPage, html: str, host: str) -> None:
    """Read public account hints without treating the shared domain as proof."""
    if host.lower() != "mp.weixin.qq.com":
        return
    patterns = {
        "wechat_biz": (
            r"""["']?biz["']?\s*[:=]\s*["']([^"']{4,160})""",
            r"""__biz=([^&"']+)""",
        ),
        "wechat_account_name": (
            r"""profile_nickname["']?\s*[:=]\s*["']([^"']{2,160})""",
            r"""<strong[^>]+class=["'][^"']*profile_nickname[^"']*["'][^>]*>(.*?)</strong>""",
            r"""<span[^>]+id=["']js_name["'][^>]*>(.*?)</span>""",
        ),
        "account_subject": (
            r"""account_subject["']?\s*[:=]\s*["']([^"']{2,160})""",
        ),
    }
    for field_name, candidates in patterns.items():
        for pattern in candidates:
            match = re.search(pattern, html, re.IGNORECASE | re.DOTALL)
            if match:
                value = _clean(re.sub(r"<[^>]+>", " ", match.group(1)))
                if value:
                    setattr(page, field_name, value)
                    break
    page.source_name = page.source_name or page.wechat_account_name


def _nested_name(value: object) -> str:
    if isinstance(value, dict):
        return _clean(str(value.get("name", "")))
    if isinstance(value, list) and value:
        return _nested_name(value[0])
    return _clean(str(value or ""))


def _published_at(value: str) -> datetime | None:
    if not value:
        return None
    normalized = value.strip().replace("年", "-").replace("月", "-").replace("日", "")
    try:
        return datetime.fromisoformat(normalized.replace("Z", "+00:00"))
    except ValueError:
        pass
    try:
        return parsedate_to_datetime(value)
    except (TypeError, ValueError):
        pass
    match = re.search(r"(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?", normalized)
    if not match:
        return None
    return datetime(
        int(match.group(1)), int(match.group(2)), int(match.group(3)),
        int(match.group(4) or 0), int(match.group(5) or 0), int(match.group(6) or 0),
    )


def classify_content(title: str, text: str) -> tuple[str, float]:
    body = text[:4000]
    strong_title_rules = (
        ("ANTI_FRAUD", ("反诈", "防诈", "诈骗")),
        ("HEALTH_EDUCATION", ("健康", "用药", "减重", "消暑", "就医")),
        ("POLICY_NEWS", ("政策", "管理办法", "行动方案")),
        ("SERVICE_NOTICE", ("报名通知", "办理通知", "申请通知")),
    )
    for kind, words in strong_title_rules:
        if any(word in title for word in words):
            return kind, 0.95
    rules = (
        ("ANTI_FRAUD", ("诈骗", "反诈", "防诈", "验证码", "安全账户", "屏幕共享")),
        ("HEALTH_EDUCATION", ("健康", "就医", "疾病", "用药", "减重", "消暑", "症状")),
        ("POLICY_NEWS", ("政策", "民政部", "补贴", "行动方案", "管理办法", "网络建设")),
        ("COMMUNITY_SERVICE", ("社区", "上门", "送餐", "助老", "志愿服务")),
        ("SERVICE_NOTICE", ("报名", "办理", "申请", "受理时间", "所需材料")),
    )
    scores = [
        (
            kind,
            sum(title.count(word) * 4 + body.count(word) for word in words),
        )
        for kind, words in rules
    ]
    kind, score = max(scores, key=lambda item: item[1])
    return (kind, min(0.98, 0.68 + score * 0.05)) if score else ("GENERAL_NEWS", 0.55)


def _image_dimensions(data: bytes, content_type: str) -> tuple[int | None, int | None]:
    if content_type.startswith("image/png") and len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n":
        return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")
    if content_type.startswith("image/jpeg") and data[:2] == b"\xff\xd8":
        index = 2
        while index + 9 < len(data):
            if data[index] != 0xFF:
                index += 1
                continue
            marker = data[index + 1]
            if marker in {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}:
                return int.from_bytes(data[index + 7:index + 9], "big"), int.from_bytes(data[index + 5:index + 7], "big")
            if index + 4 > len(data):
                break
            length = int.from_bytes(data[index + 2:index + 4], "big")
            index += max(length + 2, 2)
    return None, None


async def _validated_cover(
    page: _ParsedPage,
    domain: str,
    interval_seconds: int,
    allow_image_candidates: bool,
) -> tuple[str, str, str, int | None, int | None, str, bool]:
    # A dimension/content check requires downloading the candidate. When the
    # source registry does not explicitly allow that, fail closed and let the
    # clients use JianDa's local category artwork.
    if not allow_image_candidates:
        return "", "CATEGORY_DEFAULT", page.title, None, None, "", False
    candidates: list[tuple[str, str, str, str, str]] = []
    if page.cover_image_url:
        candidates.append((page.cover_image_url, page.cover_image_type, page.title, "OPEN_GRAPH", "OpenGraph 封面"))
    if page.json_ld_image_url and page.json_ld_image_url != page.cover_image_url:
        candidates.append((page.json_ld_image_url, "ORIGINAL_COVER", page.title, "JSON_LD", "JSON-LD 封面"))
    candidates.extend((image.url, "ARTICLE_IMAGE", image.caption, "ARTICLE_IMAGE", image.context_text) for image in page.images)
    rejected_words = (
        "logo", "icon", "avatar", "qrcode", "qr_code", "qr-code", "tracking", "pixel",
        "banner-ad", "advert", "广告", "二维码", "头像", "图标", "统计",
    )
    validated_images: list[WebArticleImage] = []
    selected: tuple[str, str, str, int, int, str, bool] | None = None
    validated_ranked: list[tuple[int, str, str, str, int, int, str]] = []
    for rank, (url, image_type, alt, discovery_method, context_text) in enumerate(candidates[:12]):
        fingerprint = f"{url} {alt} {context_text}".lower()
        if any(word in fingerprint for word in rejected_words):
            continue
        candidate = WebArticleImage(
            url=url, caption=_clean(alt),
            context_text=_clean(context_text), discovery_method=discovery_method,
        )
        relevance_score = _image_relevance(page, candidate, discovery_method)
        if discovery_method == "ARTICLE_IMAGE" and relevance_score < 20:
            continue
        try:
            await _assert_public_host(url)
            await _rate_limit(domain, interval_seconds)
            response = await _client().get(url, follow_redirects=True)
            content_type = response.headers.get("content-type", "").split(";", 1)[0].lower()
            if response.status_code != 200 or not content_type.startswith("image/"):
                continue
            data = response.content
            if not data or len(data) > 8 * 1024 * 1024:
                continue
            width, height = _image_dimensions(data, content_type)
            if width is None or height is None:
                continue
            if width <= 8 or height <= 8 or width < 600 or height < 250:
                continue
            ratio = width / height
            if ratio < 0.75 or ratio > 2.4:
                continue
            image_hash = hashlib.sha256(data).hexdigest()
            validated_images.append(WebArticleImage(
                url=str(response.url), caption=_clean(alt),
                context_text=_clean(context_text), relevance_score=relevance_score,
                discovery_method=discovery_method, mime_type=content_type,
                width=width, height=height, image_hash=image_hash,
                image_cached=False, candidate_status="VALID",
            ))
            validated_ranked.append((
                relevance_score, str(response.url), image_type,
                _clean(alt) or page.title, width, height, image_hash,
            ))
        except (httpx.HTTPError, OSError, ValueError):
            continue
    validated_images.sort(key=lambda image: image.relevance_score, reverse=True)
    page.images = validated_images
    if validated_ranked:
        best = max(validated_ranked, key=lambda item: item[0])
        selected = (best[1], best[2], best[3], best[4], best[5], best[6], True)
    return selected or ("", "CATEGORY_DEFAULT", page.title, None, None, "", False)


async def preview_web_article(
    url: str,
    rate_limit_seconds: int = 3,
    allow_image_candidates: bool = False,
) -> WebArticlePreview:
    cache_key = f"{url}|image-candidates={allow_image_candidates}"
    cached = _CACHE.get(cache_key)
    if cached and time.monotonic() - cached[0] < 600:
        return cached[1]
    await _assert_public_host(url)
    allowed, robots_status = await _robots(url, rate_limit_seconds)
    if not allowed:
        raise PermissionError(f"robots.txt 不允许采集：{robots_status}")
    parsed_url = urlparse(url)
    await _rate_limit(parsed_url.hostname or "", rate_limit_seconds)
    response = await _client().get(url)
    response.raise_for_status()
    await _assert_public_host(str(response.url))
    content_type = response.headers.get("content-type", "")
    if "html" not in content_type.lower():
        raise ValueError("目标地址未返回 HTML 网页")
    raw = response.content[:MAX_HTML_BYTES]
    html = raw.decode(response.encoding or "utf-8", errors="replace")
    parser = _ArticleParser(str(response.url))
    parser.feed(html)
    page = parser.page
    page.canonical_url = page.canonical_url or str(response.url)
    _wechat_metadata(page, html, urlparse(page.canonical_url).hostname or "")
    _json_ld_metadata(page)
    blocks = page.preferred_blocks if len("".join(page.preferred_blocks)) >= 200 else page.blocks
    deduplicated = list(dict.fromkeys(block for block in blocks if block != page.title))
    text = "\n".join(deduplicated).strip()
    if len(text) < 60:
        raise ValueError("未能提取足够的公开正文，未启用浏览器绕过页面限制")
    kind, confidence = classify_content(page.title, text)
    cover, cover_type, image_alt, image_width, image_height, image_hash, image_validated = (
        await _validated_cover(
            page,
            parsed_url.hostname or "",
            rate_limit_seconds,
            allow_image_candidates,
        )
    )
    digest = hashlib.sha256(re.sub(r"\s+", "", text).encode("utf-8")).hexdigest()
    preview = WebArticlePreview(
        title=page.title or deduplicated[0][:120],
        source_name=page.source_name,
        wechat_account_name=page.wechat_account_name,
        account_subject=page.account_subject,
        wechat_biz=page.wechat_biz,
        published_at=_published_at(page.published_at),
        author=page.author,
        cover_image_url=cover,
        cover_image_type=cover_type,
        image_alt_text=image_alt,
        image_width=image_width,
        image_height=image_height,
        image_hash=image_hash,
        image_validated=image_validated,
        canonical_url=page.canonical_url,
        content_preview=text[:600],
        extracted_text=text,
        original_html=html,
        content_hash=digest,
        content_kind=kind,
        classification_confidence=confidence,
        robots_allowed=True,
        robots_status=robots_status,
        images=page.images[:20],
        warnings=[] if page.source_name else ["网页未明确标注来源名称，请按白名单来源人工确认。"],
    )
    _CACHE[cache_key] = (time.monotonic(), preview)
    return preview


async def download_validated_image(url: str) -> tuple[bytes, str, int, int]:
    await _assert_public_host(url)
    parsed = urlparse(url)
    await _rate_limit(parsed.hostname or "", 1)
    response = await _client().get(url)
    response.raise_for_status()
    await _assert_public_host(str(response.url))
    content_type = response.headers.get("content-type", "").split(";", 1)[0].lower()
    if not content_type.startswith("image/"):
        raise ValueError("目标地址未返回图片")
    data = response.content
    if not data or len(data) > MAX_IMAGE_BYTES:
        raise ValueError("图片大小不符合缓存限制")
    width, height = _image_dimensions(data, content_type)
    if width is None or height is None or width < 600 or height < 250:
        raise ValueError("图片尺寸不符合公开封面要求")
    ratio = width / height
    if ratio < 0.75 or ratio > 2.4:
        raise ValueError("图片比例不符合公开封面要求")
    return data, content_type, width, height
