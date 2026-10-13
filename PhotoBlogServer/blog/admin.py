from django.contrib import admin
from .models import Post


@admin.register(Post)
class PostAdmin(admin.ModelAdmin):
    list_display = ('id', 'title', 'author', 'created_date', 'published_date')
    list_display_links = ('id', 'title')
    search_fields = ('title', 'text')
