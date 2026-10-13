package com.example.photoviewer;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView로 게시물 목록 보여주기 (수업 자료 ImageAdapter)
 * 원래는 List<Bitmap>을 받았는데 제목/날짜/내용도 보여주려고 List<Post>로 변경, 검색 필터 추가
 */
public class ImageAdapter extends RecyclerView.Adapter<ImageAdapter.ImageViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(Post post);
    }

    private final List<Post> allPosts = new ArrayList<>();
    private final List<Post> shownPosts = new ArrayList<>();
    private final OnItemClickListener listener;
    private String query = "";

    public ImageAdapter(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setPosts(List<Post> posts) {
        allPosts.clear();
        allPosts.addAll(posts);
        applyFilter();
    }

    public void filter(String query) {
        this.query = query == null ? "" : query.trim();
        applyFilter();
    }

    public String getQuery() {
        return query;
    }

    public int getTotalCount() {
        return allPosts.size();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void applyFilter() {
        shownPosts.clear();
        for (Post post : allPosts) {
            if (post.matches(query)) shownPosts.add(post);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // 게시물 항목을 나타낼 뷰 생성
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_image, parent, false);
        return new ImageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
        Post post = shownPosts.get(position);
        if (post.image != null) {
            holder.imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            holder.imageView.setImageBitmap(post.image);
        } else {
            // 이미지를 못 받은 경우 기본 아이콘
            holder.imageView.setScaleType(ImageView.ScaleType.CENTER);
            holder.imageView.setImageResource(R.drawable.ic_image_placeholder);
        }
        holder.textTitle.setText(post.title);
        holder.textDate.setText(post.displayDate());
        holder.textBody.setText(post.text);
        holder.itemView.setOnClickListener(v -> listener.onItemClick(post));
    }

    @Override
    public int getItemCount() {
        return shownPosts.size();
    }

    public static class ImageViewHolder extends RecyclerView.ViewHolder {
        final ImageView imageView;
        final TextView textTitle;
        final TextView textDate;
        final TextView textBody;

        public ImageViewHolder(View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.imageViewItem); // item_image.xml에 있는 ImageView
            textTitle = itemView.findViewById(R.id.textTitle);
            textDate = itemView.findViewById(R.id.textDate);
            textBody = itemView.findViewById(R.id.textBody);
        }
    }
}
