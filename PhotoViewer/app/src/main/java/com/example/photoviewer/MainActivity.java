package com.example.photoviewer;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // 에뮬레이터에서 내 컴퓨터(localhost)에 접속할 때는 10.0.2.2 사용
    static final String LOCAL_SERVER = "http://10.0.2.2:8000";
    static final String CLOUD_SERVER = "https://woojin123123.pythonanywhere.com";

    private static final String PREFS_NAME = "photoviewer";
    private static final String KEY_SERVER = "server_url";

    String site_url = LOCAL_SERVER;
    TextView textView;
    MaterialToolbar toolbar;
    SwipeRefreshLayout swipeRefresh;
    ImageAdapter adapter;
    SharedPreferences prefs;

    CloadImage taskDownload;
    PutPost taskUpload;
    private final List<BackgroundTask<?>> runningTasks = new ArrayList<>();

    // 업로드 다이얼로그 상태
    private AlertDialog uploadDialog;
    private ImageView uploadPreview;
    private TextView uploadInfo;
    private ProgressBar uploadProgress;
    private byte[] uploadJpeg;

    // 갤러리(Photo Picker)에서 사진 고르기 - 저장공간 권한 없이 사용 가능
    private final ActivityResultLauncher<PickVisualMediaRequest> pickImage =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::onImagePicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // 상태바/내비게이션바에 화면이 가려지지 않도록 여백 적용 (상단은 툴바 안쪽으로)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, 0, bars.right, bars.bottom);
            toolbar.setPadding(0, bars.top, 0, 0);
            return insets;
        });

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        site_url = prefs.getString(KEY_SERVER, LOCAL_SERVER);

        textView = findViewById(R.id.textView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setColorSchemeResources(R.color.brand);
        swipeRefresh.setOnRefreshListener(() -> onClickDownload(null));

        adapter = new ImageAdapter(this::showDetail);
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        updateSubtitle();
    }

    @Override
    protected void onDestroy() {
        // 화면이 없어진 뒤에 결과가 와서 뷰를 건드리지 않도록 작업 취소
        if (taskDownload != null) taskDownload.cancel();
        if (taskUpload != null) taskUpload.cancel();
        for (BackgroundTask<?> task : runningTasks) task.cancel();
        runningTasks.clear();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 동기화 (수업 자료 onClickDownload + CloadImage)
    // ------------------------------------------------------------------
    public void onClickDownload(View v) {
        if (taskDownload != null && taskDownload.isRunning()) {
            taskDownload.cancel();
        }
        swipeRefresh.setRefreshing(true);
        textView.setText(R.string.status_loading);

        int imageWidth = getResources().getDisplayMetrics().widthPixels;
        taskDownload = new CloadImage(site_url, getToken(), imageWidth);
        taskDownload.execute();
    }

    /** 서버에서 게시물 목록(JSON Array)을 받고, 각 게시물의 이미지를 내려받는 작업 */
    private class CloadImage extends BackgroundTask<List<Post>> {
        private final String baseUrl;
        private final String token;
        private final int imageWidth;
        private boolean tokenExpired = false;

        CloadImage(String baseUrl, String token, int imageWidth) {
            this.baseUrl = baseUrl;
            this.token = token;
            this.imageWidth = imageWidth;
        }

        @Override
        protected List<Post> doInBackground() throws Exception {
            PhotoBlogApi api = new PhotoBlogApi(baseUrl, token);
            List<Post> posts;
            try {
                posts = api.fetchPosts();
            } catch (PhotoBlogApi.ApiException e) {
                // 저장된 토큰이 서버에서 사라진 경우(DB 초기화 등) 401 -> 토큰 없이 다시 조회
                if (e.code != 401 || token == null) throw e;
                tokenExpired = true;
                api = new PhotoBlogApi(baseUrl, null);
                posts = api.fetchPosts();
            }
            // 배열 내 모든 이미지 다운로드 (하나가 실패해도 나머지는 보여줌)
            for (Post post : posts) {
                if (isCancelled()) break;
                try {
                    post.image = api.downloadImage(post.imageUrl, imageWidth);
                } catch (IOException e) {
                    post.image = null;
                }
            }
            return posts;
        }

        @Override
        protected void onPostExecute(List<Post> posts) {
            swipeRefresh.setRefreshing(false);
            if (tokenExpired) {
                clearLogin();
                toast(getString(R.string.msg_token_expired));
            }
            adapter.setPosts(posts);
            if (posts.isEmpty()) {
                textView.setText(R.string.status_empty);
            } else {
                showLoadedStatus();
            }
        }

        @Override
        protected void onError(Exception e) {
            swipeRefresh.setRefreshing(false);
            textView.setText(errorMessage(e));
        }
    }

    // ------------------------------------------------------------------
    // 새로운 이미지 게시 (수업 자료 19page onClickUpload + PutPost)
    // ------------------------------------------------------------------
    public void onClickUpload(View v) {
        if (getToken() == null) {
            // 글쓰기는 토큰이 필요해서 로그인부터 하고 업로드 화면으로
            showLoginDialog(this::showUploadDialog);
        } else {
            showUploadDialog();
        }
    }

    private void showUploadDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_upload, null);
        TextInputLayout layoutTitle = view.findViewById(R.id.layoutTitle);
        TextInputLayout layoutText = view.findViewById(R.id.layoutText);
        EditText editTitle = view.findViewById(R.id.editTitle);
        EditText editText = view.findViewById(R.id.editText);
        uploadPreview = view.findViewById(R.id.imagePreview);
        uploadInfo = view.findViewById(R.id.textImageInfo);
        uploadProgress = view.findViewById(R.id.uploadProgress);
        uploadJpeg = null;

        View.OnClickListener pick = b -> pickImage.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
        view.findViewById(R.id.btnPickImage).setOnClickListener(pick);
        uploadPreview.setOnClickListener(pick);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.upload_title)
                .setView(view)
                .setPositiveButton(R.string.upload_submit, null)   // 입력 확인 후 직접 닫기 위해 null
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            submit.setOnClickListener(b -> {
                String title = editTitle.getText().toString().trim();
                String text = editText.getText().toString().trim();
                layoutTitle.setError(title.isEmpty() ? getString(R.string.err_title_required) : null);
                layoutText.setError(text.isEmpty() ? getString(R.string.err_text_required) : null);
                if (title.isEmpty() || text.isEmpty()) return;
                if (uploadJpeg == null) {
                    toast(getString(R.string.err_image_required));
                    return;
                }
                setUploading(true);
                taskUpload = new PutPost(site_url, getToken(), title, text, uploadJpeg);
                taskUpload.execute();
            });
        });
        dialog.setOnDismissListener(d -> {
            if (taskUpload != null && taskUpload.isRunning()) taskUpload.cancel();
            uploadDialog = null;
            uploadPreview = null;
            uploadInfo = null;
            uploadProgress = null;
            uploadJpeg = null;
        });
        uploadDialog = dialog;
        dialog.show();
    }

    private void setUploading(boolean uploading) {
        if (uploadDialog == null) return;
        Button submit = uploadDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        submit.setEnabled(!uploading);
        submit.setText(uploading ? R.string.upload_uploading : R.string.upload_submit);
        if (uploadProgress != null) uploadProgress.setVisibility(uploading ? View.VISIBLE : View.GONE);
    }

    /** 고른 사진을 업로드용으로 줄이고 미리보기에 표시 */
    private void onImagePicked(Uri uri) {
        if (uri == null || uploadDialog == null) return;   // 선택 취소
        start(new BackgroundTask<ImageUtils.UploadImage>() {
            @Override
            protected ImageUtils.UploadImage doInBackground() throws Exception {
                return ImageUtils.prepareUpload(getContentResolver(), uri, ImageUtils.UPLOAD_MAX_SIZE);
            }

            @Override
            protected void onPostExecute(ImageUtils.UploadImage image) {
                if (uploadDialog == null) return;
                uploadJpeg = image.jpeg;
                uploadPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                uploadPreview.setImageBitmap(image.preview);
                uploadInfo.setText(getString(R.string.image_info, image.preview.getWidth(),
                        image.preview.getHeight(), ImageUtils.formatSize(image.jpeg.length)));
            }

            @Override
            protected void onError(Exception e) {
                toast(getString(R.string.err_image_read));
            }
        });
    }

    /** 새 게시물 업로드 (multipart : title, text, image) */
    private class PutPost extends BackgroundTask<Post> {
        private final String baseUrl;
        private final String token;
        private final String title;
        private final String text;
        private final byte[] jpeg;

        PutPost(String baseUrl, String token, String title, String text, byte[] jpeg) {
            this.baseUrl = baseUrl;
            this.token = token;
            this.title = title;
            this.text = text;
            this.jpeg = jpeg;
        }

        @Override
        protected Post doInBackground() throws Exception {
            return new PhotoBlogApi(baseUrl, token).uploadPost(title, text, jpeg, ImageUtils.makeFileName());
        }

        @Override
        protected void onPostExecute(Post post) {
            if (uploadDialog != null) uploadDialog.dismiss();
            toast(getString(R.string.msg_uploaded));
            onClickDownload(null);   // 올린 글이 바로 보이도록 다시 동기화
        }

        @Override
        protected void onError(Exception e) {
            setUploading(false);
            if (e instanceof PhotoBlogApi.ApiException && ((PhotoBlogApi.ApiException) e).code == 401) {
                clearLogin();
            }
            toast(errorMessage(e));
        }
    }

    // ------------------------------------------------------------------
    // 게시물 상세 보기 / 삭제
    // ------------------------------------------------------------------
    private void showDetail(Post post) {
        View view = getLayoutInflater().inflate(R.layout.dialog_detail, null);
        ImageView image = view.findViewById(R.id.detailImage);
        if (post.image != null) {
            image.setImageBitmap(post.image);
        } else {
            image.setImageResource(R.drawable.ic_image_placeholder);
        }
        ((TextView) view.findViewById(R.id.detailTitle)).setText(post.title);
        ((TextView) view.findViewById(R.id.detailDate)).setText(post.displayDate());
        ((TextView) view.findViewById(R.id.detailText)).setText(post.text);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton(R.string.close, null);
        if (getToken() != null && post.id > 0) {
            builder.setNegativeButton(R.string.delete, (d, which) -> confirmDelete(post));
        }
        builder.show();
    }

    private void confirmDelete(Post post) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, post.title))
                .setPositiveButton(R.string.delete, (d, which) -> deletePost(post))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deletePost(Post post) {
        String server = site_url;
        String token = getToken();
        start(new BackgroundTask<Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                new PhotoBlogApi(server, token).deletePost(post.id);
                return null;
            }

            @Override
            protected void onPostExecute(Void unused) {
                toast(getString(R.string.msg_deleted));
                onClickDownload(null);
            }

            @Override
            protected void onError(Exception e) {
                toast(errorMessage(e));
            }
        });
    }

    // ------------------------------------------------------------------
    // 로그인 (토큰 발급) - 서버마다 토큰이 다르므로 서버 주소별로 저장
    // ------------------------------------------------------------------
    private void showLoginDialog(Runnable onSuccess) {
        View view = getLayoutInflater().inflate(R.layout.dialog_login, null);
        EditText editUsername = view.findViewById(R.id.editUsername);
        EditText editPassword = view.findViewById(R.id.editPassword);
        ProgressBar progress = view.findViewById(R.id.loginProgress);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.login_title)
                .setMessage(getString(R.string.login_message, hostOf(site_url)))
                .setView(view)
                .setPositiveButton(R.string.login, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            submit.setOnClickListener(b -> {
                String username = editUsername.getText().toString().trim();
                String password = editPassword.getText().toString();
                if (username.isEmpty() || password.isEmpty()) {
                    toast(getString(R.string.err_login_empty));
                    return;
                }
                String server = site_url;
                submit.setEnabled(false);
                progress.setVisibility(View.VISIBLE);
                start(new BackgroundTask<String>() {
                    @Override
                    protected String doInBackground() throws Exception {
                        return new PhotoBlogApi(server, null).login(username, password);
                    }

                    @Override
                    protected void onPostExecute(String token) {
                        saveLogin(server, username, token);
                        dialog.dismiss();
                        toast(getString(R.string.msg_login, username));
                        if (onSuccess != null) onSuccess.run();
                    }

                    @Override
                    protected void onError(Exception e) {
                        submit.setEnabled(true);
                        progress.setVisibility(View.GONE);
                        boolean wrongPassword = e instanceof PhotoBlogApi.ApiException
                                && ((PhotoBlogApi.ApiException) e).code == 400;
                        toast(wrongPassword ? getString(R.string.err_login_failed) : errorMessage(e));
                    }
                });
            });
        });
        dialog.show();
    }

    private String getToken() {
        return prefs.getString("token@" + site_url, null);
    }

    private String getUsername() {
        return prefs.getString("user@" + site_url, null);
    }

    private void saveLogin(String server, String username, String token) {
        prefs.edit()
                .putString("token@" + server, token)
                .putString("user@" + server, username)
                .apply();
        updateSubtitle();
        invalidateOptionsMenu();
    }

    private void clearLogin() {
        prefs.edit()
                .remove("token@" + site_url)
                .remove("user@" + site_url)
                .apply();
        updateSubtitle();
        invalidateOptionsMenu();
    }

    // ------------------------------------------------------------------
    // 서버 주소 변경 (로컬 <-> PythonAnywhere)
    // ------------------------------------------------------------------
    private void showServerDialog() {
        String[] items = {
                getString(R.string.server_local, hostOf(LOCAL_SERVER)),
                getString(R.string.server_cloud, hostOf(CLOUD_SERVER)),
                getString(R.string.server_custom),
        };
        int checked = LOCAL_SERVER.equals(site_url) ? 0 : CLOUD_SERVER.equals(site_url) ? 1 : 2;
        new AlertDialog.Builder(this)
                .setTitle(R.string.server_title)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        changeServer(LOCAL_SERVER);
                    } else if (which == 1) {
                        changeServer(CLOUD_SERVER);
                    } else {
                        showCustomServerDialog();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showCustomServerDialog() {
        EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        edit.setSingleLine(true);
        edit.setText(site_url);

        FrameLayout container = new FrameLayout(this);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(edit);

        new AlertDialog.Builder(this)
                .setTitle(R.string.server_custom)
                .setView(container)
                .setPositiveButton(R.string.ok, (d, which) -> {
                    String url = edit.getText().toString().trim();
                    while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        toast(getString(R.string.err_server_url));
                        return;
                    }
                    changeServer(url);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void changeServer(String url) {
        if (taskDownload != null) taskDownload.cancel();
        swipeRefresh.setRefreshing(false);
        site_url = url;
        prefs.edit().putString(KEY_SERVER, url).apply();
        adapter.setPosts(new ArrayList<>());   // 다른 서버의 글이 섞이지 않도록 목록 비움
        textView.setText(getString(R.string.status_server_changed, hostOf(url)));
        updateSubtitle();
        invalidateOptionsMenu();
    }

    // ------------------------------------------------------------------
    // 메뉴 (검색 / 서버 주소 / 로그인 / 로그아웃)
    // ------------------------------------------------------------------
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        SearchView searchView = (SearchView) menu.findItem(R.id.action_search).getActionView();
        if (searchView != null) {
            searchView.setQueryHint(getString(R.string.search_hint));
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    searchView.clearFocus();
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    applySearch(newText);
                    return true;
                }
            });
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean loggedIn = getToken() != null;
        menu.findItem(R.id.action_login).setVisible(!loggedIn);
        menu.findItem(R.id.action_logout).setVisible(loggedIn);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_server) {
            showServerDialog();
            return true;
        } else if (id == R.id.action_login) {
            showLoginDialog(null);
            return true;
        } else if (id == R.id.action_logout) {
            clearLogin();
            toast(getString(R.string.msg_logout));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void applySearch(String query) {
        adapter.filter(query);
        if (adapter.getTotalCount() > 0) showLoadedStatus();
    }

    private void showLoadedStatus() {
        if (TextUtils.isEmpty(adapter.getQuery())) {
            textView.setText(getString(R.string.status_loaded, adapter.getTotalCount()));
        } else if (adapter.getItemCount() == 0) {
            textView.setText(R.string.status_search_empty);
        } else {
            textView.setText(getString(R.string.status_search, adapter.getItemCount(), adapter.getTotalCount()));
        }
    }

    // ------------------------------------------------------------------
    // 공통
    // ------------------------------------------------------------------
    private void updateSubtitle() {
        String user = getUsername();
        toolbar.setSubtitle(hostOf(site_url) + " · " + (user != null ? user : getString(R.string.not_logged_in)));
    }

    /** 액티비티가 닫힐 때 같이 취소되도록 목록에 넣고 실행 */
    private void start(BackgroundTask<?> task) {
        Iterator<BackgroundTask<?>> it = runningTasks.iterator();
        while (it.hasNext()) {
            if (!it.next().isRunning()) it.remove();
        }
        runningTasks.add(task);
        task.execute();
    }

    private String errorMessage(Exception e) {
        if (e instanceof PhotoBlogApi.ApiException) {
            PhotoBlogApi.ApiException api = (PhotoBlogApi.ApiException) e;
            if (api.code == 401) return getString(R.string.err_401);
            if (api.code == 403) return getString(R.string.err_403);
            if (api.code == 400) return getString(R.string.err_400, api.getMessage());
            return getString(R.string.err_http, api.code);
        }
        if (e instanceof JSONException) return getString(R.string.err_json);
        if (e instanceof IOException) return getString(R.string.err_network, hostOf(site_url));
        return getString(R.string.err_unknown, String.valueOf(e.getMessage()));
    }

    static String hostOf(String url) {
        Uri uri = Uri.parse(url);
        return uri.getAuthority() != null ? uri.getAuthority() : url;
    }

    private void toast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }
}
