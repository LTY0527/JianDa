import asyncio
from pathlib import Path

import httpx
import pytest

from app import article_discovery
from app.article_discovery import (
    DiscoverySource,
    MAX_DISCOVERY_BYTES,
    discover_articles,
    normalize_url,
    parse_feed,
    parse_html,
    parse_sitemap,
)

FIXTURES = Path(__file__).parent / "fixtures" / "article_discovery"
SOURCE = DiscoverySource(17, "https://fixture.example", 0)


def fixture(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


def test_rss_atom_and_duplicate_partial_failures_are_bounded():
    rss = parse_feed(fixture("rss.xml"), "https://fixture.example/rss.xml", SOURCE)
    assert [item.canonical_url for item in rss.candidates] == ["https://fixture.example/articles/pension"]
    assert rss.candidates[0].discovery_method == "RSS"
    assert len(rss.errors) == 2

    atom = parse_feed(fixture("atom.xml"), "https://fixture.example/feed.atom", SOURCE)
    assert atom.candidates[0].canonical_url == "https://fixture.example/articles/health"
    assert atom.candidates[0].published_time == "2026-07-28T09:00:00+08:00"
    assert atom.errors == ["条目缺少文章地址"]


def test_sitemap_and_index_apply_same_origin_and_child_limit():
    sitemap, children = parse_sitemap(
        fixture("sitemap.xml"), "https://fixture.example/sitemap.xml", SOURCE
    )
    assert [item.canonical_url for item in sitemap.candidates] == [
        "https://fixture.example/news/one",
        "https://fixture.example/news/two",
    ]
    assert len(sitemap.errors) == 1
    assert children == []

    index, children = parse_sitemap(
        fixture("sitemap-index.xml"), "https://fixture.example/sitemap-index.xml", SOURCE
    )
    assert index.candidates == []
    assert children == [
        "https://fixture.example/sitemap-news.xml",
        "https://fixture.example/sitemap-services.xml",
    ]
    assert len(index.errors) == 1


def test_json_ld_and_section_links_share_candidate_shape_and_dedup():
    result = parse_html(
        fixture("section.html"), "https://fixture.example/news/index.html", SOURCE
    )
    assert [item.canonical_url for item in result.candidates] == [
        "https://fixture.example/news/fraud-warning",
        "https://fixture.example/services/elderly",
    ]
    assert result.candidates[0].discovery_method == "JSON_LD"
    assert result.candidates[0].content_kind_candidate == "ARTICLE"
    assert result.candidates[0].dedup_key
    assert len(result.errors) == 1

    empty = parse_html(
        fixture("empty-section.html"), "https://fixture.example/empty", SOURCE
    )
    assert empty.candidates == []


def test_directory_navigation_is_filtered_and_table_article_dates_are_kept():
    navigation = "".join(
        f'<a href="/guide/list.html?dept={index}">区属部门{index}</a>' for index in range(30)
    )
    articles = """<table><tbody>
      <tr><td><a href="/article.html?infoid=first-article">社区服务开放日安排</a></td><td>2026-08-14</td></tr>
      <tr><td><a href="/content/view.html?contentid=second-article">长者助餐服务调整通知</a></td><td>2026年8月13日</td></tr>
    </tbody></table>"""
    result = parse_html(
        f"<html><body><nav><a href='/'>首页</a>{navigation}</nav>{articles}</body></html>".encode(),
        "https://fixture.example/directory/index.html?dept=community",
        SOURCE,
    )

    assert [item.title for item in result.candidates] == ["社区服务开放日安排", "长者助餐服务调整通知"]
    assert [item.published_time for item in result.candidates] == ["2026-08-14", "2026-8-13"]
    assert result.filtered_navigation_count == 31


def test_invalid_protocol_malformed_xml_and_oversized_content_are_rejected():
    with pytest.raises(ValueError, match="HTTP/HTTPS"):
        normalize_url("file:///etc/passwd")
    with pytest.raises(ValueError, match="XML 格式错误"):
        parse_feed(fixture("malformed.xml"), "https://fixture.example/rss.xml", SOURCE)
    with pytest.raises(ValueError, match="大小限制"):
        parse_html(b"x" * (MAX_DISCOVERY_BYTES + 1), "https://fixture.example/section", SOURCE)


def test_network_discovery_reuses_robots_ssrf_rate_limit_redirect_and_size_guards(monkeypatch):
    requests: list[str] = []
    public_checks: list[str] = []

    async def public_host(url: str) -> None:
        public_checks.append(url)

    async def no_wait(_domain: str, _seconds: int) -> None:
        return None

    async def robots(_url: str, _seconds: int) -> tuple[bool, str]:
        return True, "ALLOWED"

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(str(request.url))
        if request.url.path == "/start":
            return httpx.Response(302, headers={"Location": "/feed.xml"})
        return httpx.Response(
            200,
            content=fixture("rss.xml"),
            headers={"Content-Type": "application/rss+xml"},
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), follow_redirects=False)
    monkeypatch.setattr(article_discovery, "_client", lambda: client)
    monkeypatch.setattr(article_discovery, "_assert_public_host", public_host)
    monkeypatch.setattr(article_discovery, "_rate_limit", no_wait)
    monkeypatch.setattr(article_discovery, "_robots", robots)
    try:
        result = asyncio.run(discover_articles(SOURCE, "https://fixture.example/start", "RSS"))
    finally:
        asyncio.run(client.aclose())
    assert result.candidates[0].canonical_url.endswith("/articles/pension")
    assert requests == ["https://fixture.example/start", "https://fixture.example/feed.xml"]
    assert public_checks == ["https://fixture.example/start", "https://fixture.example/feed.xml"]


def test_private_host_gate_and_external_redirect_are_not_bypassed(monkeypatch):
    with pytest.raises(ValueError, match="白名单来源"):
        asyncio.run(discover_articles(SOURCE, "http://127.0.0.1/feed", "RSS"))

    async def allow(_url: str) -> None:
        return None

    async def no_wait(_domain: str, _seconds: int) -> None:
        return None

    async def robots(_url: str, _seconds: int) -> tuple[bool, str]:
        return True, "ALLOWED"

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(302, headers={"Location": "http://127.0.0.1/private"})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), follow_redirects=False)
    monkeypatch.setattr(article_discovery, "_client", lambda: client)
    monkeypatch.setattr(article_discovery, "_assert_public_host", allow)
    monkeypatch.setattr(article_discovery, "_rate_limit", no_wait)
    monkeypatch.setattr(article_discovery, "_robots", robots)
    try:
        with pytest.raises(ValueError, match="白名单来源"):
            asyncio.run(discover_articles(SOURCE, "https://fixture.example/start", "RSS"))
    finally:
        asyncio.run(client.aclose())


def test_declared_oversized_response_and_xml_depth_are_rejected(monkeypatch):
    deep = b"<rss><channel>" + b"<x>" * 40 + b"</x>" * 40 + b"</channel></rss>"
    with pytest.raises(ValueError, match="嵌套深度"):
        parse_feed(deep, "https://fixture.example/feed", SOURCE)

    async def allow(_url: str) -> None:
        return None

    async def no_wait(_domain: str, _seconds: int) -> None:
        return None

    async def robots(_url: str, _seconds: int) -> tuple[bool, str]:
        return True, "ALLOWED"

    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, headers={"Content-Length": str(MAX_DISCOVERY_BYTES + 1)})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), follow_redirects=False)
    monkeypatch.setattr(article_discovery, "_client", lambda: client)
    monkeypatch.setattr(article_discovery, "_assert_public_host", allow)
    monkeypatch.setattr(article_discovery, "_rate_limit", no_wait)
    monkeypatch.setattr(article_discovery, "_robots", robots)
    try:
        with pytest.raises(ValueError, match="响应体"):
            asyncio.run(discover_articles(SOURCE, "https://fixture.example/feed", "RSS"))
    finally:
        asyncio.run(client.aclose())
