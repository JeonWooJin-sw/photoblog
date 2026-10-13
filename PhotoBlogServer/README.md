# PhotoBlogServer - Django 포토블로그 서버

실습과제03에서 만든 web_blog(수업 예제 kiokahn/web_blog 기반)를 가져와서
안드로이드 앱(PhotoViewer)과 연동할 수 있게 고친 서버.

- 배포 주소 : https://woojin123123.pythonanywhere.com
- Django 6.1.1 / DRF 3.18.1 / Python 3.13

## 실행

```bash
cd PhotoBlogServer
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
python manage.py migrate
python manage.py createsuperuser
python manage.py runserver
```

- 웹 : http://127.0.0.1:8000 (오른쪽 위 로그인 → + 버튼으로 사진 글쓰기)
- API : http://127.0.0.1:8000/api_root/Post/ (오른쪽 위 Log in 하면 아래 HTML form으로 POST 가능)
- 안드로이드 에뮬레이터에서는 `http://10.0.2.2:8000` 으로 접속

## API

| 메서드 | 주소 | 설명 | 인증 |
|---|---|---|---|
| POST | `/api-token-auth/` | username, password → token 발급 | X |
| GET | `/api_root/Post/` | 게시물 목록 (최신순) | X |
| GET | `/api_root/Post/<id>/` | 게시물 하나 | X |
| POST | `/api_root/Post/` | 글쓰기 (multipart: title, text, image, published_date) | O |
| PUT/PATCH/DELETE | `/api_root/Post/<id>/` | 수정/삭제 (작성자만) | O |

응답 예시

```json
[
  {
    "id": 2,
    "author": 1,
    "title": "앱 업로드",
    "text": "내용",
    "created_date": "2026-10-13T15:59:20.312429+09:00",
    "published_date": "2026-10-13T15:59:20.312324+09:00",
    "image": "http://127.0.0.1:8000/media/images/2026/10/13/pig.png"
  }
]
```

### 인증 (토큰)

수업 자료와 같이 헤더에 `Authorization: JWT <토큰>` 을 붙인다.
토큰은 `/api-token-auth/` 로 받거나 `python manage.py drf_create_token <username>` 으로 만든다.

```bash
curl -X POST -S \
  -H "Authorization: JWT <토큰>" \
  -H 'Accept: application/json' \
  -F "title=curl 테스트" \
  -F "text=curl로 올린 글" \
  -F "image=@media/images/pig.png;type=image/png" \
  http://127.0.0.1:8000/api_root/Post/
```

## 수업 예제에서 바꾼 점

| 문제 | 수정 |
|---|---|
| `JWT` 헤더를 보내도 인증이 적용 안 됨 (권한 설정이 비어 있어서 누구나 글쓰기/삭제 가능) | `JWT` 키워드를 인식하는 토큰 인증 추가(`blog/authentication.py`), 조회는 누구나 / 쓰기는 토큰 필요, 수정·삭제는 작성자만 |
| API로 글 쓸 때 `author` 를 직접 보내야 함 | 토큰 주인으로 자동 저장 (수업 자료 curl 예제처럼 author 없이 보내도 됨) |
| `published_date` 없이 올리면 웹 메인에 안 보임 | 안 보내면 업로드 시각으로 채움 |
| API 응답에 글 번호가 없음 | `id` 필드 추가 (앱에서 상세보기/삭제에 사용) |
| `localhost:8000` 접속 시 400 에러 | `ALLOWED_HOSTS` 에 localhost 추가 |
| 모델과 마이그레이션 이미지 경로 불일치 (`intruder_image/` ↔ `images/`) | `0004_alter_post_image` 마이그레이션 추가 |
| 웹 글쓰기 폼에 사진이 없고, 상세 페이지에 사진이 안 나옴 | 사진 업로드 추가, 상세 페이지에 사진 표시, 목록은 최신순 |
| 큰 사진이 화면 밖으로 넘침 | CSS로 크기 제한 |
| 글을 지워도 사진 파일이 media 폴더에 남음 | 글 삭제 시 업로드 파일도 삭제 (기본 이미지는 유지) |
| Browsable API에서 로그인할 방법이 없음 | `/api-auth/` 추가 → `/api_root/Post/` 오른쪽 위 Log in 후 HTML form으로 글쓰기 |
| 뷰셋 이름 `IntruderImage` | 강의자료 ch05와 같이 `BlogImages` 로 변경 |

## 테스트 / 코드 커버리지

```bash
coverage run manage.py test   # 테스트 68개
coverage report               # 터미널에서 확인
coverage html                 # htmlcov/index.html 생성
open htmlcov/index.html
```

- `blog/tests.py` : 모델, 웹 화면, REST API(인증/업로드/검증/삭제) 테스트
- `api_checker/tests.py` : API 검증 엔진 테스트 (실제 테스트 서버 + 가짜 응답)
- 설정은 `.coveragerc` (branch 커버리지 포함, migrations 제외)

## API 검증 엔진 (api_checker)

수업 자료의 `test_api.py`(requests로 토큰 받고 글 올리기)를 발전시켜서
서버 API를 단계별로 자동 검사하는 프로그램. 서버 주소만 바꾸면 PythonAnywhere도 검사할 수 있다.

```bash
# 서버를 켜 둔 상태에서
python -m api_checker http://127.0.0.1:8000 -u admin -i media/images/pig.png
python -m api_checker https://woojin123123.pythonanywhere.com -u admin -i media/images/pig.png
```

```
================================================================
 PhotoBlog API 검증 결과 : http://127.0.0.1:8000
================================================================
 1. [PASS] 게시물 목록 조회 (GET)  - HTTP 200, 게시물 0개
 2. [SKIP] 응답 필드 형식  - 게시물이 없음
 3. [SKIP] 게시물 이미지 접근  - 게시물이 없음
 4. [PASS] 인증 없는 글쓰기 차단  - HTTP 401
 5. [PASS] 토큰 발급  - 516a5f... (admin)
 6. [PASS] 토큰으로 업로드 (POST)  - HTTP 201, id=1
 7. [PASS] 업로드한 글 목록 반영  - id=1 확인
 8. [PASS] 업로드 이미지 접근  - image/png, 8,090 bytes
 9. [PASS] 테스트 글 삭제 (DELETE)  - HTTP 204
================================================================
 PASS 7 / FAIL 0 / SKIP 2
```

- `-p` 를 안 주면 비밀번호를 입력받음
- `--keep` : 테스트로 올린 글을 지우지 않고 남김
- 하나라도 FAIL이면 종료 코드 1

## PythonAnywhere 배포

1. Bash 콘솔에서 `git clone https://github.com/JeonWooJin-sw/photoblog.git`
2. `workon web_blog` (기존 가상환경) → `cd ~/photoblog/PhotoBlogServer` → `pip install -r requirements.txt`
3. `python manage.py migrate` → `createsuperuser` → `collectstatic`
4. WSGI 파일의 경로를 `~/photoblog/PhotoBlogServer` 로 변경
5. Static files
   - `/static/` → `/home/woojin123123/photoblog/PhotoBlogServer/staticfiles`
   - `/media/` → `/home/woojin123123/photoblog/PhotoBlogServer/media`
6. Reload
