from typing import Literal

from pydantic import BaseModel, Field


class TextRequest(BaseModel):
    text: str = Field(min_length=1)
    title: str = "未命名材料"
    document_type: Literal["guide", "public_news"] = "guide"


class ExtractedField(BaseModel):
    field_type: str
    label: str
    value: str
    page_no: int
    segment_no: int
    source_quote: str
    confidence: float = Field(ge=0, le=1)


class StepCard(BaseModel):
    order: int
    title: str
    description: str


class AnalyzeResult(BaseModel):
    fields: list[ExtractedField]
    summary: list[str]
    plain_text: str
    steps: list[StepCard]
    term_explanations: dict[str, str]
    warnings: list[str]
    audio_script: str


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

