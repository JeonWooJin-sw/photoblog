"""
API 검증 엔진 테스트

1) validate_post / parse_datetime 단위 테스트
2) 실제 Django 테스트 서버(LiveServerTestCase)를 띄워서 엔진 전체 시나리오 실행
3) 가짜 서버 응답(FakeSession)으로 서버가 잘못 응답하는 경우를 엔진이 잡아내는지 확인
"""
import contextlib
import io
import os
import runpy
import shutil
import tempfile
from unittest import mock

import requests
from django.conf import settings
from django.contrib.auth.models import User
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import LiveServerTestCase, SimpleTestCase, override_settings
from PIL import Image

from blog.models import Post

from .engine import FAIL, PASS, SKIP, ApiChecker, PhotoBlogAPI, main, parse_datetime, validate_post

TEMP_MEDIA_ROOT = tempfile.mkdtemp()
FAST_HASHER = ['django.contrib.auth.hashers.MD5PasswordHasher']

VALID_POST = {
    'id': 1, 'author': 1, 'title': '제목', 'text': '내용',
    'created_date': '2026-10-13T15:00:00+09:00',
    'published_date': '2026-10-13T15:00:00+09:00',
    'image': 'http://fake/media/images/pig.png',
}


def tearDownModule():
    shutil.rmtree(TEMP_MEDIA_ROOT, ignore_errors=True)


def png_bytes():
    buffer = io.BytesIO()
    Image.new('RGB', (16, 16), (255, 148, 0)).save(buffer, 'PNG')
    return buffer.getvalue()


def statuses(results):
    return [r.status for r in results]


# ------------------------------------------------------------------
# 1) 단위 테스트
# ------------------------------------------------------------------
class ValidatePostTests(SimpleTestCase):

    def test_valid_post(self):
        self.assertEqual(validate_post(VALID_POST), [])

    def test_unpublished_post_is_valid(self):
        self.assertEqual(validate_post({**VALID_POST, 'published_date': None}), [])

    def test_not_a_dict(self):
        self.assertEqual(validate_post(['x']), ['게시물이 JSON 객체가 아님'])

    def test_missing_fields(self):
        post = dict(VALID_POST)
        del post['id']
        del post['image']
        self.assertEqual(validate_post(post), ['id 필드 없음', 'image 필드 없음'])

    def test_wrong_types(self):
        post = {
            'id': '1', 'author': None, 'title': '  ', 'text': 3,
            'created_date': '어제', 'published_date': '2026/10/13', 'image': '/media/a.png',
        }
        self.assertEqual(validate_post(post), [
            'id가 정수가 아님',
            'author가 사용자 id(정수)가 아님',
            'title이 비어 있음',
            'text가 문자열이 아님',
            'created_date 날짜 형식 오류',
            'published_date 날짜 형식 오류',
            'image가 절대 URL이 아님',
        ])

    def test_parse_datetime(self):
        self.assertEqual(parse_datetime('2026-10-13T06:00:00Z').hour, 6)
        self.assertIsNotNone(parse_datetime('2026-10-13T15:00:00.123456+09:00'))
        self.assertIsNone(parse_datetime('2026-13-45'))
        self.assertIsNone(parse_datetime(20261013))


# ------------------------------------------------------------------
# 2) 실제 서버 대상 테스트
# ------------------------------------------------------------------
@override_settings(MEDIA_ROOT=TEMP_MEDIA_ROOT, PASSWORD_HASHERS=FAST_HASHER)
class LiveServerEngineTests(LiveServerTestCase):

    def setUp(self):
        self.user = User.objects.create_user('tester', password='pw-1234')
        Post.objects.create(author=self.user, title='기존 글', text='이미 있던 글',
                            published_date='2026-10-01T12:00:00+09:00',
                            image=SimpleUploadedFile('old.png', png_bytes(), content_type='image/png'))
        # 이미지 없이 올리면 쓰는 기본 이미지도 임시 media 폴더에 복사
        os.makedirs(os.path.join(TEMP_MEDIA_ROOT, 'images'), exist_ok=True)
        shutil.copy(settings.BASE_DIR / 'media' / 'images' / 'default_error.png',
                    os.path.join(TEMP_MEDIA_ROOT, 'images', 'default_error.png'))
        self.image_path = os.path.join(TEMP_MEDIA_ROOT, 'upload_test.png')
        with open(self.image_path, 'wb') as f:
            f.write(png_bytes())

    def checker(self, **kwargs):
        return ApiChecker(PhotoBlogAPI(self.live_server_url), **kwargs)

    def test_full_scenario_passes(self):
        results = self.checker(image_path=self.image_path).run('tester', 'pw-1234')

        self.assertEqual(statuses(results), [PASS] * 9, results)
        self.assertEqual(Post.objects.count(), 1)   # 테스트 글은 지워져야 함

    def test_keep_option_leaves_uploaded_post(self):
        results = self.checker(image_path=self.image_path, cleanup=False).run('tester', 'pw-1234')

        self.assertEqual(statuses(results), [PASS] * 8)
        uploaded = Post.objects.exclude(title='기존 글').get()
        self.assertEqual(uploaded.author, self.user)
        self.assertIsNotNone(uploaded.published_date)

    def test_upload_without_image_uses_default(self):
        results = self.checker().run('tester', 'pw-1234')
        self.assertNotIn(FAIL, statuses(results))

    def test_without_account_skips_upload(self):
        results = self.checker().run()
        self.assertEqual(statuses(results), [PASS, PASS, PASS, PASS, SKIP])

    def test_wrong_password(self):
        results = self.checker().run('tester', 'wrong')
        self.assertEqual(results[-1].name, '토큰 발급')
        self.assertEqual(results[-1].status, FAIL)

    def test_empty_server(self):
        Post.objects.all().delete()
        results = self.checker().run()
        self.assertEqual(statuses(results)[:3], [PASS, SKIP, SKIP])

    def test_main_returns_0_and_prints_report(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = main([self.live_server_url, '-u', 'tester', '-p', 'pw-1234', '-i', self.image_path])
        self.assertEqual(code, 0)
        self.assertIn('PASS 9 / FAIL 0 / SKIP 0', out.getvalue())

    def test_main_asks_password(self):
        out = io.StringIO()
        with mock.patch('getpass.getpass', return_value='pw-1234') as ask, contextlib.redirect_stdout(out):
            code = main([self.live_server_url, '-u', 'tester', '--keep'])
        ask.assert_called_once()
        self.assertEqual(code, 0)

    def test_main_returns_1_when_failed(self):
        with contextlib.redirect_stdout(io.StringIO()):
            code = main([self.live_server_url, '-u', 'tester', '-p', 'wrong'])
        self.assertEqual(code, 1)

    def test_main_missing_image_file(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as cm:
            main([self.live_server_url, '-i', 'no_such_file.png'])
        self.assertEqual(cm.exception.code, 2)

    def test_run_as_module(self):
        argv = ['api_checker', self.live_server_url]
        with mock.patch('sys.argv', argv), contextlib.redirect_stdout(io.StringIO()), \
                self.assertRaises(SystemExit) as cm:
            runpy.run_module('api_checker', run_name='__main__')
        self.assertEqual(cm.exception.code, 0)


# ------------------------------------------------------------------
# 3) 서버가 잘못 응답하는 경우
# ------------------------------------------------------------------
class FakeResponse:

    def __init__(self, status_code=200, data=None, headers=None, content=b'', text=''):
        self.status_code = status_code
        self._data = data
        self.headers = headers or {}
        self.content = content
        self.text = text

    def json(self):
        if isinstance(self._data, Exception):
            raise self._data
        return self._data

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f'HTTP {self.status_code}', response=self)


class FakeSession:
    """(메서드, 경로)별로 정해둔 응답을 순서대로 돌려주는 가짜 requests.Session"""

    def __init__(self, routes):
        self.routes = {key: list(value) if isinstance(value, list) else [value]
                       for key, value in routes.items()}

    def request(self, method, url, **kwargs):
        path = url.replace('http://fake', '')
        responses = self.routes[(method, path)]
        response = responses.pop(0) if len(responses) > 1 else responses[0]
        if isinstance(response, Exception):
            raise response
        if callable(response):
            return response(**kwargs)
        return response

    def get(self, url, **kwargs):
        return self.request('GET', url, **kwargs)

    def post(self, url, **kwargs):
        return self.request('POST', url, **kwargs)

    def delete(self, url, **kwargs):
        return self.request('DELETE', url, **kwargs)


IMAGE_OK = FakeResponse(headers={'Content-Type': 'image/png'}, content=b'png')
LIST_OK = FakeResponse(data=[VALID_POST])
REJECTED = FakeResponse(401)
TOKEN_OK = FakeResponse(data={'token': 'abcdef123456'})


def echo_upload(**changes):
    """보낸 제목을 그대로 돌려주는 업로드 응답 (changes로 일부 값을 일부러 틀리게 만듦)"""
    def respond(data=None, **kwargs):
        return FakeResponse(201, data={**VALID_POST, 'id': 7, 'title': data['title'], **changes})
    return respond


def run_fake(routes, username=None, password=None):
    api = PhotoBlogAPI('http://fake/', session=FakeSession(routes))
    return ApiChecker(api).run(username, password)


class BrokenServerTests(SimpleTestCase):

    def test_list_http_error(self):
        results = run_fake({('GET', '/api_root/Post/'): FakeResponse(500),
                            ('POST', '/api_root/Post/'): REJECTED})
        self.assertEqual(results[0].detail, 'HTTP 500')
        self.assertEqual(statuses(results)[:2], [FAIL, PASS])

    def test_list_not_json(self):
        results = run_fake({('GET', '/api_root/Post/'): FakeResponse(data=ValueError('html')),
                            ('POST', '/api_root/Post/'): REJECTED})
        self.assertEqual(results[0].detail, 'JSON 응답이 아님')

    def test_list_not_array(self):
        results = run_fake({('GET', '/api_root/Post/'): FakeResponse(data={'results': []}),
                            ('POST', '/api_root/Post/'): REJECTED})
        self.assertEqual(results[0].detail, 'JSON 배열이 아님')

    def test_bad_post_format_and_missing_image_url(self):
        bad = {**VALID_POST, 'id': 3, 'image': None}
        results = run_fake({('GET', '/api_root/Post/'): FakeResponse(data=[bad, 'x']),
                            ('POST', '/api_root/Post/'): REJECTED})
        self.assertEqual(results[1].status, FAIL)
        self.assertEqual(results[1].detail, 'id=3: image가 절대 URL이 아님 / id=1: 게시물이 JSON 객체가 아님')
        self.assertEqual((results[2].status, results[2].detail), (FAIL, '이미지 URL이 없음'))

    def test_image_is_not_an_image(self):
        results = run_fake({('GET', '/api_root/Post/'): LIST_OK,
                            ('GET', '/media/images/pig.png'): FakeResponse(200, headers={'Content-Type': 'text/html'}),
                            ('POST', '/api_root/Post/'): REJECTED})
        self.assertEqual(results[2].detail, 'HTTP 200, text/html')

    def test_image_404_without_content_type(self):
        results = run_fake({('GET', '/api_root/Post/'): LIST_OK,
                            ('GET', '/media/images/pig.png'): FakeResponse(404),
                            ('POST', '/api_root/Post/'): REJECTED})
        self.assertEqual(results[2].detail, 'HTTP 404, Content-Type 없음')

    def test_write_without_token_allowed(self):
        results = run_fake({('GET', '/api_root/Post/'): FakeResponse(data=[]),
                            ('POST', '/api_root/Post/'): FakeResponse(201)})
        self.assertEqual(results[3].status, FAIL)
        self.assertIn('토큰 없이 글쓰기가 됨', results[3].detail)

    def test_token_response_without_token(self):
        results = run_fake({('GET', '/api_root/Post/'): FakeResponse(data=[]),
                            ('POST', '/api_root/Post/'): REJECTED,
                            ('POST', '/api-token-auth/'): FakeResponse(200, data={'key': 'x'})},
                           'tester', 'pw')
        self.assertEqual((results[-1].status, results[-1].detail), (FAIL, '응답에 token이 없음'))

    def test_login_rejected(self):
        results = run_fake({('GET', '/api_root/Post/'): FakeResponse(data=[]),
                            ('POST', '/api_root/Post/'): REJECTED,
                            ('POST', '/api-token-auth/'): FakeResponse(400)},
                           'tester', 'wrong')
        self.assertEqual(results[-1].detail, 'HTTP 400 (아이디/비밀번호 확인)')

    def test_upload_fails(self):
        results = run_fake({('GET', '/api_root/Post/'): FakeResponse(data=[]),
                            ('POST', '/api_root/Post/'): [REJECTED, FakeResponse(400, text='{"title":["필수"]}')],
                            ('POST', '/api-token-auth/'): TOKEN_OK},
                           'tester', 'pw')
        self.assertEqual(results[-1].status, FAIL)
        self.assertTrue(results[-1].detail.startswith('HTTP 400'))

    def test_upload_saved_with_wrong_values(self):
        cases = (
            ({'title': '다른 제목'}, '제목이 다르게 저장됨'),
            ({'published_date': None}, 'published_date가 비어 있음'),
            ({'author': None}, 'author가 사용자 id(정수)가 아님'),
        )
        for changes, expected in cases:
            with self.subTest(expected=expected):
                results = run_fake({
                    ('GET', '/api_root/Post/'): [FakeResponse(data=[]), FakeResponse(data=[{'id': 7}])],
                    ('POST', '/api_root/Post/'): [REJECTED, echo_upload(**changes)],
                    ('POST', '/api-token-auth/'): TOKEN_OK,
                    ('GET', '/media/images/pig.png'): IMAGE_OK,
                    ('DELETE', '/api_root/Post/7/'): FakeResponse(204),
                }, 'tester', 'pw')
                upload = results[5]
                self.assertEqual(upload.name, '토큰으로 업로드 (POST)')
                self.assertEqual(upload.status, FAIL)
                self.assertIn(expected, upload.detail)

    def test_uploaded_post_missing_from_list_and_delete_denied(self):
        results = run_fake({
            ('GET', '/api_root/Post/'): [FakeResponse(data=[]), FakeResponse(500)],
            ('POST', '/api_root/Post/'): [REJECTED, echo_upload()],
            ('POST', '/api-token-auth/'): TOKEN_OK,
            ('GET', '/media/images/pig.png'): IMAGE_OK,
            ('DELETE', '/api_root/Post/7/'): FakeResponse(403),
        }, 'tester', 'pw')

        by_name = {r.name: r for r in results}
        self.assertEqual(by_name['토큰으로 업로드 (POST)'].status, PASS)
        self.assertEqual(by_name['업로드한 글 목록 반영'].status, FAIL)
        self.assertEqual(by_name['업로드 이미지 접근'].status, PASS)
        self.assertEqual(by_name['테스트 글 삭제 (DELETE)'].detail, 'HTTP 403')

    def test_connection_error(self):
        results = run_fake({('GET', '/api_root/Post/'): requests.ConnectionError('서버 꺼짐')})
        self.assertEqual(len(results), 1)
        self.assertEqual((results[0].name, results[0].status), ('서버 연결', FAIL))
        self.assertIn('ConnectionError', results[0].detail)
