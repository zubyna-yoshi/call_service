import unittest

from directory_service.phone import PhoneNumberError, normalize_phone_number


class PhoneNumberTests(unittest.TestCase):
    def test_normalizes_korean_office_and_mobile_numbers(self) -> None:
        self.assertEqual(normalize_phone_number("02-555-0101"), "+8225550101")
        self.assertEqual(normalize_phone_number("010 1234 5678"), "+821012345678")

    def test_accepts_international_and_double_zero_prefixes(self) -> None:
        self.assertEqual(normalize_phone_number("+82 (10) 1234-5678"), "+821012345678")
        self.assertEqual(normalize_phone_number("00821012345678"), "+821012345678")
        self.assertEqual(normalize_phone_number("+44 20 7946 0958"), "+442079460958")

    def test_rejects_country_code_followed_by_national_trunk_zero(self) -> None:
        for value in ("+82 (0) 10-1234-5678", "+8201012345678", "008201012345678"):
            with self.subTest(value=value):
                with self.assertRaisesRegex(PhoneNumberError, "trunk zero"):
                    normalize_phone_number(value)

    def test_rejects_values_that_require_guessing(self) -> None:
        for value in ("", "1012345678", "1.01E+10", "+１２３４５６７８", "+01234567"):
            with self.subTest(value=value):
                with self.assertRaises(PhoneNumberError):
                    normalize_phone_number(value)


if __name__ == "__main__":
    unittest.main()
