"""The bounded HTTPS contract shared with the Android ChatClient."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

MODEL_ID = "nvidia/nemotron-3.5-lightning:free"


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    prompt: str = Field(min_length=1, max_length=4000)
    model: Literal["nvidia/nemotron-3.5-lightning:free"]
    data_mode: Literal["synthetic"]
    conversation_id: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_-]{1,128}$")

    @field_validator("prompt")
    @classmethod
    def strip_prompt(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Question required")
        return value.strip()


class ChatReply(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    answer: str = Field(min_length=1, max_length=16000)
    conversation_id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,128}$")
