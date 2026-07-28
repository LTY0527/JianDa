from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


SUPPORTED_FIELD_TYPES = (
    "TARGET_AUDIENCE",
    "ELIGIBILITY",
    "START_DATE",
    "END_DATE",
    "EVENT_DATE",
    "SERVICE_TIME",
    "LOCATION",
    "CONTACT",
    "FEE",
    "MATERIAL",
    "WARNING",
    "RESULT_TIME",
)
SupportedFieldType = Literal[
    "TARGET_AUDIENCE",
    "ELIGIBILITY",
    "START_DATE",
    "END_DATE",
    "EVENT_DATE",
    "SERVICE_TIME",
    "LOCATION",
    "CONTACT",
    "FEE",
    "MATERIAL",
    "WARNING",
    "RESULT_TIME",
]
ContentKind = Literal[
    "SERVICE_NOTICE",
    "HEALTH_EDUCATION",
    "POLICY_NEWS",
    "ANTI_FRAUD",
    "COMMUNITY_SERVICE",
    "GENERAL_NEWS",
]


class SourceSegment(BaseModel):
    model_config = ConfigDict(extra="forbid")

    segment_id: int
    page_no: int = Field(ge=1)
    text: str = Field(min_length=1)


class TextRequest(BaseModel):
    text: str = Field(min_length=1)
    title: str = "未命名材料"
    document_type: Literal["guide", "public_news"] = "guide"
    source_name: str = ""
    segments: list[SourceSegment] = Field(default_factory=list)
    content_sha256: str = ""
    document_id: int | None = None
    processing_job_id: int | None = None
    trace_id: str = ""
    content_kind: ContentKind | None = None
    prompt_version: Literal["v1", "v1.1", "web-v1.1"] | None = None


class RewriteOnlyRequest(TextRequest):
    fact_checkpoint: dict[str, object]


class ExtractedField(BaseModel):
    field_type: str
    label: str
    value: str
    page_no: int
    segment_no: int
    segment_id: int | None = None
    source_quote: str
    confidence: float = Field(ge=0, le=1)


class ServiceSession(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    date: str = Field(min_length=1)
    time: str = Field(min_length=1)
    location: str = Field(min_length=1)
    source_quote: str = Field(min_length=1)
    page_no: int = Field(ge=1)
    segment_id: int
    needs_human_review: bool = False

    @field_validator("date", "time", "location", "source_quote")
    @classmethod
    def strip_session_text(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("must not be blank")
        return stripped


class TraceableItem(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    source_quote: str = Field(min_length=1)
    page_no: int = Field(ge=1)
    segment_id: int
    needs_human_review: bool = False


class AudienceItem(TraceableItem):
    value: str = Field(min_length=1)


class AudienceRules(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    audience: list[AudienceItem] = Field(default_factory=list)
    conditions: list[AudienceItem] = Field(default_factory=list)


class ServiceWindow(TraceableItem):
    days: list[str] = Field(default_factory=list)
    dates: list[str] = Field(default_factory=list)
    time_ranges: list[str] = Field(default_factory=list)
    location: str | None = None
    unavailable_note: str | None = None


class ClosureRule(TraceableItem):
    value: str = Field(min_length=1)


class ServiceSchedule(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    service_windows: list[ServiceWindow] = Field(default_factory=list)
    closure_rules: list[ClosureRule] = Field(default_factory=list)


class ConditionalMaterial(TraceableItem):
    applicable_to: str = Field(min_length=1)
    required: list[str] = Field(default_factory=list)
    optional: list[str] = Field(default_factory=list)


class FeeRule(TraceableItem):
    fee_type: str = Field(min_length=1)
    amount: str | None = None
    rule: str | None = None
    payment_methods: list[str] = Field(default_factory=list)


class ResultDelivery(TraceableItem):
    method: str = Field(min_length=1)
    optional: bool = False
    available_after: str | None = None
    location: str | None = None
    fee_rule: str | None = None


class DeadlineRule(TraceableItem):
    rule_type: Literal[
        "FIXED_DATE",
        "RELATIVE_PERIOD",
        "CAPACITY_LIMIT",
        "NO_FIXED_DATE",
        "CHANNEL_SPECIFIC",
    ]
    value: str = Field(min_length=1)
    channel: str | None = None


class Amendment(TraceableItem):
    original_information: str = Field(min_length=1)
    corrected_information: str = Field(min_length=1)
    effective_priority: str = Field(min_length=1)
    supersedes: list[str] = Field(default_factory=list)


class ProcessingMetrics(BaseModel):
    schema_version: str = "1.1"
    cache_hit: bool = False
    text_extract_ms: int = 0
    fact_extract_ms: int = 0
    trace_validation_ms: int = 0
    accessible_rewrite_ms: int = 0
    persistence_ms: int = 0
    total_ms: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    source_char_count: int = 0
    accessible_char_count: int = 0
    summary_compression_ratio: float = 0
    key_fact_count: int = 0
    action_item_count: int = 0
    trace_pass_rate: float = 0
    hallucinated_field_count: int = 0
    markdown_residue_count: int = 0


class StepCard(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    order: int = Field(ge=1)
    title: str = Field(min_length=1)
    description: str = Field(min_length=1)


class ActionChecklistItem(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    action: str = Field(min_length=1)
    priority: Literal["立即", "近期", "了解即可"]
    source_quote: str = Field(min_length=1)
    segment_id: int


class KeyFactItem(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    label: str = Field(min_length=1)
    value: str = Field(min_length=1)
    source_quote: str = Field(min_length=1)
    segment_id: int


class FaqItem(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    question: str = Field(min_length=1)
    answer: str = Field(min_length=1)
    source_quote: str = Field(min_length=1)
    segment_id: int | None = None


class ContentScope(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    national_or_local: Literal["全国", "地方", "具体机构", "原文未说明"]
    applicable_region: str | None = None
    needs_personal_action: bool | None = None


class AnalyzeResult(BaseModel):
    fields: list[ExtractedField]
    sessions: list[ServiceSession] = Field(default_factory=list)
    summary: list[str]
    plain_text: str
    steps: list[StepCard]
    term_explanations: dict[str, str]
    warnings: list[str]
    audio_script: str
    audience_rules: AudienceRules = Field(default_factory=AudienceRules)
    service_schedule: ServiceSchedule = Field(default_factory=ServiceSchedule)
    conditional_materials: list[ConditionalMaterial] = Field(default_factory=list)
    fees: list[FeeRule] = Field(default_factory=list)
    result_delivery: list[ResultDelivery] = Field(default_factory=list)
    deadline_rules: list[DeadlineRule] = Field(default_factory=list)
    amendments: list[Amendment] = Field(default_factory=list)
    quick_summary: list[str] = Field(default_factory=list)
    why_it_matters: list[str] = Field(default_factory=list)
    action_checklist: list[ActionChecklistItem] = Field(default_factory=list)
    key_facts: list[KeyFactItem] = Field(default_factory=list)
    common_mistakes: list[str] = Field(default_factory=list)
    faq: list[FaqItem] = Field(default_factory=list)
    scope: ContentScope | None = None
    uncertainties: list[str] = Field(default_factory=list)
    metrics: ProcessingMetrics = Field(default_factory=ProcessingMetrics)


class FactField(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    field_type: SupportedFieldType
    label: str = Field(min_length=1)
    value: str = Field(min_length=1)
    source_quote: str = Field(min_length=1)
    page_no: int = Field(ge=1)
    segment_id: int
    confidence: float = Field(ge=0, le=1)
    needs_human_review: bool = False

    @field_validator("label", "value", "source_quote")
    @classmethod
    def strip_non_empty(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("must not be blank")
        return stripped


class FactExtractionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    prompt_version: str = Field(min_length=1)
    fields: list[FactField]
    sessions: list[ServiceSession] = Field(default_factory=list)
    audience_rules: AudienceRules = Field(default_factory=AudienceRules)
    service_schedule: ServiceSchedule = Field(default_factory=ServiceSchedule)
    conditional_materials: list[ConditionalMaterial] = Field(default_factory=list)
    fees: list[FeeRule] = Field(default_factory=list)
    result_delivery: list[ResultDelivery] = Field(default_factory=list)
    deadline_rules: list[DeadlineRule] = Field(default_factory=list)
    amendments: list[Amendment] = Field(default_factory=list)


class RewriteResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    prompt_version: str = Field(min_length=1)
    summary: list[str] = Field(min_length=1)
    plain_text: str = Field(min_length=1)
    steps: list[StepCard]
    warnings: list[str]
    term_explanations: dict[str, str]
    audio_script: str = Field(min_length=1)
    quick_summary: list[str] = Field(default_factory=list)
    why_it_matters: list[str] = Field(default_factory=list)
    action_checklist: list[ActionChecklistItem] = Field(default_factory=list)
    key_facts: list[KeyFactItem] = Field(default_factory=list)
    common_mistakes: list[str] = Field(default_factory=list)
    faq: list[FaqItem] = Field(default_factory=list)
    terms: dict[str, str] = Field(default_factory=dict)
    scope: ContentScope | None = None
    uncertainties: list[str] = Field(default_factory=list)

    @field_validator("plain_text", "audio_script")
    @classmethod
    def strip_generated_text(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("must not be blank")
        return stripped

    @field_validator("summary")
    @classmethod
    def validate_summary(cls, value: list[str]) -> list[str]:
        if any(not item.strip() for item in value):
            raise ValueError("summary items must not be blank")
        return [item.strip() for item in value]


class Segment(BaseModel):
    page_no: int
    segment_no: int
    text: str
    start_offset: int
    end_offset: int


class ExtractTextResult(BaseModel):
    text: str
    page_count: int
    segments: list[Segment]
    extraction_method: str


class MetadataPreview(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str
    source_name: str
    document_number: str = ""
    source_type: str = ""
    authority_status: Literal["DOCUMENT_EVIDENCE", "UNCONFIRMED", "CONFLICT"]
    confidence: float = Field(ge=0, le=1)
    evidence_quote: str = ""
    evidence_type: Literal[
        "HEADER", "SIGNATURE", "SEAL", "PUBLISHER_FIELD", "FILENAME", "NONE"
    ]
    page_no: int = Field(ge=1)
    warnings: list[str] = Field(default_factory=list)


class WebArticleRequest(BaseModel):
    url: str = Field(min_length=8, max_length=1500)
    allow_image_download: bool = False


class WebArticleImage(BaseModel):
    url: str
    caption: str = ""


class WebArticlePreview(BaseModel):
    title: str
    source_name: str = ""
    published_at: datetime | None = None
    author: str = ""
    cover_image_url: str = ""
    cover_image_type: Literal[
        "ORIGINAL_COVER", "ARTICLE_IMAGE", "CATEGORY_DEFAULT", "AI_ILLUSTRATION"
    ] = "CATEGORY_DEFAULT"
    image_alt_text: str = ""
    image_width: int | None = None
    image_height: int | None = None
    image_hash: str = ""
    image_validated: bool = False
    canonical_url: str
    content_preview: str
    extracted_text: str
    original_html: str
    content_hash: str
    content_kind: ContentKind
    classification_confidence: float = Field(ge=0, le=1)
    robots_allowed: bool
    robots_status: str
    original_page_available: bool = True
    images: list[WebArticleImage] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
