from django.conf import settings
from rest_framework import serializers

from blog.models import Post


class PostSerializer(serializers.ModelSerializer):
    # 작성자는 토큰으로 인증된 사용자로 저장됨 (요청에 author를 보내도 무시)
    author = serializers.PrimaryKeyRelatedField(read_only=True)

    class Meta:
        model = Post
        # id : 앱에서 상세보기/삭제할 때 필요해서 추가
        fields = ('id', 'author', 'title', 'text', 'created_date', 'published_date', 'image')

    def validate_image(self, image):
        if image.size > settings.MAX_UPLOAD_IMAGE_SIZE:
            limit_mb = settings.MAX_UPLOAD_IMAGE_SIZE // (1024 * 1024)
            raise serializers.ValidationError(f'이미지는 {limit_mb}MB 이하만 올릴 수 있습니다.')
        return image
