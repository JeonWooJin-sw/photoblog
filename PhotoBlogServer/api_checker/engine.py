"""
포토블로그 REST API 검증 엔진

수업 자료(ch03)의 test_api.py(requests로 토큰 받고 글 올리기)를 바탕으로,
서버 API가 규격대로 동작하는지 단계별로 자동 검사하도록 만든 것.
주소만 바꾸면 로컬 서버와 PythonAnywhere 둘 다 같은 방식으로 검사할 수 있다.

    python -m api_checker http://127.0.0.1:8000 -u admin -i media/images/pig.png

검사 순서
    1. 게시물 목록 조회 (GET /api_root/Post/)
    2. 응답 필드 형식 (id, author, title, text, created_date, published_date, image)
    3. 게시물 이미지 접근 (image URL이 실제 이미지인지)
    4. 인증 없는 글쓰기 차단 (401/403 이어야 함)
    5. 토큰 발급 (POST /api-token-auth/)
    6. 토큰으로 업로드 (POST /api_root/Post/, multipart)
    7. 업로드한 글이 목록에 있는지
    8. 업로드한 이미지 접근
    9. 테스트 글 삭제 (DELETE) - --keep 옵션이면 남겨둠
"""
import argparse
import getpass
import os
import sys
from dataclasses import dataclass
from datetime import datetime

import requests

API_PATH = '/api_root/Post/'
TOKEN_PATH = '/api-token-auth/'
POST_FIELDS = ('id', 'author', 'title', 'text', 'created_date', 'published_date', 'image')

PASS = 'PASS'
FAIL = 'FAIL'
SKIP = 'SKIP'


@dataclass
class CheckResult:
    name: str
    status: str
    detail: str = ''

    @property
    def ok(self):
        return self.status != FAIL


def parse_datetime(value):
    """ISO 8601 날짜 문자열을 datetime으로 바꿈. 형식이 틀리면 None"""
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        return None


def validate_post(post):
    """게시물 JSON 하나를 검사해서 문제점 목록을 돌려줌 (정상이면 빈 리스트)"""
    if not isinstance(post, dict):
        return ['게시물이 JSON 객체가 아님']

    missing = [f'{name} 필드 없음' for name in POST_FIELDS if name not in post]
    if missing:
        return missing

    errors = []
    if not isinstance(post['id'], int):
        errors.append('id가 정수가 아님')
    if not isinstance(post['author'], int):
        errors.append('author가 사용자 id(정수)가 아님')
    if not isinstance(post['title'], str) or not post['title'].strip():
        errors.append('title이 비어 있음')
    if not isinstance(post['text'], str):
        errors.append('text가 문자열이 아님')
    if parse_datetime(post['created_date']) is None:
        errors.append('created_date 날짜 형식 오류')
    if post['published_date'] is not None and parse_datetime(post['published_date']) is None:
        errors.append('published_date 날짜 형식 오류')
    if not is_absolute_url(post['image']):
        errors.append('image가 절대 URL이 아님')
    return errors


def is_absolute_url(value):
    return isinstance(value, str) and value.startswith(('http://', 'https://'))


class PhotoBlogAPI:
    """서버 호출만 담당 (수업 자료처럼 requests 사용)"""

    def __init__(self, host, token=None, timeout=10, session=None):
        self.host = host.rstrip('/')
        self.token = token
        self.timeout = timeout
        self.session = session or requests.Session()

    def headers(self, auth=True):
        headers = {'Accept': 'application/json'}
        if auth and self.token:
            headers['Authorization'] = 'JWT ' + self.token
        return headers

    def login(self, username, password):
        res = self.session.post(self.host + TOKEN_PATH,
                                data={'username': username, 'password': password},
                                timeout=self.timeout)
        res.raise_for_status()
        self.token = res.json()['token']
        return self.token

    def list_posts(self):
        return self.session.get(self.host + API_PATH, headers=self.headers(), timeout=self.timeout)

    def create_post(self, title, text, image_path=None, auth=True):
        data = {'title': title, 'text': text}
        if image_path is None:
            return self.session.post(self.host + API_PATH, data=data,
                                     headers=self.headers(auth), timeout=self.timeout)
        with open(image_path, 'rb') as image:
            files = {'image': (os.path.basename(image_path), image)}
            return self.session.post(self.host + API_PATH, data=data, files=files,
                                     headers=self.headers(auth), timeout=self.timeout)

    def delete_post(self, post_id):
        return self.session.delete(f'{self.host}{API_PATH}{post_id}/',
                                   headers=self.headers(), timeout=self.timeout)

    def fetch(self, url):
        return self.session.get(url, timeout=self.timeout)


class ApiChecker:
    """검사 시나리오를 순서대로 실행하고 결과(CheckResult)를 모은다"""

    def __init__(self, api, image_path=None, cleanup=True):
        self.api = api
        self.image_path = image_path
        self.cleanup = cleanup
        self.results = []

    def add(self, name, status, detail=''):
        self.results.append(CheckResult(name, status, detail))

    def run(self, username=None, password=None):
        self.results = []
        try:
            posts = self.check_list()
            if posts is not None:
                self.check_format(posts)
                self.check_image(posts[0] if posts else None, '게시물 이미지 접근')
            self.check_write_protected()

            if not username or password is None:
                self.add('토큰 발급 / 업로드', SKIP, '계정 정보가 없어 생략 (-u 옵션)')
            elif self.check_login(username, password):
                created = self.check_upload()
                if created is not None:
                    self.check_in_list(created)
                    self.check_image(created, '업로드 이미지 접근')
                    if self.cleanup:
                        self.check_delete(created)
        except requests.RequestException as e:
            self.add('서버 연결', FAIL, f'{type(e).__name__}: {e}')
        return self.results

    # ---------- 단계별 검사 ----------
    def check_list(self):
        name = '게시물 목록 조회 (GET)'
        res = self.api.list_posts()
        if res.status_code != 200:
            self.add(name, FAIL, f'HTTP {res.status_code}')
            return None
        try:
            posts = res.json()
        except ValueError:
            self.add(name, FAIL, 'JSON 응답이 아님')
            return None
        if not isinstance(posts, list):
            self.add(name, FAIL, 'JSON 배열이 아님')
            return None
        self.add(name, PASS, f'HTTP 200, 게시물 {len(posts)}개')
        return posts

    def check_format(self, posts):
        name = '응답 필드 형식'
        if not posts:
            self.add(name, SKIP, '게시물이 없음')
            return
        problems = []
        for index, post in enumerate(posts):
            errors = validate_post(post)
            if errors:
                post_id = post.get('id', index) if isinstance(post, dict) else index
                problems.append(f'id={post_id}: ' + ', '.join(errors))
        if problems:
            self.add(name, FAIL, ' / '.join(problems))
        else:
            self.add(name, PASS, f'{len(posts)}개 모두 정상')

    def check_image(self, post, name):
        if post is None:
            self.add(name, SKIP, '게시물이 없음')
            return
        url = post.get('image') if isinstance(post, dict) else None
        if not is_absolute_url(url):
            self.add(name, FAIL, '이미지 URL이 없음')
            return
        res = self.api.fetch(url)
        content_type = res.headers.get('Content-Type', '')
        if res.status_code == 200 and content_type.startswith('image/'):
            self.add(name, PASS, f'{content_type}, {len(res.content):,} bytes')
        else:
            self.add(name, FAIL, f'HTTP {res.status_code}, {content_type or "Content-Type 없음"}')

    def check_write_protected(self):
        name = '인증 없는 글쓰기 차단'
        res = self.api.create_post('인증 없이 쓰기 테스트', '저장되면 안 되는 글', auth=False)
        if res.status_code in (401, 403):
            self.add(name, PASS, f'HTTP {res.status_code}')
        else:
            self.add(name, FAIL, f'HTTP {res.status_code} (토큰 없이 글쓰기가 됨)')

    def check_login(self, username, password):
        name = '토큰 발급'
        try:
            token = self.api.login(username, password)
        except requests.HTTPError as e:
            self.add(name, FAIL, f'HTTP {e.response.status_code} (아이디/비밀번호 확인)')
            return False
        except (ValueError, KeyError):
            self.add(name, FAIL, '응답에 token이 없음')
            return False
        self.add(name, PASS, f'{token[:6]}... ({username})')
        return True

    def check_upload(self):
        name = '토큰으로 업로드 (POST)'
        title = f'API 검증 엔진 테스트 {datetime.now():%Y-%m-%d %H:%M:%S}'
        res = self.api.create_post(title, '자동 검증 엔진이 올린 글입니다.', image_path=self.image_path)
        if res.status_code != 201:
            self.add(name, FAIL, f'HTTP {res.status_code} {res.text[:200]}')
            return None

        post = res.json()
        errors = validate_post(post)
        if not errors and post['title'] != title:
            errors.append('제목이 다르게 저장됨')
        if not errors and post['published_date'] is None:
            errors.append('published_date가 비어 있음 (웹 목록에 안 보임)')
        if errors:
            self.add(name, FAIL, ', '.join(errors))
        else:
            self.add(name, PASS, f'HTTP 201, id={post["id"]}')
        return post

    def check_in_list(self, post):
        name = '업로드한 글 목록 반영'
        res = self.api.list_posts()
        ids = [item.get('id') for item in res.json()] if res.status_code == 200 else []
        if post.get('id') in ids:
            self.add(name, PASS, f'id={post["id"]} 확인')
        else:
            self.add(name, FAIL, f'목록에 id={post.get("id")} 없음')

    def check_delete(self, post):
        name = '테스트 글 삭제 (DELETE)'
        res = self.api.delete_post(post.get('id'))
        if res.status_code == 204:
            self.add(name, PASS, 'HTTP 204')
        else:
            self.add(name, FAIL, f'HTTP {res.status_code}')


def report(results, host, out=None):
    out = out or sys.stdout
    line = '=' * 64
    print(line, file=out)
    print(f' PhotoBlog API 검증 결과 : {host}', file=out)
    print(line, file=out)
    for number, result in enumerate(results, 1):
        detail = f'  - {result.detail}' if result.detail else ''
        print(f'{number:2d}. [{result.status}] {result.name}{detail}', file=out)
    print(line, file=out)
    counts = {status: sum(r.status == status for r in results) for status in (PASS, FAIL, SKIP)}
    print(f' PASS {counts[PASS]} / FAIL {counts[FAIL]} / SKIP {counts[SKIP]}', file=out)


def main(argv=None):
    parser = argparse.ArgumentParser(prog='api_checker', description='포토블로그 REST API 검증 엔진')
    parser.add_argument('host', nargs='?', default='http://127.0.0.1:8000', help='서버 주소 (기본: 로컬)')
    parser.add_argument('-u', '--username', help='토큰 발급에 쓸 계정')
    parser.add_argument('-p', '--password', help='비밀번호 (생략하면 입력 받음)')
    parser.add_argument('-i', '--image', help='업로드 테스트에 쓸 이미지 파일')
    parser.add_argument('--keep', action='store_true', help='테스트로 올린 글을 삭제하지 않음')
    parser.add_argument('--timeout', type=float, default=10, help='요청 제한 시간(초)')
    args = parser.parse_args(argv)

    if args.image and not os.path.isfile(args.image):
        parser.error(f'이미지 파일이 없습니다: {args.image}')
    password = args.password
    if args.username and password is None:
        password = getpass.getpass(f'{args.username} 비밀번호: ')

    api = PhotoBlogAPI(args.host, timeout=args.timeout)
    results = ApiChecker(api, image_path=args.image, cleanup=not args.keep).run(args.username, password)
    report(results, api.host)
    return 0 if all(result.ok for result in results) else 1
