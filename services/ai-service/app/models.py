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


class StepCard(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    order: int = Field(ge=1)
    title: str = Field(min_length=1)
    description: str = Field(min_length=1)


class AnalyzeResult(BaseModel):
    fields: list[ExtractedField]
    sessions: list[ServiceSession] = Field(default_factory=list)
    summary: list[str]
    plain_text: str
    steps: list[StepCard]
    term_explanations: dict[str, str]
    warnings: list[str]
    audio_script: str


class FactField(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    field_type: SupportedFieldType
    label: str = Field(min_length=1)
    value: str = Field(min_length=1)
    source_quote: str = Field(min_length=1)
    page_no: int = Field(ge=1)
    segment_id: int
    confidence: float = Field(ge=0, le=1)

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


class RewriteResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    prompt_version: str = Field(min_length=1)
    summary: list[str] = Field(min_length=1)
    plain_text: str = Field(min_length=1)
    steps: list[StepCard]
    warnings: list[str]
    term_explanations: dict[str, str]
    audio_script: str = Field(min_length=1)

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
