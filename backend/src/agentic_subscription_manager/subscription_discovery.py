"""Conservative extraction of recurring charges from recent Gmail messages."""

import base64
import re
from collections.abc import Mapping
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal, InvalidOperation
from email.header import decode_header, make_header
from email.utils import parseaddr, parsedate_to_datetime
from html import unescape
from html.parser import HTMLParser

MAX_EMAIL_AGE = timedelta(days=90)
MAX_MESSAGE_TEXT = 20000
MAX_RECEIPTS = 100

_MONTHLY = re.compile(
    r"\b(?:monthly|per\s+month|every\s+month|each\s+month|month[- ]to[- ]month)\b", re.I
)
_YEARLY = re.compile(
    r"\b(?:annual(?:ly)?|yearly|per\s+year|every\s+year|each\s+year|12[- ]month(?:s)?)\b",
    re.I,
)
_TRANSACTION = re.compile(
    r"\b(?:you\s+(?:were|have\s+been)\s+charged|we\s+charged|was\s+charged|were\s+charged|"
    r"has\s+been\s+charged|payment\s+(?:was\s+)?(?:received|processed|confirmed)|"
    r"(?:we|you)\s+paid|paid\s+(?:on|for)|invoice\s+paid|receipt\s+for|"
    r"charged\s+(?:you|your)|billed\s+(?:you|your))\b",
    re.I,
)
_OFFER_LANGUAGE = re.compile(
    r"\b(?:special\s+offer|promotional?|promo\s+code|discount|limited[- ]time|"
    r"as\s+low\s+as|starting\s+at|plans?\s+available|pricing\s+page)\b",
    re.I,
)
_INACTIVE_OR_UNPAID = re.compile(
    r"\b(?:cancel(?:led|ed|ation)|terminat(?:ed|ion)|expired|refund(?:ed)?|reversed|"
    r"voided|chargeback|trial|payment\s+(?:was\s+)?(?:failed|declined|unsuccessful)|"
    r"(?:failed|declined|unsuccessful)\s+payment|past[- ]due)\b",
    re.I,
)
_ISO_DATE = r"\d{4}-\d{2}-\d{2}"
_TEXT_DATE = (
    r"(?:January|February|March|April|May|June|July|August|September|October|November|December)"
    r"\s+\d{1,2},?\s+\d{4}|\d{1,2}\s+"
    r"(?:January|February|March|April|May|June|July|August|September|October|November|December)"
    r"\s+\d{4}"
)
_DATE_SEARCH = re.compile(rf"(?<!\d)(?:{_ISO_DATE}|{_TEXT_DATE})(?!\d)", re.I)
_RENEWAL_MARKERS = (
    re.compile(
        r"\b(?:next\s+(?:billing|payment|renewal|charge)\s+date|renewal\s+date)\b"
        r"\s*(?:is|:|-)?",
        re.I,
    ),
    re.compile(r"\b(?:will\s+renew|renews)\s+(?:automatically\s+)?on\b", re.I),
    re.compile(r"\bnext\s+(?:billing|payment|charge)\s+on\b", re.I),
)
_AMOUNT = r"(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,10})(?:\.[0-9]{1,2})?"
_CURRENCIES = "USD|ZAR|EUR|GBP|CAD|AUD|NZD|CHF|JPY|CNY|INR|SGD|HKD|SEK|NOK|DKK|BRL|MXN|AED"
_PRICE = re.compile(
    rf"(?<![A-Za-z])(?P<before>{_CURRENCIES})\s*(?P<before_amount>{_AMOUNT})(?![A-Za-z0-9.,])"
    rf"|(?<![A-Za-z0-9.,])(?P<after_amount>{_AMOUNT})\s*(?P<after>{_CURRENCIES})(?![A-Za-z])"
    rf"|(?<![A-Za-z])(?P<symbol>R|€|£)\s*(?P<symbol_amount>{_AMOUNT})(?![A-Za-z0-9.,])",
    re.I,
)
_SYMBOL_CURRENCIES = {"R": "ZAR", "€": "EUR", "£": "GBP"}
_WEBMAIL_DOMAINS = {"gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "yahoo.com"}
_PROCESSOR_DOMAINS = {
    "adyen.com",
    "braintreegateway.com",
    "checkout.com",
    "fastspring.com",
    "paypal.com",
    "paddle.com",
    "recurly.com",
    "squareup.com",
    "stripe.com",
}
_PROCESSOR_NAMES = {
    "adyen",
    "braintree",
    "checkout",
    "fastspring",
    "paypal",
    "paddle",
    "recurly",
    "square",
    "stripe",
}
_GENERIC_NAMES = {
    "account",
    "billing",
    "customer service",
    "do not reply",
    "mail delivery subsystem",
    "no reply",
    "noreply",
    "notification",
    "notifications",
    "payment",
    "payments",
    "receipt",
    "receipts",
    "support",
    "team",
}
_GENERIC_SUFFIX = re.compile(
    r"\s+(?:billing|customer support|payments?|receipts?|support|notifications?)$", re.I
)
_HTML_TAG = re.compile(r"<[^>]{0,500}>")
_VALID_SERVICE = re.compile(r"^[\w][\w &'.+()/-]{0,149}$", re.UNICODE)
_VALID_MESSAGE_ID = re.compile(r"^[A-Za-z0-9_-]{1,256}$")


class _TextOnly(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self._ignored = 0

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag.casefold() in {"script", "style"}:
            self._ignored += 1
        elif tag.casefold() in {"br", "p", "div", "li", "tr"} and self._ignored == 0:
            self.parts.append(" ")

    def handle_endtag(self, tag: str) -> None:
        if tag.casefold() in {"script", "style"} and self._ignored:
            self._ignored -= 1
        elif tag.casefold() in {"br", "p", "div", "li", "tr"} and self._ignored == 0:
            self.parts.append(" ")

    def handle_data(self, data: str) -> None:
        if self._ignored == 0:
            self.parts.append(data)


def _as_text(value: object) -> str:
    return value if isinstance(value, str) and len(value) <= 100000 else ""


def _decode_body_data(value: object) -> str:
    encoded = _as_text(value)
    if not encoded or len(encoded) > 30000:
        return ""
    try:
        raw = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        return raw.decode("utf-8", errors="replace")[:MAX_MESSAGE_TEXT]
    except (ValueError, base64.binascii.Error):
        return ""


def _payload_text(payload: object, depth: int = 0) -> list[tuple[str, str]]:
    if depth > 5 or not isinstance(payload, Mapping):
        return []
    mime = _as_text(payload.get("mimeType", payload.get("mime_type"))).casefold()
    body = payload.get("body")
    parts: list[tuple[str, str]] = []
    if isinstance(body, Mapping):
        text = _decode_body_data(body.get("data"))
        if text:
            parts.append((mime, text))
    nested = payload.get("parts")
    if isinstance(nested, list):
        for part in nested[:40]:
            parts.extend(_payload_text(part, depth + 1))
    return parts


def _html_to_text(value: str) -> str:
    parser = _TextOnly()
    try:
        parser.feed(value[:MAX_MESSAGE_TEXT])
        parser.close()
        return unescape(" ".join(parser.parts))[:MAX_MESSAGE_TEXT]
    except Exception:
        return unescape(_HTML_TAG.sub(" ", value[:MAX_MESSAGE_TEXT]))


def _message_text(message: Mapping) -> str:
    direct = []
    for key in ("textBody", "text_body", "messageText", "message_text", "body"):
        value = message.get(key)
        if isinstance(value, str) and value.strip():
            direct.append(("text/plain", value[:MAX_MESSAGE_TEXT]))
    payload = message.get("payload")
    direct.extend(_payload_text(payload))
    plain = [text for mime, text in direct if mime in ("text/plain", "")]
    html = [text for mime, text in direct if mime == "text/html"]
    selected = "\n".join(plain if plain else html)
    if html and not plain:
        selected = _html_to_text(selected)
    return selected[:MAX_MESSAGE_TEXT]


def _headers(message: Mapping) -> dict[str, str]:
    result: dict[str, str] = {}
    headers = message.get("headers")
    if not isinstance(headers, list) and isinstance(message.get("payload"), Mapping):
        headers = message["payload"].get("headers")
    if isinstance(headers, list):
        for header in headers[:100]:
            if isinstance(header, Mapping):
                name = _as_text(header.get("name")).casefold()
                value = _as_text(header.get("value"))
                if name and value:
                    result[name] = value
    return result


def _message_sender(message: Mapping, headers: Mapping[str, str]) -> str:
    return (
        _as_text(message.get("sender")) or _as_text(message.get("from")) or headers.get("from", "")
    )


def _message_date(message: Mapping, headers: Mapping[str, str]) -> datetime | None:
    raw_timestamp = _as_text(message.get("messageTimestamp"))
    if raw_timestamp:
        normalized_timestamp = (
            f"{raw_timestamp[:-1]}+00:00" if raw_timestamp.endswith("Z") else raw_timestamp
        )
        try:
            parsed_timestamp = datetime.fromisoformat(normalized_timestamp)
            if parsed_timestamp.tzinfo is not None:
                return parsed_timestamp.astimezone(UTC)
        except (TypeError, ValueError, OverflowError):
            pass

    internal_date = message.get("internalDate", message.get("internal_date"))
    if isinstance(internal_date, (int, str)) and not isinstance(internal_date, bool):
        try:
            milliseconds = int(internal_date)
            if 0 < milliseconds <= 253402300799999:
                return datetime.fromtimestamp(milliseconds / 1000, UTC)
        except (ValueError, OverflowError, OSError):
            pass
    raw = _as_text(message.get("date")) or headers.get("date", "")
    try:
        parsed = parsedate_to_datetime(raw)
        if parsed.tzinfo is None:
            return None
        return parsed.astimezone(UTC)
    except (TypeError, ValueError, OverflowError):
        return None


def _service_name(sender: str) -> str | None:
    decoded_sender = sender
    try:
        decoded_sender = str(make_header(decode_header(sender)))
    except (LookupError, UnicodeError, ValueError):
        pass
    display, address = parseaddr(decoded_sender)
    display = unescape(display).strip().strip("\"'")
    display = re.sub(r"\s+", " ", _GENERIC_SUFFIX.sub("", display)).strip(" -|:")
    _, separator, domain = address.rpartition("@")
    domain = domain.casefold().rstrip(".")
    processor_domain = any(
        domain == processor or domain.endswith(f".{processor}") for processor in _PROCESSOR_DOMAINS
    )
    if processor_domain:
        display = re.sub(
            r"\s+(?:via|through|by)\s+(?:" + "|".join(sorted(_PROCESSOR_NAMES)) + r")$",
            "",
            display,
            flags=re.I,
        ).strip()
        normalized_display = display.casefold()
        if (
            normalized_display in _GENERIC_NAMES
            or normalized_display in _PROCESSOR_NAMES
            or not _VALID_SERVICE.fullmatch(display)
        ):
            return None
        return display[:150]
    normalized_display = display.casefold()
    if (
        normalized_display not in _GENERIC_NAMES
        and normalized_display not in _PROCESSOR_NAMES
        and _VALID_SERVICE.fullmatch(display)
    ):
        return display[:150]

    if not separator or not domain or domain in _WEBMAIL_DOMAINS:
        return None
    labels = domain.split(".")
    if len(labels) < 2 or any(not label for label in labels):
        return None
    public_suffix = ".".join(labels[-2:])
    country_suffixes = {
        "co.au",
        "co.in",
        "co.jp",
        "co.nz",
        "co.uk",
        "co.za",
        "com.au",
        "com.br",
        "com.cn",
        "com.hk",
        "com.sg",
    }
    index = -3 if public_suffix in country_suffixes else -2
    if len(labels) < abs(index):
        return None
    label = labels[index]
    if label in {"googlemail", "gmail", "hotmail", "outlook", "yahoo"}:
        return None
    label = re.sub(r"[^a-z0-9&+.-]", "", label).strip(".-")
    if not label:
        return None
    name = label.replace("-", " ").replace("_", " ").title()
    return (
        name if _VALID_SERVICE.fullmatch(name) and name.casefold() not in _GENERIC_NAMES else None
    )


def _parse_date(value: str) -> date | None:
    normalized = value.strip().replace(",", "")
    try:
        if re.fullmatch(_ISO_DATE, normalized):
            return date.fromisoformat(normalized)
        for pattern in ("%B %d %Y", "%d %B %Y"):
            try:
                return datetime.strptime(normalized, pattern).date()
            except ValueError:
                continue
    except (TypeError, ValueError):
        return None
    return None


def _renewal_date(text: str) -> date | None:
    dates = set()
    for marker in _RENEWAL_MARKERS:
        for match in marker.finditer(text):
            nearby = text[match.end() : match.end() + 100]
            date_match = _DATE_SEARCH.search(nearby)
            if date_match:
                value = _parse_date(date_match.group(0))
                if value:
                    dates.add(value)
    return next(iter(dates)) if len(dates) == 1 else None


def _price(text: str) -> tuple[str, str] | None:
    candidates: set[tuple[str, str]] = set()
    cycle_matches = list(_MONTHLY.finditer(text)) + list(_YEARLY.finditer(text))
    if not cycle_matches or bool(_MONTHLY.search(text)) == bool(_YEARLY.search(text)):
        return None
    transaction_evidence = list(_TRANSACTION.finditer(text))
    if not transaction_evidence:
        return None
    for match in _PRICE.finditer(text):
        if match.group("before"):
            code, raw_amount = match.group("before").upper(), match.group("before_amount")
        elif match.group("after"):
            code, raw_amount = match.group("after").upper(), match.group("after_amount")
        else:
            symbol = match.group("symbol")
            code = _SYMBOL_CURRENCIES[symbol.upper()]
            raw_amount = match.group("symbol_amount")
        start, end = match.span()
        nearby_start, nearby_end = max(0, start - 120), min(len(text), end + 120)
        nearby = text[nearby_start:nearby_end]
        cycle_nearby = _MONTHLY.search(nearby) if _MONTHLY.search(text) else _YEARLY.search(nearby)
        if cycle_nearby is None or not any(
            nearby_start <= evidence.start() <= nearby_end for evidence in transaction_evidence
        ):
            continue
        try:
            amount = Decimal(raw_amount.replace(",", ""))
        except InvalidOperation:
            continue
        if not amount.is_finite() or amount <= 0 or amount > Decimal("9999999999.99"):
            continue
        candidates.add((code, f"{amount:.2f}"))
    return next(iter(candidates)) if len(candidates) == 1 else None


def _extract(message: object, now: datetime) -> tuple[dict, datetime] | None:
    if not isinstance(message, Mapping):
        return None
    message_id = message.get("id", message.get("messageId", message.get("message_id")))
    if not isinstance(message_id, str) or not _VALID_MESSAGE_ID.fullmatch(message_id):
        return None
    if any(
        isinstance(message.get(key), str) and message[key] != message_id
        for key in ("messageId", "message_id")
    ):
        return None

    headers = _headers(message)
    received_at = _message_date(message, headers)
    if received_at is None or received_at > now + timedelta(minutes=5):
        return None
    if now - received_at > MAX_EMAIL_AGE:
        return None

    body = _message_text(message)
    if not body or len(body) > MAX_MESSAGE_TEXT:
        return None
    subject = _as_text(message.get("subject")) or headers.get("subject", "")
    sender = _message_sender(message, headers)
    service = _service_name(sender)
    if not service:
        return None
    text = f"{subject}\n{body}"
    if _INACTIVE_OR_UNPAID.search(text) or _OFFER_LANGUAGE.search(text):
        return None
    cycle_matches = bool(_MONTHLY.search(text)), bool(_YEARLY.search(text))
    if cycle_matches == (True, False):
        cycle = "MONTHLY"
        max_renewal = received_at.date() + timedelta(days=40)
    elif cycle_matches == (False, True):
        cycle = "YEARLY"
        max_renewal = received_at.date() + timedelta(days=370)
    else:
        return None
    amount = _price(text)
    renewal = _renewal_date(text)
    if amount is None or renewal is None:
        return None
    if renewal <= now.date() or renewal > max_renewal:
        return None
    code, price = amount
    source_url = f"https://mail.google.com/mail/u/0/#all/{message_id}"
    if len(source_url) > 2048:
        return None
    receipt = {
        "source_id": message_id,
        "service_name": service,
        "price": price,
        "currency": code,
        "billing_cycle": cycle,
        "renewal_date": renewal.isoformat(),
        "source_url": source_url,
    }
    return receipt, received_at


def discover_subscriptions(messages: list[object], now: datetime | None = None) -> list[dict]:
    """Return one freshest, validated receipt per service from a bounded message page."""
    if not isinstance(messages, list) or len(messages) > MAX_RECEIPTS:
        raise ValueError("Invalid Gmail message page")
    current = (datetime.now(UTC) if now is None else now).astimezone(UTC)
    freshest: dict[tuple[str, str, str], tuple[datetime, dict]] = {}
    inactive_at: dict[str, datetime] = {}
    for message in messages:
        if isinstance(message, Mapping):
            headers = _headers(message)
            received_at = _message_date(message, headers)
            sender = _message_sender(message, headers)
            service = _service_name(sender)
            body = _message_text(message)
            subject = _as_text(message.get("subject")) or headers.get("subject", "")
            if (
                service
                and received_at is not None
                and current - MAX_EMAIL_AGE <= received_at <= current + timedelta(minutes=5)
                and _INACTIVE_OR_UNPAID.search(f"{subject}\n{body}")
            ):
                key = " ".join(service.casefold().split())
                inactive_at[key] = max(received_at, inactive_at.get(key, received_at))
        result = _extract(message, current)
        if result is None:
            continue
        receipt, received_at = result
        service_key = " ".join(receipt["service_name"].casefold().split())
        key = (service_key, receipt["currency"], receipt["billing_cycle"])
        previous = freshest.get(key)
        if previous is None or received_at > previous[0]:
            freshest[key] = received_at, receipt
    eligible = [
        item
        for (service, _, _), item in freshest.items()
        if inactive_at.get(service) is None or inactive_at[service] < item[0]
    ]
    return [item[1] for item in sorted(eligible, key=lambda item: item[0], reverse=True)]
