from django.contrib.auth.decorators import login_required
from django.db.models import F
from django.http import HttpResponseForbidden
from django.shortcuts import render, get_object_or_404, redirect
from django.utils import timezone
from rest_framework import permissions, viewsets

from .forms import PostForm
from .models import Post
from .permissions import IsAuthorOrReadOnly
from .serializers import PostSerializer


def post_list(request):
    # 최신 글이 위로 오도록 변경 (원래는 오래된 글이 위)
    posts = Post.objects.filter(
        published_date__lte=timezone.now()
    ).order_by('-published_date')
    return render(request, 'blog/post_list.html', {'posts': posts})


def post_detail(request, pk):
    post = get_object_or_404(Post, pk=pk)
    return render(request, 'blog/post_detail.html', {'post': post})


@login_required
def post_new(request):
    if request.method == "POST":
        form = PostForm(request.POST, request.FILES)
        if form.is_valid():
            post = form.save(commit=False)
            post.author = request.user
            post.published_date = timezone.now()
            post.save()
            return redirect('post_detail', pk=post.pk)
    else:
        form = PostForm()
    return render(request, 'blog/post_edit.html', {'form': form})


@login_required
def post_edit(request, pk):
    post = get_object_or_404(Post, pk=pk)
    if post.author != request.user and not request.user.is_staff:
        return HttpResponseForbidden('작성자만 수정할 수 있습니다.')
    if request.method == "POST":
        form = PostForm(request.POST, request.FILES, instance=post)
        if form.is_valid():
            # 원래 코드는 수정한 사람으로 author가 바뀌어서 author는 그대로 둠
            post = form.save(commit=False)
            post.published_date = timezone.now()
            post.save()
            return redirect('post_detail', pk=post.pk)
    else:
        form = PostForm(instance=post)
    return render(request, 'blog/post_edit.html', {'form': form, 'post': post})


def js_test(request):
    return render(request, 'blog/js_test.html', {})


class IntruderImage(viewsets.ModelViewSet):
    # 클래스 이름은 수업 예제(침입자 이미지) 그대로 사용
    queryset = Post.objects.order_by(F('published_date').desc(nulls_last=True), '-id')
    serializer_class = PostSerializer
    permission_classes = [permissions.IsAuthenticatedOrReadOnly, IsAuthorOrReadOnly]

    def perform_create(self, serializer):
        # published_date 없이 올리면 웹 메인 목록에 안 떠서 업로드 시각으로 채움
        published_date = serializer.validated_data.get('published_date') or timezone.now()
        serializer.save(author=self.request.user, published_date=published_date)
