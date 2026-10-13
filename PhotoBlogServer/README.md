# web_blog - Django 이미지 블로그 + REST API

모바일/웹서비스프로젝트 실습과제03 (2026-2)

수업에서 받은 예제 코드(kiokahn/web_blog)를 내 계정으로 옮기고 PythonAnywhere에 배포한 것.
텍스트 블로그에 이미지 필드가 붙어 있고, DRF로 `/api_root/Post/` API가 열려 있다.

- 배포 주소 : https://woojin123123.pythonanywhere.com
- 관리자 : /admin/
- API root : /api_root/
- Django 6.1.1 / DRF 3.18.1 / Python 3.13

## 로컬 실행

```bash
git clone https://github.com/JeonWooJin-sw/web_blog.git
cd web_blog
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
python manage.py migrate
python manage.py createsuperuser
python manage.py runserver
```

admin에서 로그인하면 메인 화면 오른쪽 위에 + 버튼이 생기고 글을 쓸 수 있다.

## API로 글 쓰기 (curl)

```bash
curl -X POST -H 'Accept: application/json' \
  -F "author=1" \
  -F "title=curl 테스트" \
  -F "text=curl로 올린 글" \
  -F "published_date=2026-09-28T18:00:00+09:00" \
  -F "image=@media/images/pig.png;type=image/png" \
  http://127.0.0.1:8000/api_root/Post/
```

`published_date`를 안 넣으면 목록에 안 뜬다 (post_list가 published_date 기준으로 필터링함).

## PythonAnywhere 배포 메모

1. Bash 콘솔에서 `git clone` 후 `mkvirtualenv --python=python3.13 web_blog` → `pip install -r requirements.txt`
2. `python manage.py migrate`, `createsuperuser`, `collectstatic`
3. Web 탭 → Add a new web app → Manual configuration → Python 3.13
4. Virtualenv 경로에 `/home/woojin123123/.virtualenvs/web_blog`
5. WSGI 파일을 아래처럼 수정

```python
import os, sys
path = os.path.expanduser('~/web_blog')
if path not in sys.path:
    sys.path.insert(0, path)
os.environ['DJANGO_SETTINGS_MODULE'] = 'mysite.settings'
from django.core.wsgi import get_wsgi_application
application = get_wsgi_application()
```

6. Static files
   - `/static/` → `/home/woojin123123/web_blog/staticfiles`
   - `/media/` → `/home/woojin123123/web_blog/media`
7. Reload

https라서 admin 로그인할 때 CSRF 에러가 나길래 settings.py에 `CSRF_TRUSTED_ORIGINS` 추가함.
