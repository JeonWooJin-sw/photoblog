from django.contrib import admin
from django.urls import path, re_path, include
from django.conf import settings
from django.conf.urls.static import static
from django.views.static import serve
from rest_framework.authtoken.views import obtain_auth_token


def media(request, path):
    # 업로드 이미지는 DEBUG 설정과 상관없이 보이도록 직접 연결
    # (테스트 서버는 DEBUG=False로 돌아서 static()으로는 이미지가 404 남)
    # PythonAnywhere는 Web 탭의 /media/ 매핑이 먼저 처리함
    return serve(request, path, document_root=settings.MEDIA_ROOT)


urlpatterns = [
    path('admin/', admin.site.urls),
    path('', include('blog.urls')),
    path('api-token-auth/', obtain_auth_token),
    # Browsable API(/api_root/Post/) 오른쪽 위 로그인 링크 -> 로그인하면 HTML form으로 글쓰기 가능
    path('api-auth/', include('rest_framework.urls')),
    re_path(r'^media/(?P<path>.*)$', media),
]

urlpatterns += static(settings.STATIC_URL, document_root=settings.STATIC_ROOT)
