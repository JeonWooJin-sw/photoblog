from django.db import models
from django.db.models.signals import post_delete
from django.dispatch import receiver
from django.utils import timezone

DEFAULT_IMAGE = 'images/default_error.png'


class Post(models.Model):
    author = models.ForeignKey('auth.User', on_delete=models.CASCADE)
    title = models.CharField(max_length=200)
    text = models.TextField()
    created_date = models.DateTimeField(default=timezone.now)
    published_date = models.DateTimeField(blank=True, null=True)
    image = models.ImageField(upload_to='images/%Y/%m/%d/', default=DEFAULT_IMAGE)

    def publish(self):
        self.published_date = timezone.now()
        self.save()

    def __str__(self):
        return self.title


@receiver(post_delete, sender=Post)
def delete_image_file(sender, instance, **kwargs):
    # 글을 지워도 업로드한 사진 파일은 media 폴더에 남아 있어서 같이 삭제 (기본 이미지는 제외)
    if instance.image and instance.image.name != DEFAULT_IMAGE:
        instance.image.delete(save=False)
