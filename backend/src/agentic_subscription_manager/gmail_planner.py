"""Ask the model to select an application-owned Gmail action, never raw SDK tools."""

import json

import httpx

from .adapters import OpenRouterExplanation, RemoteFailure, bounded_json
from .contracts import MODEL_ID
from .gmail_chat import GmailAction, valid_requested_service

_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "gmail_connection_status",
            "description": "Check whether the current user's Gmail is connected with Composio.",
            "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "find_gmail_subscription",
            "description": (
                "Search the current user's connected Gmail for a named subscription. "
                "Set save to true only when the user explicitly asks to add it to Services."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "service": {
                        "type": "string",
                        "description": "The service name exactly as stated by the user.",
                    },
                    "save": {"type": "boolean"},
                },
                "required": ["service", "save"],
                "additionalProperties": False,
            },
        },
    },
]


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate tool argument")
        result[key] = value
    return result


class GmailPlanner:
    def __init__(self, client: httpx.AsyncClient, key: str) -> None:
        self._client = client
        self._key = key

    async def select(self, prompt: str) -> GmailAction | None:
        request = {
            "model": MODEL_ID,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "Choose a tool only if the user requests a Gmail connection check or "
                        "a search for a specific subscription. Use the exact service name from "
                        "the user. Set save to true only when the user asks to add that "
                        "subscription to their services. Never infer a service or claim success."
                    ),
                },
                {"role": "user", "content": prompt},
            ],
            "tools": _TOOLS,
            "tool_choice": "auto",
            "parallel_tool_calls": False,
            "max_tokens": 400,
            "reasoning": {"enabled": False},
            "provider": {"allow_fallbacks": False},
        }
        try:
            async with self._client.stream(
                "POST",
                "https://openrouter.ai/api/v1/chat/completions",
                headers={"Authorization": f"Bearer {self._key}"},
                json=request,
                timeout=25,
            ) as response:
                OpenRouterExplanation._check_status(response)
                data = await bounded_json(response)
            choices = data.get("choices")
            if (
                not isinstance(choices, list)
                or len(choices) != 1
                or not isinstance(choices[0], dict)
            ):
                raise ValueError("Invalid tool selection")
            choice = choices[0]
            message = choice.get("message")
            if not isinstance(message, dict) or message.get("function_call"):
                raise ValueError("Invalid tool selection")
            calls = message.get("tool_calls")
            if choice.get("finish_reason") == "stop" and not calls:
                return None
            if (
                choice.get("finish_reason") != "tool_calls"
                or not isinstance(calls, list)
                or len(calls) != 1
            ):
                raise ValueError("Invalid tool selection")
            call = calls[0]
            if not isinstance(call, dict) or call.get("type") != "function":
                raise ValueError("Invalid tool selection")
            function = call.get("function")
            if not isinstance(function, dict):
                raise ValueError("Invalid tool selection")
            name, raw_arguments = function.get("name"), function.get("arguments")
            if not isinstance(raw_arguments, str) or len(raw_arguments) > 1024:
                raise ValueError("Invalid tool arguments")
            arguments = json.loads(raw_arguments, object_pairs_hook=_unique_object)
            if not isinstance(arguments, dict):
                raise ValueError("Invalid tool arguments")
            if name == "gmail_connection_status" and not arguments:
                return GmailAction("gmail_connection_status")
            if name == "find_gmail_subscription" and set(arguments) == {"service", "save"}:
                service, save = arguments["service"], arguments["save"]
                if valid_requested_service(prompt, service) and type(save) is bool:
                    return GmailAction("find_gmail_subscription", service, save)
            raise ValueError("Invalid tool arguments")
        except RemoteFailure:
            raise
        except httpx.TimeoutException as error:
            raise RemoteFailure(504, "AI provider timed out") from error
        except httpx.HTTPError as error:
            raise RemoteFailure(502, "AI provider unavailable") from error
        except (ValueError, TypeError, KeyError) as error:
            raise RemoteFailure(502, "Invalid AI tool request") from error
