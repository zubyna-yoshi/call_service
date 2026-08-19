from __future__ import annotations


class PhoneNumberError(ValueError):
    """Raised when a phone number cannot be normalized without guessing."""


_SEPARATORS = frozenset(" -().\t\r\n")


def normalize_phone_number(raw_value: str, country_calling_code: str = "82") -> str:
    """Normalize a Korean/local or international number to a canonical E.164 key.

    The function deliberately rejects ambiguous values such as an Excel scientific
    notation number or a local number whose leading zero was lost.
    """

    country_code = country_calling_code.strip().removeprefix("+")
    if not _is_ascii_digits(country_code) or not 1 <= len(country_code) <= 3:
        raise PhoneNumberError("country calling code must contain 1 to 3 ASCII digits")
    if country_code.startswith("0"):
        raise PhoneNumberError("country calling code cannot start with zero")

    value = str(raw_value).strip()
    if value[:4].lower() == "tel:":
        value = value[4:]
    if not value:
        raise PhoneNumberError("phone number is empty")

    compact_characters: list[str] = []
    for index, character in enumerate(value):
        if "0" <= character <= "9":
            compact_characters.append(character)
        elif character == "+" and index == 0:
            compact_characters.append(character)
        elif character in _SEPARATORS:
            continue
        else:
            raise PhoneNumberError("phone number contains an unsupported character")

    compact = "".join(compact_characters)
    if compact.startswith("+"):
        canonical = compact
    elif compact.startswith("00"):
        canonical = "+" + compact[2:]
    elif compact.startswith("0"):
        canonical = f"+{country_code}{compact[1:]}"
    elif compact.startswith(country_code):
        canonical = "+" + compact
    else:
        raise PhoneNumberError(
            "local phone number must retain its leading zero or include a country code"
        )

    digits = canonical[1:]
    if not _is_ascii_digits(digits) or not 7 <= len(digits) <= 15:
        raise PhoneNumberError("normalized phone number must contain 7 to 15 digits")
    if digits.startswith("0"):
        raise PhoneNumberError("international phone number cannot start with zero")
    if digits.startswith(f"{country_code}0"):
        raise PhoneNumberError(
            "international number cannot retain the national trunk zero after its country code"
        )
    return canonical


def _is_ascii_digits(value: str) -> bool:
    return bool(value) and all("0" <= character <= "9" for character in value)
