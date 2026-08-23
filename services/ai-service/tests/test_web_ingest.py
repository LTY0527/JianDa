import asyncio
import json

import httpx
import pytest

from app import web_ingest
from app.web_ingest import _assert_public_host, classify_content, preview_web_article


def _png(width: int, height: int) -> bytes:
    return b"\x89PNG\r\n\x1a\n" + b"\x00" * 8 + width.to_bytes(4, "big") + height.to_bytes(4, "big")


def _run_preview(
    monkeypatch,
    html: str,
    image_status: int = 200,
    allow_image_candidates: bool = True,
    requests: list[str] | None = None,
    url: str = "https://www.news.cn/article",
):
    async def allow_public(_url: str) -> None:
        return None

    async def no_wait(_domain: str, _seconds: int) -> None:
        return None

    def handler(request: httpx.Request) -> httpx.Response:
        if requests is not None:
            requests.append(request.url.path)
        if request.url.path == "/robots.txt":
            return httpx.Response(200, text="User-agent: *\nAllow: /")
        if request.url.path.endswith(".png"):
            if image_status != 200:
                return httpx.Response(image_status)
            return httpx.Response(200, content=_png(1200, 675), headers={"Content-Type": "image/png"})
        return httpx.Response(200, text=html, headers={"Content-Type": "text/html; charset=utf-8"})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler), follow_redirects=True)
    monkeypatch.setattr(web_ingest, "_CLIENT", client)
    monkeypatch.setattr(web_ingest, "_assert_public_host", allow_public)
    monkeypatch.setattr(web_ingest, "_rate_limit", no_wait)
    web_ingest._CACHE.clear()
    try:
        return asyncio.run(preview_web_article(
            url,
            allow_image_candidates=allow_image_candidates,
        ))
    finally:
        asyncio.run(client.aclose())


def test_extracts_json_ld_open_graph_and_cleans_navigation(monkeypatch):
    data = {
        "@type": "NewsArticle",
        "headline": "老年人科学减重",
        "datePublished": "2026-07-15T08:52:45+08:00",
        "publisher": {"name": "新华网"},
        "image": "/json-ld-cover.png",
    }
    html = f"""<html><head>
    <meta charset="utf-8">
    <meta property="og:image" content="/cover.png">
    <link rel="canonical" href="https://www.news.cn/canonical">
    <script type="application/ld+json">{json.dumps(data, ensure_ascii=False)}</script>
    </head><body><nav><p>首页 登录 注册 客户端下载</p></nav>
    <main><h1>老年人科学减重</h1>
    <p>老年人减重应保持吃动平衡，不建议采取极端节食。</p>
    <p>如出现持续头晕、胸闷等明显不适，应及时咨询医疗机构。</p>
    <p>具体健康情况需要由专业医务人员结合个人情况判断。</p></main></body></html>"""
    result = _run_preview(monkeypatch, html)
    assert result.title == "老年人科学减重"
    assert result.source_name == "新华网"
    assert result.canonical_url == "https://www.news.cn/canonical"
    assert result.cover_image_url == "https://www.news.cn/cover.png"
    assert result.cover_image_type == "ORIGINAL_COVER"
    assert result.image_width == 1200
    assert result.image_height == 675
    assert result.image_validated is True
    assert "首页 登录" not in result.extracted_text
    assert result.content_kind == "HEALTH_EDUCATION"


def test_uses_json_ld_image_when_open_graph_is_absent(monkeypatch):
    data = {
        "@type": "NewsArticle",
        "headline": "养老服务政策解读",
        "image": {"url": "/json-cover.png"},
    }
    html = f"""<html><head><title>养老服务政策解读</title>
    <script type="application/ld+json">{json.dumps(data, ensure_ascii=False)}</script>
    </head><body><article>
    <p>有关部门发布养老服务网络建设政策，介绍城乡三级服务网络安排。</p>
    <p>本文说明政策背景和进展，不代表个人可以直接领取补贴。</p>
    <p>具体实施安排以当地政府部门公开文件为准。</p>
    </article></body></html>"""
    result = _run_preview(monkeypatch, html)
    assert result.cover_image_url.endswith("/json-cover.png")
    assert result.cover_image_type == "ORIGINAL_COVER"


def test_rejects_logo_and_uses_first_valid_article_image(monkeypatch):
    html = """<html><head><title>社区助老服务</title>
    <meta property="og:image" content="/site-logo.png"></head><body><article>
    <h1>社区助老服务</h1>
    <img src="/article.png" alt="社区工作人员为老人提供服务">
    <p>社区工作人员开展上门问询、送餐和防暑服务。</p>
    <p>这是一篇服务工作报道，原文没有提供报名方式。</p>
    <p>居民可关注属地官方渠道发布的后续服务信息。</p>
    </article></body></html>"""
    result = _run_preview(monkeypatch, html)
    assert result.cover_image_url.endswith("/article.png")
    assert result.cover_image_type == "ARTICLE_IMAGE"
    assert result.image_alt_text == "社区工作人员为老人提供服务"


def test_broken_image_falls_back_to_category_default(monkeypatch):
    html = """<html><head><title>防范养老诈骗</title>
    <meta property="og:image" content="/missing.png"></head><body><main>
    <p>陌生人要求提供验证码或转账到安全账户时，应立即停止操作。</p>
    <p>请通过官方渠道核实，不要点击陌生链接，也不要开启屏幕共享。</p>
    <p>如已发生资金损失，应及时保留证据并报警。</p>
    </main></body></html>"""
    result = _run_preview(monkeypatch, html, image_status=404)
    assert result.cover_image_url == ""
    assert result.cover_image_type == "CATEGORY_DEFAULT"
    assert result.image_validated is False
    assert result.content_kind == "ANTI_FRAUD"


def test_source_without_image_cache_permission_does_not_download_images(monkeypatch):
    requests: list[str] = []
    html = """<html><head><title>社区文化学习活动</title>
    <meta property="og:image" content="/cover.png"></head><body><main>
    <p>社区面向老年居民开展智能手机文化学习活动，帮助大家掌握常用操作。</p>
    <p>课程内容包括拍照、视频通话和常见应用的基础使用方法。</p>
    <p>活动具体安排请关注社区后续公开通知。</p>
    </main></body></html>"""
    result = _run_preview(
        monkeypatch,
        html,
        allow_image_candidates=False,
        requests=requests,
    )
    assert result.cover_image_url == ""
    assert result.cover_image_type == "CATEGORY_DEFAULT"
    assert "/cover.png" not in requests


@pytest.mark.parametrize(
    ("markup", "expected_path"),
    [
        ('<img src="/src.png" alt="主题图片">', "/src.png"),
        ('<img data-src="/data-src.png" alt="主题图片">', "/data-src.png"),
        ('<img data-original="/original.png" alt="主题图片">', "/original.png"),
        ('<img data-lazy-src="/lazy.png" alt="主题图片">', "/lazy.png"),
        ('<img data-echo="/echo.png" alt="主题图片">', "/echo.png"),
        ('<img srcset="/small.png 480w, /srcset.png 1200w" alt="主题图片">', "/small.png"),
        ('<img data-srcset="/data-srcset.png 2x" alt="主题图片">', "/data-srcset.png"),
        ('<picture><source srcset="/picture.png 1200w"></picture>', "/picture.png"),
        ('<video poster="/poster.png"></video>', "/poster.png"),
    ],
)
def test_discovers_common_lazy_picture_and_video_image_attributes(
    monkeypatch, markup, expected_path
):
    requests: list[str] = []
    html = f"""<html><head><title>公共服务文章图片测试</title></head><body><article>
    {markup}
    <p>这是一篇公开公共服务文章，用于验证通用网页图片候选发现能力和机构端人工审核流程。</p>
    <p>候选图片只供审核人员查看，未经确认不得直接发布到用户端，也不得被当作已授权缓存图片。</p>
    <p>页面结构不依赖任何特定网站选择器，可以覆盖常见延迟加载、响应式图片和视频封面写法。</p>
    </article></body></html>"""
    result = _run_preview(monkeypatch, html, requests=requests)
    assert expected_path in requests
    assert any(image.url.endswith(expected_path) for image in result.images)


def test_candidate_download_switch_is_independent_from_public_cache_permission(monkeypatch):
    requests: list[str] = []
    html = """<html><head><title>候选与缓存权限分离</title>
    <meta property="og:image" content="/candidate.png"></head><body><main>
    <p>机构端可以下载少量图片数据来验证尺寸、媒体类型和哈希，并生成等待人工审核的图片候选。</p>
    <p>这一过程不表示平台已经获得公开缓存许可，也不会把未经审核的第三方图片直接展示给用户。</p>
    <p>只有管理员确认来源和使用依据后，后续公开流程才能根据来源配置决定是否缓存图片。</p>
    </main></body></html>"""
    result = _run_preview(
        monkeypatch,
        html,
        allow_image_candidates=True,
        requests=requests,
    )
    assert "/candidate.png" in requests
    assert result.images[0].image_cached is False
    assert result.images[0].candidate_status == "VALID"


def test_article_image_records_nearby_context_and_relevance(monkeypatch):
    html = """<html><head><title>大场镇长者助餐服务开放</title></head><body><article>
    <h1>大场镇长者助餐服务开放</h1>
    <p>大场镇社区食堂为老年居民提供午餐和助餐咨询。</p>
    <figure><img src="/canteen.png" alt="长者在大场镇社区食堂用餐">
    <figcaption>社区工作人员介绍助餐服务安排</figcaption></figure>
    <p>具体开放时间和申请条件请以属地官方公告为准。</p>
    </article></body></html>"""
    result = _run_preview(monkeypatch, html)
    candidate = next(image for image in result.images if image.url.endswith("/canteen.png"))
    assert "社区食堂" in candidate.context_text
    assert candidate.relevance_score >= 20


def test_large_navigation_banner_is_not_accepted_as_article_image(monkeypatch):
    html = """<html><head><title>社区健康服务通知</title></head><body>
    <header class="navigation"><img src="/portal-banner.png" alt="网站服务导航"></header>
    <article><p>社区卫生服务中心发布健康服务通知，请居民关注属地官方安排。</p>
    <p>本文说明服务时间变化，具体事项以社区卫生服务中心公开信息为准。</p>
    <p>如有疑问请通过官方联系电话咨询，不要相信非官方收费链接。</p></article>
    </body></html>"""
    result = _run_preview(monkeypatch, html)
    assert not any(image.url.endswith("/portal-banner.png") for image in result.images)
    assert result.cover_image_type == "CATEGORY_DEFAULT"


def test_wechat_identity_hints_do_not_claim_official_status(monkeypatch):
    html = """<html><head><title>社区健康提醒</title>
    <meta name="profile_nickname" content="浦江健康服务">
    <script>var biz = "MzA-test-account";</script></head><body><main>
    <p>社区卫生服务中心发布夏季健康提醒，请居民关注高温天气和日常补水。</p>
    <p>老年人如出现持续胸闷、头晕等异常信号，应及时联系医疗机构。</p>
    <p>本文为公开健康提示，具体诊疗事项需要由专业医务人员判断。</p>
    </main></body></html>"""
    result = _run_preview(
        monkeypatch,
        html,
        url="https://mp.weixin.qq.com/s/example",
    )
    assert result.wechat_account_name == "浦江健康服务"
    assert result.wechat_biz == "MzA-test-account"
    assert result.source_name == "浦江健康服务"


def test_policy_and_health_classification_are_distinct():
    assert classify_content("养老服务网络建设政策", "民政部发布管理办法")[0] == "POLICY_NEWS"
    assert classify_content("三伏天老年人消暑", "慢病人群注意健康，出现症状及时就医")[0] == "HEALTH_EDUCATION"
    assert classify_content(
        "新田社区助老防诈课堂开讲",
        "社区开展讲座，提醒老人识别诈骗话术。",
    )[0] == "ANTI_FRAUD"


def test_fake_ip_dns_range_requires_explicit_development_opt_in(monkeypatch):
    monkeypatch.setattr(
        web_ingest.socket,
        "getaddrinfo",
        lambda *_args: [(2, 1, 6, "", ("198.18.0.24", 443))],
    )
    monkeypatch.delenv("JIANDA_ALLOW_FAKE_IP_DNS", raising=False)
    with pytest.raises(ValueError, match="保留地址"):
        asyncio.run(_assert_public_host("https://www.news.cn/article"))

    monkeypatch.setenv("JIANDA_ALLOW_FAKE_IP_DNS", "true")
    asyncio.run(_assert_public_host("https://www.news.cn/article"))
