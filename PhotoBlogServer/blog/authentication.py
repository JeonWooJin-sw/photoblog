from rest_framework.authentication import TokenAuthentication


class JWTKeywordTokenAuthentication(TokenAuthentication):
    """
    수업 자료(curl, test_api.py)와 안드로이드 예제는 헤더를
    "Authorization: JWT <토큰>" 으로 보낸다.
    DRF 기본 TokenAuthentication은 "Token" 키워드만 인식해서
    JWT로 보내면 인증이 그냥 무시되고 있었음.

    진짜 JWT는 아니고, /api-token-auth/ 에서 받은 DRF 토큰을
    그대로 쓰면서 키워드만 JWT로 맞춰준 것.
    """
    keyword = 'JWT'
