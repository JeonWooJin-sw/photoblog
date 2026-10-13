import io
import os
import shutil
import tempfile
from datetime import timedelta

from django.contrib.auth.models import User
from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase, override_settings
from django.urls import reverse
from django.utils import timezone
from PIL import Image
from rest_framework import status
from rest_framework.authtoken.models import Token
from rest_framework.test import APITestCase

from .models import Post

# 테스트 중에 올린 이미지가 실제 media 폴더에 쌓이지 않도록 임시 폴더 사용
TEMP_MEDIA_ROOT = tempfile.mkdtemp()
# 기본 비밀번호 해시는 일부러 느려서 테스트에서는 빠른 해시 사용
FAST_HASHER = ['django.contrib.auth.hashers.MD5PasswordHasher']
API_URL = '/api_root/Post/'


def tearDownModule():
    shutil.rmtree(TEMP_MEDIA_ROOT, ignore_errors=True)


def make_image(name='test.png', size=(20, 20)):
    buffer = io.BytesIO()
    Image.new('RGB', size, (255, 148, 0)).save(buffer, 'PNG')
    return SimpleUploadedFile(name, buffer.getvalue(), content_type='image/png')


def make_post(author, title='제목', days_ago=0, published=True, **kwargs):
    published_date = timezone.now() - timedelta(days=days_ago) if published else None
    return Post.objects.create(author=author, title=title, text='내용',
                               published_date=published_date, **kwargs)


@override_settings(PASSWORD_HASHERS=FAST_HASHER)
class PostModelTests(TestCase):

    def setUp(self):
        self.user = User.objects.create_user('writer', password='pw-1234')

    def test_str_returns_title(self):
        post = make_post(self.user, title='포토 블로그')
        self.assertEqual(str(post), '포토 블로그')

    def test_publish_sets_published_date(self):
        post = make_post(self.user, published=False)
        self.assertIsNone(post.published_date)
        post.publish()
        post.refresh_from_db()
        self.assertIsNotNone(post.published_date)

    def test_default_image_when_not_uploaded(self):
        post = make_post(self.user)
        self.assertEqual(post.image.name, 'images/default_error.png')


@override_settings(MEDIA_ROOT=TEMP_MEDIA_ROOT, PASSWORD_HASHERS=FAST_HASHER)
class PostWebTests(TestCase):

    def setUp(self):
        self.user = User.objects.create_user('writer', password='pw-1234')
        self.other = User.objects.create_user('other', password='pw-1234')
        self.admin = User.objects.create_superuser('admin', password='pw-1234')

    def test_list_shows_published_posts_newest_first(self):
        old = make_post(self.user, title='예전 글', days_ago=3)
        new = make_post(self.user, title='새 글', days_ago=1)
        make_post(self.user, title='예약 글', days_ago=-1)       # 미래 날짜
        make_post(self.user, title='비공개 글', published=False)

        response = self.client.get(reverse('post_list'))

        self.assertEqual(response.status_code, 200)
        self.assertEqual(list(response.context['posts']), [new, old])
        self.assertNotContains(response, '예약 글')
        self.assertNotContains(response, '비공개 글')

    def test_list_empty_message(self):
        response = self.client.get(reverse('post_list'))
        self.assertContains(response, '아직 게시된 글이 없습니다.')

    def test_list_shows_login_link_for_anonymous(self):
        response = self.client.get(reverse('post_list'))
        self.assertContains(response, '로그인')
        self.assertNotContains(response, reverse('post_new'))

    def test_detail_shows_image_and_text(self):
        post = make_post(self.user, title='상세 보기', image=make_image())
        response = self.client.get(reverse('post_detail', args=[post.pk]))
        self.assertContains(response, '상세 보기')
        self.assertContains(response, post.image.url)

    def test_detail_404(self):
        response = self.client.get(reverse('post_detail', args=[999]))
        self.assertEqual(response.status_code, 404)

    def test_new_requires_login(self):
        response = self.client.get(reverse('post_new'))
        self.assertRedirects(response, '/admin/login/?next=/post/new/', fetch_redirect_response=False)

    def test_new_form_supports_file_upload(self):
        self.client.login(username='writer', password='pw-1234')
        response = self.client.get(reverse('post_new'))
        self.assertContains(response, 'enctype="multipart/form-data"')
        self.assertContains(response, 'New post')

    def test_new_post_with_image(self):
        self.client.login(username='writer', password='pw-1234')
        response = self.client.post(reverse('post_new'), {
            'title': '웹에서 쓴 글', 'text': '사진 포함', 'image': make_image('web.png'),
        })
        post = Post.objects.get(title='웹에서 쓴 글')
        self.assertRedirects(response, reverse('post_detail', args=[post.pk]))
        self.assertEqual(post.author, self.user)
        self.assertIsNotNone(post.published_date)
        self.assertTrue(post.image.name.startswith('images/'))
        self.assertIn('web', post.image.name)

    def test_new_post_invalid_form(self):
        self.client.login(username='writer', password='pw-1234')
        response = self.client.post(reverse('post_new'), {'title': '', 'text': ''})
        self.assertEqual(response.status_code, 200)
        self.assertFalse(Post.objects.exists())

    def test_edit_form_by_author(self):
        post = make_post(self.user)
        self.client.login(username='writer', password='pw-1234')
        response = self.client.get(reverse('post_edit', args=[post.pk]))
        self.assertContains(response, 'Edit post')

    def test_edit_by_staff_keeps_author(self):
        post = make_post(self.user, title='원래 제목')
        self.client.login(username='admin', password='pw-1234')
        self.client.post(reverse('post_edit', args=[post.pk]), {'title': '관리자가 수정', 'text': '수정됨'})
        post.refresh_from_db()
        self.assertEqual(post.title, '관리자가 수정')
        self.assertEqual(post.author, self.user)

    def test_edit_invalid_form(self):
        post = make_post(self.user, title='원래 제목')
        self.client.login(username='writer', password='pw-1234')
        response = self.client.post(reverse('post_edit', args=[post.pk]), {'title': '', 'text': ''})
        self.assertEqual(response.status_code, 200)
        post.refresh_from_db()
        self.assertEqual(post.title, '원래 제목')

    def test_edit_by_other_user_forbidden(self):
        post = make_post(self.user)
        self.client.login(username='other', password='pw-1234')
        response = self.client.post(reverse('post_edit', args=[post.pk]), {'title': '남의 글', 'text': 'x'})
        self.assertEqual(response.status_code, 403)

    def test_js_test_page(self):
        response = self.client.get(reverse('js_test'))
        self.assertContains(response, 'Number guessing game')


@override_settings(MEDIA_ROOT=TEMP_MEDIA_ROOT, PASSWORD_HASHERS=FAST_HASHER)
class PostApiTests(APITestCase):

    def setUp(self):
        self.user = User.objects.create_user('writer', password='pw-1234')
        self.other = User.objects.create_user('other', password='pw-1234')
        self.admin = User.objects.create_superuser('admin', password='pw-1234')
        self.token = Token.objects.create(user=self.user)

    def auth(self, keyword='JWT', key=None):
        self.client.credentials(HTTP_AUTHORIZATION=f'{keyword} {key or self.token.key}')

    # ---------- 조회 ----------
    def test_list_is_public_and_has_id(self):
        post = make_post(self.user, image=make_image())
        response = self.client.get(API_URL)

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(len(response.data), 1)
        item = response.data[0]
        self.assertEqual(set(item), {'id', 'author', 'title', 'text', 'created_date', 'published_date', 'image'})
        self.assertEqual(item['id'], post.id)
        self.assertTrue(item['image'].startswith('http://testserver/media/images/'))

    def test_list_newest_first_and_unpublished_last(self):
        old = make_post(self.user, title='old', days_ago=2)
        draft = make_post(self.user, title='draft', published=False)
        new = make_post(self.user, title='new', days_ago=0)

        response = self.client.get(API_URL)

        self.assertEqual([p['id'] for p in response.data], [new.id, old.id, draft.id])

    def test_retrieve_single_post(self):
        post = make_post(self.user, title='하나만')
        response = self.client.get(f'{API_URL}{post.id}/')
        self.assertEqual(response.data['title'], '하나만')

    # ---------- 글쓰기 ----------
    def test_create_without_token_is_rejected(self):
        response = self.client.post(API_URL, {'title': 't', 'text': 'x'})
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)
        self.assertFalse(Post.objects.exists())

    def test_create_with_jwt_keyword_like_lecture(self):
        # 수업 자료 curl 예제와 같은 형식 (author 없이, JWT 헤더)
        self.auth('JWT')
        response = self.client.post(API_URL, {
            'title': '제목2', 'text': 'API 내용2',
            'created_date': '2022-06-07T18:34:00+09:00',
            'published_date': '2022-06-07T18:34:00+09:00',
            'image': make_image('figure.png'),
        }, format='multipart')

        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        post = Post.objects.get(id=response.data['id'])
        self.assertEqual(post.author, self.user)
        self.assertEqual(post.published_date.isoformat(), '2022-06-07T09:34:00+00:00')
        self.assertIn('figure', post.image.name)

    def test_create_with_token_keyword(self):
        self.auth('Token')
        response = self.client.post(API_URL, {'title': 't', 'text': 'x'})
        self.assertEqual(response.status_code, status.HTTP_201_CREATED)

    def test_create_ignores_author_and_fills_published_date(self):
        self.auth()
        response = self.client.post(API_URL, {'author': self.other.id, 'title': '앱 업로드', 'text': '내용'})

        self.assertEqual(response.status_code, status.HTTP_201_CREATED)
        self.assertEqual(response.data['author'], self.user.id)
        self.assertIsNotNone(response.data['published_date'])
        # published_date가 채워져야 웹 메인 목록에도 보임
        self.assertContains(self.client.get(reverse('post_list')), '앱 업로드')

    def test_create_without_image_uses_default(self):
        self.auth()
        response = self.client.post(API_URL, {'title': 't', 'text': 'x'})
        self.assertTrue(response.data['image'].endswith('/media/images/default_error.png'))

    def test_create_with_invalid_token(self):
        self.auth(key='wrong-token')
        response = self.client.post(API_URL, {'title': 't', 'text': 'x'})
        self.assertEqual(response.status_code, status.HTTP_401_UNAUTHORIZED)

    def test_create_missing_title(self):
        self.auth()
        response = self.client.post(API_URL, {'text': 'x'})
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('title', response.data)

    def test_create_rejects_non_image_file(self):
        self.auth()
        fake = SimpleUploadedFile('note.png', b'not an image', content_type='image/png')
        response = self.client.post(API_URL, {'title': 't', 'text': 'x', 'image': fake}, format='multipart')
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('image', response.data)

    @override_settings(MAX_UPLOAD_IMAGE_SIZE=100)
    def test_create_rejects_too_large_image(self):
        self.auth()
        response = self.client.post(API_URL, {'title': 't', 'text': 'x', 'image': make_image(size=(300, 300))},
                                    format='multipart')
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn('MB 이하', str(response.data['image']))

    # ---------- 삭제 ----------
    def test_delete_by_author(self):
        post = make_post(self.user)
        self.auth()
        response = self.client.delete(f'{API_URL}{post.id}/')
        self.assertEqual(response.status_code, status.HTTP_204_NO_CONTENT)
        self.assertFalse(Post.objects.exists())

    def test_delete_by_other_user_forbidden(self):
        post = make_post(self.user)
        self.auth(key=Token.objects.create(user=self.other).key)
        response = self.client.delete(f'{API_URL}{post.id}/')
        self.assertEqual(response.status_code, status.HTTP_403_FORBIDDEN)
        self.assertTrue(Post.objects.exists())

    def test_delete_by_staff(self):
        post = make_post(self.user)
        self.auth(key=Token.objects.create(user=self.admin).key)
        response = self.client.delete(f'{API_URL}{post.id}/')
        self.assertEqual(response.status_code, status.HTTP_204_NO_CONTENT)

    def test_delete_removes_uploaded_image_file(self):
        post = make_post(self.user, image=make_image('remove_me.png'))
        default_post = make_post(self.user)
        default_path = default_post.image.path
        os.makedirs(os.path.dirname(default_path), exist_ok=True)
        open(default_path, 'wb').close()
        self.assertTrue(os.path.exists(post.image.path))

        self.auth()
        self.client.delete(f'{API_URL}{post.id}/')
        self.client.delete(f'{API_URL}{default_post.id}/')

        self.assertFalse(os.path.exists(post.image.path))
        # 기본 이미지는 다른 글들도 같이 쓰므로 남아 있어야 함
        self.assertTrue(os.path.exists(default_path))

    # ---------- 토큰 ----------
    def test_obtain_token(self):
        response = self.client.post('/api-token-auth/', {'username': 'writer', 'password': 'pw-1234'})
        self.assertEqual(response.data['token'], self.token.key)

    def test_obtain_token_wrong_password(self):
        response = self.client.post('/api-token-auth/', {'username': 'writer', 'password': 'nope'})
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)

    # ---------- 이미지 파일 ----------
    def test_uploaded_image_is_served(self):
        post = make_post(self.user, image=make_image('served.png'))
        response = self.client.get(post.image.url)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response['Content-Type'], 'image/png')
