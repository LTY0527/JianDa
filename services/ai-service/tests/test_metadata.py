from app.metadata import clean_filename_title, detect_metadata_text


def test_filename_title_cleanup():
    assert (
        clean_filename_title("简达_模拟材料4_社区流感疫苗接种登记说明.pdf")
        == "社区流感疫苗接种登记说明"
    )


def test_material_three_metadata_uses_document_header():
    preview = detect_metadata_text(
        "南江区人民医院\n国庆假期部分专家门诊预约调整告知\n南医门诊〔2026〕27号",
        "简达_模拟材料3_医院门诊预约调整告知.pdf",
    )
    assert preview.title == "国庆假期部分专家门诊预约调整告知"
    assert preview.source_name == "南江区人民医院"
    assert preview.authority_status == "DOCUMENT_EVIDENCE"
    assert preview.evidence_type == "HEADER"


def test_material_four_metadata_and_document_number():
    preview = detect_metadata_text(
        "海棠街道社区卫生服务中心\n秋冬季流感疫苗集中接种登记说明\n海卫预防〔2026〕09号",
        "简达_模拟材料4_社区流感疫苗接种登记说明.pdf",
    )
    assert preview.title == "秋冬季流感疫苗集中接种登记说明"
    assert preview.source_name == "海棠街道社区卫生服务中心"
    assert preview.document_number == "海卫预防〔2026〕09号"
    assert preview.source_type == "基层医疗卫生机构"


def test_missing_and_conflicting_publishers_are_not_claimed_as_verified():
    missing = detect_metadata_text("普通活动说明\n请按现场安排参加。", "普通材料.pdf")
    assert missing.authority_status == "UNCONFIRMED"
    assert missing.source_name == ""

    conflict = detect_metadata_text(
        "甲街道社区卫生服务中心\n联合活动通知\n乙街道社区卫生服务中心",
        "联合活动通知.pdf",
    )
    assert conflict.authority_status == "CONFLICT"
    assert conflict.source_name == ""
